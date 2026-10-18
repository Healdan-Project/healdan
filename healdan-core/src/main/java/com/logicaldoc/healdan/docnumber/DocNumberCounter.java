package com.logicaldoc.healdan.docnumber;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Issues doc #s from the {@code hd_counter} row named {@code docnumber}.
 * <p>
 * This is the one place where Healdan's JDBC does not join the caller's
 * transaction: each number is taken in a transaction of its own and committed
 * at once, so that concurrent creates never wait for each other's transactions
 * and a number is never given twice. A number taken for a store that then rolls
 * back is simply lost (numbers are never reused).
 * <p>
 * That transaction runs on a connection the counter takes from the pool at
 * startup and keeps, one number at a time. The caller of {@link #next()} holds
 * a pooled connection for its own transaction, so a counter that borrowed a
 * second one would wait for the pool, and with as many creates at once as the
 * pool has connections no create could finish. A kept connection that stops
 * working is replaced, from the pool, at the next call.
 */
@Component
public class DocNumberCounter {

	private static final Logger log = LoggerFactory.getLogger(DocNumberCounter.class);

	private static final String NAME = "docnumber";

	/** How long the database may take to confirm that the kept connection works */
	private static final int VALIDATION_SECONDS = 2;

	private final DataSource dataSource;

	/** The kept connection, in manual commit; null until taken or after a failure. Guarded by this. */
	private Connection connection;

	@Autowired
	public DocNumberCounter(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * @return the bean
	 */
	public static DocNumberCounter get() {
		return Context.get(DocNumberCounter.class);
	}

	/**
	 * Takes the connection at startup, when no request holds the pool's
	 */
	@PostConstruct
	public synchronized void open() {
		try {
			connection();
		} catch (SQLException e) {
			log.warn("Cannot take the doc # counter's connection yet, the first doc # will: {}", e.getMessage());
		}
	}

	/**
	 * Gives the connection back to the pool
	 */
	@PreDestroy
	public synchronized void close() {
		discard();
	}

	/**
	 * Takes the next doc #, in a transaction of its own.
	 *
	 * @return the number
	 */
	public long next() {
		try {
			return inTransaction("take the next doc #", c -> {
				try (PreparedStatement update = c
						.prepareStatement("update hd_counter set next_value = next_value + 1 where name = ?")) {
					update.setString(1, NAME);
					if (update.executeUpdate() != 1)
						throw new IllegalStateException("The doc # counter is missing");
				}
				return read(c, "select next_value - 1 from hd_counter where name = ?");
			});
		} catch (HealdanRuleException e) {
			// Nothing here refuses
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	/**
	 * @return the number the next document will get, without taking it
	 */
	public long peekNext() {
		try {
			return inTransaction("read the next doc #", c -> read(c, "select next_value from hd_counter where name = ?"));
		} catch (HealdanRuleException e) {
			// Nothing here refuses
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	/**
	 * Raises the next doc #, e.g. to continue after numbers moved in.
	 *
	 * @param newNext the number the next document gets
	 * @throws HealdanRuleException if it is below the current next number
	 */
	public void raiseNext(long newNext) throws HealdanRuleException {
		inTransaction("raise the next doc #", c -> {
			long current = read(c, "select next_value from hd_counter where name = ? for update");
			if (newNext < current)
				throw new HealdanRuleException("The next doc # can only be raised.");
			if (newNext > current)
				try (PreparedStatement update = c
						.prepareStatement("update hd_counter set next_value = ? where name = ?")) {
					update.setLong(1, newNext);
					update.setString(2, NAME);
					update.executeUpdate();
				}
			return null;
		});
	}

	/** A refusal is a {@link HealdanRuleException}, which is an SQLException */
	@FunctionalInterface
	private interface Work<T> {
		T run(Connection connection) throws SQLException;
	}

	/**
	 * Runs the work on the kept connection and commits it, or rolls it back if
	 * the work fails or refuses
	 */
	private synchronized <T> T inTransaction(String what, Work<T> work) throws HealdanRuleException {
		try {
			Connection c = connection();
			try {
				T result = work.run(c);
				c.commit();
				return result;
			} catch (SQLException | RuntimeException e) {
				c.rollback();
				throw e;
			}
		} catch (HealdanRuleException e) {
			throw e;
		} catch (SQLException e) {
			// Replaced at the next call
			discard();
			throw new IllegalStateException("Can't %s: %s".formatted(what, e.getMessage()), e);
		}
	}

	private static long read(Connection c, String sql) throws SQLException {
		try (PreparedStatement select = c.prepareStatement(sql)) {
			select.setString(1, NAME);
			try (ResultSet rs = select.executeQuery()) {
				if (!rs.next())
					throw new IllegalStateException("The doc # counter is missing");
				return rs.getLong(1);
			}
		}
	}

	/**
	 * @return the kept connection, taken anew from the pool if there is none
	 *         or it no longer works
	 */
	private Connection connection() throws SQLException {
		if (connection != null && !connection.isValid(VALIDATION_SECONDS))
			discard();
		if (connection == null) {
			Connection c = dataSource.getConnection();
			try {
				c.setAutoCommit(false);
			} catch (SQLException | RuntimeException e) {
				c.close();
				throw e;
			}
			connection = c;
		}
		return connection;
	}

	private void discard() {
		if (connection == null)
			return;
		try {
			connection.close();
		} catch (SQLException | RuntimeException e) {
			log.debug("Cannot close the doc # counter's connection: {}", e.getMessage());
		}
		connection = null;
	}
}
