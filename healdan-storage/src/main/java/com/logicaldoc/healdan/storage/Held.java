// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.healdan.storage.backend.BackendFailingException;

/**
 * Holds a deleted or replaced copy (spec section 8): a local backend's file moves under its {@code .held/}, replacing
 * an earlier held copy of the same key, and an S3 object stays where it is, with nothing sent. Either way a row in
 * {@code hd_storage_held} says where it is and its purge date, {@code storage.holdDays} from now, which {@link Purge}
 * acts on. Every delete, every save that replaces a copy on another backend, and the mover's source copies go through
 * here.
 */
public final class Held {

	private static final Logger log = LoggerFactory.getLogger(Held.class);

	private static final long DAY_MS = 24L * 3600 * 1000;

	private Held() {
	}

	/**
	 * Holds a file's copy on a backend. Called holding the file's lock ({@link FileLocks}).
	 *
	 * @param backendId the backend that has the copy; 0 for staging
	 * @param key the file's key, in CE's layout
	 * @param docId the document
	 * @param resource its resource
	 * @return true if the copy is held, or wasn't there to hold; false if it couldn't be held and is still in its
	 *         place, which loses nothing
	 */
	public static boolean hold(long backendId, String key, long docId, String resource) {
		Backends.Entry entry;
		try {
			entry = Backends.get().entry(backendId);
		} catch (BackendFailingException e) {
			if (!isS3(backendId)) {
				log.warn("Document {}'s {} can't be held on storage backend {}: {}", docId, resource, backendId,
						e.getMessage());
				return false;
			}
			// S3's hold sends nothing and leaves the object where it is, so one that can't be built holds the same
			log.warn("Storage backend {} can't be used ({}); document {}'s {} is held where it is", backendId,
					e.getMessage(), docId, resource);
			record(backendId, key, docId, resource);
			return true;
		}

		String location;
		try {
			location = entry.backend().hold(key);
		} catch (FileNotFoundException e) {
			log.warn("Document {}'s {} wasn't on storage backend {} to be held", docId, resource, backendId);
			return true;
		} catch (IOException | IllegalArgumentException e) {
			// A local folder that fails its marker, or a stray file whose name is no storage key
			log.warn("Document {}'s {} can't be held on storage backend {}: {}", docId, resource, backendId,
					e.getMessage());
			return false;
		}
		record(backendId, location, docId, resource);
		return true;
	}

	private static boolean isS3(long backendId) {
		try {
			return Backends.get().isS3(backendId);
		} catch (BackendFailingException e) {
			return false;
		}
	}

	private static void record(long backendId, String location, long docId, String resource) {
		Date until = new Date(System.currentTimeMillis() + StorageSettings.get().holdDays() * DAY_MS);
		HeldCopies.get().hold(backendId, location, docId, resource, until);
	}
}
