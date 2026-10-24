---
title: Continuous Integration
weight: 4
---

`master` takes changes only through pull requests, and merges a pull request once its required checks pass.

## The Checks

| Check | Workflow | What it does |
|---|---|---|
| `build` | `build-logicaldoc.yml` | Checks the GUI's Font Awesome icons (`check-fontawesome-icons.py`) and for links to LogicalDOC's docs (`check-logicaldoc-doc-links.py`), compiles every module except the WAR without the GWT compile, and runs Healdan's tests in parallel shards (see below) |
| `Test (amd64)`, `Test (arm64)` | `docker-image.yml` | Builds the image natively on each architecture, runs the init tool's unit tests, and runs `docker/smoke-test.sh` against the image |
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

## Docs-Only Pull Requests

A pull request that changes only `docs/`, `site/` or Markdown outside a `src/` folder skips the test shards and the
Docker build steps, but still reports `build`, `Test (amd64)` and `Test (arm64)`, which `master` requires.
`.github/scripts/code-changed.sh` decides. Pushes to `master` that change only those files don't rebuild the image.

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
- **Broken POMs.** Upstream sometimes ships a POM that fails Maven's validation, such as a duplicate dependency. If
  CI fails within a minute, check the POMs, the icon check and the doc-links check first.

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

To publish a release, push a tag such as `v9.3.1-1`.

### Once Images Are Published

The image is tagged `latest` and `master` for the latest build of `master`, `sha-<commit>` (the first 7 characters
of the commit's hash) for one commit's build, and `v…` for a release. An install then sets `LOGICALDOC_IMAGE` in
`.env` to one of them and runs `docker compose pull` and `docker compose up -d`, without `--build`, which would
build from the checkout and store the result under the published name. The Install manual gets a page for this, and
its Upgrading page the advice on pinning tags, in the pull request that switches publishing on.
