package com.logicaldoc.healdan;

/**
 * Thrown when Healdan cannot start safely, for instance because a schema
 * migration failed. It stops the application from starting.
 */
public class HealdanStartupException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public HealdanStartupException(String message) {
		super(message);
	}

	public HealdanStartupException(String message, Throwable cause) {
		super(message, cause);
	}
}
