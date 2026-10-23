package com.logicaldoc.healdan.api;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;

/**
 * Base class of the API tests: Healdan's test database and context, and a
 * real {@link ApiServlet} driven through Spring's servlet mocks. Every
 * response a test receives is checked against {@code openapi.yaml}
 * ({@link Contract}).
 */
public abstract class ApiTestCase extends AbstractHealdanTestCase {

	protected final HealdanFixtures fx = new HealdanFixtures();

	private MockServletContext servletContext;

	private ApiServlet servlet;

	/**
	 * @return objects to serve as resources besides the Spring beans, such as
	 *         test-only resources; none by default
	 */
	protected List<Object> extraBeans() {
		return List.of();
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		servletContext = new MockServletContext();
		MockServletConfig config = new MockServletConfig(servletContext, "api");
		// The init parameters Tomcat passes
		for (WebInitParam param : ApiServlet.class.getAnnotation(WebServlet.class).initParams())
			config.addInitParameter(param.name(), param.value());
		servlet = new ApiServlet(extraBeans());
		try {
			servlet.init(config);
		} catch (ServletException e) {
			throw new IllegalStateException("The API servlet didn't start", e);
		}
	}

	@Override
	public void tearDown() throws IOException {
		if (servlet != null)
			servlet.destroy();
		super.tearDown();
	}

	/**
	 * @param path the request path from the context root, with any query
	 *        string, e.g. {@code /api/v1/server}
	 * @return the call, to send
	 */
	protected ApiCall get(String path) {
		return call("GET", path);
	}

	protected ApiCall post(String path) {
		return call("POST", path);
	}

	protected ApiCall patch(String path) {
		return call("PATCH", path);
	}

	protected ApiCall delete(String path) {
		return call("DELETE", path);
	}

	protected ApiCall options(String path) {
		return call("OPTIONS", path);
	}

	private ApiCall call(String method, String path) {
		return new ApiCall(servlet, servletContext, method, path);
	}
}
