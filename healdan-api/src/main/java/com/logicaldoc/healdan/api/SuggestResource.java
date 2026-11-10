// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Hits;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.SuggestView;
import com.logicaldoc.healdan.api.model.SuggestView.SuggestedClient;
import com.logicaldoc.healdan.api.model.SuggestView.SuggestedDocument;
import com.logicaldoc.healdan.api.model.SuggestView.SuggestedDocumentMatter;
import com.logicaldoc.healdan.api.model.SuggestView.SuggestedMatter;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.search.MatterSearch;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Suggestions as the person types in the search box (M6 spec sections 6.3 and
 * 9.1): matters first, then a few documents by title. Everything is checked
 * as {@link SearchResource} checks its hits, so nothing the caller can't read
 * appears. It is its own root resource for the reason {@code SearchResource}
 * is.
 */
@Component
@Path("/v1/suggest")
@Produces(MediaType.APPLICATION_JSON)
public class SuggestResource {

	/** The most matters, and the most documents, suggested */
	static final int MAX_EACH = 5;

	/** Shorter text suggests nothing */
	static final int MIN_LENGTH = 2;

	/**
	 * The most hits read from the index for one request. In a real firm a short
	 * prefix can match hundreds of titles in matters the caller can't read, so
	 * the hits are read in the engine's score order, with a boost for documents
	 * dated in the last year, and each is checked for the caller until
	 * {@link #MAX_EACH} pass. This is the engine's own cap (CE's search takes
	 * its rows up to 1,000): a caller who can read only titles ranked after
	 * 1,000 others that they can't gets fewer suggestions, which a longer word
	 * fixes.
	 */
	static final int MAX_HITS = 1000;

	private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

	/**
	 * @param q what has been typed
	 * @return up to five matters whose number or name matches, then up to five
	 *         documents whose title has all its words, the last as a prefix;
	 *         both empty for fewer than two characters
	 */
	@GET
	public SuggestView suggest(@QueryParam("q") String q) {
		String text = StringUtils.strip(q);
		if (text == null || text.length() < MIN_LENGTH)
			return new SuggestView(List.of(), List.of());
		User user = Caller.current().user();
		try {
			return new SuggestView(matters(text, user), documents(text, user));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't suggest", e);
		}
	}

	private static List<SuggestedMatter> matters(String text, User user) throws PersistenceException {
		List<SuggestedMatter> matters = new ArrayList<>();
		for (Hit hit : MatterSearch.find(user, text, MAX_EACH)) {
			if (matters.size() == MAX_EACH)
				break;
			Matter matter = MatterDAO.get().findByFolderId(hit.getId());
			// The lookup reads permissions as the folder does; the check is the same one search makes
			if (matter == null || AccessChecks.readableFolder(hit.getId(), user).isEmpty())
				continue;
			Client client = ClientDAO.get().findById(matter.getClientId());
			if (client != null)
				matters.add(new SuggestedMatter(matter.getId(), matter.getNumber(), matter.getName(),
						new SuggestedClient(client.getNumber(), client.getName())));
		}
		return matters;
	}

	private static List<SuggestedDocument> documents(String text, User user) throws PersistenceException {
		String expression = expression(text);
		List<SuggestedDocument> documents = new ArrayList<>();
		if (expression == null)
			return documents;
		// Score order, not id order: the oldest documents must not crowd out the rest
		Hits hits = SearchEngine.get().search(expression, null, StringUtils.defaultIfBlank(user.getLanguage(), "en"),
				MAX_HITS);
		while (hits != null && hits.hasNext() && documents.size() < MAX_EACH)
			suggested(hits.next().getId(), user).ifPresent(documents::add);
		return documents;
	}

	/**
	 * @param text what was typed
	 * @return a query on the title field that needs every word, the last as a
	 *         prefix; null if the text has no word. Only letters and digits
	 *         reach the query, escaped all the same, as the engine's analyser
	 *         splits the titles at everything else
	 */
	static String expression(String text) {
		List<String> words = new ArrayList<>();
		for (String word : NOT_WORD.split(text))
			if (!word.isEmpty())
				words.add(word.toLowerCase(Locale.ROOT));
		if (words.isEmpty())
			return null;
		List<String> clauses = new ArrayList<>();
		for (int i = 0; i < words.size(); i++) {
			String word = ClientUtils.escapeQueryChars(words.get(i));
			clauses.add("title:" + word + (i == words.size() - 1 ? "*" : ""));
		}
		// The recent-date clause only lifts the score: the words are the required part
		return "+(" + String.join(" AND ", clauses) + ") date:[NOW-1YEAR TO *]^2";
	}

	private static Optional<SuggestedDocument> suggested(long docId, User user) throws PersistenceException {
		Optional<Document> readable = AccessChecks.readableDocument(DocumentDAO.get().findById(docId), user);
		if (readable.isEmpty() || !ApiViews.shows(readable.get()))
			return Optional.empty();
		Document document = readable.get();
		Matter matter = MatterLocator.get().locate(document.getFolder().getId()).matter();
		Client client = matter == null ? null : ClientDAO.get().findById(matter.getClientId());
		if (matter == null || client == null)
			return Optional.empty();
		return Optional.of(new SuggestedDocument(Long.parseLong(document.getCustomId()), document.getFileName(),
				new SuggestedDocumentMatter(matter.getId(), matter.getNumber(),
						MatterDisplay.format(client.getNumber(), matter.getNumber(), matter.getName()))));
	}
}
