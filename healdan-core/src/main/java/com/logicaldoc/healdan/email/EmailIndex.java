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
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
 * answers it. A shortcut ({@code ld_docref}) is never a copy, whatever row it
 * may carry. Every value is bound; a Message-ID is compared exactly, the hash
 * only narrows the search.
 */
@Component
public class EmailIndex {

	/** The most hashes one query asks for */
	private static final int CHUNK = 100;

	/** The most folders one query of {@link #emailsIn} asks about */
	private static final int FOLDER_CHUNK = 500;

	/** The documents a lookup answers: not deleted, and not shortcuts */
	private static final String LIVE = "d.ld_deleted = 0 and d.ld_docref is null";

	private static final String COPIES = "select e.hd_docid from hd_email e join ld_document d on d.ld_id = e.hd_docid "
			+ "where e.hd_messageidhash = ? and e.hd_messageid = ? and " + LIVE + " order by e.hd_docid";

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public EmailIndex(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
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
			/*
			 * Only a row that is there: on MariaDB a delete that matches nothing
			 * takes a gap lock, as an update does, and a check-in of an email with
			 * no Message-ID, which had no row, would take one. The read takes none
			 */
			if (messageIdOf(docId) != null)
				jdbc.update("delete from hd_email where hd_docid = ?", docId);
			return;
		}

		String hash = MessageIds.hash(messageId);
		/*
		 * Insert first: nearly every call adds a new document's row. On MariaDB an
		 * update that matches nothing takes a gap lock, which two wide transactions
		 * can deadlock on
		 */
		try {
			jdbc.update("insert into hd_email (hd_docid, hd_messageid, hd_messageidhash) values (?, ?, ?)", docId,
					messageId, hash);
		} catch (DuplicateKeyException e) {
			// The document has a row already: the index worker's, or an earlier one
			jdbc.update("update hd_email set hd_messageid = ?, hd_messageidhash = ? where hd_docid = ?", messageId,
					hash, docId);
		}
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
		List<String> normalized = new ArrayList<>();
		for (String messageId : messageIds) {
			String id = MessageIds.normalize(messageId);
			if (id != null)
				normalized.add(id);
		}
		return copiesOf(normalized);
	}

	/**
	 * {@link #copies(String)} for many at once, in chunked queries
	 *
	 * @param messageIds normalised Message-IDs ({@link MessageIds#normalize}),
	 *        which aren't normalised again
	 * @return the same for each, keyed by it, in the order given; an empty list
	 *         for one with no copy
	 */
	public Map<String, List<Long>> copiesOf(Collection<String> messageIds) {
		Map<String, List<Long>> answer = new LinkedHashMap<>();
		for (String id : messageIds)
			if (isKept(id))
				answer.putIfAbsent(id, new ArrayList<>());

		List<String> asked = new ArrayList<>(answer.keySet());
		for (int from = 0; from < asked.size(); from += CHUNK) {
			List<String> chunk = asked.subList(from, Math.min(from + CHUNK, asked.size()));
			Set<String> hashes = chunk.stream().map(MessageIds::hash)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			String placeholders = String.join(", ", Collections.nCopies(hashes.size(), "?"));
			jdbc.query("select e.hd_docid, e.hd_messageid from hd_email e join ld_document d on d.ld_id = e.hd_docid "
					+ "where e.hd_messageidhash in (" + placeholders + ") and " + LIVE + " order by e.hd_docid",
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

	/**
	 * The emails a folder operation brings along, for the once-per-matter rule
	 *
	 * @param folderIds folders, such as a tree's
	 * @param deleted false for the documents not deleted, true for those
	 *        deleted with code 1, which a folder restore brings back
	 * @return each such document that has a row and isn't a shortcut, with its
	 *         Message-ID, lowest id first
	 */
	public Map<Long, String> emailsIn(Collection<Long> folderIds, boolean deleted) {
		List<Long> ids = new ArrayList<>(folderIds);
		Map<Long, String> emails = new TreeMap<>();
		for (int from = 0; from < ids.size(); from += FOLDER_CHUNK)
			named.query("select e.hd_docid, e.hd_messageid from hd_email e join ld_document d on d.ld_id = e.hd_docid "
					+ "where d.ld_deleted = :deleted and d.ld_docref is null and d.ld_folderid in (:folders)",
					new MapSqlParameterSource("deleted", deleted ? 1 : 0).addValue("folders",
							ids.subList(from, Math.min(from + FOLDER_CHUNK, ids.size()))),
					rs -> {
						emails.put(rs.getLong(1), rs.getString(2));
					});
		return emails;
	}
}
