# Healdan's Maven Repository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Healdan's builds get the artifacts only LogicalDOC's Maven repository has from Healdan's own copies at `https://maven.healdan.com/`, so nothing in the build depends on LogicalDOC's infrastructure.

**Architecture:** A new public repository, `Healdan-Project/maven`, holds the artifacts in Maven's layout and GitHub Pages serves it. Every file gets in through its tested `import-artifact.py`. In this repository, a `<mirror>` of the `logicaldoc` repository in `docker/maven-settings.xml` sends every request for it to Healdan's copies, without touching upstream POMs. A new check in CI keeps any other LogicalDOC repository out. JAI, betelio and ksoap2, whose licences don't allow hosting them, leave the build instead.

**Tech Stack:** Maven 3.9+ settings mirrors, GitHub Pages (custom domain), Python 3 (standard library only, `unittest`), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md`. Read it first: this plan argues from it.

## Global Constraints

- The hosted URL is exactly `https://maven.healdan.com/`. The mirror's `<id>` is `healdan` and its `<mirrorOf>` is `logicaldoc`.
- The hosted repository is `Healdan-Project/maven`, default branch `main`, checked out at `~/Projects/healdan-maven` (outside this repository).
- The only upstream files this plan edits are the three in spec §11: `logicaldoc-core/pom.xml` (drop JAI and betelio), `logicaldoc-webapp/pom.xml` (drop betelio) and the root `pom.xml` (drop the `logicaldoc-webserviceclient` module). Everything else is Healdan's own: `docker/maven-settings.xml`, `.github/`, `CLAUDE.md`, `site/`, `docs/`.
- Scripts are Python 3 with the standard library only, tested with `unittest`, like `.github/scripts/`.
- A hosted version never changes once it's published.
- Outward-facing steps need the owner's OK in chat first, each time: creating the GitHub repository, pushing to it, changing its Pages settings, pushing this branch, opening the PR. The owner adds the DNS record.
- `gh` commands for this repository pass `--repo Healdan-Project/healdan`, because `gh`'s default here is upstream.
- Docs follow the Developer Guide's Writing Docs page: British spelling ("licence" as the noun), Title Case for titles, headings and labels, Healdan's glossary terms.
- No internal hostnames or IP addresses in anything public: commits, docs, issues, PRs.
- Don't edit `~/.m2/settings.xml`. Builds in this plan pass `-s docker/maven-settings.xml` and an empty `-Dmaven.repo.local` under `~/.cache/healdan-m2/`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. The PR's description ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

## Spec Corrections and Calls This Plan Makes

1. **`import.sh` becomes `import-artifact.py`** (spec §4.3), so it's tested with `unittest` like Healdan's other scripts. It does what §4.3 says, and two more things:
   - `--scan LOCAL_REPO --repository-id ID` lists the artifacts a local Maven repository records as downloaded from repository `ID`. It produces the first import's list (§4.3) and checks the clean build (§8).
   - `--verify [URL]` checks every hosted file against its `.sha1` and the README's table and, given a URL, against what that URL serves. That's §7's "once Pages has published it". The hosted repository's CI runs `--verify` and the tests.
2. **The README's Source column** reads `logicaldoc.sourceforge.net, <date>` for an import from SourceForge, and the `--source` text for `--file`.
3. **The assembly stays as it is.** `logicaldoc-core/src/main/assembly/assemblyplugin.xml` includes `javax.media:*` and `com.sun.media:*`. Once JAI's gone they match nothing, and the assembly plugin (no strict filtering) only warns, so the upstream edits stay in the POMs.
4. **`site/content/developer/upstream-syncs.md`** also gets a clause, beside its Font Awesome and doc-link examples of merge fixes. §9 didn't list it.
5. **No `UPSTREAM_TESTS` addition.** The drops change no code, and nothing references JAI or betelio (spec §3.1). Task 3 runs core's imaging tests locally, and CI's Docker smoke test runs the WAR.
6. **`LICENSES/` also carries jgravatar's BSD licence,** which its jar lacks too (spec §4.2 lists the others). For JPF's source, the README points to JPF's project, https://jpf.sourceforge.net/, rather than hosting a copy.

## File Structure

**`Healdan-Project/maven`** (new, at `~/Projects/healdan-maven`):

- `import-artifact.py`: imports one artifact (from LogicalDOC's repository or a local jar), and scans and verifies. One file, because its pieces share the coordinate model and are small.
- `tests/test_import_artifact.py`: its tests. They load the script by path, because its name has a hyphen.
- `README.md`: what the repository is, how to import, the artifact table between `<!-- artifacts -->` markers, the licence notes, and the Not Hosted table.
- `LICENSES/`: the licence texts and NOTICE files the hosted jars lack (spec §3.1, §4.2).
- `CNAME` (`maven.healdan.com`) and `.nojekyll`.
- `.github/workflows/check.yml`: runs the tests and `--verify` on pushes and pull requests.
- The artifacts, in Maven's layout at the root (`com/…`, `net/…`, `org/…`, …).

**This repository:**

- Modify `logicaldoc-core/pom.xml` (drop `jai-core`, `jai-codec` and `betelio`), `logicaldoc-webapp/pom.xml` (drop `betelio`) and the root `pom.xml` (drop the `logicaldoc-webserviceclient` module) (Task 3).
- Modify `docker/maven-settings.xml`: the mirror (Task 6).
- Create `.github/scripts/check-maven-repositories.py` and `.github/scripts/tests/test_check_maven_repositories.py` (Task 7).
- Modify `.github/workflows/build-logicaldoc.yml`: run the check in the `checks` job (Task 7).
- Modify `CLAUDE.md`, `site/content/developer/building-from-source.md`, `site/content/developer/continuous-integration.md` and `site/content/developer/upstream-syncs.md` (Task 8).
- Modify the spec's status (Task 8).

A parallel session is changing `build-logicaldoc.yml`'s Maven cache steps and, probably, `CLAUDE.md`'s cache bullet (line 60). Task 9 brings this branch up to date with `master` before the PR, and resolves any conflict there.

**Order.** Tasks 1–2 build the tool. Task 3 makes the drops before Task 4's inventory, so the inventory doesn't list them. Task 5 publishes the hosted repository, which Task 6's mirror needs, and Task 7's check needs Task 6's mirror to pass on this repository.

---

### Task 1: `import-artifact.py` Imports and Records an Artifact

**Files:**
- Create: `~/Projects/healdan-maven/import-artifact.py`
- Create: `~/Projects/healdan-maven/tests/test_import_artifact.py`
- Create: `~/Projects/healdan-maven/README.md`

**Interfaces:**
- Produces (used by Tasks 2, 4 and 5):
  - `Coordinates(group, artifact, version, classifier="")`, a frozen dataclass, with `Coordinates.parse(text)` (raises `ValueError`), `Coordinates.from_jar_path(parts)` (the inverse of `.jar.parts`), `str(coordinates)`, and the properties `.directory`, `.jar` and `.pom` (`pathlib.PurePosixPath`, relative to a repository root).
  - `Problem(Exception)`: a message for the user; `main` prints it and returns 1.
  - `sha1(data) -> str` and `published_sha1(text) -> str`.
  - `fetch(url) -> bytes` and `fetch_verified(base, path) -> bytes`.
  - `stub_pom(coordinates) -> bytes`, `write_artifact(root, coordinates, jar, pom)`, `readme_row(coordinates, project, licence, source) -> str`, `add_readme_row(readme, row) -> str` and `source_label(base, today) -> str`.
  - `main(argv, root=ROOT, today=None) -> int`.
  - The CLI: `python3 import-artifact.py GROUP:ARTIFACT:VERSION[:CLASSIFIER] --project TEXT --licence SPDX [--from URL]`, or `… --file JAR --source TEXT`.

- [ ] **Step 1: Start the repository**

```bash
mkdir -p ~/Projects/healdan-maven/tests
cd ~/Projects/healdan-maven
git init -q -b main
```

- [ ] **Step 2: Write `README.md`**

The artifact table starts empty. `import-artifact.py` adds every row.

````markdown
# Healdan's Maven Repository

Copies of the Maven artifacts [Healdan](https://github.com/Healdan-Project/healdan)'s build needs that only
LogicalDOC's Maven repository (`https://logicaldoc.sourceforge.net/maven/`) has. GitHub Pages serves them at
https://maven.healdan.com/, and Healdan's `docker/maven-settings.xml` mirrors LogicalDOC's repository (id
`logicaldoc`) to it, so Healdan's builds never contact LogicalDOC. The design is Healdan's
[`docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md`](https://github.com/Healdan-Project/healdan/blob/master/docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md).

Each artifact keeps its own licence, listed below. Healdan's image already ships these jars as part of the program.

## Importing an Artifact

Files only get in through `import-artifact.py` (Python 3, standard library only), so this repository's history says
where each one came from. A hosted version never changes.

First check that the artifact's licence allows hosting it publicly. One that doesn't stays out: Healdan drops it from
its build instead, and it goes in Not Hosted below. Then:

```bash
python3 import-artifact.py <group>:<artifact>:<version>[:<classifier>] --project '<name and link>' --licence <SPDX id>
```

That fetches the jar and POM from LogicalDOC's repository, checks each against the `.sha1` published beside it,
writes them with their `.sha1`s, and adds the artifact's row below. If LogicalDOC's repository refuses or has gone,
import a jar taken from a LogicalDOC CE release bundle instead, with `--file <jar> --source '<where it came from>'`.
It gets a stub POM.

Push, and once `python3 import-artifact.py --verify https://maven.healdan.com/` passes, Pages has published it.

## Artifacts

<!-- artifacts -->
| Coordinates | Project | Licence | Source |
|---|---|---|---|
<!-- /artifacts -->

## Licences

Each artifact's licence is in the table above. `LICENSES/` holds the texts and notices the jars themselves lack:
`Apache-2.0.txt`; `LGPL-2.1.txt`; `LGPL-3.0.txt` with `GPL-3.0.txt`, which it builds on; `BSD-3-Clause-jgravatar.txt`;
and the NOTICE files of Apache Commons FileUpload and Apache Jackrabbit.

- `commons-fileupload:commons-fileupload:1.6.0-jakarta` and the three `org.apache.jackrabbit` `2.22.0-jakarta` jars
  are LogicalDOC's modified builds of Apache's, rebuilt for Jakarta Servlet. FileUpload's portlet package is gone.
- JPF's jars contain no source. Its source is at https://jpf.sourceforge.net/.
- Smart GWT's Java sources and Isomorphic's
  [licensing FAQ](https://smartclient.com/product/licensingFAQ.jsp) put its LGPL edition under LGPL-3.0. Some of its
  debug and tool JavaScript files carry Isomorphic's proprietary boilerplate. Healdan relies on the LGPL statement.

## Not Hosted

| Coordinates | Licence | Why |
|---|---|---|
| `javax.media:jai-core:1.1.3`, `com.sun.media:jai-codec:1.1.3` | Sun Microsystems, Inc. Binary Code License Agreement | Its POM says distribution is `manual`, and Maven Central carries its POM but not its jar. Nothing in Healdan's build references its classes, so Healdan's `logicaldoc-core` drops it. |
| `betelio:betelio:1.0` | None stated | No licence and no confirmed author. Nothing in Healdan's build uses it, so Healdan drops it. |
| `com.google.code.ksoap2-android:ksoap2-android-assembly:3.3.0` | MIT, with bundled kObjects code that's partly GPLv2 and partly unlicensed | Its only user was LogicalDOC's SOAP client module, `logicaldoc-webserviceclient`, which Healdan leaves out of its build. |
````

- [ ] **Step 3: Write the failing tests**

`tests/test_import_artifact.py`:

```python
import contextlib
import hashlib
import importlib.util
import io
import pathlib
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("import_artifact", HERE.parent / "import-artifact.py")
tool = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tool)

README = ("# Healdan's Maven Repository\n\n<!-- artifacts -->\n| Coordinates | Project | Licence | Source |\n"
          "|---|---|---|---|\n<!-- /artifacts -->\n\n## Not Hosted\n")
JPF = "net.sf.jpf:jpf:1.5.1"
KSOAP = "com.google.code.ksoap2-android:ksoap2-android-assembly:3.3.0:jar-with-dependencies"


class CoordinatesTest(unittest.TestCase):

    def test_paths(self):
        jpf = tool.Coordinates.parse(JPF)
        self.assertEqual("net/sf/jpf/jpf/1.5.1/jpf-1.5.1.jar", jpf.jar.as_posix())
        self.assertEqual("net/sf/jpf/jpf/1.5.1/jpf-1.5.1.pom", jpf.pom.as_posix())
        self.assertEqual(JPF, str(jpf))

    def test_classifier_names_the_jar_not_the_pom(self):
        ksoap = tool.Coordinates.parse(KSOAP)
        folder = "com/google/code/ksoap2-android/ksoap2-android-assembly/3.3.0/"
        self.assertEqual(folder + "ksoap2-android-assembly-3.3.0-jar-with-dependencies.jar", ksoap.jar.as_posix())
        self.assertEqual(folder + "ksoap2-android-assembly-3.3.0.pom", ksoap.pom.as_posix())
        self.assertEqual(KSOAP, str(ksoap))

    def test_bad_coordinates_refused(self):
        for text in ("a:b", "a::c", "a:b:c:d:e", ""):
            with self.subTest(text), self.assertRaises(ValueError):
                tool.Coordinates.parse(text)

    def test_from_jar_path_reverses_jar(self):
        for text in (JPF, KSOAP):
            with self.subTest(text):
                coordinates = tool.Coordinates.parse(text)
                self.assertEqual(coordinates, tool.Coordinates.from_jar_path(coordinates.jar.parts))


class ReadmeTest(unittest.TestCase):

    def test_rows_stay_sorted(self):
        readme = README
        for text in (JPF, "betelio:betelio:1.0"):
            readme = tool.add_readme_row(readme, tool.readme_row(tool.Coordinates.parse(text), "P", "L", "S"))
        self.assertIn("|---|---|---|---|\n| `betelio:betelio:1.0` | P | L | S |\n| `net.sf.jpf:jpf:1.5.1` | P | L | S |\n"
                      "<!-- /artifacts -->\n\n## Not Hosted\n", readme)

    def test_duplicate_row_refused(self):
        row = tool.readme_row(tool.Coordinates.parse(JPF), "P", "L", "S")
        with self.assertRaises(tool.Problem):
            tool.add_readme_row(tool.add_readme_row(README, row), row)

    def test_missing_table_refused(self):
        with self.assertRaises(tool.Problem):
            tool.add_readme_row("# No table\n", "| `a:b:c` | P | L | S |")

    def test_source_label(self):
        self.assertEqual("logicaldoc.sourceforge.net, 2026-10-29",
                         tool.source_label("https://logicaldoc.sourceforge.net/maven/", "2026-10-29"))


class ImportTest(unittest.TestCase):

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = pathlib.Path(tmp.name)
        self.root = self.base / "hosted"
        self.root.mkdir()
        (self.root / "README.md").write_text(README, encoding="utf-8")
        self.remote = self.base / "remote"
        self.remote_url = self.remote.as_uri() + "/"

    def publish(self, path, data, checksum=None):
        """Puts a file in the fake remote repository, with a .sha1 beside it as SourceForge writes one."""
        target = self.remote / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        checksum = checksum or hashlib.sha1(data).hexdigest()
        (target.parent / (target.name + ".sha1")).write_text(f"{checksum}  {target.name}\n", encoding="ascii")

    def run_tool(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = tool.main(list(argv), root=self.root, today="2026-10-29")
        return status, out.getvalue(), err.getvalue()

    def import_jpf(self):
        return self.run_tool(JPF, "--from", self.remote_url, "--project", "[JPF](https://jpf.sourceforge.net/)",
                             "--licence", "LGPL-2.1-only")

    def test_imports_jar_pom_checksums_and_row(self):
        jpf = tool.Coordinates.parse(JPF)
        self.publish(jpf.jar, b"jar bytes")
        self.publish(jpf.pom, b"<project/>")
        status, out, err = self.import_jpf()
        self.assertEqual((0, ""), (status, err))
        self.assertIn(f"Imported {JPF}", out)
        self.assertEqual(b"jar bytes", (self.root / jpf.jar).read_bytes())
        self.assertEqual(b"<project/>", (self.root / jpf.pom).read_bytes())
        self.assertEqual(hashlib.sha1(b"jar bytes").hexdigest() + "\n",
                         (self.root / jpf.directory / "jpf-1.5.1.jar.sha1").read_text(encoding="ascii"))
        self.assertEqual(hashlib.sha1(b"<project/>").hexdigest() + "\n",
                         (self.root / jpf.directory / "jpf-1.5.1.pom.sha1").read_text(encoding="ascii"))
        self.assertIn(f"| `{JPF}` | [JPF](https://jpf.sourceforge.net/) | LGPL-2.1-only | {self.remote_url}, 2026-10-29 |",
                      (self.root / "README.md").read_text(encoding="utf-8"))

    def test_checksum_mismatch_refused_and_nothing_written(self):
        jpf = tool.Coordinates.parse(JPF)
        self.publish(jpf.jar, b"jar bytes", checksum="0" * 40)
        self.publish(jpf.pom, b"<project/>")
        status, _, err = self.import_jpf()
        self.assertEqual(1, status)
        self.assertIn("SHA-1", err)
        self.assertFalse((self.root / jpf.directory).exists())
        self.assertEqual(README, (self.root / "README.md").read_text(encoding="utf-8"))

    def test_missing_remote_file_refused(self):
        status, _, err = self.import_jpf()
        self.assertEqual(1, status)
        self.assertIn("jpf-1.5.1.jar", err)
        self.assertEqual(README, (self.root / "README.md").read_text(encoding="utf-8"))

    def test_hosted_version_never_changes(self):
        jpf = tool.Coordinates.parse(JPF)
        self.publish(jpf.jar, b"jar bytes")
        self.publish(jpf.pom, b"<project/>")
        self.assertEqual(0, self.import_jpf()[0])
        self.publish(jpf.jar, b"other bytes")
        status, _, err = self.import_jpf()
        self.assertEqual(1, status)
        self.assertIn("already", err)
        self.assertEqual(b"jar bytes", (self.root / jpf.jar).read_bytes())

    def test_file_import_writes_stub_pom_and_source(self):
        jar = self.base / "jpf.jar"
        jar.write_bytes(b"jar from a bundle")
        status, _, err = self.run_tool(JPF, "--file", str(jar), "--source", "LogicalDOC CE 9.3.2 release bundle",
                                       "--project", "JPF", "--licence", "LGPL-2.1-only")
        self.assertEqual((0, ""), (status, err))
        jpf = tool.Coordinates.parse(JPF)
        self.assertEqual(b"jar from a bundle", (self.root / jpf.jar).read_bytes())
        pom = (self.root / jpf.pom).read_text(encoding="utf-8")
        for element in ("<groupId>net.sf.jpf</groupId>", "<artifactId>jpf</artifactId>", "<version>1.5.1</version>"):
            self.assertIn(element, pom)
        self.assertIn("| LogicalDOC CE 9.3.2 release bundle |", (self.root / "README.md").read_text(encoding="utf-8"))

    def test_classifier_import(self):
        ksoap = tool.Coordinates.parse(KSOAP)
        self.publish(ksoap.jar, b"assembly")
        self.publish(ksoap.pom, b"<project/>")
        status, _, err = self.run_tool(KSOAP, "--from", self.remote_url, "--project", "ksoap2-android",
                                       "--licence", "MIT")
        self.assertEqual((0, ""), (status, err))
        self.assertEqual(b"assembly", (self.root / ksoap.jar).read_bytes())
        self.assertTrue((self.root / ksoap.pom).is_file())

    def test_usage_errors(self):
        jar = self.base / "x.jar"
        jar.write_bytes(b"x")
        for argv in ((JPF, "--project", "P"),
                     (JPF, "--file", str(jar), "--project", "P", "--licence", "L")):
            with self.subTest(argv), self.assertRaises(SystemExit), \
                    contextlib.redirect_stderr(io.StringIO()):
                tool.main(list(argv), root=self.root, today="2026-10-29")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 4: Run the tests to see them fail**

Run: `cd ~/Projects/healdan-maven && python3 -m unittest discover -s tests -v`
Expected: an error loading the module: `FileNotFoundError` for `import-artifact.py`.

- [ ] **Step 5: Write `import-artifact.py`**

```python
#!/usr/bin/env python3
#
# Healdan's copies of the Maven artifacts only LogicalDOC's repository has. Every file here gets in through this
# script, so the history says where each one came from, and a hosted version never changes. Standard library only.
#
# Usage:
#   import-artifact.py GROUP:ARTIFACT:VERSION[:CLASSIFIER] --project TEXT --licence SPDX [--from URL]
#       Fetches the jar and POM from LogicalDOC's repository (or URL), checks each against the .sha1 published
#       beside it, writes them with their .sha1s, and adds the artifact's row to README.md.
#   import-artifact.py GROUP:ARTIFACT:VERSION[:CLASSIFIER] --file JAR --source TEXT --project TEXT --licence SPDX
#       Imports a jar taken from elsewhere, such as a LogicalDOC CE release bundle, with a stub POM.
#   import-artifact.py --scan LOCAL_REPO [--repository-id ID]
#       Lists the artifacts a local Maven repository records as downloaded from repository ID (logicaldoc).
#   import-artifact.py --verify [URL]
#       Checks every hosted file against its .sha1 and README.md's table and, with URL, against what URL serves.
# Exit status: 0 on success, 1 on a problem, 2 on bad usage.

import argparse
import dataclasses
import datetime
import hashlib
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
SOURCEFORGE = "https://logicaldoc.sourceforge.net/maven/"
TABLE_START = "<!-- artifacts -->"
TABLE_END = "<!-- /artifacts -->"


class Problem(Exception):
    """Something the user needs to fix. main() prints it and returns 1."""


@dataclasses.dataclass(frozen=True)
class Coordinates:
    group: str
    artifact: str
    version: str
    classifier: str = ""

    @classmethod
    def parse(cls, text):
        parts = text.split(":")
        if len(parts) not in (3, 4) or not all(parts):
            raise ValueError(f"expected group:artifact:version[:classifier], not {text!r}")
        return cls(*parts)

    @classmethod
    def from_jar_path(cls, parts):
        """The coordinates of the jar at parts (group folders, artifact, version, file name), as in .jar.parts."""
        *group, artifact, version, name = parts
        stem = f"{artifact}-{version}"
        if not group or not name.startswith(stem) or not name.endswith(".jar"):
            raise ValueError(f"{'/'.join(parts)} isn't a jar in Maven's layout")
        return cls(".".join(group), artifact, version, name[len(stem):-len(".jar")].lstrip("-"))

    def __str__(self):
        return ":".join(part for part in (self.group, self.artifact, self.version, self.classifier) if part)

    @property
    def directory(self):
        return pathlib.PurePosixPath(*self.group.split("."), self.artifact, self.version)

    @property
    def jar(self):
        classifier = f"-{self.classifier}" if self.classifier else ""
        return self.directory / f"{self.artifact}-{self.version}{classifier}.jar"

    @property
    def pom(self):
        return self.directory / f"{self.artifact}-{self.version}.pom"


def sha1(data):
    return hashlib.sha1(data).hexdigest()


def published_sha1(text):
    """The checksum in a .sha1 file, which may be followed by a file name."""
    words = text.split()
    return words[0].lower() if words else ""


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Healdan import-artifact.py"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except (urllib.error.URLError, OSError) as error:
        raise Problem(f"{url}: {error}") from error


def fetch_verified(base, path):
    """Fetches base + path and checks it against the .sha1 published beside it."""
    url = urllib.parse.urljoin(base, str(path))
    data = fetch(url)
    expected = published_sha1(fetch(url + ".sha1").decode("ascii", errors="replace"))
    if sha1(data) != expected:
        raise Problem(f"{url}: its SHA-1 is {sha1(data)}, but {url}.sha1 says {expected or 'nothing'}")
    return data


def stub_pom(coordinates):
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
            f'  <modelVersion>4.0.0</modelVersion>\n'
            f'  <groupId>{coordinates.group}</groupId>\n'
            f'  <artifactId>{coordinates.artifact}</artifactId>\n'
            f'  <version>{coordinates.version}</version>\n'
            f'</project>\n').encode("utf-8")


def write_artifact(root, coordinates, jar, pom):
    """Writes the jar and POM with their .sha1s. Never changes a hosted file."""
    jar_path, pom_path = root / coordinates.jar, root / coordinates.pom
    if jar_path.exists():
        raise Problem(f"{coordinates.jar} is already hosted, and a hosted version never changes")
    if pom_path.exists() and pom_path.read_bytes() != pom:
        raise Problem(f"{coordinates.pom} is already hosted with different content")
    jar_path.parent.mkdir(parents=True, exist_ok=True)
    for path, data in ((jar_path, jar), (pom_path, pom)):
        path.write_bytes(data)
        path.with_name(path.name + ".sha1").write_text(sha1(data) + "\n", encoding="ascii")


def readme_row(coordinates, project, licence, source):
    return f"| `{coordinates}` | {project} | {licence} | {source} |"


def _row_key(row):
    return row.split("|")[1].strip()


def add_readme_row(readme, row):
    """Returns readme with row added to the artifact table, which stays sorted by coordinates."""
    try:
        start = readme.index(TABLE_START) + len(TABLE_START)
        end = readme.index(TABLE_END, start)
    except ValueError:
        raise Problem(f"README.md has no table between {TABLE_START} and {TABLE_END}") from None
    lines = readme[start:end].strip("\n").split("\n")
    header, rows = lines[:2], [line for line in lines[2:] if line.strip()]
    if any(_row_key(line) == _row_key(row) for line in rows):
        raise Problem(f"README.md already lists {_row_key(row)}")
    rows = sorted(rows + [row], key=_row_key)
    return readme[:start] + "\n" + "\n".join(header + rows) + "\n" + readme[end:]


def source_label(base, today):
    return f"{urllib.parse.urlsplit(base).hostname or base}, {today}"


def main(argv, root=ROOT, today=None):
    parser = argparse.ArgumentParser(prog="import-artifact.py",
                                     description="Imports, scans and verifies Healdan's hosted Maven artifacts.")
    parser.add_argument("coordinates", nargs="?", help="GROUP:ARTIFACT:VERSION[:CLASSIFIER]")
    parser.add_argument("--project", help="the upstream project, as Markdown for the README's table")
    parser.add_argument("--licence", help="the artifact's licence, as an SPDX id")
    parser.add_argument("--from", dest="base", default=SOURCEFORGE, help="the repository to fetch from")
    parser.add_argument("--file", type=pathlib.Path, help="import this jar instead of fetching one")
    parser.add_argument("--source", help="with --file: where the jar came from")
    args = parser.parse_args(argv)
    if not (args.coordinates and args.project and args.licence):
        parser.error("importing needs coordinates, --project and --licence")
    if args.file and not args.source:
        parser.error("--file needs --source, saying where the jar came from")
    try:
        coordinates = Coordinates.parse(args.coordinates)
        if args.file:
            jar, pom, source = args.file.read_bytes(), stub_pom(coordinates), args.source
        else:
            base = args.base if args.base.endswith("/") else args.base + "/"
            jar, pom = fetch_verified(base, coordinates.jar), fetch_verified(base, coordinates.pom)
            source = source_label(base, today or datetime.date.today().isoformat())
        readme_path = root / "README.md"
        readme = add_readme_row(readme_path.read_text(encoding="utf-8"),
                                readme_row(coordinates, args.project, args.licence, source))
        write_artifact(root, coordinates, jar, pom)
        readme_path.write_text(readme, encoding="utf-8")
    except (Problem, ValueError, OSError) as problem:
        print(problem, file=sys.stderr)
        return 1
    print(f"Imported {coordinates} from {source}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `cd ~/Projects/healdan-maven && python3 -m unittest discover -s tests -v`
Expected: all tests in `CoordinatesTest`, `ReadmeTest` and `ImportTest` pass.

- [ ] **Step 7: Commit**

```bash
cd ~/Projects/healdan-maven
git add README.md import-artifact.py tests/test_import_artifact.py
git commit -m "Import LogicalDOC-only Maven artifacts with checksums and a README row

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `import-artifact.py` Scans and Verifies, and Its CI

**Files:**
- Modify: `~/Projects/healdan-maven/import-artifact.py`
- Modify: `~/Projects/healdan-maven/tests/test_import_artifact.py`
- Create: `~/Projects/healdan-maven/.github/workflows/check.yml`

**Interfaces:**
- Consumes: `Coordinates`, `Problem`, `sha1`, `published_sha1`, `fetch`, `main` from Task 1.
- Produces (used by Tasks 4, 5 and 6):
  - `scan(local_repo, repository_id) -> list[Coordinates]`, sorted by `str`;
  - `verify(root, url=None) -> list[str]`, its problems;
  - the CLI: `python3 import-artifact.py --scan LOCAL_REPO [--repository-id ID]` (one coordinate per line, exit 0) and `python3 import-artifact.py --verify [URL]` (a problem per line; exit 1 if any, else 0).

- [ ] **Step 1: Write the failing tests**

Append these classes to `tests/test_import_artifact.py`, above `if __name__ == "__main__":`:

```python
HASH = "3c626cebd032ea6349f2426cabc3dcb9ad2aee3f"


class ScanTest(unittest.TestCase):

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.local = pathlib.Path(tmp.name)

    def record(self, folder, *lines):
        path = self.local / folder / "_remote.repositories"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#NOTE: This is a Maven Resolver internal implementation file\n" + "\n".join(lines) + "\n",
                        encoding="utf-8")

    def test_lists_jars_from_the_repository_in_both_record_formats(self):
        # Maven 3.10 keys a record by id and a hash of the URL; Maven 3.9 by id alone
        self.record("net/sf/jpf/jpf/1.5.1", f"jpf-1.5.1.jar>logicaldoc-{HASH}=", f"jpf-1.5.1.pom>logicaldoc-{HASH}=")
        self.record("com/google/code/ksoap2-android/ksoap2-android-assembly/3.3.0",
                    "ksoap2-android-assembly-3.3.0-jar-with-dependencies.jar>logicaldoc=",
                    "ksoap2-android-assembly-3.3.0.pom>logicaldoc=")
        self.record("org/example/central-only/1", f"central-only-1.jar>central-{HASH}=")
        self.record("org/example/snapshots/1", "snapshots-1.jar>logicaldoc-snapshots=")
        self.record("org/example/installed/1", "installed-1.jar>=")
        self.assertEqual([KSOAP, JPF], [str(c) for c in tool.scan(self.local, "logicaldoc")])
        self.assertEqual(["org.example:central-only:1"], [str(c) for c in tool.scan(self.local, "central")])

    def test_cli_prints_one_per_line(self):
        self.record("net/sf/jpf/jpf/1.5.1", f"jpf-1.5.1.jar>healdan-{HASH}=")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, tool.main(["--scan", str(self.local), "--repository-id", "healdan"]))
        self.assertEqual(JPF + "\n", out.getvalue())


class VerifyTest(unittest.TestCase):

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = pathlib.Path(tmp.name)
        self.root = self.base / "hosted"
        (self.root / "tests").mkdir(parents=True)
        (self.root / "tests" / "fixture.jar").write_bytes(b"not hosted")  # tests/ isn't part of the layout
        (self.root / "README.md").write_text(README, encoding="utf-8")
        jar = self.base / "jpf.jar"
        jar.write_bytes(b"jar bytes")
        with contextlib.redirect_stdout(io.StringIO()):
            status = tool.main([JPF, "--file", str(jar), "--source", "a bundle", "--project", "JPF",
                                "--licence", "LGPL-2.1-only"], root=self.root, today="2026-10-29")
        self.assertEqual(0, status)
        self.jpf = tool.Coordinates.parse(JPF)

    def test_clean_repository_passes(self):
        self.assertEqual([], tool.verify(self.root))

    def test_changed_file_reported(self):
        (self.root / self.jpf.jar).write_bytes(b"changed")
        self.assertEqual([f"{self.jpf.jar}: doesn't match its .sha1"], tool.verify(self.root))

    def test_missing_checksum_reported(self):
        (self.root / self.jpf.directory / "jpf-1.5.1.pom.sha1").unlink()
        self.assertEqual([f"{self.jpf.pom}: has no .sha1 beside it"], tool.verify(self.root))

    def test_unlisted_and_unhosted_reported(self):
        readme = (self.root / "README.md").read_text(encoding="utf-8")
        (self.root / "README.md").write_text(readme.replace(f"`{JPF}`", "`betelio:betelio:1.0`"), encoding="utf-8")
        self.assertEqual([f"{JPF}: hosted but not in README.md's table",
                          "betelio:betelio:1.0: in README.md's table but not hosted"], tool.verify(self.root))

    def test_published_copy_compared(self):
        self.assertEqual([], tool.verify(self.root, self.root.as_uri() + "/"))
        served = self.base / "served"
        (served / self.jpf.directory).mkdir(parents=True)
        (served / self.jpf.jar).write_bytes(b"stale")
        (served / self.jpf.pom).write_bytes((self.root / self.jpf.pom).read_bytes())
        self.assertEqual([f"{self.jpf.jar}: {served.as_uri()}/ serves different content"],
                         tool.verify(self.root, served.as_uri() + "/"))

    def test_unpublished_copy_reported(self):
        problems = tool.verify(self.root, (self.base / "empty").as_uri() + "/")
        self.assertEqual(2, len(problems))
        self.assertTrue(problems[0].startswith(f"{self.jpf.jar}: "), problems[0])

    def test_cli_exit_codes(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, tool.main(["--verify"], root=self.root))
        (self.root / self.jpf.jar).write_bytes(b"changed")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, tool.main(["--verify"], root=self.root))
        self.assertIn("doesn't match its .sha1", out.getvalue())
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd ~/Projects/healdan-maven && python3 -m unittest discover -s tests -v`
Expected: `ScanTest` and `VerifyTest` fail with `AttributeError: module 'import_artifact' has no attribute 'scan'` (and `'verify'`), and the CLI tests exit 2 because `--scan` and `--verify` aren't arguments yet. Task 1's tests still pass.

- [ ] **Step 3: Add `scan` and `verify`**

In `import-artifact.py`, add below `source_label`:

```python
# A record line in _remote.repositories: <file>><repository key>=, the key being the id, or (Maven 3.10) the id, a
# hyphen and a SHA-1 of the repository's URL. An installed file has an empty key.
RECORD = re.compile(r"^(?P<file>[^#>][^>]*)>(?P<key>[^=]*)=$")
EXCLUDED = {".git", ".github", "tests"}


def scan(local_repo, repository_id):
    """The jars a local Maven repository records as downloaded from repository_id."""
    local_repo = pathlib.Path(local_repo)
    key = re.compile(re.escape(repository_id) + r"(-[0-9a-f]{40})?")
    found = set()
    for record in local_repo.rglob("_remote.repositories"):
        folder = record.parent.relative_to(local_repo).parts
        for line in record.read_text(encoding="utf-8", errors="replace").splitlines():
            match = RECORD.match(line)
            if match and match["file"].endswith(".jar") and key.fullmatch(match["key"]):
                found.add(Coordinates.from_jar_path(folder + (match["file"],)))
    return sorted(found, key=str)


def hosted_files(root):
    """Every .jar and .pom in the layout under root, relative to it."""
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if relative.parts[0] not in EXCLUDED and path.is_file() and path.suffix in (".jar", ".pom"):
            yield relative


def verify(root, url=None):
    """Checks every hosted file against its .sha1 and README.md's table and, with url, against what url serves."""
    problems = []
    hosted = set()
    base = None if url is None else (url if url.endswith("/") else url + "/")
    for relative in hosted_files(root):
        name = relative.as_posix()
        data = (root / relative).read_bytes()
        checksum = root / relative.with_name(relative.name + ".sha1")
        if not checksum.is_file():
            problems.append(f"{name}: has no .sha1 beside it")
        elif published_sha1(checksum.read_text(encoding="ascii", errors="replace")) != sha1(data):
            problems.append(f"{name}: doesn't match its .sha1")
        if base is not None:
            try:
                if sha1(fetch(urllib.parse.urljoin(base, name))) != sha1(data):
                    problems.append(f"{name}: {base} serves different content")
            except Problem as problem:
                problems.append(f"{name}: {problem}")
        if relative.suffix == ".jar":
            hosted.add(str(Coordinates.from_jar_path(relative.parts)))
    # Only the artifact table: Not Hosted's rows also start with a coordinate in backticks
    readme = (root / "README.md").read_text(encoding="utf-8")
    table = readme[readme.find(TABLE_START):readme.find(TABLE_END)]
    listed = set(re.findall(r"^\| `([^`]+)` \|", table, re.M))
    problems += [f"{c}: hosted but not in README.md's table" for c in sorted(hosted - listed)]
    problems += [f"{c}: in README.md's table but not hosted" for c in sorted(listed - hosted)]
    return problems
```

Then, in `main`, add the arguments below `--source`:

```python
    parser.add_argument("--scan", type=pathlib.Path, metavar="LOCAL_REPO",
                        help="list the artifacts LOCAL_REPO records as downloaded from --repository-id")
    parser.add_argument("--repository-id", default="logicaldoc", help="with --scan (default: logicaldoc)")
    parser.add_argument("--verify", nargs="?", const="", metavar="URL",
                        help="check the hosted files, and with URL what URL serves")
```

and, right after `args = parser.parse_args(argv)`, before the import checks:

```python
    if args.scan:
        for coordinates in scan(args.scan, args.repository_id):
            print(coordinates)
        return 0
    if args.verify is not None:
        problems = verify(root, args.verify or None)
        for problem in problems:
            print(problem)
        return 1 if problems else 0
```

Update the module's usage comment if anything above differs from it.

- [ ] **Step 4: Run the tests to see them pass**

Run: `cd ~/Projects/healdan-maven && python3 -m unittest discover -s tests -v`
Expected: every test passes. `test_clean_repository_passes` also shows `tests/fixture.jar` is ignored.

- [ ] **Step 5: Write `.github/workflows/check.yml`**

```yaml
name: Check

# Every hosted file matches its .sha1 and README.md's table, and import-artifact.py's tests pass.

on:
  push:
    branches: [ main ]
  pull_request:

permissions:
  contents: read

jobs:
  check:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4

      - name: Test import-artifact.py
        run: python3 -m unittest discover -s tests -v

      - name: Verify the hosted files
        run: python3 import-artifact.py --verify
```

- [ ] **Step 6: Commit**

```bash
cd ~/Projects/healdan-maven
git add import-artifact.py tests/test_import_artifact.py .github/workflows/check.yml
git commit -m "Scan a local Maven repository, verify the hosted files, and check both in CI

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Drop JAI, betelio and the Webservice Client

Spec §3.1: JAI's and betelio's licences don't allow hosting them and nothing references them, so the build drops
them. ksoap2's only user, `logicaldoc-webserviceclient`, leaves the reactor. This goes before the inventory
(Task 4), so the inventory doesn't list them.

**Files:**
- Modify: `logicaldoc-core/pom.xml` (the `betelio` dependency around line 69, and `jai-core` and `jai-codec` just before `httpclient5`'s, around lines 310–317)
- Modify: `logicaldoc-webapp/pom.xml` (the `betelio` dependency around line 73)
- Modify: `pom.xml` (the root POM's `<module>logicaldoc-webserviceclient</module>`, line 193)

**Interfaces:**
- Produces: a reactor without `logicaldoc-webserviceclient`, whose modules don't depend on `javax.media:jai-core`, `com.sun.media:jai-codec`, `betelio:betelio` or ksoap2. Tasks 4 and 6 rely on no build fetching them.

- [ ] **Step 1: Confirm nothing references JAI or betelio, and nothing depends on the webservice client**

Write this scanner to `~/.cache/healdan-m2/scan_refs.py` (`mkdir -p ~/.cache/healdan-m2` first):

```python
"""Lists jars under a Maven repository whose class files mention any of the given names."""
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1])
needles = [name.encode() for name in sys.argv[2:]]
for jar in sorted(root.rglob("*.jar")):
    try:
        with zipfile.ZipFile(jar) as archive:
            hits = [info.filename for info in archive.infolist()
                    if info.filename.endswith(".class") and any(n in archive.read(info) for n in needles)]
    except (zipfile.BadZipFile, OSError):
        continue
    if hits:
        print(jar.relative_to(root), hits[:3])
```

Run: `python3 -I ~/.cache/healdan-m2/scan_refs.py ~/.m2/repository javax/media/jai com/sun/media/jai javax.media.jai com.sun.media.jai be/telio be.telio | grep -v -e '^javax/media/jai-core/' -e '^com/sun/media/jai-codec/' -e '^betelio/'`
Expected: no output. (The local repository must hold a full build's dependencies. It does if `mvn install` has run here.)

Run: `git grep -n -i -e 'javax.media.jai' -e 'com.sun.media.jai' -e 'be.telio' -- ':!docs'`
Expected: no output.

Run: `git grep -n 'logicaldoc-webserviceclient' -- '*pom.xml' ':!logicaldoc-webserviceclient'`
Expected: only `pom.xml:193:        <module>logicaldoc-webserviceclient</module>`.

If any of these prints something else, stop: the owner decides (spec §3.1).

- [ ] **Step 2: Remove the dependencies and the module**

In `logicaldoc-core/pom.xml`, delete these two blocks (tabs, as the file has them):

```xml
		<dependency>
			<groupId>betelio</groupId>
			<artifactId>betelio</artifactId>
			<version>1.0</version>
			<scope>provided</scope>
		</dependency>
```

```xml
		<dependency>
			<groupId>javax.media</groupId>
			<artifactId>jai-core</artifactId>
		</dependency>
		<dependency>
			<groupId>com.sun.media</groupId>
			<artifactId>jai-codec</artifactId>
		</dependency>
```

In `logicaldoc-webapp/pom.xml`, delete:

```xml
		<dependency>
			<groupId>betelio</groupId>
			<artifactId>betelio</artifactId>
			<version>1.0</version>
		</dependency>
```

In the root `pom.xml`, delete the line:

```xml
        <module>logicaldoc-webserviceclient</module>
```

Leave the module's folder, `logicaldoc-parent/pom.xml`'s `dependencyManagement` entries and core's assembly includes alone (plan call 3).

- [ ] **Step 3: Check the reactor, core's plugin zip and the dependency trees**

Run: `mvn -B -ntp validate`
Expected: `BUILD SUCCESS`, with no `logicaldoc-webserviceclient` in the Reactor Summary.

Run: `mvn -B -ntp -pl logicaldoc-core -DskipTests install`
Expected: `BUILD SUCCESS`. A `[WARNING]` that the `javax.media:*` and `com.sun.media:*` includes were never triggered is expected.

Run: `unzip -l logicaldoc-core/target/logicaldoc-core-*-plugin.zip | grep -i -e jai -e betelio`
Expected: no output.

Run: `mvn -B -ntp -pl logicaldoc-core,logicaldoc-webapp dependency:tree | grep -i -e jai -e betelio -e ksoap`
Expected: no output. (The webapp's tree resolves the other modules from the local repository, where a full `mvn install` has put them.)

- [ ] **Step 4: Run core's imaging tests**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest='ImageUtilTest,PDFImageExtractorTest'`
Expected: `BUILD SUCCESS`, and both classes' tests pass.

- [ ] **Step 5: Run Healdan's tests against the new core**

Healdan's modules test against core's installed jar (CLAUDE.md, Build and Test), which Step 3 installed. They need `~/logicaldoc-dev.properties` to exist; an empty file works.

Run: `mvn -B -ntp -pl healdan-core test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add logicaldoc-core/pom.xml logicaldoc-webapp/pom.xml pom.xml
git commit -m "Drop JAI, betelio and the webservice client from the build

Their licences don't allow Healdan to host them. JAI is under Sun's
Binary Code License, and betelio has no licence; nothing in the build
references either. ksoap2's only user was logicaldoc-webserviceclient,
which no module depends on, so it leaves the reactor as
logicaldoc-webservicesamples has. See the Maven repository spec's §3.1.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Inventory: What a Clean Build Takes From LogicalDOC

**Files:** none changed. The output is the list Task 5 imports.

**Interfaces:**
- Consumes: Task 3's `logicaldoc-core`; `python3 ~/Projects/healdan-maven/import-artifact.py --scan` from Task 2.
- Produces: the inventory list in `~/.cache/healdan-m2/inventory.txt`, and the downloaded copies in `~/.cache/healdan-m2/inventory/` that Task 5 compares its imports with.

- [ ] **Step 1: Run a full build on an empty local repository, with today's settings**

This is the build the Docker image runs, GWT compile and WAR included, against LogicalDOC's repository as `docker/maven-settings.xml` stands before Task 6. It takes a while: run it in the background.

```bash
rm -rf ~/.cache/healdan-m2/inventory && mkdir -p ~/.cache/healdan-m2/inventory
mvn -B -ntp -s docker/maven-settings.xml -Dmaven.repo.local="$HOME/.cache/healdan-m2/inventory" -DskipTests install \
  > ~/.cache/healdan-m2/inventory.log 2>&1; echo "exit $?"
```

Expected: `exit 0`, and `BUILD SUCCESS` at the end of the log. If SourceForge answers 403 (`status code: 403` in the log), run the same `mvn` command again: what's already downloaded stays.

- [ ] **Step 2: List what it took from LogicalDOC's repository**

```bash
python3 ~/Projects/healdan-maven/import-artifact.py --scan ~/.cache/healdan-m2/inventory --repository-id logicaldoc \
  | tee ~/.cache/healdan-m2/inventory.txt
```

Expected: exactly these 15 lines:

```
com.allen_sauer.gwt.dnd:gwt-dnd:3.0.1
com.google.gwt:gwt-incubator:2.1.0
com.orange.links:gwt-links:1.4.2
com.smartgwt:smartgwt-skins:14.1p
com.smartgwt:smartgwt:14.1p
com.timgroup:jgravatar:1.2.ld
commons-fileupload:commons-fileupload:1.6.0-jakarta
gdurelle.tagcloud:tagcloud:0.4
gwtchismes:gwtchismes:0.9.8
net.sf.jpf:jpf-boot:1.5.1
net.sf.jpf:jpf:1.5.1
org.apache.jackrabbit:jackrabbit-jcr-commons:2.22.0-jakarta
org.apache.jackrabbit:jackrabbit-jcr-server:2.22.0-jakarta
org.apache.jackrabbit:jackrabbit-webdav:2.22.0-jakarta
org.timepedia.exporter:gwtexporter:2.0.10
```

If the list differs, stop. An extra artifact needs its licence settled (spec §3.1) and a row in Task 5 before it's imported. A missing one means the build no longer needs it: leave it out.

- [ ] **Step 3: Check no POM-only artifact came from there**

`--scan` lists jars, so it would miss a parent or BOM POM that came from LogicalDOC's repository on its own.

Run: `grep -rlE --include=_remote.repositories '\.pom>logicaldoc(-[0-9a-f]{40})?=' ~/.cache/healdan-m2/inventory | wc -l`
Expected: `15`, one folder per artifact above. More means a POM-only artifact, which `import-artifact.py` can't import: stop and raise it with the owner.

---

### Task 5: Import, Publish and Serve at `maven.healdan.com`

**Files:**
- Create in `~/Projects/healdan-maven`: the artifacts, their README rows, `LICENSES/`, `CNAME`, `.nojekyll`.

**Interfaces:**
- Consumes: `import-artifact.py` (Tasks 1–2); `~/.cache/healdan-m2/inventory/` and `inventory.txt` (Task 4).
- Produces: `https://maven.healdan.com/` serving the 15 artifacts. Task 6 builds against it.

- [ ] **Step 1: Import the artifacts**

The projects and licences come from the licence audit (spec §3.1). Each command fetches from
`logicaldoc.sourceforge.net`. If one fails with a 403, run it again: nothing was written.

```bash
cd ~/Projects/healdan-maven
python3 import-artifact.py com.allen_sauer.gwt.dnd:gwt-dnd:3.0.1 \
  --project '[gwt-dnd](https://github.com/fredsa/gwt-dnd)' --licence Apache-2.0
python3 import-artifact.py com.google.gwt:gwt-incubator:2.1.0 \
  --project '[GWT Incubator](https://code.google.com/archive/p/google-web-toolkit-incubator/)' \
  --licence 'Apache-2.0, bundling JavaSWF (BSD-3-Clause) and W3C'"'"'s CSS parser (W3C notice)'
python3 import-artifact.py com.orange.links:gwt-links:1.4.2 \
  --project '[gwt-links](https://code.google.com/archive/p/gwt-links/)' --licence Apache-2.0
python3 import-artifact.py com.smartgwt:smartgwt:14.1p \
  --project '[Smart GWT LGPL Edition](https://smartclient.com/product/)' --licence LGPL-3.0-only
python3 import-artifact.py com.smartgwt:smartgwt-skins:14.1p \
  --project '[Smart GWT LGPL Edition](https://smartclient.com/product/)' --licence LGPL-3.0-only
python3 import-artifact.py com.timgroup:jgravatar:1.2.ld \
  --project '[jgravatar](https://github.com/tim-group/jgravatar)' \
  --licence 'BSD-3-Clause OR LGPL-3.0, bundling Guava (Apache-2.0)'
python3 import-artifact.py commons-fileupload:commons-fileupload:1.6.0-jakarta \
  --project '[Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/), LogicalDOC'"'"'s Jakarta build' \
  --licence Apache-2.0
python3 import-artifact.py gdurelle.tagcloud:tagcloud:0.4 \
  --project '[gwt-tagcloud](https://github.com/gdurelle/gwt-tagcloud)' --licence Apache-2.0
python3 import-artifact.py gwtchismes:gwtchismes:0.9.8 \
  --project '[GWTChismes](https://code.google.com/archive/p/gwtchismes/)' --licence Apache-2.0
python3 import-artifact.py net.sf.jpf:jpf:1.5.1 \
  --project '[Java Plug-in Framework](https://jpf.sourceforge.net/)' --licence LGPL-2.1
python3 import-artifact.py net.sf.jpf:jpf-boot:1.5.1 \
  --project '[Java Plug-in Framework](https://jpf.sourceforge.net/)' --licence LGPL-2.1
for a in jackrabbit-jcr-commons jackrabbit-jcr-server jackrabbit-webdav; do
  python3 import-artifact.py "org.apache.jackrabbit:$a:2.22.0-jakarta" \
    --project '[Apache Jackrabbit](https://jackrabbit.apache.org/), LogicalDOC'"'"'s Jakarta build' --licence Apache-2.0
done
python3 import-artifact.py org.timepedia.exporter:gwtexporter:2.0.10 \
  --project '[gwt-exporter](https://github.com/manolo/gwt-exporter)' --licence Apache-2.0
```

Expected: 15 `Imported … from logicaldoc.sourceforge.net, <today>` lines, and README.md's table has 15 rows.

- [ ] **Step 2: Add the licence texts and NOTICE files**

```bash
cd ~/Projects/healdan-maven
mkdir -p LICENSES
inv=~/.cache/healdan-m2/inventory/org/apache/jackrabbit/jackrabbit-jcr-commons/2.22.0-jakarta/jackrabbit-jcr-commons-2.22.0-jakarta.jar
unzip -p "$inv" META-INF/LICENSE > LICENSES/Apache-2.0.txt
unzip -p "$inv" META-INF/NOTICE > LICENSES/NOTICE-apache-jackrabbit.txt
curl -fsSL -o LICENSES/LGPL-2.1.txt https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
curl -fsSL -o LICENSES/LGPL-3.0.txt https://www.gnu.org/licenses/lgpl-3.0.txt
curl -fsSL -o LICENSES/GPL-3.0.txt https://www.gnu.org/licenses/gpl-3.0.txt
tmp=$(mktemp -d)
curl -fsSL -o "$tmp/fileupload.jar" \
  https://repo1.maven.org/maven2/commons-fileupload/commons-fileupload/1.6.0/commons-fileupload-1.6.0.jar
unzip -p "$tmp/fileupload.jar" 'META-INF/NOTICE*' > LICENSES/NOTICE-apache-commons-fileupload.txt
rm -rf "$tmp"
```

Expected: each file is non-empty (`wc -l LICENSES/*`). `Apache-2.0.txt` starts with "Apache License", and the two NOTICE files name The Apache Software Foundation.

Then write `LICENSES/BSD-3-Clause-jgravatar.txt` by hand: the BSD licence text, with its copyright line, from the "License" section of jgravatar's README at https://github.com/tim-group/jgravatar. Copy it exactly.

- [ ] **Step 3: Check the imports against what Maven downloaded**

Each imported file must be byte for byte what the inventory build downloaded.

```bash
cd ~/Projects/healdan-maven
python3 import-artifact.py --verify && echo verified
find . -path ./.git -prune -o \( -name '*.jar' -o -name '*.pom' \) -print | while read -r f; do
  cmp -s "$f" "$HOME/.cache/healdan-m2/inventory/${f#./}" || echo "differs: $f"
done
```

Expected: `verified`, and no `differs:` line.

- [ ] **Step 4: Add `CNAME` and `.nojekyll`, and commit**

```bash
cd ~/Projects/healdan-maven
printf 'maven.healdan.com\n' > CNAME
: > .nojekyll
git add -A
git commit -m "Host the 15 artifacts Healdan's build needs from LogicalDOC's repository

Imported from logicaldoc.sourceforge.net with import-artifact.py, each
checked against its published .sha1 and against the copy a clean Healdan
build downloaded. LICENSES/ has the licence texts and notices the jars
lack.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Create the GitHub repository and push (ask the owner first)**

Ask the owner in chat: "Create the public repository `Healdan-Project/maven` and push its `main` branch?" Only on a yes:

```bash
cd ~/Projects/healdan-maven
gh repo create Healdan-Project/maven --public \
  --description "Healdan's copies of the Maven artifacts only LogicalDOC's repository has, served at maven.healdan.com" \
  --source . --remote origin --push
```

Expected: the repository exists, and its `Check` workflow run on `main` passes: `gh run list --repo Healdan-Project/maven --limit 1`.

- [ ] **Step 6: Turn on Pages with the custom domain (ask the owner first)**

Ask the owner in chat whether to turn on Pages from `main` with the custom domain `maven.healdan.com`, or whether they'll do it in the repository's Settings → Pages. On a yes:

```bash
gh api -X POST repos/Healdan-Project/maven/pages -f 'source[branch]=main' -f 'source[path]=/'
gh api -X PUT repos/Healdan-Project/maven/pages -f cname=maven.healdan.com
```

- [ ] **Step 7: The owner adds the DNS record**

Ask the owner to add a DNS record `maven` CNAME `healdan-project.github.io` for `healdan.com`, and, if `healdan.com` isn't already a verified domain for the `Healdan-Project` organisation, to verify it (organisation Settings → Pages). Wait for their reply.

Then poll until Pages has a certificate:

Run: `gh api repos/Healdan-Project/maven/pages --jq '.https_certificate.state'`
Expected, eventually: `approved`. Then, with the owner's OK, `gh api -X PUT repos/Healdan-Project/maven/pages -F https_enforced=true`.

- [ ] **Step 8: Verify what Pages serves**

Run: `cd ~/Projects/healdan-maven && python3 import-artifact.py --verify https://maven.healdan.com/ && echo published`
Expected: `published`.

---

### Task 6: Mirror LogicalDOC's Repository to Healdan's

**Files:**
- Modify: `docker/maven-settings.xml` (the whole file)

**Interfaces:**
- Consumes: `https://maven.healdan.com/` (Task 5); `import-artifact.py --scan` (Task 2).
- Produces: settings with a mirror `healdan` of `logicaldoc` to `https://maven.healdan.com/`. Task 7's check requires it.

- [ ] **Step 1: Rewrite `docker/maven-settings.xml`**

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <!-- CI, the Docker build and local builds copy this file to ~/.m2/settings.xml. Some dependencies aren't on Maven
       Central: Healdan hosts the ones only LogicalDOC's repository has, and Terracotta's repository has the rest.
       The design is docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md. -->
  <mirrors>
    <!-- Upstream's POMs declare LogicalDOC's repository (id logicaldoc, on logicaldoc.sourceforge.net). A mirror
         matches by id, so every request for it, whatever URL a POM gives, goes to Healdan's copies instead.
         .github/scripts/check-maven-repositories.py fails the build without this mirror. -->
    <mirror>
      <id>healdan</id>
      <name>Healdan's copies of artifacts only LogicalDOC's repository has</name>
      <mirrorOf>logicaldoc</mirrorOf>
      <url>https://maven.healdan.com/</url>
    </mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>with-extra-repos</id>
      <repositories>
        <repository>
          <id>central</id>
          <url>https://repo1.maven.org/maven2</url>
        </repository>
        <!-- Terracotta repository (org.terracotta.internal:statistics, etc.) -->
        <repository>
          <id>terracotta</id>
          <url>https://repo.terracotta.org/maven2</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>with-extra-repos</activeProfile>
  </activeProfiles>
</settings>
```

- [ ] **Step 2: Run a full build on an empty local repository, with the new settings**

In the background, as in Task 4:

```bash
rm -rf ~/.cache/healdan-m2/verify && mkdir -p ~/.cache/healdan-m2/verify
mvn -B -ntp -s docker/maven-settings.xml -Dmaven.repo.local="$HOME/.cache/healdan-m2/verify" -DskipTests install \
  > ~/.cache/healdan-m2/verify.log 2>&1; echo "exit $?"
```

Expected: `exit 0`, and `BUILD SUCCESS` at the end of the log. If it fails with `Could not find artifact … in healdan (https://maven.healdan.com/)`, that artifact is missing from Task 5: stop and reconcile.

- [ ] **Step 3: Check nothing came from LogicalDOC's repository**

Run: `grep -rlE --include=_remote.repositories '>logicaldoc(-[0-9a-f]{40})?=' ~/.cache/healdan-m2/verify`
Expected: no output. (The log can't show this: `-ntp` leaves downloads out of it.)

Run: `python3 ~/Projects/healdan-maven/import-artifact.py --scan ~/.cache/healdan-m2/verify --repository-id healdan | diff - ~/.cache/healdan-m2/inventory.txt && echo same`
Expected: `same`.

- [ ] **Step 4: Commit**

```bash
git add docker/maven-settings.xml
git commit -m "Mirror LogicalDOC's Maven repository to maven.healdan.com

Builds now get the artifacts only logicaldoc.sourceforge.net has from
Healdan's copies. A clean full build on an empty local repository took
nothing from LogicalDOC's repository.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The Check That Keeps LogicalDOC's Repositories Out

**Files:**
- Create: `.github/scripts/check-maven-repositories.py`
- Create: `.github/scripts/tests/test_check_maven_repositories.py`
- Modify: `.github/workflows/build-logicaldoc.yml` (the `checks` job, after the "Check for links to LogicalDOC's docs" step, around line 50)

**Interfaces:**
- Consumes: `.github/scripts/tests/_scripts.py` (`load(name)`, `REPO`); Task 6's mirror.
- Produces: `find_problems(root) -> list[str]`, `main(argv) -> int`, and the constants `FIX` (a format string with `{host}` and `{id}`), `NO_MIRROR`, `SETTINGS` and `HEALDAN`.

- [ ] **Step 1: Write the failing tests**

`.github/scripts/tests/test_check_maven_repositories.py`:

```python
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
        subprocess.run(["git", "rm", "-q", "docker/maven-settings.xml"], cwd=self.repo, check=True)
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
```

The line numbers: `POM`'s body starts on line 4, so `repositories(...)`'s first `<repository>` is line 5 and its second is line 9. In `test_settings_repositories_checked`, `MIRROR` takes lines 2–8, `<repositories>` line 9 and `<repository>` line 10.

- [ ] **Step 2: Run the tests to see them fail**

Run: `python3 -m unittest discover -s .github/scripts/tests -p 'test_check_maven_repositories.py' -v`
Expected: an error loading the module: `FileNotFoundError` for `check-maven-repositories.py`.

- [ ] **Step 3: Write `.github/scripts/check-maven-repositories.py`**

```python
#!/usr/bin/env python3
#
# Checks that no build can get artifacts from LogicalDOC's Maven repositories. Builds get the artifacts only
# LogicalDOC's repository has from Healdan's copies at https://maven.healdan.com/, through a mirror of the logicaldoc
# repository in docker/maven-settings.xml, and an upstream sync can bring another LogicalDOC repository in, as it can
# Font Awesome Pro icons. Run by .github/workflows/build-logicaldoc.yml. The design is
# docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md.
#
# Fails when:
#   - docker/maven-settings.xml has no mirror of logicaldoc to https://maven.healdan.com/;
#   - a <repository> or <pluginRepository> in a tracked pom.xml, or in the settings file, has a URL on a host whose
#     name contains "logicaldoc", under an id that mirror doesn't cover.
# Repositories inside <distributionManagement>, which only a deploy reads, are ignored. An artifact only LogicalDOC's
# repository has isn't this check's job: the build fails on it with "not found in healdan".
#
# Usage: check-maven-repositories.py [repo root]
# Exit status: 0 when no build can reach LogicalDOC's repositories, 1 otherwise.

import pathlib
import subprocess
import sys
import urllib.parse
import xml.parsers.expat

SETTINGS = "docker/maven-settings.xml"
HEALDAN = "https://maven.healdan.com/"
REPOSITORY_TAGS = ("repository", "pluginRepository")

NO_MIRROR = (f"needs a mirror of logicaldoc to {HEALDAN}, so builds get the artifacts only LogicalDOC's repository "
             "has from Healdan's copies.")
FIX = ("is on LogicalDOC's host {host}. Builds get those artifacts from " + HEALDAN + ": add {id} to the healdan "
       "mirror's mirrorOf in " + SETTINGS + ", or drop the repository.")


def tracked_files(root):
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(name.decode("utf-8", errors="replace") for name in listing.split(b"\0") if name)


def elements(path, tags, fields):
    """A dict per element named in tags, outside <distributionManagement>: its line, and the text of each direct
    child named in fields (None when it has none). Raises xml.parsers.expat.ExpatError on a malformed file."""
    parser = xml.parsers.expat.ParserCreate()
    stack, found, text = [], [], []
    current = None

    def start(name, _attributes):
        nonlocal current
        if name in tags and "distributionManagement" not in stack:
            current = {"line": parser.CurrentLineNumber, **dict.fromkeys(fields)}
        stack.append(name)
        text.clear()

    def end(name):
        nonlocal current
        stack.pop()
        if current is not None:
            if name in fields and stack and stack[-1] in tags:
                current[name] = "".join(text).strip()
            elif name in tags:
                found.append(current)
                current = None
        text.clear()

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = text.append
    parser.Parse(path.read_bytes(), True)
    return found


def mirrored_ids(settings):
    """The repository ids the settings file mirrors to Healdan's repository."""
    ids = set()
    for mirror in elements(settings, ("mirror",), ("mirrorOf", "url")):
        if (mirror["url"] or "").rstrip("/") == HEALDAN.rstrip("/"):
            ids.update(part.strip() for part in (mirror["mirrorOf"] or "").split(","))
    return ids


def logicaldoc_host(url):
    """The URL's host when it's one of LogicalDOC's, else None."""
    try:
        host = urllib.parse.urlsplit(url or "").hostname or ""
    except ValueError:
        return None
    return host if "logicaldoc" in host else None


def find_problems(root):
    root = pathlib.Path(root)
    tracked = tracked_files(root)
    problems = []
    mirrored = set()
    if SETTINGS in tracked and (root / SETTINGS).is_file():
        try:
            mirrored = mirrored_ids(root / SETTINGS)
        except xml.parsers.expat.ExpatError as error:
            return [f"{SETTINGS}: can't be parsed: {error}"]
    if "logicaldoc" not in mirrored:
        problems.append(f"{SETTINGS}: {NO_MIRROR}")
    names = [name for name in tracked if name == "pom.xml" or name.endswith("/pom.xml")]
    if SETTINGS in tracked:
        names.append(SETTINGS)
    for name in names:
        path = root / name
        if not path.is_file():
            continue
        try:
            repositories = elements(path, REPOSITORY_TAGS, ("id", "url"))
        except xml.parsers.expat.ExpatError as error:
            problems.append(f"{name}: can't be parsed: {error}")
            continue
        for repository in repositories:
            host = logicaldoc_host(repository["url"])
            if host and repository["id"] not in mirrored:
                problems.append(f"{name}:{repository['line']}: repository {repository['id']} "
                                + FIX.format(host=host, id=repository["id"]))
    return problems


def main(argv):
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2]
    problems = find_problems(root)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `python3 -m unittest discover -s .github/scripts/tests -p 'test_check_maven_repositories.py' -v`
Expected: every test passes. `test_current_repo_passes` passes only with Task 6's mirror. (All 18 tracked POMs parse with `expat` as of `8cfa8be53`.)

Run: `python3 .github/scripts/check-maven-repositories.py; echo "exit $?"`
Expected: `exit 0`, no other output.

- [ ] **Step 5: Run it in CI**

In `.github/workflows/build-logicaldoc.yml`, after the "Check for links to LogicalDOC's docs" step, add:

```yaml
      # Builds get the artifacts only LogicalDOC's Maven repository has from Healdan's copies; a sync can bring
      # another of LogicalDOC's repositories in
      - name: Check Maven repositories
        run: python3 .github/scripts/check-maven-repositories.py
```

- [ ] **Step 6: Run every script test**

Run: `python3 -m unittest discover -s .github/scripts/tests -v`
Expected: all pass. (The Docs site check runs this on every pull request.)

- [ ] **Step 7: Commit**

```bash
git add .github/scripts/check-maven-repositories.py .github/scripts/tests/test_check_maven_repositories.py \
  .github/workflows/build-logicaldoc.yml
git commit -m "Fail build when a POM points a repository at LogicalDOC's hosts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Docs

**Files:**
- Modify: `CLAUDE.md` (lines 16, 19, 30, 60 and 68 as of `8cfa8be53`)
- Modify: `site/content/developer/building-from-source.md:13-17` and `:95`
- Modify: `site/content/developer/continuous-integration.md:12` and its "Things That Catch Upstream Syncs" list
- Modify: `site/content/developer/upstream-syncs.md:17-18`
- Modify: `docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md` (Status)

**Interfaces:**
- Consumes: the names from Tasks 1–7: `import-artifact.py`, `--verify`, `check-maven-repositories.py`, the mirror `healdan`.

- [ ] **Step 1: `CLAUDE.md`**

Replace, in the "Upstream sometimes ships POMs" bullet:

> If CI fails within a minute, check POM errors, the icon check and the doc-links check (next bullets) first.

with:

> If CI fails within a minute, check POM errors, the icon check, the doc-links check and the Maven repository check (next bullets) first.

Insert this bullet after the doc-links bullet (the one starting "Outside the docs site's interim pages") and before the `README.md` bullet:

```markdown
- Builds get the artifacts only LogicalDOC's Maven repository has from Healdan's copies at `https://maven.healdan.com/` (repository `Healdan-Project/maven`), through a mirror of the `logicaldoc` repository in `docker/maven-settings.xml`. JAI, betelio and ksoap2 aren't among them: their licences don't allow hosting them, so the build drops JAI and betelio, and leaves `logicaldoc-webserviceclient`, ksoap2's only user, out. `.github/scripts/check-maven-repositories.py` runs next to the doc-links check and fails `build` when a POM or the settings file points a repository at a LogicalDOC host the mirror doesn't cover.
  - When a sync bumps one of those artifacts or brings a new one, the build fails with `Could not find artifact … in healdan`. In the sync PR, as a merge fix, check its licence allows hosting it, run `python3 import-artifact.py <group>:<artifact>:<version> --project … --licence …` in a checkout of `Healdan-Project/maven`, push, and re-run CI once `python3 import-artifact.py --verify https://maven.healdan.com/` passes. One whose licence doesn't allow hosting is dropped instead, as JAI was. When the old GWT UI retires, its GWT libraries leave the build too (the spec's §3.1).
```

At the end of "Build and test", replace:

> - `logicaldoc-webservicesamples` isn't in the reactor.

with:

> - `logicaldoc-webservicesamples` and `logicaldoc-webserviceclient` aren't in the reactor.

In "Build and test", replace:

> They come from `https://logicaldoc.sourceforge.net/maven/` and `https://repo.terracotta.org/maven2`, which `docker/maven-settings.xml` lists.

with:

> They come from Healdan's copies at `https://maven.healdan.com/` and from `https://repo.terracotta.org/maven2`, which `docker/maven-settings.xml` lists.

In the CI cache bullet, delete the closing parenthetical ` (`logicaldoc.sourceforge.net` sometimes refuses requests)`, leaving the sentence ending "downloads only what it adds." If the parallel cache session has already rewritten that bullet, drop only that parenthetical from whatever it says now.

- [ ] **Step 2: `site/content/developer/building-from-source.md`**

Replace the "Two extra Maven repositories" bullet with:

```markdown
- **Two extra Maven repositories.** Some dependencies aren't on Maven Central. They come from Healdan's copies at
  `https://maven.healdan.com/`, of the artifacts only LogicalDOC's repository has, and from
  `https://repo.terracotta.org/maven2`. `docker/maven-settings.xml` lists them. CI and the Docker build copy it to
  `~/.m2/settings.xml`; copy it there for local builds.
```

and replace:

> - `logicaldoc-webservicesamples` isn't part of the build.

with:

> - `logicaldoc-webservicesamples` and `logicaldoc-webserviceclient` aren't part of the build.

- [ ] **Step 3: `site/content/developer/continuous-integration.md`**

In the checks table's `build` row, replace:

> Checks the GUI's Font Awesome icons (`check-fontawesome-icons.py`) and for links to LogicalDOC's docs (`check-logicaldoc-doc-links.py`),

with:

> Checks the GUI's Font Awesome icons (`check-fontawesome-icons.py`), for links to LogicalDOC's docs (`check-logicaldoc-doc-links.py`) and for Maven repositories on LogicalDOC's hosts (`check-maven-repositories.py`),

In "Things That Catch Upstream Syncs", insert after the "Links to LogicalDOC's docs" bullet:

```markdown
- **LogicalDOC's Maven repository.** Builds get the artifacts only LogicalDOC's repository has from Healdan's copies
  at `https://maven.healdan.com/` (repository `Healdan-Project/maven`), through a mirror in
  `docker/maven-settings.xml`. `.github/scripts/check-maven-repositories.py` runs next to the doc-links check and
  fails `build` when a POM or the settings file points a repository at a LogicalDOC host the mirror doesn't cover.
  When a sync bumps a hosted artifact or brings a new one, the build fails with `Could not find artifact … in
  healdan`. In the sync PR, check its licence allows hosting it, import it with `python3 import-artifact.py` in a
  checkout of `Healdan-Project/maven`, push, and re-run CI once
  `python3 import-artifact.py --verify https://maven.healdan.com/` passes. One whose licence doesn't allow hosting
  it is dropped from the build instead, as JAI was.
```

and in the "Broken POMs" bullet replace "check the POMs, the icon check and the doc-links check first" with "check the POMs, the icon check, the doc-links check and the Maven repository check first".

- [ ] **Step 4: `site/content/developer/upstream-syncs.md`**

Replace:

> **Keep sync pull requests free of feature work.** Fixes the merge needs, such as swapping a Font Awesome Pro icon
>   for a Free one, or a link to LogicalDOC's docs for one to Healdan's, belong in the sync itself.

with:

```markdown
- **Keep sync pull requests free of feature work.** Fixes the merge needs, such as swapping a Font Awesome Pro icon
  for a Free one, a link to LogicalDOC's docs for one to Healdan's, or importing an artifact into Healdan's Maven
  repository, belong in the sync itself.
```

- [ ] **Step 5: The spec's status**

In the spec, replace the Status line with:

```markdown
- **Status:** approved by the owner on 2026-10-29, and amended that day for licences (§3.1). Built from
  [`docs/superpowers/plans/2026-10-29-healdan-maven-repository.md`](../plans/2026-10-29-healdan-maven-repository.md).
  `https://maven.healdan.com/` serves the 15 hosted artifacts from `Healdan-Project/maven`.
```

- [ ] **Step 6: Check the docs build**

Run: `python3 .github/scripts/check-logicaldoc-doc-links.py && hugo --source site --gc --minify --panicOnWarning --quiet && python3 .github/scripts/check-site-sources.py site && python3 .github/scripts/check-site-links.py site/public && echo docs-ok`
Expected: `docs-ok`.

Run: `python3 -m unittest discover -s .github/scripts/tests -v`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md site/content/developer/building-from-source.md site/content/developer/continuous-integration.md \
  site/content/developer/upstream-syncs.md docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md
git commit -m "Document Healdan's Maven repository and the sync step for it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: The Pull Request

**Files:** none.

- [ ] **Step 1: Bring the branch up to date with `master`**

Call the `sync_with_base_branch` tool (ccd_host). Resolve any conflict in `.github/workflows/build-logicaldoc.yml` or `CLAUDE.md` with the parallel cache change by keeping both changes, then re-run Task 7 Step 6 and Task 8 Step 6.

- [ ] **Step 2: Push and open the PR (ask the owner first)**

Ask the owner in chat: "Push `claude/ci-logicaldoc-maven-deps-272484` and open the PR?" On a yes:

```bash
git push -u origin HEAD
gh pr create --repo Healdan-Project/healdan --base master --title "Get LogicalDOC-only Maven artifacts from maven.healdan.com" --body-file - <<'EOF'
Builds now get the artifacts only LogicalDOC's Maven repository has from Healdan's copies at
https://maven.healdan.com/ ([Healdan-Project/maven](https://github.com/Healdan-Project/maven)), so no build
contacts logicaldoc.sourceforge.net. Closes #107.

- `docker/maven-settings.xml` mirrors the `logicaldoc` repository to it, so upstream's POMs stay as they are.
- Three jars whose licences don't allow hosting them leave the build, the only upstream edits:
  - `logicaldoc-core` drops JAI (Sun's Binary Code License, distribution `manual`);
  - `logicaldoc-core` and `logicaldoc-webapp` drop betelio, which has no licence;
  - the root POM drops the `logicaldoc-webserviceclient` module, ksoap2's only user, which no module depends on.

  Nothing in the build references JAI or betelio.
- `.github/scripts/check-maven-repositories.py`, in the `checks` job, fails `build` when a POM or the settings file
  points a repository at a LogicalDOC host the mirror doesn't cover.
- CLAUDE.md and the Developer Guide say how to import an artifact when a sync needs one.

A full build (GWT compile and WAR) on an empty local repository with the new settings took nothing from
LogicalDOC's repository, and the 15 artifacts from Healdan's.

Spec: `docs/superpowers/specs/2026-10-29-healdan-maven-repository-design.md`. Plan:
`docs/superpowers/plans/2026-10-29-healdan-maven-repository.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

- [ ] **Step 3: Bind the PR and watch CI**

Call the ccd_pr `get_status` tool, and `bind_pr` if it doesn't report the PR. Read its CI: `build`, the Docker image builds and smoke tests, and `Docs site` must pass. Offer the owner Auto-fix. Don't poll CI yourself.

- [ ] **Step 4: Tick the issue's boxes**

When the PR merges, its "Closes #107" closes the issue. Before that, tick the boxes the hosted repository's tasks finished, by editing the issue body with `gh issue edit 107 --repo Healdan-Project/healdan --body-file -` (owner's OK first, since it's public).
