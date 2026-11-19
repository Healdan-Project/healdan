// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigPlanTest {

    public static void main(String[] args) {
        seedKeysUseAbsoluteRepositoryPaths();
        seedKeysLeaveTheDatabaseToHealdanKeys();
        envKeysUseDefaults();
        envKeysHonourOverrides();
        blankValuesFallBackToDefaults();
        passwordIsRequired();
        passwordKeptVerbatim();
        passwordRejectsSyntaxLogicalDocInterprets();
        passwordRejectsLeadingWhitespace();
        portMustBeNumeric();
        hostAndNameCantChangeTheUrl();
        publicUrlOptionalAndNormalised();
        dbWaitSecondsValidated();
        healdanKeysAreTheStoreAndTheDatabase();
        Check.done("ConfigPlanTest");
    }

    private static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    private static void seedKeysUseAbsoluteRepositoryPaths() {
        Map<String, String> seed = ConfigPlan.seedKeys("abc");
        for (String key : List.of("store.1.dir", "index.dir", "conf.userdir", "conf.plugindir", "conf.importdir",
                "conf.exportdir", "conf.logdir", "conf.dbdir")) {
            String v = seed.get(key);
            Check.isTrue(v != null && v.startsWith("/opt/logicaldoc/repository/") && v.endsWith("/"),
                    key + " is an absolute repository dir, was " + v);
        }
        Check.eq("/opt/logicaldoc/repository/docs/", seed.get("store.1.dir"), "store.1.dir");
        Check.eq("/opt/logicaldoc/repository/impex/in/", seed.get("conf.importdir"), "conf.importdir");
        Check.eq("/opt/logicaldoc", seed.get("LDOCHOME"), "LDOCHOME");
        Check.eq("abc", seed.get("id"), "id");
        Check.eq("true", seed.get("initialized"), "initialized");
        Check.eq(12, seed.size(), "number of seed keys");
    }

    private static void seedKeysLeaveTheDatabaseToHealdanKeys() {
        Map<String, String> seed = ConfigPlan.seedKeys("x");
        for (String key : List.of("jdbc.driver", "jdbc.dbms", "hibernate.dialect"))
            Check.isTrue(!seed.containsKey(key), key + " is set on every start, not seeded once");
        Check.eq("SELECT 1", seed.get("jdbc.validationQuery"), "validation query");
    }

    private static void envKeysUseDefaults() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw"));
        Check.eq("jdbc:postgresql://db:5432/logicaldoc?logServerErrorDetail=false", keys.get("jdbc.url"),
                "default url");
        // pgjdbc puts the server's detail into every PSQLException's message by default ("Failing row contains (...)",
        // "Key (...)=(...) already exists"), with the row's values in it, and Healdan never logs a row's values.
        Check.isTrue(keys.get("jdbc.url").endsWith("?logServerErrorDetail=false"), "the server's detail stays out");
        Check.eq("logicaldoc", keys.get("jdbc.username"), "default user");
        Check.eq("pw", keys.get("jdbc.password"), "password");
        Check.isTrue(!keys.containsKey("server.url"), "server.url untouched when LOGICALDOC_PUBLIC_URL is unset");
    }

    private static void envKeysHonourOverrides() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", "pg.example", "DB_PORT",
                "6543", "DB_NAME", "healdan", "DB_USER", "ldoc"));
        Check.eq("jdbc:postgresql://pg.example:6543/healdan?logServerErrorDetail=false", keys.get("jdbc.url"),
                "overridden url");
        Check.eq("ldoc", keys.get("jdbc.username"), "overridden user");
    }

    private static void blankValuesFallBackToDefaults() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", " ", "DB_USER", ""));
        Check.eq("jdbc:postgresql://db:5432/logicaldoc?logServerErrorDetail=false", keys.get("jdbc.url"),
                "blank host uses the default");
        Check.eq("logicaldoc", keys.get("jdbc.username"), "blank user uses the default");
    }

    private static void passwordIsRequired() {
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env()), "missing password");
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "")), "empty password");
    }

    private static void passwordKeptVerbatim() {
        String awkward = "sm0ke,p=a\\ss $x #1 é:! ";
        Check.eq(awkward, ConfigPlan.envKeys(env("DB_PASSWORD", awkward)).get("jdbc.password"),
                "password is neither trimmed nor altered");
    }

    private static void passwordRejectsSyntaxLogicalDocInterprets() {
        Check.throwsWith("${", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "a${b}c")), "placeholder syntax");
        Check.throwsWith("_b64_", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "_b64_abc")), "base64 prefix");
    }

    private static void passwordRejectsLeadingWhitespace() {
        // context.properties cannot keep leading whitespace in a value: ContextProperties.write() drops it.
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env("DB_PASSWORD", " pw")), "leading space");
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "\tpw")), "leading tab");
    }

    private static void portMustBeNumeric() {
        Check.throwsWith("DB_PORT", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_PORT", "33o6")),
                "non-numeric port");
    }

    /**
     * DB_HOST and DB_NAME go into the URL as they are: a ?, #, %, / or space in either would drop or garble
     * logServerErrorDetail=false, which keeps row values out of every log line. They're refused, by the rule the move
     * checks its source's with.
     */
    private static void hostAndNameCantChangeTheUrl() {
        for (String host : List.of("db?logServerErrorDetail=true", "db#", "db%2F", "db/other", "pg db", "db\t2"))
            Check.throwsWith("DB_HOST", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", host)),
                    "the host '" + host + "'");
        for (String name : List.of("logicaldoc?logServerErrorDetail=true", "logicaldoc#", "logical%64oc",
                "logicaldoc/x", "logical doc"))
            Check.throwsWith("DB_NAME", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_NAME", name)),
                    "the name '" + name + "'");
        Check.eq("jdbc:postgresql://[fd00::1]:5432/healdan_prod-2$?logServerErrorDetail=false",
                ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", "[fd00::1]", "DB_NAME", "healdan_prod-2$"))
                        .get("jdbc.url"), "an IPv6 address in brackets, and a name with _, - and $");
        Check.eq("jdbc:postgresql://pg-1.example.org:5432/logicaldoc?logServerErrorDetail=false",
                ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", " pg-1.example.org ")).get("jdbc.url"),
                "a host name, trimmed");
        Check.isTrue(ConfigPlan.HOST.matcher("mariadb").matches() && ConfigPlan.DATABASE.matcher("logicaldoc")
                .matches(), "the move's defaults match the rule it shares");
    }

    private static void publicUrlOptionalAndNormalised() {
        Check.eq("https://docs.example.org/",
                ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "LOGICALDOC_PUBLIC_URL", "https://docs.example.org"))
                        .get("server.url"),
                "trailing slash appended");
        Check.eq("https://docs.example.org/",
                ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "LOGICALDOC_PUBLIC_URL", "https://docs.example.org/"))
                        .get("server.url"),
                "trailing slash kept");
    }

    private static void healdanKeysAreTheStoreAndTheDatabase() {
        Check.eq(Map.of("store", "com.logicaldoc.healdan.storage.HealdanStorage",
                "jdbc.driver", "org.postgresql.Driver",
                "jdbc.dbms", "postgresql",
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"), ConfigPlan.HEALDAN_KEYS,
                "Healdan's own keys: store as HealdanStorage, and PostgreSQL's driver, dbms and dialect");
        // Each key has one owner, or two steps of a start would each set it.
        Map<String, String> envKeys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "LOGICALDOC_PUBLIC_URL",
                "https://docs.example.org"));
        for (String key : ConfigPlan.HEALDAN_KEYS.keySet())
            Check.isTrue(!ConfigPlan.seedKeys("x").containsKey(key) && !envKeys.containsKey(key)
                    && !ConfigPlan.IMAGE_KEYS.contains(key) && !ConfigPlan.REPLACED_DEFAULTS.containsKey(key)
                    && !ConfigPlan.PLAIN_SPACE_KEYS.contains(key), key + " is Healdan's alone");
    }

    private static void dbWaitSecondsValidated() {
        Check.eq(60, ConfigPlan.dbWaitSeconds(env()), "default wait");
        Check.eq(5, ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "5")), "explicit wait");
        Check.throwsWith("DB_WAIT_SECONDS", () -> ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "soon")),
                "non-numeric wait");
        Check.throwsWith("DB_WAIT_SECONDS", () -> ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "0")),
                "zero wait");
    }
}
