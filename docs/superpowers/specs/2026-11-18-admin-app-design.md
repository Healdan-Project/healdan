# The Admin App: design

- **Date:** 2026-11-18
- **Status:** designed with the owner on 2026-11-18, question by question and through mockups in the browser ([`2026-11-18-admin-app-mockups/`](2026-11-18-admin-app-mockups/README.md)). The owner approved this written spec the same day. Implementation plan: [`docs/superpowers/plans/2026-11-18-admin-app.md`](../plans/2026-11-18-admin-app.md), approved the same day, for subagent-driven building. Not built: the work waits for the PostgreSQL work's PR A (§9.9, §14).
- **Roadmap:** the backlog row "Admin app, then retiring the old GWT UI" in [`docs/ROADMAP.md`](../../ROADMAP.md).
- **Inputs:**
  - the roadmap's glossary and its M6 decisions "Three separate units" and "Hosting";
  - the [M6 spec](2026-11-10-m6-user-app-design.md), §7 to §10 and §16;
  - the [M1 spec](2026-10-17-m1-move-in-design.md) §5 and §7, the [M2 spec](2026-10-23-m2-api-foundation-design.md), the [M4 spec](2026-11-01-m4-oidc-sign-in-design.md), the [S3 storage spec](2026-11-01-s3-storage-backends-design.md) and the [folders and Trash spec](2026-11-16-folders-and-trash-design.md);
  - the [PostgreSQL spec](2026-11-18-postgresql-design.md) §6.5, §7.3 and §7.4, and its [plan](../plans/2026-11-18-postgresql.md)'s Global Constraints;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md) and [`docs/reference/healdan-api-inventory.md`](../../reference/healdan-api-inventory.md);
  - a survey of every screen in the Classic Interface's Administration area, of the administrator-only actions elsewhere in it, and of the server and web code a second app touches, at `252dd8e3a`. Nothing was run for the survey, and line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace*, *matter* and *template* mean Healdan's things (roadmap glossary). A *backend* is a storage backend. "CE" is LogicalDOC Community Edition. "The Classic Interface" is CE's GWT web page. "The User app" is M6's app at `/app/`. "The app" here is the Admin app. "An administrator" is a member of the group named `admin`.

## 1. Intent

Give whoever runs a Healdan server one clean place to run it: clients, matters and their workspaces, storage, people, the server's health and usage, its logs, errors and audit records, and reindexing (owner). It's the second of the new UI's three units, after User and before or alongside Walls, built on the User app's foundations. It's also one of the two things the Classic Interface waits for before it can retire. The other is the User app's later phases (M6 §16).

The aim isn't to copy CE's Administration menu. A survey found that most of that menu never shows in Healdan, that the core work of running a firm happens elsewhere (matters and Stores in the folder tree, clients in Healdan Settings), and that much of what the owner wants, such as a dashboard, grouped errors and a single audit trail, doesn't exist anywhere yet.

## 2. Scope

### 2.1 Goals (done when)

Each phase of §14 has its own "done when". The whole first version is done when:

1. An administrator reaches the app from the User app's person menu, and everyone else is refused by the server, the app's code included.
2. The owner runs their firm for a week from the app, going to the Classic Interface only for the screens §11 leaves there.
3. Every change made through the app is recorded with who, when, and the old and new values, and nothing secret is recorded.
4. An error raised anywhere in the server shows on the Dashboard and the Errors screen, and survives a restart.
5. A matter of any size can be reindexed from the app or from a right-click in the User app, and shows its progress to the end, while search keeps working.
6. No upstream file is edited.

### 2.2 Non-goals

- **Roles other than administrator** (owner). No Matter Administrator or per-area roles in this version. §5 leaves room for them.
- **Closing, archiving or merging matters** (owner: firms handle this in different ways, so it gets careful thought of its own). It goes to the roadmap's backlog.
- **Deleting people** (call). People are disabled. CE's delete can't be undone from any screen.
- **Deleting or merging clients, and renumbering clients or matters.** None exists today.
- **The screens §11 marks Stage 2,** which stay in the Classic Interface until their phase: Sign-In Providers, Healdan Settings, Outgoing Email, security settings, the property template and the older reports.
- **Retiring the Classic Interface.** That needs Stage 2 and the User app's later phases.
- **Walls** (M7), **bulk import** and **Declare as Record.**
- **Phones.** Designed for 1,280 pixels and wider; the left pane folds below 1,024, as the User app's does.
- **Languages other than US English,** as in the User app.

### 2.3 Anti-goals

- **No reliance on the app for any rule.** The server checks every request (roadmap principle 4).
- **No admin code for anyone else.** A person who isn't an administrator can't download the app's files (roadmap: "An average user never loads the Admin or Walls code").
- **No secret in a response, a log line or the audit trail:** S3 secrets, provider client secrets, passwords and recovery keys, but for the one deliberate Show Recovery Key and a temporary password shown once.
- **No long list as a drop-down** (owner, §7.2).
- **No upstream edit** (§15.1).
- **No port of a screen that doesn't work.** Screens that are dead or partly fake are dropped or replaced (§11).

## 3. What exists

- **The API has almost nothing for this.** Of `openapi.yaml`'s 47 paths, `GET /v1/stores` and `GET /v1/stores/{id}/matters` are admin-only lists, and `GET /v1/users` is a people picker. Administrators already get passes on a matter's top-level folders, `/v1/trash` (Everyone's and Removed), restoring a matter's own folder and releasing anyone's check-out.
- **The Administration menu shows little.** Of its 33 buttons in System, Access Control and Settings, 13 show under Docker, and one of those, Updates and Patches, is dead. Document Metadata shows one button, Templates. Reports shows six. Import and Export and Artificial Intelligence don't show. Healdan's own section adds Healdan Settings, with the client list, Sign-In and Storage.
- **The folder tree holds the core work.** New workspace (a Store), New Matter, Edit Matter, everything in the Templates Store, a matter's top-level folders, moving or deleting a matter's own folder or a Store, and folder permissions.
- **Traps in today's setup.** No workspace template ships, so a fresh install can't make a matter until an administrator builds one from folders. A new Store has no permissions, so New Matter can refuse it (rule P2: every group that can read a matter must be able to read its Store). Nothing checks a template's permissions while it's edited, so a bad one fails only at New Matter.
- **Clients and matters have no end of life.** A client can't be deleted. Deleting a matter's folder keeps its row and its number for good. Neither can be closed.
- **Reindexing is slow.** CE's Indexer takes 500 documents every 30 minutes, about 24,000 a day. A document that fails stays marked as waiting, so failed and waiting look alike.
- **Audit is partial.** CE records document, folder and user events in three history tables, which nothing prunes. Destroying a document deletes its rows. Changes to Healdan Settings, clients, groups, sign-in providers, storage backends, scheduled tasks and logger levels leave no record. The API's preview writes no event, so reading a document in the User app leaves no trace (§8.8).
- **Healdan's own background workers report nothing** an administrator can read.

## 4. Approaches

**Chosen: a second app in the existing web workspace, with its API inside `healdan-api`** (call, heard by the owner). `apps/admin` sits beside `apps/user` in `healdan-web/web`, shares `packages/ui` and `packages/api`, and is served at `/admin/` by a servlet in `healdan-web`. Its server calls are at `/api/v1/admin/*`, in `healdan-api`, and call services in `healdan-core`. Storage is reached through an interface in `healdan-core` that `healdan-storage` implements. Those modules are already in the reactor and the WAR, so no upstream file changes.

Rejected:

- **A new server module, `healdan-admin`.** Tidier, and it could depend on `healdan-storage` directly, but it needs a line in the root POM and in `logicaldoc-webapp/pom.xml`, against the aim of no upstream edit, and its routes would need a route-coverage test of their own.
- **`healdan-storage` contributing API resources.** The server would find them, but `healdan-api`'s `RouteCoverageTest` wouldn't, and `ApiTestCase` isn't published to other modules.
- **Calling the Classic Interface's GWT services from React.** It ties the new app to the code being retired, and those services live in `logicaldoc-webapp`, which `healdan-api` can't see.
- **An admin section inside the User app.** The roadmap decided on separate units.

## 5. Who Can Use It (owner: administrators only)

- **Only members of the `admin` group,** on every page, file and API call. Other groups given Administration screens through the Classic Interface's menu permissions keep using the Classic Interface. They get nothing here (§11's last section says what that means for retirement).
- **The server checks each area separately from day one** (call). Every admin route names its area: `MATTERS`, `STORES`, `PEOPLE`, `STORAGE`, `MONITORING`, `SEARCH_INDEX` or `DASHBOARD`. `AdminAccess.allowed(user, area)` is `AccessChecks.isAdmin(user)` for all of them today. A later Matter Administrator changes that one method and the rules behind it.
- **Being an administrator still means reading every document.** The app says so wherever someone is added to `admin`, and the docs keep advising a separate everyday account.

## 6. Hosting (call)

### 6.1 The servlet

- `AdminAppServlet`, `@WebServlet("/admin/*")`, in `healdan-web`'s `com.logicaldoc.healdan.app`, serves `healdan/app/admin/` from the jar. CE's security chain already lets `/admin/**` through, as it does `/app/**`, and nothing else claims the path.
- What it shares with `AppServlet` moves into an abstract `BuiltAppServlet`: the method check, security headers, path parsing, media types, hashed files and ETags. `AppServlet`'s behaviour and tests don't change.
- **The check comes before any look at the files,** for every path, so a person who isn't an administrator learns nothing and gets no bytes:

| Request | Signed out | Signed in, not an administrator | Administrator |
|---|---|---|---|
| A page | Remember the return path, `302` to `login.jsp` | `403`, the no-access page, and the return path forgotten | `200 index.html` |
| `assets/` or any file of the build | `401`, empty | `403`, the same answer whether or not the file exists | `200`, or `404` if missing |

- **An administrator** is `WebSessionRoute.session(request)` → `WebSessionRoute.user(...)` → `isAdmin()`, asked at each request. So someone taken out of `admin` loses the app at their next page load.
- **The no-access page** is `403` with the User app's own `index.html`, which draws a small panel when its address is under `/admin`, before any API call: "You don't have access to Healdan Admin", with a link to `/app/`.
- **Caching:** hashed files are `private, max-age=31536000, immutable`, so no proxy keeps them for someone else. `index.html` and every refusal are `no-store`, set explicitly, because CE's `CacheControlFilter` would otherwise leave `max-age=600` on a refused `.js`.
- **Content-Security-Policy:** the User app's, without the PDF.js worker and the email frame: `worker-src 'none'; frame-src 'none'`. A test checks its text.
- Answers use `setStatus`, not `sendError`, so Tomcat's error page doesn't replace them.

### 6.2 Signing in

- `ReturnPath` (fork-owned) also accepts `/admin/…` under its character rule. A refused or bare `/admin…` falls back to `/admin/`, anything else to `/app/`. `LoginLandingFilter`, `Landing`, `SignInResource` and `login.jsp` don't change. A plain sign-in still lands where `ui.start` says, administrators included.
- The session, its timeout and Sign Out are the User app's (M6 §8): the same CE web session, `DELETE /v1/session`, and a secure address, with the same page at any other.

### 6.3 Addresses

`/admin/` is the Dashboard. Then `/admin/clients`, `/admin/clients/{id}`, `/admin/matters`, `/admin/matters/{id}`, `/admin/templates`, `/admin/templates/{id}`, `/admin/stores`, `/admin/stores/{id}`, `/admin/backends`, `/admin/backends/{id}`, `/admin/users`, `/admin/users/{id}`, `/admin/groups`, `/admin/groups/{id}`, `/admin/errors`, `/admin/server-log`, `/admin/audit`, `/admin/jobs` and `/admin/search-index`.

CE's `UrlRewriteFilter` isn't anchored at the start of the path, and `login.jsp` drops some return paths. So no address uses a segment `login`, `setup`, `frontend`, `license`, `sessions`, `mobile`, `view`, `webform`, `sc` or `ace`, or one starting `info`, and none ends in a slash but `/admin/` itself. Query values go through `URLSearchParams`, and unknown parameters are ignored. A test enumerates the routes against this list.

### 6.4 Between the apps

- **User app → Admin:** an **Admin** item in the person menu, for administrators, before **Classic Interface** (owner). On a matter's right-click menu, **Manage in Admin** (call, §13).
- **Admin → User app:** **Open Healdan** in the top bar, and **Open in Healdan** on a matter's page.
- **Admin → Classic Interface:** a **Classic Interface** entry at the foot of the left pane, for what isn't here yet.

## 7. The Frame (owner: A, the left pane)

### 7.1 Layout

- **Top bar:** Healdan's mark, **Healdan** and an **Admin** tag; **Find a client, matter or person**; **Open Healdan**, Help and the person's menu.
- **Left pane,** in groups, each screen one click away, with a count where something needs attention:
  - **Dashboard**
  - **Firm:** Clients, Matters, Workspace Templates
  - **People:** Users, Groups
  - **Storage:** Stores, Backends
  - **Search:** Search Index
  - **Monitoring:** Errors, Server Log, Audit Trail, Background Jobs
  - **Classic Interface**
- **A screen appears in the pane only once its phase has shipped** (call). Sign-In Providers and Settings, drawn in the mockups, join in Stage 2. Until then the Classic Interface entry covers them.
- **The look** is the User app's: its colours, type, icons, light and dark following the system, WCAG 2.2 AA, 34-pixel rows and the keyboard behaviour of its components.

### 7.2 Rules for every screen

- **Anything that can grow long is a search** (owner): matters, clients and people, in forms and in the filters above lists. Short lists, Stores and workspace templates, stay plain menus.
- **A list leads to a page** (owner, from Matters: B). A list has its filters and New button. Selecting a row opens that thing's own page, with its actions at the top and tabs below.
- **Problems are said before the button, not after it.** A form checks what the server would refuse and says how to fix it (§8.3).
- **Every dangerous action asks first and names what it affects.**
- **Data refreshes** when the window comes back and after your own changes, as in the User app. The Dashboard and running jobs also ask again every 30 seconds while visible.

## 8. The Screens

Each screen is drawn in the mockups, which the owner reviewed. Where they differ, this section wins.

### 8.1 Dashboard (`01-frame-and-dashboard.html`)

- **Needs Attention** comes first (owner), listing what an administrator should act on, each with a link to where it's fixed: stuck files; a backend that can't be reached; documents that couldn't be indexed; unresolved errors in the last 24 hours; previews that gave up; a background job that failed or is overdue; a disk above 85% full (a setting for the builder to name). With nothing to report, the box says so in one line.
- **Server Health,** ten tiles, each with a status and a line of detail, each opening the screen behind it:

| Tile | Shows | From |
|---|---|---|
| Server | Time up, Healdan's version | the JVM, `SystemInfo` |
| Database | Product, version and size | a query per database |
| Disk | Used of total for the repository's volume | `File` on `store.1.dir`, and on the index and log folders where they differ |
| Memory | Heap used of its maximum | `Runtime` |
| Sign-In | Each enabled provider answering | `ProviderClient`, cached for a few minutes |
| Storage Backends | Reachable count, stuck files | the storage port (§9.7) |
| Search Index | Waiting and failed | §9.6 |
| Previews | Waiting and given up | `hd_preview_job`, with a new count method |
| Background Jobs | On schedule, or which aren't | §9.5 |
| Outgoing Email | Set up, and the last send's result | `OutgoingEmail` and the captured errors |

- **Usage:** totals for documents, storage, matters, clients and people, each with its recent change; **Documents Filed, Last 30 Days**, as a plain SVG bar chart with a text alternative; **Storage by Store**; **Busiest Matters This Week**; who's signed in now, as a count. These come from one in-memory summary that a background task rebuilds at most every 15 minutes (call), so opening the Dashboard costs almost nothing. The page says when each was last worked out.
- A part that fails to load shows its own error; the rest of the page still works.
- Until a tile's or row's screen has shipped, it links to the Classic Interface (call).

### 8.2 Clients

A list (Number, Name, Matters, Documents, Last Activity) with **New Client**, then a page with the client's details and its matters, and **New Matter** already set to that client. The number is suggested as the highest plus one and can't change afterwards. Only the name can be edited. Nothing deletes a client.

### 8.3 Matters (`02-matters.html`; owner: B)

**The list:** Matter, Name, Client, Store, Owner, Template, Documents, Size and Last Activity, sorted by any column, with a text filter and searches for Client and Owner, and a Store menu. Documents, Size and Last Activity come from the summary of §8.1. This is the one place that lists every matter: the roadmap keeps Store browsing out of the User app, and left it for the admin side.

**New Matter** is a dialog with Client (a search, with **New Client…**), Number (the next free one, editable), Store (a menu, with **New Store…**), Name, Description, Owner (a search) and Template. It shows what the matter will contain. It runs the server's checks as the form changes (`POST /matters/check`), so a Store a template's groups can't read is reported before **Create Matter**, with **Let {group} Read the Store** to fix it there.

**A matter's page** has the title, the client, Store, owner, template and totals, and **Edit**, **Move to Another Store**, **Open in Healdan**, **Reindex** and **Delete**. Tabs:

- **Overview:** the description, the facts, what's stuck or failing in the matter.
- **Workspace** (§8.4).
- **Activity:** the Audit Trail for this matter.

**Edit** changes the name, description and owner only, as `MatterService.update` does. **Move to Another Store** says the files follow in the background, and the rules' refusals show in the dialog. **Delete** sends the matter's folder to the Trash after naming what it holds, and says the number stays taken. A matter is restored from the User app's Trash, as today, by an administrator.

### 8.4 The Workspace Editor

The same component serves a matter's Workspace tab and a workspace template's page (call).

- **Left: the folder tree,** with search folders, and a lock on each folder that has permissions of its own. **New Folder**, rename, move and delete work on every folder here, the matter's top level included.
- **Right: the selected folder.** **Who Can Use This Folder**, with **Add Person or Group** (a search), each row's access, Save, and **Show Every Permission** for the full grid. A folder either follows its parent (**Set Own Permissions** to change that) or has its own (**Follow Parent**). A second section holds the folder's property values, such as a Class it sets, and a search folder shows its rule for editing.
- **Access levels** (owner: as in iManage). The match is exact, and anything else shows **Custom**, which Show Every Permission can edit:

| Level | Permissions |
|---|---|
| **Read Only** | Read, Preview, Download, Print, Email |
| **Read/Write** | Read Only's, plus Write, Add, Rename, Move, Delete |
| **Full Access** | all 25, which is what a matter's owner gets |

- **Rules, from the server:** a folder narrows its parent and never widens it (P1); every group that can read a matter can read its Store (P2); the owner's row stays. The `guest` group and read-only people can't be given more than Read Only, which CE doesn't check when a folder's permissions are saved, so the admin route refuses it. **Apply to Subfolders** is offered where `checkApplyToTree` allows.
- **In a template,** nothing checks permissions as they're edited today. So the page lists what New Matter would refuse, as warnings, using `AclMath` and `PermissionRules`, and saving still works.

### 8.5 Workspace Templates

A list (Name, Folders, Matters Made From It) with **New Template** (empty, or a copy of another), then the Workspace Editor. Changing a template doesn't change matters already made from it, and the page says so. A fresh install's empty list explains that a template is needed before the first matter.

### 8.6 Stores and Backends (`04-index-users-storage.html`)

- **Stores:** Store, Backend, Matters, Documents, Size, Who Can Read and the state of its files, then a page. **New Store** asks for the name, **who can see it** and the backend, so a matter can be made in it at once. A Store's access uses one level, **Can See** (Read), the only permission that matters on a Store. Default, Templates and the root can't be renamed or deleted here. A Store's delete asks first and says it takes every matter inside. Restoring one is done from its page's deleted list.
- **Backends:** everything the Storage tab does today, with the same rules: add (S3 or a local folder), test, edit, remove, recovery keys (show, confirm, enter, history), switching a Store's backend with its count first, stuck files with Retry, and the holding period, read cache and parallel transfers.

### 8.7 Users and Groups (`04-index-users-storage.html`)

- **Users:** Name, Username, Groups, Signs In With, Status, Last Sign-In and Devices, with a filter and **New User**.
- **A person's page:** details, groups, signed-in devices (with **Revoke**), what they hold (check-outs, matters they own) and an Activity tab. **Reset Password** (password accounts only: a temporary one, emailed or shown once, which must be changed at sign-in), **Sign Out Everywhere**, **Clear Sign-In Block** when blocked, and **Disable** or **Enable**.
- **New User makes a password account** (owner: keep it simple). While a sign-in provider is on, the form says that people who sign in through it don't need adding: their account appears at their first sign-in, with the groups the provider's map gives them, and can be adjusted from there. Adding someone ahead of a first provider sign-in isn't offered, as that sign-in would make them a second account.
- **Disable** revokes their device tokens and closes their open sessions.
- **The server refuses:** changing a system account; taking `admin` out of `admin`; disabling `admin` or yourself; leaving no enabled administrator. A provider account can't get a password. Unlinking stays on the Classic Interface's Sign-In tab until Stage 2, and linking happens only at a person's first sign-in, as it does today.
- **Groups:** a list and a page with members and description. `admin`, `guest`, `publisher` and Everyone can't be renamed or deleted, and personal groups aren't listed. A group a sign-in provider maps says that membership changes are overwritten at that person's next sign-in. CE's "inherit from group", which replaces a group's permissions, isn't offered.
- Adding someone to `admin` asks first and says they'll be able to read every document.

### 8.8 Monitoring (`03-monitoring.html`)

**Errors (owner: A, grouped and kept).** One row per kind of fault: What Happened, Area, Times, First Seen, Last Seen, for the last hour, 24 hours, 7 days or 30 days, with a text filter and Area and Unresolved filters. Selecting a row opens its details in a region below the list, as the list's rows have a fixed height: the latest message, the stack, the documents named, **Mark Resolved**, **Show in Server Log** and **Copy Details**. A resolved fault that happens again comes back as unresolved. §9.4 says how they're caught.

**Server Log.** The main log or a task's log; Everything, Warnings and Errors, or Errors; a text search; **following live**, with **Pause**; **Download**. It reads the files LogicalDOC writes, so it holds what they hold, about 30 MB of the main log.

**Audit Trail.** One list, newest first: Time, Who, Kind, What, Detail, From. Kinds are Sign-In, Document, Folder, User and Administration. Filters are the kind, a person, a matter, dates, and a doc #, name or address. **Export CSV** exports what the filters select. Administration rows show old and new values. It's built from CE's three history tables, `hd_storage_event` and the new administration record (§9.3). Rows are kept for good, as nothing prunes these tables. A destroyed document's CE rows go with it, while the Trash's record of it stays.

**What counts as looking at a document (owner).** Actually reading a document is recorded. Checking that it's the right one isn't.

| Recorded | How |
|---|---|
| Quick Look and the reading pane, where you can turn the pages | New: `event.viewed`, when `GET /v1/documents/{n}/preview` serves the file |
| Reading a filed email, or previewing one of its attachments | New: `event.viewed` on the email, when `/message/body` or `/message/parts/{id}?inline=true` is served |
| Downloading | As today: `event.downloaded` |
| Opening in Word, Excel, LibreOffice or CoffeePDF | As today: the Helper's check-out and its download of the file |
| Printing | The User app has no Print command, so printing follows a recorded preview or download. One added later records its own event |
| **Not recorded:** the thumbnail, a document's properties, its versions, and its row in a list | |

A view is recorded once per person, per document, per half hour (owner), so arrowing through a list twice or refreshing doesn't flood the trail. The check is in memory, as Healdan runs one node, so a restart can record one more. A preview still being made (`202`) records nothing until it's served, and a failure to record never fails the read. A server's history grows faster for this, and nothing trims it.

**Background Jobs.** Every job in one list: Healdan's workers and CE's six tasks, with Last Run, Result, Next Run and Enabled, and **Run Now**, **Stop**, **Enable** and **Disable** where the job allows. CE's tasks also have their schedule. See §9.5.

### 8.9 Search Index and Reindex (`04-index-users-storage.html`, `05-reindex-search.html`)

- **Search Index:** Indexed, Waiting, Couldn't Be Indexed, the index's health, the Indexer's last and next run, **Run Indexer Now** and **Reindex…**. Running reindexes show with progress. Tabs: **Couldn't Be Indexed** (the document, its matter, the reason, **Try Again** and **Skip**, which stops it being retried), **Waiting** and **History**.
- **Reindex (owner)** is one dialog choosing **Documents** (type or paste doc #s, each shown as matched or not found), **Folder** (find the matter, then pick from its folders), **Matter** (a search that starts from Recent Matters and matches number, name or client), **Store** (a plain list) or **Everything**. It shows the document count and an estimate.
- **Reindex runs straight through** and **search keeps working:** each document's entry is replaced as it's done. A folder has no index entry of its own, so reindexing one means the documents in it.
- **It always reaches an end.** Each document is tried once. A finished job says how many were done and how many couldn't be indexed, and those join the Couldn't Be Indexed tab.
- **Cancel** stops it. Documents it hadn't reached keep the entries they have.
- **Empty the index first** appears only under Everything, as a tick box with a warning, for a damaged index.
- Reindex on a matter's page opens the dialog with that matter chosen.

## 9. The Server

### 9.1 The API's frame (call)

- **Resources** are `@Component`s with `@Path("/v1/admin/…")` in `com.logicaldoc.healdan.api.admin`, which `ApiServer` already finds. Package-private helpers they need are made public; all are Healdan's.
- **`@AdminRoute(area)`** marks each resource. A new provider, `AdminFilter`, runs after `TokenFilter`:
  - a device token is `403 web-session-only`, so a stolen token can't administer anything;
  - no session is `401`, as today;
  - anyone not allowed the area is `403 admins-only`.
- **A test fails** if any route under `/v1/admin` lacks the annotation, or if `AdminFilter` isn't what refuses it.
- **Lists** use `Paging` and `Page`, but for the Audit Trail and logs, which page by cursor (§9.3).
- **Refusals:** a `HealdanRuleException` is `409 refused` with its sentence, as now. Forms that need a field-level message use `422 validation`.
- **Multi-step writes are `@Transactional` services in `healdan-core`,** never a resource making several DAO calls: `StoreService` (create with access), `FolderAccessService` (the access editor), `UserAdministration`, `GroupAdministration`, and the existing `MatterService` and `ClientService`, whose `create` and `rename` become transactional so their record can roll back with them.

### 9.2 The contract (call)

- **A second file,** `healdan-api/src/main/resources/healdan/api/openapi-admin.yaml`, holds every `/v1/admin` route. The public `/api/v1/openapi.yaml` doesn't list them, the Healdan Helper's CI isn't triggered by them, and the User app's generated types don't grow.
- `Contract` picks the validator by the `/api/v1/admin/` prefix; `RouteCoverageTest` compares the routes with both files; `OpenApiFileTest` covers both. `code-changed.sh` counts the new file as `web`, not `clients`.
- `$ref` can't cross the two files, so `Problem`, `Page` and a few others are repeated. A test checks the copies are the same.
- `npm run generate` writes a second types file, exported from `@healdan/api` under its own entry.

### 9.3 The administration record (call)

- **`hd_admin_event`** holds one row per administrative change: when, who (id and name), from where, the area, the action, what it acted on (kind, id, name), and a JSON detail of changed fields with old and new values.
- **Secrets never appear:** a changed secret is recorded as changed, with no value. Showing a recovery key is recorded, as it is today in `hd_storage_event`.
- **What's covered:** every change made through the app. `ClientService` and `MatterService` write the record themselves, so the Classic Interface's client and matter changes are recorded too. Its other screens, Healdan Settings, Sign-In, Storage and CE's own, stay as unrecorded as they are today, until each moves here.
- **A change that's one database transaction is recorded inside it,** so the two stand or fall together.
- **A change that isn't,** such as adding a backend (network and file steps) or a job's schedule (`context.properties`), is recorded straight after it succeeds. If that record fails, the change stands, and the failure is logged at error level, so it reaches the Errors screen.
- **Reading:** CE's history tables have no index on the date, and Healdan doesn't add indexes to `ld_` tables. The feed reads each table newest-first by `ld_id` with a cursor, merges by date in Java, and returns `nextCursor`, with no offset and no total. A date range is turned into an id range, for example by a binary search on the primary key.

### 9.4 Catching errors (owner: grouped and kept; call on how)

- **A Log4j2 appender** is attached in code at `HealdanStartup`, for level `ERROR` and above, with no edit of `log.xml`. It's attached to the root logger **and to every logger with `additivity="false"`** (36 of them, the Indexer's among them), or their errors would be missed.
- **It never logs and never touches the database itself.** It puts a copy on a bounded in-memory queue, counting what it drops. One writer thread drains the queue, so a database outage or a storm of errors can't stall a request.
- **If the logging configuration is reloaded,** a `PROPERTY_CONFIG` listener attaches a new instance, only if the root logger lacks one. (Re-attaching unconditionally loops, and an old instance can't be reused.)
- **Grouping:** a fingerprint of the exception's class, the logging class and line, and the message with its numbers and quoted names taken out. **`hd_error`** has a row per fingerprint: level, area (from the logger's package), latest message, stack and documents named, each capped in size, the total, first and last seen, and whether and by whom it's resolved. **`hd_error_hour`** has a count per fingerprint and hour, which gives the screen's time windows and is pruned after 31 days. Resolved faults unseen for 90 days are deleted.
- **One writer means no concurrent inserts,** so it updates first and inserts when no row changed, with no statement expected to fail.
- Log messages already follow the rule that subjects, addresses and attachment names stay out of info and above (M5).

### 9.5 Job status (call)

- **`JobStatus`** in `healdan-core` keeps each job's last start, end, result, error and next run, in memory, and a `HealdanJob` registers its name and what it allows. The preview worker, email index worker and Trash destroyer report directly. `healdan-storage`'s mover, retests, purge, alert and nightly check report through the same interface.
- **CE's tasks** are read from `TaskManager` and `TaskScheduling`. Their last error isn't exposed (`Task.lastRunError` has no getter), so it's taken from the captured errors for that task's logger.
- Status restarts with the server. Last-run times worth keeping across a restart go in `hd_setting`, as the email worker's already do.

### 9.6 Reindexing (owner: straight through; call on how)

- **`ReindexWorker` indexes each document itself** through `DocumentManager.index`, the call CE's Indexer makes, on as many threads as `threadpool.IndexerTask.max`. It doesn't mark documents or relaunch `IndexerTask`. So nothing waits for the Indexer's 500-per-30-minutes pace, each document is tried exactly once per job, and an unreadable file can't hold the job up or send it round again.
- **What a job covers** is its scope's live documents, taken in `ld_id` order from a saved position. Aliases, documents marked Skip and documents in a running CE transaction are left out, and the answer says how many were skipped. Folder trees are walked by parent through `StoredRows`, never through `ld_path`, which can be stale. A document filed during the job is the Indexer's, as usual.
- **`hd_reindex_job`** keeps the scope, the name shown, who started it, its times, its total, done and failed counts, its saved position and its state: `RUNNING`, `DONE` or `CANCELLED`. A job runs one at a time, and others wait their turn. If the index can't be written, the worker waits and tries again, and the screen says so.
- **Nothing is removed from the index,** unless **Empty the index first** was ticked, which also marks every document to index, so one job's failure doesn't leave them out.
- **A restart** resumes a running job from its saved position.
- **Failed versus waiting:** a document has failed when `ld_indexed = 0` and its latest `event.indexed` or `event.indexed.error` row is the error, with the reason in its comment. `DocumentManager.index` writes those rows. CE's Indexer goes on retrying such a document on its schedule, as it does today. **Try Again** indexes it now. **Skip** sets it to not indexable, so the Indexer stops.

### 9.7 Storage (call)

`StorageAdministration`, an interface in `healdan-core` with plain records, is implemented in `healdan-storage` by an adapter over `StorageAdmin`, found by type as `HealdanStartListener` is. `StorageAdmin` and the Classic Interface's Storage tab don't change. `healdan-api`'s tests use a fake. With no implementation the routes answer `503`. On a server not running `HealdanStorage`, the screens say so, as the tab does today. The port's reading side ships in Phase 1, for the Dashboard, and its writing side in Phase 3.

### 9.8 New tables

| Table | Phase | For |
|---|---|---|
| `hd_admin_event` | 1 | §9.3 |
| `hd_error`, `hd_error_hour` | 1 | §9.4 |
| `hd_reindex_job` | 6 | §9.6 |

No `ld_` table changes (roadmap §6).

### 9.9 PostgreSQL (from the PostgreSQL spec and plan)

**The app's server code is built after that work's PR A merges,** so its tests run on PostgreSQL. CE's admin services were barely exercised in that work's trial (its spec §4.1), so every service the app calls gets a test here that runs on PostgreSQL.

- **Migrations:** a new script takes the next free version, `V12` today, in **every folder that then exists**: `mariadb/` and `postgresql/` while the switch (PR B) hasn't merged, `postgresql/` alone afterwards. PR B's planned `V12` then moves to the next number. The move's expected version, its fixture and `PostgresMigrationsTest`'s count follow. Tests run only the `postgresql/` script, so the smoke test is what proves the `mariadb/` one.
- **Until PR B merges, SQL runs on both databases:** no `on conflict` and nothing else PostgreSQL-only, nothing MariaDB-only, and no SQL `nulls first` or `nulls last` (use `ListingSql.nullsLast`).
- **Always:**
  - no statement is expected to fail inside a transaction, and one that may fail without failing its caller runs in `Savepoints.run`;
  - a flag is an `int` column and field, never `boolean`;
  - SQL given to CE's DAO helpers never uses `::`;
  - every `order by` on a nullable key says where NULLs go;
  - a value a person types that's compared is trimmed in Java;
  - nothing relies on whole-second times or on `database()`;
  - database size uses each database's own query.

## 10. The API

All under `/api/v1/admin`, described in `openapi-admin.yaml`. Field names are the builder's, within M2's conventions.

| Phase | Area | Routes |
|---|---|---|
| 1 | Dashboard | `GET /dashboard` (attention and health), `GET /dashboard/usage`; `GET /find?q=` (clients, matters, people) |
| 2 | Clients | `GET`, `POST /clients`; `GET`, `PATCH /clients/{id}`; `GET /clients/next-number` |
| 2 | Matters | `GET`, `POST /matters`; `POST /matters/check`; `GET /matters/next-number?clientId=`; `GET`, `PATCH`, `DELETE /matters/{id}`; `POST /matters/{id}/move` |
| 2 | Workspaces | `GET /workspaces/{folderId}/tree`; `POST /folders/{id}/children`; `PATCH`, `DELETE /folders/{id}`; `POST /folders/{id}/move`; `GET`, `PUT /folders/{id}/access`; `POST /folders/{id}/apply-access`; `GET`, `PUT /folders/{id}/properties`; `POST /folders/{id}/search-folders`; `GET`, `PATCH /search-folders/{id}` |
| 2 | Templates | `GET`, `POST /templates`; `GET`, `PATCH`, `DELETE /templates/{id}`; `GET /templates/{id}/problems` |
| 2 | What matters need early | `GET`, `POST /stores`; `GET`, `PUT /stores/{id}/access`; `GET /groups` |
| 3 | Stores | `GET`, `PATCH`, `DELETE /stores/{id}`; `GET /stores/deleted`; `POST /stores/{id}/restore`; `GET`, `PUT /stores/{id}/backend`; `GET /stores/{id}/stuck-files`; `POST /stores/{id}/stuck-files/retry` |
| 3 | Backends | `GET`, `POST /backends`; `POST /backends/test`; `GET`, `PATCH`, `DELETE /backends/{id}`; `POST /backends/{id}/test`; `POST /backends/{id}/recovery-key/show`, `/confirm`, `/enter`; `GET /backends/{id}/key-events`; `GET`, `PUT /storage/settings` |
| 4 | People | `GET`, `POST /users`; `GET`, `PATCH /users/{id}`; `POST /users/{id}/disable`, `/enable`, `/reset-password`, `/sign-out`, `/clear-block`; `PUT /users/{id}/groups`; `GET /users/{id}/devices`; `DELETE /users/{id}/devices/{tokenId}`; `POST /groups`; `GET`, `PATCH`, `DELETE /groups/{id}`; `GET /groups/{id}/members`; `PUT`, `DELETE /groups/{id}/members/{userId}` |
| 5 | Monitoring | `GET /errors`; `GET /errors/{id}`; `POST /errors/{id}/resolve`, `/reopen`; `GET /logs`; `GET /logs/{name}/lines`; `GET /logs/{name}/file`; `GET /audit`; `GET /audit/export`; `GET /jobs`; `POST /jobs/{name}/run`, `/stop`, `/enable`, `/disable`; `PUT /jobs/{name}/schedule` |
| 6 | Search index | `GET /search-index`; `GET /search-index/failed`, `/waiting`, `/history`; `POST /search-index/run`; `POST /search-index/failed/{docNumber}/retry`, `/skip`; `POST /reindex/preview`; `POST`, `GET /reindex`; `GET`, `DELETE /reindex/{id}` |

Phase 5 also makes three existing routes record views, with no change to their answers: `GET /v1/documents/{n}/preview`, `GET /v1/documents/{n}/message/body` and `GET /v1/documents/{n}/message/parts/{id}?inline=true` (§8.8).

What the first version reuses: `GET /v1/me`, `DELETE /v1/session`, `GET /v1/matters/recent`, `GET /v1/users` where a plain people picker is enough, `/v1/trash` for restoring matters, and `DELETE /v1/documents/{n}/checkout` for releasing someone's check-out.

`docs/reference/healdan-api-inventory.md` is updated by each phase: its entries' tags and endpoints, and the corrections the survey found (Healdan's storage service and its sign-in methods are missing from it, and some entries are out of date).

## 11. Every Classic Screen's Future

**Phase** means it moves in that phase. **Stage 2** means it stays in the Classic Interface for now and gets a design of its own before the Classic Interface retires. **Replaced** and **Drop** say what happens instead.

| Classic screen | Future |
|---|---|
| **System › General:** statistics | Replaced: Dashboard (1) |
| General: Sessions | Replaced: signed-in count (1), Sign Out Everywhere (4). A list of sessions is Stage 2 |
| General: Log | Replaced: Server Log (5). Logger levels are Stage 2 |
| General: Plugins, Environment | Stage 2, as a read-only About page |
| General: Run Level | Drop: it can turn saving off |
| **System › Scheduled Tasks** | Background Jobs (5) |
| **System › Updates and Patches** | Drop: dead |
| **Access Control › Users, Groups** | Phase 4 |
| Access Control › Security: password rules, session and cookie options, content policy | Stage 2 |
| Security: Menus | Drop with the Classic Interface, whose menus they control |
| Security: Anonymous, Geolocation | Stage 2, to decide whether they stay at all |
| **Settings › Search and Indexing:** status, queue, history, reindex | Phase 6 |
| Search and Indexing: patterns, batch and threads, filters, languages, parsers | Stage 2 |
| Search and Indexing: raw index query and delete | Drop |
| **Settings › GUI Settings** | Stage 2, carrying only what the new apps read (upload limits, preview limits) |
| **Settings › Protocols** | Stage 2 |
| **Settings › Outgoing Email** | Stage 2 |
| **Settings › Auditing** | Replaced by the Audit Trail (5). Choosing which events are recorded is Stage 2. Its retention fields do nothing today, so they go |
| **Settings › Proxy** | Stage 2, saying plainly what it affects |
| **Settings › Parameters** | Replaced by named settings in Stage 2 |
| **Document Metadata › Templates** (the property template) | Stage 2 |
| **Reports › Last Changes** | Replaced: Audit Trail (5) |
| Reports › Locked Documents | Stage 2, as everyone's check-outs |
| Reports › Deleted Documents, Deleted Folders | Replaced: the User app's Trash, for administrators. Destroy stays the nightly job's |
| Reports › Tickets, API Calls | Stage 2 |
| **Healdan › Settings:** clients | Phase 2 |
| Healdan › Settings: labels, numbering, Default Class, Start In, device limits, Removed Items Kept, API origins | Stage 2 |
| Healdan › Sign-In | Stage 2 |
| Healdan › Storage | Phase 3 |
| **Folder tree:** New workspace, a Store's permissions, deleting and restoring a Store | Phase 3 |
| New Matter, Edit Matter, moving and deleting a matter's folder, its top-level folders and permissions, search folders in it | Phase 2, with New Store and a Store's access, which a first matter needs |
| Everything in the Templates Store | Phase 2. Restoring a deleted template stays in the Classic Interface until Stage 2 |

**Before the Classic Interface retires,** Stage 2 has to be designed and built, and one question answered: groups given Administration screens through its menu permissions have no equivalent here (§5).

## 12. The Web Workspace

- **`apps/admin`:** its own `package.json`, `vite.config.ts` (`base: '/admin/'`, no PDF.js, the same chunks and dev proxy) and `index.html`, with the same pressable stylesheet link as the User app, moved to one shared place.
- **Build:**
  - `healdan-web/pom.xml` gains a second resource copy, to `healdan/app/admin`;
  - the root `build` builds both apps;
  - `check-dist.mjs` takes the app, and also proves the User app's build holds no Admin code;
  - one ESLint rule forbids importing from another app.
- **Type checking runs once per app** (`tsc -p . && tsc -p apps/user && tsc -p apps/admin`), because each app registers its own router with TanStack, which one program can't hold twice.
- **`packages/shell`,** a new package, takes what both apps need that depends on TanStack or the API: `useApi`, the signed-out state, the query client, the error messages, `SignedOut`, `InsecureAddress`, `ErrorPanel`, the mark, the person menu, Help, and search and recent-matter hooks. It moves first, in a commit that changes no behaviour, with re-exports at the old paths. `Frame.tsx` stays the User app's.
- **`packages/ui` gains:** `selectionMode="none"` on `Table`; a progress bar over React Aria's; a status dot that always has text beside it; a multi-line text field; icons and secondary text on `PickField`; a list-style searching picker taken from the Move To… dialog.
- **No new runtime package** (call). The chart is plain SVG, which the page's policy allows.
- **Strings:** the app's are its own catalogue, typed over the shared one, so users don't download them. Error-message lookup takes the catalogue, and the check of problem codes treats Admin-only codes separately.
- **Preferences** get a prefix per app, keeping the User app's existing keys, so the two apps' pane widths don't collide.
- **One `notices.txt`,** at `/app/notices.txt`, already covers every package in the workspace. The app's Help links to it.

## 13. The User App's Additions (owner)

- **Admin** in the person menu, for administrators (§6.4).
- **Reindex** on the right-click menus of a document (or a selection of them), a folder and a matter, for administrators. It's the same server function as the Admin app's (owner). A small question names what will be reindexed and how many documents, and a toast confirms it, with **View Progress** opening the Search Index. It calls `POST /v1/admin/reindex` directly, so you stay where you were, and no Admin screen enters the User app's bundle (call).
- **Manage in Admin** on a matter's menu, which opens its page in a new tab (call). `MatterMenuItems` gains the matter's id, which all its callers have.
- **What says who's an administrator** is `useMe().data.admin`, which only decides what's offered. The server decides what's allowed.
- The User Guide's rule that every menu choice is also on the toolbar is reworded to except these administrators' items.

## 14. Delivery (owner: this order)

The work starts after the PostgreSQL work's PR A merges. Each phase is one or more pull requests, with its tests and docs, and is usable by itself. The Classic Interface keeps working throughout, and its screens aren't removed.

| Phase | Holds | Done when |
|---|---|---|
| **1. Foundation and Dashboard** | `apps/admin`, `packages/shell`, `AdminAppServlet`, `ReturnPath`, `AdminFilter` and the second contract, the frame, the **Admin** menu item, the administration record, error capture and job status, the storage port's reading side, the failed and waiting counts, the Dashboard | An administrator lands on a working Dashboard, a non-administrator gets none of the app's files, and errors are being recorded |
| **2. Clients, Matters, Workspaces and Templates** | §8.2 to §8.5, **Manage in Admin**, and from later areas what a first matter needs: **New Store** with its access, and a read-only list of groups for the permission rows | A fresh install goes from nothing to a Store, a template and a filed-in matter without the Classic Interface's folder tree |
| **3. Stores and Backends** | The rest of §8.6: the Stores list and pages, and the storage port's writing side | Everything the Storage tab does works here |
| **4. Users and Groups** | §8.7 | A person is added, grouped, reset, disabled and re-enabled here |
| **5. Monitoring** | §8.8, with views recorded from the User app | A fault raised on the stack is found, read and resolved; an administrative change is found in the Audit Trail with its old and new values; a document read in Quick Look shows there as viewed, and one only selected doesn't |
| **6. Search Index and Reindex** | §8.9, the User app's **Reindex** | A matter reindexed from either app runs straight through, with search working meanwhile |

A last small pull request records the result: the roadmap, the plan's ledger and the follow-ups. Stage 2 gets its own design session.

## 15. Files

### 15.1 Upstream files

**None.** Every part was checked against this aim: `/admin/*` is free in `web.xml` and CE's security chain, and each service the API needs is reachable from `healdan-core` and `healdan-api`. If the build finds an edit it can't avoid, it goes back to the owner and is listed here first.

### 15.2 Fork-owned files

- **New:** `AdminAppServlet` and `BuiltAppServlet`; the `com.logicaldoc.healdan.api.admin` package; `openapi-admin.yaml`; `healdan-core`'s `admin` package (`AdminAccess`, the administration record, `JobStatus`, error capture, the reindex job and driver, `StoreService`, `FolderAccessService`, `UserAdministration`, `GroupAdministration`, `StorageAdministration`); the adapter in `healdan-storage`; `apps/admin`; `packages/shell`; the migrations.
- **Changed:** `AppServlet`, `ReturnPath`, `ApiServer`'s providers, `Contract`, `RouteCoverageTest`, `OpenApiFileTest`, `DocumentResource` and `MessageResource` (views), `HealdanStartup`, the workers that report status, `ClientService` and `MatterService` (recording); `healdan-web/pom.xml`; the workspace's root files and `scripts/`; `apps/user` (§13, and what moves to `packages/shell`); `packages/ui`; `.github/scripts/code-changed.sh`; `docker/tests/test-web.sh`; `CLAUDE.md`; `docs/ROADMAP.md`; `docs/reference/healdan-api-inventory.md`; `docs/reference/logicaldoc-ce-capability-map.md`.

## 16. Docs (each with the phase that ships it)

The Admin Guide's pages move to the app as their screens do, keeping their addresses, and describe the Classic Interface only for what's still there.

| Phase | Pages |
|---|---|
| 1 | New: Admin Guide, The Admin App. Changed: the Admin Guide's front page; the User Guide's Getting Around and The Classic Interface; the Install manual's Known Limitations, Reverse Proxy, Troubleshooting and Quick Start; the Developer Guide's The Web App, Building from Source, How Healdan Extends LogicalDOC, Tests, Continuous Integration and API |
| 2 | Clients and Matters, Workspace Templates, Top-Level Folders, Permissions, Healdan Settings (clients), Stores (New Matter); the User Guide's Stores and Matter Workspaces |
| 3 | Stores, all of Storage Backends, Healdan Settings (Storage); the Install manual's Cloud Storage, Backup and Restore, Troubleshooting, Upgrading and Known Limitations |
| 4 | Users and Groups, Healdan Settings (devices and the throttle), System Information (signing someone out), the Install manual's Sign-In Providers |
| 5 | New: a Monitoring section, saying what counts as a view. Changed: System Information, Scheduled Tasks, Reports, Office Previews, the Install manual's Troubleshooting, How Healdan Extends LogicalDOC, the Developer Guide's API |
| 6 | Search and Indexing, Scheduled Tasks, the User Guide's Getting Around and Searching, the Install manual's Upgrading |

## 17. Testing and Acceptance

### 17.1 Automated

- **Server, on PostgreSQL:**
  - every route through `ApiTestCase` against the second contract: as an administrator, as a non-administrator, and with a device token;
  - a test that every `/v1/admin` route carries `@AdminRoute`;
  - `AdminAppServletTest`: every cell of §6.1's table, `private` caching, `no-store` on refusals, and `/app/../admin/` and its encoded forms;
  - `ReturnPathTest`'s new cases;
  - each guard of §8.6 and §8.7;
  - access levels against every combination that should read as Custom;
  - the administration record: written with its change, rolled back with it, and free of secrets;
  - error capture: an additive and a non-additive logger, a reconfigure, a full queue, the database down, grouping, and a resolved fault coming back;
  - views: a preview, an email and an inline attachment recorded once per half hour; a `202`, a thumbnail and properties not recorded;
  - reindexing: each scope, Skip left alone, a job with failures reaching its end, failed told from waiting, Cancel, a restart resuming from its place, and old hits still found.
- **App:** Vitest and Testing Library for each screen, over a fake API typed from the contract, with an axe check.
- **End to end,** in Playwright in Chromium and WebKit, in the smoke test's last step: `admin-access.spec.ts` (signing in and returning as `admin`; `webuser`'s no-access page and refused asset; a signed-out asset), one spec per screen, and `admin-a11y.spec.ts` in both themes. These specs run before most others and share the stack, so they change nothing global: their own clients, matters, Stores and people, no index emptying, and no change to `webuser`, Everyone or `Smoke Garage`. `helpers.ts`' `signIn` takes who signs in.
- **`docker/tests/test-web.sh`** gains `curl` checks that `webuser` gets `403` at `/admin/` and that a signed-out asset isn't `200`.

### 17.2 Acceptance (owner)

On the owner's server, in Safari, one walkthrough per phase, following its "done when", and at the end the week of §2.1. Anything that sends the owner to the Classic Interface other than §11's Stage 2 screens is a punch-list item.

## 18. Left to the Builder

- Field names, problem codes and the exact shape of each route, within §10 and M2's conventions.
- The disk threshold, cache lifetimes and refresh intervals.
- The fingerprint's exact recipe and the size caps, within §9.4.
- How a date range becomes an id range.
- Column details and the order of work inside each phase.
- Whether a Store's delete and restore need more than `TrashRestore`, which the survey couldn't confirm end to end.

## 19. Decisions Made in This Session

| # | Decision | By |
|---|---|---|
| 1 | The first needs: storage, matters, clients, workspaces, users, a dashboard of health and usage, logs, errors and audits, and reindexing | owner |
| 2 | The left-pane frame | owner |
| 3 | Administrators only at first, with the server checking each area separately | owner, the second part a call |
| 4 | A matter opens as its own page, with its workspace on a tab | owner |
| 5 | Errors are grouped by kind and kept until resolved | owner |
| 6 | Anything that can grow long is a search | owner |
| 7 | Reindex from documents, a folder, a matter, a Store or everything, running straight through | owner, the straight-through run a call |
| 8 | **Reindex** on the User app's right-click menus for administrators | owner |
| 9 | Closing matters is left out, for careful thought later | owner |
| 10 | The delivery order of §14 | owner |
| 11 | **Admin** in the User app's person menu | owner |
| 12 | Reading a document in the User app is recorded as a view, once per person, document and half hour; thumbnails and properties aren't | owner |
| 13 | New User makes password accounts; provider accounts appear at first sign-in | owner |
| 14 | Read Only, Read/Write and Full Access, with Custom | call, shown to the owner |
| 15 | New Matter previews its result and catches problems first; New Store asks who can see it | call, shown to the owner |
| 16 | People are disabled, not deleted | call, shown to the owner |
| 17 | The app in `healdan-web`, its API in `healdan-api`, no upstream edit | call, heard by the owner |
| 18 | New Store and a read-only list of groups come forward to Phase 2, as a first matter needs them | call, told to the owner |
| 19 | The reindex job indexes each document itself, trying each once | call, told to the owner |
| 20 | A second contract file, and admin routes that take only the web session | call |
| 21 | Only the app's changes are recorded, with clients and matters from either UI; a change that isn't one transaction is recorded just after it | call |
