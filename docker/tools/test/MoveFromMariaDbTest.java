// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * The move command's pure parts (spec section 9): its options and environment, its refusals' wording, identifier
 * quoting, sequence matching, row keys, and how a database failure is worded without its message.
 */
public class MoveFromMariaDbTest {

    public static void main(String[] args) throws Exception {
        options();
        sourceFromTheEnvironment();
        refusesASourceAtAnotherVersion();
        decidesOnTheTarget();
        pairsWithOneSchemaVersion();
        closingFailsNothing();
        quotesNames();
        matchesSequencesWhateverTheCase();
        namesASequencesTable();
        givesARowsKeyWithoutContent();
        describesFailuresWithoutTheirMessage();
        scrubsPasswords();
        saysWhatToDoAboutRefusedValues();
        reports();
        versions();
        readsDatesAsMariaDbsOwnText();
        talliesWhatWasRead();
        showsTheSourcesTimeZone();
        alwaysReachesAnExitStatus();
        listsOrphansByPlace();
        readsTheTargetsSequencesOnce();
        Check.done("MoveFromMariaDbTest");
    }

    private static void readsDatesAsMariaDbsOwnText() {
        Check.eq("cast(`ld_date` as char)", MoveFromMariaDb.sourceColumn("ld_date", true),
                "a date and time is read as MariaDB's own text, not the driver's");
        Check.eq("`ld_name`", MoveFromMariaDb.sourceColumn("ld_name", false), "anything else as it is");
        Check.eq("cast(`a``b` as char)", MoveFromMariaDb.sourceColumn("a`b", true), "quoted inside the cast");
    }

    private static void talliesWhatWasRead() {
        Check.isTrue(MoveFromMariaDb.hashed(new MoveValues.Target("character varying", 255)), "text is hashed");
        Check.isTrue(MoveFromMariaDb.hashed(new MoveValues.Target("bigint", 0)), "whole numbers are");
        Check.isTrue(MoveFromMariaDb.hashed(new MoveValues.Target("timestamp without time zone", 0)), "dates are");
        Check.isTrue(!MoveFromMariaDb.hashed(new MoveValues.Target("double precision", 0)),
                "floating point isn't: MariaDB's text of it is its own");
        Check.isTrue(!MoveFromMariaDb.hashed(new MoveValues.Target("real", 0)), "nor real");

        Check.eq("select count(*), count(`ld_id`), sum(crc32(`ld_id`)), count(cast(`ld_date` as char)),"
                + " sum(crc32(cast(`ld_date` as char))), count(`ld_x`) from `ld_history`",
                MoveFromMariaDb.tallyQuery("ld_history", List.of("ld_id", "ld_date", "ld_x"),
                        new boolean[] { false, true, false }, new boolean[] { true, true, false }),
                "MariaDB's tally: its rows, every column's values, and each hashed column's CRC-32s");
        Check.eq("select count(*), count(`ld_left`) from `ld_note`", MoveFromMariaDb.tallyQuery("ld_note",
                List.of("ld_left"), new boolean[] { false }, new boolean[] { false }),
                "a floating-point column is counted, though not hashed");

        MoveFromMariaDb.Tally ours = new MoveFromMariaDb.Tally(5);
        ours.add(0, "abc", true);
        ours.add(1, 42L, true);
        ours.add(2, new java.math.BigDecimal("12.50"), true);
        ours.add(3, null, true);
        ours.add(4, 1.5d, false);
        // MariaDB: select crc32('abc'), crc32('42'), crc32('12.50')
        Check.eq(891568578L, ours.crcs[0], "CRC-32 of the text, as MariaDB's crc32 gives it");
        Check.eq(841265288L, ours.crcs[1], "of a whole number's digits, as crc32(42) gives it");
        Check.eq(3061501937L, ours.crcs[2], "of a decimal's plain digits, as crc32 of 12.50 gives it");
        Check.eq(1, (int) ours.counts[2], "a decimal counts");
        Check.eq(0, (int) ours.counts[3], "a null counts for nothing, as count(c) has it");
        Check.eq(1, (int) ours.counts[4], "a floating-point value counts");
        Check.eq(0L, ours.crcs[4], "and isn't hashed");
        ours.add(0, "\u00e9t\u00e9 \uD83C\uDF89", true);
        Check.eq(2, (int) ours.counts[0], "values add up");
        ours.rows = 2;

        MoveFromMariaDb.Tally theirs = new MoveFromMariaDb.Tally(5);
        theirs.rows = 2;
        theirs.counts[0] = ours.counts[0];
        theirs.crcs[0] = ours.crcs[0];
        theirs.counts[1] = 1;
        theirs.crcs[1] = ours.crcs[1] + 1;
        theirs.counts[2] = 2;
        theirs.crcs[2] = ours.crcs[2];
        theirs.counts[4] = 1;
        theirs.crcs[4] = 12345;
        List<String> columns = List.of("ld_name", "ld_id", "ld_amount", "ld_x", "ld_left");
        boolean[] hashed = { true, true, true, true, false };
        Check.eq(List.of("column ld_id: the move read 1 values, MariaDB counts 1, and their CRC-32s differ",
                "column ld_amount: the move read 1 values, MariaDB counts 2"),
                ours.differences(theirs, columns, hashed), "where they differ, by column, without values; a column"
                        + " that isn't hashed is compared by its count alone");
        theirs.counts[4] = 0;
        Check.isTrue(ours.differences(theirs, columns, hashed).contains("column ld_left: the move read 1 values,"
                + " MariaDB counts 0"), "a value read where MariaDB holds a null, in a floating-point column");
        theirs.rows = 3;
        Check.eq("the move read 2 rows, and MariaDB counts 3", ours.differences(theirs, columns, hashed).get(0),
                "the rows first");
        Check.eq(List.of(), ours.differences(ours, columns, hashed), "the same tally");
    }

    private static void showsTheSourcesTimeZone() {
        Check.eq("time zone UTC (the server's, +00:00)", MoveFromMariaDb.zone("SYSTEM", "UTC", "00:00:00"),
                "the server's zone, as the old stack's MariaDB had it");
        Check.eq("time zone EST (the server's, -05:00)", MoveFromMariaDb.zone("SYSTEM", "EST", "-05:00:00"),
                "west of UTC");
        Check.eq("time zone Europe/Rome (+01:00)", MoveFromMariaDb.zone("Europe/Rome", "UTC", "01:00:00"),
                "a zone the session was given");
        Check.eq("time zone +05:30 (+05:30)", MoveFromMariaDb.zone("+05:30", "UTC", "05:30:00"), "an offset");
    }

    private static void alwaysReachesAnExitStatus() {
        Check.eq(0, MoveFromMariaDb.guarded(() -> 0), "a body that moved");
        Check.eq(1, MoveFromMariaDb.guarded(() -> 1), "a body that refused");
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(printed, true, java.nio.charset.StandardCharsets.UTF_8));
        int status;
        try {
            status = MoveFromMariaDb.guarded(() -> {
                throw new OutOfMemoryError("Java heap space, while holding Quarterly Report");
            });
        } finally {
            System.setErr(err);
        }
        String line = printed.toString(java.nio.charset.StandardCharsets.UTF_8);
        Check.eq(1, status, "an Error still gives the exit status 1");
        Check.eq("[move] ERROR: java.lang.OutOfMemoryError" + System.lineSeparator(), line,
                "and its line names its class only");
        java.io.ByteArrayOutputStream refusal = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(refusal, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            Check.eq(1, MoveFromMariaDb.run(new String[] { "--nothing" }, Map.of()), "run refuses an unknown"
                    + " argument before it reads anything");
        } finally {
            System.setErr(err);
        }
        Check.isTrue(refusal.toString(java.nio.charset.StandardCharsets.UTF_8).startsWith("[move] ERROR: unknown"
                + " argument '--nothing'"), "with its line: " + refusal);
    }

    private static void listsOrphansByPlace() {
        MoveFromMariaDb.ForeignKey key = new MoveFromMariaDb.ForeignKey("ld_document_acl", "fk_acl_doc",
                "FOREIGN KEY (ld_docid, ld_tenantid) REFERENCES ld_document(ld_id, ld_tenantid)",
                List.of("ld_docid", "ld_tenantid"), "ld_document", List.of("ld_id", "ld_tenantid"));
        Check.eq(" from \"ld_document_acl\" r where r.\"ld_docid\" is not null and r.\"ld_tenantid\" is not null"
                + " and not exists (select 1 from \"ld_document\" x where x.\"ld_id\" = r.\"ld_docid\""
                + " and x.\"ld_tenantid\" = r.\"ld_tenantid\")", MoveFromMariaDb.orphans(key),
                "the rows whose columns all have values and refer to no row, as PostgreSQL checks the key");
        Check.eq(20, MoveFromMariaDb.ORPHANS, "at most 20 rows a key");
    }

    private static void readsTheTargetsSequencesOnce() {
        Map<String, Long> increments = new java.util.LinkedHashMap<>();
        increments.put("hd_trash_hd_id_seq", 1L);
        increments.put("ld_user_seq", 50L);
        MoveFromMariaDb.TargetSequences sequences = new MoveFromMariaDb.TargetSequences(increments,
                List.of(new MoveFromMariaDb.Identity("hd_trash_hd_id_seq", "hd_trash", "hd_id")));
        Check.eq(List.of("ld_user_seq"), sequences.plain(), "the sequences that aren't an identity column's");
    }

    private static void options() throws Exception {
        Check.eq(MoveValues.Nul.REFUSE, MoveFromMariaDb.nulOption(new String[0]), "a NUL is refused by default");
        Check.eq(MoveValues.Nul.REPLACE, MoveFromMariaDb.nulOption(new String[] { "--replace-nul" }),
                "--replace-nul replaces it");
        Check.eq("--replace-nul", MoveFromMariaDb.REPLACE_NUL, "the option's name");
        Check.eq("12", MoveFromMariaDb.SOURCE_VERSION, "the last mariadb/ version");
        try {
            MoveFromMariaDb.nulOption(new String[] { "--replace-nulls" });
            Check.fail("an unknown argument is refused");
        } catch (MoveFromMariaDb.MoveException e) {
            Check.isTrue(e.getMessage().contains("--replace-nulls") && e.getMessage().contains("--replace-nul,"),
                    "the refusal names the argument and the one option: " + e.getMessage());
        }
    }

    private static void sourceFromTheEnvironment() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("DB_PASSWORD", " secret ");
        MoveFromMariaDb.Source defaults = MoveFromMariaDb.source(env);
        Check.eq(new MoveFromMariaDb.Source("mariadb", 3306, "logicaldoc", "logicaldoc", " secret "), defaults,
                "the overlay's service, with DB_PASSWORD as it is, untrimmed");
        Check.eq("jdbc:mariadb://mariadb:3306/logicaldoc", defaults.url(), "the URL holds no user or password");
        Check.isTrue(!defaults.describe().contains("secret"), "nor does its description: " + defaults.describe());

        env.put("DB_NAME", "dms");
        env.put("DB_USER", "dmsuser");
        Check.eq(new MoveFromMariaDb.Source("mariadb", 3306, "dms", "dmsuser", " secret "),
                MoveFromMariaDb.source(env), "the name and user default to DB_NAME and DB_USER");

        env.put("MOVE_SOURCE_HOST", " old-db.lan ");
        env.put("MOVE_SOURCE_PORT", "3307");
        env.put("MOVE_SOURCE_NAME", "olddms");
        env.put("MOVE_SOURCE_USER", "reader");
        env.put("MOVE_SOURCE_PASSWORD", "other");
        Check.eq(new MoveFromMariaDb.Source("old-db.lan", 3307, "olddms", "reader", "other"),
                MoveFromMariaDb.source(env), "MOVE_SOURCE_ variables win, trimmed but for the password");

        env.put("MOVE_SOURCE_NAME", "  ");
        env.put("MOVE_SOURCE_PASSWORD", "");
        Check.eq(new MoveFromMariaDb.Source("old-db.lan", 3307, "dms", "reader", " secret "),
                MoveFromMariaDb.source(env), "a blank MOVE_SOURCE_ variable counts as unset");

        refusedSource(Map.of("DB_PASSWORD", "x", "MOVE_SOURCE_PORT", "33o6"), "MOVE_SOURCE_PORT", "a port");
        refusedSource(Map.of("DB_PASSWORD", "x", "MOVE_SOURCE_PORT", "70000"), "MOVE_SOURCE_PORT", "a port too big");
        refusedSource(Map.of("DB_PASSWORD", "x", "MOVE_SOURCE_HOST", "db/x?user=root"), "MOVE_SOURCE_HOST",
                "a host that would change the URL");
        refusedSource(Map.of("DB_PASSWORD", "x", "MOVE_SOURCE_NAME", "dms?allowLoadLocalInfile=true"),
                "MOVE_SOURCE_NAME", "a name that would add a URL option");
        refusedSource(Map.of("DB_PASSWORD", ""), "MOVE_SOURCE_PASSWORD", "no password");
        Check.eq("[fd00::1]", MoveFromMariaDb.source(Map.of("DB_PASSWORD", "x", "MOVE_SOURCE_HOST", "[fd00::1]"))
                .host(), "an IPv6 address in brackets");
    }

    private static void refusedSource(Map<String, String> env, String names, String what) {
        try {
            MoveFromMariaDb.source(env);
            Check.fail(what + " is refused");
        } catch (MoveFromMariaDb.MoveException e) {
            Check.isTrue(e.getMessage().contains(names), what + ": the refusal names " + names + ": " + e.getMessage());
        }
    }

    private static void refusesASourceAtAnotherVersion() {
        Check.eq(null, MoveFromMariaDb.historyRefusal(true, "12", List.of()), "version 12, every row succeeded");
        String older = MoveFromMariaDb.historyRefusal(true, "11", List.of());
        Check.isTrue(older != null && older.contains("version 11") && older.contains("mariadb-final")
                && older.contains("start it once"), "an older source is told to upgrade and start once: " + older);
        String failed = MoveFromMariaDb.historyRefusal(true, "12", List.of("9"));
        Check.isTrue(failed != null && failed.contains("version 9") && failed.contains("mariadb-final"),
                "a failed migration is refused, naming it: " + failed);
        String none = MoveFromMariaDb.historyRefusal(false, null, List.of());
        Check.isTrue(none != null && none.contains("hd_schema_history") && none.contains("MOVE_SOURCE_NAME")
                && none.contains("mariadb-final"), "a source with no history: " + none);
        String empty = MoveFromMariaDb.historyRefusal(true, null, List.of());
        Check.isTrue(empty != null && empty.contains("(none)"), "an empty history: " + empty);
        String columns = MoveFromMariaDb.columnsRefusal("table ld_tenant lacks column ld_pagesquotathr");
        Check.isTrue(columns.contains("ld_tenant lacks column ld_pagesquotathr") && columns.contains("mariadb-final")
                && columns.contains("Upgrading") && columns.contains("Nothing was changed"),
                "a source lacking a column: " + columns);
    }

    private static void decidesOnTheTarget() {
        Check.eq(MoveFromMariaDb.TargetState.EMPTY, MoveFromMariaDb.targetState(null, false, 0, 0), "an empty schema");
        Check.eq(MoveFromMariaDb.TargetState.NOT_EMPTY, MoveFromMariaDb.targetState(null, false, 3, 0),
                "tables and no marker: an install's, or anything else");
        Check.eq(MoveFromMariaDb.TargetState.NOT_EMPTY, MoveFromMariaDb.targetState(null, true, 120, 0),
                "an install the start-up tool made");
        Check.eq(MoveFromMariaDb.TargetState.UNFINISHED, MoveFromMariaDb.targetState(false, false, 120, 0),
                "an unfinished move, whatever it made");
        Check.eq(MoveFromMariaDb.TargetState.UNFINISHED, MoveFromMariaDb.targetState(false, false, 1, 0),
                "a marker with no row is an unfinished move (Db.move reads it as false)");
        Check.eq(MoveFromMariaDb.TargetState.FINISHED, MoveFromMariaDb.targetState(true, true, 120, 0),
                "a finished move, which made the start-up tool's marker as it finished");
        Check.eq(MoveFromMariaDb.TargetState.NOT_EMPTY, MoveFromMariaDb.targetState(false, false, 120, 1),
                "an unfinished move beside a view a move never makes");
        // A move makes docker_init only in the transaction that finishes it, so an unfinished marker beside it is
        // something else's doing, on a database Healdan may have started on: never emptied
        Check.eq(MoveFromMariaDb.TargetState.STARTED, MoveFromMariaDb.targetState(false, true, 120, 0),
                "an unfinished move beside the start-up tool's marker");
        Check.eq(MoveFromMariaDb.TargetState.STARTED, MoveFromMariaDb.targetState(false, true, 2, 0),
                "a marker with no row beside the start-up tool's");
        Check.eq(MoveFromMariaDb.TargetState.STARTED, MoveFromMariaDb.targetState(false, true, 120, 1),
                "and beside a view too");
        String finished = MoveFromMariaDb.targetRefusal(MoveFromMariaDb.TargetState.FINISHED, 1, 0, 0);
        Check.isTrue(finished.contains("finished move") && finished.contains("changed nothing")
                && finished.contains("docker compose up -d"), "a finished move's refusal: " + finished);
        String notEmpty = MoveFromMariaDb.targetRefusal(MoveFromMariaDb.TargetState.NOT_EMPTY, 56, 40, 0);
        Check.isTrue(notEmpty.contains("56 tables, 40 sequences,") && notEmpty.contains("Nothing was changed")
                && notEmpty.contains("pg-data"), "a schema the move didn't make: " + notEmpty);
        for (String refusal : List.of(finished, notEmpty))
            Check.isTrue(refusal.contains("the db service's volume, pg-data, or on a stack with host folders empty"
                    + " PostgreSQL's folder") && refusal.contains("#starting-the-move-over"),
                    "emptying the target is told for a volume and for a host folder: " + refusal);
        Check.isTrue(MoveFromMariaDb.targetRefusal(MoveFromMariaDb.TargetState.NOT_EMPTY, 1, 1, 2)
                .contains("1 table, 1 sequence and 2 other relations"), "counts in words");
        String started = MoveFromMariaDb.targetRefusal(MoveFromMariaDb.TargetState.STARTED, 120, 40, 0);
        Check.isTrue(started.contains("didn't finish") && started.contains("docker_init")
                && started.contains("won't empty a database Healdan has been started on")
                && started.contains("changed nothing"), "a started database's refusal: " + started);
        Check.throwsWith("isn't refused", () -> MoveFromMariaDb.targetRefusal(MoveFromMariaDb.TargetState.EMPTY, 0, 0,
                0), "an empty target isn't refused");
    }

    /**
     * The version the move makes its target's tables at (the review's item 7, option B): the one that pairs with a
     * version-12 source, and no later one, which the app's first start applies to the moved rows
     */
    private static void pairsWithOneSchemaVersion() {
        String why = "TARGET_VERSION is the version of Healdan's tables that pairs with a source at version "
                + MoveFromMariaDb.SOURCE_VERSION + ": the twelve scripts it ran, translated, and V13, the widening of"
                + " LogicalDOC's text columns. A later migration (V14 or after) changes nothing here: the move stops at"
                + " 13, and the app's first start applies the later ones to the moved rows, as on any upgrade. So"
                + " there's nothing to decide when one ships, and the constant stays 13";
        Check.eq("13", MoveFromMariaDb.TARGET_VERSION, why);
        Check.isTrue(MoveFromMariaDbTest.class.getClassLoader().getResource("healdan/db/migration/postgresql/V"
                + MoveFromMariaDb.TARGET_VERSION + "__widen_logicaldoc_text.sql") != null,
                "the widening is the WAR's script at TARGET_VERSION. " + why);
        Check.eq(null, MoveFromMariaDb.versionRefusal("13"), "a target at 13 goes on");
        String other = MoveFromMariaDb.versionRefusal("14");
        Check.isTrue(other != null && other.contains("version 14") && other.contains("version 13")
                && other.contains("version 12") && other.contains("Run the move again"),
                "a target at another version stops the move: " + other);
    }

    /** Closing a connection after the finish never turns a finished move into a failed one */
    private static void closingFailsNothing() {
        Connection failing = (Connection) Proxy.newProxyInstance(MoveFromMariaDbTest.class.getClassLoader(),
                new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    if (method.getName().equals("close"))
                        throw new SQLException("closing the session of Quarterly Report", "08006");
                    throw new UnsupportedOperationException(method.getName());
                });
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(printed, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            MoveFromMariaDb.close(failing, "the target");
        } catch (RuntimeException e) {
            Check.fail("a failure to close throws nothing: " + e);
        } finally {
            System.setErr(err);
        }
        Check.eq("[move] WARNING: closing the connection to the target failed, which changes nothing the move did: the"
                + " connection to the database failed (SQLSTATE 08006, java.sql.SQLException)" + System.lineSeparator(),
                printed.toString(java.nio.charset.StandardCharsets.UTF_8), "it's a warning, without the driver's"
                        + " message");
    }

    private static void quotesNames() {
        Check.eq("`ld_user`", MoveFromMariaDb.quoteMaria("ld_user"), "MariaDB: backticks");
        Check.eq("`a``b`", MoveFromMariaDb.quoteMaria("a`b"), "MariaDB: a backtick doubled");
        Check.eq("`ld_user_SEQ`", MoveFromMariaDb.quoteMaria("ld_user_SEQ"), "MariaDB: the case kept");
        Check.eq("\"ld_user\"", MoveFromMariaDb.quotePg("ld_user"), "PostgreSQL: double quotes");
        Check.eq("\"a\"\"b\"", MoveFromMariaDb.quotePg("a\"b"), "PostgreSQL: a double quote doubled");
        Check.eq("\"LD_User\"", MoveFromMariaDb.quotePg("LD_User"), "PostgreSQL: the case kept");
    }

    private static void matchesSequencesWhateverTheCase() {
        MoveFromMariaDb.SequenceMatch match = MoveFromMariaDb.matchSequences(
                List.of("ld_user_seq", "hd_matter_seq", "hd_new_seq"),
                List.of("ld_user_SEQ", "hd_matter_SEQ", "ld_old_SEQ"));
        Map<String, String> pairs = new java.util.LinkedHashMap<>();
        pairs.put("ld_user_seq", "ld_user_SEQ");
        pairs.put("hd_matter_seq", "hd_matter_SEQ");
        Check.eq(pairs, match.pairs(), "each target sequence to the source's of the same name, whatever the case");
        Check.eq(List.of("hd_new_seq"), match.targetOnly(), "a target sequence the source lacks");
        Check.eq(List.of("ld_old_SEQ"), match.sourceOnly(), "a source sequence the target lacks");
        Check.eq(List.of(), match.problems(), "no problem");

        MoveFromMariaDb.SequenceMatch twice = MoveFromMariaDb.matchSequences(List.of("ld_user_seq"),
                List.of("ld_user_SEQ", "ld_user_seq", "ld_x_SEQ", "LD_X_SEQ"));
        Check.isTrue(twice.problems().size() == 1 && twice.problems().get(0).contains("ld_user_SEQ, ld_user_seq"),
                "two source sequences the target's matches is a problem: " + twice.problems());
        Check.eq(Map.of(), twice.pairs(), "and neither is carried");
        Check.eq(List.of("ld_x_SEQ", "LD_X_SEQ"), twice.sourceOnly(), "two only the source has are listed");
    }

    private static void namesASequencesTable() {
        Check.eq("ld_user", MoveFromMariaDb.sequenceTable("ld_user_seq"), "PostgreSQL's spelling");
        Check.eq("ld_user", MoveFromMariaDb.sequenceTable("ld_user_SEQ"), "MariaDB's spelling");
        Check.eq(null, MoveFromMariaDb.sequenceTable("hibernate_sequence"), "another name");
        Check.eq(null, MoveFromMariaDb.sequenceTable("_seq"), "nothing before _seq");
    }

    private static void givesARowsKeyWithoutContent() {
        Check.eq("ld_id=42", MoveFromMariaDb.key(List.of("ld_id"), List.of(42L)), "a numeric key");
        Check.eq("ld_docid=5, ld_name=emailsubject",
                MoveFromMariaDb.key(List.of("ld_docid", "ld_name"), List.of(5L, "emailsubject")),
                "an attribute's name is a key as it is");
        Check.eq("hd_name=storage.installId", MoveFromMariaDb.key(List.of("hd_name"), List.of("storage.installId")),
                "a setting's name");
        Check.eq("ld_id=18446744073709551615",
                MoveFromMariaDb.key(List.of("ld_id"), List.of(new BigInteger("18446744073709551615"))),
                "an unsigned bigint");
        String tag = MoveFromMariaDb.key(List.of("ld_tag", "ld_tenantid"), List.of("Smith v Jones 2026", 1L));
        Check.isTrue(tag.startsWith("ld_tag=(text not shown, sha256 ") && tag.endsWith("), ld_tenantid=1")
                && !tag.contains("Smith") && !tag.contains("Jones"), "text that isn't a plain name isn't shown: " + tag);
        String digest = tag.substring("ld_tag=(text not shown, sha256 ".length(), tag.indexOf(')'));
        Check.eq(16, digest.length(), "16 hex digits of its SHA-256");
        Check.isTrue(digest.matches("[0-9a-f]{16}"), "in lower case, as MariaDB's sha2(column, 256) begins: " + digest);
        String email = MoveFromMariaDb.key(List.of("ld_username"), List.of("ann@example.com"));
        Check.isTrue(!email.contains("ann") && !email.contains("example"), "an address isn't shown: " + email);
        String nul = MoveFromMariaDb.key(List.of("ld_tag"), List.of("a\u0000b"));
        Check.isTrue(!nul.contains("\u0000") && !nul.contains("a\u0000b"), "nor a value with a NUL: " + nul);
        String longName = MoveFromMariaDb.key(List.of("ld_name"), List.of("x".repeat(65)));
        Check.isTrue(!longName.contains("xxxx"), "nor a name too long to be one: " + longName);
        Check.eq("ld_id=null", MoveFromMariaDb.key(List.of("ld_id"), Arrays.asList((Object) null)), "a null");
        Check.eq("ld_x=(a Double, not shown)", MoveFromMariaDb.key(List.of("ld_x"), List.of(1.5d)),
                "a number that isn't whole isn't shown");
        Check.eq("#17 (ld_docid=5, ld_tenantid=1)",
                MoveFromMariaDb.keyless(17, List.of("ld_docid", "ld_tenantid"), List.of(5L, 1L)),
                "a table with no primary key: the row's place in the read, and its ids");
        Check.eq("#3", MoveFromMariaDb.keyless(3, List.of(), List.of()), "and with no id column, its place alone");
        Check.isTrue(Arrays.equals(new int[] { 0, 2 }, MoveFromMariaDb.idColumns(List.of("ld_docid", "ld_tag",
                "LD_TENANTID"))), "id columns are named as ids, whatever the case");
        Check.isTrue(Arrays.equals(new int[] { 1, 0 }, MoveFromMariaDb.keyColumns(List.of("ld_name", "ld_docid"),
                List.of("ld_docid", "ld_name"))), "a primary key's columns in its own order");
        Check.eq(null, MoveFromMariaDb.keyColumns(List.of("ld_name"), List.of()), "no primary key");
        Check.eq(null, MoveFromMariaDb.keyColumns(List.of("ld_name"), List.of("ld_id")), "a key column not copied");
    }

    private static void describesFailuresWithoutTheirMessage() {
        // As pgjdbc makes it with logServerErrorDetail=false: the detail, which holds the values, is left out of the
        // message, and the described failure leaves out the message too
        ServerErrorMessage server = new ServerErrorMessage("SERROR\u0000C23505\u0000Mduplicate key value violates"
                + " unique constraint \"ld_user_pkey\"\u0000DKey (ld_id)=(42) already exists.\u0000nld_user_pkey"
                + "\u0000tld_user\u0000");
        PSQLException duplicate = new PSQLException(server, false);
        String described = MoveFromMariaDb.described(duplicate);
        Check.eq("a key the table already holds (SQLSTATE 23505, constraint ld_user_pkey,"
                + " org.postgresql.util.PSQLException)", described, "a duplicate, by its constraint");
        Check.isTrue(!described.contains("42") && !described.contains("Key ("), "no value: " + described);

        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 insert into ld_user values ('ann',"
                + " 'secret-value') was aborted", null, 0, new int[0], null);
        batch.setNextException(duplicate);
        String batched = MoveFromMariaDb.described(batch);
        Check.isTrue(batched.contains("SQLSTATE 23505") && batched.contains("constraint ld_user_pkey")
                && batched.contains("java.sql.BatchUpdateException"), "a batch, by the statement's failure: " + batched);
        Check.isTrue(!batched.contains("ann") && !batched.contains("secret-value"),
                "never the batch's message, which quotes the statement with its values: " + batched);

        ServerErrorMessage tooLong = new ServerErrorMessage("SERROR\u0000C22001\u0000Mvalue too long for type"
                + " character varying(255)\u0000");
        Check.eq("text longer than its column takes (SQLSTATE 22001, org.postgresql.util.PSQLException)",
                MoveFromMariaDb.described(new PSQLException(tooLong, false)), "too long");

        SQLException lost = new SQLNonTransientConnectionException("(conn=12) Connection reset, while reading row"
                + " 'Quarterly Report'", "08000");
        String connection = MoveFromMariaDb.described(lost);
        Check.eq("the connection to the database failed (SQLSTATE 08000,"
                + " java.sql.SQLNonTransientConnectionException)", connection, "a lost connection");
        Check.eq("the database call failed (no SQLSTATE, java.sql.SQLException)",
                MoveFromMariaDb.described(new SQLException("invalid input syntax for type integer: \"Report\"")),
                "no SQLSTATE, and still no message");
        Check.eq("the database refused it", MoveFromMariaDb.plain("XX000"), "anything else");
        Check.eq("a reference to a row that isn't there", MoveFromMariaDb.plain("23503"), "a foreign key");
    }

    private static void scrubsPasswords() {
        Check.eq("connect as logicaldoc with ******** failed",
                MoveFromMariaDb.scrub("connect as logicaldoc with pgb-move-test failed", List.of("pgb-move-test")),
                "a password is never printed");
        Check.eq("text", MoveFromMariaDb.scrub("text", Arrays.asList(null, "")), "nothing to scrub");
    }

    private static void saysWhatToDoAboutRefusedValues() {
        String nuls = MoveFromMariaDb.refusalSummary(2, 2);
        Check.isTrue(nuls.startsWith("2 values in MariaDB hold a NUL") && nuls.contains("--replace-nul")
                && nuls.contains("the move stopped"), "only NULs: run again with the option: " + nuls);
        String one = MoveFromMariaDb.refusalSummary(1, 1);
        Check.isTrue(one.startsWith("1 value in MariaDB holds a NUL"), "one NUL: " + one);
        String mixed = MoveFromMariaDb.refusalSummary(3, 1);
        Check.isTrue(mixed.startsWith("3 values in MariaDB can't go into PostgreSQL") && mixed.contains("Mend them")
                && mixed.contains("1 holds only a NUL"), "mixed: mend them, and the option covers some: " + mixed);
        String none = MoveFromMariaDb.refusalSummary(1, 0);
        Check.isTrue(!none.contains("--replace-nul"), "no NUL: the option isn't offered: " + none);
    }

    private static void reports() {
        MovePlan.Plan plan = MovePlan.plan(
                Map.of("ld_uniquetag", List.of("ld_tag", "ld_int1"), "ld_old", List.of("ld_id")),
                Map.of("ld_uniquetag", List.of("ld_tag")));
        Check.eq(List.of("Only the source has these, which weren't copied:", "  table ld_old",
                "  column ld_uniquetag.ld_int1", "  sequence ld_old_SEQ"),
                MoveFromMariaDb.sourceOnly(plan, List.of("ld_old_SEQ")), "what only the source has");
        Check.eq(List.of(), MoveFromMariaDb.sourceOnly(MovePlan.plan(Map.of("a", List.of("x")),
                Map.of("a", List.of("x"))), List.of()), "nothing to list");
        List<MoveValues.Where> replaced = new ArrayList<>();
        replaced.add(new MoveValues.Where("ld_document", "ld_id=101", "ld_comment"));
        Check.eq(List.of("A space replaced each NUL in 1 value (--replace-nul):",
                "  table ld_document, row ld_id=101, column ld_comment"), MoveFromMariaDb.replacedReport(replaced),
                "every place a NUL was replaced");
        Check.eq(List.of(), MoveFromMariaDb.replacedReport(List.of()), "none replaced");
    }

    private static void versions() {
        Check.eq("18.1", MoveFromMariaDb.postgresVersion(180001), "PostgreSQL 18.1");
        Check.eq("18.12", MoveFromMariaDb.postgresVersion(180012), "PostgreSQL 18.12");
        Check.eq("11.4.13", MoveFromMariaDb.mariaDbVersion("11.4.13-MariaDB-ubu2404"), "MariaDB, without its build");
        Check.eq("10.11.9", MoveFromMariaDb.mariaDbVersion("10.11.9"), "without a suffix");
    }
}
