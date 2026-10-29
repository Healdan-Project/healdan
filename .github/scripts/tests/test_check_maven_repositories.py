import contextlib
import io
import pathlib
import subprocess
import tempfile
import unittest

import _scripts

checker = _scripts.load("check-maven-repositories")

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
{body}
</project>
"""

SETTINGS = """<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
{body}
</settings>
"""

MIRROR = """  <mirrors>
    <mirror>
      <id>healdan</id>
      <mirrorOf>{mirror_of}</mirrorOf>
      <url>{url}</url>
    </mirror>
  </mirrors>"""

SOURCEFORGE = "https://logicaldoc.sourceforge.net/maven/"


def repositories(*entries, tag="repository"):
    """A <repositories> (or <pluginRepositories>) element, one <tag> per (id, url) entry, each five lines long."""
    wrapper = {"repository": "repositories", "pluginRepository": "pluginRepositories"}[tag]
    items = "".join(f"    <{tag}>\n      <id>{id_}</id>\n      <url>{url}</url>\n    </{tag}>\n" for id_, url in entries)
    return f"  <{wrapper}>\n{items}  </{wrapper}>"


def problem(name, line, id_, host="logicaldoc.sourceforge.net"):
    return f"{name}:{line}: repository {id_} " + checker.FIX.format(host=host, id=id_)


class CheckMavenRepositoriesTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = pathlib.Path(self._tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)
        self.settings(MIRROR.format(mirror_of="logicaldoc", url="https://maven.healdan.com/"))

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, name, text, track=True):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        if track:
            subprocess.run(["git", "add", name], cwd=self.repo, check=True)

    def settings(self, body):
        self.write("docker/maven-settings.xml", SETTINGS.format(body=body))

    def problems(self):
        return checker.find_problems(self.repo)

    def test_mirrored_repository_passes_whatever_its_url(self):
        self.write("pom.xml", POM.format(body=repositories(("logicaldoc", "https://logicaldoc.sourceforge.net/maven"))))
        self.write("logicaldoc-webdav/pom.xml",
                   POM.format(body=repositories(("logicaldoc", "http://logicaldoc.sourceforge.net/maven"))))
        self.assertEqual([], self.problems())

    def test_unmirrored_repository_reported_with_file_and_line(self):
        for url in (SOURCEFORGE, "http://logicaldoc.sourceforge.net/maven"):
            with self.subTest(url):
                self.write("a/pom.xml", POM.format(body=repositories(("central", "https://repo1.maven.org/maven2"),
                                                                     ("logicaldoc-snapshots", url))))
                self.assertEqual([problem("a/pom.xml", 9, "logicaldoc-snapshots")], self.problems())

    def test_other_logicaldoc_hosts_reported(self):
        self.write("pom.xml", POM.format(body=repositories(("ld", "https://maven.logicaldoc.com/releases"))))
        self.assertEqual([problem("pom.xml", 5, "ld", host="maven.logicaldoc.com")], self.problems())

    def test_plugin_repository_checked(self):
        self.write("pom.xml", POM.format(body=repositories(("ld-plugins", SOURCEFORGE), tag="pluginRepository")))
        self.assertEqual([problem("pom.xml", 5, "ld-plugins")], self.problems())

    def test_distribution_management_ignored(self):
        body = ("  <distributionManagement>\n    <repository>\n      <id>logicaldoc-community</id>\n"
                f"      <url>{SOURCEFORGE}</url>\n    </repository>\n  </distributionManagement>")
        self.write("pom.xml", POM.format(body=body))
        self.assertEqual([], self.problems())

    def test_other_hosts_ignored(self):
        self.write("pom.xml", POM.format(body=repositories(
            ("sourceforge", "https://oss.sonatype.org/content/groups/sourceforge/"),
            ("terracotta", "https://repo.terracotta.org/maven2"),
            ("props", "${repository.url}"))))
        self.assertEqual([], self.problems())

    def test_settings_without_healdan_mirror_reported(self):
        for body in ("", MIRROR.format(mirror_of="logicaldoc", url=SOURCEFORGE),
                     MIRROR.format(mirror_of="central", url="https://maven.healdan.com/")):
            with self.subTest(body=body):
                self.settings(body)
                self.assertEqual([f"docker/maven-settings.xml: {checker.NO_MIRROR}"], self.problems())

    def test_missing_settings_reported(self):
        subprocess.run(["git", "rm", "-q", "-f", "docker/maven-settings.xml"], cwd=self.repo, check=True)
        self.assertEqual([f"docker/maven-settings.xml: {checker.NO_MIRROR}"], self.problems())

    def test_mirror_of_list_and_url_without_slash(self):
        self.settings(MIRROR.format(mirror_of="logicaldoc, logicaldoc-snapshots", url="https://maven.healdan.com"))
        self.write("pom.xml", POM.format(body=repositories(("logicaldoc-snapshots", SOURCEFORGE))))
        self.assertEqual([], self.problems())

    def test_settings_repositories_checked(self):
        self.settings(MIRROR.format(mirror_of="logicaldoc", url="https://maven.healdan.com/") + "\n"
                      + repositories(("ld", SOURCEFORGE)))
        self.assertEqual([problem("docker/maven-settings.xml", 10, "ld")], self.problems())

    def test_untracked_pom_ignored(self):
        self.write("x/pom.xml", POM.format(body=repositories(("ld", SOURCEFORGE))), track=False)
        self.assertEqual([], self.problems())

    def test_unparseable_pom_reported(self):
        self.write("pom.xml", "<project><repositories></project>\n")
        problems = self.problems()
        self.assertEqual(1, len(problems))
        self.assertTrue(problems[0].startswith("pom.xml: can't be parsed: "), problems[0])

    def test_main_exit_codes(self):
        self.write("pom.xml", POM.format(body=repositories(("logicaldoc", SOURCEFORGE))))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main([str(self.repo)]))
        self.write("a/pom.xml", POM.format(body=repositories(("ld", SOURCEFORGE))))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, checker.main([str(self.repo)]))
        self.assertIn("a/pom.xml:5:", out.getvalue())

    def test_current_repo_passes(self):
        self.assertEqual([], checker.find_problems(_scripts.REPO))


if __name__ == "__main__":
    unittest.main()
