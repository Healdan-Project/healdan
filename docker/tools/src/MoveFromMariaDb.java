// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.logicaldoc.healdan.db.HealdanMigrator;

/**
 * The move-from-mariadb command (PostgreSQL spec section 9): copies an existing MariaDB install's database into a
 * PostgreSQL schema Healdan's own scripts made, compares the two, and refuses to finish on any difference. The
 * entrypoint runs it in place of the start-up tool and Tomcat, as the app's user. Its steps, each printing one line as
 * it ends:
 * <ol>
 * <li>The source: connects to MariaDB in a session the server keeps read-only, and refuses a source whose Healdan
 * schema isn't at SOURCE_VERSION, the last on MariaDB, or that lacks a table or column the .mariadb DbInit scripts
 * create.</li>
 * <li>The target: the start-up tool's checks of the server and the database, then the target's state. An empty schema
 * goes on, one an unfinished move left is emptied, and anything else, a finished move included, is refused, as is an
 * unfinished move beside the start-up tool's marker: a database Healdan may have started on is never emptied.</li>
 * <li>Marks the target with an unfinished move (StartGuard.MOVE_MARKER), which the start-up tool won't start on.</li>
 * <li>Makes the schema as a fresh install's: LogicalDOC's DbInit scripts, then Healdan's migrations up to
 * TARGET_VERSION, the one that pairs with the source, and none after it, which the app's first start applies to the
 * moved rows. No seed runs, and the app never starts, so the install id and every seed marker come from the
 * source.</li>
 * <li>Drops the foreign keys, keeping their definitions, and empties every table but the target's own.</li>
 * <li>Copies every table both sides have, each in one transaction, reading the source in one consistent snapshot, and
 * checks what it read against MariaDB's own count of the rows and values and their CRC-32s. A value PostgreSQL can't
 * hold as it is stops the move once every table has been read, so the report lists every one.</li>
 * <li>Carries the counters: each sequence's next value from MariaDB's, and each identity column's.</li>
 * <li>Adds the foreign keys back, which checks every reference.</li>
 * <li>Compares both sides: each table's rows and checksum, and each counter against its table's ids.</li>
 * <li>Marks the schema initialised and the move finished, in one transaction.</li>
 * </ol>
 * It never writes to MariaDB, never reads or writes the conf, repository or cache volumes, and never contacts a
 * storage backend. Nothing it prints holds a row's value, a password or a URL with a password: a failure while rows are
 * read or written is described by its table, its SQLSTATE and the exception's class, never by the driver's message,
 * which can quote values.
 */
final class MoveFromMariaDb {

    /** The last version of the mariadb/ folder: a source at any other is refused */
    static final String SOURCE_VERSION = "12";

    /**
     * The version of Healdan's tables that pairs with a source at SOURCE_VERSION, which the move makes its target's:
     * the twelve scripts the source ran, translated, and V13, the widening of eight of LogicalDOC's text columns. The
     * move runs no later migration, as one that writes rows would write them to tables the copy then empties, and be
     * recorded as applied: the app's first start applies each later one to the moved rows, as on any upgrade. So it
     * stays 13 when a later migration ships.
     */
    static final String TARGET_VERSION = "13";

    /** Where an admin reports what the move can't sort out itself */
    private static final String ISSUES = "https://github.com/Healdan-Project/healdan/issues";

    /** How an admin empties the target, for the refusals that tell how: on volumes, or with host folders */
    private static final String EMPTY_IT = "remove the db service's volume, pg-data, or on a stack with host folders"
            + " empty PostgreSQL's folder";

    /** The Moving page's section that empties the target, either way */
    private static final String START_OVER = "Starting the Move Over says: " + StartGuard.MOVE_PAGE
            + "#starting-the-move-over";

    static final String REPLACE_NUL = "--replace-nul";

    /** The git tag of the last release that runs on MariaDB, to which a source is upgraded before it moves */
    static final String LAST_MARIADB_RELEASE = "mariadb-final";

    /** Rows an insert batch holds, and rows a read fetches at a time */
    static final int BATCH = 1000;

    /** A table's progress is printed every so many rows */
    private static final long PROGRESS = 100_000;

    /** The session advisory lock a move holds on the target while it runs, so that two moves never run at once */
    static final long LOCK_KEY = 0x4865616c64616e4dL;

    /** A text key value printed as it is: a name, as CE's text keys are, with no spaces, controls or at sign */
    private static final Pattern PRINTABLE_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private static final String TIMESTAMP = "timestamp without time zone";

    /** The passwords, which every printed line is scrubbed of, whatever printed them */
    private static final Set<String> SECRETS = new LinkedHashSet<>();

    private MoveFromMariaDb() {
    }

    /** Exit statuses: 0 moved, 1 refused or failed (the target is left marked unfinished, or untouched) */
    public static void main(String[] args) {
        int status = guarded(() -> run(args, System.getenv()));
        System.out.flush();
        System.err.flush();
        // DBInit leaves its connection open, and a driver can leave a thread behind: neither may keep the JVM up
        System.exit(status);
    }

    /** The command's body: its exit status, which is 0 only once it has printed Move complete. */
    interface Body {
        int status() throws Throwable;
    }

    /**
     * The body's exit status, or 1 for anything it throws, an Error included (run handles every Exception itself):
     * every way out of main reaches System.exit, so no open connection or driver thread can keep the JVM up without
     * one. An Error's line names its class only, as its message could hold anything.
     */
    static int guarded(Body body) {
        try {
            return body.status();
        } catch (Throwable t) {
            try {
                error(t.getClass().getName(), List.of());
            } catch (Throwable ignored) {
                // nothing more can be said: the exit status still says it failed
            }
            return 1;
        }
    }

    static int run(String[] args, Map<String, String> env) {
        try {
            move(nulOption(args), env);
            return 0;
        } catch (MoveException e) {
            error(e.getMessage(), e.details);
        } catch (DockerInit.InitException | IllegalArgumentException | IllegalStateException e) {
            // The start-up tool's own refusals and checks, and ConfigPlan's: messages written for an admin
            error(e.getMessage(), List.of());
        } catch (SQLException e) {
            error("a database call failed: " + described(e), List.of());
        } catch (Exception e) {
            error(e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), List.of());
        }
        return 1;
    }

    private static void move(MoveValues.Nul nul, Map<String, String> env) throws Exception {
        Map<String, String> keys = ConfigPlan.envKeys(env);
        DockerInit.Db db = new DockerInit.Db(keys.get("jdbc.url"), keys.get("jdbc.username"),
                keys.get("jdbc.password"));
        Source source = source(env);
        SECRETS.add(db.password);
        SECRETS.add(source.password());
        int waitSeconds = ConfigPlan.dbWaitSeconds(env);
        ClassLoader cl = MoveFromMariaDb.class.getClassLoader();

        // Each connection is closed by close(), which only warns, never by a try-with-resources: its failure to close
        // after the finish would make a finished move print ERROR and exit 1. The last line comes once both are closed
        Connection src = connectSource(source);
        try {
            // 1. The source
            String mariaDb = checkSource(src, source, cl);
            say("Source: MariaDB " + mariaDb + ", Healdan schema version " + SOURCE_VERSION + ", " + sourceZone(src)
                    + " ... ok");

            // 2. The target, and 3. its marker
            db.await(waitSeconds);
            String postgres = postgresVersion(DockerInit.checkDatabase(db, MoveFromMariaDb::warn).versionNum());
            Connection tgt = connectTarget(db);
            try {
                lock(tgt);
                TargetState state = prepare(tgt, db);
                say("Target: PostgreSQL " + postgres + (state == TargetState.UNFINISHED
                        ? ", an unfinished move's tables removed" : ", empty") + " ... ok");

                // 4. The schema
                int tables = makeSchema(cl, db, tgt);
                say("Schema: made as a fresh install's, by LogicalDOC's scripts and Healdan's migrations to version "
                        + TARGET_VERSION + " (" + tables + " tables) ... ok");

                // 5. The foreign keys and the scripts' rows
                List<ForeignKey> foreignKeys = dropForeignKeysAndEmpty(tgt);

                // 6. The copy
                TargetSchema schema = targetSchema(tgt);
                MovePlan.Plan plan = MovePlan.plan(sourceColumns(src), schema.columnNames());
                List<TableCopy> copies = copies(plan, schema);
                Copied copied = copy(src, tgt, copies, nul);

                // 7. The counters, still in the source's snapshot
                Counters counters = carryCounters(src, tgt, plan);
                endSnapshot(src);
                say("Sequences " + counters.sequences() + " of " + counters.sequences() + ", identity counters "
                        + counters.identities() + " of " + counters.identities() + " ... ok");

                // 8. The foreign keys back
                addForeignKeys(tgt, foreignKeys, schema);
                say("Foreign keys rebuilt: " + foreignKeys.size() + " of " + foreignKeys.size());

                // 9. The comparison
                compare(tgt, copies, copied);
                say("Comparing: rows and checksums match in " + copies.size() + " of " + copies.size() + " tables");
                checkCounters(tgt, schema, counters.target());
                say("Counters: none can hand out an id its table already holds ... ok");

                // 10. The lists, before the markers: the places --replace-nul changed are recorded nowhere else, and
                // a move stopped after its finish can't be run again to print them
                for (String line : sourceOnly(plan, counters.sourceOnlySequences()))
                    say(line);
                for (String line : replacedReport(copied.replaced()))
                    say(line);
                finish(tgt);
            } finally {
                close(tgt, "the target");
            }
        } finally {
            // The start-up tool's probes of the target share a connection of their own
            db.close();
            close(src, "the source");
        }
        say("Move complete.");
    }

    // ---- the options and the environment

    /** @return what to do with a NUL: REFUSE, or REPLACE with --replace-nul */
    static MoveValues.Nul nulOption(String[] args) throws MoveException {
        MoveValues.Nul nul = MoveValues.Nul.REFUSE;
        for (String arg : args) {
            if (!REPLACE_NUL.equals(arg))
                throw new MoveException("unknown argument '" + arg + "': the move takes only " + REPLACE_NUL
                        + ", which puts a space for each NUL in text, and lists every value it changed");
            nul = MoveValues.Nul.REPLACE;
        }
        return nul;
    }

    /**
     * The source database: MariaDB, the overlay's service by default.
     *
     * @param host, port and name as the source's JDBC URL holds them; user and password are passed apart from it
     */
    record Source(String host, int port, String name, String user, String password) {

        String url() {
            return "jdbc:mariadb://" + host + ":" + port + "/" + name;
        }

        /** For messages: never the password */
        String describe() {
            return "the database " + name + " on " + host + ":" + port + " as " + user;
        }
    }

    /**
     * The source from MOVE_SOURCE_HOST (mariadb), MOVE_SOURCE_PORT (3306), and MOVE_SOURCE_NAME, MOVE_SOURCE_USER and
     * MOVE_SOURCE_PASSWORD, each defaulting to its DB_ variable, as the old stack's MariaDB had the app's. A blank
     * value counts as unset; only the password isn't trimmed.
     */
    static Source source(Map<String, String> env) throws MoveException {
        String host = value(env, "MOVE_SOURCE_HOST", null, "mariadb");
        String port = value(env, "MOVE_SOURCE_PORT", null, "3306");
        String name = value(env, "MOVE_SOURCE_NAME", "DB_NAME", "logicaldoc");
        String user = value(env, "MOVE_SOURCE_USER", "DB_USER", "logicaldoc");
        String password = env.get("MOVE_SOURCE_PASSWORD");
        if (password == null || password.isEmpty())
            password = env.get("DB_PASSWORD");
        if (!ConfigPlan.HOST.matcher(host).matches())
            throw new MoveException("MOVE_SOURCE_HOST must be a host name or an address, got '" + host + "'");
        if (!port.matches("\\d{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
            throw new MoveException("MOVE_SOURCE_PORT must be a port number, got '" + port + "'");
        if (!ConfigPlan.DATABASE.matcher(name).matches())
            throw new MoveException("MOVE_SOURCE_NAME (or DB_NAME) must be a database name of letters, digits, _, $"
                    + " and -, got '" + name + "'");
        if (password == null || password.isEmpty())
            throw new MoveException("the source's password isn't set: set MOVE_SOURCE_PASSWORD, or DB_PASSWORD when"
                    + " it's the same");
        return new Source(host, Integer.parseInt(port), name, user, password);
    }

    private static String value(Map<String, String> env, String name, String fallback, String otherwise) {
        String v = env.get(name);
        if ((v == null || v.isBlank()) && fallback != null)
            v = env.get(fallback);
        return v == null || v.isBlank() ? otherwise : v.trim();
    }

    // ---- 1. The source

    /**
     * Connects to the source in a session MariaDB keeps read-only (SET SESSION TRANSACTION READ ONLY: every
     * transaction of the session refuses writes, DDL and sequence changes, with SQLSTATE 25006), read back before
     * anything else is read. tinyint(1) is read as the number it is, not a boolean, and prepared statements use the
     * binary protocol.
     */
    private static Connection connectSource(Source source) throws MoveException {
        Properties login = new Properties();
        login.setProperty("user", source.user());
        login.setProperty("password", source.password());
        login.setProperty("tinyInt1isBit", "false");
        // Rows are read by prepared statements in MariaDB's binary protocol, whose numbers are exact: its text
        // protocol gives a float to six digits
        login.setProperty("useServerPrepStmts", "true");
        Connection c;
        try {
            c = DriverManager.getConnection(source.url(), login);
        } catch (SQLException e) {
            throw new MoveException("can't connect to the source, " + source.describe() + ": " + e.getMessage());
        }
        try (Statement s = c.createStatement()) {
            s.execute("set session transaction read only");
            // A streamed table waits on PostgreSQL's inserts between fetches: MariaDB mustn't take that for a dead
            // client
            s.execute("set session net_write_timeout = 3600");
            boolean readOnly = false;
            int flags = 0;
            try (ResultSet r = s.executeQuery("select variable_value from information_schema.session_variables"
                    + " where variable_name in ('TRANSACTION_READ_ONLY', 'TX_READ_ONLY')")) {
                readOnly = true;
                while (r.next()) {
                    flags++;
                    readOnly &= "ON".equalsIgnoreCase(r.getString(1)) || "1".equals(r.getString(1));
                }
            }
            if (!readOnly || flags == 0)
                throw new MoveException("the source's session couldn't be made read-only, so the move stopped before"
                        + " it read anything");
            c.setReadOnly(true);
            return c;
        } catch (SQLException | MoveException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // the first failure is the one to report
            }
            if (e instanceof MoveException m)
                throw m;
            throw new MoveException("can't make the source's session read-only: " + e.getMessage());
        }
    }

    /**
     * Refuses a source that isn't MariaDB, whose Healdan schema history isn't at SOURCE_VERSION with every migration
     * succeeded, or whose LogicalDOC tables lack what the .mariadb DbInit scripts create.
     *
     * @return MariaDB's version
     */
    private static String checkSource(Connection c, Source source, ClassLoader cl) throws Exception {
        String version;
        boolean history;
        String last = null;
        List<String> failed = new ArrayList<>();
        Map<String, Set<String>> columns = new HashMap<>();
        try (Statement s = c.createStatement()) {
            version = text(s, "select version()");
            if (!version.toLowerCase(Locale.ROOT).contains("mariadb"))
                throw new MoveException("the source, " + source.describe() + ", is " + version + ", not MariaDB: the"
                        + " move reads only a Healdan install's MariaDB");
            history = baseTables(s).contains("hd_schema_history");
            if (history) {
                try (ResultSet r = s.executeQuery("select version from hd_schema_history"
                        + " order by installed_rank desc limit 1")) {
                    if (r.next())
                        last = r.getString(1);
                }
                try (ResultSet r = s.executeQuery("select coalesce(version, '') from hd_schema_history"
                        + " where success = 0 order by installed_rank")) {
                    while (r.next())
                        failed.add(r.getString(1));
                }
            }
            try (ResultSet r = s.executeQuery("select table_name, column_name from information_schema.columns"
                    + " where table_schema = database()")) {
                while (r.next())
                    columns.computeIfAbsent(r.getString(1), t -> new HashSet<>()).add(r.getString(2));
            }
        } catch (SQLException e) {
            throw new MoveException("can't read the source's schema: " + e.getMessage());
        }
        String refusal = historyRefusal(history, last, failed);
        if (refusal != null)
            throw new MoveException(refusal);
        DbInitPlan.Missing missing = DbInitPlan.missing(
                DockerInit.createdColumns(cl, DockerInit.plannedScripts(cl, false, "mariadb")), columns);
        if (!missing.isEmpty())
            throw new MoveException(columnsRefusal(missing.describe()));
        return mariaDbVersion(version);
    }

    /**
     * @param history the source has hd_schema_history
     * @param last the version of its last row by installed_rank, null when it has none
     * @param failed the versions of its rows that didn't succeed
     * @return the refusal, or null for a source at SOURCE_VERSION with every migration succeeded
     */
    static String historyRefusal(boolean history, String last, List<String> failed) {
        String upgrade = "Upgrade the install to the release tagged " + LAST_MARIADB_RELEASE + " and start it once,"
                + " so that its schema reaches version " + SOURCE_VERSION + "; then stop it with docker compose down"
                + " and run the move again. Nothing was changed.";
        if (!history)
            return "the source has no hd_schema_history table, which every Healdan install on MariaDB has: check that"
                    + " MOVE_SOURCE_HOST and MOVE_SOURCE_NAME (or DB_NAME) name the install's database. If they do, it"
                    + " hasn't started on a Healdan release yet. " + upgrade;
        if (!failed.isEmpty())
            return "the source's Healdan schema history has a migration that didn't succeed (version "
                    + String.join(", ", failed) + "), so its schema isn't whole. " + upgrade;
        if (!SOURCE_VERSION.equals(last))
            return "the source's Healdan schema is at version " + (last == null ? "(none)" : last) + ", and the move"
                    + " takes only version " + SOURCE_VERSION + ", the last on MariaDB. " + upgrade;
        return null;
    }

    /** The refusal for a source whose LogicalDOC tables lack what the .mariadb scripts create */
    static String columnsRefusal(String missing) {
        return "the source's LogicalDOC tables lack what the release tagged " + LAST_MARIADB_RELEASE + " has: "
                + missing + ". That release's start-up stops on this too. Make each change its Upgrading page gives"
                + " for LogicalDOC's tables, start the install once on that release, then stop it with docker compose"
                + " down and run the move again. Nothing was changed.";
    }

    /**
     * The time zone the source's session renders its timestamp columns in (ld_patch.ld_date today), for the Source
     * line: they come across as that wall-clock time, so it has to be the one the install ran with
     */
    private static String sourceZone(Connection c) throws MoveException {
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select @@session.time_zone, @@system_time_zone,"
                        + " cast(timediff(now(), utc_timestamp()) as char)")) {
            r.next();
            return zone(r.getString(1), r.getString(2), r.getString(3));
        } catch (SQLException e) {
            throw new MoveException("can't read the source's time zone: " + e.getMessage());
        }
    }

    /**
     * @param timeZone the session's time_zone, SYSTEM for the server's
     * @param systemZone the server's system_time_zone
     * @param offset the session's offset from UTC now, as MariaDB's timediff gives it: 00:00:00, -05:00:00
     * @return as the Source line gives it: time zone UTC (the server's, +00:00), or time zone Europe/Rome (+01:00)
     */
    static String zone(String timeZone, String systemZone, String offset) {
        String hours = offset == null ? "" : offset.trim();
        if (!hours.startsWith("-"))
            hours = "+" + hours;
        // timediff gives hours, minutes and seconds: the seconds are always 00 for a zone's offset
        if (hours.matches("[+-]\\d{2}:\\d{2}:\\d{2}"))
            hours = hours.substring(0, hours.length() - 3);
        boolean system = timeZone == null || "SYSTEM".equalsIgnoreCase(timeZone);
        return "time zone " + (system ? systemZone + " (the server's, " : timeZone + " (") + hours + ")";
    }

    /** MariaDB's version without its build suffix: 11.4.13 from 11.4.13-MariaDB-ubu2404 */
    static String mariaDbVersion(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? version : version.substring(0, dash);
    }

    /** The names of the source's base tables, as MariaDB spells them: not its views, and not its sequences */
    private static Set<String> baseTables(Statement s) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (ResultSet r = s.executeQuery("select table_name from information_schema.tables"
                + " where table_schema = database() and table_type = 'BASE TABLE' order by table_name")) {
            while (r.next())
                tables.add(r.getString(1));
        }
        return tables;
    }

    /** The columns of each of the source's base tables, in their order */
    private static Map<String, List<String>> sourceColumns(Connection c) throws MoveException {
        try (Statement s = c.createStatement()) {
            Set<String> tables = baseTables(s);
            Map<String, List<String>> columns = new LinkedHashMap<>();
            for (String table : tables)
                columns.put(table, new ArrayList<>());
            // Matched to the tables by name exactly, as MariaDB on Linux matches table names
            try (ResultSet r = s.executeQuery("select table_name, column_name from information_schema.columns"
                    + " where table_schema = database() order by table_name, ordinal_position")) {
                while (r.next()) {
                    List<String> list = columns.get(r.getString(1));
                    if (list != null)
                        list.add(r.getString(2));
                }
            }
            return columns;
        } catch (SQLException e) {
            throw new MoveException("can't read the source's tables: " + e.getMessage());
        }
    }

    // ---- 2. The target, and 3. its marker

    /** PostgreSQL's version from its server_version_num: 18.1 from 180001 */
    static String postgresVersion(int versionNum) {
        return versionNum / 10000 + "." + versionNum % 10000;
    }

    private static Connection connectTarget(DockerInit.Db db) throws MoveException {
        try {
            return db.connect();
        } catch (SQLException e) {
            throw new MoveException("can't connect to the target at " + db.url + ": " + e.getMessage());
        }
    }

    /** Holds the move's advisory lock for as long as the connection lasts, or refuses when another move holds it */
    private static void lock(Connection c) throws MoveException, SQLException {
        try (PreparedStatement s = c.prepareStatement("select pg_try_advisory_lock(?)")) {
            s.setLong(1, LOCK_KEY);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                if (!r.getBoolean(1))
                    throw new MoveException("another move-from-mariadb is running against the target database, so"
                            + " this one stopped and changed nothing. Wait for that one to end.");
            }
        }
    }

    enum TargetState {
        EMPTY, UNFINISHED, FINISHED, NOT_EMPTY, STARTED
    }

    /**
     * @param move as Db.move gives it: null without the marker, false for an unfinished move, true a finished one
     * @param initialised the schema has the start-up tool's marker, DockerInit.MARKER
     * @param tablesAndSequences the schema's tables and sequences
     * @param others the schema's other relations (views, foreign tables and the like), which a move never makes
     */
    static TargetState targetState(Boolean move, boolean initialised, int tablesAndSequences, int others) {
        if (Boolean.TRUE.equals(move))
            return TargetState.FINISHED;
        // A move makes the start-up tool's marker only in the transaction that marks it finished, so an unfinished
        // move beside that marker is something else's doing, on a database Healdan may have started on
        if (Boolean.FALSE.equals(move) && initialised)
            return TargetState.STARTED;
        if (others > 0)
            return TargetState.NOT_EMPTY;
        if (Boolean.FALSE.equals(move))
            return TargetState.UNFINISHED;
        return tablesAndSequences == 0 ? TargetState.EMPTY : TargetState.NOT_EMPTY;
    }

    /** The refusal for a target the move doesn't write to */
    static String targetRefusal(TargetState state, int tables, int sequences, int others) {
        return switch (state) {
        case FINISHED -> "the target database already holds a finished move from MariaDB, so this one stopped and"
                + " changed nothing. Start Healdan on it with docker compose up -d. To move again from the start,"
                + " the target has to be empty: point DB_NAME at a new database or, if nothing else is in it, "
                + EMPTY_IT + ", as " + START_OVER;
        case NOT_EMPTY -> "the target database already holds " + tables + " table" + (tables == 1 ? "" : "s") + ", "
                + sequences + " sequence" + (sequences == 1 ? "" : "s")
                + (others == 0 ? "" : " and " + others + " other relation" + (others == 1 ? "" : "s"))
                + ", which no move from MariaDB made, and the move writes only to an empty database or one an"
                + " unfinished move left. Nothing was changed. Point DB_NAME at a new, empty database or, if it's a"
                + " new install started by mistake and holds nothing you need, " + EMPTY_IT + " and run the move"
                + " again, as " + START_OVER;
        case STARTED -> "the target database holds a move from MariaDB that didn't finish, and the start-up tool's"
                + " marker, " + DockerInit.MARKER + ", which a move makes only as it finishes. Something other than a"
                + " move changed the database, and Healdan may have been started on it. The move won't empty a"
                + " database Healdan has been started on, so this one stopped and changed nothing. Keep the database as"
                + " it is, back it up, and ask for help: " + ISSUES;
        default -> throw new IllegalArgumentException(state + " isn't refused");
        };
    }

    /**
     * Steps 2 and 3: decides on the target's state, and marks it with an unfinished move. An unfinished move's tables
     * and sequences are dropped and the new marker made in one transaction, so a move stopped here leaves the old
     * marker.
     */
    private static TargetState prepare(Connection c, DockerInit.Db db) throws Exception {
        Boolean move = db.move();
        Map<String, Integer> kinds = relations(c);
        int tables = kinds.getOrDefault("r", 0) + kinds.getOrDefault("p", 0);
        int sequences = kinds.getOrDefault("S", 0);
        int others = kinds.getOrDefault("v", 0) + kinds.getOrDefault("m", 0) + kinds.getOrDefault("f", 0)
                + kinds.getOrDefault("c", 0);
        boolean initialised = names(c, "r", "p").contains(DockerInit.MARKER);
        TargetState state = targetState(move, initialised, tables + sequences, others);
        if (state != TargetState.EMPTY && state != TargetState.UNFINISHED)
            throw new MoveException(targetRefusal(state, tables, sequences, others));
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            if (state == TargetState.UNFINISHED) {
                List<String> drop = names(c, "r", "p");
                if (!drop.isEmpty())
                    s.execute("drop table if exists " + quoted(drop) + " cascade");
                // Sequences no table took with it
                List<String> left = names(c, "S");
                if (!left.isEmpty())
                    s.execute("drop sequence if exists " + quoted(left) + " cascade");
                Map<String, Integer> after = relations(c);
                if (!after.isEmpty())
                    throw new MoveException("removing the unfinished move's tables left relations of the kinds "
                            + after.keySet() + " in the target's schema, so the move stopped");
            }
            s.execute("create table " + StartGuard.MOVE_MARKER + " (started timestamp not null, finished timestamp)");
            s.execute("insert into " + StartGuard.MOVE_MARKER + " (started) values (current_timestamp)");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw new MoveException("can't prepare the target: " + e.getMessage() + " (SQLSTATE " + e.getSQLState()
                    + "). Nothing was changed.");
        } finally {
            if (!c.getAutoCommit())
                c.rollback();
            c.setAutoCommit(true);
        }
        return state;
    }

    /** The number of relations of each kind (pg_class.relkind) in the target's schema, leaving out indexes */
    private static Map<String, Integer> relations(Connection c) throws SQLException {
        Map<String, Integer> kinds = new HashMap<>();
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select c.relkind, count(*) from pg_class c"
                        + " join pg_namespace n on n.oid = c.relnamespace where n.nspname = current_schema()"
                        + " and c.relkind not in ('i', 'I', 't') group by c.relkind")) {
            while (r.next())
                kinds.put(r.getString(1), r.getInt(2));
        }
        return kinds;
    }

    /** The names of the target schema's relations of the kinds given (pg_class.relkind), in order */
    private static List<String> names(Connection c, String... kinds) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("select c.relname from pg_class c join pg_namespace n"
                + " on n.oid = c.relnamespace where n.nspname = current_schema()"
                + " and cast(c.relkind as text) = any (?) order by c.relname")) {
            s.setArray(1, c.createArrayOf("text", kinds));
            try (ResultSet r = s.executeQuery()) {
                while (r.next())
                    names.add(r.getString(1));
            }
        }
        return names;
    }

    // ---- 4. The schema

    /**
     * Makes the schema as a fresh install's start does: LogicalDOC's DbInit scripts, checked as the start-up tool
     * checks them, then Healdan's migrations through HealdanMigrator up to TARGET_VERSION, the widening of
     * LogicalDOC's columns included, and none after it, which the app's first start applies to the moved rows. The
     * version they reach is checked. No seed runs.
     *
     * @return the number of tables the schema has
     */
    private static int makeSchema(ClassLoader cl, DockerInit.Db db, Connection c) throws Exception {
        // The scripts' plan isn't printed: the report's Schema line says what made the schema
        List<String> scripts = DockerInit.plannedScripts(cl, true, DockerInit.DBMS, line -> {
        });
        Map<String, List<String>> expected = DockerInit.createdColumns(cl, scripts);
        DockerInit.runScripts(scripts, db.url, db.user, db.password);
        DbInitPlan.Missing missing = DbInitPlan.missing(expected, db.columns());
        if (!missing.isEmpty())
            throw new MoveException("LogicalDOC's scripts didn't make the whole schema (the errors are above): "
                    + missing.describe() + ". Run the move again, which starts over.");
        HealdanMigrator migrator = new HealdanMigrator(new DriverManagerDataSource(db.url, db.user, db.password));
        HealdanMigrator.Status status;
        String reached;
        try {
            status = migrator.migrateTo(TARGET_VERSION);
            reached = migrator.currentVersion().getVersion();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new MoveException("making Healdan's tables failed: " + cause.getMessage()
                    + ". Run the move again, which starts over.");
        }
        if (status != HealdanMigrator.Status.MIGRATED)
            throw new MoveException("Healdan's migrations didn't run (" + status + "), so the move stopped");
        String refusal = versionRefusal(reached);
        if (refusal != null)
            throw new MoveException(refusal);
        return names(c, "r", "p").size();
    }

    /**
     * @param reached the version of Healdan's tables the target's migrations reached
     * @return the refusal for one other than TARGET_VERSION, or null
     */
    static String versionRefusal(String reached) {
        if (TARGET_VERSION.equals(reached))
            return null;
        return "Healdan's tables reached version " + reached + ", not version " + TARGET_VERSION + ", the one that"
                + " pairs with the source's version " + SOURCE_VERSION + ", so the move stopped. Run the move again,"
                + " which starts over.";
    }

    // ---- 5. The foreign keys and the scripts' rows

    /** A foreign key: its table, its name and its definition, as pg_get_constraintdef gives it */
    record ForeignKey(String table, String name, String definition, List<String> columns, String referenced,
            List<String> referencedColumns) {
    }

    /** Foreign keys whose rows that refer to nothing are listed, at most so many each */
    static final int ORPHANS = 20;

    /**
     * Reads every foreign key's table, name and definition and drops them, then empties every table but the target's
     * own (MovePlan.OWN) of the rows the scripts inserted, in one transaction.
     */
    private static List<ForeignKey> dropForeignKeysAndEmpty(Connection c) throws Exception {
        List<ForeignKey> keys = new ArrayList<>();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            String attributes = " with ordinality u(attnum, i) join pg_attribute a on a.attrelid = %s"
                    + " and a.attnum = u.attnum order by u.i)";
            try (ResultSet r = s.executeQuery("select t.relname, k.conname, pg_get_constraintdef(k.oid), f.relname,"
                    + " array(select a.attname from unnest(k.conkey)" + attributes.formatted("k.conrelid") + ","
                    + " array(select a.attname from unnest(k.confkey)" + attributes.formatted("k.confrelid")
                    + " from pg_constraint k join pg_class t on t.oid = k.conrelid join pg_class f on f.oid = k.confrelid"
                    + " join pg_namespace n on n.oid = t.relnamespace"
                    + " where k.contype = 'f' and n.nspname = current_schema() order by t.relname, k.conname")) {
                while (r.next())
                    keys.add(new ForeignKey(r.getString(1), r.getString(2), r.getString(3), List.of((String[]) r
                            .getArray(5).getArray()), r.getString(4), List.of((String[]) r.getArray(6).getArray())));
            }
            for (ForeignKey key : keys)
                s.execute("alter table " + quotePg(key.table()) + " drop constraint " + quotePg(key.name()));
            List<String> tables = new ArrayList<>();
            for (String table : names(c, "r", "p"))
                if (!MovePlan.OWN.contains(table.toLowerCase(Locale.ROOT)))
                    tables.add(table);
            if (!tables.isEmpty())
                s.execute("truncate table " + quoted(tables));
            c.commit();
        } catch (SQLException e) {
            throw new MoveException("can't empty the target's tables: " + e.getMessage() + " (SQLSTATE "
                    + e.getSQLState() + "). Run the move again, which starts over.");
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        return keys;
    }

    /**
     * Adds the foreign keys back, each on its own, which checks every reference, and names each one that fails, with
     * the rows that refer to nothing, by their key and the foreign key's columns, at most ORPHANS of them
     */
    private static void addForeignKeys(Connection c, List<ForeignKey> keys, TargetSchema schema)
            throws MoveException {
        List<String> failed = new ArrayList<>();
        int failures = 0;
        try (Statement s = c.createStatement()) {
            for (ForeignKey key : keys)
                try {
                    s.execute("alter table " + quotePg(key.table()) + " add constraint " + quotePg(key.name()) + " "
                            + key.definition());
                } catch (SQLException e) {
                    failures++;
                    failed.add("foreign key " + key.name() + " on " + key.table() + ", " + key.definition() + ": "
                            + described(e));
                    failed.addAll(orphanLines(s, key, schema.tables().get(key.table())));
                }
        } catch (SQLException e) {
            throw new MoveException("can't add the foreign keys back: " + described(e));
        }
        if (!failed.isEmpty())
            throw new MoveException(failures + " of the " + keys.size() + " foreign keys " + (failures == 1 ? "doesn't"
                    : "don't") + " hold on the copied rows, so the move stopped: each row listed refers to one its"
                    + " table lacks. Mend those rows in MariaDB, and run the move again, which starts over", failed);
    }

    /** The rows of a foreign key's table whose columns all have values and refer to no row: the query after select */
    static String orphans(ForeignKey key) {
        StringJoiner where = new StringJoiner(" and ", " from " + quotePg(key.table()) + " r where ", "");
        StringJoiner match = new StringJoiner(" and ");
        for (int i = 0; i < key.columns().size(); i++) {
            where.add("r." + quotePg(key.columns().get(i)) + " is not null");
            match.add("x." + quotePg(key.referencedColumns().get(i)) + " = r." + quotePg(key.columns().get(i)));
        }
        return where.add("not exists (select 1 from " + quotePg(key.referenced()) + " x where " + match + ")")
                .toString();
    }

    /** The orphans' lines: each row by its table's primary key and the foreign key's columns, as key gives them */
    private static List<String> orphanLines(Statement s, ForeignKey key, TargetTable table) {
        List<String> primaryKey = table == null ? List.of() : table.primaryKey();
        List<String> lines = new ArrayList<>();
        StringJoiner shown = new StringJoiner(", ");
        for (String column : primaryKey)
            shown.add("r." + quotePg(column));
        for (String column : key.columns())
            shown.add("r." + quotePg(column));
        try {
            long count;
            try (ResultSet r = s.executeQuery("select count(*)" + orphans(key))) {
                r.next();
                count = r.getLong(1);
            }
            try (ResultSet rows = s.executeQuery("select " + shown + orphans(key) + " limit " + ORPHANS)) {
                while (rows.next()) {
                    List<Object> values = new ArrayList<>();
                    for (int i = 1; i <= primaryKey.size() + key.columns().size(); i++)
                        values.add(rows.getObject(i));
                    lines.add("  table " + key.table() + (primaryKey.isEmpty() ? "" : ", row " + key(primaryKey,
                            values.subList(0, primaryKey.size()))) + ": " + key(key.columns(), values.subList(
                                    primaryKey.size(), values.size())) + " isn't in " + key.referenced());
                }
            }
            if (count > ORPHANS)
                lines.add("  and " + (count - ORPHANS) + " more");
        } catch (SQLException e) {
            lines.add("  (its rows couldn't be listed: " + described(e) + ")");
        }
        return lines;
    }

    // ---- 6. The copy

    /** A target column: its name, its type and length, and whether it's an identity or generated column */
    record Column(String name, MoveValues.Target target, boolean identity, boolean generated) {
    }

    /** A target table: its columns in order, and its primary key's columns in order (empty when it has none) */
    record TargetTable(String name, List<Column> columns, List<String> primaryKey) {

        Column column(String name) {
            for (Column column : columns)
                if (column.name().equals(name))
                    return column;
            return null;
        }
    }

    /** The target's base tables, by name, as information_schema gives them */
    record TargetSchema(Map<String, TargetTable> tables) {

        Map<String, List<String>> columnNames() {
            Map<String, List<String>> names = new LinkedHashMap<>();
            tables.forEach((name, table) -> names.put(name, table.columns().stream().map(Column::name).toList()));
            return names;
        }
    }

    private static TargetSchema targetSchema(Connection c) throws MoveException {
        Map<String, List<Column>> columns = new LinkedHashMap<>();
        Map<String, List<String>> keys = new HashMap<>();
        try (Statement s = c.createStatement()) {
            for (String table : names(c, "r", "p"))
                columns.put(table, new ArrayList<>());
            try (ResultSet r = s.executeQuery("select table_name, column_name, data_type,"
                    + " coalesce(character_maximum_length, 0), is_identity, is_generated from information_schema.columns"
                    + " where table_schema = current_schema() order by table_name, ordinal_position")) {
                while (r.next()) {
                    List<Column> list = columns.get(r.getString(1));
                    if (list != null)
                        list.add(new Column(r.getString(2), new MoveValues.Target(r.getString(3), r.getInt(4)),
                                "YES".equals(r.getString(5)), !"NEVER".equals(r.getString(6))));
                }
            }
            try (ResultSet r = s.executeQuery("select t.relname, a.attname from pg_index i"
                    + " join pg_class t on t.oid = i.indrelid join pg_namespace n on n.oid = t.relnamespace"
                    + " cross join lateral unnest(i.indkey) with ordinality as k(attnum, position)"
                    + " join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum"
                    + " where i.indisprimary and n.nspname = current_schema() order by t.relname, k.position")) {
                while (r.next())
                    keys.computeIfAbsent(r.getString(1), t -> new ArrayList<>()).add(r.getString(2));
            }
        } catch (SQLException e) {
            throw new MoveException("can't read the target's tables: " + e.getMessage());
        }
        Map<String, TargetTable> tables = new LinkedHashMap<>();
        columns.forEach((name, list) -> tables.put(name, new TargetTable(name, List.copyOf(list),
                List.copyOf(keys.getOrDefault(name, List.of())))));
        return new TargetSchema(tables);
    }

    /**
     * How one table is copied: the plan's table and columns, each column's target, which columns are read from
     * MariaDB as text (dates and times), which make a row's key, and whether the table has an identity column.
     *
     * @param key the plan's indexes of the target's primary key's columns, or, for a table with none, of its columns
     *        named as ids
     */
    record TableCopy(MovePlan.TablePlan plan, List<MoveValues.Target> targets, boolean[] asText, int[] key,
            boolean primaryKey, boolean identity) {
    }

    /**
     * Stops on any problem of the plan, and on any column whose type the move doesn't carry values into, or that's
     * generated, before a single row moves: one line for each.
     */
    private static List<TableCopy> copies(MovePlan.Plan plan, TargetSchema schema) throws MoveException {
        if (!plan.problems().isEmpty())
            throw new MoveException("the source's tables don't match the target's, so nothing was copied. The source"
                    + " has to be at the version the release tagged " + LAST_MARIADB_RELEASE + " made it, with its"
                    + " Healdan schema at version " + SOURCE_VERSION, plan.problems());
        List<String> unknown = new ArrayList<>();
        List<TableCopy> copies = new ArrayList<>();
        for (MovePlan.TablePlan table : plan.copied()) {
            TargetTable target = schema.tables().get(table.table());
            if (table.columns().isEmpty()) {
                unknown.add("table " + table.table() + " has no column both sides have");
                continue;
            }
            List<MoveValues.Target> targets = new ArrayList<>();
            boolean[] asText = new boolean[table.columns().size()];
            boolean identity = false;
            for (int i = 0; i < table.columns().size(); i++) {
                Column column = target.column(table.columns().get(i));
                targets.add(column.target());
                asText[i] = TIMESTAMP.equalsIgnoreCase(column.target().type());
                identity |= column.identity();
                if (column.generated())
                    unknown.add("column " + table.table() + "." + column.name() + " is generated, and the move can't"
                            + " fill it");
                try {
                    MoveValues.carry(null, column.target(), new MoveValues.Where(table.table(), "", column.name()),
                            MoveValues.Nul.REFUSE, new ArrayList<>());
                } catch (MoveValues.Unstorable e) {
                    unknown.add("column " + table.table() + "." + column.name() + ": " + e.why());
                }
            }
            int[] key = keyColumns(table.columns(), target.primaryKey());
            boolean primaryKey = key != null;
            if (key == null)
                key = idColumns(table.columns());
            copies.add(new TableCopy(table, targets, asText, key, primaryKey, identity));
        }
        if (!unknown.isEmpty())
            throw new MoveException("the target has columns the move can't carry values into, so nothing was copied",
                    unknown);
        return copies;
    }

    /** The indexes in the plan's columns of the primary key's columns, or null when there's none or one isn't there */
    static int[] keyColumns(List<String> columns, List<String> primaryKey) {
        if (primaryKey.isEmpty())
            return null;
        int[] key = new int[primaryKey.size()];
        for (int i = 0; i < key.length; i++) {
            key[i] = columns.indexOf(primaryKey.get(i));
            if (key[i] < 0)
                return null;
        }
        return key;
    }

    /** For a table with no primary key: the indexes of its columns named as ids (ending in id) */
    static int[] idColumns(List<String> columns) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++)
            if (columns.get(i).toLowerCase(Locale.ROOT).endsWith("id"))
                ids.add(i);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** What the copy found: each table's checksum of the values carried, and the places a NUL was replaced */
    record Copied(Map<String, MoveChecksum.Sum> sums, List<MoveValues.Where> replaced) {
    }

    /**
     * Copies each table in one transaction, reading the source in one consistent snapshot, in batches of BATCH rows.
     * A value PostgreSQL can't hold rolls its table back, and the move then reads every other table without writing,
     * so the report lists every such place, before it stops.
     */
    private static Copied copy(Connection src, Connection tgt, List<TableCopy> copies, MoveValues.Nul nul)
            throws MoveException {
        try (Statement s = src.createStatement()) {
            s.execute("start transaction with consistent snapshot, read only");
        } catch (SQLException e) {
            throw new MoveException("can't start the source's snapshot: " + e.getMessage());
        }
        say("Copying " + copies.size() + " tables ...");
        Map<String, MoveChecksum.Sum> sums = new LinkedHashMap<>();
        List<MoveValues.Where> replaced = new ArrayList<>();
        int refused = 0;
        int nulOnly = 0;
        long rows = 0;
        boolean announced = false;
        for (TableCopy copy : copies) {
            if (refused > 0 && !announced) {
                say("  The move reads the other tables too, without writing to the target, to list every such value"
                        + " ...");
                announced = true;
            }
            TableResult result = copyTable(src, tgt, copy, nul, refused == 0, replaced);
            sums.put(copy.plan().table(), result.sum());
            rows += result.sum().rows();
            refused += result.refused();
            nulOnly += result.nulOnly();
        }
        if (refused > 0)
            throw new MoveException(refusalSummary(refused, nulOnly));
        say("Copied " + copies.size() + " tables, " + rows + " rows ... ok");
        return new Copied(sums, replaced);
    }

    /** One table's copy: the checksum of the rows carried, and the values refused, of which those only for a NUL */
    record TableResult(MoveChecksum.Sum sum, int refused, int nulOnly) {
    }

    /**
     * Copies one table: reads the source in the plan's column order, dates and times as MariaDB's own text of them,
     * streamed BATCH rows at a time; carries each value; inserts in batches of BATCH rows, in one transaction; and
     * adds each carried row to the table's checksum. A value PostgreSQL can't hold is printed where it's found, by its
     * place, and the table rolled back.
     * <p>
     * The checksum compares what was carried with what PostgreSQL gives back, so it can't see a value the source's
     * driver changed as it read it. So what was read is also tallied against MariaDB's own tally in the same
     * snapshot: its row count, each column's count of values, and for each column but floating-point ones the sum of
     * their CRC-32s, worked out by MariaDB from its own text of each value (sourceTally).
     *
     * @param write false once an earlier table had a value refused: then the table is only read and checked
     */
    private static TableResult copyTable(Connection src, Connection tgt, TableCopy copy, MoveValues.Nul nul,
            boolean write, List<MoveValues.Where> replaced) throws MoveException {
        MovePlan.TablePlan plan = copy.plan();
        String table = plan.table();
        List<String> columns = plan.columns();
        int n = columns.size();
        StringJoiner read = new StringJoiner(", ", "select ", " from " + quoteMaria(plan.sourceTable()));
        StringJoiner names = new StringJoiner(", ", "insert into " + quotePg(table) + " (", ")"
                + (copy.identity() ? " overriding system value" : "") + " values (");
        boolean[] hashed = new boolean[n];
        for (int i = 0; i < n; i++) {
            read.add(sourceColumn(columns.get(i), copy.asText()[i]));
            names.add(quotePg(columns.get(i)));
            hashed[i] = hashed(copy.targets().get(i));
        }
        String insert = names + "?" + ", ?".repeat(n - 1) + ")";

        MoveChecksum.Sum sum = new MoveChecksum.Sum();
        Tally tally = new Tally(n);
        int refused = 0;
        int nulOnly = 0;
        long rows = 0;
        Tally theirs;
        boolean writing = write;
        String doing = "reading " + table + " from MariaDB";
        try {
            theirs = sourceTally(src, plan.sourceTable(), columns, copy.asText(), hashed);
            tgt.setAutoCommit(false);
            try (PreparedStatement s = src.prepareStatement(read.toString(), ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY); PreparedStatement ins = tgt.prepareStatement(insert)) {
                s.setFetchSize(BATCH);
                int pending = 0;
                Object[] raw = new Object[n];
                List<Object> carried = new ArrayList<>(n);
                MoveValues.Where[] places = new MoveValues.Where[n];
                for (int i = 0; i < n; i++)
                    places[i] = new MoveValues.Where(table, "", columns.get(i));
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        rows++;
                        for (int i = 0; i < n; i++) {
                            raw[i] = copy.asText()[i] ? r.getString(i + 1) : r.getObject(i + 1);
                            tally.add(i, raw[i], hashed[i]);
                        }
                        // The row's key is worked out only for a value that needs its place: one refused, or a NUL
                        // replaced. Until then each value is carried with its column's place, which has no key
                        String key = null;
                        carried.clear();
                        boolean whole = true;
                        for (int i = 0; i < n; i++) {
                            int marked = replaced.size();
                            try {
                                carried.add(MoveValues.carry(raw[i], copy.targets().get(i), places[i], nul, replaced));
                                if (replaced.size() > marked) {
                                    key = key != null ? key : rowKey(copy, raw, rows);
                                    replaced.set(marked, new MoveValues.Where(table, key, columns.get(i)));
                                }
                            } catch (MoveValues.Unstorable e) {
                                whole = false;
                                carried.add(null);
                                refused++;
                                if (nul == MoveValues.Nul.REFUSE
                                        && onlyForANul(raw[i], copy.targets().get(i), places[i]))
                                    nulOnly++;
                                key = key != null ? key : rowKey(copy, raw, rows);
                                say("    " + new MoveValues.Where(table, key, columns.get(i)) + ": " + e.why());
                            }
                        }
                        if (!whole) {
                            writing = false;
                            continue;
                        }
                        sum.add(MoveChecksum.row(carried));
                        if (writing) {
                            doing = "writing " + table + " to PostgreSQL";
                            for (int i = 0; i < n; i++)
                                ins.setObject(i + 1, carried.get(i));
                            ins.addBatch();
                            if (++pending == BATCH) {
                                ins.executeBatch();
                                pending = 0;
                            }
                            doing = "reading " + table + " from MariaDB";
                        }
                        if (rows % PROGRESS == 0)
                            say("  " + table + ": " + rows + " rows so far");
                    }
                }
                tally.rows = rows;
                // A value refused stops the move anyway, so the tally is checked only on a table read whole
                if (refused == 0) {
                    List<String> differ = tally.differences(theirs, columns, hashed);
                    if (!differ.isEmpty())
                        throw new MoveException("what the move read from MariaDB's table " + plan.sourceTable()
                                + " isn't what MariaDB holds, by MariaDB's own count of its rows and values and"
                                + " their CRC-32s in the same snapshot, so the move stopped. Nothing was changed in"
                                + " MariaDB. Run the move again; if it stops the same way, report it, with these"
                                + " lines", differ);
                }
                doing = "writing " + table + " to PostgreSQL";
                if (writing && pending > 0)
                    ins.executeBatch();
                if (writing)
                    tgt.commit();
            } finally {
                tgt.rollback();
                tgt.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new MoveException(doing + " failed: " + described(e) + ". Run the move again, which starts over.");
        }
        if (refused > 0)
            say("  " + table + ": " + rows + " rows read, " + (refused == 1 ? "1 value" : refused + " values")
                    + " PostgreSQL can't hold as " + (refused == 1 ? "it is" : "they are") + " (listed above)");
        else if (write)
            say("  " + table + ": " + rows + " row" + (rows == 1 ? "" : "s"));
        return new TableResult(sum, refused, nulOnly);
    }

    /**
     * A column as the copy reads it from MariaDB: a date and time as MariaDB's own text of it, cast(... as char),
     * since MariaDB's driver gives a datetime(3) whose fraction is under a tenth of a second with its leading zeros
     * dropped (14:33:03.069 as 14:33:03.69000), in both protocols
     */
    static String sourceColumn(String column, boolean asText) {
        return asText ? "cast(" + quoteMaria(column) + " as char)" : quoteMaria(column);
    }

    /**
     * Whether a column's values are hashed in the tally, besides being counted: every kind but floating point, whose
     * text MariaDB gives in a form of its own (1e300), and which the binary protocol carries exactly
     */
    static boolean hashed(MoveValues.Target target) {
        String type = target.type() == null ? "" : target.type().toLowerCase(Locale.ROOT);
        return !type.equals("double precision") && !type.equals("real");
    }

    /**
     * MariaDB's tally of a table in the snapshot: count(*), then for each column count(c), and for each hashed one
     * sum(crc32(c)) too, each value in MariaDB's own text of it (a date and time as sourceColumn reads it)
     */
    static String tallyQuery(String sourceTable, List<String> columns, boolean[] asText, boolean[] hashed) {
        StringBuilder b = new StringBuilder("select count(*)");
        for (int i = 0; i < columns.size(); i++) {
            String column = sourceColumn(columns.get(i), asText[i]);
            b.append(", count(").append(column).append(")");
            if (hashed[i])
                b.append(", sum(crc32(").append(column).append("))");
        }
        return b.append(" from ").append(quoteMaria(sourceTable)).toString();
    }

    private static Tally sourceTally(Connection src, String sourceTable, List<String> columns, boolean[] asText,
            boolean[] hashed) throws SQLException {
        Tally tally = new Tally(columns.size());
        try (Statement s = src.createStatement();
                ResultSet r = s.executeQuery(tallyQuery(sourceTable, columns, asText, hashed))) {
            r.next();
            tally.rows = r.getLong(1);
            int at = 2;
            for (int i = 0; i < columns.size(); i++) {
                tally.counts[i] = r.getLong(at++);
                if (hashed[i]) {
                    BigDecimal crcs = r.getBigDecimal(at++);
                    tally.crcs[i] = crcs == null ? 0 : crcs.longValueExact();
                }
            }
        }
        return tally;
    }

    /**
     * A table's rows, and for each column the count of its values, and the sum of their CRC-32s for a hashed one,
     * each value taken as MariaDB's text of it in UTF-8: the text itself, a whole number's decimal digits, a decimal's
     * plain digits
     */
    static final class Tally {

        long rows;

        final long[] counts;

        final long[] crcs;

        Tally(int columns) {
            counts = new long[columns];
            crcs = new long[columns];
        }

        /**
         * Adds a value read from MariaDB to its column's tally: a null counts for nothing, as count(c) has it
         *
         * @param hash whether to add its CRC-32 too
         */
        void add(int column, Object raw, boolean hash) {
            if (raw == null)
                return;
            counts[column]++;
            if (!hash)
                return;
            String text;
            if (raw instanceof String string)
                text = string;
            else if (raw instanceof BigDecimal decimal)
                text = decimal.toPlainString();
            else
                text = raw.toString();
            CRC32 crc = new CRC32();
            crc.update(text.getBytes(StandardCharsets.UTF_8));
            crcs[column] = Math.addExact(crcs[column], crc.getValue());
        }

        /** Where this tally and MariaDB's differ: the rows, and each column by name, its CRC-32s if it's hashed */
        List<String> differences(Tally theirs, List<String> columns, boolean[] hashed) {
            List<String> differ = new ArrayList<>();
            if (rows != theirs.rows)
                differ.add("the move read " + rows + " rows, and MariaDB counts " + theirs.rows);
            for (int i = 0; i < columns.size(); i++)
                if (counts[i] != theirs.counts[i] || hashed[i] && crcs[i] != theirs.crcs[i])
                    differ.add("column " + columns.get(i) + ": the move read " + counts[i] + " values, MariaDB counts "
                            + theirs.counts[i] + (counts[i] == theirs.counts[i] ? ", and their CRC-32s differ" : ""));
            return differ;
        }
    }

    /** Whether a refused value would have carried with its NULs replaced: then a NUL was all that was wrong */
    private static boolean onlyForANul(Object raw, MoveValues.Target target, MoveValues.Where where) {
        try {
            MoveValues.carry(raw, target, where, MoveValues.Nul.REPLACE, new ArrayList<>());
            return true;
        } catch (MoveValues.Unstorable e) {
            return false;
        }
    }

    /** The key of a row being copied, for MoveValues.Where: its primary key, or, without one, its place in the read */
    private static String rowKey(TableCopy copy, Object[] raw, long position) {
        List<String> names = new ArrayList<>(copy.key().length);
        List<Object> values = new ArrayList<>(copy.key().length);
        for (int index : copy.key()) {
            names.add(copy.plan().columns().get(index));
            values.add(raw[index]);
        }
        return copy.primaryKey() ? key(names, values) : keyless(position, names, values);
    }

    /**
     * A row's key as the report gives it: each key column with its value, as ld_id=42. A whole number is given as it
     * is. A text value is given when it's a name of up to 64 letters, digits, dots, colons, underscores and hyphens,
     * as CE's text keys are (an attribute's or a setting's name); any other text is not shown, and its SHA-256's first
     * 16 hex digits are given instead, which MariaDB's sha2(column, 256) finds it by.
     */
    static String key(List<String> names, List<Object> values) {
        StringJoiner key = new StringJoiner(", ");
        for (int i = 0; i < names.size(); i++)
            key.add(names.get(i) + "=" + keyValue(values.get(i)));
        return key.toString();
    }

    /**
     * The key of a row of a table with no primary key: its place in MariaDB's read of the table, which is the
     * table's own order, from 1, then its id columns' values, as key gives them: #17 (ld_docid=5, ld_tenantid=1)
     */
    static String keyless(long position, List<String> names, List<Object> values) {
        return "#" + position + (names.isEmpty() ? "" : " (" + key(names, values) + ")");
    }

    private static String keyValue(Object value) {
        if (value == null)
            return "null";
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger)
            return value.toString();
        if (value instanceof String text) {
            if (PRINTABLE_KEY.matcher(text).matches())
                return text;
            return "(text not shown, sha256 " + sha256(text).substring(0, 16) + ")";
        }
        return "(a " + value.getClass().getSimpleName() + ", not shown)";
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    /** The error for values PostgreSQL can't hold, which the lines above it list: what to do about them */
    static String refusalSummary(int refused, int nulOnly) {
        String values = refused == 1 ? "1 value" : refused + " values";
        if (nulOnly == refused)
            return values + " in MariaDB hold" + (refused == 1 ? "s" : "") + " a NUL character, which PostgreSQL's text"
                    + " can't hold, so the move stopped: each is listed above, by table, row and column. Run the"
                    + " move again with " + REPLACE_NUL + " to put a space for each NUL (the report then lists every"
                    + " value it changed), or mend them in MariaDB first.";
        String nuls = nulOnly == 0 ? ""
                : " Of them, " + nulOnly + " hold" + (nulOnly == 1 ? "s" : "") + " only a NUL, which " + REPLACE_NUL
                        + " would replace with a space.";
        return values + " in MariaDB can't go into PostgreSQL as " + (refused == 1 ? "it is" : "they are")
                + ", so the move stopped: each is listed above, by table, row and column, with the reason. Mend them"
                + " in MariaDB, then run the move again, which starts over." + nuls;
    }

    // ---- 7. The counters

    /** How many sequences and identity counters were carried, and the source's sequences the target has none of */
    record Counters(int sequences, int identities, List<String> sourceOnlySequences, TargetSequences target) {
    }

    /** An identity column, by its sequence and the table and column it gives values to */
    record Identity(String sequence, String table, String column) {
    }

    /**
     * The target's sequences, read once for carrying the counters and checking them: each one's increment, by name,
     * and the identity columns', whose sequences are the others' complement
     */
    record TargetSequences(Map<String, Long> increments, List<Identity> identities) {

        /** The sequences that aren't an identity column's, in order */
        List<String> plain() {
            List<String> plain = new ArrayList<>(increments.keySet());
            for (Identity identity : identities)
                plain.remove(identity.sequence());
            return plain;
        }
    }

    private static TargetSequences targetSequences(Connection tgt) throws SQLException {
        Map<String, Long> increments = new LinkedHashMap<>();
        List<Identity> identities = new ArrayList<>();
        try (Statement s = tgt.createStatement()) {
            try (ResultSet r = s.executeQuery("select sequencename, increment_by from pg_sequences"
                    + " where schemaname = current_schema() order by sequencename")) {
                while (r.next())
                    increments.put(r.getString(1), r.getLong(2));
            }
            try (ResultSet r = s.executeQuery("select s.relname, t.relname, a.attname from pg_class s"
                    + " join pg_namespace n on n.oid = s.relnamespace join pg_depend d on d.objid = s.oid"
                    + " and d.classid = 'pg_class'::regclass and d.refclassid = 'pg_class'::regclass"
                    + " and d.deptype = 'i' join pg_class t on t.oid = d.refobjid"
                    + " join pg_attribute a on a.attrelid = t.oid and a.attnum = d.refobjsubid"
                    + " where s.relkind = 'S' and n.nspname = current_schema() order by t.relname, a.attname")) {
                while (r.next())
                    identities.add(new Identity(r.getString(1), r.getString(2), r.getString(3)));
            }
        }
        return new TargetSequences(increments, identities);
    }

    /** The source's sequences matched to the target's by name, whatever the case */
    record SequenceMatch(Map<String, String> pairs, List<String> targetOnly, List<String> sourceOnly,
            List<String> problems) {
    }

    /**
     * Matches the target's sequences with the source's by name, whatever the case (MariaDB keeps CE's ld_user_SEQ,
     * PostgreSQL folds it to ld_user_seq). A name one side gives more than once is a problem where the target has it.
     *
     * @return pairs from each target sequence to its source sequence, in the target's order
     */
    static SequenceMatch matchSequences(Collection<String> target, Collection<String> source) {
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> sourceByName = new LinkedHashMap<>();
        for (String name : source)
            sourceByName.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(name);
        Map<String, List<String>> targetByName = new LinkedHashMap<>();
        for (String name : target)
            targetByName.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(name);
        Map<String, String> pairs = new LinkedHashMap<>();
        List<String> targetOnly = new ArrayList<>();
        targetByName.forEach((key, names) -> {
            List<String> sources = sourceByName.get(key);
            if (names.size() > 1)
                problems.add("the target has more than one sequence named " + key + ", whatever the case ("
                        + String.join(", ", names) + ")");
            else if (sources == null)
                targetOnly.add(names.get(0));
            else if (sources.size() > 1)
                problems.add("the source has more than one sequence named " + key + ", whatever the case ("
                        + String.join(", ", sources) + "), so the move can't tell which to carry");
            else
                pairs.put(names.get(0), sources.get(0));
        });
        List<String> sourceOnly = new ArrayList<>();
        sourceByName.forEach((key, names) -> {
            if (!targetByName.containsKey(key))
                sourceOnly.addAll(names);
        });
        return new SequenceMatch(pairs, targetOnly, sourceOnly, problems);
    }

    /** The table a sequence gives ids to: its name without _seq, whatever the case, or null for another name */
    static String sequenceTable(String sequence) {
        if (sequence.length() > 4 && sequence.toLowerCase(Locale.ROOT).endsWith("_seq"))
            return sequence.substring(0, sequence.length() - 4);
        return null;
    }

    /**
     * Carries each sequence's next value from MariaDB's next_not_cached_value, which may leave a gap but never hands
     * out a value twice, and each identity column's from the highest of its ids and MariaDB's own counter for the
     * table. A target sequence with no source sequence, or one that steps by another increment, stops the move.
     */
    private static Counters carryCounters(Connection src, Connection tgt, MovePlan.Plan plan)
            throws MoveException {
        String doing = "reading the target's sequences";
        try {
            TargetSequences sequences = targetSequences(tgt);
            Map<String, Long> increments = sequences.increments();
            List<String> plain = sequences.plain();

            doing = "reading the source's sequences";
            List<String> sourceSequences = new ArrayList<>();
            Map<String, Long> autoIncrements = new HashMap<>();
            try (Statement s = src.createStatement()) {
                try (ResultSet r = s.executeQuery("select table_name from information_schema.tables"
                        + " where table_schema = database() and table_type = 'SEQUENCE' order by table_name")) {
                    while (r.next())
                        sourceSequences.add(r.getString(1));
                }
                try (ResultSet r = s.executeQuery("select table_name, auto_increment from information_schema.tables"
                        + " where table_schema = database() and table_type = 'BASE TABLE'"
                        + " and auto_increment is not null")) {
                    while (r.next())
                        autoIncrements.put(r.getString(1), r.getLong(2));
                }
            }
            SequenceMatch match = matchSequences(plain, sourceSequences);
            List<String> problems = new ArrayList<>(match.problems());
            for (String sequence : match.targetOnly())
                problems.add("the target's sequence " + sequence + " has no sequence of the same name in the source,"
                        + " so the move can't tell where it should start");
            if (!problems.isEmpty())
                throw new MoveException("the source's sequences don't match the target's, so the move stopped",
                        problems);

            for (Map.Entry<String, String> pair : match.pairs().entrySet()) {
                doing = "reading the source's sequence " + pair.getValue();
                long next;
                long increment;
                try (Statement s = src.createStatement();
                        ResultSet r = s.executeQuery("select next_not_cached_value, increment from "
                                + quoteMaria(pair.getValue()))) {
                    r.next();
                    next = r.getLong(1);
                    increment = r.getLong(2);
                }
                long targetIncrement = increments.get(pair.getKey());
                if (increment != targetIncrement)
                    throw new MoveException("the sequence " + pair.getValue() + " steps by " + increment
                            + " in the source and " + targetIncrement + " in the target, so the move can't tell which"
                            + " ids it would hand out");
                doing = "setting the target's sequence " + pair.getKey();
                try (PreparedStatement s = tgt.prepareStatement("select setval(cast(? as regclass), ?, false)")) {
                    s.setString(1, quotePg(pair.getKey()));
                    s.setLong(2, next);
                    s.executeQuery().close();
                }
            }

            for (Identity identity : sequences.identities()) {
                doing = "setting the target's identity counter of " + identity.table() + "." + identity.column();
                long highest = highest(tgt, identity.table(), identity.column());
                String sourceTable = sourceTableOf(plan, identity.table());
                Long sourceNext = sourceTable == null ? null : autoIncrements.get(sourceTable);
                long last = Math.max(highest, sourceNext == null ? 0 : sourceNext - 1);
                if (last >= 1)
                    try (PreparedStatement s = tgt.prepareStatement("select setval(cast(? as regclass), ?)")) {
                        s.setString(1, quotePg(identity.sequence()));
                        s.setLong(2, last);
                        s.executeQuery().close();
                    }
            }
            return new Counters(match.pairs().size(), sequences.identities().size(), match.sourceOnly(), sequences);
        } catch (SQLException e) {
            throw new MoveException(doing + " failed: " + described(e) + ". Run the move again, which starts over.");
        }
    }

    private static String sourceTableOf(MovePlan.Plan plan, String table) {
        for (MovePlan.TablePlan copied : plan.copied())
            if (copied.table().equals(table))
                return copied.sourceTable();
        return null;
    }

    /** The highest value of a whole-number column of a target table, 0 when the table is empty */
    private static long highest(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select coalesce(max(" + quotePg(column) + "), 0) from "
                        + quotePg(table))) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Ends the source's snapshot, the last read of the source */
    private static void endSnapshot(Connection src) throws MoveException {
        try (Statement s = src.createStatement()) {
            s.execute("commit");
        } catch (SQLException e) {
            throw new MoveException("ending the source's snapshot failed: " + described(e));
        }
    }

    // ---- 9. The comparison

    /**
     * Reads each copied table back from PostgreSQL into a second checksum, in the same column order, and compares the
     * row count and the checksum with the source's. Dates and times come back as LocalDateTime, as the source's were
     * carried.
     */
    private static void compare(Connection tgt, List<TableCopy> copies, Copied copied) throws MoveException {
        List<String> differences = new ArrayList<>();
        String table = null;
        try {
            tgt.setAutoCommit(false);
            tgt.setReadOnly(true);
            try {
                for (TableCopy copy : copies) {
                    table = copy.plan().table();
                    List<String> columns = copy.plan().columns();
                    StringJoiner read = new StringJoiner(", ", "select ", " from " + quotePg(table));
                    for (String column : columns)
                        read.add(quotePg(column));
                    MoveChecksum.Sum sum = new MoveChecksum.Sum();
                    try (Statement s = tgt.createStatement()) {
                        s.setFetchSize(BATCH);
                        try (ResultSet r = s.executeQuery(read.toString())) {
                            List<Object> values = new ArrayList<>(columns.size());
                            while (r.next()) {
                                values.clear();
                                for (int i = 0; i < columns.size(); i++)
                                    values.add(copy.asText()[i] ? r.getObject(i + 1, LocalDateTime.class)
                                            : r.getObject(i + 1));
                                sum.add(MoveChecksum.row(values));
                            }
                        }
                    }
                    MoveChecksum.Sum source = copied.sums().get(table);
                    if (source.rows() != sum.rows())
                        differences.add("table " + table + ": the source has " + source.rows() + " rows, and the"
                                + " target " + sum.rows());
                    else if (!source.hex().equals(sum.hex()))
                        differences.add("table " + table + ": both have " + sum.rows() + " rows, but their values"
                                + " differ");
                }
            } finally {
                tgt.commit();
                tgt.setReadOnly(false);
                tgt.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new MoveException("reading " + table + " back from PostgreSQL failed: " + described(e)
                    + ". Run the move again, which starts over.");
        }
        if (!differences.isEmpty())
            throw new MoveException("the target doesn't hold what the source holds, so the move isn't finished. Run"
                    + " it again, which starts over; if it fails the same way, keep both databases as they are and"
                    + " report it", differences);
    }

    /**
     * Checks that no counter can hand out an id its table already holds: for a sequence by the arithmetic of CE's
     * hilo id generator (MoveCounters), against its table, the sequence's name without _seq, whose primary key is one
     * whole-number column; for an identity column, by the value itself.
     */
    private static void checkCounters(Connection tgt, TargetSchema schema, TargetSequences sequences)
            throws MoveException {
        List<String> problems = new ArrayList<>();
        String doing = "checking the target's counters";
        try (Statement s = tgt.createStatement()) {
            for (Identity identity : sequences.identities()) {
                doing = "checking the identity counter of " + identity.table() + "." + identity.column();
                long highest = highest(tgt, identity.table(), identity.column());
                long next = next(s, quotePg(identity.sequence()), sequences.increments().get(identity.sequence()));
                if (next <= highest)
                    problems.add("the identity column " + identity.table() + "." + identity.column()
                            + " would hand out " + next + " next, and its table already holds ids up to " + highest);
            }
            for (String sequence : sequences.plain()) {
                doing = "checking the sequence " + sequence;
                String tableName = sequenceTable(sequence);
                TargetTable table = tableName == null ? null : schema.tables().get(tableName);
                Column id = table == null || table.primaryKey().size() != 1 ? null
                        : table.column(table.primaryKey().get(0));
                if (id == null || !Set.of("bigint", "integer", "smallint").contains(id.target().type())) {
                    problems.add("the sequence " + sequence + " has no table " + tableName + " whose key is one"
                            + " whole-number column, so the move can't check the ids it hands out");
                    continue;
                }
                long increment = sequences.increments().get(sequence);
                long next = next(s, quotePg(sequence), increment);
                long highest = highest(tgt, table.name(), id.name());
                boolean safe;
                try {
                    safe = MoveCounters.safe(next, Math.toIntExact(increment), highest);
                } catch (IllegalArgumentException | ArithmeticException e) {
                    problems.add("the sequence " + sequence + ": " + e.getMessage());
                    continue;
                }
                if (!safe)
                    problems.add("the sequence " + sequence + " would hand out ids from "
                            + MoveCounters.lowestNextId(next, Math.toIntExact(increment)) + ", and " + table.name()
                            + " already holds ids up to " + highest);
            }
        } catch (SQLException e) {
            throw new MoveException(doing + " failed: " + described(e) + ". Run the move again, which starts over.");
        }
        if (!problems.isEmpty())
            throw new MoveException("a counter could hand out an id already in use, so the move isn't finished",
                    problems);
    }

    /** The value a sequence hands out next: its last value, or the one after it once that has been handed out */
    private static long next(Statement s, String sequence, long increment) throws SQLException {
        try (ResultSet r = s.executeQuery("select last_value, is_called from " + sequence)) {
            r.next();
            return r.getBoolean(2) ? r.getLong(1) + increment : r.getLong(1);
        }
    }

    // ---- 10. The markers

    /**
     * Marks the schema initialised, as the start-up tool does after a fresh install's scripts, and the move finished,
     * in one transaction: a move stopped before its commit leaves neither.
     */
    private static void finish(Connection c) throws Exception {
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            DockerInit.recordCompletion(s);
            int marked = s.executeUpdate("update " + StartGuard.MOVE_MARKER + " set finished = current_timestamp"
                    + " where finished is null");
            if (marked != 1)
                throw new MoveException("the target's " + StartGuard.MOVE_MARKER + " held " + marked + " unfinished"
                        + " rows, not the one this move made, so the move isn't finished");
            c.commit();
        } catch (SQLException e) {
            throw new MoveException("marking the move finished failed: " + e.getMessage() + " (SQLSTATE "
                    + e.getSQLState() + "). Run the move again, which starts over.");
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
    }

    // ---- The report

    /** The report's list of what only the source has, which wasn't copied: tables, columns and sequences */
    static List<String> sourceOnly(MovePlan.Plan plan, List<String> sequences) {
        List<String> lines = new ArrayList<>();
        for (String table : plan.sourceOnlyTables())
            lines.add("  table " + table);
        for (MovePlan.TablePlan table : plan.copied())
            for (String column : table.sourceOnlyColumns())
                lines.add("  column " + table.sourceTable() + "." + column);
        for (String sequence : sequences)
            lines.add("  sequence " + sequence);
        if (!lines.isEmpty())
            lines.add(0, "Only the source has these, which weren't copied:");
        return lines;
    }

    /** The report's list of every place a NUL was replaced with a space */
    static List<String> replacedReport(List<MoveValues.Where> replaced) {
        List<String> lines = new ArrayList<>();
        if (replaced.isEmpty())
            return lines;
        lines.add("A space replaced each NUL in " + (replaced.size() == 1 ? "1 value" : replaced.size() + " values")
                + " (" + REPLACE_NUL + "):");
        for (MoveValues.Where where : replaced)
            lines.add("  " + where);
        return lines;
    }

    // ---- SQL and messages

    /** A name quoted for MariaDB: in backticks, each backtick doubled */
    static String quoteMaria(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /** A name quoted for PostgreSQL: in double quotes, each double quote doubled */
    static String quotePg(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String quoted(List<String> names) {
        StringJoiner list = new StringJoiner(", ");
        for (String name : names)
            list.add(quotePg(name));
        return list.toString();
    }

    /**
     * A database failure described without the driver's message, which can quote a row's values: the SQLSTATE in plain
     * words, the constraint and column PostgreSQL names apart from its message, and the exception's class. A batch's
     * own exception holds the statement's, which is looked for down its chain.
     */
    static String described(SQLException e) {
        String state = null;
        String constraint = null;
        String column = null;
        Throwable t = e;
        for (int depth = 0; t != null && depth < 10; depth++) {
            if (t instanceof SQLException sql && state == null)
                state = sql.getSQLState();
            if (t instanceof PSQLException p && p.getServerErrorMessage() != null) {
                ServerErrorMessage server = p.getServerErrorMessage();
                if (constraint == null)
                    constraint = server.getConstraint();
                if (column == null)
                    column = server.getColumn();
                if (state == null)
                    state = server.getSQLState();
            }
            Throwable next = t instanceof SQLException sql ? sql.getNextException() : null;
            t = next != null ? next : t.getCause();
        }
        return plain(state) + " (" + (state == null ? "no SQLSTATE" : "SQLSTATE " + state)
                + (constraint == null ? "" : ", constraint " + constraint)
                + (column == null ? "" : ", column " + column) + ", " + e.getClass().getName() + ")";
    }

    /** What a SQLSTATE means, in plain words */
    static String plain(String state) {
        if (state == null)
            return "the database call failed";
        if (state.startsWith("08"))
            return "the connection to the database failed";
        return switch (state) {
        case "23505" -> "a key the table already holds";
        case "23503" -> "a reference to a row that isn't there";
        case "23502" -> "a null in a column that takes none";
        case "22001" -> "text longer than its column takes";
        case "22003" -> "a number beyond its column's range";
        case "25006" -> "a write refused on a read-only connection";
        case "57014" -> "the statement was cancelled";
        case "57P01", "57P02", "57P03" -> "the database server shut down";
        case "40001", "40P01" -> "a conflict with another transaction";
        case "53100" -> "the database server's disk is full";
        default -> "the database refused it";
        };
    }

    /** The text with every secret replaced: no printed line may hold a password, whatever printed it */
    static String scrub(String text, Collection<String> secrets) {
        String scrubbed = text;
        for (String secret : secrets)
            if (secret != null && !secret.isEmpty())
                scrubbed = scrubbed.replace(secret, "********");
        return scrubbed;
    }

    /**
     * Closes a connection the move is done with. A failure to close it is never the move's: once the finish is
     * committed, the move is complete whatever closing does, and before that, the failure that ended the move is the
     * one to report. So it's a warning, described without the driver's message.
     *
     * @param which the source or the target, for the warning
     */
    static void close(Connection c, String which) {
        try {
            c.close();
        } catch (SQLException e) {
            warn("closing the connection to " + which + " failed, which changes nothing the move did: "
                    + described(e));
        } catch (RuntimeException e) {
            warn("closing the connection to " + which + " failed, which changes nothing the move did: "
                    + e.getClass().getName());
        }
    }

    private static String text(Statement s, String query) throws SQLException {
        try (ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getString(1);
        }
    }

    private static void warn(String warning) {
        if (warning != null) {
            System.out.flush();
            System.err.println(scrub("[move] WARNING: " + warning, SECRETS));
        }
    }

    private static void say(String line) {
        System.out.println(scrub("[move] " + line, SECRETS));
    }

    private static void error(String message, List<String> details) {
        System.out.flush();
        System.err.println(scrub("[move] ERROR: " + message + (details.isEmpty() ? "" : ":"), SECRETS));
        for (String detail : details)
            System.err.println(scrub("[move]   " + detail, SECRETS));
    }

    /** A refusal or failure, whose message, and lines, say what's wrong and what to do, and never hold a value */
    static final class MoveException extends Exception {

        private static final long serialVersionUID = 1L;

        final List<String> details;

        MoveException(String message) {
            this(message, List.of());
        }

        MoveException(String message, List<String> details) {
            super(message);
            this.details = List.copyOf(details);
        }
    }
}
