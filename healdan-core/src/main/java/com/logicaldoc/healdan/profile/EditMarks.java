package com.logicaldoc.healdan.profile;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers, for the thread, that a document is being edited by a user: an
 * update (seen by the hook before CE writes anything) or a check-in. Only the
 * store that follows may change the document's local marks (plan, R17); every
 * other store re-stamps non-local values alone. A mark that was never consumed
 * (the operation failed first) expires.
 */
public final class EditMarks {

	private static final long EXPIRY_NANOS = 30_000_000_000L;

	private static final ThreadLocal<Map<Long, Long>> PENDING = ThreadLocal.withInitial(HashMap::new);

	private EditMarks() {
	}

	/**
	 * @param documentId the document about to be edited
	 */
	public static void mark(long documentId) {
		PENDING.get().put(documentId, System.nanoTime());
	}

	/**
	 * @param documentId a document being stored
	 * @return true, once, if an edit of it was announced a moment ago
	 */
	public static boolean consume(long documentId) {
		Map<Long, Long> pending = PENDING.get();
		Long since = pending.remove(documentId);
		if (pending.isEmpty())
			PENDING.remove();
		return since != null && System.nanoTime() - since < EXPIRY_NANOS;
	}
}
