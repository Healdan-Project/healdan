package com.logicaldoc.healdan.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class CacheEvictorTest extends AbstractHealdanTestCase {

	private JdbcTemplate jdbc;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
	}

	private long createFolder(String name) throws PersistenceException {
		Folder workspace = FolderDAO.get().findFolder(Folder.DEFAULTWORKSPACEID);
		Folder vo = new Folder(name);
		vo.setTenantId(Tenant.DEFAULT_ID);
		return FolderDAO.get().create(workspace, vo, true, null).getId();
	}

	@Test
	public void testJdbcRenameIsVisibleAfterEviction() throws Exception {
		long id = createFolder("before");
		assertEquals("before", FolderDAO.get().findById(id).getName());

		jdbc.update("update ld_folder set ld_name = 'after' where ld_id = ?", id);
		assertEquals("The second-level cache still holds the old row", "before", FolderDAO.get().findById(id).getName());

		Context.get(CacheEvictor.class).evictFolders(List.of(id));

		assertEquals("after", FolderDAO.get().findById(id).getName());
	}

	@Test
	public void testRolledBackLoadIsEvicted() throws Exception {
		long poisoned = createFolder("poisoned");
		long guarded = createFolder("guarded");

		// Without the helper the cache keeps the row a rolled-back transaction loaded
		refusedRoute(poisoned, false);
		assertEquals("uncommitted", FolderDAO.get().findById(poisoned).getName());

		refusedRoute(guarded, true);
		assertEquals("guarded", FolderDAO.get().findById(guarded).getName());
		assertEquals("guarded", jdbc.queryForObject("select ld_name from ld_folder where ld_id = ?", String.class,
				guarded));
	}

	@Test
	public void testCommittedChangeStaysVisible() throws Exception {
		long id = createFolder("before");
		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
			jdbc.update("update ld_folder set ld_name = 'after' where ld_id = ?", id);
			Context.get(CacheEvictor.class).evictFolders(List.of(id));
			Context.get(CacheEvictor.class).evictFoldersOnRollback(List.of(id));
		});
		assertEquals("after", FolderDAO.get().findById(id).getName());
	}

	/**
	 * As CE's restore does: a JDBC write, an eviction, a load that caches the
	 * uncommitted row, then a veto that rolls the transaction back
	 */
	private void refusedRoute(long folderId, boolean evictOnRollback) {
		CacheEvictor evictor = Context.get(CacheEvictor.class);
		assertThrows(IllegalStateException.class,
				() -> new TransactionTemplate(Context.get(PlatformTransactionManager.class))
						.executeWithoutResult(status -> {
							jdbc.update("update ld_folder set ld_name = 'uncommitted' where ld_id = ?", folderId);
							evictor.evictFolders(List.of(folderId));
							try {
								assertEquals("uncommitted", FolderDAO.get().findById(folderId).getName());
							} catch (PersistenceException e) {
								throw new IllegalArgumentException(e);
							}
							if (evictOnRollback)
								evictor.evictFoldersOnRollback(List.of(folderId));
							throw new IllegalStateException("veto");
						}));
	}
}
