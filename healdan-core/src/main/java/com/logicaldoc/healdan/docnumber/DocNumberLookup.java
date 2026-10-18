package com.logicaldoc.healdan.docnumber;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;

/**
 * Finds a document, and optionally one of its versions, by the doc # a user
 * types (spec 6.5). It reads the database, never the full-text index, so a
 * document filed a second ago is found. A document the user can't read, or
 * that CE's searches would hide from the user because it isn't published, is
 * reported exactly as one that doesn't exist, and the doc # is never matched
 * against a legacy doc #.
 */
public final class DocNumberLookup {

	static final String NO_DOCUMENT = "No document #%s";

	static final String NO_VERSION = "Document #%s has no version %s";

	static final String NOT_A_NUMBER = "'%s' isn't a document number. Type # and the number, e.g. #1234.";

	/** The group whose members CE's searches show unpublished documents to, as in {@code Search} */
	private static final String PUBLISHER_GROUP = "publisher";

	private DocNumberLookup() {
	}

	/**
	 * @param input what the user typed, such as {@code #1234.2}
	 * @param userId who asks
	 * @return the document and version found, or a message saying why there
	 *         is none
	 * @throws PersistenceException error at data layer
	 */
	public static LookupResult lookup(String input, long userId) throws PersistenceException {
		if (!DocNumberQuery.looksLikeNumber(input))
			return LookupResult.notFound(NOT_A_NUMBER.formatted(StringUtils.defaultString(input)));
		Optional<DocNumberQuery> parsed = DocNumberQuery.parse(input);
		if (parsed.isEmpty())
			// Too big for a doc #: what follows the # is shown as typed
			return LookupResult.notFound(NO_DOCUMENT.formatted(input.strip().substring(1).strip()));
		DocNumberQuery query = parsed.get();

		DocumentDAO documents = DocumentDAO.get();
		// The DAO skips deleted documents
		Document document = documents.findByCustomId(Long.toString(query.number()), Tenant.DEFAULT_ID);
		if (document == null || !documents.isReadAllowed(document.getId(), userId) || !shown(document, userId))
			return LookupResult.notFound(NO_DOCUMENT.formatted(query.number()));
		long folderId = document.getFolder().getId();
		if (query.major() == null)
			return new LookupResult(document.getId(), folderId, null, document.getVersion(),
					document.getFileVersion(), null);

		Version version = pick(VersionDAO.get().findByDocId(document.getId()), query.major(), query.minor());
		if (version == null)
			return LookupResult.notFound(NO_VERSION.formatted(query.number(),
					query.minor() == null ? query.major() : query.major() + "." + query.minor()));
		return new LookupResult(document.getId(), folderId, version.getId(), version.getVersion(),
				version.getFileVersion(), null);
	}

	/**
	 * @return false if CE's searches would hide the document from the user: it
	 *         isn't published now (not published, or outside its publishing
	 *         dates, as in {@code Search.enrichAndPopulateHits}) and the user
	 *         is neither an administrator nor a publisher
	 */
	private static boolean shown(Document document, long userId) throws PersistenceException {
		User user = UserDAO.get().findById(userId, true);
		if (user == null)
			return false;
		if (user.isMemberOf(Group.GROUP_ADMIN) || user.isMemberOf(PUBLISHER_GROUP))
			return true;
		Date now = new Date();
		Date start = document.getStartPublishing();
		Date stop = document.getStopPublishing();
		return document.isPublished() && start != null && !start.after(now) && (stop == null || stop.after(now));
	}

	/**
	 * @return the version {@code major.minor}, or with no minor the one with
	 *         the highest minor among {@code major.*}, comparing numbers; null
	 *         if there is none
	 */
	private static Version pick(List<Version> versions, int major, Integer minor) {
		Version best = null;
		int bestMinor = -1;
		for (Version version : versions) {
			int[] parts = numbers(version.getVersion());
			if (parts.length == 0 || parts[0] != major)
				continue;
			if (minor != null && parts[1] == minor)
				return version;
			if (minor == null && parts[1] > bestMinor) {
				best = version;
				bestMinor = parts[1];
			}
		}
		return best;
	}

	/**
	 * @return the major and minor numbers of a version such as {@code 2.10},
	 *         or nothing if it doesn't have that form
	 */
	private static int[] numbers(String version) {
		String[] parts = StringUtils.split(StringUtils.defaultString(version), '.');
		if (parts.length != 2)
			return new int[0];
		try {
			return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
		} catch (NumberFormatException e) {
			return new int[0];
		}
	}
}
