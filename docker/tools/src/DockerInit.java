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

import org.java.plugin.registry.PluginDescriptor;
import org.xml.sax.SAXException;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.dbinit.DBInit;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.plugin.PluginRegistry;

/**
 * Container init, run by entrypoint.sh before Tomcat starts: works out the trusted proxies, applies the
 * container-managed context.properties keys, waits for the database, creates the schema on first boot with the
 * setup wizard's own DBInit, or on a later one checks that LogicalDOC's tables have what this version's scripts
 * create, and installs LogicalDOC's plugins.
 */
public final class DockerInit {

    static final File CONFIG = new File(ConfigPlan.HOME + "/conf/context.properties");

    /** The image's pristine context.properties, the source of keys an older conf file lacks. */
    static final File DEFAULTS = new File(ConfigPlan.HOME + "/defaults/context.properties");

    private static final String DRIVER = "org.mariadb.jdbc.Driver";

    private static final String DBMS = "mariadb";

    /** Created only after the schema is verified, so a first start that failed part-way is recognisable. */
    private static final String MARKER = "docker_init";

    /** SQLSTATE MariaDB reports for a table that does not exist. */
    private static final String NO_SUCH_TABLE = "42S02";

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
        boolean seeded = applyConfig(CONFIG, DEFAULTS, envKeys);

        Db db = new Db(envKeys.get("jdbc.url"), envKeys.get("jdbc.username"), envKeys.get("jdbc.password"));
        db.await(waitSeconds);
        List<String> missing = db.missingTables(List.of("ld_user", MARKER));
        if (missing.isEmpty()) {
            log("schema present, skipping database initialization");
            if (seeded)
                warnConfigLoss();
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
     * Refuse them here rather than let Tomcat fail later with a corrupted password or URL. A line break now comes
     * back as written, but in a password or an address it's a slip, such as a secret file's last newline, so it's
     * refused too.
     */
    private static void verifyStored(File file, Map<String, String> envKeys) throws IOException {
        ContextProperties stored = new ContextProperties(file);
        for (Map.Entry<String, String> key : envKeys.entrySet())
            if (key.getValue().contains("\r") || key.getValue().contains("\n")
                    || !key.getValue().equals(stored.getProperty(key.getKey()))
                    || !key.getValue().equals(stored.getString(key.getKey())))
                throw new IllegalArgumentException(ConfigPlan.source(key.getKey())
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
        Map<String, List<String>> expected = createdColumns(cl, plannedScripts(cl, false));
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
     */
    static List<String> plannedScripts(ClassLoader cl, boolean fresh) throws Exception {
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
                script = DbInitPlan.resolve(entry, DBMS, name -> cl.getResource(name) != null);
            } catch (IllegalStateException e) {
                if (fresh)
                    throw e;
                warnUnchecked(e.getMessage());
                continue;
            }
            if (fresh)
                log("plan: " + entry.position() + " " + entry.pluginId() + " -> " + script);
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
        List<String> scripts = plannedScripts(cl, true);
        Map<String, List<String>> expected = createdColumns(cl, scripts);

        log("creating schema");
        DBInit init = new DBInit(scripts);
        init.setDriver(DRIVER);
        init.setUrl(db.url);
        init.setUsername(db.user);
        init.setPassword(db.password);
        init.execute();

        // DBInit goes on past a statement that fails, so what it made is checked as a later start checks it.
        DbInitPlan.Missing missing = DbInitPlan.missing(expected, db.columns());
        if (!missing.isEmpty())
            throw new InitException("schema initialization incomplete: " + missing.describe()
                    + "; remove the database volume and start again");
        db.recordCompletion();
        log("schema created (" + expected.size() + " tables)");
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
    private static final class Db {

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

        void await(int seconds) throws InitException, InterruptedException {
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (true) {
                try (Connection c = connect(); Statement s = c.createStatement()) {
                    s.execute("select 1");
                    return;
                } catch (SQLException e) {
                    // SQLSTATE class 28 = invalid authorization: retrying will not help.
                    if (e.getSQLState() != null && e.getSQLState().startsWith("28"))
                        throw new InitException("database authentication failed for user '" + user + "' at " + url
                                + ": " + e.getMessage());
                    if (System.nanoTime() > deadline)
                        throw new InitException("database not reachable at " + url + " after " + seconds + "s: "
                                + e.getMessage());
                    log("waiting for database at " + url + " (" + e.getMessage() + ")");
                }
                Thread.sleep(2000);
            }
        }

        void recordCompletion() throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("create table " + MARKER + " (completed timestamp not null)");
                s.execute("insert into " + MARKER + " values (current_timestamp)");
            }
        }

        /** The columns of each table in the connection's database, by the names the database gives them. */
        Map<String, Set<String>> columns() throws SQLException {
            Map<String, Set<String>> columns = new HashMap<>();
            try (Connection c = connect(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("select table_name, column_name from information_schema.columns"
                            + " where table_schema = database()")) {
                while (r.next())
                    columns.computeIfAbsent(r.getString(1), t -> new HashSet<>()).add(r.getString(2));
            }
            return columns;
        }

        List<String> missingTables(List<String> tables) throws SQLException {
            List<String> missing = new ArrayList<>();
            try (Connection c = connect(); Statement s = c.createStatement()) {
                for (String table : tables) {
                    try {
                        s.executeQuery("select 1 from " + table + " where 1=0").close();
                    } catch (SQLException e) {
                        if (!NO_SUCH_TABLE.equals(e.getSQLState()))
                            throw e;
                        missing.add(table);
                    }
                }
            }
            return missing;
        }
    }

    private static final class InitException extends Exception {

        private static final long serialVersionUID = 1L;

        InitException(String message) {
            super(message);
        }
    }
}
