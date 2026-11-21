// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageEvent;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStoreBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStuckFile;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.storage.SecretKeyMissingProblem;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.AddedBackend;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDraft;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendList;
import com.logicaldoc.healdan.admin.storage.StorageRecords.KeyEvent;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageLimits;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StuckFile;
import com.logicaldoc.healdan.admin.storage.StorageRecords.SwitchPreview;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageEvent;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreBackend;
import com.logicaldoc.healdan.storage.StoreBackendDAO;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.web.BackendDrafts.Draft;

/**
 * The storage port for the Admin app (spec section 9.7): {@link StorageAdmin}'s operations, the Storage tab's, with
 * {@code healdan-core}'s plain records. {@link StorageAdmin} doesn't change, so its rules, its locks and its
 * refusals, each a {@code HealdanRuleException} with the tab's sentence, are the port's; its missing secret key is
 * the port's {@link SecretKeyMissingProblem}, and the tab's question, a new key, is {@code newKey}.
 * <p>
 * Each method given the administrator ({@code by}), Show Recovery Key among them, refuses first anyone who isn't one,
 * whatever called it (Admin app ruling P3-R39); then every change, Test included, refuses while storage backends are
 * off, with a sentence of its own, as the Admin app has no Storage tab to name; and a Store in the Trash keeps its
 * backend, as its files stay there for a restore, so a removal, or a save that moves the backend, is refused first
 * while one is on it, with a sentence that names each such Store and says how to free the backend (Admin app ruling
 * P3-R38). Each backend's detail lists them ({@link BackendDetail#storesInTrash}), read with the Stores' backends from
 * {@code hd_storage_store} and the folder table, as {@link StorageAdmin} lists live Stores only.
 * <p>
 * Nothing secret is answered: no S3 secret, only whether one is stored, and a recovery key only in an encrypted
 * backend's add and in {@link #showRecoveryKey}, both recorded as showings by {@link StorageAdmin}. An S3 backend's
 * endpoint is given as its scheme, host and port alone, so a user name and password, a path, a query or a fragment,
 * which the endpoint rule refuses but a row written another way could hold, never reach the answer. A recovery key's
 * last showing is its time and the username it was shown to.
 * <p>
 * Each change that succeeded is recorded in the administration record straight after it
 * ({@link AdminEvents#recordAfter}, Admin app ruling P3-R5), as none is one database transaction: area
 * {@link AdminArea#STORAGE}, aimed at the backend or the Store by id and name, or at the storage as a whole for the
 * settings and the files with no Store. What changed is read from the rows before the change and after it, so a
 * change that changes nothing records nothing, and the values recorded are those stored. The S3 secret and the access
 * key are recorded as changed with no value ({@link Change#secret()}), and a recovery key is never recorded: its
 * showings, its confirming and its entering are actions with no detail. Two administrators changing one backend at
 * once may each record the other's change as theirs, as each reads the row outside {@link StorageAdmin}'s lock.
 */
@Component
public class StorageAdministrationImpl implements StorageAdministration {

	/** What the administration record calls a backend */
	static final String BACKEND = "backend";

	/** What it calls a Store */
	static final String STORE = "store";

	/** What it calls the storage as a whole: its settings, and the files with no Store */
	static final String STORAGE = "storage";

	/** The refusal of anyone but an administrator, whatever called the port */
	static final String ONLY_ADMINS = "Only an administrator can change the storage backends, a Store's backend or the "
			+ "storage settings, or see a recovery key.";

	/** The refusal of every change while storage backends are off */
	static final String OFF = "Storage backends are off: this server keeps its files in LogicalDOC's own store, so its "
			+ "storage backends, its Stores' backends and its storage settings can't be changed.";

	/**
	 * The Stores in the Trash, deleted or removed and not destroyed yet, with the backend each keeps its files on, null
	 * for Original: workspaces directly under the root, but the root itself, LogicalDOC's Default workspace and the
	 * Templates Store
	 */
	private static final String IN_TRASH = """
			select f.ld_name, s.hd_backendid from ld_folder f
			  left join hd_storage_store s on s.hd_storeid = f.ld_id and s.ld_deleted = 0
			 where f.ld_tenantid = ? and f.ld_type = ? and f.ld_parentid = ? and f.ld_deleted in (?, ?)
			   and f.ld_id <> ? and f.ld_id <> ? and f.ld_id <> ?""";

	private final StorageAdmin admin;

	private final AdminEvents events;

	private final StorageBackendDAO backends;

	private final StoredFiles files;

	private final StorageSettings storageSettings;

	private final SecretBox box;

	private final StoreBackendDAO storeBackends;

	private final JdbcTemplate jdbc;

	@Autowired
	public StorageAdministrationImpl(StorageAdmin admin, AdminEvents events, StorageBackendDAO backends,
			StoredFiles files, StorageSettings storageSettings, SecretBox box, StoreBackendDAO storeBackends,
			DataSource dataSource) {
		this.admin = admin;
		this.events = events;
		this.backends = backends;
		this.files = files;
		this.storageSettings = storageSettings;
		this.box = box;
		this.storeBackends = storeBackends;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	// Reading

	@Override
	public StorageOverview overview() throws PersistenceException {
		GUIStorageOverview gui = admin.overview();
		StorageSettingsInfo settings = new StorageSettingsInfo(gui.getHoldDays(), gui.getCacheGb(),
				gui.getTransfers());
		// The tab shows nothing else while CE keeps its files in its own store
		if (!gui.isOn())
			return new StorageOverview(false, List.of(), List.of(), settings, 0, 0, 0);

		List<BackendInfo> backends = new ArrayList<>();
		for (GUIStorageBackend backend : gui.getBackends())
			backends.add(new BackendInfo(backend.getId(), backend.getName(), backend.getKind(), where(backend),
					encrypted(backend), backend.getFiles(), backend.getSize(), status(backend),
					instant(backend.getKeyShown())));

		List<StoreBackendInfo> stores = new ArrayList<>();
		for (GUIStoreBackend store : gui.getStores())
			stores.add(new StoreBackendInfo(store.getStoreId(), store.getName(), store.getBackendId(),
					store.getBackendName(), store.getWaiting(), store.getWaitingSize(), store.getStuck(),
					store.getFiles(), store.getSize()));

		return new StorageOverview(true, backends, stores, settings, gui.getOtherWaiting(), gui.getOtherWaitingSize(),
				gui.getOtherStuck());
	}

	@Override
	public Optional<BackendDetail> backend(long id) throws PersistenceException {
		GUIStorageOverview gui = admin.overview();
		// As the overview: nothing to show while CE keeps its files in its own store
		if (!gui.isOn())
			return Optional.empty();
		for (GUIStorageBackend backend : gui.getBackends())
			if (backend.getId() == id)
				return Optional.of(detail(backend, storesOn(gui, id), storesInTrash(id)));
		return Optional.empty();
	}

	@Override
	public BackendList backends() throws PersistenceException {
		GUIStorageOverview gui = admin.overview();
		if (!gui.isOn())
			return new BackendList(false, List.of(), 0, 0, 0);
		List<BackendDetail> details = new ArrayList<>();
		Map<Long, List<String>> inTrash = storesInTrash();
		for (GUIStorageBackend backend : gui.getBackends())
			details.add(detail(backend, storesOn(gui, backend.getId()),
					inTrash.getOrDefault(backend.getId(), List.of())));
		return new BackendList(true, details, gui.getOtherWaiting(), gui.getOtherWaitingSize(), gui.getOtherStuck());
	}

	@Override
	public boolean exists(long id) throws PersistenceException {
		// As the overview: none while CE keeps its files in its own store
		return admin.on.getAsBoolean() && backends.findById(id) != null;
	}

	/**
	 * @return the names of the Stores the tab's overview has on the backend, by name: not the Templates Store, which
	 *         the tab lists among the workspaces, on Original, as it holds templates, not files
	 */
	private static List<String> storesOn(GUIStorageOverview gui, long id) {
		Long templates = HealdanSettings.get().templatesStoreId();
		return Arrays.stream(gui.getStores())
				.filter(store -> store.getBackendId() == id && !Long.valueOf(store.getStoreId()).equals(templates))
				.map(GUIStoreBackend::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
	}

	/**
	 * @return the names of the Stores on the backend, by name, as {@link #storesOn(GUIStorageOverview, long)} gives
	 *         them, read from the Stores' backends without the overview's counts: a Store with none of its own is on
	 *         Original
	 */
	private List<String> storesOn(long id) throws PersistenceException {
		Map<Long, Long> mapped = new HashMap<>();
		for (StoreBackend row : storeBackends.findAll())
			if (row.getDeleted() == 0)
				mapped.put(row.getStoreId(), row.getBackendId());
		Long templates = HealdanSettings.get().templatesStoreId();
		List<String> names = new ArrayList<>();
		for (Folder store : FolderDAO.get().findWorkspaces(Tenant.DEFAULT_ID))
			if (store.getId() != Folder.DEFAULTWORKSPACEID && !Long.valueOf(store.getId()).equals(templates)
					&& mapped.getOrDefault(store.getId(), StorageBackend.ORIGINAL) == id)
				names.add(store.getName());
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	/**
	 * @return the names of the Stores in the Trash, by the backend each keeps its files on, each list by name ignoring
	 *         case: a Store with no backend of its own is on Original. Never the Templates Store
	 */
	private Map<Long, List<String>> storesInTrash() {
		Long templates = HealdanSettings.get().templatesStoreId();
		Map<Long, List<String>> names = new HashMap<>();
		jdbc.query(IN_TRASH, rs -> {
			long mapped = rs.getLong(2);
			long backendId = rs.wasNull() ? StorageBackend.ORIGINAL : mapped;
			names.computeIfAbsent(backendId, key -> new ArrayList<>()).add(rs.getString(1));
		}, Tenant.DEFAULT_ID, Folder.TYPE_WORKSPACE, Folder.ROOTID, PersistentObject.DELETED_CODE_DEFAULT,
				PersistentObject.DELETED_CODE_STRONG, Folder.ROOTID, Folder.DEFAULTWORKSPACEID,
				templates != null ? templates : -1L);
		names.values().forEach(list -> list.sort(String.CASE_INSENSITIVE_ORDER));
		return names;
	}

	/** @return the names of the Stores in the Trash on the backend, by name */
	private List<String> storesInTrash(long id) {
		return storesInTrash().getOrDefault(id, List.of());
	}

	@Override
	public StorageLimits limits() {
		return new StorageLimits(StorageSettings.MAX_HOLD_DAYS, StorageSettings.MAX_CACHE_GB,
				StorageSettings.MAX_TRANSFERS);
	}

	@Override
	public boolean on() {
		return admin.on.getAsBoolean();
	}

	@Override
	public List<String> testNew(long id, BackendDraft draft, String secret) throws PersistenceException {
		requireOn();
		return admin.testNew(gui(id, draft, false), secret);
	}

	@Override
	public List<String> test(long id) throws PersistenceException {
		requireOn();
		return admin.test(id);
	}

	@Override
	public List<KeyEvent> keyEvents(long id) throws PersistenceException {
		List<KeyEvent> list = new ArrayList<>();
		for (GUIStorageEvent event : admin.keyEvents(id))
			list.add(new KeyEvent(keyEvent(event.getEvent()), instant(event.getDate()), event.getUserId(),
					event.getUserName()));
		return list;
	}

	@Override
	public SwitchPreview previewSwitch(long storeId, long backendId) throws PersistenceException {
		long[] moving = admin.previewSwitch(storeId, backendId);
		return new SwitchPreview(moving[0], moving[1]);
	}

	@Override
	public List<StuckFile> stuck(long storeId) throws PersistenceException {
		List<StuckFile> list = new ArrayList<>();
		for (GUIStuckFile file : admin.stuck(storeId))
			list.add(new StuckFile(file.getDocId(), file.getFileName(), file.getResource(), file.getAttempts(),
					file.getLastError()));
		return list;
	}

	// Writing, each recorded once it has succeeded

	@Override
	public AddedBackend add(BackendDraft draft, String secret, boolean newKey, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		GUIStorageBackend added = sealing(() -> admin.add(gui(0, draft, newKey), secret, by));
		long id = added.getId();
		events.recordAfter(new AdminEvent(AdminArea.STORAGE, "backend.added", BACKEND, id, added.getName(),
				Settings.of(added).added()), by);
		// The answer shows the recovery key, which StorageAdmin recorded as a showing
		String recoveryKey = added.getRecoveryKey();
		if (recoveryKey != null)
			events.recordAfter(new AdminEvent(AdminArea.STORAGE, "backend.recovery-key-shown", BACKEND, id,
					added.getName(), null), by);
		// Just added, no Store keeps its files on it, live or in the Trash
		return new AddedBackend(detail(added, List.of(), List.of()), recoveryKey);
	}

	@Override
	public BackendDetail save(long id, BackendDraft draft, String secret, boolean newKey, User by)
			throws PersistenceException {
		requireAdmin(by);
		requireOn();
		StorageBackend row = backends.findById(id);
		if (row != null && row.getDeleted() == 0)
			refuseMoveWhileInTrash(row, draft, secret, newKey);
		// A snapshot: the row may be the instance the save goes on to change
		Settings before = row != null ? Settings.of(row) : null;
		GUIStorageBackend saved = sealing(() -> admin.save(gui(id, draft, newKey), secret, by));
		if (before != null) {
			StorageBackend stored = backends.findById(id);
			// Removed straight after its save, the answer says what was saved
			Settings after = stored != null ? Settings.of(stored) : Settings.of(saved);
			Map<String, Change> changes = before.changes(after, secretChanged(before, after, secret, newKey));
			if (!changes.isEmpty())
				events.recordAfter(
						new AdminEvent(AdminArea.STORAGE, "backend.changed", BACKEND, id, saved.getName(), changes),
						by);
		}
		// The tab's answer is the backend as saved, its own files counted: no overview of every backend's
		return detail(saved, storesOn(id), storesInTrash(id));
	}

	/**
	 * Refuses a save that would give a backend another bucket, prefix or path while a Store in the Trash keeps its
	 * files there, as they stay for a restore. A draft the tab's checks refuse is left to the save, which refuses it
	 * with its own sentence; Original's place never changes.
	 */
	private void refuseMoveWhileInTrash(StorageBackend row, BackendDraft draft, String secret, boolean newKey)
			throws PersistenceException {
		if (row.getId() == StorageBackend.ORIGINAL || draft == null)
			return;
		Draft typed;
		try {
			typed = BackendDrafts.draft(gui(row.getId(), draft, newKey), secret, row);
		} catch (HealdanRuleException e) {
			return;
		}
		if (BackendDrafts.samePlace(row, typed))
			return;
		List<String> inTrash = storesInTrash(row.getId());
		if (inTrash.isEmpty())
			return;
		boolean one = inTrash.size() == 1;
		throw new HealdanRuleException(("%s can't change while %s in the Trash, as %s files stay on this backend while "
				+ "%s there: %s").formatted(BackendDrafts.moved(row, typed), inTrash(inTrash), one ? "its" : "their",
						one ? "it's" : "they're", wayOut(one)));
	}

	/** @return "Store A is", or "Stores A, B are" */
	private static String inTrash(List<String> stores) {
		return stores.size() == 1 ? "Store " + stores.get(0) + " is" : "Stores " + String.join(", ", stores) + " are";
	}

	/** @return how to free a backend that Stores in the Trash keep */
	private static String wayOut(boolean one) {
		return one ? "restore the Store, give it another backend, then delete it again."
				: "restore each, give it another backend, then delete it again.";
	}

	@Override
	public String showRecoveryKey(long id, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		String name = name(id);
		String recoveryKey = admin.showRecoveryKey(id, by);
		events.recordAfter(new AdminEvent(AdminArea.STORAGE, "backend.recovery-key-shown", BACKEND, id, name, null),
				by);
		return recoveryKey;
	}

	@Override
	public void confirmRecoveryKey(long id, String lastGroup, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		StorageBackend row = backends.findById(id);
		boolean confirmed = row != null && row.isKeyConfirmedOn();
		String name = row != null ? row.getName() : null;
		admin.confirmRecoveryKey(id, lastGroup, by);
		// A key already confirmed changes nothing
		if (!confirmed)
			events.recordAfter(
					new AdminEvent(AdminArea.STORAGE, "backend.recovery-key-confirmed", BACKEND, id, name, null), by);
	}

	@Override
	public void enterRecoveryKey(long id, String recoveryKey, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		String name = name(id);
		admin.enterRecoveryKey(id, recoveryKey, by);
		events.recordAfter(new AdminEvent(AdminArea.STORAGE, "backend.recovery-key-entered", BACKEND, id, name, null),
				by);
	}

	@Override
	public void remove(long id, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		// Original is refused by the tab's own sentence, whatever is in the Trash
		if (id != StorageBackend.ORIGINAL) {
			List<String> inTrash = storesInTrash(id);
			if (!inTrash.isEmpty()) {
				boolean one = inTrash.size() == 1;
				throw new HealdanRuleException(("%s in the Trash, and %s files stay on this backend while %s there, so "
						+ "the backend can't be removed: %s").formatted(inTrash(inTrash), one ? "its" : "their",
								one ? "it's" : "they're", wayOut(one)));
			}
		}
		String name = name(id);
		admin.remove(id, by);
		events.recordAfter(new AdminEvent(AdminArea.STORAGE, "backend.removed", BACKEND, id, name, null), by);
	}

	@Override
	public void setStoreBackend(long storeId, long backendId, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		long was = files.storeBackend(storeId);
		admin.setStoreBackend(storeId, backendId, by);
		long now = files.storeBackend(storeId);
		// A switch to the Store's own backend changes nothing
		if (was != now)
			events.recordAfter(new AdminEvent(AdminArea.STORAGE, "store.backend-changed", STORE, storeId,
					storeName(storeId), Map.of("backend", new Change(backendName(was), backendName(now)))), by);
	}

	@Override
	public void retry(long storeId, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		// With none stuck, a retry changes nothing
		boolean stuck = !files.stuck(storeId == 0 ? null : storeId, 1).isEmpty();
		admin.retry(storeId, by);
		if (!stuck)
			return;
		AdminEvent event = storeId == 0
				? new AdminEvent(AdminArea.STORAGE, "storage.stuck-retried", STORAGE, null, null, null)
				: new AdminEvent(AdminArea.STORAGE, "storage.stuck-retried", STORE, storeId, storeName(storeId), null);
		events.recordAfter(event, by);
	}

	@Override
	public void saveSettings(StorageSettingsInfo settings, User by) throws PersistenceException {
		requireAdmin(by);
		requireOn();
		Objects.requireNonNull(settings, "settings");
		StorageSettingsInfo was = settings();
		admin.saveSettings(settings.holdDays(), settings.cacheGb(), settings.transfers(), by);
		StorageSettingsInfo now = settings();
		Map<String, Change> changes = new HashMap<>();
		change(changes, "holdDays", was.holdDays(), now.holdDays());
		change(changes, "cacheGb", was.cacheGb(), now.cacheGb());
		change(changes, "transfers", was.transfers(), now.transfers());
		if (!changes.isEmpty())
			events.recordAfter(
					new AdminEvent(AdminArea.STORAGE, "storage.settings-changed", STORAGE, null, null, changes), by);
	}

	private static void change(Map<String, Change> changes, String name, int before, int after) {
		if (before != after)
			changes.put(name, new Change(String.valueOf(before), String.valueOf(after)));
	}

	/** @return the storage settings as stored, without counting any file */
	@Override
	public StorageSettingsInfo settings() {
		return new StorageSettingsInfo(storageSettings.holdDays(), storageSettings.cacheGb(),
				storageSettings.transfers());
	}

	/**
	 * @return whether a save changed the S3 secret: its sealed value changed, and the secret it opens to isn't the one
	 *         before. Each seal differs, so the same secret typed again is sealed anew without changing; one that
	 *         doesn't open, as after a new secret key, counts as changed. With a new secret key, the old sealed value
	 *         can't be opened, so a changed one counts as changed without trying
	 */
	private boolean secretChanged(Settings before, Settings after, String typed, boolean newKey) {
		if (!after.isS3())
			return false;
		// From the save's answer, which holds no sealed value: whatever was typed
		if (after.sealed() == null)
			return StringUtils.isNotBlank(typed);
		if (Objects.equals(before.sealed(), after.sealed()))
			return false;
		if (newKey)
			return true;
		String was = box.open(before.sealed());
		String now = box.open(after.sealed());
		return was == null || now == null || !was.equals(now);
	}

	/**
	 * Refuses anyone but an administrator, whatever called the port: its routes refuse them first, and so does the
	 * port, as every admin service does
	 */
	private static void requireAdmin(User by) throws PersistenceException {
		Objects.requireNonNull(by, "by");
		if (!FilingRules.get().isAdmin(by))
			throw new HealdanRuleException(ONLY_ADMINS);
	}

	/** Refuses a change while storage backends are off, in the Admin app's words, before the tab's service is asked */
	private void requireOn() throws HealdanRuleException {
		if (!on())
			throw new HealdanRuleException(OFF);
	}

	/** Runs an add or a save that seals a typed secret, a missing secret key file being the port's problem */
	private static <T> T sealing(Work<T> work) throws PersistenceException {
		try {
			return work.run();
		} catch (StorageAdmin.SecretKeyMissing e) {
			throw new SecretKeyMissingProblem(e.sealed(), e);
		}
	}

	@FunctionalInterface
	private interface Work<T> {

		T run() throws PersistenceException;
	}

	/** @return the backend's name; null if it doesn't exist, which the operation then refuses */
	private String name(long id) throws PersistenceException {
		StorageBackend row = backends.findById(id);
		return row != null ? row.getName() : null;
	}

	/** @return the backend's name; its id if it's gone */
	private String backendName(long id) throws PersistenceException {
		String name = name(id);
		return name != null ? name : String.valueOf(id);
	}

	/** @return the Store's name; null if it's gone */
	private static String storeName(long storeId) throws PersistenceException {
		Folder store = FolderDAO.get().findById(storeId);
		return store != null ? store.getName() : null;
	}

	/**
	 * A backend's settings as the administration record compares them, from its row or from the tab's answer.
	 *
	 * @param path a folder's path; Original's from the configuration, as the tab has it
	 * @param sealed the S3 secret as sealed in its row; null from the tab's answer, which never holds it
	 */
	private record Settings(String name, String kind, String path, String endpoint, String region, String bucket,
			String prefix, boolean pathStyle, String accessKey, boolean encrypted, String sealed) {

		static Settings of(StorageBackend row) {
			return new Settings(row.getName(), row.getKind(),
					row.getId() == StorageBackend.ORIGINAL ? Staging.original().toString() : row.getPath(),
					row.getEndpoint(), row.getRegion(), row.getBucket(), row.getPrefix(), row.isPathStyleOn(),
					row.getAccessKey(), row.isEncryptedOn(), row.getSecret());
		}

		static Settings of(GUIStorageBackend gui) {
			return new Settings(gui.getName(), gui.getKind(), gui.getPath(), gui.getEndpoint(), gui.getRegion(),
					gui.getBucket(), gui.getPrefix(), gui.isPathStyle(), gui.getAccessKey(),
					StorageAdministrationImpl.encrypted(gui), null);
		}

		boolean isS3() {
			return StorageBackend.S3.equals(kind);
		}

		/**
		 * @return the settings the record shows by value, by name, for the kind: never the access key or the secret,
		 *         and an endpoint as its scheme, host and port alone
		 */
		Map<String, String> shown() {
			Map<String, String> shown = new LinkedHashMap<>();
			shown.put("name", name);
			shown.put("kind", kind);
			if (isS3()) {
				shown.put("endpoint", StorageAdministrationImpl.endpoint(endpoint));
				shown.put("region", region);
				shown.put("bucket", bucket);
				shown.put("prefix", prefix);
				shown.put("pathStyle", String.valueOf(pathStyle));
			} else {
				shown.put("path", path);
			}
			shown.put("encrypted", String.valueOf(encrypted));
			return shown;
		}

		/** @return an add's changes: every setting it was given, the access key and the secret with no value */
		Map<String, Change> added() {
			Map<String, Change> changes = new HashMap<>();
			shown().forEach((field, value) -> {
				if (value != null)
					changes.put(field, new Change(null, value));
			});
			if (isS3()) {
				changes.put("accessKey", Change.secret());
				changes.put("secret", Change.secret());
			}
			return changes;
		}

		/**
		 * @param after the settings saved
		 * @param secret whether the save changed the secret
		 * @return each setting that changed, before and after; the access key and the secret with no value
		 */
		Map<String, Change> changes(Settings after, boolean secret) {
			Map<String, Change> changes = new HashMap<>();
			Map<String, String> was = shown();
			Map<String, String> now = after.shown();
			Set<String> fields = new LinkedHashSet<>(was.keySet());
			fields.addAll(now.keySet());
			for (String field : fields)
				if (!Objects.equals(was.get(field), now.get(field)))
					changes.put(field, new Change(was.get(field), now.get(field)));
			if (!Objects.equals(accessKey, after.accessKey))
				changes.put("accessKey", Change.secret());
			if (secret)
				changes.put("secret", Change.secret());
			return changes;
		}

		/** @return what it is, never the access key or the sealed secret, which a record's own would print */
		@Override
		public String toString() {
			return "Settings[" + kind + " " + name + "]";
		}
	}

	// The records

	/** @return the tab's form, as the port's draft fills it; null for none, which the tab's service refuses */
	static GUIStorageBackend gui(long id, BackendDraft draft, boolean newKey) {
		if (draft == null)
			return null;
		GUIStorageBackend gui = new GUIStorageBackend();
		gui.setId(id);
		gui.setKind(draft.kind());
		gui.setName(draft.name());
		gui.setPath(draft.path());
		gui.setEndpoint(draft.endpoint());
		gui.setRegion(draft.region());
		gui.setBucket(draft.bucket());
		gui.setPrefix(draft.prefix());
		gui.setPathStyle(draft.pathStyle());
		gui.setAccessKey(draft.accessKey());
		gui.setEncrypted(draft.encrypted());
		gui.setNewKey(newKey);
		return gui;
	}

	/**
	 * @param stores the names of the Stores on it
	 * @param storesInTrash the names of the Stores in the Trash on it
	 * @return what the edit form shows of the tab's backend: never its recovery key, which the add answers beside it
	 */
	static BackendDetail detail(GUIStorageBackend backend, List<String> stores, List<String> storesInTrash) {
		return new BackendDetail(backend.getId(), backend.getName(), backend.getKind(), backend.isOriginal(),
				backend.getPath(), backend.isS3() ? endpoint(backend.getEndpoint()) : null, backend.getRegion(),
				backend.getBucket(), backend.getPrefix(), backend.isPathStyle(), backend.getAccessKey(),
				backend.isHasSecret(), encrypted(backend), backend.isKeyConfirmed(), backend.isOffered(),
				backend.isInUse(), backend.getFiles(), backend.getSize(), status(backend), backend.getError(),
				instant(backend.getSince()), instant(backend.getKeyShown()), backend.getKeyShownBy(), stores,
				storesInTrash);
	}

	/** @return the port's name for the tab's event: {@link KeyEvent#SHOWN} or {@link KeyEvent#ENTERED} */
	private static String keyEvent(String event) {
		if (StorageEvent.KEY_SHOWN.equals(event))
			return KeyEvent.SHOWN;
		if (StorageEvent.KEY_ENTERED.equals(event))
			return KeyEvent.ENTERED;
		return event;
	}

	/**
	 * @return the tab's status, but {@link BackendInfo#UNREACHABLE} for a backend failing because it can't be reached,
	 *         an outage, apart from one {@link BackendInfo#FAILING} for any other reason
	 */
	static String status(GUIStorageBackend backend) {
		if (BackendInfo.FAILING.equals(backend.getStatus()) && backend.isUnreachable())
			return BackendInfo.UNREACHABLE;
		return backend.getStatus();
	}

	/**
	 * @return as the tab's Where column has it: a folder's path, or an S3 backend's bucket and prefix at its endpoint,
	 *         the endpoint's scheme, host and port alone; what it lacks left out, a bucket or an endpoint that can't be
	 *         read among them
	 */
	static String where(GUIStorageBackend backend) {
		if (!backend.isS3())
			return backend.getPath();
		String place = (StringUtils.isNotBlank(backend.getBucket()) ? backend.getBucket() + "/" : "")
				+ StringUtils.defaultString(backend.getPrefix());
		String endpoint = endpoint(backend.getEndpoint());
		if (endpoint == null)
			return place;
		return place.isEmpty() ? endpoint : place + " at " + endpoint;
	}

	/**
	 * @param endpoint an S3 endpoint as stored
	 * @return its scheme, host and port, never what else it holds; null if it isn't an address with a host
	 */
	static String endpoint(String endpoint) {
		if (endpoint == null)
			return null;
		try {
			URI uri = new URI(endpoint.strip());
			if (uri.getScheme() == null || uri.getHost() == null)
				return null;
			return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
		} catch (URISyntaxException e) {
			// Its message holds the endpoint
			return null;
		}
	}

	/** @return whether its files are encrypted: as the tab reads it, the kind's default when unset */
	private static boolean encrypted(GUIStorageBackend backend) {
		return backend.getEncrypted() != null ? backend.getEncrypted() : backend.isS3();
	}

	/** @return the time, with a timestamp's fraction of a second kept whole */
	static Instant instant(Date date) {
		if (date == null)
			return null;
		// java.sql.Date has no time, and its toInstant() throws
		return date instanceof Timestamp timestamp ? timestamp.toInstant() : Instant.ofEpochMilli(date.getTime());
	}
}
