// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.healdan.rules.HealdanHooksImpl;
import com.logicaldoc.util.spring.Context;

/**
 * Pins that Healdan starts after CE's "context" bean, because the seeds use
 * {@code Context.get()}, and after {@code HealdanHooksImpl}, whose bean
 * registers Healdan's hooks, which a seed's folder delete, move or restore
 * goes through.
 */
public class HealdanStartupOrderTest extends AbstractHealdanTestCase {

	@Test
	public void testStartupDependsOnContextBean() throws Exception {
		// Context keeps its Spring context in a private field and offers no getter
		Field field = Context.class.getDeclaredField("applicationContext");
		field.setAccessible(true);
		ConfigurableApplicationContext ctx = (ConfigurableApplicationContext) field.get(Context.get());
		String[] deps = ctx.getBeanFactory().getDependenciesForBean("healdanStartup");
		assertTrue(Arrays.toString(deps), Arrays.asList(deps).contains("context"));
		assertTrue(Arrays.toString(deps), Arrays.asList(deps).contains("healdanHooksImpl"));
		// The name @DependsOn gives is the hooks' bean, which registered them
		assertSame(ctx.getBean("healdanHooksImpl"), HealdanHooks.get());
		assertTrue(ctx.getBean("healdanHooksImpl") instanceof HealdanHooksImpl);
	}
}
