// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/**
 * The move's check that no counter can hand out an id its table already holds (spec section 9.3, step 7), by the
 * arithmetic of CE's id generator rather than by comparing a sequence's value with the highest id: Hibernate's hilo
 * optimizer (hibernate.id.optimizer=hilo) takes a value v from the sequence and makes the ids v * increment -
 * (increment - 1) to v * increment, so CE's sequences, which step by the increment, 50, never make one id twice. The
 * arithmetic was confirmed on the owner's data on 2026-11-18 (the plan's call 11).
 */
final class MoveCounters {

    private MoveCounters() {
    }

    /**
     * The lowest id CE's generator can make next from a sequence: next * increment - (increment - 1)
     *
     * @param next the value the sequence hands out next, which Hibernate's hilo takes only when it's 1 or more
     * @param increment Hibernate's increment size for the sequence, 1 or more
     * @throws IllegalArgumentException for a next value or increment below 1, or ids beyond a long
     */
    static long lowestNextId(long next, int increment) {
        if (increment < 1)
            throw new IllegalArgumentException("a sequence's increment is 1 or more, not " + increment);
        if (next < 1)
            throw new IllegalArgumentException("Hibernate's hilo takes a sequence's next value only when it's 1 or"
                    + " more, not " + next);
        try {
            return Math.subtractExact(Math.multiplyExact(next, (long) increment), increment - 1L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("the ids from the sequence's next value " + next + " are beyond the"
                    + " range of a long", e);
        }
    }

    /** @return whether no id the sequence can still make is at or below the table's highest */
    static boolean safe(long next, int increment, long highestId) {
        return lowestNextId(next, increment) > highestId;
    }
}
