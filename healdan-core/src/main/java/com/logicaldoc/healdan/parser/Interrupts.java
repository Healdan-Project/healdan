package com.logicaldoc.healdan.parser;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;

/**
 * CE's {@code AbstractParser} stops a parser that runs too long by
 * interrupting its thread. A parser that swallows the exception an
 * interruption caused puts the interruption back for it.
 */
final class Interrupts {

	private Interrupts() {
	}

	/**
	 * @param t what the parser caught; the thread is interrupted again if an
	 *        interruption caused it
	 */
	static void keep(Throwable t) {
		for (Throwable cause = t; cause != null; cause = cause.getCause())
			if (cause instanceof InterruptedException || cause instanceof InterruptedIOException
					|| cause instanceof ClosedByInterruptException) {
				Thread.currentThread().interrupt();
				return;
			}
	}
}
