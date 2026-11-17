# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

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

RETIRED_MIRROR = """  <mirrors>
    <mirror>
      <id>healdan</id>
      <mirrorOf>logicaldoc</mirrorOf>
      <url>https://maven.healdan.com/</url>
    </mirror>
    <mirror>
      <id>retired-repositories</id>
      <mirrorOf>{mirror_of}</mirrorOf>
      <url>{url}</url>
    </mirror>
  </mirrors>"""

SOURCEFORGE = "https://logicaldoc.sourceforge.net/maven/"
JAVA_NET = "https://maven.java.net/content/repositories/releases"
OSSRH = "https://oss.sonatype.org/content/groups/sourceforge/"


def repositories(*entries, tag="repository"):
    """A <repositories> (or <pluginRepositories>) element, one <tag> per (id, url) entry, each four lines long."""
    wrapper = {"repository": "repositories", "pluginRepository": "pluginRepositories"}[tag]
    items = "".join(f"    <{tag}>\n      <id>{id_}</id>\n      <url>{url}</url>\n    </{tag}>\n" for id_, url in entries)
    return f"  <{wrapper}>\n{items}  </{wrapper}>"


def problem(name, line, id_, host="logicaldoc.sourceforge.net"):
    return f"{name}:{line}: repository {id_} " + checker.FIX.format(host=host, id=id_)


def retired(name, line, id_, host):
    return (f"{name}:{line}: repository {id_} "
            + checker.RETIRED_FIX.format(host=host, why=checker.RETIRED[host], id=id_))


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
            ("eclipse-releases", "https://repo.eclipse.org/content/groups/releases/"),
            ("terracotta", "https://repo.terracotta.org/maven2"),
            ("props", "${repository.url}"))))
        self.assertEqual([], self.problems())

    def test_retired_repositories_mirrored_to_central_pass(self):
        self.settings(RETIRED_MIRROR.format(mirror_of="java.net,sourceforge", url="https://repo1.maven.org/maven2/"))
        self.write("pom.xml", POM.format(body=repositories(("sourceforge", OSSRH), ("java.net", JAVA_NET))))
        self.write("samples/pom.xml", POM.format(body=repositories(
            ("sourceforge", "http://oss.sonatype.org/content/groups/sourceforge/"), ("java.net", JAVA_NET))))
        self.assertEqual([], self.problems())

    def test_unmirrored_retired_repositories_reported_with_file_and_line(self):
        self.write("pom.xml", POM.format(body=repositories(("central", "https://repo1.maven.org/maven2"),
                                                           ("sourceforge", OSSRH), ("java.net", JAVA_NET))))
        self.assertEqual([retired("pom.xml", 9, "sourceforge", "oss.sonatype.org"),
                          retired("pom.xml", 13, "java.net", "maven.java.net")], self.problems())

    def test_retired_hosts_reported_under_any_id_and_scheme(self):
        self.settings(RETIRED_MIRROR.format(mirror_of="java.net, sourceforge", url="https://repo1.maven.org/maven2"))
        self.write("pom.xml", POM.format(body=repositories(
            ("ossrh-snapshots", "http://OSS.sonatype.org/content/repositories/snapshots"),
            ("java-net-plugins", JAVA_NET), tag="pluginRepository")))
        self.assertEqual([retired("pom.xml", 5, "ossrh-snapshots", "oss.sonatype.org"),
                          retired("pom.xml", 9, "java-net-plugins", "maven.java.net")], self.problems())

    def test_retired_repository_mirrored_elsewhere_reported(self):
        for url in ("https://download.java.net/maven/2/", "https://repo1.maven.org/maven2/releases"):
            with self.subTest(url):
                self.settings(RETIRED_MIRROR.format(mirror_of="java.net", url=url))
                self.write("pom.xml", POM.format(body=repositories(("java.net", JAVA_NET))))
                self.assertEqual([retired("pom.xml", 5, "java.net", "maven.java.net")], self.problems())

    def test_retired_repository_may_be_mirrored_to_healdan(self):
        self.settings(MIRROR.format(mirror_of="logicaldoc,java.net", url="https://maven.healdan.com/"))
        self.write("pom.xml", POM.format(body=repositories(("java.net", JAVA_NET))))
        self.assertEqual([], self.problems())

    def test_retired_repository_and_logicaldoc_host_both_checked(self):
        self.settings(RETIRED_MIRROR.format(mirror_of="sourceforge", url="https://repo1.maven.org/maven2"))
        self.write("pom.xml", POM.format(body=repositories(("ld", SOURCEFORGE), ("sourceforge", OSSRH))))
        self.assertEqual([problem("pom.xml", 5, "ld")], self.problems())

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
                      + repositories(("ld", SOURCEFORGE), ("ossrh", OSSRH)))
        self.assertEqual([problem("docker/maven-settings.xml", 10, "ld"),
                          retired("docker/maven-settings.xml", 14, "ossrh", "oss.sonatype.org")], self.problems())

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
