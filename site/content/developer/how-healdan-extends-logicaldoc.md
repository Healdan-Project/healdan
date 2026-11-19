---
title: How Healdan Extends LogicalDOC
weight: 5
---

Healdan is a fork of [LogicalDOC Community Edition](https://github.com/logicaldoc/community). It started from 9.3.1
and follows upstream's releases: the root `pom.xml`'s version is the one it's on. It goes its own way, but still
merges upstream's fixes, so its own work stays out of upstream's files wherever it can.

## LogicalDOC's Modules

- `logicaldoc-util` is at the bottom, and `logicaldoc-core` builds on it.
- The feature modules build on core: `logicaldoc-webservice` (REST through CXF; Healdan's `SoapRemover` removes its
  SOAP endpoints), `logicaldoc-webdav`, `logicaldoc-cmis`, `logicaldoc-dropbox`, `logicaldoc-android` and
  `logicaldoc-ios`.
- `logicaldoc-webapp` is the WAR that bundles everything. `logicaldoc-gui` is the GWT client, and depends only on
  util. `logicaldoc-i18n` holds the message bundles.

## Plugins

Almost every module is a JPF plugin, and a plugin takes four pieces:

1. `src/main/resources/plugin.xml` declares its id, what it needs, and the extensions it adds.
2. A class extending `com.logicaldoc.util.plugin.LogicalDOCPlugin` can register servlets, scheduled tasks and
   loggers when the plugin installs.
3. `src/main/assembly/assemblyplugin.xml` packages a `*-plugin.zip`. It takes only the dependencies its
   `<include>`s name, not their own dependencies, so a library's dependency reaches the WAR only when some zip names
   it: `logicaldoc-core`'s zip ships POI, and `healdan-core`'s ships POI's SparseBitSet and curvesapi, which core's
   leaves out. Maven's tests have every dependency, so `WarParsersTest` (see [Tests](../tests/#docker-tests)) parses
   Office files on the WAR's own libraries, and `WarStorageTest` uses the AWS SDK and Tink there, which
   `healdan-storage`'s zip ships.
4. An `artifactItem` in `logicaldoc-webapp/pom.xml` copies the zip into the WAR. Without it, the plugin isn't
   shipped.

Core's `plugin.xml` defines the extension points other plugins hook into, such as `DbInit`, `DocumentListener`,
`FolderListener`, `Task`, `Feature` and `SystemInfo`. `logicaldoc-dropbox` is the smallest complete plugin to
learn from.

## Spring and Hibernate

- `web.xml` starts `com.logicaldoc.util.spring.MainContext`. It scans `com.logicaldoc` for `@PluginContext`
  classes and imports the webapp's `context.xml`, which defines the datasource, the session factory and
  transactions.
- Entities are JPA-annotated `@Entity` classes. DAOs follow one pattern: an interface `XxxDAO` and an
  implementation `HibernateXxxDAO`.
- **Both Spring and Hibernate scan only `com.logicaldoc`,** so code in any other package isn't found.
- **A bean definition Spring can't make is abstract.** Beans are fetched with `Context.get(Type.class)`, and to learn
  what a `FactoryBean` makes, Spring makes every one not yet made, lazy ones too. One that fails is made again at
  the next lookup, and Spring 6.2 records each failure without a lock, so two lookups at once could throw and
  answer a request with a 500. LogicalDOC's template for transactional proxies in the webapp's `context.xml`,
  `applicationBaseTransactionProxy`, has no target, so Healdan makes it abstract, an upstream edit.

## MariaDB and PostgreSQL

The stack runs on MariaDB, and Healdan's Java tests on PostgreSQL 18 (see
[The Test Database](../tests/#the-test-database)). So until the stack moves to PostgreSQL, Healdan's code, and
LogicalDOC's under it, works on both, and Healdan's SQL is written once, in a form both databases take, but for its
Flyway migrations, which have a folder for each until then (see [Healdan's Code](#healdans-code)). These make
LogicalDOC's code, and Healdan's, work on PostgreSQL:

- **Booleans are bound as integers.** LogicalDOC maps Java `boolean` fields onto `int` columns, and PostgreSQL
  refuses a boolean for an `int` column. `healdan-core`'s `hibernate.properties`, at its class path's root, sets
  `hibernate.type.preferred_boolean_jdbc_type=INTEGER`. Hibernate reads it when it starts, with no upstream edit, and
  binds every Java `boolean` as an integer, on either database. `BooleanBindingTest` checks the setting is in force,
  and so does every Healdan test as it starts (see [Java Tests](../tests/#java-tests)). Hibernate reads only the
  first `hibernate.properties` on the class path, so `WarHibernateTest` checks the WAR holds only Healdan's (see
  [Tests](../tests/#docker-tests)). A flag in Healdan's own tables is an `int` column too, never a `boolean` one.
- **Typed reads convert numbers in Java.** LogicalDOC's base DAO, `HibernatePersistentObjectDAO`, reads one value as
  a type in `queryForObject` and in `queryForList`'s single-column form, by asking the driver for that type.
  PostgreSQL's driver gives a `Long` only from a `bigint`, and its refusal of one from an `int`, or from a `numeric`
  such as a `sum()`, marks the caller's transaction for rollback, which its commit then rolls back without a word:
  an edit of an existing user would be lost. Two lines there, an upstream edit marked `// Healdan:`, hand the value
  the driver returned to `HealdanTypedReads`, a fork-owned class beside `HealdanHooks` in `logicaldoc-core`. A value
  already of the type comes back as it is. A number held exactly, an integer, a `BigInteger` or a `BigDecimal`, it
  converts in Java: to a `Long` or an `Integer` when that holds it exactly, and to a `Double`, a `Float`, a
  `BigDecimal` or text in plain notation. Anything else, such as a fraction asked for as a `Long`, a value that is a
  `Double`, or text, goes to the driver, and on MariaDB a read gives what its driver would. `HealdanTypedReadsTest`
  checks it, in `UPSTREAM_TESTS`, and `TypedReadsOnPostgresTest` that an edit of a user is kept.
- **A statement that may fail runs inside a savepoint.** On PostgreSQL a statement that fails ends its transaction,
  whatever the Java around it catches, where MariaDB fails only the statement. So no statement of Healdan's is
  expected to fail inside a transaction, and one that may fail without failing its caller runs inside a savepoint,
  through `healdan-core`'s `Savepoints` (`com.logicaldoc.healdan.util`). A failure, whatever it is, rolls back to the
  savepoint, and the caller's transaction goes on; outside a transaction the work runs as it is.
  - `insertIfAbsent` inserts a row unless its key is taken, as by another transaction since the caller's read:
    `PreviewJobs.add`, `EmailIndex.put` (which then updates the row), and `healdan-storage`'s
    `StoredFiles.recordWrite` and `HeldCopies.hold`.
  - The listeners that log and swallow a failure inside LogicalDOC's save run their statements inside one, so the
    save is kept, as it is on MariaDB: `EmailDocumentListener` and `PreviewDocumentListener`. `PreviewDocumentListener`
    keeps its check of the event and the file name, which is Java alone, outside, as a savepoint costs two round trips
    to the database and its methods run on every save. `EmailDocumentListener` puts the whole of its `try` inside.
    The two favourite-names listeners need none: by their `afterStore` the row before the store is in the store's
    dictionary or the session's map, so they don't reach the database there.
- **SQL given to LogicalDOC's DAO helpers never uses `::`,** which its `NamedParameterStatement` reads as a
  parameter. Write `cast(x as text)`.

## Healdan's Code

- **`healdan-core`** is Healdan's plugin. It holds Healdan's server rules and all its data, under
  `com.logicaldoc.healdan`, where the scans find it with no edits.
  - Its GUI code is in `logicaldoc-gui`, under `com.logicaldoc.gui.frontend.client.healdan`, reached from one-line
    hooks in upstream's GUI classes.
  - Its servlets register with `@WebServlet`. Its RPC paths are `/frontend/healdan*` and its data paths
    `/data/healdan*`.
  - Its listeners enforce Healdan's rules on every route into the server: the web UI, Healdan's API, LogicalDOC's
    web services, WebDAV and CMIS. Required properties are the exception: on the server, only Healdan's API
    checks them when a document is filed.
  - Two more keep favourites' names in step with their items, on the same routes: when a document or folder is
    renamed, `FavoriteNamesDocumentListener` and `FavoriteNamesFolderListener`, the last of Healdan's listeners, give
    the new name to each bookmark on it whose title is still the old one, once the store's transaction has committed,
    in a transaction of their own, so they never fail a rename. A bookmark its
    owner named keeps its name (see the [API](../api/#favorites)).
- **`healdan-api`** is Healdan's [API](../api/), a plugin of its own that requires `healdan-core`. Its code is under
  `com.logicaldoc.healdan.api`, where `healdan-core`'s Spring scan finds it.
  - `ApiServlet` serves `/api/*`, and registers with `@WebServlet`. It runs CXF's JAX-RS on a CXF bus of its own,
    so LogicalDOC's services under `/services/` don't show up under `/api` too.
  - Spring Security leaves `/api/**` alone (`security="none"`). The API signs each request in from its device
    token, or from the browser's web session on Healdan's own pages (see the API's
    [Two Ways In](../api/#two-ways-in)), and from nothing else.
  - `openapi.yaml`, in `src/main/resources/healdan/api/`, is written by hand. It's the contract the tests check
    every answer against, so a new endpoint goes into it with the code.
  - The rules and data it uses, such as device tokens and where a property's value comes from, stay in
    `healdan-core`, so code outside the API can use them too.
  - **The admin API** is the Admin app's, under `/api/v1/admin`, in `com.logicaldoc.healdan.api.admin`. Each of its
    resource classes carries `@AdminRoute` with the area it belongs to (`AdminArea`: `DASHBOARD`, `MATTERS`,
    `STORES`, `PEOPLE`, `STORAGE`, `MONITORING` or `SEARCH_INDEX`). `AdminFilter`, which runs after the token check,
    answers a device token `403 web-session-only`, so a stolen token can't administer anything, and anyone
    `healdan-core`'s `AdminAccess.allowed(user, area)` refuses `403 admins-only`. That's everyone outside the `admin`
    group, in every area, for now, asked at every request. It fails closed: a route declared under `/v1/admin`
    whose class lacks the annotation is logged as an error and refused to everyone, judged by the path the route
    declares (`RoutePaths`), never the request's, which can carry matrix parameters. A request it lets through has its
    address in `healdan-core`'s `AdminContext` until `ApiServlet` clears it, once the answer is written.
  - **The admin API's contract** is a second file, `openapi-admin.yaml`, beside `openapi.yaml`, which the server
    doesn't serve. `$ref` can't reach across two files, so it repeats `Page`, `FieldError` and the `Problem`
    response, and `Problem` with fewer members, and `OpenApiFileTest` keeps the copies the same (see
    [The API's Tests](../tests/#the-apis-tests)).
- **`healdan-storage`** keeps documents' files on [storage backends](../../admin/storage-backends/), and is a plugin
  of its own that requires `healdan-core`. Its code is under `com.logicaldoc.healdan.storage`, where `healdan-core`'s
  Spring scan finds its beans.
  - **It replaces LogicalDOC's storage by configuration.** LogicalDOC makes its one `store` bean from the `store` key
    in `context.properties` (`<bean id="store" class="${store}"/>` in core's `context-core.xml`), and every class
    that reads or writes a document's file, LogicalDOC's and Healdan's, goes through that bean. `HealdanStorage`
    implements LogicalDOC's `Store` interface, and the Docker init tool owns the key: `ConfigPlan.HEALDAN_KEYS` sets
    `store=com.logicaldoc.healdan.storage.HealdanStorage` on every start, whatever the file holds, and `DockerInit`
    checks it was stored. So no LogicalDOC Java changes, and the image's defaults, which are upstream's, keep
    LogicalDOC's `FSStore`. Outside Docker the key is whatever the file says, and on `FSStore` the module's beans and
    its download filter do nothing, and the Storage tab says storage backends are off.
  - **`HealdanStorage` is never a Spring component.** Only LogicalDOC's bean definition makes it: a component would
    be a second instance, `healdanStorage`, made on `FSStore` servers too, which `Context.get(HealdanStorage.class)`
    finds before LogicalDOC's (`Store.get()` looks `store` up by name). Its `init()` reads no table, as Spring calls it
    before Flyway has run.
  - **Its plugin pieces.** `plugin.xml` declares two listeners, `StoreTrackingDocumentListener` and
    `StoreTrackingFolderListener` (position 120, after Healdan's rules), which keep each file's Store current as
    documents and folders move. `HealdanStoragePlugin` does nothing at install, as the Storage tab's RPC servlet
    (`/frontend/healdanstorage`) and the download filter register with `@WebServlet` and `@WebFilter`.
    `assemblyplugin.xml` ships the AWS SDK for Java's S3 client and Tink, which `WarStorageTest` checks on the WAR's
    own libraries. An `artifactItem` in `logicaldoc-webapp/pom.xml` puts the zip in the WAR. That and the root
    `pom.xml`'s `<module>` are the only upstream files it edits.
  - **Its rows** are `healdan-core`'s Flyway tables, `hd_storage_*` (`V9__storage.sql`, and
    `V10__storage_file_backend.sql`'s index on each file's backend): the backends, each Store's backend, a row for
    every file saying which backend holds it, the held copies, and the showings of recovery keys.
    Its setup steps join `healdan-core`'s `SeedRunner.ORDER` by name, and do nothing unless LogicalDOC's store is
    `HealdanStorage`.
  - **Two hooks in `healdan-core`.** At a real start, after the migrations and setup steps, `HealdanStartup.start()`
    calls each `HealdanStartListener` bean: `healdan-storage`'s `StorageStartup` checks every backend, then starts the
    mover, the checks of failing backends, the purge, the alert and the nightly check. Tests call
    `HealdanStartup.run()`, which calls no listener, so they start no thread. And LogicalDOC restores a folder tree
    from the trash with JDBC, telling no folder listener, so `HealdanHooksImpl.afterFolderRestore` calls each
    `FolderTreeListener` bean with the tree's documents: `StoreTracking` works their Stores out again, in case the
    tree came back into another Store.
  - **A hold inside LogicalDOC's transaction is undone if it rolls back.** `Held.hold` moves a local backend's or
    staging's copy under `.held/` at once, holding the file's lock, while its `hd_storage_held` row and the delete of
    the file's `hd_storage_file` row are the transaction's. `HoldRollback`, a transaction synchronisation, puts each
    copy back in its place on a rollback, holding its lock, before `FileLocks`' marks clear: the mover and the purge
    leave a file an open transaction wrote, so neither finds its row back before its copy. Each copy notes the file's
    marks when it was held (`FileLocks.marksMade`, which counts writes and deletes, in a transaction or not, while one
    is open on the file). If nobody else has written or deleted the file since, anything in its place is the
    transaction's own write, which the copy replaces; if another has, the copy stays under `.held/`, as that one's hold
    may name it. A second hold of the key in the same transaction deletes the transaction's own write rather than
    replace the first held copy. A hold whose row can't be written puts its copy back at once.
  - **A copy left under `.held/` is mended.** A copy a rollback couldn't put back waits for the purge's next run,
    which puts it back if its row is on that backend and its place is empty. The purge does the same for any local
    held copy whose row reads it from `.held/`, rather than delete it: a rollback can bring back an older hold of the
    location. And a delete that finds a file missing from its place but under `.held/` holds that copy again, so the
    purge deletes it in its time.
  - **The Admin app reads storage through a port.** `healdan-core` and `healdan-api` can't depend on
    `healdan-storage`, so `healdan-core` has an interface, `StorageAdministration`
    (`com.logicaldoc.healdan.admin.storage`), with plain records (`StorageRecords`), which `healdan-storage`'s
    `StorageAdministrationImpl` bean implements over the Storage tab's own service, `StorageAdmin`, unchanged. It's
    found by type (`Context.get().getBeansOfType`), as `HealdanStartListener`s are, so a server without the module has
    none. Its answers hold nothing secret: no S3 access key or secret, no key and no recovery key, and an S3
    backend's endpoint as its scheme, host and port alone.
  - **A delete may build a backend's S3 client inside LogicalDOC's transaction.** `Held.hold`, which holds a deleted
    file's copy while holding that file's lock, inside the transaction of LogicalDOC's delete, builds the backend's
    client if nothing has yet. For an `http://` endpoint that's a DNS lookup, as the endpoint rule checks the host's
    addresses. A build that failed is remembered for 30 seconds, so while DNS is down, deletes don't each wait on a
    lookup of their own.
- **`healdan-web`** serves Healdan's User app at `/app/` and its Admin app at `/admin/`, and is a plugin of its own
  that requires `logicaldoc-core`, `healdan-core` and `healdan-api`. Its code is under `com.logicaldoc.healdan.app`.
  - `AppServlet` registers with `@WebServlet("/app/*")`, and `AdminAppServlet` with `@WebServlet("/admin/*")`, so
    `web.xml` is not edited. The built apps sit in the module's jar under `healdan/app/user/` and
    `healdan/app/admin/`, and the servlets read them from the class path. What they share is `BuiltAppServlet`: the
    methods, the headers, reading a path, the media types, the hashed files and the `ETag`s.
  - **Its policy.** A path with a file extension that the jar holds is a file, and so is any under `assets/`, where
    Vite writes the build's files: served, or `404` when the jar has none, so a stale page fails loudly. Any other
    path, `/app/doc/10388.2` too, is an address inside the app, answered with `index.html`, which the app routes in
    the browser. Hashed files under `assets/` are cached for a year, PDF.js's data under `pdfjs/` is `no-cache` with
    an `ETag`, and everything else is `no-store`. Only `GET`
    and `HEAD` are allowed. A path that could leave the app's folder is never read: it gets `index.html` or `404`.
  - **Its sign-in check.** A page load needs the web session the API's route would accept, its user included
    (`WebSessionRoute`), so a disabled account goes to the sign-in page rather than to an app that can't load
    anything. Without one, the servlet stores the return path and redirects to `login.jsp`; once a signed-in page
    loads, it forgets the return path, so a later sign-in lands where Start In says.
  - **The Admin app's check.** `AdminAppServlet` serves the Admin app to members of the `admin` group only, asked
    afresh at every request (`WebSessionRoute`'s session and user, then `AccessChecks.isAdmin`), so someone taken out
    of the group loses the app at their next load. It asks before it looks at the path or the files, so someone who
    isn't an administrator gets none of the app's bytes and learns nothing of what its build holds. Which paths are
    files it decides by the path alone: anything under `assets/`, or a last segment with an extension the builds use.

    | Request | Signed out | Signed in, not an administrator | Administrator |
    |---|---|---|---|
    | A page | The return path stored, and a redirect to `login.jsp` | `403` with the User app's own `index.html`, which draws its no-access panel there (or a line of text if the User app isn't built), and the return path forgotten | `200`, `index.html` |
    | A file | `401`, empty | The same `403`, whether the file is there or not | `200`, or `404` |

    A check that fails, as when the database doesn't answer, lets nobody in and tells nobody they've lost access:
    every path is the same `503`, `no-store`, with a line of plain text to try again, and the return path is kept.
    Hashed files are `private, max-age=31536000, immutable`, so no proxy keeps them for someone else, and the page
    and every refusal are `no-store`, set explicitly, as LogicalDOC's `CacheControlFilter` would otherwise keep a
    refused `.js` for ten minutes. Its policy is the User app's without PDF.js's worker and the
    email's frame: `worker-src 'none'; frame-src 'none'`. Its answers set their status, never send an error, so
    Tomcat's error page never replaces them.
  - **Where a sign-in lands.** LogicalDOC's `login.jsp` sends the password form, as `j_successurl`, to the HTTP
    session's `RequestedUrl`, which it writes unescaped into a script. Three pieces of `healdan-core`'s `web` package
    fill it. `ReturnPath` is the return path the two servlets store: a path under `/app/` or `/admin/` of letters,
    digits and `/ . _ ~ % - ? & = + *` only, with no `//` and at most 2,000 characters, or else `/admin/` for one
    under `/admin` and `/app/` for any other, so nothing that could end the script's string gets in.
    `LoginLandingFilter` (`@WebFilter("/login.jsp")`) sets it, when no return path is waiting (one into either app),
    to where Start In (`ui.start`) says, `Landing.page()`: `/app/` or `/frontend.jsp`. With `?switch` it does
    nothing, so `login.jsp`'s own clearing of the value still works. `SignInResource` sends a provider's sign-in to
    the same `RequestedUrl` under `login.jsp`'s conditions, or to `Landing.page()`.
  - **Its headers.** Every answer carries a Content-Security-Policy that allows only the server's own address, with
    no inline script or style, and `nosniff` and `Referrer-Policy: same-origin`.
  - A server built without the apps answers `503` at `/app/`, saying the User app wasn't built into it, and so does
    `/admin/` an administrator, for the Admin app.
  - An `artifactItem` in `logicaldoc-webapp/pom.xml` puts its zip in the WAR. That and the root `pom.xml`'s
    `<module>` are the only upstream files it edits.
- **Its own tables.** Flyway owns the tables whose names start `hd_`, and only those. A change ships as a new
  script under `healdan-core/src/main/resources/healdan/db/migration/`, never as an edit to one that has shipped.
  Healdan never changes the structure of LogicalDOC's `ld_` tables.
  - **A folder of scripts for each database.** `HealdanMigrator` reads which database it's on from the connection and
    runs the scripts in `mariadb/`, the stack's, or in `postgresql/`, the tests'. It refuses any other database, MySQL
    included, with `Healdan's tables support MariaDB and PostgreSQL only; found …`. The `postgresql/` scripts are
    `mariadb/`'s translated one for one, with the same versions and names, so a version means the same on both. Until
    the stack moves to PostgreSQL, when `mariadb/` goes, a new migration goes into both folders. The history table,
    `hd_schema_history`, starts with Flyway's version `0` baseline, as LogicalDOC's tables are there first.
  - `hd_email` indexes email: a row for each email document, with its Message-ID and the SHA-256 of it that
    lookups search by. `EmailDocumentListener` adds the row when an email is filed, from the Message-ID it read
    from the file, or from the Message-ID property the document came with when the file gives none (an `.eml` or
    `.msg` only: another document's property counts for nothing). A check-in
    replaces it with the new version's, and takes it away when the new version is no message. `EmailIndex` answers
    which documents are copies of a message, comparing the Message-ID exactly. The index, not the Message-ID property, is what Healdan trusts, as LogicalDOC's own screens can edit
    the property.
  - The row holds no matter. A message has few copies, so whoever asks finds each copy's matter afresh. A lookup
    joins `ld_document`, so it leaves out deleted documents. The row of a document deleted for good stays, and no
    lookup sees it.
  - A background thread, `EmailIndexWorker`, gives the emails filed before the index existed their row and their
    Message-ID property. It starts a minute after the server, reads 50 emails at a time in the order of their ids,
    and writes the id of the last one to `email.index.cursor` in `hd_setting`, so a restart carries on from there.
    It reads deleted documents too, so that a restore finds the row, and leaves shortcuts out. It writes the
    property directly, so no version is made and no listener runs. An email it can't read gets nothing, and is
    logged by its document id.
  - When the first pass has read them all it writes `email.index` = `done`, which only records that. Every start
    scans on from the cursor, so an email filed since whose row is missing (the listener logs a row it can't add
    and goes on) gets it at the next start. To read every email again, delete the `email.index` and
    `email.index.cursor` rows from `hd_setting` and restart.
- **The Trash** records each delete, and moves what a delete took through three states, in `healdan-core`'s
  `com.logicaldoc.healdan.trash`. The Admin Guide's [The Trash and Removed
  Items](../../admin/the-trash-and-removed-items/) has what an administrator sees.
  - **`hd_trash` and `hd_trash_member`** (`V11__trash.sql`) are the record. `hd_trash` has a row for each delete: its
    kind (`document` or `folder`), the top item's id, its name and doc #, the folder it was deleted from by id and by its
    path's names at the time, the matter it was in, the counts of what it took, and who deleted it and when.
    `hd_trash_member` has a row for each folder and document the delete took, the top item included, with its **state**
    (`deleted`, `removed`, `restored` or `destroyed`, from `TrashState`) and who changed it and when. State is kept for
    each member, so an action on an item (restore, remove, destroy) takes only the members in the state it acts on, and
    a member restored on its own and deleted again belongs to a new item. The item is in the Trash while its top member
    is deleted, and removed while it is removed. The rows are never deleted: after a restore or a destroy they are the
    record, and keep the names so it reads without LogicalDOC's rows. Times are written from the JVM's clock.
  - **`TrashRecorder` records on every route.** `HealdanHooks.beforeFolderDeleteTree` runs first in a folder delete's
    transaction and records the folder's item from the live tree it already reads; `TrashDocumentListener`
    (`healdanTrash`, position 170) records a document when a store sets it from live to deleted, which `HibernateDocumentDAO.delete`
    does on every route; `TrashFolderListener` (`healdanTrashFolders`, position 140) records a folder deleted through
    `HibernateFolderDAO.delete`, which CMIS's `deleteObject`, a merge's emptied source and `FolderTool.delete` call
    without the hook. A recording that fails vetoes the delete, so no row is ever left in the Trash with no item.
  - **Restoring and removing.** `TrashRestore` works out the tree a restore brings back, and a hook gives it to
    `HibernateFolderDAO.restore`; `TrashRemoval` writes removals, with who and when. A member is taken only while its
    own row says the state the member does.
  - **`TrashDestroyer`**, started by `TrashStartup` (a `HealdanStartListener`, on a daemon thread of its own, at 02:47
    server time, never in the first 10 minutes after a start), destroys the members removed more than `trash.removedDays`
    before. Its design is in the folders and Trash spec, §5.4, and the Ruling it cites:
    - Each document is destroyed through `DocumentManager.destroyDocument` in a transaction of its own, with the
      member's state written first, so that nothing but the commit follows the store's hold of its files, and a
      failure undoes only that document's destroy, its files going back in their places. A failure leaves that
      document and the rest of its item for the next night, and the other items go on, whatever it failed with but
      a `VirtualMachineError`: a class missing from a plugin's zip fails only its item. Then the item's folders go in
      one transaction.
    - It runs as `_system`. The `trash-system-destroy` seed gives `_system`'s group read on the Destroy Documents menu
      (-9), so `destroyDocument`'s menu check passes whether or not the `admin` account exists or is enabled.
    - It keeps a folder removed while it is a matter's own folder or a Store, while a row that isn't destroyed has it as
      its folder or parent, while a folder not yet destroyed (live or deleted) takes its security from it, or while a live
      alias points to it. `ld_securityref` has no index, and Healdan doesn't change CE's tables, so it reads which
      folders take their security from an item's folders once an item, whatever their number, in the transaction that
      destroys them. Only a live shortcut keeps its document, and only a live alias its folder: another item's shortcuts
      and aliases go with what they point to, their members there marked destroyed.
    - It deletes the folder's rows in `ld_folder_ext`, `ld_foldertag`, `ld_folder_acl` and `ld_folder_store`, which don't
      cascade, folder bookmarks, the aliases pointing to it, and Healdan's `hd_local_value` and `hd_searchfolder` rows,
      and clears a user's default workspace that names it. A destroyed document's `hd_local_value` and
      `hd_preview_job` rows go too.
  - **The seeds** are `trash-record-existing`, which makes items of what was deleted before the release (see the Install
    manual's [Upgrading](../../install/upgrading/#what-was-already-in-the-trash)), and `trash-system-destroy`.
  - **The setting** is `trash.removedDays` in `hd_setting`, 1 to 365 and 30 by default
    (`HealdanSettings.trashRemovedDays()`), set on the Classic Interface's Healdan Settings tab.
  - **The hooks** (`HealdanHooks`, which `healdan-core` implements in `HealdanHooksImpl`) are `folderRestoreScope`,
    asked at the top of `HibernateFolderDAO.restore`; `removeFromTrash`, called by the two RPC services' `deleteFromTrash`
    methods; and `emptyTrash`. Each has a default that keeps LogicalDOC's own SQL, so a build without `healdan-core`
    behaves as before. `HealdanTreeChecks.movedTrees` and the webapp's `HealdanRpcChecks.movedTrees` check Move on
    every live folder and document of a moved tree, for the web services' and the Classic Interface's moves; WebDAV's and
    CMIS's moves reach the same check through `RulesFolderListener`.
  - **The upstream files it edits** are small hooks into this code, each marked `// Healdan:`, each with its test in
    `UPSTREAM_TESTS`:
    - `HibernateFolderDAO.deleteTree` takes only live rows (`ld_deleted = 0`) in its document, delete-user and alias
      updates, so a folder delete never takes an item already in a colleague's Trash or removed; and
      `HibernateFolderDAO.restore` takes only the ids the hook names (`HibernateFolderDAOTest`).
    - `DocumentServiceImpl.deleteFromTrash` and `emptyTrash`, and `FolderServiceImpl.deleteFromTrash`, ask a hook
      first (`HealdanTrashHooksTest`).
    - `FolderServiceImpl.move` calls `HealdanRpcChecks.movedTrees`, and the web services' `SoapFolderService.move`
      `HealdanTreeChecks.movedTrees` (`RpcPermissionGapsTest`, `SoapPermissionGapsTest`).
    - WebDAV's `ResourceServiceImpl.folderRenameOrMove` and `renameFolder`: a move that also renames moves through
      `folderDAO.move`, so the folder-move rules see it, then renames (`ResourceServiceImplTest#testMoveFolder`).
    - `DocumentManager.destroyDocument` has `where ld_id = ?` on its first lookup, which had none, so each destroy wrote
      another document's name into another folder's history, and its `ld_readingrequest` block is gone: LogicalDOC's schema
      has no such table, and the failed statement made a transaction around the destroy roll back
      (`DocumentManagerTest`). It fixes the Classic Interface's Destroy Documents too.
    - The fork-owned `HealdanHooks`, `HealdanTreeChecks` and `HealdanRpcChecks` gain the methods above.
- **The Admin app's records** are `healdan-core`'s, in `com.logicaldoc.healdan.admin` and the packages under it,
  and its routes are the admin API's (see `healdan-api` above).
  - **`hd_admin_event`** (`V12__admin.sql`) is the administration record: a row for each administrative change, with
    when (the JVM's clock, to the millisecond), who (their id and username), from where, the Admin app's area
    (`AdminArea`), the action, such as `client.renamed`, what it acted on (its kind, id and name, a client and a matter
    named with their numbers, as `1001 – Saltmarsh Shipping Ltd` and `1001.1 – Charter Dispute`), and a JSON detail of
    the fields it changed, each as `{"before":…,"after":…}`, in the order of their names. A changed secret is
    `{"secret":true}`, with neither value (`AdminEvent.Change.secret()`), so no secret is ever in it. A username, an
    address or a name too long for its column is cut to fit, rather than fail the change; the detail is kept whole.
  - **From where** is the request's address, as Tomcat believes it (a trusted proxy's `X-Forwarded-For` already
    applied), which `AdminFilter` puts in `AdminContext` for the request. A change made outside an admin request, as
    from the Classic Interface, has none.
  - **`AdminEvents` writes it.** `record` writes in the caller's transaction, if one is open, so a change that's one
    database transaction and its record stand or fall together, and a record that fails fails the change.
    `recordAfter` is for a change that isn't one transaction, such as one with network or file steps: it writes once
    the change has succeeded, inside a savepoint when a transaction is open, and never throws. If the record fails,
    the change stands, and the failure is logged at error level, naming the action and the target's kind and id,
    never a value.
  - **The client and matter services record their own changes.** `ClientService`'s create and rename and
    `MatterService`'s create and edit are transactions (`@Transactional`) that record `client.created`,
    `client.renamed`, `matter.created` and `matter.updated` inside them, so the Classic Interface's client and matter
    changes are recorded too, and so are the clients and matters setup steps make, such as the smoke stack's. A
    create records every field it set, and an edit only those whose values changed, an owner by full name. A rename
    or an edit that changes nothing isn't recorded.
  - **`hd_error` and `hd_error_hour`** (`V12__admin.sql` too) hold the errors the server logged, by fault. `hd_error`
    has a row for each fingerprint: the level, the area, the logger, the latest message, its stack and the documents
    it names (the ids a message names as `doc 10412` or `document id 10412`), how many times it was seen, when first
    and last, and whether and by whom it was resolved. `hd_error_hour` counts each fault's errors in each hour, in
    UTC, for the time windows. `ErrorStore` reads and writes them.
  - **Errors are caught as they're logged** (`com.logicaldoc.healdan.admin.errors`). At a real start `ErrorWriter`, a
    `HealdanStartListener`, attaches `ErrorCapture`'s Log4j appender in code, so `log.xml` isn't edited, for `ERROR`
    and above: to the root logger, and to every logger whose additivity is false, such as the Indexer's, whose events
    never reach the root (36 in the webapp's `log.xml`). When the logging configuration is reloaded, a listener on the
    context attaches a new instance, but only if the root lacks one, as attaching makes Log4j tell the listener
    again. It uses Log4j's own classes, so `healdan-core` takes `log4j-core` as `provided`: the WAR ships it.
  - **The appender never blocks, logs or reaches the database.** On the logging thread it copies the event (its time,
    level and logger, the class and line it was logged from when the configuration records that, the exception's
    class, the message up to 4,000 characters and the stack up to 16,000, with any NUL character taken out, which
    PostgreSQL refuses in text) onto a queue in memory that holds 1,000, and counts what doesn't fit. It leaves out
    what its own package's loggers log and anything logged on the writer's thread, so a failure to write the errors
    can't feed itself.
  - **One thread, `healdan-error-writer`, writes them.** It takes the queue's errors in batches of 100, oldest first.
    An error it can't write stays at the head of its batch while the queue fills behind it, so while the database is
    down nothing is lost but what doesn't fit; the thread logs a warning, waits a second, doubling up to a minute, and
    tries again, and never stops for a failure. An error is left out only when it's refused itself three times in a
    row, so that it can't hold up the rest: when the database refuses its values (Spring's
    `DataIntegrityViolationException`, from SQLState classes 22 and 23, such as a value too long), or when it fails
    outside the database. A connection, a timeout, a lock, a full disk or a read-only database fails every error
    alike, and never leaves one out. The writer counts what it left out (`skipped()`), and the capture what didn't fit
    on the queue (`dropped()`).
  - **Grouping.** A fault's fingerprint (`ErrorFingerprint`) is the SHA-256 of the exception's class, where it was
    logged (the class and line, or the logger's name), and the message with its numbers and quoted names taken out.
    When the class it was logged from isn't the one the logger is named for, or nested in it, the logger's name
    counts too: LogicalDOC's `Task.run` logs every scheduled task's failure from one place, through each task's own
    logger, so each task's failure is a fault of its own. Its area (`ErrorAreas`) comes from the logger's package or
    class: Previews, Storage, Search Index, Email, Sign-In, Background Jobs, or Server for any other. As one thread
    writes, two writes of one fault never run at once, so a write updates the fault's row first and inserts one when
    no row changed, with no statement expected to fail. A fault seen again after it was resolved is unresolved, and an
    older error written late only counts.
  - **Pruning.** Ten minutes after the start, then once a day, the writer deletes the hours' counts older than 31 days,
    and the resolved faults not seen for 90 days, with their counts. A fault nobody has resolved stays, however old.
  - **The Dashboard's Usage** is worked out by `UsageSummary` (`com.logicaldoc.healdan.admin.dashboard`), a
    `HealdanStartListener`, on a daemon thread of its own, `healdan-admin-summary`: at the start, then 15 minutes after
    the last one ended, never on a request's thread. It keeps one summary in memory, which a request reads with no
    query: only the count of people signed in is made at each request, from the server's sessions in memory. A
    refresh that fails keeps the last summary and says when it failed, and stopping the server during one logs no
    error, so it never shows as a fault. It counts a matter's documents through the client and matter numbers stamped
    on them, in `ld_document_ext`, so no folder is walked, and a Store's documents are its matters'. Its days are the
    server's, in its time zone. The storage port names each Store's backend, and one that fails leaves the backends
    out and the rest whole. Tests call `refreshNow()` themselves, as no test makes a real start.
  - **The Search Index's counts** (`IndexCounts`, `com.logicaldoc.healdan.admin.index`) tell a document that failed
    from one that waits (Admin app spec §9.6), among the documents not in the Trash, shortcuts left out. A document
    has failed when it waits to be indexed (`ld_indexed` is `TO_INDEX`), isn't archived, and its latest
    `event.indexed` or `event.indexed.error` row in `ld_history` is the error, whose comment says why.
    `DocumentManager.index` writes those rows, and LogicalDOC's Indexer goes on trying such a document on its
    schedule, with an error row each time. A document waits when the Indexer will take it (`TO_INDEX` or
    `TO_INDEX_METADATA`, not archived) and it hasn't failed. Its latest event is chosen by one look through its own
    rows, on `ld_history`'s index by document: the latest time first, a row with no time counting as older than any
    with one, and rows at the same moment, as MariaDB's whole seconds give, by the order they were written in.
    `counts()` gives the indexed, the waiting and the failed in one pass, and `failed(offset, limit)` lists the
    failed, latest error first, each with the matter stamped on it.
  - **What the background jobs last did** (`com.logicaldoc.healdan.admin.jobs`, Admin app spec §9.5). Each of
    Healdan's jobs is a `HealdanJob` bean, with a name, a label and whether it can run now, and reports to
    `JobStatus` under its name: `started` before a run, `finished` or `failed` after it, each with its next run, and
    `scheduled` when it knows its first run before making it. `JobStatus` keeps each job's last start and end, how it
    ended, its error (the exception's class and message, or the text the job gives, at most 1,000 characters, never a
    stack) and its next run, in memory only, so it starts again with the server, and reporting never throws, so it
    can't change what a job does. The previews and the email index report from each round of their workers
    (`runOnce`), `healdan-storage`'s mover from its sweep and its `runOnce()`, and the Trash destroyer and the
    storage retests, purge, alert and nightly check from their schedulers, so a direct call to one of those last, as
    a test makes, reports nothing.
  - **`Jobs`** lists Healdan's eight jobs in a fixed order (`preview`, `email-index`, `trash-destroyer`,
    `storage-mover`, `storage-retest`, `storage-purge`, `storage-alert`, `storage-nightly-check`; the storage ones only
    where `healdan-storage` is, and switched off on an `FSStore` server, where none runs: `HealdanJob.enabled()`, which
    `healdan-storage`'s `StorageJob` answers from `StoreTracking.active()`), then LogicalDOC's six scheduled tasks from
    `TaskManager`: the Indexer, Index Optimizer, Tags Processor, Digest Processor, Statistics Collector and Paths
    Calculator. A task's last start is its schedule's previous fire time, its last end that plus its last duration once
    it isn't running, and its next run the schedule's next fire time, so a task shows no last end while it runs.
    LogicalDOC keeps a task's last error with no getter, so its error is the latest one the captured errors have from
    the task's logger since its last start: any error a task logs while it runs fails it, not only one that ends the
    run, and `Task.run`'s own "The task is stopped" and "The task gets interrupted" don't count. The email index is
    `done` once its first pass has ended (`email.index` in `hd_setting`), unless its last run failed.
  - **A job is overdue** when it's switched on, isn't running, and its next run is more than twice its interval in
    the past, and an hour at least (`JobView.overdue`). Its interval is from the run that set the next run: for
    Healdan's jobs from their last end, for LogicalDOC's tasks from their last start. A job with no next run, or
    switched off (a LogicalDOC task an administrator disabled, or a storage job where storage backends aren't in use),
    never is.
  - **The Dashboard's Needs Attention and Server Health** come from `DashboardService` (`admin.dashboard`), each
    from its own source:

    | Code or tile | Source |
    |---|---|
    | `files-stuck`, `backend-unreachable`, Storage Backends | the storage port's overview: each Store's stuck files and those with no Store; a backend `unreachable` or `failing`. With no port there's nothing to check, unless LogicalDOC's `store` is `HealdanStorage`, when the source fails; with the port saying storage backends are off, nothing, and the tile is `off` |
    | `index-failed`, Search Index | `IndexCounts.counts()` |
    | `errors` | `ErrorStore.unresolvedSince`: the faults not resolved that were seen in the last 24 hours, each counted once |
    | `previews-given-up`, Previews | `PreviewJobs.countWaiting()` and `countGivenUp()` |
    | `job-failed`, `job-overdue`, Background Jobs | `Jobs.all()`: a job switched on whose last run failed, and one overdue |
    | `disk-low`, Disk | the volumes of `store.1.dir`, `index.dir` and `conf.logdir`, each once; one more than 85% used (`DISK_LOW_PERCENT`, a constant), used being its total less what the server can still write. The tile's bar is the fullest's |
    | Server | the JVM's time up, and `product.release` |
    | Database | JDBC's product and version, and its size: PostgreSQL's `pg_database_size(current_database())`, or the sum of MariaDB's `information_schema.tables` for the connection's catalog, never `database()` |
    | Memory | `Runtime`'s heap used of its maximum |
    | Sign-In | each provider people can sign in through (`SignInProviders.enabled()`), asked whether it answers (`ProviderClient.answers`, a fresh fetch of its discovery document, a failure remembered for 30 seconds), at most every 5 minutes per provider |
    | Outgoing Email | `OutgoingEmail.isSetUp()`, and an error LogicalDOC's mail sender (`EMailSender`) logged in the last 24 hours that nobody has resolved |

    Each source is kept for a minute, the database's for five (`Cached`): then the next read works it out again, once
    however many ask, on a thread of its own, `healdan-admin-dashboard`, never a request's, and reads meanwhile get
    the last answer while it's younger than twice that. A request waits for a source only when it has no answer that
    young, at most 5 seconds for all of them, after which the source is still being worked out. A source that fails,
    or is still being worked out, puts its codes in `unchecked` and gives its tiles `problem`, a fixed line, with
    `attention` for status, and the rest are whole; the failure is logged at warn with its class only, its stack at
    debug. Each item's and tile's link is the screen that fixes it, from `DashboardService.Screen`, a table of the
    Admin app's addresses that is the Classic Interface's, `/frontend.jsp`, until each screen ships. Tests call
    `read()` after `forget()`, which drops what's kept.
- **One-time setup steps** in `com.logicaldoc.healdan.seed` create what Healdan needs inside LogicalDOC, such as
  the Everyone group and the Healdan profile. Each records that it ran in `hd_setting`, and never runs again, so a
  new default ships as a new step.
- **LogicalDOC's parsers are filled at the start.** LogicalDOC fills its `ParserFactory` the first time something
  parses, and two threads doing that at once, as the Indexer's two do after a start, can leave it without some file
  types for good. Without `pdf`, its `CatchAllParser` converts a PDF to PDF and asks for a parser again, each time on
  a new thread that waits for the next, until the server can start no more threads. So at a real start, after the
  setup steps and before its workers start, `HealdanStartup.start()` fills the factory once, with every plugin's
  parsers. LogicalDOC's scheduler, and with it the Indexer, starts only once the Spring context has started, and
  requests come only once Tomcat has deployed the webapp. A filled factory is never empty again, so LogicalDOC never
  fills it again. `HealdanStartupParsersTest` checks it.
- **`HealdanHooks`** in `logicaldoc-core` lets the few places in LogicalDOC that write without telling its
  listeners ask Healdan first. Every refusal throws `HealdanVetoException`, which the document and folder DAOs roll
  back on.
- **Licence headers.** Every source file Healdan writes opens with two SPDX lines in its own comment syntax, with
  the year the file was created:

  ```java
  // SPDX-FileCopyrightText: 2026 The Healdan Project
  // SPDX-License-Identifier: LGPL-3.0-only
  ```

  They go below a shebang, the `Dockerfile`'s `# syntax=` line and an HTML page's doctype. LogicalDOC's files never
  get them, and nor do Healdan's files copied from LogicalDOC or Hextra, or its Flyway migrations: a new line
  changes a script's checksum, and an existing database then refuses to start.
- **Upstream edits** are kept small, and each milestone's design spec lists every upstream file it edits, with the
  reason. Signing in through a provider edits three of LogicalDOC's files, one in its user code and two in its GUI:
  - `UserSource` has a fourth value, `OIDC`, for an account linked to a sign-in provider. It's appended after `SAML`,
    because the database stores each source as its position in the list: a value inserted earlier would move the
    sources of every account after it. `UserSourceTest` pins the numbers, and CI runs it on every pull request that
    changes code (one that changes only docs skips the test shards), so an upstream sync that moves them fails.
  - `UsersPanel`, the Users list in the admin screens, has a `case` for `OIDC` in its Source column. Without it, a
    provider account shows as `DEFAULT`.
  - `LoginPanel`, the sign-in page's panel in the GUI's `login` module, has one line in `initGUI` that hands the
    page's panels to `HealdanSignInButtons`. That class is in `logicaldoc-gui`'s `common` module, under
    `com.logicaldoc.gui.common.client.healdan`, because the `login` module can't see the `frontend` module's Healdan
    package. It asks `GET /api/v1/server` for the providers, and with none it does nothing, so the page stays the
    password form. With some, it adds a button for each above the form, folds the form behind a link, and shows the
    message for a refusal that sent the person back to the page. `MobileLoginPanel` runs the same method, so the
    mobile page gets them too.
