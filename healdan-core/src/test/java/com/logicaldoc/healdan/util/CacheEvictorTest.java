package com.logicaldoc.healdan.util;

import static org.junit.Assert.assertEquals;

import java.util.List;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

public class CacheEvictorTest extends AbstractHealdanTestCase {

	@Test
	public void testJdbcRenameIsVisibleAfterEviction() throws Exception {
		Folder workspace = FolderDAO.get().findFolder(Folder.DEFAULTWORKSPACEID);
		Folder vo = new Folder("before");
		vo.setTenantId(Tenant.DEFAULT_ID);
		long id = FolderDAO.get().create(workspace, vo, true, null).getId();
		assertEquals("before", FolderDAO.get().findById(id).getName());

		new JdbcTemplate(Context.get(DataSource.class)).update("update ld_folder set ld_name = 'after' where ld_id = ?",
				id);
		assertEquals("The second-level cache still holds the old row", "before", FolderDAO.get().findById(id).getName());

		Context.get(CacheEvictor.class).evictFolders(List.of(id));

		assertEquals("after", FolderDAO.get().findById(id).getName());
	}
}
