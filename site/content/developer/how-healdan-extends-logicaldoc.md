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
    the new name to each bookmark on it whose title is still the old one, in the store's transaction. A bookmark its
    owner named keeps its name (see the [API](../api/#favorites)).
- **`healdan-api`** is Healdan's [API](../api/), a plugin of its own that requires `healdan-core`. Its code is under
  `com.logicaldoc.healdan.api`, where `healdan-core`'s Spring scan finds it.
  - `ApiServlet` serves `/api/*`, and registers with `@WebServlet`. It runs CXF's JAX-RS on a CXF bus of its own,
    so LogicalDOC's services under `/services/` don't show up under `/api` too.
  - Spring Security leaves `/api/**` alone (`security="none"`). The API signs each request in from its device
    token, and from nothing else.
  - `openapi.yaml`, in `src/main/resources/healdan/api/`, is written by hand. It's the contract the tests check
    every answer against, so a new endpoint goes into it with the code.
  - The rules and data it uses, such as device tokens and where a property's value comes from, stay in
    `healdan-core`, so code outside the API can use them too.
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
  - **A delete may build a backend's S3 client inside LogicalDOC's transaction.** `Held.hold`, which holds a deleted
    file's copy while holding that file's lock, inside the transaction of LogicalDOC's delete, builds the backend's
    client if nothing has yet. For an `http://` endpoint that's a DNS lookup, as the endpoint rule checks the host's
    addresses. A build that failed is remembered for 30 seconds, so while DNS is down, deletes don't each wait on a
    lookup of their own.
- **`healdan-web`** serves Healdan's User app at `/app/`, and is a plugin of its own that requires `logicaldoc-core`,
  `healdan-core` and `healdan-api`. Its code is under `com.logicaldoc.healdan.app`.
  - `AppServlet` registers with `@WebServlet("/app/*")`, so `web.xml` is not edited. The built app sits in the
    module's jar under `healdan/app/user/`, and the servlet reads it from the class path.
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
  - **Where a sign-in lands.** LogicalDOC's `login.jsp` sends the password form, as `j_successurl`, to the HTTP
    session's `RequestedUrl`, which it writes unescaped into a script. Three pieces of `healdan-core`'s `web` package
    fill it. `ReturnPath` is the return path `AppServlet` stores: a path under `/app/` of letters, digits and
    `/ . _ ~ % - ? & = + *` only, with no `//` and at most 2,000 characters, or else `/app/`, so nothing that could end
    the script's string gets in. `LoginLandingFilter` (`@WebFilter("/login.jsp")`) sets it, when no return path is
    waiting, to where Start In (`ui.start`) says, `Landing.page()`: `/app/` or `/frontend.jsp`. With `?switch` it does
    nothing, so `login.jsp`'s own clearing of the value still works. `SignInResource` sends a provider's sign-in to
    the same `RequestedUrl` under `login.jsp`'s conditions, or to `Landing.page()`.
  - **Its headers.** Every answer carries a Content-Security-Policy that allows only the server's own address, with
    no inline script or style, and `nosniff` and `Referrer-Policy: same-origin`.
  - A server built without the app answers `503` at `/app/`, saying the User app wasn't built into it.
  - An `artifactItem` in `logicaldoc-webapp/pom.xml` puts its zip in the WAR. That and the root `pom.xml`'s
    `<module>` are the only upstream files it edits.
- **Its own tables.** Flyway owns the tables whose names start `hd_`, and only those. A change ships as a new
  script under `healdan-core/src/main/resources/healdan/db/migration/`, never as an edit to one that has shipped.
  Healdan never changes the structure of LogicalDOC's `ld_` tables.
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
