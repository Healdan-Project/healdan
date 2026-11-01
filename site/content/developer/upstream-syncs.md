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
