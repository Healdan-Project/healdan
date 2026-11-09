// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/**
 * An S3 object read in ranges: each {@link #read} is one ranged {@code GET} for exactly the bytes it can fill, from
 * the position on, and none at or past the end. Its size is the one {@code HEAD} gave when it was opened. Not for
 * writing, and not for use by two threads at once.
 */
final class S3Channel implements SeekableByteChannel {

	/** Fetches the bytes from {@code first} to {@code last}, both included, all of them or an exception */
	@FunctionalInterface
	interface Ranges {
		InputStream read(long first, long last) throws IOException;
	}

	private final long size;

	private final Ranges ranges;

	/** Run once, when it is closed */
	private final Runnable closed;

	private long position;

	private boolean open = true;

	/**
	 * @param size the object's size
	 * @param ranges fetches its ranges
	 */
	S3Channel(long size, Ranges ranges) {
		this(size, ranges, () -> {
		});
	}

	/**
	 * @param size the object's size
	 * @param ranges fetches its ranges
	 * @param closed run once, when the channel is closed
	 */
	S3Channel(long size, Ranges ranges, Runnable closed) {
		this.size = size;
		this.ranges = ranges;
		this.closed = closed;
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		ensureOpen();
		if (position >= size)
			return -1;
		int want = (int) Math.min(dst.remaining(), size - position);
		if (want == 0)
			return 0;
		byte[] bytes;
		int offset;
		if (dst.hasArray()) {
			bytes = dst.array();
			offset = dst.arrayOffset() + dst.position();
		} else {
			bytes = new byte[want];
			offset = 0;
		}
		int got;
		try (InputStream in = ranges.read(position, position + want - 1)) {
			got = in.readNBytes(bytes, offset, want);
		}
		if (got != want)
			throw new IOException("A read of " + want + " bytes at " + position + " gave " + got);
		if (dst.hasArray())
			dst.position(dst.position() + got);
		else
			dst.put(bytes, 0, got);
		position += got;
		return got;
	}

	@Override
	public int write(ByteBuffer src) {
		throw new NonWritableChannelException();
	}

	@Override
	public long position() throws IOException {
		ensureOpen();
		return position;
	}

	@Override
	public SeekableByteChannel position(long newPosition) throws IOException {
		ensureOpen();
		if (newPosition < 0)
			throw new IllegalArgumentException("A position before the start: " + newPosition);
		position = newPosition;
		return this;
	}

	@Override
	public long size() throws IOException {
		ensureOpen();
		return size;
	}

	@Override
	public SeekableByteChannel truncate(long newSize) {
		throw new NonWritableChannelException();
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public void close() {
		if (!open)
			return;
		open = false;
		closed.run();
	}

	private void ensureOpen() throws ClosedChannelException {
		if (!open)
			throw new ClosedChannelException();
	}
}
