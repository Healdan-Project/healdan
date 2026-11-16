// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * Looking a bean up by type never makes Spring create {@code applicationBaseTransactionProxy}, the template for
 * transactional proxies in the webapp's {@code context.xml}. It has no target, so creating it fails. While it wasn't
 * abstract, every lookup by type that missed Spring's cache ({@code Context.get(Class)}) created it to learn what its
 * {@code FactoryBean} makes, and each failure cleared that cache, so the next lookup missed it too. Spring 6.2 records
 * the failure in a set without a lock, which another thread's bean creation sets to null, so two lookups at once could
 * throw a {@code NullPointerException}: on the smoke stack, {@code GET /api/v1/documents/{n}} answered 500 from
 * {@code MatterDAO.get()} (Docker image workflow run 43602076929, 2026-11-14).
 */
public class TransactionProxyTemplateTest {

	private static final int THREADS = 8;

	private static final int LOOKUPS = 200_000;

	/** The types the lookups ask for, one plain bean each */
	private static final List<Class<?>> TYPES = List.of(First.class, Second.class, Third.class, Fourth.class);

	@Test
	public void aLookupByTypeCreatesNoTemplate() {
		Factory factory = factory();
		factory.getBean(First.class);
		factory.getBean(Second.class);
		assertEquals("failures Spring kept while it looked beans up by type", 0, factory.suppressed.get());
	}

	@Test
	public void lookupsByTypeAtOnceAllSucceed() throws Exception {
		Factory factory = factory();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		try {
			List<Future<?>> runs = new ArrayList<>();
			for (int t = 0; t < THREADS; t++) {
				int offset = t;
				runs.add(pool.submit(() -> {
					for (int i = 0; i < LOOKUPS && failure.get() == null; i++) {
						try {
							factory.getBean(TYPES.get((i + offset) % TYPES.size()));
						} catch (Throwable thrown) {
							failure.compareAndSet(null, thrown);
						}
					}
				}));
			}
			for (Future<?> run : runs)
				run.get(2, TimeUnit.MINUTES);
		} finally {
			pool.shutdownNow();
		}
		assertNull("a lookup by type failed: " + failure.get(), failure.get());
	}

	/**
	 * A factory with the webapp's own definition of the template, the transaction manager it names, and one plain bean
	 * of each type in {@link #TYPES}, its configuration frozen as the application context leaves it
	 */
	private static Factory factory() {
		DefaultListableBeanFactory webapp = new DefaultListableBeanFactory();
		new XmlBeanDefinitionReader(webapp).loadBeanDefinitions(new FileSystemResource("src/main/resources/context.xml"));

		Factory factory = new Factory();
		factory.registerBeanDefinition("applicationBaseTransactionProxy",
				webapp.getBeanDefinition("applicationBaseTransactionProxy"));
		factory.registerBeanDefinition("transactionManager", new RootBeanDefinition(NoTransactions.class));
		for (Class<?> type : TYPES)
			factory.registerBeanDefinition(type.getSimpleName(), new RootBeanDefinition(type));
		factory.freezeConfiguration();
		factory.preInstantiateSingletons();
		return factory;
	}

	/** Counts the failures Spring keeps as suppressed while it creates beans */
	private static class Factory extends DefaultListableBeanFactory {
		private final AtomicInteger suppressed = new AtomicInteger();

		@Override
		protected void onSuppressedException(Exception ex) {
			suppressed.incrementAndGet();
			super.onSuppressedException(ex);
		}
	}

	public static class NoTransactions implements PlatformTransactionManager {
		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void commit(TransactionStatus status) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void rollback(TransactionStatus status) {
			throw new UnsupportedOperationException();
		}
	}

	public static class First {
	}

	public static class Second {
	}

	public static class Third {
	}

	public static class Fourth {
	}
}
