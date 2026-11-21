// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.api.ApiServer;
import com.logicaldoc.healdan.api.Public;
import com.logicaldoc.healdan.api.RouteCoverageTest;
import com.logicaldoc.healdan.api.RoutePaths;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Every route under {@code /v1/admin} is behind {@link AdminFilter} (Admin app
 * spec section 9.1): its class carries {@link AdminRoute}, which is what the
 * filter looks for, and every class that carries it is under
 * {@code /v1/admin}. The classes are every one in the API's package, and
 * below it, with {@link Path} or {@link AdminRoute}, served or not, test-only
 * ones included.
 * <p>
 * Each class is judged by itself, by its own {@link AdminRoute}, as
 * {@link AdminFilter} asks the area of the class a request matched: classes
 * that share one root are each in their own area, such as
 * {@code /v1/admin/stores}, whose {@code AdminStoresResource} is
 * {@code STORES} and {@code AdminStoreStorageResource}, a Store's backend and
 * stuck files, {@code STORAGE}.
 */
public class AdminRoutesTest {

	private static final String PACKAGE = ApiServer.class.getPackageName();

	@Test
	public void testEveryAdminRouteCarriesTheAnnotation() {
		List<Class<?>> resources = resources();
		// The scan sees the test-only admin resource, so it sees the classes it should
		assertTrue(resources.toString(), resources.contains(AdminFilterTest.Ping.class));

		Set<String> wrong = new TreeSet<>();
		for (Class<?> type : resources)
			wrong.addAll(problems(type));
		assertEquals(Set.of(), wrong);
	}

	@Test
	public void testTheCheckFindsWhatsWrong() {
		assertEquals(Set.of(), problems(AdminFilterTest.Ping.class));
		assertEquals(
				Set.of(AdminFilterTest.Unmarked.class.getName() + " GET /v1/admin/test/unmarked has no @AdminRoute"),
				problems(AdminFilterTest.Unmarked.class));
		assertEquals(Set.of(Unmarked.class.getName() + " GET /v1/admin/unmarked has no @AdminRoute"),
				problems(Unmarked.class));
		assertEquals(Set.of(Outside.class.getName() + " carries @AdminRoute outside /v1/admin"),
				problems(Outside.class));
		assertEquals(Set.of(Hidden.class.getName() + " GET /v1/admin/hidden has no @AdminRoute"),
				problems(Hidden.class));
		assertEquals(Set.of(Opened.class.getName() + " GET /v1/admin/opened is @Public"), problems(Opened.class));
		assertEquals(
				Set.of(Located.class.getName()
						+ " /v1/admin/located/{part} is a sub-resource locator, whose resource AdminFilter doesn't see"),
				problems(Located.class));
	}

	/**
	 * @return what's wrong with a resource class's admin routes: a route under
	 *         {@code /v1/admin} whose class has no {@link AdminRoute}, which
	 *         is {@link Public}, or which locates a sub-resource; or the
	 *         annotation on a class that isn't under {@code /v1/admin}
	 */
	private static Set<String> problems(Class<?> type) {
		Set<String> problems = new TreeSet<>();
		Path classPath = type.getAnnotation(Path.class);
		boolean marked = type.isAnnotationPresent(AdminRoute.class);
		if (marked && (classPath == null || !RoutePaths.admin(RoutePaths.normalize(classPath.value()))))
			problems.add(type.getName() + " carries @AdminRoute outside /v1/admin");
		if (classPath == null)
			return problems;
		for (Method method : type.getMethods()) {
			String verb = RouteCoverageTest.httpMethod(method);
			Path methodPath = method.getAnnotation(Path.class);
			if (verb == null && methodPath == null)
				continue;
			String path = RoutePaths
					.normalize(classPath.value() + "/" + (methodPath != null ? methodPath.value() : ""));
			if (!RoutePaths.admin(path))
				continue;
			String route = type.getName() + " " + (verb != null ? verb + " " : "") + path;
			if (!marked)
				problems.add(route + " has no @AdminRoute");
			if (verb == null)
				problems.add(route + " is a sub-resource locator, whose resource AdminFilter doesn't see");
			if (method.isAnnotationPresent(Public.class))
				problems.add(route + " is @Public");
		}
		return problems;
	}

	/**
	 * @return every class in the API's package or below it, main or test, with
	 *         {@link Path} or {@link AdminRoute}, but this test's own examples
	 *         and {@link AdminFilterTest.Unmarked}, left unmarked on purpose to
	 *         show the filter refuses it
	 */
	private static List<Class<?>> resources() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				// Nested classes and abstract ones too: JAX-RS serves whatever it's given
				return definition.getMetadata().isIndependent();
			}
		};
		scanner.addIncludeFilter(new AnnotationTypeFilter(Path.class));
		scanner.addIncludeFilter(new AnnotationTypeFilter(AdminRoute.class));
		List<Class<?>> resources = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(PACKAGE)) {
			Class<?> type = ClassUtils.resolveClassName(definition.getBeanClassName(),
					AdminRoutesTest.class.getClassLoader());
			if (type.getEnclosingClass() != AdminRoutesTest.class && type != AdminFilterTest.Unmarked.class)
				resources.add(type);
		}
		return resources;
	}

	/* Examples of what the check refuses */

	@Path("/v1/admin/unmarked")
	public static class Unmarked {
		@GET
		public String get() {
			return "";
		}
	}

	@Path("/v1/outside")
	@AdminRoute(AdminArea.DASHBOARD)
	public static class Outside {
		@GET
		public String get() {
			return "";
		}
	}

	@Path("/")
	public static class Hidden {
		@GET
		@Path("v1/admin/hidden")
		public String get() {
			return "";
		}
	}

	@Path("/v1/admin/opened")
	@AdminRoute(AdminArea.DASHBOARD)
	public static class Opened {
		@GET
		@Public
		public String get() {
			return "";
		}
	}

	@Path("/v1/admin/located")
	@AdminRoute(AdminArea.DASHBOARD)
	public static class Located {
		@Path("{part}")
		public Object part() {
			return new Object();
		}
	}
}
