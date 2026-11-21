// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.healdan.admin.dashboard.Usage;
import com.logicaldoc.healdan.admin.dashboard.Usage.StoreUsage;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.admin.stores.StoreService;
import com.logicaldoc.healdan.api.admin.StorageView.PendingFiles;
import com.logicaldoc.healdan.api.admin.StorageView.StoreInfo;
import com.logicaldoc.healdan.api.admin.StorageView.StoreReader;
import com.logicaldoc.util.spring.Context;

/**
 * Makes the Stores' {@link StoreInfo}s: each Store's live matters, counted now, as the Matters list counts them; its
 * documents and their bytes, from the usage summary in memory, so a request never counts them; who can see it, as
 * {@link StoreService#access} lists them; and its backend and its files that haven't reached it, from the storage
 * port's overview, read once for all of them, or null without the port or while storage backends are off.
 */
final class StoreInfos {

	/** Each Store's live matters: not deleted, their folders not in the Trash, their clients not deleted */
	private static final String MATTERS = """
			select f.ld_parentid, count(*) from hd_matter m
			  join hd_client c on c.ld_id = m.hd_clientid and c.ld_deleted = 0
			  join ld_folder f on f.ld_id = m.hd_folderid and f.ld_deleted = 0
			 where m.ld_deleted = 0 and f.ld_parentid in (:stores)
			 group by f.ld_parentid""";

	private StoreInfos() {
	}

	/**
	 * @param store a live Store
	 * @return it, as its page shows it
	 */
	static StoreInfo of(Folder store) {
		return of(List.of(store)).get(0);
	}

	/**
	 * @param stores live Stores
	 * @return them, in the same order, as the Stores list shows them
	 */
	static List<StoreInfo> of(List<Folder> stores) {
		if (stores.isEmpty())
			return List.of();
		Map<Long, Long> matters = matters(stores);
		Usage usage = UsageSummary.get().current();
		Instant figuresAt = usage.computedAt();
		Map<Long, StoreUsage> used = new HashMap<>();
		for (StoreUsage store : usage.stores())
			used.put(store.id(), store);
		StorageOverview overview = overview();
		Map<Long, StoreBackendInfo> onBackends = new HashMap<>();
		Map<Long, BackendInfo> backends = new HashMap<>();
		if (overview != null) {
			overview.stores().forEach(store -> onBackends.put(store.storeId(), store));
			overview.backends().forEach(backend -> backends.put(backend.id(), backend));
		}

		StoreService service = StoreService.get();
		List<StoreInfo> infos = new ArrayList<>();
		for (Folder store : stores) {
			StoreUsage figures = used.get(store.getId());
			// A Store the summary doesn't hold yet, made or restored since, holds nothing in it
			Long documents = figuresAt == null ? null : figures != null ? figures.documents() : 0L;
			Long bytes = figuresAt == null ? null : figures != null ? figures.bytes() : 0L;
			StoreBackendInfo onBackend = onBackends.get(store.getId());
			Usage.Backend backend = onBackend != null ? backend(backends.get(onBackend.backendId())) : null;
			infos.add(new StoreInfo(store.getId(), store.getName(), matters.getOrDefault(store.getId(), 0L),
					documents, bytes, StorageView.date(figuresAt), readers(service, store.getId()), backend,
					backend != null ? PendingFiles.of(onBackend) : null));
		}
		return infos;
	}

	/**
	 * @return the storage port's overview; null without the port or while storage backends are off
	 */
	static StorageOverview overview() {
		Optional<StorageAdministration> port = StoragePort.get().find();
		if (port.isEmpty())
			return null;
		StorageOverview overview = StoragePort.run("Can't read the storage overview", () -> port.get().overview());
		return overview.on() ? overview : null;
	}

	/**
	 * @return the backend as a Store names it; null for none
	 */
	static Usage.Backend backend(BackendInfo backend) {
		return backend != null ? new Usage.Backend(backend.id(), backend.name(), backend.kind(), backend.encrypted())
				: null;
	}

	/**
	 * @return each Store's live matters, by Store
	 */
	private static Map<Long, Long> matters(List<Folder> stores) {
		Map<Long, Long> counts = new HashMap<>();
		new NamedParameterJdbcTemplate(Context.get(DataSource.class)).query(MATTERS,
				new MapSqlParameterSource("stores", stores.stream().map(Folder::getId).toList()),
				rs -> {
					counts.put(rs.getLong(1), rs.getLong(2));
				});
		return counts;
	}

	/**
	 * @return who can see the Store: each row that grants Read, in {@link StoreService#access}'s order
	 */
	private static List<StoreReader> readers(StoreService service, long storeId) {
		try {
			return service.access(storeId).rows().stream()
					.filter(row -> row.permissions().contains(Permission.READ))
					.map(row -> new StoreReader(row.groupId(), row.name(), row.person())).toList();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read who can see Store #" + storeId, e);
		}
	}
}
