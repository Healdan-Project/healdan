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
  <mirrors>
{mirrors}
  </mirrors>
{repositories}
</settings>
"""

MIRROR = """    <mirror>
      <id>{id}</id>
      <mirrorOf>{mirror_of}</mirrorOf>
      <url>{url}</url>{extra}
    </mirror>"""

SOURCEFORGE = "https://logicaldoc.sourceforge.net/maven/"
ECLIPSE = "https://repo.eclipse.org/content/groups/releases/"
GOOGLE = "https://google-api-client-libraries.appspot.com/mavenrepo"
ALFRESCO = "https://maven.alfresco.com/nexus/content/groups/public/"
CENTRAL = "https://repo1.maven.org/maven2/"


def repositories(*entries, tag="repository"):
    wrapper = {"repository": "repositories", "pluginRepository": "pluginRepositories"}[tag]
    items = "".join(f"    <{tag}>\n      <id>{id_}</id>\n      <url>{url}</url>\n    </{tag}>\n" for id_, url in entries)
    return f"  <{wrapper}>\n{items}  </{wrapper}>"


class CheckMavenRepositoriesTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.repo = pathlib.Path(self._tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)
        self.settings()

    def write(self, name, text, track=True):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        if track:
            subprocess.run(["git", "add", name], cwd=self.repo, check=True)

    def settings(self, *, mirror_of="logicaldoc", extra_mirror="", repos=""):
        mirror = MIRROR.format(id="healdan", mirror_of=mirror_of, url="https://maven.healdan.com/", extra="")
        self.write(checker.SETTINGS, SETTINGS.format(mirrors=mirror + extra_mirror, repositories=repos))

    def problems(self):
        return checker.find_problems(self.repo)

    def test_approved_direct_repositories_pass(self):
        self.write("pom.xml", POM.format(body=repositories(
            ("central", "https://repo.maven.apache.org/maven2/"),
            ("terracotta", "https://repo.terracotta.org/maven2"))))
        self.assertEqual([], self.problems())

    def test_alfresco_must_be_mirrored_to_healdan(self):
        self.write("pom.xml", POM.format(body=repositories(("Alfresco Maven Repository", ALFRESCO))))
        problem, = self.problems()
        self.assertIn("needs a mirror to https://maven.healdan.com", problem)
        self.settings(mirror_of="logicaldoc,Alfresco Maven Repository")
        self.assertEqual([], self.problems())

    def test_wrong_url_reported_even_under_an_approved_id(self):
        for url in ("http://repo1.maven.org/maven2", "https://repo1.maven.org/other", "${repository.url}"):
            with self.subTest(url=url):
                self.write("pom.xml", POM.format(body=repositories(("central", url))))
                problem, = self.problems()
                self.assertIn(f"pom.xml:5: repository central ({url}) is unapproved", problem)

    def test_explicit_mirrors_cover_dependencies_and_plugins(self):
        self.settings(mirror_of="logicaldoc,logicaldoc-snapshots",
                      extra_mirror="\n" + MIRROR.format(id="unused", mirror_of="eclipse-releases,google-api-services",
                                                        url=CENTRAL, extra=""))
        self.write("pom.xml", POM.format(body=repositories(("eclipse-releases", ECLIPSE),
                                                           ("logicaldoc-snapshots", SOURCEFORGE))))
        self.write("sub/pom.xml", POM.format(body=repositories(("google-api-services", GOOGLE), tag="pluginRepository")))
        self.assertEqual([], self.problems())

    def test_unmirrored_repositories_reported_with_file_and_line(self):
        self.write("pom.xml", POM.format(body=repositories(("eclipse-releases", ECLIPSE))))
        self.write("sub/pom.xml", POM.format(body=repositories(("new-plugins", "https://example.org/maven"),
                                                              tag="pluginRepository")))
        dependency, plugin = self.problems()
        self.assertIn("pom.xml:5: repository eclipse-releases (https://repo.eclipse.org/content/groups/releases) "
                      "is unapproved", dependency)
        self.assertIn("sub/pom.xml:5: repository new-plugins (https://example.org/maven) is unapproved", plugin)

    def test_settings_repositories_checked(self):
        self.settings(repos=repositories(("google-api-services", GOOGLE)))
        problem, = self.problems()
        self.assertIn("docker/maven-settings.xml:10: repository google-api-services", problem)

    def test_mirrors_to_unapproved_destinations_rejected(self):
        extra = MIRROR.format(id="invalid", mirror_of="central", url="https://example.org/maven", extra="")
        self.settings(extra_mirror="\n" + extra)
        problem, = self.problems()
        self.assertIn("mirror of central must point to Maven Central or https://maven.healdan.com", problem)

    def test_wildcard_padded_and_blocked_mirrors_do_not_cover_repositories(self):
        for ids, extra in (("*", ""), ("eclipse-releases, google-api-services", ""),
                           ("eclipse-releases", "<blocked>true</blocked>"),
                           ("eclipse-releases", "<mirrorOfLayouts>legacy</mirrorOfLayouts>")):
            with self.subTest(ids=ids, extra=extra):
                mirror = MIRROR.format(id="unused", mirror_of=ids, url=CENTRAL, extra=extra)
                self.settings(extra_mirror="\n" + mirror)
                self.write("pom.xml", POM.format(body=repositories(("eclipse-releases", ECLIPSE))))
                invalid, unapproved = self.problems()
                self.assertIn("needs explicit repository ids", invalid)
                self.assertIn("repository eclipse-releases", unapproved)

    def test_overlapping_mirrors_reported(self):
        extra = MIRROR.format(id="overlap", mirror_of="logicaldoc", url=CENTRAL, extra="")
        self.settings(extra_mirror="\n" + extra)
        self.assertTrue(any("second mirror for logicaldoc" in problem for problem in self.problems()))

    def test_other_logicaldoc_hosts_require_healdans_mirror(self):
        extra = MIRROR.format(id="unused", mirror_of="ld", url=CENTRAL, extra="")
        self.settings(extra_mirror="\n" + extra)
        self.write("pom.xml", POM.format(body=repositories(("ld", "https://maven.logicaldoc.com/releases"))))
        problem, = self.problems()
        self.assertIn("needs a mirror to https://maven.healdan.com", problem)

    def test_missing_logicaldoc_mirror_reported(self):
        self.settings(mirror_of="some-other-id")
        self.assertEqual([f"{checker.SETTINGS}: {checker.NO_MIRROR}"], self.problems())

    def test_missing_settings_reported(self):
        subprocess.run(["git", "rm", "-q", "-f", checker.SETTINGS], cwd=self.repo, check=True)
        self.assertEqual([f"{checker.SETTINGS}: {checker.NO_MIRROR}"], self.problems())

    def test_distribution_management_and_untracked_pom_ignored(self):
        body = ("  <distributionManagement>\n    <repository>\n      <id>logicaldoc-community</id>\n"
                f"      <url>{SOURCEFORGE}</url>\n    </repository>\n  </distributionManagement>")
        self.write("pom.xml", POM.format(body=body))
        self.write("x/pom.xml", POM.format(body=repositories(("ld", SOURCEFORGE))), track=False)
        self.assertEqual([], self.problems())

    def test_unparseable_pom_reported(self):
        self.write("pom.xml", "<project><repositories></project>\n")
        problem, = self.problems()
        self.assertTrue(problem.startswith("pom.xml: can't be parsed: "), problem)

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
