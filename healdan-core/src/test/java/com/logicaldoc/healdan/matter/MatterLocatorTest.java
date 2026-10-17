package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

public class MatterLocatorTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private MatterLocator locator;

	private Folder root;

	private Folder store;

	private Matter matter;

	private Folder workspace;

	private Folder documents;

	private Folder secured;

	private Folder search;

	private Folder deep;

	@Before
	public void prepare() throws Exception {
		locator = MatterLocator.get();
		root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		store = fx.store("Main");
		matter = fx.matter(fx.client(998, "Healdan"), 1, "Healdan Demo", store, "Documents", "Secured Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = child(workspace, "Documents");
		secured = child(workspace, "Secured Documents");
		search = fx.searchFolder(workspace, "Recent");
		deep = fx.folder(fx.folder(documents, "2026"), "Invoices");
	}

	private Folder child(Folder parent, String name) throws Exception {
		return FolderDAO.get().findByName(parent, name, Tenant.DEFAULT_ID, false).get(0);
	}

	private void assertPosition(Folder folder, Kind kind, int depth) throws Exception {
		FolderPosition position = locator.locate(folder.getId());
		assertEquals(folder.getName(), kind, position.kind());
		assertEquals(folder.getName(), depth, position.depth());
		assertEquals(position.kind(), locator.locate(folder).kind());
	}

	@Test
	public void testRoot() throws Exception {
		assertPosition(root, Kind.ROOT, -1);
	}

	@Test
	public void testStore() throws Exception {
		assertPosition(store, Kind.STORE, -1);
		assertEquals(Long.valueOf(store.getId()), locator.locate(store).storeId());
		assertTrue(locator.locate(store).isUnderAStore());
	}

	@Test
	public void testIsUnderAStore() throws Exception {
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		Folder defaultWorkspace = FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID);

		// The Store and everything in it
		assertTrue(locator.locate(store).isUnderAStore());
		assertTrue(locator.locate(workspace).isUnderAStore());
		assertTrue(locator.locate(documents).isUnderAStore());
		assertTrue(locator.locate(deep).isUnderAStore());
		assertTrue(locator.locate(search).isUnderAStore());

		// Nothing else
		assertFalse(locator.locate(root).isUnderAStore());
		assertFalse(locator.locate(templates).isUnderAStore());
		assertFalse(locator.locate(fx.folder(templates, "Standard")).isUnderAStore());
		assertFalse(locator.locate(defaultWorkspace).isUnderAStore());
		assertFalse(locator.locate(fx.folder(defaultWorkspace, "Loose")).isUnderAStore());
	}

	@Test
	public void testMatterWorkspace() throws Exception {
		assertPosition(workspace, Kind.MATTER_WORKSPACE, 0);
		FolderPosition position = locator.locate(workspace);
		assertEquals(matter.getId(), position.matter().getId());
		assertEquals(Long.valueOf(workspace.getId()), position.workspaceId());
		assertEquals(Long.valueOf(store.getId()), position.storeId());
		assertTrue(position.isInsideMatter());
		assertFalse(position.isMatterTopLevel());
		assertTrue(position.isUnderAStore());
	}

	@Test
	public void testFoldersInAMatter() throws Exception {
		assertPosition(documents, Kind.IN_MATTER, 1);
		assertPosition(secured, Kind.IN_MATTER, 1);
		assertPosition(search, Kind.IN_MATTER, 1);
		assertPosition(deep, Kind.IN_MATTER, 3);

		FolderPosition position = locator.locate(deep);
		assertEquals(matter.getId(), position.matter().getId());
		assertEquals(Long.valueOf(workspace.getId()), position.workspaceId());
		assertEquals(Long.valueOf(store.getId()), position.storeId());
		assertFalse(position.isMatterTopLevel());
		assertTrue(locator.locate(documents).isMatterTopLevel());
	}

	@Test
	public void testSearchFolderFlag() throws Exception {
		assertTrue(locator.locate(search).searchFolder());
		assertFalse(locator.locate(documents).searchFolder());
		assertFalse(locator.locate(workspace).searchFolder());
	}

	@Test
	public void testTemplatesStoreAndWhatIsBelowIt() throws Exception {
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		Folder template = fx.folder(templates, "Standard");
		Folder inTemplate = fx.folder(template, "Correspondence");

		assertPosition(templates, Kind.TEMPLATES_STORE, -1);
		assertPosition(template, Kind.IN_TEMPLATES, -1);
		assertPosition(inTemplate, Kind.IN_TEMPLATES, -1);
		assertFalse(locator.locate(inTemplate).isInsideMatter());
		assertNull(locator.locate(inTemplate).storeId());
	}

	@Test
	public void testDefaultWorkspaceAndWhatIsBelowIt() throws Exception {
		Folder defaultWorkspace = FolderDAO.get().findById(Folder.DEFAULTWORKSPACEID);
		Folder below = fx.folder(defaultWorkspace, "Loose");

		assertPosition(defaultWorkspace, Kind.DEFAULT_WORKSPACE, -1);
		assertPosition(below, Kind.OUTSIDE, -1);
		assertPosition(fx.folder(below, "Deeper"), Kind.OUTSIDE, -1);
	}

	@Test
	public void testWorkspaceWithoutAMatterRowIsStillAMatterWorkspace() throws Exception {
		// As during matter creation: the folders exist before the hd_matter row
		Folder bare = HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE, () -> fx.folder(store, "Being created"));
		Folder inside = fx.folder(bare, "Documents");

		FolderPosition workspacePosition = locator.locate(bare);
		assertEquals(Kind.MATTER_WORKSPACE, workspacePosition.kind());
		assertNull(workspacePosition.matter());
		assertEquals(Long.valueOf(bare.getId()), workspacePosition.workspaceId());

		FolderPosition insidePosition = locator.locate(inside);
		assertEquals(Kind.IN_MATTER, insidePosition.kind());
		assertEquals(1, insidePosition.depth());
		assertNull(insidePosition.matter());
		assertTrue(insidePosition.isMatterTopLevel());
	}

	@Test
	public void testSecondStoreIsAStoreToo() throws Exception {
		Folder other = fx.store("Archive");
		Folder inOther = HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE, () -> fx.folder(other, "Old matter"));

		assertPosition(other, Kind.STORE, -1);
		assertPosition(inOther, Kind.MATTER_WORKSPACE, 0);
		assertEquals(Long.valueOf(other.getId()), locator.locate(inOther).storeId());
	}

	@Test
	public void testMissingFolderIsOutside() throws Exception {
		assertEquals(Kind.OUTSIDE, locator.locate(987654321L).kind());
		assertEquals(Kind.OUTSIDE, locator.locate((Folder) null).kind());
		assertNotNull(locator.locate(root));
	}

	@Test
	public void testItDoesNotReadThePath() throws Exception {
		// A corrupt ld_path must not change the answer
		new JdbcTemplate(Context.get(DataSource.class)).update("update ld_folder set ld_path = '/nowhere'");
		Context.get(CacheEvictor.class).evictFolders(List.of(workspace.getId(), documents.getId(), deep.getId(), store.getId()));

		assertEquals(Kind.IN_MATTER, locator.locate(deep.getId()).kind());
		assertEquals(Kind.MATTER_WORKSPACE, locator.locate(workspace.getId()).kind());
	}
}
