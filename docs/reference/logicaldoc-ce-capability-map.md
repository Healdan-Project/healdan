# LogicalDOC CE 9.3.1: capability map for Healdan

- **Date:** 2026-10-16
- **Method.** Three read-only code surveys of this repository, run during the roadmap session (see [`docs/ROADMAP.md`](../ROADMAP.md)). Nothing was run against a live server. Claims marked **✔ checked** were re-read in the source by hand. Treat everything else as a well-sourced lead and confirm it before relying on it.
- **Upstream merges.** This reflects the code at merge `1c2389e3d` (upstream `3085e45c3`). Line numbers will drift.

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
| 39 / 73 | BRUTEFORCE / FIREWALL | no | **present**: `LoginThrottle`, `web/firewall/HttpFirewall` |
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
- **Gotcha.** An unregistered search type causes a NullPointerException in `doSearch`.

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
  - Folder bookmarks can back "My Matters" with no new storage.

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
  - `FolderServiceImpl.create()` re-parents any type-1 folder to the root and creates it, with no feature, admin or ADD-permission check.
  - SOAP `SoapFolderService.create` accepts type 1 under any parent. The result is malformed, and `findWorkspaces` won't list it.
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
- **Push updates.** The `/wk-event` websocket sends GWT-serialized payloads, which a non-GWT client can't read.
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

- The GUI's "Edit with Office" (20) opens an `ldedit:` URL. No `/ldedit` servlet exists.
- The `OnlyOfficeService` interface has no implementation.
- Nothing refers to WOPI.
- No add-in code is in the repository.
- The usable server endpoints are SOAP/REST checkout, checkin, lock, unlock, getContent, upload and create.

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
  - the image doesn't install LibreOffice;
  - so the result is `NotAvailableConverter`.
- **Expected result:** the text inside Word, Excel, PowerPoint and email files isn't searchable. Confirm on a running server.
- **The fix belongs in a Healdan plugin,** through the `Parser` extension point (`extension`, `class`), for example with Apache POI or Tika.

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
  | Brute-force throttle (39), firewall (73) | present |

- **`TokenProvider` is outbound only.** It provides OAuth for Microsoft 365 SMTP and IMAP, and has nothing to do with inbound API tokens.

## 6. Defects found (fix in Healdan; consider reporting upstream)

Rows marked **Fixed** or **Hardened** were verified with failing tests and fixed on 2026-10-17. Each fix names the test that shows it. The first four came from the recon. The others were found while fixing those, and the rows marked Open are still to do.

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
| Medium | `WEB/service/FolderServiceImpl.createAlias` | No permission check either. Any user can put an alias in any folder. The alias takes the target's ACL, so it doesn't widen read access, but it copies the target's name and description into a folder others can see. | Open. |
| Medium | Other `HttpUtil.getNotValidatingClient` callers: `CORE/security/Geolocation.syncDB`, `CORE/automation/HttpTool`, `WEB/service/SettingServiceImpl.testProxy` | `Geolocation` sends the MaxMind license key in the download URL. `HttpTool` is the automation scripts' HTTP helper. | Open. Switch to `getValidatingClient` where nothing depends on self-signed servers. |
| Low | `CORE/communication/oauth/Microsoft365TokenProvider` | Logs the access token, decoded, and part of the response at debug level. | Open. |
| Low | `WEB/service/SearchServiceImpl.shareSearch` | Any user can add their saved searches to any other user's list. Nothing checks the target users or groups. | Open. |
| Low | `CORE/stats/StatsCollector` | Posts usage statistics to `http://stat.logicaldoc.com`, unencrypted. | Open. Decide whether Healdan keeps it. |
| Low | `GUIUser.orderedSearches()` | `add()` on `Arrays.asList` throws `UnsupportedOperationException` for some stored preferences. | Open. |
| Low | GUI vs core `Feature` 112 | Name mismatch (`SUPPORT_TICKET` vs `ACCESS_TICKET`). | Open. |
