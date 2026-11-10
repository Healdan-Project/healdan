---
title: Upstream Syncs
weight: 6
---

Healdan keeps merging LogicalDOC Community Edition's fixes. Upstream is `https://github.com/logicaldoc/community`.
A fresh clone needs it added as a remote:

```bash
git remote add upstream https://github.com/logicaldoc/community.git
```

## How a Sync Works

- **Merge `upstream/master` into a branch of its own,** and open a pull request to `master`. Always merge: never
  squash or rebase, so the shared history stays intact.
- **Keep sync pull requests free of feature work.** Fixes the merge needs, such as swapping a Font Awesome Pro icon
  for a Free one, a link to LogicalDOC's docs for one to Healdan's, importing an artifact into Healdan's Maven
  repository, or taking out an Oracle or SQL Server driver or wizard choice, belong in the sync itself.
- **A change to LogicalDOC's tables gets an entry in Upgrading.** Healdan never changes LogicalDOC's tables on an
  existing database, so a sync that changes them, in a `logicaldoc-*` module's `sql/` scripts or in the columns an
  entity maps, adds the SQL an existing database needs, with a check, to the Install manual's
  [Changes to LogicalDOC's Tables](../../install/upgrading/#changes-to-logicaldocs-tables). That's a fix the merge
  needs too. Until an existing database has the change, the init tool stops its start, naming each table and column
  the scripts create that it lacks, so a sync that changes the scripts without an entry leaves admins stuck.
- **`.github/workflows/upstream-sync.yml`** runs a sync every week. `.github/scripts/upstream-sync.sh` does the
  merge.

## Versions

Healdan's own modules (`healdan-*`) follow upstream's version: their POM parent and `plugin.xml` version match the
root POM's. A module left behind makes Maven look for stock LogicalDOC jars, which the build no longer fetches: it
fails with `Non-resolvable parent POM … Could not find artifact com.logicaldoc:logicaldoc:pom:<old version>`. Bump
the module instead.

- After a clean merge that brings a LogicalDOC release, the sync script adds a "Follow upstream to &lt;version&gt;"
  commit.
- After a merge with conflicts it doesn't, so whoever resolves the conflicts bumps the versions too.

## Keeping Syncs Cheap

Upstream changes `logicaldoc-core` and `logicaldoc-gui` the most. New work goes into new plugin modules, and edits
to upstream files stay as small as they can be.
