// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.After;
import org.junit.Test;

import com.logicaldoc.healdan.storage.backend.FakeS3.Op;
import com.logicaldoc.healdan.storage.backend.FakeS3.Request;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.crypto.KeyCheck;

/** The S3 backend against {@link FakeS3}: what every backend does, then what leaves the client and how it fails */
public class S3BackendTest extends BackendContract {

	private static final String BUCKET = "healdan";

	private static final String INSTALL = "5f0c2a1e-install";

	/** What the backends sign with: nothing may ever show it */
	private static final String SECRET = "s3cr3t-" + UUID.randomUUID();

	private static final long MIB = 1L << 20;

	private FakeS3 fake;

	private final List<AutoCloseable> closing = new ArrayList<>();

	@Override
	protected Backend createBackend() throws IOException {
		fake = FakeS3.start().createBucket(BUCKET);
		return backend(fake, 7, "", true);
	}

	/** S3's hold leaves the object where it is, and sends nothing */
	@Override
	protected boolean holdMovesTheFile() {
		return false;
	}

	@Override
	protected long sent() {
		return fake.requests().size();
	}

	@After
	public void closeFakes() throws Exception {
		for (AutoCloseable closeable : closing)
			closeable.close();
		closing.clear();
		if (fake != null)
			fake.close();
	}

	private S3Backend backend(FakeS3 s3, long id, String prefix, boolean create) throws BackendFailingException {
		return backend(s3.settings(BUCKET, prefix), id, create);
	}

	private S3Backend backend(S3Settings settings, long id, boolean create) throws BackendFailingException {
		S3Backend built = new S3Backend(id, INSTALL, settings, SECRET, create);
		closing.add(built);
		return built;
	}

	private record Big(Path path, byte[] md5) {
	}

	/** @return a file of random bytes, and its MD5 */
	private Big big(long size, long seed) throws IOException {
		Path file = Files.createTempFile(work, "big-", ".bin");
		MessageDigest digest = md5();
		Random random = new Random(seed);
		byte[] chunk = new byte[1 << 20];
		try (OutputStream out = Files.newOutputStream(file)) {
			for (long left = size; left > 0;) {
				int n = (int) Math.min(chunk.length, left);
				random.nextBytes(chunk);
				out.write(chunk, 0, n);
				digest.update(chunk, 0, n);
				left -= n;
			}
		}
		return new Big(file, digest.digest());
	}

	private static MessageDigest md5() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] readAll(Backend from, String key) throws IOException {
		try (InputStream in = from.get(key)) {
			return in.readAllBytes();
		}
	}

	private static byte[] md5Of(Backend from, String key) throws IOException {
		MessageDigest digest = md5();
		try (InputStream in = from.get(key)) {
			byte[] buffer = new byte[1 << 16];
			int n;
			while ((n = in.read(buffer)) >= 0)
				digest.update(buffer, 0, n);
		}
		return digest.digest();
	}

	private static String text(Optional<byte[]> object) {
		return new String(object.orElseThrow(), UTF_8);
	}

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	private static BackendFailingException failing(Call call, String says) {
		try {
			call.run();
		} catch (BackendUnreachableException e) {
			throw new AssertionError("Refused as unreachable: " + e, e);
		} catch (BackendFailingException e) {
			assertTrue(e.getMessage(), e.getMessage().toLowerCase(Locale.ROOT).contains(says));
			return e;
		} catch (Exception e) {
			throw new AssertionError("Not a BackendFailingException: " + e, e);
		}
		throw new AssertionError("It worked");
	}

	private static BackendUnreachableException unreachable(Call call) {
		try {
			call.run();
		} catch (BackendUnreachableException e) {
			return e;
		} catch (Exception e) {
			throw new AssertionError("Not a BackendUnreachableException: " + e, e);
		}
		throw new AssertionError("It worked");
	}

	/** A refusal of one file, not of the backend: a plain IOException naming the error code */
	private static IOException refusedFile(Call call, String code) {
		try {
			call.run();
		} catch (BackendFailingException e) {
			throw new AssertionError("A file's refusal reported as the backend failing: " + e, e);
		} catch (FileNotFoundException e) {
			throw new AssertionError("A refusal reported as a missing file: " + e, e);
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(code));
			return e;
		} catch (Exception e) {
			throw new AssertionError("Not an IOException: " + e, e);
		}
		throw new AssertionError("It worked");
	}

	private static boolean causedBy(Throwable thrown, Class<? extends Throwable> type) {
		for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause())
			if (type.isInstance(t))
				return true;
		return false;
	}

	@Test
	public void testContentMd5OnEveryUpload() throws Exception {
		Big big = big(70 * MIB, 70);
		for (boolean https : new boolean[] { false, true }) {
			String scheme = https ? "https: " : "http: ";
			try (FakeS3 s3 = https ? FakeS3.startTls() : FakeS3.start()) {
				s3.createBucket(BUCKET);
				assertEquals(https ? "https" : "http", s3.endpoint().getScheme());
				if (https) {
					// Its certificate isn't trusted until trust(): the TLS is real
					S3Backend untrusted = backend(s3, 9, "p", false);
					assertTrue(causedBy(unreachable(untrusted::check), javax.net.ssl.SSLException.class));
					s3.trust();
				}
				S3Backend b = backend(s3, 9, "p", true);
				byte[] small = bytes(3000);
				b.put("1/2/doc/1.0", source(small), md5(small));
				b.put("1/2/doc/1.1", source(small), null);
				b.put("1/2/doc/empty", source(new byte[0]), null);
				b.stage("1/2/doc/1.2", source(small), md5(small)).commit();
				// Parts with the file's MD5 given over one scheme, and without over the other
				b.put("1/2/doc/big", big.path(), https ? null : big.md5());

				for (String key : List.of("1/2/doc/1.0", "1/2/doc/1.1", "1/2/doc/1.2"))
					assertArrayEquals(scheme + key, small, readAll(b, key));
				assertEquals(0, readAll(b, "1/2/doc/empty").length);
				assertArrayEquals(scheme, big.md5(), md5Of(b, "1/2/doc/big"));

				List<Request> uploads = s3.requests().stream()
						.filter(r -> r.op() == Op.PUT_OBJECT || r.op() == Op.UPLOAD_PART).toList();
				// The marker, four puts, and five parts
				assertEquals(scheme + uploads, 1 + 4 + 5, uploads.size());
				for (Request upload : uploads) {
					String what = scheme + upload.op() + " " + upload.key() + " " + upload.partNumber();
					assertNotNull(what + " had no Content-MD5", upload.header("content-md5"));
					String encoding = String.valueOf(upload.header("content-encoding"));
					if (https) {
						assertEquals(what, "UNSIGNED-PAYLOAD", upload.header("x-amz-content-sha256"));
						assertFalse(what, encoding.contains("aws-chunked"));
						assertNull(what, upload.header("x-amz-decoded-content-length"));
					} else {
						assertEquals(what, "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", upload.header("x-amz-content-sha256"));
						assertTrue(what, encoding.contains("aws-chunked"));
						assertEquals(what, Integer.toString(upload.bytes()), upload.header("x-amz-decoded-content-length"));
					}
				}
				// The parts asked for 100-continue, which the JDK's server answers at once: with a 1-second read
				// timeout, a client left waiting for it would have failed
				assertTrue(scheme, uploads.stream().filter(r -> r.op() == Op.UPLOAD_PART)
						.allMatch(r -> "100-continue".equalsIgnoreCase(r.header("expect"))));
				for (Request request : s3.requests())
					for (String name : request.headers().keySet())
						assertFalse(scheme + request.op() + " sent " + name, name.startsWith("x-amz-checksum-")
								|| name.equals("x-amz-sdk-checksum-algorithm") || name.equals("x-amz-trailer"));
			}
		}
	}

	@Test
	public void testCorruptUploadRefused() throws IOException {
		byte[] data = bytes(5000);
		for (boolean given : new boolean[] { true, false }) {
			fake.corrupt();
			assertNotNull(refusedFile(() -> backend.put("1/2/doc/1.0", source(data), given ? md5(data) : null),
					"BadDigest"));
			assertEquals(-1, backend.size("1/2/doc/1.0"));
			assertTrue(fake.object(BUCKET, "1/2/doc/1.0").isEmpty());
		}
		fake.corrupt();
		refusedFile(() -> backend.stage("1/2/doc/1.1", source(data), md5(data)), "BadDigest");
		assertTrue(fake.object(BUCKET, "1/2/doc/1.1").isEmpty());

		// Used up: the next put goes through whole
		backend.put("1/2/doc/1.0", source(data), md5(data));
		assertArrayEquals(data, read("1/2/doc/1.0"));
	}

	@Test
	public void testMultipart() throws IOException {
		Big big = big(70 * MIB, 7);
		fake.clearRequests();
		backend.put("1/2/doc/big", big.path(), big.md5());
		assertEquals(1, fake.requests(Op.CREATE_UPLOAD).size());
		List<Request> parts = fake.requests(Op.UPLOAD_PART);
		assertEquals(List.of(1, 2, 3, 4, 5), parts.stream().map(Request::partNumber).toList());
		assertEquals(List.of(16 * MIB, 16 * MIB, 16 * MIB, 16 * MIB, 6 * MIB),
				parts.stream().map(r -> (long) r.bytes()).toList());
		assertEquals(1, fake.requests(Op.COMPLETE_UPLOAD).size());
		assertTrue(fake.requests(Op.PUT_OBJECT).isEmpty());
		assertEquals(0, fake.openUploads());
		assertEquals(70 * MIB, backend.size("1/2/doc/big"));
		assertArrayEquals(big.md5(), md5Of(backend, "1/2/doc/big"));

		// Up to 64 MiB goes up whole
		Big edge = big(64 * MIB, 64);
		fake.clearRequests();
		backend.put("1/2/doc/edge", edge.path(), null);
		assertEquals(1, fake.requests(Op.PUT_OBJECT).size());
		assertTrue(fake.requests(Op.CREATE_UPLOAD).isEmpty());
		assertArrayEquals(edge.md5(), md5Of(backend, "1/2/doc/edge"));
	}

	@Test
	public void testMultipartAbortedOnFailure() throws IOException {
		Big big = big(70 * MIB, 3);

		// The third part refused
		fake.failPart(3, 500, "InternalError");
		unreachable(() -> putBig(big));
		assertAborted(3);

		// The third part's bytes changed on the way
		fake.behave().corruptPart(3);
		refusedFile(() -> putBig(big), "BadDigest");
		assertAborted(3);

		// The connection dropped during the third part
		fake.behave().dropPart(3);
		unreachable(() -> putBig(big));
		assertAborted(3);

		// Completing refused
		fake.behave().fail(Op.COMPLETE_UPLOAD, 503, "ServiceUnavailable");
		unreachable(() -> putBig(big));
		assertAborted(5);

		// A file that doesn't match its checksum never starts an upload
		fake.behave().clearRequests();
		try {
			backend.put("1/2/doc/big", big.path(), md5(bytes(1)));
			fail("A file that doesn't match its checksum went up");
		} catch (BackendFailingException e) {
			fail("The file's own fault reported as the backend's: " + e);
		} catch (IOException expected) {
			// as it should
		}
		assertTrue(fake.requests(Op.CREATE_UPLOAD).isEmpty());
		assertTrue(fake.requests(Op.UPLOAD_PART).isEmpty());

		// An abort that fails too doesn't hide the part's failure
		fake.failPart(3, 500, "InternalError").fail(Op.ABORT_UPLOAD, 503, "SlowDown");
		BackendUnreachableException thrown = unreachable(() -> putBig(big));
		assertTrue(thrown.getMessage(), thrown.getMessage().contains("part 3"));
		assertEquals(1, thrown.getSuppressed().length);
		assertEquals("Left for the bucket's rule on incomplete uploads", 1, fake.openUploads());
		assertTrue(fake.object(BUCKET, "1/2/doc/big").isEmpty());
	}

	private void putBig(Big big) throws IOException {
		fake.clearRequests();
		backend.put("1/2/doc/big", big.path(), big.md5());
	}

	private void assertAborted(int lastPart) {
		assertEquals(0, fake.openUploads());
		assertEquals(1, fake.requests(Op.ABORT_UPLOAD).size());
		assertEquals(lastPart, fake.requests(Op.UPLOAD_PART).stream().mapToInt(Request::partNumber).max().orElse(0));
		assertTrue(fake.object(BUCKET, "1/2/doc/big").isEmpty());
	}

	@Test
	public void testMarker() throws IOException {
		// Building with create wrote it, at <prefix>.healdan-backend
		assertEquals(INSTALL + " 7\n", text(fake.object(BUCKET, ".healdan-backend")));
		backend.check();
		S3Backend prefixed = backend(fake, 8, "pre", true);
		assertEquals(INSTALL + " 8\n", text(fake.object(BUCKET, "pre/.healdan-backend")));
		prefixed.check();

		// Again over its own marker: nothing written
		fake.clearRequests();
		backend(fake, 7, "", true).check();
		assertTrue("Over its own marker it wrote " + fake.requests(Op.PUT_OBJECT),
				fake.requests(Op.PUT_OBJECT).isEmpty());

		// Over another install's or backend's marker: refused, and the marker untouched
		for (String other : List.of("another-install 7\n", INSTALL + " 9\n")) {
			fake.putObject(BUCKET, "other/.healdan-backend", other.getBytes(UTF_8));
			fake.clearRequests();
			failing(() -> backend(fake, 7, "other", true), "another backend or install");
			assertEquals(other, text(fake.object(BUCKET, "other/.healdan-backend")));
			assertTrue(fake.requests(Op.PUT_OBJECT).isEmpty());

			// Built without create, it fails its check, and writes nothing
			S3Backend existing = backend(fake, 7, "other", false);
			failing(existing::check, "another backend or install");
			assertTrue(fake.requests(Op.PUT_OBJECT).isEmpty());
			assertEquals(other, text(fake.object(BUCKET, "other/.healdan-backend")));
		}

		// No marker, and not creating: failing, and none is written
		fake.clearRequests();
		S3Backend unmarked = backend(fake, 7, "empty", false);
		failing(unmarked::check, "no marker");
		assertTrue(fake.requests(Op.PUT_OBJECT).isEmpty());
		assertTrue(fake.object(BUCKET, "empty/.healdan-backend").isEmpty());

		// The check reads the marker with GET: HEAD can't tell a missing bucket from a missing marker
		assertTrue(fake.requests(Op.HEAD_OBJECT).isEmpty());
		assertFalse(fake.requests(Op.GET_OBJECT).isEmpty());

		// A bucket that doesn't exist: "bucket not found", built with create or not
		S3Settings missing = fake.settings("no-such-bucket", "");
		S3Backend nowhere = backend(missing, 7, false);
		failing(nowhere::check, "bucket not found");
		failing(() -> backend(missing, 7, true), "bucket not found");

		// No key reaches the marker
		for (String key : List.of(".healdan-backend", "1/.healdan-backend", "../.healdan-backend", "/x", "1//2"))
			try {
				backend.put(key, source(bytes(3)), null);
				fail("Put " + key);
			} catch (IllegalArgumentException expected) {
				// as it should
			}
		try {
			backend.delete(".healdan-backend");
			fail("Deleted the marker");
		} catch (IllegalArgumentException expected) {
			// as it should
		}
		try {
			backend.stage(".healdan-backend", source(bytes(3)), null);
			fail("Staged over the marker");
		} catch (IllegalArgumentException expected) {
			// as it should
		}
		backend.check();
	}

	@Override
	@Test
	public void testRanges() throws IOException {
		super.testRanges();

		byte[] data = bytes(2 << 20);
		backend.put("1/2/doc/2.0", source(data), md5(data));
		fake.clearRequests();
		assertArrayEquals(Arrays.copyOfRange(data, 1048000, 1050000), read("1/2/doc/2.0", 1048000, 2000));
		assertEquals("The size, from HEAD", 1, fake.requests(Op.HEAD_OBJECT).size());
		List<Request> gets = fake.requests(Op.GET_OBJECT);
		assertEquals(1, gets.size());
		assertEquals("bytes=1048000-1049999", gets.get(0).header("range"));

		try (SeekableByteChannel channel = backend.open("1/2/doc/2.0")) {
			assertEquals(data.length, channel.size());
			fake.clearRequests();

			// The last byte: a read across the end asks only for what is there
			channel.position(data.length - 1L);
			ByteBuffer buffer = ByteBuffer.allocate(10);
			assertEquals(1, channel.read(buffer));
			assertEquals(data[data.length - 1], buffer.get(0));
			assertEquals(data.length, channel.position());
			assertEquals("bytes=2097151-2097151", fake.requests(Op.GET_OBJECT).get(0).header("range"));

			// At or past the end: nothing, and no request
			assertEquals(-1, channel.read(ByteBuffer.allocate(10)));
			channel.position(data.length + 100L);
			assertEquals(-1, channel.read(ByteBuffer.allocate(10)));
			assertEquals(1, fake.requests(Op.GET_OBJECT).size());

			// Reads in a row, one GET each from where the last ended, into a heap and a direct buffer
			channel.position(0);
			ByteBuffer first = ByteBuffer.allocate(100);
			ByteBuffer second = ByteBuffer.allocateDirect(100);
			assertEquals(100, channel.read(first));
			assertEquals(100, channel.read(second));
			assertEquals(200, channel.position());
			assertArrayEquals(Arrays.copyOfRange(data, 0, 100), first.array());
			byte[] got = new byte[100];
			second.flip();
			second.get(got);
			assertArrayEquals(Arrays.copyOfRange(data, 100, 200), got);
			assertEquals(3, fake.requests(Op.GET_OBJECT).size());
			assertEquals("bytes=100-199", fake.requests(Op.GET_OBJECT).get(2).header("range"));

			// Nothing asked, nothing fetched
			assertEquals(0, channel.read(ByteBuffer.allocate(0)));
			assertEquals(3, fake.requests(Op.GET_OBJECT).size());

			try {
				channel.write(ByteBuffer.allocate(1));
				fail("Wrote to a read channel");
			} catch (NonWritableChannelException expected) {
				// as it should
			}
			try {
				channel.truncate(0);
				fail("Truncated a read channel");
			} catch (NonWritableChannelException expected) {
				// as it should
			}
			try {
				channel.position(-1);
				fail("Moved before the start");
			} catch (IllegalArgumentException expected) {
				// as it should
			}
		}

		// Replaced while open: the read fails rather than mixing two files
		try (SeekableByteChannel channel = backend.open("1/2/doc/2.0")) {
			byte[] other = bytes(1000);
			backend.put("1/2/doc/2.0", source(other), md5(other));
			try {
				channel.read(ByteBuffer.allocate(10));
				fail("Read an object replaced since it was opened");
			} catch (BackendFailingException e) {
				fail("A replaced object reported as the backend's failure: " + e);
			} catch (IOException expected) {
				// as it should
			}
		}

		SeekableByteChannel closed = backend.open("1/2/doc/2.0");
		closed.close();
		assertFalse(closed.isOpen());
		try {
			closed.read(ByteBuffer.allocate(1));
			fail("Read a closed channel");
		} catch (ClosedChannelException expected) {
			// as it should
		}

		// A 0-byte object: nothing to read, and no GET
		backend.put("1/2/doc/empty", source(new byte[0]), null);
		fake.clearRequests();
		try (SeekableByteChannel channel = backend.open("1/2/doc/empty")) {
			assertEquals(0, channel.size());
			assertEquals(-1, channel.read(ByteBuffer.allocate(10)));
		}
		assertTrue(fake.requests(Op.GET_OBJECT).isEmpty());
	}

	/**
	 * A backend closed while a download and a channel are open, as {@code Backends.forget} closes one the Storage tab
	 * dropped, keeps its client until both are closed, so they read to the end
	 */
	@Test
	public void testCloseWaitsForOpenReads() throws IOException {
		byte[] data = bytes(3000);
		backend.put("1/2/doc/3.0", source(data), md5(data));
		S3Backend s3 = (S3Backend) backend;
		InputStream stream = s3.get("1/2/doc/3.0");
		SeekableByteChannel channel = s3.open("1/2/doc/3.0");

		s3.close();
		assertFalse("open reads keep the client", s3.isShut());
		ByteBuffer first = ByteBuffer.allocate(1000);
		assertEquals(1000, channel.read(first));
		assertArrayEquals(Arrays.copyOfRange(data, 0, 1000), first.array());
		assertArrayEquals(data, stream.readAllBytes());
		stream.close();
		assertFalse("the channel still keeps it", s3.isShut());
		ByteBuffer rest = ByteBuffer.allocate(2000);
		assertEquals(2000, channel.read(rest));
		assertArrayEquals(Arrays.copyOfRange(data, 1000, 3000), rest.array());
		channel.close();
		assertTrue("shut once the last read is closed", s3.isShut());

		// With nothing open, at once
		S3Backend idle = backend(fake, 9, "idle", true);
		idle.close();
		assertTrue(idle.isShut());
	}

	/**
	 * A backend closed while a put is under way, as {@code Backends.forget} closes one the Storage tab dropped, keeps its
	 * client until the put has ended, so the upload finishes rather than failing on a shut client
	 */
	@Test
	public void testCloseWaitsForACallUnderWay() throws Exception {
		// A read timeout longer than any wait here, so only the close could end the held put
		S3Backend s3 = backend(fake.settings(BUCKET, "held/").withTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(20),
				Duration.ofSeconds(20), 1), 10, true);
		byte[] data = bytes(3000);
		Path file = source(data);
		byte[] md5 = md5(data);
		fake.holdPut("held/1/2/doc/4.0");
		ExecutorService thread = Executors.newSingleThreadExecutor();
		try {
			Future<?> put = thread.submit(() -> {
				s3.put("1/2/doc/4.0", file, md5);
				return null;
			});
			long deadline = System.currentTimeMillis() + 20_000;
			while (fake.held().isEmpty()) {
				if (put.isDone())
					put.get();
				if (System.currentTimeMillis() > deadline)
					fail("The put never reached the server");
				Thread.sleep(5);
			}

			s3.close();
			assertFalse("the put under way keeps the client", s3.isShut());
			fake.release();
			put.get(20, TimeUnit.SECONDS);
			assertArrayEquals(data, fake.object(BUCKET, "held/1/2/doc/4.0").orElseThrow());
			assertTrue("shut once the put has ended", s3.isShut());
		} finally {
			fake.release();
			thread.shutdownNow();
		}
	}

	@Test
	public void testPrefix() throws IOException {
		S3Backend prefixed = backend(fake, 8, "healdan", true);
		byte[] data = bytes(10);
		prefixed.put("123/45/doc/1.2", source(data), md5(data));
		assertArrayEquals(data, fake.object(BUCKET, "healdan/123/45/doc/1.2").orElseThrow());
		assertTrue(fake.object(BUCKET, "123/45/doc/1.2").isEmpty());
		assertTrue(fake.object(BUCKET, "healdan/.healdan-backend").isPresent());
		assertEquals(10, prefixed.size("123/45/doc/1.2"));
		assertArrayEquals(data, readAll(prefixed, "123/45/doc/1.2"));

		// Its list strips the prefix and shows what S3 holds under it, its marker too, and nothing outside it
		backend.put("9/9/doc/1.0", source(data), null);
		assertEquals(List.of(".healdan-backend", "123/45/doc/1.2"), prefixed.list("", 100));
		assertEquals(List.of("123/45/doc/1.2"), prefixed.list("123/", 100));
		assertTrue(prefixed.list("9/", 100).isEmpty());
		assertTrue(backend.list("", 100).contains("9/9/doc/1.0"));

		prefixed.delete("123/45/doc/1.2");
		assertTrue(fake.object(BUCKET, "healdan/123/45/doc/1.2").isEmpty());

		// Kept without a leading slash, with one trailing slash
		assertEquals("healdan/", fake.settings(BUCKET, "healdan").prefix());
		assertEquals("healdan/", fake.settings(BUCKET, "/healdan//").prefix());
		assertEquals("a/b/", fake.settings(BUCKET, " a/b/ ").prefix());
		assertEquals("", fake.settings(BUCKET, "").prefix());
		assertEquals("", fake.settings(BUCKET, "/").prefix());
		assertEquals("", fake.settings(BUCKET, null).prefix());
		// so either way it's one place, with one marker
		backend(fake, 8, "/healdan/", false).check();
	}

	@Test
	public void testListPages() throws IOException {
		for (int i = 0; i < 25; i++)
			backend.put("1/2/doc/" + (100 + i), source(bytes(1)), null);
		fake.clearRequests();
		List<String> all = backend.list("1/", 1000);
		assertEquals(25, all.size());
		assertEquals(10, backend.list("1/", 10).size());
		assertTrue(fake.requests(Op.LIST_OBJECTS).size() >= 2);
	}

	@Override
	@Test
	public void testHold() throws IOException {
		super.testHold();

		// It sends nothing, so it can't wait on an outage, holding the file's lock
		byte[] data = bytes(10);
		backend.put("1/2/doc/1.0", source(data), md5(data));
		fake.clearRequests();
		fake.stall();
		assertEquals("1/2/doc/1.0", backend.hold("1/2/doc/1.0"));
		fake.behave();
		assertTrue(fake.requests().isEmpty());
		try {
			backend.hold("/1/2/doc/1.0");
			fail("Held a key that isn't one");
		} catch (IllegalArgumentException expected) {
			// as it should
		}
		try {
			backend.purgeHeld(".healdan-backend");
			fail("Purged the marker");
		} catch (IOException expected) {
			// as it should
		}
		assertTrue(fake.object(BUCKET, ".healdan-backend").isPresent());
	}

	@Test
	public void testStageInPlace() throws IOException {
		byte[] data = bytes(20);
		Staged staged = backend.stage("1/2/doc/1.0", source(data), md5(data));
		assertArrayEquals("Already there", data, fake.object(BUCKET, "1/2/doc/1.0").orElseThrow());
		fake.clearRequests();
		staged.commit();
		assertTrue("commit sends nothing", fake.requests().isEmpty());
		staged.discard();
		assertEquals(1, fake.requests(Op.DELETE_OBJECT).size());
		assertTrue(fake.object(BUCKET, "1/2/doc/1.0").isEmpty());
	}

	@Test
	public void testOwnObjects() throws Exception {
		S3Backend prefixed = backend(fake, 8, "pre", true);
		byte[] data = bytes(40);
		for (String key : List.of(".healdan-check", ".healdan-test-1f2e")) {
			prefixed.put(key, source(data), md5(data));
			assertArrayEquals(key, data, fake.object(BUCKET, "pre/" + key).orElseThrow());
			assertArrayEquals(key, data, readAll(prefixed, key));
			assertEquals(key, 40, prefixed.size(key));
		}
		// S3 lists what it holds under the prefix asked for: Test finds its own object
		assertEquals(List.of(".healdan-test-1f2e"), prefixed.list(".healdan-test-", 100));
		assertTrue(prefixed.list("", 100).containsAll(List.of(".healdan-check", ".healdan-test-1f2e")));
		for (String key : List.of(".healdan-check", ".healdan-test-1f2e")) {
			prefixed.delete(key);
			assertEquals(key, -1, prefixed.size(key));
		}

		// The check object, as the key check writes and reads it
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		FileCipher cipher = new FileCipher(key);
		KeyCheck.write(prefixed, ".healdan-check", cipher);
		assertTrue(KeyCheck.verify(prefixed, ".healdan-check", cipher));

		// The marker, and every other dot name, stay out of reach
		for (String refused : List.of(".healdan-backend", ".healdan-checks", ".healdan-test-", "1/.healdan-check",
				".healdan-check/x", ".healdan-test-a/b", "1/.healdan-test-x", ".held/1/2", ".other", "..")) {
			for (Call call : List.<Call>of(() -> prefixed.put(refused, source(data), null),
					() -> prefixed.stage(refused, source(data), null), () -> prefixed.delete(refused),
					() -> prefixed.get(refused)))
				try {
					call.run();
					fail("Reached " + refused);
				} catch (IllegalArgumentException expected) {
					// as it should
				}
			try {
				prefixed.purgeHeld(refused);
				fail("Purged " + refused);
			} catch (IOException expected) {
				// as it should
			}
		}
		assertEquals(INSTALL + " 8\n", text(fake.object(BUCKET, "pre/.healdan-backend")));
		prefixed.check();
	}

	@Test
	public void testErrorMapping() throws Exception {
		byte[] data = bytes(100);
		backend.put("1/2/doc/1.0", source(data), md5(data));

		// 401 and 403: access denied, the backend failing
		for (Object[] answer : new Object[][] { { 401, "Unauthorized" }, { 403, "AccessDenied" },
				{ 403, "SignatureDoesNotMatch" }, { 403, "InvalidAccessKeyId" } }) {
			fake.behave().fail((Integer) answer[0], (String) answer[1]);
			failing(() -> backend.put("1/2/doc/1.1", source(data), md5(data)), "access denied");
			failing(() -> read("1/2/doc/1.0"), "access denied");
			failing(() -> backend.size("1/2/doc/1.0"), "access denied");
			failing(() -> backend.list("", 10), "access denied");
			failing(backend::check, "access denied");
		}

		// A bucket that doesn't exist, whatever is asked of it
		fake.behave();
		S3Backend nowhere = backend(fake.settings("no-such-bucket", ""), 7, false);
		failing(() -> nowhere.put("1/2/doc/1.0", source(data), md5(data)), "bucket not found");
		failing(() -> nowhere.get("1/2/doc/1.0"), "bucket not found");
		failing(() -> nowhere.list("", 10), "bucket not found");
		failing(() -> nowhere.delete("1/2/doc/1.0"), "bucket not found");
		failing(nowhere::check, "bucket not found");

		// Any other refusal is the file's: a plain IOException naming the code, a single put and a part alike
		for (Object[] answer : new Object[][] { { 400, "EntityTooLarge" }, { 400, "InvalidRequest" },
				{ 409, "OperationAborted" }, { 412, "PreconditionFailed" } }) {
			fake.behave().fail(Op.PUT_OBJECT, (Integer) answer[0], (String) answer[1]);
			refusedFile(() -> backend.put("1/2/doc/1.1", source(data), md5(data)), (String) answer[1]);
		}
		Big big = big(70 * MIB, 4);
		fake.behave().failPart(1, 400, "EntityTooLarge");
		refusedFile(() -> putBig(big), "EntityTooLarge");
		assertEquals(0, fake.openUploads());
		fake.behave().fail(Op.GET_OBJECT, 400, "InvalidRequest");
		refusedFile(() -> read("1/2/doc/1.0"), "InvalidRequest");
		refusedFile(() -> read("1/2/doc/1.0", 10, 10), "InvalidRequest");

		// but the check is the backend's own answer: any refusal there is the backend failing
		failing(backend::check, "invalidrequest");
		fake.behave().fail(Op.GET_OBJECT, 400, "AuthorizationHeaderMalformed");
		failing(backend::check, "authorizationheadermalformed");

		// and 5xx, once the attempts are spent, is unreachable
		fake.behave().fail(Op.PUT_OBJECT, 500, "InternalError");
		unreachable(() -> backend.put("1/2/doc/1.1", source(data), md5(data)));

		// So are throttling and a request the server timed out: outages, not the file's failures
		for (Object[] answer : new Object[][] { { 429, "TooManyRequests" }, { 503, "SlowDown" }, { 400, "SlowDown" },
				{ 400, "RequestTimeout" } }) {
			fake.behave().fail((Integer) answer[0], (String) answer[1]);
			unreachable(() -> backend.put("1/2/doc/1.1", source(data), md5(data)));
			unreachable(() -> read("1/2/doc/1.0"));
			unreachable(() -> backend.list("", 10));
			unreachable(backend::check);
		}
		fake.behave().fail(429, "TooManyRequests");
		unreachable(() -> backend.size("1/2/doc/1.0"));
		fake.behave().failPart(1, 400, "RequestTimeout");
		unreachable(() -> putBig(big));
		assertEquals(0, fake.openUploads());
		fake.behave();
		assertArrayEquals(data, read("1/2/doc/1.0"));
	}

	@Test
	public void testMissingBucketOnAnUpload() throws IOException {
		byte[] data = bytes(100);
		S3Backend nowhere = backend(fake.settings("no-such-bucket", ""), 7, false);

		// A refusal sent before 100-continue reaches the client as a 404 with no code: a GET then tells a missing
		// bucket from anything else
		fake.failWithoutBody(Op.PUT_OBJECT, 404);
		failing(() -> nowhere.put("1/2/doc/1.0", source(data), md5(data)), "bucket not found");
		failing(() -> nowhere.stage("1/2/doc/1.0", source(data), md5(data)), "bucket not found");
		fake.behave().failWithoutBody(Op.CREATE_UPLOAD, 404);
		Big big = big(70 * MIB, 6);
		failing(() -> nowhere.put("1/2/doc/big", big.path(), big.md5()), "bucket not found");
		// With the bucket there, it stays the file's failure
		fake.behave().failWithoutBody(Op.PUT_OBJECT, 404);
		refusedFile(() -> backend.put("1/2/doc/1.0", source(data), md5(data)), "404");

		// A part: the bucket gone during the upload, and then still there (as a NoSuchUpload would be)
		fake.behave().failPartWithoutBody(1, 404).fail(Op.LIST_OBJECTS, 404, "NoSuchBucket");
		failing(() -> putBig(big), "bucket not found");
		assertEquals(0, fake.openUploads());
		fake.behave().failPartWithoutBody(1, 404);
		refusedFile(() -> putBig(big), "404");
		assertEquals(0, fake.openUploads());

		// The status alone still says what a 403 and a 503 are
		fake.behave().failWithoutBody(Op.PUT_OBJECT, 403);
		failing(() -> backend.put("1/2/doc/1.0", source(data), md5(data)), "access denied");
		fake.behave().failPartWithoutBody(1, 403);
		failing(() -> putBig(big), "access denied");
		fake.behave().failWithoutBody(Op.PUT_OBJECT, 503);
		unreachable(() -> backend.put("1/2/doc/1.0", source(data), md5(data)));
		fake.behave();
		assertEquals(0, fake.openUploads());
	}

	@Test
	public void testEndpointCheckedWhenBuilt() throws IOException {
		// FakeS3 on loopback HTTP passes: the contract's backend was built on it

		// A public address over http:// is refused, naming the rule
		S3Settings open = new S3Settings(URI.create("http://8.8.8.8:9000"), FakeS3.REGION, BUCKET, "", true,
				FakeS3.ACCESS_KEY);
		BackendFailingException refused = failing(() -> backend(open, 7, false), "private network");
		assertFalse(refused.getMessage().contains(SECRET));

		List<String> looked = new ArrayList<>();
		Map<String, String> names = Map.of("storage.example", "52.95.110.1", "nas.lan", "10.0.0.9");
		S3Backend.resolver = host -> {
			looked.add(host);
			String address = names.get(host);
			if (address == null)
				throw new UncheckedIOException(new UnknownHostException(host));
			return new InetAddress[] { InetAddress.ofLiteral(address) };
		};
		try {
			failing(() -> backend(settingsAt("http://storage.example:9000"), 7, false), "private network");
			backend(settingsAt("http://nas.lan:9000"), 7, false).close();
			// A name that can't be looked up is no verdict: DNS may be down
			unreachable(() -> backend(settingsAt("http://gone.lan:9000"), 7, false));
			// and https:// needs no lookup
			looked.clear();
			backend(settingsAt("https://s3.us-east-2.wasabisys.com"), 7, false).close();
			assertTrue(looked.toString(), looked.isEmpty());

			// http:// without path style is refused, naming the rule: the SDK would connect to <bucket>.<host>
			S3Settings virtualHosted = new S3Settings(URI.create("http://nas.lan:9000"), FakeS3.REGION, BUCKET, "",
					false, FakeS3.ACCESS_KEY);
			BackendFailingException noPathStyle = failing(() -> backend(virtualHosted, 7, false), "path style");
			assertTrue(noPathStyle.getMessage(), noPathStyle.getMessage().contains("private network"));
			assertTrue(looked.toString(), looked.isEmpty());
		} finally {
			S3Backend.resolver = Endpoints.DNS;
		}
	}

	private static S3Settings settingsAt(String endpoint) {
		return new S3Settings(URI.create(endpoint), FakeS3.REGION, BUCKET, "", true, FakeS3.ACCESS_KEY);
	}

	@Test
	public void testBrokenAnswers() throws IOException {
		byte[] data = bytes(100);
		backend.put("1/2/doc/1.0", source(data), md5(data));

		// A HEAD without its length: an IOException, not a NullPointerException
		fake.headWithoutLength();
		refusedFile(() -> backend.size("1/2/doc/1.0"), "without its length");
		refusedFile(() -> backend.open("1/2/doc/1.0").close(), "without its length");

		// A marker cut off on its way is an outage, not another backend's marker, and creating over it writes nothing
		fake.behave().cutOff(5);
		unreachable(backend::check);
		fake.clearRequests();
		unreachable(() -> backend(fake, 7, "", true));
		assertTrue(fake.requests(Op.PUT_OBJECT).isEmpty());
		// and so is a file cut off
		unreachable(() -> read("1/2/doc/1.0"));
		fake.behave();
		assertEquals(INSTALL + " 7\n", text(fake.object(BUCKET, ".healdan-backend")));
		backend.check();
	}

	/** A sparse file of 10,001 parts, which takes no room: refused before it's read, and before any request */
	@Test(timeout = 10_000)
	public void testTooManyParts() throws IOException {
		Path huge = work.resolve("huge.bin");
		try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
			file.setLength(10_001L * S3Backend.PART_SIZE);
		}
		fake.clearRequests();
		refusedFile(() -> backend.put("1/2/doc/huge", huge, null), "10,000 parts");
		assertTrue(fake.requests().toString(), fake.requests().isEmpty());
	}

	@Test
	public void testUploadsHaveAnAttemptTimeout() throws Exception {
		// A read timeout longer than the uploads' timeout: only the upload's own timeout can end these
		S3Backend quick = backend(fake.settings(BUCKET, "").withTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(30),
				Duration.ofSeconds(1), 1), 7, false);

		// A server that takes a MiB of the body and stops reading: the client is stuck in a write
		Big forty = big(40 * MIB, 40);
		fake.stallBody();
		long start = System.nanoTime();
		unreachable(() -> quick.put("1/2/doc/1.0", forty.path(), forty.md5()));
		long took = (System.nanoTime() - start) / 1_000_000;
		assertTrue("took " + took + " ms", took < 10_000);
		assertFalse("The thread was left interrupted", Thread.interrupted());
		assertTrue(fake.object(BUCKET, "1/2/doc/1.0").isEmpty());

		// A part, likewise: the upload is aborted. Two seconds, so the first part, answered, is well within its own
		S3Backend parts = backend(fake.settings(BUCKET, "").withTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(30),
				Duration.ofSeconds(2), 1), 7, false);
		Big big = big(70 * MIB, 41);
		fake.behave().stallBody(2);
		fake.clearRequests();
		start = System.nanoTime();
		unreachable(() -> parts.put("1/2/doc/big", big.path(), big.md5()));
		took = (System.nanoTime() - start) / 1_000_000;
		assertTrue("took " + took + " ms", took < 10_000);
		assertFalse("The thread was left interrupted", Thread.interrupted());
		// Part 2 was sent, so part 1 was answered: only part 2's stall ended it
		assertEquals(List.of(1, 2), fake.requests(Op.UPLOAD_PART).stream().map(Request::partNumber).toList());
		assertEquals(0, fake.openUploads());
		assertEquals(1, fake.requests(Op.ABORT_UPLOAD).size());

		// The timeout is the whole attempt's: an answer that doesn't come in time ends it too
		byte[] data = bytes(1000);
		fake.behave().delay(Op.PUT_OBJECT, Duration.ofMillis(1200));
		unreachable(() -> quick.put("1/2/doc/1.1", source(data), md5(data)));
		assertFalse(Thread.interrupted());

		// Reads have none: an answer slower than the uploads' timeout still arrives
		fake.behave();
		quick.put("1/2/doc/1.1", source(data), md5(data));
		fake.delay(Op.GET_OBJECT, Duration.ofMillis(1200));
		assertArrayEquals(data, readAll(quick, "1/2/doc/1.1"));
		fake.behave();

		// Ten minutes in use
		assertEquals(Duration.ofMinutes(10),
				new S3Settings(URI.create("https://s3.example.com"), "r", "b", "", true, "AK").uploadTimeout());
	}

	@Test
	public void testUnreachable() throws IOException {
		byte[] data = bytes(100);
		backend.put("1/2/doc/1.0", source(data), md5(data));

		// A server that takes the request and never answers: the read timeout, a second here (60 in use)
		fake.stall();
		long start = System.nanoTime();
		assertTrue(causedBy(unreachable(() -> backend.size("1/2/doc/1.0")), java.net.SocketTimeoutException.class));
		unreachable(backend::check);
		unreachable(() -> backend.put("1/2/doc/1.1", source(data), md5(data)));
		long took = (System.nanoTime() - start) / 1_000_000;
		assertTrue("took " + took + " ms", took < 15_000);
		fake.behave();

		// A connection closed before the answer
		fake.drop();
		unreachable(() -> backend.size("1/2/doc/1.0"));
		unreachable(() -> read("1/2/doc/1.0"));
		unreachable(() -> read("1/2/doc/1.0", 10, 10));
		unreachable(backend::check);
		unreachable(() -> backend.put("1/2/doc/1.1", source(data), md5(data)));
		unreachable(() -> backend.delete("1/2/doc/1.0"));
		unreachable(() -> backend.list("", 10));
		fake.behave();

		// 503, once the attempts are spent
		fake.fail(503, "ServiceUnavailable");
		unreachable(() -> backend.size("1/2/doc/1.0"));
		unreachable(() -> read("1/2/doc/1.0"));
		unreachable(backend::check);
		unreachable(() -> backend.put("1/2/doc/1.1", source(data), md5(data)));
		unreachable(() -> backend.list("", 10));
		S3Backend twice = backend(
				fake.settings(BUCKET, "").withTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1),
						Duration.ofSeconds(20), 2),
				7, false);
		fake.clearRequests();
		unreachable(() -> twice.get("1/2/doc/1.0"));
		assertEquals("Two attempts", 2, fake.requests(Op.GET_OBJECT).size());
		fake.behave();

		// Nothing listening
		int closed;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closed = socket.getLocalPort();
		}
		S3Settings nowhere = new S3Settings(URI.create("http://127.0.0.1:" + closed), FakeS3.REGION, BUCKET, "",
				true, FakeS3.ACCESS_KEY, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(20), 1L << 30,
				1);
		assertTrue(causedBy(unreachable(backend(nowhere, 7, false)::check), java.net.ConnectException.class));
		unreachable(() -> backend(nowhere, 7, true));

		// Answering again, it works as before
		backend.check();
		assertArrayEquals(data, read("1/2/doc/1.0"));
	}

	@Test
	public void testSecretNeverInMessages() throws Exception {
		byte[] data = bytes(100);
		backend.put("1/2/doc/1.0", source(data), md5(data));
		Big big = big(70 * MIB, 9);

		List<Throwable> thrown = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		// As Wasabi and Garage answer a wrong secret, with the SDK's lines at every level
		for (boolean wasabi : new boolean[] { true, false })
			try (Capture logs = new Capture(Level.ALL)) {
				fake.behave();
				if (wasabi)
					fake.refuseSignatures();
				else
					fake.denyAccess();
				thrown.addAll(refusals(data, big));
				lines.addAll(logs.lines());
			}
		// A server that repeats the signature in its message: the SDK's own debug lines quote that message, so its
		// lines are read at the level the server logs them (log.xml's root, info), the storage code's at every level
		try (Capture logs = new Capture(Level.INFO)) {
			fake.behave().echoSignatures();
			thrown.addAll(refusals(data, big));
			lines.addAll(logs.lines());
		}

		Set<String> secrets = new HashSet<>();
		secrets.add(SECRET);
		for (Request request : fake.requests())
			secrets.addAll(request.signatures());
		assertTrue("Signatures seen: " + secrets.size(), secrets.size() > 20);

		List<String> texts = new ArrayList<>();
		for (Throwable t : thrown) {
			texts.add(t.toString());
			texts.add(String.valueOf(t.getMessage()));
			StringWriter trace = new StringWriter();
			t.printStackTrace(new PrintWriter(trace));
			texts.add(trace.toString());
		}
		texts.add(backend.toString());
		texts.add(fake.settings(BUCKET, "").toString());
		texts.addAll(lines);
		for (String text : texts)
			for (String secret : secrets)
				assertFalse("A secret or signature in: " + text.replace(secret, "<HERE>"), text.contains(secret));

		// The SDK's own lines were among those read
		assertTrue(lines.stream().anyMatch(line -> line.startsWith("software.amazon.awssdk")));
	}

	/** @return what each call throws while the server refuses the secret, each an "access denied" */
	private List<Throwable> refusals(byte[] data, Big big) {
		List<Throwable> thrown = new ArrayList<>();
		thrown.add(failing(backend::check, "access denied"));
		thrown.add(failing(() -> backend.put("1/2/doc/1.1", source(data), md5(data)), "access denied"));
		thrown.add(failing(() -> backend.stage("1/2/doc/1.2", source(data), null), "access denied"));
		thrown.add(failing(() -> backend.put("1/2/doc/big", big.path(), big.md5()), "access denied"));
		thrown.add(failing(() -> read("1/2/doc/1.0"), "access denied"));
		thrown.add(failing(() -> read("1/2/doc/1.0", 10, 10), "access denied"));
		thrown.add(failing(() -> backend.size("1/2/doc/1.0"), "access denied"));
		thrown.add(failing(() -> backend.delete("1/2/doc/1.0"), "access denied"));
		thrown.add(failing(() -> backend.list("", 10), "access denied"));
		thrown.add(failing(() -> backend.purgeHeld("1/2/doc/1.0"), "access denied"));
		thrown.add(failing(() -> backend(fake, 8, "new", true), "access denied"));
		return thrown;
	}

	/**
	 * Collects every log line while open: the storage code's at every level, the SDK's at the level given, the rest
	 * at theirs.
	 */
	private static final class Capture implements AutoCloseable {

		private static final String SDK = "software.amazon.awssdk";

		private static final String STORAGE = "com.logicaldoc.healdan.storage";

		private final List<String> lines = new CopyOnWriteArrayList<>();

		private final LoggerContext context = (LoggerContext) LogManager.getContext(false);

		private final AbstractAppender appender;

		private final List<String> added = new ArrayList<>();

		Capture(Level sdk) {
			appender = new AbstractAppender("s3-capture-" + UUID.randomUUID(), null, null, true,
					Property.EMPTY_ARRAY) {
				@Override
				public void append(LogEvent event) {
					StringBuilder line = new StringBuilder(event.getLoggerName()).append(' ').append(event.getLevel())
							.append(' ').append(event.getMessage().getFormattedMessage());
					if (event.getThrown() != null) {
						StringWriter trace = new StringWriter();
						event.getThrown().printStackTrace(new PrintWriter(trace));
						line.append('\n').append(trace);
					}
					lines.add(line.toString());
				}
			};
			appender.start();
			Configuration config = context.getConfiguration();
			config.addAppender(appender);
			for (String name : List.of(SDK, STORAGE))
				if (!config.getLoggers().containsKey(name)) {
					Level level = name.equals(SDK) ? sdk : Level.ALL;
					LoggerConfig logger = new LoggerConfig(name, level, false);
					logger.addAppender(appender, level, null);
					config.addLogger(name, logger);
					added.add(name);
				}
			config.getRootLogger().addAppender(appender, Level.ALL, null);
			context.updateLoggers();
		}

		List<String> lines() {
			return List.copyOf(lines);
		}

		@Override
		public void close() {
			Configuration config = context.getConfiguration();
			for (String name : added)
				config.removeLogger(name);
			config.getRootLogger().removeAppender(appender.getName());
			context.updateLoggers();
			appender.stop();
		}
	}
}
