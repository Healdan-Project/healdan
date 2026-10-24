---
title: Continuous Integration
weight: 4
---

`master` takes changes only through pull requests, and merges a pull request once its required checks pass.

## The Checks

| Check | Workflow | What it does |
|---|---|---|
| `build` | `build-logicaldoc.yml` | Checks the GUI's Font Awesome icons, compiles every module except the WAR without the GWT compile, then runs `healdan-core`'s and `healdan-api`'s tests and the upstream tests in its `-Dtest` list, which cover Healdan's edits to upstream code |
| `Test (amd64)`, `Test (arm64)` | `docker-image.yml` | Builds the image natively on each architecture, runs the init tool's unit tests, and runs `docker/smoke-test.sh` against the image |
| `Docs site` | `docs-site.yml` | Runs the script tests, builds the docs site strictly, checks its sources and its internal links, and on pushes to `master` publishes it |

## Docs-Only Pull Requests

A pull request that changes only `docs/`, `site/` or Markdown outside a `src/` folder skips the Java and Docker
build steps, but still reports `build`, `Test (amd64)` and `Test (arm64)`, which `master` requires.
`.github/scripts/code-changed.sh` decides. Pushes to `master` that change only those files don't rebuild the image.

## Things That Catch Upstream Syncs

- **Font Awesome Pro icons.** The GUI bundles Font Awesome Free 6, but upstream writes against Font Awesome Pro.
  Pro styles and Pro-only icons compile, and only show at runtime as blinking placeholders.
  `.github/scripts/check-fontawesome-icons.py` runs first in `build` and catches them. When a sync brings some in,
  swap them for Free icons in the sync PR itself.
- **Broken POMs.** Upstream sometimes ships a POM that fails Maven's validation, such as a duplicate dependency. If
  CI fails within a minute, check the POMs and the icon check first.

## Publishing the Image

Publishing to `ghcr.io` is off until the repository variable `GHCR_PUBLISH` is `true`. Until then, pushes to
`master` and `v*` tags build and test the image without publishing it.
