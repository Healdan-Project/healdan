package com.logicaldoc.healdan.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Reads and writes {@code hd_local_value}: which attributes of a document
 * ({@code 'D'}) or folder ({@code 'F'}) were set on that object itself (spec
 * §6.7). JDBC on CE's data source, so it joins the caller's transaction.
 */
@Component
public class LocalValueDAO {

	public static final char DOCUMENT = 'D';

	public static final char FOLDER = 'F';

	/** Large id lists are queried in chunks */
	private static final int CHUNK = 500;

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	@Autowired
	public LocalValueDAO(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
	}

	public static LocalValueDAO get() {
		return Context.get(LocalValueDAO.class);
	}

	/**
	 * @param type {@code 'D'} or {@code 'F'}
	 * @param id the object
	 * @return the names of its local attributes
	 */
	public Set<String> locals(char type, long id) {
		return new HashSet<>(jdbc.queryForList(
				"select hd_attribute from hd_local_value where hd_objecttype = ? and hd_objectid = ?", String.class,
				String.valueOf(type), id));
	}

	/**
	 * @param type {@code 'D'} or {@code 'F'}
	 * @param ids the objects
	 * @return the names of their local attributes, for each object that has
	 *         some
	 */
	public Map<Long, Set<String>> locals(char type, Collection<Long> ids) {
		Map<Long, Set<String>> result = new HashMap<>();
		List<Long> all = new ArrayList<>(ids);
		for (int i = 0; i < all.size(); i += CHUNK)
			named.query(
					"select hd_objectid, hd_attribute from hd_local_value where hd_objecttype = :type and hd_objectid in (:ids)",
					new MapSqlParameterSource("type", String.valueOf(type)).addValue("ids",
							all.subList(i, Math.min(all.size(), i + CHUNK))),
					rs -> {
						result.computeIfAbsent(rs.getLong(1), id -> new HashSet<>()).add(rs.getString(2));
					});
		return result;
	}

	/**
	 * @param type {@code 'D'} or {@code 'F'}
	 * @param id the object
	 * @param attribute the attribute name
	 * @param local true to mark the value local, false to make it inherited
	 */
	public void setLocal(char type, long id, String attribute, boolean local) {
		jdbc.update("delete from hd_local_value where hd_objecttype = ? and hd_objectid = ? and hd_attribute = ?",
				String.valueOf(type), id, attribute);
		if (local)
			jdbc.update("insert into hd_local_value (hd_objecttype, hd_objectid, hd_attribute) values (?, ?, ?)",
					String.valueOf(type), id, attribute);
	}

	/**
	 * Gives an object the local marks of another, replacing its own
	 *
	 * @param type {@code 'D'} or {@code 'F'}
	 * @param fromId the object to copy from
	 * @param toId the object to copy to
	 */
	public void copy(char type, long fromId, long toId) {
		jdbc.update("delete from hd_local_value where hd_objecttype = ? and hd_objectid = ?", String.valueOf(type),
				toId);
		jdbc.update(
				"insert into hd_local_value (hd_objecttype, hd_objectid, hd_attribute) select hd_objecttype, ?, hd_attribute from hd_local_value where hd_objecttype = ? and hd_objectid = ?",
				toId, String.valueOf(type), fromId);
	}
}
