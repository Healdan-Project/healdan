// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

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
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.crypto.KeyCheck;
import com.logicaldoc.healdan.storage.crypto.RecoveryKey;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.storage.web.BackendDrafts.Draft;
import com.logicaldoc.util.spring.Context;

/**
 * The Storage tab's operations (spec sections 8 to 11), apart from its servlet, which checks the administrator, and
 * from {@link BackendDrafts}, which checks what the tab sends:
 * <ul>
 * <li>an add tests the settings, saves the row, writes the marker (refusing another backend's or install's), makes an
 * encrypted backend's key file and check object, tests the backend in full, and records the showing of the recovery
 * key it answers; a step that fails undoes the ones before it, so the place is free to try again. An encrypted backend
 * isn't offered to Stores until its recovery key's last group is typed back;</li>
 * <li>the bucket, prefix and path can't change once a file is recorded or held on the backend, or while a Store keeps
 * its files there, and encryption is chosen when the backend is added, as its recovery key is shown then;</li>
 * <li>a save, a confirmed or entered recovery key, a removal and Test drop the backend's built entry
 * ({@link Backends#forget}), so the next use builds it from its row and key file as they are then;</li>
 * <li>a Store switch checks the Store's rows first, writes its row in a short transaction of its own, and wakes the
 * mover once that has committed.</li>
 * </ul>
 * Every write is refused while CE keeps its files in its own store, as every other storage bean then does nothing.
 * Two administrators' writes are serialised only around the checks and writes that must not interleave: no network
 * call is made holding that lock, so a backend that doesn't answer holds up nothing else. What an operation checked
 * before the lock is checked again under it. A backend whose add is still writing its marker, key file and check
 * object, after its row is saved, is left alone by every other write until the add ends.
 * <p>
 * The S3 secret is sealed with {@link SecretBox} and never logged, answered or put in a message, nor is a key or a
 * recovery key, but for {@link #add}'s and {@link #showRecoveryKey}'s answers to an administrator, each recorded and
 * logged as a showing. Backend changes, a Store's backend changing, and each showing and entering of a recovery key
 * are logged at info.
 */
@Component
public class StorageAdmin {

	private static final Logger log = LoggerFactory.getLogger(StorageAdmin.class);

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

	private final BackendDrafts drafts;

	/** A short transaction of its own, for the Store switch and the settings */
	private final TransactionTemplate own;

	/** Held around the checks and writes that must not interleave between two administrators; never over the network */
	private final Object writing = new Object();

	/** The backends whose add is writing their marker, key file and check object, which no other write touches */
	private final Set<Long> adding = ConcurrentHashMap.newKeySet();

	/** Whether CE keeps its files through {@code HealdanStorage}; tests turn it off */
	volatile BooleanSupplier on = StoreTracking::active;

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
		this.drafts = new BackendDrafts(keys.folder());
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
		overview.setOn(on.getAsBoolean());

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
		requireOn();
		if (backend == null)
			throw refuse("The backend is missing.");
		if (backend.getId() == StorageBackend.ORIGINAL)
			return test(StorageBackend.ORIGINAL);
		StorageBackend existing = backend.getId() != 0 ? row(backend.getId()) : null;
		Draft draft;
		try {
			draft = BackendDrafts.draft(backend, secret, existing);
			drafts.checkPlace(draft, existing == null ? 0 : existing.getId(), rows());
		} catch (HealdanRuleException e) {
			return List.of(e.getMessage());
		}
		return trial(draft, existing, existing != null && BackendDrafts.samePlace(existing, draft));
	}

	/**
	 * Adds a backend (spec section 11): Test on the settings, the row saved, the marker written, for an encrypted
	 * backend its key file and check object, and the full Test, the key check included; then, for an encrypted backend,
	 * the showing of its recovery key, which the answer carries, is recorded ({@code key-shown}, question 8) and
	 * logged, as {@link #showRecoveryKey}'s are. A step that fails undoes the ones before it, so no key is answered
	 * without its record. An encrypted backend isn't offered to Stores until {@link #confirmRecoveryKey}. Only the row's
	 * checks and write take the lock: Test, the marker, the key file and the full Test run outside it.
	 *
	 * @param backend the settings
	 * @param secret the S3 secret
	 * @param by the administrator
	 * @return the backend as added, with its recovery key if it is encrypted: the only answer that carries it
	 * @throws PersistenceException a {@link HealdanRuleException} saying why it was refused; a {@link SecretKeyMissing},
	 *         with nothing written, if the secret can't be sealed for want of the secret key file
	 */
	public GUIStorageBackend add(GUIStorageBackend backend, String secret, User by) throws PersistenceException {
		requireOn();
		if (backend == null)
			throw refuse("The backend is missing.");
		Draft draft = BackendDrafts.draft(backend, secret, null);
		// Checked now, to refuse at once, and again under the lock
		checkFree(draft, 0);
		// Before anything is written: a missing secret key file is the administrator's question
		String sealed = draft.isS3() ? seal(draft.secret(), backend.isNewKey()) : null;
		List<String> problems = trial(draft, null, false);
		if (!problems.isEmpty())
			throw refuse("Test failed, so the backend wasn't added: " + String.join(" ", problems));

		StorageBackend row = new StorageBackend();
		row.setTenantId(by.getTenantId());
		BackendDrafts.apply(row, draft);
		row.setSecret(sealed);
		row.setKeyConfirmedOn(false);
		synchronized (writing) {
			checkFree(draft, 0);
			backends.store(row);
			adding.add(row.getId());
		}
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
			// The answer shows the recovery key, often the only time it is shown: recorded once nothing else can fail
			if (key != null)
				record(id, StorageEvent.KEY_SHOWN, by);
		} catch (IOException | PersistenceException | RuntimeException e) {
			undoAdd(id, placed, key != null);
			throw refuse("The backend wasn't added: " + message(e, id));
		} finally {
			if (placed != null)
				placed.close();
			adding.remove(id);
		}
		health.check(id);
		log.info("Storage backend {} ({}, {}{}) was added by {}", id, row.getName(), draft.where(),
				draft.encrypted() ? ", encrypted" : "", by.getUsername());
		if (key != null)
			log.info("The recovery key of storage backend {} ({}) was shown to {}, as it was added", id, row.getName(),
					by.getUsername());

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

	/** Refuses a draft whose name or place another backend has */
	private void checkFree(Draft draft, long id) throws PersistenceException {
		List<StorageBackend> rows = rows();
		BackendDrafts.checkName(draft.name(), id, rows);
		drafts.checkPlace(draft, id, rows);
	}

	/**
	 * Confirms an encrypted backend's recovery key, which lets Stores use the backend.
	 *
	 * @param backendId the backend
	 * @param lastGroup its recovery key's last group, as typed
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if it isn't the key's last group
	 */
	public void confirmRecoveryKey(long backendId, String lastGroup, User by) throws PersistenceException {
		requireOn();
		synchronized (writing) {
			StorageBackend row = encrypted(backendId);
			notAdding(backendId);
			byte[] key = keys.read(backendId).orElseThrow(() -> refuse("The key file of this backend is missing, so its "
					+ "recovery key can't be confirmed: enter the recovery key to put it back."));
			if (!RecoveryKey.isLastGroup(key, lastGroup))
				throw refuse("That isn't the last group of this backend's recovery key: type its last four characters, "
						+ "as it was shown.");
			if (!row.isKeyConfirmedOn()) {
				row.setKeyConfirmedOn(true);
				backends.store(row);
				log.info("The recovery key of storage backend {} ({}) was confirmed by {}", backendId, row.getName(),
						by.getUsername());
			}
			built.forget(backendId);
		}
	}

	/**
	 * Saves a backend's settings: its name, and for S3 the endpoint, region, access key, secret and path style. While
	 * nothing is recorded or held on it and no Store uses it, its bucket, prefix or path may change too, and its new
	 * place gets its marker, and its check object, before the row points there. The new place is written outside the
	 * lock; the checks are made again under it before the row changes, and the new place undone if they fail, or if
	 * another save moved the backend meanwhile: the place this save would clear is then no longer the backend's.
	 *
	 * @param backend the settings; Original's name alone is taken
	 * @param secret a new S3 secret; null or blank keeps the stored one
	 * @param by the administrator
	 * @return the backend as saved
	 * @throws PersistenceException a {@link HealdanRuleException} saying why it was refused; a {@link SecretKeyMissing},
	 *         with nothing saved, as for {@link #add}
	 */
	public GUIStorageBackend save(GUIStorageBackend backend, String secret, User by) throws PersistenceException {
		requireOn();
		if (backend == null)
			throw refuse("The backend is missing.");
		StorageBackend before = row(backend.getId());
		long id = before.getId();
		notAdding(id);
		if (id == StorageBackend.ORIGINAL)
			return renameOriginal(backend.getName(), by);
		// Where the backend was when this save began, which it clears if it moves the backend
		BackendDrafts.Place read = BackendDrafts.Place.of(before);

		Draft draft = BackendDrafts.draft(backend, secret, before);
		boolean moves = checkSave(before, draft);
		String sealed = draft.secret() != null ? seal(draft.secret(), backend.isNewKey()) : null;

		Placed placed = null;
		Backend was = null;
		if (moves) {
			byte[] key = before.isEncryptedOn() ? keys.read(id).orElseThrow(() -> refuse("The key file of this backend "
					+ "is missing, so its new place can't get its check object: enter its recovery key first.")) : null;
			was = formerPlace(id);
			try {
				placed = place(draft, id, plainSecret(draft, before));
				if (key != null)
					placed.writeCheck(new FileCipher(key));
			} catch (IOException | PersistenceException | RuntimeException e) {
				if (placed != null) {
					placed.undo();
					placed.close();
				}
				built.release(was);
				throw refuse("The backend wasn't saved: " + message(e, id));
			}
		}

		StorageBackend row;
		List<String> changes;
		try {
			synchronized (writing) {
				row = row(id);
				notAdding(id);
				// Moved meanwhile, it would clear the place it read rather than the one the other save gave it; with
				// the place as read, the draft moves the backend as it did before the lock
				if (!BackendDrafts.Place.of(row).equals(read))
					throw refuse("The backend was changed meanwhile, so this wasn't saved: open it again.");
				checkSave(row, draft);
				changes = BackendDrafts.changes(row, draft, sealed != null);
				BackendDrafts.apply(row, draft);
				if (sealed != null)
					row.setSecret(sealed);
				backends.store(row);
				built.forget(id);
			}
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
		health.check(id);
		log.info("Storage backend {} ({}) was saved by {}{}", id, row.getName(), by.getUsername(),
				changes.isEmpty() ? ", unchanged" : ": " + String.join(", ", changes) + " changed");
		return toGUI(row);
	}

	/**
	 * The checks of a save that read the rows: the draft keeps the backend's encryption; its place changes only while
	 * nothing is recorded or held on the backend and no Store keeps its files there; and its name and place are its
	 * own.
	 *
	 * @return whether the draft moves the backend to another place
	 */
	private boolean checkSave(StorageBackend row, Draft draft) throws PersistenceException {
		if (draft.encrypted() != row.isEncryptedOn())
			throw refuse("A backend's encryption is chosen when it is added, as its recovery key is shown then. To "
					+ "change it, remove the backend while it is empty and add it again.");
		boolean moves = !BackendDrafts.samePlace(row, draft);
		if (moves && inUse(row.getId()))
			throw refuse(BackendDrafts.moved(row, draft)
					+ " can't change once files are recorded or held on the backend.");
		if (moves) {
			// A save into the Store, or an upload the mover began, would land at the old place as the row moved
			List<String> using = storesUsing(row.getId());
			if (!using.isEmpty())
				throw refuse("%s can't change while %s its files on this backend: give %s another backend first."
						.formatted(BackendDrafts.moved(row, draft), keeps(using), using.size() == 1 ? "it" : "them"));
		}
		checkFree(draft, row.getId());
		return moves;
	}

	private GUIStorageBackend renameOriginal(String typed, User by) throws PersistenceException {
		String name = BackendDrafts.name(typed);
		StorageBackend row;
		synchronized (writing) {
			row = row(StorageBackend.ORIGINAL);
			BackendDrafts.checkName(name, StorageBackend.ORIGINAL, rows());
			if (!name.equals(row.getName())) {
				String was = row.getName();
				row.setName(name);
				backends.store(row);
				log.info("Storage backend {} ({}) was renamed {} by {}", row.getId(), was, name, by.getUsername());
			}
			built.forget(row.getId());
		}
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
	 * Test of a saved backend in full, as its row and key file now are, its marker and key included. The built entry
	 * is dropped first, so a key file deleted or swapped since fails Test, rather than the key kept in memory passing
	 * it; and the outcome is recorded in its health, or the tab would show OK while Test says the key file is gone.
	 *
	 * @param backendId the backend
	 * @return empty if it passed; otherwise the problems
	 * @throws PersistenceException error at data layer
	 */
	public List<String> test(long backendId) throws PersistenceException {
		requireOn();
		row(backendId);
		notAdding(backendId);
		built.forget(backendId);
		List<String> problems = new ArrayList<>();
		try {
			BackendTest.run(built.entry(backendId), keys, true);
		} catch (BackendFailingException e) {
			problems.add(message(e, backendId));
		}
		BackendHealth.Status status = health.check(backendId);
		if (problems.isEmpty() && status.state() == BackendHealth.State.FAILING)
			problems.add(named(status.error(), backendId));
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
		requireOn();
		StorageBackend row = encrypted(backendId);
		notAdding(backendId);
		byte[] key = keys.read(backendId).orElseThrow(() -> refuse("The key file of this backend is missing or can't be "
				+ "read, so there is no key to show: enter its recovery key to put the file back."));
		record(backendId, StorageEvent.KEY_SHOWN, by);
		log.info("The recovery key of storage backend {} ({}) was shown to {}", backendId, row.getName(),
				by.getUsername());
		return RecoveryKey.format(key);
	}

	/**
	 * Rebuilds an encrypted backend's key file from its recovery key (spec section 9). The key typed must decrypt the
	 * backend's check object, read before anything is written and before the lock is taken, so a mistyped or another
	 * backend's key can't replace the file. Recorded ({@code key-entered}) and logged, never with the key.
	 *
	 * @param backendId the backend
	 * @param typed the recovery key as typed
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if the key isn't the backend's, or can't be checked
	 */
	public void enterRecoveryKey(long backendId, String typed, User by) throws PersistenceException {
		requireOn();
		encrypted(backendId);
		notAdding(backendId);
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
					+ message(e, backendId));
		}
		try {
			if (!KeyCheck.verify(backend, LocalBackend.CHECK, new FileCipher(key)))
				throw refuse("That recovery key isn't this backend's: its check object doesn't decrypt with it, so "
						+ "nothing was written. Check the key, and type it again.");
		} catch (FileNotFoundException e) {
			throw refuse("The backend's check object is missing, so the recovery key can't be checked against it, and "
					+ "nothing was written.");
		} catch (IOException e) {
			throw refuse("The backend's check object can't be read, so the recovery key wasn't put back: "
					+ message(e, backendId));
		} finally {
			built.release(backend);
		}

		StorageBackend row;
		synchronized (writing) {
			row = encrypted(backendId);
			notAdding(backendId);
			try {
				keys.restore(backendId, key);
			} catch (IOException e) {
				throw refuse("The recovery key is right, but its key file couldn't be written: " + e.getMessage());
			}
			record(backendId, StorageEvent.KEY_ENTERED, by);
			built.forget(backendId);
		}
		log.info("The recovery key of storage backend {} ({}) was entered by {}, and its key file put back", backendId,
				row.getName(), by.getUsername());
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
	 * are deleted, outside the lock, so another backend may have its place.
	 *
	 * @param backendId the backend; never Original
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} saying why it can't be removed
	 */
	public void remove(long backendId, User by) throws PersistenceException {
		requireOn();
		if (backendId == StorageBackend.ORIGINAL)
			throw refuse("Original can be renamed, but not removed: it keeps the files of every Store that has no "
					+ "other backend.");
		row(backendId);
		Backend place = formerPlace(backendId);
		StorageBackend row;
		try {
			synchronized (writing) {
				row = row(backendId);
				notAdding(backendId);
				List<String> using = storesUsing(backendId);
				if (!using.isEmpty())
					throw refuse("%s its files on this backend: give %s another backend first."
							.formatted(keeps(using), using.size() == 1 ? "it" : "them"));
				long recorded = files.countOn(backendId);
				if (recorded > 0)
					throw refuse("%s on this backend: it can be removed once they have moved off it.".formatted(
							recorded == 1 ? "1 file is recorded" : recorded + " files are recorded"));
				long heldOn = held.countOn(backendId);
				if (heldOn > 0)
					throw refuse(("%s held on this backend until the holding period ends: it can be removed once the "
							+ "purge has deleted them.").formatted(heldOn == 1 ? "1 copy is" : heldOn + " copies are"));
				try {
					keys.delete(backendId);
				} catch (IOException e) {
					throw refuse("The backend's key file couldn't be deleted, so the backend wasn't removed: "
							+ e.getMessage());
				}
				backends.remove(backendId);
				built.forget(backendId);
			}
		} catch (PersistenceException | RuntimeException e) {
			built.release(place);
			throw e;
		}
		if (place != null)
			clearPlace(place, row.isEncryptedOn());
		log.info("Storage backend {} ({}) was removed by {}", backendId, row.getName(), by.getUsername());
	}

	/** @return the names of the Stores that keep their files on the backend */
	private List<String> storesUsing(long backendId) throws PersistenceException {
		List<String> using = new ArrayList<>();
		for (StoreBackend store : stores.findAll())
			if (store.getDeleted() == 0 && store.getBackendId() == backendId)
				using.add(storeName(store.getStoreId()));
		return using;
	}

	/** @return "Store A keeps", or "Stores A, B keep" */
	private static String keeps(List<String> stores) {
		return (stores.size() == 1 ? "Store " : "Stores ") + String.join(", ", stores)
				+ (stores.size() == 1 ? " keeps" : " keep");
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
	public void setStoreBackend(long storeId, long backendId, User by) throws PersistenceException {
		requireOn();
		Folder store = store(storeId);
		offered(row(backendId));
		if (files.storeBackend(storeId) == backendId)
			return;
		tracking.checkStore(storeId);

		StorageBackend row;
		long was;
		synchronized (writing) {
			row = row(backendId);
			notAdding(backendId);
			offered(row);
			was = files.storeBackend(storeId);
			if (was == backendId)
				return;
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
		}
		mover.wake();
		log.info("Store {} ({}) keeps its files on storage backend {} ({}) from now on, not {}, as {} set it; they move "
				+ "in the background", storeId, store.getName(), backendId, row.getName(), was, by.getUsername());
	}

	/** Refuses a backend Stores can't have yet: an encrypted one whose recovery key isn't confirmed */
	private static void offered(StorageBackend row) throws HealdanRuleException {
		if (row.isEncryptedOn() && !row.isKeyConfirmedOn())
			throw refuse("The recovery key of backend " + row.getName() + " isn't confirmed yet, so no Store can use "
					+ "it: confirm its recovery key first.");
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
	 * @throws PersistenceException a {@link HealdanRuleException} if storage backends are off
	 */
	public void retry(long storeId, User by) throws PersistenceException {
		requireOn();
		log.info("{} retried the stuck files of {}", by.getUsername(), storeId == 0 ? "no Store" : "Store " + storeId);
		mover.retry(storeId);
	}

	/**
	 * Saves the storage settings, each checked before any is written, and all of them in one transaction; the transfers
	 * apply at once.
	 *
	 * @param holdDays the holding period, in days
	 * @param cacheGb the read cache's size, in GB
	 * @param transfers how many files move at once
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if one is out of range
	 */
	public void saveSettings(int holdDays, int cacheGb, int transfers, User by) throws PersistenceException {
		requireOn();
		if (holdDays < 1 || holdDays > StorageSettings.MAX_HOLD_DAYS)
			throw refuse("The holding period must be from 1 to %d days.".formatted(StorageSettings.MAX_HOLD_DAYS));
		if (cacheGb < 1 || cacheGb > StorageSettings.MAX_CACHE_GB)
			throw refuse("The read cache must be from 1 to %d GB.".formatted(StorageSettings.MAX_CACHE_GB));
		if (transfers < 1 || transfers > StorageSettings.MAX_TRANSFERS)
			throw refuse("Parallel transfers must be from 1 to %d.".formatted(StorageSettings.MAX_TRANSFERS));
		synchronized (writing) {
			// In one transaction, so a write that fails leaves the others as they were
			own.executeWithoutResult(status -> {
				settings.setHoldDays(holdDays);
				settings.setCacheGb(cacheGb);
				settings.setTransfers(transfers);
			});
		}
		mover.resize(transfers);
		log.info("The storage settings were saved by {}: copies held {} days, a {} GB read cache, {} transfers at once",
				by.getUsername(), holdDays, cacheGb, transfers);
	}

	// The secret

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
			return List.of(message(e, id));
		} catch (HealdanRuleException | IllegalArgumentException e) {
			return List.of(named(e.getMessage(), id));
		}
	}

	/**
	 * Names a backend in a message for the tab as "this backend", rather than by its id, which means nothing to an
	 * administrator: "Backend 12" or "Storage backend 12" becomes "This backend", and "backend 12" or "storage backend
	 * 12" becomes "this backend". Another backend's id, or a longer number, stays. The log keeps the ids.
	 *
	 * @param message a message from the backend, its build, its check or its Test; possibly null
	 * @param id the backend's id, or the one a backend not saved yet was built with
	 * @return the message without the id
	 */
	static String named(String message, long id) {
		if (message == null)
			return null;
		return Pattern.compile("\\b(?:[Ss]torage [Bb]ackend|[Bb]ackend) " + id + "\\b").matcher(message)
				.replaceAll(m -> Character.isUpperCase(m.group().charAt(0)) ? "This backend" : "this backend");
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

	/**
	 * Refuses every write while CE keeps its files in its own store, as every other storage bean then does nothing: the
	 * tab says storage backends are off
	 */
	private void requireOn() throws HealdanRuleException {
		if (!on.getAsBoolean())
			throw refuse("Storage backends are off: this server keeps its files in LogicalDOC's own store, so nothing on "
					+ "the Storage tab can be changed.");
	}

	/** Refuses to touch a backend whose add hasn't ended */
	private void notAdding(long id) throws HealdanRuleException {
		if (adding.contains(id))
			throw refuse("That backend is still being added: try again once its add has finished.");
	}

	/** @return whether a file is recorded or held on the backend */
	private boolean inUse(long backendId) {
		return files.countOn(backendId) > 0 || held.countOn(backendId) > 0;
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
		gui.setError(named(status.error(), id));
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

	/** @return the failure's message for the tab, which names the backend without its id ({@link #named}) */
	private static String message(Exception e, long id) {
		return named(message(e), id);
	}

	private static HealdanRuleException refuse(String message) {
		return new HealdanRuleException(message);
	}
}
