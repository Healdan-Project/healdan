// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.HealdanThrottle;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserListener;
import com.logicaldoc.util.spring.Context;

/**
 * Clears an account's username count in the sign-in throttle when it gets a
 * new password, whatever gives it: an administrator's Change Password, the
 * person's own, the sign-in page's, the lost-password link or a new account
 * (the spec of 2026-11-13 on passwords that have to change, section 3.2). The
 * failures counted were guesses at a password the account no longer has, so
 * an administrator's reset lifts a block. The address's count stays.
 * <p>
 * The count is cleared once the save has committed, in a transaction of its
 * own: a save that rolls back clears nothing, and a sign-in counting at the
 * same moment can't fail the save or hold up the sign-ins behind it. A failure
 * here is logged and never stops the save. CE creates it by reflection, hence
 * the public no-arg constructor.
 */
public class ThrottleUserListener implements UserListener {

	static final String PASSWORD_SET = "healdan.throttle.passwordSet";

	private static final Logger log = LoggerFactory.getLogger(ThrottleUserListener.class);

	public ThrottleUserListener() {
		// Instantiated by CE
	}

	@Override
	public void afterLoad(User user, Map<String, Object> dictionary) throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void beforeSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (StringUtils.isEmpty(user.getPassword()))
			return;
		try {
			// A new account, or one that had no password, gets one too
			if (!user.getPassword().equals(StoredPassword.before(user, dictionary)))
				dictionary.put(PASSWORD_SET, Boolean.TRUE);
		} catch (RuntimeException | PersistenceException e) {
			log.warn("Couldn't check whether the password of user {} changed", user.getId(), e);
		}
	}

	@Override
	public void afterSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (!Boolean.TRUE.equals(dictionary.get(PASSWORD_SET)))
			return;
		String username = user.getUsername();
		long userId = user.getId();
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			clear(username, userId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				clear(username, userId);
			}
		});
	}

	// After a commit the save's transaction is still bound, so the clear asks for a new one
	private static void clear(String username, long userId) {
		try {
			TransactionTemplate own = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
			own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			own.executeWithoutResult(status -> HealdanThrottle.passwordSet(username));
		} catch (RuntimeException e) {
			log.warn("Couldn't clear the sign-in failures of user {}", userId, e);
		}
	}
}
