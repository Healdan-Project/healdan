// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageEvent;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStuckFile;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanStorageService;
import com.logicaldoc.gui.frontend.client.healdan.services.SecretKeyMissingException;
import com.logicaldoc.healdan.web.HealdanRemoteService;

import jakarta.servlet.annotation.WebServlet;

/**
 * The Storage tab's server side (spec section 11). Every call is an administrator's, checked here before
 * {@link StorageAdmin}, which holds the rules, does anything. No answer holds an S3 secret.
 */
@WebServlet("/frontend/healdanstorage")
public class HealdanStorageServiceImpl extends HealdanRemoteService implements HealdanStorageService {

	private static final long serialVersionUID = 1L;

	/** What only an administrator may do here, for the refusal */
	private static final String STORAGE = "set up storage backends";

	/** @return where the rules are */
	protected StorageAdmin storage() {
		return StorageAdmin.get();
	}

	@Override
	public GUIStorageOverview load() throws ServerException {
		admin(STORAGE);
		return call("load the storage settings", () -> storage().overview());
	}

	@Override
	public String[] testNew(GUIStorageBackend backend, String secret) throws ServerException {
		admin(STORAGE);
		return call("test the storage backend", () -> storage().testNew(backend, secret).toArray(String[]::new));
	}

	@Override
	public GUIStorageBackend addBackend(GUIStorageBackend backend, String secret) throws ServerException {
		User by = admin(STORAGE);
		return call("add the storage backend", () -> sealing(() -> storage().add(backend, secret, by)));
	}

	@Override
	public void confirmRecoveryKey(long backendId, String lastGroup) throws ServerException {
		User by = admin(STORAGE);
		call("confirm the recovery key", () -> {
			storage().confirmRecoveryKey(backendId, lastGroup, by);
			return null;
		});
	}

	@Override
	public GUIStorageBackend saveBackend(GUIStorageBackend backend, String secret) throws ServerException {
		User by = admin(STORAGE);
		return call("save the storage backend", () -> sealing(() -> storage().save(backend, secret, by)));
	}

	@Override
	public String[] testBackend(long backendId) throws ServerException {
		admin(STORAGE);
		return call("test the storage backend", () -> storage().test(backendId).toArray(String[]::new));
	}

	@Override
	public String showRecoveryKey(long backendId) throws ServerException {
		User by = admin(STORAGE);
		return call("show the recovery key", () -> storage().showRecoveryKey(backendId, by));
	}

	@Override
	public void enterRecoveryKey(long backendId, String typed) throws ServerException {
		User by = admin(STORAGE);
		call("enter the recovery key", () -> {
			storage().enterRecoveryKey(backendId, typed, by);
			return null;
		});
	}

	@Override
	public GUIStorageEvent[] keyEvents(long backendId) throws ServerException {
		admin(STORAGE);
		return call("list the recovery key's history",
				() -> storage().keyEvents(backendId).toArray(GUIStorageEvent[]::new));
	}

	@Override
	public void removeBackend(long backendId) throws ServerException {
		User by = admin(STORAGE);
		call("remove the storage backend", () -> {
			storage().remove(backendId, by);
			return null;
		});
	}

	@Override
	public long[] previewStoreSwitch(long storeId, long backendId) throws ServerException {
		admin(STORAGE);
		return call("count the files to move", () -> storage().previewSwitch(storeId, backendId));
	}

	@Override
	public void setStoreBackend(long storeId, long backendId) throws ServerException {
		User by = admin(STORAGE);
		call("change the Store's storage backend", () -> {
			storage().setStoreBackend(storeId, backendId, by);
			return null;
		});
	}

	@Override
	public GUIStuckFile[] stuckFiles(long storeId) throws ServerException {
		admin(STORAGE);
		return call("list the stuck files", () -> storage().stuck(storeId).toArray(GUIStuckFile[]::new));
	}

	@Override
	public void retry(long storeId) throws ServerException {
		User by = admin(STORAGE);
		call("retry the stuck files", () -> {
			storage().retry(storeId, by);
			return null;
		});
	}

	@Override
	public void saveSettings(int holdDays, int cacheGb, int transfers) throws ServerException {
		User by = admin(STORAGE);
		call("save the storage settings", () -> {
			storage().saveSettings(holdDays, cacheGb, transfers, by);
			return null;
		});
	}

	/**
	 * Runs a save that seals a typed secret, answering a missing secret key as M4's Sign-In tab does (ruling R37):
	 * nothing was saved, and the tab asks whether to make a new key
	 */
	private static <T> T sealing(Work<T> work) throws PersistenceException, ServerException {
		try {
			return work.run();
		} catch (StorageAdmin.SecretKeyMissing e) {
			throw new SecretKeyMissingException(e.getMessage(), e.sealed());
		}
	}
}
