// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.backend.GatedBackend;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.crypto.KeyCheck;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Backends, Stores and matters for the tests of {@link HealdanStorage}: local backends in folders under
 * {@code target/}, and S3-kind backends that are {@link MemoryBackend}s registered with {@link Backends#use}.
 */
public final class StorageFixtures {

	/** Where the local backends' folders go; emptied as each is made */
	private static final Path LOCAL_ROOTS = Path.of("target/storage-local");

	private static int nextClient = 900;

	public final HealdanFixtures fx = new HealdanFixtures();

	/**
	 * Where a Store's documents go, and what keeps them.
	 *
	 * @param store the Store's folder
	 * @param folder a folder in a matter of the Store, where documents may be filed
	 * @param backendId the Store's backend
	 * @param root a local backend's folder; null for an S3 one
	 * @param s3 an S3 backend's fake; null for a local one
	 */
	public record Side(String name, Folder store, Folder folder, long backendId, Path root, MemoryBackend s3) {

		public boolean isS3() {
			return s3 != null;
		}

		/** @return where a save into this Store lands: the backend if local, staging if S3 */
		long saveLandsOn() {
			return isS3() ? 0L : backendId;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/**
	 * @param name a folder's name under {@code target/storage-local/}
	 * @return that folder, absolute, made empty
	 */
	public static Path freshFolder(String name) throws IOException {
		Path root = LOCAL_ROOTS.resolve(name).toAbsolutePath().normalize();
		FileUtils.deleteDirectory(root.toFile());
		Files.createDirectories(root);
		return root;
	}

	/**
	 * @param name the backend's name, also its folder's under {@code target/storage-local/}
	 * @param encrypted whether its files are encrypted, with a key made for it
	 * @return the backend's id; its folder holds its marker
	 */
	public long localBackend(String name, boolean encrypted) throws Exception {
		Path root = freshFolder(name);
		StorageBackend row = new StorageBackend();
		row.setTenantId(Tenant.DEFAULT_ID);
		row.setName(name);
		row.setKind(StorageBackend.LOCAL);
		row.setPath(root.toString());
		row.setEncryptedOn(encrypted);
		row.setKeyConfirmedOn(encrypted);
		StorageBackendDAO.get().store(row);
		LocalBackend backend = new LocalBackend(row.getId(), StorageSettings.get().installId(), root, true);
		if (encrypted)
			KeyCheck.write(backend, LocalBackend.CHECK, new FileCipher(Context.get(BackendKeys.class).make(row.getId())));
		return row.getId();
	}

	/**
	 * @param name the backend's name
	 * @param encrypted whether its files are encrypted, with a key made for it
	 * @return an S3-kind backend, kept in memory, registered for its row
	 */
	public MemoryBackend s3Backend(String name, boolean encrypted) throws Exception {
		StorageBackend row = new StorageBackend();
		row.setTenantId(Tenant.DEFAULT_ID);
		row.setName(name);
		row.setKind(StorageBackend.S3);
		row.setEndpoint("https://s3.example.test");
		row.setRegion("test-1");
		row.setBucket("healdan-tests");
		row.setPrefix("healdan/");
		row.setAccessKey("AKTEST");
		row.setEncryptedOn(encrypted);
		row.setKeyConfirmedOn(encrypted);
		StorageBackendDAO.get().store(row);
		MemoryBackend backend = MemoryBackend.s3Like(row.getId());
		if (encrypted)
			KeyCheck.write(backend, LocalBackend.CHECK, new FileCipher(Context.get(BackendKeys.class).make(row.getId())));
		Backends.get().use(row.getId(), backend);
		return backend;
	}

	/**
	 * @return a new Store on the backend, with a client, a matter and its Documents folder
	 */
	public Side side(String name, long backendId, Path root, MemoryBackend s3) throws Exception {
		Folder store = fx.store(name);
		map(store.getId(), backendId);
		Matter matter = fx.matter(fx.client(nextClient++, name + " Client"), 1, name + " Matter", store, "Documents");
		Folder folder = FolderDAO.get()
				.findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents", Tenant.DEFAULT_ID, true)
				.get(0);
		return new Side(name, store, folder, backendId, root, s3);
	}

	/** @return a Store on a new local backend */
	public Side localSide(String name, boolean encrypted) throws Exception {
		long id = localBackend(name.toLowerCase(), encrypted);
		return side(name, id, root(id), null);
	}

	/** @return a Store on a new S3-kind backend */
	public Side s3Side(String name, boolean encrypted) throws Exception {
		MemoryBackend s3 = s3Backend(name.toLowerCase(), encrypted);
		return side(name, s3.id(), null, s3);
	}

	/**
	 * Uses a backend in place of the one a backend's row would build ({@link Backends#use}), for the tests of other
	 * packages.
	 */
	public static void useBackend(long backendId, Backend backend) {
		Backends.get().use(backendId, backend);
	}

	/**
	 * @return a {@link GatedBackend} in front of the side's backend, which its files now go through: the S3 fake, or
	 *         the local folder
	 */
	public static GatedBackend gated(Side side) {
		Backend inner = side.isS3() ? side.s3()
				: new LocalBackend(side.backendId(), StorageSettings.get().installId(), side.root(), false);
		GatedBackend gated = new GatedBackend(inner);
		useBackend(side.backendId(), gated);
		return gated;
	}

	/** Gives a Store a backend, or another one, and marks its files that wait as the Store switch does */
	public void map(long storeId, long backendId) throws Exception {
		StoreBackend row = StoreBackendDAO.get().findByStore(storeId).orElseGet(StoreBackend::new);
		row.setTenantId(Tenant.DEFAULT_ID);
		row.setStoreId(storeId);
		row.setBackendId(backendId);
		StoreBackendDAO.get().store(row);
		StoredFiles.get().storeBackendChanged(storeId);
	}

	/** @return a local backend's folder */
	public static Path root(long backendId) throws Exception {
		return Path.of(StorageBackendDAO.get().findById(backendId).getPath());
	}

	/** @return CE's layout for a document's resource: {@code 123/45/doc/1.0} */
	public static String key(long docId, String resource) {
		return storage().keyOf(docId, resource);
	}

	static HealdanStorage storage() {
		return (HealdanStorage) Store.get();
	}

	/** @return where a staged file is */
	public static Path staged(long docId, String resource) {
		return Staging.folder().resolve(key(docId, resource));
	}

	/** @return where a local backend keeps a file */
	public static Path local(Path root, long docId, String resource) {
		return root.resolve(key(docId, resource));
	}

	/** @return where a local backend holds a file */
	public static Path held(Path root, long docId, String resource) {
		return root.resolve(LocalBackend.HELD).resolve(key(docId, resource));
	}

	public static StoreResource resource(long docId, String name) {
		return StoreResource.builder().docId(docId).name(name).build();
	}

	public static StoreResource resource(Document doc, String fileVersion) throws Exception {
		return StoreResource.builder().document(doc).fileVersion(fileVersion).build();
	}

	public static StoredFile row(long docId, String resource) {
		return StoredFiles.get().find(docId, resource)
				.orElseThrow(() -> new AssertionError("No row for " + docId + "/" + resource));
	}

	public static String text(StoreResource resource) throws IOException {
		return new String(Store.get().getBytes(resource), StandardCharsets.UTF_8);
	}

	/** @return a file holding the text */
	public static File file(String text) throws IOException {
		return file(text.getBytes(StandardCharsets.UTF_8));
	}

	public static File file(byte[] bytes) throws IOException {
		File file = Files.createTempFile("storage-test", ".bin").toFile();
		Files.write(file.toPath(), bytes);
		return file;
	}

	/**
	 * Does what the mover will (Task 11): puts a staged file on its Store's S3 backend, encrypted if the backend is,
	 * points its row there and takes it out of staging.
	 */
	public static void moveToBucket(Side side, long docId, String resource) throws Exception {
		StoredFile row = row(docId, resource);
		assertEquals("only a staged file is uploaded", 0L, row.backendId());
		Path staged = staged(docId, resource);
		byte[] bytes = Files.readAllBytes(staged);
		StorageBackend backend = StorageBackendDAO.get().findById(side.backendId());
		if (backend.isEncryptedOn()) {
			Path cipher = Files.createTempFile("storage-test", ".enc");
			new FileCipher(Context.get(BackendKeys.class).read(side.backendId()).orElseThrow()).encrypt(staged, cipher,
					FileCipher.associated(docId, resource));
			bytes = Files.readAllBytes(cipher);
			Files.delete(cipher);
		}
		side.s3().store(key(docId, resource), bytes);
		assertTrue(StoredFiles.get().moved(docId, resource, row.write(), side.backendId()));
		Files.delete(staged);
	}

	/** @return the held rows of a document, by location */
	public static List<HeldCopy> heldRows(long docId) {
		return jdbc().query("select hd_backendid, hd_location, hd_docid, hd_resource, hd_held, hd_purge "
				+ "from hd_storage_held where hd_docid = ? order by hd_backendid, hd_location", (rs, i) -> new HeldCopy(
						rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
						new java.util.Date(rs.getTimestamp(5).getTime()), new java.util.Date(rs.getTimestamp(6).getTime())),
				docId);
	}

	/** @return every file under a folder, dot folders left out, relative to it */
	public static List<String> files(Path root) throws IOException {
		if (root == null || !Files.isDirectory(root))
			return List.of();
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
					.filter(p -> !p.startsWith(".")).sorted().toList();
		}
	}

	/**
	 * Sets a document's version, as if it had been checked in that many times, so that the next check-in makes the
	 * version after it ({@code 1.19} makes {@code 1.20}).
	 */
	static void version(Document doc, String version) {
		jdbc().update("update ld_document set ld_version = ? where ld_id = ?", version, doc.getId());
		Context.get(CacheEvictor.class).evictDocuments(List.of(doc.getId()));
	}

	public static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}
}
