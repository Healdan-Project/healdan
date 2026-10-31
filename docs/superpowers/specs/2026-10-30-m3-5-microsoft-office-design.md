# M3.5 "Microsoft Office on the Mac": design

- **Milestone:** M3.5 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-10-30
- **Status:** design agreed with the owner on 2026-10-30. The trial (§8) ran the same day, on the owner's Mac, and its results are in §8.5. It changed the design in places, each marked "changed by the trial", and the owner decided three questions it raised (§15). Approved by the owner on 2026-10-30, after the trial. Implementation plan: [`docs/superpowers/plans/2026-10-30-m3-5-microsoft-office.md`](../plans/2026-10-30-m3-5-microsoft-office.md), approved the same day for subagent-driven building; its "Spec corrections and calls" section lists where it departs from this spec. **Built on 2026-10-30 and 2026-10-31**, on one branch, following the plan's Tasks 1 to 11 and a Task 9b the build added (§5.8's short requests). The plan's pull requests A to E are cut from the branch's commits and are to follow. Where the build departs from this spec, the section says "changed in the build", and §15 lists the decisions that changed. No Office app has yet been driven by the built code: its tests run against fakes, and the AppleScripts have only been compiled. The by-hand rows (§12.3) and the owner's acceptance walkthrough (§12.2) are next, then Healdan Helper 0.4.0.
- **Inputs:**
  - the roadmap's M3 and M3.5 entries, and its glossary;
  - the [M3 spec](2026-10-24-m3-office-integration-design.md), above all §3.1, §5, §7, §10 and the trial's results in §12.5, and the M3 plan's follow-ups "For phase 2 and M3.5" ([plan](../plans/2026-10-24-m3-office-integration.md));
  - a design conversation with the owner on 2026-10-30;
  - a survey of this repository at `96eb0b05b`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard and approved section by section. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace* and *template* mean Healdan's things here (roadmap glossary). "CE" is LogicalDOC Community Edition. "Office" means Microsoft Word, Excel and PowerPoint for Mac, from Microsoft 365. "The extension" is M3's LibreOffice extension.

## 1. Intent

Word, Excel and PowerPoint on the Mac get M3's flow: you save to and open from Healdan inside the app, the title bar shows the doc # and version, and closing checks the document in. M3's Healdan Helper already does most of it for Word with no add-in (M3 §7, §12.5). M3.5 adds the add-in that M3's trial couldn't load, for the buttons, filing a new document and the doc ID footer, and teaches the helper a second app.

**The add-in is buttons; the helper does the work** (owner, M3). Office without the add-in still gets opening, saving, the title bar and closing from the helper alone, as on a Mac where the add-in isn't deployed or Office is signed in with a personal account. Only the buttons, Save to Healdan for a new document and Insert Doc ID need it (call).

## 2. Goals, non-goals, anti-goals

### 2.1 Goals (M3.5 is done when)

The walkthrough in §12.2 passes (owner, 2026-10-30): in Word, Excel and PowerPoint for Mac, against the owner's server over HTTPS, as an ordinary non-admin user, with the helper from a signed release and the add-in deployed from the owner's Microsoft 365 admin centre. In short:

1. **Installs cleanly.** The add-in is deployed from the admin centre, the helper's Set Up Microsoft Office step is done once (§5.13), the Healdan tab appears in all three apps, and each app pairs with the helper once (§5.9).
2. **Save to Healdan** files a new document from the ribbon, and the title then reads `Lease agreement #1234 v1.0` (§5.5).
3. **Saves while editing** move the title on in Word and Excel, and closing checks the document in (§5.4).
4. **Versions and the footer.** Save as New Version makes `v2.0`, and Insert Doc ID's footer in Word moves on with the title (§5.6, §5.7).
5. **Opening** from the web page's Open in App and from the ribbon's Open from Healdan; someone else's document opens read-only (M3 §5.5).
6. **Nothing is lost** through a Force Quit or an outage (M3 §5.10).
7. **Save as New Document** gives a new doc #, and the window becomes the new document (§5.5).
8. **Excel and PowerPoint** get the same open and save flow; the footer is Word's only (owner, roadmap), and PowerPoint's title shows the version it opened with (§5.4).
9. **Documented.** The docs pages in §11.3 are written.

### 2.2 Non-goals

- **Taking over Office's own Save, Save As and Open.** A web add-in can't (§3.2), and the VBA add-ins that might were dropped (owner, 2026-10-30, §15). Healdan's buttons sit beside Office's own, as the owner ruled for apps that won't allow it (roadmap). Cmd+S on a Healdan copy still works, because the helper watches the folder; only a document never saved needs the ribbon's Save to Healdan instead of Cmd+S.
- **Windows.** It comes later (roadmap), but this design keeps it to one new class and its close detection (§5.2, owner).
- **Word for the web.** Microsoft requires its Cloud Storage Partner Program (roadmap). The same deployment shows the Healdan tab there, and its pane says the buttons work in the desktop apps only (§6.3).
- **The doc ID footer in Excel and PowerPoint** (owner, roadmap).
- **LibreOffice's formats from Office** (owner, after the trial). Save to Healdan in Word, Excel and PowerPoint offers only the app's own format (§5.5), and OpenDocument files open in LibreOffice (§5.10).
- **Sideloading.** It didn't load the add-in in Word 16.114 (M3 §12.5), and the admin centre is the route (owner, 2026-10-30).
- **Changes to the LibreOffice flow.** The extension, its channel and its copies stay as M3 built them.
- **M3's walkthrough rows that don't depend on the app:** a second computer and the Comments search, which the owner accepted in M3 (owner, 2026-10-30).

### 2.3 Anti-goals

M3's (M3 §2.4) all hold. Two more:

- **AppleScript built from text.** No document name, path or value is ever pasted into a script's text (§9).
- **A paired add-in with more power than the extension.** The add-in gets the extension's routes and no more, and never the token (§9).

## 3. Constraints

### 3.1 What M3 gives M3.5

- **The cycle has one office app.** `CopyCycle` takes an `IOfficeApp` (open, is it running, does its lock file exist) and the default app for other files ([`CopyCycle.cs`](../../../clients/helper/Healdan.Helper.Core/Cycle/CopyCycle.cs) ~56). Only `LibreOfficeApp` implements it, and `HelperSettings.AppFor` sends every kind to LibreOffice (~40).
- **Each copy's record names its app** (`CopyRecord.App`), so a restart already knows which app a copy was opened in.
- **Copies keep the name they opened with** while the app has them open (M3's R19): in LibreOffice the extension moves the title on instead. Word's rename after each save was left to M3.5 (M3 plan follow-ups).
- **The channel** is HTTP on `127.0.0.1`, at a port the helper picks at start, with a secret; both are in a file only the user can read (M3 §10). Its routes name a copy by its path, which must be in `Copies` (`ChannelHandler`).
- **The windows** (Save, Open, Properties, the warnings) are the helper's and serve any app.
- **The server stamps** `Healdan Doc ID` into `.docx`, `.docm`, `.dotx`, `.dotm`, `.odt` and `.ott` files it stores through the API (M3 §9.7). Whether Word opens the stamper's rewritten parts without complaint was left to M3.5 (M3 plan follow-ups).
- **The API serves static pages already:** `DocsResource` serves `/api/docs/` and `openapi.yaml` from the plugin's jar, public, and `RouteCoverageTest` leaves `/docs` out.

### 3.2 Facts from M3's trial and the platforms

- **Without an add-in, the helper did in Word** (M3 §12.5, M-W column): links, noticing saves by watching the folder, the title moving on by asking Word through AppleScript to save under the new name (about 0.25 s), and noticing closes and crashes from Word's owner file and process.
- **Two one-time permissions:** Automation ("Healdan Helper wants access to control Microsoft Word"), and Word's Grant File Access for the folder it saves into, once per folder. Word, Excel and PowerPoint are sandboxed separately, so each asks for itself.
- **Office's shared group container is closed to the helper,** even with "access data from other apps", so the helper can't install anything into Office's Startup folders.
- **Office web add-ins** (Office.js) load their pages over HTTPS from a server the Mac trusts. They have no event for a save or a close, can't set the window title, can't take over Office's own commands, and can't save an open document to a path of their choosing. They can read the open document's bytes in its own format (`getFileAsync`). (changed by the trial: `Office.context.document.url`, the saved document's location, isn't kept up to date, §8.5, so the add-in doesn't name documents by it.)
- **Office's web view is WebKit,** which blocks an HTTPS page's requests to `http://127.0.0.1` as mixed content ([WebKit bug 171934](https://bugs.webkit.org/show_bug.cgi?id=171934), still open). (added by the trial, §8.5.)
- **An add-in deployed from the admin centre** appears for the users it's assigned to, in Office signed in with an account in that tenant. Microsoft says it can take hours to appear.
- **The owner is the admin of their Microsoft 365 tenant** (owner, 2026-10-30).

### 3.3 The setup (owner)

| Setup | System | Apps |
|---|---|---|
| **M-W** | macOS 27, Apple silicon | Word, Excel and PowerPoint for Mac, Microsoft 365, signed in to the owner's tenant |

The trial records the apps' version.

## 4. The pieces

| Piece | What it does in M3.5 | Where it lives |
|---|---|---|
| **Healdan Helper** | A second app, Microsoft Office, beside LibreOffice; the rename after each save; filing a new document's bytes; a second loopback port for the add-in, over HTTPS with its own certificate, and pairing; Set Up Microsoft Office; Settings per kind (§5) | `clients/helper/` |
| **Office add-in** | One web add-in for Word, Excel and PowerPoint: a Healdan tab, and a pane for pairing and messages (a window the buttons open, §5.9). It reads the open document's bytes and path, and calls the helper. It holds no token and never calls Healdan (§6) | `clients/office/` (static HTML and JavaScript) |
| **The server's pages** | The add-in's files, and a manifest made with the server's own address, at `/api/addin/` (§7) | `healdan-api` |
| **Docs** | Word, Excel and PowerPoint in the user pages; the admin centre in the Install pages (§11.3) | `site/` |

No upstream file changes (§11.1).

## 5. The helper

### 5.1 Two apps in the cycle (call)

- **The cycle takes a set of apps:** LibreOffice, Microsoft Office and Default. A new copy goes to the app Settings choose for its kind (§5.10); after that, the copy's record decides, so restarts, closes and crashes use the app the copy was opened in.
- **The check-out names the program,** Word, Excel or PowerPoint, so the warning in M3 §5.10 reads "#1234 is checked out to you on MacBook (Word)".
- **Close and crash detection** asks each copy's own app (M3's `CloseWatcher` asks the one app today).
- **LibreOffice's copies are unchanged:** they keep their name for life, and the extension moves the title on.

### 5.2 Microsoft Office, and the scripting seam (owner, 2026-10-30)

Everything that drives an Office app goes behind one interface, **`IOfficeScripting`**. Its macOS implementation, `MacOfficeScripting`, uses AppleScript now; a Windows one over COM comes later. The rest of the helper never sees AppleScript.

| Operation | What it does | macOS |
|---|---|---|
| **Open** | Opens a file in the right app, kept off its Open Recent list where the app allows (§5.12), or brings its window to the front | Word's own `open … add to recent files false`; `open -a` for Excel and PowerPoint |
| **FrontDocument** | The document in the app's front window: its path, or that it was never saved (§5.5, §5.8) | AppleScript |
| **IsOpen** | Whether the app has the document open | AppleScript |
| **HasUnsavedChanges** | Whether the document has changes not yet saved | the document's `saved` |
| **Fingerprint** | A cheap summary of the document's content, to tell whether an edit landed between two moments (§5.4, §5.5) | Word: the document's text; Excel: each sheet's used range; PowerPoint: the text of every shape |
| **Save** | Saves the document where it is | AppleScript |
| **RenameIfUnchanged** | Saves the document under a new name, only if it has no unsaved changes, kept off Open Recent, and says whether it did, and whether its content changed meanwhile (§5.4) | AppleScript `save as`, in one script |
| **SaveAs** | Saves a document, found by its path, the add-in's mark or its window's name, and never the front window, under a new path, kept off Open Recent, and says how it found the document and whether its content changed since a given fingerprint (§5.5, §5.14) | AppleScript `save as` |
| **Reopen** | Closes a saved document and opens it again from its file, so that PowerPoint saves it in place afterwards (§5.5) | AppleScript |
| **SetDocId** | Word: sets `Healdan Doc ID`, updates the fields showing it, and marks the document unchanged if it was (§5.7) | AppleScript |

(changed by the trial: `InsertDocId` left the seam, as Insert Doc ID is the add-in's, §5.7; `FrontDocument` and `Reopen` joined it, §8.5.)

(changed in the build:
- `FrontDocument` answers the document's path (none for a document never saved), its name, and its window's name, which finds a document that has no path yet: Word's `name of active window`, and the document's own name in Excel and PowerPoint. It answers nothing when the app isn't running or has no document open.
- `IsOpen` never takes the front window.
- **`SaveAs` never falls back to the front window,** whoever calls it. A document is found by its path, then the add-in's mark, then its window's name (`DocumentRef`'s order), and one closed meanwhile is an error, not another document saved under the copy's name (the plan's Review Focus 2, the wrong window). The crash save-back (§5.14) has the same guarantee. Only `Fingerprint` may take the front window when it finds nothing else, as a wrong guess only makes a later Save As say the content changed.
- `Reopen` refuses a presentation with unsaved changes, as closing it without saving would lose them.
- **`SetDocId` only updates a `Healdan Doc ID` that is already there.** For a document without one it does nothing and answers false. The trial's script made the property when it was missing, which would make the server stamp every document saved from Word, not only those given Insert Doc ID (plan correction 2).)

- **Everything else stays above the seam,** platform-neutral: the add-in, pairing, the port, the rename-after-save logic and the copy records (owner). `MicrosoftOfficeApp` implements M3's `IOfficeApp` over `IOfficeScripting` and Office's close detection.
- **Close detection** is the one other platform piece: each app's process (`Microsoft Word`, `Microsoft Excel`, `Microsoft PowerPoint` on macOS), and its owner file beside the copy. An owner file seen and then gone is a close; the process gone with the owner file left is a crash, as in M3. (as the trial found: Excel and PowerPoint name the owner file `~$` and the copy's whole name; Word, for the trial's names, replaces their first two characters with `~$`. The builder confirms Word's rule for short names. Changed in the build: the helper treats any of these names beside a copy as its owner file, for any Office program: the forms M3 looked for in Word (the name's first one or two characters replaced by `~$`) joined by every program's own. A superset never misses Word's file, and costs only a false "open" if a stale owner-like file sits beside a copy. Word's rule for short names is still unconfirmed.)
- **Paths are compared resolved.** An app reports a document's path as it was opened, so `/tmp/…` and `/private/tmp/…` are the same file (the trial). The helper compares resolved paths, or file names within `Copies`. (changed in the build: the finder every script shares looks for the exact path first, over every open document, in the spellings the helper passes: the path, and its `/private` twin or the path without it. Only if none matches does it look for the file name, and then only in a document whose folder is `Copies`, which the helper passes in. After those come the mark, then the window's name. A same-named document outside `Copies` is never found by name.)
- **The scripts are written for the apps' quirks** (the trial): they loop over an app's documents, sections and fields by index, since `repeat with … in` fails or hangs in Word, and they avoid names the apps' dictionaries take, such as `flat`, `x`, `out`, `before` and `after`. (as built: every script takes its values from `on run argv`, and returns its "missing" answer before any `tell` if the app isn't running, so a poll never launches an app; only Open does. A script that may meet Grant File Access waits up to 300 seconds, and every other script 20.)
- **Windows** then means `WindowsOfficeScripting` over COM, plus close detection there (the apps' process names, and the owner files' rule on Windows) (owner).
- **The tests** replace `IOfficeScripting` with a fake, so the rename logic and the rest run in CI on both runners. `MacOfficeScripting` has its own tests against the real apps, tagged `MacOffice` and run by hand on a Mac with Office, as `MacKeychain`'s are (§12.1). (changed in the build: they skip unless `HEALDAN_MAC_OFFICE=1`, as they open Word, Excel and PowerPoint on the screen. `EveryScriptCompiles` starts nothing and needs no variable. They were written in the build and haven't yet been run.)

### 5.3 Opening

As in M3 §5.5, with the copy opened by `IOfficeScripting.Open` in the program for its kind. A copy opened in Office is named as in M3 §5.4, `Lease agreement #1234 v2.1.docx`, in `Copies`.

### 5.4 The title: renaming after each save (call)

Office takes the window's title from the file name, so in Word and Excel the title moves on by renaming the copy (M3 §5.7, the trial's T2).

1. **When a save is confirmed,** the helper calls `RenameIfUnchanged` with the new name, `… #1234 v1.1.docx`.
2. **Only while the document has no unsaved changes.** Saving under a new name saves everything in the window, so renaming with unsaved changes would send half-finished edits as a version nobody asked for. With unsaved changes, the rename waits for the next confirmed save, and the title then jumps to it (the trial: `v1.1` to `v1.3`).
3. **The check and the save run in one script.** The script also takes a fingerprint of the content before and after. If the content changed in between, an edit landed in the renamed file, and the helper sends it as a save instead of taking it as the copy. (the trial forced an edit into an 8-second gap, and it went up as the next version.)
4. **The renamed file becomes the copy.** The record follows the rename (its file name, and the renamed file's hash as the confirmed content), the old file and its record go, and the folder watcher ignores the helper's own write. (changed in the build: the copy's session is the same one, re-keyed to the new path under its lock, because the rename runs inside that session's save job: a second session would leave a second queue, and a check-in queued meanwhile would read the old path.)
5. **In Word,** `SetDocId` then moves the footer on (§5.7).
6. **PowerPoint keeps the name it opened with** (owner, after the trial). After PowerPoint saves a presentation under a new name through AppleScript, its Cmd+S opens Save As instead of saving, while a presentation it opened itself saves in place (§8.5). So a PowerPoint copy keeps its file name, and its title shows the version it was opened as, as LibreOffice's copies keep theirs (M3's R19). The helper's menu shows the version Healdan has.
7. **When the rename can't be trusted** (changed in the build):
   - **It doesn't start** while the copy is closing, ended, crashed or read-only, while a newer save or a Save as New Version waits, when the old file no longer holds the confirmed content, or when the new name already exists or has a record (Word would save over it). (changed in the final review: nor when the program's owner file is at the new name without its document, as the program may be saving there. And when the record follows a rename, the old file goes only once the new file is there.) A save that landed just before the script's own check is sent as a save, not folded in (the plan's Review Focus 1, an edit typed during a rename).
   - **The close watcher lets go of the old name before the call,** not after. Word moves its owner file during Save As, so the rename would otherwise read as a close and check the document in under its open window. (changed in the final review: it takes the new name on knowing its owner file is there, if it is, so a window closed before its first look is still a close, as for PowerPoint's reopen; otherwise the close waited until the program quit.)
   - **A script that fails or times out** is logged and taken as "unsaved", so the next confirmed save renames. If the new name's file or its owner file is there all the same, Word did it after all, and the helper follows it as a rename with changed content.
   - **A rename Word finishes late** is followed too. Before it acts on an Office copy's close, and at each start, the helper looks for an unfinished rename (a file, or the program's owner file, at the name the record's version gives; no record for that name; the old name's owner file gone). It re-keys the copy there and sends the file, and checks nothing in.

### 5.5 Saving a new document, and Save as New Document (call)

1. **The add-in asks for the Save window** (`save-new`), as the extension does. From Office, the window offers the app's own format only (owner, after the trial: Office turns the add-in off in a window holding an OpenDocument file, so a Word document saved as `.odt` would lose its Healdan tab, and Excel and PowerPoint for Mac can't save OpenDocument files at all, §8.5). This changes M3 §5.6 for Office.
2. **The helper takes the window** the request came from: the app's front window, which is the one whose button was just pressed (`FrontDocument`), as the request arrives and before any window of the helper's opens. (changed by the trial: the add-in can't name a document by its location, because `Office.context.document.url` is empty for a never-saved document and doesn't change when the helper saves the window under a name, §8.5.) (changed in the build: `save-new` gives the add-in a **ticket**, a one-time id that expires after 10 minutes and names the window it took together with the folder, name, format and properties the Save window chose. `file` carries only the ticket, the mark and the bytes. Plan correction 4. A ticket doesn't outlive a sign-out.)
3. **When the user presses Save,** the add-in reads the document's bytes (`getFileAsync`), then marks the document with a one-time id in a custom document property, and sends the bytes and the mark to the helper. Reading the bytes first keeps the mark out of them. The helper takes a fingerprint as the bytes arrive (§5.2). (changed in the build: **the fingerprint comes first,** before the mark wait and the window check in step 4. The first build took it after, and an edit typed during the wait was folded in as if the filed bytes held it. The fingerprint finds the document by the ticket's window path and name, and by the mark.) (changed in the final review: **the fingerprint is taken when the user clicks Save in the helper's Save window,** before the add-in reads the bytes, and the ticket keeps it. Taken as the bytes arrived, it already held a line typed while the add-in read the bytes, set the mark and sent them, so the copy holding that line was taken as the starting point and the line was lost at close. It finds the window by the path and window name `save-new` took. A window the app gives no fingerprint for, or doesn't answer for, counts as changed in step 8.)
4. **The mark is a check, where the app shows it.** Word and Excel show it to AppleScript within about a third of a second; PowerPoint never did. The helper waits up to a few seconds for the mark in the front window, and refuses, with a notice, a front window that carries another document's mark. Where the app doesn't show marks, the front window taken in step 2 stands. (changed by the trial.) (changed in the build: the helper asks for the mark every 250 ms for up to 3 seconds. If it shows, the mark finds the window. If it doesn't, the front window's path and window name must still be the ticket's; otherwise the helper tells the user and refuses with `409 wrong-window`, and nothing is filed. **PowerPoint isn't waited for:** it never shows a mark, so the ticket's window name decides at once. (changed in the final review: when the mark doesn't show and the app gave no window name at `save-new`, nothing says which window it is, so the helper refuses with `409 wrong-window` before anything is filed; before, an empty name matched an empty name, and `SaveAs` found nothing once the document was filed.) **The add-in sets no mark in PowerPoint,** since PowerPoint never shows one to AppleScript and the mark would only stay in the file.)
5. **The helper files the bytes** (`POST /documents`, `checkOut: true`), and the server gives the document its doc # and stamps it (M3 §9.7).
6. **The window becomes the copy.** `SaveAs` saves it under the copy's name, so the title reads `#1234 v1.0`. That write is the copy's starting point, not a save, as `adopted` is for the extension (M3 §5.6). The add-in then removes its mark through Office.js, as Word's AppleScript can't delete a custom property (the trial); the next save carries the document without it. (changed in the build: `SaveAs` finds the window by its path, mark or window name, never the front window (§5.2). The add-in removes its mark in every outcome, a failed filing included, and tells the user if it can't. The copy's record names Microsoft Office.)
7. **In PowerPoint,** the helper then calls `Reopen`, once, so that Cmd+S saves the presentation in place (§5.4).
8. **An edit made while filing is never folded in.** The app writes its own bytes, which never match the filed ones exactly, so the copy can't be checked against them. If the fingerprint changed since the bytes arrived, someone typed while the filing was in flight, and the helper sends the copy as a save straight away instead of taking it as the starting point (M3 §2.4, lost work). (changed in the build: it is sent even when the cycle's folder watcher has already taken that write as the starting point, unless the file is exactly the filed bytes or a save of it is queued.) (changed in the final review: changed since the user clicked Save in the helper's Save window (step 3). `SaveAs` fingerprints the window just before its save and again just after it, finding it by its new name as the rename does, and the copy goes up if either differs, or if the window can't be found by its new name to tell. A ticket with no fingerprint counts as changed too, so the copy goes up, at worst as a version the same as the filed one.)
9. **If the filing fails,** the window is left as it was, unsaved, the add-in removes its mark, and the helper says why. (changed in the build: if the document is filed but the window can't be saved as its copy, the helper says so, the document stays in Healdan, and the adoption stays open, so a late save, such as Word's after Grant File Access, becomes the starting point.)
10. **Save as New Document** is the same steps from a Healdan document's window, after which the helper releases the original unchanged (M3 §5.5). (changed in the build: the original is the copy in the front window, the one `SaveAs` saves. If the request's `url` names a different copy, the helper refuses with `409 wrong-window` at `save-new`, before its window opens, as releasing a copy whose window wasn't the one saved would delete its file from under its open window.)

The format choice is offered only here, as a document's format can't change later (M3 §3.1). (changed by the trial: the provisional route for LibreOffice's format is gone with that format, and `getFileAsync` worked in all three apps.)

### 5.6 Save as New Version, Properties and Open from Healdan

- **Save as New Version:** the add-in tells the helper (`new-version`), and the helper takes the front window's document (§5.8). If it has unsaved changes, the helper calls `Save`, and sends that save as `kind: version`; otherwise it sends the current file as a version (M3 §5.5). (changed in the build: the add-in asks `state` first, so a read-only copy and a document that isn't in Healdan get a message of their own. The helper saves before it marks the version, so the version holds the save's write. A document that isn't a copy is `409 not-a-copy`, and a read-only copy is left alone.)
- **Properties** and **Open from Healdan:** the helper's windows, as for the extension. Properties on a document that isn't in Healdan opens Save to Healdan. (as built: the helper answers `{"action": "file"}`, as for the extension, and the add-in goes on to Save to Healdan.)

### 5.7 The doc ID footer (Word)

- **Insert Doc ID is the add-in's** (changed by the trial): through Office.js (WordApi 1.5's field insert, which Word 16.114 supports), it sets `Healdan Doc ID` to the title's form and adds a field at the end of the footer that shows it (M3 §5.9). The trial's AppleScript insert replaced the footer's existing field instead, and switched Word to Draft view with a footer pane open. (changed in the build: before it inserts, the add-in asks the footer for its fields, and if one already shows `DOCPROPERTY "Healdan Doc ID"` it only updates that one, so pressing the button twice leaves one field. If Word can't say what the footer holds, it inserts as the trial did. For a document that isn't in Healdan it sets the property empty, adds the field, and says the number will show once the document is saved to Healdan. Excel and PowerPoint refuse it.)
- **On screen,** after each rename the helper calls `SetDocId` with the confirmed number: it sets the property, updates the fields, and marks the document unchanged if it was before (the trial: no change of view, and the document stayed saved). (changed in the build: it only sets a property that is already there, §5.2.) (changed in the final review: Word takes keystrokes between the Apple events that update the fields, one per field in each section's footers, so `SetDocId` fingerprints the text before it sets the property and again once the fields are done, and marks the document unchanged only if it was before and the text is the same. A line typed meanwhile leaves the document with unsaved changes, so Word asks at close; marked saved, it would close without asking and the line would be lost.) The file on disk keeps the old value until the next save, as in LibreOffice, and the server stamps the right number whenever it stores the file (the trial: Word opened a stamped `.docx` cleanly).

### 5.8 The add-in's port (call)

- **A second listener, over HTTPS** (changed by the trial: Office's web view blocks `http://127.0.0.1`, §3.2), on a fixed loopback port, with a few fallback ports the add-in tries in order, in case another user of the Mac has a helper on the first. Its certificate is the helper's own, which the user trusts once (§5.13). The extension's channel is unchanged.
  - (as built: the ports are 47615, then 47616 to 47618, the first free one. The server is an `SslStream` on a `TcpListener`, because `HttpListener` can't be handed a certificate in code (plan correction 3). It speaks HTTP/1.1, one request per connection, with bodies up to 150 MiB for `file` and 1 MiB for every other route.)
  - **Its life is the helper's, not a sign-in's** (changed in the build). It opens when the helper starts, before any sign-in, once the certificate exists, hasn't expired and the Mac trusts it (or as soon as Set Up finishes, with no restart), and it closes when the helper quits. A certificate in its last 30 days still serves; Set Up's renewal (§5.13) is swapped in on the same port, with no restart and no gap another user's helper could take the port in. Signed out, pairing works, `hello` answers, `ping` says `signedIn: false`, and every route that needs documents answers `409 signed-out`, `result` included, which the pane reads as signed out or still starting up (§6.3). A ticket belongs to the session that made it, so one made before a sign-out is `409 no-ticket` after it. A pending outcome (below) belongs to the key that started its route, lives in the port and not in a session, and waits through a sign-out; `result` can't collect it until the helper is signed in again.
  - **Two people signed in to one Mac at once** (changed in the build; plan correction 6, corrected in the build): each helper takes the first free port, so the second person's helper opens the next one, which is why there are fallback ports. The add-in tries the ports in order and skips a helper whose `hello` names another server than its own. So pairing fails only when both helpers are signed in to the same server: the second person's add-in then reaches the first person's helper, whose window shows the code on the first person's screen. Known Limitations says so.
- **It answers only:**
  - requests whose `Host` is the loopback address and port, which stops DNS rebinding;
  - pages from its own server's address (CORS), and it answers Chromium's private-network preflight too, so Windows' WebView2 can reach it later;
  - requests carrying a paired key (§5.9);
  - a protocol version it speaks. Otherwise it answers "Update Healdan Helper". (changed in the build: a request with no `X-Healdan-Protocol` is `400 bad-request`; only a number the helper doesn't speak is `426 update-healdan-helper`.)
- **Its routes are the extension's** (M3 plan, "The channel's routes"): the windows, `saved`, `new-version`, `state` and so on. `file` takes the document's bytes and mark instead of a temporary file's path (§5.5), and the pairing routes are new (§5.9).
  - (as built: the routes are the plan's table, under Global Constraints: `hello`, `pair/start`, `pair/finish`, `ping`, `open-dialog`, `save-new`, `file`, `new-version`, `properties`, `state` and, from the build, `result`. The helper's refusals are `409` with a code: `signed-out`, `not-a-copy`, `wrong-window` (§5.5) and `no-ticket`, for a ticket used or expired. `hello` takes the Origin check like the rest, so with no server in the helper's settings every origin is refused `bad-origin` and `hello`'s `"server": null` can't be reached; the pane reads that as the helper not running or not set up.)
- **Every request is short** (changed in the build; the plan's Task 9b). Office's web view is WebKit, which is likely to give up on a request that has had no answer for about a minute (not tried). Meanwhile `open-dialog`, `save-new` and `properties` wait for the user, and `file` can wait 300 seconds behind Grant File Access. So any keyed route except `ping`, `state` and `result` itself that is still running after 20 seconds answers `202 Accepted` with `{"pending": "<id>"}` and goes on, and `POST /v1/result {"id"}` waits up to 20 seconds for its outcome and answers it exactly as the route would have, or `202` again. A dropped answer would have lost a ticket and said "unreachable" while the helper carried on.
  - An outcome, an error too, belongs to the key that started the route, is answered once, and is kept for 10 minutes after the route finishes. At most 64 wait per key: more are `429 too-many-pending`, and the route doesn't start. A pending route keeps the session it started in, across a sign-out. No id is ever logged.
  - The add-in's `call` follows a `202` through `result`, and sends a `result` request that failed on the way once more, as an outcome is answered once and a repeat can't do anything twice. Once it has followed a `202`, any failure to get the outcome (`result` refused, `no-result`, unreachable twice) is reported as **`lost-result`**, "check the document in Healdan before trying again", and never as the route's own "try again": a pending `file` may have finished, and trying again could file twice. The route's own errors stay its own. (changed in the final review: a `file` request that fails on the way, before any answer, is `lost-result` too and isn't sent again, as the helper may have filed the document; it was "Healdan Helper isn't running".)
  - The add-in follows a `202` with no upper bound while a helper window stays open, which is why its window shows a waiting state (§6.3).
- **The helper decides which document a request is about** (changed by the trial). The add-in sends its `Office.context.document.url` when it has one, and the helper uses it only when it names one of its copies; otherwise it takes the app's front window (`FrontDocument`). (as built: the helper turns a `file://` address into a path first, and a path names a copy only when its folder is `Copies` and the cycle knows a copy of that name, compared resolved.) A ribbon button's code loads afresh on each press and had the current path in the trial; the pane's goes stale.
- **The helper notices closes itself** from the owner file and the process, so the add-in has no `closed` to send.

### 5.9 Pairing (owner, 2026-10-30: type a code)

- **The first time a Healdan button is pressed in an app,** the add-in asks to pair, and the helper's window shows a six-digit code. The user types it into the add-in's pane, and the add-in gets a random key, which it keeps in its own storage and sends with every request.
  - (changed in the build: **the pane is a window** the button opens with `displayDialogAsync`. A function command can't show the task pane, and the manifest declares no shared runtime, so the page that holds the pairing field is a dialog, with a Pair button; Insert › Add-ins may open the same page as a pane, but nothing depends on it. The dialog hands the new key back to the button with `Office.context.ui.messageParent`, and the button stores it too, so pairing doesn't rest on the dialog sharing `localStorage` with the ribbon's runtime. Office isn't told the button's press is finished until the dialog has closed.)
  - (as built: the key is in `localStorage` in the app's web view, as `healdan.key.<app>`. That store is per app, inside Word's, Excel's or PowerPoint's own container, and only the pages Office loads for the add-in run there. Healdan's web page in Safari has storage of its own and can't read it. The key is the only defence against another page on the server's own origin, which passes the origin check, so it must not live anywhere such a page can read.)
- **Once per app.** Word, Excel and PowerPoint each have their own storage, so each pairs once. (as the trial found: one key serves both the app's pane and its ribbon buttons, and survives the app restarting.)
- **The code expires** after two minutes, and three wrong tries cancel it. One pairing window at a time. (changed in the build: **nine wrong codes in a row,** across pairings, pause pairing for 10 minutes: `pair/start` answers `403 too-many-tries` and no window opens. Without it, another user of the Mac, who can forge `Host` and `Origin`, could start a pairing and make three guesses again and again, and a six-digit code would fall in under an hour. A success resets the count. The add-in refuses a code that isn't six digits itself, so a typo costs no try.)
- **The helper keeps only each key's hash,** with the app's name. Settings lists the paired apps, with Forget. (as built: in `addin-keys.json`, mode 600, which the port reads at each request, so Forget takes effect at once. A damaged file counts as no keys. A key the helper doesn't know drops the add-in's stored key, so its next press pairs again.)
- **If the app loses its key** (Office's cache cleared, say), the next button press pairs again.

### 5.10 Settings (call)

- **For each kind of file** (M3 §5.11): LibreOffice or Microsoft Office, offering only what's installed. The default is the system's default app for the kind's main format (`.docx`, `.xlsx`, `.pptx`) when it's one of the two, and LibreOffice otherwise.
  - (changed in the build: **what's installed is found through LaunchServices as well as the usual folders.** An app counts when it is in `/Applications` or `~/Applications`, or when LaunchServices finds it by bundle identifier (`org.libreoffice.script`, `com.microsoft.Word`, `Excel`, `Powerpoint`), so one kept anywhere counts, and Set Up is offered for an Office installed anywhere. `DefaultApps` asks LaunchServices which app opens the main format, and maps its bundle identifier to one of the two. A lookup that finds nothing ends there: an unknown extension has no default.)
  - (as built: Settings shows a pop-up for a kind with two installed apps and plain text for a kind with one, as Drawings always have. The Microsoft Office group, with the Set Up row and the paired apps with Forget (§5.9), shows while Office is installed or an app is paired.)
- **Drawings** (`.odg`) stay LibreOffice's: Office doesn't open them.
- **OpenDocument files** (`.odt`, `.ott`, `.ods`, `.odp`) open in LibreOffice whatever the kind's choice, while it's installed; the choice applies to Microsoft's formats and the older ones (`.doc`, `.rtf`, `.xls`, `.ppt`) (call; the trial found Office turns the add-in off for an `.odt` window). Whether Office runs the add-in for the older formats wasn't tried; the builder checks, and Known Limitations says what it finds.
- **Paired Apps,** with Forget (§5.9), and **Set Up Microsoft Office…** (§5.13).

### 5.11 Permissions

- **The package** adds the Automation entitlement (`com.apple.security.automation.apple-events`) beside `allow-jit`, and its explanation (`NSAppleEventsUsageDescription`) to `Info.plist`.
- **Each app asks once** for Automation, the first time the helper drives it, and once for Grant File Access on `Copies`, the first time it saves there (the trial: Word, Excel and PowerPoint each asked for themselves). (as built: the helper's notice comes before each program's first save into `Copies`, whether a rename, a filing or a save-back, once per program ever, and is remembered in `settings.json`.)
- **Word's folder chooser opens inside `Copies`, with Grant Access greyed out** (the trial). The user has to go up one level, select `Copies` and click Grant Access. Set Up Microsoft Office (§5.13), the notice before each app's first save into `Copies`, and the Install page all say so.

### 5.12 Open Recent

(settled by the trial)

- **The helper keeps copies off the list where the app allows:** Word's `save as` and `open` take `add to recent files false`, and Excel's `save workbook as` takes `add to most recently used list false`. In the trial, no renamed Word copy was added.
- **Entries already on it can't be removed:** none of the three apps' dictionaries offers it. Choosing a deleted copy's entry gives Word's "Word cannot open the document: user does not have access privileges".
- **PowerPoint offers no control.** As its copies keep their names (§5.4), it adds one entry per opened copy.
- (changed in the build: **Excel and PowerPoint open a copy with `open -a`,** as their dictionaries have no `open` that keeps a file off the list (plan correction 5), so an Excel copy opened by the helper may go on the list. Excel's renames still use `add to most recently used list false`; Word's `open` and `save as` both take `add to recent files false`.)
- Known Limitations says so.

### 5.13 Set Up Microsoft Office (owner, after the trial)

The add-in's port needs a certificate the Mac trusts (§5.8), so the helper makes one and asks the user to trust it, once:

- **When:** the first time the helper finds Word, Excel or PowerPoint installed, and from Settings and the helper's menu at any time. (changed in the build: the offer on the first start is made once ever, and the menu's "Set Up Microsoft Office…" shows only on a Mac with Office installed, while Set Up is needed. A Mac without Office, and Linux, never see it.)
- **Its window** says what the helper is about to ask for and why: the password prompt for trusting the helper's certificate; Automation, once per app; and Grant File Access on `Copies`, once per app, going up one level in the chooser (§5.11).
- **The certificate** is the helper's own: self-signed, for `127.0.0.1` and `localhost` only, not a certificate authority, trusted for SSL in the user's login keychain, with its private key kept where only the user can read it. macOS asks for the user's password to trust it. Without it, the add-in's pane says to run Set Up Microsoft Office.
- **As built** (changed in the build; spec §13 left these to the builder):
  - The certificate is RSA 2048 with SHA-256, valid for 800 days, and lives with its key in the helper's support folder as `addin-cert.pem` and `addin-key.pem`, the key at mode 600 from the moment it's written. A pair that can't be read, or whose key isn't the certificate's, is made again.
  - **Whether it's trusted is asked of Security.framework's TLS server evaluation for `127.0.0.1`,** which is what Safari and Office's web view ask, not of .NET's `X509Chain`. A trust setting limited to SSL, as `add-trusted-cert -p ssl` makes, isn't visible to `X509Chain`: it reported the trial's trusted certificate as untrusted, so Set Up would have been needed for ever. The trust is asked for with `/usr/bin/security add-trusted-cert -r trustRoot -p ssl -k <login keychain> <cert>`; a cancelled password prompt is reported as a cancel, and any other failure in `security`'s own words.
  - **Renewal belongs to Set Up alone.** The port serves an existing, unexpired certificate and never makes or renews one, or it could mint an untrusted certificate under a working one. Within the last 30 days Set Up is offered again, and the old certificate keeps serving until it runs out (§5.8).
- **Nothing else is asked up front** (M3 §5.4 left this to M3.5): Automation and Grant File Access come when each app first needs them, after a notice.

### 5.14 After a crash (added by the trial)

- **Word reopens the copy by itself,** at its own path, unprompted, as `… - Recovered`, when it next starts after a Force Quit. Its Cmd+S then opens Save As, as Word treats a recovered document as never saved.
- **So the helper saves it back:** when a crashed copy's owner file comes back, the helper doesn't offer Reopen or download over the copy. It calls `SaveAs` to the copy's own path, and the folder watcher sends the recovered work as a save (the trial: it went up as the next version, and the title moved on). M3's crash window (M3 §5.10) still offers Reopen, which opens the app, and Send and Check In.
- (changed in the build:
  - **The save-back waits until the copy is found by its path.** It asks `IsOpen`, which never takes the front window, every few seconds for up to 30, and calls `SaveAs` only once Word lists the copy, so it can never save another document over it. Until it succeeds the copy stays crashed, and a miss or failure is tried again. The close watcher lets go of the copy while it runs, as for the rename.
  - **Once the save-back works,** the crash flag clears, the helper raises `Reopened`, the menu drops "(closed unexpectedly)", and the crash window is dismissed by the helper. From then on Send and Check In is refused, with a notice that the copy is open again, and Reopen brings up the window at its current path, in the copy's own app. Until then the crash window offers both, and Send and Check In can still check in under Word's restored window. (changed in the final review: Send and Check In is refused, with the same notice, from the moment the copy's owner file comes back, while the save-back is still looking for the window, unless the owner file has gone again since.)
  - **Reopen leaves a crashed Office copy in crashed mode,** so the recovered window it brings back is still saved back.
  - **The same logic serves Excel and PowerPoint,** untried, and for PowerPoint a close and reopen follows the save-back, as its Cmd+S opens Save As after any save under a name (§5.4).
  - Whether Word writes its owner file again when it reopens a crashed copy, and whether its "Recovered" window is listed by the copy's path, are for the owner's session (§12.3): if not, the copy stays crashed and the helper keeps looking.)
- **The stale owner file** that the crash left beside the copy is deleted by the helper once the copy is open again or released.
- Excel's and PowerPoint's recovery wasn't tried; the builder checks them the same way.

## 6. The add-in

### 6.1 What it is (call)

- **A web add-in** (Office.js), not VBA (owner, 2026-10-30).
- **An XML manifest** for Word, Excel and PowerPoint, the kind the admin centre and Office for Mac fully support. Microsoft's newer JSON manifest can wait.
- **A Healdan tab of its own** (owner, 2026-10-30), with the extension's six buttons: Save to Healdan, Open from Healdan, Save as New Version, Save as New Document, Properties, and Insert Doc ID (Word only). Its icons are rendered from the helper's Tabler icons by `IconRender`, as the extension's are.
- **A pane** for pairing and messages, shown only when it's needed. (changed in the build: a window the button opens, §5.9. `taskpane.html` is that window's page and the manifest's pane page. It shows the last message with its time; opened by a button, it shows that button's message.)
- **Plain JavaScript** with no npm dependencies. Office.js loads from Microsoft's CDN, which Office requires. (changed in the build: **classic scripts,** as in the trial, not ES modules; plan correction 7.)
- **Its logic** is in one module with no Office in it, tested with Node's built-in runner (`node --test`), as `healdan_logic.py` is for the extension. (changed in the build: that module is `clients/office/web/healdan.js`, which also loads under Node. The Office.js calls are in `office-glue.js`, with the page's globals passed in so a fake Office can drive it under Node; it isn't called `office.js`, Microsoft's own file name, which Office.js might look for by name. `commands.js` and `taskpane.js` are the two pages' scripts, and `healdan.css` is the look. The command is `node --test 'clients/office/test/*.test.js'`, with the pattern quoted: Node 21 and later read `--test`'s arguments as file patterns, so a folder fails. CI runs it on Node 22.)

### 6.2 What it holds

No token, no server calls: only the helper's port. It sends `Office.context.document.url` when it has one, and the helper decides which document a request is about (§5.8). For Save to Healdan it sends the document's bytes and mark, and removes the mark afterwards (§5.5). (changed by the trial.) (changed in the build: Office's error text, which can hold file names, goes from the button to the window with `messageChild` (DialogApi 1.2), and **never in the window's address,** where the server's access log would keep it. The address holds only a message's code and the app. The window tells the button it's ready first, and the button then sends the text, which the window shows as text, never as markup. The last message stays in `localStorage` on this Mac only.)

### 6.3 When the helper can't be reached

- **The pane says why:** Healdan Helper isn't running, is signed out, or needs updating. Office's own commands are unaffected.
- **In Word for the web,** where no helper can be reached, the pane says the buttons work in the desktop apps only.
- (changed in the build:
  - **Signed out and still starting up read alike** ("signed out, or still starting up"): a signed-in helper answers `409 signed-out` until its documents are published. With no server in the helper's settings, or another server, the window reads the helper as not running or not set up.
  - **The window polls `ping`, never `state`,** which makes the helper drive the app by AppleScript and could raise Automation prompts. It checks `hello`, then `ping` once paired, one check at a time and the next 5 seconds after the last answer, and says the helper is taking a while after 8.
  - **It shows a waiting state** ("Healdan is working on Save to Healdan in Word. If Healdan Helper has a window open, finish there…") whenever a button's flow is running, since following a `202` has no end while a helper window stays open (§5.8). A live region is rewritten only when its words change, so a screen reader isn't told the same status every few seconds.
  - **On Word for the web the window is decided from its address** (`message=no-office-on-the-web`), whatever Office says, and asks the helper nothing, not even its status: a web page makes no request to `127.0.0.1`.)

### 6.4 Versions

The server serves the add-in and the user installs the helper, so the two can drift apart. The add-in sends its protocol version with each request, and the helper refuses one it doesn't speak (§5.8).

## 7. The server's pages (call)

- **`AddinResource`** in `healdan-api`, beside `DocsResource`, serves the add-in's files at `/api/addin/` from the plugin's jar. They're public, like `/api/docs/`.
- **A Content Security Policy** on them allows scripts only from the server and Office.js's CDN, and connections only to the helper's loopback ports. (changed in the build: connections also go to `https://*.microsoft.com`, `https://*.office.com` and `https://*.office.net`, since Office.js is Microsoft's script and reaches Microsoft's services from our page. The owner's session checks the console for violations (§12.3), and the list is cut or grown to what Office.js needs, with a test.)
- **`/api/addin/manifest.xml`** is made from a template with the server's public address, so each firm uploads one that points at itself. The add-in's id is fixed, and the manifest's version changes whenever its template does, so the admin centre picks up a change. Pages change with the server; only a manifest change needs a new upload. (as built: the address is the server's public address, `LOGICALDOC_PUBLIC_URL`, when set, else the one the request came to. It has to be `https`, or `http` on the machine's own address, or the manifest is `409 no-public-address`, naming the setting. The manifest's app domain is the address's origin.)
- **The source** stays in `clients/office/`, and `healdan-api`'s POM packages it into its jar, as it unpacks Swagger UI's files today. `code-changed.sh` counts `clients/office/` as server code as well as client code, so a change there builds both.
- **The contract:** `/api/addin/*` isn't JSON API, so `openapi.yaml` doesn't describe it and `RouteCoverageTest` leaves it out, as it does `/docs`.

## 8. The trial

### 8.1 Its question

Can a web add-in from the admin centre reach the helper, and can the two of them do in Word, Excel and PowerPoint what M3's trial couldn't? Its rows are what the platforms' documentation can't promise.

### 8.2 What it has to show

| Row | What has to happen to pass |
|---|---|
| **T12 Installing** | A manifest uploaded in Integrated apps shows the Healdan tab in Word, Excel and PowerPoint, and survives restarts. The trial notes how long it took to appear |
| **T8 Channel** | The add-in's HTTPS page reaches a fixed `127.0.0.1` port from Office's web view; pairing by typed code works; a request without the key gets `403`; the key survives the app restarting |
| **T6 New documents** | `getFileAsync` gives a never-saved document's bytes; the mark is set and found; the helper files the bytes and has the app save *that* window, never another, as the copy; an edit typed while filing is sent, not folded in; the next save is noticed, with no second Grant File Access prompt. The provisional route (§5.5) in LibreOffice's format |
| **T7 Footer** | Insert Doc ID adds the field in Word, through Office.js or AppleScript; after a save, the footer and the title move on together, and the renamed file isn't sent as a save |
| **T2b Unsaved changes** | The helper sees unsaved changes and holds the rename; an edit forced in between the check and the save is caught by the fingerprint and sent (§5.4) |
| **T11 Excel and PowerPoint** | T2 (the rename), T4 (their owner files and process, closing and Force Quit) and T6 |
| **T13 Stamping** | Word opens a `.docx` the server has stamped without a repair prompt |
| **T5b Open Recent** | What Office's Open Recent does with deleted and kept copies, and whether the helper can take them off the list |

### 8.3 How it runs

- **Throwaway code** in a scratch folder outside the repository, never committed:
  - a manifest for the three apps, with all six buttons from the start, as only a manifest change needs a new upload;
  - the add-in's pages;
  - a stand-in for the helper's add-in port, which pairs, files through the API and drives the apps with AppleScript.
- **The server** is a throwaway local Docker stack with the smoke fixture, so nothing lands on the owner's semi-production server.
- **The add-in's pages** are served from `https://localhost` with Microsoft's development certificate, which the owner installed for M3's trial. If the admin centre refuses a `localhost` manifest, or for a last check of T8 from a real origin, the pages go on the owner's server's address for the trial's last round.
  - (as run: Microsoft's certificate had been removed since M3's trial, so the trial made its own, self-signed for `localhost` and `127.0.0.1`, which the owner trusted for SSL. The admin centre accepted the `localhost` manifest, so no round from a real origin was needed.)
- **Who does what:** the owner uploads the manifest, presses the buttons and ticks rows. Claude reads the logs, runs AppleScript checks from the session, and fixes the trial's own bugs between rounds.
  - (as run: the stand-in ran from Claude's session, so macOS's Automation prompts named that app rather than Healdan Helper. The add-in's pages posted their log to the page server, so Claude saw what the add-in saw. A trial pane beside the six buttons ran the checks.)

### 8.4 If a row fails

- **T12:** Healdan's commands move into the helper's menu-bar menu, through AppleScript, with no ribbon. The owner decides.
- **T8:** the helper serves HTTPS on loopback, with a certificate it makes and asks the user to trust, or the add-in and the helper meet through the server. Both are heavier; the owner decides.
- **T6 in one app:** the provisional route for that app's own format too (§5.5).
- (as run: T8 needed its fallback, HTTPS on loopback with a certificate the user trusts.)
- **T7:** whichever of Office.js and AppleScript works.

### 8.5 Results (2026-10-30)

On the owner's Mac (macOS 27.0.1), with Word, Excel and PowerPoint 16.114.3 (16.114.1027), against a throwaway local stack. "Pass with" names the mechanism that made it pass.

| Row | Word | Excel | PowerPoint |
|---|---|---|---|
| **T12 Installing** | **Pass:** the admin centre accepted a manifest whose addresses were all `localhost`, and showed a stand-in icon, as Microsoft's servers can't fetch them. The tab appeared about 35 minutes after the upload, and stayed through restarts | **Pass:** the same deployment | **Pass:** the same deployment |
| **T8 Channel** | **Pass with HTTPS:** `http://127.0.0.1` failed in the web view ("Load failed": WebKit's mixed-content block, before any request left Word). `https://127.0.0.1` with a certificate the owner trusted answered in 47 ms. Pairing by typed code worked, a request without the key got `403`, and the key survived quitting Word | **Pass:** the same | **Pass:** the same, in 14 ms |
| **T6 New documents** | **Pass:** `getFileAsync` gave a never-saved document's 15 KB in 0.2 s. The mark reached AppleScript within about 0.3 s, though once not within 3 s, when the front window was used. The window was saved as the copy, `#1 v1.0`; `.odt` by the provisional route also passed (doc #2) | **Pass:** 10 KB in 0.06 s; found by the mark | **Pass with the front window:** 35 KB in 0.1 s, but the mark never reached AppleScript in 10 s |
| **T2 and T2b The rename** | **Pass:** saves reached Healdan in 1.3–1.6 s and the rename took 0.4–0.5 s, with no prompt. With unsaved changes the rename waited, and the title jumped from `v1.1` to `v1.3`. An edit typed into an 8-second gap between the check and the save was caught by the fingerprint and sent: `v1.5` holds it and `v1.4` doesn't | **Pass:** the rename took 0.5 s | **Fail:** after a save under a new name through AppleScript, Cmd+S opens Save As, even after an AppleScript save in place. A presentation PowerPoint opened itself saves in place |
| **T7 Footer** | **Pass with Office.js** for the insert (WordApi 1.3–1.8 supported) and **AppleScript** for moving the field on after each save, with no change of view and the document left saved. AppleScript's insert replaced the footer's existing field and switched Word to Draft view with a footer pane | – | – |
| **T4 Closing and crashes** | **Pass:** a close was noticed and released. After a Force Quit with unsaved work, the owner file stayed and the crash was noticed within 2 s. Word then reopened the copy by itself, unprompted, as `… - Recovered`; its Cmd+S opened Save As, and saving the window over the copy through AppleScript sent the recovered work as `v1.1`. A Force Quit with nothing unsaved looked like a close | **Pass** for a close; Force Quit not run | **Pass** for a close; Force Quit not run |
| **T11 Owner files** | `~$` replacing the name's first two characters | `~$` and the whole name | `~$` and the whole name |
| **T13 Stamping** | **Pass:** Word opened the server-stamped `.docx` with no prompt, its footer reading `#1 v1.8` | – | – |
| **T5b Open Recent** | **Pass with** `add to recent files false` on each rename: no renamed copy was added. Entries can't be removed, and a deleted copy's entry says "user does not have access privileges" | The option exists (`add to most recently used list`); not run | No control |

**Permissions.** Automation was asked once per app, naming the process that drove it. Grant File Access was asked once per app, and its folder chooser opened inside `Copies` with Grant Access greyed out, so the owner had to go up a level and select the folder.

**Other facts the trial found:**

- `Office.context.document.url` is empty for a never-saved document, and doesn't change when the helper saves the window under a name. A ribbon button's code loads afresh on each press, and had the current path.
- Office turns the add-in off for a window holding an `.odt`: Word's whole Healdan tab was greyed out, and enabled again in a new `.docx`.
- Excel and PowerPoint for Mac have no OpenDocument format to save in through AppleScript.
- Word's AppleScript fails or hangs on `repeat with … in` over its documents and sections, can't delete a custom property, and takes names such as `flat`, `x`, `out`, `before` and `after` for its own terms. Word reports a document's path as it was opened (`/tmp/…`, not `/private/tmp/…`).
- Requirement sets: WordApi 1.3–1.8, PowerPointApi 1.4–1.8, SharedRuntime 1.1 and DialogApi 1.2.

**What the trial changed in this spec:**

- the add-in's port over HTTPS, with the helper's own certificate and Set Up Microsoft Office (§5.8, §5.13; owner);
- PowerPoint's copies keeping their names, and one `Reopen` after Save to Healdan (§5.4, §5.5; owner);
- the app's own format only, from Office (§5.5; owner);
- the helper deciding which document a request is about, from the front window, with the mark as a check, and the add-in removing its mark (§5.5, §5.8, §6.2);
- Insert Doc ID through Office.js, and `InsertDocId` out of the seam (§5.2, §5.7);
- Open Recent (§5.12), resolved paths and the scripts' quirks (§5.2), and what happens after a crash (§5.14).

**Not run:** Force Quit in Excel and PowerPoint, an edit typed while filing (§5.5 step 8, which uses T2b's fingerprint), Save as New Document, and the older formats (`.doc`, `.xls`, `.ppt`, `.rtf`).

## 9. Security (calls)

- **The add-in's port** listens on loopback only, over HTTPS, checks `Host`, answers only its own server's origin, and only requests with a paired key and a known protocol version (§5.8). The extension's channel keeps its secret file.
- **The helper's certificate** (§5.13) is self-signed for `127.0.0.1` and `localhost` only, isn't a certificate authority, so it can't vouch for any other site, and is trusted only for SSL, only in the user's own keychain. Its private key is readable only by the user, as the extension's secret is; anyone who can read it could already read that secret.
- **What a paired add-in can ask** is what the extension can: the windows, and the routes for its own document. Paths must be copies in `Copies`, as today, except for Save to Healdan on a document that isn't in Healdan. It never gets the token.
- **Pairing codes** are six digits, shown only in the helper's window, expire after two minutes and allow three tries. The helper stores only the keys' hashes. Another user of the Mac can make the helper show a code, but can't see it. (changed in the build: and can't guess one, as nine wrong codes in a row pause pairing for 10 minutes, §5.9. Neither a key, a code nor a pending result's id is ever written to the helper's log.)
- **Another page on the server's own origin** passes the origin check, so only the key stops it, and the key is kept where only the add-in's web view can read it (§5.9). (added in the build)
- **AppleScript runs only fixed scripts,** with paths, names and values passed as arguments (`on run argv`), never pasted into the script's text, so a document named `"; do shell script …` can't run anything.
- **Entitlements:** `allow-jit` and Automation, nothing else. The "access data from other apps" permission isn't needed (M3 §10).
- **The add-in's pages** carry a Content Security Policy (§7), and the manifest's app domains are the server's alone. (changed in the build: the policy's connections also reach Microsoft's domains, §7.)

## 10. Code organisation (calls)

- **`clients/office/`:** the manifest's template, the add-in's pages and logic, its icons and its tests. (changed in the build: the template lives only here, and `healdan-api` fills it in; plan correction 1.)
- **`Healdan.Helper.Core`:** `IOfficeScripting`, `MacOfficeScripting`, `MicrosoftOfficeApp` and Office's close detection in a new `Office/` folder; the add-in's port and pairing in `Addin/`; the cycle's set of apps in `Cycle/`; Settings per kind in `Settings/`.
- **`Healdan.Helper`:** the pairing window, the Set Up Microsoft Office window, and Settings' new rows.
- **The helper's certificate** and asking macOS to trust it go beside `MacKeychain`, in `Security/`. (changed in the build: `AddinCertificate` and `OfficeSetUp` are in the core's `Addin/`, and the core's `Security/` holds only `ICertificateTrust`, the seam the port and Set Up ask whether the Mac trusts the certificate through. Its macOS implementation, `MacCertificateTrust`, is in the app's `Platform/` with `LaunchServices` and `DefaultApps`, since the core can't reference the app and its platform calls; `HelperSession` takes the trust as a constructor parameter.)
- **`healdan-api`:** `AddinResource`, and the POM's packaging of `clients/office/`, the template included.
- **CI:** `clients.yml` gets an add-in job (`node --test`, on `ubuntu-24.04`) under `Clients`; `code-changed.sh` counts `clients/office/` as both code and clients. (as built: the job is "Office add-in (ubuntu-24.04)", with Node 22 and `node --test 'clients/office/test/*.test.js'`, and `Clients` waits for it with the helper's and the extension's. `code-changed.sh` exempts only `clients/helper/`, `clients/libreoffice/` and `clients/icons/` from `code`, so a Markdown file under `clients/office/` is still docs.)
- **Pull requests (call; the plan settles them):**
  1. the server's pages;
  2. the helper's second app, the seam and the rename;
  3. the add-in's port over HTTPS, its certificate and Set Up Microsoft Office, pairing and Settings;
  4. the add-in itself.

  (changed in the plan: **five,** the plan's A to E: the server's pages; the seam, the second app, the rename, crashes and filing; the certificate, the port, pairing and Settings; the add-in; and a fifth for the records, which are CLAUDE.md, the capability map, this spec and the roadmap. The build is on one branch, and the five are cut from its commits.)

  Each carries its docs (§11.3). Then Healdan Helper 0.4.0, the owner's server upgraded, the manifest uploaded, and the walkthrough (§12.2).

## 11. Upstream files, fork-owned files and docs pages

### 11.1 Upstream files

None. `UPSTREAM_TESTS` is unchanged.

### 11.2 Fork-owned files

- `healdan-api`: `AddinResource`, the POM, `RouteCoverageTest` and `Contract` (both leave `/api/addin/` out);
- `clients/helper/`: the core (§10), the app's windows, `packaging/mac/entitlements.plist` and `Info.plist.in`, `THIRD-PARTY-NOTICES.txt` if a package is added;
- `clients/office/` (new);
- `.github/workflows/clients.yml`, `.github/scripts/code-changed.sh`, `docker/smoke-test.sh` (fetching the manifest from the stack);
- `CLAUDE.md`, `docs/ROADMAP.md`, `docs/reference/logicaldoc-ce-capability-map.md` (§5.1), and a pointer from M3's spec §7 to this spec.

### 11.3 Docs pages (the docs site, `site/content/`)

**Changed:**

- `user/office/`: "Working in LibreOffice" becomes "Working in Office Apps", with Word, Excel and PowerPoint in:
  - `_index.md`: how the helper, the add-in, the extension and the web page fit together;
  - `installing-the-helper/`: Set Up Microsoft Office and its password prompt, the Healdan tab, pairing, the Automation and Grant File Access prompts (going up one level in the chooser), and Settings per kind;
  - `saving-to-healdan/`: the ribbon's Save to Healdan for a new document, and the app's own format only from Office;
  - `opening-documents/`: opening in Word, and the ribbon's Open from Healdan beside Word's own;
  - `editing-and-versions/`: the title moving on after each save, holding while there are unsaved changes, and PowerPoint's title staying at the version it opened as;
  - `doc-id-footer.md`: Insert Doc ID in Word;
  - `when-things-go-wrong/`: a crash in Office and Word's recovered document, the helper not running, and pairing again.
- `install/office-integration.md`: deploying the add-in from the admin centre (the manifest's address, Integrated apps, whom to assign it to, how long it takes), and what the add-in's pages and port need.
- `install/known-limitations.md`: Office's own Save, Save As and Open aren't taken over; the footer is Word's only; Word for the web; the title waits for no unsaved changes, and PowerPoint's doesn't move on; OpenDocument files open in LibreOffice, and Office's Healdan tab is off for them; each app asks for Automation and Grant File Access; Open Recent keeps entries for old copies (§5.12). "LibreOffice on macOS only" changes.
- `developer/desktop-clients.md`: the add-in, its tests and packaging, the add-in's port and its routes, pairing, `IOfficeScripting` and the `MacOffice` tests.
- `developer/continuous-integration.md` and `developer/tests.md`: the add-in job, and `code-changed.sh`'s `clients/office/`.
- `developer/api.md`: `/api/addin/`, which isn't JSON API.

The screenshots are of the demo firm (CLAUDE.md, "The demo overlay"): the Healdan tab, the pairing window and Settings. (changed in the build: **not yet taken.** The pages say in words what is on screen. The pictures need the add-in running in Office and a helper to photograph, so they wait for the owner's session (§12.3). The build also retitled the user section's card in `user/_index.md`, and named Word, Excel and PowerPoint in `admin/system/webdav-cmis-and-web-services.md`.)

## 12. Verification

### 12.1 Automated

- **The helper's core, in CI on Ubuntu and macOS,** with a fake `IOfficeScripting`:
  - both apps in one cycle; a restart using each record's app; the check-out naming the program;
  - the rename after a confirmed save; held with unsaved changes and tried at the next save; the fingerprint catching an edit; the record following; the watcher ignoring the helper's own write;
  - Save to Healdan's bytes and mark, an edit while filing being sent, and a failed filing leaving the window unsaved and unmarked;
  - Office's owner files, closes and crashes;
  - pairing: the code, its expiry, three tries, nine wrong codes pausing it, one window at a time, hashed keys, Forget;
  - the port: HTTPS, `Host`, origin, the private-network preflight, the key, the protocol version, and paths outside `Copies`;
  - which document a request is about: a known copy's path, else the front window; the mark waited for, and another document's mark refused;
  - PowerPoint: no rename, and one `Reopen` after Save to Healdan;
  - a crashed copy's owner file coming back: `SaveAs` to the copy, the recovered work sent, the stale owner file deleted;
  - making the helper's certificate (its names, not a certificate authority, its expiry);
  - Settings per kind, OpenDocument files and drawings.
- **`MacOfficeScripting` against the real apps,** tagged `MacOffice`, by hand on a Mac with Office: each operation in §5.2 in Word, Excel and PowerPoint, with names that would break a script built from text. They also compile every script `MacOfficeScripting` can build, with `osacompile`, as the trial lost several rounds to scripts that didn't compile. CI's runners have no Office, whose dictionaries compiling needs, so this runs by hand too.
- **The add-in's logic** with `node --test`. (as built: `node --test 'clients/office/test/*.test.js'`, four files: `healdan.test.js` for the logic and its flows against a fake `fetch` and storage, `office-glue.test.js` for the Office.js calls against a fake Office, and `commands.test.js` and `taskpane.test.js` for the two pages. One test checks that the logic's ports are `AddinPorts.All` and in the server's Content Security Policy.)
- (added in the build) **Short requests:** a quick route answers at once; a slow one gives `202` and then its outcome through `result`, once, to its own key, for 10 minutes; the cap of 64; `ping` and `state` never pend; and a pending save keeping its session through a sign-out.
- **Server:** `AddinResource` serves the files with the Content Security Policy, and a manifest carrying the server's address; `RouteCoverageTest` leaves `/api/addin/*` out. `docker/smoke-test.sh` fetches the manifest from the stack.

### 12.2 Manual acceptance (owner)

In Word, Excel and PowerPoint for Mac (§3.3), against the owner's server over HTTPS, as a non-admin user, with the helper from the release's package and the add-in deployed from the owner's admin centre:

1. Run Set Up Microsoft Office in the helper, and trust its certificate. Deploy the add-in from Integrated apps: the Healdan tab appears in Word, Excel and PowerPoint. The first press in Word pairs it by the typed code. In Settings, `.docx`, `.xlsx` and `.pptx` open in Microsoft Office.
2. In a new Word document, press Save to Healdan on the ribbon.
   - Find the matter, pick a folder, and fill in the required fields and a Comment. The format is `.docx`.
   - The title reads `… #n v1.0`.
   - Word asks for Automation and for Grant File Access on `Copies` once each, and never again.
3. Edit and press Cmd+S twice: the title reads `v1.1`, then `v1.2`. Close: it's checked in, and its copy is gone.
4. In the web page, Open in App: it opens in Word, checked out.
   - Insert Doc ID: the footer reads `#n v1.2`.
   - Save as New Version: `v2.0`, in the title, in the footer and in the web page's preview.
   - Close.
5. Open from Healdan on the ribbon, by `#n`: it opens. As a second user, open it: a read-only copy that says who has it.
6. Type a line, don't save, and Force Quit Word. Reopen Word: the document comes back with the line, and the helper sends it as the next version. Turn off the network, save, turn it back on: the save arrives.
7. Save as New Document: a new doc #, and the window becomes the new document.
8. In Excel and PowerPoint: save a new file to Healdan, edit, save, close, and reopen it. Excel's title moves on with each save; PowerPoint's stays at the version it opened as, and its saves still reach Healdan.

### 12.3 What the build left for the owner's session (added in the build)

The build's tests use fakes, and the AppleScripts were compiled (`EveryScriptCompiles`) and checked against the apps' dictionaries. Nothing built has driven Word, Excel or PowerPoint, asked macOS to trust the certificate, or loaded the add-in in Office. These are for the owner's session, with §12.2's walkthrough:

- **The scripts against the real apps:** the `MacOffice` tests (`HEALDAN_MAC_OFFICE=1`), above all the shared `locate` handler, which is new since the trial, its path-then-name search, and `ANameThatWouldBreakAScript`.
- **Set Up:** the password prompt, then `security dump-trust-settings` listing the certificate for SSL; a cancelled prompt reading as a cancel (the match on "cancel" in `security`'s stderr is from memory); and whether the trust check needs a second or two to pass (a short retry may be wanted).
- **The port from Office's web view:** WebKit's preflight and `fetch` against the helper's server, its close after each answer, and whether WebKit really gives up on a request after about a minute (§5.8).
- **The add-in's pages in Office,** in all three apps: the console, for Content Security Policy violations (§7); `displayDialogAsync` from a function command (the window opens, its size, one at a time); the key reaching the button through `messageParent`, and the detail reaching the window through `messageChild` (DialogApi 1.2, and what `origin` Office reports on the Mac); `event.completed()` after the window closes, and that the user closing it really raises `DialogEventReceived` (12006) (if it never does, the press never completes, and a timer in `commands.js` is the fix); the six buttons (five in Excel and PowerPoint); and Word for the web showing only its message.
- **Office.js:** `getFileAsync`'s slices, and its time and memory on a document of tens of megabytes; `removeMark` leaving no `HealdanMark`; Insert Doc ID twice leaving one field (it relies on `footer.fields` and each field's code), and `customProperties.add` accepting an empty value.
- **Filing from PowerPoint:** whether PowerPoint's AppleScript save rewrites an unchanged presentation (`Reopen` saves first), which would send a needless version with each filing; and whether `Office.context.document.url` comes as a path or a `file://` address.
- **After a crash:** whether Word writes its owner file again when it reopens a crashed copy, and lists its "Recovered" window by the copy's path; Excel's and PowerPoint's recovery (§5.14); Word's owner-file rule for short names (§5.2).
- **The older formats** (`.doc`, `.xls`, `.ppt`, `.rtf`): whether Office runs the add-in for them (§5.10). Known Limitations says neither way.
- **The signed package:** that Automation's prompt names Healdan Helper, with `package.sh` and `SIGN_IDENTITY`.
- **The pictures** the docs lack (§11.3).

## 13. Left to the builder

- The fixed port, its fallbacks, and the routes' exact shapes beyond §5.8.
- How the add-in keeps its key (its web view's storage, or Office's), within §5.9.
- The mark's property name, and how long the helper waits for it (§5.5).
- Word's owner-file rule for short names (§5.2), and Excel's and PowerPoint's recovery after a crash (§5.14).
- Whether Office runs the add-in for the older formats (§5.10).
- Where the certificate's private key lives, and its lifetime and renewal (§5.13).
- The pane's layout, the icons' sizes, the pairing window's wording and the notices.
- The manifest's version scheme, within §7.

(as built: the ports and routes are §5.8's and the plan's table; the key is in `localStorage` (§5.9); the mark is the custom property `HealdanMark`, asked for every 250 ms for up to 3 seconds (§5.5); the certificate is §5.13's; the manifest's version is four-part, `1.0.0.0`, raised in any change to its template. Not settled in the build: Word's rule for short names, Excel's and PowerPoint's recovery, and whether Office runs the add-in for the older formats (§12.3).)

## 14. Open after this spec

- **The trial (§8),** then this spec's approval, then the plan. (done on 2026-10-30; the build followed, on 2026-10-30 and 2026-10-31.) Open now: the owner's session (§12.3), the pull requests, Healdan Helper 0.4.0, the owner's server upgraded and the manifest uploaded, and the walkthrough (§12.2).
- **M3's phase 2** (LibreOffice on Linux) waits for an Ubuntu 26.04 desktop; M3.5 goes first (owner, 2026-10-30).

## 15. Decisions made in this session

**Owner (2026-10-30):**

- **Order:** M3.5 now, ahead of M3's phase 2.
- **The route into Office:** the admin centre's Integrated apps, from the owner's tenant, of which the owner is the admin.
- **A web add-in, not VBA.** VBA add-ins might have taken over Cmd+S and Open on new documents, but they're binaries that can't be built from source or in CI, the helper can't install them, and they bring macro prompts.
- **A Healdan tab of its own,** with the six buttons.
- **Pairing by typing a code** shown in the helper's window, once per app. (changed in the build: typed in the add-in's window, a dialog the button opens, §5.9.)
- **Done** is the eight-row walkthrough in §12.2. A second computer and the Comments search were accepted in M3.
- **The scripting seam:** `IOfficeScripting`, AppleScript now and COM later, with everything else platform-neutral above it.

**Owner, after the trial (2026-10-30):**

- **Set Up Microsoft Office.** The add-in's port is HTTPS, with a certificate the helper makes and the user trusts once, from a Set Up Microsoft Office step the first time the helper finds Office, which also explains Automation and Grant File Access (§5.13).
- **PowerPoint's copies keep the name they opened with,** so its title shows the version it opened as, with one close and reopen straight after Save to Healdan (§5.4, §5.5).
- **The app's own format only** from Word, Excel and PowerPoint, as Office turns the add-in off for OpenDocument files and Excel and PowerPoint can't save them (§5.5). OpenDocument files open in LibreOffice (§5.10).

**Calls (approved section by section; open to the owner's review of this spec):**

- the add-in is buttons and the helper does the work, and Office without the add-in keeps opening, saving, the title and closing (§1);
- the cycle's set of apps, and the check-out naming the program (§5.1);
- renaming only without unsaved changes, in one script with a fingerprint, the record following (§5.4);
- filing a new document from the add-in's bytes, from the front window with the add-in's mark as a check, with a fingerprint so an edit made while filing is sent (§5.5; changed by the trial);
- a second, fixed loopback port with `Host`, origin, key and version checks (§5.8), over HTTPS (changed by the trial; changed in the build: it lives as long as the helper, signed in or not, and keeps every request short with `202` and `result`);
- the helper deciding which document a request is about (§5.8), Insert Doc ID through Office.js (§5.7), Open Recent (§5.12) and what follows a crash (§5.14) (added by the trial);
- Settings per kind, defaulting to the system's default app, with OpenDocument files and drawings staying in LibreOffice (§5.10; changed in the build: the apps are found through LaunchServices too);
- no permission asked up front beyond Set Up Microsoft Office, with a notice before each app's first save into `Copies` (§5.11, §5.13);
- an XML manifest, plain JavaScript, Office.js from Microsoft's CDN (§6.1);
- the add-in's pages and a per-server manifest at `/api/addin/`, with its source, the manifest's template included, in `clients/office/` (§7, §10);
- the trial against a local stack, with the pages on `localhost` first (§8.3);
- four pull requests, each with its docs (§10; changed in the plan: five, with the records in the fifth).

**Made in the build (2026-10-30 and 2026-10-31):** the plan's seven corrections, and the rulings made while building that change what this spec describes. They are open to the owner's review, like the calls above, and each is marked in its section.

- **The plan's corrections:**
  - the manifest's template lives in `clients/office/` (§10);
  - `SetDocId` only updates a `Healdan Doc ID` that is already there (§5.2);
  - the add-in's port is an `SslStream` on a `TcpListener` (§5.8);
  - `save-new` gives a ticket that `file` names (§5.5);
  - Excel and PowerPoint open copies with `open -a` (§5.12);
  - two people on one Mac, both signed in to the same server, can't both pair, since the second person's add-in reaches the first person's helper (§5.8);
  - the add-in's pages are classic scripts (§6.1).
- **Never the wrong window:**
  - `SaveAs` never falls back to the front window, for any caller (§5.2);
  - filing fingerprints first and defines `wrong-window` (§5.5);
  - from the final review: filing fingerprints the window when the user clicks Save in the helper's Save window, before the add-in reads its bytes, and `SaveAs` checks it before and after its save (§5.5);
  - the crash save-back waits until Word lists the copy by its path (§5.14).
- **Word's footer never hides an edit** (the final review): `SetDocId` leaves the document saved only if its text didn't change while the fields were updated (§5.7).
- **A rename is never taken for a close:** the same session re-keyed, the close watcher released first, an unfinished rename followed instead of checked in (§5.4).
- **The port:**
  - it lives as long as the helper, signed in or not, and serves while the certificate is unexpired and trusted (§5.8);
  - requests are short, with `202`, `result` and `lost-result` (§5.8);
  - a missing protocol header is `400`, and `426` is for a protocol the helper doesn't speak (§5.8).
- **The certificate:** trust is asked of Security.framework's TLS evaluation, and renewal belongs to Set Up alone (§5.13).
- **Pairing:** it happens in a dialog, with the key handed back by `messageParent`, and nine wrong codes in a row pause it for 10 minutes (§5.9).
- **The add-in:** Office's error text never goes in an address (§6.2), the window polls `ping` and shows a waiting state (§6.3), and its Content Security Policy allows Microsoft's domains Office.js reaches (§7).
- **Settings:** what's installed is found through LaunchServices as well as the folders (§5.10).
- **The add-in's test command** is `node --test 'clients/office/test/*.test.js'`, with the pattern quoted (§6.1).
