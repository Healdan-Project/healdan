package com.logicaldoc.healdan.api;

import java.util.List;

import org.apache.cxf.bus.extension.ExtensionManagerBus;
import org.apache.cxf.transport.servlet.CXFNonSpringServlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;

/**
 * Serves Healdan's API at {@code /api/*} (spec section 10.2). Tomcat finds it
 * by its annotation in the plugin jar.
 * <p>
 * It runs on a CXF bus of its own. CE's {@code /services/*} servlet uses CXF's
 * Spring bus, and every {@code /services/rest} server hangs off that bus: a
 * servlet on it would answer them all under {@code /api/rest} too (spec
 * correction 4). CXF's list of services is hidden.
 */
@WebServlet(urlPatterns = "/api/*", loadOnStartup = 2, initParams = @WebInitParam(name = "hide-service-list-page", value = "true"))
public class ApiServlet extends CXFNonSpringServlet {

	private static final long serialVersionUID = 1L;

	private final transient List<Object> extraBeans;

	/**
	 * The constructor Tomcat uses.
	 */
	public ApiServlet() {
		this(List.of());
	}

	/**
	 * @param extraBeans objects to serve as resources besides the Spring beans,
	 *        for tests
	 */
	ApiServlet(List<Object> extraBeans) {
		this.extraBeans = List.copyOf(extraBeans);
	}

	/**
	 * Gives the servlet its own bus and starts the API's server on it.
	 * CXFNonSpringServlet's own version isn't called: it would replace the bus
	 * with one from {@code BusFactory}, which is CXF's Spring bus factory here.
	 */
	@Override
	protected void loadBus(ServletConfig servletConfig) {
		ExtensionManagerBus apiBus = new ExtensionManagerBus();
		apiBus.setId("healdan-api");
		apiBus.initialize();
		setBus(apiBus);
		ApiServer.start(getBus(), extraBeans);
	}
}
