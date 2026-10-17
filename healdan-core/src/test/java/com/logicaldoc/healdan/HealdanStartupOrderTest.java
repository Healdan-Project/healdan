package com.logicaldoc.healdan;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.logicaldoc.util.spring.Context;

/**
 * Pins that Healdan starts after CE's "context" bean, because the seeds use
 * {@code Context.get()}.
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
	}
}
