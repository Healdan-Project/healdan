import contextlib
import io
import pathlib
import subprocess
import tempfile
import unittest

import _scripts

checker = _scripts.load("check-logicaldoc-hosts")

FIX = ("names a LogicalDOC server, and Healdan never contacts LogicalDOC. Remove it, or add the line to ALLOWED with "
       "the reason nothing contacts it.")
STATS_COLLECTOR = "logicaldoc-core/src/main/java/com/logicaldoc/core/stats/StatsCollector.java"


class CheckLogicalDocHostsTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = pathlib.Path(self._tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, name, text, track=True):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="latin-1")
        if track:
            subprocess.run(["git", "add", name], cwd=self.repo, check=True)

    def hosts(self):
        return checker.find_hosts(self.repo)

    def test_reports_file_line_and_fix(self):
        self.write("a/src/main/java/B.java", 'class B {\n  // see\n  HttpPost p = new HttpPost("http://stat.logicaldoc.com/x");\n}\n')
        self.assertEqual([f"a/src/main/java/B.java:3: {FIX}"], self.hosts())

    def test_reports_hosts_without_a_scheme_emails_and_other_domains(self):
        self.write("x.properties", 'host=activation.logicaldoc.com\nto=support@logicaldoc.com\nit=www.LogicalDOC.it\n'
                                   'net=logicaldoc.net\n')
        self.assertEqual([f"x.properties:{n}: {FIX}" for n in (1, 2, 3, 4)], self.hosts())

    def test_package_names_ignored(self):
        self.write("a/src/main/java/B.java", "import com.logicaldoc.core.Foo;\nimport com.logicaldoc.it.Bar;\n"
                                             "String s = \"logicaldoc.community\";\n")
        self.assertEqual([], self.hosts())

    def test_namespaces_ignored(self):
        self.write("a/src/main/java/B.java", '@WebService(targetNamespace = "http://ws.logicaldoc.com")\n'
                                             'String cmis = "http://logicaldoc.com/cmis";\n'
                                             'String dav = "http://logicaldoc.com/ns";\n')
        self.assertEqual([], self.hosts())
        self.write("c.sh", 'curl http://ws.logicaldoc.com/services/x\ncurl http://logicaldoc.com/cmis/x\n')
        self.assertEqual([f"c.sh:1: {FIX}", f"c.sh:2: {FIX}"], self.hosts())

    def test_untracked_files_ignored(self):
        self.write("notes.txt", "https://www.logicaldoc.com\n", track=False)
        self.assertEqual([], self.hosts())

    def test_skipped_paths(self):
        for name in ("docs/x.txt", "site/layouts/x.html", "README.md", "a/b/NOTES.md", "a/src/test/java/X.java",
                     "a/src/test/resources/mail.eml", "ReleaseNotes.txt", "logicaldoc-webservicesamples/x.java",
                     ".github/scripts/tests/x.py"):
            self.write(name, "http://www.logicaldoc.com\n")
        self.assertEqual([], self.hosts())
        self.write("a/src/main/resources/x.txt", "http://www.logicaldoc.com\n")
        self.assertEqual([f"a/src/main/resources/x.txt:1: {FIX}"], self.hosts())

    def test_allowed_line_only_exact(self):
        self.write(STATS_COLLECTOR, '\t\t\tHttpPost post = new HttpPost("http://stat.logicaldoc.com/stats/collect");\n')
        self.assertEqual([], self.hosts())
        self.write(STATS_COLLECTOR, '\t\t\tHttpPost post = new HttpPost("https://stat.logicaldoc.com/stats/collect");\n')
        self.assertEqual([f"{STATS_COLLECTOR}:1: {FIX}"], self.hosts())

    def test_binary_files_skipped(self):
        self.write("logo.png", "\x89PNG\x00https://www.logicaldoc.com\n")
        self.assertEqual([], self.hosts())

    def test_main_exit_codes(self):
        self.write("ok.txt", "https://docs.healdan.com\n")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main([str(self.repo)]))
        self.write("bad.txt", "https://activation.logicaldoc.com\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, checker.main([str(self.repo)]))
        self.assertIn("bad.txt:1:", out.getvalue())

    def test_current_repo_passes(self):
        self.assertEqual([], checker.find_hosts(_scripts.REPO))

if __name__ == "__main__":
    unittest.main()
