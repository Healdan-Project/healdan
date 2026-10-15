import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigPlanTest {

    public static void main(String[] args) {
        seedKeysUseAbsoluteRepositoryPaths();
        seedKeysConfigureMariaDb();
        envKeysUseDefaults();
        envKeysHonourOverrides();
        blankValuesFallBackToDefaults();
        passwordIsRequired();
        passwordKeptVerbatim();
        passwordRejectsSyntaxLogicalDocInterprets();
        portMustBeNumeric();
        publicUrlOptionalAndNormalised();
        dbWaitSecondsValidated();
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
        Check.eq(15, seed.size(), "number of seed keys");
    }

    private static void seedKeysConfigureMariaDb() {
        Map<String, String> seed = ConfigPlan.seedKeys("x");
        Check.eq("org.mariadb.jdbc.Driver", seed.get("jdbc.driver"), "driver");
        Check.eq("mariadb", seed.get("jdbc.dbms"), "dbms");
        Check.eq("org.hibernate.dialect.MariaDBDialect", seed.get("hibernate.dialect"), "dialect");
        Check.eq("SELECT 1", seed.get("jdbc.validationQuery"), "validation query");
    }

    private static void envKeysUseDefaults() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw"));
        Check.eq("jdbc:mariadb://db:3306/logicaldoc", keys.get("jdbc.url"), "default url");
        Check.eq("logicaldoc", keys.get("jdbc.username"), "default user");
        Check.eq("pw", keys.get("jdbc.password"), "password");
        Check.isTrue(!keys.containsKey("server.url"), "server.url untouched when LOGICALDOC_PUBLIC_URL is unset");
    }

    private static void envKeysHonourOverrides() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", "mariadb.lan", "DB_PORT",
                "3307", "DB_NAME", "docs", "DB_USER", "ldoc"));
        Check.eq("jdbc:mariadb://mariadb.lan:3307/docs", keys.get("jdbc.url"), "overridden url");
        Check.eq("ldoc", keys.get("jdbc.username"), "overridden user");
    }

    private static void blankValuesFallBackToDefaults() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", " ", "DB_USER", ""));
        Check.eq("jdbc:mariadb://db:3306/logicaldoc", keys.get("jdbc.url"), "blank host uses the default");
        Check.eq("logicaldoc", keys.get("jdbc.username"), "blank user uses the default");
    }

    private static void passwordIsRequired() {
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env()), "missing password");
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "")), "empty password");
    }

    private static void passwordKeptVerbatim() {
        String awkward = " sm0ke,p=a\\ss $x #1 é:! ";
        Check.eq(awkward, ConfigPlan.envKeys(env("DB_PASSWORD", awkward)).get("jdbc.password"),
                "password is neither trimmed nor altered");
    }

    private static void passwordRejectsSyntaxLogicalDocInterprets() {
        Check.throwsWith("${", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "a${b}c")), "placeholder syntax");
        Check.throwsWith("_b64_", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "_b64_abc")), "base64 prefix");
    }

    private static void portMustBeNumeric() {
        Check.throwsWith("DB_PORT", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_PORT", "33o6")),
                "non-numeric port");
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

    private static void dbWaitSecondsValidated() {
        Check.eq(60, ConfigPlan.dbWaitSeconds(env()), "default wait");
        Check.eq(5, ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "5")), "explicit wait");
        Check.throwsWith("DB_WAIT_SECONDS", () -> ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "soon")),
                "non-numeric wait");
        Check.throwsWith("DB_WAIT_SECONDS", () -> ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "0")),
                "zero wait");
    }
}
