import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.logicaldoc.util.config.ContextProperties;

/** Round-trips config edits through LogicalDOC's own ContextProperties, starting from the shipped defaults. */
public class ConfigFileTest {

    // Leading whitespace is refused by ConfigPlan (the file format cannot keep it); everything else must survive.
    private static final String AWKWARD = "sm0ke,p=a\\ss $x #1 é:! ";

    public static void main(String[] args) throws Exception {
        File defaults = new File(args[0]);
        File conf = new File(Files.createTempDirectory("configfile").toFile(), "context.properties");
        Files.copy(defaults.toPath(), conf.toPath(), StandardCopyOption.REPLACE_EXISTING);

        boolean seeded = DockerInit.applyConfig(conf, defaults,
                ConfigPlan.envKeys(Map.of("DB_PASSWORD", AWKWARD, "LOGICALDOC_PUBLIC_URL", "https://docs.example.org")));
        Check.isTrue(seeded, "first call seeds the shipped defaults");

        ContextProperties reread = new ContextProperties(conf);
        Check.eq(AWKWARD, reread.getProperty("jdbc.password"), "password via getProperty (expands ${...})");
        Check.eq(AWKWARD, reread.getString("jdbc.password"), "password via getString (also decodes _b64_)");
        Properties plain = new Properties();
        try (FileInputStream in = new FileInputStream(conf)) {
            plain.load(in);
        }
        Check.eq(AWKWARD, plain.getProperty("jdbc.password"), "password via java.util.Properties");
        Check.isTrue(reread.getProperty("id").matches("[0-9a-f-]{36}"), "id is a UUID: " + reread.getProperty("id"));
        long idLines = Files.readAllLines(conf.toPath(), StandardCharsets.ISO_8859_1).stream()
                .filter(l -> l.matches("^id\\s*[=:].*")).count();
        Check.eq(1L, idLines, "id appears exactly once");
        Check.eq("/opt/logicaldoc/repository/index/", reread.getProperty("index.dir"), "index.dir seeded");
        Check.eq("jdbc:mariadb://db:3306/logicaldoc", reread.getProperty("jdbc.url"), "jdbc.url applied");
        Check.eq("https://docs.example.org/", reread.getProperty("server.url"), "server.url applied");
        Check.eq("true", reread.getProperty("webservice.enabled"), "unmanaged key preserved");

        // Why ConfigPlan refuses '${' and '_b64_' in the password: no reader of the file sees it raw.
        ContextProperties probe = new ContextProperties(conf);
        probe.setProperty("probe", "${java.home}");
        Check.eq(System.getProperty("java.home"), probe.getProperty("probe"), "getProperty expands ${...}");
        probe.setProperty("probe", "_b64_eA==");
        Check.eq("_b64_eA==", probe.getProperty("probe"), "getProperty leaves _b64_ alone");
        Check.eq("x", probe.getString("probe"), "getString decodes _b64_");

        // A later boot applies env keys only: a seed-once key the admin changed must survive. A key the conf
        // file lacks (as after an upstream sync adds one to the defaults) is filled in from the defaults, and the
        // image-owned keys an older image wrote are replaced by this image's.
        reread.setProperty("conf.importdir", "/srv/custom-in/");
        reread.setProperty("webservice.enabled", "false");
        reread.remove("cookies.samesite");
        reread.setProperty("product.release", "9.0.0");
        reread.setProperty("product.year", "2006-2020");
        reread.write();
        boolean seededAgain = DockerInit.applyConfig(conf, defaults, ConfigPlan.envKeys(Map.of("DB_PASSWORD", "rotated")));
        ContextProperties later = new ContextProperties(conf);
        Check.isTrue(!seededAgain, "second call does not seed");
        Check.eq("/srv/custom-in/", later.getProperty("conf.importdir"), "admin-changed seed key preserved");
        Check.eq("false", later.getProperty("webservice.enabled"), "admin-changed default key preserved");
        Check.eq("lax", later.getProperty("cookies.samesite"), "key missing from conf restored from the defaults");
        Check.eq("rotated", later.getProperty("jdbc.password"), "env key re-applied");
        Properties shipped = new Properties();
        try (FileInputStream in = new FileInputStream(defaults)) {
            shipped.load(in);
        }
        for (String key : ConfigPlan.IMAGE_KEYS) {
            Check.isTrue(shipped.getProperty(key) != null, key + " is in the shipped defaults");
            Check.eq(shipped.getProperty(key), later.getProperty(key), key + " taken from the image");
        }

        // GWT's loaders name the build's *.cache.js files, so a browser must check them on every load: their rule
        // comes before *.js's, since the first match wins.
        String cacheControl = shipped.getProperty("cachecontrol");
        Check.isTrue(cacheControl.contains(";*.nocache.js:no-cache;")
                && cacheControl.indexOf("*.nocache.js:") < cacheControl.indexOf("*.js:"),
                "the shipped cachecontrol revalidates *.nocache.js ahead of *.js: " + cacheControl);

        // A default an earlier image shipped is replaced by this image's; a value the admin set stays.
        for (Map.Entry<String, List<String>> replaced : ConfigPlan.REPLACED_DEFAULTS.entrySet()) {
            String key = replaced.getKey();
            Check.isTrue(shipped.getProperty(key) != null, key + " is in the shipped defaults");
            Check.isTrue(!replaced.getValue().contains(shipped.getProperty(key)),
                    key + "'s shipped default isn't one it replaces");
            for (String old : replaced.getValue()) {
                set(conf, key, old);
                DockerInit.applyConfig(conf, defaults, ConfigPlan.envKeys(Map.of("DB_PASSWORD", "rotated")));
                Check.eq(shipped.getProperty(key), new ContextProperties(conf).getProperty(key),
                        key + " old default replaced");
            }
        }
        set(conf, "cachecontrol", "*:no-store");
        DockerInit.applyConfig(conf, defaults, ConfigPlan.envKeys(Map.of("DB_PASSWORD", "rotated")));
        Check.eq("*:no-store", new ContextProperties(conf).getProperty("cachecontrol"),
                "admin-set cachecontrol preserved");

        // context.properties is written byte-per-char (Latin-1): anything it cannot hold must be refused, not
        // silently corrupted into a value that only fails later when Tomcat logs in.
        Check.throwsWith("DB_PASSWORD", () -> apply(conf, Map.of("DB_PASSWORD", "pa€ss")), "non-Latin-1 password");
        Check.throwsWith("DB_PASSWORD", () -> apply(conf, Map.of("DB_PASSWORD", "pa\rss")), "line break in password");
        Check.throwsWith("LOGICALDOC_PUBLIC_URL",
                () -> apply(conf, Map.of("DB_PASSWORD", "pw", "LOGICALDOC_PUBLIC_URL", "https://bücher.例え.jp")),
                "non-Latin-1 public URL");
        Check.done("ConfigFileTest");
    }

    private static void set(File conf, String key, String value) throws java.io.IOException {
        ContextProperties config = new ContextProperties(conf);
        config.setProperty(key, value);
        config.write();
    }

    private static void apply(File conf, Map<String, String> env) {
        try {
            DockerInit.applyConfig(conf, new File(conf.getParentFile(), "absent-defaults.properties"),
                    ConfigPlan.envKeys(env));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
