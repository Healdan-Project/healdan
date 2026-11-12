// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * A data source that records each query run through it and how many rows the
 * caller read from it, so a test can tell what a page cost.
 */
final class CountingDataSource {

	/**
	 * A query and the rows read from its result
	 */
	static final class Query {

		final String sql;

		int rows;

		Query(String sql) {
			this.sql = sql;
		}

		@Override
		public String toString() {
			return rows + " rows: " + sql;
		}
	}

	private final List<Query> queries = new ArrayList<>();

	private final DataSource proxy;

	CountingDataSource(DataSource target) {
		proxy = wrap(DataSource.class, target, (method, args, result) -> method.getName().equals("getConnection")
				? connection((Connection) result)
				: result);
	}

	/**
	 * @return the data source to hand to the code under test
	 */
	DataSource dataSource() {
		return proxy;
	}

	/**
	 * @return the queries run since the last {@link #reset()}
	 */
	synchronized List<Query> queries() {
		return List.copyOf(queries);
	}

	synchronized void reset() {
		queries.clear();
	}

	private Connection connection(Connection target) {
		return wrap(Connection.class, target, (method, args, result) -> method.getName().equals("prepareStatement")
				? statement((PreparedStatement) result, (String) args[0])
				: result);
	}

	private PreparedStatement statement(PreparedStatement target, String sql) {
		return wrap(PreparedStatement.class, target, (method, args, result) -> {
			if (method.getName().equals("executeQuery")) {
				Query query = new Query(sql);
				synchronized (this) {
					queries.add(query);
				}
				return results((ResultSet) result, query);
			}
			return result;
		});
	}

	private ResultSet results(ResultSet target, Query query) {
		return wrap(ResultSet.class, target, (method, args, result) -> {
			if (method.getName().equals("next") && Boolean.TRUE.equals(result))
				query.rows++;
			return result;
		});
	}

	private interface After {
		Object apply(Method method, Object[] args, Object result) throws Exception;
	}

	@SuppressWarnings("unchecked")
	private static <T> T wrap(Class<T> type, T target, After after) {
		InvocationHandler handler = (p, method, args) -> {
			Object result;
			try {
				result = method.invoke(target, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
			return after.apply(method, args, result);
		};
		return (T) Proxy.newProxyInstance(CountingDataSource.class.getClassLoader(), new Class<?>[] { type }, handler);
	}
}
