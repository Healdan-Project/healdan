// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.util.spring.Context;

/**
 * Deletes held copies for good once their purge date has come (spec section 8). Task 11's {@code StorageStartup} runs
 * it daily, not in the first 10 minutes after a start; tests call {@link #run()}.
 * <p>
 * Each copy is purged holding its file's lock ({@link FileLocks}, call 22), with its checks inside it:
 * <ul>
 * <li>its hold row is read again, since a save or delete may have held the location afresh after the list of due
 * copies was made;</li>
 * <li>an S3 object that a row points at again, on the same backend, is left, and its hold dropped: the mover uploaded
 * the file under the same key (spec section 6.2);</li>
 * <li>an S3 object whose file the mover is moving now is left, its hold kept for the next purge;</li>
 * <li>a local copy is deleted only from the backend's {@code .held/}.</li>
 * </ul>
 * The delete itself runs holding the lock too, so that no save or delete holds the location afresh between the checks
 * and it; an S3 backend bounds that delete's whole call ({@code S3Backend.purgeHeld}), so a bucket that stalls holds
 * the lock for seconds, not minutes.
 * <p>
 * S3 can't tell a missing bucket from a missing object, and a delete of a missing object succeeds, so each backend is
 * checked once per run first, and one that is failing or unreachable is skipped with its rows kept for the next run. A
 * backend that can't be reached by a delete later in the run is skipped from then on, the same way.
 */
@Component
public class Purge {

	private static final Logger log = LoggerFactory.getLogger(Purge.class);

	/** Whether a file is being moved by the mover now; none until Task 11 sets it */
	private volatile BiPredicate<Long, String> inTransfer = (docId, resource) -> false;

	/**
	 * @return the bean
	 */
	public static Purge get() {
		return Context.get(Purge.class);
	}

	/**
	 * @param inTransfer given a held copy's doc id and resource, whether the mover is moving that file now. Task 11's
	 *        {@code StorageStartup} sets it to {@code Mover.moving} before it starts the purge.
	 */
	public void setInTransfer(BiPredicate<Long, String> inTransfer) {
		this.inTransfer = inTransfer;
	}

	/**
	 * Purges every held copy that is due. One that can't be purged is logged and kept for the next run.
	 */
	public void run() {
		Date now = new Date();
		Map<Long, Boolean> healthy = new HashMap<>();
		int purged = 0;
		for (HeldCopy copy : HeldCopies.get().due(now))
			try {
				if (purge(copy, now, healthy))
					purged++;
			} catch (RuntimeException e) {
				log.warn("The held copy of document {}'s {} on storage backend {} can't be purged: {}", copy.docId(),
						copy.resource(), copy.backendId(), e.getMessage(), e);
			}
		if (purged > 0)
			log.info("The purge deleted {} held copies", purged);
	}

	/**
	 * Purges one held copy if it is still due, as the list of due copies may be old by the time the file's lock is
	 * got.
	 *
	 * @return whether the copy was deleted
	 */
	boolean purge(HeldCopy copy) {
		return purge(copy, new Date(), new HashMap<>());
	}

	private boolean purge(HeldCopy copy, Date now, Map<Long, Boolean> healthy) {
		Backends.Entry entry = usable(copy.backendId(), healthy);
		if (entry == null)
			return false;
		try (FileLocks.Held lock = FileLocks.lock(copy.docId(), copy.resource())) {
			Optional<HeldCopy> current = HeldCopies.get().find(copy.backendId(), copy.location());
			if (current.isEmpty() || current.get().purge().after(now))
				return false; // dropped, or held afresh, since the list was made
			if (entry.s3()) {
				Optional<StoredFile> row = StoredFiles.get().find(copy.docId(), copy.resource());
				if (row.isPresent() && row.get().backendId() == copy.backendId()) {
					log.info("Document {}'s {} on storage backend {} is in use again: it is not purged", copy.docId(),
							copy.resource(), copy.backendId());
					HeldCopies.get().drop(copy.backendId(), copy.location());
					return false;
				}
				if (inTransfer.test(copy.docId(), copy.resource())) {
					log.debug("Document {}'s {} is being moved: its held copy is purged at the next run",
							copy.docId(), copy.resource());
					return false;
				}
			}
			try {
				entry.backend().purgeHeld(copy.location());
			} catch (BackendUnreachableException e) {
				// The rest of the backend's due copies are left for the next run, not each waiting out a timeout
				healthy.put(copy.backendId(), false);
				log.warn("Storage backend {} can't be reached, so the purge keeps its held copies for the next run: {}",
						copy.backendId(), e.getMessage());
				return false;
			} catch (IOException e) {
				log.warn("The held copy of document {}'s {} on storage backend {} can't be deleted: {}", copy.docId(),
						copy.resource(), copy.backendId(), e.getMessage());
				return false;
			}
			HeldCopies.get().drop(copy.backendId(), copy.location());
			log.info("Purged the held copy of document {}'s {} on storage backend {}", copy.docId(), copy.resource(),
					copy.backendId());
			return true;
		}
	}

	/**
	 * @return the backend, checked once in a run; null if it can't be built or fails its check, which is logged once
	 */
	private static Backends.Entry usable(long backendId, Map<Long, Boolean> healthy) {
		Boolean known = healthy.get(backendId);
		if (known != null && !known)
			return null;
		try {
			Backends.Entry entry = Backends.get().entry(backendId);
			if (known == null) {
				entry.backend().check();
				healthy.put(backendId, true);
			}
			return entry;
		} catch (BackendFailingException e) {
			healthy.put(backendId, false);
			log.warn("Storage backend {} isn't used by the purge, which keeps its held copies for the next run: {}",
					backendId, e.getMessage());
			return null;
		}
	}
}
