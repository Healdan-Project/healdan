# Open-source databases only: design

- **Tracking issue:** [Healdan-Project/healdan#110](https://github.com/Healdan-Project/healdan/issues/110)
- **Date:** 2026-11-01
- **Status:** the owner's decision on the issue (2026-10-29 and 2026-10-30), and their calls on what stays
  (2026-11-01). Built in Healdan-Project/healdan#148.

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks Claude's proposal.

## 1. Decision

Healdan supports only open-source databases. It keeps MariaDB, doesn't support MySQL and won't add it, and drops
SQL Server and Oracle completely. PostgreSQL becomes the first-class database later, and Healdan moves away from
MariaDB in time. (owner) The roadmap records this as principle 9, and PostgreSQL as a backlog item that needs its
own design session.

## 2. What changes

- **SQL Server's driver leaves the WAR.** `logicaldoc-webapp/pom.xml` drops `com.microsoft.sqlserver:mssql-jdbc`.
  Nothing compiles against it. Oracle's driver already went with the Maven repository (§3.1 of
  `2026-10-29-healdan-maven-repository-design.md`), so the WAR ships drivers for HSQLDB, MariaDB, MySQL and
  PostgreSQL only.
- **The setup wizard no longer offers Oracle or SQL Server, and defaults to MariaDB.** The container never runs the
  wizard (`/setup` answers 403), but "completely" was the owner's word. MySQL stays selectable, but it's no longer
  the choice the wizard starts on (call).
- **A check keeps them out.** `.github/scripts/check-database-drivers.py` runs in `build` after the Maven repository
  check (§4).
- **The docs say which databases Healdan supports:** a Supported Databases section in the Install Guide's What Runs
  page, which Known Limitations, Troubleshooting and the Admin Guide's System page point to.

## 3. What stays, and why

- **CE's Oracle and SQL Server code paths,** such as `PersistentObjectDAO.isOracle()` and `isSQLServer()` and the
  branches they guard. They never run on MariaDB, and taking them out would edit about a dozen upstream files that
  upstream changes often. (call)
- **Upstream's `.oracle` and `.mssql` SQL variants,** which `PluginDbInit` picks only for those databases.
  Upstream edited core's Oracle and SQL Server schema files 41 and 39 times in 2026, in 9 of its 10 months so far,
  and each edit to a file Healdan had deleted would make that week's sync a conflicted draft. The `.mysql` variants
  stay for the same reason. (owner, 2026-11-01)
- **The wizard's MySQL entry and MySQL's driver.** The docs say plainly that MySQL isn't supported, and
  `HealdanMigrator` already refuses to start on it. (owner, 2026-11-01)
- **The wizard's PostgreSQL entry and PostgreSQL's driver,** which PostgreSQL's support will need. `HealdanMigrator`
  refuses to start on PostgreSQL until its migrations exist.

## 4. The check

An upstream sync can bring either driver or wizard choice back, as it can Font Awesome Pro icons, so a check catches
it rather than whoever resolves the sync (call). `check-database-drivers.py` fails `build`, naming the file and
line, when:

- a tracked `pom.xml` declares a dependency, managed or not and in any scope, in one of the driver groups
  `com.microsoft.sqlserver`, `net.sourceforge.jtds`, `com.oracle.jdbc` or `com.oracle.database.jdbc`. An
  `<exclusion>` of one is fine;
- a tracked file names `com.microsoft.sqlserver.jdbc.SQLServerDriver`, `net.sourceforge.jtds.jdbc.Driver` or
  `oracle.jdbc[.driver].OracleDriver`, as the wizard's database list did.

It skips tests, prose (`docs/` and Markdown) and the docs site. The code paths and SQL variants in §3 name none of
these, so it leaves them alone. When it fails on a sync, the sync PR takes the driver or choice out, a merge fix like
the icons.

## 5. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-webapp/pom.xml` | Drops the `com.microsoft.sqlserver:mssql-jdbc` dependency | SQL Server is dropped completely |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/setup/client/Setup.java` | Drops the Oracle and SQL Server entries from `setupDatabase`'s engine list, and their two constants; preselects MariaDB instead of MySQL. A `Healdan:` comment marks the list and the default | Oracle and SQL Server are dropped completely, and the WAR ships neither driver; MariaDB is the database Healdan runs on |

## 6. Tests

`check-database-drivers.py` has its unit tests in `.github/scripts/tests/test_check_database_drivers.py`, which the
`Docs site` check runs. The check stands in for an `UPSTREAM_TESTS` class: it fails `build` if SQL Server's driver,
or the wizard's Oracle or SQL Server entry, comes back. No JUnit test can cover the wizard's GWT client code, which
needs a browser, so nothing checks its default; the setup module's GWT compile runs in the Docker image build.
