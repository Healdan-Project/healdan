---
title: Upgrading
weight: 8
---

{{< callout type="info" >}}
These docs describe Healdan as it is on its `master` branch. If your server runs an older build, some pages may
describe features or settings it doesn't have yet.
{{< /callout >}}

[Back up](../backup-and-restore/) first. Then, from the folder you cloned Healdan into:

```bash
git pull
docker compose up -d --build
```

To update MariaDB too, run `docker compose pull --ignore-buildable` before that. A plain `docker compose pull`
fails, because the app's image is built here and can't be pulled.

## What an Upgrade Changes

- **Your data stays.** The volumes are kept.
- **New default settings are added** to your `context.properties` when the app starts. Values you already have
  aren't changed, apart from three kinds:
  - the release number and copyright years (`product.release` and `product.year`), which the new image updates;
  - a setting still holding an older image's default that the new image replaces. If you changed it yourself, your
    value stays. So far that's `cachecontrol`, which tells browsers how long to keep Healdan's files;
  - non-breaking spaces in the Content-Security-Policy (`security.csp`), which tells browsers what Healdan's pages
    may load. They become ordinary spaces, in a policy you changed too, and the rest of it stays. Until November
    2026 the default had one after `object-src`, so browsers ignored that part of the policy, and their consoles
    showed "Unrecognized Content-Security-Policy directive".
- **Browsers load the new interface** the next time they open Healdan. A tab left open during the upgrade keeps the
  old one until it's reloaded.
- **Healdan's own tables are upgraded** when the app starts. If an upgrade step fails, the app stops instead of
  running on a half-upgraded database, and the log says which step failed. See
  [Troubleshooting](../troubleshooting/).
- **LogicalDOC's tables aren't.** See [Known Limitations](../known-limitations/).

## Presentations and Spreadsheets Whose Text Wasn't Read

Until November 2026, Healdan couldn't read the text of PowerPoint presentations (`.pptx`, `.ppsx`), older Excel
workbooks (`.xls`, `.xlt`), or newer ones (`.xlsx`, `.xlsm`) with a cell in a format such as Excel's Accounting.
Unless **Ignore content error** was on (see below), a full-text search didn't find them at all, not even by their
names, and only a search by their properties did. The log showed
`NoClassDefFoundError: com/zaxxer/sparsebits/SparseBitSet` at each run of the Indexer.

After the upgrade, the Indexer reads them at its next run, as it kept trying them, unless one of two settings on
[Search and Indexing](../../admin/system/search-and-indexing/) was on:

- **On error, mark unindexable** gave up on them. On its **Indexing History** tab, click the **Result** column's
  header to bring the failures together, select those documents, and choose **Mark indexable** from the right-click
  menu. **Reschedule all for indexing** doesn't reach documents marked unindexable.
- **Ignore content error** indexed them without their text, so a full-text search found them by their names but not
  their words. [Reschedule all for indexing](../../admin/system/search-and-indexing/#indexing-again) to have them
  read.

## Emails Filed Before the Outlook Add-in

The upgrade that brings [Filing Email from Outlook](../../user/office/filing-email/) also starts keeping each email's
Message-ID, which is how Healdan knows that a matter already holds a message. The emails already in Healdan get
theirs in the background:

- **A minute after the app starts,** Healdan reads the emails already filed, 50 at a time with a second's pause
  between batches, in the order they were filed. A restart carries on where it stopped. Until it has read an email,
  that email doesn't count when the same message is filed into its matter again, and the Outlook pane doesn't show it
  as filed.
- **Emails already filed twice in one matter** stay as they are. Once both are read, a folder move, merge, copy or
  restore that would bring the two copies into a matter together is refused, saying the message is already there.
  Delete one copy, or move it to another matter, first.
- **To see whether it has finished,** look for `email.index` in Healdan's `hd_setting` table. Run this, and enter
  the database password when asked (use your `DB_USER` and `DB_NAME` if you changed them):

  ```bash
  docker compose exec db mariadb -u logicaldoc -p -D logicaldoc -e "select name, value from hd_setting where name like 'email.index%'"
  ```

  `email.index` is `done` once every email filed before the upgrade has been read, and `email.index.cursor` is the
  last document it has read so far.
