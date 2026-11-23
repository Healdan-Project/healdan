---
title: Office Previews
weight: 10
---

Healdan converts Word, Excel, PowerPoint and OpenDocument files, LibreOffice Draw's `.odg` drawings among them, to
PDF with LibreOffice, which is in the Docker image, for their previews and thumbnails. There's nothing to switch on.
[Previews](../../user/documents/previews/) in the User Guide covers what people see.

## How It Works

- **In the background.** Each new file or file version is queued when it's filed, and converted one at a time.
  Previews are usually ready within a minute.
- **Files already in Healdan,** from before their previews were added, are converted in the background after the
  upgrade, newest first, alongside new uploads. Drawings got previews after other Office files, so an upgrade to
  the release that adds them converts the drawings already in Healdan the same way.
- **On the spot.** If someone opens a preview before its turn, it's converted then.
- **Safely.** Conversion never runs a document's macros and never fetches anything over the network.
- **Permissions** are those of PDF previews: Read and Download.
- **Reading one is recorded.** Opening a preview in the User app, in Quick Look or the reading pane, is recorded in
  the document's history as a view, once per person and document in half an hour, and the thumbnail isn't: see
  [What Counts as Looking at a Document](../monitoring/audit-trail/#what-counts-as-looking-at-a-document).

## When a Preview Says "Not Available"

A damaged or password-protected file can't be converted, and its preview says "not available". It doesn't hold up
other files. To find out why a file failed, open the Admin app's [Server Log](../monitoring/server-log/), choose
**Warnings and Errors**, and search for `LibreOffice` or `com.logicaldoc.healdan.preview`. The log is
`repository/logs/dms.log`.

A file that can't be converted is logged as a warning, not an error, so it isn't on the
[Errors](../monitoring/errors/) screen. A fault that stops the previews themselves is, in its **Previews** area.

## Files That Keep Timing Out

A conversion is stopped after 120 seconds. After a file version first times out, its previews say "not available"
straight away, and Healdan gives up on it after four tries.

To try again with a longer limit, raise `converter.OfficeConverter.timeout` (in seconds) in the configuration
volume's `context.properties`, and restart. Healdan then gives each version that timed out under the shorter limit
one more try. Replacing a version's file also starts its conversion over.

## Watching the Previews

The Admin app's Dashboard counts the previews waiting to be made and those given up on, on its **Previews** tile, and
under Needs Attention once one is given up on. Both open [Background Jobs](../monitoring/background-jobs/), whose
**Previews** job says when it last ran and how that went. It runs by itself, as files arrive, so Background Jobs
offers nothing to do with it.

[What Runs](../../install/what-runs/#office-previews) in the Install manual has the details.
