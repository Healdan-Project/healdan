package com.logicaldoc.healdan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;

/**
 * Marks the current thread as running a Healdan operation, so that Healdan's
 * rules can tell its own work from a user's. It is a stack: operations may
 * nest, and each is popped when it ends.
 */
public final class HealdanOperation {

	/** The kinds of operation */
	public enum Kind {
		SEED, MATTER_CREATE, MATTER_EDIT, SEARCH_FOLDER
	}

	/** A piece of work that returns nothing and may throw */
	@FunctionalInterface
	public interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static final ThreadLocal<Deque<Kind>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

	private HealdanOperation() {
	}

	/**
	 * Runs the work as an operation of the given kind.
	 * 
	 * @param <T> the type of the result
	 * @param kind the kind of operation
	 * @param work the work to do
	 * @return what the work returned
	 * @throws Exception whatever the work throws
	 */
	public static <T> T run(Kind kind, Callable<T> work) throws Exception {
		Deque<Kind> stack = STACK.get();
		stack.push(kind);
		try {
			return work.call();
		} finally {
			stack.pop();
			if (stack.isEmpty())
				STACK.remove();
		}
	}

	/**
	 * Runs the work as an operation of the given kind.
	 * 
	 * @param kind the kind of operation
	 * @param work the work to do
	 * @throws Exception whatever the work throws
	 */
	public static void run(Kind kind, ThrowingRunnable work) throws Exception {
		run(kind, () -> {
			work.run();
			return null;
		});
	}

	/**
	 * @param kind the kind of operation
	 * @return true if the current thread is inside an operation of that kind
	 */
	public static boolean active(Kind kind) {
		return STACK.get().contains(kind);
	}
}
