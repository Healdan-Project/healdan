# M3 "Office integration": design

- **Milestone:** M3 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-10-24
- **Status:** draft. The trial build (§12) runs before the owner approves this spec (owner, 2026-10-24). The sections it settles are marked **(trial)**, and its results go into §12.5. A builder check by a reviewer who hadn't seen the design conversation found nine statements the code contradicts, all now corrected (§3.1), and eleven gaps. Two of the gaps were the owner's to answer: the file format at first save, and a Comments property (§5.6, §9.9).
- **Inputs:**
  - the roadmap's M3 section, and its glossary;
  - the [M2 spec](2026-10-23-m2-api-foundation-design.md), the API's contract (`healdan-api/src/main/resources/healdan/api/openapi.yaml`) and the M2 plan's follow-ups "For M3's design session" ([plan](../plans/2026-10-23-m2-api-foundation.md));
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md) §2.3, §4.2 and §5.1;
  - a design conversation with the owner on 2026-10-24, with screenshots of iManage's Save dialog that the owner supplied, and mockups of the Save and Open windows, the title bar and the helper, which the owner reviewed;
  - a survey of this repository at `dbfb46dd4`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks Claude's proposal. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace* and *template* mean Healdan's things here (roadmap glossary). "CE" is LogicalDOC Community Edition, which Healdan is built on.

## 1. Intent

You save to and open from Healdan inside Word and LibreOffice, and the document always shows its doc # and version. It's the iManage flow the owner has used for years, and the next thing they'd miss after M1. (owner)

**How the owner works, and wants to keep working (owner):**

- **New documents** are created in the app and filed through a Healdan Save window: places to start from on the left (My Matters, Recent Matters, recent and favourite folders), the chosen place's contents in the middle, and the properties on the right, mostly filled in already.
- **Existing documents** are usually found outside the app, by doc # or matter #, and opened from there, which checks them out. Opening from inside the app also works but is used less.
- **While editing,** the app's ribbon changes the open document's properties and saves a new version, and the title bar shows the doc # and version next to the name.
- **Closing** checks the document back in.

**One helper on each computer (owner).** A small app written in C# on .NET, with Avalonia for its windows, runs on Linux and macOS (and later Windows). It holds the sign-in, catches "open this document" links from Healdan's web page, keeps local copies of open documents, notices saves and closes, talks to the server, and draws the Save, Open and Properties windows once for every app. The add-ins inside LibreOffice and Word stay slim: their buttons, the title bar and the doc ID footer. Nothing on the user's computer is written in Java (owner).

## 2. Goals, non-goals, anti-goals

### Goals (M3 is done when)

On each of the three setups in §3.2, against a Healdan server reached over HTTPS, as an ordinary non-admin user:

1. **Sign in once.** The helper signs in with a username and password, keeps M2's device token in the system keychain, and never stores the password (§5.2).
2. **Save to Healdan.** A new document's Save opens the Save to Healdan window (where the app lets Healdan take Save over; otherwise its own button). The user picks a folder from Recent Folders, Recent Matters, My Matters, Favorites or a search, fills in any required properties and Comments, keeps the app's own file format or picks another, and saves. The title bar then reads `Lease agreement #1234 v1.0`, and the document stays open, checked out (§5.6, §5.8).
3. **Save while editing.** Each save sends a snapshot, and the title bar moves on to `v1.1` (§5.5, §5.7).
4. **Close and reopen.** Closing checks the document in. It reopens from the web page's Open in App (§8) and from the Open from Healdan window, including by `#1234` (§5.8).
5. **Save a new version.** Save as New Version makes `v2.0`, and the title bar says so (§5.5).
6. **Doc ID footer.** Insert Doc ID adds a footer field showing `#1234 v2.0`, in Writer and Word. Healdan stamps the right number into every file it stores through the API, so the stored file always carries it (§5.9, §9.7).
7. **Someone else's document** opens as a read-only copy, and the helper says who has it checked out (owner, roadmap).
8. **Two computers.** A document checked out to you on another computer warns before editing moves here, and the first computer keeps its copy (owner, §5.10).
9. **Nothing is lost.** A save made while the server can't be reached is kept and sent later; a crash leaves the document checked out with its local copy, and the helper offers to reopen it or check it in (owner, §5.10).
10. **Excel, PowerPoint, Calc and Impress** get the same open and save flow. The footer is Writer and Word only (owner, roadmap).
11. **Comments.** Every document has a Comments property, and the Documents search finds a document by the words in it (owner, §9.9).
12. **Installs cleanly.** CI builds a signed, notarised helper for macOS and `.deb` packages for Ubuntu, attached to a GitHub release (owner, §5.12).
13. **Documented.** The docs pages in §13.2 are written, and the API's new endpoints are in `openapi.yaml` (§9).

### Non-goals

- **Windows.** It comes later, for adoption (roadmap). The helper's choice of .NET and Avalonia keeps it within reach.
- **Editing in Word for the web.** Microsoft requires its Cloud Storage Partner Program (roadmap).
- **Compare Versions.** Redlining is in the backlog (owner, 2026-10-24).
- **Setting a new document's permissions while saving** (iManage's Security tab). A new document takes its folder's permissions, as M1 decided; changing them stays in the web page (owner, 2026-10-24).
- **Double-click in today's web page** keeps showing the preview. Opening in the app is the Open in App button (§8). The new web interface (M6) makes double-click open documents in their app (owner, 2026-10-24).
- **Working offline.** Opening a document needs the server. Only saves survive an outage (§5.10).
- **Updating the helper by itself.** A new release is installed by hand (call).
- **Password-protected documents.** The API refuses to open or save them (M2 §6.6), so the helper says to use the web page.
- **WebDAV and CMIS.** The helper uses only `/api`. CE's WebDAV locking stays as it is, and device tokens still work only at `/api` (M2 §5.1).
- **Stamping on other routes.** Only files stored through the API are stamped (§9.7). A document with the footer uploaded or checked in through the web page keeps the number its footer had until its next save through the helper (call; recorded in Known Limitations).
- **Browsing a list of every client.** The Save and Open windows start from search, My Matters, Recent Matters, Recent Folders and Favorites, as the owner ruled for the user apps on 2026-10-23 (M2 §2). "Clients" in the left pane is a search by client (§5.8).

### Anti-goals

- **Lost work.** Any path where a save the user made is lost is a failure, even if every other part works. The helper never deletes a local copy that the server hasn't confirmed (owner).
- **A save over something newer.** A save must never silently replace a version it didn't start from, take back a document whose check-out ended, or land on a document someone else holds (§9.5, M2 §6.6).
- **A client that stores a password** (roadmap, M2).
- **A route around a rule.** The helper and add-ins go through `/api`, so every rule the server enforces still applies; the client enforces nothing on its own that the server doesn't (roadmap principle 4).
- **Two copies of the windows.** The Save, Open and Properties windows exist once, in the helper (owner).
- **Java on the user's computer** (owner).

## 3. Constraints

### 3.1 Facts from the code and the platforms

**What M2 gives the helper, and its edges:**

- **The API.** Device tokens (`POST /auth/tokens`, 30 days idle, 365 at most, revoked on a password change), Recent Matters, My Matters, Recent Folders, folder children, `/search` and `/lookup`, the properties form with required fields and inherited values, filing (`POST /documents`, with `checkOut`), check-out and release, saves as snapshots or versions with `keepCheckedOut`, recent documents and the caller's check-outs. Every rule the old web page enforces holds there (M2 §7).
- **Holds are per user, not per device** (M2 follow-up). Two of one person's computers can both hold and save a document, and the holder can release it from any of them (`CheckoutResource` ~104).
- **A save on a free document checks it out first and saves** (`VersionResource` ~202). So nothing refuses a save after an admin released the check-out, or after the holder checked the document in from the web page.
- **A save renames the document** to the file part's name, unless the request's `name` says otherwise (`VersionResource.name`, ~292). Only the extension can't change, so **the format chosen at the first save is the document's for life.**
- **A save takes at least about 1.1 seconds** on the server (CE's version write), and the API answers only once it's stored (M2 follow-up).
- **Version numbers.** A save's new number counts from `version`, not `fileVersion`: a snapshot adds 1 to `version`'s minor number, and a new version takes the next major number with minor 0. The two differ after a change of properties, a move or a rename (M2 §6.6). M2 left an `ifVersion` precondition to M3.
- **`version` can move while you hold a check-out.** Anyone with Write can change a checked-out document's properties, or rename it, in the web page: `DocumentServiceImpl.save` (~1276) checks only Write, and CE's update and rename check only whether the document is immutable (`DocumentManager` ~785, ~1417).
- **The doc # is assigned when a new document is stored,** whatever the client sends (`DocNumberListener.beforeStore`, ~54). A client can't know a new document's number before filing it.
- **Dates.** `type: date` property values are instants, so a date-only value set at midnight UTC shows as the day before west of UTC (M2 §6.7). M3 decides the convention.
- **`hold.since`** is the latest check-out or lock event, so it moves on every save that keeps the document checked out (M2 §6.4).
- **No thumbnails through the API.** `healdan-api` serves none, and CE's `/thumbnail` signs in only through `SessionFilter`, which refuses device tokens (M2 §5.1).
- **The doc # grammar** (`DocNumberQuery`) accepts `#1234`, `#1234.2`, `#1234v2` and `#1234v2.1`, but not `#1234 v2.1` with a space, which is the title bar's form.

**Properties and search:**

- **The profile has no Comments** (`ProfileSeed` ~82–104). CE's document `comment` is the latest version's comment, which each check-in replaces, though the full-text index includes it (`HitField.COMMENT`).
- **Text properties are indexed only as exact, case-sensitive fields** (capability map §2.3), so a word inside a longer text property isn't found by the Documents search.
- **Favourites already exist.** CE's bookmarks hold folders and documents (`ld_bookmark`, feature 53, on), and M1's My Matters is the user's folder bookmarks on matter workspaces (`navigation.MyMatters`). Every other bookmark shows in the old web page's Bookmarks section. The API has no call for them yet; the inventory tagged them M6.

**The old web page:**

- **CE's "Edit with Office"** is a toolbar button, a right-click item and a Tools menu item, all hidden behind feature 20 (OFFICE), which nothing declares.
  - Each opens an `ldedit:` link to a launcher Healdan doesn't ship, with the user's CE session id in the link (`Util.openEditWithOffice` ~827, `MainMenu.getOfficeMenuItem` ~484).
  - The toolbar button and right-click item are on only for one unlocked file the user can write and download, among `Util.isOfficeFile`'s extensions (~76), which include Visio and Project but not `.docm`, `.dotx`, `.ott` or `.pptm` (`DocumentToolbar` ~480 and ~696, `ContextMenu.applyOfficeSecurity` ~384).
  - Every caller passes CE's internal document id, not the doc # (`DocumentToolbar` ~482, `ContextMenu` ~209, `TaskDetailsDialog` ~873).
  - The Tools menu item also needs menu −1090 (`Menu.OFFICE`), which has no row in `ld_menu`, so declaring feature 20 doesn't show it.
- **Double-click** shows a preview, or downloads when `gui.doubleclick` is `download` (`DocumentsListPanel` ~72).

**The platforms:**

- **Office web add-ins load their pages over HTTPS** from a server the Mac trusts (M2 §3.2). Office.js has no event for a save or a close, can't set the window title, can't take over Word's own commands, and can't save an open, never-saved document to a path of its choosing.
- **Word for Mac is sandboxed.** It asks the user to "Grant File Access" for files outside the places it may use, and it takes the window title from the file name. Another app that drives Word through AppleScript needs the user's one-time Automation consent.
- **macOS 27 runs only on Apple silicon** (macOS 26 was the last release for Intel Macs).
- **LibreOffice warns when saving in a non-ODF format** ("Keep current format") through its own Save command.

### 3.2 The three setups (owner)

| Setup | System | App |
|---|---|---|
| **U** | Ubuntu 26.04, GNOME | LibreOffice 26.2.6 |
| **M-LO** | macOS 27, Apple silicon | LibreOffice 26.8.1 |
| **M-W** | macOS 27, Apple silicon | Word for Mac, Microsoft 365 (and Excel and PowerPoint) |

Platform order is the owner's: LibreOffice on Linux and macOS first, then Microsoft 365 on the Mac. The LibreOffice extension supports LibreOffice 26.2 and later (call).

### 3.3 Network and signing

- **HTTPS.** Production servers sit behind Nginx Proxy Manager with a Let's Encrypt certificate (owner). The helper refuses plain HTTP to anything but `localhost`, so development stacks still work, and it never skips certificate checks (call).
- **Apple signing.** The owner has an Apple Developer account, so CI signs and notarises the macOS helper (owner). Builds without the secrets, such as pull requests, are unsigned. The owner's Developer ID certificate is on the Mac this design was written on, so the trial can sign too (§12.4).

## 4. The pieces

| Piece | What it is | Where it lives (call) |
|---|---|---|
| **Healdan Helper** | The app on each computer: sign-in, links, local copies, saves and closes, the windows | `clients/helper/` (a .NET solution) |
| **LibreOffice extension** | Toolbar and menu, taking over Open, Save and Save As, the title bar, the footer | `clients/libreoffice/` (Python, packaged as `.oxt`) |
| **Word add-in** | Ribbon buttons in Word, Excel and PowerPoint, and the footer in Word | `clients/office/` **(trial)**: an Office web add-in or a template add-in, §7 |
| **Server additions** | Search within a folder, New Folder, favourites, device holds, save preconditions, stamping, thumbnails, Comments, the title's `#` form | `healdan-api`, `healdan-core` (§9) |
| **Open in App** | CE's hidden "Edit with Office", shown and pointed at the helper | `healdan-core`'s `plugin.xml` and small hooks in the old web page (§8) |

The name "Healdan Helper" is a call; the owner may rename it.

## 5. The helper

### 5.1 What it is

- **C# on .NET, with Avalonia for its windows (owner).** .NET 10, the current long-term release, and the current Avalonia release; the plan pins both (call).
- **Runs in the menu bar (macOS) or the top bar's tray (Ubuntu)**, from sign-in to the computer until quit. Its menu shows who is signed in, the documents open, any saves waiting to be sent, Open from Healdan, Settings, Sign Out and Quit (call).
- **One server per helper** (call). A person with two Healdan servers isn't a case M3 serves.
- **Quiet when things work.** It notifies only when something needs the user: a read-only open, a save waiting for the server, a check-out that moved, an error (call).
- **Split for testing (call):** a core library with no windows (the API client, local copies, the open-save-close cycle) and the Avalonia app on top. The core is what the automated tests drive (§14.1).

### 5.2 Signing in

- The first run asks for the server's address, a username and a password. The helper calls `GET /server` to check it's Healdan, then `POST /auth/tokens` with the name `Healdan Helper on <computer name>` and this computer's device id, and keeps only the token (M2 §5, §9.4).
- **The device id** is a random id the helper makes once per computer and user, and keeps beside its settings. It isn't a secret. Signing in again, after a `401`, a password change or Sign Out, sends the same id, so the documents this computer holds stay its own (call).
- **The token lives in the system keychain:** the macOS Keychain, and the Secret Service (GNOME Keyring) on Ubuntu **(trial, T10)**. Never in a file.
- A `401` at any time means the token has expired or been revoked; the helper asks for the password again and keeps any saves waiting meanwhile.
- Sign Out revokes the token (`DELETE /auth/tokens/current`), after the helper has sent any waiting saves or the user has chosen to leave them as local copies.
- M4 replaces the password with the identity provider's pop-up, ending in the same device token (M2 §5.6).

### 5.3 Links from the web page

- **The form:** `healdan://open/<docNumber>?server=<host>`, with `&readonly=1` for a read-only copy (call).
- **No credentials in a link,** unlike CE's `ldedit:` links, which carried the CE session id. The helper uses its own token.
- **The helper acts only on its own server.** A link naming another host is refused with a message, so a link can never open #1234 from the wrong server (call).
- **A link can only open.** It never saves, files, releases or checks anything in, and while one of the helper's windows is waiting for an answer, further links are dropped (call).
- **No extra confirmation (call).** Opening checks the document out, as the owner's flow wants. Browsers ask before a site hands a link to an app, and remember the answer per site, so a hostile page can't fire links silently; the server in the link must also match. The residual risk is that a page the user has allowed could check out documents the user may edit. It's recorded in the Install page's security notes.
- Registered as the handler for `healdan:` by the macOS app bundle and by the Ubuntu package's desktop entry **(trial, T1)**.

### 5.4 Local copies

- **One folder per open document,** inside a per-user folder only that user can read (call). The location on macOS is one Word opens without asking for access **(trial, T6)**.
- **The file name is the title bar (owner):** `<name> #<doc #> v<version>.<ext>`, for example `Lease agreement #1234 v2.1.docx`. `<name>` is the document's name without its extension, with characters the file system can't hold replaced, shortened so the whole name fits. `<version>` is the version of the content the user is editing: `fileVersion` after each confirmed save.
- **Saves send the document's own name** (`name`), so the `#1234 v2.1` in the local file's name never becomes the document's name (§3.1).
- **A record beside each copy** says which document and version it is, whether it's checked out on this computer, and whether it has changes the server hasn't confirmed. It survives the helper restarting (call).
- **When a copy goes:** a read-only copy when its window closes; an edited copy once the server has confirmed the check-in. A copy with unconfirmed changes is never deleted by the helper; the menu lists it until the user sends it or files it (owner).

### 5.5 Opening, saving and closing (owner, with calls on the mechanics)

**Opening** (from a link, the Open window or the app's Open):

1. The helper reads the document (`GET /documents/{n}`).
2. If the caller can't edit it, or someone else holds it, or the user chose read-only, it downloads a read-only copy, opens it, and says who has it checked out.
3. If it's checked out to the user on another computer, the warning in §5.10 comes first.
4. Otherwise it checks the document out from this computer (`POST …/checkout`), downloads it and opens it in the app chosen for that kind of file (§5.11).

**Saving while editing:**

- Each save in the app writes the local copy as usual, and the helper sends it as a snapshot (`POST …/versions`, `kind: snapshot`, `keepCheckedOut: true`, with `name`, `ifVersion` and `ifHeld`, §9.5).
- The app never waits for the server: the user's save is finished as soon as the local file is (call; a server save takes over a second).
- The helper sends a file only once the app has finished writing it, and once per save, never a half-written file **(trial, T3)**.
- When the server confirms, the title bar and the footer on screen move on (§5.7, §5.9). Saves made while one is being sent are queued and sent in order; a newer save waiting can replace an older one that hasn't started (call).

**Save as New Version** (a button): if the app has unsaved changes, the button saves them first, and the helper sends that save as `kind: version` instead of a snapshot. Without changes, it sends the current file as a version. The title bar moves to `v3.0`.

**Save as New Document** (a button, and the app's Save As on a Healdan document, §6.2): the Save to Healdan window opens with the current folder and properties filled in. Saving files the current content as a new document (`POST /documents`, `checkOut: true`), releases the original without saving anything to it, and the open window becomes the new document, with its own title and file (call). The server stamps the new document's own number into it (§9.7). The original keeps its last saved version.

**Properties** (a button): the Properties window shows the open document's properties (`GET …/properties`) and saves changes (`PATCH`). On a document that isn't in Healdan yet, the button opens Save to Healdan.

**Closing:** the helper notices the window closed **(trial, T4)**. If the copy has changes the server hasn't had, it sends them with `keepCheckedOut: false`, which checks the document in. If not, it releases the check-out (`DELETE …/checkout`). Then it deletes the copy. Both are refused if this computer no longer holds the check-out (§9.4), and then the copy stays (§5.10).

### 5.6 Saving a new document (owner)

1. The user presses Save on a document that isn't in Healdan (or Save to Healdan, where the app won't let Healdan take Save over, §6, §7).
2. The Save to Healdan window opens (§5.8).
3. **The format (owner, 2026-10-24).** The window offers the app's own format first and selected: `.odt`, `.ods` or `.odp` in LibreOffice, and `.docx`, `.xlsx` or `.pptx` in Word, Excel and PowerPoint. The other family's format is the alternative (`.docx` from Writer, `.odt` from Word, and so on). The choice is only offered here, because a document's format can't change later (§3.1).
4. On Save, the app writes the document in the chosen format, and the helper files that file (`POST /documents`, `checkOut: true`). The server gives it its doc # and stamps it (§9.7).
5. The open document becomes the local copy: the app saves it under the local copy's name, so the title bar reads `Lease agreement #1234 v1.0`, and later saves are noticed like any other **(trial, T6)**.
6. "Save to local computer" in the window drops back to the app's own save box.

### 5.7 The title bar

- **Format (owner):** `Lease agreement #1234 v2.1`. The version is the content's: after a save confirmed as 2.2, it reads `v2.2`, and after Save as New Version, `v3.0`.
- **A read-only copy** reads `Lease agreement #1234 v2.1 (read-only)` (call).
- **LibreOffice:** the extension sets the window title directly, so it moves on without renaming the file **(trial, T2)**.
- **Word:** the title is the file name, so moving on means Word saving the open document under the new name. The trial finds out whether that works without prompts or a visible reload **(trial, T2)**. If it doesn't, Word's title keeps the version the document opened at until it's reopened, and the helper's notification gives the new number (fallback, call).
- **Searching for it.** `#1234 v2.1` typed into any search box, or sent to `/lookup` or `/search`, finds the document and version (§9.6).

### 5.8 The windows (owner: drawn by the helper)

Each window is an Avalonia window that comes to the front over the app **(trial, T9)**.

**Save to Healdan** (mockup reviewed 2026-10-24, modelled on iManage's):

- **Toolbar:** back, forward, up; a scope picker (Folders, Documents, Matters, Clients); and a search box that searches within the place shown ("Search folders within 998.1 – Healdan Demo") (§9.1).
- **Left pane:** Recent Folders, Recent Matters, My Matters, Clients, Favorites (folders only). **Clients** sets the scope picker to Clients for a search by client number or name; it isn't a list of every client (call, §2).
- **Middle pane:** a breadcrumb from the matter down, then the place's subfolders and documents, with Title, Version, Doc # and Edited columns, and a New Folder button (§9.2). A star adds or removes a folder from Favorites (§9.3).
- **Right pane:** the properties form for a new document in the selected folder (`GET /folders/{id}/form`): Name, Client and Matter (read-only, "from the matter"), Author and Typist (default: the user), Class (with where it's inherited from), Subclass narrowed by Class, Comments (§9.9), and any other attribute the profile has. Required fields are marked. Then the format (§5.6).
- **Bottom:** "Save to local computer", Cancel, Save. Save turns on only once a folder that can take documents is selected (`canFile`) and every required field has a value (owner, roadmap).

**Open from Healdan** (mockup reviewed 2026-10-24):

- **Toolbar:** the same as Save's, defaulting to the Documents scope. `#1234` with Enter jumps straight to the document.
- **Left pane:** Recent Documents, Checked Out to Me, Favorites (folders and documents), then Recent Matters, My Matters and Recent Folders for browsing.
- **Middle pane:** Title, Doc #, Version, Matter and Edited columns, and under the title who has the document checked out, or "checked out to you on MacBook".
- **Right pane:** a thumbnail (§9.8), the main properties, and the version list. Picking an older version opens it read-only (call; `GET …/content?version=`).
- **Bottom:** "Open from local computer", "Open read-only", Cancel, Open.

**Properties:** the same form as Save's right pane, for the open document.

**Checked out on another computer** (mockup reviewed, §5.10).

**Sign In** and **Settings** (the server's address, which app opens each kind of file, §5.11) (call).

### 5.9 The doc ID footer

- **Insert Doc ID** (Writer and Word) puts a field in the footer that shows the document property `Healdan Doc ID`, whose value is the title's form, `#1234 v2.1` (owner; call on the property's name). It replaces M1's `# 1234.2.1` form for the footer, so the footer, the title bar and the search box agree (owner, 2026-10-24).
- **The stored file is always right (call).** The server stamps each file it stores through the API with the doc # and version it's stored as (§9.7), so Healdan's copy, its preview and every download show the right number, whoever opens them. That covers a new document, whose number only the server knows, and Save as New Document, whose content arrives with the old document's number.
- **On screen,** the add-in sets the property to the confirmed number after each save, and refreshes the field, where the app allows it without marking the document changed **(trial, T7)**. Where it can't, the open window's footer catches up the next time the document opens; the stored file is right either way.

### 5.10 When things go wrong (owner)

- **The server can't be reached when you save.** The save lands in the local copy as usual. The helper keeps trying, shows in its menu that a save is waiting, and the document stays checked out to you. Nothing is lost.
- **The app or the computer crashes.** The document stays checked out. When the helper next runs, or notices the window has gone **(trial, T4)**, it offers to reopen the local copy or to send it and check it in.
- **Your check-out ended** (an admin released it, someone checked it in from the web page, or another of your computers took it over). The next save, check-in or release from this computer is refused (`409 not-held` or `held-elsewhere`, §9.4, §9.5). The helper keeps your copy and offers Save as New Document.
- **Checked out to you on another computer** (owner, with the warning the owner added): the helper says "#1234 is checked out to you on MacBook (Word)", when it was last saved to Healdan, and that edits on that computer which haven't reached Healdan won't be in this copy. The choices are Open Read-Only, Edit Here Instead (which moves the check-out to this computer, §9.4) and Cancel. The other computer keeps its copy, and its next save or close is refused as above.
- **The version moved on** (`409 version-moved`, §9.5). Someone with Write changed the document's properties or name in the web page while you held it. When the document is still checked out to this computer and its `fileVersion` is still the one the copy started from, nobody has stored newer content, so the helper re-reads the version and resends once (call). Otherwise it keeps the copy, tells the user, and offers Save as New Document.
- **The token expired or was revoked:** the helper asks for the password again, and saves wait meanwhile. The device id is kept, so the documents this computer holds stay its own (§5.2).

### 5.11 Which app opens a file

- **Kinds:** documents (`.doc`, `.docx`, `.docm`, `.dotx`, `.dotm`, `.rtf`, `.odt`, `.ott`), spreadsheets (`.xls`, `.xlsx`, `.xlsm`, `.ods`) and presentations (`.ppt`, `.pptx`, `.pptm`, `.odp`) (call). Other files open read-only in the system's default app, with no check-out.
- **Settings choose LibreOffice or Microsoft Office for each kind** on macOS; the default is the system's default app for the file type. Ubuntu has only LibreOffice (call).

### 5.12 Packaging and releases (call, with the owner's signing)

- **macOS:** an Apple-silicon app bundle that registers `healdan:`, signed with the owner's Developer ID and notarised, in a disk image. CI signs only when the secrets are present.
- **Ubuntu:** a `.deb` for amd64 and arm64 that installs the helper, registers `healdan:` with a desktop entry, starts it at sign-in, and depends on LibreOffice's Python support **(trial, T1)**.
- **The add-ins come with the helper.** On first run, and after each upgrade, the helper installs or updates the LibreOffice extension and the Word add-in for the user (§6.1, §7). LibreOffice's extension tool can't install into a running LibreOffice, so the helper waits until it's closed, or asks **(trial, T12)**.
- **Releases.** Healdan has no release tags of its own yet; the repository holds upstream's. The helper is versioned on its own (`1.2.3`, valid as a macOS bundle version and a Debian version), from `helper-v*` tags, and CI attaches the disk image and both `.deb` files to that tag's GitHub release.
- **The helper and the server.** The helper checks `GET /server`'s `apiVersion` and refuses a server it doesn't speak. There are no compatibility promises until the roadmap is complete (M2 §4.1), but the helper lives in the same repository, so a change that would break it updates it in the same pull request, and a new helper release follows.

## 6. The LibreOffice extension

### 6.1 What it is

- **Python, using LibreOffice's UNO interface,** packaged as an `.oxt` (call; no Java, owner). The same extension runs on Ubuntu and macOS, in Writer, Calc and Impress.
- **Slim:** it talks to the helper, which does the work (owner). It holds no token and calls no server.
- **Installed by the helper** for the user, with LibreOffice's own extension tool (call).

### 6.2 What it does

- **A Healdan toolbar and menu:** Save to Healdan, Open from Healdan, Save as New Version, Save as New Document, Properties, and Insert Doc ID (Writer only).
- **Takes over the app's own commands (owner):**
  - **Open** shows Open from Healdan.
  - **Save** on a document that isn't in Healdan shows Save to Healdan. On a Healdan document, it saves the local copy in the document's own format without LibreOffice's "Keep current format" question, and the helper sends it.
  - **Save As** on a document that isn't in Healdan shows Save to Healdan; on a Healdan document, it's Save as New Document.
  - Each window's "local computer" link runs LibreOffice's own box instead.
  - **(trial, T5)** confirms all three can be taken over, that the format question can be skipped, and that LibreOffice's Recent Documents list reopens a local copy through the helper rather than behind its back.
- **Sets the title bar** and refreshes the footer when the helper says so (§5.7, §5.9).
- **Tells the helper when a document closes,** where LibreOffice reports it **(trial, T4)**.

## 7. The Word add-in (trial)

Word for Mac comes second in the platform order (owner). Excel and PowerPoint for Mac get the same buttons; the footer is Word only.

**What it must do (owner):** a Healdan group on the ribbon with the same buttons as LibreOffice's toolbar, Insert Doc ID in Word, and taking over Open, Save and Save As where Word allows. **Where Word won't let Healdan take a command over, Healdan's button sits beside Word's own** (owner).

**Which kind of add-in is the trial's question (T5, T6, T8):**

| | An Office web add-in (Office.js) | A template add-in (`.dotm`, with ribbon XML) |
|---|---|---|
| Microsoft's direction | current | old, still supported on the Mac |
| Takes over Save, Save As, Open | no | likely, through Word's command and event hooks |
| Saves a new document under the local copy's name (§5.6) | no; the helper must drive Word through AppleScript, which needs the user's Automation consent, or open the filed copy in a new window and close the original | yes, from the add-in itself |
| Talks to the helper | over HTTPS from a web view to the computer itself (T8) | through `AppleScriptTask`, which runs a script the helper installs |
| Excel and PowerPoint | one add-in for all three | a separate add-in for each (`.xlam`, `.ppam`) |
| Where its pages live | served by the Healdan server, under `/api/addin/` (call) | nothing served; installed with the helper |
| Installing it | sideloaded into each app by the helper (T12); if that doesn't hold up, deployed from the owner's Microsoft 365 admin centre | copied into each app's startup folder by the helper (T12) |

The spec's call, before the trial: **an Office web add-in, unless the trial shows the template add-in does T5 and T6 better and the web add-in can't reach the helper or save a new document cleanly.** The trial's result fills §12.5, and this section is rewritten around the winner, for Word, Excel and PowerPoint, before approval.

**Either way:** the helper installs the add-in for the user, so no admin deploys anything unless sideloading fails (call).

## 8. Open in App, in today's web page (owner)

- **Feature 20 is declared** in `healdan-core`'s `plugin.xml`, which shows CE's "Edit with Office" toolbar button and right-click item.
- **The Tools menu item stays hidden.** It also needs menu −1090, which has no row (§3.1), and the toolbar and right-click item are enough (call).
- **The label becomes "Open in App"** in the English messages (call).
- **They open the helper.** `Util.openEditWithOffice(docId)` calls a Healdan hook, which finds the document's doc # from CE's id (callers pass the id) and opens `healdan://open/<docNumber>?server=<host>`, with no session id in the link. Every caller goes through `Util`, `TaskDetailsDialog`'s included. If nothing seems to happen, the hook's message points to the docs page for installing the helper (§13.2).
- **On for anything the helper opens, including while checked out.** CE turns the items off unless the document is unlocked and the user has Write. Healdan's conditions are: one document, of the kinds in §5.11 (Healdan's list, not `Util.isOfficeFile`'s), that the user can download. The helper then decides between editing and a read-only copy, so others get a read-only copy and the holder can reopen after a crash.
- **Double-click is unchanged** (owner, §2).

## 9. Server additions (calls, from what the windows need)

Every endpoint goes into `openapi.yaml` in the same change, with tests, as M2's do. Paths are under `/api/v1`.

### 9.1 Searching within a place

`GET /search` takes an optional `within=<folderId>`. With it, the `folders` and `documents` scopes return only what's inside that folder, at any depth. It's the Save window's "Search folders within 998.1 – Healdan Demo" (owner, 2026-10-24). A folder the caller can't read is `404`, and a Store is `409 not-browsable` (M2 §6.2).

### 9.2 New folders

`POST /folders` with `{ "parentId", "name" }` creates a subfolder and returns it (`201`). The same rules as the old web page apply, through the same CE and Healdan code: the Add permission on the parent; never in a Store, at a matter's top level (admins only, M1 §7.2) or in a search folder; a name of at most 255 characters, cleaned as the old web page cleans it. The new folder inherits its parent's properties and permissions, as any new folder does (owner, 2026-10-24: New Folder is in M3).

### 9.3 Favourites (owner, 2026-10-24: favourites are CE's bookmarks)

- `GET /me/favorites`: the user's bookmarks except those on matter workspaces (which are My Matters), each a folder or a document in the same shape as elsewhere, leaving out what the user can't read.
- `POST /me/favorites` with `{ "folderId" }` or `{ "docNumber" }` adds one; `DELETE /me/favorites/{id}` removes one.
- Built on `BookmarkDAO` in `healdan-core`, beside `navigation.MyMatters`, so they're the same bookmarks the old web page shows. The inventory's bookmark entries move from M6 to M3.

### 9.4 Which computer holds a check-out (owner)

- **A device is a computer's device id** (§5.2), not a token, so signing in again on the same computer keeps its check-outs. `POST /auth/tokens` takes an optional `device` (`{ "id" }`), and each token carries it.
- **The server records the device** whose token checked a document out through the API, in a new table (`hd_hold_device`, Flyway `V4`), with the app the helper named when checking it out. A check-out from the old web page has no device.
- **`hold.device`** (`{ id, name, app }`, the name being the token's) is added to a document's `hold` when the caller is the holder, so the helper can say "on MacBook (Word)". Others still see only who holds it.
- **From another of the holder's devices,** `POST …/checkout` answers `409 held-elsewhere` with the `hold`. Sending it again with `{ "move": true }` moves the check-out to the caller's device, recorded in the history.
- **Saves, check-ins, releases and property changes** from a device that doesn't hold the check-out are `409 held-elsewhere`. A hold with no device (the old web page's) works from any of the holder's devices, as in M2, and the first save through the API records the device.
- **Only the API checks devices (call).** Device holds keep one person's computers from overwriting each other through the helper. The web page acts for the person whichever computer they're at, so its check-in, unlock and saves aren't device-checked, and no upstream file changes for this. When the web page ends a check-out, the helper's next save is `not-held` (§9.5).
- A record whose check-out has since ended or changed hands never blocks anything; only the current hold counts. The builder chooses how stale records are cleared.

### 9.5 Preconditions on saves

The JSON part of `POST …/versions` takes two optional preconditions. The helper always sends both.

- **`ifVersion`:** when the document's `version` isn't that value, the save is `409 version-moved`, and nothing is stored.
- **`ifHeld: true`:** when the document isn't checked out to the caller (on this device, if the hold has one), the save is `409 not-held`, and nothing is stored. Without it, M2's behaviour stays: a free document is checked out and saved.

### 9.6 The title's `#` form

`DocNumberQuery` accepts a space before `v`, so `#1234 v2.1` works wherever `#1234v2.1` does: the old web page's search box, `/lookup` and `/search`.

### 9.7 Stamping the doc ID

- **When:** `POST /documents` and `POST …/versions` stamp a file that carries the `Healdan Doc ID` property, before it's stored. A file without the property is stored as sent, byte for byte.
- **What:** the property becomes the doc # and version the file is stored as, in the title's form (`#1234 v2.1`), and the shown text of each field that displays it is updated to match, so any app or preview shows it without updating fields.
- **Formats:** `.docx`, `.docm`, `.dotx`, `.dotm`, `.odt` and `.ott` (call). Other formats are stored as sent.
- **Filing** knows the new document's number before the file is stored, so version 1.0 carries its own number with no extra version. The builder chooses how, as long as the number still comes only from Healdan's counter (`DocNumberListener`), is never reused, and isn't taken for a filing that fails before it's stored.
- **The response** carries the stored `fileVersion` as always, which the helper uses for the title and the on-screen footer.
- The stamping code is in `healdan-core` (call), so a later route (the new web interface's uploads, say) can use it.

### 9.8 Thumbnails

`GET /documents/{n}/thumbnail` returns the document's current thumbnail as an image, read through the same checks as its content (M2 §6.4). A document whose thumbnail hasn't been made yet (M1.5 makes them in the background) is `404`, and the Open window shows a placeholder.

### 9.9 Comments (owner, 2026-10-24)

- **A new property, Comments,** on Healdan's profile: optional, editable, free text of up to 4000 characters, shown after Subclass. A new seed step adds it (a shipped seed never changes, CLAUDE.md), so existing installs get it too. The old web page and the API's form show it like any other property.
- **Searchable by its words:** the Documents search finds a document by a word in its Comments. Text properties are indexed only as exact fields today (§3.1), so the builder chooses how, for example by indexing Comments with the document's text (call).
- iManage's Comments field in its Save dialog is the model (owner).

### 9.10 Answers to M2's open questions

| M2 follow-up | M3's answer |
|---|---|
| Holds are per user, not per device | The server records the device, and moving a check-out between computers warns first (§9.4, §5.10; owner) |
| A save takes at least about 1.1 s | The helper sends saves in the background; the app never waits (§5.5) |
| The footer's number comes from `version`; `ifVersion` is M3's call | The server stamps the number it stores the file as (§9.7), and `ifVersion` refuses a save that started from an older version (§9.5) |
| Browser clients send `name` | The helper sends `name` with every save too, because the local copy's file name carries the doc # and version (§5.4) |
| `type: date` values are instants | The helper treats a date property as a calendar date: it shows the UTC date and sends midnight UTC, so a value the helper set reads the same anywhere. The old web page's day-before display west of UTC is pre-existing and recorded in Known Limitations (call) |
| `hold.since` moves on every save | The helper never shows "checked out since". It shows when the document was last saved, from the versions (call) |
| Tokens work only at `/api` | The helper uses only `/api`, so no allowance is needed anywhere else |

## 10. Security (calls)

- **The token** is only ever in the system keychain and in memory, and never in a log.
- **The channel between the add-ins and the helper** is on the computer itself and refuses other users of the same computer (for example, a secret in a file only the user can read) **(trial, T8)**. Through it, an add-in can ask the helper to show a window, save or open the document it has, or set its title; it can't read the token.
- **Links** carry no credentials, can only open documents, and only from the helper's own server (§5.3).
- **Local copies** are in a folder only the user can read. Read-only and checked-in copies are deleted when done; copies with unconfirmed changes stay until the user decides (§5.4).
- **HTTPS only,** apart from `localhost`, with the system's certificate checks (§3.3).
- **CE's `ldedit:` links put the session id in the URL;** Healdan's replacement doesn't (§8).
- **Stamping changes only the one property and the text of the fields showing it,** in files that already carry the property (§9.7).

## 11. Code organisation (calls)

- **`clients/`** is new, at the repository's root:
  - `clients/helper/`: the .NET solution, with `Healdan.Helper.Core` (no windows), `Healdan.Helper` (the Avalonia app), their tests, and the packaging scripts;
  - `clients/libreoffice/`: the extension's Python code, its tests and its packaging;
  - `clients/office/`: the Word, Excel and PowerPoint add-in.
- **The API client** is written against `openapi.yaml`. Whether its models are generated from the file or written by hand is the builder's choice, as long as a test fails when the helper calls an operation or reads a field the file doesn't describe.
- **CI** gets a workflow for `clients/` that builds and tests on Ubuntu and macOS for pull requests, and signs, notarises and attaches the packages for `helper-v*` tags.
  - `code-changed.sh` learns that a pull request touching only `clients/` needs no Java build, and one touching only the server needs no client build, so neither waits for the other (the owner wants CI's wait short). The required checks still report.
  - So a pull request that changes only `clients/` doesn't build the Docker image, and the helper's test against the real server (§14.1) runs on master after the merge, and on pull requests that change the server. Its fake-server tests run on every client change. (accepted gap)
- **Server code** goes where M2's does: endpoints in `healdan-api`; domain code (favourites, device holds, stamping, the Comments seed) in `healdan-core`.

## 12. The trial build

### 12.1 Its question

Can a C# and Avalonia helper with slim add-ins give the flow in §5 on all three setups, and which mechanism works for each part on each? The helper itself is settled (owner); the trial proves what the platforms' documentation can't promise, under the conditions a real install has.

### 12.2 What it must show

Each row passes or fails on each setup (§3.2). The person checking watches the screen; a row isn't passed on the strength of a log. Every prompt the system or the app shows, at any step, is written down.

| Row | What must happen to pass |
|---|---|
| **T1 Links** | A `healdan://open/1234` link clicked in the browser (Firefox on Ubuntu; Safari and Chrome on macOS) reaches the helper, started or not, and the document opens in the right app within a few seconds. Installed from the trial's package, not run from a build folder. |
| **T2 Title bar** | The title reads `Lease agreement #1234 v2.1`. After a save confirmed as 2.2 it reads `v2.2`, and after Save as New Version `v3.0`, without closing the document, without a prompt and without the document visibly reloading. |
| **T3 Saves** | Each save in the app is noticed once and sent once. The file sent is complete (the apps' write-then-rename saves included), and autosave and recovery files are ignored. |
| **T4 Closing** | Closing the window is noticed within 5 seconds. After a Force Quit, the helper notices within 30 seconds and offers to reopen or check in. |
| **T5 Taking over commands** | LibreOffice: Open, Save and Save As show the helper's windows, each "local computer" link works, a `.docx` saves without the "Keep current format" question, and Recent Documents goes through the helper. Word: record which of Open, Save and Save As each kind of add-in (§7) can take over. |
| **T6 New documents** | After Save to Healdan, in each format offered, the open document is the local copy: title right, and the next save noticed. Record every prompt: Word's "Grant File Access" fails the row; an Automation consent is recorded as a once-only install step. For the web add-in, try the helper driving Word through AppleScript, then the filed copy opening in a new window. |
| **T7 Footer** | Insert Doc ID adds the field. A file stamped as §9.7 describes (by a script standing in for the server) shows the right number in LibreOffice, in Word and in a LibreOffice-converted preview. Record whether the on-screen field can refresh after a save without marking the document changed. |
| **T8 The add-ins reach the helper** | The extension and the add-in can call the helper, and a request without the user's secret is refused. For the web add-in: its HTTPS page can call the helper on the computer. Record every certificate or trust step that was done by hand, and what an installer would have to do instead. |
| **T9 Tray and windows** | The helper's icon and menu show in Ubuntu's top bar and the macOS menu bar, and its windows come to the front over LibreOffice and Word. |
| **T10 Keychain** | The token is stored in, and read back from, the Keychain and GNOME Keyring without a prompt at every start, by the signed helper. |
| **T11 The other apps** | T2, T5 and T6 again in Calc and Impress, and in Excel and PowerPoint, with the add-in kind T5 chose for Word. |
| **T12 Installing the add-ins** | The helper installs the extension into LibreOffice and the add-in into Word, Excel and PowerPoint, and they're still there after the app and the computer restart. Record what happens when LibreOffice is running, and whether sideloading needs a step in each Office app's My Add-ins. |

### 12.3 If a row fails

- **T2 in Word:** the fallback in §5.7 (Word's title shows the version it opened at).
- **T4:** the extension's or add-in's own close event, then polling whether the app still has the file open; if Word gives neither reliably, the menu's "Check In" for that document.
- **T5 in Word:** Healdan's buttons beside Word's own (owner).
- **T6 on macOS:** a different local-copies folder; for the web add-in, the template add-in. If every way prompts, back to the owner.
- **T8 for the web add-in:** the template add-in.
- **T11:** a different add-in kind for Excel and PowerPoint than for Word, if that's what passes.
- **T12:** for LibreOffice, installing once LibreOffice is closed; for Office, deployment from the owner's Microsoft 365 admin centre, which changes §5.12 and the Install page.
- **T1, T3, T9 or T10 on any setup:** back to the owner, because the helper itself is in question.

### 12.4 How it runs (owner: before this spec is approved)

- **Throwaway.** The trial's code lives on a scratch branch that's never merged, and is deleted once §12.5 is filled in. Nothing is built on it.
- **Like a real install.** The macOS helper is an app bundle signed with the owner's Developer ID certificate, which is on the owner's Mac, so the Keychain and the consent prompts behave as they will for the release.
- **The macOS rows** (M-LO, M-W) run on the owner's Mac, which has .NET 10, LibreOffice 26.8 and Word, against a local Docker stack with the smoke fixture. Word's web add-in loads from that stack over HTTPS with a development certificate, and T8 records each trust step that took.
- **The Ubuntu rows** (U) are checked by the owner on their Ubuntu 26.04 machine, from a `.deb` the trial builds and a short checklist, against a stack that machine can reach.
- **A minimal helper:** sign-in, the link, check-out, download, saves, close, the title, and a script standing in for the server's stamping. The windows are placeholders, not the designs in §5.8.

### 12.5 Results

To be filled in by the trial: one row per T1–T12, with U, M-LO and M-W each **pass**, **fail** or **pass with** a named mechanism, the prompts seen, and the decisions they settle (§5.2 keychain, §5.4 location, §5.7 Word's title, §5.9 on-screen footer, §6.2, §7's add-in kind, §10's channel, §5.12's install).

## 13. Upstream files M3 edits, and docs pages

### 13.1 Upstream files

| File | Edit | Why |
|---|---|---|
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/common/client/util/Util.java` | `openEditWithOffice` (~827) calls Healdan's Open in App hook instead of building an `ldedit:` link | §8 |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/document/grid/ContextMenu.java` | `applyOfficeSecurity` (~384): Healdan's conditions (§8) | §8 |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/document/DocumentToolbar.java` | the button's click handler (~480) and its enabling (~696): Healdan's conditions | §8 |
| `logicaldoc-i18n/src/main/resources/i18n/messages.properties` | `editwithoffice=Open in App` | §8 |

There are no server-side upstream edits. The GUI edits are client code, which CI's tests don't cover, so `UPSTREAM_TESTS` is unchanged. CE's `officeaddinhintlauncher` message, which the docs-site work pointed at docs.healdan.com in all 34 bundles, is no longer shown, because Healdan's hook shows its own; the bundles stay as they are.

**Fork-owned files it changes:**

- `healdan-core`: `plugin.xml` (feature 20), `DocNumberQuery` (§9.6), favourites, device holds and Flyway `V4`, stamping and how filing knows its number (`DocNumberListener` if the builder needs it), the Comments seed step and its search;
- `healdan-api`: the endpoints, `openapi.yaml`, and the add-in's files if it's a web add-in;
- Healdan's GUI package: the Open in App hook;
- `docker/tests/test-api.sh` (§14.1), `.github/workflows/` (the clients workflow, and the helper's test in the Docker image workflow) and `.github/scripts/code-changed.sh`;
- `CLAUDE.md`, `docs/reference/healdan-api-inventory.md` (tags and endpoints) and `docs/reference/logicaldoc-ce-capability-map.md` (§5.1: Open in App and the helper; §2.3: Comments).

### 13.2 Docs pages (the docs site, `site/content/`)

**New:**

- `install/office-integration.md`: for whoever runs the server: HTTPS is required, where users get the helper, the Word add-in served from the server (if it's a web add-in), what Open in App needs, and the security notes on links (§5.3).
- `user/office/` (a new section, "Working in Word and LibreOffice"):
  - `_index.md`: how the helper, the add-ins and the web page fit together;
  - `installing-the-helper.md`: macOS and Ubuntu, signing in, signing out, choosing which app opens each kind of file;
  - `saving-to-healdan.md`: the Save window, the format, New Folder, Favorites, required properties and Comments;
  - `opening-documents.md`: Open in App, the Open window, `#` lookup, read-only copies, a document on another computer;
  - `editing-and-versions.md`: saves and snapshots, Save as New Version, Save as New Document, Properties, closing;
  - `doc-id-footer.md`: Insert Doc ID, and what the footer shows;
  - `when-things-go-wrong.md`: no server, crashes, ended check-outs, waiting saves.
- `developer/desktop-clients.md`: building and testing the helper, the extension and the add-in; the clients workflow; releases and signing secrets.

**Changed:**

- `developer/api.md`: search within a folder, `POST /folders`, favourites, devices and `held-elsewhere`, `ifVersion`, `ifHeld`, `version-moved` and `not-held`, stamping, thumbnails, and the `#1234 v2.1` form.
- `developer/continuous-integration.md` and `developer/building-from-source.md`: the clients workflow, `code-changed.sh`'s new split, and where the packages come from.
- `install/reverse-proxy.md`: HTTPS is required for the helper and Word.
- `install/known-limitations.md`: no Word for the web, no Windows yet, the footer in Writer and Word only, stamping only through the helper, dates west of UTC in the web page, and whatever the trial leaves (Word's title, the on-screen footer).
- `user/documents/checking-out-and-checking-in.md`: check-outs from the apps, and on which computer.
- `user/documents/bookmarks.md`: bookmarks are the helper's Favorites.
- `user/finding-documents/finding-by-doc-number/index.md`: the `#1234 v2.1` form.
- `user/working-with-matters/properties/`: Comments, and finding a document by it.

## 14. Verification

### 14.1 Automated

**Server (`healdan-api`, `healdan-core`), in CI as M2's tests are,** for each new endpoint and rule: the success case; `404` for what the caller can't read and `403` for what they can't change; and each rule it can hit:

- `within` limiting `folders` and `documents`, and refusing a Store;
- `POST /folders` refused at a matter's top level for a non-admin, in a search folder and without Add; the new folder inheriting its parent's properties and permissions;
- favourites leaving out matter workspaces and what the caller can't read; adding and removing; the same bookmarks seen by the old web page's Bookmarks;
- devices: `held-elsewhere` on check-out, save, check-in, release and property changes from another device; `move: true`; a new token with the same device id keeping the hold; a web-page check-out working from any device; a stale record not blocking;
- `version-moved` for a stale `ifVersion`, and `not-held` for `ifHeld` on a released document, one checked in from the web page, and one someone else holds;
- stamping: a `.docx` and an `.odt` with the property, filed and saved, carry their own number and version in the property and in the field's shown text; a Save as New Document's file carries the new number; a file without the property is stored byte for byte; filing takes no number when it fails;
- the thumbnail, and `404` before one exists;
- the Comments seed on a fresh and an existing database, and the Documents search finding a word in it;
- `#1234 v2.1` in `DocNumberQuery`, `/lookup` and `/search`;
- the contract: every response checked against `openapi.yaml`, and `RouteCoverageTest` (M2 §4.6).

**The flow on the Docker stack.** `docker/tests/test-api.sh` adds: search within the matter, create a folder, add it to favourites, file a stamped `.docx` into it and check its number, check out from one device, `held-elsewhere` from a second device of the same user, move, `not-held` from the first, a stale `ifVersion` (`409`), then a good save.

**The helper's core** (no windows), in the clients workflow on Ubuntu and macOS (call): version arithmetic and file names; the open-save-close cycle against a fake server and a fake app writing files, including the failure cases in §5.10 (server down mid-queue, a restart with saves waiting, `held-elsewhere`, `not-held`, `version-moved` with and without new content, `401` and signing in again with the same device id); the record beside each copy surviving a restart; never deleting a copy with unconfirmed changes; `name` sent with every save.

**The helper against the real server** (call): the core's cycle run against the Docker stack after the smoke test, in the Docker image workflow, with a fake app (§11 says when it runs).

**The extension's** Python logic has unit tests; whether a headless LibreOffice runs in CI to test the interception is the plan's call. **The Word add-in** is covered by the acceptance walkthrough.

### 14.2 Manual acceptance (owner)

On each setup in §3.2, against the owner's stack over HTTPS, as a non-admin user, with the helper installed from the release's package:

1. Install the helper and sign in. The extension and add-in appear.
2. Create a document, press Save: Save to Healdan opens. Find the matter by search and through Recent Matters; make a new folder and star it; see the required fields and inherited values; write a Comment; keep the app's own format; save. The title reads `… #n v1.0`.
3. Edit and save twice: the title reads `v1.1`, then `v1.2`. Close: it's checked in.
4. In the web page, Open in App: it opens checked out. Insert Doc ID: the footer reads `#n v1.2`. Save as New Version: `v2.0`, in the title, in the footer on screen or after reopening (§12.5), and in the web page's preview. Close.
5. Open from Healdan with `#n`: it opens. As a second user, open it: a read-only copy that says who has it.
6. On a second computer, open it: the warning; Edit Here Instead. Back on the first, save: refused, and Save as New Document is offered.
7. Turn off the network, save, turn it back on: the save arrives. Force Quit the app: the helper offers to reopen or check in.
8. In LibreOffice Writer, save a new document as `.docx`; search the Documents scope for a word from its Comment and find it.
9. In Calc or Excel, and Impress or PowerPoint: save a new file to Healdan and reopen it.

## 15. Left to the builder

- Library choices (HTTP, JSON, keychain access, file watching, the docx and odt stamping), within §5, §9.7 and §10.
- The channel between the add-ins and the helper, within §10 and the trial's result.
- How the API client is kept true to `openapi.yaml` (§11).
- How filing knows its number before storing (§9.7), and how Comments is made searchable by its words (§9.9).
- The menu's exact items beyond §5.1, window sizes, icons and the notification wording.
- How stale device records are cleared (§9.4).
- Whether a headless LibreOffice test runs in CI (§14.1).

## 16. Decisions made in this session

**Owner:**

- The helper: a small app on each computer, in C# on .NET with Avalonia, which draws the Save, Open and Properties windows once for every app; the add-ins stay slim; no Java on the user's computer.
- The flow in §1, modelled on iManage; the Save window's three panes.
- The title bar reads `Lease agreement #1234 v2.1`, and the footer the same.
- Take over the app's Open, Save and Save As, with a way back to the local box; where an app won't allow it, Healdan's buttons sit beside its own.
- A new document's Save opens Save to Healdan; a Healdan document's save sends a snapshot, and the title moves on.
- Each app saves in its own format by default, and the Save window offers the other at the first save.
- A Comments property, as in iManage's Save window.
- The failure behaviour in §5.10, and a warning that edits on the other computer which haven't reached Healdan won't be in the copy when editing moves.
- Search within a matter and New Folder are in M3; favourites are CE's bookmarks; the Security tab and Compare Versions aren't in M3.
- Today's web page keeps double-click as preview; Open in App is CE's hidden Edit with Office; M6 changes double-click.
- Production uses HTTPS from Nginx Proxy Manager with Let's Encrypt; the owner's Apple Developer account signs the Mac helper.
- The setups: Ubuntu 26.04 with GNOME and LibreOffice 26.2.6, and macOS 27 with LibreOffice 26.8.1 and Word.
- The trial build runs before this spec is approved, and the owner checks the Ubuntu rows.

**Calls (open to the owner's review):**

- The name "Healdan Helper" (§4).
- A device id per computer, so signing in again keeps its check-outs (§5.2, §9.4).
- `healdan://open/<n>?server=<host>` links that carry no credentials, can only open, and need no extra confirmation (§5.3).
- Local copies named as the title, one folder per document, a record beside each, never deleted with unconfirmed changes (§5.4).
- Saves sent in the background, queued in order, with `name`, `ifVersion` and `ifHeld` (§5.5, §9.5).
- Save as New Document releases the original unchanged and switches the window to the new document (§5.5).
- "Clients" in the windows' left pane is a search by client, not a list of every client (§5.8).
- Older versions open read-only from the Open window, with a thumbnail from a new endpoint (§5.8, §9.8).
- The server stamps the doc ID into Word and LibreOffice files that carry the property, on every store through the API; filing knows its number first (§9.7).
- A `version-moved` refusal is resent once, only when nobody has stored newer content (§5.10).
- Device holds are checked only on the API; the web page acts for the person on any computer (§9.4).
- Comments is searchable by its words (§9.9: the owner said "add that"; this reads it as a searchable property).
- Settings choose LibreOffice or Microsoft Office per kind of file on macOS (§5.11).
- Packaging: a notarised disk image and amd64 and arm64 `.deb` files, from `helper-v*` tags; the helper installs the add-ins; no self-updating (§5.12).
- Python for the LibreOffice extension (§6).
- An Office web add-in for Word unless the trial says otherwise (§7).
- Open in App on while checked out and for Download alone, the Tools menu item left hidden, and no session id in the link (§8).
- The other server additions in §9, and the answers to M2's questions in §9.10.
- `clients/` at the root, CI split so client and server changes don't wait for each other, and the helper's real-server test running on master for client-only changes (§11).
