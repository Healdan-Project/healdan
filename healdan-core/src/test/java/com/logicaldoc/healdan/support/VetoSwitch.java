package com.logicaldoc.healdan.support;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Turns on the veto of the test listeners and hooks at one check point, the
 * place a Healdan rule would refuse the operation: a listener method or a
 * {@code HealdanHooks} method, named by the constants below. While it's on,
 * that check point throws {@code HealdanVetoException("veto")}, after asking
 * {@link CacheEvictor} to evict on rollback the folders and documents it
 * refuses, as every Healdan rule must (plan, D3).
 */
public final class VetoSwitch {

	public static final String MESSAGE = "veto";

	public static final String DOCUMENT_BEFORE_STORE = "DocumentListener.beforeStore";

	public static final String FOLDER_BEFORE_STORE = "FolderListener.beforeStore";

	public static final String BEFORE_DOCUMENT_MOVE = "HealdanHooks.beforeDocumentMove";

	public static final String BEFORE_DOCUMENT_RENAME = "HealdanHooks.beforeDocumentRename";

	public static final String BEFORE_DOCUMENT_UPDATE = "HealdanHooks.beforeDocumentUpdate";

	public static final String BEFORE_DOCUMENT_RESTORE = "HealdanHooks.beforeDocumentRestore";

	public static final String BEFORE_FOLDER_DELETE_TREE = "HealdanHooks.beforeFolderDeleteTree";

	public static final String BEFORE_FOLDER_MERGE = "HealdanHooks.beforeFolderMerge";

	public static final String BEFORE_FOLDER_COPY = "HealdanHooks.beforeFolderCopy";

	public static final String BEFORE_FOLDER_RESTORE = "HealdanHooks.beforeFolderRestore";

	public static final String BEFORE_APPLY_SECURITY_TO_TREE = "HealdanHooks.beforeApplySecurityToTree";

	private static volatile String route;

	private static final AtomicInteger vetoes = new AtomicInteger();

	private static volatile int allowedPasses;

	private static final AtomicInteger passes = new AtomicInteger();

	private VetoSwitch() {
	}

	/**
	 * Vetoes at a check point from now on
	 *
	 * @param route the check point, one of the constants of this class
	 */
	public static void on(String route) {
		on(route, 0);
	}

	/**
	 * Vetoes at a check point from its next pass after the given number, e.g.
	 * at the second document of a merge
	 * 
	 * @param route the check point, one of the constants of this class
	 * @param allowedPasses how many passes to let through first
	 */
	public static void on(String route, int allowedPasses) {
		vetoes.set(0);
		passes.set(0);
		VetoSwitch.allowedPasses = allowedPasses;
		VetoSwitch.route = route;
	}

	/**
	 * Stops vetoing
	 */
	public static void off() {
		route = null;
	}

	/**
	 * @return how many times the check point vetoed since {@link #on(String)}
	 */
	public static int vetoes() {
		return vetoes.get();
	}

	/**
	 * Called by the test listeners and hooks at each check point
	 *
	 * @param checkPoint the check point being passed
	 * @param folderIds the folders the check point would refuse
	 * @param documentIds the documents the check point would refuse
	 *
	 * @throws PersistenceException the veto, if the switch is on for this check
	 *         point
	 */
	public static void check(String checkPoint, Collection<Long> folderIds, Collection<Long> documentIds)
			throws PersistenceException {
		if (checkPoint.equals(route) && passes.getAndIncrement() >= allowedPasses) {
			vetoes.incrementAndGet();
			CacheEvictor evictor = Context.get(CacheEvictor.class);
			evictor.evictFoldersOnRollback(folderIds);
			evictor.evictDocumentsOnRollback(documentIds);
			throw new HealdanVetoException(MESSAGE);
		}
	}

	/**
	 * @param ids object ids, 0 for an object not saved yet
	 * @return the ids of the saved objects
	 */
	public static List<Long> ids(long... ids) {
		return LongStream.of(ids).filter(id -> id != 0L).boxed().toList();
	}
}
