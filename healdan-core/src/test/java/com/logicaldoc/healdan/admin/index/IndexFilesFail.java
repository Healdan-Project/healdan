// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;

import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;

/**
 * Makes CE's reading of some documents' files fail, as a storage backend that can't be reached, or a damaged file,
 * makes it fail inside {@code DocumentManager.parseDocument}: the store {@code DocumentManager} reads files through is
 * wrapped, so the documents named fail with the failure given and the others are read as before, until
 * {@link #reset}. Each document's reads through it, failed or not, are counted ({@link #reads}), and a document can be
 * read as an empty file, as one with no text ({@link #servedEmpty}).
 */
final class IndexFilesFail {

	/** The store DocumentManager had before {@link #when}, null while it has its own */
	private static Store kept;

	/** How many times each document's file has been asked for since {@link #when} */
	private static final Map<Long, AtomicInteger> READS = new ConcurrentHashMap<>();

	/** The documents whose files are read as empty ones */
	private static final Set<Long> EMPTY = ConcurrentHashMap.newKeySet();

	private IndexFilesFail() {
	}

	/**
	 * @param failures each document whose file can't be read, with what reading it fails with
	 */
	static synchronized void when(Map<Long, IOException> failures) throws Exception {
		DocumentManager manager = target();
		Field field = field();
		if (kept == null)
			kept = (Store) field.get(manager);
		Store real = kept;
		READS.clear();
		EMPTY.clear();
		Store failing = (Store) Proxy.newProxyInstance(IndexFilesFail.class.getClassLoader(),
				new Class<?>[] { Store.class }, (proxy, method, args) -> {
					if (method.getName().equals("getStream") && args != null && args.length == 1
							&& args[0] instanceof StoreResource resource) {
						READS.computeIfAbsent(resource.getDocId(), id -> new AtomicInteger()).incrementAndGet();
						if (failures.containsKey(resource.getDocId()))
							throw failures.get(resource.getDocId());
						if (EMPTY.contains(resource.getDocId()))
							return new ByteArrayInputStream(new byte[0]);
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		field.set(manager, failing);
	}

	/**
	 * The document's file is read as an empty one, with no text, until {@link #reset}; after {@link #when}.
	 *
	 * @param docId the document
	 */
	static void servedEmpty(long docId) {
		EMPTY.add(docId);
	}

	/**
	 * @param docId a document
	 * @return how many times its file has been asked for since {@link #when}
	 */
	static int reads(long docId) {
		AtomicInteger reads = READS.get(docId);
		return reads != null ? reads.get() : 0;
	}

	/** Puts DocumentManager's own store back */
	static synchronized void reset() throws Exception {
		READS.clear();
		EMPTY.clear();
		if (kept == null)
			return;
		field().set(target(), kept);
		kept = null;
	}

	private static Field field() throws NoSuchFieldException {
		Field field = DocumentManager.class.getDeclaredField("store");
		field.setAccessible(true);
		return field;
	}

	private static DocumentManager target() throws Exception {
		Object bean = DocumentManager.get();
		while (AopUtils.isAopProxy(bean) && bean instanceof Advised advised)
			bean = advised.getTargetSource().getTarget();
		return (DocumentManager) bean;
	}
}
