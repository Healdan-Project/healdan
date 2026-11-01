# Healdan roadmap

- **Date:** 2026-10-16
- **Status:** direction agreed with the owner in an interview session. M1 is done: the owner accepted it on 2026-10-22 ([spec](superpowers/specs/2026-10-17-m1-move-in-design.md), [plan](superpowers/plans/2026-10-17-m1-move-in.md)). M1.5 is done: the owner accepted it on 2026-10-23 ([spec](superpowers/specs/2026-10-22-m1-5-office-previews-design.md), [plan](superpowers/plans/2026-10-22-m1-5-office-previews.md)). M2 is done: the owner accepted it on 2026-10-24 ([spec](superpowers/specs/2026-10-23-m2-api-foundation-design.md), [plan](superpowers/plans/2026-10-23-m2-api-foundation.md)). M3's design and plan were approved on 2026-10-24, after a trial build, and its first phase, LibreOffice on macOS, was built on 2026-10-24 and 2026-10-25 and accepted by the owner on 2026-10-29, with Healdan Helper 0.3.0 released on 2026-10-30; phase 2, LibreOffice on Linux, waits for an Ubuntu 26.04 desktop ([spec](superpowers/specs/2026-10-24-m3-office-integration-design.md), [plan](superpowers/plans/2026-10-24-m3-office-integration.md)). M3.5, Microsoft Office on the Mac, went first and is done: its design was agreed, its trial run and its spec and plan approved on 2026-10-30, it was built on 2026-10-30 and 2026-10-31, and the owner accepted it on 2026-11-01, with Healdan Helper 0.4.0 released the same day ([spec](superpowers/specs/2026-10-30-m3-5-microsoft-office-design.md), [plan](superpowers/plans/2026-10-30-m3-5-microsoft-office.md)). M3.6, PDFs in CoffeePDF, was designed and planned on 2026-11-01 and built the same day, and the owner's acceptance walkthrough, then Healdan Helper 0.5.0, are next ([spec](superpowers/specs/2026-11-01-m3-6-pdfs-in-coffeepdf-design.md), [plan](superpowers/plans/2026-11-01-m3-6-pdfs-in-coffeepdf.md)). M4, OIDC sign-in, was designed with the owner on 2026-11-01, and its design spec and plan were approved the same day ([spec](superpowers/specs/2026-11-01-m4-oidc-sign-in-design.md), [plan](superpowers/plans/2026-11-01-m4-oidc-sign-in.md)); building it is next.
- **Companion:** [`docs/reference/logicaldoc-ce-capability-map.md`](reference/logicaldoc-ce-capability-map.md) records what LogicalDOC CE already has, what's hidden, half built or missing, with file paths.

This is a living document. It sets the direction and the order of work. It is not a build plan.

- **Before building:** every milestone gets its own design session first: brainstorm → design spec in `docs/superpowers/specs/` → plan in `docs/superpowers/plans/` → build. The spec names the docs pages the milestone adds or changes, and the plan has tasks for them.
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
   - Merge `upstream/master` regularly. The `upstream-sync` workflow opens a sync PR every Monday.
6. **Re-implement, never copy.** Features that LogicalDOC sells are rebuilt from observed behaviour, not from proprietary code. (owner's standing direction)
7. **Linux and macOS first.** Windows comes later, for adoption. (owner)
8. **One licence for the code.** All of Healdan's code, including its own modules, is under the LGPL-3.0, like upstream. The docs site's content is under CC BY 4.0. (owner, 2026-10-23)
9. **Open-source databases only.** Healdan runs on MariaDB today. PostgreSQL is to become its first-class database, and Healdan will move away from MariaDB in time. MySQL isn't supported and won't be added, and Oracle and SQL Server are gone completely. (owner, 2026-10-29 and 2026-10-30, [Healdan-Project/healdan#110](https://github.com/Healdan-Project/healdan/issues/110))

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
| M1.5 | Office previews and thumbnails | M1 | **done** 2026-10-23: [spec](superpowers/specs/2026-10-22-m1-5-office-previews-design.md); [plan](superpowers/plans/2026-10-22-m1-5-office-previews.md). Built 2026-10-22 in PR A (the server side) [Healdan-Project/healdan#48](https://github.com/Healdan-Project/healdan/pull/48) and PR B [Healdan-Project/healdan#49](https://github.com/Healdan-Project/healdan/pull/49) (the image, smoke checks and docs). The owner's acceptance walkthrough (the plan's Task 10) passed all five rows on 2026-10-23, on a stack upgraded from M1. It left no M1.5 punch-list items; its observations are in the plan's decision log and follow-ups |
| M2 | API foundation | – (M1 domain model for its matter endpoints) | **done** 2026-10-24: [spec](superpowers/specs/2026-10-23-m2-api-foundation-design.md); [plan](superpowers/plans/2026-10-23-m2-api-foundation.md). Design and plan approved 2026-10-23 ([Healdan-Project/healdan#57](https://github.com/Healdan-Project/healdan/pull/57)). Built in PR A (the sign-in throttle, device tokens and the API's frame) [Healdan-Project/healdan#62](https://github.com/Healdan-Project/healdan/pull/62), PR B (finding things) [Healdan-Project/healdan#63](https://github.com/Healdan-Project/healdan/pull/63), PR C (properties, filing, check-out and saves) [Healdan-Project/healdan#64](https://github.com/Healdan-Project/healdan/pull/64) and PR D (the Docker flow test, the docs and the final review's fixes) [Healdan-Project/healdan#66](https://github.com/Healdan-Project/healdan/pull/66). The owner's acceptance walkthrough (spec §14.2) passed on 2026-10-24, on a stack built from PR D. What's left open is in the plan's follow-ups; none of it blocks M3 |
| M3 | Office integration: LibreOffice, through the Healdan Helper | M2 (M4 for single sign-on) | **phase 1 accepted** 2026-10-29: [spec](superpowers/specs/2026-10-24-m3-office-integration-design.md); [plan](superpowers/plans/2026-10-24-m3-office-integration.md). Design and plan approved 2026-10-24 ([Healdan-Project/healdan#69](https://github.com/Healdan-Project/healdan/pull/69)), after a trial build on the owner's Mac (spec §12.5). Phase 1, LibreOffice on macOS, built 2026-10-24 and 2026-10-25 in PR A (the API's additions) [Healdan-Project/healdan#70](https://github.com/Healdan-Project/healdan/pull/70), PR B (Open in App in the web page) [Healdan-Project/healdan#71](https://github.com/Healdan-Project/healdan/pull/71), PR C (the helper's core, and CI for the clients) [Healdan-Project/healdan#72](https://github.com/Healdan-Project/healdan/pull/72), PR D (the helper app, its windows, the LibreOffice extension and the macOS package) [Healdan-Project/healdan#73](https://github.com/Healdan-Project/healdan/pull/73) and PR E (the docs and records) [Healdan-Project/healdan#74](https://github.com/Healdan-Project/healdan/pull/74), with a final review's fixes in PR D. The owner's acceptance of phase 1 (spec §14.2, the plan's Task 21) passed on 2026-10-29, and the signed, notarised Healdan Helper 0.3.0 was released on 2026-10-30 ([`helper-v0.3.0`](https://github.com/Healdan-Project/healdan/releases/tag/helper-v0.3.0), [Healdan-Project/healdan#75](https://github.com/Healdan-Project/healdan/issues/75)). Phase 2, LibreOffice on Linux, is next: the semi-production server it runs against has been up since 2026-10-26, and its by-hand rows need an Ubuntu 26.04 desktop. The helper's and the extension's look was polished on 2026-10-27 ([spec](superpowers/specs/2026-10-27-helper-ui-polish-design.md)), in [Healdan-Project/healdan#93](https://github.com/Healdan-Project/healdan/pull/93) and [Healdan-Project/healdan#95](https://github.com/Healdan-Project/healdan/pull/95), and checked by the owner on the Mac on 2026-10-28. LibreOffice Draw was added for `.odg` drawings on 2026-10-29, in the helper and the extension ([Healdan-Project/healdan#101](https://github.com/Healdan-Project/healdan/pull/101)), then in the web page's Open in App and the server's previews, in a pull request of their own |
| M3.5 | Microsoft Office on the Mac | M3 | **done** 2026-11-01: [spec](superpowers/specs/2026-10-30-m3-5-microsoft-office-design.md); [plan](superpowers/plans/2026-10-30-m3-5-microsoft-office.md). Split from M3 on 2026-10-24 (owner: "MS Office follows later"), from M3's spec §7 and §12.5. Designed ahead of M3's phase 2, which waits for an Ubuntu 26.04 desktop (owner, 2026-10-30). Its trial (spec §8) ran on the owner's Mac on 2026-10-30, and the owner decided the three questions it raised. Design and plan approved 2026-10-30. Built on one branch in the plan's Tasks 1 to 11 and an added Task 9b, and merged as its pull requests A to E, [Healdan-Project/healdan#129](https://github.com/Healdan-Project/healdan/pull/129) to [Healdan-Project/healdan#133](https://github.com/Healdan-Project/healdan/pull/133). The owner's acceptance walkthrough (spec §12.2) ran on Healdan Helper 0.4.0-rc1 on 2026-10-31, and on rc2 and rc3 on 2026-11-01, when it passed. Its fixes are in [Healdan-Project/healdan#137](https://github.com/Healdan-Project/healdan/pull/137) and [Healdan-Project/healdan#139](https://github.com/Healdan-Project/healdan/pull/139), and two CI fixes in [Healdan-Project/healdan#138](https://github.com/Healdan-Project/healdan/pull/138) and [Healdan-Project/healdan#140](https://github.com/Healdan-Project/healdan/pull/140). The signed, notarised Healdan Helper 0.4.0 was released on 2026-11-01 ([`helper-v0.4.0`](https://github.com/Healdan-Project/healdan/releases/tag/helper-v0.4.0)). What's left open is in the plan's follow-ups and four issues, [Healdan-Project/healdan#134](https://github.com/Healdan-Project/healdan/issues/134), [Healdan-Project/healdan#135](https://github.com/Healdan-Project/healdan/issues/135), [Healdan-Project/healdan#136](https://github.com/Healdan-Project/healdan/issues/136) and [Healdan-Project/healdan#141](https://github.com/Healdan-Project/healdan/issues/141) |
| M3.6 | PDFs in CoffeePDF | M3.5; CoffeePDF's Phase 2 | **built** 2026-11-01, acceptance next: [spec](superpowers/specs/2026-11-01-m3-6-pdfs-in-coffeepdf-design.md), approved by the owner the same day. CoffeePDF v1's Phase 3, the Helper's side of [CoffeePDF](https://github.com/coffeemuse/CoffeePDF)'s spec §4.4, which left this milestone's name and number to this session. [Plan](superpowers/plans/2026-11-01-m3-6-pdfs-in-coffeepdf.md) written and approved the same day. Built on one branch in the plan's Tasks 1 to 9, each reviewed against the spec before the next began. The whole branch was then reviewed and its last fixes made, and its pull requests opened on 2026-11-01: [Healdan-Project/healdan#150](https://github.com/Healdan-Project/healdan/pull/150) (the Helper, its docs and the records) and [Healdan-Project/healdan#151](https://github.com/Healdan-Project/healdan/pull/151) (the web page's Open in App for PDFs, which merges after Healdan Helper 0.5.0-rc1). Healdan Helper 0.5.0-rc1 and the owner's walkthrough (spec §11.2) are next. What's left open is in the plan's follow-ups |
| M4 | OIDC sign-in | – (can run alongside others; land before M5) | **design and plan approved** 2026-11-01: [spec](superpowers/specs/2026-11-01-m4-oidc-sign-in-design.md); [plan](superpowers/plans/2026-11-01-m4-oidc-sign-in.md). Designed with the owner on 2026-11-01, question by question and section by section, and the spec and plan approved the same day. Building it is next, in the plan's pull requests A to E |
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

**Design.** Settled in the [M1.5 design spec](superpowers/specs/2026-10-22-m1-5-office-previews-design.md), which the owner approved on 2026-10-22. A preview is a quick check that it's the expected document (owner), so Healdan converts each new Office file in the background, shows tracked changes and comments (owner), and serves the result through a small hook in CE's `ConvertPdf`, which until now served only PDFs. LibreOffice adds about 410 MB to the image. The build departed from the spec in four main ways: comments show as PDF notes instead of in the margin, tracked changes are forced to show only for `docx`, `docm`, `dotx` and `dotm` (not for `.doc`, `.rtf` or OpenDocument files), the background worker takes the newest jobs first, and previews of a version fail fast after a recorded timeout. The code review of both PRs then made a replaced file or a raised timeout start a version's conversion over, and made thumbnails convert only in the background. The owner accepted M1.5 on 2026-10-23, after the acceptance walkthrough (spec §11.2). LibreOffice Draw's `.odg` drawings were added on 2026-10-29, with M3's Draw support, as the spec's non-goals foresaw: a line in the format list and Draw's package, which Impress's already brought in.

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

**Design.** Settled in the [M2 design spec](superpowers/specs/2026-10-23-m2-api-foundation-design.md), which the owner approved on 2026-10-23. The owner decided that M2's only consumer is M3: it's done when M3 can start, judged by tests and a docs page rather than a demo. No compatibility promises are made until the initial roadmap is complete. The spec answers the three questions above:
- **Code location:** M1's `com.logicaldoc.healdan` package, with the HTTP layer in a new `healdan-api` module.
- **Framework:** CXF JAX-RS, which the WAR already ships.
- **Push updates:** none in M2, with Server-Sent Events proposed for M6.

The owner also decided that sign-in is throttled on every route, which CE never actually did (spec §5.7). The [inventory](reference/healdan-api-inventory.md) of every server call the old UI makes is committed beside the spec.

**Built** on 2026-10-23 and 2026-10-24, in four PRs (#62, #63, #64 and #66), and accepted by the owner on 2026-10-24, with a docs page for client developers on the docs site. The build departed from the spec in a few ways, each marked "changed in the build" there. The main ones: the tests drive the real servlet through Spring's servlet mocks rather than CXF's local transport; only a password sign-in clears the throttle's failure counts, so CE's own clear in the form login is removed, one more upstream edit; a lock taken with the old UI's Lock action stops saves through the API even for the person who took it; `/search` never counts its results; uploads keep the old UI's size and extension limits; and password-protected documents can't be downloaded, checked out, saved or changed through the API, though their properties can be read. The build also found that Tomcat trusts forwarded addresses from every private network, so a machine on the LAN can dodge the per-address limit or claim the server's own address, which exempts `admin`. Its fix, "Harden the sign-in throttle behind the proxy", landed on 2026-10-24 in [Healdan-Project/healdan#68](https://github.com/Healdan-Project/healdan/pull/68): Tomcat now believes forwarded addresses only from the proxies in `LOGICALDOC_TRUSTED_PROXIES`, by default the app's Docker networks.

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
- **LibreOffice Draw (2026-10-29)** gets the same flow for `.odg` drawings, with no footer. Visio and Publisher files, which Draw opens but can't save, stay read-only in the default app.
- **Platform priority.**
  1. LibreOffice on Linux and macOS. (2026-10-24: macOS first, then Linux once the owner's semi-production server is running.)
  2. Microsoft 365 desktop apps on macOS. (2026-10-24: later, as M3.5.)
  3. Windows, later. Others will need it before they adopt Healdan.
- **A helper app on each computer (2026-10-24).** A small app in C# on .NET, with Avalonia for its windows, holds the sign-in, catches links from the web page, keeps local copies, and draws the Save, Open and Properties windows once for every app. The add-ins inside the apps stay slim, and nothing on the user's computer is Java.
- **Taking over Open, Save and Save As (2026-10-24),** with a way back to the app's own boxes; where an app won't allow it, Healdan's buttons sit beside its own.
- **The title bar and footer read `#1234 v2.1` (2026-10-24).**
- **Out of scope: editing in Word for the web.**
  - Microsoft requires its Cloud Storage Partner Program for this.
  - It's recorded as a known limitation.

**Calls.** (As the design session on 2026-10-24 settled them; the spec has the rest.)

- **A trial build first.** It ran on the owner's Mac before the spec was approved. The owner chose the helper's local copies over WebDAV, and the trial proved them in LibreOffice and, without its add-in, in Word (spec §12.5).
- **LibreOffice.** A Python UNO extension that runs the same on Linux and macOS.
- **Microsoft 365 on the Mac (M3.5).** The helper does most of the work through AppleScript, and the add-in is buttons and the footer. Sideloading didn't load it in Word 16.114, so it comes from the owner's Microsoft 365 admin centre. (2026-10-30: settled in [M3.5's section](#m35--microsoft-office-on-the-mac).)
- **Shared components:** dropped. The windows are the helper's (owner), not React components shared with M6.
- **Sign-in.** The helper exchanges Healdan credentials for M2's device token, kept in the system keychain. It moves to the OIDC provider's pop-up once M4 lands.

**Facts from the recon.**

- **WebDAV.** CE advertises no class-2 locking. LOCK is turned into a check-out with no lock token. Expect read-only opens or failed saves in Word and LibreOffice until that's fixed. macOS Finder also mounts such a share read-only.
- **CMIS.** CE has CMIS AtomPub 1.0, which LibreOffice's CMIS support uses. It hasn't been tested.

**Done when.**

- In LibreOffice on macOS (phase 1) and on Linux (phase 2), the owner can:
  1. create a document;
  2. Save to Healdan;
  3. close it and reopen it from Healdan;
  4. edit it;
  5. save a new version;
  6. insert the doc ID footer.
- The spec's §14.2 walkthrough is the full list. The same in Word for Mac is M3.5's.

**Design.** Settled in the [M3 design spec](superpowers/specs/2026-10-24-m3-office-integration-design.md), which the owner approved on 2026-10-24, with its [plan](superpowers/plans/2026-10-24-m3-office-integration.md), approved the same day for subagent-driven building.

- **The trial build.** It ran first, on the owner's Mac, and its results are in the spec's §12.5. The owner deferred its Ubuntu rows to phase 2, and its Word add-in rows to M3.5.
- **Server additions.** M2's API gains search within a folder, New Folder, favourites (CE's bookmarks), which computer holds a check-out, save preconditions, stamping the doc ID into stored files, thumbnails and the `#1234 v2.1` form.
- **Comments.** A searchable property, as in iManage's Save window (owner).
- **The web page.** Open in App is CE's hidden "Edit with Office", pointed at the helper.

**Built** on 2026-10-24 and 2026-10-25, phase 1 only, in five PRs (#70, #71, #72, #73 and [#74](https://github.com/Healdan-Project/healdan/pull/74)), with the user, install and developer pages on the docs site. The owner accepted phase 1 on 2026-10-29, after spec §14.2's walkthrough on the Mac against the owner's server over HTTPS, with no punch-list items. The walkthrough ran before [Healdan-Project/healdan#105](https://github.com/Healdan-Project/healdan/pull/105) cut the helper's macOS entitlements to `allow-jit`; the signed release candidate built after it then opened, saved and closed a document on the Mac, posted notifications and registered its login item. Healdan Helper 0.3.0, signed and notarised, was released on 2026-10-30. The build departed from the spec in a few ways, each marked "changed in the build" there. The main ones:

- **Saves are repeat-safe.** Each carries a `saveId`, so a save whose answer was lost isn't stored twice when the helper sends it again.
- **Another of your computers' check-out** answers `held-elsewhere`, ahead of `not-held`, and the helper knows its own holds by device id, not name.
- **Local copies keep the name they opened with** while they're open, at most 241 bytes so the record beside each fits, and the title moves on instead.
- **On macOS the extension is installed by one click.** LibreOffice's command-line installer never worked there, so the helper opens the extension in LibreOffice for the user to accept, and reminds them until they have.
- **The helper starts at sign-in** on macOS, as a login item, and files that LibreOffice doesn't edit open read-only in the Mac's default app.
- **Comments are searched with a database scan,** in the web page's Documents scope as well as the API's, which the capability map records.
- **A final review of the whole branch** led to one more round of fixes, recorded in the plan's decision log. A save made straight after Save to Healdan now counts as one; the title waits for the last save, through an outage of any length; links open only documents, spreadsheets and presentations, while other files open from the Open window, marked for Gatekeeper, and never apps or scripts; and copies Healdan can't take are listed in the helper's menu as Not Yet in Healdan.

What's left open is in the plan's follow-ups.

---

### M3.5 — Microsoft Office on the Mac

**Intent.** M3's flow in Word, Excel and PowerPoint for Mac, from Microsoft 365. The helper already does most of it for Word with no add-in (M3 spec §12.5); M3.5 adds the add-in M3's trial couldn't load, for the buttons, filing a new document and the doc ID footer.

**Decided (owner, 2026-10-30).**

- **Order.** M3.5 goes ahead of M3's phase 2, which waits for an Ubuntu 26.04 desktop.
- **A web add-in, from the admin centre.** Office.js, not VBA, deployed from the owner's Microsoft 365 admin centre (Integrated apps). Office's own Save, Save As and Open stay Office's, and Healdan's buttons sit beside them.
- **A Healdan tab of its own,** with the same six buttons as LibreOffice's toolbar.
- **Pairing.** The add-in can't read the helper's secret file, so each app pairs once, by typing a code the helper shows.
- **The scripting seam.** Everything that drives an Office app goes behind `IOfficeScripting`: AppleScript on macOS now, COM on Windows later. The add-in, pairing, the helper's port, the rename after each save and the copy records stay platform-neutral.
- **After the trial:**
  - **Set Up Microsoft Office.** Office's web view blocks plain HTTP to the helper, so the add-in's port is HTTPS, with a certificate the helper makes and the user trusts once.
  - **PowerPoint's copies keep the name they opened with.** Renaming one breaks PowerPoint's Cmd+S, so its title shows the version it opened as.
  - **The app's own format only** from Word, Excel and PowerPoint. Office turns the add-in off for OpenDocument files, and Excel and PowerPoint can't save them.

**Calls.** The add-in is buttons and the helper does the work, so Office without the add-in still gets opening, saving, the title and closing. The title moves on by renaming the copy after each save, only while the document has no unsaved changes. The add-in's pages and a manifest made with the server's own address are served at `/api/addin/`.

**Done when.** The spec's §12.2 walkthrough passes in Word, Excel and PowerPoint against the owner's server: install and pair, Save to Healdan, saves and closing, versions and the footer, opening, a crash and an outage, Save as New Document, and Excel and PowerPoint.

**Design.** The [M3.5 design spec](superpowers/specs/2026-10-30-m3-5-microsoft-office-design.md), agreed with the owner on 2026-10-30. Its trial ran the same day on the owner's Mac, before approval, as M3's did, and its results are in the spec's §8.5: the add-in loads from the admin centre and reaches the helper over HTTPS, and Word and Excel pass every row run. The owner approved the spec and its [plan](superpowers/plans/2026-10-30-m3-5-microsoft-office.md) the same day, for subagent-driven building.

**Built** on 2026-10-30 and 2026-10-31, on one branch, in the plan's Tasks 1 to 11 and a Task 9b the build added, with the install, user and developer pages on the docs site, and merged as the plan's pull requests A to E (#129 to #133). The tests use fakes, and the AppleScripts were compiled and checked against the apps' dictionaries, but until the owner's walkthrough nothing built had driven Word, Excel or PowerPoint, trusted the certificate or loaded the add-in in Office. The build departed from the spec in a few ways, each marked "changed in the build" there. The main ones:

- **Save As never takes the wrong window.** It never falls back to the front window, whoever calls it. Filing fingerprints the document before anything else, and a crashed copy's recovered window is saved back only once Word lists it by the copy's path.
- **A rename is never taken for a close.** The copy's session is re-keyed, the close watcher lets go first, and a rename Word finishes late is followed, not checked in.
- **The add-in's port lives as long as the helper,** signed in or not, and keeps every request short: a route over 20 seconds answers `202`, and the add-in collects its outcome through `result`.
- **The certificate** is checked by Security.framework's TLS evaluation, which sees what .NET's chain check can't, and only Set Up renews it.
- **Pairing happens in a window the button opens,** which hands the key back to the button; nine wrong codes in a row pause it for 10 minutes.
- **Settings find the apps through LaunchServices** as well as the usual folders.

**Accepted** by the owner on 2026-11-01, after spec §12.2's walkthrough in Word, Excel and PowerPoint against the owner's server over HTTPS, on Healdan Helper 0.4.0-rc1 (2026-10-31), then rc2 and rc3 (2026-11-01). Its main fixes, each marked "changed by the owner's walkthrough" in the spec:

- **Word opens copies with `open -a`,** as Excel and PowerPoint do, so a read-only copy or a fresh download meets no Grant File Access prompt ([Healdan-Project/healdan#137](https://github.com/Healdan-Project/healdan/pull/137)).
- **Save to Healdan on a Healdan document saves it,** as Cmd+S does, rather than filing it again (#137).
- **A crashed copy counts as back once Word runs again,** as Word restored its window without writing the owner file again, and Reopen opens the copy only if Word brings nothing back, so one window opens, not two (#137).
- **Each app's first script waits for macOS's Automation question,** up to 300 seconds (#137), and asks the app something only it answers, so the question comes up while it waits ([Healdan-Project/healdan#139](https://github.com/Healdan-Project/healdan/pull/139)).
- **Word turns its ribbon off for a read-only copy,** so the docs give Word's route for filing changes from one, and a smoother route is [Healdan-Project/healdan#141](https://github.com/Healdan-Project/healdan/issues/141).

The signed, notarised Healdan Helper 0.4.0 was released on 2026-11-01 ([`helper-v0.4.0`](https://github.com/Healdan-Project/healdan/releases/tag/helper-v0.4.0)). What's left open is in the plan's follow-ups.

### M3.6 — PDFs in CoffeePDF

**Intent.** The Healdan Helper opens PDFs in [CoffeePDF](https://github.com/coffeemuse/CoffeePDF), the owner's PDF app for macOS, and runs M3's cycle for them: checked out on open, a snapshot on each save, the title moving on, and checked in on close. CoffeePDF already speaks the Helper's channel, and never holds a token or calls the server. This is CoffeePDF v1's Phase 3, the Helper's side of its spec's §4.4.

**Decided (owner, 2026-11-01).**

- **Nothing changes until CoffeePDF is installed with Healdan support on,** except four things: a link to a PDF opens it, read-only in the default app without CoffeePDF; `/v1/closed` deletes a default-app copy ([Healdan-Project/healdan#134](https://github.com/Healdan-Project/healdan/issues/134)); the channel's `readOnly` is true once a copy's saves stop going up; and `/v1/file` honours `app`, a bug fix.
- **No PDF chooser in the Helper.** CoffeePDF's own preference is the switch, and the Helper's Settings show a PDFs row as text.
- **The holder's computer is dropped:** the API shows a check-out's device only to the user who holds it.
- **The docs name CoffeePDF,** whose repository is public.

**Calls.** CoffeePDF joins M3.5's set of apps, with no recovery watch: a crash gets LibreOffice's crash window. PDFs don't become a kind of file. One list of each app's lock and owner files (`CopyNames.AppFiles`) serves deleting, renaming and setting copies aside; the stray sweep keeps its own (`IsAppFile`). No trial: CoffeePDF's Phase 2 ran against Healdan Helper 0.3.0-rc1, and the save by rename and a late lock file are tested automatically.

**Done when.** One walkthrough on a Healdan Helper 0.5.0 release candidate, against the owner's server: CoffeePDF's spec §2.5 (also its Phase 4), plus a crash, a Helper restart, CoffeePDF unavailable and a refused save. Then Healdan Helper 0.5.0, and the web page's Open in App offering PDFs.

**Design.** The [M3.6 design spec](superpowers/specs/2026-11-01-m3-6-pdfs-in-coffeepdf-design.md), agreed with the owner section by section on 2026-11-01, and approved the same day. Its [plan](superpowers/plans/2026-11-01-m3-6-pdfs-in-coffeepdf.md) was written and approved the same day, for subagent-driven building.

**Built** on 2026-11-01, on one branch, in the plan's Tasks 1 to 9, with the install, user and developer pages on the docs site. The plan's pull request A (the Helper, its docs and the records) and B (the web page's Open in App for PDFs, a commit of its own) are cut from the branch and are to follow, with the owner's go-ahead. The tests drive the real `CoffeePdfApp` with its launch and process check faked (`FakeCoffeePdf`), so nothing built has yet run against a real CoffeePDF: `open -b`, LaunchServices finding it and CoffeePDF's own `settings.json` wait for the owner's walkthrough. The build departed from the spec in a few ways, each marked "changed in the build" there. The main ones:

- **The Open window's rule is tested in the core,** through `AppChooser.OpensReadOnly`, as the window has no headless test harness of its own.
- **`CopyCycle.ClosedAsync` says whether it had anything to do,** so the channel logs a `/v1/closed` it dropped.
- **`HealdanOpenInAppTest` joins `UPSTREAM_TESTS`,** as tests in `logicaldoc-gui` run in CI only when named there. Still no upstream code changes.
- **CoffeePDF's names are in `CopyNames`:** `CoffeePdfLockName`, `AppFiles` (every app's lock and owner files, for deleting, renaming and setting copies aside) and `IsCoffeePdfTemporary`. `IsAppFile`, for the stray sweep, is kept apart from `AppFiles`, so a new app adds its names to both.
- **The Open window has two notes,** a PDF's and today's. An office file on a Mac with neither LibreOffice nor Office gets today's, which doesn't fit it, and a third wording is a follow-up.
- **More docs pages changed than the spec listed,** among them a crash in CoffeePDF, in When Things Go Wrong.

The whole branch was reviewed, and its pull requests, #150 and #151, opened on 2026-11-01. Next: Healdan Helper 0.5.0-rc1, the owner's walkthrough on it (spec §11.2) and Healdan Helper 0.5.0. What's left open is in the plan's follow-ups.

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
- **Add-ins** sign in through the same provider in a pop-up. (2026-11-01: the add-ins never sign in, as they hold no token and talk only to the helper, so the helper signs in, through the provider in the system browser. M4 spec §1, §6.)
- **Not planned.** LDAP and SAML, unless an adopter needs them.

**Facts from the recon.**

- CE's `Authentication` extension point and its authenticator chain cover every channel. Everything goes through `SessionManager.newSession`.
- OIDC is a redirect flow, though, so it also needs a hook into Spring Security, which is configured in `context-security.xml`. That file is upstream's. (2026-11-01: not needed. M2 put `/api/**` outside Spring Security, so Healdan's own endpoints there run the redirect and set the same `ldoc-sid` cookie the password form sets. M4 spec §3.1.)
- No OAuth2 or OIDC libraries are on the classpath yet.

**Timing.** The core server is LAN or VPN only (owner), so CE's broken two-factor isn't urgent, and M4 doesn't have to jump ahead of M2 and M3. Revisit this if the core server ever faces the internet.

**Design.** Settled in the [M4 design spec](superpowers/specs/2026-11-01-m4-oidc-sign-in-design.md), which the owner approved on 2026-11-01, with its [plan](superpowers/plans/2026-11-01-m4-oidc-sign-in.md), approved the same day. The owner decided:

- **Passwords.** Once a provider is on, only accounts that aren't linked to it keep a password: `admin`, and any local account an admin keeps on purpose. Provider accounts have no password on any route, so they don't use WebDAV, CMIS, SOAP or REST with one.
- **Linking.** A first sign-in links to an existing account by email, where the provider's emails can be trusted (on in the Entra preset, off in Authentik's), never to `admin`. Otherwise it makes a new account.
- **Groups.** An explicit map from provider groups to Healdan groups, synced at every sign-in. A sign-in that maps to no Healdan group is refused.
- **Leavers.** Healdan keeps the provider's refresh token for a helper sign-in, and checks with the provider when the token's session reopens and at least every 8 hours. A refusal revokes the token; an outage signs nobody out.
- **Settings and the page.** Providers are set up in a Sign-In tab in Healdan Settings. The sign-in page shows a button per provider, with the password form behind a link, and both labels are settings.
- **Unlinking and break glass.** An admin can unlink an account, which goes back to a password and keeps everything it held. `admin` is the break-glass account, and an email alert when it signs in with its password is an optional setting.
- **Testing.** Acceptance on the owner's Entra tenant and the owner's own Authentik.
- **The approach.** Healdan's own endpoints under `/api/signin/`, with the Nimbus OAuth 2.0 / OpenID Connect libraries (Apache 2.0). No edit to `context-security.xml`.

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
- **Double-click opens a document in its app (2026-10-24),** through M3's helper. The old UI keeps double-click as preview, with Open in App beside it.
- **No full Store browsing (2026-10-23).** Users start from My Matters, Recent Matters or a search, never from a list of every matter in a Store, the way a big database starts from a query. A busy Store holds hundreds of matters in one flat list, and that's a non-issue because browsing it isn't offered. The M2 API lists a Store's matters only for admins, for the admin side (M2 spec §6.2).

**Calls.**

- **Stack:** React and TypeScript.
- **Hosting.** Each unit is served from the Healdan server at its own path, next to the old UI. The server checks roles on every request; splitting the apps doesn't provide security on its own.
- **Order.** The User app comes first, in M6. Walls comes with M7. Admin is in the backlog, and once it exists the old UI can be retired.
- **Upload.** Dragging and dropping files and emails into folders.

**Inputs from M2.**

- **Push updates.** M2 has none. Its spec proposes Server-Sent Events for M6.
- **Performance.** Folder listings load the whole folder for each page, and full-text search tops out at about 1000 engine hits. The [M2 plan](superpowers/plans/2026-10-23-m2-api-foundation.md)'s follow-ups have the details.

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
| PostgreSQL as the first-class database | owner, 2026-10-30 ([Healdan-Project/healdan#110](https://github.com/Healdan-Project/healdan/issues/110)) | Healdan runs on MariaDB only today, and moves away from it in time (principle 9). The WAR already ships PostgreSQL's driver, but Healdan refuses to start on it. The work: Flyway migrations for the `hd_` tables in a `postgresql/` folder beside `mariadb/` and `hsqldb/`, with `HealdanMigrator` recognising PostgreSQL; CE's schema on PostgreSQL (it ships no `.postgresql` variant, so its generic scripts need checking) and Healdan's own SQL checked there; a compose option, with `DockerInit` and the backup and restore steps to match; CI coverage, at least the smoke test on PostgreSQL; and a path for existing MariaDB installs to move their data across. Needs its own design session. |
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
  - Settled in M1 ([spec](superpowers/specs/2026-10-17-m1-move-in-design.md) §12): Flyway upgrades Healdan's own `hd_` tables on every start, from versioned scripts that never change once shipped.
  - Healdan never changes the structure of CE's `ld_` tables, and nothing migrates them on an existing database: the Docker init creates CE's schema only on a fresh one. An upstream release that adds SQL to CE's tables needs it applied by hand, so check each upstream sync for SQL changes.
- **Tests run in CI for Healdan modules.** CI's `build` check runs every test class in a `healdan-*` module, plus the upstream tests that cover Healdan's edits to upstream code (the workflow's `UPSTREAM_TESTS`), in parallel shards. A change that edits upstream code adds its test class there.
- **Security fixes in upstream code.** Fix them in Healdan, keep each fix small so it merges cleanly, and consider reporting them upstream.
- **Keeping this file current.** After each milestone, update its status here and record any decision that changed.
- **Docs ship with the code.** A PR that changes behaviour a manual describes, or adds behaviour a manual should describe, updates the docs site (`site/`, published at docs.healdan.com) in the same PR. (owner, 2026-10-23) The site's [spec](superpowers/specs/2026-10-23-docs-site-design.md) and [plan](superpowers/plans/2026-10-23-docs-site.md) are tracked in [Healdan-Project/healdan#56](https://github.com/Healdan-Project/healdan/issues/56).
  - The site has been live since 2026-10-23, and the app's and the repo's links have pointed at it since 2026-10-24 ([Healdan-Project/healdan#61](https://github.com/Healdan-Project/healdan/pull/61)).
  - 21 pages, all about CE features Healdan uses unchanged, are still interim pages that link to LogicalDOC's. The feature PRs that touch them replace them, and #56 closes when none are left. (M3 replaced Checking Out and Checking In, and Bookmarks.) The [demo stack and docs gaps spec](superpowers/specs/2026-10-29-demo-stack-and-docs-gaps-design.md) plans the rest.

## 7. Open decisions for the owner

- **Reporting upstream.** Whether, and how, to report the upstream security bugs to LogicalDOC. Settle it before outside adoption.
