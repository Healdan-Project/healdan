---
title: Desktop Clients
weight: 9
---

Healdan's desktop clients live under `clients/` in the repository: the **Healdan Helper**, a .NET app on each
person's computer, and **Healdan's LibreOffice extension**. They build apart from Maven. The commands are in
[Building from Source](../building-from-source/#the-desktop-clients), and the workflow that tests and releases them in
[Continuous Integration](../continuous-integration/#the-clients-check). The design is the M3 spec,
`docs/superpowers/specs/2026-10-24-m3-office-integration-design.md`.

## How the Pieces Talk

| From | To | How |
|---|---|---|
| The web page's Open in App | The helper | A `healdan://open/<doc #>?server=<host>` link, which macOS hands the app bundle as an Apple event, started or not |
| The LibreOffice extension | The helper | The channel: HTTP on `127.0.0.1`, with a secret (below) |
| The Office add-in in Word, Excel and PowerPoint | The helper | [The add-in's port](#the-add-ins-port): HTTPS on `127.0.0.1`, from the server's own pages, with a paired key |
| A second helper | The running helper | The channel's `/v1/link`, for a link macOS gave another copy of the app (below) |
| The helper | Healdan | The [API](../api/) under `/api/v1`, with a device token |

The extension and the add-in hold no token and call no server. The helper does the work, and draws every window, once
for every app.

## The Helper

`clients/helper/Healdan.Helper.sln` is .NET 10 (`global.json` pins the SDK), with four projects:

- **`Healdan.Helper.Core`** has no windows and no Avalonia, so the tests drive all of it:
  - `Api/`: the API client, written by hand against `openapi.yaml`;
  - `Copies/`: the local copies and the record beside each;
  - `Cycle/`: the open-save-close cycle, and the apps it opens copies in;
  - `Office/`: Word, Excel and PowerPoint: which program opens a file, `MicrosoftOfficeApp`, `IOfficeScripting`
    with its macOS implementation, and what the cycle asks of the programs after a save or a crash
    (`OfficeAfterSave`, `CopiesNotice`);
  - `Session/` and `Security/`: sign-in, the keychain and the device id;
  - `Channel/` and `Links/`: the channel's server and routes, and the links;
  - `Addin/`: the Office add-in's port and its routes, pairing, and the certificate the port serves;
  - `Browse/`, `Forms/` and `Ui/`: what the windows show and decide, and their words;
  - `Platform/` and `Settings/`: the extension's install check, which app opens each kind of file, and the settings.
- **`Healdan.Helper`** is the Avalonia 12 app: the menu-bar icon (`Tray/`), the windows (`Windows/`), and macOS's
  notifications, login item, extension install and text layout (`Platform/`). It stays out of the Dock
  (`LSUIElement`) and hands the focus back after each window.
  - **The look** (polish spec, `docs/superpowers/specs/2026-10-27-helper-ui-polish-design.md`): the tokens and
    control styles are in `Windows/HelperStyles.axaml`, over Fluent. The icons are Tabler's, in
    `Windows/HelperIcons.axaml`, which `python3 clients/icons/write-helper-icons.py` writes from
    `clients/icons/tabler/` (`IconsTests` fails until it's run, and until a new icon is in its `SpecNames`). The menu
    bar icon is drawn at run time from `HealdanMark`'s shapes (`Tray/TrayIcons.cs`).
- **`Healdan.Helper.Core.Tests`** is xUnit.
- **`Healdan.Helper.Tests`** is xUnit too, for the app's own code that needs Avalonia to test. It runs Avalonia
  headless, with Skia's real drawing and the helper's own styles (`Headless.cs`), so a test can make a window, click
  its buttons and read what it shows, as `SetUpOfficeWindowTests` does.

### San Francisco's Layout

On macOS the windows' text is San Francisco, asked for as `.AppleSystemUIFont`. Avalonia 12 shapes text with
HarfBuzz at the font file's default instance, which for San Francisco is its Display optical size, while Skia draws
the face CoreText matched at its Text optical size. Left alone, the text is measured about 15% narrower than it's drawn,
and SemiBold about 25%.

`Platform/SystemFont.cs` closes the gap. `Program` adds its shaper with `UseSystemFontShaper()`, on macOS only. The
shaper hands each call on to Avalonia's HarfBuzz shaper. For each San Francisco typeface, it first sets HarfBuzz's font
to the instance Skia draws: the named instance with the face's PostScript name, at optical size 17. Before each run, it
sets the point size, from which HarfBuzz applies the font's tracking as CoreText does. Up to 17 pt, which covers every
size the helper uses, the widths then match CoreText's.

- Avalonia marks `ITextShaperImpl` as not for apps to implement, so the shaper is a `DispatchProxy`, made at run time.
  It goes in as an `AppBuilder` option, since the service locator isn't in Avalonia's reference assemblies.
- It reaches two public properties of internal Avalonia classes, `HarfBuzzTypeface.HBFont` and
  `SkiaTypeface.SKTypeface`, and calls HarfBuzz's C API in HarfBuzzSharp's native library. `SystemFontTests` fails
  when an Avalonia upgrade moves either property, since the widths go back to the narrow ones.
- Other fonts, and every font off macOS, go to HarfBuzz unchanged.

### The Cycle

- **Opening:** `GET /documents/{n}`; a check-out with the app's name (`{"app": "LibreOffice"}`, or `Word`, `Excel` or
  `PowerPoint`) when the user can edit it, else a read-only copy; the download into `Copies/`, through a temporary
  file; then the file's app. If the open fails after the check-out, the check-out is released, unless this computer
  already had a copy. One open of a document runs at a time: a second while one is under way, such as a
  double-clicked Open in App, is dropped.
- **The app is chosen for each file** when its copy is made, and kept in the copy's record (`App`: `LibreOffice`,
  `Microsoft Office` or `Default`). `AppChooser` makes the choice: the user's for the file's kind (`kinds` in
  `settings.json`), else the system's default app for the kind's main format when that is Word, Excel or PowerPoint,
  else LibreOffice; an OpenDocument file is LibreOffice's whatever the choice; and only an installed app
  (`InstalledApps`) is chosen. On macOS the system's default is what LaunchServices would open a file of that extension
  with (`DefaultApps.For`: `UTType typeWithFilenameExtension:`, then `NSWorkspace URLForApplicationToOpenContentType:`),
  named by its bundle identifier (`AppBundles`: `com.microsoft.Word`, `Excel` and `Powerpoint`, `org.libreoffice.script`).
  An app is installed when it is in `/Applications` (LibreOffice also in `~/Applications`) or when `NSWorkspace
  URLForApplicationWithBundleIdentifier:` finds it, as `open -a` does. `Platform/LaunchServices.cs` makes both calls,
  and its lookups stop at a nil answer, since Foundation raises an Objective-C exception for a nil argument. Settings
  (`SettingsWindow`) offer `AppChooser.Offered` for each kind and write the user's pick to `kinds`; its Microsoft Office
  group lists `Pairing.Paired()` with Forget and starts Set Up. `OfficeApps` holds the set the cycle works with, and everything after the open (the
  reopen of a kept copy, the lock check, the crash check, the resume, Bring to the Front) goes by the record's app,
  whatever Settings say by then.
  - **Microsoft Office** (`MicrosoftOfficeApp`) is one app for the three programs, on macOS only. Each program opens
    the files it opens (`OfficePrograms`), and it opens them through `IOfficeScripting`, the one seam for everything
    the helper does to a document in Word, Excel and PowerPoint. Nothing above the seam knows how the programs are
    driven: `MacOfficeScripting` runs AppleScript with `osascript`, and a Windows one may follow. Its scripts find a
    document by its exact path, then by its file name only in `Copies`, then by the add-in's mark or its window's
    name. Word's scripting names a document by its name, so of two open documents with one name (a copy in `Copies`
    and one set aside beside it) it reaches only one: the scripts refuse a name two open documents share, and
    `IsOpen` answers yes for it, so neither is checked in from under its window.
  - A file no office app edits is the `Default` app's, read-only, as below.
- **Other kinds of file** (not spec §5.11's, by the file's own extension) open read-only in the system's default app
  (`DefaultApp`), with no check-out, and only from the Open window: a link to one is refused (`OpenLinkAsync`). On
  macOS the copy gets `com.apple.quarantine` (`Platform/Quarantine.cs`), so Gatekeeper checks it, and
  `DefaultApp.RefusedExtensions` (apps, installers, scripts and the like) are never downloaded or handed over.
- **Saving:** the helper watches the folder, so it works whether or not the extension took the save over. A save
  counts once the file's size has stopped changing for 1.2 seconds, checked every 300 ms, and its content differs
  from the last sent. It goes up as a snapshot with `keepCheckedOut: true`, the document's own `name`, `ifVersion`,
  `ifHeld: true` and a fresh `saveId`, which a retry repeats. Saves queue in order, and a newer one replaces a queued
  older one. If that one was a Save as New Version, the newer one goes up as `kind: version` in its place
  (`SaveQueue.Enqueue`), so the version the user asked for is still made, with the newer content.
- **A new document's copy:** the extension's `/v1/adopted`, called right after `storeAsURL` while the window takes no
  input, makes the file as it is then the copy's starting point, so the next write is a save, however soon. Without
  it (an older extension), the first write that settles is. Until then the record's confirmed hash is the filed
  file's. A restart in between sends the copy unless it's those bytes. Nothing tells the adoption from a save made
  while the helper was down, and the adoption differs from the filed file by the doc ID the extension sets first. So
  a restart there sends an identical version, and never loses a save.
- **Closing:** the extension's `/v1/closed`, or the copy's app's lock file gone at two polls a second apart:
  LibreOffice's `.~lock.<copy>#`, or Microsoft Office's owner file, `~$` and the copy's name with its first two
  characters left out for Word and with none left out for Excel and PowerPoint (`OfficePrograms.OwnerNames`, which
  for Word also gives `~$` and the whole name, as a short name may have; copies' names are always long). Tidying up,
  the helper deletes every form any of them may use, including Word's with only one character left out
  (`CopyNames.OfficeOwnerNames`). Unsent changes go up with `keepCheckedOut: false`, otherwise `DELETE …/checkout`;
  then the copy is deleted.
  A check-in or release that's refused or fails keeps the copy, and the next start closes it again.
- **Renaming after a save (Word and Excel):** they take the window's title from the file name, so once a save is
  confirmed, `CopyCycle.RenameAfterSaveAsync` has the program save the copy under the confirmed version's name
  (`IOfficeScripting.RenameIfUnchangedAsync`), and the record (`CopyStore.Moved`), the session (`Rekey`) and both
  watchers follow it. It runs in the confirmed save's job, only when no newer save or Save as New Version waits, and
  not while the copy has changed since its confirmation, nor while the program's owner file is at the new name without
  its file. The watchers let go of the old name first, so the program's writes aren't saves and its owner file moving
  isn't a close; the close watcher takes on the new name knowing its owner file is there, so a close at its first look
  counts. The old file goes only once the new file is there (`CopyStore.Moved`). The renamed file's hash becomes the
  confirmed one, so it isn't sent, unless an edit landed in it: the program saw one between its check and its save
  (`RenamedChanged`), or the old file changed as the rename began, and then it goes up as the next save. With unsaved
  changes in the window, or when the program can't be asked (logged), nothing moves, and the next confirmation tries
  again; but if the new name's file or owner file is there all the same, as when the script gave up while the program
  went on, the copy follows it and the file goes up. Then Word's doc ID footer moves on (`SetDocIdAsync`), where the
  document has one. PowerPoint's copies keep their name. Before a program's first save into `Copies`, `CopiesNotice`
  says how to grant it access, once per program (`copiesAnnounced` in `settings.json`). A start that finds a renamed
  file the record didn't follow takes it as the copy, and sends it, and so does a close: an old name's owner file that
  went because the program saved under the next name, once the user granted access, say, isn't a close
  (`FollowedRename`).
- **A crash:** while a copy is open, the helper checks every 5 seconds that its app's process is running: `soffice`,
  or the program that has the copy (`Microsoft Word`, `Microsoft Excel` or `Microsoft PowerPoint`; one running says
  nothing of a copy another has). A crashed copy of Word's, Excel's or PowerPoint's stays watched
  (`CloseWatcher.WatchCrashed`): once its owner file is written again, as when Word restores its "Recovered" window
  at the copy's path, the close watcher lets go of it, and the helper looks for the copy by its path
  (`IsOpenAsync`, which never takes the front window) for up to 30 seconds. Only once it's found is the window saved
  over the copy (`SaveAsAsync`), and the folder watcher sends it. The copy stays crashed until that works, and is
  looked for again after a miss or a failure. Then the crash window closes itself (`IWindows.DismissCrashed`), and its
  Send and Check In and Reopen, and the menu, go by the copy as it is now (`CopyCycle.Current`), found by its document
  once it's renamed: a copy that's open again is brought up, never checked in. Send and Check In is refused from the
  moment the owner file comes back, and while the save-back looks for the window or saves it, as Word takes its owner
  file away during the save (`CopyCycle.IsOpenAgain`). Once the save-back has given up, it's refused only while the
  owner file is there, so a window closed before it was found doesn't strand the copy. The crash window's Reopen
  leaves the watch in place.
- **PowerPoint's close and reopen** (`CopyCycle.ReopenOfficeAsync`, after a save under a name through the seam): the
  close watcher lets go of the copy until its owner file is back, or for 10 seconds, so it isn't taken for a close,
  and then takes it on again knowing whether the owner file is there.
- **Starting:** `ResumeAsync` reads every record, resends what wasn't confirmed, checks in what closed meanwhile, and
  offers a crashed copy, before the helper takes any link. A save whose answer never came is repeated with its
  `saveId` and the kind it went up as (the record's `sentKind`), and then, when newer content or a version was
  still to go (`pendingKind`), that goes up as a new save; the repeat keeps the check-out for it. A window that
  closed meanwhile is checked in as `pendingKind`, so a Save as New Version that was waiting is still made.
- **Refusals:** `409 held-elsewhere` or `not-held` ends the copy's check-out: it's kept, never deleted, and the user
  is told to use Save as New Document. `409 version-moved` is resent once with the new `ifVersion` and the current
  name, but only while the document is still checked out here and its `fileVersion` hasn't moved. A repeated
  `saveId` gets `version-moved` too once newer content has been stored over the save, so it's never resent.
- **This computer's hold** is one whose `hold.device.id` is this computer's device id. Device names aren't compared,
  as two computers can share one. The server records a hold under the device id of the token that made it, which is
  why the helper never uses a token issued to another device id (below).

### Local Copies

Every copy is directly in one folder, `Copies/`, mode 700.

- **The name** is `<name> #<doc #> v<fileVersion><ext>`, with ` (read-only)` before the extension for a read-only
  copy. `/`, `:`, `\` and control characters in the name become `-`.
- **At most 241 bytes** in UTF-8, so the record beside it, `.<copy's name>.healdan.json`, fits in 255 bytes, and so
  does LibreOffice's lock file.
- **A copy keeps the name it was opened with** while it's open, except in Word and Excel, which save it under each
  confirmed version's name (above). The record holds its file name and the version it holds, and the title comes
  from the record.
- **A copy with unconfirmed changes is never deleted** by the helper, and `CopyStore.Create` never deletes anything.
  Once nothing looks after one (its check-out ended, or Healdan refused it, and its window closed), the menu lists it
  under Not Yet in Healdan (`CopyCycle.KeptCopies`), and choosing it opens it as it is, with no check-out
  (`OpenKept`). The menu asks at each rebuild, so each copy is hashed again only once its size or time changes.
- **The copies belong to one server.** While the helper runs, `Copies/` holds only the signed-in server's copies.
  Each record names its server (`CopyRecord.Server`, as `settings.json` holds it; a record without one belongs to the
  server in `settings.json`). A sign-in to another server, and every start before the resume, set aside each copy
  naming another server into `Copies from <host[-port]>/` beside `Copies/` (`CopyStore.SetAside`), numbered when a
  name is taken there; a start then brings back the signed-in server's (`CopyStore.BringBack`), except one open in an
  app there or whose name is taken in `Copies/`. A copy moves first, then its record, and moves back if the record
  can't follow. Lock and owner files stay, as the app deletes them by the path it opened, and so does Finder's
  `.DS_Store`. A setting aside that fails refuses the sign-in, or the start. The menu has a line per server with
  copies waiting (`CopyStore.Aside`).
- **No open copy is moved from under its window.** A sign-in to another server is refused, before the helper signs
  in and again just before the move, while anything it would set aside has its lock or owner file beside it in
  `Copies/` and an app is running (`CopyStore.OpenToSetAside`, `IOfficeApp.IsRunning`); the window would otherwise
  save a file with no record to the old path. A lock file with the app not running is a crash's, and doesn't count.
  A start leaves such a copy in `Copies/` (`SetAside`'s `leave`) until a later start. A read-only copy has no lock
  file, so it moves.
- **A store made for a server** (`CopyStore.All`, and `Find`, through which the cycle and the channel's path routes
  reach a record) sees only that server's copies and those without one, so the cycle never takes another server's,
  such as one dragged back into `Copies/` or one left open at a start, and `Create` sets one at its target path aside.

### The Helper's Files

| | macOS | Linux |
|---|---|---|
| Support folder | `~/Library/Application Support/Healdan Helper/` | `$XDG_DATA_HOME/healdan-helper/`, or `~/.local/share/healdan-helper/` |
| Local copies | `<support>/Copies/` | the same |
| Copies from another server | `<support>/Copies from <host[-port]>/` | the same |
| Files for filing | `<support>/Temp/`: the helper deletes each file once it's filed | the same |
| Settings | `<support>/settings.json` | the same |
| Device id | `<support>/device.json`: a random UUID made once, not a secret | the same |
| The channel | `<support>/channel.json`, mode 600: `{"port", "secret", "pid"}` | the same |
| The add-in's certificate | `<support>/addin-cert.pem` and `addin-key.pem` (mode 600): the helper's own self-signed certificate for `localhost` and `127.0.0.1`, RSA 2048, valid 800 days (`AddinCertificate`). Set Up Microsoft Office makes it, makes it again once fewer than 30 days are left, and has the user trust it, in their login keychain (`OfficeSetUp`; `MacCertificateTrust` runs `security add-trusted-cert -r trustRoot -p ssl`). The add-in's port only serves it, and never makes one | none: Microsoft Office is a Mac app here, so nothing makes one |
| The add-in's keys | `<support>/addin-keys.json` (mode 600): `{"keys": [{"hash", "app", "paired"}]}`, each paired key's SHA-256 in hex, never the key (`AddinKeys`) | none, as above |
| Logs | `~/Library/Logs/Healdan Helper/helper.log` and `extension.log` | `<support>/helper.log` and `extension.log` |

The token is in the keychain, never in a file: service `Healdan Helper`, account the server's base URL
(`https://host[:port]`). Nothing logs a token, a password, the channel's secret, a pairing code, an add-in's key or a
file's contents.

- **The item holds `{"device", "token"}`** (`KeptToken`): the token and the device id it was issued for, as the API
  doesn't say which device a token is. At start, `SignIn.TryResumeAsync` uses the token only when `device` is this
  computer's id in `device.json`. Otherwise it revokes the token (`DELETE /auth/tokens/current`, which may fail when
  Healdan can't be reached), removes it, and the helper asks for the sign-in. That covers a bare token kept by a helper
  before this check, so everyone signs in once after the update, and a `device.json` that was lost or remade.
- **A helper with its files elsewhere** (`HEALDAN_HELPER_DEV_ROOT`) uses the service `Healdan Helper (<root>)`
  (`HelperPaths.KeychainService`), so it never takes the installed helper's token, nor the installed helper its.

## The Channel

The extension, and a second helper handing over a link, call the helper over HTTP on `127.0.0.1`, at the port in
`channel.json`. Every request but
`GET /v1/ping` sends the secret as `X-Healdan-Secret`; a missing or wrong one is `403`. Bodies are JSON.

| Route | Body | Answer |
|---|---|---|
| `GET /v1/ping` | none | `{"ok": true}` |
| `POST /v1/link` | `{"url"}` | `{}` at once; the open runs in the background, as for an Apple event's link |
| `POST /v1/open-dialog` | `{"app"}` | `{"action": "opened" \| "local" \| "cancel"}`, once the window closes |
| `POST /v1/save-new` | `{"app", "name", "formats", "path"}` | `{"action": "save" \| "local" \| "cancel", "folderId", "name", "format", "properties", "tempPath"}`; `path` is the copy for Save as New Document |
| `POST /v1/file` | `{"tempPath", "folderId", "name", "format", "properties", "app", "replaces"}` | the new copy's state |
| `POST /v1/adopted` | `{"path"}` | the copy's state; the file as it is now is the new copy's starting point |
| `POST /v1/saved` | `{"path"}` | the copy's state once Healdan has every save written so far, at once when nothing is on its way, else after 120 s as it is |
| `POST /v1/new-version` | `{"path"}` | `{}`; the copy's next save goes up as a version |
| `POST /v1/properties` | `{"path"}` | `{"action": "saved" \| "cancel" \| "file"}`; `file` means it isn't in Healdan, so the caller runs Save to Healdan |
| `POST /v1/closed` | `{"path"}` | `{}` |
| `POST /v1/state` | `{"path"}` | the copy's state, or `{"known": false}` |

A copy's state is `{"known": true, "docNumber", "title", "docRef", "readOnly", "path", "holdBy", "pending"}`.
`pending` is true while the copy has changes on their way to Healdan: a save queued, going up or waiting for the
server, a Save as New Version still to go, or a write that hasn't settled.

- **Following a save:** the extension calls `/v1/saved` after each save, and sets the title and footer from the
  answer. A confirmation answers only once nothing newer is on its way, so the title never stops a version behind.
  A save Healdan refuses for good, or one that fails, answers the same way.
  While the answer says `pending`, the extension asks again after 2, 5, 15, 30, then every 60 seconds, so the title
  moves on once Healdan is back, however long it was away. It asks the same way while the helper can't be reached,
  which keeps the save on the computer until it's back to send it. Following ends when the window closes, a newer save
  of the copy takes over, or the copy is saved elsewhere.

- **A second helper's link:** only one helper runs for each user. macOS starts another copy of the app when the copy
  it hands `healdan:` links to isn't the one running, such as an old one in `/Applications` beside a newer one, and
  sends the link as an Apple event once the app runs, not in its arguments. So a start that finds a helper answering
  its channel runs `HandOverApp` instead of the helper: Avalonia with no window or icon, which takes each link's event
  and posts it to `/v1/link` (`LinkHandOver`), then quits a second after the last, or after 5 seconds when none came.
  A link it can't hand over is logged as dropped, with why. An old copy can't do any of this, so its links are lost.

- **The window routes** answer when the user closes the window, which can take minutes, so their callers mustn't time
  out. A second request while one of a window is open is answered `cancel`, and brings the open one up.
- **Save to Healdan:** `save-new` answers a `tempPath` in `<support>/Temp/`. The extension writes the document there
  in the chosen format, then calls `file` with the answer's values unchanged. `name` has no extension. The helper
  deletes the temporary file afterwards, and only ever deletes one in `<support>/Temp/` or the system's temporary
  folder. Then the extension saves the window as the answer's `path` (`storeAsURL`) and calls `adopted`, both before
  the window takes input again.

## The Add-in

`clients/office/` is Healdan's add-in for Word, Excel and PowerPoint: a web add-in with an XML manifest, in plain
JavaScript with no npm dependencies. `healdan-api`'s POM copies it into the jar, and `AddinResource` serves it at
`/api/addin/`: see [The Add-in's Port](#the-add-ins-port) for the helper's end. Its design is the M3.5 spec, §6 and §7.

- **`manifest.template.xml`** has the add-in's fixed ID, the Healdan tab and its six buttons (five in Excel and
  PowerPoint, which have no Insert Doc ID), and two placeholders `AddinResource` fills in with the server's address. Raise
  its `Version` in any change to it, or the admin centre keeps the old one.
- **`web/healdan.js`** is the logic, with no Office and no page: the helper client, the six flows, the pane's texts, and
  what the ribbon's code tells the pane. Everything else calls it as `Healdan`.
- **`web/office-glue.js`** is the `office` object the flows take, made of the Office.js calls the M3.5 trial proved:
  `getFileAsync` in 4 MiB slices for the document's bytes, custom properties for the mark (`HealdanMark`; Word and Excel,
  none in PowerPoint), and in Word a `DocProperty` field in the primary footer for Insert Doc ID. It's named so that it
  isn't taken for Microsoft's `office.js`.
- **`web/commands.html` and `commands.js`** are the ribbon's buttons. Office loads the page without showing it, and
  runs the function a button names (`saveToHealdan` and the rest, the manifest's names). Each is defined on `window` and
  with `Office.actions.associate` once Office is ready, runs its flow, and calls `event.completed()` once, whatever
  happens: after the flow has finished and, if it opened a window, after the window has closed (Office reports that as
  a `DialogEventReceived`), so that the button's page is still there to hear the window.
- **`web/taskpane.html`, `taskpane.js` and `healdan.css`** are the pane and the message window, in the helper's look.
  The manifest declares no shared runtime, so a button can't open the pane. A button with something to tell opens
  `taskpane.html?message=<code>&app=<app>` through `Office.context.ui.displayDialogAsync` instead, and the page shows
  the text `messageFor` has for the code, plus the field for the pairing code when the app isn't paired. Nothing in the
  address is trusted: the code must look like a code, and the text goes in as text. On the web
  (`message=no-office-on-the-web`) the window says so and asks the helper nothing: a web page doesn't probe loopback
  ports.
- **The window and the button talk by messages** (`windowMessage` in `healdan.js`; strings, so JSON, between
  `messageParent` and `messageChild`, accepted only from the page's own origin). The window says `ready` once it can hear
  the button. What Office said about a failure, which can hold a file name, goes to the window as a `detail` message
  then and never in its address, as the server logs addresses. And once the window has paired, it hands the button the
  `paired` key, which the button keeps in its own storage too, as the two pages may not share it.
- **The pane and the buttons share the app's `localStorage`,** as the trial found for the pane and the ribbon: the key
  (`healdan.key.<app>`), the helper's port (`healdan.port`), the last message (`healdan.last`), and a note for each
  flow still running (`healdan.busy.<flow>`), which the running flow refreshes every five seconds and the pane stops
  believing after fifteen. A flow waits as long as a window of the helper's stays open, and the pane says so. The
  message window isn't counted on to share it, hence the key handed over in a message.
- **The pane checks the helper** with `hello` and `ping` only, one check at a time and the next five seconds after the
  last has answered. It never asks `state`, which would have the helper drive Word by AppleScript at every check. The
  status and the busy line are live regions, so they are written only when what they say changes.
- **Following a `202` and losing the answer:** once `call` has followed a `202`, whatever stops it learning the outcome
  (the `result` route refused, the helper signing out, the helper not answering twice) is `lost-result`, which says to
  check the document in Healdan before trying again, because a pending `file` may already have filed it. A route's own
  errors are still its own. A `file` request that fails on the way, before any answer, is `lost-result` too, and isn't
  sent again: the helper may have filed the document before the answer was lost.
- **The server serves** only files named `[A-Za-z0-9-]+\.(html|js|css)` and `assets/*.png` from it, with a
  Content-Security-Policy on the pages that allows scripts from the server and `appsforoffice.microsoft.com` and
  connections to the server, Microsoft's services and the helper's four ports. No script, handler or style goes inline.

## The Add-in's Port

The Healdan add-in in Word, Excel and PowerPoint calls the helper on a second listener beside the channel. Office's web
view blocks a secure page's requests to `http://127.0.0.1`, so it's HTTPS, with the helper's own certificate. The code
is in `Healdan.Helper.Core/Addin/`: `AddinServer` (the transport and the checks), `AddinHandler` (the routes),
`Pairing` and `AddinKeys`. The design is the M3.5 spec, `docs/superpowers/specs/2026-10-30-m3-5-microsoft-office-design.md`,
§5.8, §5.9 and §9.

- **The ports:** `127.0.0.1`, the first free of `47615`, `47616`, `47617` and `47618` (`AddinPorts`). The add-in
  tries them in the same order, so another user of the Mac with a helper on the first doesn't stop this one. The
  add-in's pages allow connections to these ports alone.
- **When it's open:** from the helper's start to its quit, signed in or not, while the certificate exists, hasn't
  expired and the Mac trusts it. That includes its last 30 days, when Set Up Microsoft Office offers to renew it: a
  working certificate never takes the add-in down. Signed out, it stays open, so the pane can say so: the document
  routes answer `409 signed-out`, and pairing still works. Set Up opens it without a restart
  (`HelperSession.EnsureAddin`); when Set Up renews the certificate, the open port serves the new one from the next
  connection on, on the same port (`AddinServer.UseCertificate`), rather than closing and opening again. The port serves
  the certificate there is (`AddinCertificate.ForServer`), and never makes or renews one: that's Set Up's, which has
  the Mac trust it.
- **Nothing from outside closes it.** A connection that fails before it's taken, or the helper running out of file
  descriptors for a moment, is logged once for each kind, and the port goes on listening, as Kestrel's does.
- **HTTP/1.1, one request per connection** (`Connection: close`): a request line, headers to an empty line, and a
  `Content-Length` body of at most 150 MiB for `file` and 1 MiB for any other route. Anything else, such as a chunked
  body, a second request, a folded or doubled `Host`, a body that isn't UTF-8, or a value no text can hold (an escaped
  lone surrogate), is `400 bad-request`. Bodies are JSON, and errors are `{"error": "<code>"}`, as the channel's are.

### The Checks

Each request passes these in order before any route runs. Review Focus 5 of the M3.5 plan: another user of the Mac,
a web page in a browser, or a page from another Healdan is refused here.

| Check | Refused with |
|---|---|
| `Host` is `127.0.0.1:<port>` or `localhost:<port>`, so a page whose name was pointed at `127.0.0.1` (DNS rebinding) is refused | `403 bad-host` |
| `Origin` is the helper's server's: `https://host[:port]`, its address in `settings.json` without the path. No other page, and no other Healdan's, gets further | `403 bad-origin` |
| `OPTIONS`, for any path, is a preflight: `204` with the CORS headers. It carries neither the protocol nor the key | |
| `X-Healdan-Protocol: 1`, on every request but `GET /v1/hello` | `400 bad-request` without it; `426 update-healdan-helper` for a protocol the helper doesn't speak |
| `X-Healdan-Key` is a key the helper gave, on every route but `hello` and the two pairing routes. A route that doesn't exist asks for one too, so a caller without a key learns nothing | `403 not-paired` |
| The helper is signed in and started, on every keyed route but `ping` | `409 signed-out` |

Every answer once `Origin` has passed, errors included, carries `Access-Control-Allow-Origin: <origin>`,
`Vary: Origin`, `Access-Control-Allow-Headers: Content-Type, X-Healdan-Key, X-Healdan-Protocol`,
`Access-Control-Allow-Methods: GET, POST`, `Access-Control-Allow-Private-Network: true` and
`Access-Control-Max-Age: 600`. The private-network header answers Chromium's preflight, for Windows' WebView2 later:
Office for Mac's WebKit doesn't ask. A request refused at `Host` or `Origin` gets no CORS headers.

### The Routes

| Route | Body | Answer |
|---|---|---|
| `GET /v1/hello` | none | `{"name": "Healdan Helper", "protocols": [1], "server": "<origin>"}`, the server in `settings.json`, signed in or not; no key |
| `POST /v1/pair/start` | `{"app"}` | `{"pairingId"}`, and the helper's window shows the code; `403 too-many-tries` while pairing rests |
| `POST /v1/pair/finish` | `{"pairingId", "code"}` | `{"key"}`; `403 wrong-code` with `"triesLeft"`, or `403 pairing-expired` |
| `POST /v1/ping` | `{}` | `{"ok": true, "signedIn": bool}` |
| `POST /v1/open-dialog` | `{"app"}` | as the channel's |
| `POST /v1/save-new` | `{"app", "url", "asNew": bool}` | `{"action": "save" \| "local" \| "cancel", "folderId", "name", "format", "properties", "ticket"}`; `asNew` true is Save as New Document |
| `POST /v1/file` | `{"ticket", "mark", "bytes"}` | the new copy's state; `bytes` is the document, in base64 |
| `POST /v1/new-version` | `{"app", "url"}` | `{}` |
| `POST /v1/properties` | `{"app", "url"}` | as the channel's |
| `POST /v1/state` | `{"app", "url"}` | the copy's state, or `{"known": false}` |
| `POST /v1/result` | `{"id"}` | the outcome of the route that answered `202` with that `id`, as that route would have answered it; `202` again while it's still running; `404 no-result` for an id that's unknown, expired, already answered or another key's |

- **The app** is the one the request's key was paired for. A body naming another is `400 bad-request`: a key drives
  only its own app.
- **Which document:** `url` is the add-in's `Office.context.document.url`, possibly empty, as a path or a `file://`
  URL. The helper uses it only when it names one of its copies, and otherwise takes the app's front window
  (`OfficeFiling.CopyForAsync`).
- **`save-new` and `file`** are Save to Healdan from Office (`OfficeFiling`): `save-new` takes the front window as it
  arrives and answers a `ticket` for it, which `file` uses once, within 10 minutes. The document routes are made anew
  at each start (`AddinDocuments`), so a ticket from before a sign-out is `409 no-ticket` after it, whichever server the
  helper signs in to next.
- **Nothing typed while filing is lost:** once the user clicks Save in the Save window, `save-new` fingerprints the
  window it took, before the add-in reads the bytes, and the ticket keeps the fingerprint. `file` saves that window as
  the copy, and `SaveAs` fingerprints it just before and just after its save. If either differs from the ticket's, or
  there's no fingerprint to compare, the copy goes up as a save rather than being taken as its starting point.
- **`new-version`** saves the document first when it has unsaved changes, and that save goes up as the version;
  otherwise the file as it is goes up as one.
- **The routes' own refusals** are `409`: `not-a-copy` for `new-version` on a window that isn't one of the helper's
  copies, `wrong-window` when the front window isn't the one Save to Healdan was pressed in, or when the add-in's mark
  doesn't show and the app gave no window name to tell, and `no-ticket` for a ticket used or expired. Any other
  failure is `500 internal-error`, and the helper has told the user why where it's theirs to know.
- **Short requests:** the add-in calls the port with `fetch` from Office's web view, which is WebKit, and WebKit is
  likely to give up on a request that has had no answer for about a minute. `open-dialog`, `save-new` and `properties`
  wait while the helper's windows are up, and `file` can wait up to 300 s behind Word's Grant File Access, so a lost
  answer would lose a ticket, or the state of a copy just filed. So any keyed route but `ping` and `state` that hasn't
  finished after 20 s answers `202 {"pending": "<id>"}` and goes on (`PendingResults`). The add-in then asks
  `result`, which waits up to 20 s again, until the outcome comes: the status and body the route would have answered,
  an error included. An outcome belongs to the key that started it, is answered once, and is kept for 10 minutes
  after its route finishes. A key can have 64 outcomes waiting at most; the next route answers
  `429 too-many-pending` and doesn't run. `result` passes the same checks as any keyed route, so while the helper is
  signed out it's `409 signed-out`, and the outcome waits; a route that went pending keeps the session it started in,
  so a ticket it answers after a sign-out is still that session's, and `no-ticket` in the next.
- **The add-in follows a `202` itself,** in `call`, so a button's flow sees only the route's own answer. When `result`
  answers `no-result` after that, the pane says the answer was lost (`lost-result`) and asks the user to check the
  document in Healdan before trying again. The log names a route that went pending, never its id.
- **The add-in's routes and the channel's are separate lists:** the extension can't call these, nor the add-in the
  channel's.

### Pairing

- **`pair/start`** takes `Word`, `Excel` or `PowerPoint`, nothing else, as the window shows it. The helper's pairing
  window shows a six-digit code from `RandomNumberGenerator`, and the answer's `pairingId` isn't the code.
- **`pair/finish`** with the code the user typed: the right one answers a key, 32 random bytes in base64url, which the
  add-in keeps and sends as `X-Healdan-Key`. A wrong one is `403 wrong-code` with the tries left; after three, or two
  minutes, or once a newer pairing has started, it's `403 pairing-expired`.
- **One pairing at a time:** a new one replaces the window and ends the one before. The window's Cancel ends its own
  pairing, and the window closes once the app has paired or the code has expired. Pairing needs no sign-in: a pairing
  under way goes on through a sign-out, and only quitting ends it.
- **Nine wrong codes in a row,** across pairings, and pairing rests for 10 minutes: `pair/start` answers
  `403 too-many-tries` and shows nothing. Otherwise another user of the Mac could start pairing over and over, three
  guesses at a time, faster than anyone types. A pairing that works starts the count again. Expiry and the rest go by
  the system's monotonic timer, not the time of day.
- **The keys:** `addin-keys.json` keeps each key's SHA-256 only, with its app and when it was paired. A request's key
  is compared with each hash in fixed time, read from the file each time, so one Settings forgets stops working at
  once. An app that pairs again gets a new key beside the old.
- **The log** names the routes, apps and refusals, never a header's value, a code, a key or a body.

## The LibreOffice Extension

`clients/libreoffice/` is Python, with UNO and the standard library only, for LibreOffice 26.2 and later.

- **`healdan_logic.py`** decides everything that doesn't need UNO: the commands, where the helper keeps its files,
  the channel, and the flows of Open, Save, Save As and the six buttons. The tests run it without LibreOffice.
- **`healdan/healdan.py`** is the thin UNO layer:
  - a dispatch interceptor on every window, the Start Center's included, attached once, which takes over every Open
    variant (`.uno:Open`, `.uno:OpenFromWriter`, `.uno:OpenFromCalc` and the rest), `.uno:Save` and `.uno:SaveAs`;
  - a protocol handler for the Healdan menu and toolbar (`org.healdan.libreoffice:`);
  - a job on LibreOffice's events, which attaches to new windows, sets a copy's title from the helper, tells the
    helper when a copy closes, and takes the copies out of Recent Documents.
- **The `.xcu` files** declare the menu, the toolbar and its name, the protocol handler and the job. `Addons.xcu`
  divides the toolbar into three groups with separators, and its `Images` node gives each of the six commands its
  images (see [The Images](#the-images)).
- **`description.xml`** holds the version the helper compares with the installed one: it installs the bundled
  extension only when that is newer, so a change to the extension's content comes with a new version. It also names
  the extension's icon for LibreOffice's Extension Manager.
- **The notices** show in the document's window as an infobar, with the notice's first sentence in bold and the rest
  in regular type. The flows and their tests still deal in whole sentences: `notice_parts` in `healdan_logic.py`
  splits one at its first full stop (not one inside brackets, as in an error's own words, nor one inside a version
  number), and `tell` in `healdan.py` passes the two parts to the infobar. Where there's no infobar (the Start
  Center), a message box shows the headline, a blank line and the detail. A notice of one sentence is a headline
  alone.
- **Long calls** run on a thread of their own while LibreOffice's thread keeps its events going, and what they change
  in the document goes back to LibreOffice's thread.
- **When the helper can't be reached,** Open and Save fall back to LibreOffice's own boxes, with one notice until the
  helper answers again. An unexpected error before any helper window was shown falls back the same way.
  - A copy's save is still stored, and followed like any other until the helper is back and sends it.
  - Once the helper answers anything, an error included, the infobars saying it isn't running go from the windows
    still showing one (`withdraw` in `healdan.py`). Every notice goes through `Flows.tell`, so `Flows` knows which
    windows those are, and leaves a window whose infobar a later notice has replaced.

`clients/libreoffice/build.sh` packages it as `healdan.oxt`, the same bytes from the same sources.

### The Images

The toolbar's and menu's images are in `clients/libreoffice/healdan/icons/`, and are committed, so building the
extension needs no .NET.

- **For each of the six commands,** a 16 px and a 26 px PNG in mid-grey, and a white pair for high contrast
  (`save-to-healdan-16.png`, `save-to-healdan-26.png`, `save-to-healdan-16-hc.png`, and so on). Save to Healdan and
  Open from Healdan carry Healdan's shield, so they aren't taken for LibreOffice's own Save and Open. The extension's
  icon is the mark at 42 px, with a white one (`healdan-42.png`, `healdan-42-hc.png`).
- **They're rendered, not drawn by hand,** from the helper's icons and mark, so the two look alike:

  ```bash
  dotnet run --project clients/helper/tools/IconRender
  ```

  Run it on a Mac whenever an icon changes (after `python3 clients/icons/write-helper-icons.py`, if a Tabler icon
  did), and commit what it writes. `IconRender` uses Avalonia's headless Skia drawing on
  `Windows/HelperIcons.axaml`'s geometry and `HealdanMark`'s shapes. It isn't in the solution, so `dotnet test` and
  CI don't build it, and nothing checks the images against their source. A second run writes the same bytes.
- **`Addons.xcu`'s `Images` node** maps each command's URL to its four files, through `%origin%`. The same images
  show beside the Healdan menu's items wherever LibreOffice shows pictures in menus.
- **The package carries `THIRD-PARTY-NOTICES.txt`,** with Tabler Icons' name, version and MIT licence, which the
  icons are drawn from. Update it with the icons.
- **The tests** (`test_package.py`) check that every command has its four images in the package at the right sizes,
  that the toolbar has its three groups, and that the extension's icon and the notice are there.

### Installing It by Hand

1. Run `clients/libreoffice/build.sh`.
2. Open `clients/libreoffice/healdan.oxt` in LibreOffice (double-click it, or **Tools › Extensions › Add**), accept,
   then quit LibreOffice with **⌘Q** and start it again.

Don't use `unopkg` on macOS. With LibreOffice 26.8 on macOS 27 it fails to reach the LibreOffice it starts
(`NoConnectException`), even with LibreOffice closed and `TMPDIR=/tmp`, and `unopkg list` hangs beside a running
LibreOffice. So the packaged helper never runs it on macOS: it opens its own `.oxt` in LibreOffice, once for each
helper version that brings a newer extension, and reads LibreOffice's registry under
`~/Library/Application Support/LibreOffice/4/user/` to see what's installed: at each start, as the menu opens, and
every 5 seconds while the extension is missing or older, so the menu, the menu bar icon's dot and Settings follow
what the user accepts. On Ubuntu, `unopkg` works.

## Running the Helper from Source

On a Mac, against a [development stack](../development-stack/) with the smoke overlay:

```bash
HEALDAN_HELPER_DEV_ROOT=$PWD/scratch/helper dotnet run --project clients/helper/Healdan.Helper
```

Sign in with the stack's address, such as `http://localhost:8080`: the helper allows plain HTTP for `localhost`
only. A Debug build run this way:

- keeps its files under `HEALDAN_HELPER_DEV_ROOT` when it's set, away from an installed helper's, and its sign-in
  under a keychain service of its own;
- takes its token from `HEALDAN_HELPER_DEV_TOKEN` instead of the keychain, when that's set, as one issued to its own
  device id: check-outs read as this computer's only when the token was;
- answers the windows from the JSON file `HEALDAN_HELPER_DEV_ANSWERS` names, for a check with nobody at the screen;
- isn't an app bundle, so macOS doesn't hand it links, it posts no notifications (its notices become the menu's
  status line), it doesn't start at sign-in, and it carries no `.oxt`.

Release builds have none of the variables. For links, notifications and the extension's install, build the app with
`clients/helper/packaging/mac/package.sh` (unsigned, without `SIGN_IDENTITY`, it runs on your own Mac) and open
`clients/helper/packaging/mac/out/Healdan Helper.app`. Only one helper runs per user: a second start finds the first
through `channel.json`, hands it any link it was started for, and quits (see [The Channel](#the-channel)).

## Tests

- **The helper's core** has tests for each part, the cycle's failure cases included, against a fake server
  (`FakeApi`) and a fake LibreOffice that writes files and lock files (`FakeApp`). A fake `IOfficeScripting`
  (`FakeScripting`) stands in for Word, Excel and PowerPoint, with their owner files, and renames, saves and reopens
  copies as they do. `OfficeRenameTests` and `OfficeCrashTests` run the cycle over it.
- **The cycle's tests wait for what they need,** not for a fixed time, so a slow runner can't change what they see.
  `Wait.Until` polls for it (a confirmation, a save reaching `FakeApi`, or one queued, through the internal
  `CopyCycle.QueuedHash`). Where two writes must make one save, the test stops the settle's clock (a `PausableClock`
  in `CycleTimings.Clock`) between them. It stops it too while a write in place that must go up whole is made,
  since the write empties the file first.
- **`AddinServerTests`** run a real add-in's port on ports the system says are free, with a client that trusts only
  the test's certificate, and send what a web page, another program or another Healdan's page would.
  `PairingTests` and `AddinHandlerTests` cover pairing and the routes, and `PendingResultsTests` a route that answers
  `202` and is collected through `result`. Those run with a hold time of milliseconds, and a slow route waits on a gate
  the test opens rather than for a fixed time.
- **`ContractTests`** fail when the client calls a route `openapi.yaml` doesn't have, or a record (`[Schema]`) reads
  a field its schema lacks. A server change that would break the helper changes it in the same pull request.
- **`Category=Stack`** tests run the cycle against a real stack, and skip without `HEALDAN_STACK_URL`. The smoke
  test's step 5d runs them in CI.
- **`Category=MacKeychain`** tests use your real keychain, so they run only when you ask.
- **`Category=MacOffice`** tests drive the real Word, Excel and PowerPoint through `MacOfficeScripting`: they open,
  change and close throwaway documents, each test in its own folder inside `healdan-office-tests` in the temporary
  folder, and the apps ask for Automation and Grant File Access the first time. Grant File Access opens in a test's own
  folder: go up one level and grant `healdan-office-tests`, and the app asks no more, in that run or later ones. They
  skip unless `HEALDAN_MAC_OFFICE=1` is set. `EveryScriptCompiles` compiles every AppleScript against the apps'
  dictionaries with `osacompile`, starts nothing, and runs on any Mac that has the three apps.
- **`NoticesTests`** fail when the packages `Healdan.Helper/THIRD-PARTY-NOTICES.txt` names differ from those the app
  restores (its `obj/project.assets.json`), ids and versions both. The file is About's Open-Source Notices, built
  into the app. A package added or moved needs its notice: its copyright and licence text, from the package or its
  repository at that version, in a section whose `Packages:` line names it. The .NET section holds the runtime
  pack's own `LICENSE.TXT` and `THIRD-PARTY-NOTICES.TXT`, and names the pack it took them from; refresh it when the
  helper moves to a new .NET release.
- **`MarkTests`** fail when About's mark, `Windows/HealdanMark.axaml`, and the docs site's `site/static/favicon.svg`
  draw different shapes.
- **`IconsTests`** fail when `Windows/HelperIcons.axaml` and the SVGs in `clients/icons/tabler/` draw different
  shapes, or when the SVGs aren't the spec's list, `SpecNames`. After adding or changing an icon, run
  `python3 clients/icons/write-helper-icons.py`, and add a new one to `SpecNames`.
- **`AttentionTests`** pin when the menu bar icon shows its dot, and the reason its tooltip gives.
- **`SystemFontTests`** lay San Francisco out at each size the helper uses, Regular and SemiBold, and fail when a
  width is more than 0.2% from CoreText's own layout of the same text. They run on macOS and skip elsewhere.
- **The extension's tests** cover `healdan_logic.py` (the notices' headlines and details included) with fakes for
  LibreOffice, and build the `.oxt` and check that its files agree: the toolbar, the images and the notice. A real
  LibreOffice isn't part of them.
- **The add-in's tests** (`clients/office/test/*.test.js`, Node's built-in runner) run without Office:
  - `healdan.test.js` covers `web/healdan.js`: finding the helper's port, the calls with the protocol and the key,
    following a `202` through `result`, pairing, each button's flow in Word, Excel and PowerPoint, the pane's texts, and
    what the buttons tell the pane. A fake `fetch` plays the helper's port and a fake `office` object the glue. They
    also fail when the ports differ from the helper's `AddinPorts` or the server's Content-Security-Policy.
  - `office-glue.test.js` covers `web/office-glue.js` against a fake Office.js that logs what it's asked: the slices
    and their base64, the mark in Word and Excel and none in PowerPoint, and Insert Doc ID's property and field.
  - `commands.test.js` covers `web/commands.js`: the function names against the manifest's, `event.completed()` once
    however a press ends and not before the message window closes, the window's address, the detail sent to it and the
    key taken from it, and the busy note.
  - `taskpane.test.js` covers `web/taskpane.js` on a fake page made from the real `taskpane.html`: pairing, the status,
    a slow helper, and the messages. They also fail on an inline script or an id the script asks for and the page
    lacks.
  - What Office.js and Office's web view really do is checked by hand, with the owner, in Word, Excel and PowerPoint:
    the Content-Security-Policy against Office.js, the message window and its two-way messages with the button, and the
    ribbon. `AddinResourceTest` checks that
    the server serves every file the pages load.

The windows' look, focus, notifications and LibreOffice's own screens are checked by hand: each pull request that
changes them lists its checks for the owner.

## Releases

A `helper-v1.2.3` tag builds `Healdan-Helper-1.2.3.dmg`, signed with the Developer ID and notarised, and attaches it
to the tag's GitHub release. [Releasing the macOS Helper](../continuous-integration/#releasing-the-macos-helper) has
the steps and the secrets.

- **The bundle** is `com.healdan.helper`: one self-contained file for Apple silicon, with `healdan.oxt` in
  `Contents/Resources`. It registers `healdan:` and stays out of the Dock.
- **The hardened runtime** has two exceptions (`clients/helper/packaging/mac/entitlements.plist`): `allow-jit`, for
  .NET's JIT, and `automation.apple-events`, which lets the helper drive Word, Excel and PowerPoint with AppleScript.
  `Info.plist`'s `NSAppleEventsUsageDescription` is what macOS's Automation prompt says. The bundle's native libraries
  are signed with the helper's own Developer ID, so library validation accepts them.
- **The icon,** `AppIcon.icns`, is Healdan's mark from the docs site's `site/static/favicon.svg` on macOS's rounded
  square. `clients/helper/packaging/mac/make-icon.swift` draws it each time `package.sh` runs, so changing the
  favicon changes the helper's icon too. About's mark is the same shapes in XAML, as Avalonia doesn't read SVG, and
  `MarkTests` fails until it's redrawn. The menu bar icon follows the mark too: it's drawn at run time from
  `HealdanMark`'s shapes, in one colour.
- **The version** is the helper's own, from its tag. The helper checks `GET /server`'s `apiVersion`, and refuses a
  server whose API it doesn't speak.
- **The login item:** after its first sign-in, the installed helper registers itself to start at sign-in, with
  `SMAppService`. It doesn't when it runs from the disk image, or from where macOS runs a downloaded app until it's
  moved (App Translocation).

## Linux

The helper's core, and its tests, run on Linux too: CI runs them on Ubuntu. The Ubuntu package, its tray icon and its
link hand-over come in M3's second phase.
