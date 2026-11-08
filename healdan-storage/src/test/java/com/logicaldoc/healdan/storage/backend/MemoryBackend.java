// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A backend that keeps bytes in a map and takes faults, for the tests of everything built on {@link Backend}. Like a
 * local folder by default: a staged copy is absent until commit, and {@code hold} moves the bytes aside under
 * {@code .held/}. Like S3 when made with {@link #s3Like}: a staged copy is at its key at once, and {@code hold} leaves
 * the object where it is and sends nothing.
 */
public class MemoryBackend implements Backend {

	/** The operations {@link MemoryBackend#refuse} can refuse */
	public enum Op {
		GET, OPEN, PUT, DELETE, LIST
	}

	private final long id;

	private final boolean stagesInPlace;

	private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

	private final AtomicInteger total = new AtomicInteger();

	/** Like a local folder's {@code .held/}: the bytes each held key had */
	private final Map<String, byte[]> heldObjects = new ConcurrentHashMap<>();

	private volatile int refuseNext;

	private volatile Set<Op> refused = EnumSet.noneOf(Op.class);

	private volatile long failAfterBytes = -1;

	private volatile boolean unreachable;

	private volatile boolean failingCheck;

	/** The keys the backend refuses as access denied, as S3 does an object a policy forbids */
	private final Set<String> denied = ConcurrentHashMap.newKeySet();

	private volatile Duration slow = Duration.ZERO;

	private volatile boolean corruptNextPut;

	/** Like local folders: a staged copy is absent until commit, and discard has nothing to delete. */
	public MemoryBackend(long id) {
		this(id, false);
	}

	/** Like S3: a staged copy is already at its key, commit does nothing, and discard deletes the object. */
	public static MemoryBackend s3Like(long id) {
		return new MemoryBackend(id, true);
	}

	private MemoryBackend(long id, boolean stagesInPlace) {
		this.id = id;
		this.stagesInPlace = stagesInPlace;
	}

	/** The next {@code n} operations (any of the five) fail with an IOException. */
	public MemoryBackend refuseNext(int n) {
		this.refuseNext = n;
		return this;
	}

	/** Refuses these operations until cleared, and lets the others work. No operations clears it. */
	public MemoryBackend refuse(Op... ops) {
		Set<Op> set = EnumSet.noneOf(Op.class);
		set.addAll(Arrays.asList(ops));
		this.refused = set;
		return this;
	}

	/** Reads and writes fail with an IOException once this many bytes have gone; negative clears it. */
	public MemoryBackend failAfterBytes(long n) {
		this.failAfterBytes = n;
		return this;
	}

	/**
	 * Refuses every operation on these keys as access denied, a {@link BackendFailingException}, while the backend's
	 * own check passes.
	 */
	public MemoryBackend deny(String... keys) {
		denied.addAll(Arrays.asList(keys));
		return this;
	}

	/** Its {@code check} throws {@link BackendFailingException}, as when its credentials are refused. */
	public MemoryBackend failCheck(boolean failing) {
		this.failingCheck = failing;
		return this;
	}

	/** Every operation, {@code check} too, throws {@link BackendUnreachableException}. */
	public MemoryBackend unreachable(boolean unreachable) {
		this.unreachable = unreachable;
		return this;
	}

	/** Every operation takes this long. */
	public MemoryBackend slow(Duration duration) {
		this.slow = duration;
		return this;
	}

	/**
	 * The next put or stage with a checksum is refused with an IOException and stores nothing, as S3 refuses a body
	 * whose Content-MD5 doesn't match. One without a checksum stores its bytes with one flipped, silently. Either
	 * way the fault is used up.
	 */
	public MemoryBackend corruptNextPut() {
		this.corruptNextPut = true;
		return this;
	}

	/** Clears every fault. */
	public MemoryBackend clearFaults() {
		refuseNext = 0;
		refused = EnumSet.noneOf(Op.class);
		denied.clear();
		failingCheck = false;
		failAfterBytes = -1;
		unreachable = false;
		slow = Duration.ZERO;
		corruptNextPut = false;
		return this;
	}

	/** @return how many calls every operation made, for every key */
	public int calls() {
		return total.get();
	}

	/** @return how many calls any operation made for this key */
	public int calls(String key) {
		AtomicInteger n = calls.get(key);
		return n == null ? 0 : n.get();
	}

	/** @return how many calls this operation made for this key */
	public int calls(Op op, String key) {
		AtomicInteger n = calls.get(op + " " + key);
		return n == null ? 0 : n.get();
	}

	/** @return the bytes at the key, or null, without counting a call */
	public byte[] bytes(String key) {
		return objects.get(key);
	}

	/** Puts bytes straight in, without faults or counting. */
	public void store(String key, byte[] bytes) {
		objects.put(key, bytes.clone());
	}

	private void begin(Op op, String key) throws IOException {
		total.incrementAndGet();
		calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
		calls.computeIfAbsent(op + " " + key, k -> new AtomicInteger()).incrementAndGet();
		Duration wait = slow;
		if (!wait.isZero())
			try {
				Thread.sleep(wait.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted", e);
			}
		if (unreachable)
			throw new BackendUnreachableException("Backend " + id + " is unreachable");
		synchronized (this) {
			if (refuseNext > 0) {
				refuseNext--;
				throw new IOException("Backend " + id + " refused " + op);
			}
		}
		if (refused.contains(op))
			throw new IOException("Backend " + id + " refuses " + op);
		if (denied.contains(key))
			throw new BackendFailingException("Backend " + id + ": access denied (403 AccessDenied) to " + op + " " + key);
	}

	private static byte[] md5(byte[] bytes) {
		try {
			return MessageDigest.getInstance("MD5").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] read(Path file, byte[] md5) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		long limit = failAfterBytes;
		if (limit >= 0 && bytes.length > limit)
			throw new IOException("Backend " + id + " failed after " + limit + " bytes");
		if (md5 != null && !Arrays.equals(md5, md5(bytes)))
			throw new IOException("The bytes of " + file.getFileName() + " don't match their checksum");
		if (corruptNextPut && md5 != null) {
			corruptNextPut = false;
			throw new IOException("The bytes of " + file.getFileName() + " arrived corrupted");
		}
		if (corruptNextPut && bytes.length > 0) {
			corruptNextPut = false;
			bytes = bytes.clone();
			bytes[bytes.length / 2] ^= 0x55;
		}
		return bytes;
	}

	@Override
	public long id() {
		return id;
	}

	@Override
	public void put(String key, Path file, byte[] md5) throws IOException {
		begin(Op.PUT, key);
		objects.put(key, read(file, md5));
	}

	@Override
	public Staged stage(String key, Path file, byte[] md5) throws IOException {
		begin(Op.PUT, key);
		byte[] bytes = read(file, md5);
		if (stagesInPlace) {
			objects.put(key, bytes);
			return new Staged() {
				@Override
				public void commit() {
					// Already there
				}

				@Override
				public void discard() {
					objects.remove(key);
				}
			};
		}
		return new Staged() {
			@Override
			public void commit() throws IOException {
				objects.put(key, bytes);
			}

			@Override
			public void discard() {
				// Nothing was in place
			}
		};
	}

	@Override
	public InputStream get(String key) throws IOException {
		begin(Op.GET, key);
		byte[] bytes = objects.get(key);
		if (bytes == null)
			throw new FileNotFoundException(key);
		long limit = failAfterBytes;
		return failingStream(bytes, limit);
	}

	private InputStream failingStream(byte[] bytes, long limit) {
		return new InputStream() {
			private int pos;

			@Override
			public int read() throws IOException {
				if (limit >= 0 && pos >= limit)
					throw new IOException("Backend " + id + " failed after " + limit + " bytes");
				return pos < bytes.length ? bytes[pos++] & 0xff : -1;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (len == 0)
					return 0;
				if (pos >= bytes.length)
					return -1;
				if (limit >= 0 && pos >= limit)
					throw new IOException("Backend " + id + " failed after " + limit + " bytes");
				long end = Math.min(bytes.length, limit >= 0 ? limit : bytes.length);
				int n = (int) Math.min(len, end - pos);
				System.arraycopy(bytes, pos, b, off, n);
				pos += n;
				return n;
			}
		};
	}

	@Override
	public SeekableByteChannel open(String key) throws IOException {
		begin(Op.OPEN, key);
		byte[] bytes = objects.get(key);
		if (bytes == null)
			throw new FileNotFoundException(key);
		long limit = failAfterBytes;
		return new SeekableByteChannel() {
			private long position;

			private boolean open = true;

			@Override
			public int read(ByteBuffer dst) throws IOException {
				if (position >= bytes.length)
					return -1;
				if (limit >= 0 && position >= limit)
					throw new IOException("Backend " + id + " failed after " + limit + " bytes");
				long end = Math.min(bytes.length, limit >= 0 ? limit : bytes.length);
				int n = (int) Math.min(dst.remaining(), end - position);
				dst.put(bytes, (int) position, n);
				position += n;
				return n;
			}

			@Override
			public int write(ByteBuffer src) {
				throw new NonWritableChannelException();
			}

			@Override
			public long position() {
				return position;
			}

			@Override
			public SeekableByteChannel position(long newPosition) {
				position = newPosition;
				return this;
			}

			@Override
			public long size() {
				return bytes.length;
			}

			@Override
			public SeekableByteChannel truncate(long size) {
				throw new NonWritableChannelException();
			}

			@Override
			public boolean isOpen() {
				return open;
			}

			@Override
			public void close() {
				open = false;
			}
		};
	}

	@Override
	public long size(String key) throws IOException {
		total.incrementAndGet();
		calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
		if (unreachable)
			throw new BackendUnreachableException("Backend " + id + " is unreachable");
		byte[] bytes = objects.get(key);
		return bytes == null ? -1 : bytes.length;
	}

	@Override
	public void delete(String key) throws IOException {
		begin(Op.DELETE, key);
		objects.remove(key);
	}

	@Override
	public List<String> list(String prefix, int max) throws IOException {
		begin(Op.LIST, prefix);
		return new TreeMap<>(objects).keySet().stream().filter(k -> k.startsWith(prefix)).limit(max).toList();
	}

	@Override
	public void check() throws BackendFailingException {
		if (unreachable)
			throw new BackendUnreachableException("Backend " + id + " is unreachable");
		if (failingCheck)
			throw new BackendFailingException("Backend " + id + ": access denied (403 AccessDenied) to its marker");
	}

	private static final String HELD = LocalBackend.HELD + "/";

	@Override
	public Optional<String> held(String key) {
		return !stagesInPlace && heldObjects.containsKey(key) ? Optional.of(HELD + key) : Optional.empty();
	}

	/**
	 * Like S3's when S3-like: leaves the object and sends nothing, whether it's there or not. Like a local folder's
	 * otherwise: moves the bytes under {@code .held/}, replacing an older held copy, and refuses a missing key, or any
	 * while unreachable.
	 */
	@Override
	public String hold(String key) throws IOException {
		if (stagesInPlace)
			return key;
		if (unreachable)
			throw new BackendUnreachableException("Backend " + id + " is unreachable");
		byte[] bytes = objects.remove(key);
		if (bytes == null)
			throw new FileNotFoundException(key);
		heldObjects.put(key, bytes);
		return HELD + key;
	}

	@Override
	public void purgeHeld(String location) throws IOException {
		if (stagesInPlace) {
			delete(location);
			return;
		}
		if (location == null || !location.startsWith(HELD))
			throw new IOException("Not a held location: " + location);
		heldObjects.remove(location.substring(HELD.length()));
	}
}
