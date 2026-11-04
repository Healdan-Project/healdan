// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.SoapBinding;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.endpoint.ServerRegistry;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxws.EndpointImpl;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

import jakarta.xml.ws.Endpoint;

public class SoapRemoverTest extends AbstractHealdanTestCase {

	/** CE's REST servers in the same file, which stay */
	private static final List<String> REST_SERVERS = List.of("restAuth", "restDocument", "restFolder", "restSearch",
			"restTag", "restBookmark", "restSystem", "restDocumentMetadata", "commentservice");

	/**
	 * The real context, which loads the web services' XML: no SOAP endpoint is
	 * built, CXF serves nothing with a SOAP binding, at any address, and CE's
	 * REST servers are all there
	 */
	@Test
	public void testTheContextHasNoSoapServices() {
		assertEquals(0, Context.get().getBeansOfType(Endpoint.class).size());

		for (String id : REST_SERVERS)
			assertTrue(id, Context.get().containsBean(id));

		List<Server> servers = Context.get(Bus.class).getExtension(ServerRegistry.class).getServers();
		List<String> soap = servers.stream().filter(server -> server.getEndpoint().getBinding() instanceof SoapBinding)
				.map(SoapRemoverTest::address).toList();
		assertTrue(soap.toString(), soap.isEmpty());
		List<String> addresses = servers.stream().map(SoapRemoverTest::address).toList();
		assertTrue(addresses.toString(), addresses.contains("/rest/auth"));
	}

	private static String address(Server server) {
		return server.getEndpoint().getEndpointInfo().getAddress();
	}

	/**
	 * By type, as Spring predicts it, whatever the id: a JAX-WS endpoint, one a
	 * factory method makes and a JAX-WS server factory go; a JAX-RS server, and
	 * the server a factory bean's method makes from it, stay
	 */
	@Test
	public void testRemovesSoapServersOnly() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.registerBeanDefinition("anEndpoint", new RootBeanDefinition(EndpointImpl.class));
		factory.registerBeanDefinition("aSoapServer", new RootBeanDefinition(JaxWsServerFactoryBean.class));
		factory.registerBeanDefinition("aRestServer", new RootBeanDefinition(JAXRSServerFactoryBean.class));
		RootBeanDefinition noClass = new RootBeanDefinition();
		noClass.setFactoryBeanName("aRestServer");
		noClass.setFactoryMethodName("create");
		factory.registerBeanDefinition("madeByAFactory", noClass);
		RootBeanDefinition fromAMethod = new RootBeanDefinition(SoapRemoverTest.class);
		fromAMethod.setFactoryMethodName("endpoint");
		factory.registerBeanDefinition("anEndpointFromAMethod", fromAMethod);

		new SoapRemover().postProcessBeanDefinitionRegistry(factory);

		assertEquals(List.of("aRestServer", "madeByAFactory"), List.of(factory.getBeanDefinitionNames()));
	}

	/** The factory method of testRemovesSoapServersOnly's anEndpointFromAMethod, which Spring never calls */
	static Endpoint endpoint() {
		throw new UnsupportedOperationException();
	}
}
