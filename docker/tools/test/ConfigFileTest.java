import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
        Check.eq(AWKWARD, reread.getProperty("jdbc.password"), "password via getProperty (raw readers)");
        Check.eq(AWKWARD, reread.getString("jdbc.password"), "password via getString (Spring placeholders)");
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

        // A later boot applies env keys only: a seed-once key the admin changed must survive. A key the conf
        // file lacks (as after an upstream sync adds one to the defaults) is filled in from the defaults.
        reread.setProperty("conf.importdir", "/srv/custom-in/");
        reread.setProperty("webservice.enabled", "false");
        reread.remove("cookies.samesite");
        reread.write();
        boolean seededAgain = DockerInit.applyConfig(conf, defaults, ConfigPlan.envKeys(Map.of("DB_PASSWORD", "rotated")));
        ContextProperties later = new ContextProperties(conf);
        Check.isTrue(!seededAgain, "second call does not seed");
        Check.eq("/srv/custom-in/", later.getProperty("conf.importdir"), "admin-changed seed key preserved");
        Check.eq("false", later.getProperty("webservice.enabled"), "admin-changed default key preserved");
        Check.eq("lax", later.getProperty("cookies.samesite"), "key missing from conf restored from the defaults");
        Check.eq("rotated", later.getProperty("jdbc.password"), "env key re-applied");

        // context.properties is written byte-per-char (Latin-1): anything it cannot hold must be refused, not
        // silently corrupted into a value that only fails later when Tomcat logs in.
        Check.throwsWith("DB_PASSWORD", () -> apply(conf, Map.of("DB_PASSWORD", "pa€ss")), "non-Latin-1 password");
        Check.throwsWith("DB_PASSWORD", () -> apply(conf, Map.of("DB_PASSWORD", "pa\rss")), "line break in password");
        Check.throwsWith("LOGICALDOC_PUBLIC_URL",
                () -> apply(conf, Map.of("DB_PASSWORD", "pw", "LOGICALDOC_PUBLIC_URL", "https://bücher.例え.jp")),
                "non-Latin-1 public URL");
        Check.done("ConfigFileTest");
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
