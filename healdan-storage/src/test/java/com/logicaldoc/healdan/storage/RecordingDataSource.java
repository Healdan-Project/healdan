// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

/**
 * A data source that records the SQL of every statement prepared on its connections, for tests of which statements a
 * class sends. A statement made with {@code createStatement} isn't seen: Spring's {@code JdbcTemplate} prepares every
 * statement that takes arguments, as all of {@code StoredFiles}' and {@code HeldCopies}' do.
 */
final class RecordingDataSource {

	private final List<String> statements = new CopyOnWriteArrayList<>();

	private final DataSource dataSource;

	RecordingDataSource(DataSource real) {
		this.dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					Object result = call(real, method, args);
					return result instanceof Connection connection ? recording(connection) : result;
				});
	}

	/** @return the data source to give the class under test */
	DataSource dataSource() {
		return dataSource;
	}

	/** @return the first word of each statement so far, in lower case: {@code select}, {@code insert}, … */
	List<String> verbs() {
		return statements.stream().map(s -> s.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT)).toList();
	}

	void clear() {
		statements.clear();
	}

	private Connection recording(Connection connection) {
		InvocationHandler handler = (proxy, method, args) -> {
			if ((method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) && args != null
					&& args.length > 0 && args[0] instanceof String sql)
				statements.add(sql);
			return call(connection, method, args);
		};
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class }, handler);
	}

	private static Object call(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
