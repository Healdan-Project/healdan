// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.lang.reflect.Method;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

import jakarta.ws.rs.Path;

/**
 * The paths the API's resources declare, as {@link Path} gives them: one
 * rule for the admin API's filter and for the tests that compare the routes
 * with the description files.
 */
public final class RoutePaths {

	/** The admin API's path, which it and every path under it is (Admin app spec section 9.1) */
	private static final String ADMIN = "/v1/admin";

	private RoutePaths() {
	}

	/**
	 * @param type a resource class, or a proxy of one
	 * @param method one of its resource methods, or null for none
	 * @return the route's declared path, the class's {@link Path} and the
	 *         method's, {@link #normalize normalized}: never the request's
	 */
	public static String declared(Class<?> type, Method method) {
		Path classPath = AnnotationUtils.findAnnotation(ClassUtils.getUserClass(type), Path.class);
		Path methodPath = method != null ? AnnotationUtils.findAnnotation(method, Path.class) : null;
		return normalize((classPath != null ? classPath.value() : "") + "/"
				+ (methodPath != null ? methodPath.value() : ""));
	}

	/**
	 * @param path a normalized path
	 * @return true if it's the admin API's: {@code /v1/admin} or under it
	 */
	public static boolean admin(String path) {
		return path.equals(ADMIN) || path.startsWith(ADMIN + "/");
	}

	/**
	 * Joins slashes, drops a final slash and writes each template as
	 * {@code {name}}, without its regular expression.
	 *
	 * @param path a path as {@link Path} declares it, or a join of two
	 * @return the path, starting with a slash
	 */
	public static String normalize(String path) {
		StringBuilder normalized = new StringBuilder();
		int i = 0;
		while (i < path.length()) {
			char c = path.charAt(i);
			if (c == '{') {
				int depth = 1;
				int end = i + 1;
				while (end < path.length() && depth > 0) {
					if (path.charAt(end) == '{')
						depth++;
					else if (path.charAt(end) == '}')
						depth--;
					end++;
				}
				String template = path.substring(i + 1, end - 1);
				int colon = template.indexOf(':');
				normalized.append('{').append((colon < 0 ? template : template.substring(0, colon)).trim()).append('}');
				i = end;
			} else {
				if (c != '/' || normalized.isEmpty() || normalized.charAt(normalized.length() - 1) != '/')
					normalized.append(c);
				i++;
			}
		}
		if (normalized.length() > 1 && normalized.charAt(normalized.length() - 1) == '/')
			normalized.setLength(normalized.length() - 1);
		if (normalized.isEmpty() || normalized.charAt(0) != '/')
			normalized.insert(0, '/');
		return normalized.toString();
	}
}
