import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public class DbInitPlanTest {

    // Same shape as logicaldoc-core's plugin.xml: internal DTD subset, DbInit plus unrelated extensions.
    private static final String CORE = """
            <?xml version="1.0" ?>
            <!DOCTYPE plugin [
            <!ELEMENT plugin ANY>
            ]>
            <plugin id="logicaldoc-core" version="9.3.1">
              <extension plugin-id="logicaldoc-core" point-id="DbInit" id="coreDbInit">
                <parameter id="position" value="1" />
                <parameter id="sqlFile" value="sql/logicaldoc-core.sql" />
              </extension>
              <extension plugin-id="logicaldoc-core" point-id="Feature" id="f53">
                <parameter id="name" value="Feature_53" />
              </extension>
            </plugin>
            """;

    public static void main(String[] args) throws Exception {
        parsesCoreDbInitExtension();
        ignoresForeignManifests();
        rejectsDbInitWithoutSqlFile();
        ordersByPosition();
        prefersDbmsVariant();
        fallsBackToGenericScript();
        failsWhenScriptMissing();
        extractsCreatedTables();
        Check.done("DbInitPlanTest");
    }

    private static List<DbInitPlan.Entry> parse(String xml) {
        try {
            return DbInitPlan.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void parsesCoreDbInitExtension() {
        Check.eq(List.of(new DbInitPlan.Entry("logicaldoc-core", 1, "sql/logicaldoc-core.sql")), parse(CORE),
                "core DbInit entry, Feature extension ignored");
    }

    private static void ignoresForeignManifests() {
        String eclipseStyle = """
                <?xml version="1.0"?>
                <plugin><extension point="org.eclipse.ui.views"/></plugin>
                """;
        Check.eq(List.of(), parse(eclipseStyle), "Eclipse-style plugin.xml yields nothing");
        Check.eq(List.of(), parse("<?xml version=\"1.0\"?><project/>"), "non-plugin root yields nothing");
    }

    private static void rejectsDbInitWithoutSqlFile() {
        String broken = """
                <?xml version="1.0"?>
                <plugin id="logicaldoc-broken">
                  <extension plugin-id="logicaldoc-core" point-id="DbInit" id="x">
                    <parameter id="position" value="5" />
                  </extension>
                </plugin>
                """;
        Check.throwsWith("logicaldoc-broken", () -> parse(broken), "DbInit without sqlFile names the plugin");
    }

    private static void ordersByPosition() {
        var core = new DbInitPlan.Entry("logicaldoc-core", 1, "sql/logicaldoc-core.sql");
        var ws = new DbInitPlan.Entry("logicaldoc-webservice", 20, "sql/logicaldoc-webservice.sql");
        var dropbox = new DbInitPlan.Entry("logicaldoc-dropbox", 1500, "sql/logicaldoc-dropbox.sql");
        Check.eq(List.of(core, ws, dropbox), DbInitPlan.order(List.of(dropbox, core, ws)), "ascending position");
    }

    private static void prefersDbmsVariant() {
        var core = new DbInitPlan.Entry("logicaldoc-core", 1, "sql/logicaldoc-core.sql");
        Set<String> present = Set.of("sql/logicaldoc-core.sql", "sql/logicaldoc-core.sql.mariadb");
        Check.eq("sql/logicaldoc-core.sql.mariadb", DbInitPlan.resolve(core, "MariaDB", present::contains),
                "variant preferred, dbms lower-cased");
    }

    private static void fallsBackToGenericScript() {
        var dropbox = new DbInitPlan.Entry("logicaldoc-dropbox", 1500, "sql/logicaldoc-dropbox.sql");
        Check.eq("sql/logicaldoc-dropbox.sql",
                DbInitPlan.resolve(dropbox, "mariadb", Set.of("sql/logicaldoc-dropbox.sql")::contains),
                "generic script when there is no variant");
    }

    private static void failsWhenScriptMissing() {
        var ws = new DbInitPlan.Entry("logicaldoc-webservice", 20, "sql/logicaldoc-webservices.sql");
        Check.throwsWith("logicaldoc-webservice", () -> DbInitPlan.resolve(ws, "mariadb", Set.<String>of()::contains),
                "missing script names the plugin");
        Check.throwsWith("sql/logicaldoc-webservices.sql",
                () -> DbInitPlan.resolve(ws, "mariadb", Set.<String>of()::contains), "missing script names the file");
    }

    private static void extractsCreatedTables() {
        String sql = """
                -- create table ld_commented_out (x int);
                create table ld_bookmark (ld_id bigint not null);
                CREATE  TABLE ld_webservicecall (ld_id bigint);
                create table if not exists ld_extra (ld_id bigint);
                insert into ld_menu values (1);
                create table ld_bookmark (dup int);
                """;
        Check.eq(List.of("ld_bookmark", "ld_webservicecall", "ld_extra"), DbInitPlan.createdTables(sql),
                "created tables in order, de-duplicated, comments ignored");
    }
}
