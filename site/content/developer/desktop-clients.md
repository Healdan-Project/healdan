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
| The helper | Healdan | The [API](../api/) under `/api/v1`, with a device token |

The extension holds no token and calls no server. The helper does the work, and draws every window, once for every
app.

## The Helper

`clients/helper/Healdan.Helper.sln` is .NET 10 (`global.json` pins the SDK), with three projects:

- **`Healdan.Helper.Core`** has no windows and no Avalonia, so the tests drive all of it:
  - `Api/`: the API client, written by hand against `openapi.yaml`;
  - `Copies/`: the local copies and the record beside each;
  - `Cycle/`: the open-save-close cycle;
  - `Session/` and `Security/`: sign-in, the keychain and the device id;
  - `Channel/` and `Links/`: the channel's server and routes, and the links;
  - `Browse/`, `Forms/` and `Ui/`: what the windows show and decide, and their words;
  - `Platform/` and `Settings/`: the extension's install check, which app opens each kind of file, and the settings.
- **`Healdan.Helper`** is the Avalonia 12 app: the menu-bar icon (`Tray/`), the windows (`Windows/`), and macOS's
  notifications, login item and extension install (`Platform/`). It stays out of the Dock (`LSUIElement`) and hands
  the focus back after each window.
- **`Healdan.Helper.Core.Tests`** is xUnit.

### The Cycle

- **Opening:** `GET /documents/{n}`; a check-out with `{"app": "LibreOffice"}` when the user can edit it, else a
  read-only copy; the download into `Copies/`, through a temporary file; then LibreOffice. If the open fails after
  the check-out, the check-out is released, unless this computer already had a copy. One open of a document runs at
  a time: a second while one is under way, such as a double-clicked Open in App, is dropped.
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
  it (an older extension), the first write that settles is. The record keeps `adoptingSince` until then: at a restart
  in between, a file written within 10 seconds of the filing is the starting point, and one written later is sent.
- **Closing:** the extension's `/v1/closed`, or LibreOffice's lock file, `.~lock.<copy>#`, gone at two polls a second
  apart. Unsent changes go up with `keepCheckedOut: false`, otherwise `DELETE …/checkout`; then the copy is deleted.
- **A crash:** while a copy is open, the helper checks every 5 seconds that `soffice` is running.
- **Starting:** `ResumeAsync` reads every record, resends what wasn't confirmed, checks in what closed meanwhile, and
  offers a crashed copy, before the helper takes any link. A save whose answer never came is repeated with its
  `saveId` and the kind it went up as (the record's `sentKind`), and then, when newer content or a version was
  still to go (`pendingKind`), that goes up as a new save.
- **Refusals:** `409 held-elsewhere` or `not-held` ends the copy's check-out: it's kept, never deleted, and the user
  is told to use Save as New Document. `409 version-moved` is resent once with the new `ifVersion` and the current
  name, but only while the document is still checked out here and its `fileVersion` hasn't moved.
- **This computer's hold** is one whose `hold.device.id` is this computer's device id. Device names aren't compared,
  as two computers can share one.

### Local Copies

Every copy is directly in one folder, `Copies/`, mode 700.

- **The name** is `<name> #<doc #> v<fileVersion><ext>`, with ` (read-only)` before the extension for a read-only
  copy. `/`, `:`, `\` and control characters in the name become `-`.
- **At most 241 bytes** in UTF-8, so the record beside it, `.<copy's name>.healdan.json`, fits in 255 bytes, and so
  does LibreOffice's lock file.
- **A copy keeps the name it was opened with** while it's open. The record holds its file name and the version it
  holds, and the title comes from the record.
- **A copy with unconfirmed changes is never deleted** by the helper, and `CopyStore.Create` never deletes anything.
  Once nothing looks after one (its check-out ended, or Healdan refused it, and its window closed), the menu lists it
  under Not Yet in Healdan (`CopyCycle.KeptCopies`), and choosing it opens it as it is, with no check-out
  (`OpenKept`).

### The Helper's Files

| | macOS | Linux |
|---|---|---|
| Support folder | `~/Library/Application Support/Healdan Helper/` | `$XDG_DATA_HOME/healdan-helper/`, or `~/.local/share/healdan-helper/` |
| Local copies | `<support>/Copies/` | the same |
| Files for filing | `<support>/Temp/`: the helper deletes each file once it's filed | the same |
| Settings | `<support>/settings.json` | the same |
| Device id | `<support>/device.json`: a random UUID made once, not a secret | the same |
| The channel | `<support>/channel.json`, mode 600: `{"port", "secret", "pid"}` | the same |
| Logs | `~/Library/Logs/Healdan Helper/helper.log` and `extension.log` | `<support>/helper.log` and `extension.log` |

The token is in the keychain, never in a file: service `Healdan Helper`, account the server's base URL
(`https://host[:port]`). Nothing logs a token, a password, the channel's secret or a file's contents.

## The Channel

The extension calls the helper over HTTP on `127.0.0.1`, at the port in `channel.json`. Every request but
`GET /v1/ping` sends the secret as `X-Healdan-Secret`; a missing or wrong one is `403`. Bodies are JSON.

| Route | Body | Answer |
|---|---|---|
| `GET /v1/ping` | none | `{"ok": true}` |
| `POST /v1/link` | `{"url"}` | `{}` at once; the open runs in the background |
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
  While the answer says `pending`, the extension asks again after 2, 5, 15, 30, then every 60 seconds, so the title
  moves on once Healdan is back, however long it was away.

- **The window routes** answer when the user closes the window, which can take minutes, so their callers mustn't time
  out. A second request while one of a window is open is answered `cancel`, and brings the open one up.
- **Save to Healdan:** `save-new` answers a `tempPath` in `<support>/Temp/`. The extension writes the document there
  in the chosen format, then calls `file` with the answer's values unchanged. `name` has no extension. The helper
  deletes the temporary file afterwards, and only ever deletes one in `<support>/Temp/` or the system's temporary
  folder. Then the extension saves the window as the answer's `path` (`storeAsURL`) and calls `adopted`, both before
  the window takes input again.

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
- **The `.xcu` files** declare the menu, the toolbar and its name, the protocol handler and the job.
  `description.xml` holds the version the helper compares with the installed one.
- **Long calls** run on a thread of their own while LibreOffice's thread keeps its events going, and what they change
  in the document goes back to LibreOffice's thread.
- **When the helper can't be reached,** Open and Save fall back to LibreOffice's own boxes, with one notice. A copy's
  save is still stored, and the helper sends it once it's back. An unexpected error before any helper window was
  shown falls back the same way.

`clients/libreoffice/build.sh` packages it as `healdan.oxt`, the same bytes from the same sources.

### Installing It by Hand

1. Run `clients/libreoffice/build.sh`.
2. Open `clients/libreoffice/healdan.oxt` in LibreOffice (double-click it, or **Tools › Extensions › Add**), accept,
   then quit LibreOffice with **⌘Q** and start it again.

Don't use `unopkg` on macOS. With LibreOffice 26.8 on macOS 27 it fails to reach the LibreOffice it starts
(`NoConnectException`), even with LibreOffice closed and `TMPDIR=/tmp`, and `unopkg list` hangs beside a running
LibreOffice. So the packaged helper never runs it on macOS: it opens its own `.oxt` in LibreOffice, once for each
helper version that brings a newer extension, and reads LibreOffice's registry under
`~/Library/Application Support/LibreOffice/4/user/` to see what's installed. On Ubuntu, `unopkg` works.

## Running the Helper from Source

On a Mac, against a [development stack](../development-stack/) with the smoke overlay:

```bash
HEALDAN_HELPER_DEV_ROOT=$PWD/scratch/helper dotnet run --project clients/helper/Healdan.Helper
```

Sign in with the stack's address, such as `http://localhost:8080`: the helper allows plain HTTP for `localhost`
only. A Debug build run this way:

- keeps its files under `HEALDAN_HELPER_DEV_ROOT` when it's set, away from an installed helper's;
- takes its token from `HEALDAN_HELPER_DEV_TOKEN` instead of the keychain, when that's set;
- answers the windows from the JSON file `HEALDAN_HELPER_DEV_ANSWERS` names, for a check with nobody at the screen;
- isn't an app bundle, so macOS doesn't hand it links, it posts no notifications (its notices become the menu's
  status line), it doesn't start at sign-in, and it carries no `.oxt`.

Release builds have none of the variables. For links, notifications and the extension's install, build the app with
`clients/helper/packaging/mac/package.sh` (unsigned, without `SIGN_IDENTITY`, it runs on your own Mac) and open
`clients/helper/packaging/mac/out/Healdan Helper.app`. Only one helper runs per user: a second start finds the first
through `channel.json`, and quits.

## Tests

- **The helper's core** has tests for each part, the cycle's failure cases included, against a fake server
  (`FakeApi`) and a fake LibreOffice that writes files and lock files (`FakeApp`).
- **The cycle's tests wait for what they need,** not for a fixed time, so a slow runner can't change what they see.
  `Wait.Until` polls for it (a confirmation, a save reaching `FakeApi`, or one queued, through the internal
  `CopyCycle.QueuedHash`). Where two writes must make one save, the test stops the settle's clock (a `PausableClock`
  in `CycleTimings.Clock`) between them.
- **`ContractTests`** fail when the client calls a route `openapi.yaml` doesn't have, or a record (`[Schema]`) reads
  a field its schema lacks. A server change that would break the helper changes it in the same pull request.
- **`Category=Stack`** tests run the cycle against a real stack, and skip without `HEALDAN_STACK_URL`. The smoke
  test's step 5d runs them in CI.
- **`Category=MacKeychain`** tests use your real keychain, so they run only when you ask.
- **The extension's tests** cover `healdan_logic.py` with fakes for LibreOffice, and build the `.oxt` and check that
  its files agree. A real LibreOffice isn't part of them.

The windows' look, focus, notifications and LibreOffice's own screens are checked by hand: each pull request that
changes them lists its checks for the owner.

## Releases

A `helper-v1.2.3` tag builds `Healdan-Helper-1.2.3.dmg`, signed with the Developer ID and notarised, and attaches it
to the tag's GitHub release. [Releasing the macOS Helper](../continuous-integration/#releasing-the-macos-helper) has
the steps and the secrets.

- **The bundle** is `com.healdan.helper`: one self-contained file for Apple silicon, with `healdan.oxt` in
  `Contents/Resources`. It registers `healdan:` and stays out of the Dock.
- **The version** is the helper's own, from its tag. The helper checks `GET /server`'s `apiVersion`, and refuses a
  server whose API it doesn't speak.
- **The login item:** after its first sign-in, the installed helper registers itself to start at sign-in, with
  `SMAppService`. It doesn't when it runs from the disk image, or from where macOS runs a downloaded app until it's
  moved (App Translocation).

## Linux

The helper's core, and its tests, run on Linux too: CI runs them on Ubuntu. The Ubuntu package, its tray icon and its
link hand-over come in M3's second phase.
