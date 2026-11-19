// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.util.Savepoints;
import com.logicaldoc.util.spring.Context;

/**
 * The administration record, {@code hd_admin_event} (Admin app spec section
 * 9.3): a row for each administrative change, with when, who and from where,
 * and a JSON detail of the fields it changed with their old and new values.
 * A secret's change has no value ({@link Change#secret()}).
 * <p>
 * A change that's one database transaction records with {@link #record}
 * inside it, so the two stand or fall together. A change that isn't, such as
 * one with network or file steps, records with {@link #recordAfter} once it
 * has succeeded, and stands if the record fails.
 */
@Component
public class AdminEvents {

	private static final Logger log = LoggerFactory.getLogger(AdminEvents.class);

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The most characters the username, the address and the target's name hold */
	static final int TEXT_LIMIT = 255;

	private static final String INSERT = "insert into hd_admin_event (hd_tenantid, hd_date, hd_userid, hd_username,"
			+ " hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail)"
			+ " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	private final JdbcTemplate jdbc;

	@Autowired
	public AdminEvents(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean in the application context
	 */
	public static AdminEvents get() {
		return Context.get(AdminEvents.class);
	}

	/**
	 * Records a change in the caller's transaction, if one is open, so that a
	 * rollback takes both; with none open, the row commits on its own. The
	 * time is the application's clock, to the millisecond, and the address is
	 * {@link AdminContext#address()}: none outside an admin request, as from
	 * the Classic Interface. A username, an address or a target's name too
	 * long for its column is cut to fit, rather than fail the change.
	 *
	 * @param event the change
	 * @param actor who made it; the row takes their tenant
	 * @throws org.springframework.dao.DataAccessException if the row can't be
	 *         written, which fails the caller's transaction
	 */
	public void record(AdminEvent event, User actor) {
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(actor, "actor");
		Timestamp date = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
		String username = fit(Objects.requireNonNullElse(actor.getUsername(), ""));
		String address = fit(AdminContext.address());
		String detail = detail(event.changes());
		jdbc.update(INSERT, ps -> {
			int i = 1;
			ps.setLong(i++, actor.getTenantId());
			ps.setTimestamp(i++, date);
			ps.setLong(i++, actor.getId());
			ps.setString(i++, username);
			ps.setString(i++, address);
			ps.setString(i++, event.area().name());
			ps.setString(i++, event.action());
			ps.setString(i++, event.targetKind());
			if (event.targetId() != null)
				ps.setLong(i++, event.targetId());
			else
				ps.setNull(i++, Types.BIGINT);
			ps.setString(i++, fit(event.targetName()));
			ps.setString(i, detail);
		});
	}

	/**
	 * Records a change that isn't one database transaction, straight after it
	 * succeeded. It never throws an exception: if the record fails, the change
	 * stands, and the failure is logged at error level, naming the action and
	 * the target's kind and id, never a value. Inside a caller's transaction
	 * the row goes in a savepoint, so that a failed insert leaves the
	 * transaction able to go on.
	 *
	 * @param event the change
	 * @param actor who made it
	 */
	public void recordAfter(AdminEvent event, User actor) {
		try {
			Savepoints.run(jdbc, () -> record(event, actor));
		} catch (RuntimeException e) {
			String what = event == null ? "an administrative change"
					: "%s of %s%s".formatted(event.action(), event.targetKind(),
							event.targetId() != null ? " " + event.targetId() : "");
			// A database's message can quote the row: the error line names the causes' classes and codes only
			log.error("The administration record of {} failed, and the change stands: {}", what, causes(e));
			log.debug("The administration record of {} failed", what, e);
		}
	}

	/**
	 * @return the changes as JSON, each field by its name in order, as
	 *         {@code {"before":...,"after":...}}, or {@code {"secret":true}} for a
	 *         secret's; null for no change
	 */
	static String detail(Map<String, Change> changes) {
		if (changes.isEmpty())
			return null;
		ObjectNode detail = JSON.createObjectNode();
		changes.forEach((name, change) -> {
			ObjectNode field = detail.putObject(name);
			if (change.isSecret()) {
				field.put("secret", true);
			} else {
				field.put("before", change.before());
				field.put("after", change.after());
			}
		});
		try {
			return JSON.writeValueAsString(detail);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("The administration record's detail can't be written", e);
		}
	}

	/**
	 * @return the text, cut to {@link #TEXT_LIMIT} characters if it is longer,
	 *         never between the two halves of a character beyond the Basic
	 *         Multilingual Plane; null for null
	 */
	static String fit(String text) {
		if (text == null || text.length() <= TEXT_LIMIT)
			return text;
		int end = Character.isHighSurrogate(text.charAt(TEXT_LIMIT - 1)) ? TEXT_LIMIT - 1 : TEXT_LIMIT;
		return text.substring(0, end);
	}

	/**
	 * @return the failure and its causes, each one's class joined by
	 *         {@code " <- "}, with a database failure's SQL state and vendor
	 *         code; never a message, which can quote the row's values
	 */
	private static String causes(Throwable failure) {
		return ExceptionUtils.getThrowableList(failure).stream()
				.map(t -> t instanceof SQLException sql && (sql.getSQLState() != null || sql.getErrorCode() != 0)
						? "%s [SQL state %s, code %d]".formatted(t.getClass().getName(), sql.getSQLState(),
								sql.getErrorCode())
						: t.getClass().getName())
				.collect(Collectors.joining(" <- "));
	}
}
