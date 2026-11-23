# Healdan Repository Guidance

Healdan is a fork of LogicalDOC Community Edition. The detailed repository guide is in several files. Before editing, read [CLAUDE.md](CLAUDE.md), which holds what applies everywhere, including the build and test instructions. Then read the guide for each area you are changing, as nothing loads these for you:

- [docker/CLAUDE.md](docker/CLAUDE.md): the Docker image, the `Dockerfile` and compose files, the start-up tool, the move from MariaDB, and the Docker and browser tests.
- [healdan-core/CLAUDE.md](healdan-core/CLAUDE.md): provider sign-in, email, the Trash, and the Admin app's records and services.
- [healdan-api/CLAUDE.md](healdan-api/CLAUDE.md): the JSON API and its contracts.
- [healdan-web/CLAUDE.md](healdan-web/CLAUDE.md): the User app and the Admin app.
- [healdan-storage/CLAUDE.md](healdan-storage/CLAUDE.md): storage backends.
- [clients/CLAUDE.md](clients/CLAUDE.md): the Healdan Helper, the LibreOffice extension and the Office add-ins.
- [.github/CLAUDE.md](.github/CLAUDE.md): CI's workflows and checks in detail.
- [docs/reference/upstream-edits.md](docs/reference/upstream-edits.md): every upstream file Healdan edits, by spec.

## Working in This Repository

- Work directly on small fixes, reviews, documentation edits, and upstream syncs. Before starting feature work, read the [roadmap and glossary](docs/ROADMAP.md). Milestones get a design spec before implementation.
- Put new Healdan behaviour in the `healdan-*` modules where practical. Keep upstream edits small, and list each in its spec and in [the upstream edits list](docs/reference/upstream-edits.md). For upstream merges, follow the [upstream sync guide](site/content/developer/upstream-syncs.md) and keep feature work out of sync PRs. The repository uses merge commits.
- Update the affected manuals in the same PR as the code they describe. Follow the [writing guide](site/content/developer/writing-docs.md), including British spelling and the project’s glossary.

## Build and Test

- Use the [build guide](site/content/developer/building-from-source.md) and [test guide](site/content/developer/tests.md) to choose checks for the affected area. The Java build uses JDK 25, Maven 3.9+, and [the repository’s Maven settings](docker/maven-settings.xml). Healdan’s Java tests use PostgreSQL 18. Upstream Java tests use HSQLDB.
- For the User and Admin apps, run the relevant npm checks from `healdan-web/web`. See [The Web App](site/content/developer/the-web-app.md) for setup and API type generation. The [desktop clients guide](site/content/developer/desktop-clients.md) gives the separate Helper, LibreOffice, and Office add-in checks.
- When CI fails, read [.github/CLAUDE.md](.github/CLAUDE.md) for what each check runs. When `build` fails within a minute, [CLAUDE.md](CLAUDE.md) lists the quick checks to look at first, each of which runs locally with `python3`.
