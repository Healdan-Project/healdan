// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/**
 * The move's check that no counter can hand out an id its table already holds, by the arithmetic of CE's id
 * generator: Hibernate's hilo optimizer makes increment ids from each value of a sequence (spec section 9.3, step 7).
 * The figures are the owner's data's, read on 2026-11-18 (the plan's call 11).
 */
public class MoveCountersTest {

    public static void main(String[] args) {
        ownersData();
        atTheEdges();
        refusesWhatNoSequenceGives();
        Check.done("MoveCountersTest");
    }

    private static void ownersData() {
        Check.eq(7_504_951L, MoveCounters.lowestNextId(150100, 50), "ld_document's next value after the move");
        Check.isTrue(MoveCounters.safe(150100, 50, 5_007_455), "MariaDB's next_not_cached_value is safe");
        Check.eq(4951L, MoveCounters.lowestNextId(100, 50), "the first id a fresh install makes");
        Check.isTrue(!MoveCounters.safe(100150, 50, 5_007_455),
                "the value that made the highest id: its ids, 5,007,451 to 5,007,500, include it");
    }

    private static void atTheEdges() {
        Check.isTrue(MoveCounters.safe(100151, 50, 5_007_500), "the value after the one that made the highest id");
        Check.isTrue(!MoveCounters.safe(100151, 50, 5_007_501), "a highest id that value would make");
        Check.eq(10L, MoveCounters.lowestNextId(10, 1), "a step of one");
        Check.isTrue(MoveCounters.safe(10, 1, 9), "a step of one, above the highest");
        Check.isTrue(!MoveCounters.safe(10, 1, 10), "a step of one, at the highest");
        Check.isTrue(MoveCounters.safe(100, 50, 0), "an empty table");
        Check.eq(1L, MoveCounters.lowestNextId(1, 50), "the value 1's ids start at 1");
    }

    private static void refusesWhatNoSequenceGives() {
        Check.throwsWith("increment", () -> MoveCounters.lowestNextId(100, 0), "an increment of zero");
        Check.throwsWith("increment", () -> MoveCounters.safe(100, -50, 0), "a negative increment");
        Check.throwsWith("next", () -> MoveCounters.lowestNextId(0, 50), "a next value of zero");
        Check.throwsWith("beyond", () -> MoveCounters.lowestNextId(Long.MAX_VALUE / 10, 50),
                "a next value whose ids are beyond a long");
    }
}
