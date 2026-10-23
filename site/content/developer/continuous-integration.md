---
title: Continuous Integration
weight: 4
---

`master` takes changes only through pull requests, and merges a pull request once its required checks pass.

## The Checks

| Check | Workflow | What it does |
|---|---|---|
| `build` | `build-logicaldoc.yml` | Checks the GUI's Font Awesome icons (`check-fontawesome-icons.py`) and for links to LogicalDOC's docs (`check-logicaldoc-doc-links.py`), compiles every module except the WAR without the GWT compile, then runs `healdan-core`'s tests and the upstream tests in its `-Dtest` list, which cover Healdan's edits to upstream code |
| `Test (amd64)`, `Test (arm64)` | `docker-image.yml` | Builds the image natively on each architecture, runs the init tool's unit tests, and runs `docker/smoke-test.sh` against the image |
| `Docs site` | `docs-site.yml` | Runs the script tests, builds the docs site strictly, checks its sources (`check-site-sources.py`) and its internal links (`check-site-links.py`), and on pushes to `master` publishes it |

## Docs-Only Pull Requests

A pull request that changes only `docs/`, `site/` or Markdown outside a `src/` folder skips the Java and Docker
build steps, but still reports `build`, `Test (amd64)` and `Test (arm64)`, which `master` requires.
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
