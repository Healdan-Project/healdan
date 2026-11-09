// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.OutgoingEmail;
import com.logicaldoc.util.spring.Context;

/**
 * The alert (spec section 7), run hourly by {@code StorageStartup}: when a file has waited more than 24 hours to
 * reach its Store's backend (its row's {@code hd_waiting}, set when the backend stopped matching the Store's, so a
 * Store switched an hour ago raises nothing however old its files are), or a backend has been failing that long, the
 * members of the {@code admin} group who are enabled, not expired and have an email get the {@code storage.alert}
 * template, through M4's sender, which throws where CE's would drop an email without a word. At most one a day:
 * {@code storage.alert.sent} holds the day it last went, and is written once at least one email was taken, so a mail
 * server that was down is tried again at the next hourly run, and one that refuses an admin's address doesn't make the
 * other admins get an email every hour. The first alert comes within 25 hours of the condition. Nothing goes while
 * outgoing email isn't set up ({@link OutgoingEmail#isSetUp}).
 * <p>
 * It names no file, backend or address: the email sends the admin to the Storage tab, and nothing logged says who was
 * emailed. A refused email is logged by its exception's class alone, as a mail server's refusal can quote the address.
 */
@Component
public class StorageAlert {

	private static final Logger log = LoggerFactory.getLogger(StorageAlert.class);

	/** The message template (the seed adds it) */
	public static final String TEMPLATE = "storage.alert";

	/** The group whose members are told */
	static final String ADMINS = "admin";

	/** How long files wait, or a backend fails, before the admins are told */
	static final Duration LIMIT = Duration.ofHours(24);

	private final BackendHealth health;

	private final StoredFiles files;

	private final HealdanSettings settings;

	/** What the day and the 24 hours are measured by; tests move it */
	volatile Clock clock = Clock.systemDefaultZone();

	@Autowired
	public StorageAlert(BackendHealth health, StoredFiles files, HealdanSettings settings) {
		this.health = health;
		this.files = files;
		this.settings = settings;
	}

	/**
	 * @return the bean
	 */
	public static StorageAlert get() {
		return Context.get(StorageAlert.class);
	}

	/**
	 * Alerts the admins if something has waited or failed for more than 24 hours and they weren't told today.
	 *
	 * @return whether an alert went
	 */
	public boolean run() {
		String today = LocalDate.now(clock).toString();
		if (today.equals(settings.get(StorageSettings.ALERT_SENT)) || !OutgoingEmail.isSetUp())
			return false;
		Date cutoff = new Date(clock.millis() - LIMIT.toMillis());
		if (!waiting(cutoff) && !failing(cutoff))
			return false;

		List<User> admins = admins();
		if (admins.isEmpty()) {
			log.warn("Storage needs attention, but no member of the {} group has an email to tell", ADMINS);
			return false;
		}
		int taken = 0;
		for (User admin : admins)
			try {
				send(admin);
				taken++;
			} catch (Exception e) {
				log.warn("The mail server didn't take the storage alert to one of the admins: {}",
						e.getClass().getSimpleName());
			}
		if (taken == 0) {
			log.warn("No admin was told that storage needs attention; the alert is tried again at the next run");
			return false;
		}
		settings.set(StorageSettings.ALERT_SENT, today);
		log.info("Told {} of {} admins that storage needs attention", taken, admins.size());
		return true;
	}

	private boolean waiting(Date cutoff) {
		return files.waitingBefore(cutoff) > 0;
	}

	private boolean failing(Date cutoff) {
		for (long id : health.unavailable()) {
			BackendHealth.Status status = health.status(id);
			if (status.state() == BackendHealth.State.FAILING && status.since() != null && status.since().before(cutoff))
				return true;
		}
		return false;
	}

	/** @return the members of the admin group who have an email */
	private static List<User> admins() {
		List<User> admins = new ArrayList<>();
		try {
			Group group = GroupDAO.get().findByName(ADMINS, Tenant.DEFAULT_ID);
			if (group == null)
				return admins;
			for (User user : UserDAO.get().findByGroup(group.getId()))
				// A disabled or expired account belongs to someone barred or gone
				if (user.isEnabled() && !user.isExpired() && StringUtils.isNotBlank(user.getEmail()))
					admins.add(user);
		} catch (PersistenceException e) {
			log.warn("The members of the {} group can't be read, so the storage alert isn't sent: {}", ADMINS,
					e.getMessage());
		}
		return admins;
	}

	private static void send(User user) throws Exception {
		OutgoingEmail.sendTemplate(user, TEMPLATE, new HashMap<>());
	}
}
