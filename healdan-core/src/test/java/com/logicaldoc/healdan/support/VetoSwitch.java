package com.logicaldoc.healdan.support;

import java.util.concurrent.atomic.AtomicInteger;

import com.logicaldoc.core.PersistenceException;

/**
 * Turns on the veto of the test listeners and hooks at one check point, the
 * place a Healdan rule would refuse the operation: a listener method or a
 * {@code HealdanHooks} method, named by the constants below. While it's on,
 * that check point throws {@code PersistenceException("veto")}.
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

	public static final String BEFORE_FOLDER_RESTORE = "HealdanHooks.beforeFolderRestore";

	public static final String BEFORE_APPLY_SECURITY_TO_TREE = "HealdanHooks.beforeApplySecurityToTree";

	private static volatile String route;

	private static final AtomicInteger vetoes = new AtomicInteger();

	private VetoSwitch() {
	}

	/**
	 * Vetoes at a check point from now on
	 *
	 * @param route the check point, one of the constants of this class
	 */
	public static void on(String route) {
		vetoes.set(0);
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
	 *
	 * @throws PersistenceException the veto, if the switch is on for this check
	 *         point
	 */
	public static void check(String checkPoint) throws PersistenceException {
		if (checkPoint.equals(route)) {
			vetoes.incrementAndGet();
			throw new PersistenceException(MESSAGE);
		}
	}
}
