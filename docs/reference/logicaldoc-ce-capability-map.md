# LogicalDOC CE 9.3.2: capability map for Healdan

- **Date:** 2026-10-16
- **Method.** Three read-only code surveys of this repository, run during the roadmap session (see [`docs/ROADMAP.md`](../ROADMAP.md)). Nothing was run against a live server. Claims marked **✔ checked** were re-read in the source by hand. Treat everything else as a well-sourced lead and confirm it before relying on it.
- **Upstream merges.** This reflects the code at merge `1c2389e3d` (upstream `3085e45c3`). Line numbers will drift.
- **9.3.2.** Rechecked on 2026-10-26 against the 9.3.2 sync ([#80](https://github.com/Healdan-Project/healdan/pull/80), upstream `cfebaee9d`), which changed nothing this map records: the release number, Java 25 source, two stream leaks closed in `MailUtil` and `EMailSender`, a copiable anonymous URL and wider password fields.
- **M3.** Updated on 2026-10-25, when M3's first phase was built: §2.3 adds Healdan's Comments property and how it's searched, §2.6 the API's favourites, and §5.1 Open in App and the Healdan Helper. The [M3 plan](../superpowers/plans/2026-10-24-m3-office-integration.md) has the rulings and follow-ups.
- **M2.** Updated on 2026-10-24, when M2 was built: the sign-in throttle is **Fixed** (§5.4, §6), §4.1 points to Healdan's own API, and §6 adds what the build found.
- **M1.** Updated on 2026-10-21, when M1 was built (`4d1b2ca2d`). The CE gaps M1 fixed are marked **Fixed in M1**, and §6 adds the CE defects M1 fixed or found. The [M1 plan](../superpowers/plans/2026-10-17-m1-move-in.md) has the details: its rulings (R1–R131), decision log (D1–D8) and follow-ups.

Path shorthand:
- **CORE** = `logicaldoc-core/src/main/java/com/logicaldoc/core`
- **GUI** = `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client`
- **WEB** = `logicaldoc-webapp/src/main/java/com/logicaldoc/web`

Tooling note: `logicaldoc-webapp/src/main/resources/context.properties` is ISO-8859-1 with CRLF line endings, and some `grep` builds (ugrep) silently skip it. Use `/usr/bin/grep -a`.

## 1. How feature gating actually works

**The headline: CE gates almost everything in the GUI only.**

- **How the GUI decides.** `Feature.enabled(n)` (`logicaldoc-gui/.../gui/common/client/Feature.java`) checks for the string `Feature_<n>`.
- **Where that list comes from.** The names are `Feature` extensions declared in the plugins' `plugin.xml` files. `CORE/SystemInfo.java` (~line 120) collects them, `InfoServiceImpl.getInfo` (~line 255) sends them to the GUI, and REST `system/getInfo` returns them too.
- **The server never checks.** The server-side enum `CORE/runtime/Feature.java` (key `Feature_(ordinal+1)`) isn't checked by any service.
- **What actually blocks a feature** is a stub or a method that throws, not a check.
- **Turning a gated feature on.** Declare `Feature_<n>` in a Healdan plugin's `plugin.xml`. That's enough when the server code exists; it doesn't make missing server code appear.
- **Mismatch.** GUI feature 112 is `SUPPORT_TICKET`, but core 112 is `ACCESS_TICKET`.

**Declared in CE:**
- core: 13, 14, 27, 53, 56, 57, 97, 110
- gui: 5, 11, 22, 24, 25
- webservice: 57, 58
- webdav: 59
- cmis: 29
- dropbox: 80

27 (SPLIT) and 110 (AUTOFILL) are declared, but their RPC services have no implementation.

| # | Name | In CE? | Server reality |
|---|---|---|---|
| 5 / 11 / 53 / 56 | TAGS / TRASH / BOOKMARKS / TEMPLATE | yes | present |
| 10 | EMAIL_IMPORT | no | absent: no `EmailAccount` entity, and `EmailAccountService` has no implementation |
| 12 | SAVED_SEARCHES | no | **present**: `SearchServiceImpl` save/load/delete/share. Only `/data/savedsearches.xml` is missing |
| 15 | SINGLE_SIGNON (SAML) | no | absent |
| 20 | OFFICE ("Edit with Office", `ldedit:` protocol) | no | absent: no `/ldedit` servlet |
| 21 | ENTERPRISE_SEARCH | no | – |
| 28 | BULK_UPDATE | no | – |
| 39 / 73 | BRUTEFORCE / FIREWALL | no | **present**: `LoginThrottle`, `web/firewall/HttpFirewall`. Healdan runs the throttle on every route since M2, but its screen stays hidden (§5.4) |
| 42 | FOLDER_TEMPLATE | no | stubs only (§3.6) |
| 44 / 46 | SMB_STORE / CLOUD_STORE | no | absent |
| 48 | AUTOMATION | no | GUI only |
| 52 | MULTI_STORE | no | absent (§3.2) |
| 54 | CUSTOMID | no | field present; no numbering engine (§2.5) |
| 55 | LDAP | no | absent |
| 59 / 29 / 57 / 58 | WEBDAV / CMIS / PROTOCOLS / WEBSERVICE | yes | present |
| 62 | PARAMETRIC_SEARCHES | no | **absent**: no Search implementation for type 2 |
| 65 | QUOTAS | no | quota columns exist |
| 71 | MULTI_WORKSPACE | no | **present**: no server-side limit (§3.1) |
| 79 | MULTI_TENANT | no | blocked: `HibernateTenantDAO.store()` throws (§3.3) |
| 88 / 89 | AUTO_NAMING / AUTO_FOLDING | no | absent |
| 93 | WEBDAV_BASIC | no | Basic auth is always on anyway |
| 94 / 111 | 2FA / WhatsApp 2FA | no | partial: nothing verifies the code (§5.4) |
| 107 | ONLYOFFICE | no | absent |
| 109 | SEMANTIC_SEARCHES | no | absent |

## 2. Search and document identity

### 2.1 Search architecture

- **The `Search` extension point** (`logicaldoc-core/src/main/resources/plugin.xml:140`). CE registers three search types:
  - 0, `FulltextSearch`
  - 1, `TagSearch`
  - 3, `folder.FolderSearch`

  `SearchOptions` also defines `TYPE_PARAMETRIC=2` and `TYPE_SEMANTIC=4`, but neither has an implementation.
- **Dispatch.** `CORE/searchengine/Search.java` (`get`, `newOptions`, `search` → `internalSearch`). Optionally it adds extended-attribute values to hits (tenant setting `search.extattr`), and it logs a SEARCH history event.
- **Engine.** Embedded Apache Solr 8.11.4 (`StandardSearchEngine`).
  - Schema: `logicaldoc-core/src/main/resources/index/logicaldoc/conf/schema.xml`.
  - Indexing: `searchengine/indexer/IndexerTask`.
  - `addHit` commits to Solr after every single document.
- **Full-text flow.**
  1. Solr query over `fileName`, `title`, `tags` and `content`, plus filters.
  2. SQL on `ld_document`, which fills in the hits and applies permissions (`Search.enrichAndPopulateHits`).
- **What the index holds.**
  - Fields: `id`, `tenantId`, `title`, `fileName`, `templateId`, `folderId`, `customId` (an untokenized string), `comment`, `tags`, `content`, `notes`, and dynamic `ext_*` string fields.
  - **Only documents are indexed. Folders never are.**
- **Server entry points.**
  - RPC `WEB/service/SearchServiceImpl`. Its converter map registers only FULLTEXT, FOLDERS and TAGS. `registerConverter` is public static, so a plugin can add more.
  - REST/SOAP `SearchService`: `find`, `findByFilename`, `findFolders(name)`.
- **Gotcha.** An unregistered search type causes a NullPointerException in `doSearch`. **Fixed in M1** (Task 18, R48): `search`, `save` and `load` fall back to `FulltextOptionsConverter` (`SearchServiceImplTest.testUnregisteredTypeUsesDefaultConverter`).

### 2.2 Folder search

- **The server code is complete and not gated.**
  - `CORE/searchengine/folder/FolderSearch.java` is pure SQL on `ld_folder`, joined with `ld_folder_ext`. It includes aliases and can search a subtree.
  - `FolderCriterion` supports id, name, description, dates, creator, tags, template, folder and extended attributes.
  - The GUI's Search → Folders form isn't gated.
- **Why it feels missing.** The quick-search box (`GUI/menu/QuickSearchTray.java`) only runs full-text search, and folders aren't in Solr. So folders never appear in quick search.
- **Gotchas.**
  - It loads every matching row and filters by permission in Java, with no SQL limit.
  - `GUIUser.orderedSearches()` calls `add()` on `Arrays.asList`. That throws if a user's stored search preference lacks a search type.
  - **SQL injection**, now fixed. See §6.

### 2.3 Attribute and metadata search

- **The model** (`CORE/metadata/`): `Template` (`ld_template`, `ld_template_ext`, `ld_template_acl`) and `AttributeSet` (`ld_attributeset`, `ld_attributeset_ext`). Attribute options are in `ld_extoption`.
- **Where values are stored.** `ld_document_ext`, `ld_folder_ext`, `ld_version_ext` and `ld_generic_ext`, with typed value columns.
  - Indexes exist only on `ld_document_ext(ld_name, ld_type)`.
  - There are none on the value columns, and none at all on `ld_folder_ext`.
- **Folder-to-document inheritance already exists, in weak form.** `HibernateDocumentDAO.copyFolderMetadata` / `copyFolderExtendedAttributes` (~lines 435–478) run when a document is saved:
  - non-empty folder values are copied into empty document values;
  - only when the document has no template, or the same template as the folder;
  - as a one-time copy that later folder changes don't reach.

  Subfolders copy their parent's template and attributes when they're created (`replicateParentMetadata`). `FolderDAO.applyMetadataToTree` pushes values down on demand.
- **Searching documents by attribute in CE.**
  - It works only through full text, on Solr `ext_<name>` fields.
  - Only top-level STRING and USER attributes are indexed. Matching is exact and case-sensitive.
  - Int, double, date, boolean and reference attributes aren't indexed at all.
  - The GUI offers `ext_` fields only after a template has been picked.
- **Parametric search (SQL, type 2).** The GUI has `ParametricForm`, gated by 62. The server has nothing.
- **Folders are the one working attribute-query path,** through `FolderSearch`.
- **Healdan's Comments (M3).** A free-text string attribute, `comments`, of at most 4000 characters, after Subclass in the Healdan profile. The `profile-comments` seed adds it to the template, and it belongs to one document: it doesn't pass down (`ProfileSchema.DOCUMENT_ONLY`). Like any text attribute it's indexed only as one exact value, so Healdan's Documents search (`DocumentsOnlySearch`, the old UI's Documents scope and the API's) adds, after the full-text hits, the documents whose Comments contain every word of the query, in any case (`healdan-core/.../search/CommentsMatch.java`).
  - **The cost:** a `LIKE '%word%'` scan of `ld_document_ext.ld_stringvalue` for `ld_name = 'comments'`, one condition per word, with no index that helps. It's capped at 1000 candidates (`COMMENTS_WINDOW`), newest first, before the access checks, so a user who can read few of more than 1000 matches loses hits.
  - The matches honour the search's folder (`within` in the API), but not its other filters, such as dates or template.

### 2.4 Document IDs

- **How IDs are generated.** `CORE/PersistentObject` uses `@GeneratedValue(AUTO)` on a `long`. That gives one sequence per table (`create sequence ld_document_SEQ start with 100 INCREMENT BY 50`, `logicaldoc-core.sql:277`). **✔ checked**
- **Properties of the IDs.**
  - Global across tenants.
  - Always increasing.
  - Gaps: pooled blocks of 50 are lost on restart.
  - Documents and folders use separate sequences, so the same number exists as both.
- **Typing an ID into search.**
  - `QuickSearchTray` offers id, customId and filename modes only when feature 62 is on. Those modes build a parametric query, which has no server side.
  - In CE, typing `1234` runs a full-text search for the text "1234".
- **Going to a document by ID.** The only way is a URL deep link: `?docId=N` or `?folderId=N`. It's handled in `GUI/folder/FolderNavigator` and `GUI/panels/MainPanel`.
- **DAO lookups.** `DocumentDAO.findById` and `findByCustomId(customId, tenantId)`.

### 2.5 Custom IDs

- **The field.** `AbstractDocument.customId` → `ld_document.ld_customid` varchar(200).
  - Unique index `AK_DOCUMENT(ld_customid, ld_tenantid)`, so it's unique per tenant, not system-wide.
  - Also on `ld_version`, also indexed in Solr.
  - The ACL tables have a `CUSTOMID` permission column.
- **CE overwrites it with a random UUID on every save.** `HibernateDocumentDAO.store` (~line 310) does this whenever the `CUSTOMID` aspect is off, which is the default. **✔ checked**
  - CE declares no `customId` aspect.
- **When the aspect is on:**
  - document listeners (`beforeStore`) may set the value;
  - an empty value becomes a UUID;
  - if it's still empty after saving, it becomes the ID;
  - uniqueness is checked only under the `UNIQUENESSCUSTOMID` aspect;
  - on delete, `.<id>` is appended.
- **There is no numbering engine.**
  - `CORE/sequence/{Sequence, SequenceDAO, HibernateSequenceDAO}` (`ld_sequence`) is a generic named counter with next and reset. It's used only for stats, API counters and login throttling.
  - GUI `SchemeService` and `CustomIdPanel` have no server implementation and no servlet mapping.

### 2.6 Saved searches and bookmarks

- **Saved searches: the server side is complete.**
  - `CORE/searchengine/saved/*`, table `ld_search`.
  - RPC `SearchServiceImpl` save, load, delete and share.
  - The GUI gates it with 12.
  - Options are serialized with `XMLEncoder`/`XMLDecoder`. See §6.
- **Bookmarks: present and enabled (53).**
  - `CORE/document/Bookmark`, table `ld_bookmark`. Columns: `userId`, `targetId`, `title`, `description`, `position`, and `type` (DOCUMENT or FOLDER).
  - `BookmarkDAO`.
  - RPC `DocumentService.addBookmarks`, `deleteBookmarks`, `updateBookmark`.
  - `/data/bookmarks.xml`.
  - Folder bookmarks can back "My Matters" with no new storage. M1's My Matters is built on them.
- **The API's favourites (M3).** `GET`, `POST /me/favorites` and `DELETE /me/favorites/{id}` are these bookmarks, apart from those on matter workspaces (My Matters), through `BookmarkDAO`, and leave out what the user can't read. The old UI's Bookmarks list (`/data/bookmarks.xml`) hides deleted targets but doesn't check read access.
- **Folder bookmarks: Fixed in M1** (Task 20). `DocumentServiceImpl.addBookmarks` looked for an existing bookmark by document ID even for a folder, so a folder could be bookmarked twice. It now looks a folder up by folder (`findByUserIdAndFolderId`), and skips a folder that is missing, deleted or unreadable (`DocumentServiceImplTest.testFolderBookmarkNotDuplicated`). The M1 spec also suspected that `/data/bookmarks.xml` listed deleted folders. It doesn't: `@SQLRestriction("ld_deleted=0")` hides them, and `BookmarksDataServletTest` guards that (M1 plan, D8).

## 3. Containers, storage, tenants, security

### 3.1 Workspaces (LogicalDOC sense)

- **There's no separate entity.** A workspace is an `ld_folder` row with `ld_type=1` (`Folder.TYPE_WORKSPACE`), directly under the tenant root `/`.
  - Root is `ROOTID=5`. The default workspace is `DEFAULTWORKSPACEID=4`, named "Default".
  - Aliases are `TYPE_ALIAS=2`.
- **DAO** (`HibernateFolderDAO`):
  - `findWorkspaces`, `findWorkspace` (walks up the parents), `findDefaultWorkspace`;
  - `workspaceChecks()`: workspaces can't be moved, and "Default" can't be renamed;
  - `checkIfCanDelete`.
- **Gated only in the GUI, by 71** (`ContextMenu`, `ItemFactory.newWorkspaceSelector`).
  - `FolderServiceImpl.create()` re-parents any type-1 folder to the root and creates it, with no feature, admin or ADD-permission check. **Fixed** (§6).
  - SOAP `SoapFolderService.create` accepts type 1 under any parent. The result is malformed, and `findWorkspaces` won't list it. **Fixed in M1** (Task 9): Healdan's structure rules refuse a type-1 folder (a Store) anywhere but directly under the root, and from anyone but an admin, on every route, type changes included (`StructureRulesTest.testOnlyAdminsCreateStores`).
- **Already there.**
  - `User.defaultWorkspace` (`ld_defworkspace`).
  - REST `folder/listWorkspaces`.
- **Gotcha.** The per-workspace counters `wsdocs`, `wspages` and `wssize` are read but never incremented in CE.

### 3.2 Storage backends (LogicalDOC `Store`)

- **One bean.** `<bean id="store" class="${store}"/>` in `context-core.xml`, which is `FSStore`.
  - Keys: `store.write=1`, `store.1.dir`, `store.1.type=fs`, `store.1.encryption`, `store.1.compression`.
- **The extension point is unused.** A `Store` extension point exists, but CE registers no implementations, so `newStore(id)` returns null. S3, Azure and SMB are EE only.
- **All reads and writes go to store 1.** `AbstractStore.id` is fixed at 1.
- **The per-folder mapping exists but isn't used.**
  - `Folder.stores` (`ld_folder_store`, node → storeId).
  - `DocumentManager.getStore(Folder)` resolves it through ancestors, falling back to `store.write`.
  - The only consumer, `enforceFilesIntoFolderStore`, ends in `FSStore.moveResourcesToStore`, which throws `UnsupportedOperationException`.
- **No backend id is recorded per document or version** (`ld_document`, `ld_version`).
- **Gating.** GUI 52, 44 and 46. The server-side settings save and remove are ungated.

### 3.3 Tenants

- **Model.** `CORE/security/Tenant` (`ld_tenant`, with quotas) and `HibernateTenantDAO`.
- **Creating tenants is blocked.** `HibernateTenantDAO.store()` always throws "Feature not enabled". **✔ checked**
  - There's no `TenantServiceImpl`.
  - GUI 79.
- **A user belongs to exactly one tenant,** and usernames are unique across the system.
- **One shared Solr index.** It's filtered by tenant only when there's more than one tenant.
- **Conclusion:** too isolating to be a Healdan Store.

### 3.4 Security model

- **ACL tables.** `ld_folder_acl`, `ld_document_acl`, `ld_template_acl`, `ld_menu_acl` and `ld_note_acl`. Each row is (objectId, groupId) plus about 25 int permission flags.
- **The `Permission` enum.** READ, DOWNLOAD, WRITE, ADD, SECURITY, IMMUTABLE, DELETE, RENAME, IMPORT, EXPORT, SIGN, ARCHIVE, WORKFLOW, CALENDAR, SUBSCRIPTION, PRINT, PASSWORD, MOVE, EMAIL, AUTOMATION, STORE, READINGREQ, PREVIEW, CUSTOMID, REVISION.
- **Inheritance.** `Folder.securityRef` points to the single folder that owns the effective ACL. `applySecurityToTree` and `inheritACL` re-point a subtree.
- **Folder evaluation.** `HibernateFolderDAO.getAllowedPermissions` ORs every row for the user's groups.
  - **There are no deny entries.**
  - **Members of the `admin` group bypass everything.**
- **Document evaluation.** `HibernateDocumentDAO.getAllowedPermissions` uses the document's ACL only if the user matches one of its rows; otherwise it falls back to the folder. So a document ACL can't exclude anyone.
- **Search filtering** (`findFolderIdByUserId*`) also works only from allow sets.
- **Each user has a personal group.** Memberships are in `ld_usergroup`.

### 3.5 Folder metadata

- Folders are `ExtensibleObject`s: `ld_folder.ld_templateid`, `ld_templocked`, and values in `ld_folder_ext`.
- TEMPLATE (56) is enabled.
- Inheritance is described in §2.3.

### 3.6 Aliases and folder templates

- **Aliases are present and ungated.** `TYPE_ALIAS` plus `foldRef`, `createAlias` with cycle detection, and `updateAliases`.
- **Folder templates (create a folder structure from a template)** are gated in the GUI by 42. On the server, `FolderServiceImpl.loadTemplates`, `saveTemplates` and `applyTemplate` are **empty stubs**, and there's no entity or table. This has to be built.

## 4. APIs

### 4.1 REST

- **Healdan's own API.** M2 added a JSON API at `/api/v1` (`healdan-api`), with device tokens that work only there, for Healdan's new clients. It's described by `healdan-api/src/main/resources/healdan/api/openapi.yaml`; see the [M2 spec](../superpowers/specs/2026-10-23-m2-api-foundation-design.md) and the docs site's Developer Guide, API page. Everything below is CE's REST, which stays as it is.
- **Where it lives.**
  - Interfaces: `logicaldoc-webservice/.../rest/*.java`.
  - Implementations: `rest/endpoint/Rest*Service`, each extending its `Soap*Service`.
  - Wired as CXF `jaxrs:server` beans in `context-webservice.xml`, at `/services/rest/<area>`.
- **About 129 RPC-style operations:**

  | Area | Ops | Coverage |
  |---|---|---|
  | auth | 8 | login (GET, form, JSON), API key, logout, getSid, renew |
  | document | 55 | the CRUD set, versions, check-out/in, lock, notes, links, tickets, ACL, sendEmail, … |
  | folder | 26 | CRUD, paths, listChildren, listWorkspaces, alias, ACL |
  | search | 3 | find, findByFilename, findFolders |
  | tag | 11 | |
  | bookmark | 7 | |
  | documentMetadata | 15 | templates, attribute sets, options |
  | system | 4 | getInfo, which includes the feature list |

- **SOAP mirrors REST** and adds a Security service for user and group CRUD. **REST has no security service.**
- **Authentication** (`logicaldoc-webapp/src/main/resources/context-security.xml`):
  - `/services/rest/auth/**` is open, and everything else under `/services/rest/**` requires authentication.
  - `SessionFilter` reads a `sid` from a parameter, a header, an attribute or the `ldoc-sid` cookie.
  - An `X-API-KEY` header goes through `ApiKeyAuthenticator`. Keys start with `ld-`.
  - **An open session is reused without re-authenticating.** `SessionManager.getSessionFromApiKey` finds an open session whose key hash matches the `X-API-KEY` and uses it, with no check of the key, its user or the chain (✔ checked). So a key keeps working as long as its session is open. An M2 device token isn't a key: it works only at `/api`, and LogicalDOC's routes refuse it (M2 spec §5.1). The sessions the API opens for a token are keyed by a one-time key that no client knows, so this lookup never finds them from the token.
  - **`SessionFilter` can't open a session from a key.** With no open session it calls `newSession(null, null, key, request)`, and `AuthenticationChain.authenticate` calls `username.contains(...)` on the null username, so the request fails (✔ checked). A key signs in through `/services/rest/auth/loginApiKey` or SOAP's `loginApiKey`, after which the reuse above applies. Found while building M2.
  - HTTP Basic works when `webservice.basicauth.enabled=true`, which is the default.
  - There are no JWT, OAuth bearer or refresh tokens.
  - **There's no CORS.**
- **OpenAPI.** The endpoints carry swagger annotations, but spec generation lives only in `logicaldoc-webservicedoc`, which **isn't shipped in the WAR**.
- **What the GWT UI uses that REST lacks.**
  - Current user, profile, menus and permissions.
  - Users and groups (SOAP only).
  - Change password, API-key management, sessions.
  - The 52 `/data/*.xml` grid servlets (`WEB/data/*DataServlet`).
  - History and audit, subscriptions.
  - Trash and locked-document listings.
  - Bulk operations.
  - Settings, system, search engine, messages, dashlets, contacts, saved searches.
- **Push updates.** The `/wk-event` websocket sends GWT-serialized payloads, which a non-GWT client can't read. It's on as shipped (`default.gui.serverpush=true`), and until Healdan's fix on 2026-10-23 it sent every event to every connected user without a permission check (§6).
- **Reusable as they are:** `/download`, `/convertpdf`, `/thumbnail`, `/display`, `/download-attachment` and `/upload` all accept `sid` through `SessionFilter`.

### 4.2 WebDAV and CMIS

- **WebDAV** (`logicaldoc-webdav`, Jackrabbit, `/webdav/*`).
  - Basic auth.
  - Supports GET, PUT, PROPFIND, MKCOL, COPY, MOVE and DeltaV check-in/check-out.
  - **Advertises DAV `1, version-control`, with no class 2.**
    - `doLock` turns LOCK into a check-out and returns no lock token.
    - `DavResourceImpl.lock`/`unlock` throw.
  - A PUT on an existing file makes a new version.
  - **Consequence:** Word and LibreOffice will likely open read-only or fail to save, and macOS Finder mounts the share read-only.
- **CMIS** (`logicaldoc-cmis`, `/service/cmis/*`).
  - AtomPub binding only, CMIS 1.0, Basic auth.
  - Supports check-out and check-in, versions, `setContentStream`, and full-text-only query.
  - AtomPub is the binding LibreOffice's libcmis uses. Untested.

## 5. Office, email, authentication

### 5.1 Office

- The GUI's "Edit with Office" (20) opens an `ldedit:` URL, with the CE session id in it. No `/ldedit` servlet exists.
  - **Open in App (M3).** `healdan-core`'s `plugin.xml` declares feature 20, so the toolbar button and the right-click item show, labelled "Open in App" in the English bundles. `Util.openEditWithOffice` calls Healdan's hook (`common.client.healdan.HealdanOpenInApp`), which opens `healdan://open/<doc #>?server=<host>` with no credentials, and shows a notice linking to the helper's install page. It's on for one document of the kinds the helper opens, that the user can download, checked out or not. The Tools menu item stays hidden: it also needs menu −1090, which has no `ld_menu` row. Its click goes through `Util.openEditWithOffice` too, so no caller builds an `ldedit:` link any more (the M3 final review).
- **The Healdan Helper (M3).** `clients/helper/` is a .NET app on each user's computer, with a LibreOffice extension in `clients/libreoffice/` (Python, UNO). It signs in to `/api` with a device token, keeps local copies, checks documents out on open and in on close, and sends each save as a snapshot. It's what "Edit with Office" now opens, for LibreOffice on macOS; Linux comes in M3's second phase, and Microsoft Office in M3.5. See the M3 spec.
- The `OnlyOfficeService` interface has no implementation.
- Nothing refers to WOPI.
- No Office add-in code is in the repository.
- The usable server endpoints were SOAP/REST checkout, checkin, lock, unlock, getContent, upload and create. Healdan's own API (§4.1) has replaced them for Healdan's clients.

### 5.2 Email

- **Present.**
  - `CORE/communication/MailUtil` parses EML (Jakarta Mail) and MSG (`com.auxilii.msgparser`), and extracts attachments.
  - `EMail`, `EMailAttachment`, `Recipient`.
  - `PSTWorker` and `PstConverter`.
  - `DocumentServiceImpl.extractEmail` and `saveEmailAttachment` drive previews and saving an attachment as a document.
  - `/download-attachment`.
- **SMTP.** `EMailSender` supports `smtp` and `smtpmicrosoft365` (XOAUTH2), configured by the tenant settings `default.smtp.*`.
- **Outgoing mail can be archived** as `.eml` documents with an `email` template (from, to, cc, subject, …). **CE doesn't seed that template.**
- **Absent.**
  - IMAP/POP import. There's no `EmailAccount` entity, and `EmailCrawler` appears only as a logger name. The GUI client code exists.
  - Inbound "email to DMS" addresses.

### 5.3 Full-text extraction gap ✔ checked

- `CORE/parser/ParserFactory` registers parsers for doc, dot, htm(l), pdf, rtf, sx*, od*/ot*, KOffice, AbiWord, plain text, xml, xls, ppt, zip, gz, rar, tar, 7z, epub and markdown.
  - **There is no parser for docx, xlsx, pptx, eml or msg.**
  - No `Parser` extensions are declared in any `plugin.xml`.
- **What happens to those files.** They fall to `CatchAllParser`, which tries to convert them to PDF. CE has no converter for these formats:
  - `converter.LibreOfficeConverter.*` keys exist in `context.properties`, but there's no such class in the source;
  - the image didn't install LibreOffice;
  - so the result was `NotAvailableConverter`.
  - **Changed in M1.5:** Office formats now convert through Healdan's `OfficeConverter`, with LibreOffice in the image. `CatchAllParser` gets real text for the formats Healdan has no parser for.
- **Expected result:** the text inside Word, Excel, PowerPoint and email files isn't searchable. Confirm on a running server.
- **The fix belongs in a Healdan plugin,** through the `Parser` extension point (`extension`, `class`), for example with Apache POI or Tika.
- **Fixed in M1** (Task 21). M1 confirmed the gap on a running stack first (M1 plan, D5). `healdan-core` now declares parsers for `docx`, `docm`, `dotx`, `xlsx`, `xlsm`, `pptx`, `ppsx`, `eml` and `msg`, built on POI and `MailUtil`. A first-start seed flags existing documents of those types for re-indexing.

### 5.4 Authentication

- **The extension point.** `Authentication` (`authenticatorId`, `position`).
  - CE declares `DefaultAuthenticator` (100) and `ApiKeyAuthenticator` (110).
  - `AuthenticationChain` sorts them by position, and handles anonymous login and impersonation.
  - Every channel goes through `SessionManager.newSession`, which calls the chain: GUI, REST, SOAP, WebDAV and CMIS.
- **Spring Security 6.3.10** (`context-security.xml`).
  - Separate `http` blocks for setup, WebDAV, CMIS and REST.
  - Form login at `/j_spring_security_check`.
  - `LDAuthenticationDetails` carries the 2FA key.
  - No OAuth2, SAML or LDAP Spring modules are on the classpath.
- **What's there per method:**

  | Method | CE server side |
  |---|---|
  | DB password, API key | present |
  | LDAP/AD (55) | absent (`LDAPService` has no implementation) |
  | SAML (15) | absent |
  | OIDC login | absent |
  | 2FA (94) | partial: columns, settings and trusted devices exist, but **nothing verifies the code** |
  | Brute-force throttle (39) | **Fixed in M2**: runs on every route, on by default with Healdan's limits (§6). Its settings screen stays hidden, because nothing declares feature 39, so admins change `throttle.*` in `context.properties` |
  | Firewall (73) | present |

- **`TokenProvider` is outbound only.** It provides OAuth for Microsoft 365 SMTP and IMAP, and has nothing to do with inbound API tokens.

## 6. Defects found (fix in Healdan; consider reporting upstream)

Rows marked **Fixed** or **Hardened** were verified with failing tests and fixed on 2026-10-17. Each fix names the test that shows it. The first four came from the recon. The others were found while fixing those, or in the review of PR #16 on 2026-10-17 where a row says so. Rows marked Open are still to do.

The rows from the M1 build (2026-10-18 to 2026-10-21) come last. They name the M1 plan's task, ruling (R) or decision (D), and the follow-up section an open one comes from. The PRs they name:
- [#28](https://github.com/Healdan-Project/healdan/pull/28), the follow-ups PR;
- [#29](https://github.com/Healdan-Project/healdan/pull/29), the gaps PR;
- [#30](https://github.com/Healdan-Project/healdan/pull/30), the web-services PR;
- [#32](https://github.com/Healdan-Project/healdan/pull/32), the WebDAV and CMIS PR;
- [#33](https://github.com/Healdan-Project/healdan/pull/33), the ACL equality PR;
- [#44](https://github.com/Healdan-Project/healdan/pull/44), the pre-acceptance PR, which fixed four gaps before the owner's acceptance walkthrough.

Every upstream edit those fixes make is listed in the [M1 spec](../superpowers/specs/2026-10-17-m1-move-in-design.md)'s §13.

| Severity | Where | What | Status |
|---|---|---|---|
| High | `CORE/searchengine/folder/FolderSearch.java` ✔ checked | A criterion's field name went straight into the SQL, as the extended attribute name and as the column name. The field comes from the search RPC, so any user could rewrite the query. The language value had no escaping at all. String values only had their quotes doubled, which a backslash defeats on MariaDB and MySQL: `zzz\' or 1=1 -- ` matched every row on `mariadb:11.4` with the default `sql_mode`, which Healdan's Docker stack uses. | **Fixed.** Names and values are bound as parameters, and a column name must be an identifier. `FolderSearchTest`. |
| High | `IOUtil.deserialize`, used for `ld_search.ld_options` ✔ checked | Saved-search options are read back with `java.beans.XMLDecoder`, which runs any code the XML names. **Users can't control that XML today.** `SavedSearch.saveOptions()` is the only writer, and XMLEncoder escapes everything users type. `shareSearch` copies a row as is, and no REST or SOAP endpoint touches `ld_search`. A row written some other way, such as SQL injection or a tampered backup, would still run code. | **Hardened.** `XMLDecoderGuard` (util) allows only what the encoder writes for the search option classes. `SavedSearchTest`, `XMLDecoderGuardTest`. |
| Medium | `WEB/service/FolderServiceImpl.create` ✔ checked | No ADD-permission check, only session validation. It also let any user create workspaces. | **Fixed.** Needs ADD on the parent, or on an alias parent's target. Workspaces need the admin group. `FolderServiceImplTest`. |
| Medium | `HttpUtil.getNotValidatingClient`, used by `Microsoft365TokenProvider` ✔ checked | The token request sent the OAuth client secret through a client that trusts any certificate. | **Fixed.** New `HttpUtil.getValidatingClient`. `Microsoft365TokenProviderTest` impersonates the endpoint through a proxy. |
| High | `SqlUtil.doubleQuotes` callers: `CORE/folder/HibernateFolderDAO`, `HibernateFolderHistoryDAO`, `CORE/document/HibernateDocumentDAO`, `HibernateDocumentHistoryDAO`, `CORE/metadata/HibernateAttributeOptionDAO`, `WEB/service/SecurityServiceImpl`, `SystemServiceImpl` ✔ checked | Same quoting as FolderSearch had, so the same backslash bypass applied on MariaDB and MySQL. Every call site was traced. User input reached `HibernateFolderDAO` (name lookups, `create`'s unique name, tag lookups), `HibernateDocumentDAO` (file names on store and lookup, custom IDs, the tag search for non-admins, the unique-tag cleanup fallback), `HibernateDocumentHistoryDAO.markHistoriesAsRead`, `HibernateAttributeOptionDAO.deleteOrphaned`, `SecurityServiceImpl.searchUsers` and `SystemServiceImpl.search`. In the same queries `searchUsers` took its group ID with no quoting at all, and `search` its session ID and impersonator. No user input reaches the rest: `HibernateFolderDAO`'s `ld_path` queries (paths are built from IDs), `HibernateFolderHistoryDAO.findByUserIdAndEvent` (no caller passes an event) and `HibernateDocumentHistoryDAO`'s event lookups (an enum, or constants). | **Fixed.** Values are bound as parameters, and the group ID must be a number. The `*InjectionTest` classes in core and webapp use `SqlRecorder` (util) to check that a value never reaches the SQL text. |
| High | `WEB/service/SystemServiceImpl.searchApiCalls` ✔ checked | The session ID and protocol filters went into the SQL with no escaping at all, and any logged-in user could call it, so a plain quote broke out on every database. A session ID of `zzz' or 1=1 -- ` returned every call. The comment also cut off the tenant condition, so a user outside the default tenant got every tenant's calls. The user ID, dates and tenant ID are numbers and timestamps. | **Fixed.** Both are bound as parameters. `SystemServiceImplInjectionTest`. |
| High | `WEB/service/SystemServiceImpl.search`, `searchApiCalls` ✔ checked | They only checked for a session, while the other log RPCs check `Menu.LOGS`. Their results hold every user's session IDs, including the live ID that each login records (`event.user.login`). `SessionManager.getSessionId` accepts a session ID from the `sid` header (always), the `sid` parameter (with `security.acceptsid`) or the `ldoc-sid` cookie, so any user could take over an administrator's session. A test before the fix confirmed it end to end: as the non-admin `author`, `search` returned the admin's login session ID, and a request with it in the `sid` header validated as the admin. | **Fixed.** Each search needs a menu that opens a screen running it: Reports > Last changes (`-2`) for `search`, Reports > API calls (`-8`) or Settings > Protocols (`3`) for `searchApiCalls`. `Menu.LOGS` opens neither screen, so it wasn't used. Only administrators have these menus by default. Session IDs are left out unless the user can open `Menu.SESSIONS`, as the document, folder and user history data servlets already do, so a delegated report doesn't hand out sessions. The GUI's Reports > API calls button checked the Tickets menu (`-7`) and now checks `-8`. `SystemServiceImplAuthorizationTest`. |
| High | `WEB/service/SystemServiceImpl`: `restart`, `startTask`, `stopTask`, `saveTask`, `enableTask`, `disableTask`, `unscheduleJobs`, `confirmUpdate`, `setGUILanguageStatus`, `getStatistics`, `loadTasks`, `getTaskByName`, `getPlugins` ✔ checked | None checked a menu. `startTask` and `stopTask` didn't check the session either, though `/frontend/**` still needs a login. So any logged-in user could restart the server, run, stop or reschedule any task, unschedule jobs, reset the run level and switch the tenant's GUI languages. The read RPCs showed anyone the repository statistics, the task schedules with their report recipients, and the installed plugins with their versions. Tests before the fix let the non-admin `author` through every one. `restart` got as far as forking `bin/restart.sh`, which Healdan's Docker image allows. | **Fixed.** Each RPC needs the menu of the screen that calls it, so a screen delegated through menu security keeps working. The task and job RPCs need System (`80`), because the Scheduled tasks screen has no menu of its own. `getStatistics` and `getPlugins` need System > General (`70`), and `setGUILanguageStatus` needs Settings > GUI settings (`102`). `restart` needs Restart (`-1190`) and `confirmUpdate` needs Updates and patches (`-1130`). CE's database has neither of those two menus, so only administrators pass, and the GUI shows neither button in CE. Only administrators have the other menus by default. `StatChartServlet` draws its pies through `getStatistics`, so it needs System > General too. The GUI also limits the restart, the update confirmation and task changes to the default tenant. CE can't create other tenants (§3.3), so the server doesn't repeat that. `SystemServiceImplAuthorizationTest`. |
| High | `WEB/service/SettingServiceImpl.saveSettings`, `removeStore` | `saveSettings` writes whatever keys it's given into `context.properties` (two GUI settings go to the database instead), and `removeStore` deletes a store's settings. Both only check the Administration menu (`2`), which the default `poweruser` group holds. So a power user can rewrite the server's configuration, for example to point a store at another directory or relax the firewall. `saveFirewallSettings` and `saveStoreSettings` go through `saveSettings`. Its callers sit behind many different menus, so the fix needs a check per screen or per key. Found while fixing `SystemServiceImpl`. Not tested. | Open. |
| Low | `WEB/service/SystemServiceImpl.searchApiCalls` | Ignores its `uri` argument, so the API calls screen's URI filter does nothing. | Open. |
| Low | `WEB/data/TasksDataServlet`, `JobsDataServlet` | They only check the session, so any user can list the scheduled tasks with their status and schedule, and the scheduled jobs with their triggers. They fill the Scheduled tasks screen, whose RPCs now need the System menu. Found while fixing `SystemServiceImpl`. | Open. |
| Medium | Other values concatenated into SQL with no escaping: `CORE/document/HibernateDocumentHistoryDAO` (`findByUserIdAndEvent`'s session ID, `findByPath`'s events), `CORE/folder/HibernateFolderHistoryDAO.findByPath` (events), `HibernateDocumentDAO.cleanExpiredTransactions`, `CMIS/LDRepository` (events), `WEB/service/SetupServiceImpl` (language) | Found while auditing the `doubleQuotes` callers. Whether user input reaches them hasn't been checked. | Open. |
| Low | `CORE/document/HibernateDocumentDAO.findDocIdByTag` | Uses `doubleQuotesAndBackslashes`, which can't be broken out of, but databases that don't treat a backslash as an escape (PostgreSQL, SQL Server, Oracle) see it doubled. The admins' tag search then misses tags that contain a backslash. | Open. Bind the tag. |
| Low | `CORE/document/HibernateDocumentDAO.cleanUnexistingUniqueTagsOneByOne` | Decides which tags are unused with Java's `Set.contains`, not the database's collation. On a case-insensitive collation (SQL Server's default), deleting an unused case variant such as `'foo'` also removes the still-used `'Foo'` row. It also sends one DELETE per unused tag. | Open. Send the deletes as a JDBC batch. Found in the review of PR #16 on 2026-10-17. |
| Low | `WEB/service/SecurityServiceImpl.searchUsers` | Runs `Long.parseLong(groupId)` outside the `try`, so a non-numeric group ID throws a raw `NumberFormatException` instead of a `ServerException`. The query also has no `ld_tenantid` filter, so it returns users from every tenant. That's latent while CE can't create tenants. | Open. Found in the review of PR #16 on 2026-10-17. |
| Low (test coverage) | `WEB/service/SystemServiceImplInjectionTest` | Only asserts empty results. Nothing shows that a bound event or session ID still returns matching rows across the `UNION`, where `:event0` and `:sessionId` repeat in every branch. | Open. Add that positive case. Found in the review of PR #16 on 2026-10-17. |
| Low (hardening) | `SqlUtil.doubleQuotes`, `doubleQuotesAndBackslashes` | Still public and not deprecated, so nothing flags new uses. | Open. Mark them `@Deprecated`, pointing at bound parameters. Found in the review of PR #16 on 2026-10-17. |
| Low (test only) | `SqlRecorder` (util) | The proxy answers `equals` by identity but forwards `hashCode` to the wrapped object, so the two disagree. | Open. Found in the review of PR #16 on 2026-10-17. |
| Medium | `WEB/service/FolderServiceImpl.createAlias` ✔ checked | No permission check either. Any user can put an alias in any folder. The alias takes the target's ACL, so it doesn't widen read access, but it copies the target's name and description into a folder others can see. | **Fixed.** Needs ADD on the parent and READ on the target. `FolderServiceImplTest`. |
| Medium | Other `HttpUtil.getNotValidatingClient` callers: `CORE/security/Geolocation.syncDB`, `CORE/automation/HttpTool`, `WEB/service/SettingServiceImpl.testProxy` ✔ checked | `Geolocation` sends the MaxMind license key in the download URL. `HttpTool` is the automation scripts' HTTP helper. | **Fixed.** All three use `getValidatingClient`. A script that calls a server with a private or self-signed certificate needs that certificate in the JVM's trust store (`-Djavax.net.ssl.trustStore=…`, through `CATALINA_OPTS` in Docker). `testProxy` validates too, so it fails for a TLS-intercepting proxy whose CA the JVM doesn't trust, as the real callers would. `StatsCollector` (plain HTTP, see its row) and `HttpUpload` (no callers) still use the non-validating client. `GeolocationTest`, `HttpToolTest`, `SettingServiceImplTest`. |
| Low | `CORE/communication/oauth/Microsoft365TokenProvider` ✔ checked | Logs the access token, decoded, and part of the response at debug level. The part is everything after the 150th character, which holds most of the raw token. `Geolocation.syncDB` likewise logged its download URL, MaxMind license key included, at info level. | **Fixed.** Neither secret is logged. `Microsoft365TokenProviderTest`, `GeolocationTest`. |
| Low | `WEB/service/SearchServiceImpl.shareSearch` ✔ checked | Any user can add their saved searches to any other user's list. Nothing checks the target users or groups. | **Fixed.** Every target user and group must exist and be in the sharer's tenant, which is what the GUI's user and group pickers list. Within a tenant anyone can still share with anyone, as in the GUI. A shared search runs with the recipient's own permissions. `SearchServiceImplTest`. |
| Low | `CORE/searchengine/saved/SavedSearch(SavedSearch)` | The copy constructor, which `shareSearch` uses, drops `tenantId`, so a shared copy is filed under the default tenant even when sharer and recipient are in another one. Lookups go by user, so the recipient still sees it. | Open. |
| Low | `CORE/stats/StatsCollector` | Posts usage statistics to `http://stat.logicaldoc.com`, unencrypted. | **Fixed** by the pre-acceptance PR (R135): the upload is gone, at the owner's call. The task keeps the local statistics System > General shows. `StatsCollectorTest.testSendsNothingOut`. |
| Low | `GUIUser.orderedSearches()` | `add()` on `Arrays.asList` throws `UnsupportedOperationException` for some stored preferences. | Open. |
| Low | GUI vs core `Feature` 112 | Name mismatch (`SUPPORT_TICKET` vs `ACCESS_TICKET`). | Open. |
| High | `WEB/data/DocumentsDataServlet.retrieveExtendedAttributesValues` ✔ checked | A folder listing took the attribute names from the `extattr` request parameter (or the `<tenant>.search.extattr` setting), wrapped each in quotes and joined them into `ld_name in (...)` with no escaping, so any signed-in user could rewrite the query through `/data/documents.xml?folderId=…&extattr=…`. Nothing else in the servlet puts the names into SQL. Found while building PR D (M1 plan, R96). | **Fixed** on 2026-10-21. The names are bound as parameters, as the docIds mode PR D added binds them. Unlike the rows above, no failing test came first: `DocumentsDataServletTest.testFolderModePrintsExtendedAttributes` only shows that ordinary names still print their values. |
| Medium | `WEB/ConvertPdf` | Reads only the `version` request parameter, while the preview viewer sends `fileVersion`, so every older version of a PDF previews as the current one (CE's own Versions tab too). | **Fixed** in Healdan by Task 25 (R119): it serves the `fileVersion` asked for, checked against the document's versions, and takes the file type from that version's name; `ConvertPdfTest`. M1.5 added a hook: `ConvertPdf` now serves conversions of non-PDF versions when a real converter exists for them, passing the `Version`. |
| High | `WEB/service/DocumentServiceImpl`, `FolderServiceImpl`, `WEB/data/VersionsDataServlet`, `DeletedDocsDataServlet`, `DeletedFoldersDataServlet` ✔ checked | Many RPCs checked no permission on what they read or wrote, or checked the folder where a document was read or written. Some examples: `saveACL` and `inheritACL` (SECURITY), `rename` (RENAME), `getById`, `getVersionsById` and `/data/versions.xml` (READ), and `save`, which also took a shortcut's target from the client. `checkin` had no WRITE check, and `deleteBookmarks` didn't check the bookmark's owner. Also `delete`, the trash purges, notes, links, ratings, tickets, copies and pastes, and the deleted-items reports. A document-only grant couldn't use the calls that checked the folder. | **Fixed in M1**: Task 14 (SECURITY and RENAME), Task 19 (D7, R51), the follow-ups PR (R57–R69) and the gaps PR (R70–R77). Each call is listed under the M1 plan's "Follow-ups from PR C" and "Follow-ups found while closing PR C's". `FolderServiceImplTest`, `DocumentServiceImplTest`, `DocumentOnlyGrantTest`, `RpcPermissionTest`, `RpcPermissionGapsTest`. |
| High | `logicaldoc-webservice` `SoapDocumentService`, `SoapFolderService` ✔ checked | The same gaps through SOAP and REST: document `copy` checked nothing; folder `copy` checked only ADD on the target; folder `delete` and `merge` checked the top folder only; `sendEmail` checked the folder's READ and silently left out the rest; `unprotect` checked nothing; document `delete` checked the folder's DELETE. | **Fixed in M1** by the web-services PR (R78, R79). `SoapPermissionGapsTest`. |
| High | `logicaldoc-webdav` `ResourceServiceImpl`, `logicaldoc-cmis` `LDRepository` ✔ checked | WebDAV and CMIS checked only the folder given. So a user who could delete a folder deleted its whole tree, and could delete or copy a document they had no DELETE or DOWNLOAD on. | **Fixed in M1** by the WebDAV and CMIS PR (R80). `ChannelTreePermissionsTest` (`healdan-core`). |
| Medium | SOAP/REST `SoapFolderService.create` | Makes a folder of whatever type the caller asks for, including a workspace (type 1) under any parent (§3.1). | **Fixed in M1** (Task 9) by Healdan's structure rules, on every route. `StructureRulesTest.testOnlyAdminsCreateStores`. |
| Medium | `CORE/document/DocumentAccessControlEntry.equals` ✔ checked | Never true: it compared the wrapped entry, which only equals its own class. So every flush of a document read with `findById(id, true)` deleted and re-inserted its ACL rows and bumped its record version and last-modified date. Every CMIS call on such a document marked it modified. | **Fixed in M1** by the ACL equality PR: equal by group, as `ld_document_acl`'s key. `DocumentAclEqualityTest`. |
| Low | `WEB/service/DocumentServiceImpl.addBookmarks` ✔ checked | Looked for a folder's existing bookmark by document ID (§2.6). | **Fixed in M1** (Task 20). `DocumentServiceImplTest.testFolderBookmarkNotDuplicated`. |
| Low | `WEB/data/DocumentsDataServlet`, `docIds` mode ✔ checked | Didn't load the extended attributes the grid asks for, so a listing by IDs showed those columns empty. | **Fixed in M1** (Task 24, R96, R99): bound like folder mode's. `DocumentsDataServletTest.testDocIdsModePrintsExtendedAttributes`. |
| Low | `WEB/service/SearchServiceImpl` ✔ checked | An unregistered search type threw a NullPointerException (§2.1). | **Fixed in M1** (Task 18, R48). `SearchServiceImplTest`. |
| Low | GUI `document/DocumentsPanel.openInFolder` | Open in Folder adds the folder to the tree's selection, so the folder selected before stays selected and the folder menu acts on both. | **Fixed in M1** (Task 26, R128): the selection is cleared first. Checked on the stack; the GUI has no unit tests. |
| Medium | `WEB/service/FolderServiceImpl.save` ✔ checked | No WRITE check. Any user can change any folder's description, tags, extended attributes, quotas, grid, colour, position and Store by ID. M1 added only RENAME, when the name changes (M1 plan, correction 3), and Healdan's rules refuse the type changes that matter to it. | **Fixed** by the pre-acceptance PR (R133): WRITE on the folder, and on the folder an alias points to, read from the alias's stored `foldRef` rather than the client's; STORE to change the storage. `FolderServiceImplTest.testSaveNeedsWrite`, `testSaveNeedsWriteOnTheFolderAnAliasPointsTo`, `testSaveIgnoresAFoldRefTheClientAdds`, `testSaveNeedsStoreToChangeTheStorage`. |
| Medium | `CMIS/LDRepository.createFolder` (~701) ✔ checked | Checks WRITE on the parent, where the GUI and SOAP ask ADD. | Open. Ask ADD. |
| Low | `logicaldoc-webservice` `SoapFolderService.createAlias` ✔ checked | Checks ADD on the parent but nothing on the target, so it copies a folder's name into a folder others can see. The GUI's `createAlias` asks READ on the target since PR #18 (row above). | Open. Ask READ on the target, as the GUI does. |
| Medium | `CORE/folder/HibernateFolderDAO.merge` | Takes the source's "children" from `findByParentId`, which returns the whole subtree, each through `findFolder`, which resolves an alias to its target. So a merge flattens the tree into the target, moves the folder an alias points to (even one outside the tree), and keeps what it moved when it then fails, since the DAO's transaction commits on the checked `PersistenceException`. A user with MOVE and DELETE on a tree holding an alias can move the alias's target. Found on a probe by the web-services PR. | **Fixed** by the pre-acceptance PR (R134): it walks the direct children, so subfolders stay nested (the owner's choice) and an alias with no namesake moves as itself. Healdan refuses merging an alias otherwise, as CE would merge the folder it points to (R136). `HibernateFolderDAOTest.testMerge`, `StructureRulesTest.testMergeKeepsSubfoldersNested`, `testMergeRefusesFolderAliases`. The DAO still commits on a `PersistenceException` other than a veto (D3). |
| Low | `logicaldoc-webservice` `SoapFolderService.delete` (~145) | Its root guard sets `rootId` to the root's tenant ID (1), so "Cannot delete root folder or Default workspace" never fires. Seen in passing, not verified. Healdan's tree check refuses a non-admin. | Open. Same follow-up section. |
| Medium | `CMIS/LDRepository.getObject` | Reads a `doc.` ID with `findDocument`, which resolves a shortcut to its target. So `deleteObject` given a shortcut's ID deletes the target document, and `createDocumentFromSource` copies the target. Code read, not probed. | Open. M1 plan, "Follow-ups found while closing the WebDAV and CMIS gaps". |
| Low | Folder moves: WebDAV MOVE, CMIS `moveObject`, the GUI's cut | Each route asks for different permissions: WebDAV asks DELETE on the folder and ADD on the target, CMIS DOWNLOAD and WRITE, and the GUI MOVE, which neither channel asks. Seen in passing. | Open. Same follow-up section. |
| Low | `CORE/document/FolderAccessControlEntry` | One built with `new FolderAccessControlEntry(groupId)` never equals the stored one, because the constructor also writes unmapped inherited fields that `equals` and `hashCode` read. So `Folder.addAccessControlEntry` adds a second entry for the group instead of replacing it. Probed on core's fixture, not tested. | Open. M1 plan, "Follow-ups found while fixing document ACL equality": compare group IDs, as `DocumentAccessControlEntry` now does. |
| Low | `WEB/data/DocumentsDataServlet.printExtendedAttributes` (~276) | Prints each `extattr` name unescaped as part of an XML element name. Only names a listed document has an attribute for are printed, and administrators name attributes, so a request can't use it on its own. | Open. M1 plan, "Follow-ups found while building PR D". |
| Low | GUI `document/LinksPanel` (~242, ~250) | It gates downloading a link on the folder's DOWNLOAD, so a user whose only grant is on the document can't. `NotesPanel`, listed here before, already checks the document (~107), as the review of 2026-10-22 found. | Open. Same follow-up section (D7). |
| Low | `CORE/document/NoteAccessControlEntry` | Keeps two copies of its flags and persists the one CE's save doesn't set, so a note's ACL loses WRITE whenever the note is saved from the GUI. The note's creator and admins are unaffected. | Open. M1 plan, the follow-ups PR's accepted limits. |
| Medium | `CORE/HibernatePersistentObjectDAO.initialize` (~350, ~368) ✔ checked | Wrote the entity it was asked to read. It merges the detached copy it's given into a new session, in a read-write transaction, so whatever that copy didn't share with the session's copy was flushed. On MariaDB and MySQL that's every read (next row). Each listing of the users bumped every listed user's record version, and concurrent listings failed on the optimistic lock with HTTP 500. So a GUI user picker sometimes came up empty, showing the raw user id and refusing to save ("Unexisting user"). Opening a document in the GUI rewrote its row and moved its last-modified date. Found on the stack, [#51](https://github.com/Healdan-Project/healdan/issues/51). | **Fixed** on 2026-10-23: both methods are `@Transactional(readOnly = true)` (Spring's), so a call in its own transaction never flushes. A call inside another transaction behaves as before, and the copy returned still carries the caller's changes. A new entity (id 0) comes back as it is, unsaved. Listed in the M1 spec's §13. `InitializeReadOnlyTest`. |
| Medium | Schema: every table's `ld_lastmodified` and `ld_creation` on MariaDB and MySQL, and `ld_user.ld_lastlogin` ✔ checked | CE's scripts create them as `datetime`, which keeps whole seconds, while the entities map them as `DATETIME(3)` and set them with milliseconds. So the second-level cache's copy of a row never matches the row, and anything that merges a detached copy finds a change. HSQLDB's `timestamp` keeps the fraction, so CE's tests can't see it. | Open. Healdan doesn't change `ld_` tables, so report it upstream. The row above removes the effect found so far, for an `initialize` in its own transaction. Inside another transaction `initialize` still merges and flushes the copy it's given, so the caller's row is still rewritten: a folder copy with Replicate security initializes the caller's source folder (`CORE/folder/HibernateFolderDAO` ~1627), and so do the other DAO methods that initialize an argument. Fixing that for every path means removing the mismatch itself, for example by dropping the milliseconds before a flush on MariaDB. |
| Medium | `WEB/websockets/EventEndpoint.distributeMessage` (~245) ✔ checked | `/wk-event`, the old GUI's live updates, sent every document, folder and user event to every connected peer, whoever its user was. A document event carries the document's details (`DocumentServiceImpl.fromDocument`) and its folder's (`FolderServiceImpl.fromFolder`): name, path, properties, description. Server push is on by default (`default.gui.serverpush`, `aspect.eventsgathering.default`), and `/wk-event` only asks for a login at the handshake. So every signed-in user of the old GUI received what happened to documents and folders they couldn't read, every user's system messages with their text, and every session's automation commands. It would also defeat M7's ethical walls. Tomcat makes an endpoint for each peer, and each one registered itself with the event collector and never left, so every event went to every peer once for each connection made since startup. Found while designing M2's API on 2026-10-23. | **Fixed** on 2026-10-23 (`HealdanEventDelivery`, a new file in the webapp). A peer gets a message only through an open session, in the message's tenant, and only if its user may read what the message is about. A document event needs READ on the document, and on a shortcut's target; a document-only grant is enough (D7). A user who can't read the folder gets its id and name only, as `getById` gives them (T19-e). A folder event needs READ on the folder. A deletion is judged on the ACL the soft delete keeps, which CE's checks no longer read. A system message goes to its recipient only, a login, logout or timeout to that user and the admins, and a command to the session it was sent for; anything else to nobody. A user's groups are read for each message, not taken from the session, so an admin removed since signing in stops getting other users' logins. A permission check that fails, or a copy of the message that can't be written, costs only the peers it was for. One endpoint listens to the collector. Both edits to `EventEndpoint` (`onOpen`, `distributeMessage`) are one-line hooks, listed in the M1 spec's §13. A user who loses READ through a move gets no message, so their GUI keeps a stale row until it refreshes. CE never sends a deleted folder's message: `EventEndpoint.setFolder` fails on the null that `fromFolder` gives for a deleted folder. `EventEndpointPermissionTest`; checked on a stack, where an upload reached the admin and a reader but not a user refused the document. |
| Medium | `CORE/security/LoginThrottle.checkLoginThrottle` ✔ checked | Nothing calls it, so no sign-in route is throttled: the old UI's form login, SOAP, REST, WebDAV and CMIS all accept unlimited password guesses. The shipped `context.properties` also sets `throttle.enabled = false`, which stops failures being counted at all. The settings screen and the blocked-entities report suggest otherwise. Found by the builder check of the [M2 design](../superpowers/specs/2026-10-23-m2-api-foundation-design.md) (§5.7). | **Fixed** in M2 ([#62](https://github.com/Healdan-Project/healdan/pull/62)). `SessionManager.newSession` checks the throttle before the chain on every route, and writes a `LOGIN_FAILED` row for a blocked attempt without counting it. Only a password sign-in clears the counts: `LDAuthenticationProvider`'s own clear, which ran even when it reused a session without checking a password, is removed. The `throttle-signin` seed turns it on: 5 wrong passwords block a username and 20 failures an address, each for 15 minutes, and the account is never disabled. `SessionManagerThrottleTest`. The two rows below are what's left. |
| Medium | `docker/server.xml` `RemoteIpValve` ✔ checked | It trusts `X-Forwarded-For` from every private, loopback and link-local address (Tomcat's default `internalProxies`), not only the proxy's, and nginx and NPM pass on the header a client sent. So a client on the LAN or VPN can claim any address: a new one for each attempt, which dodges the per-address limit, or `127.0.0.1`, which `checkLoginThrottle` exempts from every check for `admin`. Found while building M2. | **Fixed** in [Healdan-Project/healdan#68](https://github.com/Healdan-Project/healdan/pull/68): the valve believes forwarded headers only from `LOGICALDOC_TRUSTED_PROXIES`, by default the app's Docker networks. `TrustedProxiesTest` and `docker/tests/test-proxies.sh`. |
| Low | `CORE/security/LoginThrottle.notifyBruteForceAttack` ✔ checked | Runs on every blocked attempt, so an attack sends `throttle.alert.recipients` one system message and one email per attempt. Found while building M2. In fact CE ships no `bfa.alert` template, so the task failed silently and no alert ever went out; only a warning was logged per attempt. A key's failure counter was also named after the raw key. | **Fixed** in [Healdan-Project/healdan#68](https://github.com/Healdan-Project/healdan/pull/68): a seed adds the template, the alert goes out once when a block starts, HTML-escaped, and a key's counter is named after its SHA-256. `SessionManagerThrottleTest`, `ThrottleAlertSeedTest`, `ApiKeyCountersSeedTest`. |
| Low | `WEB/data/LockedDocsDataServlet` ✔ checked | Its SQL is missing a comma between `A.ld_lockuserid` and `B.ld_firstname`, so the query fails. It also checks no permission: once the comma is back, any signed-in user's request, with any `userId` or none, would list every locked, checked-out or immutable document in the tenant. Found while building M2 (plan correction 15). | Open. The API's `GET /me/checkouts` doesn't use it: it calls `DocumentDAO.findByLockUserAndStatus` and leaves out what the caller can't read. |
| Low | Healdan's document listeners (`healdan-core`) | They don't enforce required (mandatory) properties when a document is created, so other routes, such as SOAP, REST and WebDAV, can file a document with a required property empty. Only the API's `POST /documents` checks on the server. Pre-existing; found while building M2. | Open. |
