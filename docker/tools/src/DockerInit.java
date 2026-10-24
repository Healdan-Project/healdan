import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;

import org.xml.sax.SAXException;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.dbinit.DBInit;

/**
 * Container init, run by entrypoint.sh before Tomcat starts: applies the container-managed context.properties
 * keys, waits for the database and creates the schema on first boot with the setup wizard's own DBInit.
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
    }

    static void run(Map<String, String> env) throws Exception {
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
        } else if (missing.size() == 2) {
            createSchema(db);
        } else {
            throw new InitException("the database holds part of a LogicalDOC schema, but its initialization did not"
                    + " finish (an earlier first start failed or was interrupted); remove the database volume and"
                    + " start again");
        }
    }

    /**
     * Adds keys the file lacks from the image defaults (upstream adds settings over time, and LogicalDOC fails to
     * start on an unresolvable one), seeds the container's paths and DB settings the first time (recognised by a
     * missing LDOCHOME, which the shipped defaults lack), then applies the env-owned keys. Returns true when this
     * call seeded the file.
     */
    static boolean applyConfig(File file, File defaults, Map<String, String> envKeys) throws IOException {
        ContextProperties config = new ContextProperties(file);
        TreeSet<String> added = addMissingDefaults(config, defaults);
        if (!added.isEmpty())
            log("added settings missing from " + file + ": " + String.join(", ", added));
        String home = config.getProperty("LDOCHOME");
        boolean seed = home == null || home.isBlank();
        if (seed) {
            log("seeding " + file);
            ConfigPlan.seedKeys(UUID.randomUUID().toString()).forEach(config::setProperty);
        }
        envKeys.forEach(config::setProperty);
        config.write();
        verifyStored(file, envKeys);
        return seed;
    }

    /** Copies into config every key of the defaults file that config lacks; existing values are kept. */
    private static TreeSet<String> addMissingDefaults(ContextProperties config, File defaults) throws IOException {
        TreeSet<String> added = new TreeSet<>();
        if (!defaults.isFile())
            return added;
        Properties pristine = new Properties();
        try (FileInputStream in = new FileInputStream(defaults)) {
            pristine.load(in);
        }
        for (String key : pristine.stringPropertyNames())
            if (!config.containsKey(key)) {
                config.setProperty(key, pristine.getProperty(key));
                added.add(key);
            }
        return added;
    }

    /**
     * LogicalDOC writes context.properties one byte per character, so values outside Latin-1 come back changed.
     * Refuse them here rather than let Tomcat fail later with a corrupted password or URL.
     */
    private static void verifyStored(File file, Map<String, String> envKeys) throws IOException {
        ContextProperties stored = new ContextProperties(file);
        for (Map.Entry<String, String> key : envKeys.entrySet())
            if (!key.getValue().equals(stored.getProperty(key.getKey()))
                    || !key.getValue().equals(stored.getString(key.getKey())))
                throw new IllegalArgumentException(ConfigPlan.source(key.getKey())
                        + " cannot be stored in context.properties as given: use only Latin-1 characters and no line breaks");
    }

    private static void createSchema(Db db) throws Exception {
        ClassLoader cl = DockerInit.class.getClassLoader();
        List<DbInitPlan.Entry> entries = new ArrayList<>();
        for (URL manifest : Collections.list(cl.getResources("plugin.xml"))) {
            try (InputStream in = manifest.openStream()) {
                entries.addAll(DbInitPlan.parse(in));
            } catch (SAXException e) {
                log("ignoring unparseable " + manifest + ": " + e.getMessage());
            }
        }
        List<DbInitPlan.Entry> ordered = DbInitPlan.order(entries);
        if (ordered.stream().noneMatch(e -> e.pluginId().equals("logicaldoc-core")))
            throw new InitException("no DbInit extension of logicaldoc-core found on the classpath");

        List<String> scripts = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        for (DbInitPlan.Entry entry : ordered) {
            String script = DbInitPlan.resolve(entry, DBMS, name -> cl.getResource(name) != null);
            log("plan: " + entry.position() + " " + entry.pluginId() + " -> " + script);
            scripts.add(script);
            try (InputStream in = cl.getResourceAsStream(script)) {
                // Same encoding upstream's DBInit reads the scripts with.
                tables.addAll(DbInitPlan.createdTables(new String(in.readAllBytes(), Charset.forName("Cp1252"))));
            }
        }

        log("creating schema");
        DBInit init = new DBInit(scripts);
        init.setDriver(DRIVER);
        init.setUrl(db.url);
        init.setUsername(db.user);
        init.setPassword(db.password);
        init.execute();

        List<String> missing = db.missingTables(tables);
        if (!missing.isEmpty())
            throw new InitException("schema initialization incomplete, missing tables " + missing
                    + "; remove the database volume and start again");
        db.recordCompletion();
        log("schema created (" + tables.size() + " tables)");
    }

    private static void warnConfigLoss() {
        String rule = "*".repeat(100);
        System.err.println(rule);
        System.err.println("[docker-init] WARNING: context.properties was just created, but the database already has a schema.");
        System.err.println("[docker-init] The encryption key and node id are new, so settings encrypted before will not decrypt.");
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
