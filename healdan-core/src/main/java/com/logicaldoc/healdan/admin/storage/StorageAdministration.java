// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.storage;

import java.util.List;
import java.util.Optional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.storage.StorageRecords.AddedBackend;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDraft;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendList;
import com.logicaldoc.healdan.admin.storage.StorageRecords.KeyEvent;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageLimits;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StuckFile;
import com.logicaldoc.healdan.admin.storage.StorageRecords.SwitchPreview;
import com.logicaldoc.util.spring.Context;

/**
 * Storage backends as the Admin app reads and changes them (spec section 9.7). {@code healdan-storage} implements it
 * as a bean, over the Storage tab's own service, which doesn't change, so every rule the tab keeps holds here too;
 * {@code healdan-core} and {@code healdan-api} find it by type ({@link #ports()}), as {@code HealdanStartListener}s
 * are found, since they can't depend on {@code healdan-storage}. With no bean, the storage port is absent.
 * <p>
 * Its answers hold no S3 secret, only whether one is stored, and a recovery key only in {@link #add}'s answer for an
 * encrypted backend and in {@link #showRecoveryKey}'s, each a showing that is recorded. A refusal is a
 * {@link HealdanRuleException} whose message is the rule's sentence, written for the administrator. Each method given
 * the administrator ({@code by}), Show Recovery Key among them, refuses first anyone who isn't one, whatever called
 * it; then, while storage backends are off, on a server whose CE keeps its files in its own store, every change is
 * refused. Every change,
 * once it has succeeded, is recorded in the administration record (area {@code STORAGE}) straight after it, as it
 * isn't one database transaction; a change that changes nothing, and a read, record nothing. No value of a secret, an
 * access key or a recovery key is recorded, logged or put in a message.
 */
public interface StorageAdministration {

	/**
	 * @return the storage ports in the application context: one with {@code healdan-storage}, else none
	 */
	static List<StorageAdministration> ports() {
		return Context.get().getBeansOfType(StorageAdministration.class);
	}

	/**
	 * @return the backends, the Stores, the settings and the files with no Store; on a server whose CE doesn't keep
	 *         its files through Healdan's storage, {@code on} false and empty lists, never an exception for that
	 * @throws PersistenceException error at data layer
	 */
	StorageOverview overview() throws PersistenceException;

	/**
	 * @param id a backend
	 * @return what its edit form shows; empty if it doesn't exist, or while storage backends are off
	 * @throws PersistenceException error at data layer
	 */
	Optional<BackendDetail> backend(long id) throws PersistenceException;

	/**
	 * @return every backend as {@link #backend} gives it, and the files with no Store, from one reading of the
	 *         backends' and the Stores' files, as {@link #overview} reads them; while storage backends are off,
	 *         {@code on} false, no backend and the other files' counts 0
	 * @throws PersistenceException error at data layer
	 */
	BackendList backends() throws PersistenceException;

	/**
	 * Whether a backend is there to act on, without counting its files: what a route asks before it changes one.
	 *
	 * @param id a backend
	 * @return true if storage backends are on and the backend exists; false otherwise, as {@link #backend} is empty
	 * @throws PersistenceException error at data layer
	 */
	boolean exists(long id) throws PersistenceException;

	/**
	 * @return the most each storage setting may be; the least is 1 for each
	 */
	StorageLimits limits();

	/**
	 * Whether storage backends are on, without counting any file: what the storage settings' page asks.
	 *
	 * @return true if CE keeps its files through Healdan's storage backends; false while they're off
	 */
	boolean on();

	/**
	 * The storage settings as stored, without counting any file, whether storage backends are on or off.
	 *
	 * @return the settings
	 * @throws PersistenceException error at data layer
	 */
	StorageSettingsInfo settings() throws PersistenceException;

	/**
	 * Test before saving: a write, a read whole and by a range, a list (S3) and a delete of a test object, with the
	 * settings as typed. Records nothing.
	 *
	 * @param id the backend being edited; 0 for one not added yet
	 * @param draft the settings as typed
	 * @param secret the S3 secret as typed; null or blank, for a backend already added, uses the stored one
	 * @return empty if the test passed; otherwise the problems, a refused setting included
	 * @throws PersistenceException a {@link HealdanRuleException} if storage backends are off, the draft is missing or
	 *         the backend doesn't exist; or an error at data layer
	 */
	List<String> testNew(long id, BackendDraft draft, String secret) throws PersistenceException;

	/**
	 * Adds a backend, once Test passes on its settings. An encrypted backend's answer carries its recovery key, and is
	 * recorded as a showing of it; it isn't offered to Stores until {@link #confirmRecoveryKey}.
	 *
	 * @param draft the settings
	 * @param secret the S3 secret; ignored for a local folder
	 * @param newKey whether to make a new secret key for the secret, which the administrator confirmed when told the
	 *        key file is missing
	 * @param by the administrator
	 * @return the backend as added, with its recovery key if it is encrypted
	 * @throws PersistenceException a {@link SecretKeyMissingProblem}, with nothing written, if the secret can't be
	 *         sealed for want of the secret key file and {@code newKey} is false; a {@link HealdanRuleException} saying
	 *         why it was refused; or an error at data layer
	 */
	AddedBackend add(BackendDraft draft, String secret, boolean newKey, User by) throws PersistenceException;

	/**
	 * Saves a backend's settings: its name, and for S3 the endpoint, region, access key, secret and path style; its
	 * bucket, prefix or path only while nothing is recorded or held on it and no Store keeps its files there, live or in
	 * the Trash, a Store in the Trash refused first, by name. Its kind and encryption never change, and Original's name
	 * alone is taken.
	 *
	 * @param id the backend
	 * @param draft the settings
	 * @param secret a new S3 secret; null or blank keeps the stored one
	 * @param newKey as for {@link #add}
	 * @param by the administrator
	 * @return the backend as saved, read as {@link #backend} reads it but for this backend's files alone
	 * @throws PersistenceException a {@link SecretKeyMissingProblem}, with nothing saved, as for {@link #add}; a
	 *         {@link HealdanRuleException} saying why it was refused; or an error at data layer
	 */
	BackendDetail save(long id, BackendDraft draft, String secret, boolean newKey, User by)
			throws PersistenceException;

	/**
	 * Tests a saved backend in full, as its row and key file now are, its marker and key included. The outcome goes
	 * into its health, not the administration record.
	 *
	 * @param id the backend
	 * @return empty if it passed; otherwise the problems
	 * @throws PersistenceException a {@link HealdanRuleException} if storage backends are off or the backend doesn't
	 *         exist; or an error at data layer
	 */
	List<String> test(long id) throws PersistenceException;

	/**
	 * Shows an encrypted backend's recovery key, from its key file as it is now: recorded as a showing.
	 *
	 * @param id the backend
	 * @param by the administrator it is shown to
	 * @return the recovery key, in its groups
	 * @throws PersistenceException a {@link HealdanRuleException} if the backend isn't encrypted or its key file is
	 *         missing; or an error at data layer
	 */
	String showRecoveryKey(long id, User by) throws PersistenceException;

	/**
	 * Confirms an encrypted backend's recovery key, which lets Stores use the backend. A key already confirmed changes
	 * nothing.
	 *
	 * @param id the backend
	 * @param lastGroup its recovery key's last group, as typed
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if it isn't the key's last group; or an error at
	 *         data layer
	 */
	void confirmRecoveryKey(long id, String lastGroup, User by) throws PersistenceException;

	/**
	 * Puts an encrypted backend's key file back from its recovery key, which must decrypt the backend's check object.
	 *
	 * @param id the backend
	 * @param recoveryKey the recovery key as typed
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if the key isn't the backend's, or can't be checked;
	 *         or an error at data layer
	 */
	void enterRecoveryKey(long id, String recoveryKey, User by) throws PersistenceException;

	/**
	 * @param id a backend, which may have been removed since: its history is kept
	 * @return every showing and entering of its recovery key, newest first
	 * @throws PersistenceException error at data layer
	 */
	List<KeyEvent> keyEvents(long id) throws PersistenceException;

	/**
	 * Removes a backend, once no Store uses it, live or in the Trash, and no file is recorded or held on it. Never
	 * Original. A Store in the Trash on it is refused first, by name, as {@link BackendDetail#storesInTrash} lists it.
	 *
	 * @param id the backend
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} saying why it can't be removed; or an error at data
	 *         layer
	 */
	void remove(long id, User by) throws PersistenceException;

	/**
	 * @param storeId a Store
	 * @param backendId the backend it would keep its files on
	 * @return how many of its files would move, and their size in bytes
	 * @throws PersistenceException a {@link HealdanRuleException} if either isn't there; or an error at data layer
	 */
	SwitchPreview previewSwitch(long storeId, long backendId) throws PersistenceException;

	/**
	 * Gives a Store another backend; its files move in the background. Its own backend changes nothing.
	 *
	 * @param storeId the Store
	 * @param backendId the backend, which must be offered to Stores; Original to bring its files home
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if it can't; or an error at data layer
	 */
	void setStoreBackend(long storeId, long backendId, User by) throws PersistenceException;

	/**
	 * @param storeId a Store; 0 for the files with no Store
	 * @return its files the mover gave up on, the newest documents first, at most 1,000
	 * @throws PersistenceException error at data layer
	 */
	List<StuckFile> stuck(long storeId) throws PersistenceException;

	/**
	 * Puts a Store's stuck files back to waiting, and wakes the mover. With none stuck it changes nothing.
	 *
	 * @param storeId a Store; 0 for the files with no Store
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if storage backends are off; or an error at data
	 *         layer
	 */
	void retry(long storeId, User by) throws PersistenceException;

	/**
	 * Saves the storage settings, each checked against {@link #limits()} before any is written, and all of them in
	 * one transaction; the transfers apply at once.
	 *
	 * @param settings the settings
	 * @param by the administrator
	 * @throws PersistenceException a {@link HealdanRuleException} if one is out of range; or an error at data layer
	 */
	void saveSettings(StorageSettingsInfo settings, User by) throws PersistenceException;
}
