// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.seed.SeedStep;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreOf;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.util.StringUtil;
import com.logicaldoc.util.spring.Context;

/**
 * Records what Original's folder already holds (spec section 5.3): writes Original's marker if it has none, and gives
 * each file with no row in {@code hd_storage_file} one, on backend 1, with its size and its document's Store. Only
 * missing rows are inserted, never updated, so a start that fails part-way resumes at the next. It takes no file's
 * lock: seeds run while the Spring context starts, before Tomcat opens its connectors and before CE's scheduler
 * starts, so no save can run beside it.
 * <p>
 * A folder it can't read is logged and skipped, not thrown: a seed that fails stops every start. Its files get no
 * row and still read, as a file with no row is read from Original. So is a file at a path CE's layout wouldn't make
 * for its digits ({@code 12/345/doc/1.0} spells document 12345, whose place is {@code 123/45/doc/1.0}), which would
 * otherwise collide with the real one. The files of documents that aren't in the database are counted in one warning
 * that names the first few, and each is logged at debug.
 */
@Component
public class RecordOriginalSeed implements SeedStep {

	private static final Logger log = LoggerFactory.getLogger(RecordOriginalSeed.class);

	private static final long ORIGINAL = StorageBackend.ORIGINAL;

	private static final int BATCH = 500;

	private static final int PROGRESS = 10_000;

	/** How many files of documents not in the database the warning names */
	private static final int ORPHANS_NAMED = 5;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Where the seed learns which store CE runs; tests set it */
	Supplier<Store> store = Store::get;

	private final JdbcTemplate jdbc;

	private final StoreOf storeOf;

	private final StorageSettings settings;

	@Autowired
	public RecordOriginalSeed(DataSource dataSource, StoreOf storeOf, StorageSettings settings) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.storeOf = storeOf;
		this.settings = settings;
	}

	@Override
	public String name() {
		return "storage-record-original";
	}

	/** Only a server that runs {@link com.logicaldoc.healdan.storage.HealdanStorage} records anything */
	@Override
	public boolean enabled() {
		return StorageSettingsSeed.enabledFor(store.get());
	}

	/** One file found in Original: a document's resource, its size and its path under Original */
	private record Found(long docId, String resource, long size, String path) {
	}

	/** What a walk counted */
	private static final class Totals {
		long seen;

		long recorded;

		long existing;

		long orphans;

		final List<String> orphansNamed = new ArrayList<>();

		final List<Path> unreadable = new ArrayList<>();
	}

	@Override
	public void run() throws Exception {
		Path root = Path.of(Context.get().getConfig().getProperty("store.1.dir")).toAbsolutePath().normalize();
		writeMarker(root);

		StoreOf.Folders folders = storeOf.folders();
		Map<Long, Long> backendOfStore = new HashMap<>();
		jdbc.query("select hd_storeid, hd_backendid from hd_storage_store where ld_deleted = 0",
				rs -> {
					backendOfStore.put(rs.getLong(1), rs.getLong(2));
				});

		Totals totals = new Totals();
		List<Found> batch = new ArrayList<>(BATCH);
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return !dir.equals(root) && dotName(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (dotName(file) || !attrs.isRegularFile())
					return FileVisitResult.CONTINUE;
				Found found = parse(root.relativize(file), attrs.size());
				if (found == null)
					return FileVisitResult.CONTINUE;
				totals.seen++;
				batch.add(found);
				if (batch.size() >= BATCH) {
					record(batch, folders, backendOfStore, totals);
					batch.clear();
				}
				if (totals.seen % PROGRESS == 0)
					log.info("Recording Original's files: {} found, {} recorded", totals.seen, totals.recorded);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path path, IOException e) {
				// A folder it can't open: skipped, and named below
				totals.unreadable.add(path);
				log.warn("Original's folder {} can't be read, so its files get no row: {}", path, e.toString());
				return FileVisitResult.CONTINUE;
			}
		});
		record(batch, folders, backendOfStore, totals);

		log.info("Recorded Original's files: {} found, {} recorded, {} already had a row, {} of documents that are "
				+ "not in the database, {} folders skipped", totals.seen, totals.recorded, totals.existing,
				totals.orphans, totals.unreadable.size());
		if (totals.orphans > 0)
			log.warn("{} files in Original's folder are of documents that are not in the database, so they get no "
					+ "Store; the first: {}", totals.orphans, String.join(", ", totals.orphansNamed));
		for (Path folder : totals.unreadable)
			log.warn("Original's folder {} was skipped", folder);
	}

	private static boolean dotName(Path path) {
		Path name = path.getFileName();
		return name != null && name.toString().startsWith(".");
	}

	/**
	 * @param relative a file's path under Original
	 * @return the file as CE's layout names it ({@code 123/45/doc/1.0} is document 12345's {@code 1.0}); null if it
	 *         isn't one, or is CE's own test file, document 0. Digits in other folders ({@code 12/345/doc/1.0}) are
	 *         warned of and skipped, as CE never puts a file there.
	 */
	static Found parse(Path relative, long size) {
		int count = relative.getNameCount();
		StringBuilder digits = new StringBuilder();
		int i = 0;
		while (i < count && relative.getName(i).toString().chars().allMatch(Character::isDigit)
				&& !relative.getName(i).toString().isEmpty())
			digits.append(relative.getName(i++));
		if (digits.length() == 0 || digits.length() > 18 || i >= count - 1 || !relative.getName(i).toString().equals("doc"))
			return null;
		long docId = Long.parseLong(digits.toString());
		if (docId == 0)
			return null;
		String path = relative.toString().replace('\\', '/');
		if (!relative.subpath(0, i + 1).toString().replace('\\', '/').equals(folderOf(docId))) {
			log.warn("{} in Original's folder isn't where CE keeps document {}'s files, so it gets no row", path, docId);
			return null;
		}
		String resource = relative.subpath(i + 1, count).toString().replace('\\', '/');
		return new Found(docId, resource, size, path);
	}

	/** @return the folder CE keeps a document's files in, as {@code AbstractStore.computeRelativePath} makes it */
	private static String folderOf(long docId) {
		return StringUtil.split(Long.toString(docId), '/', 3) + "/doc";
	}

	/**
	 * Writes the marker if there is none, unless the folder looks unmounted: no marker, no file, and documents that
	 * aren't deleted. The first start with an NFS share that didn't mount would otherwise mark the empty mount point,
	 * and the guard against it would be gone for good (section 10.2).
	 */
	private void writeMarker(Path root) throws IOException {
		if (Files.exists(root.resolve(LocalBackend.MARKER)))
			return;
		Long documents = jdbc.queryForObject("select count(*) from ld_document where ld_deleted = 0", Long.class);
		if (documents != null && documents > 0 && !holdsFiles(root)) {
			String installId = settings.installId();
			throw new IllegalStateException("Original's folder looks unmounted: it has no " + LocalBackend.MARKER
					+ " and no file, but the database has " + documents + " documents. Check that the folder named by "
					+ "store.1.dir (" + root + ") is mounted. If every document really has lost its file, write the "
					+ "marker by hand: a file " + LocalBackend.MARKER + " in that folder holding the line \""
					+ installId + " " + ORIGINAL + "\", then start again.");
		}
		new LocalBackend(ORIGINAL, settings.installId(), root, true);
		log.info("Wrote Original's marker in {}", root);
	}

	/** @return whether the folder holds any file but dot names, or can't be read all the way (so it may) */
	private static boolean holdsFiles(Path root) throws IOException {
		if (!Files.isDirectory(root))
			return false;
		boolean[] found = new boolean[1];
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return !dir.equals(root) && dotName(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (dotName(file))
					return FileVisitResult.CONTINUE;
				found[0] = true;
				return FileVisitResult.TERMINATE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path path, IOException e) {
				found[0] = true;
				return FileVisitResult.TERMINATE;
			}
		});
		return found[0];
	}

	/** Inserts a row for each file of the batch that has none */
	private void record(List<Found> batch, StoreOf.Folders folders, Map<Long, Long> backendOfStore, Totals totals) {
		if (batch.isEmpty())
			return;
		Set<Long> docIds = new HashSet<>();
		batch.forEach(f -> docIds.add(f.docId()));
		Set<String> existing = existingRows(docIds);
		Map<Long, Long> storeOfDoc = storesOf(docIds, folders);

		Timestamp now = new Timestamp(System.currentTimeMillis());
		List<Object[]> inserts = new ArrayList<>();
		for (Found f : batch) {
			if (existing.contains(f.docId() + "/" + f.resource())) {
				totals.existing++;
				continue;
			}
			Long storeId = storeOfDoc.get(f.docId());
			if (!storeOfDoc.containsKey(f.docId())) {
				totals.orphans++;
				if (totals.orphansNamed.size() < ORPHANS_NAMED)
					totals.orphansNamed.add(f.path());
				log.debug("Document {} is not in the database, so its file {} gets no Store", f.docId(), f.resource());
			}
			boolean inPlace = backendOfStore.getOrDefault(storeId, ORIGINAL) == ORIGINAL;
			inserts.add(new Object[] { f.docId(), f.resource(), ORIGINAL, storeId, f.size(), RANDOM.nextLong(), now,
					inPlace ? null : now });
		}
		if (!inserts.isEmpty())
			jdbc.batchUpdate("insert into hd_storage_file (hd_docid, hd_resource, hd_backendid, hd_storeid, hd_size, "
					+ "hd_write, hd_stored, hd_waiting, hd_attempts, hd_stuck) values (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)",
					inserts);
		totals.recorded += inserts.size();
	}

	/** @return the {@code docId/resource} of the rows the documents already have */
	private Set<String> existingRows(Set<Long> docIds) {
		Set<String> rows = new HashSet<>();
		jdbc.query("select hd_docid, hd_resource from hd_storage_file where hd_docid in (" + marks(docIds.size()) + ")",
				rs -> {
					rows.add(rs.getLong(1) + "/" + rs.getString(2));
				}, docIds.toArray());
		return rows;
	}

	/**
	 * @return each document that is in the database, to its Store (null for none; an alias stands for its target, as
	 *         {@link StoreOf#of} has it); a document that isn't there has no entry
	 */
	private Map<Long, Long> storesOf(Set<Long> docIds, StoreOf.Folders folders) {
		Map<Long, Long[]> documents = new HashMap<>();
		jdbc.query("select ld_id, ld_folderid, ld_docref from ld_document where ld_id in (" + marks(docIds.size()) + ")",
				rs -> {
					long folder = rs.getLong(2);
					Long folderId = rs.wasNull() ? null : folder;
					long ref = rs.getLong(3);
					Long refId = rs.wasNull() ? null : ref;
					documents.put(rs.getLong(1), new Long[] { folderId, refId });
				}, docIds.toArray());

		Set<Long> targets = new HashSet<>();
		documents.forEach((id, d) -> {
			if (d[1] != null && !d[1].equals(id))
				targets.add(d[1]);
		});
		Map<Long, Long> targetFolders = new HashMap<>();
		if (!targets.isEmpty())
			jdbc.query("select ld_id, ld_folderid from ld_document where ld_id in (" + marks(targets.size()) + ")",
					rs -> {
						long folder = rs.getLong(2);
						Long folderId = rs.wasNull() ? null : folder;
						targetFolders.put(rs.getLong(1), folderId);
					}, targets.toArray());

		Map<Long, Long> stores = new HashMap<>();
		documents.forEach((id, d) -> {
			Long folder = d[1] != null && !d[1].equals(id) ? targetFolders.get(d[1]) : d[0];
			stores.put(id, folder == null ? null : folders.of(folder).orElse(null));
		});
		return stores;
	}

	private static String marks(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}
}
