# M3 "Office integration": design

- **Milestone:** M3 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-10-24
- **Status:** built on 2026-10-24 and 2026-10-25, following the implementation plan named below, in five pull requests: [Healdan-Project/healdan#70](https://github.com/Healdan-Project/healdan/pull/70) (PR A: the API's additions), [#71](https://github.com/Healdan-Project/healdan/pull/71) (PR B: Open in App in the web page), [#72](https://github.com/Healdan-Project/healdan/pull/72) (PR C: the helper's core, and CI for the clients), [#73](https://github.com/Healdan-Project/healdan/pull/73) (PR D: the helper app, its windows, the LibreOffice extension and the macOS package) and [#74](https://github.com/Healdan-Project/healdan/pull/74) (PR E: the docs, and these records). A final review of the whole branch then found a few problems, fixed in PR D and recorded in PR E: the "final review" notes below. Where the build departs from this spec, the section says "changed in the build". Phase 1's acceptance (§14.2, the plan's Task 21) is pending, and phase 2 starts once the owner's semi-production server is running. Approved by the owner on 2026-10-24. Implementation plan: [`docs/superpowers/plans/2026-10-24-m3-office-integration.md`](../plans/2026-10-24-m3-office-integration.md); its "Spec corrections and calls" section and decision log list where the build departs from this spec.
  - **Builder check.** A reviewer who hadn't seen the design conversation found nine statements the code contradicts, all now corrected (§3.1), and eleven gaps. Two of the gaps were the owner's to answer: the file format at first save, and a Comments property (§5.6, §9.9).
  - **Trial build (§12).** It ran on 2026-10-24 on the owner's Mac, before approval as the owner decided, and answered the questions marked "(the trial)" (§12.5).
  - **Deferred rows.** The owner deferred the trial's Ubuntu rows and its Word add-in rows: "we have proven the concept". The Ubuntu rows wait for the owner's semi-production server; the Word rows go with Microsoft Office, which comes later (§2.1).
- **Inputs:**
  - the roadmap's M3 section, and its glossary;
  - the [M2 spec](2026-10-23-m2-api-foundation-design.md), the API's contract (`healdan-api/src/main/resources/healdan/api/openapi.yaml`) and the M2 plan's follow-ups "For M3's design session" ([plan](../plans/2026-10-23-m2-api-foundation.md));
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md) §2.3, §4.2 and §5.1;
  - a design conversation with the owner on 2026-10-24, with screenshots of iManage's Save dialog that the owner supplied, and mockups of the Save and Open windows, the title bar and the helper, which the owner reviewed;
  - the trial build's results (§12.5);
  - a survey of this repository at `dbfb46dd4`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks Claude's proposal. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace* and *template* mean Healdan's things here (roadmap glossary). "CE" is LogicalDOC Community Edition, which Healdan is built on.

## 1. Intent

You save to and open from Healdan inside your office apps, and the document always shows its doc # and version. It's the iManage flow the owner has used for years, and the next thing they'd miss after M1. (owner)

**How the owner works, and wants to keep working (owner):**

- **New documents** are created in the app and filed through a Healdan Save window: places to start from on the left (My Matters, Recent Matters, recent and favourite folders), the chosen place's contents in the middle, and the properties on the right, mostly filled in already.
- **Existing documents** are usually found outside the app, by doc # or matter #, and opened from there, which checks them out. Opening from inside the app also works but is used less.
- **While editing,** the app's ribbon changes the open document's properties and saves a new version, and the title bar shows the doc # and version next to the name.
- **Closing** checks the document back in.

**One helper on each computer (owner).**

- **What it is:** a small app written in C# on .NET, with Avalonia for its windows. It runs on macOS and Linux, and later on Windows.
- **What it does:**
  - holds the sign-in;
  - catches "open this document" links from Healdan's web page;
  - keeps local copies of open documents;
  - notices saves and closes, and talks to the server;
  - draws the Save, Open and Properties windows once, for every app.
- **The add-ins stay slim:** inside each app, just the buttons, the title bar and the doc ID footer.
- **No Java** on the user's computer (owner).

## 2. Goals, non-goals, anti-goals

### 2.1 The order of work (owner, 2026-10-24)

1. **LibreOffice on macOS.** Built and accepted first.
2. **LibreOffice on Linux** (Ubuntu 26.04). It starts once the owner's semi-production Healdan server is running, which a Linux machine on the network can reach. Its first task is the trial's Ubuntu rows (§12.5).
3. **Microsoft Office on the Mac**, later. Its first task is the trial's Word add-in rows: loading the add-in, and the rows that need it (§12.5).

**M3 is done when phases 1 and 2 are accepted (call).** Microsoft Office on the Mac becomes its own milestone, M3.5, as Office previews were M1.5, with §7 and §12.5 as its starting point. The roadmap's M3 row and section say so. The owner may instead keep it inside M3 as a third phase; nothing else in this spec changes either way.

### 2.2 Goals (each phase is done when)

On the phase's platform (§3.2), against a Healdan server reached over HTTPS, as an ordinary non-admin user:

1. **Sign in once.** The helper signs in with a username and password, keeps M2's device token in the system keychain, and never stores the password (§5.2).
2. **Save to Healdan.** A new document's Save opens the Save to Healdan window. The user:
   - picks a folder from Recent Folders, Recent Matters, My Matters, Favorites or a search;
   - fills in any required properties and Comments;
   - keeps the app's own file format, or picks the other one;
   - saves.

   The title bar then reads `Lease agreement #1234 v1.0`, and the document stays open and checked out (§5.6, §5.8).
3. **Save while editing.** Each save sends a snapshot, and the title bar moves on to `v1.1` (§5.5, §5.7).
4. **Close and reopen.** Closing checks the document in. It reopens from the web page's Open in App (§8), and from the Open from Healdan window, including by `#1234` (§5.8).
5. **Save a new version.** Save as New Version makes `v2.0`, and the title bar says so (§5.5).
6. **Doc ID footer.** Insert Doc ID adds a footer field showing `#1234 v2.0`, and it moves on after each save. Healdan stamps the right number into every file it stores through the API, so the stored file always carries it (§5.9, §9.7).
7. **Someone else's document** opens as a read-only copy, and the helper says who has it checked out (owner, roadmap).
8. **Two computers.** A document checked out to you on another computer warns before editing moves here, and the first computer keeps its copy (owner, §5.10).
9. **Nothing is lost.**
   - A save made while the server can't be reached is kept and sent later.
   - A crash leaves the document checked out, with its local copy, and the helper offers to reopen it or check it in (owner, §5.10).
10. **Calc and Impress** get the same open and save flow as Writer; the footer is Writer only (owner, roadmap). (added after the build, 2026-10-29: Draw too, for `.odg` only, owner.)
11. **Comments.** Every document has a Comments property, and the Documents search finds a document by the words in it (owner, §9.9).
12. **Installs cleanly.**
    - On macOS: a signed, notarised disk image.
    - On Ubuntu: `.deb` packages.
    - Both are built by CI and attached to a GitHub release (owner, §5.12).
13. **Documented.** The docs pages in §13.2 are written, and the API's new endpoints are in `openapi.yaml` (§9).

The server additions in §9, Open in App (§8) and the Comments property belong to phase 1, because LibreOffice on macOS needs them.

### 2.3 Non-goals

- **Microsoft Office in M3** (§2.1). Excel and PowerPoint come with it.
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

### 2.4 Anti-goals

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
- **A save takes at least about 1.1 seconds** on the server (CE's version write), and the API answers only once it's stored (M2 follow-up). The trial measured about 1.25 s per save.
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

**The platforms** (the trial's findings are in §12.5):

- **LibreOffice's Open command has several names.** Writer's File → Open and Cmd+O send `.uno:OpenFromWriter`, Calc's `.uno:OpenFromCalc`, and the others `.uno:Open`. The Start Center isn't a document window, so a document event never reaches it.
- **LibreOffice keeps its own Recent Documents list,** which records the local copies, so it would list them after the helper has deleted them.
- **LibreOffice's command-line extension installer** (`unopkg`) couldn't install a Python extension on macOS 27 with LibreOffice 26.8, even with LibreOffice closed: it couldn't connect to the LibreOffice process it starts. Opening the `.oxt` in LibreOffice installed it with one click and a restart. On Ubuntu 26.04 with LibreOffice 26.2, `unopkg` works.
- **macOS asks before one app controls another:** once, the first time the helper drives an app through AppleScript ("Healdan Helper wants access to control Microsoft Word").
- **Word for Mac is sandboxed.**
  - Saving the open document under a new name needs access to its folder, so Word asks "Grant File Access" once per folder. Once granted, Word remembers it.
  - Word takes the window title from the file name.
  - Office's shared group container is closed to apps that aren't Microsoft's, even with the "access data from other apps" permission.
- **Office web add-ins load their pages over HTTPS** from a server the Mac trusts (M2 §3.2). Office.js has no event for a save or a close, can't set the window title, can't take over Word's own commands, and can't save an open, never-saved document to a path of its choosing.
- **Sideloading an add-in didn't load it in Word 16.114.** Microsoft's documented folder for sideloading on the Mac is inside Word's own container, which macOS opens to another app only after the user allows "access data from other apps". With that allowed and a manifest that Microsoft's validator passes, Word still didn't list the add-in, even after its add-in cache was cleared.
- **Avalonia puts an app in the Dock** unless told not to, and a menu-bar app keeps the focus after its window closes unless it gives it back.
- **Notifications through AppleScript don't work from the helper.** macOS showed nothing, then an unrelated AppleScript prompt.
- **On Ubuntu,** the .NET build crashed on start about half the time until it used Ubuntu's own HarfBuzz library instead of its bundled copy. GNOME's top bar is dark, so the tray icon needs a light variant, and GNOME runs on Wayland, which may refuse to raise another app's window.
- **macOS 27 runs only on Apple silicon** (macOS 26 was the last release for Intel Macs).

### 3.2 The setups (owner)

| Setup | System | App | Phase |
|---|---|---|---|
| **M-LO** | macOS 27, Apple silicon | LibreOffice 26.8.1 | 1 |
| **U** | Ubuntu 26.04, GNOME | LibreOffice 26.2.6 | 2 |
| **M-W** | macOS 27, Apple silicon | Word, Excel and PowerPoint for Mac, Microsoft 365 | M3.5 |

The LibreOffice extension supports LibreOffice 26.2 and later (call).

### 3.3 Network and signing

- **HTTPS.** Production servers sit behind Nginx Proxy Manager with a Let's Encrypt certificate (owner). The helper refuses plain HTTP to anything but `localhost`, so development stacks still work, and it never skips certificate checks (call).
- **The owner's semi-production server** comes before phase 2 (owner, 2026-10-24). The M2 follow-up "Harden the sign-in throttle behind the proxy" said to land before any production deployment; it landed in [Healdan-Project/healdan#68](https://github.com/Healdan-Project/healdan/pull/68). With NPM on another machine, that server's `.env` sets `LOGICALDOC_TRUSTED_PROXIES` to NPM's address (the docs site's Reverse Proxy page).
- **Apple signing.** The owner has an Apple Developer account, so CI signs and notarises the macOS helper (owner). Builds without the secrets, such as pull requests, are unsigned. The trial signed with the owner's Developer ID certificate on the owner's Mac.

## 4. The pieces

| Piece | What it is | Where it lives (call) |
|---|---|---|
| **Healdan Helper** | The app on each computer: sign-in, links, local copies, saves and closes, the windows | `clients/helper/` (a .NET solution) |
| **LibreOffice extension** | Toolbar and menu, taking over Open, Save and Save As, the title bar, the footer | `clients/libreoffice/` (Python, packaged as `.oxt`) |
| **Server additions** | Search within a folder, New Folder, favourites, device holds, save preconditions, stamping, thumbnails, Comments, the title's `#` form | `healdan-api`, `healdan-core` (§9) |
| **Open in App** | CE's hidden "Edit with Office", shown and pointed at the helper | `healdan-core`'s `plugin.xml` and small hooks in the old web page (§8) |
| **Office add-in** (M3.5) | Ribbon buttons in Word, Excel and PowerPoint, and the footer in Word | `clients/office/` (§7) |

The name "Healdan Helper" is a call; the owner may rename it.

## 5. The helper

### 5.1 What it is

- **C# on .NET, with Avalonia for its windows (owner).** .NET 10, the current long-term release, and Avalonia 12, the current one; the trial built with .NET 10 and Avalonia 12.1.4 for macOS (arm64) and Linux (x64 and arm64). The plan pins both (call).
- **Runs in the menu bar (macOS) or the top bar's tray (Ubuntu)**, from sign-in to the computer until quit. (changed in the build: on macOS the helper registers itself as a login item, with `SMAppService`, after its first successful sign-in. It doesn't while it runs from the disk image or from where macOS runs a downloaded app until it's moved (App Translocation), and re-registers if macOS has lost it. Settings shows whether it's on. R21.)
  - **Its menu:** who is signed in, the documents open, any saves waiting to be sent, Open from Healdan, Settings, Sign Out and Quit (call).
  - **On macOS it stays out of the Dock,** and after each of its windows closes it hands the focus back to the app the user was in (the trial).
- **One server per helper** (call). A person with two Healdan servers isn't a case M3 serves.
- **Quiet when things work.** It speaks up only when something needs the user: a read-only open, a save waiting for the server, a check-out that moved, a crash, an error.
  - It speaks through the system's native notifications, or its own window when it needs an answer, as after a crash.
  - Never through AppleScript, which didn't work (the trial).
- **Split for testing (call):** a core library with no windows (the API client, local copies, the open-save-close cycle) and the Avalonia app on top. The core is what the automated tests drive (§14.1).

### 5.2 Signing in

- The first run asks for the server's address, a username and a password. The helper calls `GET /server` to check it's Healdan, then `POST /auth/tokens` with the name `Healdan Helper on <computer name>` and this computer's device id, and keeps only the token (M2 §5, §9.4).
- **The device id** is a random id the helper makes once per computer and user, and keeps beside its settings. It isn't a secret. Signing in again, after a `401`, a password change or Sign Out, sends the same id, so the documents this computer holds stay its own (call).
- **The token lives in the system keychain:** the macOS Keychain through Security.framework, and the Secret Service (GNOME Keyring) on Ubuntu. Never in a file. On macOS the signed helper reads it back with no prompt at every start, including after a rebuild signed with the same Developer ID (the trial).
- A `401` at any time means the token has expired or been revoked; the helper asks for the password again and keeps any saves waiting meanwhile.
- Sign Out revokes the token (`DELETE /auth/tokens/current`), after the helper has sent any waiting saves or the user has chosen to leave them as local copies. (changed in the build: Sign Out asks nothing. It tries every unsent save at once, waits up to 30 seconds for them, and keeps the sign-in, with a notice, if any is still unsent; quitting meanwhile keeps it too. After Sign Out the channel stops, so the extension falls back to LibreOffice's own commands, §6.2. Changed after the build: a revoke gets 30 seconds. When Healdan can't be reached at all (no connection, no answer in time, or a proxy's 502, 503 or 504; after unsent saves, a 10-second `GET /server` decides), Sign Out on This Computer Only? (§5.8) asks whether to forget the sign-in here and leave the token on the server until it expires, so a server that's gone for good doesn't keep the helper signed in to it for ever. The window waits outside the session's lifecycle lock, and a second Sign Out meanwhile does nothing. On Sign Out on This Computer the revoke is tried once more, for 5 seconds, in case Healdan is back. Unsent saves stay with their copies, for the next sign-in to that server; a sign-in to another server sets them aside, §5.4.)
- M4 replaces the password with the identity provider's pop-up, ending in the same device token (M2 §5.6).

### 5.3 Links from the web page

- **The form:** `healdan://open/<docNumber>?server=<host>`, with `&readonly=1` for a read-only copy (call).
- **No credentials in a link,** unlike CE's `ldedit:` links, which carried the CE session id. The helper uses its own token.
- **The helper acts only on its own server.** A link naming another host is refused with a message, so a link can never open #1234 from the wrong server (call). (changed in the build: `server` is the web page's `window.location.host`, so the host and any port, compared with the helper's server case-insensitively. R9.)
- **A link can only open.** It never saves, files, releases or checks anything in, and while one of the helper's windows is waiting for an answer, further links are dropped (call). (changed in the build, final review C2: a link opens only §5.11's kinds, by the file's own extension, and refuses any other kind, before downloading it, with a notice to open it from the web page or the Open window. A page can make a link to any document, and anyone who can add files to a matter could otherwise have a click hand, say, a `.command` file to its handler.) (changed in the build: a dropped link brings the waiting window to the front. After a Crash and Settings don't count as waiting. A link that arrives while the helper is first starting is held until it has signed in; one that arrives while it's signed out is dropped with a notice.)
- **No extra confirmation (call).** Opening checks the document out, as the owner's flow wants. Browsers ask before a site hands a link to an app (Safari did in the trial), and remember the answer per site, so a hostile page can't fire links silently; the server in the link must also match. The residual risk is that a page the user has allowed could check out documents the user may edit. It's recorded in the Install page's security notes.
- **How a link arrives:**
  - **macOS:** the app bundle registers `healdan:` and receives links as Apple events, started or not. It opened a document within a fraction of a second in the trial.
  - **Ubuntu:** the package's desktop entry registers `healdan:` and starts a second process, which hands the link to the running helper over its channel (§10) and exits. The running helper answers the hand-over at once and opens the document in the background, so a slow download can't make the hand-over give up and start a second helper. A link clicked while the helper is still signing in must not start a second helper either (the trial).

### 5.4 Local copies

- **One folder, for every app (call, from the trial).** Word's sandbox needs access to the folder it saves into, and asks once per folder. So every local copy lives directly in one per-user folder, `Copies` beside the helper's settings, that only the user can read. Word asks for it once, the first time it saves there, and never again; the trial showed exactly that. The Install page explains the prompt, and M3.5 decides whether the helper's first run triggers it.
- **The file name is the title bar (owner):** `<name> #<doc #> v<version>.<ext>`, for example `Lease agreement #1234 v2.1.docx`.
  - `<name>` is the document's name without its extension, with characters the file system can't hold replaced, and shortened so the whole name fits. (changed in the build: `/`, `:`, `\` and control characters become `-`, and the name is cut so the whole file name is at most 241 bytes in UTF-8, not 255, so that the record beside it, `.<file name>.healdan.json`, and LibreOffice's lock file, `.~lock.<file name>#`, fit as well. R18.)
  - `<version>` is the version of the content the user is editing: `fileVersion` after each confirmed save. (changed in the build: that's the version when the copy is opened. A copy keeps the file name it was opened with while the app has it open: in LibreOffice the extension moves the title on instead (§5.7), and the record beside the copy holds its file name and the version it holds. Word's rename after each save (§5.7) is M3.5's. R19.)
  - The doc # makes each name unique in the shared folder. A read-only copy adds ` (read-only)`.
- **The copies belong to one server (added after the build, #85).** A sign-in to another server than the one in the settings first moves everything in `Copies` into a folder beside it, `Copies from <host[-port]>`, and tells the user, so no copy is resumed against a server where the same doc # is another document. `Copies` itself stays, so an app's access to it does too. A copy still open in LibreOffice at that moment is moved from under it, which is why the user guide says to close documents before changing servers.
- **Saves send the document's own name** (`name`), so the `#1234 v2.1` in the local file's name never becomes the document's name (§3.1).
- **A record beside each copy** says which document and version it is, whether it's checked out on this computer, and whether it has changes the server hasn't confirmed. It's a hidden file in the same folder, and survives the helper restarting (call).
- **When a copy goes:** a read-only copy when its window closes; an edited copy once the server has confirmed the check-in. The helper deletes only that copy's own files, never the folder. A copy with unconfirmed changes is never deleted by the helper; the menu lists it until the user sends it or files it (owner). (as built, after the final review's I2, which found the plan had dropped this: the menu's Not Yet in Healdan lists each such copy nothing looks after any more, once its window has closed, and choosing one opens it as it is, with no check-out or download, for Save as New Document.)

### 5.5 Opening, saving and closing (owner, with calls on the mechanics)

**Opening** (from a link, the Open window or the app's Open):

1. The helper reads the document (`GET /documents/{n}`).
2. If the caller can't edit it, or someone else holds it, or the user chose read-only, it downloads a read-only copy, opens it, and says who has it checked out.
3. If it's checked out to the user on another computer, the warning in §5.10 comes first.
4. Otherwise it checks the document out from this computer (`POST …/checkout`), downloads it and opens it in the app chosen for that kind of file (§5.11).
5. **If the open fails after the check-out** (the download, the folder, the app), the helper releases the check-out again, unless it already has a local copy of that document (the trial left a document checked out this way).

**Saving while editing:**

- Each save in the app writes the local copy as usual, and the helper sends it as a snapshot (`POST …/versions`, `kind: snapshot`, `keepCheckedOut: true`, with `name`, `ifVersion` and `ifHeld`, §9.5). (changed in the build: and a `saveId`, which the helper keeps with the copy and repeats if it sends the save again, so a save whose answer was lost isn't stored twice, nor refused as `version-moved`. Plan correction 1.)
- The app never waits for the server: the user's save is finished as soon as the local file is (call). In the trial the title bar moved on about 2.7 s after Cmd+S: a 1.2 s pause to let the file settle, then the server's save.
- The helper notices saves by watching the folder, so it works the same whether or not the app's add-in took the save over. It sends a file only once it has stopped changing, once per save, and compares contents, so the app reopening or adopting the file isn't sent as a save.
- When the server confirms, the title bar and the footer on screen move on (§5.7, §5.9). Saves made while one is being sent are queued and sent in order; a newer save waiting can replace an older one that hasn't started (call). (changed in the build, final review I1: they move on to the last save. The extension's wait after a save is answered only once nothing newer is on its way, and its answer says `pending` while a save is still to go, as during an outage, so the extension asks again until Healdan has it.)

**Save as New Version** (a button): if the app has unsaved changes, the button saves them first, and the helper sends that save as `kind: version` instead of a snapshot. Without changes, it sends the current file as a version. The title bar moves to `v3.0`. (changed in the build: a newer save that replaces the version while it waits behind another goes up as the version in its place, so the version isn't lost. A restart keeps it too: the record holds the kind of the save going up apart from that of the newest still to go, so the save going up is repeated as the kind it was sent as, and a version waiting behind a snapshot goes up after it. A window that closed while the helper was stopped is checked in as the version too.)

**Save as New Document** (a button, and the app's Save As on a Healdan document, §6.2):

1. The Save to Healdan window opens with the current folder and properties filled in. (changed in the build: titled Save as New Document. Its name is the original document's, without its extension, never the copy's file name or the window's title, and the copy's own format is chosen when it's among those offered.)
2. Saving files the current content as a new document (`POST /documents`, `checkOut: true`). The server stamps the new document's own number into it (§9.7).
3. The helper releases the original without saving anything to it; the original keeps its last saved version.
4. The open window becomes the new document, with its own title and file (call).

**Properties** (a button): the Properties window shows the open document's properties (`GET …/properties`) and saves changes (`PATCH`). On a document that isn't in Healdan yet, the button opens Save to Healdan.

**Closing:**

1. **The helper notices the window closed:**
   - in LibreOffice, from the extension's close event, or from LibreOffice's lock file disappearing (`.~lock.<name>#`);
   - in Word, from Word's owner file (`~$` and the name) disappearing, or by asking Word through AppleScript whether it still has the document.

   Each was noticed within about two seconds in the trial.
2. If the copy has changes the server hasn't had, the helper sends them with `keepCheckedOut: false`, which checks the document in. If not, it releases the check-out (`DELETE …/checkout`).
3. Then it deletes the copy.

Both the check-in and the release are refused if this computer no longer holds the check-out (§9.4), and then the copy stays (§5.10).

### 5.6 Saving a new document (owner)

1. The user presses Save on a document that isn't in Healdan.
2. The Save to Healdan window opens (§5.8).
3. **The format (owner, 2026-10-24).** The window offers the app's own format first, selected, and the other family's format as the alternative:
   - from LibreOffice, `.odt`, `.ods` or `.odp`, with `.docx`, `.xlsx` or `.pptx` as the alternative (added after the build, 2026-10-29: from Draw, `.odg` alone, as Draw saves no Microsoft format, so the File Format pop-up has the one choice);
   - from Word, Excel and PowerPoint (M3.5), the other way round.

   The choice is only offered here, because a document's format can't change later (§3.1).
4. On Save, the app writes the document in the chosen format to a temporary file, and the helper files that file (`POST /documents`, `checkOut: true`). The server gives it its doc # and stamps it (§9.7).
5. The open document becomes the local copy: the app saves it under the local copy's name, so the title bar reads `Lease agreement #1234 v1.0`. The helper takes that first write as the copy's starting point, not as a save, and later saves are noticed like any other. (changed in the build, final review C1: taking the first write that settles as the starting point folded a save made within 1.2 s of it into it, unsent. The extension now tells the helper, over the channel's `/v1/adopted`, right after `storeAsURL` and before the window takes input again, and the file as it is then is the starting point; an older extension leaves the first settled write as it was. At a restart before either, the build took the file as the starting point when it was written within 10 seconds of the filing, which could fold a save made in those seconds, while the helper was down, into it, to be lost at close. After the final review's re-review (#76, owner), the filed bytes are the starting point until then, so a restart sends the copy unless it's those bytes: an identical version, as the extension sets the doc ID before `storeAsURL`, but never a lost save.)
   - In LibreOffice the extension does this (`storeAsURL` with the format's filter).
   - In Word the helper asks Word through AppleScript to save the open document under that name (M3.5).
6. "Save to local computer" in the window drops back to the app's own save box, with one click (the trial found a double window when the extension had attached itself twice; §6.2 attaches once).

### 5.7 The title bar

- **Format (owner):** `Lease agreement #1234 v2.1`. The version is the content's: after a save confirmed as 2.2, it reads `v2.2`, and after Save as New Version, `v3.0`.
- **A read-only copy** reads `Lease agreement #1234 v2.1 (read-only)` (call).
- **LibreOffice:** the extension sets the document's title, which LibreOffice shows as the window title, so it moves on without renaming the file. The trial saw it move from v1.0 to v1.1, v1.2 and v2.0, with no prompt and no reload.
- **Word (M3.5):** the title is the file name. After each confirmed save, the helper asks Word through AppleScript to save the open document under the new name, then deletes the old file. In the trial it took about a quarter of a second once Word had its one-time permissions (§3.1): the Automation consent, and Grant File Access for the copies folder.
- **Searching for it.** `#1234 v2.1` typed into any search box, or sent to `/lookup` or `/search`, finds the document and version (§9.6).

### 5.8 The windows (owner: drawn by the helper)

Each window is an Avalonia window that comes to the front over the app, and hands the focus back when it closes (§5.1). The mockups the owner reviewed are in [`2026-10-24-m3-mockups/`](2026-10-24-m3-mockups/README.md), with what changed after the review.

**Save to Healdan** (mockup reviewed 2026-10-24, modelled on iManage's):

- **Toolbar:** back, forward, up; a scope picker (Folders, Documents, Matters, Clients); and a search box that searches within the place shown ("Search folders within 998.1 – Healdan Demo") (§9.1).
- **Left pane:** Recent Folders, Recent Matters, My Matters, Clients, Favorites (folders only). **Clients** sets the scope picker to Clients for a search by client number or name; it isn't a list of every client (call, §2.3).
- **Middle pane:** a breadcrumb from the matter down, then the place's subfolders and documents, with Title, Version, Doc # and Edited columns, and a New Folder button (§9.2). A star adds or removes a folder from Favorites (§9.3).
- **Right pane:** the properties form for a new document in the selected folder (`GET /folders/{id}/form`):
  - Name;
  - Client and Matter, read-only and marked "from the matter";
  - Author and Typist, defaulting to the user;
  - Class, showing where it's inherited from;
  - Subclass, narrowed by Class;
  - Comments (§9.9);
  - any other attribute the profile has.

  Required fields are marked. The format choice comes last (§5.6).
- **Bottom:** "Save to local computer", Cancel, Save. Save turns on only once a folder that can take documents is selected (`canFile`) and every required field has a value (owner, roadmap).

**Open from Healdan** (mockup reviewed 2026-10-24):

- **Toolbar:** the same as Save's, defaulting to the Documents scope. `#1234` with Enter jumps straight to the document.
- **Left pane:** Recent Documents, Checked Out to Me, Favorites (folders and documents), then Recent Matters, My Matters and Recent Folders for browsing.
- **Middle pane:** Title, Doc #, Version, Matter and Edited columns, and under the title who has the document checked out, or "checked out to you on MacBook".
- **Right pane:** a thumbnail (§9.8), the main properties, and the version list. Picking an older version opens it read-only (call; `GET …/content?version=`).
- **Bottom:** "Open from local computer", "Open read-only", Cancel, Open.

**Properties:** the same form as Save's right pane, for the open document.

**Checked out on another computer** (mockup reviewed, §5.10).

**After a crash** (the trial's window): "<title> closed unexpectedly. It's still checked out to you, and your local copy is kept." with Reopen and Send and Check In.

**Sign Out on This Computer Only?** (added after the build, §5.2): "Healdan at <server> can't be reached", what forgetting the sign-in here leaves on the server, and how many saves never reached Healdan. Sign Out on This Computer, and Stay Signed In, the default.

**Sign In** and **Settings** (the server's address, which app opens each kind of file, §5.11) (call). (changed in the build: the server's address is read-only in Settings, which says to sign out and in again to change it, as there's one server per helper. Settings also says whether the helper starts at sign-in, and which version of the LibreOffice extension is installed and how.)

The look changed in the polish: see [the polish spec](2026-10-27-helper-ui-polish-design.md).

### 5.9 The doc ID footer

- **Insert Doc ID** (Writer, and Word in M3.5) does two things (owner; call on the property's name):
  - sets the document property `Healdan Doc ID` to the title's form, `#1234 v2.1`;
  - puts a field in the footer that shows it.

  This replaces M1's `# 1234.2.1` form for the footer, so the footer, the title bar and the search box agree (owner, 2026-10-24). (changed in the build: on a document that isn't in Healdan yet, the field goes in empty, with a notice, and the server stamps the property when it's filed. The field goes into the current page style's footer, which is turned on if need be, and into its left and first-page footers when they're separate.)
- **The stored file is always right (call).** The server stamps each file it stores through the API with the doc # and version it's stored as (§9.7), so Healdan's copy, its preview and every download show the right number, whoever opens them. That covers:
  - a new document, whose number only the server knows;
  - Save as New Document, whose content arrives with the old document's number.
- **On screen in LibreOffice:** after each confirmed save, the extension sets the property to the confirmed number and refreshes the fields. It then clears the "modified" flag it caused, if the document had no unsaved changes before. The trial confirmed the footer moved on without LibreOffice thinking the document had changed.
- **On screen in Word** is M3.5's to show: the trial couldn't load the add-in (§12.5).

### 5.10 When things go wrong (owner)

- **The server can't be reached when you save.** The save lands in the local copy as usual. The helper keeps trying, shows in its menu that a save is waiting, and the document stays checked out to you. Nothing is lost.
- **The app or the computer crashes.**
  - The document stays checked out.
  - The helper notices the app has gone (its process is no longer running, which took 10 to 20 seconds in the trial), or, after a restart, finds the copy's record.
  - Its window then offers Reopen, which opens the kept copy rather than downloading over it, or Send and Check In. The trial's Reopen brought back the saved text.
- **Your check-out ended** (an admin released it, someone checked it in from the web page, or another of your computers took it over).
  - The next save, check-in or release from this computer is refused (`409 not-held` or `held-elsewhere`, §9.4, §9.5).
  - The helper keeps your copy and offers Save as New Document.
- **Checked out to you on another computer** (owner, with the warning the owner added):
  - The helper says "#1234 is checked out to you on MacBook (LibreOffice)", when it was last saved to Healdan, and that edits on that computer which haven't reached Healdan won't be in this copy. (changed in the build: a document filed with `checkOut: true` is held with no app, §9.4, so for it the window says "on MacBook" until it's next checked out.)
  - The choices are Open Read-Only, Edit Here Instead (which moves the check-out to this computer, §9.4) and Cancel.
  - The other computer keeps its copy, and its next save or close is refused as above.
- **The version moved on** (`409 version-moved`, §9.5). Someone with Write changed the document's properties or name in the web page while you held it.
  - When the document is still checked out to this computer and its `fileVersion` is still the one the copy started from, nobody has stored newer content, so the helper re-reads the version and resends once (call). (changed in the build: the resend carries the document's name as it is now, so it never undoes a rename made in the web page.)
  - Otherwise it keeps the copy, tells the user, and offers Save as New Document.
- **The token expired or was revoked:** the helper asks for the password again, and saves wait meanwhile. The device id is kept, so the documents this computer holds stay its own (§5.2).

(changed in the build) **Which holds are this computer's.** The helper recognises this computer's check-out by `hold.device.id`, its own device id, and never by the device's name, as two computers can share one. A hold with no device can't be told from another computer's, so it isn't treated as this one's. Every release the helper queues, after a restart included, first reads the document and releases only this computer's hold.

### 5.11 Which app opens a file

- **Kinds (call):**
  - documents: `.doc`, `.docx`, `.docm`, `.dotx`, `.dotm`, `.rtf`, `.odt`, `.ott`;
  - spreadsheets: `.xls`, `.xlsx`, `.xlsm`, `.ods`;
  - presentations: `.ppt`, `.pptx`, `.pptm`, `.odp`;
  - (added after the build, 2026-10-29) drawings: `.odg`. Draw opens Visio and Publisher files but can't save them, so they stay other files, read-only in the default app, and Draw's templates (`.otg`) stay out, as Calc's and Impress's do (owner). The helper's links and Open from Healdan open drawings in LibreOffice now; the web page's Open in App for `.odg`, and the server's previews of drawings, follow in a pull request of their own, once a helper that takes them is out.

  Other files open read-only in the system's default app, with no check-out. (changed in the build: the plan's cycle left these out, and the build added them: `open` on macOS, `xdg-open` on Linux, from the Open window, since Open in App is offered only for the kinds above (§8). The default app keeps no lock file, so such a copy is deleted at the helper's next start, unless it changed. Final review C2: they open only from the Open window, never from a link; each copy gets macOS's `com.apple.quarantine`, so Gatekeeper checks it; and kinds that run a program or change the system when opened (apps, installers, disk images, scripts, Automator and Shortcuts files, internet location files, kernel extensions, configuration profiles: 24 extensions) are never downloaded or handed over, with a notice.)
- **In M3 every kind opens in LibreOffice.** From M3.5, Settings choose LibreOffice or Microsoft Office for each kind on macOS, with the system's default app as the default. Ubuntu has only LibreOffice (call).

### 5.12 Packaging and releases (call, with the owner's signing)

- **macOS:**
  - **The bundle:** an Apple-silicon app bundle that registers `healdan:` and stays out of the Dock, signed with the owner's Developer ID and notarised, in a disk image.
  - **Signing needs a single-file .NET build.** Any file other than native code inside `Contents/MacOS` breaks the bundle's signature (the trial). CI signs only when the secrets are present.
- **Ubuntu:**
  - **The package:** a `.deb` for amd64 and arm64 that installs the helper, registers `healdan:` with a desktop entry, and starts it at sign-in.
  - **It depends on** `libsecret-tools`, LibreOffice's Python support (`libreoffice-script-provider-python`) and `libharfbuzz0b` (the trial's start-up crash, §3.1).
  - **LibreOffice must be installed first;** otherwise apt brings in LibreOffice without its windows. The Install page says so.
  - **The tray icon** needs Ubuntu's AppIndicator extension, which is on by default, and a light icon for GNOME's dark top bar.
- **The extension comes with the helper.** On first run, and after each upgrade, the helper installs or updates the LibreOffice extension for the user:
  - **on Ubuntu,** with `unopkg` while LibreOffice is closed;
  - **on macOS,** where `unopkg` failed in the trial, by opening the `.oxt` in LibreOffice for the user to accept, followed by a restart. The plan tries `unopkg` with a short temporary folder, or with LibreOffice running headless, before settling for that. (changed in the build: on macOS the helper never runs `unopkg`. It failed in the build as in the trial, `TMPDIR=/tmp` included, it hangs beside a running LibreOffice, and installing through a headless LibreOffice would have meant placing a script in the user's profile that approves the extension. Instead the helper reads LibreOffice's user registry, which runs nothing, to see what's installed. When the bundled extension is newer, or none is installed, it opens the `.oxt` in LibreOffice once per helper version, with a notice to click OK and restart. After that it doesn't start LibreOffice again: each start gives a notice, and the menu offers Install the LibreOffice Extension… until it's installed. A turned-off extension counts as missing. On Ubuntu, `unopkg` stays. R22.)
- **Releases.** Healdan has no release tags of its own yet; the repository holds upstream's.
  - **Versioning:** the helper is versioned on its own (`1.2.3`, valid as a macOS bundle version and a Debian version), from `helper-v*` tags.
  - **What's attached:** CI attaches the disk image and both `.deb` files to that tag's GitHub release. (changed in the build: only for a pushed `helper-v*` tag, and only once `xcrun stapler validate` has found the disk image's ticket. A run by hand packages any branch and uploads the disk image as an artifact, attaching nothing. The bundle is `com.healdan.helper`, for macOS 26 and later.)
- **The helper and the server.** The helper checks `GET /server`'s `apiVersion` and refuses a server it doesn't speak. There are no compatibility promises until the roadmap is complete (M2 §4.1), but the helper lives in the same repository, so a change that would break it updates it in the same pull request, and a new helper release follows.

## 6. The LibreOffice extension

### 6.1 What it is

- **Python, using LibreOffice's UNO interface,** packaged as an `.oxt` (call; no Java, owner). The same extension runs on Ubuntu and macOS, in Writer, Calc and Impress (added after the build, 2026-10-29: and Draw, with its own window state for the toolbar, `DrawWindowState.xcu`). The trial's extension ran on LibreOffice 26.8 on macOS and installed on 26.2 in Ubuntu.
- **Slim:** it talks to the helper, which does the work (owner). It holds no token and calls no server.
- **Installed by the helper** for the user (§5.12).

### 6.2 What it does

- **A Healdan toolbar and menu:** Save to Healdan, Open from Healdan, Save as New Version, Save as New Document, Properties, and Insert Doc ID (Writer only). The menu also appears in the Start Center, with Open from Healdan.
- **Takes over the app's own commands (owner).** The extension intercepts the window's commands (a dispatch interceptor):
  - **Open** (`.uno:Open`, `.uno:OpenFromWriter`, `.uno:OpenFromCalc` and the other modules' variants) shows Open from Healdan, from every document window and from the Start Center.
  - **Save** on a document that isn't in Healdan shows Save to Healdan. On a Healdan document, it stores the local copy in the document's own format, without LibreOffice's "Keep current format" question (the trial saved a `.docx` this way), and the helper sends it. A local file that was never in Healdan saves locally, as before.
  - **Save As** on a document that isn't in Healdan shows Save to Healdan; on a Healdan document, it's Save as New Document.
  - **The way back:** each window's "local computer" link runs LibreOffice's own box, at once.
  - **One interceptor per window.** The extension attaches once to every window, the Start Center's included, and never twice to the same one.
- **Keeps local copies out of LibreOffice's Recent Documents.** The extension removes Healdan's copies from that list as each one closes, on LibreOffice's own thread.
  - The trial's first try ran on a background thread and never took effect. The second, on LibreOffice's own events, wasn't rechecked before the trial ended, so the plan makes it a checked task.
  - The alternative is opening copies through the extension with LibreOffice's `PickListEntry` set to false, so they never enter the list.
  - (changed in the build) The extension removes every Healdan copy from LibreOffice's list (the PickList's `ItemList` and `OrderList`) in the job that runs as each copy closes, on LibreOffice's own thread, and again at start, for anything a crash left. A headless LibreOffice 26.8.1 confirmed the list it leaves; the owner's acceptance checks the Start Center and File › Recent Documents on screen, and `PickListEntry` stays the fallback.
- (changed in the build) **When the helper can't be reached** (it isn't running, or is signed out), Open, Save and Save As fall back to LibreOffice's own, with one notice until the helper answers again. A copy's save is still stored, and the helper sends it once it's back. An unexpected error in a Healdan command before any of the helper's windows was shown also falls back to LibreOffice's own command, and says so. (changed after the build, 2026-10-28: the extension stopped following a save once the helper couldn't be reached, so the title stayed a version behind and the notice stayed up after the helper came back. It now follows the save while the helper is away, at the same growing pause as for `pending`, and once the helper answers anything, an error included, takes the notice that it isn't running out of the window that still shows it.)
- **Sets the title bar** and refreshes the footer when the helper confirms a save (§5.7, §5.9). It learns about the confirmation by waiting on the helper's channel after each save, in the background. (changed in the build, final review I1: while the answer says `pending`, it asks again after 2, 5, 15, 30, then every 60 seconds, so an outage longer than the helper's 120-second wait still moves the title on once Healdan is back.)
- **Tells the helper when a document closes** (its close event), and the helper also watches the lock file (§5.5).

The look changed in the polish: see [the polish spec](2026-10-27-helper-ui-polish-design.md).

## 7. The Office add-in (M3.5)

Microsoft Office on the Mac comes after M3 (owner, §2.1). This section records what the trial learned, as M3.5's starting point.

**What it must do (owner):** a Healdan group on the ribbon with the same buttons as LibreOffice's toolbar, Insert Doc ID in Word, and taking over Open, Save and Save As where Word allows. **Where Word won't let Healdan take a command over, Healdan's button sits beside Word's own** (owner).

**What the trial showed without the add-in (M-W rows):**

- **The helper does most of the work.** It handled every one of these with no add-in:
  - opening from a link;
  - noticing saves;
  - renaming the open document so its title moves on;
  - noticing closes and crashes.
- **Two one-time permissions:** Automation, and Grant File Access for the copies folder (§5.4, §5.7).
- **What's left for the add-in:**
  - the buttons;
  - Save to Healdan for a document that has never been saved;
  - Insert Doc ID;
  - refreshing the footer on screen.

**What M3.5 settles first:**

1. **Getting the add-in into Word.** Sideloading didn't load it (§3.1). The next route is deploying it from the owner's Microsoft 365 admin centre (Integrated apps), which is also the production route. Then, with the add-in loaded: Save to Healdan on a new document (the add-in sends the file's bytes, and the helper files them and has Word save under the copy's name), Insert Doc ID, the on-screen footer, the add-in reaching the helper (§10), and Excel and PowerPoint.
2. **Which kind of add-in.** An Office web add-in (Office.js) is Microsoft's current kind, but it can't take over Save, Save As or Open. A template add-in (`.dotm` with ribbon XML and VBA) may be able to, and talks to the helper through `AppleScriptTask`. The call is the web add-in, with Healdan's buttons beside Word's, unless M3.5's check shows the template add-in does T5 and T6 better.
3. **Where its pages come from.** A web add-in's pages are served by the Healdan server under `/api/addin/` (call), over the server's own certificate. The trial used a development certificate that the owner trusted by hand.

## 8. Open in App, in today's web page (owner)

- **Feature 20 is declared** in `healdan-core`'s `plugin.xml`, which shows CE's "Edit with Office" toolbar button and right-click item.
- **The Tools menu item stays hidden.** It also needs menu −1090, which has no row (§3.1), and the toolbar and right-click item are enough (call).
- **The label becomes "Open in App"** in the English messages (call). (changed in the build: in `messages.properties`, `messages_en.properties` and `messages_en_US.properties`, as the last two override the first for English. Other languages keep "Edit with Office". R14.)
- **They open the helper.**
  - `Util.openEditWithOffice(docId)` calls a Healdan hook. Callers pass CE's id, so the hook finds the document's doc # from it. (changed in the build: the hook, `common.client.healdan.HealdanOpenInApp`, is in GWT's common module, because `Util` is there and can't see the frontend's `DocumentService`. The frontend registers `HealdanDocumentOpener`, which finds the doc #. Plan correction 6.)
  - The hook opens `healdan://open/<docNumber>?server=<host>`, with no session id in the link. (changed in the build: `<host>` is the page's host and port, R9.)
  - Every caller goes through `Util`, `TaskDetailsDialog`'s included. (changed in the build: so does `MainMenu`'s hidden Microsoft Office item, which still built an `ldedit:` link with the session id until the final review's M3, §13.1.)
  - If nothing seems to happen, the hook's message points to the docs page for installing the helper (§13.2). (as built: every Open in App shows the notice "If nothing opened, install the Healdan Helper", linked to `https://docs.healdan.com/user/office/installing-the-helper/`, which the docs site's tests keep in step.)
- **On for anything the helper opens, including while checked out.** CE turns the items off unless the document is unlocked and the user has Write. Healdan's conditions are:
  - one document;
  - of the kinds in §5.11, from Healdan's list rather than `Util.isOfficeFile`'s;
  - that the user can download.

  The helper then decides between editing and a read-only copy, so others get a read-only copy and the holder can reopen after a crash.
- **Double-click is unchanged** (owner, §2.3).

## 9. Server additions (calls, from what the windows need)

Every endpoint goes into `openapi.yaml` in the same change, with tests, as M2's do. Paths are under `/api/v1`.

### 9.1 Searching within a place

`GET /search` takes an optional `within=<folderId>`. With it, the `folders` and `documents` scopes return only what's inside that folder, at any depth. It's the Save window's "Search folders within 998.1 – Healdan Demo" (owner, 2026-10-24). A folder the caller can't read is `404`, and a Store is `409 not-browsable` (M2 §6.2).

### 9.2 New folders

`POST /folders` with `{ "parentId", "name" }` creates a subfolder and returns it (`201`). New Folder is in M3 (owner, 2026-10-24).

- **The same rules as the old web page,** through the same CE and Healdan code:
  - the Add permission on the parent;
  - never in a Store, at a matter's top level (admins only, M1 §7.2) or in a search folder;
  - a name of at most 255 characters, cleaned as the old web page cleans it.
- **The new folder inherits** its parent's properties and permissions, as any new folder does.

### 9.3 Favourites (owner, 2026-10-24: favourites are CE's bookmarks)

- `GET /me/favorites`: the user's bookmarks except those on matter workspaces (which are My Matters), each a folder or a document in the same shape as elsewhere, leaving out what the user can't read.
- `POST /me/favorites` with `{ "folderId" }` or `{ "docNumber" }` adds one; `DELETE /me/favorites/{id}` removes one.
- Built on `BookmarkDAO` in `healdan-core`, beside `navigation.MyMatters`, so they're the same bookmarks the old web page shows. The inventory's bookmark entries move from M6 to M3.
- (changed in the build) The routes are on `MeResource`, not a resource of their own, because CXF lets a shorter root path swallow a longer one (M2). Adding a favourite that exists answers `201` with it, and a matter's workspace is `400`. Plan correction 3.

### 9.4 Which computer holds a check-out (owner)

- **A device is a computer's device id** (§5.2), not a token, so signing in again on the same computer keeps its check-outs. `POST /auth/tokens` takes an optional `device` (`{ "id" }`), and each token carries it.
- **The server records the device** whose token checked a document out through the API, in a new table (`hd_hold_device`, Flyway `V4`), with the app the helper named when checking it out. A check-out from the old web page has no device. (changed in the build: the app is the `app` in the check-out's body, `"LibreOffice"`, plan correction 5. Filing with `checkOut: true` records the filing token's device with no app, as `POST /documents` takes none. A token that signed in with no device, as M2's clients may, holds a document with no device, so `hd_device` is nullable, R1.)
- **`hold.device`** (`{ id, name, app }`, the name being the token's) is added to a document's `hold` when the caller is the holder, so the helper can say "on MacBook (LibreOffice)". Others still see only who holds it.
- **From another of the holder's devices,** `POST …/checkout` answers `409 held-elsewhere` with the `hold`. Sending it again with `{ "move": true }` moves the check-out to the caller's device, recorded in the history.
- **Saves, check-ins, releases and property changes** from a device that doesn't hold the check-out are `409 held-elsewhere`. A hold with no device (the old web page's) works from any of the holder's devices, as in M2, and the first save through the API records the device. (changed in the build: `held-elsewhere` comes before `ifHeld`'s `not-held` (§9.5), which is only for a document that isn't checked out to the caller at all. The helper treats the two alike, and `held-elsewhere` names the device. R2.)
- **Only the API checks devices (call).**
  - Device holds keep one person's computers from overwriting each other through the helper.
  - The web page acts for the person whichever computer they're at, so its check-in, unlock and saves aren't device-checked, and no upstream file changes for this.
  - When the web page ends a check-out, the helper's next save is `not-held` (§9.5).
- A record whose check-out has since ended or changed hands never blocks anything; only the current hold counts. The builder chooses how stale records are cleared. (changed in the build: when a check-out ends, its row stays with the device, token and app cleared, and keeps the document's last `saveId` (§9.5), so a check-in sent again after its answer was lost is still recognised. R1.)

### 9.5 Preconditions on saves

The JSON part of `POST …/versions` takes two optional preconditions. The helper always sends both.

- **`ifVersion`:** when the document's `version` isn't that value, the save is `409 version-moved`, and nothing is stored.
- **`ifHeld: true`:** when the document isn't checked out to the caller (on this device, if the hold has one), the save is `409 not-held`, and nothing is stored. Without it, M2's behaviour stays: a free document is checked out and saved. (changed in the build: the caller's hold on another device is `409 held-elsewhere` with or without `ifHeld`, §9.4, and someone else's is `409 checked-out`, so `not-held` means the document is free: released, or checked in from the web page. R2.)
- (changed in the build) **`saveId`,** a UUID the client makes for each save and sends again unchanged with a retry. The server keeps the last `saveId` stored for each document, and a save repeating it is answered `201` with the document as it stands, storing nothing. The repeat is checked first, after read access, so a retry is recognised whatever has happened to the document since, its own check-in included; the preconditions alone would refuse it as `version-moved`. A `saveId` that isn't a UUID is `422`. Plan correction 1. (changed after the build: the server keeps the version record the save's check-in made beside its `saveId` (V5), and answers a repeat `201` only while that record stands and its `fileVersion` is still the document's. Once newer content has been stored, by any route, or the save's version has been deleted, the repeat is `409 version-moved` with the document, so a late retry is never answered with someone else's content. The record, not the `fileVersion` alone, as CE gives a deleted version's `fileVersion` to the next check-in. A property change alone still gets `201`. The plan's follow-up from final review M5.)

### 9.6 The title's `#` form

`DocNumberQuery` accepts a space before `v`, so `#1234 v2.1` works wherever `#1234v2.1` does: the old web page's search box, `/lookup` and `/search`.

### 9.7 Stamping the doc ID

- **When:** `POST /documents` and `POST …/versions` stamp a file that carries the `Healdan Doc ID` property, before it's stored. A file without the property is stored as sent, byte for byte.
- **What:** the property becomes the doc # and version the file is stored as, in the title's form (`#1234 v2.1`), and the shown text of each field that displays it is updated to match, so any app or preview shows it without updating fields.
- **Formats:** `.docx`, `.docm`, `.dotx`, `.dotm`, `.odt` and `.ott` (call). Other formats are stored as sent.
- **Filing** knows the new document's number before the file is stored, so version 1.0 carries its own number with no extra version. The builder chooses how, as long as the number:
  - still comes only from Healdan's counter (`DocNumberListener`);
  - is never reused;
  - isn't taken for a filing that fails before it's stored. (changed in the build: the API takes the number from the counter only once every API check has passed, immediately before CE's create. A failure inside CE's create leaves a gap in the doc #s, as a deleted document does, and the number is never reused. The reservation is cleared however the create ends, so it can't pass to the next document stored on that thread. Plan correction 2, R8.)
- **The response** carries the stored `fileVersion` as always, which the helper uses for the title and the on-screen footer.
- The stamping code is in `healdan-core` (call), so a later route (the new web interface's uploads, say) can use it.

### 9.8 Thumbnails

`GET /documents/{n}/thumbnail` returns the document's current thumbnail as an image, read through the same checks as its content (M2 §6.4). A document whose thumbnail hasn't been made yet (M1.5 makes them in the background) is `404`, and the Open window shows a placeholder.

### 9.9 Comments (owner, 2026-10-24)

- **A new property, Comments,** on Healdan's profile.
  - It's optional, editable, free text of up to 4000 characters, shown after Subclass.
  - A new seed step adds it (a shipped seed never changes, CLAUDE.md), so existing installs get it too.
  - The old web page and the API's form show it like any other property.
- **Searchable by its words:** the Documents search finds a document by a word in its Comments. Text properties are indexed only as exact fields today (§3.1), so the builder chooses how, for example by indexing Comments with the document's text (call). (changed in the build: Healdan's own Documents search, `DocumentsOnlySearch`, adds after the full-text hits the readable documents whose Comments contain every word of `q`, in any case, by a `LIKE` scan of `ld_document_ext` (`CommentsMatch`). They honour the search's folder, so `within` (§9.1) limits them too (R3). It's the old web page's Documents scope as well as the API's. Plan correction 4; the capability map records the cost. Comments belongs to one document, and doesn't pass down from folders.)
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
- **The channel between the add-ins and the helper.**
  - **What it is:** HTTP on `127.0.0.1`, at a port the helper picks at start. The port and a random secret are in a file only the user can read, and every request carries the secret, so other users of the computer can't drive the helper. The trial's extension used exactly this.
  - **What an add-in may ask:** to show a window, to save or open the document it has, or to set its title. It can't read the token. (as built: the routes are the plan's table, "The channel's routes". The final review added `POST /v1/adopted {"path"}`, which marks a new document's copy as adopted (§5.6) and answers its state, and `pending` in every copy state, true while the copy has changes on their way to Healdan.)
  - **What a web add-in still needs** (M3.5): it can't read the file, so it pairs with the helper once instead. The trial's shortcut, putting the secret in the add-in's page, isn't acceptable.
- **Links** carry no credentials, can only open documents, and only from the helper's own server (§5.3). (changed in the build: only §5.11's kinds; other files open only from the Open window, quarantined, and never the kinds that run programs. Final review C2.)
- **Local copies** are in a folder only the user can read. Read-only and checked-in copies are deleted when done; copies with unconfirmed changes stay until the user decides (§5.4).
- **HTTPS only,** apart from `localhost`, with the system's certificate checks (§3.3).
- **CE's `ldedit:` links put the session id in the URL;** Healdan's replacement doesn't (§8).
- **Stamping changes only the one property and the text of the fields showing it,** in files that already carry the property (§9.7). (found in the build: a signed package loses its signature when it's stamped, and the parts the stamper rewrites change form, though not content. The Install pages' Known Limitations say so; the plan's follow-ups keep the fix.)
- **No more permissions than it needs.** In M3 the helper needs only the Keychain. The "access data from other apps" permission was only for sideloading into Word's container, which didn't work (§3.1); M3.5 adds Automation, and Word's one-time folder grant.

## 11. Code organisation (calls)

- **`clients/`** is new, at the repository's root:
  - `clients/helper/`: the .NET solution, with `Healdan.Helper.Core` (no windows), `Healdan.Helper` (the Avalonia app), their tests, and the packaging scripts;
  - `clients/libreoffice/`: the extension's Python code, its tests and its packaging;
  - `clients/office/`: the Office add-in, from M3.5.
- **The API client** is written against `openapi.yaml`. Whether its models are generated from the file or written by hand is the builder's choice, as long as a test fails when the helper calls an operation or reads a field the file doesn't describe.
- **CI** gets a workflow for `clients/`. It builds and tests on Ubuntu and macOS for pull requests, and signs, notarises and attaches the packages for `helper-v*` tags. (changed in the build: `clients.yml` runs the helper's tests on `ubuntu-24.04` and `macos-15` and the extension's on `ubuntu-24.04`, and one aggregating `Clients` job passes when they passed, or were skipped because nothing under `clients/` changed. `Clients` should be required: the owner adds it to master's ruleset after its first green run. Plan correction 7, R16.)
  - `code-changed.sh` learns that a pull request touching only `clients/` needs no Java build, and one touching only the server needs no client build, so neither waits for the other (the owner wants CI's wait short). The required checks still report. (changed in the build: it gets a second output, `clients`, true for any change under `clients/` or to `clients.yml`, and on every push. `code` is false for a pull request that changes only `clients/`, docs or both. Plan correction 8.)
  - So a pull request that changes only `clients/` doesn't build the Docker image. The helper's test against the real server (§14.1) runs on master after the merge, and on pull requests that change the server. Its fake-server tests run on every client change. (accepted gap)
- **Server code** goes where M2's does: endpoints in `healdan-api`; domain code (favourites, device holds, stamping, the Comments seed) in `healdan-core`.

## 12. The trial build

### 12.1 Its question

Can a C# and Avalonia helper with slim add-ins give the flow in §5 on each setup, and which mechanism works for each part on each? The helper itself was settled (owner); the trial was to prove what the platforms' documentation can't promise, under the conditions a real install has.

### 12.2 What it had to show

Each row passes or fails on each setup (§3.2). The person checking watched the screen, and every prompt was written down.

| Row | What had to happen to pass |
|---|---|
| **T1 Links** | A `healdan://open/1234` link clicked in the browser reaches the helper, started or not, and the document opens in the right app within a few seconds. |
| **T2 Title bar** | The title reads `Lease agreement #1234 v2.1`, and moves on after each save and after Save as New Version, without closing the document, a prompt or a visible reload. |
| **T3 Saves** | Each save in the app is noticed once and sent once, complete. |
| **T4 Closing** | Closing is noticed within 5 seconds; after a Force Quit, within 30 seconds, with Reopen or Check In offered. |
| **T5 Taking over commands** | Open, Save and Save As show the helper's windows, with a working way back to the app's own boxes, and Recent Documents doesn't bypass the helper. |
| **T6 New documents** | After Save to Healdan, the open document is the local copy, title right, next save noticed, and no Grant File Access prompt. |
| **T7 Footer** | Insert Doc ID adds the field, and it moves on after a save without marking the document changed. |
| **T8 The add-ins reach the helper** | The extension and the add-in can call the helper, which refuses a request without the user's secret. |
| **T9 Tray and windows** | The helper's icon and menu show, and its windows come to the front and give the focus back. |
| **T10 Keychain** | The token is stored and read back without a prompt at every start, by the signed helper. |
| **T11 The other apps** | T2, T5 and T6 in Calc and Impress (and Excel and PowerPoint). |
| **T12 Installing the add-ins** | The helper installs the extension and the add-in, and they survive restarts. |

### 12.3 How it ran

- **Throwaway code** in a scratch folder outside the repository, never committed:
  - a menu-bar helper with placeholder windows, signed with the owner's Developer ID;
  - a Python LibreOffice extension;
  - an Office web add-in served from a local HTTPS server with Microsoft's development certificate, which the owner installed;
  - throwaway Docker stack with the smoke fixture.
- **Who did what.** Computer use wasn't available in the session, so the owner watched the screen and ticked each step in a checklist. Claude read the helper's and the extension's logs, and fixed the trial's own bugs between rounds.
- **The Ubuntu packages** were built and tested in Ubuntu 26.04 containers by a background agent: install, the extension, links, the keyring and the sign-in window. The owner's Ubuntu machine couldn't reach the trial's stack, so the Ubuntu rows on a real desktop are deferred to phase 2 (owner, 2026-10-24).

### 12.4 If a row had failed

The fallbacks the trial needed are now the design: one copies folder (T6 on Word), the helper's own crash window (T4), the extension's attach-once interceptor on every window (T5). The ones still open:

- **T7, T8, T11 and T12 for Office:** M3.5's first task (§7).
- **The Ubuntu rows:** phase 2's first task. If a window doesn't come to the front under Wayland (T9), the helper shows a notification that brings it up when clicked.

### 12.5 Results (2026-10-24)

"Pass with" names the mechanism that made it pass. "Deferred" rows weren't run, by the owner's decision.

| Row | M-LO (macOS, LibreOffice 26.8) | M-W (macOS, Word 16.114) | U (Ubuntu 26.04, LibreOffice 26.2) |
|---|---|---|---|
| **T1 Links** | **Pass:** Safari asked once to allow the site; the helper got the link as an Apple event and the document opened in 0.2 s | **Pass:** the same, opening in Word | Deferred. In a container: the desktop entry's second process handed the link to the running helper in 0.1 s |
| **T2 Title bar** | **Pass with** the extension setting the document's title: v1.0 → v1.1 → v1.2 → v2.0, no prompt, no reload | **Pass with** the helper asking Word through AppleScript to save under the new name: about 0.25 s, after two one-time permissions (Automation; Grant File Access for the copies folder) | Deferred |
| **T3 Saves** | **Pass:** one upload per save, about 2.7 s from Cmd+S to the new title (1.2 s settling, 1.25 s server) | **Pass:** the same, by watching the folder | Deferred |
| **T4 Closing** | **Pass with** the close event and the lock file, within 2 s. Force Quit was noticed in 10–20 s; AppleScript notifications failed, so the helper's own window offers Reopen, which reopens the kept copy | **Pass with** Word's owner file and process; Force Quit and Reopen as for LibreOffice | Deferred |
| **T5 Commands** | **Pass with** intercepting `.uno:Save`, `.uno:SaveAs` and every Open variant (`.uno:OpenFromWriter`, `.uno:OpenFromCalc`) on every window, the Start Center's included; a `.docx` saved with no "Keep current format" question; the way back in one click once the extension attached only once. **Open: Recent Documents** listed deleted copies; the fix wasn't rechecked (§6.2) | Word's own Save and Open stay Word's without an add-in, as expected; the add-in kind wasn't tried (deferred to M3.5) | Deferred |
| **T6 New documents** | **Pass:** the extension writes the chosen format to a temporary file, the helper files it, and the extension adopts the local copy; `.odt`, `.ods` and `.pptx` | **Pass with** one copies folder: Word asked Grant File Access once, for the folder, and not for the next document. Per-document folders asked each time, and Office's group container was refused to the helper | Deferred |
| **T7 Footer** | **Pass:** the field shows the property; refreshing it after a save didn't mark the document changed | Deferred (the add-in didn't load) | Deferred |
| **T8 Channel** | **Pass:** the extension called the helper over `127.0.0.1` with the secret from the user-only file (mode 600); a request with no secret or a wrong one got `403` | Deferred (the add-in didn't load) | Deferred |
| **T9 Tray and windows** | **Pass with** staying out of the Dock and handing the focus back (`NSApp hide:`) after each window | **Pass:** the crash window came up over Word | Deferred. Risk: GNOME on Wayland may not raise the windows |
| **T10 Keychain** | **Pass:** Security.framework from the signed helper; no prompt at start, even after rebuilds signed with the same identity | **Pass:** the same helper | Deferred. In a container: `secret-tool` with GNOME Keyring worked |
| **T11 Other apps** | **Pass:** Calc and Impress filed and titled | Deferred | Deferred |
| **T12 Installing** | **Pass with** opening the `.oxt` in LibreOffice and accepting; `unopkg` from the command line failed (§3.1) | **Fail:** the signed helper wrote the manifest to Word's sideload folder once the owner allowed "access data from other apps", but Word 16.114 didn't load it, even after its add-in cache was cleared; next: the Microsoft 365 admin centre (§7) | Deferred. In a container: `apt install` and `unopkg` worked |

**What the trial changed in this spec:**

- one copies folder (§5.4);
- every Open variant, the Start Center and attaching once (§6.2);
- the Recent Documents cleanup (§6.2);
- the crash window and native notifications (§5.1, §5.8, §5.10);
- staying out of the Dock and handing the focus back (§5.1);
- releasing after a failed open (§5.5);
- the `.oxt` install on macOS (§5.12);
- single-file signing (§5.12);
- Ubuntu's HarfBuzz library, its light tray icon and the link hand-over (§5.3, §5.12);
- Office's add-in route (§7);
- the order of work (§2.1).

## 13. Upstream files M3 edits, and docs pages

### 13.1 Upstream files

| File | Edit | Why |
|---|---|---|
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/common/client/util/Util.java` | `openEditWithOffice` (~827) calls Healdan's Open in App hook instead of building an `ldedit:` link | §8 |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/document/grid/ContextMenu.java` | `applyOfficeSecurity` (~384): Healdan's conditions (§8) | §8 |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/document/DocumentToolbar.java` | the button's click handler (~480) and its enabling (~696): Healdan's conditions | §8 |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/menu/MainMenu.java` | `getOfficeMenuItem`'s click handler (~487) calls `Util.openEditWithOffice` instead of building an `ldedit:` link with the session id, and the two imports that left unused go (changed in the build: added by the final review's M3; the item stays hidden, as menu −1090 has no row) | §8, §10 |
| `logicaldoc-i18n/src/main/resources/i18n/messages.properties` | `editwithoffice=Open in App` | §8 |
| `logicaldoc-i18n/src/main/resources/i18n/messages_en.properties` | `editwithoffice=Open in App` (changed in the build: added, R14) | §8: overrides `messages.properties` for English |
| `logicaldoc-i18n/src/main/resources/i18n/messages_en_US.properties` | `editwithoffice=Open in App` (changed in the build: added, R14) | §8: overrides it for US English |

There are no server-side upstream edits. The GUI edits are client code, which CI's tests don't cover, so `UPSTREAM_TESTS` is unchanged. CE's `officeaddinhintlauncher` message, which the docs-site work pointed at docs.healdan.com in all 34 bundles, is no longer shown, because Healdan's hook shows its own; the bundles stay as they are.

**Fork-owned files it changes:**

- `healdan-core`: `plugin.xml` (feature 20), `DocNumberQuery` (§9.6), favourites, device holds and Flyway `V4`, stamping and how filing knows its number (`DocNumberListener` if the builder needs it), the Comments seed step and its search;
- `healdan-api`: the endpoints and `openapi.yaml`;
- Healdan's GUI packages: the Open in App hook (changed in the build: `common.client.healdan.HealdanOpenInApp`, and `frontend.client.healdan.HealdanDocumentOpener`, which the frontend registers, §8);
- `docker/tests/test-api.sh` (§14.1), `.github/workflows/` (the clients workflow, and the helper's test in the Docker image workflow) and `.github/scripts/code-changed.sh`;
- `CLAUDE.md`, `docs/reference/healdan-api-inventory.md` (tags and endpoints) and `docs/reference/logicaldoc-ce-capability-map.md` (§5.1: Open in App and the helper; §2.3: Comments).

### 13.2 Docs pages (the docs site, `site/content/`)

**New:**

- `install/office-integration.md`: for whoever runs the server: HTTPS is required, where users get the helper, what Open in App needs, and the security notes on links (§5.3).
- `user/office/` (a new section, "Working in LibreOffice"; Word joins it in M3.5):
  - `_index.md`: how the helper, the extension and the web page fit together;
  - `installing-the-helper.md`:
    - macOS: the disk image, accepting the LibreOffice extension, and where the helper lives in the menu bar;
    - Ubuntu: LibreOffice first, then the `.deb`, and the tray icon;
    - signing in, signing out, and the prompts to expect;
  - `saving-to-healdan.md`: the Save window, the format, New Folder, Favorites, required properties and Comments;
  - `opening-documents.md`: Open in App, the Open window, `#` lookup, read-only copies, a document on another computer;
  - `editing-and-versions.md`: saves and snapshots, Save as New Version, Save as New Document, Properties, closing;
  - `doc-id-footer.md`: Insert Doc ID, and what the footer shows;
  - `when-things-go-wrong.md`: no server, crashes, ended check-outs, waiting saves.
- `developer/desktop-clients.md`: building and testing the helper and the extension; the clients workflow; releases and signing secrets.

**Changed:**

- `developer/api.md`: search within a folder, `POST /folders`, favourites, devices and `held-elsewhere`, `ifVersion`, `ifHeld`, `version-moved` and `not-held`, stamping, thumbnails, and the `#1234 v2.1` form.
- `developer/continuous-integration.md` and `developer/building-from-source.md`: the clients workflow, `code-changed.sh`'s new split, and where the packages come from.
- `install/reverse-proxy.md`: HTTPS is required for the helper.
- `install/known-limitations.md`: no Microsoft Office or Windows yet, the footer in Writer only, stamping only through the helper, dates west of UTC in the web page.
- `user/documents/checking-out-and-checking-in.md`: check-outs from the apps, and on which computer.
- `user/documents/bookmarks.md`: bookmarks are the helper's Favorites.
- `user/finding-documents/finding-by-doc-number/index.md`: the `#1234 v2.1` form.
- `user/working-with-matters/properties/`: Comments, and finding a document by it.

(changed in the build) The user pages with pictures are page bundles (`installing-the-helper/index.md` and so on), so their addresses are the same. The pictures are offscreen renders of the helper's real windows, to be replaced with screenshots at acceptance. Checking Out and Checking In and Bookmarks were interim pages, so they were written in full. Searching (Comments in the Documents scope), the Developer Guide's Tests and Building from Source, and the three manuals' landing pages changed too. PR A and PR C wrote the API page's and the CI pages' parts with their code (R12, R17).

## 14. Verification

### 14.1 Automated

**Server (`healdan-api`, `healdan-core`), in CI as M2's tests are.** Each new endpoint and rule gets the success case, `404` for what the caller can't read, `403` for what they can't change, and each rule it can hit:

- `within` limiting `folders` and `documents`, and refusing a Store;
- `POST /folders` refused at a matter's top level for a non-admin, in a search folder and without Add; the new folder inheriting its parent's properties and permissions;
- favourites leaving out matter workspaces and what the caller can't read; adding and removing; the same bookmarks seen by the old web page's Bookmarks;
- devices:
  - `held-elsewhere` on check-out, save, check-in, release and property changes from another device;
  - `move: true`;
  - a new token with the same device id keeping the hold;
  - a web-page check-out working from any device;
  - a stale record not blocking;
- the preconditions:
  - `version-moved` for a stale `ifVersion`;
  - `not-held` for `ifHeld` on a released document, one checked in from the web page, and one someone else holds (changed in the build: someone else's hold is `409 checked-out`, which comes first, §9.5);
  - a repeated `saveId` answered `201`, storing nothing (changed in the build), and `409 version-moved` once newer content was stored over it, after a check-in on closing too, or its version was deleted and other content took its `fileVersion` (changed after the build);
- stamping:
  - a `.docx` and an `.odt` with the property, filed and saved, carry their own number and version in the property and in the field's shown text;
  - a Save as New Document's file carries the new number;
  - a file without the property is stored byte for byte;
  - a filing that fails takes no number (changed in the build: a filing the API refuses takes none, and one that fails inside CE's create leaves a gap, §9.7);
- the thumbnail, and `404` before one exists;
- the Comments seed on a fresh and an existing database, and the Documents search finding a word in it;
- `#1234 v2.1` in `DocNumberQuery`, `/lookup` and `/search`;
- the contract: every response checked against `openapi.yaml`, and `RouteCoverageTest` (M2 §4.6).

**The flow on the Docker stack.** `docker/tests/test-api.sh` adds these steps, in order:

1. search within the matter;
2. create a folder and add it to favourites;
3. file a stamped `.docx` into it and check its number;
4. check it out from one device, then get `held-elsewhere` from a second device of the same user;
5. move the check-out, then get `not-held` from the first device (changed in the build: `held-elsewhere`, R2);
6. save with a stale `ifVersion` (`409`), then make a good save.

(changed in the build) These are steps 20 to 28 of `test-api.sh`, which also send a good save again with its `saveId`, look the document up in the title's form, `#n v<version>`, and leave nothing checked out.

**The helper's core** (no windows), in the clients workflow on Ubuntu and macOS (call):

- version arithmetic and file names in the shared folder;
- the open-save-close cycle against a fake server and a fake app writing files, including the failure cases in §5.10:
  - the server going down mid-queue;
  - a restart with saves waiting;
  - `held-elsewhere` and `not-held`;
  - `version-moved` with and without new content;
  - `401`, and signing in again with the same device id;
- adopting a new copy without sending it as a save;
- releasing after a failed open;
- the record beside each copy surviving a restart;
- never deleting a copy with unconfirmed changes, nor another copy in the folder;
- `name` sent with every save.

**The helper against the real server** (call): the core's cycle run against the Docker stack after the smoke test, in the Docker image workflow, with a fake app (§11 says when it runs).

**The extension's** Python logic has unit tests. Whether a headless LibreOffice runs in CI to test the interception, the attach-once rule and the Recent Documents cleanup is the plan's call. (changed in the build: none runs in CI. The tests drive `healdan_logic.py`'s flows with fakes for LibreOffice, and build the `.oxt` and check its parts agree. The UNO layer was checked once in a headless LibreOffice 26.8.1 with a scratch profile and a fake helper, and the rest is in the owner's acceptance.)

### 14.2 Manual acceptance (owner)

For each phase, on its setup (§3.2), against the owner's server over HTTPS, as a non-admin user, with the helper installed from the release's package:

1. Install the helper and sign in. The extension appears, and the Healdan menu is in the Start Center.
2. Create a document, press Save: Save to Healdan opens.
   - Find the matter by search and through Recent Matters.
   - Make a new folder and star it.
   - See the required fields and inherited values, and write a Comment.
   - Keep the app's own format, and save. The title reads `… #n v1.0`.
3. Edit and save twice: the title reads `v1.1`, then `v1.2`. Close: it's checked in, and it isn't in Recent Documents.
4. In the web page, Open in App: it opens checked out.
   - Insert Doc ID: the footer reads `#n v1.2`.
   - Save as New Version: `v2.0`, in the title, in the footer, and in the web page's preview.
   - Close.
5. Press Cmd+O (Ctrl+O on Ubuntu), from Writer and from the Start Center, and open by `#n`: it opens.
   - As a second user, open it: a read-only copy that says who has it.
6. On a second computer, open it: the warning; Edit Here Instead. Back on the first, save: refused, and Save as New Document is offered.
7. Turn off the network, save, turn it back on: the save arrives. Force Quit the app: the helper offers Reopen, and Reopen brings back the saved text.
8. Save a new Writer document as `.docx`; search the Documents scope for a word from its Comment and find it.
9. In Calc and Impress: save a new file to Healdan and reopen it.

## 15. Left to the builder

- Library choices (HTTP, JSON, keychain access, file watching, the docx and odt stamping), within §5, §9.7 and §10.
- How the API client is kept true to `openapi.yaml` (§11).
- How filing knows its number before storing (§9.7), and how Comments is made searchable by its words (§9.9).
- The menu's exact items beyond §5.1, window sizes, icons and the notification wording.
- How stale device records are cleared (§9.4).
- How the Start Center's window is found (the trial scanned the windows every second; a frame or global event is better if LibreOffice gives one).
- Whether a headless LibreOffice test runs in CI (§14.1).

## 16. Decisions made in this session

**Owner:**

- **The helper:**
  - a small app on each computer, in C# on .NET with Avalonia;
  - it draws the Save, Open and Properties windows once for every app;
  - the add-ins stay slim, and there's no Java on the user's computer.
- **The flow** in §1, modelled on iManage, with the Save window's three panes.
- **The title bar** reads `Lease agreement #1234 v2.1`, and the footer the same.
- **The app's own commands:**
  - Healdan takes over Open, Save and Save As, with a way back to the local box;
  - where an app won't allow it, Healdan's buttons sit beside its own.
- **Saving:**
  - a new document's Save opens Save to Healdan;
  - a Healdan document's save sends a snapshot, and the title moves on.
- **Formats:** each app saves in its own format by default, and the Save window offers the other at the first save.
- **A Comments property,** as in iManage's Save window.
- **The failure behaviour** in §5.10, with a warning that edits on the other computer which haven't reached Healdan won't be in the copy when editing moves.
- **In M3:** search within a matter, and New Folder. Favourites are CE's bookmarks.
- **Not in M3:** the Security tab and Compare Versions.
- **The web page:** it keeps double-click as preview, Open in App is CE's hidden Edit with Office, and M6 changes double-click.
- **Production** uses HTTPS from Nginx Proxy Manager with Let's Encrypt, and the owner's Apple Developer account signs the Mac helper.
- **The setups:**
  - Ubuntu 26.04 with GNOME and LibreOffice 26.2.6;
  - macOS 27 with LibreOffice 26.8.1 and Word.
- **The trial** runs before this spec is approved, and the owner checks its rows.
- **After the trial (2026-10-24):**
  - the concept is proven;
  - the Ubuntu rows and the Word add-in rows are deferred;
  - macOS with LibreOffice comes first;
  - Linux follows once the owner's semi-production server is up;
  - Microsoft Office follows later.

**Calls (open to the owner's review):**

- **Milestones and naming:**
  - M3 is done with phases 1 and 2, and Microsoft Office on the Mac becomes M3.5 (§2.1);
  - the name "Healdan Helper" (§4).
- **Devices and links:**
  - a device id per computer, so signing in again keeps its check-outs (§5.2, §9.4);
  - `healdan://open/<n>?server=<host>` links that carry no credentials, can only open, and need no extra confirmation (§5.3).
- **Local copies:**
  - one folder for all of them, named as the title, with a hidden record beside each;
  - never deleted with unconfirmed changes (§5.4).
- **Saves:**
  - sent in the background, queued in order, with `name`, `ifVersion` and `ifHeld` (§5.5, §9.5);
  - after a failed open, the check-out is released (§5.5);
  - Save as New Document releases the original unchanged and switches the window to the new document (§5.5);
  - a `version-moved` refusal is resent once, only when nobody has stored newer content (§5.10).
- **The windows:**
  - "Clients" in the left pane is a search by client, not a list of every client (§5.8);
  - older versions open read-only from the Open window, with a thumbnail from a new endpoint (§5.8, §9.8);
  - native notifications, or the helper's own window, never AppleScript notifications (§5.1).
- **The server:**
  - it stamps the doc ID into Word and LibreOffice files that carry the property, on every store through the API, and filing knows its number first (§9.7);
  - device holds are checked only on the API, and the web page acts for the person on any computer (§9.4);
  - Comments is searchable by its words (§9.9: the owner said "add that", which this reads as a searchable property);
  - the other server additions in §9, and the answers to M2's questions in §9.10.
- **Packaging:**
  - a notarised disk image, and amd64 and arm64 `.deb` files, from `helper-v*` tags;
  - the helper installs the extension;
  - no self-updating (§5.12).
- **The clients:**
  - Python for the LibreOffice extension (§6);
  - an Office web add-in for Word unless M3.5's check says otherwise (§7);
  - Open in App on while checked out and for Download alone, the Tools menu item left hidden, and no session id in the link (§8).
- **The repository:**
  - `clients/` at the root;
  - CI split so client and server changes don't wait for each other;
  - the helper's real-server test running on master for client-only changes (§11).
