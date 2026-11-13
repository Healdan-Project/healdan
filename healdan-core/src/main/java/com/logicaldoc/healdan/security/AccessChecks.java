// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.util.spring.Context;

/**
 * What a user may see and do, for code that answers a user rather than a
 * listener's veto. {@code HealdanTreeChecks} in logicaldoc-core throws
 * {@code PermissionException}; these return answers, so the API can report an
 * unreadable object as one that doesn't exist (M2 spec section 7).
 */
public final class AccessChecks {

	/** The group whose members CE's searches show unpublished documents to */
	private static final String PUBLISHER_GROUP = "publisher";

	/** Ids per query, to stay under the databases' limits on a list */
	private static final int CHUNK = 500;

	/**
	 * Every permission column of {@code ld_document_acl}, as CE's
	 * {@code getAllowedPermissions} reads them, each {@code ld_} and its
	 * {@code Permission}'s name in lower case
	 */
	public static final List<String> PERMISSION_COLUMNS = List.of("ld_read", "ld_write", "ld_security", "ld_immutable",
			"ld_delete", "ld_rename", "ld_sign", "ld_archive", "ld_workflow", "ld_download", "ld_calendar",
			"ld_subscription", "ld_print", "ld_password", "ld_move", "ld_email", "ld_automation", "ld_readingreq",
			"ld_preview", "ld_customid", "ld_revision");

	private AccessChecks() {
	}

	/**
	 * @param user the user, with its groups
	 * @return true if the user is an administrator, as Healdan's rules decide
	 * @throws PersistenceException error at data layer
	 */
	public static boolean isAdmin(User user) throws PersistenceException {
		return FilingRules.get().isAdmin(user);
	}

	/**
	 * @param docNumber the doc # (the document's custom id), looked up in the
	 *        user's own tenant
	 * @param user the user, with its groups
	 * @return the document, if it exists, isn't deleted and the user may read
	 *         it; otherwise empty, whatever the reason
	 * @throws PersistenceException error at data layer
	 */
	public static Optional<Document> readableDocument(long docNumber, User user) throws PersistenceException {
		// The DAO skips deleted documents
		return readableDocument(DocumentDAO.get().findByCustomId(Long.toString(docNumber), user.getTenantId()), user);
	}

	/**
	 * @param document a document, or null
	 * @param user the user, with its groups
	 * @return the document, if it isn't deleted, the user may read it (through
	 *         the document's own grants, or its folder's) and it is published
	 *         now, unless the user is in the {@code admin} or {@code publisher}
	 *         group; otherwise empty
	 * @throws PersistenceException error at data layer
	 */
	public static Optional<Document> readableDocument(Document document, User user) throws PersistenceException {
		if (document == null || document.getDeleted() != 0)
			return Optional.empty();
		if (!DocumentDAO.get().isReadAllowed(document.getId(), user.getId()))
			return Optional.empty();
		return visible(document, user) ? Optional.of(document) : Optional.empty();
	}

	/**
	 * The one publishing rule, CE's own ({@code Document.isPublishing}): the
	 * {@code #} lookup and the API both use it.
	 *
	 * @param document a document
	 * @param user the user, with its groups
	 * @return true if the document is published now, or the user is in the
	 *         {@code admin} or {@code publisher} group
	 */
	public static boolean visible(Document document, User user) {
		return document.isPublishing() || seesUnpublished(user);
	}

	/**
	 * @param user the user, with its groups
	 * @return true if the user is in the {@code admin} or {@code publisher}
	 *         group, whom CE shows documents that aren't published now
	 */
	public static boolean seesUnpublished(User user) {
		return user.isMemberOf(Group.GROUP_ADMIN) || user.isMemberOf(PUBLISHER_GROUP);
	}

	/**
	 * For SQL that restates {@link #readableDocuments}' rule, as
	 * {@code FolderListing} does.
	 *
	 * @param alias the alias of {@code ld_document_acl} with its dot, such as
	 *        {@code a.}, or empty for none
	 * @return the sum of a row's permission columns: more than 0 when the row
	 *         grants anything
	 */
	public static String anyPermission(String alias) {
		return PERMISSION_COLUMNS.stream().map(column -> alias + column).collect(Collectors.joining(" + "));
	}

	/**
	 * @param user the user
	 * @param permission the permission
	 * @param document the document
	 * @return true if the user has the permission on the document: its own
	 *         grants if it has any, else its folder's
	 * @throws PersistenceException error at data layer
	 */
	public static boolean allowed(User user, Permission permission, Document document) throws PersistenceException {
		return DocumentDAO.get().isPermissionAllowed(permission, document.getId(), user.getId());
	}

	/**
	 * @param folderId the folder's id
	 * @param user the user, with its groups
	 * @return the folder, if it exists and the user may read it; otherwise empty
	 * @throws PersistenceException error at data layer
	 */
	public static Optional<Folder> readableFolder(long folderId, User user) throws PersistenceException {
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null || folder.getDeleted() != 0 || !FolderDAO.get().isReadAllowed(folderId, user.getId()))
			return Optional.empty();
		return Optional.of(folder);
	}

	/**
	 * @param user the user
	 * @param permission the permission
	 * @param folder the folder
	 * @return true if the user has the permission on the folder
	 * @throws PersistenceException error at data layer
	 */
	public static boolean allowed(User user, Permission permission, Folder folder) throws PersistenceException {
		return FolderDAO.get().isPermissionAllowed(permission, folder.getId(), user.getId());
	}

	/**
	 * Which of a folder's documents the user can read, for a listing: the
	 * answer of {@link #readableDocument(Document, User)} for each, with one
	 * query on the documents' own ACLs instead of several per document.
	 * <p>
	 * As CE decides ({@code getAllowedPermissions}): an administrator reads all;
	 * a document whose own ACL rows for the user's groups grant anything is
	 * read if they grant READ; any other document takes its folder's answer.
	 * {@code FolderListing} restates this rule in SQL, to page a folder in the
	 * database: a change here changes it there.
	 *
	 * @param folder the folder that holds all the documents
	 * @param documents documents of the folder
	 * @param user the user, with its groups
	 * @return the readable and published ones, in the order given
	 * @throws PersistenceException error at data layer
	 */
	public static List<Document> readableDocuments(Folder folder, List<Document> documents, User user)
			throws PersistenceException {
		boolean admin = user.isAdmin();
		boolean folderRead = admin || FolderDAO.get().isReadAllowed(folder.getId(), user.getId());
		Map<Long, Boolean> own = admin || documents.isEmpty() ? Map.of()
				: ownAclDecisions(documents.stream().map(Document::getId).toList(), user.getId());

		List<Document> readable = new ArrayList<>();
		for (Document document : documents) {
			if (document.getDeleted() != 0)
				continue;
			if (!own.getOrDefault(document.getId(), folderRead))
				continue;
			if (visible(document, user))
				readable.add(document);
		}
		return readable;
	}

	/**
	 * As {@link #readableDocuments(Folder, List, User)}, for documents in any
	 * folders, such as a search's hits: one query on their own ACLs, and each
	 * folder's READ asked once ({@link FolderReads})
	 *
	 * @param documents the documents
	 * @param reads who reads, and what is known of the folders they may read
	 * @return the readable and published ones, in the order given
	 * @throws PersistenceException error at data layer
	 */
	public static List<Document> readableDocuments(List<Document> documents, FolderReads reads)
			throws PersistenceException {
		User user = reads.user();
		boolean admin = user.isAdmin();
		Map<Long, Boolean> own = admin || documents.isEmpty() ? Map.of()
				: ownAclDecisions(documents.stream().map(Document::getId).toList(), user.getId());

		List<Document> readable = new ArrayList<>();
		for (Document document : documents) {
			if (document.getDeleted() != 0)
				continue;
			Boolean decided = own.get(document.getId());
			if (!(decided != null ? decided : reads.readable(document.getFolder().getId())))
				continue;
			if (visible(document, user))
				readable.add(document);
		}
		return readable;
	}

	/**
	 * @return for each document with own ACL rows that grant the user's groups
	 *         anything, whether they grant READ
	 */
	private static Map<Long, Boolean> ownAclDecisions(List<Long> docIds, long userId) {
		NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(Context.get(DataSource.class));
		Map<Long, Boolean> decisions = new HashMap<>();
		Set<Long> seen = new HashSet<>();
		for (int from = 0; from < docIds.size(); from += CHUNK) {
			List<Long> chunk = docIds.subList(from, Math.min(from + CHUNK, docIds.size()));
			jdbc.query("""
					select ld_docid, max(ld_read), max(%s)
					  from ld_document_acl
					 where ld_docid in (:ids)
					   and ld_groupid in (select ld_groupid from ld_usergroup where ld_userid = :user)
					 group by ld_docid
					""".formatted("(" + anyPermission("") + ")"),
					Map.of("ids", chunk, "user", userId), rs -> {
						// Rows that grant nothing at all send the document to its folder
						if (rs.getInt(3) > 0 && seen.add(rs.getLong(1)))
							decisions.put(rs.getLong(1), rs.getInt(2) > 0);
					});
		}
		return decisions;
	}
}
