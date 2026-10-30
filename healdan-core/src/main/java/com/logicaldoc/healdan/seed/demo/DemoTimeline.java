package com.logicaldoc.healdan.seed.demo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.HealdanTreeChecks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.Message;
import com.logicaldoc.core.communication.Recipient;
import com.logicaldoc.core.communication.SystemMessage;
import com.logicaldoc.core.communication.SystemMessageDAO;
import com.logicaldoc.core.conversion.FormatConversionManager;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentLink;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentNote;
import com.logicaldoc.core.document.DocumentNoteDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.parser.CatchAllParser;
import com.logicaldoc.core.parser.ParserFactory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.ticket.Ticket;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.navigation.MyMatters;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;

/**
 * Plays the demo's timeline (demo spec §4.6, §5.2): each step as its user, on
 * its day, through the services the web page uses. Before each action it
 * checks the permission the web page would ask for, since inside a seed
 * Healdan counts everyone as an admin and CE's create checks no permission
 * (plan call 1). Each history row gets the event the web page's RPC logs,
 * set here where CE's call leaves it to the caller. After each action,
 * {@link Backdater} dates what it wrote.
 * <p>
 * The actions, with the fields each needs (any other field is ignored), the
 * permission it checks, and what it calls. A refusal reads
 * "&lt;username&gt; lacks &lt;PERMISSION&gt; on &lt;target&gt;":
 *
 * <table>
 * <caption>The actions</caption>
 * <tr>
 * <th>{@code do}</th>
 * <th>Needs</th>
 * <th>Also reads</th>
 * <th>Checks</th>
 * <th>Calls</th>
 * </tr>
 * <tr>
 * <td>{@code file}</td>
 * <td>{@code matter}, {@code file}</td>
 * <td>{@code folder} (none: the workspace itself), {@code key}, {@code name},
 * {@code author}, {@code typist}, {@code class}, {@code subclass},
 * {@code comments}, {@code tags}</td>
 * <td>WRITE on the folder</td>
 * <td>{@code DocumentManager.create(File, …)}, then indexes</td>
 * </tr>
 * <tr>
 * <td>{@code checkin}</td>
 * <td>{@code doc}, {@code file}</td>
 * <td>{@code release}</td>
 * <td>WRITE on the document</td>
 * <td>checks it out if it isn't (logged as CHECKEDOUT), {@code checkin}, then
 * indexes</td>
 * </tr>
 * <tr>
 * <td>{@code checkout}</td>
 * <td>{@code doc}</td>
 * <td></td>
 * <td>WRITE</td>
 * <td>{@code DocumentManager.checkout}, logged as CHECKEDOUT</td>
 * </tr>
 * <tr>
 * <td>{@code lock}</td>
 * <td>{@code doc}</td>
 * <td></td>
 * <td>WRITE</td>
 * <td>{@code DocumentManager.lock(…, LOCKED, …)}, logged as LOCKED</td>
 * </tr>
 * <tr>
 * <td>{@code password}</td>
 * <td>{@code doc}, {@code password}</td>
 * <td></td>
 * <td>PASSWORD</td>
 * <td>{@code DocumentDAO.setPassword}</td>
 * </tr>
 * <tr>
 * <td>{@code note}</td>
 * <td>{@code doc}, {@code text}</td>
 * <td></td>
 * <td>WRITE</td>
 * <td>{@code DocumentNoteDAO.store}</td>
 * </tr>
 * <tr>
 * <td>{@code tag}</td>
 * <td>{@code doc}, {@code tags}</td>
 * <td></td>
 * <td>WRITE</td>
 * <td>{@code addTag}, {@code DocumentDAO.store}, logged as CHANGED</td>
 * </tr>
 * <tr>
 * <td>{@code link}</td>
 * <td>{@code doc}, {@code target}</td>
 * <td></td>
 * <td>WRITE on {@code doc}, READ on {@code target}</td>
 * <td>{@code DocumentLinkDAO.store}, type {@code default}</td>
 * </tr>
 * <tr>
 * <td>{@code move}</td>
 * <td>{@code doc}, {@code matter}, {@code folder}</td>
 * <td></td>
 * <td>MOVE on the document, WRITE on the folder</td>
 * <td>{@code DocumentManager.moveToFolder}</td>
 * </tr>
 * <tr>
 * <td>{@code folder}</td>
 * <td>{@code matter}, {@code name}</td>
 * <td>{@code folder} (the parent; none: the workspace itself)</td>
 * <td>ADD on the parent; for the workspace itself, being an administrator</td>
 * <td>{@code FolderDAO.create(parent, …, true, …)}</td>
 * </tr>
 * <tr>
 * <td>{@code delete}</td>
 * <td>{@code doc}</td>
 * <td></td>
 * <td>DELETE</td>
 * <td>{@code DocumentDAO.delete}, logged as DELETED</td>
 * </tr>
 * <tr>
 * <td>{@code deleteFolder}</td>
 * <td>{@code matter}, {@code folder}</td>
 * <td></td>
 * <td>DELETE on the folder, then on every folder and document in its tree
 * ({@code HealdanTreeChecks.deletedTrees}, as the web page's RPC checks); for
 * a top-level folder or the workspace, being an administrator</td>
 * <td>{@code FolderDAO.deleteTree}</td>
 * </tr>
 * <tr>
 * <td>{@code bookmark}</td>
 * <td>{@code doc}</td>
 * <td></td>
 * <td>READ</td>
 * <td>{@code BookmarkDAO.store}, type DOCUMENT</td>
 * </tr>
 * <tr>
 * <td>{@code myMatter}</td>
 * <td>{@code matter}</td>
 * <td></td>
 * <td>READ (as {@code MyMatters} checks)</td>
 * <td>{@code MyMatters.add}</td>
 * </tr>
 * <tr>
 * <td>{@code message}</td>
 * <td>{@code to}, {@code subject}, {@code text}</td>
 * <td></td>
 * <td>none</td>
 * <td>one {@code SystemMessage} per recipient, valid 60 days (plan call
 * 6)</td>
 * </tr>
 * <tr>
 * <td>{@code ticket}</td>
 * <td>{@code doc}</td>
 * <td></td>
 * <td>DOWNLOAD</td>
 * <td>{@code DocumentManager.createTicket}, expiring 30 days after the real
 * today</td>
 * </tr>
 * <tr>
 * <td>{@code grant}</td>
 * <td>{@code doc}, {@code to} (one username)</td>
 * <td></td>
 * <td>SECURITY</td>
 * <td>adds READ, PREVIEW and DOWNLOAD for that user's personal group to the
 * document's own ACL, logged as PERMISSION</td>
 * </tr>
 * </table>
 * <p>
 * Inside a seed Healdan treats every actor as an admin, so the positions only
 * admins may use (a matter's top level, workspaces, Stores, the Templates
 * Store) aren't checked beyond the refusals above, and a manifest must not use
 * them for a user who isn't an admin. Filing or moving a document to a
 * matter's top level is refused for everyone, as Healdan's filing rule
 * stands in a seed too.
 * <p>
 * Run it outside any Hibernate session or transaction: {@link Backdater} writes
 * dates with JDBC and clears only the second-level cache, so a session that
 * had loaded the rows would write them back.
 */
public final class DemoTimeline {

	private static final Logger log = LoggerFactory.getLogger(DemoTimeline.class);

	/** Indexes a document inside the action that filed or changed it (plan call 2) */
	@FunctionalInterface
	public interface Indexing {
		void index(long docId, DocumentHistory transaction) throws Exception;
	}

	/** How long a message from the timeline stays valid, in days (plan call 6) */
	private static final int MESSAGE_DAYS = 60;

	private static final Duration TICKET_LIFE = Duration.ofDays(30);

	private static final String TOP_LEVEL_ADMINS = "only administrators add or delete a matter's top-level folders";

	private static final String WORKSPACE_ADMINS = "only administrators delete a matter's workspace";

	private final DemoFirm firm;

	private final Path files;

	private final DemoClock clock;

	private final Backdater backdater;

	private final Indexing indexing;

	private final Map<String, Long> docs = new HashMap<>();

	public DemoTimeline(DemoFirm firm, Path files, DemoClock clock, Backdater backdater, Indexing indexing) {
		this.firm = firm;
		this.files = files;
		this.clock = clock;
		this.backdater = backdater;
		this.indexing = indexing;
	}

	/**
	 * Indexes the document as the web page does, with its text parsed from its
	 * file
	 *
	 * @param docId the document
	 * @param transaction the history entry to log the indexing in
	 * @throws Exception the document can't be indexed
	 */
	public static void index(long docId, DocumentHistory transaction) throws Exception {
		convertFirst(docId);
		DocumentManager.get().index(docId, null, transaction);
	}

	/**
	 * @param fileName a file's name
	 * @return whether CE reads the file's text through its PDF conversion, as
	 *         it does for a file no parser knows, an image for one
	 */
	static boolean readThroughConversion(String fileName) {
		return ParserFactory.getParser(fileName) instanceof CatchAllParser;
	}

	/**
	 * Makes the PDF conversion of a file CE reads through it before CE indexes
	 * the document. CE's indexing loads the document, then parses it, and the
	 * conversion saves the document (event Converted, a new record version)
	 * under it, so the save that ends the indexing finds the document changed
	 * and fails, which stops the seed. With the conversion already there,
	 * parsing only reads it. A conversion that fails is logged and the
	 * document is indexed without text, as CE's own parser does.
	 */
	private static void convertFirst(long docId) throws PersistenceException {
		Document doc = DocumentDAO.get().findById(docId);
		if (doc == null || !readThroughConversion(doc.getFileName()))
			return;
		try {
			FormatConversionManager.get().convertToPdf(doc, null, null);
		} catch (IOException | PersistenceException | RuntimeException e) {
			log.warn("Cannot convert {} to PDF before indexing it: {}", doc.getFileName(), e.getMessage());
		}
	}

	/**
	 * Plays the steps in day and time order; steps at the same moment keep
	 * the manifest's order
	 *
	 * @param timeline the steps
	 * @throws Exception a step is refused or fails; the message names it
	 */
	public void run(List<Step> timeline) throws Exception {
		List<Step> ordered = new ArrayList<>(timeline);
		ordered.sort(Comparator.comparingInt(Step::day).thenComparing(s -> s.time() == null ? "09:00" : s.time()));
		for (Step step : ordered) {
			Date when = clock.at(step.day(), step.time());
			try {
				perform(step, when);
			} catch (Exception e) {
				throw new IllegalStateException("Demo step '%s' by %s on day %d failed: %s".formatted(step.action(),
						step.as(), step.day(), e.getMessage()), e);
			}
			backdater.stamp(when);
		}
	}

	/**
	 * @param key the key a {@code file} step gave its document
	 * @return the document's id
	 */
	public long doc(String key) {
		Long id = docs.get(key);
		if (id == null)
			throw new IllegalArgumentException("No document has the key %s yet".formatted(key));
		return id;
	}

	private void perform(Step step, Date when) throws Exception {
		// With its groups, which CE's checks and history read
		User actor = UserDAO.get().findById(firm.user(step.as()).getId(), true);
		switch (step.action()) {
		case "file" -> file(step, actor, when);
		case "checkin" -> checkin(step, actor, when);
		case "checkout" -> {
			Document doc = need(actor, Permission.WRITE, step.doc());
			DocumentManager.get().checkout(doc.getId(), history(actor, when, DocumentEvent.CHECKEDOUT));
			backdater.touch(doc.getId(), when, false);
		}
		case "lock" -> {
			Document doc = need(actor, Permission.WRITE, step.doc());
			DocumentManager.get().lock(doc.getId(), DocumentStatus.LOCKED, history(actor, when, DocumentEvent.LOCKED));
			backdater.touch(doc.getId(), when, false);
		}
		case "password" -> {
			Document doc = need(actor, Permission.PASSWORD, step.doc());
			DocumentDAO.get().setPassword(doc.getId(), step.password(), history(actor, when));
			backdater.touch(doc.getId(), when, false);
		}
		case "note" -> note(step, actor, when);
		case "tag" -> tag(step, actor, when);
		case "link" -> link(step, actor);
		case "move" -> move(step, actor, when);
		case "folder" -> folder(step, actor, when);
		case "delete" -> {
			Document doc = need(actor, Permission.DELETE, step.doc());
			// The event the web page's delete logs; CE's delete leaves it to the caller
			DocumentDAO.get().delete(doc.getId(), history(actor, when, DocumentEvent.DELETED));
			// The Recycle Bin lists a deleted document by its last change
			backdater.touch(doc.getId(), when, false);
		}
		case "deleteFolder" -> deleteFolder(step, actor, when);
		case "bookmark" -> bookmark(step, actor);
		case "myMatter" -> MyMatters.get().add(actor.getId(), firm.workspace(step.matter()).getId());
		case "message" -> message(step, actor, when);
		case "ticket" -> ticket(step, actor, when);
		case "grant" -> grant(step, actor, when);
		default -> throw new IllegalArgumentException("Unknown action %s".formatted(step.action()));
		}
	}

	/**
	 * As the web page's folder dialog: ADD on the parent. A matter's top
	 * level is for administrators only, which Healdan's rules skip inside a
	 * seed.
	 */
	private void folder(Step step, User actor, Date when) throws Exception {
		Folder parent = folderAt(step.matter(), step.folder());
		if (isWorkspace(step.folder()) && !actor.isAdmin())
			throw adminsOnly(actor, TOP_LEVEL_ADMINS);
		need(actor, Permission.ADD, parent, where(step));
		FolderDAO.get().create(parent, new Folder(step.name()), true, folderHistory(actor, when));
	}

	/**
	 * As the web page's delete: DELETE on the folder, then on every folder and
	 * document in its tree ({@code HealdanTreeChecks.deletedTrees}, which the
	 * web page's RPC calls and Healdan's rules skip inside a seed). A matter's
	 * workspace and top-level folders are for administrators only.
	 */
	private void deleteFolder(Step step, User actor, Date when) throws Exception {
		Folder folder = folderAt(step.matter(), step.folder());
		if (isWorkspace(step.folder()) && !actor.isAdmin())
			throw adminsOnly(actor, WORKSPACE_ADMINS);
		if (isTopLevel(step.folder()) && !actor.isAdmin())
			throw adminsOnly(actor, TOP_LEVEL_ADMINS);
		need(actor, Permission.DELETE, folder, where(step));
		try {
			HealdanTreeChecks.deletedTrees(actor, List.of(folder.getId()));
		} catch (PermissionException e) {
			throw new IllegalStateException("%s lacks DELETE on everything in %s, so couldn't do this in the web page: %s"
					.formatted(actor.getUsername(), where(step), e.getMessage()), e);
		}
		List<Folder> left = FolderDAO.get().deleteTree(folder.getId(), folderHistory(actor, when));
		if (!left.isEmpty())
			throw new IllegalStateException(
					"%d folders under %s weren't deleted".formatted(left.size(), step.folder()));
		// As a deleted document's: the Recycle Bin lists the folder by its last change
		backdater.touchFolder(folder.getId(), when);
	}

	/** @param path a folder in the matter's workspace; null or blank for the workspace itself */
	private Folder folderAt(String matter, String path) throws Exception {
		return isWorkspace(path) ? firm.workspace(matter) : firm.folder(matter, path);
	}

	private static boolean isWorkspace(String path) {
		return path == null || path.isBlank();
	}

	/** @return true for a folder directly in the workspace, which Healdan calls the matter's top level */
	private static boolean isTopLevel(String path) {
		return !isWorkspace(path) && !path.contains("/");
	}

	private static String where(Step step) {
		return step.matter() + "/" + (step.folder() == null ? "" : step.folder());
	}

	private static IllegalStateException adminsOnly(User actor, String rule) {
		return new IllegalStateException("%s isn't an administrator, and %s, so couldn't do this in the web page"
				.formatted(actor.getUsername(), rule));
	}

	private void file(Step step, User actor, Date when) throws Exception {
		Folder folder = folderAt(step.matter(), step.folder());
		need(actor, Permission.WRITE, folder, where(step));

		Document vo = new Document();
		vo.setFolder(folder);
		vo.setFileName(step.name() != null ? step.name() : step.file());
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		Map<String, Object> values = new LinkedHashMap<>();
		if (step.author() != null)
			values.put(HealdanConstants.ATTR_AUTHOR, firm.user(step.author()));
		if (step.typist() != null)
			values.put(HealdanConstants.ATTR_TYPIST, firm.user(step.typist()));
		if (step.docClass() != null)
			values.put(HealdanConstants.ATTR_DOCCLASS, step.docClass());
		if (step.subclass() != null)
			values.put(HealdanConstants.ATTR_DOCSUBCLASS, step.subclass());
		if (step.comments() != null)
			values.put(HealdanConstants.ATTR_COMMENTS, step.comments());
		setProperties(vo, values);
		step.tags().forEach(vo::addTag);

		File content = content(step.file(), when);
		try {
			Document doc = DocumentManager.get().create(content, vo, history(actor, when)).getDocument();
			if (step.key() != null)
				docs.put(step.key(), doc.getId());
			indexing.index(doc.getId(), history(actor, when));
		} finally {
			Files.deleteIfExists(content.toPath());
		}
	}

	private void checkin(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.WRITE, step.doc());
		DocumentManager manager = DocumentManager.get();
		if (doc.getStatus() != DocumentStatus.CHECKEDOUT)
			manager.checkout(doc.getId(), history(actor, when, DocumentEvent.CHECKEDOUT));
		File content = content(step.file(), when);
		try (InputStream in = new FileInputStream(content)) {
			manager.checkin(doc.getId(), in, doc.getFileName(), step.release(), null, history(actor, when))
					.getDocument();
		} finally {
			Files.deleteIfExists(content.toPath());
		}
		indexing.index(doc.getId(), history(actor, when));
		backdater.touch(doc.getId(), when, true);
	}

	/** As the web page's DocumentServiceImpl.saveNote does for a new note */
	private void note(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.WRITE, step.doc());
		DocumentNote note = new DocumentNote();
		note.setTenantId(Tenant.DEFAULT_ID);
		note.setDocId(doc.getId());
		note.setFileVersion(doc.getFileVersion());
		note.setFileName(doc.getFileName());
		note.setUserId(actor.getId());
		note.setUsername(actor.getFullName());
		note.setMessage(step.text());
		note.setDate(when);
		DocumentNoteDAO.get().store(note, history(actor, when));
		// CE saves the document for a whole-document note: with the real time, and marked to index again
		reindexIfNeeded(doc.getId(), actor, when);
		backdater.touch(doc.getId(), when, false);
	}

	private void tag(Step step, User actor, Date when) throws Exception {
		DocumentDAO dao = DocumentDAO.get();
		// initialize returns the document with its collections loaded; the one it was given stays lazy
		Document doc = dao.initialize(need(actor, Permission.WRITE, step.doc()));
		step.tags().forEach(doc::addTag);
		// A change, as the web page's save logs it, so Recent Matters counts it
		dao.store(doc, history(actor, when, DocumentEvent.CHANGED));
		reindexIfNeeded(doc.getId(), actor, when);
		backdater.touch(doc.getId(), when, false);
	}

	/** As the web page's DocumentServiceImpl.linkDocuments does for one link */
	private void link(Step step, User actor) throws Exception {
		Document from = need(actor, Permission.WRITE, step.doc());
		Document to = need(actor, Permission.READ, step.target());
		DocumentLink link = new DocumentLink();
		link.setTenantId(Tenant.DEFAULT_ID);
		link.setType("default");
		link.setDocument1(from);
		link.setDocument2(to);
		DocumentLinkDAO.get().store(link);
	}

	private void move(Step step, User actor, Date when) throws Exception {
		DocumentDAO dao = DocumentDAO.get();
		Document doc = need(actor, Permission.MOVE, step.doc());
		Folder target = folderAt(step.matter(), step.folder());
		need(actor, Permission.WRITE, target, where(step));
		doc = dao.initialize(doc);
		DocumentManager.get().moveToFolder(doc, target, history(actor, when)).getDocument();
		reindexIfNeeded(doc.getId(), actor, when);
		backdater.touch(doc.getId(), when, false);
	}

	/** As the web page's DocumentServiceImpl.addBookmarks does for one document */
	private void bookmark(Step step, User actor) throws Exception {
		Document doc = need(actor, Permission.READ, step.doc());
		Bookmark bookmark = new Bookmark();
		bookmark.setTenantId(Tenant.DEFAULT_ID);
		bookmark.setUserId(actor.getId());
		bookmark.setTargetId(doc.getId());
		bookmark.setTitle(doc.getFileName());
		bookmark.setFileType(doc.getType());
		bookmark.setType(Bookmark.Type.DOCUMENT);
		BookmarkDAO.get().store(bookmark);
	}

	/** As the web page's MessageServiceImpl.saveMessage does: one message per recipient */
	private void message(Step step, User actor, Date when) throws Exception {
		for (String username : step.to()) {
			User to = firm.user(username);
			SystemMessage message = new SystemMessage();
			message.setTenantId(Tenant.DEFAULT_ID);
			message.setAuthor(actor.getUsername());
			message.setSentDate(when);
			message.setLastNotified(when);
			message.setStatus(SystemMessage.STATUS_NEW);
			message.setType(Message.Type.SYSTEM);
			message.setSubject(step.subject());
			message.setMessageText(step.text());
			Recipient recipient = new Recipient();
			recipient.setName(to.getUsername());
			recipient.setAddress(to.getUsername());
			recipient.setType(Recipient.Type.SYSTEM);
			message.setRecipients(new HashSet<>(Set.of(recipient)));
			message.setDateScope(MESSAGE_DAYS);
			message.setConfirmation(0);
			SystemMessageDAO.get().store(message);
		}
	}

	private void ticket(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.DOWNLOAD, step.doc());
		Ticket ticket = new Ticket();
		ticket.setTenantId(Tenant.DEFAULT_ID);
		ticket.setDocId(doc.getId());
		ticket.setUserId(actor.getId());
		ticket.setType(Ticket.DOWNLOAD);
		// Expires from the real today, so the screenshots show a live link
		ticket.setExpired(Date.from(Instant.now().plus(TICKET_LIFE)));
		DocumentManager.get().createTicket(ticket, history(actor, when));
	}

	/**
	 * A document-only grant (demo spec §4.4): enough for one person who can't
	 * see its folder to open, preview and download it
	 */
	private void grant(Step step, User actor, Date when) throws Exception {
		Document doc = DocumentDAO.get().initialize(need(actor, Permission.SECURITY, step.doc()));
		if (step.to().size() != 1)
			throw new IllegalArgumentException("A grant names one user, not %d".formatted(step.to().size()));
		User to = UserDAO.get().findById(firm.user(step.to().get(0)).getId(), true);
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(to.getUserGroup().getId());
		ace.grantPermissions(Set.of(Permission.READ, Permission.PREVIEW, Permission.DOWNLOAD));
		doc.addAccessControlEntry(ace);
		DocumentDAO.get().store(doc, history(actor, when, DocumentEvent.PERMISSION));
		backdater.touch(doc.getId(), when, false);
	}

	/**
	 * A copy of a demo file to file, with an email's dates moved to just
	 * before its filing (plan call 5)
	 */
	private File content(String name, Date when) throws IOException {
		Path source = files.resolve(name);
		if (!Files.isRegularFile(source))
			throw new IllegalArgumentException("No demo file %s".formatted(source));
		byte[] bytes = Files.readAllBytes(source);
		if (name.toLowerCase().endsWith(".eml"))
			bytes = EmailDates.restamp(bytes, when);
		Path copy = Files.createTempFile("demo-", "-" + source.getFileName());
		Files.write(copy, bytes);
		return copy.toFile();
	}

	/** CE marks a moved or retagged document to index again; the demo does it now, at the action's date */
	private void reindexIfNeeded(long docId, User actor, Date when) throws Exception {
		if (DocumentDAO.get().findById(docId).getIndexed() == IndexingStatus.TO_INDEX)
			indexing.index(docId, history(actor, when));
	}

	/** As the API's PropertyInput.set: each value on the value object's own copy of the profile's attribute */
	private static void setProperties(Document vo, Map<String, Object> values) {
		Template template = ProfileSchema.get().template();
		for (Map.Entry<String, Object> value : values.entrySet()) {
			Attribute own = vo.getAttribute(value.getKey());
			if (own == null) {
				own = new Attribute(template.getAttribute(value.getKey()));
				own.setInitialization(null);
				own.setValidation(null);
				vo.getAttributes().put(value.getKey(), own);
			}
			Inheritance.clear(own);
			own.setValue(value.getValue());
		}
	}

	private Document need(User actor, Permission permission, String key) throws Exception {
		Document doc = DocumentDAO.get().findById(doc(key));
		if (!AccessChecks.allowed(actor, permission, doc))
			throw new IllegalStateException("%s lacks %s on document %s, so couldn't do this in the web page"
					.formatted(actor.getUsername(), permission, key));
		return doc;
	}

	private static void need(User actor, Permission permission, Folder folder, String where) throws Exception {
		if (!AccessChecks.allowed(actor, permission, folder))
			throw new IllegalStateException("%s lacks %s on %s, so couldn't do this in the web page"
					.formatted(actor.getUsername(), permission, where));
	}

	/**
	 * For a call that always sets its own event, as CE's create, checkin, move,
	 * setPassword, createTicket, index and note store do
	 */
	private static DocumentHistory history(User actor, Date when) {
		DocumentHistory history = new DocumentHistory();
		history.setUser(actor);
		history.setDate(when);
		return history;
	}

	/**
	 * For a call that leaves the event to its caller, with the one the web
	 * page's RPC sets: without it the row shows a blank event in Document
	 * History, and Recent Matters doesn't see it. CE's checkout and lock set
	 * theirs only when it's null, and a new history's is empty, not null.
	 */
	private static DocumentHistory history(User actor, Date when, DocumentEvent event) {
		DocumentHistory history = history(actor, when);
		history.setEvent(event);
		return history;
	}

	private static FolderHistory folderHistory(User actor, Date when) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		history.setDate(when);
		return history;
	}
}
