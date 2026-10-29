package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class DemoFixtureSeedTest extends AbstractHealdanTestCase {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

	static final String PASSWORD = "Demo-Firm-2026";

	static final String TEST_DIR = "src/test/resources/demo";

	static DemoFixtureSeed seed(String dir) {
		Map<String, String> env = new HashMap<>();
		env.put(DemoFixtureSeed.ENV, "true");
		env.put(DemoFixtureSeed.DIR_ENV, dir);
		env.put(DemoFixtureSeed.PASSWORD_ENV, PASSWORD);
		return new DemoFixtureSeed(env::get, () -> TODAY);
	}

	static void run(DemoFixtureSeed seed) throws Exception {
		HealdanOperation.run(Kind.SEED, seed::run);
	}

	static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	static Matter matter(long client, long number) throws Exception {
		return MatterDAO.get().findByClientAndNumber(ClientDAO.get().findByNumber(client).getId(), number);
	}

	private static Set<Permission> granted(Folder folder, String group) throws Exception {
		long groupId = GroupDAO.get().findByName(group, Tenant.DEFAULT_ID).getId();
		FolderAccessControlEntry ace = FolderDAO.get().findById(folder.getId(), true).getAccessControlEntry(groupId);
		assertNotNull(group, ace);
		return ace.grantedPermissions();
	}

	@Test
	public void testOffWithoutTheFlag() {
		assertFalse(new DemoFixtureSeed(name -> null, () -> TODAY).enabled());
		assertEquals("demo-fixture", seed(TEST_DIR).name());
		assertTrue(seed(TEST_DIR).enabled());
		assertTrue(SeedRunner.ORDER.contains("demo-fixture"));
	}

	@Test
	public void testNeedsItsFolder() {
		DemoFixtureSeed noDir = new DemoFixtureSeed(name -> DemoFixtureSeed.ENV.equals(name) ? "true" : null,
				() -> TODAY);
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(noDir));
		assertTrue(e.getMessage(), e.getMessage().contains(DemoFixtureSeed.DIR_ENV));
	}

	@Test
	public void testBuildsTheFirm() throws Exception {
		run(seed(TEST_DIR));

		User pnair = UserDAO.get().findByUsername("pnair", true);
		assertEquals("Priya", pnair.getFirstName());
		assertTrue(pnair.isMemberOf("author"));
		assertTrue(pnair.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		User hashcombe = UserDAO.get().findByUsername("hashcombe", true);
		assertTrue(hashcombe.isMemberOf("Partners"));

		long setId = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true)
				.getId();
		List<String> classes = AttributeOptionDAO.get().findByAttribute(setId, HealdanConstants.ATTR_DOCCLASS)
				.stream().map(AttributeOption::getValue).toList();
		assertTrue(classes.toString(), classes.containsAll(List.of("Docs", "Correspondence")));
		assertEquals(Set.of("Letter", "Email"),
				AttributeOptionDAO.get()
						.findByAttributeAndCategory(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Correspondence")
						.stream().map(AttributeOption::getValue).collect(Collectors.toSet()));

		FolderDAO dao = FolderDAO.get();
		Folder store = dao.findByName(dao.findRoot(Tenant.DEFAULT_ID), "Client Work", Tenant.DEFAULT_ID, true).get(0);
		assertEquals(Folder.TYPE_WORKSPACE, store.getType());
		assertEquals(Set.of(Permission.READ), granted(store, HealdanConstants.EVERYONE_GROUP));

		Folder templates = dao.findById(HealdanSettings.get().templatesStoreId());
		Folder template = dao.findByName(templates, "Litigation", Tenant.DEFAULT_ID, true).get(0);
		Folder privileged = dao.findByName(template, "Privileged", Tenant.DEFAULT_ID, true).get(0);
		assertEquals(Set.of(Permission.READ, Permission.PREVIEW, Permission.DOWNLOAD, Permission.WRITE),
				granted(privileged, "Partners"));
		assertFalse(dao.findByName(template, "My Recent Docs Here", Tenant.DEFAULT_ID, true).isEmpty());

		Matter charter = matter(1001, 1);
		// An escape for the en dash, because the build reads Java sources as ISO-8859-1 and the manifest as UTF-8
		assertEquals("Charter Dispute \u2013 MV Northern Star", charter.getName());
		Matter sale = matter(1001, 2);
		assertTrue(dao.isReadAllowed(charter.getFolderId(), pnair.getId()));
		assertFalse(dao.isReadAllowed(sale.getFolderId(), pnair.getId()));
		assertTrue(dao.isReadAllowed(sale.getFolderId(), hashcombe.getId()));
	}

	@Test
	public void testDatesTheStructureBeforeTheTimeline() throws Exception {
		run(seed(TEST_DIR));
		Timestamp created = jdbc().queryForObject("select ld_creation from hd_client where hd_number = 1001",
				Timestamp.class);
		assertTrue(created.toString(), created.toLocalDateTime().toLocalDate().isBefore(TODAY));
	}

	/** Demo spec §5.3: any document at all is real data, so nothing is built beside it */
	@Test
	public void testRefusesARepositoryWithDocuments() throws Exception {
		jdbc().update("insert into ld_document (ld_id, ld_lastmodified, ld_recordversion, ld_deleted, ld_tenantid, "
				+ "ld_immutable, ld_creation, ld_publisherid, ld_creatorid, ld_signed, ld_stamped, ld_exportstatus, "
				+ "ld_published, ld_pages, ld_previewpages, ld_nature, ld_links, ld_docattrs) "
				+ "values (987654, current_timestamp, 1, 0, 1, 0, current_timestamp, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0)");

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(seed(TEST_DIR)));
		assertTrue(e.getMessage(), e.getMessage().contains("down -v"));
		// Nothing was built beside it
		assertNull(ClientDAO.get().findByNumber(1001));
		assertNull(UserDAO.get().findByUsername("pnair"));
	}

	@Test
	public void testAnUnknownGroupIsRefused() throws Exception {
		Path dir = tempDir.toPath().resolve("bad-group");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("manifest.json"), """
				{"users": [{"username": "x", "firstName": "X", "name": "Y", "email": "x@demo.example",
				"groups": ["Nobody"]}]}
				""");
		Exception e = assertThrows(Exception.class, () -> run(seed(dir.toString())));
		assertTrue(e.getMessage(), e.getMessage().contains("Nobody"));
	}
}
