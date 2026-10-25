package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;

/**
 * {@code GET /v1/documents/{docNumber}/thumbnail}: the current file's
 * thumbnail, read through the same checks as its content (M3 spec section
 * 9.8).
 * <p>
 * Clerk reads and downloads in matter 998.1; Reader may only read; Outsider
 * has no grant.
 */
public class ThumbnailTest extends ApiTestCase {

	/** A PNG's signature, and a little more */
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H',
			'D', 'R' };

	private User clerk;

	private User reader;

	private User outsider;

	private Document d;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.DOWNLOAD);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		d = fx.upload(documents, "a.txt", "Lease", fx.admin());
		awaitAsyncWork();
	}

	private String path() {
		return "/api/v1/documents/" + d.getCustomId() + "/thumbnail";
	}

	@Test
	public void testPng() throws Exception {
		// Where CE's ThumbnailManager puts the current file's thumbnail
		Store.get().store(new ByteArrayInputStream(PNG),
				StoreResource.builder().docId(d.getId()).fileVersion(d.getFileVersion()).suffixThumbnail().build());

		ApiResponse response = get(path()).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("image/png", response.header("Content-Type"));
		assertEquals(Integer.toString(PNG.length), response.header("Content-Length"));
		assertArrayEquals(PNG, response.body());
	}

	@Test
	public void testMissing404() throws Exception {
		ApiResponse response = get(path()).token(signIn(clerk)).send();

		assertEquals(response.toString(), 404, response.status());
		assertEquals("not-found", response.code());
		assertEquals("Document #%s has no thumbnail yet.".formatted(d.getCustomId()),
				response.json().path("detail").textValue());
	}

	@Test
	public void testUnreadable404() throws Exception {
		Store.get().store(new ByteArrayInputStream(PNG),
				StoreResource.builder().docId(d.getId()).fileVersion(d.getFileVersion()).suffixThumbnail().build());
		String token = signIn(outsider);

		ApiResponse unreadable = get(path()).token(token).send();
		ApiResponse unknown = get("/api/v1/documents/987654/thumbnail").token(token).send();

		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(unknown.text().replace("987654", "N"), unreadable.text().replace(d.getCustomId(), "N"));
	}

	@Test
	public void testWithoutDownload403() throws Exception {
		Store.get().store(new ByteArrayInputStream(PNG),
				StoreResource.builder().docId(d.getId()).fileVersion(d.getFileVersion()).suffixThumbnail().build());

		ApiResponse response = get(path()).token(signIn(reader)).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("forbidden", response.code());
	}
}
