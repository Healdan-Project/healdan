// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.preview.PreviewJobs;
import com.logicaldoc.healdan.support.OfficeFiles;
import com.logicaldoc.healdan.util.StorageUnreachableException;

/**
 * {@code GET /v1/documents/{docNumber}/preview}: a PDF to show, still being made, or none (M6 spec section 9.3).
 * <p>
 * Clerk reads and previews, with no Download, in matter 998.1; Reader may only read; Outsider has no grant.
 */
public class PreviewResourceTest extends ApiTestCase {

	private User clerk;

	private User reader;

	private User outsider;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.PREVIEW);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private Document file(String name, byte[] content) throws Exception {
		Document d = fx.upload(documents, name, content, fx.admin());
		awaitAsyncWork();
		return d;
	}

	private static String path(Document d) {
		return "/api/v1/documents/" + d.getCustomId() + "/preview";
	}

	private static void storeConversion(Document d, String fileVersion, byte[] pdf) throws Exception {
		Store.get().store(new ByteArrayInputStream(pdf),
				StoreResource.builder().docId(d.getId()).fileVersion(fileVersion).suffixPdfConversion().build());
	}

	@Test
	public void testPdfIsServedAsItIs() throws Exception {
		byte[] pdf = OfficeFiles.pdf(2);
		Document d = file("a.pdf", pdf);

		ApiResponse response = get(path(d)).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("application/pdf", response.header("Content-Type"));
		assertEquals(Integer.toString(pdf.length), response.header("Content-Length"));
		assertArrayEquals(pdf, response.body());
	}

	@Test
	public void testConversionOfTheAskedVersion() throws Exception {
		Document d = file("a.docx", OfficeFiles.docx(null));
		byte[] first = OfficeFiles.pdf(1);
		byte[] second = OfficeFiles.pdf(2);
		storeConversion(d, "1.0", first);
		DocumentManager.get().checkout(d.getId(), fx.documentHistory(fx.admin()));
		DocumentManager.get().checkin(d.getId(), new ByteArrayInputStream(OfficeFiles.docx(null)), "a.docx", false,
				null, fx.documentHistory(fx.admin()));
		awaitAsyncWork();
		String current = DocumentDAO.get().findById(d.getId()).getFileVersion();
		storeConversion(d, current, second);
		String token = signIn(clerk);

		ApiResponse asked = get(path(d) + "?version=1.0").token(token).send();
		ApiResponse latest = get(path(d)).token(token).send();

		assertEquals(asked.toString(), 200, asked.status());
		assertEquals("application/pdf", asked.header("Content-Type"));
		assertArrayEquals(first, asked.body());
		assertArrayEquals(second, latest.body());
	}

	/** A PNG's signature, and a little more */
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D',
			'R' };

	private int downloads() {
		return jdbc().queryForObject("select count(*) from ld_history where ld_event = 'event.downloaded'",
				Integer.class).intValue();
	}

	@Test
	public void testPictureAnswersItsOwnBytesAndType() throws Exception {
		Document d = file("a.png", PNG);

		ApiResponse response = get(path(d)).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("image/png", response.header("Content-Type"));
		assertEquals(Integer.toString(PNG.length), response.header("Content-Length"));
		assertArrayEquals(PNG, response.body());
	}

	@Test
	public void testPictureOfAskedVersion() throws Exception {
		Document d = file("a.png", PNG);

		ApiResponse response = get(path(d) + "?version=1.0").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertArrayEquals(PNG, response.body());
	}

	@Test
	public void testPictureWithoutPreviewPermission403() throws Exception {
		Document d = file("a.png", PNG);

		ApiResponse response = get(path(d)).token(signIn(reader)).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("forbidden", response.code());
	}

	@Test
	public void testPreviewIsNotRecordedAsADownload() throws Exception {
		Document picture = file("a.png", PNG);
		Document pdf = file("a.pdf", OfficeFiles.pdf(1));
		String token = signIn(clerk);

		assertEquals(200, get(path(picture)).token(token).send().status());
		assertEquals(200, get(path(pdf)).token(token).send().status());

		assertEquals(0, downloads());
	}

	@Test
	public void testProtectedDocumentIsRefused() throws Exception {
		Document d = file("a.pdf", OfficeFiles.pdf(1));
		com.logicaldoc.core.document.DocumentHistory history = fx.documentHistory(fx.admin());
		history.setEvent(com.logicaldoc.core.document.DocumentEvent.CHANGED);
		DocumentDAO.get().setPassword(d.getId(), "S3cret-pw!", history);

		ApiResponse response = get(path(d)).token(signIn(clerk)).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("forbidden", response.code());
	}

	@Test
	public void testHeadOpensNoStream() throws Exception {
		Document d = file("a.pdf", OfficeFiles.pdf(1));
		String token = signIn(clerk);
		int[] opened = { 0 };
		DefaultListableBeanFactory beans = ((GenericApplicationContext) context).getDefaultListableBeanFactory();
		String name = beans.getBeanNamesForType(Store.class)[0];
		FSStore real = (FSStore) beans.getBean(name);
		FSStore counting = new FSStore() {
			@Override
			public InputStream getStream(StoreResource resource) throws IOException {
				opened[0]++;
				return super.getStream(resource);
			}
		};
		counting.setId(real.getId());
		beans.destroySingleton(name);
		beans.registerSingleton(name, counting);

		ApiResponse head = head(path(d)).token(token).send();

		assertEquals(head.toString(), 200, head.status());
		assertEquals("application/pdf", head.header("Content-Type"));
		assertEquals(0, opened[0]);
	}

	@Test
	public void testPendingAsksAgainIn5Seconds() throws Exception {
		Document d = file("a.docx", OfficeFiles.docx(null));
		PreviewJobs.get().add(d.getId(), d.getFileVersion(), Instant.now());

		ApiResponse response = get(path(d)).token(signIn(clerk)).send();

		assertEquals(response.toString(), 202, response.status());
		assertEquals("5", response.header("Retry-After"));
		assertEquals(0, response.body().length);
	}

	@Test
	public void testNoPreviewIs404() throws Exception {
		Document d = file("a.zip", new byte[] { 'P', 'K' });

		ApiResponse response = get(path(d)).token(signIn(clerk)).send();

		assertEquals(response.toString(), 404, response.status());
		assertEquals("no-preview", response.code());
	}

	@Test
	public void testUnknownVersion404() throws Exception {
		Document d = file("a.pdf", OfficeFiles.pdf(1));

		ApiResponse response = get(path(d) + "?version=9.9").token(signIn(clerk)).send();

		assertEquals(response.toString(), 404, response.status());
		assertEquals("not-found", response.code());
	}

	@Test
	public void testUnreadable404() throws Exception {
		Document d = file("a.pdf", OfficeFiles.pdf(1));
		String token = signIn(outsider);

		ApiResponse unreadable = get(path(d)).token(token).send();
		ApiResponse unknown = get("/api/v1/documents/987654/preview").token(token).send();

		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(unknown.text().replace("987654", "N"), unreadable.text().replace(d.getCustomId(), "N"));
	}

	@Test
	public void testWithoutPreviewPermission403() throws Exception {
		Document d = file("a.pdf", OfficeFiles.pdf(1));

		ApiResponse response = get(path(d)).token(signIn(reader)).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("forbidden", response.code());
	}

	@Test
	public void testStorageOutage503() throws Exception {
		Document pdf = file("a.pdf", OfficeFiles.pdf(1));
		Document word = file("a.docx", OfficeFiles.docx(null));
		storeConversion(word, word.getFileVersion(), OfficeFiles.pdf(1));
		String token = signIn(clerk);
		unreachable();

		// The file itself can't be opened, nor the stored conversion
		for (Document d : new Document[] { pdf, word }) {
			ApiResponse response = get(path(d)).token(token).send();

			assertEquals(response.toString(), 503, response.status());
			assertEquals("storage-unreachable", response.code());
			assertTrue(response.text(), response.text().contains("Smoke's cloud storage"));
		}
	}

	/**
	 * Makes the Store answer as cloud storage does while it can't be reached
	 */
	private void unreachable() {
		DefaultListableBeanFactory beans = ((GenericApplicationContext) context).getDefaultListableBeanFactory();
		String name = beans.getBeanNamesForType(Store.class)[0];
		FSStore real = (FSStore) beans.getBean(name);
		FSStore down = new FSStore() {
			@Override
			public InputStream getStream(StoreResource resource) throws IOException {
				throw new StorageUnreachableException("Smoke", true, new IOException("down"));
			}
		};
		down.setId(real.getId());
		beans.destroySingleton(name);
		beans.registerSingleton(name, down);
	}
}
