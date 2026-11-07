// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** A place file bytes go (spec section 10): keys are CE's relative paths, with no prefix */
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

	/** S3's Test only (the recording seed walks the folder itself). Never lists the backend's own objects. */
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
	 * S3: leaves it, and returns the key.
	 *
	 * @throws java.io.FileNotFoundException if there is no such file
	 */
	String hold(String key) throws IOException;

	/** Deletes a held copy, by the location {@link #hold} or {@link #held} gave. */
	void purgeHeld(String location) throws IOException;
}
