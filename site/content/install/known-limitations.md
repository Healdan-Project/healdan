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
- **The sign-in throttle trusts every private network.** A machine on your LAN or VPN, or the Docker host
  itself, can claim another network address, and so get round the per-address limit and every limit on `admin`. See
  [Reverse Proxy](../reverse-proxy/#the-sign-in-throttle-behind-a-proxy).
- **No list of signed-in devices.** The web page doesn't show the devices a person has signed in on through the
  API. Changing their password or disabling them signs every one out.
