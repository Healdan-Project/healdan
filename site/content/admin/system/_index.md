---
title: System
weight: 9
---
LogicalDOC Community Edition's settings and tools that Healdan uses as they are.

Healdan's database is MariaDB, the stack's own or a server of your own: Healdan doesn't support MySQL, Oracle or
SQL Server.
See [Supported Databases](../../install/what-runs/#supported-databases) in the Install Guide.

## Giving a Settings Screen to Others

Each screen under **Administration → Settings** opens from a menu of its own, which can be given to a group as a
[report](reports/#giving-a-report-to-others) can: on the **Menus** tab of **Administration → Access Control →
Security**, give the group **Administration**, **Settings** and the screen's menu. As Healdan ships, only
administrators have them.

The server checks the screen's own menu, and lets the group change only the settings that screen shows. A group given
**Proxy** can't change the stores, and one given **Parameters** can change only what the Parameters list shows. The
database's settings, and those of LogicalDOC's paid screens, such as the firewall, stay with administrators.
