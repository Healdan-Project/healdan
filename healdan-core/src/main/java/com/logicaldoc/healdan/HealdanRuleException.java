package com.logicaldoc.healdan;

import com.logicaldoc.core.HealdanVetoException;

/**
 * Thrown when a Healdan rule refuses something. It extends core's veto type so
 * that a refusal inside a DAO transaction rolls that transaction back (plan,
 * D3). The message is written for the user, who sees it as it is.
 */
public class HealdanRuleException extends HealdanVetoException {

	private static final long serialVersionUID = 1L;

	public HealdanRuleException(String message) {
		super(message);
	}

	public HealdanRuleException(String message, Throwable cause) {
		super(message, cause);
	}
}
