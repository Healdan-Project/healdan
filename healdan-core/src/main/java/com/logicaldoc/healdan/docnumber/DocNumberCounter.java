package com.logicaldoc.healdan.docnumber;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.util.spring.Context;

/**
 * Issues doc #s from the {@code hd_counter} row named {@code docnumber}.
 * <p>
 * This is the one place where Healdan's JDBC does not join the caller's
 * transaction: each number is taken on a connection of its own and committed
 * at once, so that concurrent creates never wait for each other's transactions
 * and a number is never given twice. A number taken for a store that then rolls
 * back is simply lost (numbers are never reused).
 */
@Component
public class DocNumberCounter {

	private static final String NAME = "docnumber";

	private final DataSource dataSource;

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
	 * Takes the next doc #, in a transaction of its own.
	 *
	 * @return the number
	 */
	public long next() {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				long number;
				try (PreparedStatement update = connection
						.prepareStatement("update hd_counter set next_value = next_value + 1 where name = ?")) {
					update.setString(1, NAME);
					if (update.executeUpdate() != 1)
						throw new IllegalStateException("The doc # counter is missing");
				}
				try (PreparedStatement select = connection
						.prepareStatement("select next_value - 1 from hd_counter where name = ?")) {
					select.setString(1, NAME);
					try (ResultSet rs = select.executeQuery()) {
						if (!rs.next())
							throw new IllegalStateException("The doc # counter is missing");
						number = rs.getLong(1);
					}
				}
				connection.commit();
				return number;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			} finally {
				connection.setAutoCommit(autoCommit);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Can't take the next doc #: " + e.getMessage(), e);
		}
	}

	/**
	 * @return the number the next document will get, without taking it
	 */
	public long peekNext() {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement select = connection.prepareStatement("select next_value from hd_counter where name = ?")) {
			select.setString(1, NAME);
			try (ResultSet rs = select.executeQuery()) {
				if (!rs.next())
					throw new IllegalStateException("The doc # counter is missing");
				return rs.getLong(1);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Can't read the next doc #: " + e.getMessage(), e);
		}
	}

	/**
	 * Raises the next doc #, e.g. to continue after numbers moved in.
	 *
	 * @param newNext the number the next document gets
	 * @throws HealdanRuleException if it is below the current next number
	 */
	public void raiseNext(long newNext) throws HealdanRuleException {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				long current;
				try (PreparedStatement select = connection
						.prepareStatement("select next_value from hd_counter where name = ? for update")) {
					select.setString(1, NAME);
					try (ResultSet rs = select.executeQuery()) {
						if (!rs.next())
							throw new IllegalStateException("The doc # counter is missing");
						current = rs.getLong(1);
					}
				}
				if (newNext < current)
					throw new HealdanRuleException("The next doc # can only be raised.");
				if (newNext > current)
					try (PreparedStatement update = connection
							.prepareStatement("update hd_counter set next_value = ? where name = ?")) {
						update.setLong(1, newNext);
						update.setString(2, NAME);
						update.executeUpdate();
					}
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			} finally {
				connection.setAutoCommit(autoCommit);
			}
		} catch (HealdanRuleException e) {
			throw e;
		} catch (SQLException e) {
			throw new IllegalStateException("Can't raise the next doc #: " + e.getMessage(), e);
		}
	}
}
