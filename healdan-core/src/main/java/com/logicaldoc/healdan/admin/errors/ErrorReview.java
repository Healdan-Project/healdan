// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.UrlCredentials;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.util.spring.Context;

/**
 * Marking a fault resolved, and unresolved again (Admin app spec section 8.8). Each change and its record in the
 * administration record are one transaction: area {@code MONITORING}, aimed at the fault ({@code error}) by its id,
 * named by its latest message with any URL's credentials taken out ({@link UrlCredentials#cut}), as the Errors screen
 * shows it, cut to fit. A fault already in the state asked for is answered as it is, and nothing is recorded.
 * Administrators only.
 */
@Component
public class ErrorReview {

	/** The refusal of anyone but an administrator */
	public static final String ONLY_ADMINS = "Only an administrator can mark an error resolved or unresolved.";

	/** The action a resolve records */
	public static final String RESOLVED = "error.resolved";

	/** The action a reopen records */
	public static final String REOPENED = "error.reopened";

	/** What the record aims at */
	public static final String TARGET = "error";

	private final ErrorStore store;

	private final AdminEvents events;

	private final TransactionTemplate transaction;

	@Autowired
	public ErrorReview(ErrorStore store, AdminEvents events, PlatformTransactionManager transactions) {
		this.store = store;
		this.events = events;
		this.transaction = new TransactionTemplate(transactions);
	}

	/**
	 * @return the bean in the application context
	 */
	public static ErrorReview get() {
		return Context.get(ErrorReview.class);
	}

	/**
	 * Marks a fault resolved by the actor, now, and records it, in one transaction.
	 *
	 * @param id the fault's id
	 * @param actor who resolves it
	 * @return the fault as it is then, which names who resolved it; empty when there's no such fault
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else
	 */
	public Optional<ErrorGroup> resolve(long id, User actor) throws PersistenceException {
		requireAdmin(actor);
		return transaction.execute(status -> change(store.resolve(id, actor), id, RESOLVED, actor));
	}

	/**
	 * Marks a resolved fault unresolved, forgetting who resolved it and when, and records it, in one transaction.
	 *
	 * @param id the fault's id
	 * @param actor who reopens it
	 * @return the fault as it is then; empty when there's no such fault
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator, before anything else
	 */
	public Optional<ErrorGroup> reopen(long id, User actor) throws PersistenceException {
		requireAdmin(actor);
		return transaction.execute(status -> change(store.reopen(id), id, REOPENED, actor));
	}

	/**
	 * @return the fault after a change, recorded when the change changed it
	 */
	private Optional<ErrorGroup> change(boolean changed, long id, String action, User actor) {
		Optional<ErrorGroup> fault = store.find(id);
		if (changed && fault.isPresent())
			events.record(new AdminEvent(AdminArea.MONITORING, action, TARGET, id,
					UrlCredentials.cut(fault.get().message()), null), actor);
		return fault;
	}

	/*
	 * FilingRules reloads the user's groups, which are transient
	 */
	private static void requireAdmin(User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}
}
