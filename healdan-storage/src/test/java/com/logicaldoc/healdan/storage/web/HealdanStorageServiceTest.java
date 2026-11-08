// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import static com.logicaldoc.healdan.storage.StorageFixtures.freshFolder;
import static com.logicaldoc.healdan.storage.StorageFixtures.jdbc;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageEvent;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.Backends;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageEvent;
import com.logicaldoc.healdan.storage.StorageEventDAO;
import com.logicaldoc.healdan.storage.StorageFixtures;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.backend.FakeS3;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.crypto.RecoveryKey;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The Storage tab's servlet (spec section 11), called as the tab calls it, with a session: every call is an
 * administrator's, no answer holds an S3 secret, and an encrypted backend's recovery key is shown, confirmed and
 * entered as section 9 says. S3 backends are added against {@link FakeS3}.
 */
public class HealdanStorageServiceTest extends AbstractStorageTestCase {

	/** A secret no answer or log line may hold */
	private static final String SECRET = "s3cr3t";

	private static final String BUCKET = "healdan-tests";

	private final StorageFixtures sf = new StorageFixtures();

	private HealdanFixtures fx;

	private User bossUser;

	private Session boss;

	private Session author;

	private FakeS3 fake;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		fx = sf.fx;
		try {
			bossUser = fx.user("boss", "admin");
			boss = open(bossUser);
			author = open(fx.user("author", "author"));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		fake = FakeS3.start().createBucket(BUCKET);
	}

	@Override
	public void tearDown() throws IOException {
		SessionManager.get().kill(boss.getSid());
		SessionManager.get().kill(author.getSid());
		fake.close();
		super.tearDown();
	}

	private static Session open(User user) {
		return SessionManager.get().newSession(user.getUsername(), HealdanFixtures.PASSWORD,
				(com.logicaldoc.core.security.Client) null);
	}

	/** The servlet as the session's user */
	static HealdanStorageServiceImpl as(Session session) {
		return new HealdanStorageServiceImpl() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Session session() {
				return session;
			}
		};
	}

	private HealdanStorageServiceImpl service() {
		return as(boss);
	}

	/** An S3 backend on the fake, as the tab's form fills it in; encrypted unless the admin turns it off */
	private GUIStorageBackend s3(String name, String prefix) {
		GUIStorageBackend backend = new GUIStorageBackend();
		backend.setKind(GUIStorageBackend.S3);
		backend.setName(name);
		backend.setEndpoint(fake.endpoint().toString());
		backend.setRegion(FakeS3.REGION);
		backend.setBucket(BUCKET);
		backend.setPrefix(prefix);
		backend.setPathStyle(true);
		backend.setAccessKey(FakeS3.ACCESS_KEY);
		return backend;
	}

	/** A local folder, as the tab's form fills it in */
	static GUIStorageBackend local(String name, Path path, Boolean encrypted) {
		GUIStorageBackend backend = new GUIStorageBackend();
		backend.setKind(GUIStorageBackend.LOCAL);
		backend.setName(name);
		backend.setPath(path.toString());
		backend.setEncrypted(encrypted);
		return backend;
	}

	static GUIStorageBackend find(GUIStorageOverview overview, long id) {
		return Arrays.stream(overview.getBackends()).filter(b -> b.getId() == id).findFirst()
				.orElseThrow(() -> new AssertionError("No backend " + id));
	}

	private static String lastGroup(String recoveryKey) {
		return recoveryKey.substring(recoveryKey.length() - 4);
	}

	private static long backends() {
		return jdbc().queryForObject("select count(*) from hd_storage_backend", Long.class);
	}

	private static void assertHolds(String message, String text, ThrowingRunnable run) {
		ServerException e = assertThrows(ServerException.class, run);
		assertTrue(message + ": " + e.getMessage(), e.getMessage().contains(text));
	}

	private static String serialised(Serializable answer) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(answer);
		}
		return bytes.toString(StandardCharsets.ISO_8859_1);
	}

	@Test
	public void testAdminsOnly() throws Exception {
		GUIStorageBackend added = service().addBackend(local("Archive", freshFolder("archive"), null), null);
		Folder store = fx.store("Archive Store");
		long backends = backends();
		int holdDays = StorageSettings.get().holdDays();

		HealdanStorageServiceImpl service = as(author);
		long id = added.getId();
		List<ThrowingRunnable> calls = List.of(service::load, () -> service.testNew(added, null),
				() -> service.addBackend(local("Other", freshFolder("other"), null), null),
				() -> service.confirmRecoveryKey(id, "0000"), () -> service.saveBackend(added, null),
				() -> service.testBackend(id), () -> service.showRecoveryKey(id),
				() -> service.enterRecoveryKey(id, "0000"), () -> service.keyEvents(id),
				() -> service.removeBackend(id), () -> service.previewStoreSwitch(store.getId(), id),
				() -> service.setStoreBackend(store.getId(), id), () -> service.stuckFiles(store.getId()),
				() -> service.retry(store.getId()), () -> service.saveSettings(5, 5, 5));
		for (ThrowingRunnable call : calls)
			assertThrows(AccessDeniedException.class, call);

		// Nothing changed
		assertEquals(backends, backends());
		assertEquals(StorageBackend.ORIGINAL, StoredFiles.get().storeBackend(store.getId()));
		assertEquals(holdDays, StorageSettings.get().holdDays());
		assertNotNull(StorageBackendDAO.get().findById(id));
	}

	@Test
	public void testSecretNeverComesBack() throws Exception {
		List<Serializable> answers = new ArrayList<>();
		try (LogCapture admin = LogCapture.of(StorageAdmin.class);
				LogCapture s3 = LogCapture.of(S3Backend.class);
				LogCapture built = LogCapture.of(Backends.class);
				LogCapture health = LogCapture.of(BackendHealth.class)) {
			GUIStorageBackend fresh = s3("Cloud", "healdan/");
			answers.add(service().testNew(fresh, SECRET));
			GUIStorageBackend added = service().addBackend(fresh, SECRET);
			answers.add(added);
			assertTrue(added.isHasSecret());
			long id = added.getId();

			answers.add(service().load());
			assertTrue(find(service().load(), id).isHasSecret());
			answers.add(service().testBackend(id));
			added.setName("Cloud Two");
			answers.add(service().saveBackend(added, null));
			// Test before saving an edit uses the stored secret
			answers.add(service().testNew(added, null));
			answers.add(service().showRecoveryKey(id));
			answers.add(service().keyEvents(id));

			String sealed = jdbc().queryForObject("select hd_secret from hd_storage_backend where ld_id = ?",
					String.class, id);
			assertTrue(sealed, sealed.startsWith("v1:"));
			for (Serializable answer : answers) {
				String bytes = serialised(answer);
				assertFalse("an answer holds the secret: " + answer, bytes.contains(SECRET));
				assertFalse("an answer holds the sealed secret: " + answer, bytes.contains(sealed.substring(3)));
			}
			for (LogCapture log : List.of(admin, s3, built, health))
				for (String line : log.lines())
					assertFalse(line, line.contains(SECRET));
			// It is still there, for the backend
			assertEquals(SECRET, SecretBox.get().open(sealed));
			assertEquals(0, service().testBackend(id).length);
		}
	}

	@Test
	public void testRecoveryKeyFlow() throws Exception {
		Folder store = fx.store("Vault Store");
		GUIStorageBackend added = service().addBackend(s3("Vault", "vault/"), SECRET);
		long id = added.getId();

		// Encrypted, as S3 is unless turned off, with its key shown once
		assertEquals(Boolean.TRUE, added.getEncrypted());
		String key = added.getRecoveryKey();
		assertNotNull(key);
		assertEquals(52, key.replace(" ", "").length());
		assertArrayEquals(Context.get(BackendKeys.class).read(id).orElseThrow(), RecoveryKey.parse(key));
		assertTrue(fake.object(BUCKET, "vault/" + LocalBackend.MARKER).isPresent());
		assertTrue(fake.object(BUCKET, "vault/" + LocalBackend.CHECK).isPresent());
		assertNull(find(service().load(), id).getRecoveryKey());

		// Not offered to Stores until its last group is typed back
		assertFalse(added.isOffered());
		assertFalse(find(service().load(), id).isKeyConfirmed());
		assertHolds("unconfirmed", "recovery key", () -> service().setStoreBackend(store.getId(), id));
		assertEquals(StorageBackend.ORIGINAL, StoredFiles.get().storeBackend(store.getId()));

		String last = lastGroup(key);
		String wrong = last.equals("ZZZZ") ? "YYYY" : "ZZZZ";
		assertHolds("a wrong group", "last group", () -> service().confirmRecoveryKey(id, wrong));
		assertFalse(find(service().load(), id).isOffered());

		// As typed: any case, spaces around
		service().confirmRecoveryKey(id, " " + last.toLowerCase() + " ");
		GUIStorageBackend confirmed = find(service().load(), id);
		assertTrue(confirmed.isKeyConfirmed());
		assertTrue(confirmed.isOffered());
		service().setStoreBackend(store.getId(), id);
		assertEquals(id, StoredFiles.get().storeBackend(store.getId()));
	}

	@Test
	public void testShowRecoveryKeyRecorded() throws Exception {
		GUIStorageBackend added = service().addBackend(local("Vault", freshFolder("vault"), true), null);
		long id = added.getId();
		String key = added.getRecoveryKey();
		assertNotNull(key);

		try (LogCapture log = LogCapture.of(StorageAdmin.class)) {
			assertEquals(key, service().showRecoveryKey(id));

			List<StorageEvent> events = StorageEventDAO.get().findByBackend(id);
			assertEquals(1, events.size());
			assertEquals(StorageEvent.KEY_SHOWN, events.get(0).getEvent());
			assertEquals(id, events.get(0).getBackendId());
			assertEquals(bossUser.getId(), events.get(0).getUserId());
			assertEquals("boss", events.get(0).getUserName());
			GUIStorageEvent[] listed = service().keyEvents(id);
			assertEquals(1, listed.length);
			assertEquals(GUIStorageEvent.KEY_SHOWN, listed[0].getEvent());
			assertEquals("boss", listed[0].getUserName());
			GUIStorageBackend shown = find(service().load(), id);
			assertNotNull(shown.getKeyShown());
			assertEquals("boss", shown.getKeyShownBy());

			// Each showing is recorded, newest first
			assertEquals(key, service().showRecoveryKey(id));
			listed = service().keyEvents(id);
			assertEquals(2, listed.length);
			assertTrue(listed[0].getDate().compareTo(listed[1].getDate()) >= 0);

			// Logged with who and which backend, never with the key
			List<String> lines = log.lines().stream().filter(l -> l.contains("recovery key")).toList();
			assertEquals(log.lines().toString(), 2, lines.size());
			for (String line : lines) {
				assertTrue(line, line.contains("boss"));
				assertTrue(line, line.contains("storage backend " + id));
			}
			for (String line : log.lines()) {
				assertFalse(line, line.contains(key));
				assertFalse(line, line.contains(key.replace(" ", "")));
			}
		}

		// The record outlives the backend
		service().removeBackend(id);
		assertNull(StorageBackendDAO.get().findById(id));
		assertEquals(2, service().keyEvents(id).length);
	}

	@Test
	public void testEnterRecoveryKey() throws Exception {
		Path folder = freshFolder("vault");
		GUIStorageBackend added = service().addBackend(local("Vault", folder, true), null);
		long id = added.getId();
		String key = added.getRecoveryKey();
		service().confirmRecoveryKey(id, lastGroup(key));
		Side side = sf.side("Vault Store", id, folder, null);
		Document before = fx.upload(side.folder(), "before.txt", "written before the loss", fx.admin());

		// The key file goes. The key kept in memory works until Test, which reads the file as it now is
		Path keyFile = Context.get(BackendKeys.class).file(id);
		Files.delete(keyFile);
		String[] problems = service().testBackend(id);
		assertEquals(1, problems.length);
		assertTrue(problems[0], problems[0].contains("key file"));
		assertEquals("failing", find(service().load(), id).getStatus());

		// Checked against the check object before anything is written
		byte[] another = new byte[32];
		new SecureRandom().nextBytes(another);
		assertHolds("another key", "isn't this backend's",
				() -> service().enterRecoveryKey(id, RecoveryKey.format(another)));
		assertFalse(Files.exists(keyFile));
		assertHolds("not a key", "recovery key", () -> service().enterRecoveryKey(id, "not a key"));
		assertFalse(Files.exists(keyFile));
		assertEquals(0, service().keyEvents(id).length);

		// The right one, in any case
		service().enterRecoveryKey(id, key.toLowerCase());
		assertArrayEquals(RecoveryKey.parse(key), Files.readAllBytes(keyFile));
		assertEquals("ok", find(service().load(), id).getStatus());
		GUIStorageEvent[] events = service().keyEvents(id);
		assertEquals(1, events.length);
		assertEquals(GUIStorageEvent.KEY_ENTERED, events[0].getEvent());
		assertEquals("boss", events[0].getUserName());

		// The next write is encrypted under it, and what was there reads
		Document after = fx.upload(side.folder(), "after.txt", "written after the key came back", fx.admin());
		Path stored = StorageFixtures.local(folder, after.getId(), "1.0");
		assertFalse(Files.readString(stored, StandardCharsets.ISO_8859_1).contains("after the key"));
		try (InputStream in = new FileCipher(RecoveryKey.parse(key)).decrypting(Files.newInputStream(stored),
				FileCipher.associated(after.getId(), "1.0"))) {
			assertEquals("written after the key came back", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertEquals("written before the loss", text(resource(before, "1.0")));
	}

	@Test
	public void testAddLocalFolder() throws Exception {
		Path folder = freshFolder("archive");
		GUIStorageBackend fresh = local("Archive", folder, null);

		// Test before saving probes the folder, and writes no marker
		assertEquals(0, service().testNew(fresh, null).length);
		assertFalse(Files.exists(folder.resolve(LocalBackend.MARKER)));

		long count = backends();
		GUIStorageBackend added = service().addBackend(fresh, null);
		assertEquals(count + 1, backends());
		// Its marker names this install and the backend
		assertEquals(StorageSettings.get().installId() + " " + added.getId() + "\n",
				Files.readString(folder.resolve(LocalBackend.MARKER)));
		// Plain, as a local folder is unless turned on, so there is no recovery key, and Stores may use it at once
		assertEquals(Boolean.FALSE, added.getEncrypted());
		assertNull(added.getRecoveryKey());
		assertTrue(added.isOffered());
		assertEquals(folder.toString(), added.getPath());
		assertEquals(0, service().testBackend(added.getId()).length);
		assertEquals("ok", find(service().load(), added.getId()).getStatus());

		// A folder that isn't there fails Test, and isn't added
		GUIStorageBackend missing = local("Missing", folder.resolveSibling("missing"), null);
		String[] problems = service().testNew(missing, null);
		assertEquals(1, problems.length);
		assertTrue(problems[0], problems[0].contains("doesn't exist"));
		assertHolds("a missing folder", "doesn't exist", () -> service().addBackend(missing, null));
		assertEquals(count + 1, backends());
	}
}
