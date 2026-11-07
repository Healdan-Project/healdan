// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A place file bytes go (spec section 10). Keys are CE's relative paths, with no prefix, or one of the backend's two
 * own objects a key may name, at its top level: {@code .healdan-check} and {@code .healdan-test-<name>}. The marker,
 * {@code .healdan-backend}, is written only when a backend is made.
 */
public interface Backend {

	long id();

	/** Saves: whole, verified, in place. */
	void put(String key, Path file, byte[] md5) throws IOException;

	/** The mover: verified, not yet in place (call 20). */
	Staged stage(String key, Path file, byte[] md5) throws IOException;

	/** @throws java.io.FileNotFoundException when missing */
	InputStream get(String key) throws IOException;

	/** For ranges. @throws java.io.FileNotFoundException when missing */
	SeekableByteChannel open(String key) throws IOException;

	/** @return the size, -1 when missing */
	long size(String key) throws IOException;

	/** Missing is fine. */
	void delete(String key) throws IOException;

	/**
	 * S3's Test only (the recording seed walks the folder itself). Local: never lists a dot name (the marker, held
	 * copies, the check object, Test's objects). S3: every key under the prefix, the backend's own objects included,
	 * so Test finds its own.
	 */
	List<String> list(String prefix, int max) throws IOException;

	/**
	 * Cheap: the marker, or reachability.
	 *
	 * @throws BackendUnreachableException when the backend can't be reached (an outage, not a refusal)
	 * @throws BackendFailingException when it can be reached and is the wrong place, or refuses
	 */
	void check() throws BackendFailingException;

	/** Local: the key's path under {@code .held/} if there. S3: empty. */
	Optional<String> held(String key);

	/**
	 * Local: moves the key under {@code .held/}, replacing an older held copy of it, and returns the location.
	 * S3: leaves it, sends nothing, and returns the key, whether or not the object is there.
	 *
	 * @throws java.io.FileNotFoundException if a local backend has no such file
	 */
	String hold(String key) throws IOException;

	/** Deletes a held copy, by the location {@link #hold} or {@link #held} gave. */
	void purgeHeld(String location) throws IOException;
}
