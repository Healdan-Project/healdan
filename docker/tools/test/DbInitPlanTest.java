// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        extractsColumns();
        skipsKeysAndConstraints();
        readsCrlfScripts();
        ignoresWhatQuotesAndCommentsHold();
        knowsNoColumnsOfATableCopiedFromAnother();
        keepsTheFirstDefinitionOfATableCreatedTwice();
        readsQualifiedAndReplacingCreates();
        readsTheRealScripts();
        readsCoreTenantQuotaThresholds();
        reportsWhatTheDatabaseLacks();
        ignoresWhatTheDatabaseHasBesides();
        comparesColumnNamesInAnyCaseAndTableNamesExactly();
        reportsTenantAsBeforeTheQuotaThresholds();
        Check.done("DbInitPlanTest");
    }

    /** The 14 columns upstream sync #215 brought to ld_tenant, in the order logicaldoc-core.sql.mariadb has them. */
    private static final List<String> QUOTA_THRESHOLDS = List.of("ld_formsquotathr", "ld_usersquotathr",
            "ld_sessionsquotathr", "ld_documentsquotathr", "ld_workflowsquotathr", "ld_storagequotathr",
            "ld_rousersquotathr", "ld_reportsquotathr", "ld_apicallsquotathr", "ld_ticketsquotathr",
            "ld_stampsquotathr", "ld_importfoldersquotathr", "ld_emailaccountsquotathr", "ld_pagesquotathr");

    private static Map<String, List<String>> real;

    private static Map<String, List<String>> columns(String... scripts) {
        return DbInitPlan.createdColumns(List.of(scripts));
    }

    /**
     * What the WAR's own DbInit scripts create: the plan DockerInit works out for a fresh MariaDB from the plugin.xml
     * files on this classpath, read as DockerInit reads it.
     */
    private static Map<String, List<String>> real() {
        if (real == null) {
            ClassLoader cl = DbInitPlanTest.class.getClassLoader();
            try {
                real = DockerInit.createdColumns(cl, DockerInit.plannedScripts(cl, true));
            } catch (Exception e) {
                throw new IllegalStateException("can't read the WAR's DbInit scripts", e);
            }
        }
        return real;
    }

    private static Map<String, Set<String>> database(Map<String, List<String>> tables) {
        Map<String, Set<String>> db = new HashMap<>();
        tables.forEach((table, cols) -> db.put(table, new HashSet<>(cols)));
        return db;
    }

    private static void extractsColumns() {
        String sql = """
                create table ld_bookmark (ld_id bigint not null, ld_title varchar(255) not null,
                                          ld_amount decimal(10, 2), ld_date datetime(3), ld_position int not null default 0,
                                          primary key (ld_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
                create table ld_uniquetag(ld_tag varchar(255),ld_tenantid bigint, primary key (ld_tag, ld_tenantid));
                """;
        Check.eq(Map.of("ld_bookmark", List.of("ld_id", "ld_title", "ld_amount", "ld_date", "ld_position"),
                "ld_uniquetag", List.of("ld_tag", "ld_tenantid")), columns(sql),
                "columns with sized types, no space before the parenthesis, the primary key left out");
    }

    private static void skipsKeysAndConstraints() {
        String sql = """
                create table ld_a (ld_id bigint, ld_b bigint, ld_c varchar(10),
                  constraint pk_a primary key (ld_id), unique (ld_c), unique key uk_a (ld_b, ld_c),
                  key ix_a (ld_b), index ix_b (ld_c), foreign key (ld_b) references ld_b (ld_id),
                  check (ld_b > 0), fulltext (ld_c), spatial index ix_s (ld_c), `key` int, `check` int);
                """;
        Check.eq(List.of("ld_id", "ld_b", "ld_c", "key", "check"), columns(sql).get("ld_a"),
                "constraints and keys aren't columns, but a backquoted name is a column whatever it says");
    }

    private static void readsCrlfScripts() {
        String sql = "create table ld_tenant (ld_id bigint not null,\r\n"
                + "                        ld_name varchar(255),\r\n"
                + "                        ld_pagesquotathr int,\r\n"
                + "                        primary key (ld_id)) ENGINE=InnoDB;\r\n"
                + "-- a comment\r\ncreate table ld_next (ld_id bigint);\r\n";
        Check.eq(Map.of("ld_tenant", List.of("ld_id", "ld_name", "ld_pagesquotathr"), "ld_next", List.of("ld_id")),
                columns(sql), "CRLF line endings leave nothing on a name");
    }

    private static void ignoresWhatQuotesAndCommentsHold() {
        String sql = """
                insert into ld_menu (ld_name) values ('create table ld_quoted (x int)');
                insert into ld_menu (ld_name) values ("create table ld_doubled (x int)"), ("it""s");
                insert into ld_menu (ld_name) values ('it''s -- not a comment'); create table ld_after (ld_id bigint);
                /* create table ld_blocked (x int); */
                # it's a comment: create table ld_hashed (x int);
                create table ld_c (ld_id bigint, -- ld_gone int,
                                   ld_kind varchar(10) default 'a,b)', /* ld_also_gone int, */ ld_last int,
                                   ld_note varchar(10) default "c,d)", # ld_hash_gone int,
                                   ld_end int);
                """;
        Check.eq(Map.of("ld_after", List.of("ld_id"), "ld_c", List.of("ld_id", "ld_kind", "ld_last", "ld_note",
                "ld_end")), columns(sql), "a string's text, single- or double-quoted, and comments create nothing");
    }

    private static void knowsNoColumnsOfATableCopiedFromAnother() {
        String sql = """
                create table ld_copy like ld_original;
                create table ld_selected as select * from ld_original;
                """;
        Check.eq(Map.of("ld_copy", List.of(), "ld_selected", List.of()), columns(sql),
                "a table made from another is expected, its columns unknown");
    }

    private static void keepsTheFirstDefinitionOfATableCreatedTwice() {
        Map<String, List<String>> tables = columns("create table ld_a (ld_id bigint, ld_x int);",
                "create table if not exists ld_a (ld_id bigint, ld_y int); create table ld_b (ld_id bigint);"
                        + " create table ld_b (ld_id bigint, ld_z int);");
        Check.eq(List.of("ld_a", "ld_b"), List.copyOf(tables.keySet()), "a table created twice is listed once");
        Check.eq(List.of("ld_id", "ld_x"), tables.get("ld_a"), "with its first definition's columns, as MariaDB keeps it");
        Check.eq(List.of("ld_id"), tables.get("ld_b"), "a second plain CREATE TABLE fails, and changes nothing");
        Check.eq(List.of("ld_id", "ld_w"), columns("create table ld_r (ld_id bigint, ld_v int);",
                "create or replace table ld_r (ld_id bigint, ld_w int);").get("ld_r"),
                "CREATE OR REPLACE replaces the columns");
    }

    private static void readsQualifiedAndReplacingCreates() {
        Check.eq(Map.of("ld_q", List.of("ld_id"), "ld_r", List.of("ld_id"), "ld_s", List.of("ld_id")),
                columns("create table logicaldoc.ld_q (ld_id bigint); create or replace table ld_r (ld_id bigint);"
                        + " create table `logicaldoc` . `ld_s` (ld_id bigint);"),
                "a database's name before the table's is left out, and CREATE OR REPLACE creates a table");
    }

    private static void readsTheRealScripts() {
        Map<String, List<String>> real = real();
        Check.isTrue(real.size() >= 50, "the real scripts create LogicalDOC's tables, " + real.size() + " found");
        Check.isTrue(real.containsKey("ld_webservicecall"), "the webservice script's table is among them");
        real.forEach((table, cols) -> {
            Check.isTrue(table.matches("ld_[a-z0-9_]+"), "table name '" + table + "' is a plain name");
            Check.isTrue(!cols.isEmpty(), "table " + table + " has columns");
            for (String col : cols)
                Check.isTrue(col.matches("[a-z][a-z0-9_]*") && !Set.of("primary", "constraint", "unique", "key",
                        "index", "foreign", "check").contains(col), table + "'s column '" + col + "' is a plain name");
        });
    }

    private static void readsCoreTenantQuotaThresholds() {
        List<String> tenant = real().get("ld_tenant");
        Check.isTrue(tenant != null && tenant.contains("ld_id"), "ld_tenant has its columns");
        Check.isTrue(tenant != null && tenant.containsAll(QUOTA_THRESHOLDS), "ld_tenant has the 14 quota thresholds");
    }

    private static void reportsWhatTheDatabaseLacks() {
        Map<String, List<String>> expected = columns("""
                create table ld_a (ld_id bigint, ld_x int, ld_y int, ld_z int);
                create table ld_b (ld_id bigint);
                create table ld_c (ld_id bigint, ld_w int);
                create table ld_d (ld_id bigint);
                """);
        Map<String, Set<String>> db = Map.of("ld_a", Set.of("ld_id", "ld_y"), "ld_c", Set.of("ld_id"));
        DbInitPlan.Missing missing = DbInitPlan.missing(expected, db);
        Check.isTrue(!missing.isEmpty(), "something is missing");
        Check.eq(List.of("ld_b", "ld_d"), missing.tables(), "the tables it lacks, in the scripts' order");
        Check.eq(Map.of("ld_a", List.of("ld_x", "ld_z"), "ld_c", List.of("ld_w")), missing.columns(),
                "the columns each table it has lacks, in the scripts' order");
        Check.eq(List.of("ld_a", "ld_c"), List.copyOf(missing.columns().keySet()), "tables in the scripts' order");
        Check.eq("table ld_a lacks columns ld_x, ld_z; table ld_c lacks column ld_w; tables ld_b, ld_d are missing",
                missing.describe(), "described table by table");
        Check.eq("table ld_d is missing", DbInitPlan.missing(columns("create table ld_d (ld_id bigint);"), Map.of())
                .describe(), "one missing table");
    }

    private static void ignoresWhatTheDatabaseHasBesides() {
        Map<String, List<String>> expected = columns("create table ld_a (ld_id bigint, ld_x int);");
        Map<String, Set<String>> db = Map.of("ld_a", Set.of("ld_id", "ld_x", "ld_leftover"), "ld_old",
                Set.of("ld_id"), "hd_setting", Set.of("name"));
        DbInitPlan.Missing missing = DbInitPlan.missing(expected, db);
        Check.isTrue(missing.isEmpty(), "extra columns and tables don't count");
        Check.eq("", missing.describe(), "nothing to describe");
    }

    private static void comparesColumnNamesInAnyCaseAndTableNamesExactly() {
        Map<String, List<String>> expected = columns("create table ld_tenant (LD_ID bigint, ld_Name varchar(255));");
        Check.isTrue(DbInitPlan.missing(expected, Map.of("ld_tenant", Set.of("ld_id", "LD_NAME"))).isEmpty(),
                "column names compared whatever their case");
        Check.eq(List.of("ld_tenant"), DbInitPlan.missing(expected, Map.of("LD_TENANT", Set.of("ld_id", "ld_name")))
                .tables(), "table names compared as written, as MariaDB on Linux compares them");
    }

    private static void reportsTenantAsBeforeTheQuotaThresholds() {
        Map<String, List<String>> expected = real();
        Map<String, Set<String>> current = database(expected);
        Check.isTrue(DbInitPlan.missing(expected, current).isEmpty(), "a database made from the scripts lacks nothing");

        Map<String, Set<String>> beforeSync = database(expected);
        beforeSync.get("ld_tenant").removeAll(QUOTA_THRESHOLDS);
        beforeSync.get("ld_tenant").add("ld_qthresholdold");
        DbInitPlan.Missing missing = DbInitPlan.missing(expected, beforeSync);
        Check.eq(List.of(), missing.tables(), "no table missing");
        Check.eq(Set.of("ld_tenant"), missing.columns().keySet(), "only ld_tenant lacks columns");
        Check.eq(Set.copyOf(QUOTA_THRESHOLDS), Set.copyOf(missing.columns().get("ld_tenant")),
                "ld_tenant as before upstream sync #215 lacks exactly the 14 quota thresholds");
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
        Check.eq(List.of("ld_bookmark", "ld_webservicecall", "ld_extra"), List.copyOf(columns(sql).keySet()),
                "created tables in order, de-duplicated, comments ignored");
    }
}
