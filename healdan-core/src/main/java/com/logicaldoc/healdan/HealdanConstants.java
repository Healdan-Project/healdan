package com.logicaldoc.healdan;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.logicaldoc.core.document.DocumentEvent;

/**
 * Names and codes shared by all the Healdan classes.
 */
public final class HealdanConstants {

	/** Folder type of a search folder */
	public static final int FOLDER_TYPE_SEARCH = 1001;

	/** Name of the CE template that carries the Healdan profile */
	public static final String PROFILE_TEMPLATE = "Healdan profile";

	/** Name of the store holding the templates */
	public static final String TEMPLATES_STORE_NAME = "Templates";

	/** Name of the group that contains all the users */
	public static final String EVERYONE_GROUP = "Everyone";

	/** Extensions of the files that are considered emails */
	public static final Set<String> EMAIL_EXTENSIONS = Set.of("eml", "msg");

	/**
	 * The document history events that count as a change (spec 5.4): created,
	 * new version or snapshot, properties edited, renamed and moved in. CE's
	 * last modified date isn't used, because check-outs and locks change it
	 * too.
	 */
	public static final Set<DocumentEvent> CHANGE_EVENTS = Set.of(DocumentEvent.STORED, DocumentEvent.CHECKEDIN,
			DocumentEvent.CHANGED, DocumentEvent.RENAMED, DocumentEvent.MOVED);

	/** The {@link #CHANGE_EVENTS} as CE stores them in the history, sorted */
	public static final List<String> CHANGE_EVENT_KEYS = CHANGE_EVENTS.stream().map(DocumentEvent::toKey).sorted()
			.toList();

	/** A day in milliseconds */
	public static final long DAY = Duration.ofDays(1).toMillis();

	public static final String ATTR_CLIENT = "client";

	public static final String ATTR_MATTER = "matter";

	public static final String ATTR_AUTHOR = "author";

	public static final String ATTR_TYPIST = "typist";

	public static final String ATTR_DOCCLASS = "docclass";

	public static final String ATTR_DOCSUBCLASS = "docsubclass";

	public static final String ATTR_COMMENTS = "comments";

	public static final String ATTR_LEGACYDOCNUM = "legacydocnum";

	public static final String ATTR_EMAILFROM = "emailfrom";

	public static final String ATTR_EMAILTO = "emailto";

	public static final String ATTR_EMAILCC = "emailcc";

	public static final String ATTR_EMAILSUBJECT = "emailsubject";

	public static final String ATTR_EMAILSENT = "emailsent";

	public static final String ATTR_EMAILRECEIVED = "emailreceived";

	public static final int SEARCH_DOC_NUMBER = 101;

	public static final int SEARCH_MATTERS = 102;

	public static final int SEARCH_CLIENTS = 103;

	public static final int SEARCH_DOCUMENTS_ONLY = 104;

	public static final int SEARCH_EMAILS_ONLY = 105;

	private HealdanConstants() {
	}
}
