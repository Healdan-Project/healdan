# Healdan API inventory

- **What:** every server call the old GWT UI makes, with the milestone that first needs it in the new JSON API, and where it lands there.
- **Written:** 2026-10-23, for the [M2 design](../superpowers/specs/2026-10-23-m2-api-foundation-design.md) (§12), from the code at `808d5395b`.
- **Kept current:** each milestone that adds endpoints updates its entries' tags and endpoints in the same pull request.

**Tags.** The milestone that first needs the call: `M2` (the add-in slice), `M3`, `M5`, `M6` (the User app), `M7` (walls), `Admin` (the backlog's Admin app), or `none`. `none` covers enterprise stubs with no server code (marked **NO IMPL**) and CE features Healdan doesn't use. Tags are judgements, and a milestone's design session can move them.

**Lands in.** `M2` entries name the endpoint that replaces them (§0.1, and the M2 spec §6). Everything else names only the resource area it will probably land in: a hint for that milestone's design, not a designed endpoint. Paths are under `/api/v1`.

**Purposes** were inferred from names, signatures and spot-checked implementations, not from reading every method body.

## 0. Totals

- GWT-RPC services: **50**: the 45 in `*/client/services/`, Healdan's 4 in `frontend/client/healdan/services/`, and Dropbox's. **29 have no server implementation.**
- RPC methods: **475**, counting overloads separately.
- `/data/*.xml` servlets: **52**. Healdan has none of its own.
- Other servlets and endpoints (§3): **23**.

### Count per tag

| Tag | RPC methods | Data servlets | Other | Total |
|---|---:|---:|---:|---:|
| M2 | 31 | 6 | 3 | 40 |
| M3 | 0 | 0 | 0 | 0 |
| M5 | 0 | 0 | 0 | 0 |
| M6 | 99 | 21 | 11 | 131 |
| M7 | 0 | 0 | 0 | 0 |
| Admin | 122 | 20 | 3 | 145 |
| none | 223 | 5 | 6 | 234 |
| **all** | 475 | 52 | 23 | 550 |

### 0.1 What M2 replaces

| Old call | M2 endpoint |
|---|---|
| form login `/j_spring_security_check` | `POST /auth/tokens` |
| `SecurityService.logout` | `DELETE /auth/tokens/current` |
| `SecurityService.getSession`, `getUser` (own) | `GET /me` |
| `SecurityService.searchUsers` | `GET /users?q=` |
| `InfoService.getInfo`, `HealdanSettingsService.labels` | `GET /server` |
| `HealdanMatterService.recentMatters`, `myMatters` | `GET /matters/recent`, `GET /matters/mine` |
| `HealdanMatterService.describeFolder`, `FolderService.getFolder`, `getAllowedPermissions` | `GET /folders/{id}` (with `kind`, `matter`, `path`, `canFile`) |
| `/data/folders.xml`, `/data/documents.xml`, `HealdanSearchFolderService.get`, `evaluate` | `GET /folders/{id}/children` |
| `SearchService.search`, `HealdanMatterService.resolve` | `GET /search?scope=&q=` |
| `HealdanLookupService.lookup` | `GET /lookup?q=` |
| (legacy doc # field search) | `GET /documents?legacyDocNumber=` |
| `DocumentService.getById`, `getAllowedPermissions` | `GET /documents/{docNumber}` (with `canEdit`) |
| `/download` | `GET /documents/{docNumber}/content` |
| `/data/versions.xml` | `GET /documents/{docNumber}/versions` |
| `/upload` + `DocumentService.addDocuments`, `createDocument`, `createWithContent` | `POST /documents` (multipart) |
| `DocumentService.checkout` | `POST /documents/{docNumber}/checkout` |
| `DocumentService.unlock` | `DELETE /documents/{docNumber}/checkout` |
| `/upload` + `DocumentService.checkin`, `checkinContent` | `POST /documents/{docNumber}/versions` (multipart) |
| `TemplateService.getTemplate`, `getAttributes`, `/data/attributes.xml`, `/data/attributeoptions.xml` | `GET /folders/{id}/form`, `GET /documents/{docNumber}/properties` |
| `DocumentService.save`, `validate` | `PATCH /documents/{docNumber}/properties` |
| `/data/lockeddocs.xml` | `GET /me/checkouts` |
| `HealdanMatterService.listStores`; `/data/folders.xml` on a Store (admins) | `GET /stores`, `GET /stores/{id}/matters` (admins only) |
| (none: recent folders are new) | `GET /folders/recent` |
| (none: recent documents are new) | `GET /documents/recent` |

### 0.2 Per service

| Service | Methods | M2 | M3 | M5 | M6 | M7 | Admin | none |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| InfoService | 4 | 1 |  |  | 1 |  | 1 | 1 |
| SecurityService | 44 | 4 |  |  | 14 |  | 26 |  |
| HealdanLookupService | 1 | 1 |  |  |  |  |  |  |
| HealdanMatterService | 12 | 5 |  |  | 2 |  | 5 |  |
| HealdanSearchFolderService | 6 | 2 |  |  | 4 |  |  |  |
| HealdanSettingsService | 6 | 1 |  |  |  |  | 5 |  |
| AttributeSetService | 10 |  |  |  |  |  | 10 |  |
| AuditService | 4 |  |  |  |  |  |  | 4 |
| AutomationService | 8 |  |  |  |  |  |  | 8 |
| BarcodeService | 4 |  |  |  |  |  |  | 4 |
| CalendarService | 5 |  |  |  |  |  |  | 5 |
| ChatGPTService | 5 |  |  |  |  |  |  | 5 |
| ChatService | 2 |  |  |  |  |  |  | 2 |
| ClusterService | 2 |  |  |  |  |  |  | 2 |
| ContactService | 5 |  |  |  |  |  |  | 5 |
| DashletService | 7 |  |  |  | 4 |  | 3 |  |
| DocuSignService | 6 |  |  |  |  |  |  | 6 |
| DocumentService | 72 | 12 |  |  | 39 |  | 7 | 14 |
| EmailAccountService | 8 |  |  |  |  |  |  | 8 |
| FolderService | 27 | 2 |  |  | 18 |  | 7 |  |
| FormService | 6 |  |  |  |  |  |  | 6 |
| ImpexService | 12 |  |  |  |  |  |  | 12 |
| ImportFolderService | 8 |  |  |  |  |  |  | 8 |
| LDAPService | 7 |  |  |  |  |  |  | 7 |
| MessageService | 8 |  |  |  |  |  | 5 | 3 |
| OCRService | 1 |  |  |  |  |  |  | 1 |
| ReadingRequestService | 5 |  |  |  |  |  |  | 5 |
| ReportService | 9 |  |  |  |  |  |  | 9 |
| RetentionPoliciesService | 5 |  |  |  |  |  |  | 5 |
| SchemeService | 6 |  |  |  |  |  |  | 6 |
| SearchEngineService | 14 |  |  |  |  |  | 14 |  |
| SearchService | 5 | 1 |  |  | 4 |  |  |  |
| SettingService | 18 |  |  |  | 2 |  | 16 |  |
| ShareFileService | 5 |  |  |  |  |  |  | 5 |
| SignService | 11 |  |  |  |  |  |  | 11 |
| SplitService | 1 |  |  |  |  |  |  | 1 |
| StampService | 8 |  |  |  |  |  |  | 8 |
| SyndicationService | 6 |  |  |  |  |  |  | 6 |
| SystemService | 17 |  |  |  |  |  | 17 |  |
| TagService | 6 |  |  |  | 4 |  | 2 |  |
| TemplateService | 6 | 2 |  |  |  |  | 4 |  |
| TenantService | 7 |  |  |  |  |  |  | 7 |
| TwoFactorsAuthenticationService | 3 |  |  |  |  |  |  | 3 |
| UpdateService | 17 |  |  |  |  |  |  | 17 |
| WorkflowService | 26 |  |  |  |  |  |  | 26 |
| ZonalOCRService | 4 |  |  |  |  |  |  | 4 |
| LoginService | 7 |  |  |  | 7 |  |  |  |
| TfaService | 1 |  |  |  |  |  |  | 1 |
| SetupService | 2 |  |  |  |  |  |  | 2 |
| DropboxService | 6 |  |  |  |  |  |  | 6 |

## 1. GWT-RPC services

Transport: each interface is bound to a servlet by `@RemoteServiceRelativePath`; `web.xml` maps the full URL (`/frontend/<path>`, plus `/login/...`, `/setup/...`, `/sessions/...` aliases for a few). All are GWT-serialized (not JSON), session from the `ldoc-sid`/JSESSIONID cookie. `*Impl` classes are in `logicaldoc-webapp/.../web/service/` unless stated. Healdan impls extend `HealdanRemoteService` and register with `@WebServlet`. `HealdanRpcChecks` (webapp) adds Healdan permission checks inside CE's DocumentService/FolderService/SearchService/SecurityService impls.

### InfoService

- Interface: `gui/common/client/services/InfoService.java`; RPC URL `/frontend/info` (`@RemoteServiceRelativePath("info")`)
- Server impl: **InfoServiceImpl**
- Methods: 4 (M2 1, M6 1, Admin 1, none 1)
- Lands in: `/server` (M2); `/me` (M6); `/admin/tasks` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getInfo` | Server/product info, feature list (Feature_*), tenant config, i18n bundle, supported languages, alerts. Called before and after login (`login` flag). | M2 |
| `ping` | Session keep-alive; false if the session is gone. Not needed by the API: each token request renews its session. | none |
| `getSessionInfo` | Unread system-message count and whether the session is still open (heartbeat poll). | M6 |
| `getCronDescription` | Human text for a cron expression (task scheduler UI). | Admin |

### SecurityService

- Interface: `gui/common/client/services/SecurityService.java`; RPC URL `/frontend/security` (`@RemoteServiceRelativePath("security")`)
- Server impl: **SecurityServiceImpl**
- Methods: 44 (M2 4, M6 14, Admin 26)
- Lands in: `/me`, `/auth/tokens`, `/users` (M2); `/me/profile`, `/me/password`, `/auth/tokens` for API keys and devices (M6); `/admin/users`, `/admin/groups`, `/admin/security` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getSession` | Builds the GUISession (sid, GUIUser incl. groups/permissions/menus, locale, config) for the current or given sid; null if invalid. This is the "current user" call. | M2 |
| `getUser` | Full GUIUser by id (profile, groups, quotas, settings). Used for the current user too: GUI passes its own id. | M2 |
| `logout` | Close the current session. | M2 |
| `saveProfile`, `saveInterfaceSettings`, `changePassword`, `saveAvatar`, `resetAvatar` | User edits own profile, UI settings, password and avatar (avatar is uploaded to /upload first). | M6 |
| `createApiKey`, `deleteApiKey`, `updateApiKey` | Own API keys (X-API-KEY, `ld-` prefix). Candidate seed for a token API. | M6 |
| `trustDevice`, `updateDeviceLabel`, `isTrustedDevice`, `deleteTrustedDevices` | Trusted-device list for 2FA (own account). | M6 |
| `addImpersonifier`, `deleteImpersonifiers` | Users allowed to impersonate me. | M6 |
| `searchUsers` | Find users by username and/or group (user pickers in permissions, share, messages). Also needed by M7. | M2 |
| `saveUser`, `deleteUser`, `changeStatus`, `replicateUsersSettings`, `cloneWorkTimes`, `createAccessTicket` | User CRUD, enable/disable, copy settings/work times, one-time access tickets. | Admin |
| `getGroup`, `saveGroup`, `deleteGroup`, `removeFromGroup`, `addUserToGroup` | Group CRUD and membership. | Admin |
| `generatePassword`, `generatePassword2`, `validatePassword` | Generate/validate a password against policy. | Admin |
| `loadSettings`, `saveSettings` | Security settings (password policy, lockout, SSO flags). | Admin |
| `kill` | Terminate a session by sid. | Admin |
| `getMenu`, `getMenus`, `saveMenus`, `saveMenu`, `deleteMenu`, `saveACL` | Menu tree and menu ACLs (what admin screens a group may open). | Admin |
| `loadBlockedEntities`, `removeBlockedEntities`, `syncGeolocationDB` | Blocked IPs/users, geolocation DB sync. | Admin |

### HealdanLookupService

- Interface: `gui/frontend/client/healdan/services/HealdanLookupService.java`; RPC URL `/frontend/healdanlookup` (`@RemoteServiceRelativePath("healdanlookup")`)
- Server impl: **HealdanLookupServiceImpl**
- Methods: 1 (M2 1)
- Lands in: `/lookup` (M2)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `lookup` | `#1234.2` doc-number lookup: returns document id + version or a message; unreadable = "doesn't exist". | M2 |

### HealdanMatterService

- Interface: `gui/frontend/client/healdan/services/HealdanMatterService.java`; RPC URL `/frontend/healdanmatter` (`@RemoteServiceRelativePath("healdanmatter")`)
- Server impl: **HealdanMatterServiceImpl**
- Methods: 12 (M2 5, M6 2, Admin 5)
- Lands in: `/folders/{id}`, `/matters/recent`, `/matters/mine`, `/search?scope=matters`, `/stores` (M2); `/matters/mine/{id}` (M6); `/admin/matters`, `/admin/templates` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `describeFolder` | What a folder is in Healdan's layout (kind, matter id, top level?, search folder?, in My Matters?). Needs READ. | M2 |
| `recentMatters`, `myMatters` | Recent matters (where I last changed a document) and My Matters, with workspace id, display `client.matter - name`, client name. | M2 |
| `resolve` | Matter-number lookup: `998.1` or a bare number -> matters (max 100). | M2 |
| `addToMyMatters`, `removeFromMyMatters` | Edit My Matters (folder bookmarks). | M6 |
| `listStores` | The Stores to choose from. Admin-only, and the API keeps it admin-only (`GET /stores`). | M2 |
| `create`, `update`, `getByFolder`, `suggestMatterNumber`, `listTemplates` | Matter admin: create from template (all-or-nothing), edit, read by workspace, suggest number, templates to choose from. All admin-only. | Admin |

### HealdanSearchFolderService

- Interface: `gui/frontend/client/healdan/services/HealdanSearchFolderService.java`; RPC URL `/frontend/healdansearchfolder` (`@RemoteServiceRelativePath("healdansearchfolder")`)
- Server impl: **HealdanSearchFolderServiceImpl**
- Methods: 6 (M2 2, M6 4)
- Lands in: `/folders/{id}`, `/folders/{id}/children` (M2); `/search-folders/{id}` (M6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `get`, `evaluate` | Search folders (saved rule shown as a folder): read one; evaluate it to up to 1000 doc ids. | M2 |
| `create`, `update` | Create or edit a search folder's rule. | M6 |
| `classes`, `subclasses` | Class and Subclass option lists for the rule editor. | M6 |

### HealdanSettingsService

- Interface: `gui/frontend/client/healdan/services/HealdanSettingsService.java`; RPC URL `/frontend/healdansettings` (`@RemoteServiceRelativePath("healdansettings")`)
- Server impl: **HealdanSettingsServiceImpl**
- Methods: 6 (M2 1, Admin 5)
- Lands in: `/server` (M2); `/admin/settings`, `/admin/clients` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `labels` | Matter label singular/plural (any user). | M2 |
| `load`, `save` | Healdan settings (admin). | Admin |
| `listClients`, `saveClient`, `suggestClientNumber` | Client list/create/rename, next client number. | Admin |

### AttributeSetService

- Interface: `gui/frontend/client/services/AttributeSetService.java`; RPC URL `/frontend/attributeset` (`@RemoteServiceRelativePath("attributeset")`)
- Server impl: **AttributeSetServiceImpl**
- Methods: 10 (Admin 10)
- Lands in: `/admin/attributes` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getAttributeSets`, `getAttributeSet`, `save`, `delete` | Attribute set (custom field group) CRUD. | Admin |
| `saveOptions`, `deleteOptions`, `parseOptions` | Option lists (dropdown values) for an attribute; parse from uploaded file. Reading options for a form is /data/attributeoptions.xml. | Admin |
| `applyAllToTemplates`, `applyValidationToTemplates`, `applyInitializationToTemplates` | Push an attribute definition to all templates using it. | Admin |

### AuditService

- Interface: `gui/frontend/client/services/AuditService.java`; RPC URL `/frontend/audit` (`@RemoteServiceRelativePath("audit")`)
- Server impl: **NO IMPL**
- Methods: 4 (none 4)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `subscribeFolder`, `subscribeDocuments`, `update`, `deleteSubscriptions` | NO IMPL (enterprise stub): event subscriptions/notifications. | none |

### AutomationService

- Interface: `gui/frontend/client/services/AutomationService.java`; RPC URL `/frontend/automation` (`@RemoteServiceRelativePath("automation")`)
- Server impl: **NO IMPL**
- Methods: 8 (none 8)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `saveRoutine`, `getRoutine`, `deleteRoutines`, `deleteTriggers`, `saveTrigger`, `getTrigger`, `applyTriggersToTree`, `execute` | NO IMPL (enterprise stub): automation routines and triggers. | none |

### BarcodeService

- Interface: `gui/frontend/client/services/BarcodeService.java`; RPC URL `/frontend/barcode` (`@RemoteServiceRelativePath("barcode")`)
- Server impl: **NO IMPL**
- Methods: 4 (none 4)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `getTemplate`, `updateZone` | NO IMPL (enterprise stub): barcode templates. | none |

### CalendarService

- Interface: `gui/frontend/client/services/CalendarService.java`; RPC URL `/frontend/calendar` (`@RemoteServiceRelativePath("calendar")`)
- Server impl: **NO IMPL**
- Methods: 5 (none 5)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `saveEvent`, `getEvent`, `find`, `deleteEvent`, `countUserEvents` | NO IMPL (enterprise stub): calendar. | none |

### ChatGPTService

- Interface: `gui/frontend/client/services/ChatGPTService.java`; RPC URL `/frontend/chatgpt` (`@RemoteServiceRelativePath("chatgpt")`)
- Server impl: **NO IMPL**
- Methods: 5 (none 5)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadSettings`, `saveSettings`, `startThread`, `ask`, `getAnswer` | NO IMPL (enterprise stub): AI chat. | none |

### ChatService

- Interface: `gui/frontend/client/services/ChatService.java`; RPC URL `/frontend/chat` (`@RemoteServiceRelativePath("chat")`)
- Server impl: **NO IMPL**
- Methods: 2 (none 2)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `post`, `invite` | NO IMPL (enterprise stub): user chat (pushed over /wk-event "event.chat.newmessage"). | none |

### ClusterService

- Interface: `gui/frontend/client/services/ClusterService.java`; RPC URL `/frontend/cluster` (`@RemoteServiceRelativePath("cluster")`)
- Server impl: **NO IMPL**
- Methods: 2 (none 2)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `makeGlobal`, `makeLocal` | NO IMPL (enterprise stub): cluster-wide settings. | none |

### ContactService

- Interface: `gui/frontend/client/services/ContactService.java`; RPC URL `/frontend/contact` (`@RemoteServiceRelativePath("contact")`)
- Server impl: **ContactServiceImpl**
- Methods: 5 (none 5)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `load`, `parseContacts`, `shareContacts` | Address book (CE feature, not in roadmap). | none |

### DashletService

- Interface: `gui/frontend/client/services/DashletService.java`; RPC URL `/frontend/dashlet` (`@RemoteServiceRelativePath("dashlet")`)
- Server impl: **DashletServiceImpl**
- Methods: 7 (M6 4, Admin 3)
- Lands in: `/me/dashlets` (M6); `/admin/dashlets` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadDashlets`, `saveUserDashlets` | The user's home dashlets and their layout. | M6 |
| `get`, `get` | Dashlet by id and by name (overloads). | M6 |
| `save`, `saveDashlets`, `delete` | Define/delete dashlets. | Admin |

### DocuSignService

- Interface: `gui/frontend/client/services/DocuSignService.java`; RPC URL `/frontend/docusign` (`@RemoteServiceRelativePath("docusign")`)
- Server impl: **NO IMPL**
- Methods: 6 (none 6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadSettings`, `authorize`, `isAuthorized`, `getSigners`, `validateEnvelope`, `sendEnvelope` | NO IMPL (enterprise stub): DocuSign e-signature. | none |

### DocumentService

- Interface: `gui/frontend/client/services/DocumentService.java`; RPC URL `/frontend/document` (`@RemoteServiceRelativePath("document")`)
- Server impl: **DocumentServiceImpl**
- Methods: 72 (M2 12, M6 39, Admin 7, none 14)
- Lands in: `/documents/{docNumber}`, `/documents/{docNumber}/checkout`, `/documents/{docNumber}/versions`, `/documents/{docNumber}/properties`, `POST /documents` (M2); `/documents/{docNumber}/…` for notes, links, tickets, permissions and bookmarks, and `/trash` (M6); `/admin/documents` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getById` | Full GUIDocument: properties, extended attributes, folder, lock/checkout state, permissions, tags, versions info. | M2 |
| `save` | Update document properties (no content). Healdan hooks apply (profile stamping, edit marks). | M2 |
| `validate` | Validate mandatory/format rules of a GUIDocument without saving. | M2 |
| `checkout` | Mark documents checked-out by me. Does NOT return content: client downloads via /download. | M2 |
| `checkin` | Check in using files previously posted to /upload (held in the HTTP session keyed by filename); `major` flag. Returns the new GUIDocument. = "save as new version". | M2 |
| `checkinContent` | Check in with text content passed as a string. | M2 |
| `unlock` | Release lock/checkout (cancel checkout). | M2 |
| `createDocument` | Create a document from GUIDocument metadata + string content (text docs). | M2 |
| `createWithContent` | Create from metadata + string content, optionally leaving it checked out. | M2 |
| `addDocuments`, `addDocuments` | Create documents from files posted earlier to /upload (session-held). Overload 1: language/folder/zip import/charset/template; overload 2: takes a metadata GUIDocument. = "save as new document". | M2 |
| `cleanUploadedFileFolder` | Discard the session's pending uploads. Not needed by the API: uploads travel in the request that uses them. | none |
| `countDocuments` | Count documents in folders by status (confirm dialogs, folder stats). | M6 |
| `getVersionsById` | Compare/fetch two versions' details (id1,id2). | M6 |
| `promoteVersion`, `deleteVersions`, `replaceFile` | Make an old version current; delete versions; replace a version's file with an upload. | M6 |
| `rename` | Rename document. | M6 |
| `lock` | Lock documents with comment. | M6 |
| `delete`, `restore`, `deleteFromTrash`, `emptyTrash`, `destroyDocuments` | Move to trash, restore, purge from trash, empty trash, hard delete. | M6 |
| `bulkUpdate` | Apply the same properties to many documents. | M6 |
| `getAllowedPermissions` | Effective permissions of the user over a set of documents (enables/disables actions). The API returns it as `canEdit`. | M2 |
| `saveACL`, `applyParentFolderSecurity` | Edit a document's ACL; reset to the folder's. | M6 |
| `makeImmutable` | Make documents immutable (read-only forever). | M6 |
| `addBookmarks`, `deleteBookmarks`, `updateBookmark` | Favorites (bookmarks). Healdan uses folder bookmarks as My Matters. | M6 |
| `linkDocuments`, `deleteLinks`, `updateLink`, `replaceAlias` | Document links; turn an alias into a real document. | M6 |
| `saveNote`, `getNotes`, `getNote`, `saveNotes`, `deleteNotes` | Annotations/notes on a document or version. | M6 |
| `createTicket`, `setTicketPassword`, `deleteTickets`, `enableTickets`, `disableTickets` | Download/view tickets (shareable links; served by /download-ticket). | M6 |
| `getContentAsString` | Text content of a document (text editor). | M6 |
| `convert` | Convert a file version to another format; creates a document. | M6 |
| `sendAsEmail` | Email documents (links or attachments) from the server. | M6 |
| `extractEmail`, `saveEmailAttachment` | Parse an .eml/.msg document for the viewer; save an attachment as a new document. | M6 |
| `markHistoryAsRead` | Mark a history/notification event as read. | M6 |
| `indexDocuments`, `markUnindexable`, `markIndexable` | Force index / exclude from full-text index. | Admin |
| `updatePages`, `deDuplicate`, `enforceFilesIntoFolderStore`, `merge` | Maintenance: recount pages, remove duplicates, move files to the folder's storage, merge PDFs into a new document. | Admin |
| `isPasswordProtected`, `setPassword`, `unsetPassword`, `unprotect` | Per-document password protection (not relevant). | none |
| `getRating`, `saveRating`, `getUserRating`, `deleteRating` | Star ratings (not relevant). | none |
| `getSummary`, `saveSummary` | AI summary text (enterprise AI). | none |
| `archiveDocuments`, `archiveFolder`, `unarchiveDocuments` | Archiving (CE feature, not in roadmap). | none |

### EmailAccountService

- Interface: `gui/frontend/client/services/EmailAccountService.java`; RPC URL `/frontend/emailaccount` (`@RemoteServiceRelativePath("emailaccount")`)
- Server impl: **NO IMPL**
- Methods: 8 (none 8)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `get`, `test`, `changeStatus`, `resetCache`, `resetCounter`, `clone` | NO IMPL (enterprise stub): IMAP/POP mailbox import (backlog "importing from IMAP/POP"). | none |

### FolderService

- Interface: `gui/frontend/client/services/FolderService.java`; RPC URL `/frontend/folder` (`@RemoteServiceRelativePath("folder")`)
- Server impl: **FolderServiceImpl**
- Methods: 27 (M2 2, M6 18, Admin 7)
- Lands in: `/folders/{id}` (M2); `/folders/{id}/…` for create, move, copy, permissions and trash (M6); `/admin/folders` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getFolder` | Folder bean (path, permissions, template/attributes, counts) with optional path/doc count/subfolder count. The browse call. | M2 |
| `getAllowedPermissions` | Effective permissions over a set of folders (can I add/write here). | M2 |
| `create`, `createAlias`, `save`, `rename` | Create, alias, update properties, rename a folder. (Healdan restricts Client/Matter structure.) | M6 |
| `delete`, `restore`, `deleteFromTrash` | Delete to trash, restore, purge. | M6 |
| `move`, `merge`, `copyFolders`, `paste`, `pasteAsAlias` | Move/merge/copy folders; paste documents (copy/cut, links, notes, security options). | M6 |
| `saveACL`, `inheritACL` | Edit folder ACL (optionally to subfolders); inherit from another folder. | M6 |
| `applyTemplate` | Apply a folder template (structure) to a folder. | M6 |
| `setFolderPagination`, `applyGridLayout` | Per-folder list paging/grid layout preference. | M6 |
| `readImage` | Read an image posted to /upload for the folder (icon/image). | M6 |
| `applyMetadata`, `applyTags`, `applyStore`, `applyCapture` | Push a folder's metadata/tags/storage/capture settings to its subtree. | Admin |
| `loadTemplates`, `saveTemplates` | List/save folder templates (CE feature, unrelated to Healdan workspace templates). | Admin |
| `computeStats` | Compute folder statistics (counts, size). | Admin |

### FormService

- Interface: `gui/frontend/client/services/FormService.java`; RPC URL `/frontend/form` (`@RemoteServiceRelativePath("form")`)
- Server impl: **NO IMPL**
- Methods: 6 (none 6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getById`, `save`, `processImage`, `delete`, `invite`, `getPreFilledLink` | NO IMPL (enterprise stub): web forms. | none |

### ImpexService

- Interface: `gui/frontend/client/services/ImpexService.java`; RPC URL `/frontend/impex` (`@RemoteServiceRelativePath("impex")`)
- Server impl: **NO IMPL**
- Methods: 12 (none 12)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `deleteVersions`, `setStatus`, `save`, `load`, `addDocuments`, `addFolder`, `deleteIncremental`, `loadIncremental`, `saveIncremental`, `deleteFolder`, `startImport` | NO IMPL (enterprise stub): import/export archives (relates to backlog bulk import/export, but nothing exists). | none |

### ImportFolderService

- Interface: `gui/frontend/client/services/ImportFolderService.java`; RPC URL `/frontend/importfolder` (`@RemoteServiceRelativePath("importfolder")`)
- Server impl: **NO IMPL**
- Methods: 8 (none 8)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `get`, `test`, `changeStatus`, `resetCounter`, `resetCache`, `clone` | NO IMPL (enterprise stub): import folders (SMB/FTP/etc.). | none |

### LDAPService

- Interface: `gui/frontend/client/services/LDAPService.java`; RPC URL `/frontend/ldap` (`@RemoteServiceRelativePath("ldap")`)
- Server impl: **NO IMPL**
- Methods: 7 (none 7)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `save`, `testConnection`, `get`, `delete`, `reorder`, `listUsers`, `importUsers` | NO IMPL (enterprise stub): LDAP/AD directories (backlog "LDAP/SAML"). | none |

### MessageService

- Interface: `gui/frontend/client/services/MessageService.java`; RPC URL `/frontend/message` (`@RemoteServiceRelativePath("message")`)
- Server impl: **MessageServiceImpl**
- Methods: 8 (Admin 5, none 3)
- Lands in: `/admin/message-templates` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getMessage`, `delete`, `save` | Internal user-to-user/system messages (not in roadmap). | none |
| `loadTemplates`, `getTemplate`, `saveTemplates`, `deleteTemplates`, `deleteTemplates` | Message/email templates (overloads delete by ids or name). | Admin |

### OCRService

- Interface: `gui/frontend/client/services/OCRService.java`; RPC URL `/frontend/ocr` (`@RemoteServiceRelativePath("ocr")`)
- Server impl: **NO IMPL**
- Methods: 1 (none 1)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadSettings` | NO IMPL (enterprise stub): OCR settings. | none |

### ReadingRequestService

- Interface: `gui/frontend/client/services/ReadingRequestService.java`; RPC URL `/frontend/readingrequest` (`@RemoteServiceRelativePath("readingrequest")`)
- Server impl: **NO IMPL**
- Methods: 5 (none 5)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `askReadingConfirmation`, `confirmReadings`, `delete`, `notityReadingRequest`, `getUnconfimedReadings` | NO IMPL (enterprise stub): reading confirmations. | none |

### ReportService

- Interface: `gui/frontend/client/services/ReportService.java`; RPC URL `/frontend/report` (`@RemoteServiceRelativePath("report")`)
- Server impl: **NO IMPL**
- Methods: 9 (none 9)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `storeUploadedDesign`, `create`, `getReport`, `getReports`, `getReportParameters`, `changeStatus`, `execute` | NO IMPL (enterprise stub): reports (Admin app would want reports, but nothing exists). | none |

### RetentionPoliciesService

- Interface: `gui/frontend/client/services/RetentionPoliciesService.java`; RPC URL `/frontend/retentionpolicies` (`@RemoteServiceRelativePath("retentionpolicies")`)
- Server impl: **NO IMPL**
- Methods: 5 (none 5)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `getPolicy`, `reorder`, `changeStatus` | NO IMPL (enterprise stub): retention policies. | none |

### SchemeService

- Interface: `gui/frontend/client/services/SchemeService.java`; RPC URL `/frontend/scheme` (`@RemoteServiceRelativePath("scheme")`)
- Server impl: **NO IMPL**
- Methods: 6 (none 6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `get`, `load`, `resetSequence`, `deleteSequence` | NO IMPL (enterprise stub): naming schemes/sequences (autonaming). Note /data/sequences.xml does exist. | none |

### SearchEngineService

- Interface: `gui/frontend/client/services/SearchEngineService.java`; RPC URL `/frontend/searchengine` (`@RemoteServiceRelativePath("searchengine")`)
- Server impl: **SearchEngineServiceImpl**
- Methods: 14 (Admin 14)
- Lands in: `/admin/search-engine` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getInfo`, `countEntries`, `check`, `unlock`, `purge`, `rescheduleAll`, `save` | Lucene index admin: info, size, check, unlock, purge, reindex all, save config. | Admin |
| `setLanguageStatus`, `setAliases`, `reorderTokenFilters`, `saveTokenFilterSettings`, `setTokenFilterStatus` | Index language/analyzer/token-filter settings. | Admin |
| `query`, `remove` | Query the raw index; remove entries. | Admin |

### SearchService

- Interface: `gui/frontend/client/services/SearchService.java`; RPC URL `/frontend/search` (`@RemoteServiceRelativePath("search")`)
- Server impl: **SearchServiceImpl**
- Methods: 5 (M2 1, M6 4)
- Lands in: `/search` (M2); saved searches, if M6 turns them on (M6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `search` | Run a search from GUISearchOptions: type = fulltext, parametric (name/metadata), folders, tags, etc. Returns GUIResult (hits, time, estimated count). | M2 |
| `save`, `load`, `delete`, `shareSearch` | Saved searches: store, load, delete, share with users/groups. | M6 |

### SettingService

- Interface: `gui/frontend/client/services/SettingService.java`; RPC URL `/frontend/setting` (`@RemoteServiceRelativePath("setting")`)
- Server impl: **SettingServiceImpl**
- Methods: 18 (M6 2, Admin 16)
- Lands in: `/server` (M6); `/admin/settings` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadGUISettings`, `loadSettingsByNames` | UI/config settings by name (e.g. upload limits); GUI-visible settings. | M6 |
| `loadSettings`, `saveSettings`, `loadProtocolSettings`, `loadAuditingSettings`, `loadWebserviceStats`, `saveRegistration` | System settings by tenant; protocols (REST/WebDAV/CMIS); auditing; API stats; registration. | Admin |
| `loadEmailSettings`, `saveEmailSettings`, `testEmail`, `testProxy` | Outgoing mail and proxy config + tests. | Admin |
| `testStore`, `saveStoreSettings`, `removeStore` | Storage backends (CE "Store" = file store, NOT Healdan's Store). | Admin |
| `saveFirewallSettings`, `saveExtensionAliases`, `loadConverterParameters` | Firewall rules, extension aliases, converter parameters. | Admin |

### ShareFileService

- Interface: `gui/frontend/client/services/ShareFileService.java`; RPC URL `/frontend/sharefile` (`@RemoteServiceRelativePath("sharefile")`)
- Server impl: **NO IMPL**
- Methods: 5 (none 5)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `exportDocuments`, `importDocuments`, `loadSettings`, `authorize`, `isAuthorized` | NO IMPL (enterprise stub): ShareFile integration. | none |

### SignService

- Interface: `gui/frontend/client/services/SignService.java`; RPC URL `/frontend/sign` (`@RemoteServiceRelativePath("sign")`)
- Server impl: **NO IMPL**
- Methods: 11 (none 11)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadKeystore`, `generateNewKeystore`, `generateNewCertificate`, `importCertificate`, `getUploadedContent`, `deleteCertificate`, `deleteKeystore`, `saveKeystore`, `imporKeystore`, `isVisualSignatureEnabled`, `signDocuments` | NO IMPL (enterprise stub): digital signing / keystores. | none |

### SplitService

- Interface: `gui/frontend/client/services/SplitService.java`; RPC URL `/frontend/split` (`@RemoteServiceRelativePath("split")`)
- Server impl: **NO IMPL**
- Methods: 1 (none 1)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `split` | NO IMPL (enterprise stub): split PDFs. | none |

### StampService

- Interface: `gui/frontend/client/services/StampService.java`; RPC URL `/frontend/stamp` (`@RemoteServiceRelativePath("stamp")`)
- Server impl: **NO IMPL**
- Methods: 8 (none 8)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `saveImage`, `getSignature`, `getStamp`, `getStamp`, `changeStatus`, `applyStamp` | NO IMPL (enterprise stub): stamps/signature images. | none |

### SyndicationService

- Interface: `gui/frontend/client/services/SyndicationService.java`; RPC URL `/frontend/syndication` (`@RemoteServiceRelativePath("syndication")`)
- Server impl: **NO IMPL**
- Methods: 6 (none 6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `getSyndication`, `test`, `changeStatus`, `resetCache` | NO IMPL (enterprise stub): syndication with remote LogicalDOC servers. | none |

### SystemService

- Interface: `gui/frontend/client/services/SystemService.java`; RPC URL `/frontend/system` (`@RemoteServiceRelativePath("system")`)
- Server impl: **SystemServiceImpl**
- Methods: 17 (Admin 17)
- Lands in: `/admin/system`, `/admin/tasks`, `/admin/history` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getStatistics` | Server statistics for the system screen. | Admin |
| `loadTasks`, `getTaskByName`, `startTask`, `stopTask`, `enableTask`, `disableTask`, `saveTask`, `unscheduleJobs` | Scheduled tasks: list, run, stop, enable, schedule. | Admin |
| `setGUILanguageStatus`, `getPlugins`, `confirmUpdate`, `restart` | Language toggle, installed plugins, restart. | Admin |
| `saveLogger`, `removeLogger` | Log4j logger levels. | Admin |
| `search` | Audit history search (users, events, dates, root folder). | Admin |
| `searchApiCalls` | API call log search. | Admin |

### TagService

- Interface: `gui/frontend/client/services/TagService.java`; RPC URL `/frontend/tag` (`@RemoteServiceRelativePath("tag")`)
- Server impl: **TagServiceImpl**
- Methods: 6 (M6 4, Admin 2)
- Lands in: `/tags` (M6); `/admin/tags` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getTagCloud`, `getSettings` | Tags with counts; tag settings (mode, size limits). | M6 |
| `addTag`, `removeTag` | Add/remove a tag to the user's "my tags" selection. | M6 |
| `delete`, `rename` | Delete/rename a tag globally. | Admin |

### TemplateService

- Interface: `gui/frontend/client/services/TemplateService.java`; RPC URL `/frontend/template` (`@RemoteServiceRelativePath("template")`)
- Server impl: **TemplateServiceImpl**
- Methods: 6 (M2 2, Admin 4)
- Lands in: `/folders/{id}/form`, `/documents/{docNumber}/properties` (M2); `/admin/profile` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getTemplate` | Template (Healdan: profile) definition incl. attributes, mandatory flags, defaults. | M2 |
| `getAttributes` | Attributes of a template filled for a given object (document/folder) -> basis for the properties form with required fields. | M2 |
| `save`, `delete`, `clone`, `countDocuments` | Template CRUD, clone, number of documents using it. | Admin |

### TenantService

- Interface: `gui/frontend/client/services/TenantService.java`; RPC URL `/frontend/tenant` (`@RemoteServiceRelativePath("tenant")`)
- Server impl: **NO IMPL**
- Methods: 7 (none 7)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `load`, `changeAdminPassword`, `changeSessionTenant`, `encodeBrandingImage`, `importBrandingPackage` | NO IMPL (enterprise stub): multi-tenant admin / branding (CE is single-tenant in the UI). | none |

### TwoFactorsAuthenticationService

- Interface: `gui/frontend/client/services/TwoFactorsAuthenticationService.java`; RPC URL `/frontend/twoFactorsAuth` (`@RemoteServiceRelativePath("twoFactorsAuth")`)
- Server impl: **NO IMPL**
- Methods: 3 (none 3)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `generateGoogleAuthorizationCredentials`, `generateYubiKeyCredentials`, `changeTwoFactorsAuthentication` | NO IMPL (enterprise stub): 2FA enrolment (backlog "Two-factor for local accounts"). | none |

### UpdateService

- Interface: `gui/frontend/client/services/UpdateService.java`; RPC URL `/frontend/update` (`@RemoteServiceRelativePath("update")`)
- Server impl: **NO IMPL**
- Methods: 17 (none 17)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `checkUpdate`, `checkPatch`, `downloadUpdate`, `downloadPatch`, `confirmUpdate`, `confirmPatch`, `getUpdateNotes`, `getPatchNotes`, `loadUpdate`, `deleteUpdate`, `loadPatch`, `deletePatch`, `getPlugins`, `installPlugin`, `initializePlugin`, `uninstallPlugin`, `checkDownloadStatus` | NO IMPL (enterprise stub): online update/patch/plugin manager (irrelevant to a Docker/Healdan deploy). | none |

### WorkflowService

- Interface: `gui/frontend/client/services/WorkflowService.java`; RPC URL `/frontend/workflow` (`@RemoteServiceRelativePath("workflow")`)
- Server impl: **NO IMPL**
- Methods: 26 (none 26)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `get`, `getCompletionDiagram`, `delete`, `deleteInstance`, `deleteInstances`, `importSchema`, `save`, `saveACL`, `deploy`, `undeploy`, `list`, `deleteTrigger`, `applyTriggersToTree`, `saveTrigger`, `startWorkflow`, `getWorkflowDetailsByTask`, `claimTask`, `reassignTask`, `assignTasks`, `turnBackTaskToPool`, `endTask`, `countAssignedTasks`, `appendDocuments`, `removeDocument`, `addNote`, `deleteNote` | NO IMPL (enterprise stub): BPM workflows/tasks. | none |

### ZonalOCRService

- Interface: `gui/frontend/client/services/ZonalOCRService.java`; RPC URL `/frontend/zonalocr` (`@RemoteServiceRelativePath("zonalocr")`)
- Server impl: **NO IMPL**
- Methods: 4 (none 4)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `delete`, `save`, `updateZone`, `getTemplate` | NO IMPL (enterprise stub): zonal OCR templates. | none |

### LoginService

- Interface: `gui/login/client/services/LoginService.java`; RPC URL `/login/login` (`@RemoteServiceRelativePath("login")`)
- Server impl: **LoginServiceImpl**
- Methods: 7 (M6 7)
- Lands in: `/auth/…` for password reset, legal notices and forced password change (M6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `resetPassword` | Starts password recovery: emails a reset link (see /pswrecovery). | M6 |
| `isSecretKeyRequired` | Does this user/device need a 2FA code at login. | M6 |
| `getLegalsToConfirm`, `confirmLegal` | Legal documents the user must accept at login; record acceptance. | M6 |
| `changePassword`, `generatePassword` | Forced password change at login; generate a compliant password for a username. | M6 |
| `getUser` | Look up a user by username for the login/expired-password flow (unauthenticated). | M6 |

### TfaService

- Interface: `gui/login/client/services/TfaService.java`; RPC URL `/login/tfa` (`@RemoteServiceRelativePath("tfa")`)
- Server impl: **NO IMPL**
- Methods: 1 (none 1)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `generateKey` | Generate a 2FA secret at login. NO IMPL (enterprise 2FA); CE only has settings/columns (backlog item). | none |

### SetupService

- Interface: `gui/setup/client/services/SetupService.java`; RPC URL `/setup/setup` (`@RemoteServiceRelativePath("setup")`)
- Server impl: **SetupServiceImpl**
- Methods: 2 (none 2)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `securityCheck`, `setup` | First-run setup wizard (DB, paths, admin). Docker image never runs it (DockerInit seeds config). | none |

### DropboxService

- Interface: `logicaldoc-gui/.../frontend/client/dropbox/DropboxService.java`; RPC URL `/frontend/dropbox` (`@RemoteServiceRelativePath("dropbox")`)
- Server impl: **DropboxServiceImpl (logicaldoc-dropbox module)**
- Methods: 6 (none 6)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `isConnected`, `startAuthorization`, `finishAuthorization`, `exportDocuments`, `saveSettings`, `loadSettings` | Dropbox import/export (CE plugin; not relevant). Not under client/services; lives in gui/frontend/client/dropbox. | none |

## 2. Data servlets (`/data/*.xml`)

All extend `AbstractDataServlet`: GET only, require a session (`isAuthenticated()` on `/data/**`, then `ServletUtil.validateSession`), return `text/xml` (UTF-8, no caching) for SmartGWT `DataSource`s. Common params: `max` (limit), `locale`. Dates are `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`. No Healdan-owned data servlets exist: Healdan edits CE servlets (`DocumentsData`, `VersionsData`, `DeletedDocs/Folders`) in place. Errors come back as an XML error body, not an HTTP status.

| URL | Class (web/data) | Main request params | Lists | First needed by | Lands in |
|---|---|---|---|---|---|
| `/data/folders.xml` | FoldersDataServlet | parent (folder id, "/" = root), criteria (SmartGWT criteria JSON, parent extracted by string slicing), withroot, withdocs, nopagination | Folder tree children for a parent (name, perms, color, type, doc counts); optionally the documents in them. The tree browse call. | M2 | `/folders/{id}/children?type=folder` |
| `/data/documents.xml` | DocumentsDataServlet | folderId, docIds (comma list; used for search folders and result sets), filename, status, page, sort, hiliteDocId, formId, indexed, extattr (extended attribute names to include) | Documents of a folder or id-list, paged and sorted, with selected extended attributes. The main document grid. | M2 | `/folders/{id}/children` |
| `/data/templates.xml` | TemplatesDataServlet | type, templateId, withempty | Templates (Healdan: profiles) available for documents or folders. Healdan has one profile, so the API's form doesn't need it. | Admin | `/admin/profile` |
| `/data/attributes.xml` | AttributesDataServlet | templateId, context, sections, extOnly | Attributes of a template (or all attribute-set attributes): name, type, mandatory, defaults, options-set. | M2 | `/folders/{id}/form`, `/documents/{docNumber}/properties` |
| `/data/attributeoptions.xml` | AttributeOptionsDataServlet | setId, attribute, category, withempty | Option values for a dropdown/preset attribute. | M2 | the `options` of each form field |
| `/data/lockeddocs.xml` | LockedDocsDataServlet | userId | Documents locked/checked out (by a user). "My checked-out documents". | M2 | `/me/checkouts` |
| `/data/versions.xml` | VersionsDataServlet | docId, archiveId | Version history of a document (or versions of an Import/Export archive). | M2 | `/documents/{docNumber}/versions` |
| `/data/documenthistory.xml` | DocumentHistoryDataServlet | docId (plus max, locale) | Audit events for one document (author, event, version, date, comment). Possible source for "recent documents". | M6 | `/documents/{docNumber}/history` |
| `/data/folderhistory.xml` | FolderHistoryDataServlet | id | Audit events for one folder. | M6 | `/folders/{id}/history` |
| `/data/acl.xml` | AclDataServlet | id, type (document/folder/menu/template) | Access control list entries (groups, users, permission flags) of an object. | M6 | `/documents/{docNumber}/permissions`, `/folders/{id}/permissions` |
| `/data/bookmarks.xml` | BookmarksDataServlet | (none)  | The user's bookmarks (documents and folders). Healdan stores My Matters as folder bookmarks. | M6 | `/me/favorites` |
| `/data/notes.xml` | NotesDataServlet | docId, userId, page | Notes/annotations of a document or by a user. | M6 | `/documents/{docNumber}/notes` |
| `/data/links.xml` | LinksDataServlet | docId, parent | Links of a document, or children of a link/metadata group. | M6 | `/documents/{docNumber}/links` |
| `/data/documentaliases.xml` | DocumentAliasesDataServlet | docId | Aliases (shortcuts) pointing to a document. | M6 | – (shortcuts are refused inside Stores) |
| `/data/folderaliases.xml` | FolderAliasesDataServlet | folderId | Aliases pointing to a folder. | M6 | – (shortcuts are refused inside Stores) |
| `/data/tags.xml` | TagsDataServlet | docId, folderId, firstLetter, editing | Tags (cloud or per document/folder). | M6 | `/tags` |
| `/data/tickets.xml` | TicketsDataServlet | docId (+ standard) | Download/view tickets of a document. | M6 | `/documents/{docNumber}/tickets` |
| `/data/deleteddocs.xml` | DeletedDocsDataServlet | folderId, userId | Documents in the trash (Healdan checks the menu right). | M6 | `/trash` |
| `/data/deletedfolders.xml` | DeletedFoldersDataServlet | parentId, userId | Folders in the trash. | M6 | `/trash` |
| `/data/garbage.xml` | GarbageDataServlet | (none) | Mixed latest 100 deleted docs/folders for the user (trash dashlet). | M6 | `/trash` |
| `/data/users.xml` | UsersDataServlet | groupId, required, skipdisabled, impersonifiers | Users (optionally of a group), for pickers and admin grid. Needed by permission UIs (M6) and Walls (M7) too. | M6 | `/users` |
| `/data/groups.xml` | GroupsDataServlet | (none) | Groups (name, description, source). | M6 | `/groups` |
| `/data/dashlets.xml` | DashletsDataServlet | (none) | Defined dashlets. | M6 | `/me/dashlets` |
| `/data/languages.xml` | LanguagesDataServlet | gui (only GUI-enabled languages) | Active languages. | M6 | `/server` |
| `/data/timezones.xml` | TimeZonesDataServlet | (none) | Time zones for the profile form. | M6 | `/server` |
| `/data/devices.xml` | DevicesDataServlet | trustedonly | The user's (trusted) devices. | M6 | `/auth/tokens` |
| `/data/apikeys.xml` | ApiKeysDataServlet | (none) | The user's API keys. | M6 | `/auth/tokens` |
| `/data/ratings.xml` | RatingsDataServlet | docId | Ratings of a document. | none | – |
| `/data/contacts.xml` | ContactsDataServlet | userId | Address book contacts. | none | – |
| `/data/messages.xml` | MessagesDataServlet | (none) | The user's internal messages. | none | – |
| `/data/onlineusers.xml` | OnlineUsersDataServlet | (none) | Users with an open session. | none | – |
| `/data/dropbox.xml` | DropboxDataServlet (logicaldoc-dropbox module) | parent, etc. | Dropbox folder tree. Lives in the dropbox module, not in web/data. | none | – |
| `/data/attributesets.xml` | AttributeSetsDataServlet | type, withempty | Attribute sets. | Admin | `/admin/attributes` |
| `/data/stores.xml` | StoresDataServlet | types, parameters, empty | Configured file-storage backends (CE "Store"; unrelated to Healdan Store). | Admin | `/admin/storage` |
| `/data/menus.xml` | MenusDataServlet | (none) | Menu tree (admin screens). | Admin | `/admin/security` |
| `/data/sessions.xml (also /sessions/sessions.xml)` | SessionsDataServlet | node, status, kill, csv | Open sessions; can kill one or export CSV. | Admin | `/admin/sessions` |
| `/data/userhistory.xml` | UserHistoryDataServlet | id, event, comment | User audit events (logins etc.). | Admin | `/admin/history` |
| `/data/events.xml` | EventsDataServlet | folder, workflow, importfolder, user, ocr, webservice, ai, all | Audit event-type names for filters. | Admin | `/admin/history` |
| `/data/tasks.xml` | TasksDataServlet | (none) | Scheduled tasks and status. | Admin | `/admin/tasks` |
| `/data/jobs.xml` | JobsDataServlet | group, groupsonly | Quartz jobs. | Admin | `/admin/tasks` |
| `/data/indexingqueue.xml` | IndexingQueueDataServlet | (none) | Documents waiting to be indexed. | Admin | `/admin/search-engine` |
| `/data/parsers.xml` | ParsersDataServlet | (none) | Registered parsers (extension -> parser). | Admin | `/admin/search-engine` |
| `/data/formatconverters.xml` | FormatConvertersDataServlet | converter, in, out | Registered format converters. | Admin | `/admin/system` |
| `/data/conversionformats.xml` | ConversionFormatsDataServlet | fileName | Formats a file can be converted to (convert menu). | M6 | `/documents/{docNumber}/conversions` |
| `/data/tokenfilters.xml` | TokenFiltersDataServlet | filter | Index token filters. | Admin | `/admin/search-engine` |
| `/data/extensionaliases.xml` | ExtensionAliasesDataServlet | (none) | Extension aliases for parsing. | Admin | `/admin/search-engine` |
| `/data/charsets.xml` | CharsetsDataServlet | (none) | Available character sets (import). | Admin | `/admin/search-engine` |
| `/data/sequences.xml` | SequencesDataServlet | prefix | Named sequences (counters). | Admin | `/admin/system` |
| `/data/aspects.xml` | AspectsDataServlet | (none) | Run-level aspects (features on/off). | Admin | `/admin/system` |
| `/data/environment.xml` | EnvironmentDataServlet | (none) | System/Java/DB environment values. | Admin | `/admin/system` |
| `/data/log.xml` | LogDataServlet | loggers | Log4j loggers and appenders. | Admin | `/admin/system` |
| `/data/updatehistory.xml` | UpdateHistoryDataServlet | (none) | Installed updates and patches. | Admin | `/admin/system` |

## 3. Other servlets and endpoints

| URL | Class | Params | What it does | First needed by | Lands in |
|---|---|---|---|---|---|
| `/upload` | UploadServlet | multipart POST (any field); sid from SessionFilter (param/header/cookie); GET lists pending | Stores posted files in the HTTP session map `uploads` keyed by original file name (UUID file on disk). Max `<tenant>.upload.maxsize` MB (default 10), `<tenant>.upload.disallow` extensions. Files are consumed by RPCs (addDocuments, checkin, replaceFile, readImage, saveAvatar...). Stateful and name-keyed: two uploads with the same name collide. | M2 | the `file` part of `POST /documents` and `POST /documents/{docNumber}/versions` |
| `/uploadresource` | DocumentResourceUpload | docId, versionId/version, suffix; POST multipart | Upload a resource file (e.g. thumbnail/preview/alt rendition) for a document version; needs write. | M6 | `/documents/{docNumber}/renditions` |
| `/download, /download/*` | DownloadServlet | docId, versionId, version, fileVersion, suffix, fileName, downloadText, pluginId+resourcePath | Download a document's current/old file, a suffix rendition (thumb, conversion), the indexed text, or a plugin resource. Check-out needs this to get bytes. | M2 | `/documents/{docNumber}/content` |
| `/download-ticket` | TicketDownload | ticketId, suffix, password, behavior, control; Basic Authorization header | Public (security=none) download/preview via a ticket; checks expiry, counters, password. | M6 | stays as it is (public ticket links) |
| `/download-attachment` | DownloadAttachmentServlet | docId, fileVersion, attachmentFileName | Download an attachment inside an email document. | M6 | `/documents/{docNumber}/attachments` |
| `/thumbnail` | ThumbnailServlet | docId, version, fileVersion, suffix | Image rendition (thumbnail / preview image); generated on demand. | M6 | `/documents/{docNumber}/thumbnail` |
| `/convertpdf/*` | ConvertPdf | docId, version, fileVersion | Convert (if needed) and stream a PDF for viewing. | M6 | `/documents/{docNumber}/preview` |
| `/zip-export` | ExportZip | folderId, level (and doc ids); GET/POST | Zip a folder tree or selected docs for download. | M6 | `/folders/{id}/zip` |
| `/avatar` | AvatarServlet | userId | User picture image. | M6 | `/users/{id}/avatar` |
| `/legal` | LegalServlet | legal, user | Legal-document text/PDF to accept at login (unauthenticated). | M6 | `/auth/legal` |
| `/pswrecovery` | PswRecovery | ticketId, userId, tenant | Completes password recovery from the emailed link. | M6 | `/auth/password-reset` |
| `/dashletcontent` | com.logicaldoc.core.dashlet.DashletContent (core) | dashletId etc. | Renders a content dashlet. | M6 | `/me/dashlets` |
| `/display, /display/*` | DisplayServlet | query string or /type/id | Redirects deep links to `frontend.jsp?...` (GUI deep links). Replaced by the new UI's own routing. | none | – (the new UI routes its own links) |
| `/stat` | StatChartServlet (stat package; abstract ChartServlet base) | objectId, tenantId, width, height, locale | Chart images of repository stats for the Admin screens. | Admin | `/admin/system` |
| `/webservicechart` | WebserviceChartServlet (webservice module) | as /stat | Chart of web-service calls. | Admin | `/admin/system` |
| `/log` | LogDownload | appender | Downloads a log file / appender output (menu right). | Admin | `/admin/system` |
| `/echo` | EchoServlet | any | Debug: logs the request when echo.enabled=true. | none | – |
| `/frontend/gwteventservice` | de.novanic.eventservice (GWT Event Service) | GWT-RPC long-poll | Third-party GWT server-push; legacy, not part of the Healdan code. | none | – |
| `/frontend/dropbox` | DropboxServiceImpl | GWT-RPC | See Dropbox section. | none | – |
| `/services/*` | WebserviceServlet (CXF) | SOAP + REST (`/services/rest/*`) | Existing SOAP/REST APIs: out of scope, kept as is. | none | stays as it is |
| `/webdav/*, /service/cmis/*` | WebdavServlet, CmisServlet | WebDAV, CMIS | Existing interfaces: out of scope, kept as is. | none | stays as it is |
| `/j_spring_security_check (not a servlet)` | Spring Security form login | j_username, j_password, (2FA key)  | How the old GUI signs in (session cookie + ldoc-sid). Add-ins use /services/rest/auth instead. | M2 | `POST /auth/tokens` |
| `/wk-event (WebSocket)` | websockets.EventEndpoint | (none; server push only) | See WebSocket section. | M6 | `/events` (Server-Sent Events, proposed for M6) |

## 4. WebSocket `/wk-event`

- Class `websockets.EventEndpoint` (`@ServerEndpoint("/wk-event")`), registered as an `EventCollector` listener on first `@OnOpen`. Incoming messages are ignored (log only): it is push-only. `/wk-event` requires `isAuthenticated()` at the HTTP upgrade (context-security.xml), but the endpoint keeps a global `peers` set and **broadcasts every message to every connected peer**: no per-user, per-folder or per-permission filtering, and no tenant filtering beyond the event's own tenant name in the payload.
- Pushing is on only when `EventCollector.isEnabled()` and `<tenant>.gui.serverpush=true`, and `event.isNotifyEvent()`.
- Events pushed (whitelist `MONITORED_EVENTS`): document STORED, CHANGED, CHECKEDIN, CHECKEDOUT, IMMUTABLE, LOCKED, UNLOCKED, SIGNED, STAMPED, MOVED, DELETED, RENAMED, INDEXED, EMBEDDED, RESTORED, PASSWORD_PROTECTED/UNPROTECTED, READING_CONFIRMED/REQUESTED; folder RENAMED, CREATED, CHANGED, MOVED, DELETED; user MESSAGE_RECEIVED, LOGIN, LOGOUT, TIMEOUT; `event.chat.newmessage`. De-duplicated by a 1000-entry FIFO per History class.
- Payload: a `WebsocketMessage` (GUI bean: sid, event name, userId, username, comment, date, id, tenantId, author, folderId, docId, plus full `GUIDocument` and `GUIFolder` built with `DocumentServiceImpl.fromDocument` / `FolderServiceImpl.fromFolder`). Commands from automation (`WebsocketTool.showMessage/openUrl`) use event `command` with `command`, `payload`, `target` and a negative random id.
- Serialization: **GWT-RPC stream format** written with `ServerSerializationStreamWriter` and a permissive `SimpleSerializationPolicy` (`CustomSerializationPolicyProvider`), sent as a text frame. Not JSON; only a GWT client (or a hand-written GWT-RPC parser) can read it. The payload leaks document metadata (name, folder path, attributes) of every event to every connected user, which conflicts with M7 walls.
- No history/replay, no subscription model, no acknowledgements.

## 5. Notes for the API design (odd semantics and gaps)

1. **Uploads are stateful.** `/upload` stores files in the HTTP session keyed by *file name*; `addDocuments`, `checkin`, `replaceFile`, `readImage`, `saveAvatar` consume them from the session of the same sid. A JSON API needs a stateless upload (multipart on the create/check-in endpoint, or an upload-id) and must not reuse name-keyed session state.
2. **Check-out is two calls.** `checkout` only flags the document; the bytes come from `/download`. `checkin` needs a prior `/upload`. There is no single "check in this content" RPC except `checkinContent` (text only).
3. **Create has three shapes**: `addDocuments` (from uploads, many files, zip import), `createDocument` (metadata + string), `createWithContent` (metadata + string, optional checkout). None takes binary content directly.
4. **No RPC exposes inherited values or required-field status.** Healdan stamps inherited profile values onto documents in listeners (`profile.Inheritance`, `inheritedFrom(folderId)` exists server side only) and keeps edit marks; the only GUI-visible hints are `GUIAttribute.mandatory` (via `TemplateService.getAttributes`) and `DocumentService.validate`. The add-in "properties with required fields and inherited values" needs a new read model.
5. **Healdan matter RPCs are mostly admin-only.** `describeFolder`, `myMatters`, `recentMatters`, `resolve` work for any user, but `listStores`, `getByFolder` and `listTemplates` require admin. Browsing "Stores" for ordinary users is not possible through the existing RPCs; `FoldersDataServlet` (`parent=/`) is the only generic way. A "Recent folders" source does not exist (only recent matters); `documenthistory.xml`/`DocumentHistory` is the raw material.
6. **"Store" naming clash.** `/data/stores.xml`, `SettingService.testStore/saveStoreSettings/removeStore` are CE file-storage backends, not Healdan Stores/Cabinets.
7. **Sign-in is not an RPC.** The GUI signs in with Spring form login at `/j_spring_security_check` and then calls `SecurityService.getSession` for the GUISession/GUIUser. `LoginService` only covers password reset/legal/2FA hints. Existing token-ish options: REST `/services/rest/auth` (sid), `X-API-KEY`.
8. **`getSession(locale, sid)` accepts any sid** and returns the full GUISession (user, groups, menus, config) or null.
9. **XML data servlets use SmartGWT conventions** (`criteria` JSON string, `parent`, `max`), paging via `page` in `DocumentsDataServlet`, and return errors as an error body. They are the only path for listings (folders, documents, versions, history, ACL, trash), so each needs a JSON equivalent; search results come from `SearchService.search` (RPC), not a data servlet.
10. **Enterprise stubs:** 29 of 50 services (192 of 475 methods) have a GUI interface but no server class; they are listed so nobody mistakes them for working RPCs.
11. **M5 and M7 have no existing RPC**: email filing reuses the create/upload path (M2); walls need new server code. `searchUsers`, `users.xml`, `groups.xml` and the ACL RPCs are the CE pieces a Walls app would read.
