# Healdan roadmap

- **Date:** 2026-10-16
- **Status:** direction agreed with the owner in an interview session. M1 is done: the owner accepted it on 2026-10-22 ([spec](superpowers/specs/2026-10-17-m1-move-in-design.md), [plan](superpowers/plans/2026-10-17-m1-move-in.md)).
- **Companion:** [`docs/reference/logicaldoc-ce-capability-map.md`](reference/logicaldoc-ce-capability-map.md) records what LogicalDOC CE 9.3.1 already has, what's hidden, half built or missing, with file paths.

This is a living document. It sets the direction and the order of work. It is not a build plan.

- **Before building:** every milestone gets its own design session first: brainstorm → design spec in `docs/superpowers/specs/` → plan in `docs/superpowers/plans/` → build.
- **When a milestone moves:** update its status here and link its spec and plan.
- **Changing direction:** if a design session changes an owner decision recorded here, update this file in the same PR.

Throughout, **(owner)** marks something the owner decided or described. **(call)** marks Claude's proposal, which the owner heard and didn't object to. Calls are open to revision in the milestone's design session without going back to first principles.

## 1. What Healdan is

**Healdan is a self-hosted, open-source, matter-centric document management system.** It's a smaller alternative to iManage Work and NetDocuments, built on LogicalDOC Community Edition. (owner)

### Why

- **The owner's background.** The owner used iManage professionally for many years, and every other solution falls short of that. Today they use a mix of NAS storage, iCloud and SharePoint. They have tried LogicalDOC as shipped, Alfresco and others, and rarely liked the results. (owner)
- **LogicalDOC is the right size.** It has the foundation and the scale to become what the owner expects once the missing pieces are added and refined. (owner)
- **The gaps are the parts LogicalDOC sells as paid extras.** (owner)
  - Finding things by client, matter or document number.
  - Custom document identifiers.
  - Folder search.
  - Office integration.
- **The gap is how the data gets used, not how it's stored.** Client/matter metadata can already be modelled with templates and attribute sets. What's missing is UI/UX and search built around it. (owner)

### Who it's for

1. **The owner, as the first real user.** The bar is iManage muscle memory. (owner)
2. **Small firms organised by client/matter or client/project.** (owner)
3. **Non-profits doing case management, legal clinics and similar.** (owner)

### Success

- **First:** the owner moves their own work off the NAS, iCloud and SharePoint mix and into Healdan full time. The hard blockers are first-class document numbers and matter-centric workspaces. Everything else can follow while the owner is using it. (owner)
- **Then:** Healdan is good enough that small firms and clinics adopt it. Windows support matters at that point. (owner)

## 2. Principles

1. **The doc # is the handle.** People say "can you revise doc #23456222?" in email, and the number is stamped in the document's footer. Typing `#23456222` and pressing Enter returns search results containing just that document, ready to act on. (owner)
2. **Common lookups need no search-building.** If finding a document by number, or a matter by client or matter, takes convoluted search criteria, Healdan has failed, even if the feature technically works. This is the anti-goal. (owner)
3. **The matter is the unit of organisation.** Properties, permissions and folder structure start at the matter workspace and flow down. (owner)
4. **The server enforces every rule, whichever way a request comes in.** That covers the web UI, the API, WebDAV, CMIS and the add-ins. LogicalDOC gates features only in its GWT UI; Healdan doesn't rely on UI gating for anything that matters. (call)
5. **Stay mergeable with upstream.** (CLAUDE.md, owner's standing direction)
   - New work goes in new plugin modules.
   - Keep edits to upstream files to a minimum, and list them in each milestone spec.
   - Merge `upstream/master` regularly.
6. **Re-implement, never copy.** Features that LogicalDOC sells are rebuilt from observed behaviour, not from proprietary code. (owner's standing direction)
7. **Linux and macOS first.** Windows comes later, for adoption. (owner)

## 3. Glossary

The words *Store* and *workspace* already mean something different in LogicalDOC. Specs and code comments must say which meaning they use.

| Healdan term | Meaning | What it maps to in LogicalDOC |
|---|---|---|
| **Store** | **Organisation:** the top-level container, e.g. Personal vs Client Work, or geographic regions. **Location:** where its files physically live, either local/NFS or later S3. Access is permissioned. (owner) | **Call:** a LogicalDOC *workspace*, which is a `Folder` of type 1 directly under the root. Its backing storage comes from LogicalDOC's per-folder storage mapping. It is **not** a LogicalDOC tenant. |
| **Storage backend** | The physical place file bytes go. | What LogicalDOC calls a `Store` (`FSStore` etc.). In Healdan documents, call it "storage backend", never "store". |
| **Workspace** (matter workspace) | One per client/matter, built from a workspace template. (owner) | **Call:** an ordinary LogicalDOC folder inside a Store, marked as a matter workspace and carrying the client/matter properties. It is **not** a LogicalDOC workspace. |
| **Client**, **Matter** | Managed lists. A matter belongs to a client. A workspace *is* a matter, and client is one of its properties. (call) **Everything uses client/matter**, including non-client work, which gets house numbers. A client can be the owner themself ("Personal"), and "matter" also means "project". (owner) | New Healdan data. |
| **Workspace template** | A predefined set of folders and search folders that a new workspace is created from. (owner) | CE's folder templates are stubs. M1 builds templates as real folder trees in an admin-only Templates Store, copied when a matter is created ([M1 spec](superpowers/specs/2026-10-17-m1-move-in-design.md) §5.2). |
| **Search folder** | A virtual folder inside a workspace. Its contents come from a saved search, and it can't hold documents. (owner) | New. M1 gives each search folder its own rule (time window, whose changes, kind, Class) rather than a CE saved search ([M1 spec](superpowers/specs/2026-10-17-m1-move-in-design.md) §5.4). |
| **Doc #** | The document number: numeric and unique across the whole system. It covers every version of the document. (owner) | Healdan's own counter, stored in LogicalDOC's `customId` field ([M1 spec](superpowers/specs/2026-10-17-m1-move-in-design.md) §6.2). |
| **Legacy doc #** | A document's number from the DMS it was migrated from. Searchable. Imports set it; Healdan never assigns it. (owner) | New. |
| **Properties** (profile) | Metadata on a document or folder: client, matter, doc type, classification and so on. | Templates and extended attributes. |
| **Ethical wall** | A client- or matter-level access restriction that overrides every permission. (owner) | New. LogicalDOC has no deny semantics. |
| **Tenant** | LogicalDOC's multi-tenancy. | Not used by Healdan. It's too isolating: a user belongs to exactly one tenant. |

## 4. Milestones

| # | Milestone | Depends on | Status |
|---|---|---|---|
| M1 | Move in | – | **done** 2026-10-22: [spec](superpowers/specs/2026-10-17-m1-move-in-design.md); [plan](superpowers/plans/2026-10-17-m1-move-in.md). Design and plan approved 2026-10-17. PR A (Tasks 1–7) merged 2026-10-17, PR B (Tasks 8–16) 2026-10-18 and PR C (Tasks 17–21) 2026-10-19. Five PRs closing CE permission gaps merged 2026-10-19 and 2026-10-20. PR D (Tasks 22–28, the GUI and rebrand) merged in five parts on 2026-10-21, after the owner reviewed its mockups. On 2026-10-22 a review of every open follow-up found four gaps to fix before acceptance, which the pre-acceptance PR ([Healdan-Project/healdan#44](https://github.com/Healdan-Project/healdan/pull/44)) fixed. The owner's acceptance walkthrough (the plan's Task 30) passed all eight rows on 2026-10-22. Its one punch-list item, Doc # in search results, was fixed in [Healdan-Project/healdan#45](https://github.com/Healdan-Project/healdan/pull/45) |
| M1.5 | Office previews and thumbnails | M1 | **built** 2026-10-22: [spec](superpowers/specs/2026-10-22-m1-5-office-previews-design.md); [plan](superpowers/plans/2026-10-22-m1-5-office-previews.md); PR A (the server side) [Healdan-Project/healdan#48](https://github.com/Healdan-Project/healdan/pull/48) and PR B [Healdan-Project/healdan#49](https://github.com/Healdan-Project/healdan/pull/49) (the image, smoke checks and docs). Awaiting the owner's acceptance walkthrough |
| M2 | API foundation | – (M1 domain model for its matter endpoints) | not started |
| M3 | Office integration | M2 (M4 for single sign-on) | not started |
| M4 | OIDC sign-in | – (can run alongside others; land before M5) | not started |
| M5 | Email | M2, M4, M1's email indexing fix | not started |
| M6 | New web UI: User app | M2, expanded | not started |
| M7 | Ethical walls and the Walls app | M6 | not started |

The order is the owner's priority (owner):
- M1 removes the blockers to moving in.
- Tight office suite integration is the next thing the owner would miss.
- The UI refresh and rebrand follow.

M2 comes before M3 because the add-ins need an API (call). M4 can be pulled earlier at any point.

---

### M1 — Move in

**Intent.** Make Healdan good enough that the owner moves their own work in and keeps using it. It ships in the current LogicalDOC UI, changed only as much as it needs. The new UI comes later. (owner: "the first class identifiers and workspaces are hard blocks"; the rest can follow while using it.)

**Decided (owner).**

- **Document numbers.**
  - Every document has a numeric doc #, unique across the whole system and all Stores.
  - iManage makes numbers unique per database, and firms work around that with per-database number ranges. Healdan uses one number space instead.
- **Legacy doc #.**
  - A per-document field holding the number from a previous DMS.
  - Searchable.
  - Set only by import and migration.
- **Search box.**
  - A scope picker sits beside the search box: Documents (default), Emails, Docs & Emails, Folders, Matters, Clients.
  - Typing `#123456` and pressing Enter shows search results containing just that document. There are no criteria to fill in.
  - From that result you can do everything you'd do to the document anywhere else: open, preview, download, change permissions, amend properties and see its versions.
  - The Emails scopes only become useful with M5.
- **Client and matter are first-class.** Both are easy to search.
- **Stores.**
  - A Store is a top-level classification and also decides where its files are stored.
  - Stores are governed by permissions.
  - Moving items between Stores moves the backing files and folders.
  - For M1, every Store uses one local or NFS storage backend. S3 is a later goal.
- **Matter workspaces from templates.**
  - A template defines predefined folders and virtual search folders.
  - Properties and permissions start at the workspace and propagate to every folder, subfolder and document. A document added to a folder inherits client/matter from the folder, which inherits it from the workspace.
  - A folder can define additional properties, such as doc type or classification, which pass down to its children.
  - **Overrides:** a folder or document can override an inherited property.
- **Filing rule.**
  - Documents are saved only within a folder of a workspace.
  - They can't be saved at the top level of a workspace or into a search folder.
- **My Matters.**
  - Each user keeps a list of favourite client/matter workspaces.
  - Recent matters are listed too. The iManage reference layout has both.
- **Migration is manual for M1.** The owner uploads a few thousand files at most through the browser. A bulk import/export tool is in the backlog.
- **Client/matter everywhere.**
  - Every workspace has a client and a matter, including personal and non-client work, which use house numbers.
  - A client can be the owner themself ("Personal").
  - "Matter" also means "project".
- **Dates.**
  - Every document has a **Date filed**: when it entered Healdan. It is always "now" when the document is added.
  - Emails also carry their **sent** and **received** timestamps.
  - Original file dates don't matter for anything else.
- **Network.** The M1 server is reachable only on the LAN or over VPN. The internet-facing piece is the separate Healdan Share project (backlog).
- **Previews.** Office previews aren't in M1. They're M1.5.
- **Design session, 2026-10-17.** The owner settled the details in the [M1 design spec](superpowers/specs/2026-10-17-m1-move-in-design.md). Its §16 lists every decision. The main ones:
  - Doc #s are unpadded whole numbers with room for at least 999,999,999.
  - Versions use LogicalDOC's numbering: the major number is the version and minors are snapshots on its timeline. The footer shows `# 1234.2.1`.
  - Matter numbers are either per client (`998.1`) or system-wide, chosen per install.
  - Admins create matters. The dialog picks client, number, name, Store, owner and template. It opens from a Store's menu and pre-selects that Store; on 2026-10-21 the owner dropped the default Store setting this line first had.
  - Every document carries Author, Typist, Class (required, default "Docs") and an optional Subclass.
  - Emails also capture From, To, CC and Subject, and are named after their subject.
  - Only admins add, rename, move or delete a matter's top-level folders.
  - A document can be granted to people who can't see its folder. This is a one-off or temporary exception, as in iManage.
  - My Matters and Recent Matters are lists in the old UI's left pane; trees wait for M6.
- **Mockup review, 2026-10-21.** Matter lists show the client's name: matter search results get a Client column, and My Matters and Recent Matters entries read `998.1 – Healdan Demo (Healdan)`, with the client smaller and muted. The spec's §8.1 and §8.2 carry it.

**Calls.**

- **The doc # counter.**
  - The doc # gets its own system-wide counter. It doesn't reuse LogicalDOC's internal document ID, because:
    - document and folder IDs come from separate sequences, so the same number exists as both;
    - the internal ID skips blocks of up to 50 on every restart;
    - a separate counter lets Healdan choose the number's starting value and format.
  - Imports never set the doc #. A migrated document gets a new doc #, and its old number goes in the legacy doc # field.
  - During manual migration, the legacy doc # can be typed into a document's properties.
  - The number is assigned when the document is created.
  - It never changes: not on a move, a rename or a new version.
  - It is never reused, even after deletion.
- **Version lookup** (refined in the design session).
  - `#n` finds the document and shows its latest version.
  - `#n.N` finds version N as it stands, and `#n.N.M` finds one snapshot. `#nvN` also works.
- **Jumping to a matter.** With the Matters scope, a matter number jumps straight to its workspace, the way `#` does for documents.
- **Client and matter.**
  - The workspace is the matter. Client is one of its properties, not a folder level above it.
  - The Clients scope lists the matters of every matching client. M1 has no separate client list.
  - Client and matter numbers are picked from managed lists, so nobody can create a matter under a mistyped client.
- **Inherited values.**
  - Inherited property values are copied onto each document, so search is fast and the document carries its own profile.
  - Moving a document to another matter re-stamps it with the new matter's values.
- **Overrides.**
  - An override applies to one property at a time. In the old UI, giving a property a value different from the one it would inherit makes it an override. Setting it back, or clearing it, follows the parent again.
  - An overridden value stops following its parent.
  - Client/matter can't be overridden.
- **Folder permissions.** A folder can restrict access more tightly than its workspace (a "Privileged" folder, say), but it can't loosen it.
- **Template changes** don't rebuild workspaces that already exist.
- **Search folders** search only within their own workspace, e.g. "Emails in this matter" or "My recent docs here".
- **Store visibility** is controlled by permissions like anything else.
- **One storage backend in M1.**
  - Every Store uses the same backend, so moving a workspace between Stores changes only the database.
  - Real file moves arrive with the per-Store storage item in the backlog.
  - Files stay stored by internal ID, as CE and iManage both do, not as browsable `Client/Matter/…` paths.
- **Permissions are enforced in M1, not deferred.**
  - Store permissions and "folders can restrict but not loosen" are both enforced and tested in M1.
  - The owner's day-to-day account should not be an admin, because CE admins skip every permission check.
- **Emails uploaded by hand keep their dates.** When an `.eml` or `.msg` file is uploaded in M1, its sent and received timestamps are read from the message and stored as properties. CE's `MailUtil` already parses both formats.
- **Date filed** maps to LogicalDOC's creation timestamp and can't be edited.
- **Configurable label.** The user-facing label "Matter" can be set per install, for example to "Project".
- **Light rebrand of the old UI.** The name, logo, login page and browser title become Healdan. Full branding comes with M6.
- **Fix full-text indexing.** CE has no text extractor for `.docx`, `.xlsx`, `.pptx`, `.eml` or `.msg`, so their contents aren't searchable today. Check it on a running server, then fix it. The capability map has the evidence.
- **Fix the upstream security bugs the recon found.** They're listed in the capability map. The four it listed first were fixed in PR [Healdan-Project/healdan#15](https://github.com/Healdan-Project/healdan/pull/15), and the map tracks the rest. The design session found more: CE's RPCs don't check who may change permissions or rename. The M1 spec fixes those (§7.3). PR C found CE RPCs that check no permission at all. It fixed the four a `#` result uses and listed the rest under the M1 plan's "Follow-ups from PR C". A follow-up PR checks all of those, and lists the further unchecked calls it found under "Follow-ups found while closing PR C's". A second follow-up PR, the gaps PR, checks those too, and a third, the web-services PR, closes the same gaps on SOAP and REST, which the gaps PR listed under "Follow-ups found while closing the gaps".
- **Turn on cheap gated features where they help.** M1 turns on multiple LogicalDOC workspaces, which are Healdan's Stores. Saved searches stay off, because search folders cover the need.

**Non-goals.**

- The new UI.
- Office previews and thumbnails (M1.5).
- S3.
- Office add-ins.
- Email filing.
- OIDC.
- Ethical walls.
- A bulk import tool.

**Done when.**

1. On a fresh install, the owner can:
   - create Stores, clients and matters;
   - create a matter workspace from a template;
   - upload documents through the browser into its folders;
   - see each document get a doc # and its inherited properties.
2. `#n` in the search box returns just that document, and you can open, preview, download, change permissions, edit properties and see versions from the result. A matter number finds its workspace.
3. A document can't be saved at a workspace's top level or into a search folder, by any route.
4. My Matters and recent matters work.
5. Text inside Word documents and emails is found by full-text search.
6. An uploaded email shows its sent and received timestamps, and every document shows its Date filed.
7. Data survives upgrading to the next Healdan release. See the schema upgrades item in §6.

**Design.** Settled in the [M1 design spec](superpowers/specs/2026-10-17-m1-move-in-design.md), which the owner approved on 2026-10-17. The [M1 plan](superpowers/plans/2026-10-17-m1-move-in.md) records how it was built: its rulings, decision log and follow-ups. The spec's §13 lists every upstream file M1 edits.

---

### M1.5 — Office previews and thumbnails

**Intent.** Word, Excel and PowerPoint files preview in the browser and get thumbnails. The owner placed this right after M1. (owner)

**Facts from the recon.** CE has no converter for Office formats. They fall to `NotAvailableConverter`, which shows a "not available" placeholder and no thumbnail.

- `converter.LibreOfficeConverter.*` keys exist in `context.properties`, but the class doesn't.
- The Docker image doesn't install LibreOffice.

**Calls.**

- **The converter.** Add headless LibreOffice to the runtime image on both amd64 and arm64, plus a Healdan `FormatConverter` plugin for Office formats to PDF.
- **Commands allow-list.** New external commands need a line in `docker/allowed-commands.txt`.
- **Indexing stays separate.** Full-text indexing (M1) doesn't depend on this; it uses a parser such as POI or Tika, not PDF conversion.
- **Watch the image size.** Record the size increase in the design.

**Design.** Settled in the [M1.5 design spec](superpowers/specs/2026-10-22-m1-5-office-previews-design.md), which the owner approved on 2026-10-22. A preview is a quick check that it's the expected document (owner), so Healdan converts each new Office file in the background, shows tracked changes and comments (owner), and serves the result through a small hook in CE's `ConvertPdf`, which until now served only PDFs. LibreOffice adds about 410 MB to the image. The build departed from the spec in four main ways: comments show as PDF notes instead of in the margin, tracked changes are forced to show only for `docx`, `docm`, `dotx` and `dotm` (not for `.doc`, `.rtf` or OpenDocument files), the background worker takes the newest jobs first, and previews of a version fail fast after a recorded timeout. The code review of both PRs then made a replaced file or a raised timeout start a version's conversion over, and made thumbnails convert only in the background.

---

### M2 — API foundation

**Intent.** A documented JSON API that the add-ins (M3, M5) and the new UI (M6) are built on.

**Decided (owner).**

- The new UI is non-Java and talks to the server through an API.
- The old UI does much of its work through internal Java RPC, so the API has to expose everything the new clients need. Today's REST API isn't enough.

**Calls.**

- **Design approach.**
  - Design the API first, as an OpenAPI spec.
  - Make it resource-oriented and versioned, e.g. `/api/v1`.
  - Serve it from the same server as the rest of Healdan.
- **Inventory first.** The spec starts with an inventory of every GWT-RPC service (`logicaldoc-gui/**/client/services`) and every `/data/*.xml` data servlet. Each entry is mapped to an endpoint and tagged with the milestone that first needs it, so the API grows with its clients.
- **First slice: what the add-ins need.**
  - Sign-in and tokens.
  - Current user.
  - Browsing and searching Stores, workspaces and folders.
  - Recent workspaces and folders.
  - `#` and legacy lookup.
  - Document create and upload.
  - Check-out and check-in.
  - Save as new version and save as new document.
  - Properties, with required fields and inherited values.
- **Add-in requirements.**
  - CORS, because Office web add-ins call the API from a browser context.
  - Token authentication, so add-ins never store a password.
- **Leave the old interfaces alone.** The existing SOAP, REST, WebDAV and CMIS interfaces stay as they are.

**Non-goals.** Removing or reshaping the existing `/services/rest` API.

**For the design session.**

- **Code location.** Both Spring and Hibernate scan only `com.logicaldoc`. Either Healdan code lives under `com.logicaldoc.healdan…`, or the scans are extended. Decide once and use it everywhere. M1 may need to make this call first.
- **Framework.** Use the existing CXF JAX-RS stack, or something else.
- **Push updates.** The `/wk-event` websocket sends GWT-serialized payloads. Decide what the new clients use instead.

---

### M3 — Office integration

**Intent.** Tight integration with the office suite: you save to and open from Healdan inside the app, and the document always shows its doc # and version.

**Decided (owner).** The flow below was proposed modelled on iManage, and the owner confirmed it.

- **Ribbon.** A Healdan group on the ribbon.
- **Save to Healdan.**
  1. A dialog opens. You either search for a workspace or folder, or pick a recently used one.
  2. You select a folder.
  3. The dialog asks for any required properties. Most are pre-filled from the folder.
  4. The final **Save** button turns on once everything required is filled in.
  5. After saving, the title bar shows the document's title with its doc # and version.
- **Opening.**
  - Open from Healdan through the web UI, or through an **Open from Healdan** dialog with recent documents, search and `#` lookup.
  - Opening checks the document out to you. Others get a read-only copy and see who has it.
- **Saving while editing.** An ordinary save updates the current version.
- **Explicit save commands.**
  - **Save as new version** is a ribbon button.
  - **Save as new document** files a copy under a new doc #, through the same dialog.
- **Closing** checks the document back in.
- **Insert doc ID.** A button inserts a footer field that updates itself with each new version.
- **Excel and PowerPoint** get the same open and save flow. Footer stamping is Word and LibreOffice Writer only at first.
- **Platform priority.**
  1. LibreOffice on Linux and macOS.
  2. Microsoft 365 desktop apps on macOS.
  3. Windows, later. Others will need it before they adopt Healdan.
- **Out of scope: editing in Word for the web.**
  - Microsoft requires its Cloud Storage Partner Program for this.
  - It's recorded as a known limitation.

**Calls.**

- **Start with a spike.** M3 opens with a throwaway trial build to settle how files travel between the app and the server, because the title bar requirement rules out a simple "upload a copy" add-in. The candidates:
  - **WebDAV.** Open the file straight from the server, with real file locking (WebDAV class 2) and the doc # in the file name.
  - **Local cache.** An add-in or small helper keeps local copies, the way iManage and NetDocuments do.
- **LibreOffice.** A LibreOffice extension (UNO, probably Python) runs the same on Linux, macOS and Windows. It can probably download, cache and upload files itself.
- **Microsoft 365 on the Mac.** An Office web add-in (Office.js) plus whichever transport the spike picks.
- **Shared components.** The Office web add-ins share React components with the new web UI, such as the workspace picker and the properties form.
- **Sign-in.** Add-ins start with Healdan credentials exchanged for a token. They move to the OIDC provider's pop-up once M4 lands.

**Facts from the recon.**

- **WebDAV.** CE advertises no class-2 locking. LOCK is turned into a check-out with no lock token. Expect read-only opens or failed saves in Word and LibreOffice until that's fixed. macOS Finder also mounts such a share read-only.
- **CMIS.** CE has CMIS AtomPub 1.0, which LibreOffice's CMIS support uses. It hasn't been tested.

**Done when.**

- In LibreOffice on Linux and on macOS, the owner can:
  1. create a document;
  2. Save to Healdan;
  3. close it and reopen it from Healdan;
  4. edit it;
  5. save a new version;
  6. insert the doc ID footer.
- The same works in Word for Mac.

---

### M4 — OIDC sign-in

**Intent.** Sign in with the identity providers the owner already uses.

**Decided (owner).**

- Two providers:
  - Microsoft Entra ID (formerly Azure AD), because the owner's organisation is a Microsoft 365 tenant;
  - Authentik, through OAuth/OIDC.
- The scope below is right "for initial deployment".

**Calls.**

- **One implementation.** One standard OIDC sign-in, configured per provider.
- **Tested providers.** Entra ID and Authentik get tested, documented presets. Other OIDC providers should work but aren't promised.
- **Accounts and groups.**
  - The first sign-in creates the Healdan user.
  - Group membership can be mapped from the provider's groups.
- **Emergency access.** A local admin login with a password always remains, as a way in if the provider is down.
- **Two-factor.** The provider handles it. Healdan's own two-factor for local accounts is in the backlog.
- **Add-ins** sign in through the same provider in a pop-up.
- **Not planned.** LDAP and SAML, unless an adopter needs them.

**Facts from the recon.**

- CE's `Authentication` extension point and its authenticator chain cover every channel. Everything goes through `SessionManager.newSession`.
- OIDC is a redirect flow, though, so it also needs a hook into Spring Security, which is configured in `context-security.xml`. That file is upstream's.
- No OAuth2 or OIDC libraries are on the classpath yet.

**Timing.** The core server is LAN or VPN only (owner), so CE's broken two-factor isn't urgent, and M4 doesn't have to jump ahead of M2 and M3. Revisit this if the core server ever faces the internet.

---

### M5 — Email

**Intent.** Email lives in the DMS next to the documents and is filed into matters.

**Decided (owner).**

- **Filing from Outlook.**
  1. An add-in shows a bar for the email you're reading.
  2. You search for a workspace or folder, then click File.
  3. An option chooses whether attachments stay inside the email or are filed as separate documents marked as related.
- **Drag and drop.** Dragging an email from the mail app into a folder in the web app files it.
- **Mail clients.**
  - Outlook on macOS is primary.
  - Outlook on the web, used on Linux, is secondary.
  - Integration options for Linux are worth exploring.
- **Later phase.** An Outlook folder is linked to a matter folder, and everything that lands in it is filed automatically.
- **Dates on a filed email.**
  - Its sent and received timestamps are captured.
  - Its Date filed is when it entered Healdan.

**Calls** (the owner confirmed "that looks correct").

- A filed email becomes a document with its own doc #.
- It inherits the matter's properties like any other document.
- From, to, cc, subject and sent date become searchable properties. They power the Emails and Docs & Emails search scopes.
- Filing the same email twice is detected by its Message-ID.

**Notes.**

- **One Outlook add-in.** A single Outlook web add-in (Office.js) runs in Outlook for Mac, on the web and on Windows, so it covers both of the owner's mail setups. It can fetch the current message as `.eml` along with its attachments. Office.js has no "bar below the ribbon"; the closest equivalent is a task pane that can be pinned open.
- **Requirements.**
  - The server must be reachable over HTTPS from wherever Outlook runs.
  - M2's CORS and token authentication.
  - M1's email indexing fix.
- **What CE has.** CE already parses EML and MSG, shows email previews, and can save an attachment as a document. Document links exist and can carry the "related" relationship.

**Non-goals.**

- Importing from IMAP or POP mailboxes.
- A Thunderbird add-on.

---

### M6 — New web UI: User app

**Intent.** A modern web UI for everyday work, built without Java and branded Healdan.

**Decided (owner).**

- **Frame of reference: iManage Work (cloud).** Clean.
  - **Top bar.** A scope picker beside a prominent search box.
  - **Left navigation.** The search scopes (Documents, Emails, Folders, Clients), then Recent Matters, then My Matters as a tree that opens into each matter's folders, then My Favorites.
  - **Main area.** A breadcrumb showing the matter as `client.matter – name`, then the document list. Columns include Title, Version, Doc # and Edit Date.
  - **Right side.** A panel for faceted filters (date presets, file type, author, client, matter) or for the selected item's details.
- **Three separate units.** The new UI is split into **User**, **Admin** and **Walls**, the way iManage separates them. An average user never loads the Admin or Walls code.
- **Its own design session.** The full UI design happens in a separate session, with mockups.

**Calls.**

- **Stack:** React and TypeScript.
- **Hosting.** Each unit is served from the Healdan server at its own path, next to the old UI. The server checks roles on every request; splitting the apps doesn't provide security on its own.
- **Order.** The User app comes first, in M6. Walls comes with M7. Admin is in the backlog, and once it exists the old UI can be retired.
- **Upload.** Dragging and dropping files and emails into folders.

---

### M7 — Ethical walls and the Walls app

**Intent.** Keep people away from clients or matters they mustn't see, whatever other permissions say.

**Decided (owner).**

- Walls are set at the client or matter level.
- A wall overrides every user permission and every attempt to change permissions.
- A **wall admin** role can at least see all walls and manage who is on each one.
- Walls are managed in the new UI's Walls app.

**Calls.**

- Walls apply to admins too.
- Only the wall admin role can create or remove a wall, and every change is audited.
- The wall admin role doesn't grant access to walled content.
- **Two kinds of wall:**
  - exclusive: "these people can't enter";
  - inclusive: "only these people may enter".
- **Enforced on every route in:**
  - search results;
  - previews and thumbnails;
  - downloads;
  - WebDAV and CMIS;
  - the API and the add-ins.

**Facts from the recon.** CE's permissions can only grant access, never deny it, and admins skip all checks. Walls therefore need changes to core permission evaluation and to search filtering. The details are in the capability map. Expect edits to upstream files, and design them to be as small as possible.

## 5. Backlog

The backlog isn't in priority order. Promote an item into a milestone through a design session.

| Item | Source | Notes |
|---|---|---|
| **Healdan Share**: a client-facing portal | owner: ancillary project, not yet designed | Like iManage Share. A subset of Healdan documents and folders is published or synced to an external, internet-facing site for clients. It is the only piece meant to face the internet; the core server stays on the LAN or behind a VPN. Its design must assume a hostile network, and it probably runs as a separate deployment so it doesn't expose the core server. |
| S3, and a storage backend per Store | owner: later goal | CE has a single disk storage backend. It records no backend per document or version, and its "move files to another backend" path throws. The work: a storage backend that dispatches to the right place, recording where each version lives, an S3 implementation, and moving files when a workspace changes Store. |
| Bulk import/export with metadata | owner | Must set legacy doc #s and client/matter properties. |
| Admin app, then retiring the old GWT UI | owner (UI split) | Needs the full API inventory from M2. |
| Office add-ins on Windows | owner | The Outlook add-in already runs on Windows. |
| Rule-based email filing (Outlook folder → matter folder) | owner: later phase | |
| Redlining between versions | owner mentioned; timing is a call | Needs a comparison engine. |
| Two-factor for local accounts | call | CE has 2FA settings and columns, but nothing verifies the code. |
| LDAP / SAML | call | Only if an adopter needs them. |
| Editing in the browser | idea | Collabora Online or OnlyOffice over WOPI would avoid Microsoft's partner programme. |
| Importing from IMAP/POP mailboxes | idea | The GUI client code exists in CE; the server side doesn't. |
| Thunderbird add-on | idea | Only if the owner leaves Outlook. |

## 6. Engineering ground rules for every milestone

These are calls unless CLAUDE.md already says the same thing.

- **Plugin modules.** Use the four-piece plugin pattern in CLAUDE.md. List every upstream file a milestone edits in its spec, with the reason.
- **No reliance on UI gating.** Enabling a CE feature that is gated only in the GUI is cheap: declare its `Feature_<n>` extension in a Healdan plugin. Healdan's own rules are enforced on the server.
- **Schema upgrades.** From M1 on, the owner runs Healdan with real data, so every later schema change needs an upgrade path for an existing database.
  - Today nothing migrates existing databases. The Docker init creates the schema only on a fresh database.
  - The M1 design decides how Healdan's own tables get upgraded, e.g. versioned SQL scripts applied at startup. It must also say whether that mechanism touches upstream tables.
- **Tests run in CI for Healdan modules.** CI currently builds with `-DskipTests`, which proves nothing about behaviour.
- **Security fixes in upstream code.** Fix them in Healdan, keep each fix small so it merges cleanly, and consider reporting them upstream.
- **Keeping this file current.** After each milestone, update its status here and record any decision that changed.

## 7. Open decisions for the owner

These aren't needed before M1's design session. Settle them before outside adoption.

- **License.** Healdan's own new modules need a license. Upstream is LGPLv3.
- **Reporting upstream.** Whether, and how, to report the upstream security bugs to LogicalDOC.
