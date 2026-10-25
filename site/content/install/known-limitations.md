---
title: Known Limitations
weight: 9
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
