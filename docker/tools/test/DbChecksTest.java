// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/** What the start-up tool refuses and warns of in the PostgreSQL server and database it's given. */
public class DbChecksTest {

    public static void main(String[] args) {
        refusesAServerOlderThan18();
        notesAMajorNewerThanTheOneTested();
        refusesADatabaseThatIsNotUtf8();
        warnsOfACollationThatDoesNotSortByCodePoint();
        refusesARoleThatCannotCreateTables();
        Check.eq("50s", DbChecks.LOCK_TIMEOUT, "MariaDB's default lock wait, as PostgreSQL shows it");
        Check.done("DbChecksTest");
    }

    private static void refusesAServerOlderThan18() {
        String refusal = DbChecks.tooOld(170006);
        Check.isTrue(refusal != null && refusal.contains("PostgreSQL 18 or later") && refusal.contains("17"),
                "PostgreSQL 17 is refused, naming both versions: " + refusal);
        Check.eq(null, DbChecks.tooOld(180004), "PostgreSQL 18 is taken");
        Check.eq(null, DbChecks.tooOld(190001), "PostgreSQL 19 is taken");
    }

    private static void notesAMajorNewerThanTheOneTested() {
        String note = DbChecks.untested(190001);
        Check.isTrue(note != null && note.contains("19") && note.contains("hasn't been tested"),
                "PostgreSQL 19 is noted as untested: " + note);
        Check.eq(null, DbChecks.untested(180004), "PostgreSQL 18 is the one tested");
    }

    private static void refusesADatabaseThatIsNotUtf8() {
        String refusal = DbChecks.wrongEncoding("LATIN1");
        Check.isTrue(refusal != null && refusal.contains("UTF8") && refusal.contains("LATIN1"),
                "a LATIN1 database is refused, naming both encodings: " + refusal);
        Check.eq(null, DbChecks.wrongEncoding("UTF8"), "a UTF8 database is taken");
    }

    private static void warnsOfACollationThatDoesNotSortByCodePoint() {
        Check.eq(null, DbChecks.otherLocale("b", "C", "C.UTF-8"), "the stack's built-in C.UTF-8 sorts by code point");
        // Plain C sorts by code point too: spec section 5.5 rules it out only as the stack's choice.
        Check.eq(null, DbChecks.otherLocale("c", "C", null), "libc's C sorts by code point");
        for (String[] locale : new String[][] { { "c", "en_US.utf8", null }, { "i", "C", "en-GB" } }) {
            String warning = DbChecks.otherLocale(locale[0], locale[1], locale[2]);
            Check.isTrue(warning != null && warning.contains("sort") && warning.contains("folder"),
                    locale[0] + " " + locale[1] + " " + locale[2] + " warns of the order and of the folder lookups: "
                            + warning);
        }
    }

    private static void refusesARoleThatCannotCreateTables() {
        String refusal = DbChecks.cannotCreate(false, "healdan", "public");
        Check.isTrue(refusal != null && refusal.contains("healdan") && refusal.contains("public")
                && refusal.contains("create"), "a role without CREATE is refused, naming the role and the schema: "
                        + refusal);
        Check.eq(null, DbChecks.cannotCreate(true, "healdan", "public"), "a role with CREATE is taken");
    }
}
