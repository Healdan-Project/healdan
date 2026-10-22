# M1 "Move in": design

- **Milestone:** M1 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-10-17
- **Status:** approved by the owner on 2026-10-17, and amended after the owner reviewed mockups of the PR D screens on 2026-10-21 (client names in matter lists, §8.1 and §8.2) and decided the New Matter dialog pre-selects the clicked Store, with no default Store setting (§4, §5.3, §8.6). Implementation plan: [`docs/superpowers/plans/2026-10-17-m1-move-in.md`](../plans/2026-10-17-m1-move-in.md). M1 was built by 2026-10-21 and awaits the owner's acceptance walkthrough (§14.2). §13 now lists the upstream edits as built, with the plan's corrections folded in.
- **Inputs:**
  - the roadmap's M1 section and glossary;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md);
  - a design interview with the owner on 2026-10-17;
  - three code surveys of this repository at `85bce449c`. File and line references come from them; line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks a proposal the owner heard and didn't object to. Anything unmarked follows from those two, or is detail the builder can change without changing what gets built.

*Store*, *workspace* and *template* each mean something different in Healdan and in LogicalDOC (see the roadmap glossary). This spec says "LogicalDOC workspace", "CE template" and so on whenever it means LogicalDOC's sense.

## 1. Intent

The owner moves their own work into Healdan and keeps using it. Two things block that today: **document numbers** and **matter workspaces**. M1 delivers both, plus the minimum around them (search by number and by matter, My Matters, email dates, full-text indexing for Office and email files), inside the existing LogicalDOC UI. (owner)

The bar is iManage muscle memory. If finding a document by number, or a matter by client or matter, takes convoluted search criteria, Healdan has failed, even if the feature technically works. (owner, roadmap principle 2)

The old UI is interim. M6 replaces it, so M1 changes it only as much as it needs: "minimal within reason". (owner)

## 2. Goals, non-goals, anti-goals

### Goals (M1 is done when)

On a fresh Docker install, the owner, signed in as an ordinary **non-admin** user except where a step says admin, can do all of the following.

1. **Set up (admin).** Create Stores, create client 998 "Healdan", build a workspace template, and create matter 998.1 "Healdan Demo" from it. The template has:
   - three folders with different permissions (§5.3);
   - the two search folders (§5.4).
2. **File.** Upload documents and `.eml`/`.msg` files into the matter's folders through the browser. Each document gets the next doc #, inherits client, matter and Class, and shows its Date filed. Each email shows From, To, CC, Subject, Sent and Received.
3. **Look up by number.** Typing `#n` in the search box and pressing Enter returns just that document. From the result you can open, preview, download, change permissions, edit properties and see versions.
   - `#n.N` and `#n.N.M` reach a version or a snapshot.
   - A matter number in the Matters scope jumps to its workspace.
4. **Filing rule.** No document can be saved at a matter's top level, into a search folder, or outside a matter, by any route: web UI, REST, SOAP, WebDAV or CMIS.
5. **Permissions.**
   - Only admins can add, rename, move or delete a matter's top-level folders.
   - A folder can't grant more than its parent (§7.3).
6. **Navigation.** My Matters and Recent Matters work, and so do the Recent Work and My Recent Work search folders.
7. **Full text.** Text inside `.docx`, `.xlsx`, `.pptx`, `.eml` and `.msg` files is found by full-text search.
8. **Upgrades.** Healdan's data survives an upgrade. Healdan's schema is versioned and upgrades itself on startup (§12), and the mechanism is tested.
9. **Rebrand.** The browser title, login page and logo say Healdan.

### Non-goals (from the roadmap, unchanged)

- The new UI (M6).
- Office previews and thumbnails (M1.5).
- S3, and a storage backend per Store.
- Office add-ins.
- Email filing from Outlook (M5).
- OIDC.
- Ethical walls (M7).
- A bulk import tool.

### Also not in M1 (decided in this session)

- **Trees in the left pane.** My Matters and Recent Matters don't expand into folder trees. They're lists that jump to the matter in the folder tree. (owner chose option A: "for now in the old UI")
- **Legacy doc # via `#`.** `#` never matches a legacy doc #. Legacy numbers are found through advanced search, e.g. "Legacy Doc Number equals 4521876". A dedicated legacy lookup is a UI/UX question for M6. (owner)
- **Reserved number ranges.** The owner keeps clients 1–999 for internal work, but that's their habit, and Healdan doesn't enforce it. (owner)
- **Changing a matter after creation (call).**
  - A matter's client and number can't change after it's created. To renumber, create a new matter and move the documents.
  - Closing or archiving matters is later work.
- **CE's own folder templates (call).** CE's folder-template admin screen (feature 42) and its empty server stubs stay unused (§5.2).
- **Saved searches (call).** Saved searches (feature 12) stay off. Search folders cover the matter-scoped need.
- **Inheritance markers in the UI (call).** The Profile tab doesn't show whether a value is inherited or overridden. The data is there for M2 and M6.
- **Translations.** Healdan's own UI strings are English only.
- **Expiring grants.** Temporary access to a document is granted and removed by hand. Grants that expire on their own are later work.

### Anti-goals

- **Lookups that need building.** Finding a document by number, or a matter by number, must never mean building a search. (owner)
- **Rules only the GUI enforces.** CE gates features only in its GUI. Every Healdan rule is checked on the server. (roadmap principle 4)
- **Breaking upstream merges.** No large rewrites of upstream files. Every upstream edit is listed in §13. (roadmap principle 5)
- **A doc # that changes.** Once assigned, a doc # never changes and is never reused, through any operation and any CE bug (§6.3).

## 3. Constraints

- **Stack.** LogicalDOC CE 9.3.1 on JDK 21, Tomcat 11 and MariaDB 11.4, using the Docker stack in `compose.yaml`. A single node, reached only on the LAN or over VPN. (owner, existing stack)
- **Data volume.** A few thousand files, uploaded by hand through the browser. (owner)
- **Scale.** Doc #s must reach at least 999,999,999. The owner has seen an iManage install at 15,000,000,000. (owner)
- **One tenant.** Healdan uses only LogicalDOC's default tenant. (roadmap glossary)
- **Admins.** CE admins bypass every permission check. The owner's day-to-day account is not an admin. (roadmap call)
- **Real data from day one.** From M1 on, the owner runs Healdan with real data, so every later schema change needs an upgrade path. (roadmap §6)

### Facts from the code that shape the design

- **No upgrade path for plugin schemas.**
  - Plugin `DbInit` SQL runs only from the setup wizard (`SetupServiceImpl`) or from `DockerInit` on an empty database.
  - CE has no schema-upgrade mechanism, and no Flyway or Liquibase.
- **Plugin `install()` timing.**
  - It runs once per install id, guarded by a marker file in `conf.plugindir`.
  - It runs **before** the Spring context exists, so it can use only JDBC.
- **`addServlet` doesn't survive Docker upgrades.**
  - `LogicalDOCPlugin.addServlet` rewrites `WEB-INF/web.xml`. In the Docker image, `web.xml` is rebuilt with every new image.
  - The install marker lives in the repository volume (`conf.plugindir` is under `/opt/logicaldoc/repository`).
  - So a servlet added at install time disappears after an image upgrade, and is never re-added.
- **Hard-coded scanning.**
  - Spring finds `@PluginContext` classes only under `com.logicaldoc` (`MainContext`).
  - Hibernate finds `@Entity` classes only under `com.logicaldoc` (`LocalSessionFactory`, hard-coded).
- **Document saves.**
  - Every document save goes through `HibernateDocumentDAO.store`, which calls `DocumentListener.beforeStore` before writing, with the target folder known.
  - Throwing a `PersistenceException` there vetoes the save, and the GUI shows the message.
- **What a veto can't undo.**
  - `PersistenceException` is a checked exception (`extends SQLException`).
  - The DAOs use `jakarta.transaction.Transactional`, whose attribute is `rollbackOn`, not Spring's `rollbackFor`.
  - `DocumentManager` isn't transactional, so each DAO call commits on its own.
  - `moveToFolder` removes the document from the search index and runs a JDBC update (`DocumentManager.java` ~933–949) **before** the `store` that a listener could veto.
  - Document `restore` moves the row with JDBC before calling `store`.
- **Folder saves.**
  - Folder create, move, rename and ACL saves go through `HibernateFolderDAO.store`, which calls `FolderListener.beforeStore`.
  - These skip the listeners because they're plain JDBC: `deleteTree` (every folder delete: GUI, SOAP/REST and WebDAV), `restore` (which accepts any parent), and `applySecurityToTree`.
- **Permission checks in CE's RPCs.** These validate only the session:
  - `FolderServiceImpl.saveACL` and `inheritACL`, and `DocumentServiceImpl.saveACL`, don't check SECURITY;
  - `FolderServiceImpl.rename` doesn't check RENAME;
  - `FolderServiceImpl.restore` checks nothing.

  SOAP/REST `create` makes a folder of whatever type the caller asks for. PR #15 fixes only the GUI `create`.
- **customId.**
  - `ld_document.ld_customid` is varchar(200), unique per tenant, indexed in Solr as an exact-match string, and copied onto every `ld_version` row.
  - The `customid` aspect is already on in `context.properties`, so CE doesn't overwrite it with a UUID.
  - Three CE behaviours change it, though:
    - a GUI check-in sends a null customId (feature 54 is off), and CE then assigns a UUID. This is inferred from the code, not observed;
    - deleting appends `.<id>`;
    - restoring never removes that suffix.
- **Version numbers.**
  - CE numbers versions `major.minor`.
  - Property edits, moves and renames bump the minor number. A check-in bumps the minor unless "major version" is ticked.
  - WebDAV PUT is always minor.
  - `fileVersion` moves only when the file changes.
- **Attribute indexing.** Only top-level STRING and USER attributes are indexed in Solr (exact, case-sensitive).
- **Metadata inheritance in CE.** `copyFolderMetadata` runs on every document save, but only fills blank document values from the folder, and later folder changes never reach the document.
- **Unused i18n extension point.** The `ResourceBundle` extension point does nothing. Relabelling a CE string means editing `logicaldoc-i18n/.../messages.properties`.

## 4. Stores

**What a Store is.** A Store is a LogicalDOC workspace: a folder of type 1 directly under the root. Its permissions are that workspace's ACL. (roadmap call)

**Managing Stores.**
- Admins create and rename Stores and set their permissions with CE's existing screens.
- Healdan declares `Feature_71` (MULTI_WORKSPACE) so the GUI offers "New workspace" to admins.
- PR [Healdan-Project/healdan#15](https://github.com/Healdan-Project/healdan/pull/15) already makes the server refuse workspace creation by non-admins.

**Which workspaces count as Stores.** Every LogicalDOC workspace is a Store except two:
- **"Default"** is CE's built-in workspace. CE won't let it be renamed or deleted. It holds nothing in Healdan: the filing rule (§7.1) already keeps documents out of it.
- **"Templates"** is created by Healdan on first start (§12.3). It holds workspace templates (§5.2), and only admins can see it.

**The Store for a new matter.** New Matter opens from a Store's context menu and pre-selects that Store; any Store can be chosen. There is no default Store setting. (owner, 2026-10-21; this replaces the default Store decided on 2026-10-17, because every way into the dialog already names a Store)

**Storage.**
- All Stores share CE's single storage backend.
- Moving a matter between Stores changes only the database, because files stay stored by internal ID. (roadmap call)

## 5. Clients, matters and workspaces

### 5.1 Clients and matters

**Clients.**
- A client has a **number** (a positive whole number) and a **name**.
- Only admins create clients and edit their names. Client numbers are unique.
- When creating a client, the admin dialog suggests the highest client number + 1.

**Matters.**
- A matter has: (owner)
  - a client;
  - a matter number (a positive whole number);
  - a name;
  - an optional description;
  - an owner (a user);
  - the Store it lives in;
  - the template it was created from.
- A matter **is** its workspace: exactly one folder in a Store. (roadmap call)

**Numbering modes.** These are set per install in Healdan Settings. (owner asked for both; the mechanism is a call)

| | **Per client** (default; the owner's) | **System-wide** |
|---|---|---|
| Unique across | the client's matters (998.1 and 999.1 can both exist) | all matters |
| Suggested number for a new matter | the client's highest matter number + 1, or 1 | the highest matter number in the system + 1 |
| Matters scope: `998.1` | jumps to the matter | jumps to the matter |
| Matters scope: `1` | lists every matter numbered 1 | jumps to the matter |

- **Switching from system-wide to per client** is always allowed.
- **Switching from per client to system-wide** is allowed only if no two clients share a matter number. Otherwise it's refused, and the refusal lists the clashes.
- **What's stored.** Both modes store the same data: a matter row pointing at a client row. The mode only changes the uniqueness check, the suggestion and the lookup.

**Display.**
- Numbers are shown unpadded: client.matter, then an en dash and the matter name, e.g. `998.1 – Healdan Demo`. The owner writes "C/M 998.1". (owner)
- The workspace folder's name is that display string, and Healdan keeps it in sync when an admin edits the matter name.
- Users can't rename a matter workspace folder directly. Healdan's FolderListener refuses it.

**Configurable label.**
- The word "Matter" in Healdan's UI comes from two settings, singular and plural. The defaults are "Matter" and "Matters"; an install can use "Project" and "Projects", for example. (roadmap call)
- The label is used in:
  - the scope picker;
  - the My/Recent sections;
  - the New Matter dialog;
  - Healdan Settings.
- "Client" isn't configurable.

### 5.2 Workspace templates

**What a template is (call).** A workspace template is an ordinary folder directly under the **Templates** Store. Admins build it with CE's normal tools:
- folders and subfolders;
- permissions;
- Profile values on folders, such as "Invoices" setting Class = Financial;
- search folders (§5.4).

Creating a matter **copies** the template folder into the chosen Store as the new workspace.

**Why this design:**
- CE's folder-template RPCs are empty stubs (`FolderServiceImpl.loadTemplates`/`saveTemplates`/`applyTemplate`).
- CE's template format is a flat list of paths. It can't express permissions, Profile values or search folders.
- Building a template from real folders needs no template editor, reuses screens CE already has, and matches how iManage treats template workspaces.

**What a copy includes:**
- the folder tree;
- each folder's own permissions;
- each folder's Profile values that were set on that folder (§6.6);
- search folders with their rules.

**What a copy doesn't include:**
- **Documents.** The filing rule already keeps documents out of templates.
- **Later template changes.** Changing a template doesn't touch workspaces already made from it. (roadmap call)

**Permissions inside templates.** A template names groups directly. The demo template uses "Everyone" (§7.4) and "IT". (owner)

### 5.3 Creating a matter

Admins only. The entry point is **New matter…** on a Store's context menu in the folder tree. The dialog asks, in order: (owner)

1. **Client.** Picked from the client list, with a **New client…** shortcut.
2. **Matter number.** Pre-filled with the suggestion (§5.1). It can be changed. The server rejects a duplicate under the current numbering mode, and the dialog shows the error.
3. **Name**, and an optional **description**.
4. **Store.** The Store whose menu opened the dialog is pre-selected, and any Store can be chosen (owner, 2026-10-21; §4).
5. **Workspace owner.** A user.
6. **Template.** Any folder directly under the Templates Store.

**On Create, the server does the following in one transaction:**
1. Creates the client/matter record.
2. Copies the template into the Store as the workspace folder, named per §5.1.
3. Stamps the workspace with client and matter (§6).
4. Gives the owner's personal group every permission on the workspace's ACL. (call: the owner "gets full rights on the workspace and is shown on it")
5. Checks the result against the permission rules (§7.3).

After that, admins adjust permissions with CE's Security tab as usual. (owner)

**Editing a matter.** **Edit matter…** (admins only) changes the name, description and owner.
- **Changing the owner** (call): the new owner's personal group gets every permission on the workspace (P2 is checked), and the old owner's entry is removed. The dialog warns about this first. An admin can grant the old owner access again afterwards.

**Deleting a matter** (call).
- An admin deletes the workspace folder with CE's normal delete. It goes to the trash, and its `hd_matter` row stays.
- The matter number stays **reserved for good**, even after the trash is emptied, like doc #s.
- A deleted matter disappears from the Matters and Clients scopes, My Matters and Recent Matters.
- Restoring the workspace from the trash brings the matter back as it was.

**The demo matter as the acceptance case.** The owner described this template and matter. (owner)

| Folder | Permissions (template) |
|---|---|
| *(workspace)* | Everyone: read. IT: read and modify. |
| Documents | inherits the workspace: Everyone read-only, IT can modify |
| Secured Documents | own ACL: IT only, read and modify |
| Emails | own ACL: IT only, read and modify |
| Recent Work | search folder (§5.4) |
| My Recent Work | search folder (§5.4) |

### 5.4 Search folders

**What it is.** A search folder is a folder inside a matter workspace or a template. Its list of documents comes from a rule, and it can't hold documents or subfolders. (owner)

**Where search folders can go (call).**
- Anywhere inside a matter workspace.
- At a matter's top level, only admins can create one, as with any top-level folder (§7.2).
- **New search folder…** and **Edit search folder…** appear on the folder context menu.

**The rule has these fields (call):**

| Field | Values | Recent Work | My Recent Work |
|---|---|---|---|
| Window | changed in the last *N* days | 30 | 30 |
| Whose changes | anyone / only the person viewing | anyone | only me |
| Kind | all / documents only / emails only | all | all |
| Class | optional | – | – |
| Subclass | optional | – | – |

**Scope.** Always the search folder's own matter workspace and everything in it. (roadmap call)

**What counts as a change.**
- These document history events, inside the window:
  - created (`STORED`);
  - new version or snapshot (`CHECKEDIN`);
  - properties edited (`CHANGED`);
  - renamed (`RENAMED`);
  - moved in (`MOVED`).
- CE's `lastModified` isn't used, because check-outs and locks also change it.
- With "only the person viewing", the event must be by the person viewing. One search folder therefore shows each user their own list. (owner)

**Evaluation and permissions.**
- The rule runs each time the folder is opened. Results are newest change first.
- Results include only documents the viewer can read.

**How it's built (call).**
- A search folder is an `ld_folder` row with **folder type 1001**, a Healdan constant documented in code, so the GUI, the data servlets and the listeners can recognise it without extra queries.
- Its rule lives in the Healdan table `hd_searchfolder` (§11).
- CE treats an unknown type as an ordinary folder.
- The builder must confirm that WebDAV and CMIS tolerate type 1001. They can show it as an empty folder.

## 6. Documents: numbers, versions and the profile

### 6.1 Doc # format

- A doc # is a positive whole number, displayed unpadded with no prefix: `1234`. (owner)
- It's stored as a Java `long`, which gives room for about 9.2 × 10¹⁸. (owner: "plan for at least 999,999,999")
- Numbering starts at **1**. (call)
  - An admin can raise the next number in Healdan Settings, for example to 1000 after the demo.
  - It can't be set at or below a number already issued.
- Numbers are never reused, even after deletion. Gaps can occur when a save fails after taking a number. (roadmap call, plus a call on gaps)

### 6.2 Where the doc # lives (call)

**Storage.** The doc # is stored in CE's **`customId`** field (`ld_document.ld_customid`) as decimal digits. Healdan's own counter issues the numbers.

**Why `customId`, not a new column:**
- **Already everywhere.** It's on every version row (copied by `Version.create`), in the Solr index, in the documents grid (`DocumentsListGrid` has a `customId` column), in the search hits grid, in `GUIDocument`, and in REST/SOAP (`getDocumentByCustomId`).
- **Fewer upstream edits.** A new column would need edits to `GUIDocument`, `DocumentServiceImpl`, the data servlets, the grids and REST.
- **Per-tenant uniqueness is enough.** `customId` is unique per tenant, and Healdan uses only one tenant. The counter also guarantees uniqueness by construction.

**The counter.**
- One row in `hd_counter` (name `docnumber`). The next number is taken with an atomic `UPDATE … SET next_value = next_value + 1` plus a read, in its **own short transaction** (a separate connection or `REQUIRES_NEW`).
- CE's `SequenceDAO` isn't used, because concurrent uploads in one JVM can read the same value before either commits.

### 6.3 Assigning and protecting the number

A Healdan `DocumentListener`, positioned after CE's listeners, enforces these rules in `beforeStore`.

**New documents** (`id == 0`):
- Take the next number, whatever customId the caller supplied (REST/SOAP can supply one).
- This covers uploads, API creates, WebDAV/CMIS creates, email-attachment saves, zip import and **copies**. CE nulls customId on copy, so a copy is a new document with a new number.

**Existing documents:**
- Re-read the stored customId with JDBC.
- If the incoming value differs, put the stored value back.
- This covers:
  - the GUI check-in bug that nulls it;
  - API updates that try to change it;
  - CE's delete, which appends `.<id>`. Healdan keeps the plain number on deleted documents, which is safe because numbers are never reused;
  - restore.
- Changing a doc # is silently ignored rather than refused, because CE's own GUI check-in sends a null.

**Shortcuts.** Document shortcuts (CE aliases) and folder aliases are **refused inside Stores** in M1. (call) An alias can't carry its target's number (the unique index), and an alias that crosses matters would break inheritance and permissions.

**Display:**
- The documents grid's and the hits grid's `customId` column is relabelled **Doc #**, and it sorts **numerically**.
- The Properties tab always shows Doc #, read-only.
- Feature 54 (CUSTOMID) stays **off**, because it would also expose CE's custom-ID scheme screens, which have no server code.

### 6.4 Versions and snapshots (owner)

**Numbering.** Healdan uses CE's version numbers as they are:
- The **major** number is the Healdan version: 2.0 is "version 2".
- The **minor** numbers are **snapshots** on that version's timeline: 2.1, 2.2.
  - Ordinary saves (a minor check-in, a WebDAV PUT) create snapshots.
  - So do property edits, moves and renames, as CE already does.
- Ticking "major version" at check-in starts the next version. The check-in dialog's label changes from "Major Version" to "New version (otherwise a snapshot)". (call)
- This matches the M3 plan: an ordinary save in Word is a snapshot, and "Save as new version" is a major check-in.

**Display (call):**
- The documents grid's Version column is CE's `fileVersion`, the version the file content was last saved as. That is what a footer stamp would show.
- The Versions tab is the timeline and lists every snapshot. Reverting to a snapshot uses CE's existing promote action.

**Footer form (M3).** The full number, e.g. `# 1234.2.1`. (owner)

### 6.5 Number lookup syntax

These work in the Documents scope (owner, with call details). Spaces after `#` are allowed.

| Input | Finds |
|---|---|
| `#1234` | document 1234, current version |
| `#1234.2` or `#1234v2` | version 2 as it stands, i.e. the highest snapshot whose major number is 2, compared numerically |
| `#1234.2.1` or `#1234v2.1` | exactly snapshot 2.1 |
| `1234` (no `#`) | an ordinary full-text search (call: numbers appear inside documents too) |

**How lookups work.**
- Lookups use the **database**, never the Solr index, so a document filed a second ago is found.
- A doc # the user can't read behaves exactly like one that doesn't exist: "No document #1234". The same goes for a missing version ("Document #1234 has no version 2.1").
- `#` never matches a legacy doc # (§2).

### 6.6 The profile (owner, with calls on the mechanics)

**One CE template.** Every document and folder inside a Store, and inside the Templates Store, uses a single CE template, **Healdan profile**, which Healdan creates on first start (§12.3).
- Healdan's listeners force that template onto documents and folders. On folders they also set `templocked`, which only folders have.
- Admins can add attributes to it later with CE's existing template editor. Inheritance works for them with no new code.

| Attribute (name) | Label | Type | Rules |
|---|---|---|---|
| `client` | Client | string, read-only | client number; always from the workspace; can't be overridden |
| `matter` | Matter (configurable label) | string, read-only | matter number; same rules as client |
| `author` | Author | user | defaults to the creator; editable; document only |
| `typist` | Typist | user | defaults to the creator; editable, for someone filing on another's behalf; document only |
| `docclass` | Class | string, list, **required** | admin-managed list; inherits; install default "Docs" |
| `docsubclass` | Subclass | string, list, optional | admin-managed list; each option belongs to one Class; inherits |
| `legacydocnum` | Legacy doc # | string | document only; set by import or typed in during manual migration |
| `emailfrom`, `emailto`, `emailcc`, `emailsubject` | From, To, CC, Subject | string | emails only (§6.8) |
| `emailsent`, `emailreceived` | Sent, Received | date | emails only (§6.8) |

**Class and Subclass.**
- They are CE attribute options (`ld_extoption`). Each Subclass option's `category` is its Class value.
- The GUI narrows the Subclass list to the chosen Class if CE's `dependsOn` supports that. Either way, the server rejects a Subclass that doesn't belong to the Class.
- Setups that don't use Subclasses simply leave the list empty. (owner)
- Admins maintain both lists with CE's existing attribute-options editor.

**Class default (owner).** Class is required. When nothing above a document sets a Class, it gets the install default, "Docs", a Healdan setting. That value counts as inherited, so a folder that later sets a Class passes it down.

### 6.7 Inheritance and overrides (owner, with calls on the mechanics)

**Terms.**
- *Inheritable* attributes are every Healdan profile attribute except the document-only ones (`author`, `typist`, `legacydocnum` and the email fields). That includes attributes admins add later.
- A value is **local** on an object when it was set there:
  - on a folder, it was *set on this folder* and passes down;
  - on a document, it's an *override*.
- Otherwise the value is **inherited**.
- Healdan records local values in `hd_local_value` (object type, object id, attribute name).

**Effective value.** An object's effective value for an inheritable attribute is:
- its local value, if it has one;
- otherwise its parent folder's effective value;
- for Class only, the install default when no ancestor sets one.

The chain stops at the matter workspace. `client` and `matter` are local on the workspace and on nothing else.

**Stamping.** Effective values are **copied onto every document and folder** (`ld_document_ext`, `ld_folder_ext`), so search is fast and each document carries its own profile. (roadmap call)

**When values are recalculated:**

| Event | What happens |
|---|---|
| Document created (upload, API, copy, any route) | It inherits every effective value from its folder; Author and Typist default to the creator. A value the caller supplies that differs from the inherited one becomes local; a blank or equal value inherits. (call: the listener can't tell a copy from an upload, and this needs no hook) So choosing a different Class in the upload dialog is an override, and a copy keeps a Class that differs from its destination's. |
| Someone edits a document's or folder's inheritable value | If the new value differs from what it would inherit, it becomes local. If it equals the inherited value or is cleared, the object goes back to inheriting. (call: this is how a user makes an override "explicit" in the old UI) |
| A folder's local value changes or is removed | The new effective value is stamped onto every descendant folder and document that doesn't have its own local value for that attribute. |
| Document moved | Every non-local value is recalculated from the new folder; client and matter always are. (roadmap call) |
| Folder moved | The same, for its whole subtree. |
| Workspace created from a template | Local values on template folders stay local; client and matter are set on the workspace. |
| Effective Class changes and the Subclass no longer belongs to it | The Subclass is cleared. |
| Someone edits `client` or `matter` | Ignored: they are put back from the workspace. |

**Propagation writes.**
- Propagation updates descendants' `*_ext` values directly in SQL and flags those documents for re-indexing (`ld_indexed = 0`).
- It must **not** create versions or snapshots or fire listeners. A re-stamp isn't a user edit and mustn't flood the timeline. (call)
- CE's `copyFolderMetadata` still runs first on every save. It only fills blanks, and Healdan's listener runs after it and sets the final values.

### 6.8 Dates and emails

**Date filed.**
- Date filed is CE's `creation` timestamp. (roadmap call)
- Healdan's listener sets it to "now" on every new document, overriding any value a REST/SOAP caller supplies. (owner: "always now")
- CE's GUI already shows it read-only. The label "Created" becomes "Date filed".

**Emails.** An email is a document whose file extension is `.eml` or `.msg`. (call)

**When an email is created, by any route:**
1. Parse it with CE's `MailUtil`.
2. Set `emailfrom`, `emailto`, `emailcc` and `emailsubject` (recipient lists comma-separated).
3. Set `emailsent` and `emailreceived`.
   - `.eml`: Received falls back to the `Delivery-Date` header.
   - `.msg`: CE sets both dates to the message date. The builder should use the MSG submit and delivery times if msgparser exposes them, and otherwise accept CE's behaviour.
4. **Name it after its subject.** CE 9.3 has no separate title (it's the file name without the extension), so the file name becomes the subject plus the original extension, e.g. `Re: Demo plan.eml`. (owner: "subject can become the default doc title")
   - Characters that aren't allowed in file names are replaced.
   - An empty subject keeps the uploaded name.
   - Users can rename the email afterwards.

These values are written during creation, so they don't add a snapshot.

## 7. Rules the server enforces on every route

All rules live in Healdan's `DocumentListener` and `FolderListener` (and one `UserListener`), so they apply to:
- the web UI;
- REST and SOAP;
- WebDAV;
- CMIS;
- zip import;
- Dropbox;
- email attachment saves;
- anything else that calls the DAOs.

**Admins are bound by them too**, except where a rule says "admins only".

### 7.1 Filing rule (owner)

A document may be created in, moved into, copied into or restored into folder *F* only if both of these hold:
- *F* is **inside** a matter workspace (a descendant, not the workspace folder itself);
- *F* is not a search folder.

That rules out:
- a Store's top level;
- the Default workspace;
- the Templates Store;
- a matter workspace's top level;
- search folders.

Also enforced:
- **Search folders hold no subfolders.** No folder may be created in, or moved into, a search folder.
- **Matters and the Templates Store.** Only the New Matter flow creates folders directly in a Store. Admins may create folders anywhere in the Templates Store.

### 7.2 Matter structure (owner, extended by a call)

**Top-level folders.**
- Only admins may create, rename, move or delete a folder directly under a matter workspace, including search folders. (owner: "only Admins can add root level folders"; call: rename, move and delete too, since the template's top level is the matter's skeleton)
- Below the top level, CE's normal permissions apply.

**The workspace folder.**
- It can't be renamed directly. Edit matter does that (§5.3).
- Only admins can delete it or move it to another Store.

**Folder types only Healdan creates.**
- Only admins can create type-1 folders (Stores).
- Type-1001 folders (search folders) can be created only through Healdan's own search-folder service.
- The FolderListener enforces both, which also closes the SOAP/REST `create` route that PR #15 leaves open.

**Deletes and restores need hooks.** Folder delete (`deleteTree`) and folder restore (`restore`) are plain JDBC and never reach the FolderListener (§3).
- M1 adds a small upstream hook at the start of each one, which runs Healdan's checks before anything is written.
- **Delete:** the top-level folder and workspace rules above.
- **Restore:** the filing and structure rules for the target parent, plus the re-stamping in §6.7. Also the permission check CE's RPC lacks: the user needs DELETE on the folder, the same right that deleting it needed.

### 7.3 Permissions: folders narrow, never widen (roadmap call, with calls on the mechanics)

**P1: inside a matter.**
- A folder with its own ACL may list only groups that its parent's effective ACL lists, each with the same or fewer permissions.
- The check is per group and per permission flag. It counts personal groups as groups, so the rule doesn't change when someone joins or leaves a group.
- It's checked on every ACL save, which goes through `HibernateFolderDAO.store` with event `PERMISSION`, and whenever `securityRef` changes. SOAP `update` can set `securityRef` while checking only RENAME.
- **When a parent is narrowed** (call), descendants with their own ACLs are narrowed automatically to stay within it. Refusing instead would make removing someone's access painfully hard.

**P2: matter versus Store.**
- Every group that a matter workspace's ACL gives READ must have READ on its Store.
- The owner's personal group is the one exception: it passes if the owner can read the Store.
- It's checked when a matter is created, when the workspace's ACL is saved, and when a matter moves to another Store.
- Without P2, a matter in a private Store such as "Personal" could be readable through search by people who can't see the Store.

**Who may change permissions (call).**
- CE's RPCs don't check this today (§3). M1 adds the checks:
  - changing a folder's or document's ACL, or making a folder inherit again, needs SECURITY on that folder or document itself;
  - renaming needs RENAME.
- **A restricted folder stays restricted.** CE's "apply to subfolders" (`applySecurityToTree`) deletes every descendant's own ACL and points them all at the parent's, which **widens** a restricted folder such as Secured Documents. Inside Stores, Healdan refuses it unless the user has SECURITY on every descendant that has its own ACL, or is an admin.
- So the matter owner, who has full rights on the workspace but none on Secured Documents, can't re-open Secured Documents.

**Document permissions: one-off grants stay possible (owner).**
- A document keeps LogicalDOC's behaviour. Its own permissions can grant access its folder doesn't. P1 and P2 apply to folders only.
- This matches iManage. Someone with no access to a folder can be granted one document, sometimes only for a while. They can't find it by browsing, but they can reach it by doc # or link.
- Granting access still needs SECURITY on that document (the check M1 adds).
- **What the builder must make work for a document-only grant:**
  - `#n` lookup finds the document;
  - from the result: open, preview, download, properties and versions.

  CE's search hit handling also fetches the hit's folder, and full-text search filters hits by readable folders, so both need checking.
- **Where those documents appear:**
  - in search folder results, if they match;
  - not in Recent Matters, which lists only matters whose workspace the user can open.

**Known limit.** CE has no deny entries and doesn't intersect ACLs along the path. P1 and P2 keep the ACLs consistent when they're saved. Group membership changes later aren't re-checked against P2. Real enforcement that overrides everything is M7 (ethical walls).

### 7.4 Everyone group (call)

CE has no group containing every user.
- Healdan creates an **Everyone** group on first start and adds every existing user to it.
- A `UserListener` keeps every user except read-only ones in it, on every save, so an admin can't take a user out. The owner settled this on 2026-10-22, after CE's user form and SOAP were found to drop a new user from the group (plan, R132). Deleting or renaming the group is refused (R137, R138).
- Templates use it for "public within the install" access, as in the demo.

### 7.5 A veto must leave nothing behind

**The problem.** A listener veto stops only the `store` it runs in (§3). Two cases need more:
- **Work committed before the store.** For example, `moveToFolder` has already removed the document from the index and run a JDBC update, and document restore has already moved the row.
- **Work in the same transaction as the store.** That covers anything Hibernate might still flush after a checked exception.

**What M1 does:**
- **Rules run before the side effects.** Where CE does work a veto can't undo, M1 adds a small upstream hook that runs Healdan's checks **before** those side effects. In M1 these are document `moveToFolder` and `restore`, and folder `deleteTree` and `restore` (§7.2).
  - Each hook is a call into a Healdan check. It's a few lines, and it doesn't reorder CE's code.
  - The builder designs the call shape, for example a small interface in a new core file that `healdan-core` implements.
- **Rollback for the rest.** Where a test shows a partial write within the store's own transaction, the fix is `rollbackOn = PersistenceException.class` on that DAO's `jakarta.transaction.Transactional`.
- **Proof.** For every rule and every route, a test shows that a refused operation leaves the database and the search index exactly as they were.

## 8. The existing GUI: what changes

The owner saw and accepted a mockup of these changes on 2026-10-17: the existing Documents screen, with the additions below.

### 8.1 Search box and scope picker

**The picker.**
- It sits left of the quick-search box (`QuickSearchTray`). (owner)
- Its choices are **Documents** (default), **Emails**, **Docs & Emails**, **Folders**, **Matters** and **Clients**, using the configurable matter label.
- It remembers the last choice for the session.

| Scope | Input | Behaviour |
|---|---|---|
| Documents | `#…` (§6.5) | number lookup; the result list contains just that document |
| Documents | anything else | full-text search, emails excluded |
| Emails | text | full-text search over emails only |
| Docs & Emails | text | full-text search over everything (today's CE behaviour) |
| Folders | text | CE's existing folder search, name contains |
| Matters | `998.1`, a bare number (§5.1), or text | number: an exact match jumps straight to the workspace (opens it in the folder tree); several matches are listed. Text: matters whose name or description contains it are listed |
| Clients | number or text | lists the matters of every client whose number or name matches (call: M1 has no separate client list) |

**Results.**
- They appear in CE's existing **Search** tab.
- Document hits get CE's full document context menu and details panel: properties, Profile, versions, history, security and preview.
- Matter hits are folder hits, and double-clicking one opens the folder.
- **Matter hits show their client (owner, 2026-10-21).** In the Matters and Clients scopes the results have a **Client** column with the client's name, next to the matter. The display `1001.1 – Leases` names only the client's number, so without it a search for "leases" can't tell Acme, Inc.'s matter from ABC Burgers'. The column holds the name alone, because the display already starts with the number.

**`#n.N` and `#n.N.M` results.** The result shows the document. Previewing or downloading **that version or snapshot** takes one click, for example by opening the Versions tab with that row selected. The builder chooses how.

**Server side (call).**
- The Documents-scope `#` lookup, the Matters and Clients scopes, and the documents-only and emails-only full-text scopes are Healdan `Search` implementations registered through CE's `Search` extension point, each with its own type number.
- `SearchServiceImpl` needs a small upstream edit: fall back to a default options converter (copying the common fields: type, expression, max hits, folder) for types without a registered converter. Today an unregistered type throws a NullPointerException.
- The emails-only and documents-only filters wrap CE's `FulltextSearch` with a filter on file extension. The builder chooses the mechanism, but must not edit `FulltextSearch` itself.

### 8.2 Left pane: My Matters and Recent Matters (owner: option A)

**Sections.** `DocumentsMenu` gets two new sections above **Browser**: **My Matters** and **Recent Matters**, using the plural label.

**My Matters.**
- It's the user's own list of matters, built on CE's **folder bookmarks**. (call: no new storage)
- It lists the user's folder bookmarks whose target is a matter workspace. Other bookmarks stay in the Bookmarks section.
- "Add to My Matters" and "Remove from My Matters" are on a matter workspace's context menu.
- Two CE bugs need small fixes:
  - `DocumentServiceImpl.addBookmarks` checks for duplicates by document ID, even for folders;
  - `BookmarksDataServlet` lists deleted folders.

**Recent Matters.** It lists matters where the user created or changed a document in the last 30 days: the same events as §5.4, from the document history CE already keeps (`ld_history`, mapped through `ld_folder.ld_path` to the matter workspace). (owner)
- Newest first, at most 20 entries. (call)
- The 30 days is a Healdan setting.

**Both lists:**
- Each entry reads `998.1 – Healdan Demo (Healdan)`: the matter's display, then its client's name in brackets, in slightly smaller, muted text (owner, 2026-10-21). The client name is there for reference, and the matter stays the focus. An entry too long for the pane is cut off at the end, so the client name goes first, and hovering shows the whole entry.
- Clicking it opens the matter in the **Browser** tree below, which shows its folders.
- They don't expand into trees in M1.

### 8.3 Folder tree, folder list and breadcrumb

- **Stores** show as CE workspaces at the top of the tree. Matters show by their display name.
- **Search folders** get their own icon, a Font Awesome Free icon (the CI icon check enforces this).
- **Opening a search folder** lists its results in the normal documents grid. The GUI asks Healdan for the result ids, and the grid uses `DocumentsDataServlet`'s existing `docIds` mode.
  - That mode doesn't load extended-attribute values today (`findDocumentsByIds`), so the Author and Class columns would be blank.
  - M1 makes it load them: a small upstream edit in `DocumentsDataServlet`.
- **Drag and drop.** The GUI may refuse a drop onto a search folder, matter workspace or Store, but the server refuses it regardless.

### 8.4 Documents grid and properties

**Default columns** for new users (call): Name, Version (`fileVersion`), Doc #, Edit Date (last modified), Size, Author and Class. That matches the owner's iManage screenshot.
- Healdan sets CE's `default.gui.document.columns` on first start.
- **Search results** show Doc # too, right after the file name (`default.gui.search.columns`). (owner, at the acceptance walkthrough on 2026-10-22; plan, R139)
- Users can still change their columns.

**Labels.** These are edits to `logicaldoc-i18n`'s `messages.properties`, `messages_en.properties` and `messages_en_US.properties`. The two English files are full copies that the GUI loads by locale, so all three need the edit. Other languages are unchanged.
- `customid` → "Doc #";
- `created` → "Date filed";
- `majorversion` → "New version (otherwise a snapshot)";
- the two "LogicalDOC" product strings → "Healdan".

**Properties tab.** It shows Doc # read-only. The Profile tab is CE's extended-attributes panel, showing the Healdan profile.

### 8.5 Dialogs

These are new Healdan GWT code:
- **New matter… / Edit matter…** (§5.3), on the folder context menu, admins only.
- **New client… / Edit client…**, reached from the New Matter dialog and from Healdan Settings.
- **New search folder… / Edit search folder…** (§5.4), on the folder context menu.
- **Add to / Remove from My Matters**, on the folder context menu.

### 8.6 Healdan Settings (admins)

One new panel under CE's **Administration** tab. It holds:
- the matter label, singular and plural;
- the numbering mode;
- the default Class;
- the Recent Matters window, in days;
- the next doc #, which can only be raised;
- the client list: add, and edit names.

### 8.7 Light rebrand (roadmap call)

**Product name.**
- A Healdan `SystemInfo` extension sets the product name, vendor and links.
- CE's `InfoServiceImpl` passes these to the browser title and the login page, so this part needs no upstream edit.

**Upstream edits:**
- **Logo and favicon.** The defaults in `GUIBranding.java`, `war/favicon.ico` and `war/skin/images/brand/logo*.png`.
- **Page title.** The `<title>` in `war/header.jsp`.
- **Display name.** `web.xml`'s `display-name`.

**Logo artwork.** It's an open item for the owner. The builder ships a plain "Healdan" wordmark until there is one.

## 9. Full-text indexing (roadmap call)

**First, confirm the gap.** On a running Docker instance, upload a `.docx` and an `.eml`, wait for indexing, and search for a word inside each. Record the result in the PR.

**The fix.** Add Healdan `Parser` extensions, which override CE's built-ins by extension:

| Extensions | How |
|---|---|
| `docx`, `docm`, `dotx` | Apache POI `XWPFWordExtractor` |
| `xlsx`, `xlsm` | Apache POI `XSSFExcelExtractor` |
| `pptx`, `ppsx` | Apache POI `XSLFPowerPointExtractor` |
| `eml`, `msg` | CE's `MailUtil`: headers (from, to, cc, subject, date), the body (HTML bodies stripped to text) and attachment file names |

**Notes:**
- POI 4.1.2 (`poi-ooxml`, `poi-scratchpad`) is already on the classpath. Healdan adds no new parser library.
- Parsers extend CE's `AbstractParser`, which applies CE's parse timeout.
- Indexing attachment *contents* is optional (builder's call). If it's done, it goes through the same parsers.
- Documents uploaded before the fix need re-indexing. A Healdan seed step (§12.3) flags existing documents of these types with `ld_indexed = 0`.

## 10. Code organisation (call)

### 10.1 The module

**One new plugin module, `healdan-core`.**
- Its JPF plugin id is `healdan-core`, and it depends on `logicaldoc-core`.
- It follows CLAUDE.md's four-piece plugin pattern:
  - `plugin.xml`;
  - a `LogicalDOCPlugin` subclass;
  - an assembly `assemblyplugin.xml`;
  - an `artifactItem` in `logicaldoc-webapp/pom.xml`.
- Like `logicaldoc-dropbox`, it depends on `logicaldoc-gui` (provided) for the RPC interfaces and GUI beans.

**Its `plugin.xml` declares:**
- `DocumentListener`, `FolderListener` and `UserListener`;
- the `Parser` extensions (§9);
- the `Search` extensions (§8.1);
- `SystemInfo` (§8.7);
- `Feature_71`.

### 10.2 Package root

**Server code: `com.logicaldoc.healdan`.**
- Spring (`MainContext`) and Hibernate (`LocalSessionFactory`) already scan `com.logicaldoc`, so this needs **no upstream edits**.
- The other hard-coded `com.logicaldoc` scans (history `@Table`, automation dictionaries, fillers) also see Healdan classes.
- `HealdanContext` is a `@PluginContext` with `@ComponentScan("com.logicaldoc.healdan")`.
- Choosing `org.healdan` instead would need an upstream edit to `LocalSessionFactory` or `context.xml` for entities, and a bridge class for Spring. The name is cosmetic, so it isn't worth an edit that recurs at every sync.

**GUI code: `com.logicaldoc.gui.frontend.client.healdan.*`.**
- This is new files inside `logicaldoc-gui`, because the GWT `Frontend` module only compiles sources under `com.logicaldoc.gui.frontend.client`.
- New files don't conflict in upstream merges. The edits to existing GUI files are only the hooks listed in §13.

### 10.3 Servlets and data access

**Servlets.**
- Healdan registers its GWT-RPC and data servlets with **`@WebServlet` annotations**, which `web.xml`'s `metadata-complete="false"` allows. It doesn't use `LogicalDOCPlugin.addServlet`, which the Docker upgrade problem in §3 rules out.
- RPC paths are `/frontend/healdan*` and data paths are `/data/healdan*`. The builder confirms that Tomcat 11 scans the plugin jar.
- If it doesn't, the fallback is static entries in the source `web.xml`, as Dropbox already has.

**Sessions.** RPC implementations validate the session the way `DropboxServiceImpl.validateSession` does, because plugins can't extend the webapp's `AbstractRemoteService`.

**Data access.** Entities and DAOs follow CE's pattern:
- `PersistentObject`;
- `XxxDAO` / `HibernateXxxDAO`;
- `XxxDAO.get()`.

Listeners are created by reflection, not Spring, so they look beans up through `Context.get(...)`.

## 11. Healdan data model

Tables are prefixed `hd_`. Entities that extend `PersistentObject` carry its standard `ld_id`, `ld_lastmodified`, `ld_creation`, `ld_recordversion`, `ld_deleted` and `ld_tenantid` columns.

| Table | Columns (beyond the standard ones) | Notes |
|---|---|---|
| `hd_setting` | `name` (PK), `value` | Healdan settings (§8.6), the Templates Store id, and seed markers (§12.3). Kept in the database, not `context.properties`, so they stay consistent with the rows they refer to. |
| `hd_counter` | `name` (PK), `next_value` bigint | `docnumber` (§6.2) |
| `hd_client` | `hd_number` bigint unique, `hd_name` | |
| `hd_matter` | `hd_clientid`, `hd_number` bigint, `hd_name`, `hd_description`, `hd_folderid` (workspace, unique), `hd_ownerid`, `hd_templateid` (the template folder's id, informational) | unique (`hd_clientid`, `hd_number`); the system-wide mode is checked in code |
| `hd_searchfolder` | `hd_folderid` (PK), `hd_days`, `hd_onlymine`, `hd_kind`, `hd_docclass`, `hd_docsubclass` | §5.4 |
| `hd_local_value` | `hd_objecttype` (D/F), `hd_objectid`, `hd_attribute`; PK on all three | §6.7 |

Healdan never changes the **structure** of LogicalDOC's tables.

## 12. Schema upgrades and first-start setup (call; roadmap §6 asks for this decision)

### 12.1 Mechanism

**Flyway** (Apache 2.0), embedded in `healdan-core`, manages **only Healdan's tables**.
- History table: `hd_schema_history`.
- Scripts: `classpath:healdan/db/migration/{vendor}/V<n>__<name>.sql`, for `mariadb` (production) and `hsqldb` (tests). MySQL can reuse the MariaDB scripts if Flyway's vendor mapping allows it.
- Other databases CE supports (Oracle, SQL Server, PostgreSQL) aren't supported for Healdan tables in M1.
- `baselineOnMigrate` is on at version 0, because the database already holds LogicalDOC's tables.

**When it runs.**
- A bean in `HealdanContext` runs Flyway when the Spring context starts, before Tomcat serves requests.
- If LogicalDOC's schema doesn't exist yet (no `ld_user`, i.e. a non-Docker install before the setup wizard has run), it logs and skips. It runs on the next start.

**Failure is fatal.** If a migration fails, startup fails and the error is logged. Healdan never runs against a half-migrated schema. This deliberately differs from CE's `DBInit`, which continues on error.

**No `DbInit` extension.** `healdan-core` declares none, so `DockerInit` and the setup wizard never create Healdan tables. Flyway owns them from the first start.

**Why Flyway.** It already provides ordering, checksums, a history table and locking. A home-grown runner would be about 150 lines to maintain. If the Flyway dependency conflicts with CE's libraries, the fallback is a minimal runner with the same file layout and history table.

### 12.2 Upstream schema changes

Upstream merges that change LogicalDOC's schema still need SQL applied by hand on existing databases, as CLAUDE.md says today. Healdan's mechanism doesn't change that; it's a later item.

### 12.3 First-start setup ("seed")

**Runs after the migrations.** Seeding touches LogicalDOC's own tables, so it's Java code using CE's DAOs, not SQL.

**Idempotent.** Each step records `seed.<name>` in `hd_setting` when it completes, and never runs again. It also never re-creates something an admin later deleted.

| Seed step | What it does |
|---|---|
| `everyone` | Creates the Everyone group and adds every user to it. |
| `profile` | Creates the Healdan profile CE template and its attributes (§6.6), the Class option "Docs", and the `class.default` setting. |
| `templates-store` | Creates the Templates Store (a LogicalDOC workspace, admins only) and records its id. |
| `columns` | Sets `default.gui.document.columns` (§8.4). |
| `search-docnumber-column` | Adds Doc # to `default.gui.search.columns`, right after the file name (§8.4). |
| `reindex-office` | Flags existing `docx`/`xlsx`/`pptx`/`eml`/`msg` documents for re-indexing (§9). |
| `settings` | Writes default values for every Healdan setting. |

The demo template and matter aren't seeded. The owner creates them through the UI as the acceptance test (§14.2).

## 13. Upstream files M1 edits

This is the list as built, after M1's last PR ([Healdan-Project/healdan#41](https://github.com/Healdan-Project/healdan/pull/41)) and the JDK 25 PR ([#40](https://github.com/Healdan-Project/healdan/pull/40)) that merged after it, plus the credits PR ([#43](https://github.com/Healdan-Project/healdan/pull/43)) and the pre-acceptance PR ([#44](https://github.com/Healdan-Project/healdan/pull/44)), which closed some of the plan's leftovers. It replaces the list approved with this spec. It includes the [M1 plan](../plans/2026-10-17-m1-move-in.md)'s corrections 1–5 and every row that its decisions D1, D3 and D7 added.
- R and D numbers point to the plan's rulings and decision log.
- The Task column names the plan task, or the PR, that made the edit (key below the table).
- Paths are relative to the repository root. Line numbers are approximate, from the code at `1c1507d86`.
- Every edit is a hook or a small fix. New logic lives in new files: `healdan-core`, `com.logicaldoc.gui.frontend.client.healdan` (`common.client.healdan` for what the login page needs too), and the fork-owned files marked **new file** below.
- Many of these files have CRLF line endings. Keep them when editing.

| File | Change | Task |
|---|---|---|
| `pom.xml` (root, `<modules>` ~199) | add `healdan-core` after `logicaldoc-gui` | 1 |
| `logicaldoc-parent/pom.xml` (maven-compiler-plugin, ~85–88) | `-proc:full`: javac 23+ runs annotation processors only when asked, and Log4j's indexes `LDHtmlLayout` | JDK 25 |
| `logicaldoc-webapp/pom.xml` (~616) | `artifactItem` for `healdan-core` `zip`/`plugin` | 1 |
| `.github/workflows/build-logicaldoc.yml` (~132–140) | run `healdan-core`'s tests, and the named upstream tests that cover Healdan's edits: `HealdanHooksTest`, `DocumentAclEqualityTest`, `HibernateFolderDAOTest#testMerge`, `StatsCollectorTest`, `FolderServiceImplTest`, `SecurityServiceImplTest#testDeleteGroupRefusedKeepsTheMembers`, `DocumentServiceImplTest`, `SearchServiceImplTest`, `DocumentOnlyGrantTest`, `BookmarksDataServletTest`, `RpcPermissionTest`, `RpcPermissionGapsTest`, `SoapPermissionGapsTest`, `DocumentsDataServletTest`, `ConvertPdfTest` and `HealdanDocNumberSortTest`; the web-services PR added `logicaldoc-webservice` to the step's modules | 1, then extended by 3, 14, 18, 19, 20, 24, 25 (`ConvertPdfTest`), 27 (`HealdanDocNumberSortTest`, R106), the follow-ups PR (`RpcPermissionTest`), the gaps PR (`RpcPermissionGapsTest`), the web-services PR (`SoapPermissionGapsTest`), the ACL equality PR (`DocumentAclEqualityTest`) and the pre-acceptance PR (`HibernateFolderDAOTest#testMerge`, `StatsCollectorTest`, `SecurityServiceImplTest#testDeleteGroupRefusedKeepsTheMembers`) |
| `logicaldoc-core/.../core/HealdanHooks.java` | **new file**: the hook interface and its registry. Core calls `HealdanHooks.get()`, which is a no-op until `healdan-core` registers `HealdanHooksImpl`, so core doesn't depend on Healdan | 3 |
| `logicaldoc-core/.../core/HealdanVetoException.java` | **new file**: `extends PersistenceException`; every Healdan veto throws it (or a subclass), and the document and folder DAOs roll back on it alone (D3) | 3 |
| `logicaldoc-core/.../core/HealdanTreeChecks.java` | **new file**: the checks on a whole folder tree (R73's copy walk, R74's delete and merge), and the checks on one folder or document they're built from, moved from `HealdanRpcChecks` so the web services can call them too (R78) | web services |
| `logicaldoc-core/.../document/HealdanDocNumberSort.java` | **new file**: the numeric Doc # comparison (R104); its test is `HealdanDocNumberSortTest` (R106) | 27 |
| `logicaldoc-core/.../document/DocumentComparator.java` (~89) | `CUSTOMID_SORT` calls `HealdanDocNumberSort` (R104) | 27 |
| `logicaldoc-core/.../document/DocumentManager.java` `moveToFolder` (~917) | `HealdanHooks.get().beforeDocumentMove(...)` right after the same-folder early return, so a no-op move isn't vetoed | 3 |
| `logicaldoc-core/.../document/DocumentManager.java` `rename` (~1406) | `beforeDocumentRename(...)` first. CE schedules the renamed version's background save before the store, so a listener veto still leaves an `ld_version` row (D3) | 3 |
| `logicaldoc-core/.../document/DocumentManager.java` `update` (~760) | `beforeDocumentUpdate(...)` first. When the language changes, CE calls `deleteHit` before the store, so a listener veto leaves the document out of the index (D3) | 3 |
| `logicaldoc-core/.../document/HibernateDocumentDAO.java` `restore` (~1016), class annotation (~71) | `beforeDocumentRestore(...)` first (correction 1: `DocumentManager` has no `restore`); `@Transactional(rollbackOn = HealdanVetoException.class)`. `restore` undeletes the rows with JDBC and then calls `store` in the same transaction, so a listener veto there would otherwise commit the undelete (D3) | 3 |
| `logicaldoc-core/.../document/DocumentAccessControlEntry.java` `equals` (~302), `hashCode` (~307) | two entries are equal when they're for the same group (`ld_document_acl`'s key), and the hash is the group id's. CE's `equals` was never true, so every flush of an initialized document rewrote its ACL rows and bumped its record version and last-modified date | ACL equality |
| `logicaldoc-core/.../folder/HibernateFolderDAO.java` `deleteTree(Folder, int, FolderHistory)` (~1713, the entry all three overloads share), `restore` (~859, ~893), `applySecurityToTree` (~1250), class annotation (~68) | `beforeFolderDeleteTree`; `beforeFolderRestore` first and `afterFolderRestore` last (correction 2: `restore` never calls `store`, so re-stamping needs the after hook); `beforeApplySecurityToTree`; `@Transactional(rollbackOn = HealdanVetoException.class)` (D3) | 3 |
| `logicaldoc-core/.../folder/HibernateFolderDAO.java` `merge` (~2347) | `beforeFolderMerge(...)` first. `merge` moves each document with `DocumentManager.moveToFolder`, which removes the hit from the index and saves a MOVED version in the background. Neither is transactional, so a veto partway through leaves both behind even when the database rolls back (D3). The pre-acceptance PR: both loops walk the direct children (`findChildren`, ~2372, ~2382) instead of `findByParentId`'s whole subtree, which landed every folder at any depth directly in the target and moved an alias's target rather than the alias (R134) | 3, pre-acceptance |
| `logicaldoc-core/.../folder/HibernateFolderDAO.java` `copy` (~1505) | `beforeFolderCopy(...)` first. `copy` creates each document with `DocumentManager.create` inside the folder DAO's transaction, and each create writes the file to the Store and schedules the first version's save in the background, which waits for the document row. A veto partway through rolls the database back, but the files stay and each version save holds a `VersionSave` thread for about 100 s before logging an error (D3) | 3 |
| `logicaldoc-core/.../stats/StatsCollector.java` `runTask` (~348) | the upload to `stat.logicaldoc.com` removed; the task still saves the local statistics System > General shows (R135) | pre-acceptance |
| `logicaldoc-core/.../security/user/HibernateGroupDAO.java` `delete` (~49), `insert` (~91), `store` (~163) | `HealdanHooks.get().beforeGroupDelete(group)` first in `delete`, so Healdan can refuse deleting Everyone (R137), and `beforeGroupStore(group)` first in `insert` and `store`, so it can refuse renaming it (R138) | pre-acceptance |
| `logicaldoc-cmis/.../cmis/LDRepository.java` `deleteTree` (~890–895) | `HealdanHooks.get().beforeFolderDeleteTree(...)` first when the object is a folder, with a `FolderHistory` naming the session user; a refusal reaches the client through the method's own catch (`CmisStorageException`). CMIS deletes each document and subfolder in its own commit before the folder itself, so a refusal at the folder's store would leave the contents deleted (R10) | 9 |
| `logicaldoc-cmis/.../cmis/LDRepository.java` `createDocumentFromSource` (~488), `deleteObject` (~846), `deleteTree` (~883) | `HealdanCmisChecks` on the document: DOWNLOAD on the one copied (R73); DELETE on the one deleted, `deleteTree` given a document included (R70, R80) | WebDAV/CMIS |
| `logicaldoc-cmis/.../cmis/HealdanCmisChecks.java` | **new file**: the document checks above, through core's `HealdanTreeChecks.document` (R78), refusing with a `CmisPermissionDeniedException` (R80) | WebDAV/CMIS |
| `logicaldoc-webdav/.../resource/service/ResourceServiceImpl.java` `deleteResource` (~730), `copy` (~785) | `HealdanDavChecks` on the document: DELETE on the one deleted (R70), DOWNLOAD on the one copied (R73); a folder's tree is the hook's (R80) | WebDAV/CMIS |
| `logicaldoc-webdav/.../resource/service/HealdanDavChecks.java` | **new file**: the document checks above, through core's `HealdanTreeChecks.document` (R78), refusing with a 403 (R80) | WebDAV/CMIS |
| `logicaldoc-webservice/.../soap/endpoint/SoapDocumentService.java` | the document's DELETE in `delete` (~233, in place of the folder's and before CE's lock check, so a refusal names nothing, D7); DOWNLOAD in `copy` (~474, R73); DOWNLOAD and EMAIL in `sendEmail` (~688, R72, R79), whose filter no longer reads the folder's READ and skips a null id (~717); READ on the document and a shortcut's target in `unprotect` (~1098, R75, R79). REST's `RestDocumentService` extends it | web services |
| `logicaldoc-webservice/.../soap/endpoint/SoapFolderService.java` | DELETE on the whole tree and its documents in `delete` (~151, R74); READ and DOWNLOAD on what `copy` copies (~308, R73); MOVE and DELETE on the source tree in `merge` (~612, R74). REST's `RestFolderService` extends it and serves `delete` and `copy` | web services |
| `logicaldoc-webservice/.../webservice/HealdanWsChecks.java` | **new file**: the document check for SOAP calls that declare no `PermissionException`: it runs core's `HealdanTreeChecks` and refuses with a `WebserviceException` (R78, R79) | web services |
| `logicaldoc-webapp/.../web/service/FolderServiceImpl.java` | SECURITY in `saveACL` (~97) and `inheritACL` (~76); RENAME in `rename` (~590) and in `save` (~626) when the name changes (correction 3). No check in `restore` (~1097): CE's permission check reads the folder through `findById`, which returns nothing for a deleted folder, so it would refuse every non-admin restore. The DELETE check lives in Healdan's `beforeFolderRestore` hook (`FilingRules.checkFolderRestore`, which reads the permission with JDBC, deleted folders included), and every restore route goes through it (R22). `saveACL` with "apply to subfolders" no longer swallows the `applySecurityToTree` error in its background task, so a refusal reaches the user with its own message (~107–121, R35). The follow-ups PR: WRITE on the whole tree in `applyMetadata`, `applyTags`, `applyGridLayout` and `applyCapture` (~133, ~1136, ~1168, ~1190, R66), READ in `computeStats` (~239), and `deleteFromTrash` keeps to the user's trash and to trashed folders (~1124, R61). The gaps PR: DELETE on the whole tree and its documents in `delete` (~153, R74); READ and DOWNLOAD on what `copyFolders` copies (~474) and DOWNLOAD in `paste`'s copy (~916, R73); WRITE on the folder and DOWNLOAD in `pasteAsAlias` (~947, R73); MOVE and DELETE on the source trees in `merge` (~1231, R74). The pre-acceptance PR: in `save`, WRITE on the folder, and on the folder an alias points to, where CE writes all but the name, position and colour; the alias's stored `foldRef` instead of the client's, to check and to write (~629–653); STORE when the storage changes (~634, R133) | 14, follow-ups, gaps, pre-acceptance |
| `logicaldoc-webapp/.../web/service/SecurityServiceImpl.java` `deleteGroup` (~406) | `HealdanHooks.get().beforeGroupDelete(grp)` before the loop that takes the members out, so a refused delete of Everyone re-saves no user (R137) | pre-acceptance |
| `logicaldoc-webapp/.../web/service/DocumentServiceImpl.java` | SECURITY in `saveACL` (~3309) and in `applyParentFolderSecurity` (~3364), which resets a document's ACL to its folder's and can widen it (R24); `addBookmarks` (~179) looks a folder bookmark up by folder (`findByUserIdAndFolderId`), not by document, and skips a folder that is missing or deleted (~197); READ on the document in `getById` (~803, a missing document still gives null) and on each version's document in `getVersionsById` (~988, ~1033); in `save` (~1276), WRITE on the document and on a shortcut's target, a `docRef` that differs from the stored one refused, and RENAME when the file name changes (~1287), a shortcut's name sanitized like a document's and never written blank; all through a new `checkDocumentPermission` (~1086): CE checked none of them (T19-c, T19-f, D7). `getDocument` (~837) gives a user who can't READ the document's folder a stub folder with only its id and name (T19-e, D7). The follow-ups PR: a one-line `HealdanRpcChecks` call in each RPC listed under "Follow-ups from PR C" (R57), and `checkDocumentPermission` delegates to it; `deleteFromTrash`'s update keeps to trashed documents (R61); `saveRating` looks up the caller's vote (R64); `getContentAsString`, `bulkUpdate`, `setPassword` and `rename` check the document instead of the folder (R68). The gaps PR: DELETE in `delete` (~713, R70); the document's WRITE in `promoteVersion` (~638, in place of the folder's), and in `replaceFile` and `checkinContent` (~2683, ~2648, in place of CE's ignored `isWriteAllowed`, R71); DOWNLOAD and EMAIL in `sendAsEmail` (~1545), DOWNLOAD in `convert` (~2782), WRITE and DOWNLOAD in `replaceAlias` (~2950), DOWNLOAD and the target folder's WRITE in `merge` (~3213, R72, R73); READ in `getNote` and `getNotes` (~1961, ~2027), ARCHIVE in `archiveDocuments` (~2386), PASSWORD in `unsetPassword` (~2587), READ in `unprotect` (~2603, R75), and the web-services PR adds READ on a shortcut's target there (~2604, R75); `restore` (~1233) checks every document before it restores any and throws a refusal instead of logging it (R76) | 14, 19, 20, follow-ups, gaps |
| `logicaldoc-webapp/.../web/service/HealdanRpcChecks.java` | **new file**: the checks the follow-ups PR adds to CE's RPCs (R57), extended by the gaps PR. The web-services PR moves its tree checks, and its checks on one folder or document, to core's `HealdanTreeChecks`, which it delegates to (R78) | follow-ups, gaps, web services |
| `logicaldoc-webapp/.../web/data/VersionsDataServlet.java` (~108) | READ on the requested document before listing its versions; refused with an `IOException`, sent as a 500 with its message (T19-c, D7). The follow-ups PR: the `archiveId` branch (~117) needs the Import/Export menu (R67) | 19, follow-ups |
| `logicaldoc-webapp/.../web/data/DeletedDocsDataServlet.java` (~49), `DeletedFoldersDataServlet.java` (~44) | a user without the report's menu gets only their own deletions (R77) | gaps |
| `logicaldoc-webapp/.../web/service/SearchServiceImpl.java` (~192, ~216, ~263) | default converter for unregistered search types, in `search`, `save` and `load` (R48) | 18 |
| `logicaldoc-webapp/.../web/data/DocumentsDataServlet.java` | extended attributes in `docIds` mode (~155, `putExtendedAttributesValues` ~353, R96, R99); folder mode's query binds the `extattr` names instead of splicing them in (`retrieveExtendedAttributesValues`, ~755) | 24, extattr |
| `logicaldoc-webapp/.../web/ConvertPdf.java` (~107–117) | honours `fileVersion`, and checks that version's file name for `.pdf` (R119) | 25 |
| `logicaldoc-gui/.../frontend/client/menu/QuickSearchTray.java` (~47, ~107–113) | a `ScopePicker` field; `setItems(scope, searchBox)` in CE's default branch, then `HealdanQuickSearch.init(...)`; `onSearch`'s first line hands `scope.getScope()` and the text to `HealdanQuickSearch.handle`, which returns false when CE's own search should run: the Docs and Emails scope, or no picker (R114) | 25 |
| `logicaldoc-gui/.../frontend/client/search/HitsListPanel.java` (~91–101, ~134) | double-clicking a folder hit opens it (correction 4); a document hit's double-click and context menu take the document's own allowed permissions and the hit's own folder (R116, D7) | 25 |
| `logicaldoc-gui/.../frontend/client/document/VersionsPanel.java` (~89, ~136, ~181, ~218) | the four reads of the folder's DOWNLOAD read the document's own (R117, D7) | 25 |
| `logicaldoc-gui/.../frontend/client/document/DocumentDetailsPanel.java` (~709) | the shortcut swap after save removed (R118) | 25 |
| `logicaldoc-gui/.../frontend/client/document/DocumentsMenu.java` (~52) | `HealdanDocumentsMenu.addSections(this)` before `addBrowser()` | 26 |
| `logicaldoc-gui/.../frontend/client/document/DocumentsPanel.java` `openInFolder` (~146–148) | `deselectAllRecords()` before `openFolder` (R128), and `expandSection("browser")` instead of section 0 (correction 4) | 26 |
| `logicaldoc-gui/.../frontend/client/document/DocumentsListPanel.java` (~52, ~145) | search folders list through `docIds` (`SearchFolderListing.load`, R102) | 24 |
| `logicaldoc-gui/.../frontend/client/folder/ContextMenu.java` (~132) | `HealdanFolderMenu.contribute(...)` | 23 |
| `logicaldoc-gui/.../common/client/util/DocUtil.java` (~160) | `magnifying-glass` icon for type 1001 (R101) | 24 |
| `logicaldoc-gui/.../frontend/client/administration/AdminMenu.java` (~88) | `HealdanAdminMenu.addTo(this)`: a "Healdan" section, admins only (R85) | 22 |
| `logicaldoc-gui/.../frontend/client/document/DocumentStandardPropertiesPanel.java` (~90, ~128, ~477) | Doc # always visible and read-only, with no change handler and not read back (R124); the creation date titled `created`, "Date filed" (R110) | 27 |
| `logicaldoc-gui/.../frontend/client/document/grid/DocumentsListGrid.java` (~257, ~980) | numeric sort normalizer on `customId` (R105) | 27 |
| `logicaldoc-gui/.../frontend/client/metadata/form/ResponsesGrid.java` (~80) | the date column titled `createdon` (R123) | 27 |
| `logicaldoc-i18n/.../messages.properties`, `messages_en.properties`, `messages_en_US.properties` | `customid`, `customId` → "Doc #"; `created` → "Date filed"; `majorversion` → "New version (otherwise a snapshot)"; `task.description.GoogleCalendar` and `askexecutepackage`: "LogicalDOC" → "Healdan" (R109) | 27, 28 |
| `logicaldoc-gui/.../common/client/beans/GUIBranding.java`, `logicaldoc-gui/war/favicon.ico`, `logicaldoc-gui/war/header.jsp` | the owner's logo redrawn as SVG data URIs (correction 5, R107; the banner stays CE's, R108), `productName`, `product`, and the link and vendor defaults (R121); the favicon; `<title>` | 28 |
| `logicaldoc-gui/.../login/client/LoginPanel.java` (~252, ~260), `logicaldoc-gui/.../frontend/client/menu/AboutDialog.java` (~74–82, ~120) | the copyright line shows Healdan's year (`HealdanCredits.YEAR`) instead of CE's `product.year`, LogicalDOC's range, and the credit to LogicalDOC follows it. About's trademark line was CE's `copyrights` message, which called Healdan a registered trademark of Healdan Project. `HealdanCredits.aboutCredits` replaces it with the credit to LogicalDOC, both trademarks and the license | credits |
| `logicaldoc-gui/.../common/client/healdan/HealdanCredits.java` | **new file**: the credit lines, in the common module because the login module can't see the frontend's | credits |
| `logicaldoc-gui/war/skin/style-login.css` (~90–112) | the Sign in button in the logo's dark blue (`#0b5dab`, `#084a88` on hover and press) instead of CE's orange, and its text stays white when pressed. Tahoe, the only skin `header.jsp` serves, takes this file | credits |
| `README.md` (top) | Healdan's notice that it's a modified version of LogicalDOC CE, under the LGPL, above upstream's README | credits |
| `logicaldoc-webapp/src/main/webapp/WEB-INF/web.xml` (~9) | `display-name`. No servlet entries: D1 found that Tomcat 11 scans `@WebServlet` in the plugin jar | 28 |
| `docker/smoke-test.sh`, `docker/tests/compose.smoke.yaml` (fork-owned) | the smoke overlay, uploads into the smoke matter, Healdan checks | 16, 21 |

**Task column key.** Numbers are plan tasks: 1–7 shipped in PR A ([#22](https://github.com/Healdan-Project/healdan/pull/22)), 8–16 in PR B ([#26](https://github.com/Healdan-Project/healdan/pull/26)), 17–21 in PR C ([#27](https://github.com/Healdan-Project/healdan/pull/27)), and 22–28 in PR D's five parts ([#34](https://github.com/Healdan-Project/healdan/pull/34), [#36](https://github.com/Healdan-Project/healdan/pull/36), [#39](https://github.com/Healdan-Project/healdan/pull/39), [#38](https://github.com/Healdan-Project/healdan/pull/38), [#41](https://github.com/Healdan-Project/healdan/pull/41)). The names are the PRs that closed follow-ups:
- follow-ups: [#28](https://github.com/Healdan-Project/healdan/pull/28);
- gaps: [#29](https://github.com/Healdan-Project/healdan/pull/29);
- web services: [#30](https://github.com/Healdan-Project/healdan/pull/30);
- WebDAV/CMIS: [#32](https://github.com/Healdan-Project/healdan/pull/32);
- ACL equality: [#33](https://github.com/Healdan-Project/healdan/pull/33);
- extattr: [#37](https://github.com/Healdan-Project/healdan/pull/37);
- JDK 25: [#40](https://github.com/Healdan-Project/healdan/pull/40), which moved CI and the Docker image to JDK 25 ahead of upstream's Java 25 switch (the plan's call 11);
- credits: [#43](https://github.com/Healdan-Project/healdan/pull/43), which credits LogicalDOC on the login page and in About, and makes the Sign in button blue;
- pre-acceptance: [#44](https://github.com/Healdan-Project/healdan/pull/44), which fixed four gaps found before the owner's acceptance walkthrough (R132–R138).

**Planned, but not edited:**
- `logicaldoc-webapp/.../web/data/FoldersDataServlet.java`: the tree draws a search folder's magnifying glass through `DocUtil.getFolderIcon` (`FolderListGridField`), found in Task 24.
- `logicaldoc-webapp/.../web/data/BookmarksDataServlet.java`: §8.2's second bookmark bug doesn't reproduce, because Hibernate's `@SQLRestriction("ld_deleted=0")` already hides deleted folders. `BookmarksDataServletTest` guards it (D8, R54).
- `logicaldoc-gui/.../frontend/client/document/grid/ContextMenu.java`: it reads only the folder's id, which a hit's own folder carries (R116).
- `logicaldoc-gui/war/skin/images/brand/logo*.png`: no code uses them, so they stay until the owner supplies artwork (correction 5).

**Upstream test files.** These are in upstream modules but aren't in the table:
- **New, fork-owned test classes:**
  - in core: `HealdanHooksTest`, `DocumentAclEqualityTest` and `HealdanDocNumberSortTest`;
  - in the webapp: `ConvertPdfTest`, `BookmarksDataServletTest`, `DocumentOnlyGrantTest`, `RpcPermissionTest`, `RpcPermissionGapsTest` and the helper `MemoryResponse`;
  - in `logicaldoc-webservice`: `SoapPermissionGapsTest`.
- **Upstream tests edited in place:** `DocumentServiceImplTest`, `FolderServiceImplTest`, `SearchServiceImplTest`, `DocumentsDataServletTest`, `HibernateFolderDAOTest` (its `testMerge` asserted CE's flattened merge and now asserts the nested one, R134), `StatsCollectorTest` (`testSendsNothingOut`, R135) and `SecurityServiceImplTest` (`testDeleteGroupRefusedKeepsTheMembers`, R137). Each edit is a small addition, with the file's line endings kept. The plan's upstream-edit section says what each adds. An upstream sync can expect conflicts in these seven.

**What this list doesn't cover:** the CE security fixes made outside M1. PRs [#15](https://github.com/Healdan-Project/healdan/pull/15), [#16](https://github.com/Healdan-Project/healdan/pull/16), [#18](https://github.com/Healdan-Project/healdan/pull/18), [#20](https://github.com/Healdan-Project/healdan/pull/20) and [#21](https://github.com/Healdan-Project/healdan/pull/21) edited upstream files too, for example `SystemServiceImpl`, `HttpUtil`, and `FolderServiceImpl.create` and `createAlias`. The [capability map](../../reference/logicaldoc-ce-capability-map.md)'s §6 records them. A later milestone lists its own upstream edits in its own spec (roadmap §6).

## 14. Verification

### 14.1 Automated

**Unit and integration tests** (JUnit 4) live in `healdan-core`. They use a base class like CE's `AbstractCoreTestCase` (HSQLDB, CE schema plus Healdan's Flyway scripts) and cover:
- **Doc #:**
  - assignment on create and copy;
  - concurrent creates from several threads never share a number;
  - unchanged after a GUI-style check-in with a null customId, an API update, delete and restore;
  - the "raise only" rule for the next number.
- **Lookup syntax:** `#n`, `#n.N`, `#n.N.M`, the `v` forms, missing and unreadable documents, and version comparison (`2.10` sorts after `2.9`).
- **Numbering modes:** suggestions, duplicate checks, and switching in both directions.
- **Matter creation from a template:** the copied folders, ACLs, local values and search folders; the owner's rights; the P2 check.
- **Inheritance:** every row of the §6.7 table, including that propagation creates no version rows.
- **Filing rule and structure rules (§7.1, §7.2):**
  - through `DocumentManager` create, move, copy and restore, and through `FolderDAO` create and move;
  - and at least one test per external channel: REST, WebDAV and CMIS, either in those modules' test suites or as `docker/smoke-test.sh` steps;
  - each refusal leaves no partial write (§7.5).
- **P1 and P2 (§7.3),** including automatic narrowing.
- **Permission checks:**
  - the new SECURITY, RENAME and DELETE checks on the RPCs;
  - a refused "apply to subfolders" that would re-open a restricted folder;
  - a document-only grant in a folder the user can't read: `#n`, open, preview, download, properties and versions all work (§7.3).
- **Folder delete and restore** go through the new hooks (§7.2), including restoring a workspace from the trash, which brings its matter back (§5.3).
- **Matter lifecycle:** deleted matters vanish from lookups and lists, their numbers stay reserved, and changing the owner swaps the ACL entries.
- **Search folder rules:** window, only mine, kind, Class and Subclass, and permission filtering.
- **Recent Matters:** the window, the cap, and event filtering.
- **Emails:** fields and naming for fixture `.eml` and `.msg` files.
- **Parsers:** text extracted from fixture `.docx`, `.xlsx`, `.pptx`, `.eml` and `.msg` files.
- **Migrations:**
  - Flyway from an empty Healdan schema on a CE schema;
  - re-running is a no-op;
  - a failing script fails startup;
  - a test pattern that later milestones extend with "upgrade from the previous version".

**CI** runs these tests (§13). The Healdan tests need a `~/logicaldoc-dev.properties` file to exist; an empty one works (found while preparing PR #15).

**`docker/smoke-test.sh`** gains steps for a fresh stack:
- the Healdan tables and seed exist;
- a REST upload into a matter folder gets doc # 1;
- a REST upload to a matter's top level is refused.

### 14.2 Manual acceptance (owner)

On a fresh `docker compose up`, the owner walks through §2's goals with the demo matter:
1. Signed in as admin, create the "Client Work" and "Personal" Stores, the IT group, client 998 "Healdan", the demo template (§5.3) and matter 998.1 "Healdan Demo".
2. Signed in as their own non-admin user, upload a `.docx`, an `.xlsx` and an `.eml` into Documents. Check the doc #s, the Class "Docs", Author and Typist, Date filed, the email fields, and the email's name.
3. Try to upload to the matter's top level and into Recent Work. Both are refused.
4. Look up `#1`, `#1.1` and `998.1` (Matters scope). Add the matter to My Matters, and see it in Recent Matters.
5. Search for a word inside the `.docx` and inside the email.
6. As a user outside IT, confirm Secured Documents and Emails are invisible.

## 15. Left to the builder

These choices don't change what gets built, so the builder decides them:

- Exact class names and package layout inside `com.logicaldoc.healdan` and the GUI's `healdan` package.
- The search type numbers, and the folder type value if 1001 turns out to collide with anything.
- Whether `dependsOn` can narrow the Subclass list in CE's GUI. The server check is required either way.
- How the GUI shows a `#n.N` version result (§8.1), and how numeric Doc # sorting is done (§13).
- How the GUI keeps the scope picker's last choice.
- Re-index batching for propagation (§6.7) and for the seed (§9).
- Whether the email parser also indexes attachment contents.
- The en dash versus a hyphen in matter display names, if the en dash causes trouble in WebDAV paths.
- The placeholder wordmark until the owner supplies a logo.

## 16. Decisions made in this session

| Topic | Decision | Source |
|---|---|---|
| Doc # format | unpadded integer, 64-bit, starts at 1, admin can raise | owner (format, scale); call (start, raise) |
| Doc # storage | CE `customId` plus Healdan counter `hd_counter`, protected by a listener | call |
| Versions | CE major = Healdan version; minor = snapshot; footer shows `# n.N.M` | owner |
| Lookup | `#n`, `#n.N`, `#n.N.M` (and `v` forms); bare numbers are full text; `#` ignores legacy | owner (`.` form, legacy), call (rest) |
| Legacy doc # | a string attribute; found by advanced search | owner |
| Client/matter numbers | whole numbers; per-client or system-wide mode per install; suggested and overridable, duplicates refused | owner |
| Display | `998.1 – Healdan Demo` | owner ("C/M 998.1"), call (en dash) |
| Matter creation | admins only; client → number → name → Store (the clicked Store pre-selected; no default Store setting, 2026-10-21) → owner → template | owner |
| Templates | real folder trees in an admin-only Templates Store, copied on creation | call |
| Search folders | Recent Work and My Recent Work, rule-based, workspace-scoped | owner (folders), call (rule model) |
| Profile | Author, Typist, Class (required, default "Docs"), Subclass (optional, belongs to a Class), legacy doc #, email fields | owner |
| Inheritance | stamped copies; local values per attribute; client/matter fixed; re-stamp on move | roadmap calls, call (mechanics) |
| Top-level folders | admins only, including rename, move and delete | owner (add), call (the rest) |
| Folder permissions | P1 per group within a matter, auto-narrowing; P2 matter vs Store for READ | roadmap call, call (mechanics) |
| Emails | From, To, CC, Subject, Sent, Received; named after the subject | owner (CC is a call) |
| My Matters / Recent Matters | lists in the left pane that jump to the tree; recent = changed in 30 days | owner |
| Client in matter lists | matter search results get a Client column; My/Recent Matters entries read `998.1 – Healdan Demo (Healdan)`, the client smaller and muted (mockup review, 2026-10-21) | owner |
| UI scope | the existing GUI, changed minimally | owner |
| Values supplied at creation | different from inherited → local; same or blank → inherited (covers upload, API, copy) | call |
| Owner change | the new owner gets full rights; the old owner's entry is removed | call |
| Deleting a matter | soft delete with the workspace; the number stays reserved; hidden from lookups; restore brings it back | call |
| Who changes permissions | SECURITY on the object itself; a restricted folder can't be re-opened from above except by admins | call |
| Document permissions | LogicalDOC's behaviour stays: a document can be granted to people who can't see its folder (one-off or temporary access, as in iManage) | owner |
| Package root | `com.logicaldoc.healdan` | call |
| Upgrades | Flyway for `hd_` tables, plus idempotent Java seed steps | call |
