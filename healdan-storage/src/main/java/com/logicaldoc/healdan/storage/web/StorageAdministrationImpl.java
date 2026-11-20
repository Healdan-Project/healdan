// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStoreBackend;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;

/**
 * The storage port for the Admin app (spec section 9.7): {@link StorageAdmin#overview()}, the Storage tab's, as
 * {@code healdan-core}'s plain records. {@link StorageAdmin} doesn't change.
 * <p>
 * Nothing secret is answered: the S3 access key and secret, and the recovery key, aren't in the records, and an S3
 * backend's endpoint is given as its scheme, host and port alone, so a user name and password, a path, a query or a
 * fragment, which the endpoint rule refuses but a row written another way could hold, never reach the answer. A
 * recovery key's last showing is its time alone.
 */
@Component
public class StorageAdministrationImpl implements StorageAdministration {

	private final StorageAdmin admin;

	@Autowired
	public StorageAdministrationImpl(StorageAdmin admin) {
		this.admin = admin;
	}

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
					store.getBackendName(), store.getWaiting(), store.getStuck(), store.getFiles(), store.getSize()));

		return new StorageOverview(true, backends, stores, settings, gui.getOtherWaiting(), gui.getOtherWaitingSize(),
				gui.getOtherStuck());
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
	 *         the endpoint's scheme, host and port alone, and left out if it can't be read
	 */
	static String where(GUIStorageBackend backend) {
		if (!backend.isS3())
			return backend.getPath();
		String place = backend.getBucket() + "/" + (backend.getPrefix() != null ? backend.getPrefix() : "");
		String endpoint = endpoint(backend.getEndpoint());
		return endpoint != null ? place + " at " + endpoint : place;
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
