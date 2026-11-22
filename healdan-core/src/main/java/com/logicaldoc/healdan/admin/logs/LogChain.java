// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A log's files, open for one read (Admin app ruling P5-R27): the files it rolled over to, oldest first, then its
 * current file, each with the size it had when it was opened, which the read goes no further than.
 * <p>
 * A file's identity is the start of a digest (SHA-256, 16 bytes) of its first line, which is its first entry's start
 * line with its time to the millisecond: fixed once that line is written, and the same when the log rolls over and
 * the file is renamed. A first line longer than {@link #IDENTITY_BYTES} is taken by that many of its bytes, which are
 * fixed too. The current file has no identity until its first line is whole; a file it rolled over to always has one.
 * The files it rolled over to come in the order the log's configuration gives them ({@link LogFiles}).
 */
final class LogChain implements Closeable {

	/** The most bytes of a file's first line its identity takes */
	static final int IDENTITY_BYTES = 4096;

	/** Opens a log's file to read it: a seam, so that a test can fail one */
	interface Opener {
		FileChannel open(Path path) throws IOException;
	}

	/**
	 * One of the log's files.
	 *
	 * @param path where it is
	 * @param channel the open file, or null for a current file that isn't there
	 * @param size its size when it was opened
	 * @param identity its identity, or null for a current file with no whole line yet
	 * @param current whether it's the current file
	 */
	record Part(Path path, FileChannel channel, long size, String identity, boolean current) {
	}

	private final List<Part> parts;

	private LogChain(List<Part> parts) {
		this.parts = List.copyOf(parts);
	}

	/**
	 * Opens a log's files. A file that isn't there, or goes before it's opened, is left out; the current file is
	 * always the last part.
	 *
	 * @param current the current file
	 * @param rolled the files it may have rolled over to, the oldest first
	 * @param opener what opens them
	 * @throws IOException if a file that's there can't be read
	 */
	static LogChain open(Path current, List<Path> rolled, Opener opener) throws IOException {
		List<Part> parts = new ArrayList<>();
		try {
			for (Path file : rolled) {
				Part part = Files.isRegularFile(file) ? part(file, false, opener) : null;
				if (part != null)
					parts.add(part);
			}
			Part last = Files.isRegularFile(current) ? part(current, true, opener) : null;
			parts.add(last != null ? last : new Part(current, null, 0, null, true));
		} catch (IOException | RuntimeException e) {
			close(parts);
			throw e;
		}
		return new LogChain(parts);
	}

	private static Part part(Path path, boolean current, Opener opener) throws IOException {
		FileChannel channel;
		try {
			channel = opener.open(path);
		} catch (NoSuchFileException e) {
			return null;
		}
		try {
			long size = channel.size();
			return new Part(path, channel, size, identity(channel, size, current), current);
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	/**
	 * @return the file's identity; null for a current file whose first line isn't whole yet
	 */
	private static String identity(FileChannel channel, long size, boolean current) throws IOException {
		ByteBuffer start = ByteBuffer.allocate((int) Math.min(size, IDENTITY_BYTES));
		while (start.hasRemaining() && channel.read(start, start.position()) >= 0) {
			// Until the buffer is full or the file ends
		}
		int length = start.position();
		int line = length;
		for (int i = 0; i < length; i++)
			if (start.get(i) == '\n') {
				line = i;
				break;
			}
		if (line == length && length < IDENTITY_BYTES && current)
			return null;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(start.array(), 0, line);
			return HexFormat.of().formatHex(digest.digest(), 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is missing", e);
		}
	}

	/**
	 * @return the parts, oldest first, the current file last
	 */
	List<Part> parts() {
		return parts;
	}

	/**
	 * @return the index of the current file, the last
	 */
	int current() {
		return parts.size() - 1;
	}

	/**
	 * @param position a position
	 * @return the index of the part the position names, or -1 when none has its file's identity, as when the file
	 *         was deleted as the log rolled over again
	 */
	int find(LogPosition position) {
		if (position.isCurrentStart())
			return current();
		for (int i = parts.size() - 1; i >= 0; i--)
			if (position.identity().equals(parts.get(i).identity()))
				return i;
		return -1;
	}

	/**
	 * @return the position of an offset in a part
	 */
	LogPosition position(int part, long offset) {
		String identity = parts.get(part).identity();
		return identity != null ? new LogPosition(identity, offset) : LogPosition.currentStart();
	}

	@Override
	public void close() throws IOException {
		close(parts);
	}

	private static void close(List<Part> parts) throws IOException {
		IOException failure = null;
		for (Part part : parts) {
			if (part.channel() == null)
				continue;
			try {
				part.channel().close();
			} catch (IOException e) {
				failure = e;
			}
		}
		if (failure != null)
			throw failure;
	}
}
