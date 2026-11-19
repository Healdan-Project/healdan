// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.File;
import java.nio.file.Files;

/** When the start-up tool refuses to start an install, before it changes anything (spec section 8.3). */
public class StartGuardTest {

    public static void main(String[] args) throws Exception {
        recognisesAConfThatNamesMariaDb();
        decides();
        saysWhatToDoAndWhere();
        seesFilesInOriginalsFolder();
        Check.done("StartGuardTest");
    }

    private static void recognisesAConfThatNamesMariaDb() {
        Check.isTrue(StartGuard.namesMariaDb("jdbc:mariadb://db:3306/logicaldoc", "mariadb"),
                "a MariaDB install's URL and dbms");
        Check.isTrue(StartGuard.namesMariaDb("jdbc:mysql://db/x", null), "a MySQL URL alone");
        Check.isTrue(StartGuard.namesMariaDb(null, "MariaDB"), "the dbms alone, whatever its case");
        Check.isTrue(!StartGuard.namesMariaDb("jdbc:hsqldb:mem:logicaldoc", "hsqldb"),
                "the image's defaults, which a fresh conf volume holds");
        Check.isTrue(!StartGuard.namesMariaDb("jdbc:postgresql://db:5432/logicaldoc", "postgresql"),
                "a conf volume the start-up tool has written since the move");
        Check.isTrue(!StartGuard.namesMariaDb(null, null), "neither key");
    }

    private static void decides() {
        decide(true, false, null, true, true, StartGuard.Verdict.MOVE_FIRST, "DB_HOST still names MariaDB");
        decide(true, true, null, true, true, StartGuard.Verdict.MOVE_FIRST, "an empty PostgreSQL beside MariaDB's conf");
        decide(true, true, false, false, true, StartGuard.Verdict.MOVE_AGAIN, "a move that failed part-way");
        decide(false, true, false, false, false, StartGuard.Verdict.MOVE_AGAIN,
                "a move that failed part-way, beside a fresh conf volume");
        decide(true, true, true, false, true, StartGuard.Verdict.START, "the first start after a move");
        decide(false, true, null, true, true, StartGuard.Verdict.RESTORE_FIRST,
                "the conf volume lost, or a restore whose files came back before its database");
        decide(false, true, null, true, false, StartGuard.Verdict.START, "a fresh install");
        decide(false, true, null, false, true, StartGuard.Verdict.START,
                "an ordinary start, and a restore done in the documented order");
        decide(false, true, true, false, true, StartGuard.Verdict.START, "every start after a move");
        Check.throwsWith("names MariaDB", () -> StartGuard.decide(false, false, null, true, false),
                "a database that didn't answer is decided on only for a conf that names MariaDB");
    }

    private static void decide(boolean mariaDb, boolean reachable, Boolean move, boolean empty, boolean files,
            StartGuard.Verdict expected, String what) {
        Check.eq(expected, StartGuard.decide(mariaDb, reachable, move, empty, files), what);
    }

    private static void saysWhatToDoAndWhere() {
        String moveFirst = StartGuard.message(StartGuard.Verdict.MOVE_FIRST);
        Check.isTrue(moveFirst.contains("MariaDB") && moveFirst.contains(StartGuard.MOVE_PAGE),
                "MOVE_FIRST says the data is in MariaDB, and where the move is described: " + moveFirst);
        String moveAgain = StartGuard.message(StartGuard.Verdict.MOVE_AGAIN);
        Check.isTrue(moveAgain.contains("move-from-mariadb") && moveAgain.contains(StartGuard.MOVE_PAGE),
                "MOVE_AGAIN names the command to run again, and the page: " + moveAgain);
        String restoreFirst = StartGuard.message(StartGuard.Verdict.RESTORE_FIRST);
        Check.isTrue(restoreFirst.contains("restore") && restoreFirst.contains(StartGuard.RESTORE_PAGE),
                "RESTORE_FIRST says to restore the database first, and where: " + restoreFirst);
        for (String message : new String[] { moveFirst, moveAgain, restoreFirst })
            Check.isTrue(!message.contains("jdbc:") && !message.toLowerCase().contains("password"),
                    "no JDBC URL or password: " + message);
        Check.throwsWith("START", () -> StartGuard.message(StartGuard.Verdict.START), "START has no message");
    }

    private static void seesFilesInOriginalsFolder() throws Exception {
        File parent = Files.createTempDirectory("startguard").toFile();
        File original = new File(parent, "docs");
        Check.isTrue(!StartGuard.holdsFiles(original), "a missing folder holds nothing");
        Check.isTrue(original.mkdir(), "made Original's folder");
        Check.isTrue(!StartGuard.holdsFiles(original), "an empty folder holds nothing, as a fresh install's");
        Check.isTrue(new File(original, ".healdan-backend").createNewFile(), "made a storage marker");
        Check.isTrue(StartGuard.holdsFiles(original), "a storage marker alone is an install's");
    }
}
