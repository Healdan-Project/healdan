// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Reads and writes {@code hd_storage_file}, one row for each resource of each document: which backend holds its bytes
 * and how its move to its Store's backend is going. JDBC on CE's data source, so it joins the caller's transaction.
 * <p>
 * Every per-file write ({@link #recordWrite}, {@link #moved}, {@link #delete}) is called holding the file's lock. The
 * lock ends before the caller's transaction commits, so another transaction may insert a row that {@code recordWrite}
 * read as missing; its insert then runs in a savepoint ({@link Savepoints}) and becomes an update, which PostgreSQL
 * forgives inside a transaction.
 */
@Component
public class StoredFiles {

	/** Original, which a Store with no row of its own uses */
	private static final long ORIGINAL = StorageBackend.ORIGINAL;

	private static final String COLUMNS = "f.hd_docid, f.hd_resource, f.hd_backendid, f.hd_storeid, f.hd_size, f.hd_write, "
			+ "f.hd_stored, f.hd_waiting, f.hd_attempts, f.hd_lasterror, f.hd_stuck, f.hd_nexttry";

	/** The backend a row's Store uses: Original's for no Store, or a Store with no row */
	private static final String TARGET = "coalesce((select s.hd_backendid from hd_storage_store s "
			+ "where s.hd_storeid = f.hd_storeid and s.ld_deleted = 0), " + ORIGINAL + ")";

	private static final int BATCH = 500;

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final RowMapper<StoredFile> MAPPER = (rs, i) -> {
		long storeId = rs.getLong(4);
		Long store = rs.wasNull() ? null : storeId;
		Timestamp stored = rs.getTimestamp(7);
		Timestamp waiting = rs.getTimestamp(8);
		Timestamp nextTry = rs.getTimestamp(12);
		return new StoredFile(rs.getLong(1), rs.getString(2), rs.getLong(3), store, rs.getLong(5), rs.getLong(6),
				stored == null ? null : new Date(stored.getTime()), waiting == null ? null : new Date(waiting.getTime()),
				rs.getInt(9), rs.getString(10), rs.getInt(11) == 1,
				nextTry == null ? null : new Date(nextTry.getTime()));
	};

	private final JdbcTemplate jdbc;

	@Autowired
	public StoredFiles(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static StoredFiles get() {
		return Context.get(StoredFiles.class);
	}

	/**
	 * @param docId a document
	 * @param resource one of its resources
	 * @return its row; none if it has none
	 */
	public Optional<StoredFile> find(long docId, String resource) {
		return jdbc.query("select " + COLUMNS + " from hd_storage_file f where f.hd_docid = ? and f.hd_resource = ?",
				MAPPER, docId, resource).stream().findFirst();
	}

	/**
	 * @param docId a document
	 * @return the rows of all its resources, by name
	 */
	public List<StoredFile> list(long docId) {
		return jdbc.query("select " + COLUMNS + " from hd_storage_file f where f.hd_docid = ? order by f.hd_resource",
				MAPPER, docId);
	}

	/**
	 * Records that a file's bytes were written, replacing its row if it has one, and gives the row a new random write
	 * id: unlike a counter, it never repeats when a row is deleted and made again. The row's attempts and failure are
	 * cleared, as a write supersedes any move of the old bytes.
	 * <p>
	 * {@code hd_waiting} is cleared when the write lands on the backend the Store uses (Original's for no Store, or a
	 * Store with no row), and set to now, only if empty, when it lands anywhere else, staging included.
	 *
	 * @param docId the document
	 * @param resource its resource
	 * @param backendId where the bytes landed; 0 for staging
	 * @param storeId the document's Store; null for none
	 * @param size the size in bytes
	 * @return the new write id
	 */
	public long recordWrite(long docId, String resource, long backendId, Long storeId, long size) {
		return recordWrite(docId, resource, backendId, storeId, size, find(docId, resource).isPresent(),
				backendId == storeBackend(storeId));
	}

	/**
	 * Records a write as {@link #recordWrite(long, String, long, Long, long)} does, for a caller that has read the row
	 * already, holding the file's lock, and knows its Store's backend.
	 * <p>
	 * A new row is inserted, never first tried as an update: on MariaDB, whose isolation is REPEATABLE READ, an UPDATE
	 * that matches no row takes a gap lock that lasts until its transaction commits. CE's folder copy stores every new
	 * file in one transaction, so such a lock would hold every other save of a new file in that gap (the higher
	 * document ids) until the copy commits, each waiting while it holds its file's lock, which may share a stripe with
	 * a file the copy stores next. A plain read takes no lock, and an insert into a gap only an insert intention lock,
	 * which other inserts don't wait for. An insert that finds the key taken, by a transaction that hadn't committed
	 * when the row was read, updates that row instead.
	 *
	 * @param rowExists whether the file has a row, as the caller read it holding the file's lock
	 * @param inPlace whether the bytes landed on the backend the Store uses ({@link #storeBackend})
	 * @return the new write id
	 */
	public long recordWrite(long docId, String resource, long backendId, Long storeId, long size, boolean rowExists,
			boolean inPlace) {
		long write = RANDOM.nextLong();
		Timestamp now = new Timestamp(System.currentTimeMillis());
		// Only a delete that didn't take the file's lock could have taken a row that was read
		if (rowExists && update(docId, resource, backendId, storeId, size, write, now, inPlace))
			return write;
		if (!Savepoints.insertIfAbsent(jdbc,
				"insert into hd_storage_file (hd_docid, hd_resource, hd_backendid, hd_storeid, hd_size, "
						+ "hd_write, hd_stored, hd_waiting, hd_attempts, hd_stuck) values (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)",
				docId, resource, backendId, storeId, size, write, now, inPlace ? null : now))
			update(docId, resource, backendId, storeId, size, write, now, inPlace);
		return write;
	}

	/** @return whether the file's row was there to be given the write */
	private boolean update(long docId, String resource, long backendId, Long storeId, long size, long write,
			Timestamp now, boolean inPlace) {
		String waiting = inPlace ? "null" : "coalesce(hd_waiting, ?)";
		List<Object> arguments = new ArrayList<>(List.of(backendId));
		arguments.add(storeId);
		arguments.addAll(List.of(size, write, now));
		if (!inPlace)
			arguments.add(now);
		arguments.addAll(List.of(docId, resource));
		return jdbc.update("update hd_storage_file set hd_backendid = ?, hd_storeid = ?, hd_size = ?, "
				+ "hd_write = ?, hd_stored = ?, hd_waiting = " + waiting + ", hd_attempts = 0, "
				+ "hd_lasterror = null, hd_stuck = 0, hd_nexttry = null where hd_docid = ? and hd_resource = ?",
				arguments.toArray()) == 1;
	}

	/**
	 * @param storeId a Store; null for none
	 * @return the backend the Store uses: Original's for no Store, or a Store with no row
	 */
	public long storeBackend(Long storeId) {
		if (storeId == null)
			return ORIGINAL;
		List<Long> backends = jdbc.queryForList(
				"select hd_backendid from hd_storage_store where hd_storeid = ? and ld_deleted = 0", Long.class,
				storeId);
		return backends.isEmpty() ? ORIGINAL : backends.get(0);
	}

	/**
	 * Records that a file's bytes now sit on another backend, only if the row is still the write the mover read, so a
	 * save or a delete during the move wins. Clears {@code hd_waiting}, the attempts and the failure.
	 *
	 * @param docId the document
	 * @param resource its resource
	 * @param write the write id the mover moved
	 * @param toBackend the backend the bytes are now on
	 * @return false if the row had changed or gone, and nothing was changed
	 */
	public boolean moved(long docId, String resource, long write, long toBackend) {
		return jdbc.update("update hd_storage_file set hd_backendid = ?, hd_waiting = null, hd_attempts = 0, "
				+ "hd_lasterror = null, hd_stuck = 0, hd_nexttry = null "
				+ "where hd_docid = ? and hd_resource = ? and hd_write = ?", toBackend, docId, resource, write) == 1;
	}

	/**
	 * Gives documents' rows a new Store. A row that now sits on another backend than its Store's gets
	 * {@code hd_waiting} set to now if it has none, and one that sits on its Store's backend has it cleared. A bulk
	 * update that takes no file's lock: its race with a mover's commit at worst leaves a row misplaced for the next
	 * pass, which the nightly check backs up.
	 *
	 * @param docIds the documents
	 * @param storeId their new Store; null for none
	 */
	public void setStore(Collection<Long> docIds, Long storeId) {
		long target = storeBackend(storeId);
		Timestamp now = new Timestamp(System.currentTimeMillis());
		List<Long> ids = new ArrayList<>(docIds);
		for (int from = 0; from < ids.size(); from += BATCH) {
			List<Long> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
			String in = String.join(", ", batch.stream().map(id -> "?").toList());

			List<Object> arguments = new ArrayList<>();
			arguments.add(storeId);
			arguments.addAll(batch);
			jdbc.update("update hd_storage_file set hd_storeid = ? where hd_docid in (" + in + ")",
					arguments.toArray());

			arguments = new ArrayList<>();
			arguments.add(now);
			arguments.addAll(batch);
			arguments.add(target);
			jdbc.update("update hd_storage_file set hd_waiting = coalesce(hd_waiting, ?) where hd_docid in (" + in
					+ ") and hd_backendid <> ?", arguments.toArray());

			arguments = new ArrayList<>(batch);
			arguments.add(target);
			jdbc.update("update hd_storage_file set hd_waiting = null where hd_docid in (" + in
					+ ") and hd_backendid = ?", arguments.toArray());
		}
	}

	/**
	 * @return the plaintext sizes of every file, summed
	 */
	public long totalSize() {
		Long total = jdbc.queryForObject("select coalesce(sum(hd_size), 0) from hd_storage_file", Long.class);
		return total == null ? 0L : total;
	}

	/**
	 * Deletes a file's row; one that isn't there is left alone.
	 *
	 * @param docId the document
	 * @param resource its resource
	 */
	public void delete(long docId, String resource) {
		jdbc.update("delete from hd_storage_file where hd_docid = ? and hd_resource = ?", docId, resource);
	}

	/**
	 * Lists the rows the mover should move: those whose backend isn't their Store's (Original's for no Store or a
	 * Store with no row), staged ones first and then the newest documents. Left out, in the SQL so that one paused
	 * backend's rows can't fill every page and starve the rest: rows whose source or target is in
	 * {@code unavailableBackends}, rows marked stuck, and rows not yet due.
	 *
	 * @param limit the most rows to list
	 * @param unavailableBackends the backends that are paused or not yet checked
	 * @return the rows
	 */
	public List<StoredFile> misplaced(int limit, Collection<Long> unavailableBackends) {
		List<Object> arguments = new ArrayList<>();
		arguments.add(new Timestamp(System.currentTimeMillis()));
		StringBuilder sql = new StringBuilder("select " + COLUMNS + " from hd_storage_file f "
				+ "where f.hd_stuck = 0 and (f.hd_nexttry is null or f.hd_nexttry <= ?) and f.hd_backendid <> "
				+ TARGET);
		if (!unavailableBackends.isEmpty()) {
			String in = String.join(", ", unavailableBackends.stream().map(id -> "?").toList());
			sql.append(" and f.hd_backendid not in (").append(in).append(") and ").append(TARGET)
					.append(" not in (").append(in).append(")");
			arguments.addAll(unavailableBackends);
			arguments.addAll(unavailableBackends);
		}
		sql.append(" order by case when f.hd_backendid = 0 then 0 else 1 end, f.hd_docid desc, f.hd_resource limit ?");
		arguments.add(limit);
		return jdbc.query(sql.toString(), MAPPER, arguments.toArray());
	}

	/** The failure that marks a file stuck: the tenth in a row */
	public static final int STUCK_AT = 10;

	/**
	 * Records a failed move of a file, only if the row is still the write that failed, so a failed move of an old write
	 * can't count against a new one. Adds one to the attempts, keeps the error and the next try, and marks the row stuck
	 * at the {@link #STUCK_AT}th failure. Called holding the file's lock, like the other per-file writes. The caller
	 * gives the next try for the count this writes, from the retry schedule (1, 2, 4, 8, 16, 32, 60, 60 and 60
	 * minutes); this class only stores it.
	 *
	 * @param docId the document
	 * @param resource its resource
	 * @param write the write id the failed move was of
	 * @param error the failure's message, cut to what the column holds
	 * @param nextTry given the failures in a row, this one included, when the mover may try again; null for none
	 * @return the failures in a row it recorded, this one included; 0 if the row had changed or gone, and nothing was
	 *         changed
	 */
	public int recordFailure(long docId, String resource, long write, String error, IntFunction<Date> nextTry) {
		Optional<StoredFile> row = find(docId, resource);
		if (row.isEmpty() || row.get().write() != write)
			return 0;
		int attempts = row.get().attempts() + 1;
		boolean stuck = attempts >= STUCK_AT;
		String message = error == null || error.length() <= 4000 ? error : error.substring(0, 4000);
		Date next = nextTry.apply(attempts);
		int updated = jdbc.update("update hd_storage_file set hd_attempts = ?, hd_lasterror = ?, hd_stuck = ?, "
				+ "hd_nexttry = ? where hd_docid = ? and hd_resource = ? and hd_write = ?", attempts, message,
				stuck ? 1 : 0, next == null ? null : new Timestamp(next.getTime()), docId, resource, write);
		return updated == 1 ? attempts : 0;
	}

	/**
	 * Puts a Store's stuck files back to waiting, for Retry: clears the stuck mark, the attempts, the error and the
	 * next try.
	 *
	 * @param storeId the Store; null for the files with no Store
	 * @return how many files were put back
	 */
	public int retry(Long storeId) {
		String set = "update hd_storage_file set hd_stuck = 0, hd_attempts = 0, hd_lasterror = null, hd_nexttry = null "
				+ "where hd_stuck = 1 and ";
		return storeId == null ? jdbc.update(set + "hd_storeid is null")
				: jdbc.update(set + "hd_storeid = ?", storeId);
	}

	/**
	 * @param cutoff a time
	 * @return how many files have waited, since their row's backend stopped matching their Store's, from before it
	 */
	public long waitingBefore(Date cutoff) {
		Long count = jdbc.queryForObject(
				"select count(*) from hd_storage_file where hd_waiting is not null and hd_waiting < ?", Long.class,
				new Timestamp(cutoff.getTime()));
		return count == null ? 0 : count;
	}

	/**
	 * @return for each Store with files, and for the files with no Store (store id null, first), how many files there
	 *         are, how many wait, how many are stuck, and their total size
	 */
	public List<StoreStats> statsByStore() {
		return jdbc.query("select hd_storeid, count(*), sum(case when hd_waiting is not null then 1 else 0 end), "
				+ "sum(case when hd_stuck = 1 then 1 else 0 end), sum(hd_size) from hd_storage_file "
				+ "group by hd_storeid order by case when hd_storeid is null then 0 else 1 end, hd_storeid", (rs, i) -> {
					long storeId = rs.getLong(1);
					Long store = rs.wasNull() ? null : storeId;
					return new StoreStats(store, rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
				});
	}
}
