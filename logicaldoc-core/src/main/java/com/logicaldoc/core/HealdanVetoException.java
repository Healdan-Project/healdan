package com.logicaldoc.core;

/**
 * Thrown by a Healdan rule, from a {@link HealdanHooks} method or a listener,
 * to refuse an operation. The document and folder DAOs roll their transaction
 * back on this exception only, so CE's own {@link PersistenceException}s keep
 * committing as before.
 * <p>
 * Healdan addition: lives in its own file so upstream merges don't touch it.
 */
public class HealdanVetoException extends PersistenceException {

    private static final long serialVersionUID = 1L;

    public HealdanVetoException(String message) {
        super(message);
    }

    public HealdanVetoException(String message, Throwable cause) {
        super(message, cause);
    }
}
