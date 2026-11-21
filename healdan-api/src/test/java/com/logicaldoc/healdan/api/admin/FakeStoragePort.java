// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
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
import com.logicaldoc.healdan.admin.stores.StoreService;

/**
 * A storage port for the API's tests, which keeps its backends, Stores and settings in memory and refuses as the
 * Storage tab's service does, with sentences of its own: storage backends off, a backend that doesn't exist, one not
 * offered, a recovery key mistyped. It records nothing, as the routes record nothing for storage; it keeps what each
 * write was given, but never a secret or a recovery key, so a test can check what the routes sent.
 */
final class FakeStoragePort implements StorageAdministration {

	/** The S3 secret the tests type; no answer, record or log line may hold it */
	static final String SECRET = "Wj4!secret-typed-Kq9";

	/** The recovery key of every encrypted backend the fake adds; only the add's and Show's answers may hold it */
	static final String RECOVERY_KEY = "7G2K 9QWX 3M4N P5R6 S7T8 V9W0 X1Y2 Z3A4 B5C6 D7E8 F9G0 H1J2 K3MG";

	/** Its last group */
	static final String LAST_GROUP = "K3MG";

	/** The refusal of any write while storage backends are off */
	static final String OFF = "Storage backends are off on this test's server.";

	/** The refusal of a backend that doesn't exist */
	static final String NO_BACKEND = "That storage backend doesn't exist.";

	static final StorageLimits LIMITS = new StorageLimits(365, 1000, 16);

	boolean on = true;

	/** Whether the overview's read fails, as when the database does */
	boolean overviewFails;

	/** The backends, Original first */
	final Map<Long, BackendDetail> backends = new LinkedHashMap<>();

	/** Each Store's backend, by Store; Original when it has none */
	final Map<Long, Long> storeBackends = new HashMap<>();

	/** Each Store's files: waiting, their bytes, stuck, recorded, their bytes */
	final Map<Long, long[]> storeFiles = new HashMap<>();

	/** The files with no Store: waiting, their bytes, stuck */
	long[] otherFiles = { 0, 0, 0 };

	/** The names of the Stores in the Trash that keep their files on each backend, by backend */
	final Map<Long, List<String>> storesInTrash = new HashMap<>();

	/** The stuck files, by Store; 0 for those with no Store */
	final Map<Long, List<StuckFile>> stuck = new HashMap<>();

	final List<KeyEvent> keyEvents = new ArrayList<>();

	StorageSettingsInfo settings = new StorageSettingsInfo(30, 10, 4);

	/** What a test finds, for Test before saving and Test */
	List<String> problems = List.of();

	/** How many values are sealed with a missing secret key file; 0 while it's there */
	long sealed;

	/** The next write's refusal, if any */
	String refusal;

	/**
	 * How many times the backends' files were counted, as the real port's overview, its backend and its list of them
	 * each count them
	 */
	int reads;

	/** What each write was given, as {@code <operation> <what>}, never a secret or a recovery key */
	final List<String> calls = new ArrayList<>();

	/** The last draft a test, an add or a save was given */
	BackendDraft lastDraft;

	/** Whether the last test, add or save was given a secret, and whether it was {@link #SECRET} */
	String lastSecret;

	boolean lastNewKey;

	private long nextId = 2;

	FakeStoragePort() {
		backends.put(1L, new BackendDetail(1, "Original", "local", true, "/repository/docs", null, null, null, null,
				false, null, false, false, false, true, true, 12, 3400, BackendInfo.OK, null,
				Instant.parse("2026-11-20T08:00:00Z"), null, null, List.of(), List.of()));
	}

	/**
	 * Adds an S3 backend as one added long ago, its recovery key shown and confirmed
	 *
	 * @return its id
	 */
	long s3(String name, boolean encrypted, boolean confirmed, String status) {
		long id = nextId++;
		backends.put(id, new BackendDetail(id, name, "s3", false, null, "https://s3.example.test", "eu-west-1",
				"firm-files", "healdan/", true, "AKEXAMPLE", true, encrypted, confirmed, !encrypted || confirmed, true,
				5, 500, status, BackendInfo.OK.equals(status) ? null : "This backend can't be reached.",
				Instant.parse("2026-11-21T09:30:00Z"), encrypted ? Instant.parse("2026-11-19T10:15:30Z") : null,
				encrypted ? "boss" : null, List.of(), List.of()));
		return id;
	}

	/**
	 * Adds a local folder
	 *
	 * @return its id
	 */
	long local(String name, String path) {
		long id = nextId++;
		backends.put(id, new BackendDetail(id, name, "local", false, path, null, null, null, null, false, null, false,
				false, false, true, false, 0, 0, BackendInfo.UNCHECKED, null, null, null, null, List.of(), List.of()));
		return id;
	}

	@Override
	public StorageOverview overview() throws PersistenceException {
		reads++;
		if (overviewFails)
			throw new PersistenceException("The storage overview can't be read in this test.");
		if (!on)
			return new StorageOverview(false, List.of(), List.of(), settings, 0, 0, 0);
		List<BackendInfo> infos = backends.values().stream()
				.map(b -> new BackendInfo(b.id(), b.name(), b.kind(), b.path() != null ? b.path() : b.bucket(),
						b.encrypted(), b.files(), b.bytes(), b.status(), b.keyLastShown()))
				.toList();
		List<StoreBackendInfo> stores = new ArrayList<>();
		for (Folder store : StoreService.get().stores()) {
			long backendId = storeBackends.getOrDefault(store.getId(), 1L);
			long[] files = storeFiles.getOrDefault(store.getId(), new long[5]);
			stores.add(new StoreBackendInfo(store.getId(), store.getName(), backendId,
					backends.get(backendId).name(), files[0], files[1], files[2], files[3], files[4]));
		}
		return new StorageOverview(true, infos, stores, settings, otherFiles[0], otherFiles[1], otherFiles[2]);
	}

	@Override
	public Optional<BackendDetail> backend(long id) {
		reads++;
		return detail(id);
	}

	@Override
	public BackendList backends() {
		reads++;
		if (!on)
			return new BackendList(false, List.of(), 0, 0, 0);
		List<BackendDetail> details = new ArrayList<>();
		for (long id : backends.keySet())
			details.add(detail(id).orElseThrow());
		return new BackendList(true, details, otherFiles[0], otherFiles[1], otherFiles[2]);
	}

	@Override
	public boolean exists(long id) {
		return on && backends.containsKey(id);
	}

	/** @return the backend with its Stores, counting nothing */
	private Optional<BackendDetail> detail(long id) {
		if (!on)
			return Optional.empty();
		BackendDetail backend = backends.get(id);
		if (backend == null)
			return Optional.empty();
		List<String> stores = new ArrayList<>();
		try {
			for (Folder store : StoreService.get().stores())
				if (storeBackends.getOrDefault(store.getId(), 1L) == id)
					stores.add(store.getName());
		} catch (PersistenceException e) {
			throw new IllegalStateException(e);
		}
		return Optional.of(with(backend, stores, storesInTrash.getOrDefault(id, List.of())));
	}

	@Override
	public StorageLimits limits() {
		return LIMITS;
	}

	@Override
	public boolean on() {
		return on;
	}

	@Override
	public StorageSettingsInfo settings() {
		return settings;
	}

	@Override
	public List<String> testNew(long id, BackendDraft draft, String secret) throws PersistenceException {
		requireOn();
		if (id != 0)
			row(id);
		calls.add("testNew " + id + " " + draft.kind() + " " + draft.name());
		lastDraft = draft;
		lastSecret = given(secret);
		return problems;
	}

	@Override
	public AddedBackend add(BackendDraft draft, String secret, boolean newKey, User by) throws PersistenceException {
		requireOn();
		refuseIfAsked();
		boolean s3 = "s3".equals(draft.kind());
		if (s3 && sealed > 0 && !newKey)
			throw new SecretKeyMissingProblem(sealed);
		calls.add("add " + draft.kind() + " " + draft.name() + (newKey ? " newKey" : ""));
		lastDraft = draft;
		lastSecret = given(secret);
		lastNewKey = newKey;
		boolean encrypted = draft.encrypted() != null ? draft.encrypted() : s3;
		long id = nextId++;
		BackendDetail added = new BackendDetail(id, draft.name(), draft.kind(), false, draft.path(),
				s3 ? draft.endpoint() : null, draft.region(), draft.bucket(),
				s3 ? (draft.prefix().isEmpty() ? "" : draft.prefix() + "/") : null,
				draft.pathStyle(), draft.accessKey(), s3 && secret != null, encrypted, false, !encrypted, false, 0, 0,
				BackendInfo.OK, null, Instant.parse("2026-11-21T10:00:00Z"),
				encrypted ? Instant.parse("2026-11-21T10:00:00Z") : null, encrypted ? by.getUsername() : null,
				List.of(), List.of());
		backends.put(id, added);
		if (encrypted)
			keyEvents.add(0, new KeyEvent(KeyEvent.SHOWN, Instant.parse("2026-11-21T10:00:00Z"), by.getId(),
					by.getUsername()));
		return new AddedBackend(added, encrypted ? RECOVERY_KEY : null);
	}

	@Override
	public BackendDetail save(long id, BackendDraft draft, String secret, boolean newKey, User by)
			throws PersistenceException {
		requireOn();
		BackendDetail row = row(id);
		refuseIfAsked();
		if (secret != null && sealed > 0 && !newKey)
			throw new SecretKeyMissingProblem(sealed);
		calls.add("save " + id + " " + draft.name() + (newKey ? " newKey" : ""));
		lastDraft = draft;
		lastSecret = given(secret);
		lastNewKey = newKey;
		BackendDetail saved = new BackendDetail(id, draft.name(), row.kind(), row.original(),
				row.original() ? row.path() : draft.path(),
				// As the real port's save: an endpoint left out keeps the stored one
				draft.endpoint() != null ? draft.endpoint() : row.endpoint(), draft.region(), draft.bucket(),
				draft.prefix(), draft.pathStyle(), draft.accessKey(), row.hasSecret() || secret != null,
				row.encrypted(), row.keyConfirmed(), row.offered(), row.inUse(), row.files(), row.bytes(),
				row.status(), row.error(), row.since(), row.keyLastShown(), row.keyLastShownBy(), List.of(),
				List.of());
		backends.put(id, saved);
		// As the real port's save, which counts the backend's own files alone
		return detail(id).orElseThrow();
	}

	@Override
	public List<String> test(long id) throws PersistenceException {
		requireOn();
		row(id);
		calls.add("test " + id);
		return problems;
	}

	@Override
	public String showRecoveryKey(long id, User by) throws PersistenceException {
		requireOn();
		BackendDetail row = encrypted(id);
		calls.add("show " + id);
		keyEvents.add(0, new KeyEvent(KeyEvent.SHOWN, Instant.parse("2026-11-21T11:00:00Z"), by.getId(),
				by.getUsername()));
		backends.put(id, new BackendDetail(id, row.name(), row.kind(), row.original(), row.path(), row.endpoint(),
				row.region(), row.bucket(), row.prefix(), row.pathStyle(), row.accessKey(), row.hasSecret(),
				row.encrypted(), row.keyConfirmed(), row.offered(), row.inUse(), row.files(), row.bytes(),
				row.status(), row.error(), row.since(), Instant.parse("2026-11-21T11:00:00Z"), by.getUsername(),
				List.of(), List.of()));
		return RECOVERY_KEY;
	}

	@Override
	public void confirmRecoveryKey(long id, String lastGroup, User by) throws PersistenceException {
		requireOn();
		BackendDetail row = encrypted(id);
		if (!LAST_GROUP.equalsIgnoreCase(lastGroup.replace(" ", "").replace("-", "")))
			throw new HealdanRuleException("That isn't the last group of this backend's recovery key.");
		calls.add("confirm " + id);
		backends.put(id, new BackendDetail(id, row.name(), row.kind(), row.original(), row.path(), row.endpoint(),
				row.region(), row.bucket(), row.prefix(), row.pathStyle(), row.accessKey(), row.hasSecret(),
				row.encrypted(), true, true, row.inUse(), row.files(), row.bytes(), row.status(), row.error(),
				row.since(), row.keyLastShown(), row.keyLastShownBy(), List.of(), List.of()));
	}

	@Override
	public void enterRecoveryKey(long id, String recoveryKey, User by) throws PersistenceException {
		requireOn();
		encrypted(id);
		if (!RECOVERY_KEY.replace(" ", "").equalsIgnoreCase(recoveryKey.replace(" ", "").replace("-", "")))
			throw new HealdanRuleException("That recovery key isn't this backend's, so nothing was written.");
		calls.add("enter " + id);
		keyEvents.add(0, new KeyEvent(KeyEvent.ENTERED, Instant.parse("2026-11-21T12:00:00Z"), by.getId(),
				by.getUsername()));
	}

	@Override
	public List<KeyEvent> keyEvents(long id) {
		return List.copyOf(keyEvents);
	}

	@Override
	public void remove(long id, User by) throws PersistenceException {
		requireOn();
		BackendDetail row = row(id);
		if (row.original())
			throw new HealdanRuleException("Original can be renamed, but not removed.");
		refuseIfAsked();
		calls.add("remove " + id);
		backends.remove(id);
	}

	@Override
	public SwitchPreview previewSwitch(long storeId, long backendId) throws PersistenceException {
		row(backendId);
		if (storeBackends.getOrDefault(storeId, 1L) == backendId)
			return new SwitchPreview(0, 0);
		long[] files = storeFiles.getOrDefault(storeId, new long[5]);
		return new SwitchPreview(files[3], files[4]);
	}

	@Override
	public void setStoreBackend(long storeId, long backendId, User by) throws PersistenceException {
		requireOn();
		BackendDetail row = row(backendId);
		if (!row.offered())
			throw new HealdanRuleException("The recovery key of backend " + row.name() + " isn't confirmed yet.");
		refuseIfAsked();
		calls.add("setStoreBackend " + storeId + " " + backendId);
		storeBackends.put(storeId, backendId);
	}

	@Override
	public List<StuckFile> stuck(long storeId) {
		return stuck.getOrDefault(storeId, List.of());
	}

	@Override
	public void retry(long storeId, User by) throws PersistenceException {
		requireOn();
		calls.add("retry " + storeId);
	}

	@Override
	public void saveSettings(StorageSettingsInfo settings, User by) throws PersistenceException {
		requireOn();
		if (settings.holdDays() < 1 || settings.holdDays() > LIMITS.maxHoldDays())
			throw new HealdanRuleException("The holding period is out of range.");
		calls.add("saveSettings " + settings.holdDays() + " " + settings.cacheGb() + " " + settings.transfers());
		this.settings = settings;
	}

	private void requireOn() throws HealdanRuleException {
		if (!on)
			throw new HealdanRuleException(OFF);
	}

	private void refuseIfAsked() throws HealdanRuleException {
		if (refusal != null) {
			String refused = refusal;
			refusal = null;
			throw new HealdanRuleException(refused);
		}
	}

	private BackendDetail row(long id) throws HealdanRuleException {
		BackendDetail row = backends.get(id);
		if (row == null)
			throw new HealdanRuleException(NO_BACKEND);
		return row;
	}

	private BackendDetail encrypted(long id) throws HealdanRuleException {
		BackendDetail row = row(id);
		if (!row.encrypted())
			throw new HealdanRuleException("Backend " + row.name() + " isn't encrypted, so it has no recovery key.");
		return row;
	}

	/** @return whether a secret was given, and whether it was the test's: never its value */
	private static String given(String secret) {
		if (secret == null)
			return null;
		return SECRET.equals(secret) ? "the secret" : "another secret";
	}

	/** @return the backend with its Stores, live and in the Trash */
	private static BackendDetail with(BackendDetail b, List<String> stores, List<String> storesInTrash) {
		return new BackendDetail(b.id(), b.name(), b.kind(), b.original(), b.path(), b.endpoint(), b.region(),
				b.bucket(), b.prefix(), b.pathStyle(), b.accessKey(), b.hasSecret(), b.encrypted(), b.keyConfirmed(),
				b.offered(), b.inUse(), b.files(), b.bytes(), b.status(), b.error(), b.since(), b.keyLastShown(),
				b.keyLastShownBy(), stores, storesInTrash);
	}
}
