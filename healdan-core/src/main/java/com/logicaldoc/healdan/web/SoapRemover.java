// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Removes LogicalDOC's SOAP web services (SOAP removal spec, section 3).
 * After every plugin context's XML is read and before any bean is built, it
 * removes each bean definition that would build a SOAP server: a JAX-WS
 * {@code Endpoint}, which {@code jaxws:endpoint} makes and upstream's
 * {@code context-webservice.xml} declares for all nine services, or a CXF
 * {@code ServerFactoryBean}, which {@code jaxws:server} makes. It matches by
 * type, as Spring predicts it, never by id, so a SOAP endpoint upstream adds
 * later goes too, whether XML, a factory method or a {@code FactoryBean}
 * declares it. {@code SoapRemoverTest} checks that CXF serves no SOAP at all,
 * which catches one made some other way.
 * <p>
 * CE's REST servers are CXF {@code JAXRSServerFactoryBean}s, neither of the
 * two, and stay, as do the {@code Soap*Service} classes they extend. There's
 * no setting: SOAP never comes back.
 */
@Component
public class SoapRemover implements BeanDefinitionRegistryPostProcessor, BeanClassLoaderAware {

	/**
	 * The types whose definitions build a SOAP server, by name, since
	 * healdan-core doesn't compile against CXF
	 */
	static final List<String> SOAP_TYPES = List.of("jakarta.xml.ws.Endpoint",
			"org.apache.cxf.frontend.ServerFactoryBean");

	private static final Logger log = LoggerFactory.getLogger(SoapRemover.class);

	private ClassLoader classLoader = ClassUtils.getDefaultClassLoader();

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		if (!(registry instanceof ListableBeanFactory factory)) {
			log.warn("Can't remove LogicalDOC's SOAP web services from a {}", registry.getClass().getName());
			return;
		}

		List<String> removed = new ArrayList<>();
		for (String typeName : SOAP_TYPES) {
			Class<?> type = load(typeName);
			if (type == null) {
				log.warn("Can't load {}, so SOAP servers of that type aren't removed", typeName);
				continue;
			}
			for (String name : factory.getBeanNamesForType(type, true, false)) {
				String beanName = BeanFactoryUtils.transformedBeanName(name);
				if (registry.containsBeanDefinition(beanName)) {
					registry.removeBeanDefinition(beanName);
					removed.add(beanName);
				}
			}
		}
		if (!removed.isEmpty())
			log.info("Removed LogicalDOC's SOAP web services: {}", String.join(", ", removed));
	}

	/**
	 * Loads a class without initialising it
	 *
	 * @param className the class's name
	 *
	 * @return the class, or {@code null} when it can't be loaded
	 */
	private Class<?> load(String className) {
		try {
			return ClassUtils.forName(className, classLoader);
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}
}
