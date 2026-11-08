// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
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
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.BackendTest;
import com.logicaldoc.healdan.storage.Backends;
import com.logicaldoc.healdan.storage.HeldCopies;
import com.logicaldoc.healdan.storage.ReadCache;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageEvent;
import com.logicaldoc.healdan.storage.StorageEventDAO;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreBackend;
import com.logicaldoc.healdan.storage.StoreBackendDAO;
import com.logicaldoc.healdan.storage.StoreOf;
import com.logicaldoc.healdan.storage.StoreStats;
import com.logicaldoc.healdan.storage.StoreTracking;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.StoredFiles.Usage;
import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.Endpoints;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.backend.S3Settings;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.crypto.KeyCheck;
import com.logicaldoc.healdan.storage.crypto.RecoveryKey;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.util.spring.Context;

/**
 * The Storage tab's rules (spec sections 8 to 11, call 23), apart from its servlet, which checks the administrator:
 * <ul>
 * <li>a backend is checked whole before anything is written, and an add or a move of an empty backend's place that
 * fails part-way is undone, so the place is free to try again;</li>
 * <li>an add tests the settings, saves the row, writes the marker (refusing another backend's or install's), makes an
 * encrypted backend's key file and check object, and tests the backend in full; an encrypted one isn't offered to
 * Stores until its recovery key's last group is typed back;</li>
 * <li>backends never share a place: an S3 bucket and prefix that equal or hold another's on the same host, or a
 * folder that is, holds or is inside Original's, staging's, the cache's, the configuration folder, the key files' or
 * another backend's;</li>
 * <li>the bucket, prefix and path can't change once a file is recorded or held on the backend, and encryption is
 * chosen when the backend is added, as its recovery key is shown then;</li>
 * <li>a save, a confirmed or entered recovery key, a removal and Test drop the backend's built entry
 * ({@link Backends#forget}), so the next use builds it from its row and key file as they are then;</li>
 * <li>a Store switch checks the Store's rows first, writes its row in a short transaction of its own, and wakes the
 * mover once that has committed.</li>
 * </ul>
 * The S3 secret is sealed with {@link SecretBox} and never logged, answered or put in a message, nor is a key or a
 * recovery key, but for {@link #showRecoveryKey}'s answer to an administrator, which is recorded and logged. Backend
 * changes, a Store's backend changing, and each showing and entering of a recovery key are logged at info.
 */
@Component
public class StorageAdmin {

	private static final Logger log = LoggerFactory.getLogger(StorageAdmin.class);

	/**
	 * The longest S3 prefix (as stored, with its last {@code /}) and local path: a held location,
	 * {@code hd_storage_held.hd_location}, then always fits (pre-flight 4.5)
	 */
	static final int MAX_PLACE = 255;

	/** The longest name, as {@code hd_name} holds */
	static final int MAX_NAME = 100;

	static final int MAX_ENDPOINT = 1000;

	static final int MAX_REGION = 100;

	static final int MAX_BUCKET = 255;

	static final int MAX_ACCESS_KEY = 255;

	/** The longest S3 secret, in the bytes that are sealed: its sealed value fits {@code hd_secret} */
	static final int MAX_SECRET_BYTES = 1000;

	/** The most stuck files listed at once */
	static final int MAX_STUCK = 1000;

	/** The id an S3 backend not saved yet is built with, for Test before saving */
	private static final long UNSAVED = 0L;

	/** The secret key file is missing while values are sealed with it, so a typed secret is refused (M4 R37) */
	public static final class SecretKeyMissing extends HealdanRuleException {

		private static final long serialVersionUID = 1L;

		private final long sealed;

		SecretKeyMissing(long sealed, Throwable cause) {
			super(("Healdan's secret key file is missing, and %s sealed with it. Restore it from a backup and save "
					+ "again, or make a new key.").formatted(
							sealed == 1 ? "1 stored value is" : sealed + " stored values are"), cause);
			this.sealed = sealed;
		}

		/** @return how many stored values are sealed with the missing key */
		public long sealed() {
			return sealed;
		}
	}

	/**
	 * A backend's settings as the tab sent them, each one checked.
	 *
	 * @param root a local folder's path, absolute; null for S3
	 * @param s3 an S3 backend's settings; null for a local folder
	 * @param secret the S3 secret as typed, stripped; null to keep the stored one
	 */
	private record Draft(String kind, String name, Path root, S3Settings s3, boolean encrypted, String secret) {

		boolean isS3() {
			return s3 != null;
		}

		/** @return where it is, for the log */
		String where() {
			if (!isS3())
				return root.toString();
			return s3.endpoint() + ", bucket " + s3.bucket() + (s3.prefix().isEmpty() ? "" : " under " + s3.prefix());
		}
	}

	private final StorageBackendDAO backends;

	private final StoreBackendDAO stores;

	private final StorageEventDAO events;

	private final StoredFiles files;

	private final HeldCopies held;

	private final Backends built;

	private final BackendKeys keys;

	private final BackendHealth health;

	private final StorageSettings settings;

	private final StoreTracking tracking;

	private final StoreOf storeOf;

	private final Mover mover;

	private final SecretBox box;

	/** A short transaction of its own, for the Store switch */
	private final TransactionTemplate own;

	@Autowired
	public StorageAdmin(StorageBackendDAO backends, StoreBackendDAO stores, StorageEventDAO events, StoredFiles files,
			HeldCopies held, Backends built, BackendKeys keys, BackendHealth health, StorageSettings settings,
			StoreTracking tracking, StoreOf storeOf, Mover mover, SecretBox box,
			PlatformTransactionManager transactions) {
		this.backends = backends;
		this.stores = stores;
		this.events = events;
		this.files = files;
		this.held = held;
		this.built = built;
		this.keys = keys;
		this.health = health;
		this.settings = settings;
		this.tracking = tracking;
		this.storeOf = storeOf;
		this.mover = mover;
		this.box = box;
		this.own = new TransactionTemplate(transactions);
		this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @return the bean
	 */
	public static StorageAdmin get() {
		return Context.get(StorageAdmin.class);
	}

	/**
	 * @return the backends, Original first, the Stores by name, the files with no Store, the settings, and whether CE
	 *         keeps its files through Healdan's storage at all
	 * @throws PersistenceException error at data layer
	 */
	public GUIStorageOverview overview() throws PersistenceException {
		GUIStorageOverview overview = new GUIStorageOverview();
		overview.setOn(StoreTracking.active());

		List<StorageBackend> rows = rows();
		Map<Long, Usage> usage = files.usageByBackend();
		Map<Long, Long> heldOn = held.countByBackend();
		List<GUIStorageBackend> list = new ArrayList<>();
		Map<Long, String> names = new HashMap<>();
		for (StorageBackend row : rows) {
			list.add(toGUI(row, usage, heldOn));
			names.put(row.getId(), row.getName());
		}
		overview.setBackends(list.toArray(GUIStorageBackend[]::new));

		Map<Long, StoreStats> stats = new HashMap<>();
		for (StoreStats store : files.statsByStore())
			stats.put(store.storeId(), store);
		Map<Long, Long> mapped = new HashMap<>();
		for (StoreBackend row : stores.findAll())
			if (row.getDeleted() == 0)
				mapped.put(row.getStoreId(), row.getBackendId());
		List<GUIStoreBackend> storeList = new ArrayList<>();
		for (Folder store : FolderDAO.get().findWorkspaces(Tenant.DEFAULT_ID)) {
			if (store.getId() == Folder.DEFAULTWORKSPACEID)
				continue;
			GUIStoreBackend gui = new GUIStoreBackend();
			gui.setStoreId(store.getId());
			gui.setName(store.getName());
			long backendId = mapped.getOrDefault(store.getId(), StorageBackend.ORIGINAL);
			gui.setBackendId(backendId);
			gui.setBackendName(names.get(backendId));
			StoreStats counts = stats.get(store.getId());
			if (counts != null) {
				gui.setFiles(counts.files());
				gui.setSize(counts.size());
				gui.setWaiting(counts.waiting());
				gui.setWaitingSize(counts.waitingSize());
				gui.setStuck(counts.stuck());
			}
			storeList.add(gui);
		}
		overview.setStores(storeList.toArray(GUIStoreBackend[]::new));

		StoreStats other = stats.get(null);
		if (other != null) {
			overview.setOtherWaiting(other.waiting());
			overview.setOtherWaitingSize(other.waitingSize());
			overview.setOtherStuck(other.stuck());
		}
		overview.setHoldDays(settings.holdDays());
		overview.setCacheGb(settings.cacheGb());
		overview.setTransfers(settings.transfers());
		return overview;
	}

	/**
	 * Test before saving (spec section 10.1): a write, a read whole and by a range, a list (S3) and a delete of a test
	 * object, with the settings as typed. A place no backend has yet is tested without its marker, which the add
	 * writes, and a local folder is only probed; a backend's own place is tested with its marker.
	 *
	 * @param backend the settings as typed
	 * @param secret the S3 secret as typed; null or blank, for a backend already added, uses the stored one
	 * @return empty if the test passed; otherwise the problems, a refused setting included
	 * @throws PersistenceException error at data layer
	 */
	public List<String> testNew(GUIStorageBackend backend, String secret) throws PersistenceException {
		if (backend == null)
			throw refuse("The backend is missing.");
		if (backend.getId() == StorageBackend.ORIGINAL)
			return test(StorageBackend.ORIGINAL);
		StorageBackend existing = backend.getId() != 0 ? row(backend.getId()) : null;
		Draft draft;
		try {
			draft = draft(backend, secret, existing);
			checkPlace(draft, existing == null ? 0 : existing.getId());
		} catch (HealdanRuleException e) {
			return List.of(e.getMessage());
		}
		return trial(draft, existing, existing != null && samePlace(existing, draft));
	}

	/**
	 * Adds a backend (spec section 11): Test on the settings, the row saved, the marker written, for an encrypted
	 * backend its key file and check object, and the full Test, the key check included. A step that fails undoes the
	 * ones before it. An encrypted backend isn't offered to Stores until {@link #confirmRecoveryKey}.
	 *
	 * @param backend the settings
	 * @param secret the S3 secret
	 * @param by the administrator
	 * @return the backend as added, with its recovery key if it is encrypted: the only answer that carries it
	 * @throws PersistenceException a {@link HealdanRuleException} saying why it was refused; a {@link SecretKeyMissing},
	 *         with nothing written, if the secret can't be sealed for want of the secret key file
	 */
	public synchronized GUIStorageBackend add(GUIStorageBackend backend, String secret, User by)
			throws PersistenceException {
		if (backend == null)
			throw refuse("The backend is missing.");
		Draft draft = draft(backend, secret, null);
		checkName(draft.name(), 0);
		checkPlace(draft, 0);
		// Before anything is written: a missing secret key file is the administrator's question
		String sealed = draft.isS3() ? seal(draft.secret(), backend.isNewKey()) : null;
		List<String> problems = trial(draft, null, false);
		if (!problems.isEmpty())
			throw refuse("Test failed, so the backend wasn't added: " + String.join(" ", problems));

		StorageBackend row = new StorageBackend();
		row.setTenantId(by.getTenantId());
		apply(row, draft);
		row.setSecret(sealed);
		row.setKeyConfirmedOn(false);
		backends.store(row);
		long id = row.getId();

		Placed placed = null;
		byte[] key = null;
		try {
			placed = place(draft, id, draft.secret());
			if (draft.encrypted()) {
				key = keys.make(id);
				placed.writeCheck(new FileCipher(key));
			}
			built.forget(id);
			BackendTest.run(built.entry(id), keys, true);
		} catch (IOException | RuntimeException e) {
			undoAdd(id, placed, key != null);
			throw refuse("The backend wasn't added: " + message(e));
		} finally {
			if (placed != null)
				placed.close();
		}
		health.check(id);
		log.info("Storage backend {} ({}, {}{}) was added by {}", id, row.getName(), draft.where(),
				draft.encrypted() ? ", encrypted" : "", by.getUsername());

		GUIStorageBackend answer = toGUI(row);
		if (key != null)
			answer.setRecoveryKey(RecoveryKey.format(key));
		return answer;
	}

	/** Undoes an add that failed part-way, so its place and name are free to try again */
	private void undoAdd(long id, Placed placed, boolean keyMade) {
		if (placed != null)
			placed.undo();
		if (keyMade)
			try {
				keys.delete(id);
			} catch (IOException e) {
				log.warn("The key file of storage backend {}, whose add failed, wasn't deleted: {}", id, e.getMessage());
			}
		try {
			backends.remove(id);
		} catch (PersistenceException | RuntimeException e) {
			log.error("Storage backend {}, whose add failed, wasn't deleted: {}", id, e.getMessage(), e);
		}
		built.forget(id);
	}

	/**
	 * Confirms an encrypted backend's recovery key, which lets Stores use the backend.
	 *
	 * @param backendId the backend
	 * @param lastGroup its recovery key's last group, as typed
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if it isn't the key's last group
	 */
	public synchronized void confirmRecoveryKey(long backendId, String lastGroup, User by) throws PersistenceException {
		StorageBackend row = encrypted(backendId);
		byte[] key = keys.read(backendId).orElseThrow(() -> refuse("The key file of this backend is missing, so its "
				+ "recovery key can't be confirmed: enter the recovery key to put it back."));
		if (!RecoveryKey.isLastGroup(key, lastGroup))
			throw refuse("That isn't the last group of this backend's recovery key: type its last four characters, as "
					+ "it was shown.");
		if (!row.isKeyConfirmedOn()) {
			row.setKeyConfirmedOn(true);
			backends.store(row);
			log.info("The recovery key of storage backend {} ({}) was confirmed by {}", backendId, row.getName(),
					by.getUsername());
		}
		built.forget(backendId);
	}

	/**
	 * Saves a backend's settings: its name, and for S3 the endpoint, region, access key, secret and path style. While
	 * nothing is recorded or held on it, its bucket, prefix or path may change too, and its new place gets its marker,
	 * and its check object, before the row points there.
	 *
	 * @param backend the settings; Original's name alone is taken
	 * @param secret a new S3 secret; null or blank keeps the stored one
	 * @param by the administrator
	 * @return the backend as saved
	 * @throws PersistenceException a {@link HealdanRuleException} saying why it was refused; a {@link SecretKeyMissing},
	 *         with nothing saved, as for {@link #add}
	 */
	public synchronized GUIStorageBackend save(GUIStorageBackend backend, String secret, User by)
			throws PersistenceException {
		if (backend == null)
			throw refuse("The backend is missing.");
		StorageBackend row = row(backend.getId());
		long id = row.getId();
		if (id == StorageBackend.ORIGINAL)
			return renameOriginal(row, backend.getName(), by);

		Draft draft = draft(backend, secret, row);
		checkName(draft.name(), id);
		if (draft.encrypted() != row.isEncryptedOn())
			throw refuse("A backend's encryption is chosen when it is added, as its recovery key is shown then. To "
					+ "change it, remove the backend while it is empty and add it again.");
		boolean moves = !samePlace(row, draft);
		if (moves && inUse(id))
			throw refuse(moved(row, draft) + " can't change once files are recorded or held on the backend.");
		checkPlace(draft, id);
		String sealed = draft.secret() != null ? seal(draft.secret(), backend.isNewKey()) : null;
		List<String> changes = changes(row, draft, sealed != null);

		Placed placed = null;
		Backend was = null;
		if (moves) {
			byte[] key = row.isEncryptedOn() ? keys.read(id).orElseThrow(() -> refuse("The key file of this backend is "
					+ "missing, so its new place can't get its check object: enter its recovery key first.")) : null;
			was = formerPlace(id);
			try {
				placed = place(draft, id, plainSecret(draft, row));
				if (key != null)
					placed.writeCheck(new FileCipher(key));
			} catch (IOException | PersistenceException | RuntimeException e) {
				if (placed != null) {
					placed.undo();
					placed.close();
				}
				built.release(was);
				throw refuse("The backend wasn't saved: " + message(e));
			}
		}
		try {
			apply(row, draft);
			if (sealed != null)
				row.setSecret(sealed);
			backends.store(row);
		} catch (PersistenceException | RuntimeException e) {
			if (placed != null)
				placed.undo();
			built.release(was);
			throw e;
		} finally {
			if (placed != null)
				placed.close();
		}
		if (was != null)
			clearPlace(was, row.isEncryptedOn());
		built.forget(id);
		health.check(id);
		log.info("Storage backend {} ({}) was saved by {}{}", id, row.getName(), by.getUsername(),
				changes.isEmpty() ? ", unchanged" : ": " + String.join(", ", changes) + " changed");
		return toGUI(row);
	}

	private GUIStorageBackend renameOriginal(StorageBackend row, String typed, User by) throws PersistenceException {
		String name = text(typed, "name", MAX_NAME);
		checkName(name, StorageBackend.ORIGINAL);
		if (!name.equals(row.getName())) {
			String was = row.getName();
			row.setName(name);
			backends.store(row);
			log.info("Storage backend {} ({}) was renamed {} by {}", row.getId(), was, name, by.getUsername());
		}
		built.forget(row.getId());
		health.check(row.getId());
		return toGUI(row);
	}

	/** @return the backend's place before a move, to clear once the row points elsewhere; null if it can't be built */
	private Backend formerPlace(long id) {
		try {
			return built.withoutKey(id);
		} catch (BackendFailingException e) {
			log.warn("Storage backend {}'s former place can't be reached, so its marker stays there: {}", id,
					e.getMessage());
			return null;
		}
	}

	/**
	 * Test of a saved backend in full, as its row and key file now are, its marker and key included: the built entry is
	 * dropped first, so a key file deleted or swapped since fails it (pre-flight 1.16), and the outcome is recorded in
	 * its health, or the tab would show OK while Test says the key file is gone.
	 *
	 * @param backendId the backend
	 * @return empty if it passed; otherwise the problems
	 * @throws PersistenceException error at data layer
	 */
	public List<String> test(long backendId) throws PersistenceException {
		row(backendId);
		built.forget(backendId);
		List<String> problems = new ArrayList<>();
		try {
			BackendTest.run(built.entry(backendId), keys, true);
		} catch (BackendFailingException e) {
			problems.add(message(e));
		}
		BackendHealth.Status status = health.check(backendId);
		if (problems.isEmpty() && status.state() == BackendHealth.State.FAILING)
			problems.add(status.error());
		return problems;
	}

	/**
	 * Shows an encrypted backend's recovery key, from its key file as it is now. Each showing is recorded
	 * ({@code key-shown}, question 8) before the key is answered, and logged with the administrator and the backend,
	 * never the key.
	 *
	 * @param backendId the backend
	 * @param by the administrator it is shown to
	 * @return the recovery key, in its groups
	 * @throws PersistenceException a {@link HealdanRuleException} if the backend isn't encrypted or its key file is
	 *         missing
	 */
	public String showRecoveryKey(long backendId, User by) throws PersistenceException {
		StorageBackend row = encrypted(backendId);
		byte[] key = keys.read(backendId).orElseThrow(() -> refuse("The key file of this backend is missing or can't be "
				+ "read, so there is no key to show: enter its recovery key to put the file back."));
		record(backendId, StorageEvent.KEY_SHOWN, by);
		log.info("The recovery key of storage backend {} ({}) was shown to {}", backendId, row.getName(),
				by.getUsername());
		return RecoveryKey.format(key);
	}

	/**
	 * Rebuilds an encrypted backend's key file from its recovery key (spec section 9). The key typed must decrypt the
	 * backend's check object, read before anything is written, so a mistyped or another backend's key can't replace
	 * the file. Recorded ({@code key-entered}) and logged, never with the key.
	 *
	 * @param backendId the backend
	 * @param typed the recovery key as typed
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if the key isn't the backend's, or can't be checked
	 */
	public synchronized void enterRecoveryKey(long backendId, String typed, User by) throws PersistenceException {
		StorageBackend row = encrypted(backendId);
		byte[] key;
		try {
			key = RecoveryKey.parse(typed);
		} catch (IllegalArgumentException e) {
			// Its message never holds what was typed
			throw refuse("That isn't a recovery key: " + e.getMessage() + ".");
		}
		Backend backend;
		try {
			backend = built.withoutKey(backendId);
		} catch (BackendFailingException e) {
			throw refuse("The backend can't be used, so the recovery key can't be checked and wasn't put back: "
					+ message(e));
		}
		try {
			if (!KeyCheck.verify(backend, LocalBackend.CHECK, new FileCipher(key)))
				throw refuse("That recovery key isn't this backend's: its check object doesn't decrypt with it, so "
						+ "nothing was written. Check the key, and type it again.");
		} catch (FileNotFoundException e) {
			throw refuse("The backend's check object is missing, so the recovery key can't be checked against it, and "
					+ "nothing was written.");
		} catch (IOException e) {
			throw refuse("The backend's check object can't be read, so the recovery key wasn't put back: " + message(e));
		} finally {
			built.release(backend);
		}
		try {
			keys.restore(backendId, key);
		} catch (IOException e) {
			throw refuse("The recovery key is right, but its key file couldn't be written: " + e.getMessage());
		}
		record(backendId, StorageEvent.KEY_ENTERED, by);
		log.info("The recovery key of storage backend {} ({}) was entered by {}, and its key file put back", backendId,
				row.getName(), by.getUsername());
		built.forget(backendId);
		health.check(backendId);
	}

	/**
	 * @param backendId a backend, which may have been removed since: its record is kept
	 * @return every showing and entering of its recovery key, newest first
	 * @throws PersistenceException error at data layer
	 */
	public List<GUIStorageEvent> keyEvents(long backendId) throws PersistenceException {
		List<GUIStorageEvent> list = new ArrayList<>();
		for (StorageEvent event : events.findByBackend(backendId)) {
			GUIStorageEvent gui = new GUIStorageEvent();
			gui.setBackendId(event.getBackendId());
			gui.setEvent(event.getEvent());
			gui.setDate(event.getDate());
			gui.setUserId(event.getUserId());
			gui.setUserName(event.getUserName());
			list.add(gui);
		}
		Collections.reverse(list);
		return list;
	}

	/**
	 * Removes a backend (spec section 8): only once no Store uses it and no file is recorded or held on it. Its key
	 * file goes, its row is deleted for good, so its sealed secret leaves the database, and its marker and check object
	 * are deleted, so another backend may have its place.
	 *
	 * @param backendId the backend; never Original
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} saying why it can't be removed
	 */
	public synchronized void remove(long backendId, User by) throws PersistenceException {
		if (backendId == StorageBackend.ORIGINAL)
			throw refuse("Original can be renamed, but not removed: it keeps the files of every Store that has no "
					+ "other backend.");
		StorageBackend row = row(backendId);
		List<String> using = new ArrayList<>();
		for (StoreBackend store : stores.findAll())
			if (store.getDeleted() == 0 && store.getBackendId() == backendId)
				using.add(storeName(store.getStoreId()));
		if (!using.isEmpty())
			throw refuse("%s %s its files on this backend: give %s another backend first.".formatted(
					(using.size() == 1 ? "Store " : "Stores ") + String.join(", ", using),
					using.size() == 1 ? "keeps" : "keep", using.size() == 1 ? "it" : "them"));
		long recorded = files.countOn(backendId);
		if (recorded > 0)
			throw refuse("%s on this backend: it can be removed once they have moved off it.".formatted(
					recorded == 1 ? "1 file is recorded" : recorded + " files are recorded"));
		long heldOn = held.countOn(backendId);
		if (heldOn > 0)
			throw refuse(("%s held on this backend until the holding period ends: it can be removed once the purge has "
					+ "deleted them.").formatted(heldOn == 1 ? "1 copy is" : heldOn + " copies are"));

		Backend place = formerPlace(backendId);
		try {
			keys.delete(backendId);
		} catch (IOException e) {
			built.release(place);
			throw refuse("The backend's key file couldn't be deleted, so the backend wasn't removed: " + e.getMessage());
		}
		try {
			backends.remove(backendId);
		} catch (PersistenceException | RuntimeException e) {
			built.release(place);
			throw e;
		}
		built.forget(backendId);
		if (place != null)
			clearPlace(place, row.isEncryptedOn());
		log.info("Storage backend {} ({}) was removed by {}", backendId, row.getName(), by.getUsername());
	}

	/**
	 * @param storeId a Store
	 * @param backendId the backend it would keep its files on
	 * @return how many of its files would move, and their size in bytes
	 * @throws PersistenceException a {@link HealdanRuleException} if either isn't there
	 */
	public long[] previewSwitch(long storeId, long backendId) throws PersistenceException {
		store(storeId);
		row(backendId);
		Usage moving = files.toMove(storeId, backendId);
		return new long[] { moving.files(), moving.size() };
	}

	/**
	 * Gives a Store another backend (spec section 5.4). The Store's rows are checked first
	 * ({@link StoreTracking#checkStore}); then its {@code hd_storage_store} row is written, or deleted to go back to
	 * Original, with its rows' waiting marks set or cleared ({@link StoredFiles#storeBackendChanged}), in a short
	 * transaction of its own, as the mover's commits read that row and on MariaDB would wait for a long one; then the
	 * mover is woken.
	 *
	 * @param storeId the Store
	 * @param backendId the backend, which must be offered to Stores
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if it can't
	 */
	public synchronized void setStoreBackend(long storeId, long backendId, User by) throws PersistenceException {
		Folder store = store(storeId);
		StorageBackend row = row(backendId);
		if (row.isEncryptedOn() && !row.isKeyConfirmedOn())
			throw refuse("The recovery key of backend " + row.getName() + " isn't confirmed yet, so no Store can use "
					+ "it: confirm its recovery key first.");
		long was = files.storeBackend(storeId);
		if (was == backendId)
			return;

		tracking.checkStore(storeId);
		try {
			own.executeWithoutResult(status -> {
				try {
					Optional<StoreBackend> mapped = stores.findByStore(storeId);
					if (backendId == StorageBackend.ORIGINAL) {
						if (mapped.isPresent())
							stores.delete(mapped.get().getId());
					} else {
						StoreBackend m = mapped.orElseGet(StoreBackend::new);
						m.setTenantId(store.getTenantId());
						m.setStoreId(storeId);
						m.setBackendId(backendId);
						stores.store(m);
					}
					files.storeBackendChanged(storeId);
				} catch (PersistenceException e) {
					throw new Unchecked(e);
				}
			});
		} catch (Unchecked e) {
			throw e.cause;
		}
		mover.wake();
		log.info("Store {} ({}) keeps its files on storage backend {} ({}) from now on, not {}, as {} set it; they move "
				+ "in the background", storeId, store.getName(), backendId, row.getName(), was, by.getUsername());
	}

	/** A checked exception through a transaction's callback */
	private static final class Unchecked extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final PersistenceException cause;

		Unchecked(PersistenceException cause) {
			super(cause);
			this.cause = cause;
		}
	}

	/**
	 * @param storeId a Store; 0 for the files with no Store
	 * @return its files the mover gave up on, the newest documents first, at most {@value #MAX_STUCK}
	 * @throws PersistenceException error at data layer
	 */
	public List<GUIStuckFile> stuck(long storeId) throws PersistenceException {
		List<StoredFile> rows = files.stuck(storeId == 0 ? null : storeId, MAX_STUCK);
		Set<Long> ids = new HashSet<>();
		for (StoredFile row : rows)
			ids.add(row.docId());
		Map<Long, String> names = new HashMap<>();
		for (Document document : DocumentDAO.get().findByIds(ids, null))
			names.put(document.getId(), document.getFileName());
		List<GUIStuckFile> list = new ArrayList<>();
		for (StoredFile row : rows) {
			GUIStuckFile gui = new GUIStuckFile();
			gui.setDocId(row.docId());
			gui.setFileName(names.get(row.docId()));
			gui.setResource(row.resource());
			gui.setAttempts(row.attempts());
			gui.setLastError(row.lastError());
			list.add(gui);
		}
		return list;
	}

	/**
	 * Puts a Store's stuck files back to waiting, and wakes the mover.
	 *
	 * @param storeId a Store; 0 for the files with no Store
	 * @param by the administrator
	 */
	public void retry(long storeId, User by) {
		log.info("{} retried the stuck files of {}", by.getUsername(), storeId == 0 ? "no Store" : "Store " + storeId);
		mover.retry(storeId);
	}

	/**
	 * Saves the storage settings, each checked before any is written; the transfers apply at once.
	 *
	 * @param holdDays the holding period, in days
	 * @param cacheGb the read cache's size, in GB
	 * @param transfers how many files move at once
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if one is out of range
	 */
	public synchronized void saveSettings(int holdDays, int cacheGb, int transfers, User by)
			throws PersistenceException {
		if (holdDays < 1 || holdDays > StorageSettings.MAX_HOLD_DAYS)
			throw refuse("The holding period must be from 1 to %d days.".formatted(StorageSettings.MAX_HOLD_DAYS));
		if (cacheGb < 1 || cacheGb > StorageSettings.MAX_CACHE_GB)
			throw refuse("The read cache must be from 1 to %d GB.".formatted(StorageSettings.MAX_CACHE_GB));
		if (transfers < 1 || transfers > StorageSettings.MAX_TRANSFERS)
			throw refuse("Parallel transfers must be from 1 to %d.".formatted(StorageSettings.MAX_TRANSFERS));
		settings.setHoldDays(holdDays);
		settings.setCacheGb(cacheGb);
		settings.setTransfers(transfers);
		mover.resize(transfers);
		log.info("The storage settings were saved by {}: copies held {} days, a {} GB read cache, {} transfers at once",
				by.getUsername(), holdDays, cacheGb, transfers);
	}

	// Checking what the tab sent

	/**
	 * @param existing the backend's row, for a save; null for an add
	 * @return the settings, each checked: S3's against the endpoint rule (Global Constraints)
	 */
	private Draft draft(GUIStorageBackend backend, String secret, StorageBackend existing)
			throws HealdanRuleException {
		String kind = StringUtils.strip(backend.getKind());
		if (!StorageBackend.LOCAL.equals(kind) && !StorageBackend.S3.equals(kind))
			throw refuse("Choose the kind of backend: a local folder or S3.");
		if (existing != null && !kind.equals(existing.getKind()))
			throw refuse("A backend's kind can't change.");
		String name = text(backend.getName(), "name", MAX_NAME);
		boolean s3 = StorageBackend.S3.equals(kind);
		// On for S3 unless the administrator turns it off; off for a local folder unless turned on
		boolean encrypted = backend.getEncrypted() != null ? backend.getEncrypted() : s3;
		if (!s3)
			return new Draft(kind, name, root(backend.getPath()), null, encrypted, null);

		S3Settings place = s3Settings(backend);
		String typed = StringUtils.strip(secret);
		if (StringUtils.isEmpty(typed)) {
			if (existing == null || StringUtils.isBlank(existing.getSecret()))
				throw refuse("The secret can't be empty.");
			typed = null;
		} else if (typed.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES) {
			throw refuse(("The secret can't be longer than %d characters, or fewer in a script that takes more than one "
					+ "byte a character.").formatted(MAX_SECRET_BYTES));
		}
		return new Draft(kind, name, null, place, encrypted, typed);
	}

	/** @return the text, stripped, which the database can hold */
	private static String text(String value, String what, int max) throws HealdanRuleException {
		String text = StringUtils.strip(value);
		if (StringUtils.isEmpty(text))
			throw refuse("The %s can't be empty.".formatted(what));
		if (text.length() > max)
			throw refuse("The %s can't be longer than %d characters.".formatted(what, max));
		// MariaDB's utf8 tables hold no character beyond the Basic Multilingual Plane (R33)
		if (!BasicPlane.holds(text))
			throw refuse("The %s can't contain emoji or other characters the database can't store.".formatted(what));
		return text;
	}

	/** @return a local folder's path as typed, absolute and normalised, as it is stored */
	private static Path root(String typed) throws HealdanRuleException {
		String text = text(typed, "path", Integer.MAX_VALUE);
		Path path;
		try {
			path = Path.of(text);
		} catch (InvalidPathException e) {
			throw refuse("The path isn't a folder's path: " + e.getReason() + ".");
		}
		if (!path.isAbsolute())
			throw refuse("The path must be the folder's whole path in the container, from /, such as /mnt/archive.");
		path = path.normalize();
		if (path.toString().length() > MAX_PLACE)
			throw refuse("The path can't be longer than %d characters.".formatted(MAX_PLACE));
		return path;
	}

	/** @return an S3 backend's place, checked as {@link S3Backend} checks it when it is built */
	private static S3Settings s3Settings(GUIStorageBackend backend) throws HealdanRuleException {
		String endpoint = text(backend.getEndpoint(), "endpoint", MAX_ENDPOINT);
		String region = text(backend.getRegion(), "region", MAX_REGION);
		String bucket = text(backend.getBucket(), "bucket", MAX_BUCKET);
		String prefix = S3Settings.prefix(backend.getPrefix());
		if (prefix.length() > MAX_PLACE)
			throw refuse("The prefix can't be longer than %d characters, with the / it is kept with at its end."
					.formatted(MAX_PLACE));
		if (!BasicPlane.holds(prefix))
			throw refuse("The prefix can't contain emoji or other characters the database can't store.");
		String accessKey = text(backend.getAccessKey(), "access key", MAX_ACCESS_KEY);
		S3Settings settings;
		try {
			settings = new S3Settings(new URI(endpoint), region, bucket, prefix, backend.isPathStyle(), accessKey);
		} catch (URISyntaxException e) {
			throw refuse("The endpoint isn't an address, such as https://s3.example.com.");
		} catch (IllegalArgumentException e) {
			throw refuse(e.getMessage() + ".");
		}
		try {
			S3Backend.checkEndpoint(settings);
		} catch (Endpoints.HostNotFound e) {
			throw refuse(e.getMessage() + ", so its addresses can't be checked: http:// is only for an address on a "
					+ "private network.");
		} catch (IllegalArgumentException e) {
			throw refuse(e.getMessage() + ".");
		}
		return settings;
	}

	/** Refuses a name another backend has, whatever its case */
	private void checkName(String name, long id) throws PersistenceException {
		for (StorageBackend other : rows())
			if (other.getId() != id && name.equalsIgnoreCase(StringUtils.strip(other.getName())))
				throw refuse("Another backend is called " + other.getName() + ": give this one a name of its own.");
	}

	/**
	 * Backends can't share a place (call 23): an S3 bucket and prefix can't equal or hold another's on the same host
	 * (an empty prefix holds every other), and a folder can't be, hold or sit inside Original's, staging's, the
	 * cache's, the configuration folder, the key files' folder, or another backend's.
	 *
	 * @param id the backend's own id, which isn't compared with itself; 0 for one not added yet
	 */
	private void checkPlace(Draft draft, long id) throws PersistenceException {
		for (StorageBackend other : rows()) {
			if (other.getId() == id)
				continue;
			if (draft.isS3() && StorageBackend.S3.equals(other.getKind()) && sharesBucket(draft.s3(), other))
				throw refuse("This bucket and prefix share their place with backend " + other.getName()
						+ ": two backends can't keep files in one place, so neither prefix may hold the other's. "
						+ "Choose another prefix, or another bucket.");
			if (!draft.isS3() && StorageBackend.LOCAL.equals(other.getKind()))
				apart(draft.root(), other.getId() == StorageBackend.ORIGINAL ? "Original's folder"
						: "the folder of backend " + other.getName(), folder(other));
		}
		if (!draft.isS3()) {
			apart(draft.root(), "staging's folder", Staging.folder());
			apart(draft.root(), "the read cache's folder", ReadCache.folder());
			apart(draft.root(), "the configuration folder", BackendKeys.configurationFolder());
			apart(draft.root(), "the storage key files' folder", keys.folder().toAbsolutePath().normalize());
		}
	}

	/** @return whether the settings' place equals or holds the row's, or sits inside it, on the same host */
	private static boolean sharesBucket(S3Settings mine, StorageBackend other) {
		String host;
		try {
			host = URI.create(StringUtils.trimToEmpty(other.getEndpoint())).getHost();
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (host == null || !host.equalsIgnoreCase(mine.endpoint().getHost())
				|| !StringUtils.strip(StringUtils.defaultString(other.getBucket())).equalsIgnoreCase(mine.bucket()))
			return false;
		String theirs = S3Settings.prefix(other.getPrefix());
		return mine.prefix().startsWith(theirs) || theirs.startsWith(mine.prefix());
	}

	private static void apart(Path folder, String otherName, Path other) throws HealdanRuleException {
		if (other == null)
			return;
		Path theirs = other.toAbsolutePath().normalize();
		if (folder.startsWith(theirs) || theirs.startsWith(folder))
			throw refuse("The folder " + folder + " can't be, hold or sit inside " + otherName + " (" + theirs
					+ "): give the backend a folder of its own.");
	}

	/** @return a local backend's folder, absolute; null for one without */
	private static Path folder(StorageBackend row) {
		if (row.getId() == StorageBackend.ORIGINAL)
			return Staging.original();
		if (StringUtils.isBlank(row.getPath()))
			return null;
		try {
			return Path.of(row.getPath().trim()).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** @return whether the draft keeps the row's bucket and prefix, or its folder */
	private static boolean samePlace(StorageBackend row, Draft draft) {
		if (draft.isS3())
			return draft.s3().bucket().equals(StringUtils.strip(row.getBucket()))
					&& draft.s3().prefix().equals(S3Settings.prefix(row.getPrefix()));
		return draft.root().equals(folder(row));
	}

	/** @return what of the place a draft changes, for the refusal */
	private static String moved(StorageBackend row, Draft draft) {
		if (!draft.isS3())
			return "The path";
		return draft.s3().bucket().equals(StringUtils.strip(row.getBucket())) ? "The prefix" : "The bucket";
	}

	/** @return the settings a save changes, by name, for the log: never their values, the secret's above all */
	private static List<String> changes(StorageBackend row, Draft draft, boolean secret) {
		List<String> changes = new ArrayList<>();
		if (!draft.name().equals(row.getName()))
			changes.add("its name");
		if (draft.isS3()) {
			S3Settings s3 = draft.s3();
			if (!s3.endpoint().toString().equals(row.getEndpoint()))
				changes.add("the endpoint");
			if (!s3.region().equals(row.getRegion()))
				changes.add("the region");
			if (!s3.bucket().equals(row.getBucket()))
				changes.add("the bucket");
			if (!s3.prefix().equals(row.getPrefix()))
				changes.add("the prefix");
			if (s3.pathStyle() != row.isPathStyleOn())
				changes.add("path style");
			if (!s3.accessKey().equals(row.getAccessKey()))
				changes.add("the access key");
			if (secret)
				changes.add("the secret");
		} else if (!draft.root().toString().equals(row.getPath())) {
			changes.add("the path");
		}
		return changes;
	}

	/** @return whether a file is recorded or held on the backend */
	private boolean inUse(long backendId) {
		return files.countOn(backendId) > 0 || held.countOn(backendId) > 0;
	}

	private static void apply(StorageBackend row, Draft draft) {
		row.setName(draft.name());
		row.setKind(draft.kind());
		row.setEncryptedOn(draft.encrypted());
		if (draft.isS3()) {
			S3Settings s3 = draft.s3();
			row.setPath(null);
			row.setEndpoint(s3.endpoint().toString());
			row.setRegion(s3.region());
			row.setBucket(s3.bucket());
			row.setPrefix(s3.prefix());
			row.setPathStyleOn(s3.pathStyle());
			row.setAccessKey(s3.accessKey());
		} else {
			row.setPath(draft.root().toString());
		}
	}

	/**
	 * Seals a typed secret: with a new key only once the administrator confirmed it, as the key file may be missing
	 * only for a while, and a new key would leave every other sealed value unreadable (M4 ruling R37)
	 */
	private String seal(String secret, boolean newKey) throws HealdanRuleException {
		try {
			return newKey ? box.sealWithNewKey(secret) : box.seal(secret);
		} catch (SecretBox.KeyMissingException e) {
			throw new SecretKeyMissing(e.sealed(), e);
		} catch (IllegalStateException e) {
			// The message holds no secret, and neither does this one
			throw new HealdanRuleException("The secret couldn't be kept, because Healdan couldn't read or make its "
					+ "secret key file. Ask whoever runs the server to check the configuration folder.", e);
		}
	}

	/** @return the S3 secret a draft is built with: the one typed, or the stored one */
	private String plainSecret(Draft draft, StorageBackend existing) throws HealdanRuleException {
		if (!draft.isS3())
			return null;
		if (draft.secret() != null)
			return draft.secret();
		String stored = existing == null ? null : box.open(existing.getSecret());
		if (stored == null)
			throw refuse("The stored secret can't be read, as after a new secret key: type the secret again.");
		return stored;
	}

	// The backend itself

	/**
	 * Test before saving, on a backend built from the draft: a place no backend has yet is tested without its marker
	 * (a local folder only probed), and a backend's own place with it.
	 *
	 * @param existing the backend's row; null for one not added yet
	 * @param samePlace whether the draft keeps the row's place
	 */
	private List<String> trial(Draft draft, StorageBackend existing, boolean samePlace) {
		long id = samePlace ? existing.getId() : UNSAVED;
		try {
			if (!draft.isS3()) {
				if (samePlace)
					BackendTest.run(new LocalBackend(id, settings.installId(), draft.root(), false), true);
				else
					LocalBackend.probe(draft.root());
			} else {
				try (S3Backend s3 = new S3Backend(id, settings.installId(), draft.s3(), plainSecret(draft, existing),
						false)) {
					BackendTest.run(s3, samePlace);
				}
			}
			return List.of();
		} catch (BackendFailingException e) {
			return List.of(samePlace ? message(e) : unsaved(message(e)));
		} catch (HealdanRuleException | IllegalArgumentException e) {
			return List.of(e.getMessage());
		}
	}

	/** @return a message about a backend not saved yet, which names it by the id it was built with, without it */
	private static String unsaved(String message) {
		return message.replaceAll("\\bBackend " + UNSAVED + "\\b", "The backend").replaceAll("\\bbackend " + UNSAVED
				+ "\\b", "the backend");
	}

	/**
	 * A place as an add or a move gave it this backend's marker, and its check object; taken away again if what
	 * follows fails, so the place is free to try again
	 */
	private static final class Placed {

		private final Backend backend;

		private boolean checkWritten;

		Placed(Backend backend) {
			this.backend = backend;
		}

		void writeCheck(FileCipher cipher) throws IOException {
			// Set first: a write that stored the object and then failed on its answer still has it deleted
			checkWritten = true;
			KeyCheck.write(backend, LocalBackend.CHECK, cipher);
		}

		void undo() {
			if (checkWritten)
				try {
					backend.delete(LocalBackend.CHECK);
				} catch (IOException | RuntimeException e) {
					log.warn("Storage backend {}'s check object, written by a change that failed, stays: {}",
							backend.id(), e.getMessage());
				}
			removeMarker(backend);
		}

		void close() {
			closeQuietly(backend);
		}
	}

	/**
	 * Writes this backend's marker at the draft's place: a {@link LocalBackend} or an {@link S3Backend} made with
	 * {@code create}, which refuses a place whose marker names another backend or install.
	 */
	private Placed place(Draft draft, long id, String secret) throws IOException {
		if (draft.isS3())
			return new Placed(new S3Backend(id, settings.installId(), draft.s3(), secret, true));
		try {
			return new Placed(new LocalBackend(id, settings.installId(), draft.root(), true));
		} catch (IllegalStateException | UncheckedIOException e) {
			throw new BackendFailingException(e.getMessage(), e);
		}
	}

	/**
	 * Clears a backend's former place, once nothing points there: its check object and its marker, so another backend
	 * may have it. What fails is logged; the place then keeps them.
	 */
	private void clearPlace(Backend place, boolean encrypted) {
		try {
			if (encrypted)
				place.delete(LocalBackend.CHECK);
		} catch (IOException | RuntimeException e) {
			log.warn("Storage backend {}'s check object stays in its former place: {}", place.id(), e.getMessage());
		}
		removeMarker(place);
		built.release(place);
	}

	/** Deletes a backend's own marker; one that names another backend or install stays */
	private static void removeMarker(Backend backend) {
		try {
			if (backend instanceof LocalBackend local)
				local.removeMarker();
			else if (backend instanceof S3Backend s3)
				s3.removeMarker();
		} catch (IOException | RuntimeException e) {
			log.warn("Storage backend {}'s marker {} stays where it was, so no other backend can have that place until "
					+ "it is deleted by hand: {}", backend.id(), LocalBackend.MARKER, e.getMessage());
		}
	}

	private static void closeQuietly(Backend backend) {
		if (backend instanceof Closeable closeable)
			try {
				closeable.close();
			} catch (IOException | RuntimeException e) {
				log.debug("Storage backend {} didn't close: {}", backend.id(), e.getMessage());
			}
	}

	// Rows and answers

	/** @return every backend's row, Original first */
	private List<StorageBackend> rows() throws PersistenceException {
		List<StorageBackend> rows = new ArrayList<>();
		for (StorageBackend row : backends.findAll())
			if (row.getDeleted() == 0)
				rows.add(row);
		rows.sort(Comparator.comparingLong(StorageBackend::getId));
		return rows;
	}

	private StorageBackend row(long id) throws PersistenceException {
		StorageBackend row = backends.findById(id);
		if (row == null || row.getDeleted() != 0)
			throw refuse("That storage backend doesn't exist. Someone may have removed it meanwhile.");
		return row;
	}

	private StorageBackend encrypted(long id) throws PersistenceException {
		StorageBackend row = row(id);
		if (!row.isEncryptedOn())
			throw refuse("Backend " + row.getName() + " isn't encrypted, so it has no recovery key.");
		return row;
	}

	/** @return the Store's folder */
	private Folder store(long storeId) throws PersistenceException {
		if (!Objects.equals(storeOf.ofFolder(storeId).orElse(null), storeId))
			throw refuse("That isn't a Store.");
		return FolderDAO.get().findById(storeId);
	}

	private String storeName(long storeId) throws PersistenceException {
		Folder store = FolderDAO.get().findById(storeId);
		return store == null ? Long.toString(storeId) : store.getName();
	}

	private void record(long backendId, String event, User by) throws PersistenceException {
		StorageEvent row = new StorageEvent();
		row.setTenantId(by.getTenantId());
		row.setBackendId(backendId);
		row.setEvent(event);
		row.setDate(new Date());
		row.setUserId(by.getId());
		row.setUserName(by.getUsername());
		events.store(row);
	}

	private GUIStorageBackend toGUI(StorageBackend row) throws PersistenceException {
		return toGUI(row, files.usageByBackend(), held.countByBackend());
	}

	private GUIStorageBackend toGUI(StorageBackend row, Map<Long, Usage> usage, Map<Long, Long> heldOn)
			throws PersistenceException {
		long id = row.getId();
		GUIStorageBackend gui = new GUIStorageBackend();
		gui.setId(id);
		gui.setKind(row.getKind());
		gui.setName(row.getName());
		gui.setOriginal(id == StorageBackend.ORIGINAL);
		gui.setPath(id == StorageBackend.ORIGINAL ? Staging.original().toString() : row.getPath());
		gui.setEndpoint(row.getEndpoint());
		gui.setRegion(row.getRegion());
		gui.setBucket(row.getBucket());
		gui.setPrefix(row.getPrefix());
		gui.setPathStyle(row.isPathStyleOn());
		gui.setAccessKey(row.getAccessKey());
		gui.setHasSecret(StorageBackend.S3.equals(row.getKind()) && box.opens(row.getSecret()));
		gui.setEncrypted(row.isEncryptedOn());
		gui.setKeyConfirmed(row.isKeyConfirmedOn());
		gui.setOffered(!row.isEncryptedOn() || row.isKeyConfirmedOn());

		Usage on = usage.getOrDefault(id, new Usage(0, 0));
		gui.setFiles(on.files());
		gui.setSize(on.size());
		gui.setInUse(on.files() > 0 || heldOn.getOrDefault(id, 0L) > 0);

		BackendHealth.Status status = health.status(id);
		gui.setStatus(status.state().name().toLowerCase(Locale.ROOT));
		gui.setError(status.error());
		gui.setSince(status.since());
		gui.setUnreachable(status.unreachable());

		StorageEvent shown = null;
		for (StorageEvent event : events.findByBackend(id))
			if (StorageEvent.KEY_SHOWN.equals(event.getEvent()))
				shown = event;
		if (shown != null) {
			gui.setKeyShown(shown.getDate());
			gui.setKeyShownBy(shown.getUserName());
		}
		return gui;
	}

	private static String message(Exception e) {
		Throwable cause = e instanceof UncheckedIOException && e.getCause() != null ? e.getCause() : e;
		return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
	}

	private static HealdanRuleException refuse(String message) {
		return new HealdanRuleException(message);
	}
}
