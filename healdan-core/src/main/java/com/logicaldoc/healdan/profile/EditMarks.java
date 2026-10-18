package com.logicaldoc.healdan.profile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.document.DocumentEvent;

/**
 * Remembers, for the thread, what a hook has decided about a document that the
 * store which follows must act on: that a user is editing it (an update, seen
 * by the hook before CE writes anything, or a check-in that changes the
 * profile), and the local marks a move has dropped. Only the store that
 * follows may change the local marks (plan, R17); every other store re-stamps
 * non-local values alone. A note that is never consumed (the operation failed
 * first) expires, is not taken by a store of another kind, and is purged.
 */
public final class EditMarks {

	private static final long EXPIRY_NANOS = 30_000_000_000L;

	/** The events of the stores that can carry an edit; no transaction can too */
	private static final Set<String> EDIT_EVENTS = Set.of(DocumentEvent.CHANGED.toString(), DocumentEvent.RENAMED.toString(),
			DocumentEvent.CHECKEDIN.toString(), DocumentEvent.STORED.toString());

	private static final ThreadLocal<Map<Long, Long>> EDITS = ThreadLocal.withInitial(HashMap::new);

	private static final ThreadLocal<Map<Long, Dropped>> DROPPED = ThreadLocal.withInitial(HashMap::new);

	private record Dropped(Set<String> attributes, long since) {
	}

	private EditMarks() {
	}

	/**
	 * @param documentId the document about to be edited
	 */
	public static void mark(long documentId) {
		purge(EDITS.get());
		EDITS.get().put(documentId, System.nanoTime());
	}

	/**
	 * @param documentId a document being stored
	 * @param event the event of the store's transaction, null if it has none
	 * @return true, once, if an edit of it was announced a moment ago and this
	 *         store can carry it
	 */
	public static boolean consume(long documentId, String event) {
		Map<Long, Long> pending = EDITS.get();
		purge(pending);
		if (event != null && !event.isEmpty() && !EDIT_EVENTS.contains(event))
			return false;
		return pending.remove(documentId) != null;
	}

	/**
	 * @param documentId the document that is about to move
	 * @param attribute an attribute whose local mark the move has dropped
	 */
	public static void dropLocal(long documentId, String attribute) {
		Map<Long, Dropped> dropped = DROPPED.get();
		dropped.values().removeIf(d -> System.nanoTime() - d.since() > EXPIRY_NANOS);
		dropped.computeIfAbsent(documentId, id -> new Dropped(new HashSet<>(), System.nanoTime())).attributes()
				.add(attribute);
	}

	/**
	 * @param documentId a document being stored after a move
	 * @return the attributes whose local marks the move dropped
	 */
	public static Set<String> takeDropped(long documentId) {
		Dropped dropped = DROPPED.get().remove(documentId);
		return dropped != null && System.nanoTime() - dropped.since() <= EXPIRY_NANOS ? dropped.attributes()
				: Set.of();
	}

	private static void purge(Map<Long, Long> pending) {
		long now = System.nanoTime();
		pending.values().removeIf(since -> now - since > EXPIRY_NANOS);
	}
}
