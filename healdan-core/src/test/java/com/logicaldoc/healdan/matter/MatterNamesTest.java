// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.security.FolderReads;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * The names the Client and Matter filters' options get: only through a matter
 * whose own folder the caller can read and that isn't deleted.
 */
public class MatterNamesTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User clerk;

	private Folder open;

	private Folder shut;

	@Before
	public void createStores() throws Exception {
		clerk = fx.user("clerk");
		open = fx.store("Open");
		fx.grant(open, clerk.getUserGroup(), Permission.READ);
		shut = fx.store("Shut");
	}

	@Test
	public void testNamesOnlyThroughAMatterTheCallerCanRead() throws Exception {
		Client both = fx.client(998, "Both");
		Client hidden = fx.client(997, "Hidden");
		fx.matter(both, 1, "Readable", open);
		fx.matter(both, 2, "Unreadable", shut);
		fx.matter(hidden, 1, "Private", shut);
		Set<String> clients = Set.of("998", "997");
		Set<String> matters = Set.of("998.1", "998.2", "997.1");

		MatterNames names = new MatterNames(clients, matters, new FolderReads(clerk));
		assertEquals("998 \u2013 Both", names.client("998"));
		assertEquals("997", names.client("997"));
		assertEquals("998.1 \u2013 Readable", names.matter("998.1"));
		assertEquals("998.2", names.matter("998.2"));
		assertEquals("997.1", names.matter("997.1"));

		names = new MatterNames(clients, matters, new FolderReads(fx.admin()));
		assertEquals("997 \u2013 Hidden", names.client("997"));
		assertEquals("998.2 \u2013 Unreadable", names.matter("998.2"));
	}

	@Test
	public void testNamesNothingThroughADeletedFolder() throws Exception {
		Matter matter = fx.matter(fx.client(998, "Healdan"), 1, "Demo", open);
		new JdbcTemplate(Context.get(DataSource.class)).update("update ld_folder set ld_deleted = 1 where ld_id = ?",
				matter.getFolderId());

		for (User user : new User[] { clerk, fx.admin() }) {
			MatterNames names = new MatterNames(Set.of("998"), Set.of("998.1"), new FolderReads(user));
			assertEquals("998", names.client("998"));
			assertEquals("998.1", names.matter("998.1"));
		}
	}

	@Test
	public void testKeepsWhatIsNoOptionsNumber() throws Exception {
		fx.matter(fx.client(998, "Healdan"), 1, "Demo", open);
		Set<String> clients = Set.of("0998", "abc", "999", "1234567890123456789012");
		Set<String> matters = Set.of("998.01", "998.x", "998", "998.9", "x.1");

		MatterNames names = new MatterNames(clients, matters, new FolderReads(clerk));
		for (String option : clients)
			assertEquals(option, names.client(option));
		for (String option : matters)
			assertEquals(option, names.matter(option));
	}

	@Test
	public void testNumbersAreInPlainDigitsWhateverTheLocale() {
		Locale format = Locale.getDefault(Locale.Category.FORMAT);
		Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
		try {
			assertEquals("998.1", MatterDisplay.number(998, 1));
			assertEquals("998 \u2013 Healdan", MatterDisplay.client(998, "Healdan"));
		} finally {
			Locale.setDefault(Locale.Category.FORMAT, format);
		}
	}
}
