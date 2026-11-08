// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.Backends;
import com.logicaldoc.healdan.storage.FileLocks;
import com.logicaldoc.healdan.storage.HealdanStorage;
import com.logicaldoc.healdan.storage.Held;
import com.logicaldoc.healdan.storage.HeldCopies;
import com.logicaldoc.healdan.storage.ReadCache;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.backend.BackendCommon;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.Staged;
import com.logicaldoc.healdan.storage.crypto.FileCipher;

/**
 * One file's move to its Store's backend (spec section 7, calls 16, 20 and 21):
 * <ol>
 * <li>the row is read again, and the file is left if it is no longer misplaced toward the target, as when its Store
 * turned local after the pass began;</li>
 * <li>the file is read from where it is, decrypted if that backend is encrypted, into a copy in staging, noting the
 * row's write id;</li>
 * <li>the copy is staged on the target, encrypted if the target is, with its MD5: an S3 target's object is at its key,
 * which saves never write, and read back with {@code HEAD}; a local target's file waits beside its key under a
 * temporary name, where a save's bytes can't be overwritten;</li>
 * <li>holding the file's lock, in a short transaction of its own: if the row is still that write, on the source, and
 * its Store's backend still the target, the staged copy is committed and the row pointed at the target; once that
 * transaction has committed, and still holding the lock, the source is held (spec section 8), or, when the source is
 * staging, its copy renamed aside for the read cache (question 9), so a commit that fails leaves the source as it
 * was; otherwise the staged copy is discarded, and an S3 object kept only if a hold or a row names it.</li>
 * </ol>
 * The transfer takes and releases the file's lock once before it reads the file, as the purge checks and deletes a
 * held S3 object holding it: a purge that found the file in no transfer finishes its delete before the upload, and
 * every later one finds the file in transfer (call 22).
 * The file's lock is taken only for the commit, and never while a transaction still open wrote the row: that one's
 * row lock lasts until it ends, and it may want the lock again, so the commit waits for nothing a CE transaction holds.
 * A failure counts an attempt and sets the next try ({@link RetrySchedule}); a backend that can't be reached or used
 * counts none and pauses its transfers until its retest.
 */
final class Transfer {

	private static final Logger log = LoggerFactory.getLogger(Transfer.class);

	/** How a transfer ended */
	enum Outcome {
		/** The row points at the target, and the source is held */
		MOVED(true),
		/** The row, or its Store's backend, changed since it was listed: a pass looks at it again */
		CHANGED(true),
		/** A transaction still open wrote the file's row: it is left until another pass */
		PENDING(false),
		/** A backend can't be reached or used: its transfers wait for its retest */
		PAUSED(false),
		/** It failed, which its row records for the retry */
		FAILED(false),
		/** The mover stopped during it, which counts nothing */
		STOPPED(false);

		private final boolean progress;

		Outcome(boolean progress) {
			this.progress = progress;
		}

		/** @return whether another pass may find something to do because of it */
		boolean progress() {
			return progress;
		}
	}

	private final Mover mover;

	private final StoredFiles files;

	private final long docId;

	private final String resource;

	private final long target;

	private final String key;

	/**
	 * @param listed the row as the pass listed it
	 * @param target its Store's backend when the pass listed it
	 */
	Transfer(Mover mover, StoredFile listed, long target) {
		this.mover = mover;
		this.files = mover.files();
		this.docId = listed.docId();
		this.resource = listed.resource();
		this.target = target;
		this.key = ((HealdanStorage) Store.get()).keyOf(docId, resource);
	}

	Outcome run() {
		// The file is in transfer already: wait out a purge's delete under way of the object this may upload again
		FileLocks.lock(docId, resource).close();
		StoredFile row = files.find(docId, resource).orElse(null);
		if (row == null || row.stuck() || row.backendId() == target || files.storeBackend(row.storeId()) != target)
			return Outcome.CHANGED;
		long source = row.backendId();
		if (!mover.health().available(source) || !mover.health().available(target))
			return Outcome.PAUSED;
		if (FileLocks.writtenByOpenTransaction(docId, resource))
			return Outcome.PENDING;
		Backends.Entry from;
		Backends.Entry to;
		try {
			from = mover.backends().entry(source);
		} catch (BackendFailingException e) {
			return pause(source, e);
		}
		try {
			to = mover.backends().entry(target);
		} catch (BackendFailingException e) {
			return pause(target, e);
		}

		log.info("Moving document {}'s {} from storage backend {} to {}", docId, resource, source, target);
		List<Path> copies = new ArrayList<>();
		Staged staged = null;
		long using = source;
		try {
			Path folder = Mover.workFolder();
			Path plain = copy(folder, copies);
			MessageDigest plainMd5 = BackendCommon.md5();
			read(from, row, plain, plainMd5);

			using = target;
			Path upload = plain;
			byte[] md5 = plainMd5.digest();
			if (to.encrypted()) {
				upload = copy(folder, copies);
				md5 = encrypt(to.cipher(), plain, upload);
			}
			staged = to.backend().stage(key, upload, md5);
			if (to.s3())
				verify(to, upload);

			Path[] aside = new Path[1];
			Outcome outcome = commit(row, source, staged, aside);
			if (outcome != Outcome.MOVED) {
				discard(to, staged);
				log.info("The move of document {}'s {} to storage backend {} was dropped: the file changed meanwhile",
						docId, resource, target);
				return outcome;
			}
			staged = null;
			log.info("Moved document {}'s {} from storage backend {} to {}", docId, resource, source, target);
			mover.committed.accept(row);
			if (aside[0] != null)
				cache(to, row, aside[0]);
			return outcome;
		} catch (BackendFailingException e) {
			discard(to, staged);
			return pause(using, e);
		} catch (IOException | RuntimeException e) {
			discard(to, staged);
			if (mover.stopping())
				return Outcome.STOPPED;
			return failed(row, e);
		} finally {
			for (Path copy : copies)
				deleteQuietly(copy);
		}
	}

	/** @return a new file in the mover's folder, to be deleted when the transfer ends */
	private static Path copy(Path folder, List<Path> copies) {
		Path copy = folder.resolve(UUID.randomUUID() + ".tmp");
		copies.add(copy);
		return copy;
	}

	private String associated() {
		return FileCipher.associated(docId, resource);
	}

	/**
	 * Reads the file, plain, into a copy, hashing it as it goes, and checks its size against the row's plaintext size.
	 */
	private void read(Backends.Entry from, StoredFile row, Path plain, MessageDigest digest) throws IOException {
		try (InputStream raw = open(from);
				InputStream in = from.cipher() == null ? raw : from.cipher().decrypting(raw, associated());
				InputStream hashed = new DigestInputStream(in, digest)) {
			Files.copy(hashed, plain, StandardCopyOption.REPLACE_EXISTING);
		}
		long size = Files.size(plain);
		if (size != row.size())
			throw new IOException("Document " + docId + "'s " + resource + " reads as " + size
					+ " bytes on storage backend " + row.backendId() + ", not the " + row.size() + " its row records");
	}

	/** @return the file's bytes on its backend; a local one missing from its place is read from .held/ */
	private InputStream open(Backends.Entry from) throws IOException {
		try {
			return from.backend().get(key);
		} catch (FileNotFoundException e) {
			// A restored database finds a file deleted after its backup in .held/ (spec section 6.3)
			if (from.backend() instanceof LocalBackend folder && folder.held(key).isPresent())
				return folder.getHeld(key);
			throw e;
		}
	}

	/** @return the MD5 of the ciphertext written */
	private byte[] encrypt(FileCipher cipher, Path plain, Path encrypted) throws IOException {
		MessageDigest digest = BackendCommon.md5();
		try (OutputStream out = new DigestOutputStream(Files.newOutputStream(encrypted), digest)) {
			cipher.encrypt(plain, out, associated());
		}
		return digest.digest();
	}

	/** Reads an S3 upload's size back (call 16): S3 checked its Content-MD5 already */
	private void verify(Backends.Entry to, Path upload) throws IOException {
		long expected = Files.size(upload);
		long found = to.backend().size(key);
		if (found != expected)
			throw new IOException("Document " + docId + "'s " + resource + " reads back from storage backend " + target
					+ " as " + found + " bytes, not the " + expected + " uploaded");
	}

	/**
	 * Commits the staged copy holding the file's lock (call 20), in a transaction of its own, then, once that has
	 * committed and still holding the lock, lets the source go: a save can't land in between, and a failed commit
	 * leaves the source where it was.
	 *
	 * @param aside set to the staged copy renamed aside, when the source is staging
	 */
	private Outcome commit(StoredFile row, long source, Staged staged, Path[] aside) throws IOException {
		try (FileLocks.Held lock = FileLocks.lock(docId, resource)) {
			mover.atCommit.accept(row);
			if (FileLocks.writtenByOpenTransaction(docId, resource))
				return Outcome.PENDING;
			Outcome outcome;
			try {
				outcome = mover.own().execute(status -> commitHolding(row, source, staged));
			} catch (UncheckedIOException e) {
				throw e.getCause();
			}
			if (outcome == Outcome.MOVED) {
				if (source == Staging.ID)
					aside[0] = setAside();
				else
					hold(source);
			}
			return outcome;
		}
	}

	private Outcome commitHolding(StoredFile row, long source, Staged staged) {
		Optional<StoredFile> now = files.find(docId, resource);
		if (now.isEmpty() || now.get().write() != row.write() || now.get().backendId() != source
				|| files.storeBackend(now.get().storeId()) != target)
			return Outcome.CHANGED;
		if (!files.moved(docId, resource, row.write(), target))
			return Outcome.CHANGED;
		try {
			staged.commit();
		} catch (IOException e) {
			// The row's update rolls back with it
			throw new UncheckedIOException(e);
		}
		return Outcome.MOVED;
	}

	/**
	 * Renames the staged file aside within staging, an instant step holding the file's lock, so a save that lands in
	 * staging next is never the copy the cache takes (question 9).
	 *
	 * @return where it went; null if it couldn't be moved, which leaves it in staging
	 */
	private Path setAside() {
		Path root = Staging.folder();
		Path file = root.resolve(key);
		try {
			Path aside = Mover.workFolder().resolve(UUID.randomUUID() + ".uploaded");
			Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE);
			prune(file.getParent(), root);
			return aside;
		} catch (NoSuchFileException e) {
			log.warn("Document {}'s {} wasn't in staging when its upload was committed", docId, resource);
		} catch (IOException e) {
			log.warn("Document {}'s {} stays in staging after its upload: {}", docId, resource, e.getMessage());
		}
		return null;
	}

	/** Removes a folder and its parents while they are empty, stopping before {@code stop} */
	private static void prune(Path folder, Path stop) {
		for (Path dir = folder; dir != null && !dir.equals(stop) && dir.startsWith(stop); dir = dir.getParent())
			try {
				Files.delete(dir);
			} catch (IOException e) {
				// Not empty, or gone: nothing more to do
				return;
			}
	}

	/**
	 * Holds the source copy (spec section 8), once the row's move has committed, so a failure here loses nothing: the
	 * copy stays where it was.
	 */
	private void hold(long source) {
		try {
			if (!Held.hold(source, key, docId, resource))
				log.warn("Document {}'s {} is on storage backend {} now, and its copy on storage backend {} couldn't be "
						+ "held: it stays where it was", docId, resource, target, source);
		} catch (RuntimeException e) {
			log.error("Document {}'s {} is on storage backend {} now, and its copy on storage backend {} couldn't be "
					+ "recorded as held ({}): no purge will delete it", docId, resource, target, source,
					e.getMessage(), e);
		}
	}

	/** Hands the staged copy set aside to the read cache, as the write's entry, if the row is still that write */
	private void cache(Backends.Entry to, StoredFile row, Path aside) {
		try {
			Optional<StoredFile> now = files.find(docId, resource);
			if (to.s3() && now.isPresent() && now.get().backendId() == target && now.get().write() == row.write())
				ReadCache.get().adopt(target, docId, resource, row.write(), aside);
		} catch (IOException | RuntimeException e) {
			log.warn("Document {}'s {} wasn't put in the read cache after its upload: {}", docId, resource,
					e.getMessage());
		} finally {
			deleteQuietly(aside);
		}
	}

	/**
	 * Discards a staged copy that wasn't committed, which no row ever pointed at. An S3 object at the key stays if a
	 * hold names it, as the purge deletes it in its time, or the row points there.
	 */
	private void discard(Backends.Entry to, Staged staged) {
		if (staged == null)
			return;
		try {
			if (to.s3() && named()) {
				log.debug("Document {}'s object on storage backend {} is kept: a hold or its row names it", docId,
						target);
				return;
			}
			staged.discard();
		} catch (IOException | RuntimeException e) {
			log.warn("The copy of document {}'s {} that wasn't committed stays on storage backend {}: {}", docId,
					resource, target, e.getMessage());
		}
	}

	/** @return whether a hold on the target names the key, or the row points at the target */
	private boolean named() {
		if (HeldCopies.get().find(target, key).isPresent())
			return true;
		Optional<StoredFile> now = files.find(docId, resource);
		return now.isPresent() && now.get().backendId() == target;
	}

	/**
	 * Pauses a backend's transfers: one that can't be reached, built or used is the backend's failure, not the
	 * file's, so it counts no attempt.
	 */
	private Outcome pause(long backendId, BackendFailingException e) {
		if (e instanceof BackendUnreachableException)
			mover.health().reportUnreachable(backendId, message(e));
		else
			mover.health().reportFailing(backendId, message(e));
		return Outcome.PAUSED;
	}

	/**
	 * Records a failed move in a short transaction of its own, holding the file's lock: an attempt more, its error, and
	 * the next try, or stuck at the tenth.
	 */
	private Outcome failed(StoredFile row, Exception e) {
		String error = message(e);
		Date now = new Date();
		Integer recorded;
		try (FileLocks.Held lock = FileLocks.lock(docId, resource)) {
			if (FileLocks.writtenByOpenTransaction(docId, resource))
				return Outcome.PENDING;
			recorded = mover.own().execute(status -> files.recordFailure(docId, resource, row.write(), error,
					failures -> RetrySchedule.nextTry(failures, now)));
		}
		int failures = recorded == null ? 0 : recorded;
		if (failures == 0)
			// A save or delete since the transfer began: the failure was the old write's
			return Outcome.CHANGED;
		if (RetrySchedule.stuck(failures))
			log.warn("Document {}'s {} is stuck: its move to storage backend {} failed {} times, the last with: {}",
					docId, resource, target, failures, error);
		else
			log.warn("Moving document {}'s {} to storage backend {} failed ({} of {}), and is tried again from {}: {}",
					docId, resource, target, failures, RetrySchedule.STUCK_AT, RetrySchedule.nextTry(failures, now),
					error);
		return Outcome.FAILED;
	}

	private static String message(Exception e) {
		Throwable cause = e instanceof UncheckedIOException && e.getCause() != null ? e.getCause() : e;
		return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			log.debug("{} wasn't deleted: {}", path, e.getMessage());
		}
	}
}
