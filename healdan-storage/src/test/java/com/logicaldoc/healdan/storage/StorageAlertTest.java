// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The daily alert (spec section 7, R38): files waiting, or a backend failing, for more than 24 hours email the
 * admins once a day, through M4's sender, and only while outgoing email is set up. The clock is moved, so no test waits.
 */
public class StorageAlertTest extends AbstractStorageTestCase {

	private final List<EMail> emails = new CopyOnWriteArrayList<>();

	private final Map<String, String> savedConfig = new HashMap<>();

	private StorageFixtures sf;

	private Side cloud;

	private StorageAlert alert;

	private BackendHealth health;

	private Clock start;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		for (String name : List.of("default.smtp.host", "default.smtp.sender", "smtp.nosend"))
			savedConfig.put(name, config().getProperty(name));
		config().setProperty("default.smtp.host", "smtp.healdan.example");
		config().setProperty("default.smtp.sender", "office@healdan.example");
		SignInTestHooks.captureEmails(emails);
		try {
			sf = new StorageFixtures();
			cloud = sf.s3Side("Cloud", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		alert = StorageAlert.get();
		health = BackendHealth.get();
		health.checkAll();
		start = Clock.systemDefaultZone();
	}

	@Override
	public void tearDown() throws IOException {
		alert.clock = Clock.systemDefaultZone();
		SignInTestHooks.reset();
		for (Map.Entry<String, String> saved : savedConfig.entrySet())
			if (saved.getValue() == null)
				config().remove(saved.getKey());
			else
				config().setProperty(saved.getKey(), saved.getValue());
		super.tearDown();
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	/** Moves the alert's clock to this long after the test began */
	private void after(Duration time) {
		alert.clock = Clock.offset(start, time);
	}

	private static String sentDay() {
		return HealdanSettings.get().get(StorageSettings.ALERT_SENT);
	}

	private void waitingFile() throws Exception {
		// A save into an S3 Store lands in staging and waits for the mover, which isn't running
		sf.fx.upload(cloud.folder(), "a.txt", "waits", sf.fx.admin());
		assertEquals(1L, StoredFiles.get().waitingBefore(new java.util.Date(System.currentTimeMillis() + 1000)));
	}

	@Test
	public void testNoEmailBefore24HoursOneAfterAndOnceADay() throws Exception {
		waitingFile();

		after(Duration.ofHours(23));
		assertFalse(alert.run());
		assertTrue(emails.isEmpty());
		assertNull(sentDay());

		after(Duration.ofHours(25));
		assertTrue(alert.run());
		assertEquals(1, emails.size());
		assertEquals("admin@admin.net", emails.get(0).getRecipients().iterator().next().getAddress());
		assertTrue(emails.get(0).getSubject(), emails.get(0).getSubject().contains("storage needs attention"));
		assertEquals(LocalDate.now(alert.clock).toString(), sentDay());

		// Not a second time that day
		assertFalse(alert.run());
		assertEquals(1, emails.size());

		// Another the next day, while it lasts
		after(Duration.ofHours(49));
		assertTrue(alert.run());
		assertEquals(2, emails.size());

		// None once it clears
		jdbc().update("update hd_storage_file set hd_waiting = null");
		after(Duration.ofHours(73));
		assertFalse(alert.run());
		assertEquals(2, emails.size());
	}

	@Test
	public void testDisabledAndExpiredAdminsGetNothing() throws Exception {
		User gone = sf.fx.user("gone", "admin");
		gone.setEnabled(false);
		UserDAO.get().store(gone);
		User expired = sf.fx.user("expired", "admin");
		expired.setExpire(new java.util.Date(System.currentTimeMillis() - 1000));
		UserDAO.get().store(expired);
		sf.fx.user("current", "admin");
		waitingFile();
		after(Duration.ofHours(25));

		assertTrue(alert.run());

		List<String> told = emails.stream().map(e -> e.getRecipients().iterator().next().getAddress()).sorted()
				.toList();
		assertEquals(List.of("admin@admin.net", "current@example.com"), told);
	}

	@Test
	public void testBackendFailingForADay() throws Exception {
		cloud.s3().unreachable(true);
		assertEquals(BackendHealth.State.FAILING, health.check(cloud.backendId()).state());

		after(Duration.ofHours(23));
		assertFalse(alert.run());
		after(Duration.ofHours(25));
		assertTrue(alert.run());
		assertEquals(1, emails.size());

		// Once it works again there is nothing to tell
		cloud.s3().unreachable(false);
		assertEquals(BackendHealth.State.OK, health.check(cloud.backendId()).state());
		after(Duration.ofHours(49));
		assertFalse(alert.run());
		assertEquals(1, emails.size());
	}

	@Test
	public void testNothingWithoutOutgoingEmail() throws Exception {
		waitingFile();
		config().setProperty("default.smtp.host", "localhost");
		after(Duration.ofHours(25));

		assertFalse(alert.run());

		assertTrue(emails.isEmpty());
		assertNull("the day isn't spent", sentDay());
	}

	@Test
	public void testNotSpentUntilAnEmailWasTaken() throws Exception {
		waitingFile();
		after(Duration.ofHours(25));
		// A mail server that is down: the sender throws
		SignInTestHooks.captureEmails(new ArrayList<>() {
			private static final long serialVersionUID = 1L;

			@Override
			public boolean add(EMail email) {
				throw new IllegalStateException("The mail server is down");
			}
		});

		assertFalse(alert.run());
		assertNull(sentDay());

		SignInTestHooks.captureEmails(emails);
		assertTrue(alert.run());
		assertEquals(1, emails.size());
		assertEquals(LocalDate.now(alert.clock).toString(), sentDay());
	}

	@Test
	public void testOneRefusedAddressDoesNotEmailTheOthersAgain() throws Exception {
		sf.fx.user("current", "admin");
		waitingFile();
		after(Duration.ofHours(25));
		// The mail server refuses one admin's address, and takes the other's
		SignInTestHooks.captureEmails(new CopyOnWriteArrayList<>() {
			private static final long serialVersionUID = 1L;

			@Override
			public boolean add(EMail email) {
				if ("current@example.com".equals(email.getRecipients().iterator().next().getAddress()))
					throw new IllegalStateException("The recipient is refused");
				return emails.add(email);
			}
		});

		try (LogCapture log = LogCapture.of(StorageAlert.class)) {
			assertTrue(alert.run());

			assertEquals(1, emails.size());
			assertEquals("admin@admin.net", emails.get(0).getRecipients().iterator().next().getAddress());
			assertEquals("the day is spent", LocalDate.now(alert.clock).toString(), sentDay());
			List<String> warnings = log.lines().stream().filter(line -> line.startsWith("WARN ")).toList();
			assertEquals(log.lines().toString(), 1, warnings.size());
			assertTrue(warnings.get(0), warnings.get(0).contains("IllegalStateException"));
			assertFalse("no address is logged: " + log.lines(), log.lines().toString().contains("@"));
		}

		// The next hourly run, the same day: the admin who was told isn't told again
		assertFalse(alert.run());
		assertEquals(1, emails.size());
	}

	@Test
	public void testStoreSwitchedAnHourAgoRaisesNothing() throws Exception {
		Side local = sf.localSide("Local", false);
		var doc = sf.fx.upload(local.folder(), "old.txt", "months old", sf.fx.admin());
		jdbc().update("update hd_storage_file set hd_stored = ?",
				new Timestamp(System.currentTimeMillis() - Duration.ofDays(90).toMillis()));

		// The Store switches to S3: its files wait from now, not from when they were stored
		sf.map(local.store().getId(), cloud.backendId());
		StoredFiles.get().setStore(List.of(doc.getId()), local.store().getId());
		assertEquals(0L, StoredFiles.get().waitingBefore(new java.util.Date(System.currentTimeMillis() - 1000)));

		after(Duration.ofHours(1));
		assertFalse(alert.run());
		assertTrue(emails.isEmpty());

		// Still waiting 25 hours after the switch
		after(Duration.ofHours(25));
		assertTrue(alert.run());
		assertEquals(1, emails.size());
	}

	private static org.springframework.jdbc.core.JdbcTemplate jdbc() {
		return StorageFixtures.jdbc();
	}
}
