package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanStartup;
import com.logicaldoc.healdan.HealdanStartupException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

public class SeedsTest extends AbstractHealdanTestCase {

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private User newUser(String username) throws Exception {
		User user = new User();
		user.setUsername(username);
		user.setName(username);
		user.setFirstName(username);
		user.setEmail(username + "@example.com");
		user.setPassword("Secret-1234");
		user.setTenantId(Tenant.DEFAULT_ID);
		UserDAO.get().store(user);
		return UserDAO.get().findByUsername(username, true);
	}

	private void forget(String seed) {
		jdbc().update("delete from hd_setting where name = ?", "seed." + seed);
	}

	@Test
	public void testEveryoneHoldsAllUsersAndKeepsTheirGroups() throws Exception {
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		assertNotNull(everyone);
		User admin = UserDAO.get().findByUsername("admin", true);
		assertTrue(admin.isMemberOf(HealdanConstants.EVERYONE_GROUP));

		// A read-only user, whom CE keeps in its own group and guest only
		User reader = newUser("reader");
		reader.setType(User.Type.READONLY);
		UserDAO.get().store(reader);

		// Back to the state before the seed: no membership, no marker
		jdbc().update("delete from ld_usergroup where ld_groupid = ?", everyone.getId());
		forget("everyone");
		assertFalse(UserDAO.get().findByUsername("admin", true).isMemberOf(HealdanConstants.EVERYONE_GROUP));

		Context.get(HealdanStartup.class).run();

		admin = UserDAO.get().findByUsername("admin", true);
		assertTrue(admin.isMemberOf(Group.GROUP_ADMIN));
		assertTrue(admin.isMemberOf("_user_1"));
		assertTrue(admin.isMemberOf(HealdanConstants.EVERYONE_GROUP));

		User system = UserDAO.get().findById(User.USERID_SYSTEM, true);
		assertFalse(system.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		reader = UserDAO.get().findByUsername("reader", true);
		assertFalse(reader.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		assertTrue(reader.isMemberOf("guest"));
		assertEquals(1, jdbc().queryForObject("select count(*) from ld_group where ld_name = ?", Integer.class,
				HealdanConstants.EVERYONE_GROUP).intValue());
	}

	@Test
	public void testTemplatesStoreIsAdminOnly() throws Exception {
		Long id = HealdanSettings.get().templatesStoreId();
		assertNotNull(id);
		Folder store = FolderDAO.get().findById(id);
		assertEquals(HealdanConstants.TEMPLATES_STORE_NAME, store.getName());
		assertEquals(Folder.TYPE_WORKSPACE, store.getType());

		User alice = newUser("alice");
		assertFalse(FolderDAO.get().isPermissionAllowed(Permission.READ, id, alice.getId()));
		assertTrue(FolderDAO.get().isPermissionAllowed(Permission.READ, id, 1L));
	}

	@Test
	public void testColumnsDefault() {
		assertEquals("filename,fileVersion,customId,lastModified,size,ext_author,ext_docclass",
				Context.get().getConfig().getProperty("default.gui.document.columns"));
	}

	@Test
	public void testReindexIsNotABeanUntilTheParsersExist() {
		// Task 21 makes it a bean; until then startup must not flag anything
		assertTrue(Context.get().getBeansOfType(ReindexOfficeSeed.class).isEmpty());
		assertNull(HealdanSettings.get().get("seed.reindex-office"));
	}

	@Test
	public void testReindexFlagsOnlyOfficeAndEmail() throws Exception {
		Folder root = FolderDAO.get().findFolder(Folder.DEFAULTWORKSPACEID);
		List<String> names = List.of("a.docx", "b.XLSX", "c.pptx", "d.eml", "e.msg", "f.txt", "g.pdf", "h.docx");
		for (String name : names)
			createDocument(root, name);

		// Everything looks indexed, as it would after a normal life; an admin set h.docx to skip indexing
		jdbc().update("update ld_document set ld_indexed = ?", IndexingStatus.INDEXED.ordinal());
		jdbc().update("update ld_document set ld_indexed = ? where ld_filename = 'h.docx'",
				IndexingStatus.SKIP.ordinal());
		Context.get(CacheEvictor.class).evictDocuments(jdbc().queryForList("select ld_id from ld_document", Long.class));

		new ReindexOfficeSeed(Context.get(DataSource.class), Context.get(CacheEvictor.class)).run();

		Set<String> flagged = Set.copyOf(jdbc().queryForList(
				"select lower(ld_filename) from ld_document where ld_indexed = ?", String.class,
				IndexingStatus.TO_INDEX.ordinal()));
		assertEquals(Set.of("a.docx", "b.xlsx", "c.pptx", "d.eml", "e.msg"), flagged);

		// The DAO sees it too
		assertEquals(IndexingStatus.TO_INDEX, DocumentDAO.get().findById(idOf("a.docx")).getIndexingStatus());
		assertEquals(IndexingStatus.INDEXED, DocumentDAO.get().findById(idOf("f.txt")).getIndexingStatus());
		assertEquals(IndexingStatus.SKIP, DocumentDAO.get().findById(idOf("h.docx")).getIndexingStatus());
	}

	@Test
	public void testSettingsSeedKeepsExistingValues() {
		HealdanSettings settings = HealdanSettings.get();
		settings.set(HealdanSettings.MATTER_LABEL, "Case");
		forget("settings");

		Context.get(HealdanStartup.class).run();

		assertEquals("Case", settings.matterLabel());
		assertEquals("Matters", settings.matterLabelPlural());
	}

	@Test
	public void testSeedsRunOnce() throws Exception {
		Long id = HealdanSettings.get().templatesStoreId();
		FolderHistory history = new FolderHistory();
		history.setUser(UserDAO.get().findByUsername("admin"));
		FolderDAO.get().delete(id, history);
		assertNull(FolderDAO.get().findById(id));

		Context.get(HealdanStartup.class).run();

		Folder root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		assertTrue(FolderDAO.get().findByName(root, HealdanConstants.TEMPLATES_STORE_NAME, Tenant.DEFAULT_ID, false)
				.isEmpty());
		assertEquals("done", HealdanSettings.get().get("seed.templates-store"));
	}

	@Test
	public void testFailingSeedFailsStartup() {
		SeedStep failing = new SeedStep() {
			@Override
			public String name() {
				return "smoke-fixture";
			}

			@Override
			public void run() throws Exception {
				throw new IllegalStateException("boom");
			}
		};
		forget("smoke-fixture");

		HealdanStartupException e = assertThrows(HealdanStartupException.class,
				() -> Context.get(SeedRunner.class).runPending(List.of(failing)));

		assertTrue(e.getMessage().contains("smoke-fixture"));
		// The message says how to skip the step
		assertTrue(e.getMessage(), e.getMessage().contains("insert into hd_setting"));
		assertTrue(e.getMessage(), e.getMessage().contains("'seed.smoke-fixture'"));
		assertNull(HealdanSettings.get().get("seed.smoke-fixture"));
	}

	@Test
	public void testUnknownSeedNameFailsStartup() {
		SeedStep unknown = new SeedStep() {
			@Override
			public String name() {
				return "nope";
			}

			@Override
			public void run() {
				// never reached
			}
		};
		assertThrows(HealdanStartupException.class, () -> Context.get(SeedRunner.class).runPending(List.of(unknown)));
	}

	private long idOf(String fileName) {
		return jdbc().queryForObject("select ld_id from ld_document where ld_filename = ?", Long.class, fileName);
	}

	private Document createDocument(Folder folder, String fileName) throws Exception {
		Document vo = new Document();
		vo.setFileName(fileName);
		vo.setFolder(folder);
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		DocumentHistory transaction = new DocumentHistory();
		transaction.setSessionId("seed-test");
		transaction.setUser(UserDAO.get().findByUsername("admin"));
		return DocumentManager.get()
				.create(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)), vo, transaction)
				.getDocument();
	}
}
