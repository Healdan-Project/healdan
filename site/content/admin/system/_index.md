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
administrators have **Settings** and its screens. LogicalDOC's **poweruser** group has **Administration**, and
**Document metadata**, whose **Tags** screen changes the tag settings.

The server checks the screen's own menu, and lets the group change only the settings that screen shows. A group given
**Proxy** can't change the storage backends. **Parameters** shows its list to a group given it, but only
administrators can change the list. Settings no screen shows, such as the database's, stay with administrators.
