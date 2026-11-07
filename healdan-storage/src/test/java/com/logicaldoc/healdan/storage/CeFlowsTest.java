// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.files;
import static com.logicaldoc.healdan.storage.StorageFixtures.held;
import static com.logicaldoc.healdan.storage.StorageFixtures.heldRows;
import static com.logicaldoc.healdan.storage.StorageFixtures.key;
import static com.logicaldoc.healdan.storage.StorageFixtures.local;
import static com.logicaldoc.healdan.storage.StorageFixtures.moveToBucket;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.StorageFixtures.staged;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static com.logicaldoc.healdan.storage.StorageFixtures.version;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.document.thumbnail.ThumbnailBuilder;
import com.logicaldoc.core.document.thumbnail.ThumbnailManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.preview.OfficeThumbnailBuilder;
import com.logicaldoc.healdan.preview.PreviewBridge;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * CE's own document code through {@link HealdanStorage} (spec section 16.1), each flow on a Store on a local backend
 * and on one on an encrypted S3-kind backend.
 */
public class CeFlowsTest extends AbstractStorageTestCase {

	/** A PNG's signature, enough for the stored thumbnail */
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };

	private StorageFixtures sf;

	private HealdanFixtures fx;

	private Side local;

	private Side cloud;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			local = sf.localSide("Local", false);
			cloud = sf.s3Side("Cloud", true);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private interface Flow {
		void run(Side side) throws Exception;
	}

	/** Runs the flow on the local Store, then on the S3 one */
	private void each(Flow flow) throws Exception {
		for (Side side : List.of(local, cloud))
			try {
				flow.run(side);
			} catch (AssertionError e) {
				throw new AssertionError("On the " + side + " Store: " + e.getMessage(), e);
			}
	}

	private com.logicaldoc.core.document.DocumentHistory history() throws Exception {
		return fx.documentHistory(fx.admin());
	}

	private Document upload(Side side, String name, String text) throws Exception {
		return fx.upload(side.folder(), name, text, fx.admin());
	}

	private Document checkin(Document doc, String text) throws Exception {
		DocumentManager.get().checkout(doc.getId(), history());
		var future = DocumentManager.get().checkin(doc.getId(),
				new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), doc.getFileName(), false, null,
				history());
		future.get();
		return future.getDocument();
	}

	/**
	 * A document with file versions 1.2 and 1.20, each made by a check-in through CE, and their thumbnails and
	 * conversions. The test context keeps two versions ({@code document.maxversions}), so CE's version quota deletes
	 * 1.0 at the second check-in, as it would on a server.
	 */
	private Document versions(Side side) throws Exception {
		Document doc = upload(side, "a.txt", "file 1.0");
		version(doc, "1.1");
		assertEquals("1.2", checkin(doc, "file 1.2").getFileVersion());
		version(doc, "1.19");
		Document current = checkin(doc, "file 1.20");
		assertEquals("1.20", current.getFileVersion());
		// CE's own delete of an old version, from HibernateVersionDAO, is held as any other
		assertTrue("CE's version quota deleted 1.0", StoredFiles.get().find(doc.getId(), "1.0").isEmpty());
		assertHeld(side, doc.getId(), "1.0", side.saveLandsOn());
		for (String fileVersion : List.of("1.2", "1.20")) {
			Store.get().store(file("thumb " + fileVersion), resource(doc.getId(), fileVersion + "-thumb.png"));
			Store.get().store(file("pdf " + fileVersion), resource(doc.getId(), fileVersion + "-conversion.pdf"));
		}
		return current;
	}

	/**
	 * The row is where a save into the side's Store lands, with the Store, and the file is there: on the local
	 * backend, not in staging; or in staging, for the mover
	 */
	private static void assertSaved(Side side, long docId, String resource) {
		StoredFile row = row(docId, resource);
		assertEquals(resource, side.saveLandsOn(), row.backendId());
		assertEquals(resource, Long.valueOf(side.store().getId()), row.storeId());
		Path place = side.isS3() ? staged(docId, resource) : local(side.root(), docId, resource);
		assertTrue(resource + " at " + place, Files.isRegularFile(place));
		if (!side.isS3())
			assertFalse(Files.exists(staged(docId, resource)));
	}

	/** The copy is held where its row was: under a local backend's or staging's .held/, or left in the bucket */
	private static void assertHeld(Side side, long docId, String resource, long backendId) {
		String key = key(docId, resource);
		HeldCopy held = heldRows(docId).stream().filter(h -> h.resource().equals(resource)).findFirst()
				.orElseThrow(() -> new AssertionError("No held row for " + resource));
		assertEquals(resource, backendId, held.backendId());
		if (backendId == side.backendId() && side.isS3()) {
			assertEquals(key, held.location());
			assertNotNull(resource + " stays in the bucket", side.s3().bytes(key));
		} else {
			assertEquals(".held/" + key, held.location());
			Path root = backendId == 0L ? Staging.folder() : side.root();
			assertTrue(resource + " under .held/", Files.isRegularFile(held(root, docId, resource)));
			assertFalse(resource + " gone from its place", Files.exists(root.resolve(key)));
		}
	}

	private static void assertNoS3Calls(Side side, long docId, String... resources) {
		if (side.isS3())
			for (String resource : resources)
				assertEquals(resource, 0, side.s3().calls(key(docId, resource)));
	}

	private static Map<String, StoredFile> rows(long docId) {
		Map<String, StoredFile> rows = new LinkedHashMap<>();
		for (StoredFile row : StoredFiles.get().list(docId))
			rows.put(row.resource(), row);
		return rows;
	}

	@Test
	public void testCreateThenCheckin() throws Exception {
		each(side -> {
			Document doc = upload(side, "a.txt", "one");
			assertSaved(side, doc.getId(), "1.0");

			Document current = checkin(doc, "two");
			assertEquals("1.1", current.getFileVersion());
			assertSaved(side, doc.getId(), "1.0");
			assertSaved(side, doc.getId(), "1.1");
			assertEquals("one", text(resource(doc.getId(), "1.0")));
			assertEquals("two", text(StoreResource.builder().document(current).build()));
			assertNoS3Calls(side, doc.getId(), "1.0", "1.1");
			if (side.isS3())
				for (String resource : List.of("1.0", "1.1"))
					assertNull(side.s3().bytes(key(doc.getId(), resource)));
		});
	}

	@Test
	public void testReplaceFileExactVersion() throws Exception {
		each(side -> {
			Document doc = versions(side);
			long id = doc.getId();
			// On S3, as after the mover's uploads, so that what is held stays in the bucket
			if (side.isS3())
				for (String resource : rows(id).keySet())
					moveToBucket(side, id, resource);
			Map<String, StoredFile> before = rows(id);
			assertEquals(List.of("1.2", "1.2-conversion.pdf", "1.2-thumb.png", "1.20", "1.20-conversion.pdf",
					"1.20-thumb.png"), List.copyOf(before.keySet()));
			int heldBefore = heldRows(id).size();

			DocumentManager.get().replaceFile(id, "1.2", file("replaced 1.2"), history()).get();

			// 1.20's files stay as they were
			for (String resource : List.of("1.20", "1.20-conversion.pdf", "1.20-thumb.png"))
				assertEquals(resource, before.get(resource), row(id, resource));
			assertEquals("file 1.20", text(resource(id, "1.20")));
			assertEquals("thumb 1.20", text(resource(id, "1.20-thumb.png")));
			// 1.2's derived files are held, their rows gone
			for (String resource : List.of("1.2-conversion.pdf", "1.2-thumb.png")) {
				assertTrue(resource, StoredFiles.get().find(id, resource).isEmpty());
				assertHeld(side, id, resource, side.backendId());
			}
			// 1.2 itself is rewritten: in place on a local backend, staged on S3 with the bucket's copy held
			assertEquals("replaced 1.2", text(resource(id, "1.2")));
			assertSaved(side, id, "1.2");
			if (side.isS3())
				assertHeld(side, id, "1.2", side.backendId());
			assertEquals(heldBefore + (side.isS3() ? 3 : 2), heldRows(id).size());

			// M1.5 makes the thumbnail again, and the file is replaced again within the holding period
			Store.get().store(file("thumb 1.2 again"), resource(id, "1.2-thumb.png"));
			long firstPurge = heldRows(id).stream().filter(h -> h.resource().equals("1.2-thumb.png")).findFirst()
					.orElseThrow().purge().getTime();
			DocumentManager.get().replaceFile(id, "1.2", file("replaced again"), history()).get();

			assertEquals("replaced again", text(resource(id, "1.2")));
			assertTrue(StoredFiles.get().find(id, "1.2-thumb.png").isEmpty());
			long thumbHolds = heldRows(id).stream().filter(h -> h.resource().equals("1.2-thumb.png")).count();
			if (side.isS3()) {
				// The new thumbnail was staged, so it is held in staging; the bucket's older one stays held
				assertEquals(2, thumbHolds);
				assertHeld(side, id, "1.2-thumb.png", 0L);
				assertEquals("thumb 1.2 again",
						Files.readString(held(Staging.folder(), id, "1.2-thumb.png")));
			} else {
				// The newer copy took the older one's place under .held/, and its row's
				assertEquals(1, thumbHolds);
				assertHeld(side, id, "1.2-thumb.png", side.backendId());
				assertEquals("thumb 1.2 again", Files.readString(held(side.root(), id, "1.2-thumb.png")));
				long purge = heldRows(id).stream().filter(h -> h.resource().equals("1.2-thumb.png")).findFirst()
						.orElseThrow().purge().getTime();
				assertTrue(purge >= firstPurge);
			}
			for (String resource : List.of("1.20", "1.20-conversion.pdf", "1.20-thumb.png"))
				assertEquals(resource, before.get(resource), row(id, resource));
		});
	}

	@Test
	public void testZeroByteRefused() throws Exception {
		each(side -> {
			Document doc = upload(side, "a.txt", "kept");
			StoredFile before = row(doc.getId(), "1.0");
			File empty = file(new byte[0]);

			assertThrows(IOException.class,
					() -> DocumentManager.get().replaceFile(doc.getId(), "1.0", empty, history()));
			assertEquals(before, row(doc.getId(), "1.0"));
			assertEquals("kept", text(resource(doc.getId(), "1.0")));

			DocumentManager.get().checkout(doc.getId(), history());
			assertThrows(PersistenceException.class,
					() -> DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream(new byte[0]), "a.txt",
							false, null, history()));
			assertEquals(before, row(doc.getId(), "1.0"));
			assertEquals("kept", text(resource(doc.getId(), "1.0")));
			assertEquals(List.of("1.0"), List.copyOf(rows(doc.getId()).keySet()));
			assertTrue(heldRows(doc.getId()).isEmpty());
		});
	}

	@Test
	public void testReplaceOverAnUnrecordedFile() throws Exception {
		// A thumbnail only CE's own store wrote, in Original's folder, with no row (call 11), beside one of 1.20
		Document doc = upload(local, "a.txt", "file 1.0");
		long id = doc.getId();
		Path original = Path.of(Context.get().getConfig().getProperty("store.1.dir"));
		Path thumb = original.resolve(key(id, "1.0-thumb.png"));
		Path other = original.resolve(key(id, "1.00-thumb.png"));
		Files.createDirectories(thumb.getParent());
		Files.writeString(thumb, "stale thumbnail");
		Files.writeString(other, "another version's");
		assertEquals(List.of("1.0", "1.0-thumb.png"),
				Store.get().listResources(id, "1.0").stream().map(StoreResource::name).sorted().toList());

		DocumentManager.get().replaceFile(id, "1.0", file("replaced"), history()).get();

		// CE deleted what the store listed: the stale thumbnail is held in Original, and no longer read as current
		assertFalse(Files.exists(thumb));
		assertEquals("stale thumbnail", Files.readString(held(original, id, "1.0-thumb.png")));
		assertFalse(Store.get().exists(resource(id, "1.0-thumb.png")));
		assertThrows(IOException.class, () -> Store.get().getBytes(resource(id, "1.0-thumb.png")));
		assertEquals("replaced", text(resource(id, "1.0")));
		// Another file version's is left alone
		assertEquals("another version's", Files.readString(other));
		assertTrue(Store.get().exists(resource(id, "1.00-thumb.png")));
	}

	@Test
	public void testAliasReadAndDestroyed() throws Exception {
		each(side -> {
			Document target = upload(side, "target.txt", "the target");
			long id = target.getId();
			Document alias = fx.legacyShortcut(target, side.folder());
			if (side.isS3())
				moveToBucket(side, id, "1.0");
			Map<String, StoredFile> before = rows(id);
			byte[] stored = side.isS3() ? side.s3().bytes(key(id, "1.0"))
					: Files.readAllBytes(local(side.root(), id, "1.0"));

			// CE's resource of an alias is its target's
			assertEquals("the target", text(StoreResource.builder().document(alias).build()));

			DocumentDAO.get().delete(alias.getId(), history());
			DocumentManager.get().destroyDocument(alias.getId(), fx.folderHistory(fx.admin()));

			assertEquals(before, rows(id));
			assertTrue(heldRows(id).isEmpty());
			assertArrayEquals(stored, side.isS3() ? side.s3().bytes(key(id, "1.0"))
					: Files.readAllBytes(local(side.root(), id, "1.0")));
			assertEquals("the target", text(resource(id, "1.0")));
			if (side.isS3())
				assertEquals(0, side.s3().calls(Op.DELETE, key(id, "1.0")));
			// The alias's own rows, if any, are gone with it
			assertTrue(StoredFiles.get().list(alias.getId()).isEmpty());
		});
	}

	@Test
	public void testDeleteVersionExact() throws Exception {
		each(side -> {
			Document doc = versions(side);
			long id = doc.getId();
			if (side.isS3())
				moveToBucket(side, id, "1.2");
			Map<String, StoredFile> before = rows(id);
			Version doomed = VersionDAO.get().findByVersion(id, "1.2");

			DocumentManager.get().deleteVersion(doomed.getId(), history());

			for (String resource : List.of("1.2", "1.2-conversion.pdf", "1.2-thumb.png"))
				assertTrue(resource, StoredFiles.get().find(id, resource).isEmpty());
			assertHeld(side, id, "1.2", side.backendId());
			assertHeld(side, id, "1.2-thumb.png", side.saveLandsOn());
			assertHeld(side, id, "1.2-conversion.pdf", side.saveLandsOn());
			for (String resource : List.of("1.20", "1.20-conversion.pdf", "1.20-thumb.png"))
				assertEquals(resource, before.get(resource), row(id, resource));
			assertEquals("file 1.20", text(resource(id, "1.20")));
			assertEquals(List.of("1.20", "1.20-conversion.pdf", "1.20-thumb.png"),
					Store.get().listResources(id, "1.20").stream().map(StoreResource::name).sorted().toList());
		});
	}

	@Test
	public void testDestroyDocument() throws Exception {
		each(side -> {
			Document doc = upload(side, "a.txt", "one");
			long id = doc.getId();
			checkin(doc, "two");
			Store.get().store(file("thumb"), resource(id, "1.1-thumb.png"));
			// On S3, one file uploaded and two still staged
			if (side.isS3())
				moveToBucket(side, id, "1.0");

			DocumentDAO.get().delete(id, history());
			DocumentManager.get().destroyDocument(id, fx.folderHistory(fx.admin()));

			assertEquals(Long.valueOf(0), StorageFixtures.jdbc()
					.queryForObject("select count(*) from ld_document where ld_id = ?", Long.class, id));
			assertTrue(StoredFiles.get().list(id).isEmpty());
			assertEquals(3, heldRows(id).size());
			assertHeld(side, id, "1.0", side.backendId());
			assertHeld(side, id, "1.1", side.saveLandsOn());
			assertHeld(side, id, "1.1-thumb.png", side.saveLandsOn());
			for (String resource : List.of("1.0", "1.1", "1.1-thumb.png"))
				assertFalse(resource, Store.get().exists(resource(id, resource)));
			if (side.isS3())
				assertEquals("the bucket's copy is left alone", 0, side.s3().calls(Op.DELETE, key(id, "1.0")));
		});
	}

	@Test
	public void testCopyToFolderAcrossStores() throws Exception {
		// From S3 to a local Store: the copy is on the local backend, and nothing is written to the bucket
		Document fromCloud = upload(cloud, "a.txt", "from the cloud");
		moveToBucket(cloud, fromCloud.getId(), "1.0");
		var future = DocumentManager.get().copyToFolder(fromCloud, local.folder(), history(), false, false, false);
		future.get();
		Document copy = future.getDocument();
		assertSaved(local, copy.getId(), "1.0");
		assertEquals("from the cloud", text(resource(copy.getId(), "1.0")));
		assertEquals(0, cloud.s3().calls(key(copy.getId(), "1.0")));
		assertEquals(1, cloud.s3().calls(Op.GET, key(fromCloud.getId(), "1.0")));
		assertEquals(0, cloud.s3().calls(Op.PUT, key(fromCloud.getId(), "1.0")));

		// From a local Store to S3: the copy is staged with the S3 Store, and nothing is written to the local backend
		Document fromLocal = upload(local, "b.txt", "from local");
		List<String> onLocal = files(local.root());
		future = DocumentManager.get().copyToFolder(fromLocal, cloud.folder(), history(), false, false, false);
		future.get();
		copy = future.getDocument();
		assertSaved(cloud, copy.getId(), "1.0");
		assertNull(cloud.s3().bytes(key(copy.getId(), "1.0")));
		assertEquals("from local", text(resource(copy.getId(), "1.0")));
		assertEquals(onLocal, files(local.root()));
	}

	@Test
	public void testFolderCopyInOneTransaction() throws Exception {
		// CE copies a folder's documents in one transaction, each document's row not yet committed when its file
		// is stored
		Folder source = fx.folder(cloud.folder(), "Source");
		Document doc = fx.upload(source, "a.txt", "copied", fx.admin());
		moveToBucket(cloud, doc.getId(), "1.0");
		List<String> onLocal = files(local.root());

		Folder copied = FolderDAO.get().copy(source, local.folder(), null, false, "inherit",
				fx.folderHistory(fx.admin()));

		List<Document> copies = DocumentDAO.get().findByFolder(copied.getId(), null);
		assertEquals(1, copies.size());
		long id = copies.get(0).getId();
		assertSaved(local, id, "1.0");
		assertEquals("copied", text(resource(id, "1.0")));
		assertEquals(onLocal.size() + 1, files(local.root()).size());
		assertEquals(0, cloud.s3().calls(key(id, "1.0")));
	}

	@Test
	public void testPreviewAndThumbnail() throws Exception {
		Context.get().getConfig().setProperty("converter.OfficeConverter.timeout", "30");
		Context.get().getConfig().setProperty("converter.OfficeConverter.path", PreviewBridge.soffice().getPath());
		Map<String, ThumbnailBuilder> builders = ThumbnailManager.get().getBuilders();
		ThumbnailBuilder before = builders.put("docx", new OfficeThumbnailBuilder() {
			// No Poppler or ImageMagick in the tests: the page is "rendered" as a PNG signature
			@Override
			protected void renderFirstPage(String sid, Document document, String fileVersion, File pdf, File dest,
					int size, int quality) throws IOException {
				assertTrue("rendered from the stored conversion", Files.size(pdf.toPath()) > 0);
				Files.write(dest.toPath(), PNG);
			}
		});
		try {
			each(side -> {
				Document doc = upload(side, "a.docx", "a document " + side);
				assertEquals(1, PreviewBridge.runOnce(Instant.now().plus(Duration.ofDays(1))));

				String[] resources = { "1.0", "1.0-conversion.pdf", "1.0-thumb.png", "1.0-tile.png" };
				for (String resource : resources)
					assertSaved(side, doc.getId(), resource);
				assertEquals("%PDF", text(resource(doc.getId(), "1.0-conversion.pdf")).substring(0, 4));
				assertArrayEquals(PNG, Store.get().getBytes(resource(doc.getId(), "1.0-thumb.png")));
				assertNoS3Calls(side, doc.getId(), resources);
			});
		} finally {
			if (before != null)
				builders.put("docx", before);
			else
				builders.remove("docx");
		}
	}

	@Test
	public void testRangedRead() throws Exception {
		byte[] content = new byte[(5 << 20) / 2];
		new Random(11).nextBytes(content);
		each(side -> {
			Document doc = fx.upload(side.folder(), "video.bin", content, fx.admin());
			String key = key(doc.getId(), "1.0");
			if (side.isS3())
				moveToBucket(side, doc.getId(), "1.0");
			StoreResource resource = resource(doc, "1.0");

			// A range across the first segment's edge, as ServletUtil serves a Range header
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Store.get().writeToStream(resource, out, 1_048_000, 200_000);
			assertArrayEquals(Arrays.copyOfRange(content, 1_048_000, 1_248_000), out.toByteArray());

			// A range that runs past the end stops there
			assertArrayEquals(Arrays.copyOfRange(content, 2_000_000, content.length),
					Store.get().getBytes(resource, 2_000_000, 1_000_000));

			if (side.isS3()) {
				assertEquals("never the whole object", 0, side.s3().calls(Op.GET, key));
				assertEquals(2, side.s3().calls(Op.OPEN, key));
			}
		});
	}
}
