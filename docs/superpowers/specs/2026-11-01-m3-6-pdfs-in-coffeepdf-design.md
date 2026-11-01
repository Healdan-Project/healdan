# M3.6 "PDFs in CoffeePDF": design

- **Milestone:** M3.6 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-11-01
- **Status:** design agreed with the owner section by section on 2026-11-01. Awaiting the owner's review of this written spec. The implementation plan follows in `docs/superpowers/plans/`.
- **Inputs:**
  - CoffeePDF's v1 design spec ([`docs/superpowers/specs/2026-10-29-coffeepdf-v1-design.md`](https://github.com/coffeemuse/CoffeePDF/blob/main/docs/superpowers/specs/2026-10-29-coffeepdf-v1-design.md) in `coffeemuse/CoffeePDF`), above all §4.4, the list of Healdan's changes that this milestone builds, §2.5, the acceptance it must make possible, §5 and §6, and §16's "For Healdan's milestone (Phase 3) and the acceptance (Phase 4)";
  - CoffeePDF's code at `5280c58`: `ChannelRoutes`, `CopyLock`, `AtomicFile` and `Settings/`;
  - the [M3 spec](2026-10-24-m3-office-integration-design.md) (§5.11, the kinds of file) and the [M3.5 spec](2026-10-30-m3-5-microsoft-office-design.md) (§5.1, the cycle's set of apps; §5.10, Settings; §5.14, after a crash);
  - a survey of the Helper at `helper-v0.4.0`, which is master at `1c9aabaa3`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard and approved section by section. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

"CoffeePDF's spec" is CoffeePDF's v1 design spec, and "CoffeePDF §n" one of its sections. This spec refers to it rather than repeating it. "The Helper" is the Healdan Helper, and "the channel" its local channel (the Developer Guide's Desktop Clients page).

## 1. Intent

The Healdan Helper opens PDFs in CoffeePDF, the owner's PDF app for macOS, and runs M3's cycle for them as it does for LibreOffice: checked out on open, a snapshot on each save, the title moving on, and checked in on close. CoffeePDF already speaks the channel (its Phase 2, built 2026-10-31 and checked against Healdan Helper 0.3.0-rc1). This milestone is the Helper's side, CoffeePDF's Phase 3 (CoffeePDF §13).

CoffeePDF §4.4 lists the changes. Checked against `helper-v0.4.0`, some are done already, one is a bug §4.4 didn't see, and a few go further than §4.4 says (§3). This spec is the corrected list.

## 2. Goals, non-goals and the gate

### 2.1 Done when

- The acceptance walkthrough (§11.2) passes on the owner's Mac against the owner's server, on a Healdan Helper 0.5.0 release candidate: CoffeePDF §2.5, which is also CoffeePDF's Phase 4, plus four rows only the Helper's side can fail (owner, 2026-11-01).
- Healdan Helper 0.5.0 is released, signed and notarised.
- The web page's Open in App offers PDFs (§5.1).

### 2.2 Non-goals

- **Linux.** CoffeePDF's Linux phase follows the Helper's (M3's phase 2). Off macOS, CoffeePDF is never available.
- **A PDF chooser in the Helper's Settings** (owner). CoffeePDF's own Healdan support preference (CoffeePDF §5) is the switch.
- **The holder's computer in a copy's state** (CoffeePDF §4.4 item 9; owner, dropped). The API shows `hold.device` only to the user who holds the check-out, and `null` to anyone else (`HoldView` in `openapi.yaml`), so the second account in CoffeePDF §2.5 step 5 can never be told it. CoffeePDF's read-only bar names the person.
- **A change to the server or the API.** A check-out's `app` is free text already.
- **A change to how LibreOffice or Microsoft Office work,** beyond §2.3's exceptions.

### 2.3 The gate, and what changes without CoffeePDF

What depends on CoffeePDF changes nothing until CoffeePDF is installed with Healdan support on (CoffeePDF §4.4). Until then PDFs open read-only in the default app, as today.

Four changes reach everyone, with or without CoffeePDF (owner, 2026-11-01):

1. **A `healdan://` link to a PDF opens it,** read-only in the default app, where today it's refused (§5.1).
2. **`/v1/closed` deletes a default-app copy,** which closes Healdan-Project/healdan#134 (§5.5).
3. **The channel's `readOnly` is true for a copy whose saves no longer go up** (§6.3). The Office add-in's Save as New Version sees it.
4. **`/v1/file` honours `app`** (§6.2), a bug fix.

## 3. What the Helper does at `helper-v0.4.0`

Each of CoffeePDF §4.4's items, as found:

| §4.4 item | Found | Here |
|---|---|---|
| 1. `CoffeePdfApp` | `IOfficeApp` has grown `Start`, `CheckOutName`, `IsRunningFor`, `LockWritten` and `Scripting`, each with a default. `CopyNames.IsAppFile` is one of four places that name the apps' files: `CopyStore.Delete`, `Moved` and `IsOpen` name LibreOffice's lock and Office's owner files one by one. | §4.1, §4.5 |
| 2. An app per kind | M3.5's `OfficeApps` and `AppChooser` already choose an app per file. A PDF has no `FileKind`, so it goes to `Default`, read-only. | §4.3 |
| 3. Links and the Open window | A link refuses a PDF ("a link opens only documents, spreadsheets, presentations and drawings"). The Open window forces it read-only, with a note. | §5.1, §5.2 |
| 4. `/v1/save-new` and `/v1/file` | `/v1/save-new` takes `app` and `formats: ["pdf"]` already. **`/v1/file` ignores `app`**: a filed PDF's record would name LibreOffice, the close watch would look for LibreOffice's lock, and after a restart the Helper would check the PDF in from under CoffeePDF's window. | §6.2 |
| 5. "Available" | M3.5's `AppChooser` reads the settings and the installed apps at each call. | §4.2 |
| 6. The web page | `HealdanOpenInApp` lists its extensions. | §5.1 |
| 7. Docs | The user, install and developer pages say PDFs open read-only, and quote the link's refusal. | §10.3 |
| 8. A lock that comes late, and a save by rename | `CloseWatcher` ignores a lock it has never seen, and counts the app running without one as neither a close nor a crash. `SaveWatcher` routes each event by the copy's name, so a rename onto the copy is a write of it, and the temporary file is never looked at. **But** the stray sweep (`CopyStore.ToSetAside`) would set a leftover `.coffeepdf-….tmp` aside as a stray copy. | §6.1, §6.4 |
| 9. The holder's computer | The API hides another user's device. | dropped (§2.2) |
| 10. A refused save's state | Still `readOnly: false`: `EndCheckout` ends the session and clears `CheckedOutHere`, and `StateOf` reads `ReadOnly` from the record. | §6.3 |
| §16: Healdan-Project/healdan#134 | Open. `OpenOtherAsync` begins no session, so `/v1/closed` is dropped. | §5.4, §5.5 |
| §16: `"path": null`, `"replaces": null` | Both count as missing: `ChannelHandler.Optional` reads a JSON null as no value. | tests (§11.1) |

The channel's code didn't change between `helper-v0.3.0` and `helper-v0.4.0`, so CoffeePDF's `ChannelRoutes` matches it today.

## 4. CoffeePDF in the cycle

### 4.1 `CoffeePdfApp` (call)

`CoffeePdfApp : IOfficeApp`, in `Cycle/` beside `LibreOfficeApp`:

- **`Name`** is `CoffeePDF` (`AppName`). `CheckOutName` keeps its default, so the check-out names it too: "#1234 is checked out to you on MacBook (CoffeePDF)".
- **`Open`** runs `open -b com.healdan.coffeepdf <path>`, waited for as `LibreOfficeApp.Open` waits. A non-zero exit throws, so a failed open deletes the fresh copy and releases the check-out, as for LibreOffice. CoffeePDF keeps one instance and takes each file as an open-file event (CoffeePDF §4.3).
- **`IsRunning`:** a process named `CoffeePDF`.
- **`LockExists`:** `.~coffeepdf.<copy file name>#` beside the copy (CoffeePDF §6.8, `CopyLock`).
- **`Start`, `IsRunningFor`, `LockWritten` and `Scripting` keep their defaults.** CoffeePDF restores nothing after a crash, so there is no recovery watch (M3.5 §5.14 is Office's), and a crash gets the crash window LibreOffice's copies get (§7).

The bundle identifier goes in `AppBundles` (`CoffeePdf = "com.healdan.coffeepdf"`). `AppBundles.NameOf` doesn't learn it: it names the system's default app for an office kind's main format, which CoffeePDF never is.

### 4.2 What "available" means (call)

CoffeePDF is available when it's installed and its Healdan support is on. Both are read at each choice, as M3.5's `AppChooser` reads everything, not "at start and as the menu opens" as CoffeePDF §4.4 item 5 had it, so turning support on takes effect with the next PDF opened.

- **Installed:** `InstalledApps` gains `CoffeePdf`, found as M3.5 finds the others: `CoffeePDF.app` in `/Applications` or `~/Applications`, else LaunchServices by bundle identifier. Never off macOS.
- **Support on:** a new `CoffeePdfSettings` in `Settings/` reads `settings.json` from CoffeePDF's settings folder, found as CoffeePDF finds it (`SettingsLocation`): `COFFEEPDF_SETTINGS_DIR` when it's set, else `~/Library/Application Support/CoffeePDF/` on macOS. Support is on only when `healdan.enabled` is the JSON value `true` (CoffeePDF's `SettingsStore` documents the shape as a contract). A missing, unreadable or malformed file, or a value of another type, is off. The Helper only reads the file.

### 4.3 Choosing the app (owner, call)

- **`AppChooser.For`** sends a `.pdf` file, by its own extension in any case, to CoffeePDF while it's available, else to `Default`, read-only, as today. Nothing else it chooses changes.
- **PDFs don't become a `FileKind`** (call). Kinds drive Settings' office choices, the files' icons and LibreOffice as the fallback, and none of those should take PDFs in. A small `PdfFiles.Is(name)` serves the chooser, the links (§5.1) and the Open window (§5.2).
- **`OfficeApps`** takes CoffeePDF as a fourth app, null where it can't be installed (off macOS). It's always in `All`, whatever its availability, so `Of(record)` finds the app a copy was opened in: a PDF opened in CoffeePDF finishes its cycle there even if support is turned off meanwhile, as CoffeePDF §5 expects. A record naming CoffeePDF read by a Helper without it falls back to LibreOffice, as any unknown name does.

### 4.4 Settings (owner, call)

"Open Each Kind of File In" gains a **PDFs** row, text only, with no choice:

- "CoffeePDF" while it's available;
- "Default app, read-only" otherwise;
- with CoffeePDF installed and its support off, a note under the row: *Turn on Healdan support in CoffeePDF's Settings to edit PDFs there.*

The group's note ("Other files open read-only in the computer's default app.") stays. The Helper's menu doesn't change (owner).

### 4.5 One list of an app's files (call)

- **`CopyNames.AppFiles(copyFileName)`** lists every lock or owner file any app may keep beside a copy: LibreOffice's lock, Word's, Excel's and PowerPoint's owner files (`OfficeOwnerNames`), and CoffeePDF's lock.
- **`CopyStore.Delete`, `Moved` and `IsOpen`** use it. So a crash's stale CoffeePDF lock goes with its copy, as Office's owner files do, and setting copies aside for a server change counts a copy open in CoffeePDF as open.
- **`CopyNames.IsAppFile`** recognises every name in it, and CoffeePDF's temporary files too: `.coffeepdf-` followed by 32 hex digits and `.tmp` (`AtomicFile`), so the stray sweep leaves them where they are (§6.4).
- A later app adds one entry.
- **The names fit.** CoffeePDF's lock adds 13 bytes to a copy's name, and `CopyNames.MaxBytes` (241) leaves 14, so it stays. Its comment names CoffeePDF's lock beside LibreOffice's.

## 5. Opening

### 5.1 Links (owner, call)

- **A link takes PDFs** as well as M3's kinds: `CopyCycle`'s link check becomes "a kind, or `PdfFiles.Is`". Its refusal for other files says "documents, spreadsheets, presentations, drawings and PDFs".
- **The chooser picks the app,** as for any open. In CoffeePDF the PDF is checked out, unless the link asks for read-only. Without CoffeePDF it opens read-only in the default app, with macOS's quarantine, as from the Open window today.
- **Links refused PDFs because of final review C2:** any page can make a link to any document, so a link opened only kinds an office app edits. The guard that matters, never handing the default app a file that can run a program (`DefaultApp.Refuses`), stays, and a PDF isn't one. A read-only PDF in the default app is what the Open window already allows.
- **The web page's Open in App** adds `pdf` to `HealdanOpenInApp`'s extensions, in a pull request of its own, as `.odg` did (§12).

### 5.2 The Open window (call)

The window forces read-only exactly when the chooser would send the file to the default app (`For(name)` is `Default`), and its note says why. One rule covers:

- **a PDF without CoffeePDF:** *PDFs open read-only in the computer's default app. Turn on Healdan support in CoffeePDF to edit them there.*;
- **any other file,** with today's note (*Only office documents can be edited from here: this one opens read-only.*);
- **an office file on a Mac with neither LibreOffice nor Office,** which today the window offers to edit and the cycle opens read-only anyway.

With CoffeePDF available, a PDF can be edited, and Open Read-Only stays.

### 5.3 Checking out

A PDF for CoffeePDF goes the way an office file goes: checked out as `CoffeePDF`, downloaded, recorded with the app `CoffeePDF`, and opened. Every read-only case is unchanged: someone else holds it, the user can't edit it, read-only was asked for, or an older version. Such a copy has the `(read-only)` name and mode 0400. Checked out to the same user on another computer, the Held Elsewhere window asks first. A PDF already open in CoffeePDF comes to the front.

Copies opened in CoffeePDF don't carry macOS's quarantine, as LibreOffice's don't. Only the default app's do.

### 5.4 Read-only copies in CoffeePDF are tracked

They go through the usual path, with a session, not `OpenOtherAsync`, so CoffeePDF's `/v1/closed` deletes the copy at once. A read-only copy has no lock file, so CoffeePDF quitting counts as its close. At the Helper's start, it's deleted unless CoffeePDF is running, and watched if it is. All of this is what LibreOffice's read-only copies already get.

### 5.5 Healdan-Project/healdan#134, for the default app (owner, call)

- **The channel's `/v1/closed`** (`CopyCycle.ClosedAsync`), finding no session, deletes the copy when its record is a read-only copy for the default app with no unconfirmed changes: the rule `ResumeAsync` applies at the next start.
- **Any other `/v1/closed` with no session** is logged as ignored, so a dropped report shows.
- **This lives in the route,** not in the cycle's own `Close`, which the cycle calls for its own reasons.

### 5.6 Kept copies

A copy kept with unconfirmed changes (the menu's kept copies) reopens in the app its record names, CoffeePDF for a PDF opened there. Unless it's still checked out here, the channel answers it `readOnly: true` (§6.3), so CoffeePDF offers Save as New Document, which is what a kept copy is opened for.

## 6. Saving, filing and the channel

### 6.1 Saves

`SaveWatcher` doesn't change (§3). CoffeePDF's save, a temporary file in the copy's folder renamed over the copy (CoffeePDF §6.4, `AtomicFile`), counts as one save of the copy. Two tests show it against the real `FileSystemWatcher`, so CI's macOS run uses FSEvents (§11.1).

`/v1/saved`, `/v1/new-version`, `/v1/state`, `/v1/adopted` and `/v1/properties` don't change.

### 6.2 Filing (call)

- **`/v1/file` reads `app`:** `CoffeePDF` files with `CoffeePdfApp`, and `LibreOffice` or none with LibreOffice. Any other value files with LibreOffice too, and is logged. It doesn't ask whether CoffeePDF is available: the caller is the app.
- **The app goes to `AdoptNewAsync`,** which already takes one (M3.5's filing passes Microsoft Office), so the record names CoffeePDF, the close watch looks for CoffeePDF's lock, and a restart no longer checks the copy in from under its window.
- **The failure notice** names the app from the request where it now says "the copy LibreOffice wrote for it".
- **`/v1/save-new`** needs no change: `app`, `formats: ["pdf"]` and `"path": null` work today.

### 6.3 `readOnly` on the channel (owner, call)

- **`CopyState` gains `SavesStopped`.** `CopyCycle.StateOf` sets it for a copy that isn't read-only but whose saves won't go up: its record isn't checked out here, or its session has ended (a refused save, or newer content stored, M3 §5.10).
- **`ChannelHandler.Json` writes `readOnly` as `ReadOnly || SavesStopped`.** That's what CoffeePDF §6.9 expects after a refusal, and its Phase 2 fake Helper already answers it.
- **The `title` doesn't change:** it's the copy's file name, which keeps no `(read-only)`. CoffeePDF's bar says read-only, with `holdBy` null.
- **Inside the Helper, nothing changes:** its windows, the crash handling and the add-in handler's server side read `ReadOnly` as they do today.
- **Other clients.** The LibreOffice extension doesn't read the field. The Office add-in's page reads `state` through the same JSON (`AddinHandler`'s `state` route), so its Save as New Version on such a copy says *This is a read-only copy, so it can't be saved as a new version…* where today it does nothing.

### 6.4 CoffeePDF's temporary files (call)

- **At start,** `ResumeAsync` sweeps `.healdan-*.part` already. It also deletes `.coffeepdf-` + 32 lowercase hex digits + `.tmp` in `Copies`, a crash's leftovers, only while no `CoffeePDF` process runs, so it never takes one from under a save.
- **Otherwise the Helper leaves them alone:** the stray sweep skips them as app files (§4.5), and the save watch never looks at them (§6.1).

### 6.5 For CoffeePDF's `ChannelRoutes`

The channel's routes and shapes don't change. Two meanings do, and the Desktop Clients page gives both:

- `readOnly` is also true once a copy's saves have stopped going up (§6.3);
- `/v1/file` honours `app` (§6.2).

CoffeePDF §9 has `ChannelRoutes` follow the channel in the same release: its `StateShape` and `File` descriptions say so.

## 7. Closing, crashes and restarts

Nothing here is CoffeePDF's own: the cycle's rules apply, asking each copy's app.

- **A close:** CoffeePDF removes its lock and calls `/v1/closed` (CoffeePDF §6.8), and the copy is checked in and deleted. The lock gone at two checks in a row closes it too, whichever comes first; the second does nothing.
- **A crash:** CoffeePDF's process gone with the lock still there. The crash window offers Reopen, which opens the copy in CoffeePDF and clears the crash, and Send and Check In, whose delete takes the stale lock with it (§4.5).
- **At the Helper's start,** for a copy whose record names CoffeePDF: its lock there and CoffeePDF running, it's watched; its lock there and CoffeePDF not running, it's crashed; no lock, its window closed while the Helper wasn't running, so it's checked in; read-only, §5.4.
- **The Helper away:** CoffeePDF saves locally, and the Helper's resume sends what it finds (CoffeePDF §6.9), as for LibreOffice.

## 8. Security (calls)

- **The channel** is unchanged: loopback, its secret file, and paths only in `Copies`.
- **A PDF link without CoffeePDF** opens the PDF read-only in the default app. A PDF can't run a program, and `DefaultApp.Refuses` and the quarantine stay for everything the default app is given (§5.1).
- **CoffeePDF's `settings.json`** is read, never written, and only `healdan.enabled` from it. Anything the Helper can't read as `true` is off.
- **The temporary-file sweep** deletes only names of CoffeePDF's exact pattern, only in `Copies`, and only while CoffeePDF isn't running.
- **The new deletes,** `/v1/closed` for a default-app copy (§5.5) and the sweep, touch only files whose bytes are Healdan's or CoffeePDF's own leftovers: a copy with unconfirmed changes is never deleted.

## 9. Code organisation (calls)

- **`Healdan.Helper.Core`:**
  - `Cycle/`: `CoffeePdfApp` (new); `OfficeApps` (a fourth app); `CopyCycle` (links, `ClosedAsync`, `StateOf`, the start's sweep); `CopyState` (`SavesStopped`).
  - `Settings/`: `CoffeePdfSettings` and `PdfFiles` (new); `AppBundles`, `InstalledApps` and `AppChooser`.
  - `Copies/`: `CopyNames` (`AppFiles`, `IsAppFile`) and `CopyStore`.
  - `Channel/`: `ChannelHandler` (`/v1/file`'s `app`, `readOnly`).
- **`Healdan.Helper`:** `Platform/OfficeAppSet` (builds the set and the chooser with CoffeePDF), the Settings window's PDFs row, and the Open window's rule.
- **`logicaldoc-gui`:** `HealdanOpenInApp` (`com.logicaldoc.gui.common.client.healdan`, Healdan's own), in its own pull request.
- **Pull requests (call; the plan settles them):** the Helper with its docs, then the web page's `pdf`. The second merges once Healdan Helper 0.5.0-rc1 exists (§12).

## 10. Upstream files, fork-owned files and docs pages

### 10.1 Upstream files

None. `UPSTREAM_TESTS` is unchanged.

### 10.2 Fork-owned files

- `clients/helper/`: §9's files and their tests;
- `logicaldoc-gui/.../common/client/healdan/HealdanOpenInApp.java`;
- `CLAUDE.md` (the clients paragraph points to this spec), `docs/ROADMAP.md`, and this spec.

### 10.3 Docs pages (the docs site, `site/content/`)

The docs name CoffeePDF and link to its repository, [coffeemuse/CoffeePDF](https://github.com/coffeemuse/CoffeePDF), which is public (owner, 2026-11-01).

- `user/office/opening-documents/`: a PDF opens in CoffeePDF, checked out, when CoffeePDF is installed with Healdan support on, and read-only in the Mac's usual app otherwise; a link and Open in App now open PDFs.
- `user/office/installing-the-helper/`: Settings' PDFs row and its note, and where to get CoffeePDF.
- `user/office/when-things-go-wrong/`: the link refusal's new wording, and a crash in CoffeePDF.
- `install/office-integration.md`: PDFs in CoffeePDF, and "Other files" now opening from a link too.
- `install/known-limitations.md`: PDFs are edited only in CoffeePDF, on macOS.
- `developer/desktop-clients.md`: CoffeePDF in the cycle (its lock, its temporary files and the `settings.json` the Helper reads), the read-only copies tracked, `/v1/closed` for the default app, and §6.5's two meanings.

## 11. Verification

### 11.1 Automated

In `Healdan.Helper.Core.Tests`, on Ubuntu and macOS in CI, with a fake `IOfficeApp` for CoffeePDF, so no CoffeePDF build is needed:

- `CoffeePdfApp`: the lock's name and `LockExists`.
- `CoffeePdfSettings`: on, off, missing, malformed, `enabled` of another type, and `COFFEEPDF_SETTINGS_DIR`.
- `AppChooser`: a `.pdf`, in any case, goes to CoffeePDF only when it's installed and on, else to `Default`; nothing else moves.
- `OfficeApps.Of`: a record naming CoffeePDF finds it while it isn't available.
- `CopyNames` and `CopyStore`: `AppFiles`, `IsAppFile` with near-miss names, `Delete` taking CoffeePDF's lock, `IsOpen` seeing it, and the stray sweep leaving the lock and a temporary file in place.
- `SaveWatcher`, against the real watcher: `.coffeepdf-<32 hex>.tmp` written in `Copies` and renamed over a watched copy settles once, with the new bytes, and the temporary file is never reported.
- `CloseWatcher`: with CoffeePDF running and no lock yet, neither a close nor a crash; once the lock comes and goes, a close.
- `CopyCycle`:
  - a PDF link, with CoffeePDF available (checked out, record `CoffeePDF`) and without (read-only, default app);
  - a read-only CoffeePDF copy deleted by `/v1/closed`;
  - a restart with CoffeePDF's copies, each case in §7;
  - the start's temporary-file sweep, only while CoffeePDF isn't running.
- `ChannelHandler`:
  - `/v1/file` with `app: "CoffeePDF"`, `"LibreOffice"`, none and another value;
  - `"path": null` on `/v1/save-new` and `"replaces": null` on `/v1/file`;
  - `readOnly` after a refused save and after newer content was stored, with `title` unchanged;
  - `/v1/closed` for a default-app copy (deleted, or kept with unconfirmed changes) and for an unknown path (logged).

In `Healdan.Helper.Tests` (Avalonia headless): Settings' PDFs row and note in each state, and the Open window forcing read-only by the chooser's answer.

### 11.2 Manual acceptance (owner)

On the owner's Mac, against the owner's server upgraded with §5.1's web page, on Healdan Helper 0.5.0-rc with CoffeePDF built from its `main`:

- **CoffeePDF §2.5, steps 1 to 7.** Step 3 adds a markup before Save as New Version (CoffeePDF §16, Phase 2's note).
- **H1, a crash:** with a PDF checked out in CoffeePDF, Force Quit CoffeePDF. The crash window comes up; Reopen opens the PDF in CoffeePDF; closing it checks it in, and its copy and lock file are gone.
- **H2, a restart:** with one PDF opened in CoffeePDF and another filed from CoffeePDF with Save to Healdan, quit and start the Helper. Both stay checked out, a save of each goes up, and closing each checks it in.
- **H3, CoffeePDF unavailable:** with CoffeePDF's support off, a PDF's Open in App opens it read-only in the default app (CoffeePDF itself, when it's the default PDF app, with its bar offering to turn Healdan support on, CoffeePDF §6.1); Settings show "Default app, read-only" with the note; the Open window forces read-only, with its note.
- **H4, a refused save:** with a PDF checked out in CoffeePDF, release its check-out in the web page, then mark it up and press ⌘S. CoffeePDF turns read-only and offers Save as New Document.

## 12. Order and release (owner, call)

1. The Helper's change and its docs, in one pull request or a few (§9), then **Healdan Helper 0.5.0-rc1**.
2. The web page's `pdf`, in its own pull request, merged once rc1 exists, as CoffeePDF §2.5 step 1 needs it. Until a Helper is updated, it refuses a PDF link with its usual message; the owner is the only Helper user today.
3. The owner's server upgraded, and the walkthrough (§11.2) on the rc. Fixes go into further rcs, as in M3.5.
4. **Healdan Helper 0.5.0,** then CoffeePDF v1.0 (CoffeePDF §13).

**No trial** (call). M3's and M3.5's trials ran before approval because they rested on things nobody had seen. Here CoffeePDF's Phase 2 already ran Open from Healdan, Save to Healdan with its adoption, and closing against Helper 0.3.0-rc1 (CoffeePDF §16), and the two open questions, a save by rename and a lock that comes late, are tested automatically (§11.1).

## 13. Left to the builder

- The notes' exact words (§4.4, §5.2), within the docs site's writing rules.
- How the Open window reaches the chooser.
- Whether `SavesStopped` is a member of `CopyState` or worked out where the JSON is written, as long as the Helper's own uses of `ReadOnly` don't change.
- How long `CoffeePdfApp.Open` waits for `open -b`, as `LibreOfficeApp.Open` does.
- Healdan Helper's version, if the build finds a reason for other than 0.5.0.

## 14. What CoffeePDF's spec changes to match

CoffeePDF's own session makes these edits:

- **§4.4:** item 2 says `OfficeApps` and `AppChooser` already exist, and PDFs join them with no chooser in the Helper; item 3 adds that a link opens a PDF read-only in the default app without CoffeePDF; item 4 notes `/v1/file`'s `app` was ignored until M3.6; item 5 reads "at each choice"; item 8's rename needs only a test, and the Helper leaves `.coffeepdf-….tmp` alone, deleting leftovers at its start while CoffeePDF isn't running; item 9 is dropped, as the API hides another user's device; item 10 is M3.6 §6.3, with the title unchanged; #134 is fixed. It names this spec and M3.6.
- **§13 and §15:** Phase 3 is Healdan's M3.6, "PDFs in CoffeePDF", with this spec; the open item about its name and number is settled.
- **§9 and `ChannelRoutes`:** §6.5's two meanings, in the release that ships Healdan Helper 0.5.0.
- **§6.1 and §6.9:** a copy whose saves stopped answers `readOnly: true` with its title unchanged, and `holdBy` null.
- **Its roadmap** points Phase 3 at M3.6.

## 15. Decisions made in this session

| Question | Decision |
|---|---|
| The milestone's name and number | M3.6 "PDFs in CoffeePDF" (owner) |
| Acceptance | CoffeePDF §2.5 plus rows H1 to H4, in one walkthrough on a Helper 0.5.0 rc (owner) |
| A PDF chooser in the Helper's Settings | None: CoffeePDF's preference is the switch, and Settings show a PDFs row as text (owner) |
| A PDF link without CoffeePDF | Opens read-only in the default app (owner) |
| "Available" | Installed and support on, read at each choice; Settings show it, the menu doesn't (owner) |
| The holder's computer | Dropped: the API hides another user's device (owner) |
| A refused save's `readOnly` | True on the channel for any copy whose saves stopped; the Helper's own reads unchanged (owner) |
| Healdan-Project/healdan#134 | Fixed for the default app too, and a dropped `/v1/closed` is logged (owner) |
| The public docs | Name CoffeePDF and link to its repository, which is public (owner) |
| Everything marked (call) | Heard and approved with its section (owner, 2026-11-01) |
