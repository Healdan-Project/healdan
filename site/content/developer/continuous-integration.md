---
title: Continuous Integration
weight: 4
---

`master` takes changes only through pull requests, and merges a pull request once its required checks pass.

## The Checks

| Check | Workflow | What it does |
|---|---|---|
| `build` | `build-logicaldoc.yml` | Checks the GUI's Font Awesome icons (`check-fontawesome-icons.py`), for links to LogicalDOC's docs (`check-logicaldoc-doc-links.py`) and for LogicalDOC's servers (`check-logicaldoc-hosts.py`), compiles every module except the WAR without the GWT compile, and runs Healdan's tests in parallel shards (see below) |
| `Test (amd64)`, `Test (arm64)` | `docker-image.yml` | Builds the image natively on each architecture, runs the init tool's unit tests, and runs `docker/smoke-test.sh` against the image, the API's add-in flow and the helper's open-save-close cycle (its stack test) included |
| `Clients` | `clients.yml` | Builds and tests the desktop clients in `clients/`: the helper on Ubuntu and macOS, and the LibreOffice extension's Python tests on Ubuntu (see below) |
| `Docs site` | `docs-site.yml` | Runs the script tests, builds the docs site strictly, checks its sources (`check-site-sources.py`) and its internal links (`check-site-links.py`), and on pushes to `master` publishes it |

## How `build` Runs

`build` is the last of several jobs, so that the tests can run side by side:

1. **`checks`** runs the icon and doc-link checks, reads the JDK from the `Dockerfile`, and plans the test shards
   with `.github/scripts/test-shards.py`.
2. **`Tests (1 of N)` … `Tests (N of N)`** each compile every module except the WAR, without the GWT compile, the
   javadoc or the plugin zips (the Docker build makes all of those), then run their share of the tests.
3. **`build`** passes when `checks` and every shard passed. It's the check `master` requires.
4. **`test-times`** saves how long each test class took, for the next run's plan. It isn't part of `build`.

The tests are:

- every test class in a `healdan-*` module. A new class, or a new `healdan-*` module in the reactor, is picked up
  without a list.
- the upstream tests named in the workflow's `UPSTREAM_TESTS`, one per line, which cover Healdan's edits to
  upstream code. A line is a class name, or `Class#method`. When a change edits upstream code, add its test class
  there. A name that no upstream module has fails `checks`.

The plan gives each shard about five minutes of tests, with at most eight shards. Each test class weighs its time in
the newest successful run, the pull request's own or else `master`'s, or 3 seconds per `@Test` until a run records
one. The log of `checks`' plan step lists each shard's classes with their times. A failing shard doesn't stop the
others, so one run shows every failing test.

### The Maven Repository Cache

Each shard restores a copy of Maven's local repository before it builds: the copy for the POMs as they are, or else
the newest copy `master` saved. Only `master`'s runs save one, from a single shard, so a pull request that edits a
POM downloads only what its edit adds. That matters because some dependencies come only from
`logicaldoc.sourceforge.net`, which sometimes refuses requests.

Maven notes where each file came from in a `_remote.repositories` file beside it, and uses a cached file only when
that note names one of the build's repositories. Maven 3.9 notes the repository's id, and Maven 3.10 its id and a
hash of its URL. 3.10 accepts 3.9's notes, but 3.9 rejects 3.10's, and `ubuntu-latest`'s images ship both. So each
shard deletes those files after restoring the copy, and Maven then takes every cached file as installed locally and
downloads only what's missing. Before that step, on 29 October 2026, shards on Maven 3.9 restored a copy Maven 3.10
had saved, downloaded every file again, and failed on SourceForge's 403.

The Docker build keeps its own copy, which the `maven:3.9` image both writes and reads, so it doesn't need the step.

## What a Pull Request Runs

`.github/scripts/code-changed.sh` lists the files a pull request changes and writes two answers. `code` is false
when the pull request changes only docs (`docs/`, `site/`, and Markdown outside a `src/` folder) and `clients/`. It
gates the Java build's test shards and the Docker build steps. `clients` is true when the pull request changes
anything under `clients/`, `clients.yml` itself, or the API contract,
`healdan-api/src/main/resources/healdan/api/openapi.yaml`, which the helper's contract tests read. It gates the client
test jobs. A push always gets both true.

Every workflow still reports its required checks when its steps are skipped, so `master`'s ruleset can require
them all.

| The pull request changes | `build` shards | Docker `Test` jobs | Client test jobs |
|---|---|---|---|
| Only `clients/` | Skipped | Skipped | Run |
| The API contract, `openapi.yaml` | Run | Run | Run |
| Only the server, or anything else outside `clients/` and the docs | Run | Run | Skipped |
| Only docs | Skipped | Skipped | Skipped |
| Docs and `clients/` | Skipped | Skipped | Run |
| The server and `clients/` | Run | Run | Run |

Pushes to `master` that change only docs or `clients/` don't rebuild the image.

## The Clients Check

`clients.yml` has no paths filter, so every pull request runs it and the check can be required. Its four check jobs
are:

1. **`changes`** runs `code-changed.sh`.
2. **`Helper (ubuntu-24.04)`** and **`Helper (macos-15)`** run `dotnet test clients/helper/Healdan.Helper.sln`, with
   the .NET SDK that `clients/helper/global.json` pins.
3. **`LibreOffice extension (ubuntu-24.04)`** runs `python3 -m unittest discover -s clients/libreoffice/tests -v`,
   which also builds the extension's `.oxt` and checks that its parts agree.
4. **`Clients`** is the check to require. It passes when both test jobs passed, or when both were skipped because
   nothing under `clients/`, nor the API contract, changed. A cancelled run fails it.

`Clients` is one of `master`'s required checks.

Two more jobs package the macOS helper. They run only for a `helper-v*` tag or a run by hand, never for a pull
request, and `Clients` doesn't wait for them (see [Releasing the macOS Helper](#releasing-the-macos-helper)).

The helper's tests marked `[Trait("Category", "Stack")]` need a running Healdan stack and skip here, because
`HEALDAN_STACK_URL` isn't set. The ones marked `MacKeychain` touch the real macOS keychain, so they run by hand on a Mac.

### The Helper's Stack Test in the Image CI

`docker/smoke-test.sh` has a step 5d, after the API's add-in flow, that runs the helper's stack test
(`dotnet test clients/helper/Healdan.Helper.sln --filter Category=Stack`, with `HEALDAN_STACK_URL` set to the smoke
stack). It signs in as `admin` through the API, files a document in the Smoke Matter, and opens, saves and closes
it through the helper's cycle with a fake LibreOffice.

- It runs only when `SMOKE_HELPER_TEST=1`, which `docker-image.yml` sets. The workflow sets up the .NET SDK and
  builds the helper's tests first, so a build failure shows on its own. Run locally without the variable, the
  smoke test skips the step.
- `dotnet test` passes when no test ran, so the step passes only if its summary shows at least one passed test and
  none skipped.

A pull request that changes only `clients/` doesn't build the image, so this test doesn't run on it. It runs on
`master` after the merge, and on pull requests that change the server. That gap is accepted.

## Things That Catch Upstream Syncs

- **Font Awesome Pro icons.** The GUI bundles Font Awesome Free 6, but upstream writes against Font Awesome Pro.
  Pro styles and Pro-only icons compile, and only show at runtime as blinking placeholders.
  `.github/scripts/check-fontawesome-icons.py` runs first in `build` and catches them. When a sync brings some in,
  swap them for Free icons in the sync PR itself.
- **Links to LogicalDOC's docs.** `.github/scripts/check-logicaldoc-doc-links.py` runs next to the icon check and
  fails `build` on any tracked file that links to docs, wiki, help or forums.logicaldoc.com, naming the file and line.
  When a sync brings one in, point it at docs.healdan.com in the sync PR. The docs site's interim pages are checked
  by `check-site-sources.py` instead, and the script's `ALLOWED` list holds the few exact lines Healdan leaves
  alone, each with its reason.
- **LogicalDOC's servers.** Healdan never contacts LogicalDOC. CE posted usage statistics to `stat.logicaldoc.com`
  and tested the proxy against `activation.logicaldoc.com`, and a sync can bring a call like those back.
  `.github/scripts/check-logicaldoc-hosts.py` runs after the doc-links check and fails `build` on any host, URL or
  email address at logicaldoc.com, .it, .net or .org, naming the file and line. It skips tests, prose, the docs site
  and the SOAP, CMIS and WebDAV XML namespaces, which are identifiers no one fetches. When a sync brings one in,
  remove the call in the sync PR. The script's `ALLOWED` list holds the exact lines nothing contacts, such as the
  POM's credits, each with its reason.
- **Broken POMs.** Upstream sometimes ships a POM that fails Maven's validation, such as a duplicate dependency. If
  CI fails within a minute, check the POMs, the icon check, the doc-links check and the hosts check first.

## Publishing the Image

`docker-image.yml` builds and tests the image on every push to `master` and every `v*` tag (pushes that only change
docs are skipped), but publishes it to `ghcr.io/healdan-project/healdan` only while the repository variable
`GHCR_PUBLISH` is `true`. It's off today. To switch it on:

1. Set the variable, under Settings → Secrets and variables → Actions → Variables, or with:

   ```bash
   gh variable set GHCR_PUBLISH --body true --repo Healdan-Project/healdan
   ```

   The workflow signs in with its own `GITHUB_TOKEN`, so no secret is needed.
2. After the first publish, open the package (the Healdan-Project organisation → Packages → `healdan`). If it's
   private, make it public under Package settings → Change visibility, so pulls work without `docker login`.

To publish a release, push a tag such as `v9.3.2-1`.

### Once Images Are Published

The image is tagged `latest` and `master` for the latest build of `master`, `sha-<commit>` (the first 7 characters
of the commit's hash) for one commit's build, and `v…` for a release. An install then sets `LOGICALDOC_IMAGE` in
`.env` to one of them and runs `docker compose pull` and `docker compose up -d`, without `--build`, which would
build from the checkout and store the result under the published name. The Install manual gets a page for this, and
its Upgrading page the advice on pinning tags, in the pull request that switches publishing on.

## Releasing the macOS Helper

The helper has versions of its own, such as `1.2.3` or `1.2.3-rc1`. Pushing the tag `helper-v1.2.3` runs
`clients.yml`'s tests, then two more jobs:

1. **`Package (macos-15)`** runs `clients/helper/packaging/mac/package.sh 1.2.3`, which publishes the helper as a
   single self-contained file for Apple silicon, builds `Healdan Helper.app` with the LibreOffice extension's
   `healdan.oxt` and the app's icon inside, signs it with the Developer ID, has Apple notarise it, and wraps it in
   `Healdan-Helper-1.2.3.dmg`, signed and notarised too. It uploads the disk image as the run's artifact.
2. **`Release (macOS)`** attaches the disk image to the tag's GitHub release, and makes the release first if
   there isn't one (a pre-release for a version like `1.2.3-rc1`). It runs only for a pushed tag, and only once
   `xcrun stapler validate` has found the disk image's notarisation ticket. It's the only job with write access to
   the repository, and it sees no signing secret.

Actions → Clients → Run workflow packages any branch or tag by hand, under a version you give or
`0.0.0-dev.<run>`, and attaches nothing to a release. Only a `helper-v*` tag's run is signed: a branch's is built
unsigned.

Signing needs six secrets in the repository's `helper-release` environment (Settings → Environments →
`helper-release` → Environment secrets). Only `helper-v*` tags may use it, so a run on a branch can't sign with the
Developer ID. Each is set with `gh secret set <name> --env helper-release --repo Healdan-Project/healdan`:

| Secret | What it holds |
|---|---|
| `APPLE_DEVELOPER_ID_P12` | The Developer ID Application certificate and its private key, exported from Keychain Access as a `.p12` file, in base64 (`base64 -i developer-id.p12`) |
| `APPLE_DEVELOPER_ID_PASSWORD` | The password the `.p12` file was exported with |
| `APPLE_TEAM_ID` | The Apple developer team's ID, the 10 characters in the certificate's name |
| `NOTARY_KEY` | An App Store Connect team API key (Users and Access → Integrations → App Store Connect API → Team Keys → Generate API Key, with the Developer role; notarytool can't use an individual key), its `.p8` file in base64 |
| `NOTARY_KEY_ID` | That key's ID |
| `NOTARY_ISSUER` | The Issuer ID shown above the team keys |

The job imports the certificate into a keychain of its own, which it deletes at the end, and decodes the key into
the runner's temporary folder, which it also clears. On a branch, or without the first three secrets (as on a fork),
the app gets only an ad-hoc signature, and without the last three it isn't notarised. Either way the job says so,
uploads the disk image as its artifact, and attaches nothing to the release.

On a Mac with the Developer ID certificate in the login keychain, the same script builds a signed package
(`OUT_DIR` sets where it goes, `clients/helper/packaging/mac/out/` by default). A `SIGN_IDENTITY` it can't find
in the keychains stops it, rather than building without a signature:

```bash
SIGN_IDENTITY="Developer ID Application: <name> (<team ID>)" clients/helper/packaging/mac/package.sh 1.2.3
```

`NOTARY_KEY` (the `.p8` file's path), `NOTARY_KEY_ID` and `NOTARY_ISSUER` notarise it as well.
