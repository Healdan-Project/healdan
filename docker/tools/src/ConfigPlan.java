import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides which context.properties keys the container manages. Pure logic over an environment map; DockerInit
 * applies the result with LogicalDOC's own ContextProperties.
 */
public final class ConfigPlan {

    public static final String HOME = "/opt/logicaldoc";

    private static final String REPO = HOME + "/repository";

    private ConfigPlan() {
    }

    /** Written once, when the container first sees context.properties; LogicalDOC owns them afterwards. */
    public static Map<String, String> seedKeys(String id) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("store.1.dir", REPO + "/docs/");
        keys.put("index.dir", REPO + "/index/");
        keys.put("conf.userdir", REPO + "/users/");
        keys.put("conf.plugindir", REPO + "/plugins/");
        keys.put("conf.importdir", REPO + "/impex/in/");
        keys.put("conf.exportdir", REPO + "/impex/out/");
        keys.put("conf.logdir", REPO + "/logs/");
        keys.put("conf.dbdir", REPO + "/db/");
        keys.put("LDOCHOME", HOME);
        keys.put("jdbc.driver", "org.mariadb.jdbc.Driver");
        keys.put("jdbc.dbms", "mariadb");
        keys.put("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect");
        keys.put("jdbc.validationQuery", "SELECT 1");
        keys.put("initialized", "true");
        // Pre-set so the core plugin's install marker is stable from the first boot.
        keys.put("id", id);
        return keys;
    }

    /** Re-applied on every boot, so the environment stays the source of truth for them. */
    public static Map<String, String> envKeys(Map<String, String> env) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("jdbc.url", jdbcUrl(env));
        keys.put("jdbc.username", value(env, "DB_USER", "logicaldoc"));
        keys.put("jdbc.password", password(env));
        String publicUrl = value(env, "LOGICALDOC_PUBLIC_URL", "");
        if (!publicUrl.isEmpty())
            keys.put("server.url", publicUrl.endsWith("/") ? publicUrl : publicUrl + "/");
        return keys;
    }

    /** The environment variable(s) an env-owned key is built from, for error messages. */
    public static String source(String key) {
        return switch (key) {
        case "jdbc.url" -> "DB_HOST, DB_PORT or DB_NAME";
        case "jdbc.username" -> "DB_USER";
        case "jdbc.password" -> "DB_PASSWORD";
        case "server.url" -> "LOGICALDOC_PUBLIC_URL";
        default -> key;
        };
    }

    /** How long to wait for the database before giving up. */
    public static int dbWaitSeconds(Map<String, String> env) {
        String raw = value(env, "DB_WAIT_SECONDS", "60");
        if (!raw.matches("\\d{1,6}") || Integer.parseInt(raw) == 0)
            throw new IllegalArgumentException("DB_WAIT_SECONDS must be a positive number of seconds, got '" + raw + "'");
        return Integer.parseInt(raw);
    }

    private static String jdbcUrl(Map<String, String> env) {
        String port = value(env, "DB_PORT", "3306");
        if (!port.matches("\\d{1,5}"))
            throw new IllegalArgumentException("DB_PORT must be a port number, got '" + port + "'");
        return "jdbc:mariadb://" + value(env, "DB_HOST", "db") + ":" + port + "/" + value(env, "DB_NAME", "logicaldoc");
    }

    private static String password(Map<String, String> env) {
        String password = env.get("DB_PASSWORD");
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("DB_PASSWORD is not set; copy .env.example to .env and set it");
        // LogicalDOC expands ${...} and decodes a _b64_ prefix when it reads jdbc.password through getString().
        if (password.contains("${"))
            throw new IllegalArgumentException("DB_PASSWORD must not contain '${'");
        if (password.startsWith("_b64_"))
            throw new IllegalArgumentException("DB_PASSWORD must not start with '_b64_'");
        // ContextProperties.write() does not escape leading whitespace, so it would be lost on the next read.
        if (Character.isWhitespace(password.charAt(0)))
            throw new IllegalArgumentException("DB_PASSWORD must not start with a space or tab");
        return password;
    }

    /** Blank counts as unset, so an empty line in .env falls back to the default. */
    private static String value(Map<String, String> env, String name, String fallback) {
        String v = env.get(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
