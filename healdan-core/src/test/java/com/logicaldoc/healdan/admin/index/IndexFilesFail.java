// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;

import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;

/**
 * Makes CE's reading of some documents' files fail, as a storage backend that can't be reached, or a damaged file,
 * makes it fail inside {@code DocumentManager.parseDocument}: the store {@code DocumentManager} reads files through is
 * wrapped, so the documents named fail with the failure given and the others are read as before, until
 * {@link #reset}.
 */
final class IndexFilesFail {

	/** The store DocumentManager had before {@link #when}, null while it has its own */
	private static Store kept;

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
		Store failing = (Store) Proxy.newProxyInstance(IndexFilesFail.class.getClassLoader(),
				new Class<?>[] { Store.class }, (proxy, method, args) -> {
					if (method.getName().equals("getStream") && args != null && args.length == 1
							&& args[0] instanceof StoreResource resource && failures.containsKey(resource.getDocId()))
						throw failures.get(resource.getDocId());
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		field.set(manager, failing);
	}

	/** Puts DocumentManager's own store back */
	static synchronized void reset() throws Exception {
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
