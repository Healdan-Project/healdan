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
