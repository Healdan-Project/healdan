package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;
import org.springframework.util.ClassUtils;

import com.logicaldoc.healdan.AbstractHealdanTestCase;

import io.swagger.v3.oas.models.OpenAPI;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

/**
 * The server implements exactly the {@code /v1} operations that
 * {@code openapi.yaml} describes (spec section 4.6), and the file says which
 * of them need no token. Only the docs page, {@code /docs} and {@code /docs/{file}}, the Office add-in's
 * files, {@code /addin/...}, and the sign-in pages, {@code /signin/...}, aren't described: a resource anywhere
 * else, such as {@code /v2} or a mistyped {@code /vl}, fails.
 */
public class RouteCoverageTest extends AbstractHealdanTestCase {

	@Test
	public void testEveryRouteIsDescribed() {
		assertEquals(described().keySet(), implemented().keySet());
	}

	@Test
	public void testPublicRoutesAreDescribedAsPublic() {
		Map<String, Boolean> described = described();
		Map<String, Boolean> implemented = implemented();
		for (Map.Entry<String, Boolean> route : implemented.entrySet())
			if (described.containsKey(route.getKey()))
				assertEquals("@Public versus security: [] for " + route.getKey(), route.getValue(),
						described.get(route.getKey()));
	}

	/**
	 * @return "METHOD /v1/path" of each resource method, and whether it's
	 *         {@link Public}
	 */
	private static Map<String, Boolean> implemented() {
		Map<String, Boolean> routes = new TreeMap<>();
		for (Object resource : ApiServer.resources()) {
			Class<?> type = ClassUtils.getUserClass(resource);
			String classPath = type.getAnnotation(Path.class).value();
			for (Method method : type.getMethods()) {
				String verb = httpMethod(method);
				if (verb == null)
					continue;
				Path methodPath = method.getAnnotation(Path.class);
				String path = normalize(classPath + "/" + (methodPath != null ? methodPath.value() : ""));
				// The docs page, the Office add-in's files (AddinResource) and the sign-in pages (SignInResource) are
				// web pages, not JSON API
				if (!path.equals("/docs") && !path.equals("/docs/{file}") && !path.startsWith("/addin/")
						&& !path.startsWith("/signin/"))
					routes.put(verb + " " + path, method.isAnnotationPresent(Public.class));
			}
		}
		return routes;
	}

	/**
	 * @return "METHOD /v1/path" of each operation in the file, and whether it
	 *         says {@code security: []}
	 */
	private static Map<String, Boolean> described() {
		OpenAPI api = OpenApiFileTest.parse().getOpenAPI();
		Map<String, Boolean> routes = new TreeMap<>();
		api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((verb, operation) -> routes
				.put(verb.name() + " " + path, operation.getSecurity() != null && operation.getSecurity().isEmpty())));
		return routes;
	}

	private static String httpMethod(Method method) {
		for (Annotation annotation : method.getAnnotations()) {
			HttpMethod verb = annotation.annotationType().getAnnotation(HttpMethod.class);
			if (verb != null)
				return verb.value();
		}
		return null;
	}

	/**
	 * Joins slashes, drops a final slash and writes each template as
	 * {@code {name}}, without its regular expression.
	 */
	static String normalize(String path) {
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
