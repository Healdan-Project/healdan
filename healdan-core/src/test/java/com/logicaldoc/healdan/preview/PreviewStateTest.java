// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.Instant;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.OfficeFiles;
import com.logicaldoc.util.plugin.PluginException;

/**
 * What a document's preview is, version by version (M6 spec section 9.3).
 */
public class PreviewStateTest extends AbstractHealdanTestCase {

	private HealdanFixtures fx;

	private Folder folder;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		PreviewTests.clearStore();
		try {
			fx = new HealdanFixtures();
			Matter matter = fx.matter(fx.client(993, "Healdan"), 1, "State", fx.store("Main"), "Documents");
			folder = FolderDAO.get()
					.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true)
					.get(0);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static StoreResource pdfOf(Document doc, String fileVersion) {
		return StoreResource.builder().docId(doc.getId()).fileVersion(fileVersion).suffixPdfConversion().build();
	}

	private static byte[] placeholder() throws IOException {
		try (InputStream in = PreviewStateTest.class.getResourceAsStream("/pdf/notavailable.pdf")) {
			return in.readAllBytes();
		}
	}

	@Test
	public void testPdfIsReady() throws Exception {
		Document pdf = fx.upload(folder, "a.pdf", OfficeFiles.pdf(2), fx.admin());

		assertEquals(PreviewState.READY, PreviewState.of(pdf, pdf.getFileVersion()));
	}

	@Test
	public void testStoredConversionIsReady() throws Exception {
		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		Store.get().store(new ByteArrayInputStream(OfficeFiles.pdf(3)), pdfOf(word, word.getFileVersion()));
		// The job is still queued: the conversion is what counts
		PreviewJobs.get().add(word.getId(), word.getFileVersion(), Instant.now());

		assertEquals(PreviewState.READY, PreviewState.of(word, word.getFileVersion()));
	}

	@Test
	public void testQueuedJobWithoutConversionIsPending() throws Exception {
		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		PreviewJobs.get().add(word.getId(), word.getFileVersion(), Instant.now());

		assertEquals(PreviewState.PENDING, PreviewState.of(word, word.getFileVersion()));
	}

	@Test
	public void testPlaceholderIsNone() throws Exception {
		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		Store.get().store(new ByteArrayInputStream(placeholder()), pdfOf(word, word.getFileVersion()));

		assertEquals(PreviewState.NONE, PreviewState.of(word, word.getFileVersion()));
	}

	@Test
	public void testGivenUpJobIsNone() throws Exception {
		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(word.getId(), word.getFileVersion(), Instant.now());
		for (int i = 0; i < PreviewJobs.MAX_ATTEMPTS; i++)
			jobs.failed(word.getId(), word.getFileVersion(), "no", Instant.now());

		assertEquals(PreviewState.NONE, PreviewState.of(word, word.getFileVersion()));
	}

	@Test
	public void testZipIsNone() throws Exception {
		Document zip = fx.upload(folder, "a.zip", "PK", fx.admin());

		assertEquals(PreviewState.NONE, PreviewState.of(zip, zip.getFileVersion()));
	}

	@Test
	public void testAFileVersionOfAnotherTypeGoesByItsOwnName() throws Exception {
		Document word = fx.upload(folder, "a.docx", OfficeFiles.docx(null), fx.admin());

		assertEquals(PreviewState.READY, PreviewState.of(word, "1.0", "old.pdf"));
		// Version 2.0 has no job: the upload of the Word file queued 1.0 alone
		assertEquals(PreviewState.NONE, PreviewState.of(word, "2.0", "old.zip"));
	}
}
