# Healdan API inventory

- **What:** every server call the old GWT UI makes, with the milestone that first needs it in the new JSON API, and where it lands there.
- **Written:** 2026-10-23, for the [M2 design](../superpowers/specs/2026-10-23-m2-api-foundation-design.md) (§12), from the code at `808d5395b`.
- **Kept current:** each milestone that adds endpoints updates its entries' tags and endpoints in the same pull request.
- **After M6**, the User app's later phases add their endpoints to §0.3, each marked "built after M6", and their old calls move from `Later` to `M6`: a favourite's name and renaming folders (2026-11-14), and moving and deleting folders and the Trash (2026-11-17, the [folders and Trash spec](../superpowers/specs/2026-11-16-folders-and-trash-design.md) §6).
- **The Admin app's Phase 1 built** its endpoints on 2026-11-19 (§0.5), in pull request `Admin app: the foundation and the Dashboard` ([Admin app spec](../superpowers/specs/2026-11-18-admin-app-design.md) §10). Its later phases add theirs to §0.5: Phase 2 built its clients', matters', workspaces', templates', Stores' and groups' on 2026-11-20, in pull request `Admin app: clients, matters and workspaces` (Healdan-Project/healdan#287), Phase 3 its Stores' and storage's on 2026-11-21, in pull request `Admin app: Stores and Backends` (Healdan-Project/healdan#294), Phase 4 its users' and groups' on 2026-11-21, in pull request `Admin app: Users and Groups` (Healdan-Project/healdan#298), and Phase 5 its errors', logs', audit trail's and background jobs' on 2026-11-22, in pull request `Admin app: monitoring` (Healdan-Project/healdan#311). Of the corrections the spec's §10 names, Healdan's storage service (`HealdanStorageService`, the Classic Interface's Storage tab, `/frontend/healdanstorage`), which §1 doesn't list and §0's totals don't count, has each of its calls mapped in §0.5, as Phase 3 replaces it whole; the sign-in methods M4 added to `HealdanSettingsService`, which this file doesn't list yet, wait for Stage 2, which replaces them.
- **M6 built** its endpoints on 2026-11-10 to 2026-11-12 (§0.3), and its design left the rest of the old calls it weighed to later phases of the User app (`Later`) or to none (M6 spec §4, §16).
- **M3 built** its endpoints on 2026-10-24 (§0.2): the bookmarks, `FolderService.create` and `/thumbnail` moved from M6 to M3, and the rest of M3's additions have no old call behind them.
- **M2 built** every `M2` endpoint named here on 2026-10-23 and 2026-10-24 (§0.1). Its `openapi.yaml` (`healdan-api/src/main/resources/healdan/api/openapi.yaml`) is the reference for what they do; this file only maps the old calls to them.

**Tags.** The milestone that first needs the call: `M2` (the add-in slice), `M3`, `M5`, `M6` (the User app), `M7` (walls), `Later` (a phase of the User app after M6), `Admin` (the backlog's Admin app), or `none`. `none` covers enterprise stubs with no server code (marked **NO IMPL**) and CE features Healdan doesn't use. Tags are judgements, and a milestone's design session can move them.

**Lands in.** `M2`, `M3` and `M6` entries name the endpoint that replaces them (§0.1 and the M2 spec §6, §0.2 and the M3 spec §9, §0.3 and the M6 spec §9). Everything else names only the resource area it will probably land in: a hint for that milestone's design, not a designed endpoint. Paths are under `/api/v1`.

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
| M3 | 3 | 1 | 1 | 5 |
| M5 | 0 | 0 | 0 | 0 |
| M6 | 18 | 4 | 2 | 24 |
| M7 | 0 | 0 | 0 | 0 |
| Later | 64 | 14 | 7 | 85 |
| Admin | 123 | 20 | 3 | 146 |
| none | 236 | 7 | 7 | 250 |
| **all** | 475 | 52 | 23 | 550 |

### 0.1 What M2 replaces (built in M2)

M2 built every endpoint in this table. The rows marked "(none: …)" are endpoints with no old call behind them.

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
| (none: a user's device sign-ins are new) | `GET /auth/tokens`, `DELETE /auth/tokens/{id}` |
| (none: the matter admin RPCs read a matter by its workspace, for admins only) | `GET /matters/{id}` |
| (none: the description file is new) | `GET /openapi.yaml`, and the docs page at `/api/docs/` |

### 0.2 What M3 adds (built in M3)

M3 built every endpoint in this table, for the Healdan Helper's windows and saves ([M3 spec](../superpowers/specs/2026-10-24-m3-office-integration-design.md) §9). The rows marked "(none: …)" have no old call behind them.

| Old call | M3 endpoint |
|---|---|
| `DocumentService.addBookmarks`, `deleteBookmarks`; `/data/bookmarks.xml` | `GET /me/favorites`, `POST /me/favorites`, `DELETE /me/favorites/{id}`: the bookmarks, apart from My Matters |
| `FolderService.create` | `POST /folders` |
| `/thumbnail` | `GET /documents/{docNumber}/thumbnail` |
| (none: searching inside a folder is new) | `GET /search?within=` |
| (none: which computer holds a check-out is new) | `device` on `POST /auth/tokens`; `app` and `move` on `POST /documents/{docNumber}/checkout`; `hold.device`; `409 held-elsewhere` |
| (none: save preconditions are new) | `ifVersion`, `ifHeld` and `saveId` on `POST /documents/{docNumber}/versions` |
| (none: stamping is new) | the `Healdan Doc ID` property filled in by `POST /documents` and `POST /documents/{docNumber}/versions` |
| `HealdanLookupService.lookup` (the title's form) | `GET /lookup?q=` and `/search` take `#1234 v2.1` |

### 0.3 What M6 adds (built in M6)

M6 built every endpoint in this table, for the User app ([M6 spec](../superpowers/specs/2026-11-10-m6-user-app-design.md) §9), but `GET /server` and `GET /users`, which M2 built and the app uses as they are. The rows marked "(none: …)" have no old call behind them.

| Old call | M6 endpoint |
|---|---|
| `SecurityService.logout`, on the web page | `DELETE /session`, on the web session only |
| `HealdanMatterService.addToMyMatters`, `removeFromMyMatters` | `PUT /me/matters/{folderId}`, `DELETE /me/matters/{folderId}` |
| `DocumentService.rename` | `PATCH /documents/{docNumber}` |
| `DocumentService.updateBookmark`, a bookmark's name | `PATCH /me/favorites/{id}`, and `name` in `FavoriteView` (built after M6: the owner's dogfooding punch list, 2026-11-14) |
| `DocumentService.delete` | `DELETE /documents/{docNumber}`, which answers the delete's `TrashItem` (since the folders and Trash spec; `204` before) |
| `FolderService.paste`, a cut's move | `POST /documents/{docNumber}/move` |
| `FolderService.rename` | `PATCH /folders/{id}` with `{ name }`, and `canRename` in `FolderView` (built after M6: the [folder rename spec](../superpowers/specs/2026-11-14-folder-rename-design.md), 2026-11-14) |
| `FolderService.delete` | `DELETE /folders/{id}`, answering the delete's `TrashItem` (built after M6: the folders and Trash spec, 2026-11-17) |
| `FolderService.move` | `POST /folders/{id}/move` (built after M6: the same) |
| `DocumentService.restore`, `FolderService.restore` | `POST /trash/{id}/restore`, back where it was or into `folderId` (built after M6: the same) |
| `DocumentService.deleteFromTrash`, `FolderService.deleteFromTrash` | `DELETE /trash/{id}`, to Removed (built after M6: the same) |
| `DocumentService.emptyTrash` | `DELETE /trash` (built after M6: the same) |
| `/data/garbage.xml`, `/data/deleteddocs.xml`, `/data/deletedfolders.xml` | `GET /trash?view=mine`, and `everyone` and `removed` for administrators (built after M6: the same) |
| (none: the counts before a folder's delete or move are new) | `GET /folders/{id}/contents`, and `canMove` and `canDelete` in `FolderView` (built after M6: the same) |
| `DocumentService.extractEmail`, `saveEmailAttachment` | `GET /documents/{docNumber}/message`, `GET /documents/{docNumber}/message/body`, `POST /documents/{docNumber}/message/attachments/{id}/file` |
| `/download-attachment` | `GET /documents/{docNumber}/message/parts/{id}` |
| `/convertpdf/*` | `GET /documents/{docNumber}/preview` |
| `SettingService.loadGUISettings`, `loadSettingsByNames`; `/data/users.xml` | M2's `GET /server` and `GET /users?q=`, as they are |
| (none: recent emails are new) | `GET /documents/recent?kind=email` |
| (none: recent clients are new) | `GET /clients/recent` |
| (none: suggestions are new) | `GET /suggest?q=` |
| (none: filters and their counts are new) | `type`, `author`, `class`, `client`, `matter`, `edited`, `from`, `to`, `zone`, `sort`, `order` and `facets` on `GET /folders/{id}/children` and `GET /search` |
| (none: overrides are new) | `overrides` on `GET /documents/{docNumber}/properties` |
| (none: the browser's way in is new) | the web-session route, on every endpoint but those for tokens alone (M6 spec §8.1) |

### 0.4 Per service

| Service | Methods | M2 | M3 | M5 | M6 | M7 | Later | Admin | none |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| InfoService | 4 | 1 |  |  |  |  | 1 | 1 | 1 |
| SecurityService | 44 | 4 |  |  |  |  | 14 | 26 |  |
| HealdanLookupService | 1 | 1 |  |  |  |  |  |  |  |
| HealdanMatterService | 12 | 5 |  |  | 2 |  |  | 5 |  |
| HealdanSearchFolderService | 6 | 2 |  |  |  |  | 4 |  |  |
| HealdanSettingsService | 6 | 1 |  |  |  |  |  | 5 |  |
| AttributeSetService | 10 |  |  |  |  |  |  | 10 |  |
| AuditService | 4 |  |  |  |  |  |  |  | 4 |
| AutomationService | 8 |  |  |  |  |  |  |  | 8 |
| BarcodeService | 4 |  |  |  |  |  |  |  | 4 |
| CalendarService | 5 |  |  |  |  |  |  |  | 5 |
| ChatGPTService | 5 |  |  |  |  |  |  |  | 5 |
| ChatService | 2 |  |  |  |  |  |  |  | 2 |
| ClusterService | 2 |  |  |  |  |  |  |  | 2 |
| ContactService | 5 |  |  |  |  |  |  |  | 5 |
| DashletService | 7 |  |  |  |  |  |  | 3 | 4 |
| DocuSignService | 6 |  |  |  |  |  |  |  | 6 |
| DocumentService | 72 | 12 | 2 |  | 8 |  | 28 | 8 | 14 |
| EmailAccountService | 8 |  |  |  |  |  |  |  | 8 |
| FolderService | 27 | 2 | 1 |  | 6 |  | 10 | 7 | 1 |
| FormService | 6 |  |  |  |  |  |  |  | 6 |
| ImpexService | 12 |  |  |  |  |  |  |  | 12 |
| ImportFolderService | 8 |  |  |  |  |  |  |  | 8 |
| LDAPService | 7 |  |  |  |  |  |  |  | 7 |
| MessageService | 8 |  |  |  |  |  |  | 5 | 3 |
| OCRService | 1 |  |  |  |  |  |  |  | 1 |
| ReadingRequestService | 5 |  |  |  |  |  |  |  | 5 |
| ReportService | 9 |  |  |  |  |  |  |  | 9 |
| RetentionPoliciesService | 5 |  |  |  |  |  |  |  | 5 |
| SchemeService | 6 |  |  |  |  |  |  |  | 6 |
| SearchEngineService | 14 |  |  |  |  |  |  | 14 |  |
| SearchService | 5 | 1 |  |  |  |  |  |  | 4 |
| SettingService | 18 |  |  |  | 2 |  |  | 16 |  |
| ShareFileService | 5 |  |  |  |  |  |  |  | 5 |
| SignService | 11 |  |  |  |  |  |  |  | 11 |
| SplitService | 1 |  |  |  |  |  |  |  | 1 |
| StampService | 8 |  |  |  |  |  |  |  | 8 |
| SyndicationService | 6 |  |  |  |  |  |  |  | 6 |
| SystemService | 17 |  |  |  |  |  |  | 17 |  |
| TagService | 6 |  |  |  |  |  |  | 2 | 4 |
| TemplateService | 6 | 2 |  |  |  |  |  | 4 |  |
| TenantService | 7 |  |  |  |  |  |  |  | 7 |
| TwoFactorsAuthenticationService | 3 |  |  |  |  |  |  |  | 3 |
| UpdateService | 17 |  |  |  |  |  |  |  | 17 |
| WorkflowService | 26 |  |  |  |  |  |  |  | 26 |
| ZonalOCRService | 4 |  |  |  |  |  |  |  | 4 |
| LoginService | 7 |  |  |  |  |  | 7 |  |  |
| TfaService | 1 |  |  |  |  |  |  |  | 1 |
| SetupService | 2 |  |  |  |  |  |  |  | 2 |
| DropboxService | 6 |  |  |  |  |  |  |  | 6 |

### 0.5 What the Admin app adds (Phases 1 to 5 built)

Phase 1 built the first three endpoints in this table, for the Admin app's Dashboard and its find (Admin app spec §8.1, §10), Phase 2 those from `listClients` to `/data/groups.xml`, for its clients, its matters, their workspaces, the workspace templates, and the Stores and groups a first matter needs (§8.2 to §8.6, §14), Phase 3 those from `HealdanMatterService.listStores` with each Store's backend to `HealdanStorageService.saveSettings`, for the Stores and the storage backends (§8.6, §9.7), which do everything the Classic Interface's Storage tab does, through `HealdanStorageService`'s calls, Phase 4 those from `/data/users.xml` to `/data/users.xml?groupId=`, for the users and groups (§8.7), through LogicalDOC's user and group DAOs, none of `SecurityService`'s calls, which stay the Classic Interface's, and Phase 5 the rest so far, for the errors, the server log, the audit trail and the background jobs (§8.8, §9.3 to §9.5), through Healdan's own services over LogicalDOC's history tables, its logging configuration and its task scheduling, none of `SystemService`'s calls, which stay the Classic Interface's. They're under `/api/v1/admin`, described in `openapi-admin.yaml`, not `openapi.yaml`, and answer administrators' web sessions only. The rows marked "(none: …)" have no old call behind them. Phase 5 also makes three of the User app's routes record a reading of the document in its history (`event.viewed`), with no change to their answers but a `HEAD` of `/message/body`, which now answers the headers alone: `GET /documents/{docNumber}/preview`, `GET /documents/{docNumber}/message/body` and `GET /documents/{docNumber}/message/parts/{id}?inline=true`, the last two as a view of the email. Those three are under `/api/v1`, in `openapi.yaml`.

| Old call | Admin endpoint |
|---|---|
| `SystemService.getStatistics` | `GET /admin/dashboard/usage`, whose signed-in count is what Phase 1 replaces of the Sessions screen; its list, `/data/sessions.xml`, is Stage 2's (spec §11) |
| (none: Needs Attention and Server Health are new) | `GET /admin/dashboard` |
| (none: one find of clients, matters and people is new) | `GET /admin/find?q=` |
| `HealdanSettingsService.listClients` | `GET /admin/clients`, with each client's matters and figures, and `GET /admin/clients/{id}` |
| `HealdanSettingsService.saveClient` | `POST /admin/clients` to create, `PATCH /admin/clients/{id}` to rename |
| `HealdanSettingsService.suggestClientNumber` | `GET /admin/clients/next-number` |
| (none: no old call lists every matter) | `GET /admin/matters` |
| `HealdanMatterService.suggestMatterNumber` | `GET /admin/matters/next-number?clientId=` |
| (none: New Matter's check as the form changes is new) | `POST /admin/matters/check` |
| `HealdanMatterService.create` | `POST /admin/matters` |
| `HealdanMatterService.getByFolder` | `GET /admin/matters/{id}`, by the matter's id |
| `HealdanMatterService.update` | `PATCH /admin/matters/{id}` |
| `FolderService.move` of a matter's own folder into another Store | `POST /admin/matters/{id}/move` |
| `FolderService.delete` of a matter's own folder | `DELETE /admin/matters/{id}`, answering its Trash item |
| `FolderService.getFolder`'s permissions, for a workspace's folder | `GET /admin/folders/{id}/access` |
| `FolderService.saveACL`, `inheritACL`, for a workspace's folder | `PUT /admin/folders/{id}/access`, with `rows` or `followParent: true` |
| `FolderService.saveACL` with its subfolders, for a workspace's folder | `POST /admin/folders/{id}/apply-access` |
| `/data/folders.xml`, for a workspace's folders | `GET /admin/workspaces/{folderId}/tree`, the whole tree at once, with search folders and which folders have permissions of their own |
| `FolderService.create`, for a workspace's folder | `POST /admin/folders/{id}/children` |
| `FolderService.rename`, for a workspace's folder or search folder | `PATCH /admin/folders/{id}` |
| `FolderService.move`, within a workspace | `POST /admin/folders/{id}/move` |
| `FolderService.delete`, for a workspace's folder | `DELETE /admin/folders/{id}`, answering its Trash item |
| `FolderService.getFolder`'s attributes and `FolderService.save`, for a workspace's folder | `GET`, `PUT /admin/folders/{id}/properties` |
| `HealdanSearchFolderService.create`, in a workspace | `POST /admin/folders/{id}/search-folders` |
| `HealdanSearchFolderService.get`, `update`, in a workspace | `GET`, `PATCH /admin/search-folders/{id}` (the rule; a rename is `PATCH /admin/folders/{id}`) |
| `HealdanSearchFolderService.classes`, `subclasses` | the `docclass` and `docsubclass` options of `GET /admin/folders/{id}/properties` |
| `HealdanMatterService.listTemplates` | `GET /admin/templates`, with each template's folders and matters, and `GET /admin/templates/{id}` |
| `FolderService.create` in the Templates Store | `POST /admin/templates`, empty or a copy of another (`copyOf`) |
| `FolderService.rename` of a template | `PATCH /admin/templates/{id}` |
| `FolderService.delete` of a template | `DELETE /admin/templates/{id}`, answering its Trash item |
| `FolderService.restore` of a template, or of a template's folder | stays the Classic Interface's, its Trash and its Deleted folders report, until Stage 2 (spec §11), as `POST /trash/{id}/restore` restores into Stores only |
| (none: what New Matter would refuse in a template is new) | `GET /admin/templates/{id}/problems` |
| `HealdanMatterService.listStores` | `GET /admin/stores`, beside M2's `GET /stores` |
| `FolderService.create` of a workspace under the root | `POST /admin/stores`, with the groups that can see it |
| `FolderService.getFolder`'s permissions, `saveACL`, for a Store | `GET`, `PUT /admin/stores/{id}/access` |
| `/data/groups.xml` | `GET /admin/groups?q=`, people's own groups left out |
| `HealdanMatterService.listStores`, with each Store's backend from `HealdanStorageService.load` | `GET /admin/stores`, each row with its matters, figures, who can read it, its backend and its files waiting and stuck (changed from Phase 2's `{ id, name }`), and `GET /admin/stores/{id}` |
| `FolderService.rename` of a Store | `PATCH /admin/stores/{id}` (Default, the Templates Store and the root refused) |
| `FolderService.delete` of a Store | `DELETE /admin/stores/{id}`, the Store and its matters as one Trash item, answering it |
| `/data/deletedfolders.xml`, for the Stores | `GET /admin/stores/deleted`, deleted or removed |
| `FolderService.restore` of a Store | `POST /admin/stores/{id}/restore`, back under the root, as `POST /trash/{id}/restore` refuses a Store's item |
| `HealdanStorageService.load` | `GET /admin/backends` (the backends, each with its live Stores and its Stores in the Trash, and the files with no Store), `GET /admin/backends/{id}`, `GET /admin/stores/{id}/backend` (a Store's) and `GET /admin/storage/settings`, with the settings' limits |
| `HealdanStorageService.testNew` | `POST /admin/backends/test`, with `id` for a backend being edited |
| `HealdanStorageService.addBackend` | `POST /admin/backends`, answering an encrypted backend's recovery key once |
| `HealdanStorageService.saveBackend` | `PATCH /admin/backends/{id}`, a new bucket, prefix or path refused by name while a Store in the Trash keeps its files there |
| `HealdanStorageService.testBackend` | `POST /admin/backends/{id}/test` |
| `HealdanStorageService.removeBackend` | `DELETE /admin/backends/{id}`, refused by name while a Store in the Trash keeps its files there, as while a live one does |
| `HealdanStorageService.showRecoveryKey`, `confirmRecoveryKey`, `enterRecoveryKey` | `POST /admin/backends/{id}/recovery-key/show`, `/confirm`, `/enter` |
| `HealdanStorageService.keyEvents` | `GET /admin/backends/{id}/key-events` |
| `HealdanStorageService.previewStoreSwitch` | `GET /admin/stores/{id}/backend?backendId=`'s `switchPreview` |
| `HealdanStorageService.setStoreBackend` | `PUT /admin/stores/{id}/backend`, and `backendId` on `POST /admin/stores` |
| `HealdanStorageService.stuckFiles`, `retry` | `GET /admin/stores/{id}/stuck-files` and `POST …/retry` for a Store's; `GET /admin/storage/stuck-files` and `POST …/retry` for the files with no Store |
| `HealdanStorageService.saveSettings` | `PUT /admin/storage/settings`, `422 out-of-range` against the limits |
| `/data/users.xml`, the Users screen's grid | `GET /admin/users`, with each person's groups, how they sign in, status (`blocked` from the sign-in throttle), last sign-in and devices, system and deleted accounts left out |
| (none: what New User and Edit offer is new) | `GET /admin/users/options`: the languages, the default, the time zones, the sign-in providers on, and whether outgoing email is set up |
| `SecurityService.saveUser` of a new account, with `generatePassword` | `POST /admin/users`, a password account with a temporary password, emailed or answered once |
| `SecurityService.getUser`, for an administrator | `GET /admin/users/{id}`, with the actions the server would refuse (`refusals`) |
| `SecurityService.saveUser` of an account's names, email, language and time zone | `PATCH /admin/users/{id}` |
| `SecurityService.changeStatus` | `POST /admin/users/{id}/disable`, `/enable`, Disable revoking the devices and closing the sessions |
| `SecurityService.changePassword` by an administrator | `POST /admin/users/{id}/reset-password`, a temporary password only, emailed or answered once |
| `SecurityService.kill`, of every session of one person | `POST /admin/users/{id}/sign-out`, every session and device token; one session's `kill` and the Sessions list stay Stage 2's (spec §11) |
| `SecurityService.loadBlockedEntities`, `removeBlockedEntities`, for a username | `POST /admin/users/{id}/clear-block`, the username's count only; the users list's `status` says who's blocked |
| `SecurityService.saveUser`'s groups, `addUserToGroup`, `removeFromGroup` | `PUT /admin/users/{id}/groups`, a person's whole set, and `PUT`, `DELETE /admin/groups/{id}/members/{userId}`, one at a time |
| (none: an administrator's view of a person's device tokens is new) | `GET /admin/users/{id}/devices`, `DELETE /admin/users/{id}/devices/{tokenId}` |
| `/data/lockeddocs.xml?userId=`, for an administrator | `GET /admin/users/{id}/checkouts`, check-outs only, as `GET /me/checkouts` reads one's own; released through `DELETE /documents/{docNumber}/checkout` |
| `/data/groups.xml`, the Groups screen's grid | `GET /admin/groups`, each row with `members`, `fixed`, `admin` and `mappedBy` (grown from Phase 2's `id`, `name` and `description`) |
| `SecurityService.saveGroup` of a new group | `POST /admin/groups` |
| `SecurityService.getGroup` | `GET /admin/groups/{id}`, with `folders`, how many folders grant it |
| `SecurityService.saveGroup` of a group's name and description | `PATCH /admin/groups/{id}` (the fixed groups' names refused) |
| `SecurityService.deleteGroup` | `DELETE /admin/groups/{id}` (fixed groups, and groups a sign-in provider's map names, refused) |
| `/data/users.xml?groupId=` | `GET /admin/groups/{id}/members`, paged, with `q` |
| (none: the errors the server logged, grouped by fault, are new) | `GET /admin/errors`, by window, area, words and whether resolved, and `GET /admin/errors/{id}`, with its stack, the documents it names and its log |
| (none: marking a fault resolved is new) | `POST /admin/errors/{id}/resolve`, `/reopen`, each recorded |
| `/data/log.xml`'s appenders | `GET /admin/logs`, the logs written to files, the HTML copies left out; the loggers' levels stay the Classic Interface's |
| `/log?appender=`, to show one log | `GET /admin/logs/{name}/lines`, a log's current and rolled-over files read as one, by position, with a level and words, followed as it grows |
| `/log?appender=`, to download one log | `GET /admin/logs/{name}/file`, the log's files as they are, zipped; `appender=all`, every log with the server's settings, stays the Classic Interface's (System Information) |
| `SystemService.search`, `/data/userhistory.xml`, `/data/documenthistory.xml` and `/data/folderhistory.xml`, for an administrator | `GET /admin/audit`, one feed of the document, folder and account histories, the administration record and the storage record of recovery keys, newest first, by cursor, with the kind, a person, a matter, a doc #, days and words; a person's and a matter's Activity tabs are its `userId` and `matterId` |
| (none: exporting the audit trail is new) | `GET /admin/audit/export`, the same rows as CSV, to 100,000 |
| `SystemService.loadTasks`, `getTaskByName`, `/data/tasks.xml` | `GET /admin/jobs`, Healdan's jobs and LogicalDOC's six tasks, each with its last run, result, error, next run and schedule |
| `SystemService.startTask`, `stopTask` | `POST /admin/jobs/{name}/run`, `/stop`, Run Now for five of Healdan's jobs too |
| `SystemService.enableTask`, `disableTask` | `POST /admin/jobs/{name}/enable`, `/disable` |
| `SystemService.saveTask`'s schedule | `PUT /admin/jobs/{name}/schedule`, every so many seconds or a cron expression; a task's delay and maximum duration stay the Classic Interface's |

## 1. GWT-RPC services

Transport: each interface is bound to a servlet by `@RemoteServiceRelativePath`; `web.xml` maps the full URL (`/frontend/<path>`, plus `/login/...`, `/setup/...`, `/sessions/...` aliases for a few). All are GWT-serialized (not JSON), session from the `ldoc-sid`/JSESSIONID cookie. `*Impl` classes are in `logicaldoc-webapp/.../web/service/` unless stated. Healdan impls extend `HealdanRemoteService` and register with `@WebServlet`. `HealdanRpcChecks` (webapp) adds Healdan permission checks inside CE's DocumentService/FolderService/SearchService/SecurityService impls.

### InfoService

- Interface: `gui/common/client/services/InfoService.java`; RPC URL `/frontend/info` (`@RemoteServiceRelativePath("info")`)
- Server impl: **InfoServiceImpl**
- Methods: 4 (M2 1, Later 1, Admin 1, none 1)
- Lands in: `/server` (M2); `/me` (Later); `/admin/tasks` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getInfo` | Server/product info, feature list (Feature_*), tenant config, i18n bundle, supported languages, alerts. Called before and after login (`login` flag). | M2 |
| `ping` | Session keep-alive; false if the session is gone. Not needed by the API: each token request renews its session. | none |
| `getSessionInfo` | Unread system-message count and whether the session is still open (heartbeat poll). The User app sends no keep-alive (M6 spec §8.2); the unread count waits for Messages. | Later |
| `getCronDescription` | Human text for a cron expression (task scheduler UI). | Admin |

### SecurityService

- Interface: `gui/common/client/services/SecurityService.java`; RPC URL `/frontend/security` (`@RemoteServiceRelativePath("security")`)
- Server impl: **SecurityServiceImpl**
- Methods: 44 (M2 4, Later 14, Admin 26)
- Lands in: `/me`, `/auth/tokens`, `/users` (M2); `/session` for the User app's sign-out (M6); `/me/profile`, `/me/password`, `/auth/tokens` for API keys and devices (Later); `/admin/users`, `/admin/groups` (Admin, built in the Admin app's Phase 4, §0.5), `/admin/security` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getSession` | Builds the GUISession (sid, GUIUser incl. groups/permissions/menus, locale, config) for the current or given sid; null if invalid. This is the "current user" call. | M2 |
| `getUser` | Full GUIUser by id (profile, groups, quotas, settings). Used for the current user too: GUI passes its own id. | M2 |
| `logout` | Close the current session. Built as `DELETE /auth/tokens/current` for a token, and as `DELETE /session` for the User app's web session (M6). | M2 |
| `saveProfile`, `saveInterfaceSettings`, `changePassword`, `saveAvatar`, `resetAvatar` | User edits own profile, UI settings, password and avatar (avatar is uploaded to /upload first). | Later |
| `createApiKey`, `deleteApiKey`, `updateApiKey` | Own API keys (X-API-KEY, `ld-` prefix). Candidate seed for a token API. | Later |
| `trustDevice`, `updateDeviceLabel`, `isTrustedDevice`, `deleteTrustedDevices` | Trusted-device list for 2FA (own account). | Later |
| `addImpersonifier`, `deleteImpersonifiers` | Users allowed to impersonate me. | Later |
| `searchUsers` | Find users by username and/or group (user pickers in permissions, share, messages). Also needed by M7. | M2 |
| `saveUser`, `deleteUser`, `changeStatus`, `replicateUsersSettings`, `cloneWorkTimes`, `createAccessTicket` | User CRUD, enable/disable, copy settings/work times, one-time access tickets. | Admin (`saveUser` and `changeStatus` in `/admin/users`, §0.5; the Admin app deletes no account) |
| `getGroup`, `saveGroup`, `deleteGroup`, `removeFromGroup`, `addUserToGroup` | Group CRUD and membership. | Admin (`/admin/groups` and a person's `/admin/users/{id}/groups`, §0.5) |
| `generatePassword`, `generatePassword2`, `validatePassword` | Generate/validate a password against policy. | Admin (a temporary password made on the server, in `/admin/users`' New User and Reset Password, §0.5) |
| `loadSettings`, `saveSettings` | Security settings (password policy, lockout, SSO flags). | Admin |
| `kill` | Terminate a session by sid. | Admin (every session of one person: `/admin/users/{id}/sign-out`, §0.5; one session, Stage 2) |
| `getMenu`, `getMenus`, `saveMenus`, `saveMenu`, `deleteMenu`, `saveACL` | Menu tree and menu ACLs (what admin screens a group may open). | Admin |
| `loadBlockedEntities`, `removeBlockedEntities`, `syncGeolocationDB` | Blocked IPs/users, geolocation DB sync. | Admin (a username's block: `/admin/users/{id}/clear-block`, §0.5) |

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
- Lands in: `/folders/{id}`, `/matters/recent`, `/matters/mine`, `/search?scope=matters`, `/stores` (M2); `/me/matters/{folderId}` (M6); `/admin/matters`, `/admin/templates` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `describeFolder` | What a folder is in Healdan's layout (kind, matter id, top level?, search folder?, in My Matters?). Needs READ. | M2 |
| `recentMatters`, `myMatters` | Recent matters (where I last changed a document) and My Matters, with workspace id, display `client.matter - name`, client name. | M2 |
| `resolve` | Matter-number lookup: `998.1` or a bare number -> matters (max 100). | M2 |
| `addToMyMatters`, `removeFromMyMatters` | Edit My Matters (folder bookmarks). Built as `PUT` and `DELETE /me/matters/{folderId}`. | M6 |
| `listStores` | The Stores to choose from. Admin-only, and the API keeps it admin-only (`GET /stores`). | M2 |
| `create`, `update`, `getByFolder`, `suggestMatterNumber`, `listTemplates` | Matter admin: create from template (all-or-nothing), edit, read by workspace, suggest number, templates to choose from. All admin-only. | Admin (`/admin/matters`, and `listTemplates` `/admin/templates`, §0.5) |

### HealdanSearchFolderService

- Interface: `gui/frontend/client/healdan/services/HealdanSearchFolderService.java`; RPC URL `/frontend/healdansearchfolder` (`@RemoteServiceRelativePath("healdansearchfolder")`)
- Server impl: **HealdanSearchFolderServiceImpl**
- Methods: 6 (M2 2, Later 4)
- Lands in: `/folders/{id}`, `/folders/{id}/children` (M2); `/search-folders/{id}` (Later); `/admin/folders/{id}/search-folders`, `/admin/search-folders/{id}` (a workspace's, built in the Admin app's Phase 2, §0.5)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `get`, `evaluate` | Search folders (saved rule shown as a folder): read one; evaluate it to up to 1000 doc ids. | M2 |
| `create`, `update` | Create or edit a search folder's rule. | Later (in a workspace: `/admin/folders/{id}/search-folders` and `/admin/search-folders/{id}`, §0.5) |
| `classes`, `subclasses` | Class and Subclass option lists for the rule editor. | Later (in a workspace: `/admin/folders/{id}/properties`' options, §0.5) |

### HealdanSettingsService

- Interface: `gui/frontend/client/healdan/services/HealdanSettingsService.java`; RPC URL `/frontend/healdansettings` (`@RemoteServiceRelativePath("healdansettings")`)
- Server impl: **HealdanSettingsServiceImpl**
- Methods: 6 (M2 1, Admin 5)
- Lands in: `/server` (M2); `/admin/settings`, `/admin/clients` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `labels` | Matter label singular/plural (any user). | M2 |
| `load`, `save` | Healdan settings (admin). | Admin |
| `listClients`, `saveClient`, `suggestClientNumber` | Client list/create/rename, next client number. | Admin (`/admin/clients`, §0.5) |

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
- Methods: 7 (Admin 3, none 4)
- Lands in: `/admin/dashlets` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadDashlets`, `saveUserDashlets` | The user's home dashlets and their layout. The User app has none: its Recent section is the start page (M6 spec §4). | none |
| `get`, `get` | Dashlet by id and by name (overloads). | none |
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
- Methods: 72 (M2 12, M3 2, M6 8, Later 28, Admin 8, none 14)
- Lands in: `/documents/{docNumber}`, `/documents/{docNumber}/checkout`, `/documents/{docNumber}/versions`, `/documents/{docNumber}/properties`, `POST /documents` (M2); `/me/favorites` (M3); `PATCH` and `DELETE /documents/{docNumber}`, `/documents/{docNumber}/message`, and `PATCH /me/favorites/{id}` for a favourite's name and `/trash` (M6, after M6); `/documents/{docNumber}/…` for notes, links, tickets and permissions, and a bookmark's description (Later); `/admin/documents` (Admin)

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
| `countDocuments` | Count documents in folders by status (confirm dialogs, folder stats). | Later |
| `getVersionsById` | Compare/fetch two versions' details (id1,id2). | Later |
| `promoteVersion`, `deleteVersions`, `replaceFile` | Make an old version current; delete versions; replace a version's file with an upload. | Later |
| `rename` | Rename document. Built as `PATCH /documents/{docNumber}` with `{ name }`. | M6 |
| `lock` | Lock documents with comment. | Later |
| `delete` | Move to trash. Built as `DELETE /documents/{docNumber}`. | M6 |
| `restore`, `deleteFromTrash`, `emptyTrash` | Restore, purge from trash, empty trash. Built as `POST /trash/{id}/restore`, `DELETE /trash/{id}` (to Removed, which an administrator can still restore) and `DELETE /trash` (after M6: the folders and Trash spec, 2026-11-17). | M6 |
| `destroyDocuments` | Hard delete. The User app never offers it: the nightly job destroys what was removed, and Destroy Documents stays the Classic Interface's, for administrators (folders and Trash spec §2.2). | Admin |
| `bulkUpdate` | Apply the same properties to many documents. | Later |
| `getAllowedPermissions` | Effective permissions of the user over a set of documents (enables/disables actions). The API returns it as `canEdit`. | M2 |
| `saveACL`, `applyParentFolderSecurity` | Edit a document's ACL; reset to the folder's. | Later |
| `makeImmutable` | Make documents immutable (read-only forever). | Later |
| `addBookmarks`, `deleteBookmarks` | Favorites (bookmarks). Healdan uses folder bookmarks as My Matters. Built as `POST` and `DELETE /me/favorites`. | M3 |
| `updateBookmark` | A bookmark's own name and description. Its name is built as `PATCH /me/favorites/{id}` with `{ name }` (after M6: the owner's dogfooding punch list, 2026-11-14); its description waits for its phase. | M6 |
| `linkDocuments`, `deleteLinks`, `updateLink`, `replaceAlias` | Document links; turn an alias into a real document. | Later |
| `saveNote`, `getNotes`, `getNote`, `saveNotes`, `deleteNotes` | Annotations/notes on a document or version. | Later |
| `createTicket`, `setTicketPassword`, `deleteTickets`, `enableTickets`, `disableTickets` | Download/view tickets (shareable links; served by /download-ticket). | Later |
| `getContentAsString` | Text content of a document (text editor). | Later |
| `convert` | Convert a file version to another format; creates a document. | Later |
| `sendAsEmail` | Email documents (links or attachments) from the server. | Later |
| `extractEmail`, `saveEmailAttachment` | Parse an .eml/.msg document for the viewer; save an attachment as a new document. Built as `GET /documents/{docNumber}/message` (with `/message/body` and `/message/parts/{id}`) and `POST /documents/{docNumber}/message/attachments/{id}/file`. | M6 |
| `markHistoryAsRead` | Mark a history/notification event as read. | Later |
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
- Methods: 27 (M2 2, M3 1, M6 6, Later 10, Admin 7, none 1)
- Lands in: `/folders/{id}` (M2); `POST /folders` (M3); `POST /documents/{docNumber}/move` (M6); `PATCH` and `DELETE /folders/{id}`, `POST /folders/{id}/move` and `/trash` (M6, after M6); `/folders/{id}/…` for merge, copy and permissions (Later); `/admin/folders` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getFolder` | Folder bean (path, permissions, template/attributes, counts) with optional path/doc count/subfolder count. The browse call. | M2 |
| `getAllowedPermissions` | Effective permissions over a set of folders (can I add/write here). | M2 |
| `create` | Create a folder. (Healdan restricts Client/Matter structure.) Built as `POST /folders`, New Folder in the helper's Save window. | M3 |
| `createAlias`, `save` | Alias, update properties. | Later (a workspace's folder's values: `/admin/folders/{id}/properties`, §0.5) |
| `rename` | Rename a folder. Built as `PATCH /folders/{id}` with `{ name }` (after M6: the folder rename spec, 2026-11-14). | M6 |
| `delete`, `restore`, `deleteFromTrash` | Delete to trash, restore, purge. Built as `DELETE /folders/{id}`, `POST /trash/{id}/restore` and `DELETE /trash/{id}` (after M6: the folders and Trash spec, 2026-11-17). | M6 |
| `paste` | Paste documents (copy/cut, links, notes, security options). A cut's move is built as `POST /documents/{docNumber}/move`; copying waits for its phase. | M6 |
| `move` | Move a folder. Built as `POST /folders/{id}/move` (after M6: the folders and Trash spec, 2026-11-17). | M6 |
| `merge`, `copyFolders`, `pasteAsAlias` | Merge/copy folders; paste documents as aliases. | Later |
| `saveACL`, `inheritACL` | Edit folder ACL (optionally to subfolders); inherit from another folder. | Later (a workspace's folder: `/admin/folders/{id}/access` and `/apply-access`, §0.5) |
| `applyTemplate` | Apply a folder template (structure) to a folder. | Later |
| `setFolderPagination` | Per-folder list paging preference. The User app loads every list 100 rows at a time (M6 spec §10.2). | none |
| `applyGridLayout` | Per-folder grid layout preference: choosing columns waits for its phase. | Later |
| `readImage` | Read an image posted to /upload for the folder (icon/image). | Later |
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
- Methods: 5 (M2 1, none 4)
- Lands in: `/search` (M2)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `search` | Run a search from GUISearchOptions: type = fulltext, parametric (name/metadata), folders, tags, etc. Returns GUIResult (hits, time, estimated count). | M2 |
| `save`, `load`, `delete`, `shareSearch` | Saved searches: store, load, delete, share with users/groups. Never in the User app: M1 turned saved searches off, and search folders cover them (M6 spec §4). | none |

### SettingService

- Interface: `gui/frontend/client/services/SettingService.java`; RPC URL `/frontend/setting` (`@RemoteServiceRelativePath("setting")`)
- Server impl: **SettingServiceImpl**
- Methods: 18 (M6 2, Admin 16)
- Lands in: `/server` (M6); `/admin/settings` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `loadGUISettings`, `loadSettingsByNames` | UI/config settings by name (e.g. upload limits); GUI-visible settings. The User app reads what it needs from M2's `GET /server`. | M6 |
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
- Lands in: `/admin/jobs` and `/admin/audit` (Admin, built in the Admin app's Phase 5, §0.5), `/admin/system` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getStatistics` | Server statistics for the system screen. | Admin (`GET /admin/dashboard/usage`, §0.5) |
| `loadTasks`, `getTaskByName`, `startTask`, `stopTask`, `enableTask`, `disableTask`, `saveTask`, `unscheduleJobs` | Scheduled tasks: list, run, stop, enable, schedule. | Admin (all but `unscheduleJobs` in `/admin/jobs`, §0.5; a task's delay and maximum duration stay the Classic Interface's) |
| `setGUILanguageStatus`, `getPlugins`, `confirmUpdate`, `restart` | Language toggle, installed plugins, restart. | Admin |
| `saveLogger`, `removeLogger` | Log4j logger levels. | Admin |
| `search` | Audit history search (users, events, dates, root folder). | Admin (`/admin/audit`, §0.5) |
| `searchApiCalls` | API call log search. | Admin |

### TagService

- Interface: `gui/frontend/client/services/TagService.java`; RPC URL `/frontend/tag` (`@RemoteServiceRelativePath("tag")`)
- Server impl: **TagServiceImpl**
- Methods: 6 (Admin 2, none 4)
- Lands in: `/admin/tags` (Admin)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `getTagCloud`, `getSettings` | Tags with counts; tag settings (mode, size limits). Never in the User app (M6 spec §4); the Classic Interface keeps them. | none |
| `addTag`, `removeTag` | Add/remove a tag to the user's "my tags" selection. | none |
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
- Methods: 7 (Later 7)
- Lands in: `/auth/…` for password reset, legal notices and forced password change (Later)

| Method(s) | Purpose | First needed by |
|---|---|---|
| `resetPassword` | Starts password recovery: emails a reset link (see /pswrecovery). | Later |
| `isSecretKeyRequired` | Does this user/device need a 2FA code at login. | Later |
| `getLegalsToConfirm`, `confirmLegal` | Legal documents the user must accept at login; record acceptance. | Later |
| `changePassword`, `generatePassword` | Forced password change at login; generate a compliant password for a username. | Later |
| `getUser` | Look up a user by username for the login/expired-password flow (unauthenticated). | Later |

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
| `/data/lockeddocs.xml` | LockedDocsDataServlet | userId | Documents locked/checked out (by a user). "My checked-out documents". **Broken upstream:** its SQL is missing a comma (capability map §6). | M2 | `/me/checkouts` (built on `DocumentDAO.findByLockUserAndStatus` instead); a person's, for an administrator: `/admin/users/{id}/checkouts` (§0.5) |
| `/data/versions.xml` | VersionsDataServlet | docId, archiveId | Version history of a document (or versions of an Import/Export archive). | M2 | `/documents/{docNumber}/versions` |
| `/data/documenthistory.xml` | DocumentHistoryDataServlet | docId (plus max, locale) | Audit events for one document (author, event, version, date, comment). Possible source for "recent documents". | Later | `/documents/{docNumber}/history`; an administrator's: `/admin/audit?docNumber=` (§0.5) |
| `/data/folderhistory.xml` | FolderHistoryDataServlet | id | Audit events for one folder. | Later | `/folders/{id}/history`; an administrator's, a matter's folders together: `/admin/audit?matterId=` (§0.5) |
| `/data/acl.xml` | AclDataServlet | id, type (document/folder/menu/template) | Access control list entries (groups, users, permission flags) of an object. | Later | `/documents/{docNumber}/permissions`, `/folders/{id}/permissions` |
| `/data/bookmarks.xml` | BookmarksDataServlet | (none)  | The user's bookmarks (documents and folders). Healdan stores My Matters as folder bookmarks. | M3 | `GET /me/favorites` (built; leaves out My Matters and what the user can't read) |
| `/data/notes.xml` | NotesDataServlet | docId, userId, page | Notes/annotations of a document or by a user. | Later | `/documents/{docNumber}/notes` |
| `/data/links.xml` | LinksDataServlet | docId, parent | Links of a document, or children of a link/metadata group. | Later | `/documents/{docNumber}/links` |
| `/data/documentaliases.xml` | DocumentAliasesDataServlet | docId | Aliases (shortcuts) pointing to a document. | Later | – (shortcuts are refused inside Stores) |
| `/data/folderaliases.xml` | FolderAliasesDataServlet | folderId | Aliases pointing to a folder. | Later | – (shortcuts are refused inside Stores) |
| `/data/tags.xml` | TagsDataServlet | docId, folderId, firstLetter, editing | Tags (cloud or per document/folder). | none | – (never in the User app, M6 spec §4) |
| `/data/tickets.xml` | TicketsDataServlet | docId (+ standard) | Download/view tickets of a document. | Later | `/documents/{docNumber}/tickets` |
| `/data/deleteddocs.xml` | DeletedDocsDataServlet | folderId, userId | Documents in the trash (Healdan checks the menu right). | M6 | `GET /trash?view=everyone` and `removed`, one row per delete (built after M6: the folders and Trash spec, 2026-11-17) |
| `/data/deletedfolders.xml` | DeletedFoldersDataServlet | parentId, userId | Folders in the trash. | M6 | `GET /trash?view=everyone` and `removed`, a folder one row with what it took (built after M6: the same) |
| `/data/garbage.xml` | GarbageDataServlet | (none) | Mixed latest 100 deleted docs/folders for the user (trash dashlet). | M6 | `GET /trash` (`view=mine`, paged; built after M6: the same) |
| `/data/users.xml` | UsersDataServlet | groupId, required, skipdisabled, impersonifiers | Users (optionally of a group), for pickers and admin grid. Needed by permission UIs (Later) and Walls (M7) too. | M6 | `GET /users?q=` (built in M2; the User app's Author and Typist pickers use it); the Admin app's Users list and a group's members: `/admin/users`, `/admin/groups/{id}/members` (§0.5) |
| `/data/groups.xml` | GroupsDataServlet | (none) | Groups (name, description, source). | Later | `/groups` (the Admin app's search and Groups list: `/admin/groups`, §0.5) |
| `/data/dashlets.xml` | DashletsDataServlet | (none) | Defined dashlets. | none | – (the User app has no dashlets, M6 spec §4) |
| `/data/languages.xml` | LanguagesDataServlet | gui (only GUI-enabled languages) | Active languages. | Later | `/server` |
| `/data/timezones.xml` | TimeZonesDataServlet | (none) | Time zones for the profile form. | Later | `/server` |
| `/data/devices.xml` | DevicesDataServlet | trustedonly | The user's (trusted) devices. | Later | `/auth/tokens` |
| `/data/apikeys.xml` | ApiKeysDataServlet | (none) | The user's API keys. | Later | `/auth/tokens` |
| `/data/ratings.xml` | RatingsDataServlet | docId | Ratings of a document. | none | – |
| `/data/contacts.xml` | ContactsDataServlet | userId | Address book contacts. | none | – |
| `/data/messages.xml` | MessagesDataServlet | (none) | The user's internal messages. | none | – |
| `/data/onlineusers.xml` | OnlineUsersDataServlet | (none) | Users with an open session. | none | – |
| `/data/dropbox.xml` | DropboxDataServlet (logicaldoc-dropbox module) | parent, etc. | Dropbox folder tree. Lives in the dropbox module, not in web/data. | none | – |
| `/data/attributesets.xml` | AttributeSetsDataServlet | type, withempty | Attribute sets. | Admin | `/admin/attributes` |
| `/data/stores.xml` | StoresDataServlet | types, parameters, empty | Configured file-storage backends (CE "Store"; unrelated to Healdan Store). | Admin | `/admin/storage` |
| `/data/menus.xml` | MenusDataServlet | (none) | Menu tree (admin screens). | Admin | `/admin/security` |
| `/data/sessions.xml (also /sessions/sessions.xml)` | SessionsDataServlet | node, status, kill, csv | Open sessions; can kill one or export CSV. | Admin | `/admin/sessions` |
| `/data/userhistory.xml` | UserHistoryDataServlet | id, event, comment | User audit events (logins etc.). | Admin | `/admin/audit` (`kind=sign-in`, `kind=user`, `userId`; §0.5) |
| `/data/events.xml` | EventsDataServlet | folder, workflow, importfolder, user, ocr, webservice, ai, all | Audit event-type names for filters. | Admin | `/admin/audit`'s rows carry their own words (`what`), and its filter is the kind (§0.5) |
| `/data/tasks.xml` | TasksDataServlet | (none) | Scheduled tasks and status. | Admin | `/admin/jobs` (§0.5) |
| `/data/jobs.xml` | JobsDataServlet | group, groupsonly | Quartz jobs. | Admin | `/admin/tasks` |
| `/data/indexingqueue.xml` | IndexingQueueDataServlet | (none) | Documents waiting to be indexed. | Admin | `/admin/search-engine` |
| `/data/parsers.xml` | ParsersDataServlet | (none) | Registered parsers (extension -> parser). | Admin | `/admin/search-engine` |
| `/data/formatconverters.xml` | FormatConvertersDataServlet | converter, in, out | Registered format converters. | Admin | `/admin/system` |
| `/data/conversionformats.xml` | ConversionFormatsDataServlet | fileName | Formats a file can be converted to (convert menu). | Later | `/documents/{docNumber}/conversions` |
| `/data/tokenfilters.xml` | TokenFiltersDataServlet | filter | Index token filters. | Admin | `/admin/search-engine` |
| `/data/extensionaliases.xml` | ExtensionAliasesDataServlet | (none) | Extension aliases for parsing. | Admin | `/admin/search-engine` |
| `/data/charsets.xml` | CharsetsDataServlet | (none) | Available character sets (import). | Admin | `/admin/search-engine` |
| `/data/sequences.xml` | SequencesDataServlet | prefix | Named sequences (counters). | Admin | `/admin/system` |
| `/data/aspects.xml` | AspectsDataServlet | (none) | Run-level aspects (features on/off). | Admin | `/admin/system` |
| `/data/environment.xml` | EnvironmentDataServlet | (none) | System/Java/DB environment values. | Admin | `/admin/system` |
| `/data/log.xml` | LogDataServlet | loggers | Log4j loggers and appenders. | Admin | the appenders: `/admin/logs` (§0.5); the loggers, `/admin/system` |
| `/data/updatehistory.xml` | UpdateHistoryDataServlet | (none) | Installed updates and patches. | Admin | `/admin/system` |

## 3. Other servlets and endpoints

| URL | Class | Params | What it does | First needed by | Lands in |
|---|---|---|---|---|---|
| `/upload` | UploadServlet | multipart POST (any field); sid from SessionFilter (param/header/cookie); GET lists pending | Stores posted files in the HTTP session map `uploads` keyed by original file name (UUID file on disk). Max `<tenant>.upload.maxsize` MB (default 10), `<tenant>.upload.disallow` extensions. Files are consumed by RPCs (addDocuments, checkin, replaceFile, readImage, saveAvatar...). Stateful and name-keyed: two uploads with the same name collide. | M2 | the `file` part of `POST /documents` and `POST /documents/{docNumber}/versions` |
| `/uploadresource` | DocumentResourceUpload | docId, versionId/version, suffix; POST multipart | Upload a resource file (e.g. thumbnail/preview/alt rendition) for a document version; needs write. | Later | `/documents/{docNumber}/renditions` |
| `/download, /download/*` | DownloadServlet | docId, versionId, version, fileVersion, suffix, fileName, downloadText, pluginId+resourcePath | Download a document's current/old file, a suffix rendition (thumb, conversion), the indexed text, or a plugin resource. Check-out needs this to get bytes. | M2 | `/documents/{docNumber}/content` |
| `/download-ticket` | TicketDownload | ticketId, suffix, password, behavior, control; Basic Authorization header | Public (security=none) download/preview via a ticket; checks expiry, counters, password. | Later | stays as it is (public ticket links) |
| `/download-attachment` | DownloadAttachmentServlet | docId, fileVersion, attachmentFileName | Download an attachment inside an email document. | M6 | `GET /documents/{docNumber}/message/parts/{id}` (built: a download, or with `inline=true` a PDF's or a picture's preview) |
| `/thumbnail` | ThumbnailServlet | docId, version, fileVersion, suffix | Image rendition (thumbnail / preview image); generated on demand. | M3 | `GET /documents/{docNumber}/thumbnail` (built: the current thumbnail, `404` until M1.5 has made it) |
| `/convertpdf/*` | ConvertPdf | docId, version, fileVersion | Convert (if needed) and stream a PDF for viewing. | M6 | `GET /documents/{docNumber}/preview` (built: `?version=`; `202` with `Retry-After` while M1.5 converts, `404 no-preview`; it never converts on the spot) |
| `/zip-export` | ExportZip | folderId, level (and doc ids); GET/POST | Zip a folder tree or selected docs for download. | Later | `/folders/{id}/zip` |
| `/avatar` | AvatarServlet | userId | User picture image. | Later | `/users/{id}/avatar` |
| `/legal` | LegalServlet | legal, user | Legal-document text/PDF to accept at login (unauthenticated). | Later | `/auth/legal` |
| `/pswrecovery` | PswRecovery | ticketId, userId, tenant | Completes password recovery from the emailed link. | Later | `/auth/password-reset` |
| `/dashletcontent` | com.logicaldoc.core.dashlet.DashletContent (core) | dashletId etc. | Renders a content dashlet. | none | – (the User app has no dashlets, M6 spec §4) |
| `/display, /display/*` | DisplayServlet | query string or /type/id | Redirects deep links to `frontend.jsp?...` (GUI deep links). Replaced by the new UI's own routing. | none | – (the new UI routes its own links) |
| `/stat` | StatChartServlet (stat package; abstract ChartServlet base) | objectId, tenantId, width, height, locale | Chart images of repository stats for the Admin screens. | Admin | `/admin/system` |
| `/webservicechart` | WebserviceChartServlet (webservice module) | as /stat | Chart of web-service calls. | Admin | `/admin/system` |
| `/log` | LogDownload | appender | Downloads a log file / appender output (menu right). | Admin | one log: `/admin/logs/{name}/lines` and `/file` (§0.5); `appender=all`, `/admin/system` |
| `/echo` | EchoServlet | any | Debug: logs the request when echo.enabled=true. | none | – |
| `/frontend/gwteventservice` | de.novanic.eventservice (GWT Event Service) | GWT-RPC long-poll | Third-party GWT server-push; legacy, not part of the Healdan code. | none | – |
| `/frontend/dropbox` | DropboxServiceImpl | GWT-RPC | See Dropbox section. | none | – |
| `/services/*` | WebserviceServlet (CXF) | REST (`/services/rest/*`); Healdan removes the SOAP endpoints ([spec](../superpowers/specs/2026-11-03-soap-removal-design.md)) | Existing REST API: out of scope, kept as is. | none | stays as it is |
| `/webdav/*, /service/cmis/*` | WebdavServlet, CmisServlet | WebDAV, CMIS | Existing interfaces: out of scope, kept as is. | none | stays as it is |
| `/j_spring_security_check (not a servlet)` | Spring Security form login | j_username, j_password, (2FA key)  | How the old GUI signs in (session cookie + ldoc-sid). Add-ins use /services/rest/auth instead. | M2 | `POST /auth/tokens` |
| `/wk-event (WebSocket)` | websockets.EventEndpoint | (none; server push only) | See WebSocket section. | Later | `/events` (Server-Sent Events, proposed; push updates are a later phase, M6 spec §16) |

## 4. WebSocket `/wk-event`

**Fixed since this inventory was taken:** [Healdan-Project/healdan#58](https://github.com/Healdan-Project/healdan/pull/58) (2026-10-23) makes `/wk-event` send each peer only what its user may read. The broadcast described below is the state before that fix; the serialization is unchanged.

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
