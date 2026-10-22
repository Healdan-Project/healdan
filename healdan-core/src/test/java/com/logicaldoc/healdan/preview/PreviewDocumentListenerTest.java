package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

public class PreviewDocumentListenerTest extends AbstractHealdanTestCase {

	private HealdanFixtures fx;

	private Folder folder;

	@Override
	public void setUp() throws java.io.IOException, java.sql.SQLException, com.logicaldoc.util.plugin.PluginException {
		super.setUp();
		try {
			fx = new HealdanFixtures();
			Matter matter = fx.matter(fx.client(997, "Healdan"), 1, "Previews", fx.store("Main"), "Documents");
			folder = FolderDAO.get()
					.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true)
					.get(0);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static boolean queued(long id, String version) {
		return PreviewJobs.get().find(id, version).isPresent();
	}

	@Test
	public void testUploadQueuesOffice() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		Document b = fx.upload(folder, "B.XLSX", "b", fx.admin());
		Document c = fx.upload(folder, "c.txt", "c", fx.admin());
		Document d = fx.upload(folder, "d.pdf", "d", fx.admin());
		assertTrue(queued(a.getId(), "1.0"));
		assertTrue(queued(b.getId(), "1.0"));
		assertFalse(queued(c.getId(), "1.0"));
		assertFalse(queued(d.getId(), "1.0"));
		assertEquals(2, jdbc().queryForObject("select count(*) from hd_preview_job", Integer.class).intValue());
	}

	@Test
	public void testCheckinQueuesNewVersion() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		DocumentManager.get().checkout(a.getId(), fx.documentHistory(fx.admin()));
		DocumentManager.get().checkin(a.getId(), new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)),
				"a.docx", false, null, fx.documentHistory(fx.admin()));
		String version = DocumentDAO.get().findById(a.getId()).getFileVersion();
		assertEquals("1.1", version);
		assertTrue(queued(a.getId(), "1.0"));
		assertTrue(queued(a.getId(), version));
	}

	@Test
	public void testCopyQueuesTheCopy() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		Folder target = fx.folder(folder, "Copies");
		Document copy = DocumentManager.get()
				.copyToFolder(a, target, fx.documentHistory(fx.admin()), false, false, false).getDocument();
		assertTrue(copy.getId() != a.getId());
		assertTrue(queued(copy.getId(), copy.getFileVersion()));
	}

	@Test
	public void testReplaceFileQueuesCurrentVersion() throws Exception {
		Document a = fx.upload(folder, "a.docx", "a", fx.admin());
		// The upload queued it already; take it off so that the replace has to do it again
		PreviewJobs.get().done(a.getId(), "1.0");
		assertFalse(queued(a.getId(), "1.0"));

		File file = Files.createTempFile("replace", ".docx").toFile();
		try {
			Files.writeString(file.toPath(), "new");
			DocumentHistory transaction = fx.documentHistory(fx.admin());
			DocumentManager.get().replaceFile(a.getId(), "1.0", file, transaction);
		} finally {
			file.delete();
		}
		assertTrue(queued(a.getId(), "1.0"));
		assertEquals(1, PreviewJobs.get().due(Instant.now().plusSeconds(1), 10).size());
	}

	@Test
	public void testListenerNeverBlocksTheSave() throws Exception {
		jdbc().execute("drop table hd_preview_job");
		try {
			Document e = fx.upload(folder, "e.docx", "e", fx.admin());
			assertTrue(e.getId() != 0L);
		} finally {
			jdbc().execute(
					"create table hd_preview_job (hd_docid bigint not null, hd_fileversion varchar(10) not null, hd_attempts int not null, hd_nexttry timestamp not null, hd_lasterror varchar(4000), hd_created timestamp not null, primary key (hd_docid, hd_fileversion))");
		}
	}
}
