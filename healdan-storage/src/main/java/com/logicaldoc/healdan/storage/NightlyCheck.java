// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.util.spring.Context;

/**
 * The backstop for {@link StoreTracking} (spec section 5, call 10): works every row's Store out again from the
 * folders' parent ids, read once, and gives the rows that differ the right one, which leaves them misplaced for the
 * mover when their backend isn't their Store's, and wakes it. The rows are read after the folders, so a document whose
 * matter moved in between, and whose rows {@code StoreTracking} then gave the new Store, looks wrong against the
 * folders as they were read: each fix is checked against the document's Store read again, live ({@link StoreOf#of}),
 * and made only if it still holds. Then a row its backend leaves misplaced but that doesn't wait is marked as waiting
 * ({@link StoredFiles#markWaiting}), as the mover lists only rows that wait. Each fix is logged at debug, and their
 * count at info. {@code StorageStartup} runs it
 * at 03:17 every night. Does nothing unless CE's Store is a {@link HealdanStorage}.
 */
@Component
public class NightlyCheck {

	private static final Logger log = LoggerFactory.getLogger(NightlyCheck.class);

	private final JdbcTemplate jdbc;

	private final StoreOf storeOf;

	private final StoredFiles files;

	private final Mover mover;

	/** Tests: runs once the folders are read, before the rows are */
	volatile Runnable afterFolders = () -> {
	};

	@Autowired
	public NightlyCheck(DataSource dataSource, StoreOf storeOf, StoredFiles files, Mover mover) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.storeOf = storeOf;
		this.files = files;
		this.mover = mover;
	}

	/**
	 * @return the bean
	 */
	public static NightlyCheck get() {
		return Context.get(NightlyCheck.class);
	}

	/** A row whose Store is wrong */
	private record Fix(long docId, String resource, Long was) {
	}

	/**
	 * @return the rows fixed
	 */
	public int run() {
		return StoreTracking.active() ? check(null) : 0;
	}

	/**
	 * @param onlyStore a Store's folder id: only rows that say they are in it, or are, are checked; null for all
	 * @return the rows fixed
	 */
	int check(Long onlyStore) {
		StoreOf.Folders folders = storeOf.folders();
		afterFolders.run();
		Map<Long, List<Fix>> wrong = new HashMap<>();
		jdbc.query("select f.hd_docid, f.hd_resource, f.hd_storeid, d.ld_id, d.ld_folderid, d.ld_docref, t.ld_folderid "
				+ "from hd_storage_file f left join ld_document d on d.ld_id = f.hd_docid "
				+ "left join ld_document t on t.ld_id = d.ld_docref", rs -> {
					long docId = rs.getLong(1);
					String resource = rs.getString(2);
					long was = rs.getLong(3);
					Long recorded = rs.wasNull() ? null : was;
					rs.getLong(4);
					if (rs.wasNull())
						return; // no document: not this check's to judge
					long own = rs.getLong(5);
					Long ownFolder = rs.wasNull() ? null : own;
					long ref = rs.getLong(6);
					boolean alias = !rs.wasNull() && ref != docId;
					long target = rs.getLong(7);
					Long targetFolder = rs.wasNull() ? null : target;
					Long folder = alias ? targetFolder : ownFolder;
					Long should = folder == null ? null : folders.of(folder).orElse(null);
					if (Objects.equals(recorded, should))
						return;
					if (onlyStore != null && !onlyStore.equals(recorded) && !onlyStore.equals(should))
						return;
					wrong.computeIfAbsent(should, k -> new ArrayList<>()).add(new Fix(docId, resource, recorded));
				});

		int fixed = 0;
		Map<Long, Optional<Long>> live = new HashMap<>();
		for (Map.Entry<Long, List<Fix>> entry : wrong.entrySet()) {
			Long should = entry.getKey();
			Set<Long> docIds = new LinkedHashSet<>();
			for (Fix fix : entry.getValue()) {
				// A fix that no longer holds is a document moved since the folders were read
				if (!Objects.equals(live.computeIfAbsent(fix.docId(), storeOf::of).orElse(null), should))
					continue;
				log.debug("{}", describe(fix.docId(), fix.resource(), fix.was(), should));
				docIds.add(fix.docId());
				fixed++;
			}
			if (!docIds.isEmpty())
				files.setStore(docIds, should);
		}
		if (fixed > 0)
			log.info("The Store check fixed {} files", fixed);
		// The mover lists only rows that wait, so one a write left misplaced without the mark is given it
		int unmarked = files.markWaiting(onlyStore);
		if (unmarked > 0)
			log.info("The Store check marked {} files as waiting to move", unmarked);
		if (fixed > 0 || unmarked > 0)
			mover.wake();
		return fixed;
	}

	/**
	 * @return the log line for a fix
	 */
	static String describe(long docId, String resource, Long was, Long should) {
		return "Store check: document " + docId + " file " + resource + " was in Store " + was + ", it is in Store "
				+ should;
	}
}
