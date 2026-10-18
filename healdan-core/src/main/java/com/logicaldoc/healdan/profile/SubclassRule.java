package com.logicaldoc.healdan.profile;

import java.util.function.BiPredicate;

/**
 * The one rule for a Subclass that doesn't belong to the effective Class, for
 * every route that isn't refusing it (spec §6.6, §6.7; plan R16, R16a, R21):
 * an inherited one is cleared; a local one that the operation made foreign (a
 * move, a restore, a Class change passed down from a folder above, an edit
 * that leaves it as it was) is dropped together with its local mark. Either
 * way the object then takes the parent's Subclass as an inherited value if it
 * belongs, or none. Only a Subclass the user supplies in a create or an edit
 * is refused, and that is decided before this rule. A store that isn't an edit
 * keeps a local Subclass, as it keeps every local mark (plan, R17).
 */
final class SubclassRule {

	/** What becomes of the Subclass */
	enum Outcome {
		/** It stays: it belongs, or it is local and the operation keeps marks */
		KEEP,
		/** It takes the parent's Subclass, which belongs, as an inherited value */
		INHERIT,
		/** It is cleared, as an inherited value */
		CLEAR
	}

	private SubclassRule() {
	}

	/**
	 * @param subclass the object's Subclass
	 * @param local true if the object's Subclass is local
	 * @param docClass the object's effective Class
	 * @param inherited the Subclass of the folder above
	 * @param dropLocal true if the operation may drop a local Subclass
	 * @param belongs tells if a Subclass belongs to a Class
	 * @return what becomes of the Subclass; when it isn't {@link Outcome#KEEP}
	 *         and the Subclass was local, its mark goes
	 */
	static Outcome settle(String subclass, boolean local, String docClass, String inherited, boolean dropLocal,
			BiPredicate<String, String> belongs) {
		if (blank(subclass) || belongs.test(subclass, docClass))
			return Outcome.KEEP;
		if (local && !dropLocal)
			return Outcome.KEEP;
		return !blank(inherited) && belongs.test(inherited, docClass) ? Outcome.INHERIT : Outcome.CLEAR;
	}

	/**
	 * @param value an attribute value
	 * @return its text, null if it is blank
	 */
	static String text(Object value) {
		return value == null || value.toString().isEmpty() ? null : value.toString();
	}

	private static boolean blank(String value) {
		return value == null || value.isEmpty();
	}
}
