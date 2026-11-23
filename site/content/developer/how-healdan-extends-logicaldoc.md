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

## PostgreSQL

Healdan runs on PostgreSQL 18 only, and its Java tests run there too (see
[The Test Database](../tests/#the-test-database)). LogicalDOC ships no PostgreSQL variant of its schema scripts, so
its tables are made by its generic ones. These make LogicalDOC's code, and Healdan's, work on PostgreSQL:

- **Booleans are bound as integers.** LogicalDOC maps Java `boolean` fields onto `int` columns, and PostgreSQL
  refuses a boolean for an `int` column. `healdan-core`'s `hibernate.properties`, at its class path's root, sets
  `hibernate.type.preferred_boolean_jdbc_type=INTEGER`. Hibernate reads it when it starts, with no upstream edit, and
  binds every Java `boolean` as an integer. `BooleanBindingTest` checks the setting is in force,
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
  `Double`, or text, goes to the driver, as it did before. `HealdanTypedReadsTest` checks it, in `UPSTREAM_TESTS`,
  and `TypedReadsOnPostgresTest` that an edit of a user is kept.
- **No statement is expected to fail inside a transaction.** On PostgreSQL a statement that fails ends its
  transaction, whatever the Java around it catches.
  - An insert that may find its key taken, as by another transaction since the caller's read, says `on conflict`
    with the key it means, so it fails nowhere and PostgreSQL's log has no error for it: `PreviewJobs.add` and
    `healdan-storage`'s `StoredFiles.recordWrite` and `HeldCopies.hold` then do nothing, and `EmailIndex.put` updates
    the row. A unique index added later on other columns still fails such an insert, rather than being taken for a
    duplicate.
  - Work that isn't expected to fail, but mustn't fail its caller if it does, runs inside a savepoint, through
    `healdan-core`'s `Savepoints` (`com.logicaldoc.healdan.util`). A failure, whatever it is, an `Error` included,
    rolls back to the savepoint, and the caller's transaction goes on; outside a transaction the work runs as it is.
    `Savepoints.inTransaction` says which it is.
  - The listeners that log and swallow a failure inside LogicalDOC's save run their statements inside one, so the
    save is kept: `EmailDocumentListener` and `PreviewDocumentListener`. `PreviewDocumentListener` keeps its check of
    the event and the file name, which is Java alone, outside, as a savepoint costs two round trips to the database
    and its methods run on every save. `EmailDocumentListener` puts the whole of its `try` inside. The two
    favourite-names listeners need none: by their `afterStore` the row before the store is in the store's dictionary
    or the session's map, so they don't reach the database there.
- **A lock waits 50 seconds at most.** The app's role has `lock_timeout` set to `50s` in its database, so every
  connection it opens, Tomcat's pool and Flyway's included, gives up a lock it has waited that long for, and the
  statement's transaction fails. A lock cycle that nothing else ends, such as the one `healdan-storage`'s `FileLocks`
  describes, ends that way, and its callers retry or mend it.
- **Messages don't quote rows.** PostgreSQL's driver puts the server's detail into each `SQLException`'s message, and
  for a failed check or a duplicate key the detail holds the row's values. The stack's JDBC URL ends
  `?logServerErrorDetail=false`, which keeps them out, as Healdan's log never holds file names, subjects or comments.
  PostgreSQL's own log still has the detail.
- **Text keeps everything but NUL.** PostgreSQL stores any character but NUL, emoji included, and a lone surrogate
  isn't a character at all. `healdan-core`'s `StorableText` checks for those two (`holds`). Where a person types the
  text, such as a matter's or a device's name, Healdan refuses it, saying it has characters Healdan can't keep, and
  the API answers `400 bad-request` for it in a query parameter or anywhere in a JSON body (see the
  [API](../api/#errors)). Where Healdan takes the text from a file or a sign-in provider, such as an email's headers
  or a person's name, `clean` puts a space for a NUL and U+FFFD for a lone surrogate, and `cut` shortens text to a
  column's length without splitting a surrogate pair. A Message-ID keeps its own rule (see
  [Desktop Clients](../desktop-clients/)).
- **Eight of LogicalDOC's columns are widened.** LogicalDOC's generic script gives eight text columns a `varchar`
  that PostgreSQL enforces, where its MariaDB script has `mediumtext`, so an avatar over 4,000 characters wouldn't
  save. Healdan's migration `V13__widen_logicaldoc_text.sql` makes them `text`: `ld_user.ld_avatar`,
  `ld_systemmessage.ld_messagetext`, `ld_filler.ld_automation` and `ld_automationbefore`, `ld_folder.ld_tile`,
  `ld_menu.ld_automation`, `ld_generic.ld_text1` and `ld_legal.ld_content`. It's the one change Healdan makes to the
  structure of LogicalDOC's tables.
- **SQL given to LogicalDOC's DAO helpers never uses `::`,** which its `NamedParameterStatement` reads as a
  parameter. Write `cast(x as text)`.

### The Start-Up Tool

In the image, `docker/tools`' `DockerInit` runs before Tomcat on every start. Before it writes anything, to the conf
volume or to the database, it stops, changing nothing, when an install would start empty beside its own data
(`StartGuard`): its conf volume names MariaDB and PostgreSQL holds no move from it, or doesn't answer within 15
seconds; the database holds a move that didn't finish, whatever the conf volume names; or the database is empty and
Original's folder already holds an install's files. Then it:

- sets the database settings in `context.properties` on every start: `jdbc.url`, from `DB_HOST`, `DB_PORT` and
  `DB_NAME`, with `?logServerErrorDetail=false`, and `jdbc.driver`, `jdbc.dbms` (`postgresql`, as LogicalDOC's code
  spells it) and `hibernate.dialect`, which it reads back, so a conf volume a MariaDB install wrote is put right at
  its first start after the move;
- checks the server and the database (`DbChecks`): it refuses a PostgreSQL older than 18, a database that isn't
  UTF-8 and a role that can't create tables in its schema, and warns of a newer major than 18 and of a collation
  that doesn't sort by code point;
- gives the app's role its lock timeout in the database, `alter role … in database … set lock_timeout = '50s'`, and
  reads it back on a new connection;
- makes the schema on a fresh database, or checks LogicalDOC's tables on an existing one.

It asks whether a table exists with `to_regclass`, never by reading it, so a start leaves no `ERROR` line in
PostgreSQL's log. The move from MariaDB, `MoveFromMariaDb` beside it, shares its checks of the server and the
database (see [The Move's Test](../tests/#the-moves-test)). The Install manual's
[Troubleshooting](../../install/troubleshooting/) has its messages. An install run outside Docker gets none of this:
see [Building from Source](../building-from-source/#running-the-war-outside-docker).

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
  - **Clients, matters and folder access** are `AdminClientsResource`, `AdminMattersResource` and
    `AdminFolderAccessResource`, over `healdan-core`'s services (see the Admin app's records, below). Their lists
    read every matching row in one query, then sort and page in Java, with the figures from `UsageSummary`, but for
    the Matters list sorted by number, its own order, which the database pages, so a page reads only its own
    matters. A create
    whose insert a unique index refuses (PostgreSQL's SQLState `23505`), as the second of two creates of one number
    at the same moment is, goes through `NumberRaces`, which asks the number's check again once the first has
    committed, so the answer is the used number's `409 refused`. A matter's move and delete are `FolderChanges`'
    `moveMatter` and `deleteMatter`, the public folder routes' move and delete with the same locks and checks, but
    for the refusal of a matter's own folder, and with their refusals answered as `409 refused`, the same sentence
    and no `holds`. These two and `FolderChanges`' other entry points for the Admin app, `deleteWorkspaceFolder`,
    `deleteTemplate` and `deleteStore`, refuse anyone but an administrator first, `403 admins-only`, as their routes
    already do, since every admin service checks for itself. `AdminFolderAccessResource` is rooted at exactly
    `@Path("/v1/admin/folders")`, with the id in its methods' paths: CXF looks for a method only in the root classes
    whose path is the best match's, so every route under `/v1/admin/folders/{id}` has to be in a class with that same
    root, or another class's would answer `405`.
  - **Stores and groups** are `AdminStoresResource` (`STORES`, rooted at exactly `/v1/admin/stores`), over
    `healdan-core`'s `StoreService`, and `AdminGroupsResource` (`PEOPLE`), which reads `ld_group` itself: live groups
    of the caller's tenant, LogicalDOC's ordinary groups only, without the `_user_` group each person has. A new
    Store's name is checked by `FolderRenames.name`, the folder routes' rule, whose form overload adds its errors to
    the other fields' so one `422` names them all. `StoreInfos` makes a Store's row for the list and its page: its
    live matters in one grouped query, its documents and bytes from `UsageSummary`, who can read it from
    `StoreService.access`, and its backend and files from one read of the storage port's overview, null without the
    port, while storage backends are off, and when the overview can't be read: that's logged at warn with the
    exception's class alone, its stack at debug, and the list is answered all the same, as New Matter's Store menu
    needs it.
  - **A Store's rename, delete and restore.** `PATCH` and `DELETE` on `/v1/admin/stores/{id}` refuse LogicalDOC's
    Default workspace, the Templates Store and the root first (`StoreService.fixedFolder`, a sentence for each,
    `409 refused`), and answer anything else that isn't a live Store `404`. `POST …/restore` answers anything that's
    no Store, live or in the Trash, `404`, those three included, and a live Store `409 refused`, as it isn't in the
    Trash. The rename is `StoreService.rename` and the restore `StoreService.restore`, each in its own transaction.
    The delete is `FolderChanges.deleteStore`, beside `deleteMatter`: after the check for an administrator
    (`403 admins-only`), the same checks and delete (`deleteForAdmin`: Delete on everything inside, a document checked
    out or immutable refused, then `trashTree`), so the Store and its matters go to the Trash as one item, which it
    answers. Its files stay on the Store's backend while it's there, as its restore needs them. A Store's create,
    rename and restore hold the root's folder lock, through `FolderChanges.withFolderLocks`, taken in the resource
    around the service's transaction, as the folder routes hold the folder a name goes into, so two of them at once
    can't give one name twice under the root (`AdminStoresResourceTest#testTwoCreatesOfOneName`, `#testTheRootsLock`).
    A Store's delete holds the Store's own lock, as a matter's move into it and New Matter do, so no matter is put in
    a Store on its way to the Trash.
  - **The storage routes** are three classes, all `STORAGE`, over `StoragePort`, so each route's area is its record's:
    `AdminStoreStorageResource`, rooted at exactly `/v1/admin/stores` beside `AdminStoresResource` (CXF looks for a
    method only in the root classes whose path is the best match's), with a Store's backend, its switch and its stuck
    files; `AdminBackendsResource` (`/v1/admin/backends`); and `AdminStorageResource` (`/v1/admin/storage`), the
    settings and the files with no Store. Each checks what a form sends (`400`, `422`) before it asks the port, and
    records nothing itself, as the port records every storage change. A new Store's `backendId` is checked through the
    port before the create, and given after it (`setStoreBackend`, a transaction of its own, which needs the Store
    committed); a switch that fails in between, only in a race, leaves the Store on Original, logged at warn, and
    the answer's `backendId` is then Original's, so New Store says it was made on Original.
  - **`StoragePort`** (a `@Component`) finds the port by type, through a seam tests set to a fake and put back
    (`volatile Supplier<List<StorageAdministration>> ports`), as `DashboardService` and `UsageSummary` have. With none
    it answers `503 storage-unavailable` (`require()`). Its `run` turns the port's `SecretKeyMissingProblem` into
    `409 secret-key-missing` with `detail` the port's sentence and `sealed`, and leaves any other refusal of the port's,
    a `HealdanRuleException` with the Storage tab's sentence or the port's own, to `ProblemMapper`'s `409 refused`. A
    backend named in the path that the port doesn't give is `404`, as is any while storage backends are off, as the
    port gives none then. `StorageView` holds the contract's records; `AddedBackend` and `RecoveryKey` leave the key
    out of their `toString()`.
  - **A workspace's folders and the templates** are four classes, all `MATTERS`, each rooted at its own exact path for
    the same reason: `AdminWorkspaceResource` (`/v1/admin/workspaces`, the tree), `AdminFoldersResource`
    (`/v1/admin/folders`, beside `AdminFolderAccessResource`: New Folder, rename, move, delete, the values and New
    Search Folder), `AdminSearchFoldersResource` (`/v1/admin/search-folders`, a search folder's rule) and
    `AdminTemplatesResource` (`/v1/admin/templates`), over `healdan-core`'s `WorkspaceAdmin` and `TemplateAdmin`. Every
    route under `/v1/admin/folders/{id}`, the access routes' too, finds its folder through `WorkspaceRequests.folder`,
    `WorkspaceAdmin.workspaceFolder`'s one scope, and answers `404` outside it. Names go through `FolderRenames.name`,
    search folders' too, which refuses a character Healdan doesn't keep in a name rather than let the name be changed. A
    change that gives a folder a name holds the lock of the folder the name goes into, through
    `FolderChanges.withFolderLocks`, the public folder routes' locks: a rename its parent's, New Folder and New Search
    Folder the folder they're made in, a move both parents', in id order, and a template's create or rename the
    Templates Store's, and a rename or move checks, once it holds them, that the folder is still where it was. So two
    changes at once can't give one name twice in a folder: the locks are what keep the names apart. A folder's delete
    goes to the Trash through `FolderChanges.deleteWorkspaceFolder`, and a template's through `deleteTemplate`:
    `deleteMatter`'s checks and delete, without the refusal of a matter's own folder, each refusing for itself a folder
    its route doesn't take (`404` outside the workspace scope, or for anything but a template's own folder, and
    `409 refused` for a workspace's top folder through the folder route), and answering the Trash item.
  - **The people routes** are `AdminUsersResource` (`/v1/admin/users`) and `AdminGroupsResource` (`/v1/admin/groups`),
    both `PEOPLE`, over `healdan-core`'s `UserAdministration` and `GroupAdministration` (see the Admin app's records,
    below), which check every change's fields and rules themselves and record it. `PeopleRequests.run` answers what
    they refuse as the contract has it: a `FieldRefusal`, the form's fields each with its code and sentence, as
    `422 validation`; an `IllegalArgumentException` as `400 bad-request`, logged at error, as the routes check what a
    service would find malformed before they call it, so one is a fault; and a rule's `HealdanRuleException` is left to
    `ProblemMapper`'s `409 refused`. The routes check a body's members before the path (`checkGroupIds`, for a null
    or a group named twice, and a `PATCH` member of the wrong type), answer the path's `404` with the services' cheap
    `isPerson` and `isGroup`, and open no transaction, as each change runs its own. A `PATCH` fills the members it
    isn't sent from the person or the group as read, and sends the service the whole. A person's id in a path is
    digits (`{id: [0-9]+}`), and a group's may carry a minus (`{id: -?[0-9]+}`), as LogicalDOC's `publisher` group is
    `-10000`. `PeopleView` holds the contract's records, and its `CreatedUser` and `TemporaryPassword` leave the
    password out of their `toString()`.
  - **The Monitoring routes** are four classes, all `MONITORING`, each at its own exact root: `AdminErrorsResource`
    (`/v1/admin/errors`: the list, a fault, and its resolve and reopen, over `ErrorStore` and `ErrorReview`),
    `AdminLogsResource` (`/v1/admin/logs`: the logs, a log's lines and its download, over `LogFiles`),
    `AdminAuditResource` (`/v1/admin/audit`: a page and the export, over `AuditFeed` and `AuditCsv`) and
    `AdminJobsResource` (`/v1/admin/jobs`, over `Jobs` and `JobControl`), over `healdan-core`'s services (see the
    Admin app's records, below), which check their caller and record what they change themselves, so the routes record
    nothing. `MonitoringView`, `AuditView` and `BackgroundJobView` hold the contract's records, and cut a user name
    and password written in a URL from a fault's message and stack and from a job's error (`UrlCredentials`, below). A
    malformed parameter (a window, an area, an `unresolved` other than `true` or `false`, a position, a cursor, a
    zone, a day not of the years 0001 to 9999, or not written as `2026-11-22`) is `400`, before a path's `404`; a
    schedule's fields are `422`; and what a job doesn't allow now is `409 refused`, with `JobControl`'s sentence. A
    log's download and the export stream through `StreamingOutput`, both `no-store`: the log's files are checked, and
    the export's filters worked out and its first page read (`AuditCsv.open`), before the first byte, so a failure
    there is a problem, not a file cut short, and a `HEAD` of either reads no file and no row.
  - **The Search Index routes** are two classes, both `SEARCH_INDEX`, each at its own exact root:
    `AdminSearchIndexResource` (`/v1/admin/search-index`: the heading, the failed and waiting lists, the history, Run
    Indexer Now, and Try Again and Skip by doc #, over `IndexOverview`, `IndexHistory`, `IndexReview` and
    `JobControl`) and `AdminReindexResource` (`/v1/admin/reindex`: the preview, the start, the list, a reindex and its
    cancel, over `ReindexService`). Their path ids are digits (`{docNumber: [0-9]+}`, `{id: [0-9]+}`), so
    `/v1/admin/reindex/preview` never reaches `{id}`. `AdminReindexResource` reads its body as JSON nodes, so a member
    left out, one sent as null and one of the wrong type are told apart: a wrong type, a member for another kind and
    a doc # that isn't a whole number from 0 up are `400` before the target's `404`, a `kind` missing or unknown is
    `422`, and the service's field refusals, null target and rules follow. `SearchIndexView` and `ReindexView` hold
    the contract's records. Try Again's storage outage goes up to `ProblemMapper`, which answers it
    `503 storage-unreachable`.
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
    its download filter do nothing, and the Storage tab and the Admin app's Backends say storage backends are off.
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
  - **The Admin app reads and changes storage through a port.** `healdan-core` and `healdan-api` can't depend on
    `healdan-storage`, so `healdan-core` has an interface, `StorageAdministration`
    (`com.logicaldoc.healdan.admin.storage`), with plain records (`StorageRecords`), which `healdan-storage`'s
    `StorageAdministrationImpl` bean implements over the Storage tab's own service, `StorageAdmin`, unchanged. It's
    found by type (`Context.get().getBeansOfType`), as `HealdanStartListener`s are, so a server without the module has
    none.
    - **A method for each of `StorageAdmin`'s operations:** the overview; Test of the settings as typed and of a saved
      backend; add, save and remove; a recovery key's show, confirm and enter, and its history; a Store's switch, and
      the count of files it would move; the stuck files and their retry; and the settings. Besides them,
      `backend(id)` answers what a backend's page and its edit show; `backends()` every backend as `backend(id)`
      gives it, with the files with no Store, from one reading of the overview, and `exists(id)` whether a backend is
      there, counting no file, so a request reads the overview once at most, where a list of `backend(id)`s read it
      once a backend; `on()` and `settings()` whether storage backends are on and the settings as stored, counting no
      file, for the settings' routes; and `limits()` the settings' maxima. Each maps the records to the tab's beans and back, so the
      tab's rules, locks and sentences are the port's: a refusal is `StorageAdmin`'s `HealdanRuleException`, with its
      sentence.
    - **It checks three things itself, first.** Each change, and Show Recovery Key, refuses anyone but an
      administrator (`FilingRules.isAdmin`, the check `StoreService` makes), as every admin service checks for itself
      what its routes refuse: "Only an administrator can change the storage backends, a Store's backend or the storage
      settings, or see a recovery key." While storage backends are off, every change is refused with a sentence of
      its own, as the Admin app has no Storage tab to name: "Storage backends are off: this server keeps its files in
      LogicalDOC's own store, so its storage backends, its Stores' backends and its storage settings can't be
      changed." And a Store in the Trash, whose files stay on its backend for its restore, is named: `BackendDetail`
      lists them as `storesInTrash` (one query over the workspaces in the Trash, a Store with no backend of its own
      counted on Original, the Templates Store never), and the remove of a backend, but Original, and a save that
      would change its bucket, prefix or path refuse while there's one, naming each, before `StorageAdmin`, which
      would name it by its id: "Store Archive is in the Trash, and its files stay on this backend while it's there,
      so the backend can't be removed: restore the Store, give it another backend, then delete it again."
    - **Nothing secret in an answer.** No S3 secret, only whether one is stored; the access key ID, which an edit
      shows; a recovery key only in an encrypted backend's add (`AddedBackend`) and in `showRecoveryKey`'s answer,
      each recorded as a showing; and an S3 endpoint as its scheme, host and port alone. The records that hold an
      access key or a recovery key leave both out of their `toString()`.
    - **A missing secret key file** is `SecretKeyMissingProblem`, a `HealdanRuleException` with the tab's sentence
      and `sealed()`, how many stored values are sealed with the missing key. Nothing is written. The add and the save
      take `newKey`, the tab's question answered, to make a new key.
    - **It records each change** that succeeded straight after it, with `AdminEvents.recordAfter`, as none is one
      database transaction, in the `STORAGE` area: `backend.added`, `backend.changed`, `backend.removed`,
      `backend.recovery-key-shown`, `backend.recovery-key-confirmed`, `backend.recovery-key-entered`,
      `store.backend-changed` (the backend before and after, by name), `storage.stuck-retried` and
      `storage.settings-changed`. Each is aimed at its backend or Store by id and name, or, for the settings and the
      files with no Store, at the kind `storage` with neither. What changed is read from the rows before and after
      the change, so the values recorded are those stored. An access key or a secret that changed is
      `Change.secret()`, with no value, and a secret typed again the same isn't a change. A recovery key is never
      recorded: its showing, confirming and entering are actions with no detail, and an encrypted add records
      `backend.added` and `backend.recovery-key-shown`. A read, and a change that changes nothing (a save of what's
      there, a switch to the Store's own backend, a confirm of a confirmed key, a retry with nothing stuck), record
      nothing. The rows before are read outside `StorageAdmin`'s lock, so two administrators changing one backend at
      the same moment may each record the other's change.
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
    Hashed files are `private, max-age=31536000, immutable`, so no proxy keeps them for someone else, and the page and
    every refusal are `no-store`, set explicitly, as LogicalDOC's `CacheControlFilter` would otherwise keep a refused
    `.js` for ten minutes. Its policy is the User app's without PDF.js's worker, `worker-src 'none'`; its
    `frame-src 'self'` is for the downloads' hidden frame (see The Web App's [Working with a
    Document](../the-web-app/#working-with-a-document)). Its answers set their status, never send an error, so
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
  script under `healdan-core/src/main/resources/healdan/db/migration/postgresql/`, never as an edit to one that has
  shipped. Healdan never changes the structure of LogicalDOC's `ld_` tables, with one exception: V13 widens eight of
  their text columns to `text` (see [PostgreSQL](#postgresql)). That list is fixed, a column is only widened, and
  nothing of LogicalDOC's is added, renamed or dropped.
  - **One folder of scripts.** `HealdanMigrator` reads which database it's on from the connection and runs the
    scripts in `postgresql/`. It refuses MariaDB with a message that names the Install manual's
    [Moving from MariaDB to PostgreSQL](../../install/moving-from-mariadb/), and any other database, MySQL and HSQLDB
    included, with `Healdan's tables support PostgreSQL only; found …`. V1 to V12 are the scripts Healdan ran on
    MariaDB, translated one for one, with the same versions and names, so a version means the same on both sides of a
    move. The move makes them at V13, the version that goes with a MariaDB install's 12, and no later one
    (`HealdanMigrator.migrateTo`), so the app's first start applies any later script to the moved rows, as on any
    upgrade. PostgreSQL rolls a failed script back whole, and the start stops. The history table, `hd_schema_history`,
    starts with Flyway's version `0` baseline, as LogicalDOC's tables are there first.
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
    server time, its nightly run never in the first 10 minutes after a start, and at once on an administrator's Run
    Now, which may come then), destroys the members removed more than `trash.removedDays` before. Its design is in the
    folders and Trash spec, §5.4, and the Ruling it cites:
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
    create records every field it set, and an edit only those whose values changed, an owner by name and username, as
    `Ann Lee (alee)`, the name by `PersonNames`, so `dave` alone for an owner with no first or last name (a deleted
    owner by the username LogicalDOC gave the account when it was deleted). A rename or an edit that changes nothing
    isn't recorded.
  - **The admin routes record the rest.** A matter's move records `matter.moved`, with the Store before and after,
    inside the move's transaction, and its delete `matter.deleted`, after it. Saving a folder's access, or making it
    follow its parent, records `folder.access-changed` inside its transaction: `follows`, the folder it followed before
    and after by its path from its workspace's top folder (null with its own), and a change for each row that changed,
    named as `group Partners` or `user jdoe`, with its permissions before and after as they're stored (null for no row).
    Apply to Subfolders records `folder.access-applied` after it's done, with how many folders below, the Trash's
    included, had their own permissions and how many followed, before and after. Each names its folder by that path, as
    `1001.1 – Charter Dispute/Documents`. A Store's changes are the `STORES` area's: `store.created`, with its name and
    a change for each row it was given, and `store.access-changed`, with `follows` when it followed another folder's
    permissions and a change for each row that changed, both inside their transaction and named as a folder's rows are,
    `store.renamed`, with the name before and after, inside the rename's, and `store.deleted` and `store.restored`
    straight after the change. Storage's are the `STORAGE` area's, which the storage port records itself (see
    `healdan-storage` above). A workspace's folders and the templates record inside their transactions `folder.created`
    (its name and the folder it's in), `folder.renamed`, `folder.moved` (the folder it was in, before and after),
    `folder.properties-changed` (each value the folder sets of its own that changed, by the property's name, null for
    one it inherits), `search-folder.created` (its name, the folder it's in and its rule), `search-folder.rule-changed`
    (the rule's members that changed), `template.created` (its name, and the template it copies) and `template.renamed`,
    and after the delete `folder.deleted` and `template.deleted`. A folder is named by its path, and a template by its
    name.
  - **New Matter's check** is `MatterAdmin.check` (`com.logicaldoc.healdan.admin.matters`), which takes a
    `MatterAdmin.Draft`, every member of which may be null, and answers a `MatterCheck`. It looks for each problem
    only once the draft has what it needs, with `MatterService`'s own checks and sentences and `MatterNumbering`'s,
    and the permission rules as the create applies them: each folder of the template, top down and aliases left out,
    its prospective permissions (`PermissionRules.prospective`) against its parent's (`AclMath.effective`), and the
    workspace's permissions as the create would make them, the template's with the owner's own group given every
    permission, against the Store (`PermissionRules.p2Failures`, which `checkP2` uses too). It writes nothing. A
    problem about a group names it, or a person by `PersonNames` (first and last name, or the username when both are
    blank).
  - **A workspace's folder access** is `FolderAccessService` (`com.logicaldoc.healdan.admin.access`), with
    `AccessLevel`, the exact match of a row's permissions to Read Only, Read/Write, Full Access, Can See or Custom.
    `workspaceFolder(id)` is the scope, which it takes from `WorkspaceAdmin`: a live matter's own folder and anything in
    it, or a template's top folder and anything in it; anything else, an alias and a folder in the Trash included, is
    none. `read` takes its rows from `AclMath.effective`, so a row that grants nothing isn't listed, names a person by
    `PersonNames` and a group whose record is gone by `GroupNames`, and works out whether Apply to Subfolders is offered
    from a live folder below and `PermissionRules.checkApplyToTree`. `save` resolves each row to a group, a person to
    their own, refuses what the admin route refuses, and checks the matter's rules (P1 inside a matter, P2 on its own
    folder) with `PermissionRules`' own methods before it writes, as `HibernateFolderDAO.store` logs a listener's
    refusal at error level, which the error capture would count as a fault. The cap on `guest` and read-only people
    binds what each row adds to what the folder grants that group now, so a row kept as it is passes, and every row
    but the owner's on a matter's own folder, which must hold all 25 permissions whoever owns the
    matter: a read-only owner's row is then stored as what a read-only person's row holds, Read, Download, Preview,
    Print, Email, Subscription and Store, which `read` gives as Custom, and the record has each row as it's stored. It
    then stores through `FolderDAO.store` with `FolderEvent.PERMISSION`, so `PermissionsFolderListener` applies the
    rules again and narrows the folders below. A save that changes nothing writes nothing. **Follow Parent** is
    `FolderDAO.updateSecurityRef`, and **Apply to Subfolders** LogicalDOC's `applySecurityToTree`. All three refuse
    anyone but an administrator first, as the other admin services do, and a save and Follow Parent evict the folder
    from the second-level cache if their transaction rolls back (`CacheEvictor.evictFoldersOnRollback`), as that cache
    isn't transactional; `StoreService`, `WorkspaceAdmin`, `TemplateAdmin`, `MatterService` and a matter's move do the
    same.
  - **What `applySecurityToTree` does.** Healdan's hook runs first: `PermissionRules.checkApplyToTree`, which in a
    Store, its matters included, in the Templates Store and its templates, and from the root, lets an administrator
    through and needs anyone else to have the Security permission on every folder below that has permissions of its
    own, those in the Trash included. Only administrators reach the admin route, so there it always passes, and
    `canApplyToSubfolders` comes down to a live folder below. Then every folder and search folder below, at every
    level, those in the Trash included, loses its own rows and follows what the folder follows. Nothing is moved or
    deleted, and the folder itself doesn't change. So a restricted folder below is opened up: it grants exactly what
    the folder grants, and one in the Trash comes back so if it's restored
    (`FolderAccessServiceTest#testApplyToSubfoldersOpensUpARestrictedFolderBelow`).
  - **A Store and who can see it** are `StoreService` (`com.logicaldoc.healdan.admin.stores`). `stores()` and
    `store(id)` read the live Stores, `MatterLocator` deciding what's a Store, and `access(id)` gives a Store's rows
    with `AccessLevel.CAN_SEE` for Read alone and `CUSTOM` for any other set. `create` makes the workspace under the
    root through `FolderDAO`, with its own rows, a Read row for each group named, and records it, all in one
    transaction, so a failed record leaves no folder. `setAccess` gives each group listed Read, keeping its other
    permissions, and takes every other row away, in one `FolderDAO.store` with `FolderEvent.PERMISSION`, so
    `PermissionsFolderListener` narrows what in the Store's matters follows the Store; a change that changes nothing
    writes and records nothing. `rename` takes `create`'s name rules, the Store itself left out, its own name changing
    nothing, and records `store.renamed` in its transaction. `restore` takes the Store's Trash item, `deleted` or
    `removed` (`trashItem`), checks its name as `create` checks a new one, as the restore keeps a folder's name, then
    restores it through `TrashRestore.restore(item, rootId, …)`, back directly under the root, with what its delete
    took, so Healdan's rules and hooks decide as on every route; its caller records `store.restored`. `deleted` lists
    the Stores in the Trash in one query, with their item, state, matters, who deleted them and when they were
    removed. All of them refuse anyone but an administrator first.
  - **P2 for a Store's matters** is `PermissionRules.checkStoreP2(storeId, storeAcl)`, which `setAccess` calls before
    it writes, as `HibernateFolderDAO.store` logs a listener's refusal at error level. It reads the Store's live
    matters' own folders, the groups each lets read it and the owners' memberships in a few queries, chunked by 500
    ids, whatever the number of matters, skips a matter's folder that follows the Store (it follows the new rows too),
    and checks each against the Store's permissions as they would be, as `checkP2` does, the owner's own group passing
    while the owner could read the Store. It refuses only what the change breaks: a group counts only where P2 holds
    against the Store's rows now and fails against the new ones, so a matter that already let in a group the Store
    didn't blocks no other change, and the change that puts it right goes through. A matter in the Trash is checked
    when it's restored (`checkRestore`), and a plain folder left in a Store, with no live matter, isn't checked.
  - **A workspace's folders** are `WorkspaceAdmin` (`com.logicaldoc.healdan.admin.workspace`), whose `workspaceFolder`
    is the admin routes' one scope, which `FolderAccessService` and the routes' `WorkspaceRequests.folder` take. `tree`
    reads the workspace's folders once (`StoredRows`), aliases left out and hidden ones kept, into `WorkspaceNode`s.
    `createFolder`, `rename` and `move` check first what people meet, each with its sentence, so a refusal writes
    nothing: the workspace's top folder; a name another live folder in the folder has, ignoring case, a hidden one, an
    alias or a search folder too, as LogicalDOC would otherwise give the folder a name of its own making; a move out of
    its workspace (a matter's folder moves to another matter in the User app, and a template's stays in its template),
    into a search folder or into its own tree; and a document checked out in a folder that moves. Then they go through
    LogicalDOC's folder DAO, so the filing rules, P1's narrowing inside a matter and the values passed down decide as on
    every route, and they refuse a name LogicalDOC changed anyway. `properties` is `ProfileView.forFolder`, walking up
    to the workspace's top folder, Client and Matter left out; `setProperties` stores with `FolderEvent.CHANGED`, so the
    profile's rules mark each value local or inheriting and pass it down, and stores nothing that wouldn't change the
    folder's own values. A search folder is made and its rule changed through `SearchFolderService`, its name checked
    first as a folder's. Only administrators make the changes, and each is recorded inside its transaction.
  - **The templates** are `TemplateAdmin`. `list` and `info` count each template's live folders, aliases left out,
    and its live matters in one grouped query. `create` makes an empty template following the Templates Store, or
    copies one with New Matter's `TemplateCopier`, in the create's transaction, as a search-folder operation so it may
    make search folders; then a copy's top folder, which the copier gives permissions of its own, follows what the
    other's follows, if it does. `create` and `rename` refuse a name another folder in the Templates Store has,
    ignoring case. `problems` is `wider-than-parent` for each folder or search folder of the tree whose
    `AclMath.effective` grants more than its parent's, and `store-unreadable` for each live Store and each group
    `PermissionRules.p2Failures` finds for the template's permissions, the owner left aside, by Store name and then
    group name. A group is named as `MatterCheck` names it (`GroupNames`).
  - **People** are `UserAdministration` and `GroupAdministration` (`com.logicaldoc.healdan.admin.people`), the Users
    and Groups screens' reads and changes, on LogicalDOC's `UserDAO` and `GroupDAO`, never its RPC services, so its
    listeners and Healdan's run as on every route: `TokenUserListener` revokes a person's device tokens when a new
    password or a disable is stored, `ThrottleUserListener` clears their username's count once a new password commits,
    and `EveryoneUserListener` keeps every person but a read-only one in Everyone. A person is an account of the
    reader's tenant that isn't deleted or a system account, and a system account is never changed here; a read-only
    account belongs only to its own group and `guest`. A group is a live ordinary group of the tenant that isn't a
    person's own (`_user_…`), and `admin`, `guest`, `publisher` and Everyone are fixed. The Users list reads the
    accounts, their groups, provider links, throttle counts and device tokens in one go (`PeopleReads`), then filters,
    sorts and pages in Java, as a firm has hundreds of people. A person's Last Sign-In is when LogicalDOC last opened a
    session for them: `Session` writes `ld_lastlogin` and an `event.user.login` history row for every new session, a
    device token's included once its bound session has timed out, and an account with no such row, and no
    `ld_lastlogin` later than when it was made (LogicalDOC's `history.events` can leave the row out), has never signed
    in.
  - **Each change runs a transaction of its own,** a `TransactionTemplate` rather than `@Transactional`, and refuses to
    run inside a caller's (`IllegalStateException`), so a guarded change commits before it lets go of its lock, and what
    follows a commit follows a real one: closing sessions, emailing a temporary password and `recordAfter`. Each refuses
    anyone but an administrator first (and, for the changes the guards below bind, a caller whose own account is no
    longer enabled), then what isn't a person or a group, then a form's fields (`admin.FieldRefusal`, each field with
    its code and sentence, in the form's order), then a group id in the request that names no group, then a change that
    would change nothing, answered as it is and not recorded, and last Healdan's rules, each a `HealdanRuleException`
    with its sentence (public constants, such as `UserAdministration.LAST_ADMIN`). A refusal changes nothing. A person's
    `refusals`, on their page, are those sentences, worked out at the read.
  - **The guards and the locks.** The `admin` account is never disabled or taken out of the `admin` group, nobody
    disables or takes themselves out of it, and no change made here leaves the tenant without an enabled administrator
    (an enabled account in `admin`, not a system account). A change that could lower their count, a disable or a change
    of anyone's memberships (a person's groups, a group's member added or removed), holds
    `UserAdministration.ADMIN_CHANGES`, counts them after its write, inside its transaction, and commits before it lets
    go, so of two administrators taking each other out at the same moment the second counts what the first left, and is
    refused. Every change, inside its lock where it holds one and before anything else, refuses a caller whose own
    account, as stored now and read with JDBC past the second-level cache, is no longer enabled
    (`YOUR_ACCOUNT_DISABLED`), so an administrator another disabled while their change waited its turn changes
    nothing. New User, Edit, Enable, Reset Password's transaction and a group's delete hold `ADMIN_CHANGES` too, so
    the Admin app's stores of one person take turns, none puts back groups another has just changed, and nobody is
    made in a group as it's deleted. New User holds `NEW_USERNAMES` as well, outside it, and New Group and a rename
    `GroupAdministration.GROUP_NAMES`, so a name's check in any case and its insert can't interleave with another's; a
    unique index's refusal (SQLState `23505`) is checked again after the transaction, as `NumberRaces` does, and
    answered `taken` only when the name is taken now. Each lock is taken outside its transaction, and nothing after a
    commit runs under one.
  - **Signing a person out** is `PersonSessions` (`admin.people`): `closeAll(userId, username)` closes every open
    LogicalDOC session of the account on this server, however it was opened (the web page's, the Classic Interface's,
    WebDAV's, CMIS's, the web services', an API key's or a device token's), and every one the person opened as another
    account, whose `impersonator` is their stored username, each by its id with `SessionManager.kill`, never through the
    request that asks, and each on its own, so one that fails leaves the others closed all the same. It holds the
    session manager's lock, which LogicalDOC holds while it signs someone in, so a sign-in that read the account before
    the commit has its session in the pool by then. It never throws. Disable and Reset Password call it once they've
    committed, and Sign Out Everywhere once it has revoked the device tokens. M4's account linking (`SignInAccounts`)
    closes an account's sessions, those it opened as another account, and those its API keys opened, through it too.
  - **Temporary passwords** are `TemporaryPasswords` (`com.logicaldoc.healdan.signin`), which New User, Reset Password
    and unlinking an account from its sign-in provider (`SignInAdmin`) share. `make` makes one and checks it against the
    tenant's password rules, before the change's transaction, with a second try; `email` sends each in LogicalDOC's
    credentials email (`psw.rec1`) once the change has committed, waits 15 seconds at most for the mail server to take
    it, and says what became of it (`EMAILED`, `NOT_SET_UP`, `NO_EMAIL` or `NOT_TAKEN`). A reset stores the password
    with the must-change flag and the history LogicalDOC writes for a new password, through `UserDAO.store`, so the
    listeners revoke the person's device tokens and lift their username's block. The password is in New User's and Reset
    Password's result, once, when it wasn't emailed, and in no record, log line, exception or `toString()`.
  - **The other changes.** Clear Sign-In Block clears the username's count through `HealdanThrottle.passwordSet`'s
    logic, never an address's. A person's groups are set as a whole, their own group, and Everyone or `guest`, kept
    whatever is sent. A group's delete takes each member who's a person out through `UserDAO.store`, so the listeners
    run, and any other account's membership, such as a system account's, by its `ld_usergroup` row alone, never storing
    that account, then deletes the group, all in one transaction. A group's name is at most 200 characters, as
    LogicalDOC renames a deleted group `<name>.<id>` within 255, and a longer one given elsewhere is refused a delete
    until it's renamed. An edit takes the person's current names, email, language and time zone as they are, a blank
    name or an email set elsewhere that New User wouldn't take included, and checks only what it changes, as the Admin
    app sends back what it was given.
  - **Recording.** Each change is in the administration record, in the People area, a person named as
    `Ann Lee (alee)` (`PersonNames.recorded`) and a group by its name: `user.created` (every field set, the groups by
    name, and `password` as a secret), `user.changed`, `user.disabled`, `user.enabled`, `user.groups-changed` (the
    groups before and after, by name), `user.block-cleared`, `group.created`, `group.changed`, `group.deleted`,
    `group.member-added` and `group.member-removed` inside the change's transaction, and `user.password-reset`
    (`password` as a secret, and `emailed`), `user.signed-out` (the sessions and devices) and `user.device-revoked`
    (the device's name) straight after it, with `recordAfter`: a reset's record says whether the email went, which is
    known only once it has.
  - **`hd_error` and `hd_error_hour`** (`V12__admin.sql` too) hold the errors the server logged, by fault. `hd_error`
    has a row for each fingerprint: the level, the area, the logger, the latest message, its stack and the documents
    it names (the ids a message names as `doc 10412` or `document id 10412`), how many times it was seen, when first
    and last, and whether and by whom it was resolved. `hd_error_hour` counts each fault's errors in each hour, in
    UTC, for the time windows, each hour written as UTC's own date and time, so the server's clocks going back can't
    make two hours one. `ErrorStore` reads and writes them.
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
    can't feed itself. It leaves out a refused sign-in too: an `AuthenticationException`, of any kind, that LogicalDOC's
    `AuthenticationChain` logs, such as a password that has to be changed first or a disabled account, which is a
    refusal, not a fault, and which every first sign-in with a temporary password would otherwise add. Anything else
    that logger logs at error, a `PersistenceException` or an error with no exception, is a fault as any other
    (`ErrorCaptureTest#testASignInRefusedIsNoFault`).
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
    reader's, the browser's `zone`, as the User app's are: it keeps a summary for the server's time zone and for the
    four zones asked for most recently, and a zone asked for the first time gets the empty one and has its own made at
    once. The storage port names each Store's backend, and one that fails leaves the backends
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
    `counts()` gives the indexed, the waiting and the failed in one pass, `failed(offset, limit)` lists the failed,
    latest error first, and `waiting(offset, limit)` the waiting, by id, each with the matter stamped on it.
    `changes()` moves on (`moved()`) after Try Again, Skip, a waiting reindex cancelled and a reindex's end, and the
    Dashboard's index source reads afresh after it moves, so the left pane's Search Index count follows at its next
    read.
  - **The Search Index's reads and two actions** (`admin.index`, Admin app spec §8.9). `IndexOverview` gives the
    heading, the counts read at the request rather than the Dashboard's: the index's entries, asked of Solr directly
    (LogicalDOC's own count logs an error and answers 0 when it fails), and its folder's size, each null, logged at
    warn by class, when it can't be read; the health, the Dashboard's own tile (`DashboardService.searchIndexTile`);
    and the Indexer, `Jobs.one("IndexerTask")` with `JobControl.runRefusal`, a read-only check made of Run Now's own
    checks in its order, which Run Now itself uses, so the greyed-out button and a `409` never disagree.
    `IndexHistory` pages `ld_history`'s `event.indexed` and `event.indexed.error` rows newest first by a cursor
    (`IndexHistoryCursor`): no index serves a newest-first read by event, so each page looks through at most 100,000
    of the tenant's rows by the key (`SCAN`), as the Audit Trail reads a source no index serves, with the event's
    condition written `ld_event || ''`, so neither a custom plan nor a generic one reads the event's index; an
    indexed row's comment, the start of the document's text, is never read out. `IndexReview` is Try Again and Skip,
    for a live document of the caller's tenant that couldn't be indexed only (`NOT_FAILED` otherwise). Try Again calls
    `DocumentManager.index(id, null, null)` on the request's thread, with no history, so LogicalDOC writes no row and
    applies none of its settings, then writes the row itself through `IndexEventRows` and, with `index.skiponerror`
    on, sets a document that failed again to Skip, as LogicalDOC's Indexer would. A failure with a
    `StorageUnreachableException` among its causes writes, counts and records nothing, and goes up as the route's
    `503`. Skip sets Skip in one statement that holds only while the document still couldn't be indexed, and evicts
    the document from the second-level cache. Both are recorded with `recordAfter`, `index.retried` with its
    `outcome` and `index.skipped`, aimed at `document` by its id and named `# 10412 Witness Statement.pdf`.
  - **`IndexEventRows`** writes `event.indexed` and `event.indexed.error` (with the failure's message as its
    comment) as `DocumentManager.recordIndexingError` writes its own row, with the folder's path, in the caller's
    transaction, and, for a session this node has open, its address and device; a document that no longer exists gets
    none. Try Again and the reindex worker both write through it, as LogicalDOC's `index` writes its error row before
    it throws whenever it's given a history, a storage outage's included.
  - **`hd_reindex_job` and `hd_reindex_doc`** (`V14__reindex.sql`) are the reindexes. `hd_reindex_job` has a row for
    each reindex started: its kind, target and name, who started it, its state (`WAITING`, `RUNNING`, `DONE` or
    `CANCELLED`), whether it was asked to stop (`hd_cancel`) and whether it empties the index first and has
    (`hd_emptyfirst`, `hd_emptied`), its counts (`hd_total`, `hd_done`, `hd_failed`, `hd_skipped`, `hd_leftout`), its
    position, its times, and since when the index couldn't be written (`hd_stalled`). `hd_reindex_doc` holds the
    documents of each reindex that hasn't ended, fixed when it's started, and loses them in the transaction that ends
    it, so a reindex knows what it covered whatever happens to its folders, its counts add up, and a restart goes on
    over the same set. `ReindexJobs` is their JDBC store.
  - **`ReindexService`** (`admin.index`) previews, starts, lists and cancels reindexes, administrators only. A scope
    is `ReindexScope`: documents by doc # (1,000 different ones at most), a folder's, a matter's or a Store's tree
    walked by parent (`StoredRows.tree`), or the tenant's every live document. Of those, a reindex tries what
    `ReindexJobs.ELIGIBLE` keeps, and counts as left out the shortcuts and the documents set to Skip, archived (which
    LogicalDOC's Indexer leaves alone) or in a running LogicalDOC transaction (a `lock` row of `ld_generic` for its
    transaction, the rows `LockManager.getAllTransactions()` reads), so a preview's `count` is the reindex's total.
    A start is refused when it would try nothing, and while a reindex of the same kind and target hasn't ended (never
    for documents), both checked under one lock so two starts at once can't both pass; it writes the reindex, its
    documents and its record (`reindex.started`) in one transaction, `WAITING`, and wakes the worker. A cancel ends a
    waiting reindex at once and asks a running one to stop. The estimate is the count times the seconds a document
    took in the latest reindex that ended `DONE` having tried 20 or more, or 1.75 s each.
  - **`ReindexWorker`** (`admin.index`) is a `HealdanStartListener` and a `HealdanJob`, `reindex`, labelled Reindex,
    which can't be run now. It runs the reindexes one at a time, oldest first, on its thread, `healdan-reindex`, from
    a minute after the start, woken by a start or a cancel. Each turn ends a running reindex asked to stop, or one
    with nothing left, or works through its next 100 documents above its position, in id order, on as many threads as
    LogicalDOC's Indexer takes (`threadpool.IndexerTask.max`); with none running, it begins the oldest waiting one,
    and a reindex that empties the index first drops the index and marks every document indexed, or waiting for its
    properties alone, `TO_INDEX`, Skip kept, the second-level cache evicted, once (`hd_emptied`), as its run begins.
    Each document is looked at again just before its turn: one no longer live, set to Skip, archived or in a running
    transaction is skipped, and so is one whose file's storage can't be reached, its entry, status and history as
    they were. It's indexed through `DocumentManager.index(id, null, null)`, its old entry kept
    until the new one replaces it, so search goes on finding it; a document's own failure writes its error row, as
    the reindex's starter, and marks it `TO_INDEX`, so it joins Couldn't Be Indexed and LogicalDOC's Indexer tries it
    again. A batch's rows, counts and position are saved together once it ends. A batch in which nothing was indexed
    and a document failed because the index can't be written (an `IndexException` or a `SolrException` among its
    causes) is the index's outage: nothing of it is saved, `hd_stalled` says since when, and the batch runs again
    after 5 seconds, doubling to a minute. It reports to `JobStatus` as a reindex's run begins and ends, and as the
    worker itself fails, which it tries again 30 seconds later.
  - **What the background jobs last did** (`com.logicaldoc.healdan.admin.jobs`, Admin app spec §9.5). Each of
    Healdan's jobs is a `HealdanJob` bean, with a name, a label and whether it can run now, and reports to
    `JobStatus` under its name: `started` before a run, `finished` or `failed` after it, each with its next run, and
    `scheduled` when it knows its first run before making it. `JobStatus` keeps each job's last start and end, how it
    ended, its error (the exception's class and message, or the text the job gives, at most 1,000 characters, never a
    stack) and its next run, in memory only, so it starts again with the server, and reporting never throws, so it
    can't change what a job does. The previews and the email index report from each round of their workers
    (`runOnce`), `healdan-storage`'s mover from its sweep and its `runOnce()`, and the Trash destroyer and the
    storage retests, purge, alert and nightly check from their schedulers and from Run Now (see Job control, below),
    so a direct call to one of those last, as a test makes, reports nothing.
  - **`Jobs`** lists Healdan's nine jobs in a fixed order (`preview`, `email-index`, `trash-destroyer`,
    `storage-mover`, `storage-retest`, `storage-purge`, `storage-alert`, `storage-nightly-check`, `reindex`; the
    storage ones only
    where `healdan-storage` is, and switched off on an `FSStore` server, where none runs: `HealdanJob.enabled()`, which
    `healdan-storage`'s `StorageJob` answers from `StoreTracking.active()`), then LogicalDOC's six scheduled tasks from
    `TaskManager`: the Indexer, Index Optimizer, Tags Processor, Digest Processor, Statistics Collector and Paths
    Calculator. A task's last start is its schedule's previous fire time, its last end that plus its last duration once
    it isn't running, so a task shows no last end while it runs, and its next run the first time after its last start
    that the trigger Quartz holds for it fires, so a schedule saved since the start counts (with no trigger, its
    scheduling's own next fire time). A task's view carries its schedule (`JobSchedule`: `simple`, every so many
    seconds, or `cron`, with its expression); Healdan's jobs keep their own, and have none here. One of Healdan's can
    run now while it's switched on and isn't running, if it's a job that does (`HealdanJob.canRunNow()`), and a task
    while it's enabled and idle and the server's run level has scheduled tasks on.
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
    | `errors` | `ErrorStore.unresolved`: the faults not resolved that were seen in the last 24 hours, each counted once, and when the latest was, in one query |
    | `previews-given-up`, Previews | `PreviewJobs.countWaiting()` and `countGivenUp()` |
    | `job-failed`, `job-overdue`, Background Jobs | `Jobs.all()`: a job switched on whose last run failed, and one overdue that didn't, so none is counted twice |
    | `disk-low`, Disk | the volumes of `store.1.dir`, `index.dir` and `conf.logdir`, each once; one more than 85% used (`DISK_LOW_PERCENT`, a constant), used being its total less what the server can still write. The tile's bar is the fullest's |
    | Server | the JVM's time up, and `product.release` |
    | Database | JDBC's product and version, and its size: PostgreSQL's `pg_database_size(current_database())`, or the sum of MariaDB's `information_schema.tables` for the connection's catalog, never `database()` |
    | Memory | `Runtime`'s heap used of its maximum |
    | Sign-In | each provider people can sign in through (`SignInProviders.enabled()`), asked whether it answers (`ProviderClient.answers`, a fresh fetch of its discovery document whose failure isn't remembered, so it never makes a sign-in fail; a sign-in's failure in the last 30 seconds answers no without asking), at most every 5 minutes per provider |
    | Outgoing Email | `OutgoingEmail.isSetUp()`, and an error LogicalDOC's mail sender (`EMailSender`) logged in the last 24 hours that nobody has resolved |

    Each source is kept for a minute, the database's for five (`Cached`): then the next read works it out again, once
    however many ask, on a thread of its own, `healdan-admin-dashboard`, never a request's, and reads meanwhile get
    the last answer while it's younger than twice that. A request waits for a source only when it has no answer that
    young, at most 5 seconds for all of them, after which the source is still being worked out. A read still running
    after its minute is stuck: it's logged once, and a request with no answer young enough gets a failure at once
    rather than wait for it; one still running after ten is given up on, logged, and read again. The errors' source
    and outgoing email's are also told of each fault marked resolved or unresolved (`ErrorStore.reviews()`, a count
    each change adds to once it commits): the next read after it forgets what they keep, as `forget()` does, and works
    them out afresh, so the Errors screen's ask straight after a resolve gets the new count. The jobs' source is told
    the same way of each job action that changed something and each run that ends otherwise than the one before
    (`Jobs.changes()`, below), and the index's source of Try Again, Skip and a reindex's end (`IndexCounts.changes()`,
    above). `readAt` is when the
    oldest answer a request is given was read. A source that fails, is stuck,
    or is still being worked out, puts its codes in `unchecked` and gives its tiles `problem`, a fixed line, with
    `attention` for status, and the rest are whole; the failure is logged at warn with its class only, its stack at
    debug. Each item's and tile's link is the screen that fixes it, from `DashboardService.Screen`, a table of the
    Admin app's addresses that is the Classic Interface's, `/frontend.jsp`, until each screen ships. Phase 2 gave
    Clients, Matters and Workspace Templates theirs, Phase 3 Stores and Backends, Phase 4 Users, `/admin/users`,
    which no item or tile links to yet, and Phase 5 Errors (`/admin/errors`), Server Log (`/admin/server-log`),
    Audit Trail (`/admin/audit`) and Background Jobs (`/admin/jobs`), and Phase 6 Search Index
    (`/admin/search-index`) (`DashboardScreensTest`):
    `files-stuck` links to Stores, or to Backends when only files with no Store are stuck (the item's own screen, in
    `Found`), and `backend-unreachable`, `disk-low`, the Storage Backends tile and the Disk tile to Backends; where
    storage backends are off, `disk-low` and the Disk tile link to the Classic Interface, as Backends has nothing
    there. `errors` links to Errors with Unresolved Only set (`UNRESOLVED_ERRORS`, `/admin/errors?unresolved=true`);
    `previews-given-up`, `job-failed`, `job-overdue`, and the Previews and Background
    Jobs tiles to Background Jobs; `index-failed` and the Search Index tile to Search Index; and the Server, Database
    and Memory tiles to Server Log. Tests
    call `read()` after `forget()`, which drops what's kept.
  - **Reading a document is recorded** (`DocumentViews`, `com.logicaldoc.healdan.admin`; Admin app spec §8.8), as
    LogicalDOC's `event.viewed` in the document's history, for the Audit Trail. `healdan-api`'s `/preview` calls
    `viewed` once it has opened the file's stream, as it's about to answer `200`, and `MessageResource` as it serves an
    email's body or an attachment shown in place (`inline=true`), on the email; so a `202`, a refusal, a `404`, a
    `HEAD` and a `503` record nothing, and an email's body, its pictures and its attachments shown in place are one
    view of it. A view is recorded once per person and document in 30 minutes (`WINDOW`): a map in memory, in the order
    views were claimed, drops what's older than that as each is claimed, and holds 20,000 at most (`MAX_REMEMBERED`),
    past which the oldest go, which can only record a view again sooner; a restart forgets it, as Healdan runs one
    node. The claim comes before the write, so two reads at once write one row. The row is built as a download's is
    (`DocumentResource.recordDownload`), the session's id and the address it signed in from included, and written in a
    transaction of its own (`PROPAGATION_REQUIRES_NEW`), so no caller's transaction holds it; a view of an older file
    takes that file version, and the version and file name of the version that made it. A write that fails is logged
    at warn with the document's id and the exception's class alone (its stack at debug), takes its claim back, so the
    next read tries again, and never throws: the read goes on, whatever fails, an `Error` such as a missing class
    included, but for the virtual machine's own (`VirtualMachineError`), which goes on up.
  - **A user name and password written in a URL are cut from what's shown** (`UrlCredentials.cut`, in
    `com.logicaldoc.healdan.admin`). In every URL written plainly, a scheme then `://`, the user part of the authority
    goes, with its password or without, so `scheme://user:secret@host/path` reads `scheme://host/path`, and so does a
    URL that follows another's host directly, as in a list of hosts joined by commas. The authority ends at the first
    `/`, `?`, `#`, whitespace, quote or angle bracket, and the user part at the last `@` before that, so three things
    aren't recognised, and the contract's Monitoring tag says so: a password that itself holds one of those; a URL
    escaped as JSON or percent-encoded; and a URL cut short by a length limit before its `@`. An `@` in a path or query,
    and an email address outside a URL, stay. It's one pass over the text, with no regular expression. The
    Errors routes cut a fault's message and stack with it, the Server Log each line, Background Jobs a job's error, and
    `ErrorReview` the name it records. A log's download is the files as they are.
  - **Marking a fault resolved** is `ErrorReview` (`admin.errors`): `resolve(id, actor)` and `reopen(id, actor)`
    refuse anyone but an administrator first, then change the fault and record it in one transaction, `error.resolved`
    or `error.reopened`, in the `MONITORING` area, aimed at `error` by the fault's id and named by its latest message,
    cut as above and to fit. `ErrorStore.resolve` changes only a fault that isn't resolved, and `reopen` only one that
    is, so of two resolves at once only one changes the row and is recorded, and a change that would change nothing is
    answered as it is. `ErrorStore.reviews()` counts each change once it commits, which the Dashboard's sources
    compare (above). A fault's details name the documents its message names (`ErrorStore.findDocuments`, the caller's
    tenant's, deleted ones too, each with its doc # and file name, or neither for an id no such document has) and the
    log its logger writes to (`LogFiles.logOf`, below), for **Show in Server Log**. The list (`ErrorPage`) adds
    `errors`, the times the faults the filters keep were seen in the window, beside the page; the windows count whole
    UTC hours of `hd_error_hour`, so the last hour's counts are the hour now and the one before.
  - **The Server Log** is `LogFiles` (`admin.logs`), over LogicalDOC's `LogConfigurator`, read at each call. A log is
    a file appender of `log.xml`, named by its appender's name, but for the HTML copies (`_WEB`) and a name with a
    slash, a backslash or an apostrophe, or of dots alone. `list` gives `DMS`, **Main Log**, whenever the configuration
    has it, then each other log whose files hold anything, by label, ignoring case: a task's by `Jobs.label`, any other
    by its name. A name a request sends is only compared with those names, so nothing it sends is joined to a path, and
    the Admin app's address carries it as a search parameter, `?log=DMS`, never a path segment, which
    `AdminAppServlet` could take for a file by its extension. `list`, `read` and `zip` refuse anyone but an
    administrator first.
    - **A log is its current file and the files it rolled over to,** `<file>.1` up to its `DefaultRolloverStrategy`'s
      `max` (log4j's 7 when it names none, and 100 at most), read as one, oldest first, by the strategy's `fileIndex`:
      with `min` the highest number is the oldest, and otherwise the lowest. `LogChain` opens them once for a read,
      each up to the size it had then, and knows each by its identity, the first 16 bytes of the SHA-256 of its first
      line (4 KiB of it at most), which stays as the file is renamed; the current file has none until its first line
      is whole.
    - **A position** (`LogPosition`) is an identity and an offset, as base64url without padding, opaque to a client.
      `parse` takes only the one spelling `text()` writes, at most 80 characters, and a read finds the position's file
      among the log's by its identity, never by a name. An `after` whose file isn't the current one any more reads the
      current file from its start, `rolled`; a `before` whose file has gone answers nothing, at the log's start.
    - **`LogReader`** reads 64 KiB at a time and keeps at most 40,000 bytes of a line, decoded as UTF-8, a URL's user
      name and password cut, then 10,000 characters of each message and continuation line. A line starts an entry when
      it starts with its time as every shipped pattern writes it (`ENTRY`, read in the server's time zone); the rest
      is read as the main log's pattern writes it, a level, a source and the message (`FULL`), or as a level and the
      message, or the message alone after `- `, as a few of the shipped logs write them, the level and source null
      where the line gives none. The lines after it are its continuation, and a line that starts no entry at the start
      of what was read, or of a file, is an entry of its own, as log4j writes each event whole to one file. A read
      stops at 200 entries, 5 MiB looked through or 50,000 lines looked at. Going back, it stops at an entry's start,
      so an entry's lines come together, and goes on into the next older file. Going forward, it reads the current
      file up to its size, leaves a last line not yet ended, and, stopped by its budget inside an entry, gives none of
      that entry and answers its start as `after`, but for the first entry it read, which it gives as far as it got,
      so that a read always moves on. A file that shrinks while it's read ends the read where it now ends.
    - **The download.** `zip` opens each file to check it can be read and closes it again, and `LogZip.writeTo` opens
      them as it writes the zip, so nothing is held open between the request and the body, and a `HEAD` opens nothing:
      the current file first, then the newest of the others, each up to its size then, under its own name. A file that
      can't be read is an `UncheckedIOException`, a `500` before the answer starts.
    - **`logOf(logger)`** is the log a logger's lines go to: from the logger `log.xml` configures that is it or the
      nearest package of it, up through those that pass their lines on to their parents, the first listed log of the
      first that doesn't, or the root's, `DMS`. It never throws: a configuration that can't be read logs one warning and
      answers none.
  - **The Audit Trail** is `AuditFeed` (`admin.audit`), one feed, newest first, over five sources: `ld_history`
    (Document), `ld_folder_history` (Folder), `ld_user_history` (Sign-In for a sign-in, a sign-out, a session that
    timed out and a failed sign-in, User for the rest), `hd_admin_event` and `hd_storage_event` (Administration).
    `page`, `forEach` and `prepare` refuse anyone but an administrator first, and read the actor's tenant only, every
    value bound. `prepare` works out once what the filters name (the doc #'s documents, the matter's folders, each
    table's id range for the days), so an export's refusal or failure comes before its first byte.
    - **The filters.** A person (`userId`) is the rows they did and those about them: the history rows with their id,
      and the administration rows by them or aimed at `user` and their id. A matter is its own folder and every folder
      in it, live or in the Trash, walked by parent, never by `ld_path`: those folders' document and folder rows, and
      the administration rows aimed at the matter or at a folder or search folder in it; no account or storage rows.
      A doc # is its documents' rows in `ld_history` alone. A `userId` that names no account of the tenant, 0 included,
      keeps no row. `q` is a `like`, with `%` and `_` taken as they are, on a file name, a folder's path, a target's
      name (a storage row's backend name is its target's), an address, and the person as the row kept them: in
      LogicalDOC's history the login and the name at the time (`ld_userlogin`, `ld_username`), and in Healdan's
      records the username. Never the person's name now; and an account row's person only where its user id names an
      account of the tenant, so what was typed at a failed sign-in for no account is never compared.
    - **The twins.** A recovery key shown or entered through the Admin app is in both `hd_storage_event` and
      `hd_admin_event`, so a storage row with an administration row of the same action, backend and person written
      within 10 seconds after it is left out: the storage record gives only the Classic Interface Storage tab's.
    - **The cursor** (`AuditCursor`) holds each source's place, the id below which its rows are still to come, as
      base64url of `d<n>.f<n>.u<n>.a<n>.s<n>`, parsed only in the spelling `text()` writes. Rows written meanwhile take
      higher ids, so they never repeat one on a later page: they're on a fresh first page. An id is given at the
      insert, not the commit, so a row whose transaction commits after a page has passed its id isn't on a later page
      of that cursor, nor in an export under way, only on a fresh first page. The sources are
      merged by date in Java, ties in the sources' order, and a row is given only once every source that could still
      hold a newer one has been read that far, so the order holds across short reads.
    - **Reading.** None of the tables has an index on its date, and Healdan adds none to LogicalDOC's, so each source
      is read newest first by its key. With a filter no index serves (the words, the dates, one of the two account
      kinds alone, a matter's document rows, a person's folder rows, and every storage read, for its twins), a page
      looks through at most `SCAN_PER_SOURCE`, 100,000, of a source's rows by its key, as much as one request can
      afford (about 0.9 µs a row on the test database: 91 ms for 100,000), and may come back short, even empty, with
      a cursor. A filter an index serves (a person on `ld_history`, `ld_user_history` and `hd_admin_event`, a matter
      on `ld_folder_history` and `hd_admin_event`) is read in two steps: that window first, which finds the rows of
      anyone or anything active; then, only while the page isn't full and the window didn't reach the source's end,
      the rows below it through the index (`belowSql`: the ids an inner select finds by the indexed columns alone,
      then those rows with every filter), so a page costs the window and the value's own rows, wherever they lie. A
      doc # reads through its index alone. Every statement keeps its bound under a generic plan as well as a custom
      one, as pgjdbc prepares a statement on the server once a connection has run it five times: the inner select's
      bounds on the key are written `id + 0`, so no index serves them and it reads the value's own index whatever the
      plan. Each page says how far back it has read (`AuditPage.readBackTo`): the newest of each source's next row not
      given and each open source's window end, so every row the filters keep that's newer has been given and the next
      page's rows are no newer; null exactly when the cursor is.
    - **Dates** become an id range for each table by a binary search on the key, each probe the row with the greatest
      id at or below a point, widened by an hour each side (`DATE_SLACK`), and the rows are still filtered by their own
      dates, so the answer is a full scan's while no row's date is more than an hour out of step with its id's order.
    - **Words** (`AuditWords`): each row carries its stored event and its words in US English (`what`), from one
      table, an event with no entry showing itself, each true whoever did it ("Account disabled", "Asked a job to
      run"). `who` is the person's name now (`PersonNames`, read for a page in one query), or the person as the row
      kept them; `detail` is one line the server makes (a document's doc #, file name and version, a folder's path, a
      sign-in's client, a target's name); and an administration row's changed fields come apart, as `changes`, a
      secret as `Change.secret()`. A User row, an account's history but its sign-ins, names who made the change: `who`
      is the row's author (`ld_author`), named as any person is, read for a page by username in one query, or null
      where the row has none, and `detail` is the account as the administration record names a person, as
      `Sam Okafor (sokafor)`, then, for the label events (`LABELLED`: an account created, changed, disabled, enabled
      or deleted, a password changed, an API key made, a sign-in as another account, a change of who can sign in as
      it, a legal notice confirmed), `: ` and LogicalDOC's note, cut to 300 characters; for any other, the account
      alone. A failed sign-in for a username no account has has `who` null and the detail `Unknown username`
      (`UNKNOWN_USERNAME`), so what was typed is never answered or exported.
    - **The export** (`AuditCsv`) is UTF-8 with a byte order mark and CR LF, with the columns Time (UTC, ISO 8601, to
      the millisecond), Who, Kind, What, Detail, From and Changes. `AuditCsv.open(prepared, max)` reads the first page,
      a page of 1,000 rows, before the answer starts, so one that can't be read is a `500` problem; `Export.writeTo`
      then writes the byte order mark and the column names and flushes at once, then each page's rows as it's read,
      to 100,000 (`MAX_ROWS`), then a last line that says it stopped. Before each later page it asks whether its reader
      is still an administrator (`Prepared.readerIsAdmin`), and ends with `NOT_ADMIN`'s line ("Stopped before the end:
      only an administrator can export the Audit Trail.") if not, logged at info; a later page that can't be read ends
      it with `UNREADABLE`'s ("Stopped before the end: the server couldn't read the rest. Export again."), logged once
      at error by class. Every cell, the last lines included, once composed, is guarded (a `'` before a leading `=`,
      `+`, `-`, `@`, tab or CR) and then quoted.
  - **Job control** is `JobControl` (`admin.jobs`): Run Now, Stop, Enable, Disable and a task's schedule. Each refuses
    anyone but an administrator first, then answers null for a job `Jobs` doesn't list (the route's `404`), then
    refuses what the job doesn't allow now with a sentence of its own (`ALREADY_RUNNING`, `SCHEDULED_TASKS_OFF`,
    `NO_SCHEDULE` and the rest), and answers the job as `Jobs.one` reads it after.
    - **LogicalDOC's tasks.** Run Now, while one is enabled and idle and scheduled tasks are on, runs the task as its
      schedule does, on a daemon thread of its own, `healdan-run-now-<task>`, refused while the thread it last started
      is alive, so two at once make one run. Stop, while it runs, is `Task.interrupt()` under the task's own monitor,
      refused once asked. Enable, Disable and the schedule go through the task's `TaskScheduling`, saved as the
      Classic Interface saves it, and take turns on one lock. A schedule is `simple`, every 1 to 31,536,000 seconds, or
      `cron`, an expression Quartz takes that comes round again, its fields checked first (`FieldRefusal`, `422`),
      whatever the job; the task's delay and maximum duration stay. A save that fails puts the scheduling and its
      settings back as they were in memory, so the same change asked again is made.
    - **Healdan's jobs.** Run Now alone, for those whose `canRunNow()` is true, the Trash destroyer and the storage
      mover, retests, purge and nightly check, while they're switched on and not running (`HealdanJob.runNow()`). Each
      but the mover runs through a `JobRuns`, which takes the job's runs one at a time: a run asked for now starts on a
      daemon thread, `healdan-run-now-<job>`, only while none goes on, and reports to `JobStatus` as a scheduled run
      does, the job's next scheduled run left as it was; a scheduled run that comes due meanwhile waits for it to end,
      then runs. The mover's Run Now wakes its coordinator, whose passes report as they always do, and is refused before
      the coordinator has started. Previews, the email index, the storage alert and the reindex worker run only by
      themselves.
    - **Recorded** with `recordAfter`, as saving a task's scheduling writes `context.properties` and reschedules it,
      which isn't one transaction: in the `MONITORING` area, aimed at `job` with no id and named by the job's name,
      `job.run-requested`, `job.stop-requested`, `job.enabled`, `job.disabled` and `job.schedule-changed` (one change,
      `schedule`, before and after, as `every 1800 seconds` or `cron 0 0 3 * * ?`). Run Now and Stop are recorded once
      taken; Enable, Disable and the schedule only when they change something. Each action recorded, and the end of a
      task's run it started, moves `Jobs.changes()` on (`JobStatus.changed()`); a Healdan job's report whose result or
      error differs from the one before moves it too, and the Dashboard's jobs source reads afresh after it moves.
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
- **A version record follows its write.** LogicalDOC's `DocumentManager` writes the document, then saves the
  write's version record a second or more later, on a thread of its own, under the number the write gave the
  document; `ak_version`, a unique index on the document and the number, refuses a second record with it. Another
  thread's write of the document between LogicalDOC's read and its write, as the Indexer's after it parses, makes
  that write fail, and the API asks LogicalDOC again. LogicalDOC's `rename` started its record before its write, so
  a rename whose write threw kept one, and the rename asked again had its own refused: an `ERROR` line in
  PostgreSQL's log, and in the Classic Interface, which doesn't ask again, a rename in the timeline that never
  happened, holding the number whose record the document's next write, a save among them, then lost. `rename` starts
  it once the write is stored, as a check-in and a move do, with the name and the type the document was stored with
  (the store makes the name unique in the folder), an upstream edit marked `// Healdan:`
  (`DocumentManagerTest#testRenameWhoseWriteFailsKeepsNoVersion` and `#testRenameRecordsTheNameStored`, in
  `UPSTREAM_TESTS`, and `DocumentChangesTest`'s renames and moves past a background write). A write lost in its
  flush, which rolls back unseen, still keeps its record: the API waits for it and drops it before it asks again,
  but the Classic Interface, WebDAV and the REST web service keep it, which is still open.
- **An attachment's link is read back.** LogicalDOC's link DAO stores a link and then, in the same transaction,
  reads each of its two documents, counts its links and writes the count to it. Another thread's write of either
  document between that read and that write, as the Indexer's of an attachment it has just indexed, makes the
  count's write fail in its flush, which LogicalDOC's DAO swallows: the transaction rolls back at its commit, the
  link with it, unseen and unlogged. Save to Matter then answered `201` for a copy with no link, and the message
  read next named no document for the attachment (the smoke test's step 9, whose Indexer runs every 5 seconds,
  2026-11-21). So `EmailFiling.link`, which `POST /emails` and Save to Matter both link with, asks the table
  whether the link's row is there and stores a new link while it isn't, three times at most; a link still not
  stored fails the filing, which takes back what it filed (`MessageResourceTest`'s saves past the Indexer's write
  and `EmailFilingTest#testFilesTickedAttachmentsPastABackgroundWriteAtTheFlush`, with `BackgroundWrite`'s
  `overtakingLinkCount`). The delete of a link that a failed filing takes back is read back the same way, as
  LogicalDOC's DAO counts the links again in that transaction: a lost delete left the email the count of a link it
  no longer had (`MessageResourceTest#testAFailureAfterTheLinkTakesTheLinkBackPastABackgroundWrite`). `link` refuses
  inside a transaction, where its read-back would count a row that isn't committed yet. A link made in the Classic
  Interface can still be lost this way.
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
