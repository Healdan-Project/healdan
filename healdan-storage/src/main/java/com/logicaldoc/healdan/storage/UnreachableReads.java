// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.function.Function;

import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.util.StorageUnreachableException;

/**
 * Turns a {@link BackendUnreachableException} thrown by a stream's or channel's own {@code read}, after the call that
 * opened it returned, into the {@link StorageUnreachableException} that opening throws (spec section 6.5). Nothing else
 * is changed. The conversion is made once, so that its warning is logged once for a stream however many reads fail.
 */
final class UnreachableReads {

	private UnreachableReads() {
	}

	/** @return the stream, converting the outage of its reads */
	static InputStream wrap(InputStream in, Function<BackendUnreachableException, StorageUnreachableException> convert) {
		return new Stream(in, convert);
	}

	/** @return the channel, converting the outage of its reads */
	static SeekableByteChannel wrap(SeekableByteChannel channel,
			Function<BackendUnreachableException, StorageUnreachableException> convert) {
		return new Channel(channel, convert);
	}

	/** Converts an outage the first time, and throws that exception again */
	private static final class Converter {

		private final Function<BackendUnreachableException, StorageUnreachableException> convert;

		private StorageUnreachableException converted;

		Converter(Function<BackendUnreachableException, StorageUnreachableException> convert) {
			this.convert = convert;
		}

		StorageUnreachableException of(BackendUnreachableException e) {
			if (converted == null)
				converted = convert.apply(e);
			return converted;
		}
	}

	private static final class Stream extends FilterInputStream {

		private final Converter converter;

		Stream(InputStream in, Function<BackendUnreachableException, StorageUnreachableException> convert) {
			super(in);
			this.converter = new Converter(convert);
		}

		@Override
		public int read() throws IOException {
			try {
				return in.read();
			} catch (BackendUnreachableException e) {
				throw converter.of(e);
			}
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			try {
				return in.read(b, off, len);
			} catch (BackendUnreachableException e) {
				throw converter.of(e);
			}
		}

		@Override
		public long skip(long n) throws IOException {
			try {
				return in.skip(n);
			} catch (BackendUnreachableException e) {
				throw converter.of(e);
			}
		}
	}

	private static final class Channel implements SeekableByteChannel {

		private final SeekableByteChannel channel;

		private final Converter converter;

		Channel(SeekableByteChannel channel, Function<BackendUnreachableException, StorageUnreachableException> convert) {
			this.channel = channel;
			this.converter = new Converter(convert);
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			try {
				return channel.read(dst);
			} catch (BackendUnreachableException e) {
				throw converter.of(e);
			}
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			return channel.write(src);
		}

		@Override
		public long position() throws IOException {
			return channel.position();
		}

		@Override
		public SeekableByteChannel position(long newPosition) throws IOException {
			channel.position(newPosition);
			return this;
		}

		@Override
		public long size() throws IOException {
			try {
				return channel.size();
			} catch (BackendUnreachableException e) {
				throw converter.of(e);
			}
		}

		@Override
		public SeekableByteChannel truncate(long size) throws IOException {
			channel.truncate(size);
			return this;
		}

		@Override
		public boolean isOpen() {
			return channel.isOpen();
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}
	}
}
