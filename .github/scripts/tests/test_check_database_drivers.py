import contextlib
import io
import pathlib
import subprocess
import tempfile
import unittest

import _scripts

checker = _scripts.load("check-database-drivers")

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
{body}
</project>
"""


def dependencies(*entries, wrapper="dependencies"):
    """A <dependencies> element, one <dependency> per (groupId, artifactId) entry, each four lines long."""
    items = "".join(f"    <dependency>\n      <groupId>{group}</groupId>\n      <artifactId>{artifact}</artifactId>\n"
                    f"    </dependency>\n" for group, artifact in entries)
    return f"  <{wrapper}>\n{items}  </{wrapper}>"


def dependency_problem(name, line, group, artifact, database):
    return f"{name}:{line}: dependency {group}:{artifact} " + checker.DEPENDENCY_FIX.format(database=database)


def class_problem(name, line, database):
    return f"{name}:{line}: " + checker.CLASS_FIX.format(database=database)


class CheckDatabaseDriversTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = pathlib.Path(self._tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, name, text, track=True):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        if track:
            subprocess.run(["git", "add", name], cwd=self.repo, check=True)

    def problems(self):
        return checker.find_problems(self.repo)

    def test_open_source_drivers_pass(self):
        self.write("logicaldoc-webapp/pom.xml", POM.format(body=dependencies(
            ("org.postgresql", "postgresql"), ("com.mysql", "mysql-connector-j"),
            ("org.mariadb.jdbc", "mariadb-java-client"), ("org.hsqldb", "hsqldb"))))
        self.assertEqual([], self.problems())

    def test_sql_server_and_oracle_drivers_reported_with_file_and_line(self):
        self.write("logicaldoc-webapp/pom.xml", POM.format(body=dependencies(
            ("org.mariadb.jdbc", "mariadb-java-client"), ("com.microsoft.sqlserver", "mssql-jdbc"),
            ("net.sourceforge.jtds", "jtds"), ("com.oracle.jdbc", "ojdbc8"),
            ("com.oracle.database.jdbc", "ojdbc11"))))
        name = "logicaldoc-webapp/pom.xml"
        self.assertEqual([dependency_problem(name, 9, "com.microsoft.sqlserver", "mssql-jdbc", "SQL Server"),
                          dependency_problem(name, 13, "net.sourceforge.jtds", "jtds", "SQL Server"),
                          dependency_problem(name, 17, "com.oracle.jdbc", "ojdbc8", "Oracle"),
                          dependency_problem(name, 21, "com.oracle.database.jdbc", "ojdbc11", "Oracle")],
                         self.problems())

    def test_managed_and_plugin_dependencies_reported(self):
        managed = ("  <dependencyManagement>\n" + dependencies(("com.microsoft.sqlserver", "mssql-jdbc"))
                   + "\n  </dependencyManagement>")
        self.write("pom.xml", POM.format(body=managed))
        self.assertEqual([dependency_problem("pom.xml", 6, "com.microsoft.sqlserver", "mssql-jdbc", "SQL Server")],
                         self.problems())
        plugin = ("  <build><plugins><plugin>\n" + dependencies(("com.oracle.database.jdbc", "ojdbc11"))
                  + "\n  </plugin></plugins></build>")
        self.write("pom.xml", POM.format(body=plugin))
        self.assertEqual([dependency_problem("pom.xml", 6, "com.oracle.database.jdbc", "ojdbc11", "Oracle")],
                         self.problems())

    def test_exclusion_passes(self):
        body = ("  <dependencies>\n    <dependency>\n      <groupId>org.example</groupId>\n"
                "      <artifactId>tool</artifactId>\n      <exclusions>\n        <exclusion>\n"
                "          <groupId>com.microsoft.sqlserver</groupId>\n          <artifactId>mssql-jdbc</artifactId>\n"
                "        </exclusion>\n      </exclusions>\n    </dependency>\n  </dependencies>")
        self.write("pom.xml", POM.format(body=body))
        self.assertEqual([], self.problems())

    def test_driver_classes_reported(self):
        self.write("logicaldoc-gui/src/main/java/Setup.java",
                   'class Setup {\n  String a = "com.microsoft.sqlserver.jdbc.SQLServerDriver";\n'
                   '  String b = "oracle.jdbc.driver.OracleDriver";\n  String c = "oracle.jdbc.OracleDriver";\n'
                   '  String d = "net.sourceforge.jtds.jdbc.Driver";\n  String e = "org.mariadb.jdbc.Driver";\n}\n')
        name = "logicaldoc-gui/src/main/java/Setup.java"
        self.assertEqual([class_problem(name, 2, "SQL Server"), class_problem(name, 3, "Oracle"),
                          class_problem(name, 4, "Oracle"), class_problem(name, 5, "SQL Server")], self.problems())
        self.write("context.properties", "jdbc.driver=com.microsoft.sqlserver.jdbc.SQLServerDriver\n")
        self.assertIn(class_problem("context.properties", 1, "SQL Server"), self.problems())

    def test_prose_tests_and_site_skipped(self):
        driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver\n"
        for name in ("docs/plan.txt", "NOTES.md", "site/content/a.html", "logicaldoc-core/src/test/java/T.java",
                     ".github/scripts/tests/x.py"):
            self.write(name, driver)
        self.write("logicaldoc-core/src/test/pom.xml", POM.format(body=dependencies(("com.oracle.jdbc", "ojdbc8"))))
        self.assertEqual([], self.problems())

    def test_untracked_files_ignored(self):
        self.write("pom.xml", POM.format(body=dependencies(("com.microsoft.sqlserver", "mssql-jdbc"))), track=False)
        self.write("a.properties", "jdbc.driver=oracle.jdbc.OracleDriver\n", track=False)
        self.assertEqual([], self.problems())

    def test_unparseable_pom_reported(self):
        self.write("pom.xml", "<project><dependencies></project>\n")
        problems = self.problems()
        self.assertEqual(1, len(problems))
        self.assertTrue(problems[0].startswith("pom.xml: can't be parsed: "), problems[0])

    def test_main_exit_codes(self):
        self.write("pom.xml", POM.format(body=dependencies(("org.mariadb.jdbc", "mariadb-java-client"))))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main([str(self.repo)]))
        self.write("a/pom.xml", POM.format(body=dependencies(("com.microsoft.sqlserver", "mssql-jdbc"))))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, checker.main([str(self.repo)]))
        self.assertIn("a/pom.xml:5:", out.getvalue())

    def test_current_repo_passes(self):
        self.assertEqual([], checker.find_problems(_scripts.REPO))


if __name__ == "__main__":
    unittest.main()
