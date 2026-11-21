// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.util.spring.Context;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_FIXTURE} is {@code true},
 * adds the Classes {@code Correspondence} and {@code Pleadings} to the
 * Healdan profile's Class list, after the Classes it holds, so the User app's
 * browser tests can file documents of three Classes (with the default,
 * {@code Docs}) and sort a folder by Class. A fresh install's list holds only
 * the default Class, and the filing refuses a value the list lacks. It is a
 * step of its own, as a shipped step never changes, and it adds only the
 * Classes the list lacks, as a Class's value is unique in the list.
 * <p>
 * Without the profile's attribute set, which the {@code profile} step makes,
 * it does nothing and counts as done. Without the variable the step does
 * nothing and writes no marker, so it can run on a later start.
 */
@Component
public class SmokeClassesSeed implements SeedStep {

	static final List<String> CLASSES = List.of("Correspondence", "Pleadings");

	private static final Logger log = LoggerFactory.getLogger(SmokeClassesSeed.class);

	private final Supplier<String> flag;

	@Autowired
	public SmokeClassesSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeClassesSeed(Supplier<String> flag) {
		this.flag = flag;
	}

	@Override
	public String name() {
		return "smoke-classes";
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	/** In one transaction, so a failure leaves nothing for the next start to trip over */
	@Override
	public void run() throws Exception {
		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).execute(status -> {
			try {
				add();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			return null;
		});
	}

	private void add() throws Exception {
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID,
				true);
		if (set == null) {
			log.warn("No attribute set {} to add the smoke test's Classes to: the profile step hasn't made it",
					HealdanConstants.PROFILE_TEMPLATE);
			return;
		}
		AttributeOptionDAO dao = AttributeOptionDAO.get();
		List<AttributeOption> existing = dao.findByAttribute(set.getId(), HealdanConstants.ATTR_DOCCLASS);
		int position = existing.stream().mapToInt(AttributeOption::getPosition).max().orElse(-1);
		for (String value : CLASSES) {
			if (existing.stream().anyMatch(option -> value.equals(option.getValue())))
				continue;
			AttributeOption option = new AttributeOption(set.getId(), HealdanConstants.ATTR_DOCCLASS, value);
			option.setPosition(++position);
			dao.store(option);
		}
	}
}
