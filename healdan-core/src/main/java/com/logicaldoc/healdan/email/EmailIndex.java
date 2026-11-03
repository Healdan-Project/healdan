// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Which documents are copies of which message (M5 spec §6.2, §12), kept in
 * {@code hd_email}: a row per email, with its Message-ID and the hash the
 * lookups go by. It is the source of truth for the once-per-matter rule and
 * the lookups, not the Message-ID attribute, which CE's own screens can edit.
 * It holds no matter: a message has few copies, so whoever asks finds each
 * copy's matter afresh.
 * <p>
 * It works through a {@code JdbcTemplate} on the shared {@code DataSource}, as
 * {@link com.logicaldoc.healdan.preview.PreviewJobs} does, so a row joins the
 * transaction of the document that adds it when one is open. A row for a
 * document that is gone for good stays, and every lookup joins
 * {@code ld_document} and skips it. A deleted document (in the recycle bin)
 * keeps its row too, so it can be restored with its Message-ID, and no lookup
 * answers it. Every value is bound; a Message-ID is compared exactly, the hash
 * only narrows the search.
 */
@Component
public class EmailIndex {

	/** The most hashes one query asks for */
	private static final int CHUNK = 100;

	private static final String COPIES = "select e.hd_docid from hd_email e join ld_document d on d.ld_id = e.hd_docid "
			+ "where e.hd_messageidhash = ? and e.hd_messageid = ? and d.ld_deleted = 0 order by e.hd_docid";

	private final JdbcTemplate jdbc;

	@Autowired
	public EmailIndex(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static EmailIndex get() {
		return Context.get(EmailIndex.class);
	}

	/**
	 * Adds or replaces a document's row. A Message-ID that is null, empty or
	 * longer than {@link MessageIds#MAX_LENGTH} removes it: callers pass what
	 * {@link MessageIds#normalize} made of the header, which is none for those.
	 *
	 * @param docId the document
	 * @param messageId its normalised Message-ID
	 */
	public void put(long docId, String messageId) {
		if (!isKept(messageId)) {
			jdbc.update("delete from hd_email where hd_docid = ?", docId);
			return;
		}

		String hash = MessageIds.hash(messageId);
		if (replace(docId, messageId, hash))
			return;
		try {
			jdbc.update("insert into hd_email (hd_docid, hd_messageid, hd_messageidhash) values (?, ?, ?)", docId,
					messageId, hash);
		} catch (DuplicateKeyException e) {
			// Another thread added the row since, or the update counted no change
			replace(docId, messageId, hash);
		}
	}

	private boolean replace(long docId, String id, String hash) {
		return jdbc.update("update hd_email set hd_messageid = ?, hd_messageidhash = ? where hd_docid = ?", id, hash,
				docId) > 0;
	}

	/**
	 * @param messageId a normalised Message-ID ({@link MessageIds#normalize})
	 * @return the ids of the documents not deleted that are copies of it, lowest
	 *         first; none when it isn't one
	 */
	public List<Long> copies(String messageId) {
		if (!isKept(messageId))
			return List.of();
		return jdbc.queryForList(COPIES, Long.class, MessageIds.hash(messageId), messageId);
	}

	/**
	 * @param messageIds Message-IDs as a client sent them, with or without angle
	 *        brackets; the ones that aren't Message-IDs are left out
	 * @return the same for each, keyed by the normalised Message-ID asked, in the
	 *         order asked; an empty list for one with no copy
	 */
	public Map<String, List<Long>> copies(Collection<String> messageIds) {
		Map<String, List<Long>> answer = new LinkedHashMap<>();
		for (String messageId : messageIds) {
			String id = MessageIds.normalize(messageId);
			if (id != null)
				answer.putIfAbsent(id, new ArrayList<>());
		}

		List<String> asked = new ArrayList<>(answer.keySet());
		for (int from = 0; from < asked.size(); from += CHUNK) {
			List<String> chunk = asked.subList(from, Math.min(from + CHUNK, asked.size()));
			Set<String> hashes = chunk.stream().map(MessageIds::hash)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			String placeholders = String.join(", ", Collections.nCopies(hashes.size(), "?"));
			jdbc.query("select e.hd_docid, e.hd_messageid from hd_email e join ld_document d on d.ld_id = e.hd_docid "
					+ "where e.hd_messageidhash in (" + placeholders + ") and d.ld_deleted = 0 order by e.hd_docid",
					rs -> {
						// The hash only narrows the search; the Message-ID itself decides
						List<Long> copies = answer.get(rs.getString(2));
						if (copies != null)
							copies.add(rs.getLong(1));
					}, hashes.toArray());
		}
		return answer;
	}

	private static boolean isKept(String messageId) {
		return messageId != null && !messageId.isEmpty() && messageId.length() <= MessageIds.MAX_LENGTH;
	}

	/**
	 * @param docId a document
	 * @return its Message-ID, as stored when it was filed; null when it has none.
	 *         Deleted documents are answered too.
	 */
	public String messageIdOf(long docId) {
		List<String> found = jdbc.queryForList("select hd_messageid from hd_email where hd_docid = ?", String.class,
				docId);
		return found.isEmpty() ? null : found.get(0);
	}
}
