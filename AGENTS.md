# Healdan Repository Guidance

Healdan is a fork of LogicalDOC Community Edition. Use [CLAUDE.md](CLAUDE.md) as the detailed repository guide. Before editing, read its sections for the area you are changing, including the relevant build and test instructions.

## Working in This Repository

- Work directly on small fixes, reviews, documentation edits, and upstream syncs. Before starting feature work, read the [roadmap and glossary](docs/ROADMAP.md). Milestones get a design spec before implementation.
- Put new Healdan behaviour in the `healdan-*` modules where practical. Keep upstream edits small. For upstream merges, follow the [upstream sync guide](site/content/developer/upstream-syncs.md) and keep feature work out of sync PRs. The repository uses merge commits.
- Update the affected manuals in the same PR as the code they describe. Follow the [writing guide](site/content/developer/writing-docs.md), including British spelling and the project’s glossary.

## Build and Test

- Use the [build guide](site/content/developer/building-from-source.md) and [test guide](site/content/developer/tests.md) to choose checks for the affected area. The Java build uses JDK 25, Maven 3.9+, and [the repository’s Maven settings](docker/maven-settings.xml). Healdan’s Java tests use PostgreSQL 18. Upstream Java tests use HSQLDB.
- For the User and Admin apps, run the relevant npm checks from `healdan-web/web`. See [The Web App](site/content/developer/the-web-app.md) for setup and API type generation. The [desktop clients guide](site/content/developer/desktop-clients.md) gives the separate Helper, LibreOffice, and Office add-in checks.
