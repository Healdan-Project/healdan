---
title: Known Limitations
weight: 10
---

- **LogicalDOC's database schema is never migrated.** Healdan upgrades its own tables on every start, but not
  LogicalDOC's. If a new release changes LogicalDOC's tables, that SQL has to be applied to an existing database
  by hand, after a backup.
- **MariaDB only.** Healdan's own tables support MariaDB, and HSQLDB for its tests. MySQL isn't supported.
- **No HTTPS on its own.** The app speaks plain HTTP, so anything beyond the machine running Docker goes through a
  [reverse proxy](../reverse-proxy/).
- **No list of signed-in devices.** The web page doesn't show the devices a person has signed in on through the
  API. Changing their password or disabling them signs every one out. For a lost device, also delete any LogicalDOC
  API keys the person doesn't recognise, under the person icon, **Security → API Keys**: a key outlasts a password
  change. Sessions already open in the web page or with a key stay open until an administrator kills them. See
  [Healdan Settings](../../admin/healdan-settings/#device-sign-ins).
- **LibreOffice on macOS only.** The [Healdan Helper](../office-integration/), which brings Healdan into LibreOffice,
  runs on Macs with Apple silicon. The helper for Ubuntu comes next, and Microsoft Office, on the Mac, after that.
  Windows comes later.
- **The extension is installed by a click.** On macOS, LibreOffice's command-line installer can't install Healdan's
  extension, so the helper opens it in LibreOffice for the person to accept: the first time, and whenever a new
  version of the helper brings a newer extension.
- **The doc ID footer is Writer's only.** Calc, Impress and Draw show the doc # and version in the title bar, but have
  no footer field.
- **Stamping removes a signature.** A Word or LibreOffice file with a digital signature that carries
  `Healdan Doc ID` loses its signature when a save through the helper or the API stamps it, as the stamp changes
  the signed content. The parts Healdan rewrites can also change form, such as the order of attributes, though not
  what they say. File signed documents without the property, or through the web page, which doesn't stamp.
- **Only saves through the helper update the doc ID.** Healdan writes the doc ID into files stored through its API,
  as the helper's are. A document with the footer that's uploaded or checked in through the web page keeps the
  number its footer had, until its next save through the helper.
- **Dates west of UTC in the web page.** The helper treats a date property as a calendar date, stored as midnight
  UTC. West of UTC, as in the Americas, the web page shows such a date as the day before.
- **A new document's check-out has no app.** For a document filed from LibreOffice and still open, another of the
  person's computers warns that it's "checked out to you on Office PC", without "(LibreOffice)". Once it's opened again,
  the warning names the app.
