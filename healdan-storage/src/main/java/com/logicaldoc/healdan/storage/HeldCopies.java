// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Reads and writes {@code hd_storage_held}, the copies a backend keeps after a delete until their purge date. JDBC on
 * CE's data source. Holding a location again moves its purge date on, so a location is held once at a time.
 */
@Component
public class HeldCopies {

	private static final RowMapper<HeldCopy> MAPPER = (rs, i) -> new HeldCopy(rs.getLong(1), rs.getString(2),
			rs.getLong(3), rs.getString(4), new Date(rs.getTimestamp(5).getTime()),
			new Date(rs.getTimestamp(6).getTime()));

	private final JdbcTemplate jdbc;

	@Autowired
	public HeldCopies(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static HeldCopies get() {
		return Context.get(HeldCopies.class);
	}

	/**
	 * Holds a location until a date, replacing the hold it has already. Called holding the file's lock, which covers
	 * its location.
	 * <p>
	 * The hold is read first, then inserted or updated, never first tried as an update: on MariaDB an UPDATE that
	 * matches no row takes a gap lock until its transaction commits, and saves and deletes run inside CE's
	 * transactions (see {@link StoredFiles#recordWrite(long, String, long, Long, long, boolean)}).
	 *
	 * @param backendId the backend
	 * @param location the object key, or the path under {@code .held/}
	 * @param docId the document it belonged to
	 * @param resource its resource
	 * @param until the purge date
	 */
	public void hold(long backendId, String location, long docId, String resource, Date until) {
		Timestamp now = new Timestamp(System.currentTimeMillis());
		Timestamp purge = new Timestamp(until.getTime());
		Long holds = jdbc.queryForObject(
				"select count(*) from hd_storage_held where hd_backendid = ? and hd_location = ?", Long.class,
				backendId, location);
		if (holds != null && holds > 0)
			jdbc.update("update hd_storage_held set hd_docid = ?, hd_resource = ?, hd_held = ?, hd_purge = ? "
					+ "where hd_backendid = ? and hd_location = ?", docId, resource, now, purge, backendId, location);
		else
			jdbc.update("insert into hd_storage_held (hd_backendid, hd_location, hd_docid, hd_resource, hd_held, "
					+ "hd_purge) values (?, ?, ?, ?, ?, ?)", backendId, location, docId, resource, now, purge);
	}

	/**
	 * @param backendId the backend
	 * @param location the object key, or the path under {@code .held/}
	 * @return the hold of the location, if there is one
	 */
	public Optional<HeldCopy> find(long backendId, String location) {
		return jdbc.query("select hd_backendid, hd_location, hd_docid, hd_resource, hd_held, hd_purge "
				+ "from hd_storage_held where hd_backendid = ? and hd_location = ?", MAPPER, backendId, location)
				.stream().findFirst();
	}

	/**
	 * @param now the time to compare with
	 * @return the holds whose purge date has come, the oldest first
	 */
	public List<HeldCopy> due(Date now) {
		return jdbc.query("select hd_backendid, hd_location, hd_docid, hd_resource, hd_held, hd_purge "
				+ "from hd_storage_held where hd_purge <= ? order by hd_purge, hd_backendid, hd_location", MAPPER,
				new Timestamp(now.getTime()));
	}

	/**
	 * Drops a hold, as when its copy is purged or put back; one that isn't there is left alone.
	 *
	 * @param backendId the backend
	 * @param location the object key, or the path under {@code .held/}
	 */
	public void drop(long backendId, String location) {
		jdbc.update("delete from hd_storage_held where hd_backendid = ? and hd_location = ?", backendId, location);
	}
}
