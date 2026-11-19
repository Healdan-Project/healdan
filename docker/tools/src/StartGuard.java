// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import com.logicaldoc.healdan.db.HealdanMigrator;

/**
 * The guard against starting an install empty (spec section 8.3). An install that starts on PostgreSQL without its
 * data would get a new, empty schema beside its conf and repository volumes, and a new install id that its storage
 * backends' markers refuse. So before the start-up tool changes anything, in the conf volume or the database, it
 * stops when the install's data is still in MariaDB, when the database holds a move that didn't finish, and when the
 * database is empty beside a repository that already holds an install's files. Decisions, and the two reads of the
 * volumes they need, neither of which writes; DockerInit asks the database.
 */
final class StartGuard {

    /** The Install manual's page on moving an install's data from MariaDB to PostgreSQL, as the app names it */
    static final String MOVE_PAGE = HealdanMigrator.MOVE_PAGE;

    /** The Install manual's section on restoring a backup onto a fresh stack, the database before the app's start */
    static final String RESTORE_PAGE = "https://docs.healdan.com/install/backup-and-restore/#restoring-onto-a-fresh-stack";

    /**
     * The table a move marks its target with, docker_move (started timestamp not null, finished timestamp): one row,
     * whose finished is null until every check has passed
     */
    static final String MOVE_MARKER = "docker_move";

    /**
     * How long a database has to answer when the conf volume names MariaDB, after which it's taken as not answering
     * rather than waited for: the install's DB_HOST may still name its MariaDB server, which never answers as
     * PostgreSQL
     */
    static final int ANSWER_SECONDS = 15;

    enum Verdict {
        START, MOVE_FIRST, MOVE_AGAIN, RESTORE_FIRST
    }

    private StartGuard() {
    }

    /**
     * @param url and dbms as the conf volume's context.properties holds them before this start changes it; either may
     *        be null
     * @return whether they name MariaDB, or MySQL, whose installs never ran Healdan but whose data isn't in PostgreSQL
     *         either
     */
    static boolean namesMariaDb(String url, String dbms) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        String d = dbms == null ? "" : dbms.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("jdbc:mariadb:") || u.startsWith("jdbc:mysql:") || d.equals("mariadb") || d.equals("mysql");
    }

    /**
     * @param confNamesMariaDb the conf volume is a MariaDB install's
     * @param reachable PostgreSQL answered. Only a conf that names MariaDB is decided on without an answer: for any
     *        other the start-up tool waits for the database, and stops when it doesn't answer
     * @param move null when the database has no MOVE_MARKER table, false when it holds an unfinished move, true a
     *        finished one
     * @param empty the database has neither ld_user nor the start-up tool's marker
     * @param repositoryHoldsFiles Original's folder has any entry
     */
    static Verdict decide(boolean confNamesMariaDb, boolean reachable, Boolean move, boolean empty,
            boolean repositoryHoldsFiles) {
        if (!reachable) {
            if (!confNamesMariaDb)
                throw new IllegalArgumentException("a database that didn't answer is decided on only for a conf that"
                        + " names MariaDB");
            return Verdict.MOVE_FIRST;
        }
        if (Boolean.FALSE.equals(move))
            return Verdict.MOVE_AGAIN;
        if (confNamesMariaDb && move == null)
            return Verdict.MOVE_FIRST;
        if (empty && repositoryHoldsFiles)
            return Verdict.RESTORE_FIRST;
        return Verdict.START;
    }

    /** The message for a verdict other than START: what's wrong, what to do, and the page */
    static String message(Verdict verdict) {
        return switch (verdict) {
        case MOVE_FIRST -> "this install's conf volume names MariaDB as its database, so its data is in MariaDB, and"
                + " the PostgreSQL database that DB_HOST, DB_PORT and DB_NAME name didn't answer within "
                + ANSWER_SECONDS + " seconds or holds no finished move. Healdan now runs on PostgreSQL only, so this"
                + " start stopped and changed nothing. Move the data across first with the move-from-mariadb command,"
                + " as Moving from MariaDB to PostgreSQL describes: " + MOVE_PAGE;
        case MOVE_AGAIN -> "the database holds a move from MariaDB that didn't finish: it failed, or was stopped"
                + " part-way. Healdan won't start on it, so this start stopped and changed nothing. Run the"
                + " move-from-mariadb command again, which starts the move over, as Moving from MariaDB to PostgreSQL"
                + " describes: " + MOVE_PAGE;
        case RESTORE_FIRST -> "the database is empty, but the repository volume already holds an install's files. That"
                + " happens when the database's volume is new or was lost, or when a restore brought the files back"
                + " before the database. Starting would make a new, empty install beside them, so this start stopped"
                + " and changed nothing. Restore the database first, as Restoring onto a Fresh Stack describes: "
                + RESTORE_PAGE + ". For a new install, start on empty volumes instead. If this install came from"
                + " MariaDB, move its data across first: " + MOVE_PAGE;
        case START -> throw new IllegalArgumentException("START has no message");
        };
    }

    /**
     * Whether the conf file names MariaDB (namesMariaDb), with jdbc.url and jdbc.dbms as LogicalDOC reads them: from
     * the override file beside it (context-override.properties for context.properties) when that sets them, as
     * ContextProperties does. Both are read as LogicalDOC writes them, Latin-1, and neither is written:
     * ContextProperties' constructor touches its file, and a refused start leaves the conf file as it was. A missing
     * file names nothing.
     */
    static boolean confNamesMariaDb(File conf) throws IOException {
        Properties keys = read(conf);
        Properties override = read(new File(conf.getParentFile(),
                conf.getName().replaceFirst("\\.properties$", "") + "-override.properties"));
        return namesMariaDb(override.getProperty("jdbc.url", keys.getProperty("jdbc.url")),
                override.getProperty("jdbc.dbms", keys.getProperty("jdbc.dbms")));
    }

    /** The file's properties, read in Latin-1, or none when it's absent */
    private static Properties read(File file) throws IOException {
        Properties properties = new Properties();
        if (file.isFile())
            try (InputStream in = new FileInputStream(file)) {
                properties.load(in);
            }
        return properties;
    }

    /**
     * @return whether the folder holds anything: a storage marker or a document's folder. Only a folder known to be
     *         missing, or one listed and found empty, holds nothing: one that exists and can't be listed may hold an
     *         install's files, and counts as holding them.
     */
    static boolean holdsFiles(File originalFolder) {
        Path folder = originalFolder.toPath();
        if (Files.notExists(folder))
            return false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            return entries.iterator().hasNext();
        } catch (IOException | DirectoryIteratorException | SecurityException e) {
            return true;
        }
    }
}
