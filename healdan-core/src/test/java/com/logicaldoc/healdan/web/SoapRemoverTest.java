// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.apache.cxf.Bus;
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

	/** The addresses of the nine SOAP services upstream's context-webservice.xml declares */
	private static final Set<String> SOAP_ADDRESSES = Set.of("/Auth", "/Document", "/Folder", "/Search", "/Tag",
			"/Bookmark", "/System", "/Security", "/DocumentMetadata");

	/** CE's REST servers in the same file, which stay */
	private static final List<String> REST_SERVERS = List.of("restAuth", "restDocument", "restFolder", "restSearch",
			"restTag", "restBookmark", "restSystem", "restDocumentMetadata", "commentservice");

	/**
	 * The real context, which loads the web services' XML: no SOAP endpoint is
	 * built, none is running, and CE's REST servers are all there
	 */
	@Test
	public void testTheContextHasNoSoapServices() {
		assertEquals(0, Context.get().getBeansOfType(Endpoint.class).size());

		for (String id : REST_SERVERS)
			assertTrue(id, Context.get().containsBean(id));

		List<String> addresses = Context.get(Bus.class).getExtension(ServerRegistry.class).getServers().stream()
				.map(server -> server.getEndpoint().getEndpointInfo().getAddress()).toList();
		assertTrue(addresses.toString(), addresses.stream().noneMatch(SOAP_ADDRESSES::contains));
		assertTrue(addresses.toString(), addresses.contains("/rest/auth"));
	}

	/**
	 * By type, whatever the id: a JAX-WS endpoint and a JAX-WS server factory
	 * go; a JAX-RS server and a definition with no class name stay
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

		new SoapRemover().postProcessBeanDefinitionRegistry(factory);

		assertEquals(List.of("aRestServer", "madeByAFactory"), List.of(factory.getBeanDefinitionNames()));
	}
}
