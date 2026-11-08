// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.services;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;
import com.google.gwt.user.client.rpc.ServiceDefTarget;
import com.logicaldoc.gui.common.client.LDRpcRequestBuilder;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageEvent;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStuckFile;

/**
 * The Storage tab of Healdan Settings (spec section 11): storage backends, each
 * Store's backend, stuck files and the storage settings. Every method is for
 * administrators only. No answer ever holds an S3 secret; a recovery key comes
 * back only from {@link #addBackend} and {@link #showRecoveryKey}.
 * <p>
 * Each method throws a {@link ServerException} when the user isn't an
 * administrator, a value or a step is refused (the message says why), or an
 * error happened in the server application.
 */
@RemoteServiceRelativePath("healdanstorage")
public interface HealdanStorageService extends RemoteService {

	/**
	 * @return the backends with their status, files and size, the Stores with
	 *         their backend and what waits or is stuck, the files with no Store,
	 *         the settings, and whether storage backends are on at all
	 *
	 * @throws ServerException as the interface says
	 */
	public GUIStorageOverview load() throws ServerException;

	/**
	 * Tests a backend's settings before they are saved: a write, a read whole
	 * and by a range, a list (S3) and a delete of a test object. A local folder
	 * not added yet is only probed.
	 *
	 * @param backend the settings as typed
	 * @param secret the S3 secret as typed; null or blank, for a backend already
	 *        added, uses the stored one
	 *
	 * @return empty if the test passed; otherwise the problems
	 *
	 * @throws ServerException as the interface says
	 */
	public String[] testNew(GUIStorageBackend backend, String secret) throws ServerException;

	/**
	 * Adds a backend: tests it, saves it, writes its marker, and for an
	 * encrypted one makes its key file and check object, then tests it again in
	 * full. An encrypted backend isn't offered to Stores until
	 * {@link #confirmRecoveryKey}.
	 *
	 * @param backend the settings
	 * @param secret the S3 secret
	 *
	 * @return the backend as added, with its recovery key if it is encrypted:
	 *         the only time it comes with the answer, and a showing that is
	 *         recorded in its Key History as Show Recovery Key's are
	 *
	 * @throws ServerException as the interface says; a
	 *         {@link SecretKeyMissingException}, with nothing added, if
	 *         Healdan's secret key file is missing while values are sealed
	 *         with it and {@code newKey} isn't set
	 */
	public GUIStorageBackend addBackend(GUIStorageBackend backend, String secret) throws ServerException;

	/**
	 * Confirms that the administrator saved a new backend's recovery key, which
	 * lets Stores use it.
	 *
	 * @param backendId the backend
	 * @param lastGroup the recovery key's last group of four, as typed
	 *
	 * @throws ServerException as the interface says, as for a group that isn't
	 *         the key's
	 */
	public void confirmRecoveryKey(long backendId, String lastGroup) throws ServerException;

	/**
	 * Saves a backend's settings. Its encryption never changes here: it is
	 * chosen when the backend is added, as its recovery key is shown then. Its
	 * place (the folder's path, or the bucket and prefix) can't change once a
	 * file is recorded or held on it, nor while a Store keeps its files there.
	 *
	 * @param backend the settings
	 * @param secret a new S3 secret; null or blank keeps the stored one
	 *
	 * @return the backend as saved
	 *
	 * @throws ServerException as the interface says; a
	 *         {@link SecretKeyMissingException}, with nothing saved, as for
	 *         {@link #addBackend}
	 */
	public GUIStorageBackend saveBackend(GUIStorageBackend backend, String secret) throws ServerException;

	/**
	 * Tests a saved backend in full, its marker and key included, as its row
	 * and key file now are, and records the outcome in its status.
	 *
	 * @param backendId the backend
	 *
	 * @return empty if the test passed; otherwise the problems
	 *
	 * @throws ServerException as the interface says
	 */
	public String[] testBackend(long backendId) throws ServerException;

	/**
	 * Shows an encrypted backend's recovery key, which is recorded in its Key
	 * History with who it was shown to.
	 *
	 * @param backendId the backend
	 *
	 * @return the recovery key, in its groups
	 *
	 * @throws ServerException as the interface says
	 */
	public String showRecoveryKey(long backendId) throws ServerException;

	/**
	 * Rebuilds an encrypted backend's key file from its recovery key, once the
	 * key typed decrypts the backend's check object. Recorded in its Key
	 * History.
	 *
	 * @param backendId the backend
	 * @param typed the recovery key as typed
	 *
	 * @throws ServerException as the interface says, as for a key that isn't
	 *         the backend's, which writes nothing
	 */
	public void enterRecoveryKey(long backendId, String typed) throws ServerException;

	/**
	 * @param backendId the backend
	 *
	 * @return every showing and entering of its recovery key, newest first
	 *
	 * @throws ServerException as the interface says
	 */
	public GUIStorageEvent[] keyEvents(long backendId) throws ServerException;

	/**
	 * Removes a backend no Store uses and that holds no file, recorded or held,
	 * with its key file. Original can't be removed.
	 *
	 * @param backendId the backend
	 *
	 * @throws ServerException as the interface says
	 */
	public void removeBackend(long backendId) throws ServerException;

	/**
	 * @param storeId the Store's folder id
	 * @param backendId the backend it would keep its files on
	 *
	 * @return how many of its files would move, and their size in bytes
	 *
	 * @throws ServerException as the interface says
	 */
	public long[] previewStoreSwitch(long storeId, long backendId) throws ServerException;

	/**
	 * Gives a Store another backend: its files move there in the background,
	 * and are read from where they are until each has moved.
	 *
	 * @param storeId the Store's folder id
	 * @param backendId the backend, which must be offered to Stores
	 *
	 * @throws ServerException as the interface says
	 */
	public void setStoreBackend(long storeId, long backendId) throws ServerException;

	/**
	 * @param storeId the Store's folder id; 0 for the files with no Store
	 *
	 * @return the files of the Store the mover gave up on
	 *
	 * @throws ServerException as the interface says
	 */
	public GUIStuckFile[] stuckFiles(long storeId) throws ServerException;

	/**
	 * Puts a Store's stuck files back to waiting, and wakes the mover.
	 *
	 * @param storeId the Store's folder id; 0 for the files with no Store
	 *
	 * @throws ServerException as the interface says
	 */
	public void retry(long storeId) throws ServerException;

	/**
	 * Saves the storage settings, all of them or none.
	 *
	 * @param holdDays the holding period, 1 to 365 days
	 * @param cacheGb the read cache's size, 1 to 10000 GB
	 * @param transfers how many files move at once, 1 to 16
	 *
	 * @throws ServerException as the interface says
	 */
	public void saveSettings(int holdDays, int cacheGb, int transfers) throws ServerException;

	public static class Instance {
		private static HealdanStorageServiceAsync inst;

		private Instance() {
		}

		public static HealdanStorageServiceAsync get() {
			if (inst == null) {
				inst = GWT.create(HealdanStorageService.class);
				((ServiceDefTarget) inst).setRpcRequestBuilder(new LDRpcRequestBuilder());
			}
			return inst;
		}
	}
}
