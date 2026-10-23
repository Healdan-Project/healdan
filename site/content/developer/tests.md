---
title: Tests
weight: 3
---

## Java Tests

The tests use JUnit 4. [Building from Source](../building-from-source/) has the commands.

- **The base class** is `com.logicaldoc.util.junit.AbstractTestCase`. It builds a Spring context from the module's
  test `context.xml` and `context.properties`, with an in-memory HSQLDB loaded from `getDatabaseScripts()`. For
  `logicaldoc-core` that's `sql/logicaldoc-core.sql` plus the test `data.sql`.
- **Each module has its own base class,** such as `AbstractCoreTestCase`, `AbstractWebappTestCase` or
  `AbstractWebserviceTestCase`. Extend the closest one. `healdan-core`'s `AbstractHealdanTestCase` also runs
  Healdan's migrations and setup steps on the test database.
- **Plugin manifests.** Some tests load them from fixture jars in `src/test/resources` through
  `getPluginArchives()`. Those jars are kept by hand; they aren't built from source.
- Tests write their stores, indexes and temporary files under `target/`.

## Docker Tests

| Command | What it checks |
|---|---|
| `docker build --target tools .` | the init tool's unit tests |
| `docker/tests/test-init.sh` | the init tool against a throwaway MariaDB |
| `docker/tests/test-image.sh` | the image: a prompt stop during init, file ownership, the `/setup` block, Admin > Restart, settings that persist |
| `docker/tests/test-overlays.sh` | the dev and proxy overlays |
| `docker/tests/test-fulltext.sh` | full-text search: a `.docx` and an `.eml` are indexed and found by their words |
| `docker/tests/test-previews.sh` | Office previews: converted ahead of time, text, markup and thumbnails, no fetching or macros while converting, a broken file failing cleanly, leftover processes cleaned up |
| `docker/smoke-test.sh` | end to end: build, start, REST login, uploads into a seeded matter, thumbnails, Healdan's schema, doc #s and filing rule, and data surviving a restart. With `SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image>` it tests an existing image instead of building one |

## Script and Docs Site Tests

The scripts in `.github/scripts/` and the docs site have Python tests, which need only Python 3 and, for the
site's build test, Hugo:

```bash
python3 -m unittest discover -s .github/scripts/tests -v
```
