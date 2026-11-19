// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import org.java.plugin.registry.PluginDescriptor;
import org.xml.sax.SAXException;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.dbinit.DBInit;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.plugin.PluginRegistry;

/**
 * Container init, run by entrypoint.sh before Tomcat starts: works out the trusted proxies, waits for the database and
 * stops, before it writes anything, an install that would start empty (StartGuard), applies the container-managed
 * context.properties keys, checks the PostgreSQL server and database and sets the app's role's lock timeout, creates
 * the schema on first boot with the setup wizard's own DBInit, or on a later one checks that LogicalDOC's tables have
 * what this version's scripts create, and installs LogicalDOC's plugins.
 */
public final class DockerInit {

    static final File CONFIG = new File(ConfigPlan.HOME + "/conf/context.properties");

    /** The image's pristine context.properties, the source of keys an older conf file lacks. */
    static final File DEFAULTS = new File(ConfigPlan.HOME + "/defaults/context.properties");

    /** Original's folder, store.1.dir as the conf volume is seeded with it, which the entrypoint makes */
    static final File ORIGINAL = new File(ConfigPlan.HOME + "/repository/docs");

    static final String DRIVER = "org.postgresql.Driver";

    /** The DbInit variant the app's database takes: PostgreSQL has none, so every plugin gets its generic script. */
    static final String DBMS = "postgresql";

    /** Created only after the schema is verified, so a first start that failed part-way is recognisable. */
    private static final String MARKER = "docker_init";

    /** The Install manual's list of the changes to LogicalDOC's tables an existing database needs, with their SQL. */
    private static final String TABLE_CHANGES = "https://docs.healdan.com/install/upgrading/#changes-to-logicaldocs-tables";

    private DockerInit() {
    }

    public static void main(String[] args) {
        try {
            run(System.getenv());
        } catch (IllegalArgumentException | IllegalStateException | InitException e) {
            System.err.println("[docker-init] ERROR: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        // A plugin's start can leave a thread running that isn't a daemon, which would keep this JVM, and so the
        // entrypoint's wait for it, from ever ending.
        System.exit(0);
    }

    static void run(Map<String, String> env) throws Exception {
        String proxies = TrustedProxies.resolve(env, TrustedProxies.dockerNetworks());
        TrustedProxies.write(TrustedProxies.FILE, proxies);
        log("Tomcat believes X-Forwarded-For and X-Forwarded-Proto from " + (proxies.isEmpty() ? "no address" : proxies));

        Map<String, String> envKeys = ConfigPlan.envKeys(env);
        int waitSeconds = ConfigPlan.dbWaitSeconds(env);
        refuseUnstorable(envKeys);
        Db db = new Db(envKeys.get("jdbc.url"), envKeys.get("jdbc.username"), envKeys.get("jdbc.password"));
        // Nothing is written before the guard: not the conf volume, and not the database.
        List<String> missing = guard(db, waitSeconds);

        boolean seeded = applyConfig(CONFIG, DEFAULTS, envKeys);
        // Straight after the seeding, before the checks that can refuse: the next start doesn't seed, so wouldn't warn.
        if (seeded && missing.isEmpty())
            warnConfigLoss();
        checkServerAndDatabase(db);
        if (missing.isEmpty()) {
            log("schema present, skipping database initialization");
            checkLogicalDocTables(db);
        } else if (missing.size() == 2) {
            createSchema(db);
        } else {
            throw new InitException("the database holds part of a LogicalDOC schema, but its initialization did not"
                    + " finish (an earlier first start failed or was interrupted); remove the database volume and"
                    + " start again");
        }

        PluginStart plugins;
        try {
            plugins = installPlugins();
        } catch (PluginException e) {
            throw new InitException("cannot install LogicalDOC's plugins: " + withCauses(e));
        }
        if (!plugins.installed().isEmpty())
            log("installed LogicalDOC's plugins " + String.join(", ", plugins.installed()));
        if (!plugins.failed().isEmpty())
            System.err.println("[docker-init] WARNING: LogicalDOC's plugins " + String.join(", ", plugins.failed())
                    + " did not start here (the error is above). Tomcat starts them again, and asks for a restart if"
                    + " one of them installs then.");
    }

    /**
     * The guard against starting an install empty (spec section 8.3; StartGuard decides), before this start writes
     * anything, to the conf volume or to the database. It reads the conf volume's database keys as they are, without
     * writing the file, waits for the database, and asks it whether it holds a move and a schema. When the conf names
     * MariaDB, a database that doesn't answer within StartGuard.ANSWER_SECONDS isn't waited for: the install's DB_HOST
     * may still name its MariaDB server, which never answers as PostgreSQL. A sign-in the database refuses stops the
     * start at once, with its own message, whatever the conf names.
     *
     * @return the tables of ld_user and the start-up tool's marker that the database lacks
     */
    private static List<String> guard(Db db, int waitSeconds) throws Exception {
        boolean mariaDb = StartGuard.confNamesMariaDb(CONFIG);
        boolean reachable = true;
        if (mariaDb) {
            log(CONFIG + " names MariaDB: PostgreSQL has " + StartGuard.ANSWER_SECONDS + "s to answer");
            reachable = db.answers(StartGuard.ANSWER_SECONDS);
        } else {
            db.await(waitSeconds);
        }
        List<String> schema = List.of("ld_user", MARKER);
        Boolean move = reachable ? db.move() : null;
        List<String> missing = reachable ? db.missingTables(schema) : schema;
        StartGuard.Verdict verdict = StartGuard.decide(mariaDb, reachable, move, missing.size() == schema.size(),
                StartGuard.holdsFiles(ORIGINAL));
        if (verdict != StartGuard.Verdict.START)
            throw new InitException(StartGuard.message(verdict));
        return missing;
    }

    /**
     * Checks the server and the database, on every start, before anything is created in them (spec sections 6.1 to
     * 6.3; DbChecks decides): stops on a server older than PostgreSQL 18, a database that isn't UTF-8 or a role that
     * can't create tables in its schema, and warns of a newer major than the one tested and of a collation that doesn't
     * sort by code point. Then it gives the app's role a lock_timeout in this database (spec section 7.4), which every
     * later connection of the role takes, Tomcat's pool and Flyway included, and reads it back on a new connection.
     * Only that setting writes anything.
     */
    private static void checkServerAndDatabase(Db db) throws Exception {
        Database database = checkDatabase(db, DockerInit::warn);
        int version = database.versionNum();
        String encoding = database.encoding();

        try {
            db.setLockTimeout();
        } catch (SQLException e) {
            throw new InitException("can't give the role '" + db.user + "' a lock_timeout in its database: "
                    + e.getMessage());
        }
        String timeout = db.lockTimeout();
        if (!DbChecks.LOCK_TIMEOUT.equals(timeout))
            throw new InitException("lock_timeout reads back as '" + timeout + "' on a new connection, not the '"
                    + DbChecks.LOCK_TIMEOUT + "' this start gave the role '" + db.user + "' in its database: something"
                    + " sets it for the connection, which Healdan's settings can't override");
        log("PostgreSQL " + version / 10000 + " (" + version + "), a " + encoding + " database, lock_timeout " + timeout
                + " for the role '" + db.user + "' in it");
    }

    /** What checkDatabase found: PostgreSQL's server_version_num, such as 180004, and the database's encoding */
    record Database(int versionNum, String encoding) {
    }

    /**
     * The checks of the server and the database that the start-up tool and the move from MariaDB share, before either
     * writes anything (spec sections 6.1 to 6.3; DbChecks decides): stops, with an InitException, on a server older
     * than PostgreSQL 18, a database that isn't UTF-8 or a role that can't create tables in its schema, and gives
     * warnings each warning, of a newer major than the one tested and of a collation that doesn't sort by code point.
     * Nothing is written.
     */
    static Database checkDatabase(Db db, Consumer<String> warnings) throws Exception {
        int version = db.versionNum();
        String encoding = db.encoding();
        refuse(DbChecks.tooOld(version));
        refuse(DbChecks.wrongEncoding(encoding));
        refuse(DbChecks.cannotCreate(db.canCreate(), db.user, db.schema()));
        String[] locale = db.locale();
        for (String warning : new String[] { DbChecks.untested(version),
                DbChecks.otherLocale(locale[0], locale[1], locale[2]) })
            if (warning != null)
                warnings.accept(warning);
        return new Database(version, encoding);
    }

    /** Stops the start with the refusal, if there is one. */
    private static void refuse(String refusal) throws InitException {
        if (refusal != null)
            throw new InitException(refusal);
    }

    /** Logs the warning, if there is one. */
    private static void warn(String warning) {
        if (warning != null)
            System.err.println("[docker-init] WARNING: " + warning);
    }

    /**
     * What DockerInit's start of the plugins did: the ids of those it installed, of those that failed to start, which
     * Tomcat then starts again, and whether one asked for a restart.
     */
    record PluginStart(List<String> installed, List<String> failed, boolean restartRequired) {
    }

    /**
     * Starts LogicalDOC's plugins with its own PluginRegistry, as Tomcat does next, so each one not yet installed (on a
     * new repository volume, all of them) is installed here, before Tomcat starts. A plugin's install edits web.xml and
     * context.properties, then asks for a restart, since Tomcat has read web.xml by then: the sign-in page says "The
     * application has to be restarted" until one. Here the edits are in place before Tomcat reads anything, and Tomcat
     * finds every plugin installed, so nothing asks. The plugins find context.properties and log.xml on the classpath,
     * which needs the conf folder on it, as Tomcat's common.loader has it. The registry only logs a plugin that fails to
     * start, so this checks each one.
     */
    static PluginStart installPlugins() throws PluginException, IOException, URISyntaxException {
        // The plugins are the WAR's *-plugin.jar files, beside LogicalDOC's util jar on this classpath. The jar's URL
        // escapes a space as %20, which only a URI turns back.
        File lib = Path.of(PluginRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().toFile();
        // LogicalDOCPlugin names a plugin's install marker after the id setting.
        String marker = "install-" + new ContextProperties().getProperty("id");
        TreeSet<String> before = installedPlugins(marker);
        PluginRegistry registry = PluginRegistry.getInstance();
        registry.init(lib.getPath());
        // The registry only logs a plugin jar whose manifest it can't address, as on a path with a space in it.
        if (registry.getPlugins().isEmpty())
            throw new PluginException("found no plugin LogicalDOC could read in " + lib + " (the errors are above)");
        TreeSet<String> installed = installedPlugins(marker);
        installed.removeAll(before);
        TreeSet<String> failed = new TreeSet<>();
        for (PluginDescriptor plugin : registry.getPlugins())
            if (!registry.getManager().isPluginActivated(plugin))
                failed.add(plugin.getId());
        return new PluginStart(List.copyOf(installed), List.copyOf(failed), registry.isRestartRequired());
    }

    /** The ids of the plugins whose folder under conf.plugindir, named after the plugin, holds its install marker. */
    private static TreeSet<String> installedPlugins(String marker) {
        TreeSet<String> ids = new TreeSet<>();
        File[] folders = PluginRegistry.getPluginsDir().listFiles(File::isDirectory);
        if (folders != null)
            for (File folder : folders)
                if (new File(folder, marker).isFile())
                    ids.add(folder.getName());
        return ids;
    }

    /** The exception's message and each of its causes', which a PluginException's own message leaves out. */
    static String withCauses(Throwable e) {
        StringBuilder text = new StringBuilder(String.valueOf(e.getMessage()));
        Throwable cause = e.getCause();
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause())
            text.append(": ").append(cause);
        return text.toString();
    }

    /**
     * Adds keys the file lacks from the image defaults (upstream adds settings over time, and LogicalDOC fails to
     * start on an unresolvable one), takes the image-owned keys from them, sets the keys Healdan owns, replaces a
     * default an earlier image shipped with this image's, makes the non-breaking spaces in the plain-space keys plain,
     * seeds the container's paths and DB settings the first time (recognised by a missing LDOCHOME, which the shipped
     * defaults lack), then applies the env-owned keys. Returns true when this call seeded the file.
     */
    static boolean applyConfig(File file, File defaults, Map<String, String> envKeys) throws IOException {
        ContextProperties config = new ContextProperties(file);
        Properties pristine = readDefaults(defaults);
        TreeSet<String> added = addMissingDefaults(config, pristine);
        if (!added.isEmpty())
            log("added settings missing from " + file + ": " + String.join(", ", added));
        TreeSet<String> updated = applyImageKeys(config, pristine);
        if (!updated.isEmpty())
            log("updated from the image: " + String.join(", ", updated));
        TreeSet<String> owned = applyHealdanKeys(config);
        if (!owned.isEmpty())
            log("set Healdan's own: " + String.join(", ", owned));
        TreeSet<String> replaced = replaceOldDefaults(config, pristine);
        if (!replaced.isEmpty())
            log("replaced an earlier image's default with this image's: " + String.join(", ", replaced));
        TreeSet<String> spaced = plainSpaces(config);
        if (!spaced.isEmpty())
            log("replaced non-breaking spaces with plain ones in: " + String.join(", ", spaced));
        String home = config.getProperty("LDOCHOME");
        boolean seed = home == null || home.isBlank();
        if (seed) {
            log("seeding " + file);
            ConfigPlan.seedKeys(UUID.randomUUID().toString()).forEach(config::setProperty);
        }
        envKeys.forEach(config::setProperty);
        config.write();
        verifyStored(file, envKeys);
        verifyHealdanKeys(file);
        return seed;
    }

    /** The image's defaults, or none when the file is absent. */
    private static Properties readDefaults(File defaults) throws IOException {
        Properties pristine = new Properties();
        if (defaults.isFile())
            try (FileInputStream in = new FileInputStream(defaults)) {
                pristine.load(in);
            }
        return pristine;
    }

    /** Copies into config every key of the defaults that config lacks; existing values are kept. */
    private static TreeSet<String> addMissingDefaults(ContextProperties config, Properties pristine) {
        TreeSet<String> added = new TreeSet<>();
        for (String key : pristine.stringPropertyNames())
            if (!config.containsKey(key)) {
                config.setProperty(key, pristine.getProperty(key));
                added.add(key);
            }
        return added;
    }

    /** Overwrites each image-owned key whose value differs from the defaults'; returns them as key=value. */
    private static TreeSet<String> applyImageKeys(ContextProperties config, Properties pristine) {
        TreeSet<String> updated = new TreeSet<>();
        for (String key : ConfigPlan.IMAGE_KEYS) {
            String value = pristine.getProperty(key);
            if (value != null && !value.equals(config.getProperty(key))) {
                config.setProperty(key, value);
                updated.add(key + "=" + value);
            }
        }
        return updated;
    }

    /**
     * Sets each key Healdan owns (ConfigPlan.HEALDAN_KEYS) whose value, as written, differs; returns them as key=value.
     * Whatever the file holds is replaced: a placeholder too, so the value never depends on what it would expand to.
     */
    private static TreeSet<String> applyHealdanKeys(ContextProperties config) {
        TreeSet<String> set = new TreeSet<>();
        ConfigPlan.HEALDAN_KEYS.forEach((key, value) -> {
            if (!value.equals(config.get(key))) {
                config.setProperty(key, value);
                set.add(key + "=" + value);
            }
        });
        return set;
    }

    /** Gives each key still holding a default an earlier image shipped (ConfigPlan.REPLACED_DEFAULTS) the image's. */
    private static TreeSet<String> replaceOldDefaults(ContextProperties config, Properties pristine) {
        TreeSet<String> replaced = new TreeSet<>();
        ConfigPlan.REPLACED_DEFAULTS.forEach((key, oldValues) -> {
            String value = pristine.getProperty(key);
            if (value != null && oldValues.contains(config.getProperty(key))) {
                config.setProperty(key, value);
                replaced.add(key);
            }
        });
        return replaced;
    }

    /**
     * Makes the non-breaking spaces in each ConfigPlan.PLAIN_SPACE_KEYS value plain ones. It reads the value as
     * written, not through getProperty, which expands ${...} from system properties and the environment, so the
     * placeholders in it are written back as they were.
     */
    private static TreeSet<String> plainSpaces(ContextProperties config) {
        TreeSet<String> spaced = new TreeSet<>();
        for (String key : ConfigPlan.PLAIN_SPACE_KEYS)
            if (config.get(key) instanceof String value && value.indexOf('\u00a0') >= 0) {
                config.setProperty(key, value.replace('\u00a0', ' '));
                spaced.add(key);
            }
        return spaced;
    }

    /**
     * LogicalDOC writes context.properties one byte per character, so values outside Latin-1 come back changed.
     * Refuse them rather than let Tomcat fail later with a corrupted password or URL. A line break now comes back as
     * written, but in a password or an address it's a slip, such as a secret file's last newline, so it's refused too.
     * run refuses them before anything uses them: the guard signs in with the password before applyConfig stores it,
     * and a sign-in would only say it's wrong.
     */
    private static void refuseUnstorable(Map<String, String> envKeys) {
        for (Map.Entry<String, String> key : envKeys.entrySet())
            if (key.getValue().contains("\r") || key.getValue().contains("\n")
                    || key.getValue().chars().anyMatch(c -> c > 0xFF))
                throw unstorable(key.getKey());
    }

    /** Refuses what refuseUnstorable does, then reads the env-owned keys back from the file, as LogicalDOC will. */
    private static void verifyStored(File file, Map<String, String> envKeys) throws IOException {
        refuseUnstorable(envKeys);
        ContextProperties stored = new ContextProperties(file);
        for (Map.Entry<String, String> key : envKeys.entrySet())
            if (!key.getValue().equals(stored.getProperty(key.getKey()))
                    || !key.getValue().equals(stored.getString(key.getKey())))
                throw unstorable(key.getKey());
    }

    private static IllegalArgumentException unstorable(String key) {
        return new IllegalArgumentException(ConfigPlan.source(key)
                + " cannot be stored in context.properties as given: use only Latin-1 characters and no line breaks");
    }

    /**
     * Reads the keys Healdan owns back from the written file, as LogicalDOC will: a store read back as anything else
     * would start LogicalDOC on another store than Healdan's, so the start stops instead.
     */
    private static void verifyHealdanKeys(File file) throws IOException {
        ContextProperties stored = new ContextProperties(file);
        for (Map.Entry<String, String> key : ConfigPlan.HEALDAN_KEYS.entrySet())
            if (!key.getValue().equals(stored.getProperty(key.getKey())))
                throw new IllegalStateException(key.getKey() + " reads back from " + file + " as '"
                        + stored.getProperty(key.getKey()) + "', not '" + key.getValue() + "'");
    }

    /**
     * Stops the start when the database lacks a table or a column that this version's DbInit scripts create.
     * Healdan never changes LogicalDOC's tables on an existing database, and doesn't here: each change a new version
     * needs is made by hand, as the Install manual's Upgrading page gives it. Without it, whatever reads the table
     * fails, as signing in did for everyone when ld_tenant lacked the 14 quota thresholds upstream sync #215 brought,
     * so the app isn't started at all. A table the scripts create that the database lacks stops it too, as a new
     * plugin's would: nothing else creates the table, and the plugin's code expects it. Tables and columns the
     * database has besides, such as one an older version left, don't count, and nor do the columns' types.
     */
    private static void checkLogicalDocTables(Db db) throws Exception {
        ClassLoader cl = DockerInit.class.getClassLoader();
        Map<String, List<String>> expected = createdColumns(cl, plannedScripts(cl, false, DBMS));
        DbInitPlan.Missing missing = DbInitPlan.missing(expected, db.columns());
        if (!missing.isEmpty())
            throw new InitException("LogicalDOC's tables lack what this version needs: " + missing.describe()
                    + ". Healdan doesn't change LogicalDOC's tables itself, and the app would fail where it reads them,"
                    + " so it isn't started. Make each change Upgrading gives for this version, then start again: "
                    + TABLE_CHANGES);
        log("LogicalDOC's tables have every table and column this version's scripts create (" + expected.size()
                + " tables)");
    }

    /**
     * The DbInit scripts to run, in order: every plugin's DbInit extensions against logicaldoc-core, as the setup
     * wizard's PluginDbInit picks them. Each plugin.xml it can't read is logged and left out. For a fresh database,
     * whose start runs the scripts, it logs the plan, and an extension it can't plan (no position or sqlFile, or a
     * script that isn't on the classpath) stops the start. A later start runs none of them, so there such an
     * extension only leaves its tables out of the check, with a warning.
     *
     * @param dbms the variant to prefer: DBMS for the app's database, "mariadb" for a move's source
     */
    static List<String> plannedScripts(ClassLoader cl, boolean fresh, String dbms) throws Exception {
        return plannedScripts(cl, fresh, dbms, DockerInit::log);
    }

    /** As plannedScripts above, with the plan's lines for a fresh database given to planLog */
    static List<String> plannedScripts(ClassLoader cl, boolean fresh, String dbms, Consumer<String> planLog)
            throws Exception {
        List<DbInitPlan.Entry> entries = new ArrayList<>();
        for (URL manifest : Collections.list(cl.getResources("plugin.xml"))) {
            try (InputStream in = manifest.openStream()) {
                entries.addAll(DbInitPlan.parse(in));
            } catch (SAXException e) {
                log("ignoring unparseable " + manifest + ": " + e.getMessage());
            } catch (IllegalArgumentException e) {
                if (fresh)
                    throw e;
                warnUnchecked(manifest + ": " + e.getMessage());
            }
        }
        List<DbInitPlan.Entry> ordered = DbInitPlan.order(entries);
        if (ordered.stream().noneMatch(e -> e.pluginId().equals("logicaldoc-core")))
            throw new InitException("no DbInit extension of logicaldoc-core found on the classpath");

        List<String> scripts = new ArrayList<>();
        for (DbInitPlan.Entry entry : ordered) {
            String script;
            try {
                script = DbInitPlan.resolve(entry, dbms, name -> cl.getResource(name) != null);
            } catch (IllegalStateException e) {
                if (fresh)
                    throw e;
                warnUnchecked(e.getMessage());
                continue;
            }
            if (fresh)
                planLog.accept("plan: " + entry.position() + " " + entry.pluginId() + " -> " + script);
            scripts.add(script);
        }
        return scripts;
    }

    /** Warns that a DbInit extension that can't be planned leaves its tables out of the check, and why. */
    private static void warnUnchecked(String why) {
        System.err.println("[docker-init] WARNING: LogicalDOC's tables are checked without a DbInit extension that"
                + " can't be planned: " + why);
    }

    /** The tables the scripts create, with their columns (DbInitPlan.createdColumns). */
    static Map<String, List<String>> createdColumns(ClassLoader cl, List<String> scripts) throws IOException {
        List<String> texts = new ArrayList<>();
        for (String script : scripts)
            try (InputStream in = cl.getResourceAsStream(script)) {
                // Same encoding upstream's DBInit reads the scripts with.
                texts.add(new String(in.readAllBytes(), Charset.forName("Cp1252")));
            }
        return DbInitPlan.createdColumns(texts);
    }

    private static void createSchema(Db db) throws Exception {
        ClassLoader cl = DockerInit.class.getClassLoader();
        List<String> scripts = plannedScripts(cl, true, DBMS);
        Map<String, List<String>> expected = createdColumns(cl, scripts);

        log("creating schema");
        runScripts(scripts, db.url, db.user, db.password);

        // DBInit goes on past a statement that fails, so what it made is checked as a later start checks it.
        DbInitPlan.Missing missing = DbInitPlan.missing(expected, db.columns());
        if (!missing.isEmpty())
            throw new InitException("schema initialization incomplete: " + missing.describe()
                    + "; remove the database volume and start again");
        db.recordCompletion();
        log("schema created (" + expected.size() + " tables)");
    }

    /**
     * Runs the DbInit scripts on PostgreSQL with the setup wizard's own DBInit, for a fresh install's first start and
     * for the move from MariaDB. DBInit logs a statement that fails and goes on, so each caller checks what it made.
     */
    static void runScripts(List<String> scripts, String url, String user, String password) {
        DBInit init = new DBInit(scripts);
        init.setDriver(DRIVER);
        init.setUrl(url);
        init.setUsername(user);
        init.setPassword(password);
        init.execute();
    }

    /**
     * Makes the start-up tool's marker, which says the schema is initialised, in the statement's transaction: a start
     * that finds it skips the schema's creation.
     */
    static void recordCompletion(Statement s) throws SQLException {
        s.execute("create table " + MARKER + " (completed timestamp not null)");
        s.execute("insert into " + MARKER + " values (current_timestamp)");
    }

    private static void warnConfigLoss() {
        String rule = "*".repeat(100);
        System.err.println(rule);
        System.err.println("[docker-init] WARNING: context.properties was just created, but the database already has a schema.");
        System.err.println("[docker-init] The encryption key and node id are new, so settings encrypted before will not decrypt.");
        System.err.println("[docker-init] The keys of encrypted storage backends (healdan-storage-keys) are gone too: their files open again only with the backends' recovery keys.");
        System.err.println("[docker-init] Restore /opt/logicaldoc/conf from your backup if you have one (see https://docs.healdan.com/install/backup-and-restore/).");
        System.err.println(rule);
    }

    private static void log(String message) {
        System.out.println("[docker-init] " + message);
    }

    /** Thin JDBC helper; every call opens its own short-lived connection. */
    static final class Db {

        final String url;

        final String user;

        final String password;

        Db(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        /** Waits for the database to answer, and stops the start when it hasn't within the seconds given. */
        void await(int seconds) throws InitException, InterruptedException {
            SQLException e = attempt(seconds);
            if (e != null)
                throw new InitException("database not reachable at " + url + " after " + seconds + "s: "
                        + e.getMessage());
        }

        /** Waits for the database to answer: false when it hasn't within the seconds given. */
        boolean answers(int seconds) throws InitException, InterruptedException {
            return attempt(seconds) == null;
        }

        /**
         * Connects until the database answers, then returns null, or until the seconds have passed, then returns the
         * last connection's error. Each connection has only the time left (pgjdbc's loginTimeout, a second at least),
         * so a host that never answers, or answers as something other than PostgreSQL and then says nothing, can't
         * hold the start past it. A sign-in the database refuses stops the start at once.
         */
        private SQLException attempt(int seconds) throws InitException, InterruptedException {
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (true) {
                Properties login = new Properties();
                login.setProperty("user", user);
                login.setProperty("password", password);
                login.setProperty("loginTimeout",
                        String.valueOf(Math.max(1, (deadline - System.nanoTime()) / 1_000_000_000L)));
                try (Connection c = DriverManager.getConnection(url, login); Statement s = c.createStatement()) {
                    s.execute("select 1");
                    return null;
                } catch (SQLException e) {
                    // SQLSTATE class 28 = invalid authorization: retrying will not help.
                    if (e.getSQLState() != null && e.getSQLState().startsWith("28"))
                        throw new InitException("database authentication failed for user '" + user + "' at " + url
                                + ": " + e.getMessage());
                    if (System.nanoTime() > deadline)
                        return e;
                    log("waiting for database at " + url + " (" + e.getMessage() + ")");
                }
                Thread.sleep(2000);
            }
        }

        /**
         * Whether the database holds a move from MariaDB: null when the schema the connection creates tables in
         * (current_schema(), where the move makes it, and whose tables the move empties) has no
         * StartGuard.MOVE_MARKER table, true when the move finished, and false when it didn't, a table with no row
         * included, as a move stopped before it wrote one leaves. The marker is looked up in that schema by name,
         * never through the search path, which could find one in a later schema. It asks whether the table exists
         * first, so nothing fails in PostgreSQL's log.
         */
        Boolean move() throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                String marker;
                try (ResultSet r = s.executeQuery("select quote_ident(current_schema()) || '.' || quote_ident('"
                        + StartGuard.MOVE_MARKER + "'), to_regclass(quote_ident(current_schema()) || '.'"
                        + " || quote_ident('" + StartGuard.MOVE_MARKER + "')) is not null")) {
                    r.next();
                    // With no current schema the answer is null, which reads as false: there's no marker
                    if (!r.getBoolean(2))
                        return null;
                    marker = r.getString(1);
                }
                try (ResultSet r = s.executeQuery("select count(*) > 0 and count(finished) = count(*) from "
                        + marker)) {
                    r.next();
                    return r.getBoolean(1);
                }
            }
        }

        void recordCompletion() throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                DockerInit.recordCompletion(s);
            }
        }

        /**
         * The columns of each table in the schema the connection creates tables in, by the names the database gives
         * them.
         */
        Map<String, Set<String>> columns() throws SQLException {
            Map<String, Set<String>> columns = new HashMap<>();
            try (Connection c = connect(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("select table_name, column_name from information_schema.columns"
                            + " where table_schema = current_schema()")) {
                while (r.next())
                    columns.computeIfAbsent(r.getString(1), t -> new HashSet<>()).add(r.getString(2));
            }
            return columns;
        }

        /**
         * The tables of the list the connection's names don't find. It asks without a statement that fails, as each
         * failed one is an ERROR in PostgreSQL's own log.
         */
        List<String> missingTables(List<String> tables) throws SQLException {
            List<String> missing = new ArrayList<>();
            try (Connection c = connect();
                    PreparedStatement s = c.prepareStatement("select to_regclass(?) is not null")) {
                for (String table : tables) {
                    s.setString(1, table);
                    try (ResultSet r = s.executeQuery()) {
                        r.next();
                        if (!r.getBoolean(1))
                            missing.add(table);
                    }
                }
            }
            return missing;
        }

        /** PostgreSQL's server_version_num, such as 180004 for 18.4 */
        int versionNum() throws SQLException {
            return Integer.parseInt(text("show server_version_num"));
        }

        /** The database's encoding, by PostgreSQL's name for it, such as UTF8 */
        String encoding() throws SQLException {
            return text("select pg_encoding_to_char(encoding) from pg_database where datname = current_database()");
        }

        /** The database's locale provider ('b', 'c' or 'i'), its collation and its locale (null for libc's) */
        String[] locale() throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("select datlocprovider, datcollate, datlocale from pg_database"
                            + " where datname = current_database()")) {
                r.next();
                return new String[] { r.getString(1), r.getString(2), r.getString(3) };
            }
        }

        /** The schema the connection creates tables in, or null when its search_path names none that exists */
        String schema() throws SQLException {
            return text("select current_schema()");
        }

        /** Whether the role may create tables in that schema: not when there's none */
        boolean canCreate() throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "select has_schema_privilege(current_user, current_schema(), 'CREATE')")) {
                r.next();
                // A schema that is null makes the answer null, which reads as false
                return r.getBoolean(1);
            }
        }

        /**
         * Gives the connection's role DbChecks.LOCK_TIMEOUT in this database, which each of its later connections
         * takes. A role may set its own defaults, so this needs no other right.
         */
        void setLockTimeout() throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                String database;
                try (ResultSet r = s.executeQuery("select current_database()")) {
                    r.next();
                    database = r.getString(1);
                }
                s.execute("alter role current_user in database \"" + database.replace("\"", "\"\"")
                        + "\" set lock_timeout = '" + DbChecks.LOCK_TIMEOUT + "'");
            }
        }

        /** The lock_timeout a new connection of the role has */
        String lockTimeout() throws SQLException {
            return text("show lock_timeout");
        }

        /** The first column of the one row the query returns, as text */
        private String text(String query) throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
                r.next();
                return r.getString(1);
            }
        }
    }

    /** A refusal or failure whose message the start-up tool, or the move, prints as it is */
    static final class InitException extends Exception {

        private static final long serialVersionUID = 1L;

        InitException(String message) {
            super(message);
        }
    }
}
