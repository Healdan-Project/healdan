---
title: What Runs
weight: 2
---

## The Two Services

| Service | What it is | Where its data lives |
|---|---|---|
| `db` | MariaDB 11.4 | the volume `logicaldoc_db-data` |
| `app` | Tomcat 11 running Healdan | the volumes `logicaldoc_logicaldoc-conf` (configuration: `context.properties`, `log.xml`, `allowed-commands.txt`) and `logicaldoc_logicaldoc-repository` (documents, the search index, user data and logs) |

[Backup and Restore](../backup-and-restore/) covers all three volumes.

## Supported Databases

Healdan runs on MariaDB: the stack's own `db` service, or a MariaDB server of your own, which `DB_HOST` and
`DB_PORT` point at (see [Configuration](../configuration/)). It supports only open-source databases, and stops when
it starts on any other.

- **MySQL isn't supported,** although MariaDB grew out of it. Healdan's own tables use sequences, which MySQL
  doesn't have.
- **Oracle and SQL Server aren't supported.** Healdan doesn't include their drivers.
- **PostgreSQL** support comes later.

## What the App Does When It Starts

- **On the first start** it writes `context.properties`, waits for MariaDB, and creates the database schema.
- **On every later start** it re-applies only the settings that come from `.env` (see
  [Configuration](../configuration/)).
- **It adds new default settings.** A setting that's in the image's defaults but missing from your
  `context.properties`, such as one a new release introduces, is added. A value you already have is never changed,
  except the release number and copyright years (`product.release` and `product.year`), which always come from the
  image, and an older image's default that the new image replaces (so far only `cachecontrol`), unless you changed
  it yourself.
- **The setup wizard is switched off.** `/setup` answers with HTTP 403, because `.env` configures the database.

## External Programs

The image includes ImageMagick, Poppler and Ghostscript for thumbnails and previews, and LibreOffice for Office
and drawing previews. Healdan only runs external programs listed in `conf/allowed-commands.txt`, in the
configuration volume. The app puts its own entries back in that file on every start; add your own lines there if
you need more.
Ghostscript isn't listed, because ImageMagick calls it directly.

## Office Previews

The image includes LibreOffice (headless Writer, Calc, Impress and Draw) and fonts metrically compatible with
Microsoft's (Carlito, Caladea and Liberation), so Word, Excel, PowerPoint and OpenDocument files, LibreOffice Draw's
`.odg` drawings among them, get a PDF preview and a thumbnail. Nothing needs configuring.

- **When conversions run.** In the background, after upload. After an upgrade, Office documents already in Healdan
  are converted in the background too, newest first, alongside new uploads, and so are drawings, after the upgrade
  that adds their previews.
- **When a conversion fails,** the preview says "not available". To find out why, search
  `repository/logs/dms.log` for `LibreOffice` or `com.logicaldoc.healdan.preview`.
- **Files that keep timing out.** After a version first times out, its previews say "not available" straight
  away instead of waiting, and Healdan gives up on it after four tries. To try again, raise
  `converter.OfficeConverter.timeout` (in seconds, default 120) in the configuration volume's
  `context.properties` and restart. Healdan then gives each version that timed out under the shorter limit one
  more try. Replacing a version's file also starts its conversion over.
- **LibreOffice's own output** reaches the log only at DEBUG level, on the logger `com.logicaldoc.util.exec`. The
  default `log.xml` already sets it to `debug`. If your copy in the configuration volume sets it higher, change
  that logger's line back to `level="debug"` and restart.
