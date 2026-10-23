"""Runs code-changed.sh on a throwaway git repository and checks what it tells the CI workflows."""

import os
import pathlib
import subprocess
import tempfile
import unittest

import _scripts

SCRIPT = _scripts.SCRIPTS / "code-changed.sh"


class CodeChangedTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = pathlib.Path(self._tmp.name)
        self.git("init", "-q")
        self.write("README.md", "base\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")

    def tearDown(self):
        self._tmp.cleanup()

    def git(self, *args):
        subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.org",
                        "-c", "commit.gpgsign=false", *args], cwd=self.repo, check=True, capture_output=True)

    def write(self, name, text):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def change(self, *names):
        """Commits the named files on top of the base commit, as a pull request's merge commit would."""
        for name in names:
            self.write(name, f"changed {name}\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "change")

    def code(self, event="pull_request"):
        output = self.repo / "github-output"
        output.write_text("", encoding="utf-8")
        env = dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_OUTPUT=str(output))
        subprocess.run(["bash", str(SCRIPT)], cwd=self.repo, env=env, check=True, capture_output=True)
        return output.read_text(encoding="utf-8").strip()

    def test_site_only_is_docs(self):
        self.change("site/hugo.yaml", "site/static/favicon.svg")
        self.assertEqual("code=false", self.code())

    def test_site_and_java_is_code(self):
        self.change("site/hugo.yaml", "healdan-core/src/main/java/A.java")
        self.assertEqual("code=true", self.code())

    def test_docs_only_still_docs(self):
        self.change("docs/x.md")
        self.assertEqual("code=false", self.code())

    def test_markdown_under_src_is_code(self):
        self.change("logicaldoc-core/src/test/resources/README.md")
        self.assertEqual("code=true", self.code())

    def test_push_is_code(self):
        self.change("site/hugo.yaml")
        self.assertEqual("code=true", self.code(event="push"))


if __name__ == "__main__":
    unittest.main()
