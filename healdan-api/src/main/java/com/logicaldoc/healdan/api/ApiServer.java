package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxrs.model.wadl.WadlGenerator;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import com.logicaldoc.util.spring.Context;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * Builds the API's JAX-RS server (spec section 10.2). Its resources are the
 * Spring beans in this package that carry {@link Path}: a resource is added by
 * writing a {@code @Component} here, and it's then served under {@code /api}.
 */
@Component
public class ApiServer implements ApplicationContextAware {

	private static final String PACKAGE = ApiServer.class.getPackageName();

	private static final String WADL_GENERATOR = "org.apache.cxf.jaxrs.model.wadl.WadlGenerator";

	private ApplicationContext applicationContext;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * Starts the server on a bus.
	 *
	 * @param bus the bus, which must be the API's own (see {@link ApiServlet})
	 * @param extraBeans objects to serve as resources besides the Spring beans
	 * @return the started server, which the bus's shutdown destroys
	 */
	public static Server start(Bus bus, List<Object> extraBeans) {
		List<Object> resources = new ArrayList<>(resources());
		resources.addAll(extraBeans);

		JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();
		factory.setBus(bus);
		factory.setAddress("/");
		factory.setServiceBeans(resources);
		factory.setProviders(providers());
		factory.setProperties(properties());

		// Nothing CXF does while building may fall back on the default bus
		Bus previous = BusFactory.getAndSetThreadDefaultBus(bus);
		try {
			return factory.create();
		} finally {
			BusFactory.setThreadDefaultBus(previous);
		}
	}

	/**
	 * @return the providers every request goes through: JSON, problems, CORS
	 *         (before matching, so a preflight needs no token), the token
	 *         check, and the upload limit and clean-up
	 */
	static List<Object> providers() {
		List<Object> providers = new ArrayList<>();
		providers.add(ApiJson.provider());
		providers.add(new ProblemMapper());
		providers.add(new CorsFilter());
		providers.add(new TokenFilter());
		providers.add(new UploadFilter());
		// CXF answers ?_wadl with a WADL document whenever its generator is on the classpath, as the webservice
		// plugin puts it; the API is described by openapi.yaml only
		if (ClassUtils.isPresent(WADL_GENERATOR, ApiServer.class.getClassLoader()))
			providers.add(new NoWadl());
		return providers;
	}

	/**
	 * Takes the place of CXF's WADL generator, and ignores {@code ?_wadl}.
	 */
	static final class NoWadl extends WadlGenerator {

		@Override
		public void filter(ContainerRequestContext context) {
			// The request goes on as if the parameter weren't there
		}
	}

	/**
	 * Upload parts stream to temporary files above 1 MB (spec section 10.2).
	 * Their size limit follows {@code upload.maxsize} on every request
	 * ({@link UploadFilter}).
	 */
	private static Map<String, Object> properties() {
		Map<String, Object> properties = new HashMap<>();
		properties.put("attachment-directory", System.getProperty("java.io.tmpdir"));
		properties.put("attachment-memory-threshold", "1048576");
		return properties;
	}

	/**
	 * @return the Spring beans in this package, or one below it, that carry
	 *         {@link Path}
	 */
	static List<Object> resources() {
		return Context.get(ApiServer.class).findResources();
	}

	private List<Object> findResources() {
		List<Object> resources = new ArrayList<>();
		// By type, without creating the beans of other packages: CE has lazy ones that can't be created alone
		for (String name : applicationContext.getBeanNamesForType(Object.class, false, false)) {
			Class<?> type = applicationContext.getType(name, false);
			if (type == null)
				continue;
			Class<?> userType = ClassUtils.getUserClass(type);
			if ((userType.getPackageName().equals(PACKAGE) || userType.getPackageName().startsWith(PACKAGE + "."))
					&& userType.isAnnotationPresent(Path.class))
				resources.add(applicationContext.getBean(name));
		}
		return resources;
	}
}
