package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.logicaldoc.healdan.support.LogCapture;

public class SecretBoxTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Path keyFile() throws IOException {
		return folder.getRoot().toPath().resolve("conf").resolve("healdan-secret.key");
	}

	/** A box for a database that holds nothing sealed, as on a fresh install */
	private static SecretBox box(Path keyFile) {
		return new SecretBox(keyFile, () -> 0L);
	}

	/** What a start that died after writing the key, and before putting it in place, leaves */
	private Path stray() throws IOException {
		return keyFile().resolveSibling("healdan-secret.key.new");
	}

	@Test
	public void testSealOpens() throws IOException {
		SecretBox box = box(keyFile());

		String sealed = box.seal("s3cret");

		assertTrue(sealed, sealed.startsWith("v1:"));
		assertFalse(sealed, sealed.contains("s3cret"));
		// The IV (12 bytes), then the text and the 16-byte tag
		assertEquals(12 + "s3cret".length() + 16, Base64.getDecoder().decode(sealed.substring(3)).length);
		assertEquals("s3cret", box.open(sealed));
	}

	@Test
	public void testSealsAnyText() throws IOException {
		SecretBox box = box(keyFile());
		for (String text : new String[] { "", "pass word", "ünï-cødé ☃ 🔑", "x".repeat(3000) })
			assertEquals(text, box.open(box.seal(text)));
	}

	@Test
	public void testNullInNullOut() throws IOException {
		SecretBox box = box(keyFile());

		assertNull(box.seal(null));
		assertNull(box.open(null));
		// Sealing nothing makes no key
		assertFalse(Files.exists(keyFile()));
		assertFalse(box.hasKey());
	}

	@Test
	public void testSealTwiceDiffers() throws IOException {
		SecretBox box = box(keyFile());

		assertNotEquals(box.seal("s3cret"), box.seal("s3cret"));
	}

	@Test
	public void testKeyFileMadeOnceWithMode600() throws IOException {
		Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		Path keyFile = keyFile();
		SecretBox first = box(keyFile);
		assertFalse(first.hasKey());
		assertFalse(Files.exists(keyFile));

		String sealed = first.seal("s3cret");

		assertTrue(first.hasKey());
		assertEquals(32, Files.size(keyFile));
		assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyFile));
		byte[] key = Files.readAllBytes(keyFile);

		// A second box on the same path uses the key that's there, and makes no other
		SecretBox second = box(keyFile);
		assertTrue(second.hasKey());
		assertEquals("s3cret", second.open(sealed));
		String sealedBySecond = second.seal("other");
		assertEquals("other", first.open(sealedBySecond));
		assertTrue(Arrays.equals(key, Files.readAllBytes(keyFile)));
	}

	@Test
	public void testAnEmptyKeyFileIsReplaced() throws IOException {
		// What a start that died between making the file and writing it left, before keys were put in place whole
		Files.createDirectories(keyFile().getParent());
		Files.createFile(keyFile());
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
			Files.setPosixFilePermissions(keyFile(), PosixFilePermissions.fromString("rw-r--r--"));
		SecretBox box = box(keyFile());
		assertFalse(box.hasKey());

		String sealed = box.seal("s3cret");

		assertEquals(32, Files.size(keyFile()));
		assertEquals("s3cret", box(keyFile()).open(sealed));
		// The key is never written into a file others can read
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
			assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyFile()));
		assertFalse(Files.exists(stray()));
	}

	@Test
	public void testAStrayFromADeadStartIsCleared() throws IOException {
		Files.createDirectories(keyFile().getParent());
		Files.write(stray(), new byte[32]);
		SecretBox box = box(keyFile());
		assertFalse(box.hasKey());

		String sealed = box.seal("s3cret");

		assertEquals("s3cret", box(keyFile()).open(sealed));
		assertFalse(Arrays.equals(new byte[32], Files.readAllBytes(keyFile())));
		assertFalse(Files.exists(stray()));
	}

	@Test
	public void testNoKeyIsMadeOnceValuesAreSealed() throws IOException {
		// The key file went, as in a re-check, while the database holds a client secret and two refresh tokens
		SecretBox box = new SecretBox(keyFile(), () -> 3L);

		try (LogCapture log = LogCapture.of(SecretBox.class)) {
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> box.seal("s3cret-value"));

			assertFalse(e.toString() + e.getCause(), (e.toString() + e.getCause()).contains("s3cret-value"));
			assertFalse(Files.exists(keyFile()));
			assertFalse(Files.exists(keyFile().getParent()));
			assertFalse(box.hasKey());

			// Loudly, saying what to do, and never the value
			assertEquals(log.lines().toString(), 1, log.lines().size());
			String line = log.lines().get(0);
			assertTrue(line, line.startsWith("ERROR "));
			assertTrue(line, line.contains(keyFile().toString()));
			assertTrue(line, line.contains("3 stored values are sealed"));
			assertTrue(line, line.contains("Restore the key file from a backup"));
			assertTrue(line, line.contains("enter each sign-in provider's client secret again"));
			assertFalse(line, line.contains("s3cret-value"));
		}

		// Each time
		assertThrows(IllegalStateException.class, () -> box.seal("s3cret-value"));
		assertFalse(Files.exists(keyFile()));
		// Nothing to seal needs no key
		assertNull(box.seal(null));
	}

	@Test
	public void testAnEmptyKeyFileIsNotFilledOnceValuesAreSealed() throws IOException {
		Files.createDirectories(keyFile().getParent());
		Files.createFile(keyFile());
		SecretBox box = new SecretBox(keyFile(), () -> 1L);

		assertThrows(IllegalStateException.class, () -> box.seal("s3cret"));

		assertEquals(0, Files.size(keyFile()));
		assertFalse(Files.exists(stray()));
	}

	@Test
	public void testTheKeyBackFromABackupIsUsed() throws IOException {
		String sealed = box(keyFile()).seal("s3cret");
		byte[] key = Files.readAllBytes(keyFile());
		Files.delete(keyFile());
		SecretBox box = new SecretBox(keyFile(), () -> 1L);
		assertThrows(IllegalStateException.class, () -> box.seal("other"));

		// Restored, with nothing restarted
		Files.write(keyFile(), key);

		assertEquals("s3cret", box.open(sealed));
		assertEquals("other", box.open(box.seal("other")));
		assertTrue(Arrays.equals(key, Files.readAllBytes(keyFile())));
	}

	@Test
	public void testAnEnteredSecretMakesANewKeyAfterALoss() throws IOException {
		String sealed = box(keyFile()).seal("s3cret");
		Files.delete(keyFile());
		SecretBox box = new SecretBox(keyFile(), () -> 3L);

		try (LogCapture log = LogCapture.of(SecretBox.class)) {
			// An administrator entering a client secret again is how a lost key is put right
			String entered = box.sealEntered("new-s3cret-value");

			assertEquals("new-s3cret-value", box.open(entered));
			assertEquals(32, Files.size(keyFile()));
			assertNull(box.open(sealed));
			String said = log.lines().toString();
			assertTrue(said, said.contains("WARN Made a new secret key file " + keyFile()));
			assertTrue(said, said.contains("3 stored values were sealed with a key that's missing"));
			assertFalse(said, said.contains("new-s3cret-value"));
		}

		// And then everything seals with it
		assertEquals("other", box.open(box.seal("other")));
	}

	@Test
	public void testAnEnteredSecretUsesTheKeyThatsThere() throws IOException {
		SecretBox box = new SecretBox(keyFile(), () -> 3L);
		String sealed = box(keyFile()).seal("s3cret");
		byte[] key = Files.readAllBytes(keyFile());

		assertEquals("entered", box.open(box.sealEntered("entered")));

		assertTrue(Arrays.equals(key, Files.readAllBytes(keyFile())));
		assertEquals("s3cret", box.open(sealed));
		assertNull(box.sealEntered(null));
	}

	@Test
	public void testNoKeyIsMadeWhenTheDatabaseCantBeAsked() throws IOException {
		SecretBox box = new SecretBox(keyFile(), () -> {
			throw new IllegalStateException("The database is down");
		});

		assertThrows(IllegalStateException.class, () -> box.seal("s3cret"));

		assertFalse(Files.exists(keyFile()));
	}

	@Test
	public void testTheDatabaseIsAskedOnlyWithoutAKey() throws IOException {
		AtomicInteger asked = new AtomicInteger();
		SecretBox box = new SecretBox(keyFile(), () -> {
			asked.incrementAndGet();
			return 0L;
		});

		box.seal("s3cret");
		box.seal("s3cret");
		box.sealEntered("s3cret");

		assertEquals(1, asked.get());
	}

	@Test
	public void testAFileOfAnotherSizeIsNotOverwritten() throws IOException {
		Files.createDirectories(keyFile().getParent());
		Files.write(keyFile(), "someone's file".getBytes(StandardCharsets.UTF_8));
		SecretBox box = box(keyFile());

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> box.seal("s3cret-value"));

		assertFalse(e.toString() + e.getCause(), (e.toString() + e.getCause()).contains("s3cret-value"));
		assertEquals("someone's file", new String(Files.readAllBytes(keyFile()), StandardCharsets.UTF_8));
		assertFalse(box.hasKey());
	}

	@Test
	public void testNoKeyOpensNothing() throws IOException {
		SecretBox maker = box(keyFile());
		String sealed = maker.seal("s3cret");

		SecretBox other = box(folder.getRoot().toPath().resolve("elsewhere").resolve("none.key"));

		assertNull(other.open(sealed));
		assertFalse(other.hasKey());
		// Opening never makes a key
		assertFalse(Files.exists(folder.getRoot().toPath().resolve("elsewhere")));
	}

	@Test
	public void testAnotherKeyOpensNothing() throws IOException {
		String sealed = box(keyFile()).seal("s3cret");

		SecretBox other = box(folder.getRoot().toPath().resolve("other.key"));
		other.seal("make the key");

		assertNull(other.open(sealed));
	}

	@Test
	public void testAKeyOfTheWrongSizeOpensNothing() throws IOException {
		SecretBox maker = box(keyFile());
		String sealed = maker.seal("s3cret");
		Files.write(keyFile(), new byte[16]);

		SecretBox box = box(keyFile());

		assertNull(box.open(sealed));
		assertFalse(box.hasKey());
	}

	@Test
	public void testDamagedValueOpensNothing() throws IOException {
		SecretBox box = box(keyFile());
		String sealed = box.seal("s3cret");

		// A flipped byte, wherever it falls
		byte[] raw = Base64.getDecoder().decode(sealed.substring(3));
		for (int i : new int[] { 0, 11, 12, raw.length - 17, raw.length - 1 }) {
			byte[] damaged = raw.clone();
			damaged[i] ^= 1;
			assertNull("byte " + i, box.open("v1:" + Base64.getEncoder().encodeToString(damaged)));
		}

		assertNull(box.open(sealed.substring(3)));
		assertNull(box.open("v2:" + sealed.substring(3)));
		assertNull(box.open("v1:"));
		assertNull(box.open("v1:not base64!"));
		assertNull(box.open("v1:" + Base64.getEncoder().encodeToString(new byte[8])));
		assertNull(box.open(""));
		assertNull(box.open(null));
		// and the box still works
		assertEquals("s3cret", box.open(sealed));
	}

	@Test
	public void testWarnsOnceAndNeverSaysTheValue() throws IOException {
		String sealed = box(keyFile()).seal("s3cret-value");
		SecretBox other = box(folder.getRoot().toPath().resolve("none.key"));

		try (LogCapture log = LogCapture.of(SecretBox.class)) {
			assertNull(other.open(sealed));
			assertNull(other.open(sealed));
			assertNull(other.open("v1:" + sealed.substring(3, 20)));

			assertEquals(log.lines().toString(), 1, log.lines().size());
			assertTrue(log.lines().toString(), log.lines().get(0).startsWith("WARN "));
			for (String line : log.lines()) {
				assertFalse(line, line.contains("s3cret-value"));
				assertFalse(line, line.contains(sealed));
			}
		}
	}

	@Test
	public void testTwoFirstUsesMakeOneKey() throws Exception {
		Path keyFile = keyFile();
		SecretBox[] boxes = { box(keyFile), box(keyFile), box(keyFile),
				box(keyFile) };
		String[] sealed = new String[boxes.length];
		Thread[] threads = new Thread[boxes.length];
		for (int i = 0; i < boxes.length; i++) {
			int n = i;
			threads[i] = new Thread(() -> sealed[n] = boxes[n].seal("s3cret"));
		}
		for (Thread thread : threads)
			thread.start();
		for (Thread thread : threads)
			thread.join();

		// Every value opens with the one key that's left
		SecretBox reader = box(keyFile);
		for (String value : sealed)
			assertEquals("s3cret", reader.open(value));
	}
}
