import contextlib
import io
import pathlib
import subprocess
import tempfile
import unittest

import _scripts

checker = _scripts.load("check-logicaldoc-doc-links")

FIX = "links to LogicalDOC's docs. Link to https://docs.healdan.com instead, or add the line to ALLOWED with a reason."
SYSTEM_INFO = "logicaldoc-core/src/main/java/com/logicaldoc/core/SystemInfo.java"


class CheckLogicalDocDocLinksTest(unittest.TestCase):

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

    def links(self):
        return checker.find_links(self.repo)

    def test_reports_file_line_and_fix(self):
        self.write("a/B.java", 'class B {\n  // see\n  String help = "https://docs.logicaldoc.com/en/x";\n}\n')
        self.assertEqual([f"a/B.java:3: {FIX}"], self.links())

    def test_untracked_files_ignored(self):
        self.write("notes.txt", "https://docs.logicaldoc.com\n", track=False)
        self.assertEqual([], self.links())

    def test_skipped_prefixes_and_files(self):
        for name in ("docs/x.md", "site/content/x.md", "site/_vendor/x.html", ".github/scripts/tests/x.py",
                     "ReleaseNotes.md", "logicaldoc-core/src/test/resources/README.md"):
            self.write(name, "http://wiki.logicaldoc.com/wiki/Developer_Guide\n")
        self.assertEqual([], self.links())
        self.write("site/layouts/x.html", '<a href="https://docs.logicaldoc.com">x</a>\n')
        self.assertEqual([f"site/layouts/x.html:1: {FIX}"], self.links())

    def test_allowed_line_only_exact(self):
        self.write(SYSTEM_INFO, '    protected String help = "https://docs.logicaldoc.com";\n')
        self.assertEqual([], self.links())
        self.write(SYSTEM_INFO, '    protected String help = "https://docs.logicaldoc.com/en";\n')
        self.assertEqual([f"{SYSTEM_INFO}:1: {FIX}"], self.links())

    def test_properties_escaped_colon_reported(self):
        # Properties files may write a URL's colon as "\\:", as the English message bundles do
        self.write("messages_en.properties", "hint=<a href\\='https\\://docs.logicaldoc.com/webstart'>x</a>\n")
        self.assertEqual([f"messages_en.properties:1: {FIX}"], self.links())

    def test_other_logicaldoc_hosts_ignored(self):
        self.write("x.properties", "url=https://www.logicaldoc.com\nns=http://ws.logicaldoc.com\n"
                                   "bugs=https://bugs.logicaldoc.com\n")
        self.assertEqual([], self.links())

    def test_binary_files_skipped(self):
        self.write("logo.png", "\x89PNG\x00https://docs.logicaldoc.com\n")
        self.assertEqual([], self.links())

    def test_main_exit_codes(self):
        self.write("ok.txt", "https://docs.healdan.com\n")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main([str(self.repo)]))
        self.write("bad.txt", "https://forums.logicaldoc.com\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, checker.main([str(self.repo)]))
        self.assertIn("bad.txt:1:", out.getvalue())

    def test_current_repo_fails_before_switch(self):
        files = {problem.split(":", 1)[0] for problem in checker.find_links(_scripts.REPO)}
        for expected in ("README.md", "logicaldoc-gui/war/releasecheck.jsp",
                         "logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/rest/endpoint/RestAuthService.java",
                         "logicaldoc-webservicedoc/src/main/resources/context/context-webservicedoc.xml",
                         "logicaldoc-gui/src/main/java/com/logicaldoc/gui/common/client/beans/GUIBranding.java",
                         "Build_this_Sources.md",
                         "logicaldoc-i18n/src/main/resources/i18n/messages_en.properties"):
            self.assertIn(expected, files)
        self.assertNotIn(SYSTEM_INFO, files)


if __name__ == "__main__":
    unittest.main()
