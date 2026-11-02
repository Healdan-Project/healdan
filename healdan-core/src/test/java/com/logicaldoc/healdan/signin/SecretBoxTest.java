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

	@Test
	public void testSealOpens() throws IOException {
		SecretBox box = new SecretBox(keyFile());

		String sealed = box.seal("s3cret");

		assertTrue(sealed, sealed.startsWith("v1:"));
		assertFalse(sealed, sealed.contains("s3cret"));
		// The IV (12 bytes), then the text and the 16-byte tag
		assertEquals(12 + "s3cret".length() + 16, Base64.getDecoder().decode(sealed.substring(3)).length);
		assertEquals("s3cret", box.open(sealed));
	}

	@Test
	public void testSealsAnyText() throws IOException {
		SecretBox box = new SecretBox(keyFile());
		for (String text : new String[] { "", "pass word", "ünï-cødé ☃ 🔑", "x".repeat(3000) })
			assertEquals(text, box.open(box.seal(text)));
	}

	@Test
	public void testNullInNullOut() throws IOException {
		SecretBox box = new SecretBox(keyFile());

		assertNull(box.seal(null));
		assertNull(box.open(null));
		// Sealing nothing makes no key
		assertFalse(Files.exists(keyFile()));
		assertFalse(box.hasKey());
	}

	@Test
	public void testSealTwiceDiffers() throws IOException {
		SecretBox box = new SecretBox(keyFile());

		assertNotEquals(box.seal("s3cret"), box.seal("s3cret"));
	}

	@Test
	public void testKeyFileMadeOnceWithMode600() throws IOException {
		Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		Path keyFile = keyFile();
		SecretBox first = new SecretBox(keyFile);
		assertFalse(first.hasKey());
		assertFalse(Files.exists(keyFile));

		String sealed = first.seal("s3cret");

		assertTrue(first.hasKey());
		assertEquals(32, Files.size(keyFile));
		assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyFile));
		byte[] key = Files.readAllBytes(keyFile);

		// A second box on the same path uses the key that's there, and makes no other
		SecretBox second = new SecretBox(keyFile);
		assertTrue(second.hasKey());
		assertEquals("s3cret", second.open(sealed));
		String sealedBySecond = second.seal("other");
		assertEquals("other", first.open(sealedBySecond));
		assertTrue(Arrays.equals(key, Files.readAllBytes(keyFile)));
	}

	@Test
	public void testAnEmptyKeyFileIsFilled() throws IOException {
		// What a start that died between making the file and writing it leaves
		Files.createDirectories(keyFile().getParent());
		Files.createFile(keyFile());
		SecretBox box = new SecretBox(keyFile());
		assertFalse(box.hasKey());

		String sealed = box.seal("s3cret");

		assertEquals(32, Files.size(keyFile()));
		assertEquals("s3cret", new SecretBox(keyFile()).open(sealed));
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
			assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyFile()));
	}

	@Test
	public void testAFileOfAnotherSizeIsNotOverwritten() throws IOException {
		Files.createDirectories(keyFile().getParent());
		Files.write(keyFile(), "someone's file".getBytes(StandardCharsets.UTF_8));
		SecretBox box = new SecretBox(keyFile());

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> box.seal("s3cret-value"));

		assertFalse(e.toString() + e.getCause(), (e.toString() + e.getCause()).contains("s3cret-value"));
		assertEquals("someone's file", new String(Files.readAllBytes(keyFile()), StandardCharsets.UTF_8));
		assertFalse(box.hasKey());
	}

	@Test
	public void testNoKeyOpensNothing() throws IOException {
		SecretBox maker = new SecretBox(keyFile());
		String sealed = maker.seal("s3cret");

		SecretBox other = new SecretBox(folder.getRoot().toPath().resolve("elsewhere").resolve("none.key"));

		assertNull(other.open(sealed));
		assertFalse(other.hasKey());
		// Opening never makes a key
		assertFalse(Files.exists(folder.getRoot().toPath().resolve("elsewhere")));
	}

	@Test
	public void testAnotherKeyOpensNothing() throws IOException {
		String sealed = new SecretBox(keyFile()).seal("s3cret");

		SecretBox other = new SecretBox(folder.getRoot().toPath().resolve("other.key"));
		other.seal("make the key");

		assertNull(other.open(sealed));
	}

	@Test
	public void testAKeyOfTheWrongSizeOpensNothing() throws IOException {
		SecretBox maker = new SecretBox(keyFile());
		String sealed = maker.seal("s3cret");
		Files.write(keyFile(), new byte[16]);

		SecretBox box = new SecretBox(keyFile());

		assertNull(box.open(sealed));
		assertFalse(box.hasKey());
	}

	@Test
	public void testDamagedValueOpensNothing() throws IOException {
		SecretBox box = new SecretBox(keyFile());
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
		String sealed = new SecretBox(keyFile()).seal("s3cret-value");
		SecretBox other = new SecretBox(folder.getRoot().toPath().resolve("none.key"));

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
		SecretBox[] boxes = { new SecretBox(keyFile), new SecretBox(keyFile), new SecretBox(keyFile),
				new SecretBox(keyFile) };
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
		SecretBox reader = new SecretBox(keyFile);
		for (String value : sealed)
			assertEquals("s3cret", reader.open(value));
	}
}
