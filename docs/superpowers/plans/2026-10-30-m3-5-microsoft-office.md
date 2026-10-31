# M3.5 "Microsoft Office on the Mac" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save to and open from Healdan inside Word, Excel and PowerPoint for Mac, through a web add-in deployed from the Microsoft 365 admin centre and the Healdan Helper, with the doc # and version in the title bar and Word's footer, as the approved spec describes.

**Architecture:**
- **Server.** `healdan-api` serves the add-in's pages, and a manifest made with the server's own address, at `/api/addin/`. No other server change, and no upstream edit.
- **The helper.** Microsoft Office becomes a second app beside LibreOffice:
  - everything that drives Word, Excel and PowerPoint goes behind `IOfficeScripting`, with an AppleScript implementation;
  - the cycle takes a set of apps, renames a copy after each confirmed save, files a new document's bytes, and saves Word's recovered window back after a crash;
  - a second listener, HTTPS on loopback with the helper's own certificate, serves the add-in, which pairs once per app by a typed code;
  - a Set Up Microsoft Office window makes the certificate and asks macOS to trust it.
- **The add-in.** Plain JavaScript in `clients/office/`: a Healdan tab with six buttons, a pane for pairing and messages, and Office.js for the document's bytes, the mark and Insert Doc ID.
- **CI.** The add-in's tests run under `Clients`; `code-changed.sh` counts `clients/office/` as server code too.

**Tech Stack:**
- **Server:** as M3: JDK 25, CXF 4.0 JAX-RS, JUnit 4 with Spring's servlet mocks.
- **Helper:** .NET 10 and Avalonia 12.1.4, as M3; `System.Net.Security.SslStream` and `System.Security.Cryptography.X509Certificates` for the add-in's port; `osascript` for AppleScript. No new NuGet packages.
- **Add-in:** Office.js from `https://appsforoffice.microsoft.com/lib/1/hosted/office.js`; plain JavaScript in classic scripts, no npm dependencies; Node's built-in test runner (`node --test`).

**Status:** the owner approved this plan on 2026-10-30, and chose subagent-driven execution. **Built** on 2026-10-30 and 2026-10-31, on one branch: Tasks 1 to 11, and a Task 9b the build added (short requests to the add-in's port); Tasks 1 to 10 and 9b were each reviewed against the spec before the next began. The spec carries the plan's corrections and the build's rulings, marked "changed in the build"; the decision log lists them, and the follow-ups what the build left open. **Next:** the pull requests A to E, cut from the branch's commits, and Task 12, with the owner's by-hand checks (spec §12.3). Nothing built has yet run against Office itself.

**Spec:** [`docs/superpowers/specs/2026-10-30-m3-5-microsoft-office-design.md`](../specs/2026-10-30-m3-5-microsoft-office-design.md), approved by the owner on 2026-10-30 after the trial. Every § below points into it.
- Where the two disagree, the spec wins, except for the corrections under "Spec corrections and calls this plan makes".
- §8.5 is the trial's evidence. The trial's code was throwaway and isn't in the repository, but the scripts that worked are written into Task 2.
- M3's spec and plan still hold for everything M3.5 doesn't change: the copies, the cycle, the windows, the extension and the channel.

## Global Constraints

Every task's requirements include these.

- **Scope.** Word, Excel and PowerPoint for Mac, from Microsoft 365, on macOS 27 (Apple silicon). No Windows code, but nothing above `IOfficeScripting` may be macOS-specific (spec §5.2). The LibreOffice flow doesn't change (spec §2.2).
- **No upstream file changes** (spec §11.1). `UPSTREAM_TESTS` is unchanged.
- **The programs.**

  | | Word | Excel | PowerPoint |
  |---|---|---|---|
  | Extensions it opens | `.doc`, `.docx`, `.docm`, `.dotx`, `.dotm`, `.rtf` | `.xls`, `.xlsx`, `.xlsm` | `.ppt`, `.pptx`, `.pptm` |
  | Save to Healdan's format | `.docx` | `.xlsx` | `.pptx` |
  | Process (macOS) | `Microsoft Word` | `Microsoft Excel` | `Microsoft PowerPoint` |
  | Bundle | `/Applications/Microsoft Word.app` | `/Applications/Microsoft Excel.app` | `/Applications/Microsoft PowerPoint.app` |
  | Check-out's `app` | `Word` | `Excel` | `PowerPoint` |
  | Owner file beside the copy | `~$` replacing the first two characters (long names), or `~$` + name (short) | `~$` + name | `~$` + name |
  | Renamed after each save | yes | yes | no: keeps its opened name (spec §5.4) |
  | Kept off Open Recent | `save as … add to recent files false`; `open … add to recent files false` | `save workbook as … add to most recently used list false` | no control |

  A copy's record's `App` is `Microsoft Office` for all three.
- **OpenDocument files and drawings** (`.odt`, `.ott`, `.ods`, `.odp`, `.odg`) open in LibreOffice whatever Settings say, while LibreOffice is installed (spec §5.10).
- **AppleScript** (spec §5.2, §9):
  - fixed script text only, embedded as resources; every value goes in through `on run argv`;
  - collections are looped by index (`repeat with i from 1 to (count documents)` / `set xd to document i`), never `repeat with x in documents`;
  - no variable named `flat`, `x`, `out`, `before`, `after`, `found` or `mark`;
  - a script that may meet Grant File Access runs `with timeout of 300 seconds`, and its process is given 330 s; every other script gets 20 s.
- **The add-in's port** (spec §5.8):
  - HTTPS on `127.0.0.1`, first free of ports **47615, 47616, 47617, 47618**;
  - HTTP/1.1, one request per connection (`Connection: close`), bodies at most 150 MiB;
  - headers: `X-Healdan-Protocol: 1` on every request but `GET /v1/hello` and `OPTIONS`; `X-Healdan-Key: <key>` on every keyed route;
  - `Host` must be `127.0.0.1:<port>` or `localhost:<port>`, else `403 bad-host`;
  - `Origin` must be the helper's server's origin (`https://host[:port]`, as `settings.json` holds it), else `403 bad-origin`;
  - CORS answers carry `Access-Control-Allow-Origin: <that origin>`, `Vary: Origin`, `Access-Control-Allow-Headers: Content-Type, X-Healdan-Key, X-Healdan-Protocol`, `Access-Control-Allow-Methods: GET, POST`, `Access-Control-Allow-Private-Network: true` and `Access-Control-Max-Age: 600`; `OPTIONS` answers `204`;
  - errors are `{"error": "<code>"}`, as the extension's channel's are.
- **Pairing** (spec §5.9): six digits, from `RandomNumberGenerator`; two minutes; three tries; one pairing at a time. A key is 32 random bytes in base64url. `<support>/addin-keys.json` (mode 600) holds `{"keys": [{"hash": "<sha256 hex>", "app": "Word", "paired": "<ISO time>"}]}`.
- **The certificate** (spec §5.13): `<support>/addin-cert.pem` and `<support>/addin-key.pem` (mode 600); RSA 2048, SHA-256; subject `CN=Healdan Helper (this Mac)`; SAN `DNS:localhost`, `IP:127.0.0.1`; `CA:FALSE` (critical); key usage digital signature and key encipherment; EKU server authentication; valid 800 days, renewed by Set Up Microsoft Office once fewer than 30 remain. Trusted with `/usr/bin/security add-trusted-cert -r trustRoot -p ssl -k ~/Library/Keychains/login.keychain-db <cert>` (the trial's form; `trustAsRoot` is refused for a self-signed certificate).
- **The add-in's pages** are at `/api/addin/`, public. The manifest is `/api/addin/manifest.xml`. Its id is `7b0f3a52-8d1e-4c4b-9a6e-2f5d0c8e4a91`, and its `<Version>` is bumped in any change to the manifest's template.
- **Copies, records, timings and the extension's channel** are M3's (M3 plan, Global Constraints), unchanged.
- **CI time.** State in each PR how many seconds it adds to `build`, the `Tests` jobs and `Clients`.

**The add-in's routes** (Tasks 7, 9, 9b):

| Route | Body | Answer |
|---|---|---|
| `GET /v1/hello` | none | `{"name": "Healdan Helper", "protocols": [1], "server": "<origin>" \| null}`; no key |
| `POST /v1/pair/start` | `{"app"}` | `{"pairingId"}`, and the helper's window shows the code |
| `POST /v1/pair/finish` | `{"pairingId", "code"}` | `{"key"}`; `403 wrong-code` with `"triesLeft"`, or `403 pairing-expired` |
| `POST /v1/ping` | `{}` | `{"ok": true, "signedIn": bool}` |
| `POST /v1/open-dialog` | `{"app"}` | as the extension's |
| `POST /v1/save-new` | `{"app", "url", "asNew": bool}` | `{"action": "save" \| "local" \| "cancel", "folderId", "name", "format", "properties", "ticket"}`; `asNew` true is Save as New Document |
| `POST /v1/file` | `{"ticket", "mark", "bytes"}` | the copy's state; `bytes` is base64 |
| `POST /v1/new-version` | `{"app", "url"}` | `{}` |
| `POST /v1/properties` | `{"app", "url"}` | as the extension's |
| `POST /v1/state` | `{"app", "url"}` | the copy's state, or `{"known": false}` |
| `POST /v1/result` | `{"id"}` | the pending route's outcome, its status and body as that route would have answered; `202 {"pending": "<id>"}` again while it runs; `404 no-result` for an id unknown, expired, already answered, or started with another key (added in the build, Task 9b) |

**Short requests** (added in the build, Task 9b): Office's web view is WebKit, which is likely to give up on a request that has had no answer for about 60 seconds, while `open-dialog`, `save-new` and `properties` wait for the user and `file` can wait 300 s behind Grant File Access. So any keyed route except `ping`, `state` and `result` itself that hasn't finished after the hold time (20 s, a constructor parameter) answers `202 Accepted` with `{"pending": "<id>"}` (16 random bytes, base64url), and keeps running; its outcome, success or error, is kept under that id. `result` (keyed, `X-Healdan-Protocol: 1`, through every check as any keyed route) waits up to the hold time for it. Each outcome is answered once, belongs to the key that started it, and is kept for 10 minutes after its route finishes. At most 64 ids wait per key; more answer `429 too-many-pending` without starting the route. The add-in's `call` follows a `202` through `result`; a `no-result` while following is the add-in's `lost-result`.

`url` is the add-in's `Office.context.document.url`, possibly empty. A `ticket` is a one-time id the helper makes at `save-new` for the window it took (Task 5); it expires after 10 minutes. Without the helper signed in, keyed routes answer `409 signed-out`; for a document that isn't a copy where one is needed, `409 not-a-copy`; for a front window carrying another document's mark, `409 wrong-window`; for a used or expired ticket, `409 no-ticket`.

## Review Focus

These are the inputs most likely to hurt a person using M3.5 that no task's main tests reach. Each one has a test in the task named.

1. **An edit typed while the helper renames, or while it files a new document.** The rename and Save to Healdan both save the window with what's in it; a keystroke in between must reach Healdan, never be folded silently into the copy's starting point and lost at close (Tasks 4, 5).
2. **The wrong window.** The user presses Save to Healdan in one window and clicks into another while the Save window is open. The helper must save the window the request came from, or refuse, never another document (Task 5).
3. **PowerPoint's close and reopen.** It must never read as the window closing, which would check the document in (Task 4).
4. **A crash with unsaved work in Word.** Word reopens the copy as "Recovered", where Cmd+S opens Save As. The work must go back to the copy and up to Healdan, not to wherever Save As puts it (Task 4).
5. **A request that isn't from the user's add-in.** Another user of the Mac, a web page in a browser, or a page served from another Healdan: each is refused before any route runs, and no key or code is ever written to the log (Task 7).

## Spec corrections and calls this plan makes

The owner hears about these when this plan is handed over. Task 11 folded them into the spec, each marked "changed in the build" in its section.

1. **The manifest's template lives in `clients/office/`,** with the add-in's pages; `healdan-api` fills it in. (Spec §10 names it under both.)
2. **`SetDocId` only updates a `Healdan Doc ID` that's already there.** The trial's script created the property when it was missing, which would make the server stamp every document saved from Word, not only those with Insert Doc ID.
3. **The add-in's port is an `SslStream` server on a `TcpListener`,** not `HttpListener`, which can't be handed a certificate in code.
4. **`save-new` gives a `ticket`.** The helper takes the front window when `save-new` arrives, before its Save window opens (spec §5.5 step 2), and `file` names it by the ticket.
5. **Excel and PowerPoint open copies with `open -a`.** Their dictionaries have no `open` that keeps a file off Open Recent; Word's own `open` does.
6. **Two people signed in to one Mac at once:** (corrected in the build) each helper takes the first free port, so the second person's helper opens the next one, and the add-in skips a helper whose `hello` names another server. Pairing fails only when both helpers are signed in to the same server: the second person's add-in then reaches the first person's helper, whose window shows the code. Known Limitations says so. (As first written: "only the first one's helper can take the add-in's ports, and the other's add-in reaches the wrong helper and can't pair.")
7. **The add-in's pages are classic scripts,** as in the trial, with the logic in one file that also loads under Node for its tests, not ES modules.

## Conventions

- **Running Maven:** as M3's plan. `mvn -B -ntp -pl healdan-core install -DskipTests`, then `mvn -B -ntp -pl healdan-api test -Dtest=<Class>`.
- **Running the helper's tests:** `dotnet test clients/helper/Healdan.Helper.sln`; one class with `--filter "FullyQualifiedName~<Class>"`.
- **By-hand tests on a Mac with Office:** `HEALDAN_MAC_OFFICE=1 dotnet test clients/helper/Healdan.Helper.sln --filter "Category=MacOffice"` (without the variable they skip, apart from `EveryScriptCompiles`, which starts nothing). They open, change and close throwaway documents in a temporary folder; quit Office's apps first, and expect Automation and Grant File Access prompts the first time.
- **Running the add-in's tests:** `node --test 'clients/office/test/*.test.js'`, with the pattern quoted: Node 21 and later read `--test`'s arguments as file patterns, so a folder (`node --test clients/office/test/`) fails as a file that isn't a test.
- **Commits:** one per task, in the repo's style: an imperative subject, a body that says why, and the `Co-Authored-By` trailer.
- **Docs ship in the task that changes the behaviour** (CLAUDE.md). Screenshots are of the demo firm (CLAUDE.md, "The demo overlay").

## PRs

All target `master`; stack each on the one before.

- **PR A, Task 1:** the add-in's pages and manifest on the server, with placeholder pages. Usable on its own: an admin can fetch the manifest.
- **PR B, Tasks 2–5:** the helper's core: the scripting seam, Microsoft Office as a second app, the rename, crashes and filing from Office. No add-in yet; Word opens copies from links and the Open window, and the title moves on.
- **PR C, Tasks 6–8:** the certificate and Set Up Microsoft Office, the add-in's port and pairing, Settings and the package.
- **PR D, Tasks 9–10:** the add-in itself, and its CI job.
- **PR E, Task 11:** CLAUDE.md, the capability map, the spec's corrections and the roadmap.
- **Task 12:** Healdan Helper 0.4.0, the owner's server upgraded, the manifest uploaded, and the owner's walkthrough.

---

### Task 1: The add-in's pages and manifest on the server

**Files:**
- Create: `clients/office/manifest.template.xml`, `clients/office/web/taskpane.html`, `clients/office/web/commands.html` (placeholders until Task 10), `clients/office/web/assets/` (the icons, Step 6)
- Create: `healdan-api/src/main/java/com/logicaldoc/healdan/api/AddinResource.java`
- Modify: `healdan-api/pom.xml` (copy `clients/office/manifest.template.xml` and `clients/office/web/**` into `healdan/api/addin/`)
- Modify: `healdan-api/src/test/java/com/logicaldoc/healdan/api/RouteCoverageTest.java:57`, `Contract.java:48` (leave `/api/addin` out, as `/api/docs`)
- Modify: `.github/scripts/code-changed.sh:28`, `.github/scripts/tests/test_code_changed.py`
- Modify: `docker/smoke-test.sh` (step 2)
- Modify: `clients/helper/tools/IconRender` (the Office sizes)
- Test: `healdan-api/src/test/java/com/logicaldoc/healdan/api/AddinResourceTest.java`
- Docs: `site/content/developer/api.md` (a short section: `/api/addin/` isn't JSON API), `site/content/install/office-integration.md` (new `## Microsoft Office` section: what the server needs, the manifest's address, `LOGICALDOC_PUBLIC_URL`)

**Interfaces:**
- Produces: `GET /api/addin/manifest.xml`, `GET /api/addin/{file}` and `GET /api/addin/assets/{file}`; the template's placeholders `{{BASE}}` (for example `https://healdan.example/api/addin`) and `{{ORIGIN}}` (`https://healdan.example`).

- [ ] **Step 1: Write the manifest's template**

The trial's manifest, with `{{BASE}}` and `{{ORIGIN}}` for its addresses, the id and version from Global Constraints, `DisplayName` "Healdan", and no Trial Pane button. Its shape:

```xml
<OfficeApp xmlns="http://schemas.microsoft.com/office/appforoffice/1.1" … xsi:type="TaskPaneApp">
  <Id>7b0f3a52-8d1e-4c4b-9a6e-2f5d0c8e4a91</Id>
  <Version>1.0.0.0</Version>
  <ProviderName>Healdan</ProviderName>
  <DefaultLocale>en-US</DefaultLocale>
  <DisplayName DefaultValue="Healdan"/>
  <IconUrl DefaultValue="{{BASE}}/assets/healdan-32.png"/>
  <HighResolutionIconUrl DefaultValue="{{BASE}}/assets/healdan-64.png"/>
  <SupportUrl DefaultValue="https://docs.healdan.com/user/office/"/>
  <AppDomains><AppDomain>{{ORIGIN}}</AppDomain></AppDomains>
  <Hosts><Host Name="Document"/><Host Name="Workbook"/><Host Name="Presentation"/></Hosts>
  <DefaultSettings><SourceLocation DefaultValue="{{BASE}}/taskpane.html"/></DefaultSettings>
  <Permissions>ReadWriteDocument</Permissions>
  <VersionOverrides … xsi:type="VersionOverridesV1_0">
    <!-- per host: FunctionFile {{BASE}}/commands.html; a CustomTab "Healdan.Tab" with one group;
         buttons (ExecuteFunction): saveToHealdan, openFromHealdan, saveAsNewVersion, saveAsNewDocument,
         properties, and insertDocId in Document only -->
  </VersionOverrides>
</OfficeApp>
```

The labels and tips are the extension's (Save to Healdan, Open from Healdan, Save as New Version, Save as New Document, Properties, Insert Doc ID).

- [ ] **Step 2: Write the failing tests**

`AddinResourceTest extends ApiTestCase`:
- `testManifestNamesThisServer`: with `server.url` as the test context has it (`http://localhost:8080`), `GET /api/addin/manifest.xml` is `200`, `application/xml`, contains `http://localhost:8080/api/addin/taskpane.html` and `<AppDomain>http://localhost:8080</AppDomain>`, and no `{{`.
- `testManifestUsesThePublicAddress`: with `server.url` set to `https://healdan.example/` for the test, the manifest has `https://healdan.example/api/addin/commands.html`.
- `testManifestRefusesPlainHttpElsewhere`: with `server.url` unset and the request's host `healdan.example` over `http` (the test sets the mock request's server name; add that to `ApiCall` if it has no way yet), the answer is `409` with the code `no-public-address`, whose detail says to set `LOGICALDOC_PUBLIC_URL` to the server's `https://` address.
- `testPages`: `taskpane.html` and `commands.html` are `200` `text/html; charset=UTF-8`, with a `Content-Security-Policy` header that has `script-src 'self' https://appsforoffice.microsoft.com` and `connect-src 'self' https://127.0.0.1:47615 https://127.0.0.1:47616 https://127.0.0.1:47617 https://127.0.0.1:47618`, and `X-Content-Type-Options: nosniff`.
- `testIcons`: `assets/save-to-healdan-32.png` is `200` `image/png`.
- `testNothingElseIsServed`: `404` for `/api/addin/..%2Fpom.xml`, `/api/addin/..`, `/api/addin/.hidden`, `/api/addin/manifest.template.xml` and `/api/addin/nothing.js`.
- `testNoTokenNeeded`: every request above is made without a token.

- [ ] **Step 3: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest=AddinResourceTest`
Expected: FAIL (`404` everywhere).

- [ ] **Step 4: Package the files and write `AddinResource`**

- **POM:** a `maven-resources-plugin` execution `copy-addin` at `generate-resources`, copying `${project.basedir}/../clients/office/manifest.template.xml` and `../clients/office/web/**` into `${project.build.outputDirectory}/healdan/api/addin/`.
- **`AddinResource`** is `@Component @Path("/addin")`, beside `DocsResource`, with `@GET @Public` methods. If CXF lets `DocsResource`'s `/` root take its requests (M2 found that a shorter root path swallows a longer one; M3 plan correction 3), `testPages` shows it with `404`s: then make it `@Path("/")` with `addin/…` method paths, as `DocsResource` does, and say so in the PR.
  - `manifest()` at `manifest.xml`: reads the template, works out the origin, replaces both placeholders, and answers `application/xml; charset=UTF-8`;
  - `file(@PathParam("file"))` at `{file: [A-Za-z0-9-]+\.(html|js|css)}`, and `asset(@PathParam("file"))` at `assets/{file: [A-Za-z0-9-]+\.png}`, read with `DocsResource`'s `readOrNull` pattern; `manifest.template.xml` is never served.
- **The origin:** `Context.get().getConfig().getProperty("server.url")` without its trailing slash, when set; otherwise the request's scheme, host and port (Tomcat's `RemoteIpValve` has already applied `X-Forwarded-Proto`). It must be `https`, or `http` on `localhost`, `127.0.0.1` or `[::1]`; otherwise the answer is `ApiProblem` `409 no-public-address`.
- **Headers on HTML:** `Content-Security-Policy: default-src 'self'; script-src 'self' https://appsforoffice.microsoft.com; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' https://127.0.0.1:47615 https://127.0.0.1:47616 https://127.0.0.1:47617 https://127.0.0.1:47618 https://*.microsoft.com https://*.office.com https://*.office.net` and `X-Content-Type-Options: nosniff` on every file.
- `RouteCoverageTest` leaves `/addin/…` out, and `Contract.check` skips `/api/addin`, each with a comment, as for `/docs`.

- [ ] **Step 5: Run the tests**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='AddinResourceTest,DocsResourceTest,RouteCoverageTest'`
Expected: PASS.

- [ ] **Step 6: Icons**

`IconRender` writes the add-in's images into `clients/office/web/assets/`: each of the six buttons' Tabler icons (the extension's) at 16, 32 and 80 px, and the mark (`healdan`) at 16, 32, 64 and 80. Run it by hand on a Mac (`dotnet run --project clients/helper/tools/IconRender`) and commit the PNGs.

- [ ] **Step 7: CI and the smoke test**

- `code-changed.sh`: `clients/office/` counts as code as well as clients. Line 28's exemption becomes `^clients/(helper|libreoffice|icons)/` instead of `^clients/`. Tests: `test_addin_runs_the_build_and_the_clients` (a change only under `clients/office/` gives `code=true`, `clients=true`), and the existing clients-only tests still pass.
- `smoke-test.sh` step 2: `check "GET /api/addin/manifest.xml names this server" grep -q "$BASE/api/addin/commands.html" <(curl -fsS "$BASE/api/addin/manifest.xml")`.

Run: `python3 -m unittest discover -s .github/scripts/tests -v`
Expected: PASS.

- [ ] **Step 8: Docs, then commit** — "Serve the Office add-in's pages and manifest"

The Install manual's Office Integration page gains `## Microsoft Office`: the server must be reachable over HTTPS at the address people use (`LOGICALDOC_PUBLIC_URL`), the manifest's address, and that the admin centre steps are on the same page (Task 10 writes them). The API page says `/api/addin/` serves the add-in's files and isn't part of the JSON API.

---

### Task 2: The scripting seam and its AppleScript

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Office/OfficeProgram.cs`, `IOfficeScripting.cs`, `IOsaRunner.cs`, `OsaRunner.cs`, `MacOfficeScripting.cs`
- Create: `clients/helper/Healdan.Helper.Core/Office/Scripts/*.applescript` (embedded resources, one per operation and program where they differ)
- Modify: `clients/helper/Healdan.Helper.Core/Healdan.Helper.Core.csproj` (`<EmbeddedResource Include="Office/Scripts/*.applescript" />`)
- Modify: `clients/helper/packaging/mac/entitlements.plist` (`com.apple.security.automation.apple-events`), `Info.plist.in` (`NSAppleEventsUsageDescription`: "Healdan Helper saves, renames and checks your documents in Word, Excel and PowerPoint so they stay in step with Healdan.")
- Test: `clients/helper/Healdan.Helper.Core.Tests/Office/MacOfficeScriptingTests.cs` (CI, with a fake runner), `ScriptsTests.cs` (CI), `MacOfficeTests.cs` (`[Trait("Category", "MacOffice")]`, by hand)

**Interfaces:**
- Produces:

```csharp
public enum OfficeProgram { Word, Excel, PowerPoint }

public static class OfficePrograms
{
    public static OfficeProgram? For(string fileName);            // by extension, Global Constraints' table
    public static string ProcessName(OfficeProgram p);            // "Microsoft Word"
    public static string BundlePath(OfficeProgram p);             // "/Applications/Microsoft Word.app"
    public static string CheckOutName(OfficeProgram p);           // "Word"
    public static string OwnFormat(OfficeProgram p);              // ".docx"
    public static bool RenamesAfterSave(OfficeProgram p);         // false for PowerPoint
    public static IEnumerable<string> OwnerNames(OfficeProgram p, string fileName);
}

public sealed record FrontDocument(string? Path, string Name, string? WindowName); // Path null: never saved
public sealed record DocumentRef(string? Path, string? Mark, string? WindowName); // SaveAs looks in this order, then the front window
public enum RenameResult { Missing, Unsaved, Renamed, RenamedChanged }
public sealed record SaveAsResult(string FoundBy, bool Changed);                // "path" | "mark" | "window" | "front"

public interface IOfficeScripting
{
    Task OpenAsync(OfficeProgram p, string path, CancellationToken ct = default);
    Task<FrontDocument?> FrontDocumentAsync(OfficeProgram p, CancellationToken ct = default);
    Task<bool> IsOpenAsync(OfficeProgram p, string path, CancellationToken ct = default);
    Task<bool?> HasUnsavedChangesAsync(OfficeProgram p, string path, CancellationToken ct = default); // null: not open
    Task<string?> FingerprintAsync(OfficeProgram p, DocumentRef doc, CancellationToken ct = default); // SHA-256 hex of the script's text
    Task<bool> HasMarkAsync(OfficeProgram p, string mark, CancellationToken ct = default);
    Task SaveAsync(OfficeProgram p, string path, CancellationToken ct = default);
    Task<RenameResult> RenameIfUnchangedAsync(OfficeProgram p, string path, string newPath, CancellationToken ct = default);
    Task<SaveAsResult> SaveAsAsync(OfficeProgram p, DocumentRef doc, string newPath, string? fingerprint, CancellationToken ct = default);
    Task ReopenAsync(OfficeProgram p, string path, CancellationToken ct = default);
    Task<bool> SetDocIdAsync(string path, string docRef, CancellationToken ct = default); // Word; false without the property
}

public interface IOsaRunner
{
    Task<string> RunAsync(string script, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct);  // osascript
    Task RunProcessAsync(string file, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct);    // open -a
}
```

`OwnerNames` gives every candidate (Word's two forms, and `~$` + name); a lock check is true when any exists. `OsaRunner` runs `/usr/bin/osascript -` with the args after it and the script on standard input, and throws `OfficeScriptException` (with osascript's error text, never the arguments) on a non-zero exit or the timeout.

- [ ] **Step 1: Write the failing CI tests**

`ScriptsTests` reads every embedded script:
- `EveryScriptTakesItsValuesAsArguments`: each has `on run argv`, and none contains `{`, `}` used as a placeholder (`{{` or `{0}`).
- `NoScriptLoopsOverACollectionWithIn`: none matches `repeat with \w+ in (documents|sections|fields|workbooks|worksheets|presentations|slides|shapes)`.
- `NoScriptUsesANameTheAppsTake`: none matches `set (flat|x|out|before|after|found|mark) to`.
- `LongScriptsWaitForGrantFileAccess`: the save-as, rename and reopen scripts contain `with timeout of 300 seconds`.

`MacOfficeScriptingTests` with a `FakeOsaRunner` recording `(script, args, timeout)` and answering canned output:
- `ArgumentsNeverReachTheScriptText`: `SaveAsAsync(Word, new DocumentRef(@"/tmp/a""; do shell script ""x.docx", null, null), "/tmp/b.docx", null)` passes both paths in `args`; the script text contains neither.
- `RenameParsesEachAnswer`: canned `missing`, `unsaved`, `renamed` and `renamed-changed` give the four `RenameResult`s.
- `SaveAsAndRenameGetTheLongTimeout`: 330 s; `FingerprintAsync` gets 20 s.
- `FingerprintHashesTheScriptsText`: canned `Hello` gives SHA-256 hex of `Hello`.
- `SetDocIdIsFalseWithoutTheProperty`: canned `no-property` gives false.
- `WordOpensThroughWordAndOthersThroughOpenA`: `OpenAsync(Word, p)` runs Word's open script; `OpenAsync(Excel, p)` runs `/usr/bin/open -a "Microsoft Excel" <p>` through `RunProcessAsync`.
- `OwnerNamesCoverWordsTwoForms`: `OwnerNames(Word, "Lease #1 v1.0.docx")` has `~$ase #1 v1.0.docx` and `~$Lease #1 v1.0.docx`; `OwnerNames(Excel, "Fees #3 v1.0.xlsx")` has `~$Fees #3 v1.0.xlsx`.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~Office"`
Expected: FAIL to compile.

- [ ] **Step 3: Write the scripts and `MacOfficeScripting`**

The scripts are the trial's, which worked (spec §8.5), with the corrections above. Word's rename, for reference:

```applescript
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set newPath to item 3 of argv
    set newName to item 4 of argv
    tell application "Microsoft Word"
        with timeout of 300 seconds
            set d to missing value
            repeat with i from 1 to (count documents)
                set xd to document i
                if (posix full name of xd) is p or (name of xd) is fname then set d to xd
            end repeat
            if d is missing value then return "missing"
            if (saved of d) is false then return "unsaved"
            set fpBefore to content of text object of d
            save as d file name newPath file format format document add to recent files false
            set d2 to missing value
            repeat with i from 1 to (count documents)
                set xd to document i
                if (name of xd) is newName then set d2 to xd
            end repeat
            if d2 is missing value then return "renamed-but-lost"
            if fpBefore is (content of text object of d2) then return "renamed"
            return "renamed-changed"
        end timeout
    end tell
end run
```

(`renamed-but-lost` maps to `Renamed`: the file was written, and the cycle finds the copy by its new name.) The `file format` follows the extension: `format document` (`.docx`), `format documentME` (`.docm`). Excel uses `save workbook as d filename newPath file format Excel XML file format add to most recently used list false`; PowerPoint never renames, so it has no rename script.

Word's `SetDocId`:

```applescript
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set v to item 3 of argv
    tell application "Microsoft Word"
        with timeout of 20 seconds
            set d to missing value
            repeat with i from 1 to (count documents)
                set xd to document i
                if (posix full name of xd) is p or (name of xd) is fname then set d to xd
            end repeat
            if d is missing value then return "missing"
            try
                set value of custom document property "Healdan Doc ID" of d to v
            on error
                return "no-property"
            end try
            set wasSaved to saved of d
            repeat with k from 1 to (count sections of d)
                repeat with hf in {header footer primary, header footer first page, header footer even pages}
                    try
                        set fls to (get fields of text object of (get footer (section k of d) index (contents of hf)))
                        repeat with j from 1 to (count fls)
                            update field (item j of fls)
                        end repeat
                    end try
                end repeat
            end repeat
            if wasSaved then set saved of d to true
            return "ok"
        end timeout
    end tell
end run
```

The fingerprints: Word `content of text object of d`; Excel each sheet's `value of (get used range of (worksheet k of d))`, joined; PowerPoint the text of every shape on every slide, by index. `FrontDocument`: Word `posix full name`, `name` and `name of active window`; Excel and PowerPoint `full name` (a POSIX path in the trial for PowerPoint) and `name`. Path-less (never saved) when the document's `path` is empty. `HasMark` and `SaveAs` find a document by `value of custom document property "HealdanMark"` inside `try`. `Reopen` (PowerPoint): `close d saving no` (the cycle has already saved it), then `open (POSIX file p)`.

- [ ] **Step 4: Run the CI tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~Office"`
Expected: PASS on macOS and Linux.

- [ ] **Step 5: The by-hand tests, on a Mac with Office**

`MacOfficeTests` (`[Trait("Category", "MacOffice")]`, returning early off macOS), in a temporary folder:
- `EveryScriptCompiles`: each embedded script compiles with `osacompile -o /dev/null -` (this needs the apps' dictionaries, so it can't run in CI).
- `WordRoundTrip`: create a document (`make new document`, a line of text), `SaveAsAsync` to `a.docx`, `FrontDocumentAsync`, `HasUnsavedChangesAsync` false, type through AppleScript, `RenameIfUnchangedAsync` → `Unsaved`, `SaveAsync`, `RenameIfUnchangedAsync` to `b.docx` → `Renamed` with `a.docx`'s owner file gone, insert the property and a field, `SetDocIdAsync` → true and the field shows the value, and Open Recent has no `b.docx` (`name of every recent file`).
- `ExcelRoundTrip` and `PowerPointRoundTrip`: the same where it applies; PowerPoint's `ReopenAsync` leaves it saved and open.
- `ANameThatWouldBreakAScript`: a document named `a"; do shell script "touch /tmp/pwned.docx` survives every operation, and `/tmp/pwned` never appears.

Run: `HEALDAN_MAC_OFFICE=1 dotnet test clients/helper/Healdan.Helper.sln --filter "Category=MacOffice"` (record the result in the PR).

- [ ] **Step 6: Commit** — "Drive Word, Excel and PowerPoint through one scripting seam"

---

### Task 3: Microsoft Office as a second app

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Office/MicrosoftOfficeApp.cs`, `clients/helper/Healdan.Helper.Core/Cycle/OfficeApps.cs`, `clients/helper/Healdan.Helper.Core/Settings/AppChooser.cs`, `InstalledApps.cs`
- Modify: `Cycle/IOfficeApp.cs` (two default members), `Cycle/CopyCycle.cs` (every `_app`/`_other` site, listed in Step 3), `Cycle/CloseWatcher.cs` (per-copy app), `Copies/CopyNames.cs` (`OfficeOwnerNames`, every program's `OfficePrograms.OwnerNames`), `Copies/CopyStore.cs` (`Delete` and `IsOpen` know Office's owner files), `Settings/HelperSettings.cs` (`Kinds`), `Session/HelperSession.cs`, `clients/helper/Healdan.Helper/App.axaml.cs:76`
- Test: `Healdan.Helper.Core.Tests/Cycle/OfficeAppsTests.cs`, `Settings/AppChooserTests.cs`, additions to `Cycle/CopyCycleTests.cs` and `Settings/HelperSettingsTests.cs`; a `FakeScripting : IOfficeScripting` in `Healdan.Helper.Core.Tests/Office/`

**Interfaces:**
- Consumes: Task 2's `IOfficeScripting`, `OfficePrograms`.
- Produces:

```csharp
public interface IOfficeApp
{
    string Name { get; }
    void Open(string path);
    bool IsRunning();
    bool LockExists(string path);
    string CheckOutName(string fileName) => Name;          // the check-out's app
    bool IsRunningFor(string path) => IsRunning();         // crash detection per copy
}

public sealed class MicrosoftOfficeApp(IOfficeScripting scripting, Func<string, bool> processRunning) : IOfficeApp
{
    public const string AppName = "Microsoft Office";
    // Name = AppName; CheckOutName = OfficePrograms.CheckOutName(For(fileName)); LockExists = any OwnerNames exists;
    // IsRunningFor(path) = processRunning(ProcessName(For(path))); Open = scripting.OpenAsync(...).GetAwaiter().GetResult()
    public IOfficeScripting Scripting { get; }
}

public sealed class OfficeApps(IOfficeApp libreOffice, IOfficeApp? microsoftOffice, IOfficeApp? otherFiles, Func<string, string> appFor)
{
    public IOfficeApp For(string fileName);   // a new copy: appFor(fileName) → LibreOffice, Microsoft Office or Default
    public IOfficeApp Of(CopyRecord record);  // an existing copy: by record.App, LibreOffice for an unknown name
    public IReadOnlyList<IOfficeApp> All { get; }
}

public sealed record InstalledApps(bool LibreOffice, bool Word, bool Excel, bool PowerPoint);
public sealed class AppChooser(HelperSettings settings, InstalledApps installed, Func<string, string?> systemDefaultApp)
{
    public string For(string fileName);       // "LibreOffice" | "Microsoft Office" | "Default"
}
```

`HelperSettings.Kinds` is `Dictionary<string, string>?`, from a kind's label to `LibreOffice` or `Microsoft Office`; absent means the default.

- [ ] **Step 1: Write the failing tests**

`AppChooserTests`:
- `OpenDocumentFilesStayInLibreOffice`: with Word chosen for Documents, `For("a.odt")` is `LibreOffice`; `For("a.odg")` is `LibreOffice`.
- `TheChoiceAppliesToMicrosoftFormats`: Documents → Microsoft Office makes `For("a.docx")` and `For("a.doc")` `Microsoft Office`.
- `TheDefaultIsTheSystemsWhenItsOneOfTheTwo`: no choice, system default for `.docx` is Word → `Microsoft Office`; system default is Pages → `LibreOffice`.
- `OnlyWhatsInstalled`: Word chosen but not installed → `LibreOffice`; LibreOffice missing and `a.odt` → `Microsoft Office` only if Word is installed, else `Default`.
- `OtherFilesAreTheDefaultApp`: `For("a.pdf")` is `Default`.

`HelperSettingsTests` (additions):
- `KindsRoundTrip`: `Kinds["Documents"] = "Microsoft Office"` survives Save and Load; an older `settings.json` without it loads with `Kinds` null.
- `AnUnknownAppInKindsIsIgnored`: `"Pages"` is treated as absent.

`OfficeAppsTests`:
- `ARecordKeepsItsApp`: a record with `App` `Microsoft Office` resolves to it even after Settings change to LibreOffice.

`CopyCycleTests` (with `FakeApp` for LibreOffice, a `MicrosoftOfficeApp` over `FakeScripting` and a fake process check):
- `ADocxForOfficeOpensInWordAndChecksOutAsWord`: with the chooser giving `Microsoft Office`, `OpenAsync` checks out with `app` `Word`, records `App` `Microsoft Office`, and calls `FakeScripting.OpenAsync(Word, path)`.
- `AnOdtStillOpensInLibreOffice` in the same cycle.
- `OfficesCloseIsItsOwnerFileGoing`: the owner file seen, then gone twice → checked in.
- `AWordCrashIsWordGoneWithTheOwnerFileLeft`: LibreOffice still running doesn't hide it.
- `ResumeUsesEachRecordsApp`: one copy of each app at start; each lock is checked with its own app.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~AppChooser|FullyQualifiedName~OfficeApps|FullyQualifiedName~CopyCycleTests|FullyQualifiedName~HelperSettings"`
Expected: FAIL to compile.

- [ ] **Step 3: Make the cycle take a set of apps**

- `CopyCycle`'s constructor becomes `CopyCycle(HealdanApi api, CopyStore store, OfficeApps apps, string deviceId, ICycleEvents events, CycleTimings? timings = null)`; the `otherFiles` parameter goes.
- At every site the survey found, use the copy's app:
  - 151 and 615: `_apps.For(doc.Name).Name` for a new record;
  - 251, 283, 288, 1417: `_apps.Of(record)`;
  - 261, 373–381, 573–575, 743–749: `_apps.Of(record)`, and `_apps.For(doc.Name)` for a new download (the default app is whatever `For` gives a file no office app opens);
  - 587: `CheckOutAsync(n, app.CheckOutName(doc.Name), move, ct)`;
  - 630, 778: `app.Open(path)`.
- `CloseWatcher.Watch(string path, IOfficeApp app, bool appSeenRunning = false)`: each entry keeps its app, the lock check uses it, and the crash check calls `app.IsRunningFor(path)`.
- `CopyStore.Delete` and `IsOpen` use `CopyNames.OfficeOwnerNames` (Word's two forms and `~$` + name) instead of `WordOwnerNames`.
- `HelperSession` takes `OfficeApps` instead of `app` and `otherFiles`. Its `_app` uses (Bring, AppRunning, the messages) take the copy's app, and "is an app running" becomes "is any app running" for `SettleCopies` and `OpenToSetAside`, with the message naming the app that is.
- `App.axaml.cs:76` builds `new OfficeApps(new LibreOfficeApp(), new MicrosoftOfficeApp(new MacOfficeScripting(new OsaRunner(), paths.Copies), ProcessRunning), new DefaultApp(), chooser.For)` on macOS, and without Microsoft Office on Linux. `InstalledApps` checks the bundles; `systemDefaultApp` is Task 8's `DefaultApps.For` (until then, `_ => null`).
- `SettingsWindow.axaml.cs:38` shows `chooser.For` instead of `AppFor`; `HelperSettings.AppFor` goes.

- [ ] **Step 4: Run the helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS, the existing LibreOffice tests unchanged.

- [ ] **Step 5: Docs, then commit** — "Open Word, Excel and PowerPoint copies in Microsoft Office"

`developer/desktop-clients.md`'s "The Cycle" says a copy's app is chosen per file and kept in its record, and names `IOfficeScripting`.

---

### Task 4: The rename after each save, Open Recent and crashes

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Office/OfficeAfterSave.cs`, `Office/CopiesNotice.cs`
- Modify: `Cycle/CopyCycle.cs` (`Confirm` at 1048–1072, a re-key of a session, the crash path), `Cycle/CloseWatcher.cs` (a reopened callback), `Copies/CopyStore.cs` (`Moved`), `Office/MicrosoftOfficeApp.cs`, `Settings/HelperSettings.cs` (`CopiesAnnounced`)
- Test: `Healdan.Helper.Core.Tests/Cycle/OfficeRenameTests.cs`, `OfficeCrashTests.cs`

**Interfaces:**
- Consumes: Task 3's `OfficeApps`, `MicrosoftOfficeApp.Scripting`.
- Produces:
  - `CopyStore.Moved(string oldPath, string newPath, CopyRecord record)`: writes the record beside `newPath` (its `FileName` the new name), deletes the old record, the old file if it's still there, and the old name's owner files.
  - `CloseWatcher`'s constructor gains `Action<string>? reopened`: called when a crashed copy's lock or owner file appears again.
  - In `CopyCycle`, internal: `Task RenameAfterSaveAsync(Session s)` and `void Rekey(Session s, string newPath)`.
  - `CopiesNotice(HelperPaths paths, INotifier notifier)`, with `void BeforeFirstSave(OfficeProgram p)`: the notice before an app's first save into `Copies` (spec §5.11), once per app, recorded in `HelperSettings.CopiesAnnounced` (a list of app names). Task 5 uses it too.

- [ ] **Step 1: Write the failing tests**

`OfficeRenameTests`, with `MicrosoftOfficeApp` over `FakeScripting` (whose `RenameIfUnchangedAsync` moves the file on disk as Word does, and can be told to answer `Unsaved` or `RenamedChanged`):
- `AConfirmedSaveRenamesTheCopy`: after a confirmed `v1.1`, the file and record are `… #1 v1.1.docx`, the old file and record are gone, and `StateOf(newPath).Title` ends `v1.1`.
- `TheRenamedFileIsntSentAgain`: no further save after the rename settles.
- `UnsavedChangesHoldTheRename`: `Unsaved`, then the next confirmed save (`v1.3`) renames straight to `v1.3`.
- `AnEditInTheGapIsSent` (Review Focus 1): `RenamedChanged` → the renamed file goes up as the next snapshot, and the title reaches it.
- `NoRenameWhileANewerSaveWaits`: a second save queued behind the first; the rename runs once, after the second.
- `PowerPointNeverRenames`: a `.pptx` copy keeps its name through two saves; both reach Healdan.
- `WordsFooterMovesOnAfterTheRename`: `SetDocIdAsync(newPath, "#1 v1.1")` is called; a copy without the property (`false`) is left alone.
- `TheCloseWatcherFollowsTheRename`: closing the renamed copy checks it in; nothing reads the old name's missing owner file as a close.
- `ARestartFindsTheRenamedCopy`: the record beside the new name is found at start.
- `TheFirstRenameIsAnnounced`: before Word's first rename, `FakeNotifier` gets "Word will ask for access to Healdan Helper's Copies folder. Click Select…, go up one level, select Copies, and click Grant Access."; not again for Word; again for Excel's first.

`OfficeCrashTests`:
- `WordsRecoveredWindowIsSavedBack` (Review Focus 4): crash (process gone, owner file left), then the owner file appears again → `SaveAsAsync(Word, new DocumentRef(path, null, null), path, null)` is called, and the change goes up as a save.
- `TheStaleOwnerFileGoesWithTheRename`: after the next rename, the old name's owner file is deleted.
- `PowerPointsReopenIsntAClose` (Review Focus 3): during `ReopenAsync` the owner file disappears for 2 s and comes back; no check-in, no release, no crash.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~OfficeRename|FullyQualifiedName~OfficeCrash"`
Expected: FAIL.

- [ ] **Step 3: The rename**

- After `Confirm` (CopyCycle.cs:1048–1072) raises `Confirmed`, and only when the copy's app is `MicrosoftOfficeApp`, `OfficePrograms.RenamesAfterSave` is true for it, the copy isn't read-only and the save queue for it is idle, call `RenameAfterSaveAsync` on the cycle's thread of work for that copy.
- It computes the new name with `CopyNames.FileName(record.DocumentName, docNumber, fileVersion, false)`. When the new name is the same, nothing happens.
- Before the call, `CopiesNotice.BeforeFirstSave(program)` and `SaveWatcher.Unwatch(old)`; then by result:
  - `Renamed`: `CopyStore.Moved`, `Rekey(s, newPath)` (a new `Session` keyed by the new name, carrying the old one's state), `SaveWatcher.Watch(newPath)` (it takes the file as settled), `CloseWatcher.Unwatch(old)` and `Watch(newPath, app, appSeenRunning: true)`, `ConfirmedHash` = the new file's hash; then `SetDocIdAsync` for Word.
  - `RenamedChanged`: the same, except `ConfirmedHash` stays, and `SaveWatcher.Touch(newPath)` so the new file goes up as a save.
  - `Unsaved` or `Missing`: `SaveWatcher.Watch(old)` again; the next confirmation tries again.
- An exception from the scripting (Word busy, a dialog up) is logged and treated as `Unsaved`.

- [ ] **Step 4: Crashes and PowerPoint's reopen**

- `OnCrashed` (CopyCycle.cs:1331) keeps watching a crashed Office copy for its owner file to appear again (`CloseWatcher` `reopened`). When it does, the cycle clears `Crashed`, calls `SaveAsAsync` to the copy's own path, and lets the watcher send the result. M3's crash window still offers Reopen and Send and Check In until then.
- A cycle-level `ReopenOfficeAsync(path)` for PowerPoint (Task 5 calls it): `CloseWatcher.Unwatch`, `SaveAsync`, `ReopenAsync`, wait up to 10 s for the owner file, `CloseWatcher.Watch(..., appSeenRunning: true)`.

- [ ] **Step 5: Run the helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 6: Docs, then commit** — "Rename Office copies after each save, and save Word's recovered window back"

The User Guide's `editing-and-versions/` says the title moves on after each save in Word and Excel, waits while there are unsaved changes, and stays at the version PowerPoint opened. `when-things-go-wrong/` describes Word's "Recovered" document and that Healdan saves it back by itself.

---

### Task 5: Filing from Office, and the front window

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Office/OfficeFiling.cs`, `Office/FilingTickets.cs`
- Modify: `Cycle/CopyCycle.cs` (`Adopted(string path, bool changedSinceFiling)`), `Ui/WindowAnswers.cs` (Office's formats)
- Test: `Healdan.Helper.Core.Tests/Office/OfficeFilingTests.cs`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces (Task 7 calls these from the add-in's routes):

```csharp
public sealed record FilingTicket(string Id, OfficeProgram Program, FrontDocument Window, string? Replaces, DateTimeOffset Expires);

public sealed class OfficeFiling(CopyCycle cycle, OfficeApps apps, IWindows windows, INotifier notifier, HelperPaths paths, HelperLog log)
{
    // save-new: takes the front window first, then shows the Save window with the app's own format only
    public Task<JsonObject> SaveNewAsync(OfficeProgram p, string? url, bool asNew);
    // file: writes the bytes to Temp, files them, saves the window as the copy, checks the fingerprint
    public Task<CopyState> FileAsync(string ticket, string? mark, byte[] bytes);
    // the copy a request is about: a known copy named by url, else the front window's copy, else null
    public Task<string?> CopyForAsync(OfficeProgram p, string? url);
}
```

- [ ] **Step 1: Write the failing tests**

With `FakeScripting` and `FakeWindows`:
- `SaveNewTakesTheFrontWindowFirst`: `FrontDocumentAsync` is called before `FakeWindows.SaveNewAsync`; the answer carries a `ticket`; the Save window was offered `["docx"]` only.
- `FileFilesTheBytesAndSavesTheWindowAsTheCopy`: `FileAsync` files the bytes (`POST /documents`, `checkOut: true`), calls `SaveAsAsync(Word, new DocumentRef(null, mark, window), copyPath, fp)`, and the state's title is `… #n v1.0`.
- `TheMarkIsWaitedForThenChecked`: `HasMarkAsync` false twice then true (0.5 s), and the front window is used once it's true; a front window carrying another document's mark is refused with `409 wrong-window` and nothing is filed (Review Focus 2).
- `PowerPointWithoutAMarkUsesTheTicketsWindow`: `HasMarkAsync` never true for PowerPoint → `SaveAsAsync` with the ticket's window name, then `ReopenOfficeAsync`.
- `AnEditWhileFilingIsSent` (Review Focus 1): `SaveAsAsync` answers `Changed: true` → `Adopted(path, changedSinceFiling: true)`, and the copy goes up as a save.
- `AFailedFilingLeavesTheWindowAlone`: the API refuses → no `SaveAsAsync`, a notice, and `FileAsync` throws so the add-in can remove its mark.
- `SaveAsNewDocumentReleasesTheOriginal`: `asNew` with the front window a copy → the ticket's `Replaces` is it, and after filing the original is released unchanged, as M3's `replaces`.
- `TicketsAreOneUseAndExpire`: a second `FileAsync` with the same ticket, or one after 10 minutes, is `409 no-ticket`.
- `CopyForPrefersAKnownUrl`: a `url` naming a copy (by resolved path or by file name in `Copies`) wins; an unknown or empty `url` falls back to the front window; `/tmp/…` and `/private/tmp/…` are the same file.
- `TheFirstSaveIntoCopiesIsAnnounced`: before an app's first `SaveAsAsync`, Task 4's `CopiesNotice` announces it, unless that app's first rename already did.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~OfficeFiling"`
Expected: FAIL.

- [ ] **Step 3: Write `OfficeFiling`**

- `SaveNewAsync`: `FrontDocumentAsync` first, then `windows.SaveNewAsync` with `{"app": "<Word|Excel|PowerPoint>", "name": <the window's name without extension, or the original document's for asNew>, "formats": [<own, without the dot>], "path": <replaces or null>}`; on `save`, a `FilingTicket` (10 minutes, `FilingTickets`), whose id goes in the answer.
- `FileAsync`: take the ticket; write `bytes` to `WindowAnswers.TempPath(paths, format)`; wait up to 3 s (every 250 ms) for `HasMarkAsync(mark)` when a mark is given; take a fingerprint (`FingerprintAsync` with `DocumentRef(window.Path, mark, window.WindowName)`); call `cycle.AdoptNewAsync(tempPath, new NewDocument(folderId, name, properties, true), replaces)`; `SaveAsAsync` to the state's path; `cycle.Adopted(path, result.Changed)`; for PowerPoint, `cycle.ReopenOfficeAsync(path)`. The temporary file is deleted in `finally`, as `ChannelHandler.FileAsync` does.
- `CopyForAsync`: as the test says, through `Path.GetFullPath` with `/private` stripped from both sides before comparing.
- **The notice before an app's first save into `Copies`** (spec §5.11): `OfficeFiling` calls Task 4's `CopiesNotice.BeforeFirstSave(program)` before `SaveAsAsync`.
- The notice for a failed filing names the app ("Word couldn't be filed in Healdan: …").

- [ ] **Step 4: Run the helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 5: Docs, then commit** — "File new Office documents in Healdan from the window they're in"

The User Guide's `saving-to-healdan/` gains `## From Word, Excel and PowerPoint`: the ribbon's Save to Healdan for a new document, the app's own format only, and the first Grant File Access (going up one level to select `Copies`).

---

### Task 6: The helper's certificate and Set Up Microsoft Office

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Addin/AddinCertificate.cs`, `clients/helper/Healdan.Helper.Core/Security/ICertificateTrust.cs`
- Create: `clients/helper/Healdan.Helper/Platform/MacCertificateTrust.cs`, `clients/helper/Healdan.Helper/Windows/SetUpOfficeWindow.axaml(.cs)`
- Modify: `Core/HelperPaths.cs` (`AddinCertificate`, `AddinKey`), `Core/Ui/IWindows.cs` (`Task<bool> SetUpOfficeAsync()`), `Healdan.Helper/Windows/AppWindows.cs`, `Healdan.Helper/Tray/TrayMenu.cs` (`Set Up Microsoft Office…` while it isn't), `Healdan.Helper/App.axaml.cs` (`AfterFirstStart`)
- Test: `Healdan.Helper.Core.Tests/Addin/AddinCertificateTests.cs`

**Interfaces:**
- Produces:

```csharp
public interface ICertificateTrust
{
    bool IsTrusted(X509Certificate2 cert);            // X509Chain.Build with the system's trust, user settings included
    Task<bool> TrustAsync(string certPemPath, CancellationToken ct); // the security CLI; false when the user cancels
}

public sealed class AddinCertificate(HelperPaths paths, TimeProvider clock)
{
    public X509Certificate2 LoadOrCreate();           // makes it when missing or within 30 days of expiry
    public X509Certificate2 ForServer();              // with its private key, as SslStream needs on macOS
    public bool NeedsSetUp(ICertificateTrust trust);  // missing, expiring or untrusted
}
```

On macOS, `SslStream` needs the key re-imported: `X509Certificate2.CreateFromPemFile(cert, key)`, exported as PKCS#12 and loaded again (`X509CertificateLoader.LoadPkcs12`).

- [ ] **Step 1: Write the failing tests**

`AddinCertificateTests`:
- `MakesTheCertificateTheSpecDescribes`: subject, SAN `localhost` and `127.0.0.1`, `CA:FALSE` critical, server authentication, 800 days; the key file is mode 600.
- `KeepsAnExistingCertificate`, and `RenewsWithinThirtyDaysOfExpiry` (with a fake clock).
- `ServesTls`: a `TcpListener` with `SslStream.AuthenticateAsServerAsync(ForServer())`, and an `HttpClient` that accepts exactly this certificate (`ServerCertificateCustomValidationCallback` comparing thumbprints), completes a TLS handshake on both runners.
- `NeedsSetUpUntilTrusted`: a fake `ICertificateTrust`.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~AddinCertificate"`
Expected: FAIL.

- [ ] **Step 3: Write `AddinCertificate` and `MacCertificateTrust`**

- `CertificateRequest` with RSA 2048 and SHA-256, the extensions in Global Constraints, `CreateSelfSigned(now - 1 day, now + 800 days)`; PEM files written through a temporary file and `File.Move`, the key with `UnixCreateMode` 600.
- `MacCertificateTrust.TrustAsync` runs `/usr/bin/security add-trusted-cert -r trustRoot -p ssl -k <home>/Library/Keychains/login.keychain-db <cert>` with `ArgumentList` (no shell); macOS asks for the password. `IsTrusted` builds an `X509Chain` with the default policy and returns true when it builds without errors.

- [ ] **Step 4: The window, the menu and the first start**

- **`SetUpOfficeWindow`**, in the helper's style (`HelperStyles.axaml` groups):
  - a heading "Set Up Microsoft Office", and three rows, each an icon, a title and a muted note:
    - "Trust Healdan Helper's certificate": "macOS asks for your password once. Word, Excel and PowerPoint use it to reach Healdan Helper on this Mac."
    - "Allow Healdan Helper to control each app": "The first time it saves a document in Word, Excel or PowerPoint, macOS asks. Choose Allow."
    - "Grant each app the Copies folder": "The first time an app saves into Healdan Helper's Copies folder, it asks. Click Select…, go up one level, select Copies, and click Grant Access."
  - Not Now, and Set Up (the default), which runs `TrustAsync` and shows a tick, or the error.
- **When:** `AfterFirstStart` shows it once when Word, Excel or PowerPoint is installed and `NeedsSetUp` is true. `settings.json` gains `OfficeSetUpOffered` (bool), so it isn't shown again unasked. The tray menu shows "Set Up Microsoft Office…" while `NeedsSetUp`, and Settings has a row (Task 8).

- [ ] **Step 5: Run the helper's tests, and check by hand on the Mac**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

By hand, with a Debug helper (`HEALDAN_HELPER_DEV_ROOT=/tmp/hh`): Set Up asks for the password; `security dump-trust-settings` lists "Healdan Helper (this Mac)" with policy SSL; the menu item goes.

- [ ] **Step 6: Docs, then commit** — "Make the helper's own certificate, and set up Microsoft Office"

The User Guide's `installing-the-helper/` gains `## Setting Up Microsoft Office`, with the window's three rows and a screenshot.

---

### Task 7: The add-in's port and pairing

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Addin/AddinServer.cs`, `AddinHandler.cs`, `AddinPorts.cs`, `Pairing.cs`, `AddinKeys.cs`
- Create: `clients/helper/Healdan.Helper/Windows/PairingWindow.axaml(.cs)`
- Modify: `Core/Ui/IWindows.cs` (`void ShowPairingCode(string app, string code, DateTimeOffset expires)`, `void ClosePairingCode()`), `Core/Session/HelperSession.cs` (start and stop the add-in's server beside the channel, at :882 and :957), `Core/HelperPaths.cs` (`AddinKeys`), `Healdan.Helper/Windows/AppWindows.cs`
- Test: `Healdan.Helper.Core.Tests/Addin/AddinServerTests.cs`, `PairingTests.cs`, `AddinHandlerTests.cs`

**Interfaces:**
- Consumes: Task 5's `OfficeFiling`, Task 6's `AddinCertificate.ForServer()`, M3's `ChannelHandler` (for `open-dialog` and `properties`) and `CopyCycle.StateOf`.
- Produces: the routes table under Global Constraints; `AddinServer.Port` (the port it took, or null).

```csharp
public sealed class Pairing(HelperPaths paths, IWindows windows, TimeProvider clock)
{
    public string Start(string app);                                   // pairingId; shows the code
    public PairResult Finish(string pairingId, string code);           // Key, WrongCode(triesLeft), Expired
    public string? AppFor(string key);                                 // null when unknown
    public IReadOnlyList<PairedApp> Paired();                          // for Settings
    public void Forget(string app);
}
```

- [ ] **Step 1: Write the failing tests**

`PairingTests` (fake clock, `FakeWindows`):
- `ACodeIsSixDigitsAndShown`; `TheRightCodeGivesAKey` (and `AppFor(key)` is the app); `AKeyIsStoredOnlyAsItsHash` (the file holds no key, and is mode 600).
- `ThreeWrongCodesCancel`; `ACodeExpiresAfterTwoMinutes`; `OnePairingAtATime` (a second `Start` cancels the first and its window).
- `ForgetRemovesTheAppsKeys`; `KeysSurviveARestart`.

`AddinServerTests` (a real `AddinServer` on a free test port, with the test certificate and an `HttpClient` pinned to it):
- `HelloNeedsNoKey`; `TheFirstFreePortIsTaken` (with 47615 held by another listener, it takes 47616).
- `WrongHostIsRefused`: `Host: healdan.example:47615` → `403 bad-host` (DNS rebinding).
- `WrongOriginIsRefused` (Review Focus 5): `Origin: https://evil.example` → `403 bad-origin`, no CORS headers; a request with no `Origin` → `403 bad-origin`.
- `PreflightAnswersCors`: `OPTIONS` with the server's origin and `Access-Control-Request-Private-Network: true` → `204` with every header in Global Constraints.
- `WithoutAKeyIsNotPaired`; `AnOldProtocolIsAskedToUpdate` (`X-Healdan-Protocol: 2` → `426 update-healdan-helper`).
- `SignedOutIsSaid`: keyed routes answer `409 signed-out` while the session is signed out.
- `BodiesOverTheCapAreRefused`: 150 MiB + 1 → `413 too-large`, read no further.
- `NothingSecretIsLogged`: after pairing and a keyed call, the log holds neither the key nor the code.

`AddinHandlerTests`:
- `StateNamesTheFrontWindowsCopy`: `state` with an empty `url` answers the front window's copy, with `docRef` (`#n v1.1`) for Insert Doc ID.
- `NotACopyIsSaid`: `new-version` on a window that isn't a copy → `409 not-a-copy`.
- `SaveNewAndFileGoThroughOfficeFiling`.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~Addin"`
Expected: FAIL.

- [ ] **Step 3: Write the server, the handler and pairing**

- **`AddinServer`**: a `TcpListener` on `IPAddress.Loopback` and the first of `AddinPorts.All` it can bind; each accepted connection gets an `SslStream` (TLS 1.2 and 1.3, `ForServer()`), reads one request (request line, headers to an empty line, a `Content-Length` body up to the cap), answers, and closes. Checks run in this order: `Host`, `Origin` (against the session's server, through `ServerAddress`), `OPTIONS`, the protocol, the key (by `Pairing.AppFor`, compared with `CryptographicOperations.FixedTimeEquals` on the hashes), then the route. A malformed request is `400 bad-request`.
- **`AddinHandler`** maps the routes to `Pairing`, `OfficeFiling`, `ChannelHandler.HandleAsync("open-dialog" | "properties", …)` with the copy `CopyForAsync` found, `CopyCycle.MarkNextVersion` (plus `SaveAsync` when the document has unsaved changes, spec §5.6), and `CopyCycle.StateOf`.
- **`PairingWindow`**: "<App> wants to connect to Healdan Helper", the code in large digits (`### ###`), "Type this code in the Healdan pane in <App>. It expires in 2 minutes.", and Cancel. It closes on success, on cancel and on expiry.

- [ ] **Step 4: Start it with the session**

`HelperSession.BeginAsync` starts the `AddinServer` after the channel (`HelperSession.cs` ~882) when `AddinCertificate` exists and is trusted, logs the port, and stops it in `Stop` (:957). Without a certificate it doesn't start; the add-in's pane then says to run Set Up Microsoft Office.

- [ ] **Step 5: Run the helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 6: Docs, then commit** — "Serve the Office add-in over HTTPS on loopback, after pairing"

`developer/desktop-clients.md` gains `## The Add-in's Port`: the routes table, the ports, the checks, pairing and the certificate. The User Guide's `installing-the-helper/` gains `## Pairing Word, Excel and PowerPoint`, with the pairing window's screenshot.

---

### Task 8: Settings, and the package

**Files:**
- Create: `clients/helper/Healdan.Helper/Platform/DefaultApps.cs`
- Modify: `Healdan.Helper/Windows/SettingsWindow.axaml(.cs)` (kind rows with a choice; a "Microsoft Office" group: Set Up and Paired Apps), `Healdan.Helper/Tray/TrayMenu.cs`
- Modify: `clients/helper/Healdan.Helper.Core.Tests/App/IconsTests.cs` (`SpecNames`, for any new Tabler icon), `clients/icons/tabler/` and `HelperIcons.axaml` (`python3 clients/icons/write-helper-icons.py`)
- Test: `Healdan.Helper.Core.Tests/Settings/AppChooserTests.cs` (additions)

**Interfaces:**
- Consumes: Task 3's `AppChooser` and `HelperSettings.Kinds`, Task 6's `NeedsSetUp`, Task 7's `Pairing.Paired()` and `Forget`.
- Produces: `DefaultApps.For(string extension)`: `"Microsoft Office"`, `"LibreOffice"` or null, from the system's default app for that extension (`NSWorkspace URLForApplicationToOpenContentType:` with `UTType typeWithFilenameExtension:`, through `MacPlatform`'s `objc_msgSend` helpers).

- [ ] **Step 1: Write the failing tests**

`AppChooserTests` (additions):
- `TheSystemDefaultComesFromDefaultApps`: with `DefaultApps.For` giving `Microsoft Office` for `docx`, a fresh install opens `.docx` in Microsoft Office; `null` (an app that's neither) gives LibreOffice.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~AppChooser"`
Expected: FAIL.

- [ ] **Step 3: Settings**

- **"Open Each Kind of File In":** each kind's row has a pop-up of the installed apps (LibreOffice, Microsoft Office), showing the chooser's current answer; choosing one writes `Kinds`. Drawings shows LibreOffice only. The footnote says OpenDocument files always open in LibreOffice.
- **A "Microsoft Office" group:** a Set Up row (its status, and "Set Up…" while `NeedsSetUp`), and Paired Apps (each app and when it was paired, with Forget).
- The tray menu's "Set Up Microsoft Office…" sits after "Open from Healdan…", while `NeedsSetUp`.

- [ ] **Step 4: Run the tests, package, and check the signed build by hand**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

Then `clients/helper/packaging/mac/package.sh 0.4.0-rc1` with `SIGN_IDENTITY` set, and on the Mac: `codesign -d --entitlements - "Healdan Helper.app"` shows `allow-jit` and `automation.apple-events`; the first Office save shows "Healdan Helper wants access to control Microsoft Word" with the `Info.plist` text.

- [ ] **Step 5: Docs, then commit** — "Choose Microsoft Office per kind of file, and package the Automation permission"

The User Guide's `installing-the-helper/` `## Settings` describes the per-kind choice, the Microsoft Office group and Forget. Known Limitations gains the two-people-on-one-Mac limitation (correction 6).

---

### Task 9: The add-in's logic

**Files:**
- Create: `clients/office/web/healdan.js` (the logic: no Office, no DOM), `clients/office/test/healdan.test.js`
- Modify: `.github/workflows/clients.yml` (an `addin` job, and `Clients` needs it)

**Interfaces:**
- Produces, as `globalThis.Healdan` in the pages and `module.exports` under Node:

```js
// A client for the helper's port. fetch and storage are passed in, so tests fake them.
createHelperClient({ fetch, storage, origin, host /* "Word" | "Excel" | "PowerPoint" */ }) → {
  find(),                 // probes AddinPorts in order with GET /v1/hello; remembers the first that answers
  call(route, body),      // keyed POST; throws HelperError with .code and .message for the pane
  pairStart(), pairFinish(pairingId, code),
  paired()                // a key for this host is stored
}
messageFor(code)          // the pane's text for each error code, and for "unreachable" and "no-office-on-the-web"
saveToHealdan({ client, office }), saveAsNewDocument(...), saveAsNewVersion(...), properties(...),
openFromHealdan(...), insertDocId(...)   // the six buttons' flows, over an `office` object Task 10 implements
```

The ports are `[47615, 47616, 47617, 47618]`; the key is stored as `healdan.key.<host>`, and the port as `healdan.port`.

- [ ] **Step 1: Write the failing tests**

`healdan.test.js` (`node:test`, `node:assert`), with a fake `fetch` and a `Map` for storage:
- `find tries each port in order and remembers the one that answers`.
- `an unpaired button asks to pair first` (the flow answers `{ needsPairing: true }` and makes no other call).
- `pairing stores the key per host`; `a wrong code says how many tries are left`.
- `every call carries the protocol and the key`.
- `saveToHealdan`: `save-new` (`asNew: false`) → on `save`, `office.readBytes()`, then `office.setMark(id)`, then `file` with the ticket, mark and bytes → `office.removeMark(id)` in every outcome; on `cancel` nothing else; on `local`, the pane's message to use Word's own Save As.
- `insertDocId`: `state` → `office.insertDocId(docRef)`; a document not in Healdan inserts an empty field and says so.
- `messages`: `signed-out`, `not-paired`, `update-healdan-helper`, `not-a-copy`, `unreachable` (no port answers: "Healdan Helper isn't running, or Microsoft Office isn't set up in it") and `no-office-on-the-web` each have a message.

- [ ] **Step 2: Run them to see them fail**

Run: `node --test 'clients/office/test/*.test.js'`
Expected: FAIL.

- [ ] **Step 3: Write `healdan.js`**

A classic script: `(function (root) { … root.Healdan = api; if (typeof module !== "undefined") module.exports = api; })(typeof globalThis !== "undefined" ? globalThis : this);`. No `import`, no npm.

- [ ] **Step 4: CI**

`clients.yml` gains `addin`, named "Office add-in (ubuntu-24.04)": `if: needs.changes.outputs.clients == 'true'`, 10 minutes, checkout, `actions/setup-node@v5` with Node 22, `node --test 'clients/office/test/*.test.js'`. `Clients` gains it in `needs` and in its pass rule (succeeded, or skipped with `CLIENTS=false`).

Run: `node --test 'clients/office/test/*.test.js'`
Expected: PASS.

- [ ] **Step 5: Commit** — "Write the Office add-in's logic, tested under Node"

---

### Task 10: The add-in's pages, and deploying it

**Files:**
- Modify: `clients/office/web/taskpane.html`, `clients/office/web/commands.html` (replacing Task 1's placeholders)
- Create: `clients/office/web/office.js` (the Office.js glue), `clients/office/web/taskpane.js`, `clients/office/web/commands.js`, `clients/office/web/healdan.css`
- Docs: `site/content/install/office-integration.md` (`### Deploying the Add-in`), `site/content/user/office/` (the section becomes "Working in Office Apps"; `_index.md`, `installing-the-helper/`, `opening-documents/`, `doc-id-footer.md`, `when-things-go-wrong/`), `site/content/install/known-limitations.md`

**Interfaces:**
- Consumes: Task 9's `Healdan`.
- Produces: the `office` object Task 9's flows use:

```js
{
  host,                        // Office.context.host as "Word" | "Excel" | "PowerPoint"
  onTheWeb,                    // Office.context.platform === Office.PlatformType.OfficeOnline
  url(),                       // Office.context.document.url || ""
  readBytes(),                 // getFileAsync(Compressed, { sliceSize: 4194304 }), every slice, as base64
  setMark(id), removeMark(id), // Word: document.properties.customProperties; Excel: workbook.properties.custom;
                               // PowerPoint: presentation.properties.customProperties
  insertDocId(docRef)          // Word: the property, then footer (section 1, primary) getRange("End").insertField("End", "DocProperty", '"Healdan Doc ID"', true) and updateResult()
}
```

- [ ] **Step 1: Write the pages**

- `commands.html` loads `office.js` from the CDN, then `healdan.js`, `office.js` (the glue) and `commands.js`. `commands.js` defines the six functions on `window` and with `Office.actions.associate` after `Office.onReady`, as the trial did. Each runs its flow and calls `event.completed()`. When a flow needs the pane (pairing, a message), it opens it with `Office.addin.showAsTaskpane()` where SharedRuntime 1.1 is supported, and otherwise shows the message through `Office.context.ui.displayDialogAsync` with `taskpane.html?message=<code>`.
- `taskpane.html` shows pairing (the six-digit field and Pair), the last message, and the helper's status. It has no other buttons.
- The pane says "Healdan's buttons work in the desktop apps" when `onTheWeb`.

- [ ] **Step 2: Check on the Mac, against a stack from this branch**

Upload the manifest from a local stack's `/api/addin/manifest.xml` (with its address trusted by the Mac; or the owner's server once Task 12 upgrades it) and, in Word, Excel and PowerPoint:
- the tab shows the six buttons (five in Excel and PowerPoint);
- the first press pairs; Save to Healdan files a new document; Save as New Version, Properties, Open from Healdan and Insert Doc ID work;
- the pane's console shows no Content Security Policy errors from `AddinResource`'s header. If Office.js needs another source, add it there, with a test, and say so in the PR.

- [ ] **Step 3: Docs**

- The Install manual's Office Integration page: `### Deploying the Add-in`, the trial's steps (admin.microsoft.com → Settings → Integrated apps → Upload custom apps → Office Add-in → upload the manifest file downloaded from `https://<server>/api/addin/manifest.xml` → assign users → finish), that it can take hours to appear (35 minutes in the trial), and that a new manifest version needs Update add-in.
- The User Guide's office section: retitled "Working in Office Apps"; Word, Excel and PowerPoint in each page as spec §11.3 lists; screenshots of the Healdan tab, the pane and the pairing window (demo firm).
- Known Limitations: "LibreOffice on macOS only" becomes "LibreOffice and Microsoft Office on macOS only"; and new bullets: Office's own Save, Save As and Open aren't taken over; Insert Doc ID is Word's only; Word for the web; the title waits for unsaved changes, and PowerPoint's doesn't move on; OpenDocument files open in LibreOffice; Open Recent keeps entries for old copies (spec §5.12).

- [ ] **Step 4: Run the checks, then commit** — "Add the Office add-in's pages, and document deploying it"

Run: `node --test 'clients/office/test/*.test.js'`, `mvn -B -ntp -pl healdan-api test -Dtest=AddinResourceTest`, and the docs site's checks (`python3 -m unittest discover -s .github/scripts/tests`, `hugo --source site --panicOnWarning`, `python3 .github/scripts/check-site-sources.py`, `python3 .github/scripts/check-site-links.py`).
Expected: PASS.

---

### Task 11: Records

**Files:**
- Modify: `CLAUDE.md` (:37–52 the commands: `node --test 'clients/office/test/*.test.js'`, `--filter "Category=MacOffice"`; :69–75 `code-changed.sh` and `Clients`; :164–172 the clients: the add-in, `IOfficeScripting`, the add-in's port and its certificate, M3.5's spec)
- Modify: `docs/reference/logicaldoc-ce-capability-map.md` (§5.1: Microsoft Office through the add-in and the helper)
- Modify: `docs/superpowers/specs/2026-10-30-m3-5-microsoft-office-design.md` (this plan's corrections, marked "changed in the build"; the status line), `docs/ROADMAP.md` (M3.5's row and section: built, acceptance next)
- Modify: `site/content/developer/continuous-integration.md`, `tests.md` (the add-in job; `code-changed.sh`'s `clients/office/`; the `MacOffice` tests)

- [ ] **Step 1: Write them**, from what Tasks 1–10 actually built; each departure from the spec gets "changed in the build" in the spec.
- [ ] **Step 2: Run the docs site's checks** (as Task 10's Step 4). Expected: PASS.
- [ ] **Step 3: Commit** — "Record M3.5's build"

---

### Task 12: Release and the owner's acceptance

Not code. In order:

- [ ] **Step 1:** tag `helper-v0.4.0` on master once PRs A–E are merged; `clients.yml` packages, signs, notarises and attaches the disk image.
- [ ] **Step 2:** the owner upgrades the semi-production server to master (the persistent host's `git pull` and rebuild) and sets `LOGICALDOC_PUBLIC_URL` if it isn't set.
- [ ] **Step 3:** the owner removes the trial's add-in from Integrated apps, and the trial certificate's trust (`security remove-trusted-cert` with the trial's path), then uploads `https://<server>/api/addin/manifest.xml`, assigned to themselves.
- [ ] **Step 4:** the owner installs Healdan Helper 0.4.0, runs Set Up Microsoft Office, and runs spec §12.2's walkthrough, against the server over HTTPS, as a non-admin user. Each row is recorded in the plan's decision log; failures become a punch list.
- [ ] **Step 5:** the roadmap's M3.5 row becomes **done**, with the date and the PRs.

## Decision log

Every call made while building that the plan or the spec didn't settle, in order. "When" is the task or review that raised it; "Where" is the spec section or file it changed. Tasks 1 to 4 ran on 2026-10-30, and the rest on 2026-10-31 (the commits' dates).

| Date | When | Decision | Why | Where |
|---|---|---|---|---|
| 2026-10-30 | Pre-flight (P1) | Task 5 passes `window.WindowName`, a string, as `DocumentRef`'s third field | the plan's `window` was the whole `FrontDocument`, and the interface takes the window's name | spec §5.2 |
| 2026-10-30 | Pre-flight (P2) | `HelperSession` takes an `ICertificateTrust` as a constructor parameter, and `App.axaml.cs` passes `MacCertificateTrust` | the core can't reference the app's `Platform/` folder | spec §10 |
| 2026-10-30 | Pre-flight (P3) | Task 2's by-hand `MacOffice` tests are written and compiled but not run by the implementer | they open Word, Excel and PowerPoint on the owner's screen and may prompt | spec §12.3 |
| 2026-10-30 | Pre-flight (P4) | The by-hand checks that need the owner (Set Up's password prompt, the signed package, Office against an HTTPS server) move to one owner session after Task 10 | implementers did everything else in those steps; the cost is finding later whatever they would have caught, notably whether the Content Security Policy blocks Office.js | spec §12.3 |
| 2026-10-30 | Pre-flight (P5) | All tasks are built on one branch; the plan's pull requests A to E are cut from its commits at the end, and nothing is pushed without the owner's go-ahead | pushing is outside the worktree | spec §10 |
| 2026-10-30 | Pre-flight (P6) | Docs text ships in each task; the screenshots (the Healdan tab, the Healdan window, pairing, Set Up, Settings) wait for the owner's session | as M3's did | spec §11.3 |
| 2026-10-30 | Task 1 review | The Content Security Policy keeps `https://*.microsoft.com`, `*.office.com` and `*.office.net` in `connect-src` beside the helper's ports | Office.js is Microsoft's script and reaches Microsoft's services from our page; spec §7 said only the helper's ports | spec §7 |
| 2026-10-30 | Task 2 review | `locate` tries the exact path first, over every document, in the spellings C# passes (the path, and its `/private` twin); a file-name match comes next and only for a document in `Copies`, which the helper passes in | the first draft let a name match beat an exact path, in any folder | spec §5.2 |
| 2026-10-30 | Task 2 review | `Word.SetDocId` reads `saved` before it sets the property | the plan's script read it after, and so lost the document's state | `SetDocId` script |
| 2026-10-30 | Task 2 review | The by-hand command is `HEALDAN_MAC_OFFICE=1 dotnet test … --filter "Category=MacOffice"`, and CLAUDE.md, the Developer Guide and this plan say so | without the variable a plain `dotnet test` on a Mac with Office would open its apps; `EveryScriptCompiles` needs none | spec §5.2, CLAUDE.md |
| 2026-10-30 | Task 3 | `CopyNames.OfficeOwnerNames` is the union of M3's Word forms and every program's `OfficePrograms.OwnerNames` | a superset never misses Word's owner file | spec §5.2 |
| 2026-10-30 | Task 3 | `CopyCycle.AdoptNewAsync` takes an optional app and records LibreOffice by default; `OfficeFiling` passes Microsoft Office | its other caller is the extension's `file` route, whose window LibreOffice holds | spec §5.5 |
| 2026-10-30 | Task 3 | Task 8 widens the installed-app detection with LaunchServices | it adds that plumbing for `DefaultApps` anyway | spec §5.10 |
| 2026-10-30 | Task 4 | The rename keeps the same `Session` and changes its `Path` under the lock | the rename runs inside that session's save job; a second session would leave a second queue and stale waiters | spec §5.4 |
| 2026-10-30 | Task 4 | The close watcher lets go of the old name before the rename call | Word moves its owner file during Save As, so the plan's order read every rename as a close and checked the document in | spec §5.4 |
| 2026-10-30 | Task 4 | `ICycleEvents.Reopened` (a default no-op) and `HelperSession`'s handler | the menu must drop "(closed unexpectedly)" once Word has the copy again | spec §5.14 |
| 2026-10-30 | Task 4 review | The crash window is dismissed once the save-back works; Send and Check In and Bring refuse a copy that is no longer crashed | the window stayed live after recovery, and its Send and Check In would check in an open document | spec §5.14 |
| 2026-10-30 | Task 4 review | After a rename that failed or timed out, a file or owner file at the new name is followed as a rename with changed content; before acting on an Office copy's close, an unfinished rename is followed instead; "treat as unsaved" otherwise stands | a rename Word completed anyway was checked in as a close, orphaning its window | spec §5.4 |
| 2026-10-30 | Task 4 review | The save-back asks `IsOpen` by path for up to 30 seconds before `SaveAs`, stays crashed until it works, and tries again; the close watcher lets go meanwhile | a one-shot `SaveAs` could save the front document over the copy | spec §5.14 |
| 2026-10-31 | Task 5 | `409 wrong-window` means the mark is set but not shown for 3 seconds while the front window's path or name is no longer the ticket's; PowerPoint decides by the ticket's window name | the seam can't read another document's mark directly | spec §5.5 |
| 2026-10-31 | Task 5 | `OfficeFiling`'s route errors are one exception type carrying the code | for the port to map | spec §5.8 |
| 2026-10-31 | Task 5 | The ticket carries the folder, name and properties (`NewDocument`); PowerPoint isn't waited for a mark; Save as New Document is refused `wrong-window` at `save-new` when `url` names another copy; `Adopted(path, true)` still sends when the cycle already took the write | all within the spec | spec §5.5 |
| 2026-10-31 | Task 5 | The add-in sets no mark in PowerPoint | PowerPoint never shows a mark to AppleScript, and the mark would stay in its files | spec §5.5 |
| 2026-10-31 | Task 5 | The port turns a `file://` address into a path before looking for a copy | Office may give a file URL | spec §5.8 |
| 2026-10-31 | Task 5 review | The fingerprint is taken first, as the bytes arrive | the plan's order folded an edit made during the mark wait into the filed bytes | spec §5.5 |
| 2026-10-31 | Task 5 review | `SaveAs` never falls back to the front window, for any caller; the crash save-back has the same guarantee | the fallback could save another document as the copy | spec §5.2 |
| 2026-10-31 | Task 6 | `MacCertificateTrust.IsTrusted` uses Security.framework's TLS server evaluation for `127.0.0.1`, not `X509Chain.Build` | `X509Chain` can't see a trust setting limited to SSL: it reported the trial's trusted certificate as untrusted | spec §5.13 |
| 2026-10-31 | Task 6 | "Set Up Microsoft Office…" shows only on a Mac with Office installed, while Set Up is needed | a Mac without Office has nothing to set up | spec §5.13 |
| 2026-10-31 | Task 6 review | `AddinCertificate.ForServer` serves an existing, unexpired certificate and never makes or renews one | renewal belongs to Set Up, or the port could mint an untrusted certificate under a working one | spec §5.13 |
| 2026-10-31 | Task 7 | Nine wrong pairing codes in a row pause pairing for 10 minutes (`403 too-many-tries`) | another local user could otherwise start pairings and guess a six-digit code in under an hour | spec §5.9 |
| 2026-10-31 | Task 7 review | The port lives as long as the helper, signed in or not; signed out, keyed routes answer `409 signed-out`; `hello`'s server and the Origin check read `settings.json` | the pane should say why, and a port tied to a sign-in is unreachable exactly then | spec §5.8 |
| 2026-10-31 | Task 7 review | The port opens while the certificate exists, is unexpired and trusted; Set Up's renewal is swapped in on the same port | a working certificate shouldn't take the add-in down in its last 30 days | spec §5.8 |
| 2026-10-31 | Task 7 | `hello` keeps the check order, so with no server in `settings.json` it answers `bad-origin` and `"server": null` can't be reached | an add-in can't be checked against a server the helper has never had | spec §5.8 |
| 2026-10-31 | Task 7 | The add-in keeps its key in `localStorage` in Office's web view | that store is per app, inside the app's container; the key is the only defence against another page on the server's origin | spec §5.9 |
| 2026-10-31 | Task 7 | The pane's message for `signed-out` reads "signed out, or still starting up" | a signed-in helper answers `signed-out` until its documents are published | spec §6.3 |
| 2026-10-31 | Task 8 | The add-in's test command is `node --test 'clients/office/test/*.test.js'`, quoted | Node 21 and later read `--test`'s arguments as file patterns, so a folder fails | spec §6.1, CLAUDE.md |
| 2026-10-31 | Task 8 | Locally, the add-in's tests run under Visual Studio Code's bundled Node (`ELECTRON_RUN_AS_NODE=1 "/Applications/Visual Studio Code.app/Contents/MacOS/Code" --test …`, Node 24.18.1); CI runs Node 22 | no Node is installed on the Mac, and none was downloaded | |
| 2026-10-31 | Task 9 | `saveAsNewVersion` asks `state` first; `insertDocId` passes an empty string for a document not in Healdan; `pairFinish` refuses a code that isn't six digits itself; each port probe times out after 3 seconds | a read-only copy and a document not in Healdan need messages of their own; a typo shouldn't cost a try; a port held by something silent mustn't hang the window | spec §5.6, §5.7, §5.9 |
| 2026-10-31 | Task 9 (9b) | Any keyed route still running after 20 seconds answers `202 {"pending": id}`, and `POST /v1/result {"id"}` waits up to 20 seconds more for its outcome, which is kept for 10 minutes, answered once, to its own key | WebKit is likely to give up on a request idle for about a minute, while the windows wait for the user and `file` can wait 300 seconds; a dropped answer would lose a ticket | spec §5.8 |
| 2026-10-31 | Task 9b | A missing `X-Healdan-Protocol` stays `400 bad-request`; `426` is for a protocol the helper doesn't speak | the brief was wrong; the existing check is pinned by a test | spec §5.8 |
| 2026-10-31 | Task 9b | Accepted: slow-route tests are gate-driven; the cap of 64 counts running and uncollected entries; an internal `WhenFinished` seam; the add-in retries a failed `result` once; a pending route keeps its session across a sign-out | a 3-times delay is racy; an outcome is answered once, so a repeat can't act twice | spec §5.8 |
| 2026-10-31 | Task 9b | Once `call` has followed a `202`, any failure to get the outcome is `lost-result` ("check the document in Healdan before trying again"), not the route's own "try again" | a pending `file` may have finished, and trying again could file twice | spec §5.8 |
| 2026-10-31 | Task 10 | The Office.js calls are in `office-glue.js`, not `office.js` | Office.js may look for its own script tag by that name, and a second one could confuse it | spec §6.1 |
| 2026-10-31 | Task 10 | The window polls `ping`, never `state`; Insert Doc ID updates an existing field rather than adding a second | `state` makes the helper drive the app at every check; the User Guide says pressing it twice leaves one field | spec §6.3, §5.7 |
| 2026-10-31 | Task 10 review | Pairing happens in a `displayDialogAsync` window, which hands the key to the button with `messageParent`; the button's press isn't completed until the dialog closes | the documented way for a dialog to talk to a function command; the key doesn't depend on shared storage | spec §5.9 |
| 2026-10-31 | Task 10 review | Office's error text goes from the button to the window with `messageChild`, never in the window's address | the server's access log could hold file names | spec §6.2 |

## Follow-ups

Filled in as the build found them; left open when Tasks 1 to 11 were built. None is a known failure. The reviews' style points are left out.

**For the owner's session** (spec §12.3 has the checks themselves)
- `state` may ask Office for its front window by AppleScript and so block behind a Grant File Access dialog. It is read-only, and the window polls `ping`, but a button's `state` call can wait (`AddinHandler.cs`).
- `commands.js`'s `displayDialogAsync` callback is unguarded: a throw there leaves `event.completed()` uncalled and the button busy. Wrap it, calling the failure path.
- There is no fallback timer if Office never raises `DialogEventReceived` after the user closes the window.
- An origin in another form (a port, a trailing slash) drops the paired message silently, and the manifest declares no DialogApi 1.2 requirement.
- Accessibility: a `display:none` live region may not announce when it is shown; `aria-busy` is on all of `<main>` for up to 12 seconds; focus moves from the message to the code field at once; the message and the status repeat each other.
- `MacCertificateTrust`'s cancel, failure, kill and timeout paths are untested; `OfficeSetUp.TrustAsync` re-checks `NeedsSetUp` straight after `security` returns, with no retry for trustd's lag; renewal leaves the old certificate and its trust entry in the login keychain.
- The `check` icon's path data was written by hand in the release's format, not copied from Tabler 3.50.0; diff it against the real file.
- Docs: the Install page names "a helper version with Microsoft Office support" with no number, and Task 12 should say 0.4.0; `settings.png` and its alt text show the old Settings; the screenshots listed in spec §11.3 are not taken.

**Lost work and reachability, for the final review**
- Adopting a rename on the owner file alone lets `CopyStore.Moved` delete the old copy without checking it's confirmed (`CopyCycle.cs`, `CopyStore.cs`). The pre-rename check covers the file but not the owner names. Theoretical: no helper flow leaves a stray owner file.
- Until the save-back succeeds, the crash window's Send and Check In can still check in under Word's restored window.
- `Word.SetDocId` reads `saved`, updates fields over many Apple events, then sets `saved` true: a keystroke in between is marked saved and lost at close. It runs after every Word rename.
- An edit saved after the rename script's last fingerprint but before `Settled(newPath)` is taken as confirmed (tens of milliseconds); a modification-date check would close it.
- Word relaunched within one crash poll (5 seconds) of a Force Quit never registers as a crash, so its recovered window isn't saved back. A restored window open before the helper starts is treated as open and never saved back (pre-existing).
- A body with an escaped lone surrogate (`{"app":"\ud800"}`) passes UTF-8 validation and JSON parsing, then throws in `TryGetValue<string>` and answers `500` with a stack trace in the log, without a key (`AddinHandler.cs`). `result` maps it to `400`; the other routes should.
- `CopyCycleTests.PartialWriteNotSent` (M3's) failed once in a full run, then passed six times: possibly flaky.
- A Settings change mid-flight re-records a copy's app when `CopyStore.Create` runs again, so a read-only or ended copy still open in Word could move its close watch to LibreOffice. Narrow.

**The scripts and the seam**
- `OsaRunner` promises its errors never carry arguments, but `osascript`'s stderr can echo values.
- The HFS-path retry's bare `on error` also catches a timeout or a refusal, and retries for up to another 300 seconds, asking Grant File Access twice (`Word.Open`, `Word.Rename`, `Word.SaveAs`).
- `PowerPoint.Reopen` reopens the argument path, not the located presentation's own full name; reachable through the `Copies` name fallback. `MacOfficeScripting`'s `copiesFolder` is optional, so a caller that forgets it loses the name fallback silently.
- Window-name matching is the last step: a new document reusing a closed window's name, such as "Document1", during an upload could be saved as the copy. With neither a mark nor a window name, the window check passes on null equals null, and `SaveAs` then answers missing after filing.
- A failed `SaveAs` after Save as New Document leaves the original Closing with its watchers off. Save-new's refusals (no document open; a mismatched `url`) throw without a notice, where spec §5.5 step 4 says "with a notice".
- After a rename or reopen the new name is watched with `lockSeen: false`, so a close during the tail isn't seen until the program quits; `CloseWatcher` treats a null `LockWritten` as a reopen; the save-back's retry has no end while an owner file stays, at about seven `osascript` launches per 36 seconds.
- `MicrosoftOfficeApp.IsRunningFor` passes macOS process names, and `InstalledApps` checks macOS bundle paths, in the core; a Windows port would translate them. `CopiesNotice`'s text is macOS-specific, called above the seam.
- Detection is by bundle identifier but launch is by name (`open -a LibreOffice`), and LaunchServices may answer for an app in the Trash. The system default can't be recorded as an explicit choice without switching away and back.

**The add-in's port**
- No cap on connections at once; one 60-second window for TLS and the head; the body buffer is allocated at its declared length up front; refusals are logged without a rate limit, so a page in Safari can push history out of the 2 MiB log.
- An app pairing again leaves its old key valid until Forget. A slow request from one server's page across a sign-out and a sign-in to another can run on the other's cycle (60 seconds at most).
- A running port keeps serving a certificate past its expiry, and says "stays closed" in its log while open. `Dispose` disposes the certificate during handshakes. Signed in but not yet started also answers `signed-out`, while `ping` says `signedIn: true`.
- A `file://` address with a literal `%` in a name Office didn't escape would be decoded wrongly; the front window still decides then.
- An idempotent call (`ping`, `state`) isn't retried when the helper comes back on the same port. The remembered port skips `hello`, and `not-paired` from any port drops the key, which is within the two-people limitation; a failed `setItem` after pairing leaves a key the helper keeps.

**Server and docs**
- No test parses the served manifest as XML or checks that every `resid` has a `bt:*` id. `maven-resources-plugin` is unpinned in `healdan-api/pom.xml`. The blank `server.url` branch is untested, and the manifest varies by `Host` with no `Cache-Control: no-cache`.
- The Install pages omit that the proxy must pass `Host`, and that `X-Forwarded-Proto` is believed only from `LOGICALDOC_TRUSTED_PROXIES`.
- When Things Go Wrong's message table omits `too-large`, `too-many-pending`, `no-ticket`, `mark-left`, `storage-failed`, `internal-error` and `bad-request`. Known Limitations should mention the remembered-port case under two people on one Mac.
- A Markdown file under `clients/office/` stays docs for `code-changed.sh`, which the Developer Guide and CLAUDE.md now say.
