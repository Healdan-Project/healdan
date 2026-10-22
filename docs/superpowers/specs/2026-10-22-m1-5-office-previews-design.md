# M1.5 "Office previews and thumbnails": design

- **Milestone:** M1.5 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-10-22
- **Status:** approved by the owner on 2026-10-22. A builder check by a reviewer who hadn't seen the conversation found that `ConvertPdf` never converts (§5.6) and raised the two questions the owner answered in §4.1 and §10.
- **Inputs:**
  - the roadmap's M1.5 section;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md);
  - a short design conversation with the owner on 2026-10-22;
  - a survey of this repository at `2027659be`. Line numbers drift;
  - measurements in a throwaway container on the runtime base image (§3.2).

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks Claude's proposal. The owner heard the calls in §4–§7 played back in the design conversation and said "looks right", except §5.6, which came out of the builder check afterwards. Anything unmarked follows from those two, or is detail the builder can change without changing what gets built.

## 1. Intent

Word, Excel and PowerPoint files preview in the browser and get thumbnails, as PDFs and images already do. (owner)

**What a preview is for.** A preview is a quick check that this is the expected document, before you open, print or email it. It isn't a reading view. (owner) Two things follow:

- **Speed beats fidelity.** The preview should be ready when you look. A layout that's recognisably the document is enough; it doesn't have to match Word pixel for pixel.
- **The first page matters most.** The thumbnail shows it, and it's usually where you recognise the document.

## 2. Goals, non-goals, anti-goals

### Goals (M1.5 is done when)

On the Docker stack, signed in as an ordinary non-admin user:

1. **New documents.** Upload a `.docx`, an `.xlsx` and a `.pptx` into a matter folder. Within a minute, without opening anything first, each one previews as its pages in the preview panel and shows a first-page thumbnail wherever the old UI shows one: the documents grid's tile view and the thumbnail on the document's properties tab.
2. **On the spot.** A document whose background conversion hasn't run yet still previews when you open it. You wait a moment; you don't get "not available".
3. **Versions.** After checking in a new version, the preview shows the new version. Older versions in the Versions tab preview as they were.
4. **Documents already in Healdan.** After upgrading an M1 install, its existing Office documents get previews and thumbnails without anyone doing anything. That includes the few that have a "not available" page cached from before the upgrade (§3.1).
5. **Bad files.** A corrupt or password-protected file shows the "not available" page. It doesn't hold up other documents' conversions, and it leaves no LibreOffice process behind.
6. **Safe conversion.** Converting a document never runs its macros and never fetches anything over the network (§5.3).
7. **Printing.** From the preview, a user with the Print permission can print the rendered document, as they can a PDF today.
8. **Markup shows.** A Word document that still has tracked changes and comments previews with the redlines and the comments visible, and so does its thumbnail (§4.1).

### Non-goals

- **Pixel-perfect rendering.** LibreOffice's rendering of Office files is good, not identical (§5.4).
- **Formats beyond §4.** Apple Pages, Numbers and Keynote, Visio, and drawing formats need more LibreOffice packages and convert less reliably. Adding one later is a line in the format list plus, for drawings, one package. (call)
- **Editing in the browser.** That's the backlog's Collabora/OnlyOffice item.
- **Converting other formats ahead of time.** PDFs and images keep CE's behaviour: their thumbnails are made the first time someone looks. The queue (§7) is built so they could be added later. (call)
- **A settings screen.** The converter has no on/off switch and no admin page (§5.5). (call)
- **New permissions.** Office previews follow the same permission rules as PDF previews today (§5.6).
- **CE's PDF buttons** stay hidden (§10). (owner)

### Anti-goals

- **A preview that isn't there when you look.** If the owner routinely waits on conversions, M1.5 has failed even though conversion works.
- **The server bogged down by a backlog.** Converting a few thousand existing documents mustn't make the UI slow for the person using it.
- **"Not available" that sticks.** A placeholder cached by an earlier failure must never outlive the problem that caused it.

## 3. Constraints

### 3.1 Facts from the code that shape the design

- **The preview never converts.** The GUI's PDF viewer (`logicaldoc-gui/war/prev/index.jsp`) loads `/convertpdf`, served by `ConvertPdf` (`logicaldoc-webapp/.../web/ConvertPdf.java`). Since LogicalDOC 8.8.1, `ConvertPdf` serves only a version whose file is itself a PDF. For anything else it logs "Unsupported format" and streams the "not available" page, without asking any converter. Healdan's `ConvertPdfTest#testFileTypeIsTheVersions` checks that refusal. So a converter alone doesn't make Office previews work: `ConvertPdf` needs a hook (§5.6).
- **Conversion.** `FormatConversionManager.getConverter` (`logicaldoc-core/.../core/conversion/FormatConversionManager.java`) looks up the converters registered for `<in>-<out>`, e.g. `docx-pdf`. It takes the first one that's enabled. If none is registered it falls back to `*-pdf`, which is `NotAvailableConverter`. If converters are registered but none is enabled, it also falls back to `NotAvailableConverter`.
- **Converters are enabled by property, and none is.** `AbstractFormatConverter.isEnabled` reads `converter.<SimpleClassName>.enabled` from `context.properties`, defaulting to `false`. The image's `context.properties` sets no `converter.*` key, so every CE converter is disabled and `getConverter` returns `NotAvailableConverter` for every type.
- **Conversions are cached.** `convertToPdf` stores the PDF as a resource of the document and file version (`conversion.pdf`). It skips converting when that resource already exists.
- **"Not available" can get cached.** `NotAvailableConverter` copies `/pdf/notavailable.pdf` into the output, and `convertToPdf` stores it like a real conversion. Previews never call `convertToPdf`, so M1's previews cached nothing. The routes that do call it can have cached the placeholder for an Office file before the upgrade: `CatchAllParser`, for the Office formats Healdan has no parser for (`dotm`, `xltx`, `xltm`, `pptm`, `potx`); ZIP export as PDF; PDF alias downloads; and automation. When a converter throws instead, nothing is cached.
- **Thumbnails.** `ThumbnailManager.getBuilder` picks a `ThumbnailBuilder` by file extension. No builder is registered for Office extensions and there's no `*` builder, so Office files get no thumbnail. Thumbnails are made the first time `/thumbnail` is asked for one (`thumb.png` for the preview tile, `tile.png` for the tile view), then cached. A failure is logged and nothing is cached.
- **The GUI needs no change.** `PreviewPanel.onAccessGranted` sends anything that isn't media, web content, email or DICOM to the PDF viewer, so Office files already go there. The viewer shows Print and Download buttons according to the user's permissions. Above `gui.preview.maxfilesize` MB the panel says the document is too big and doesn't load the viewer.
- **Permissions differ by route.** The viewer page and `/thumbnail` need Read and Preview. `ConvertPdf` also needs Download, since upstream's access-check fix of 2026-10-12. So today a user with Preview but not Download sees "not available" in the viewer even for a PDF.
- **Running programs.** `com.logicaldoc.util.exec.Exec` refuses commands not listed in `allowed-commands.txt` (see the Docker design spec and `docker/allowed-commands.txt`). On a timeout, `execPB` calls `destroy()` on the process it started and nothing else.
- **Indexing.** M1's parsers (`healdan-core/.../parser`) read `.docx`, `.xlsx` and `.pptx` text directly. Office formats without a parser fall to `CatchAllParser`, which calls `convertToPdf` and parses the PDF. Once M1.5's converter exists, that path starts producing text for those formats too, and warms their preview cache as a side effect.

### 3.2 Measured on the runtime base image

Measured on 2026-10-22 in a throwaway container from `tomcat:11.0-jre25-temurin` (Ubuntu 26.04.1, arm64, on the owner's Mac):

- Ubuntu's archive has LibreOffice 26.2.6.
- Installing `libreoffice-writer-nogui libreoffice-calc-nogui libreoffice-impress-nogui fonts-crosextra-carlito fonts-crosextra-caladea fonts-liberation` with `--no-install-recommends`, on top of the packages the image already installs, adds **407 MB** on disk.
- Converting a 20-page text-only `.docx` to PDF with `soffice --headless --convert-to pdf` takes **0.2–0.3 s**, LibreOffice's start-up included, with a fresh or a reused profile. A profile directory is 0.6 MB.

Real documents with images and complex layouts will take longer. The point is that starting LibreOffice per conversion costs a fraction of a second, which decides §5.1.

### 3.3 Other constraints

- **Stay mergeable.** New work goes in `healdan-core`. M1.5 edits one upstream file, with a small hook (§9).
- **CI time.** The owner wants time-to-green kept low. Anything M1.5 adds to the required checks gets measured and stated in the PR (§11).
- **Both architectures.** The image is built and smoke-tested on amd64 and arm64.

## 4. Formats (call)

LibreOffice's Writer, Calc and Impress convert these to PDF:

| Kind | Extensions |
|---|---|
| Word | `doc`, `docx`, `docm`, `dot`, `dotx`, `dotm`, `rtf` |
| Excel | `xls`, `xlsx`, `xlsm`, `xlt`, `xltx`, `xltm`, `csv` |
| PowerPoint | `ppt`, `pptx`, `pptm`, `pps`, `ppsx`, `pot`, `potx` |
| OpenDocument | `odt`, `ott`, `ods`, `ots`, `odp`, `otp` |

The list lives in one place in code and in `plugin.xml`, so adding a format is one change. `csv` is in because the GUI sends it to the PDF viewer, which shows "not available" today.

How each kind comes out:

- **Word:** pages as they'd print.
- **Excel:** every visible sheet, using its print settings. A wide sheet without print settings is split across pages. That's fine for a quick check.
- **PowerPoint:** one slide per page, without speaker notes.

### 4.1 Markup (owner)

A preview shows the markup a document still carries, so the quick check before emailing or printing catches a draft that isn't clean.

- **Tracked changes** show as redlines: insertions and deletions marked, as Word shows "All Markup". They show even when the document was saved with markup hidden.
- **Comments** show in the margin of Word documents. In spreadsheets and presentations they show as notes the viewer can display, as far as LibreOffice allows.
- **Thumbnails** come from the same PDF, so they show the markup too.

The builder checks each with a test file. If plain `soffice --convert-to` with PDF export options can't force tracked changes to show, the builder may drive LibreOffice with a small script inside the same one-process-per-conversion model (§5.1), and says so in the plan.

## 5. The converter

A Healdan `FormatConverter`, here called `OfficeConverter` in `com.logicaldoc.healdan.preview`, registered in `healdan-core`'s `plugin.xml` for every extension in §4 to `pdf`. Since it's the only converter registered for those pairs, `getConverter` picks it.

### 5.1 Running LibreOffice (call)

- **One process per conversion.** The converter runs `soffice --headless --convert-to pdf` for each file and waits for it to finish. There's no long-running LibreOffice daemon: §3.2 shows start-up is cheap, it needs no new Java libraries, and an idle server holds no LibreOffice memory.
  - If the owner finds on-the-spot previews slow on real documents, a warm process pool (JODConverter, say) is the fallback. That's a change inside the converter, not to the rest of this design.
- **Slots.** LibreOffice processes can't share a profile directory, so each conversion running at once uses its own profile: a *slot*. There are **2 slots** by default.
- **The background worker uses at most 1 slot** (§7.2), so an on-the-spot preview never waits behind the backlog.
- **Allow-listed.** The command goes through the `allowed-commands.txt` check, and `docker/allowed-commands.txt` gets a `soffice` line. Allowing `soffice` is admin-equivalent in the same way `convert` already is, since only server code and admins choose the command lines.
- **Timeout.** A conversion that runs longer than **120 s** is stopped, and **every** process it started is killed. `soffice` is a launcher that starts `soffice.bin`, so killing only the launcher, as `Exec.execPB` does, can leave LibreOffice running.
- **Parameters.** Slots, timeout and the `soffice` path are converter parameters (`converter.OfficeConverter.*`), with the defaults above built in. Nobody needs to set them.

### 5.2 Profiles

Each slot's profile is created from a template that `healdan-core` ships, the first time the slot is used after a start. Profiles live in the server's temp directory, so a restart starts them fresh.

### 5.3 Hardening

The profile template turns these off. The builder verifies each one with a test document (§11).

- **Macros.** Macro security at its highest level, and macro execution disabled outright. Converting a `.docm` with an auto-run macro must not run it.
- **Links and remote content.** LibreOffice never updates links or fetches linked images, fonts or data when it loads a document. Converting a document that links to an HTTP URL must make no request to it.
- **Anything interactive.** No first-start wizard, no update check, no crash reporter, no recovery dialog.

### 5.4 Fonts

The image installs fonts with the same metrics as Office's common fonts, so text flows onto the same lines and pages:

- Carlito for Calibri;
- Caladea for Cambria;
- Liberation for Arial, Times New Roman and Courier New.

Aptos, Office's default since 2023, has no free metric-compatible match that we know of. Documents set in it come out with slightly different line breaks. That's acceptable for a quick check.

### 5.5 Failures and the placeholder

- **The converter always reports itself enabled.** If it could be disabled, CE would fall back to `NotAvailableConverter` and cache the placeholder (§3.1).
- **It throws instead of producing a placeholder.** When LibreOffice is missing, fails, times out or produces an empty file, the converter throws. `ConvertPdf` then shows "not available" for that request only, and nothing is cached, so a later attempt can succeed.
- **No on/off switch.** On a host without LibreOffice, every Office conversion fails as above and the preview says "not available", as it does today. (call)

### 5.6 Serving the preview (call; upstream edit)

`ConvertPdf` gets a small hook where it now refuses a version whose file isn't a PDF:

- **It asks `FormatConversionManager` for that file's `-pdf` converter.** If that's a real converter, not `NotAvailableConverter`, it calls `convertToPdf` for that document and file version, which uses the cached conversion when there is one, and serves the version's `conversion.pdf` resource.
- **Otherwise it refuses as it does now.** That keeps the hook generic: it names no Healdan class or format. Since the image enables no CE converter (§3.1), only Healdan's converter qualifies.
- **CE's checks before it are unchanged:** the permissions, alias resolution, publishing and the document password.
- **Permissions stay as they are for PDFs: Read and Download.** A user with Preview but not Download gets a thumbnail but "not available" in the viewer, exactly as for a PDF today. Changing that rule for every format is a separate decision. (call)
- **Why here, not elsewhere.** A Healdan filter in front of `/convertpdf` would avoid the upstream edit but would have to copy `ConvertPdf`'s checks, which upstream rewrote on 2026-10-12 and may change again. Pointing the GUI at a Healdan servlet would be an upstream GUI edit with the same copying problem. Healdan already edits `ConvertPdf` (M1's R119), and `ConvertPdfTest` is already in CI's `-Dtest` list.

## 6. Thumbnails

A Healdan `ThumbnailBuilder` (`OfficeThumbnailBuilder`) is registered for the same extensions. It gets the document's PDF conversion through `FormatConversionManager`, which uses the cached one when it exists, and hands that PDF to CE's `PdfThumbnailBuilder`. A thumbnail therefore never converts the document twice, and a thumbnail and the preview always agree.

When the conversion fails, the builder throws, CE logs it and caches nothing, and the grid shows the file-type icon as it does today.

## 7. Converting ahead of time (call)

Goal 1 needs previews ready before anyone looks, so Healdan converts each new Office file version in the background.

### 7.1 The queue

- **A Healdan table, `hd_preview_job`,** added by a new Flyway script, `V2__preview_jobs.sql`, for MariaDB and HSQLDB. One row per document and file version waiting to be converted, with its attempt count, the time of its next try and its last error.
- **Adding jobs.** A new `DocumentListener` (`healdanPreview`) adds a row whenever a document whose extension is in §4 gets a new file version: on create, on check-in, and on every other route that stores new file content. The builder lists those routes and tests each one.
- **Timing.** Adding the row mustn't make the document's save fail or wait. The worker can come to a job before the document's transaction has committed, so a missing document is retried later, not dropped.
- **Duplicates.** A second job for the same document and file version is ignored.

### 7.2 The worker

- **One background thread,** started after Healdan's startup (migrations and seeds) has finished, takes due jobs oldest first.
- **For each job:**
  1. Skip it if the document has been deleted, or is larger than `gui.preview.maxfilesize`, since the GUI won't ask for its preview.
  2. Delete every cached conversion of the document, for any of its versions, that is the "not available" placeholder (compare it with `/pdf/notavailable.pdf`).
  3. Convert to PDF through `FormatConversionManager.convertToPdf`, which also caches it.
  4. Make the `thumb.png` and `tile.png` thumbnails through `ThumbnailManager`.
  5. Delete the job.
- **Failures.** A failed job is tried again after 1 minute, then 10 minutes, then 1 hour. After the fourth failure it stays in the table with its last error and isn't tried again. A new version of the document gets a new job as usual.
- **One slot.** The worker converts one document at a time, through one of the converter's slots (§5.1).

### 7.3 Documents already in Healdan

- **A seed step, `queue-office-previews`,** adds a job for the current file version of every non-deleted document whose extension is in §4. It's one SQL insert, so startup isn't held up. It runs once, like M1's `reindex-office` seed.
- **Placeholders cached before the upgrade** (by the routes in §3.1) are cleared by step 2 of the worker as it reaches each document, for all of its versions.
- **Older versions** aren't converted ahead of time. With their placeholders cleared, they convert on the spot the first time someone previews them.
- **No new placeholders.** After M1.5 nothing caches the placeholder for an Office format (§5.5), so only documents that existed before the upgrade can have one, and the seed queues all of them.

## 8. Docker image

- **Packages.** The runtime stage's existing `apt-get install` line gets `libreoffice-writer-nogui`, `libreoffice-calc-nogui`, `libreoffice-impress-nogui`, `fonts-crosextra-carlito`, `fonts-crosextra-caladea` and `fonts-liberation`, with `--no-install-recommends` as now. That layer comes before `ARG REVISION`, so CI's build cache keeps it between commits.
- **Allowed commands.** `docker/allowed-commands.txt` gets `soffice`, with a comment saying why.
- **Size.** About +410 MB on disk (§3.2). The plan records the compressed size CI reports for both architectures.
- **Home directory.** LibreOffice needs a writable home or profile path for uid 1000. The profiles live under the temp directory (§5.2); the builder checks nothing else needs writing.
- **`docker/README.md`** says Office previews need nothing configured, and how to tell from the log whether a conversion failed.

## 9. Upstream files M1.5 edits

One: the `ConvertPdf` hook (§5.6). Everything else lives in `healdan-core`, the fork-owned `Dockerfile` and the fork-owned files under `docker/`.

| File | Change | Why |
|---|---|---|
| `logicaldoc-webapp/.../web/ConvertPdf.java` (~118–120) | a non-PDF version with a real converter is converted and its `conversion.pdf` served, instead of refused (§5.6) | the preview page only loads `/convertpdf`, and `ConvertPdf` never converts |
| `logicaldoc-webapp/src/test/.../web/ConvertPdfTest.java` | `testFileTypeIsTheVersions` keeps its refusal for a type with no converter (`.txt`); new tests cover a type with a real converter (a stand-in converter, so the test needs no LibreOffice), and that a converter failure still serves the placeholder without caching it | covers the edit; the class is already in CI's `-Dtest` list |

If the builder finds another upstream edit unavoidable, it gets listed here with its reason, as M1's §13 does, and covered by a test in CI's `-Dtest` list.

## 10. CE's PDF buttons stay hidden (owner)

CE has a toolbar button that downloads a document as PDF, an "attach as PDF" option when emailing a document, and a "merge into one PDF" action. They're hidden behind CE's `PDF` feature gate (`Feature_8`), which nothing in Healdan turns on. M1.5 leaves them hidden. (owner) Printing works from the preview (goal 7), and the viewer's download button saves the rendered PDF when the user has the Download permission.

Turning them on later is one `Feature` extension in `healdan-core`'s `plugin.xml`. M1's gaps PR already checks Download in the server calls behind them (`convert`, `merge` and `sendAsEmail`). The gate also makes "paste as alias" ask what kind of alias to create.

M1.5 does make those server calls produce real PDFs for Office files through RPC, SOAP and REST, even while the GUI hides the buttons. That's harmless: they already check Download.

## 11. Verification

### 11.1 Automated

In `healdan-core`, running in CI's `build` check:

- **Converter:** the command line it builds; the profile template is applied to new slots; a timeout kills the whole process tree (use a stand-in script that starts a child and hangs); it throws, rather than writing a placeholder, when the command is missing or fails.
- **Thumbnail builder:** it uses the cached conversion and doesn't convert twice.
- **Queue:** each file-changing route adds a job; duplicates are ignored; the retry schedule; giving up after four failures; deleted and over-size documents are skipped; a cached placeholder is deleted before converting.
- **Seed:** it queues every current Office file version, and nothing else.
- **Real LibreOffice:** tests that need `soffice` are skipped when it isn't installed. CI's `build` runner may not have it; the Docker smoke test covers the real thing.

In the Docker image:

- **Smoke test** (`docker/smoke-test.sh`), on both architectures: upload a small committed `.docx` fixture into the smoke matter; poll until `/convertpdf` returns a PDF that isn't the placeholder and `/thumbnail` returns an image, with a time limit. State in the PR how many seconds this adds to the `Test` jobs.
- **Hardening:** the `.docm` macro test and the remote-link test from §5.3 run against the image's LibreOffice, in the smoke test or a `docker/tests/test-*.sh` script, whichever costs CI less.
- **Markup:** a `.docx` with a tracked insertion, a tracked deletion and a comment, saved with markup hidden, converts to a PDF whose text includes the deleted words and the comment (`pdftotext` is in the image).

### 11.2 Manual acceptance (owner)

On a stack upgraded from M1, with a handful of the owner's own Word, Excel and PowerPoint files:

1. The ones uploaded under M1 show previews and thumbnails after the upgrade.
2. A newly uploaded file previews within a minute without being opened first, and is recognisably the document.
3. A new version previews as the new version, and the old one as the old one.
4. Printing from the preview works.
5. A draft with tracked changes and comments shows them in its preview.

## 12. Left to the builder

- Class and package names other than those fixed above.
- How the worker thread is started and stopped with the webapp.
- The exact LibreOffice profile settings that achieve §5.3.
- How to kill the whole process tree on a timeout, including whether to use `Exec` or Healdan's own runner (which must still apply the allow-list).
- Which routes store new file content (§7.1), found by reading CE's `DocumentManager` and the WebDAV and CMIS modules.
- Whether two conversions of the same file version at once (the worker and an on-the-spot preview) need to be prevented. Duplicated work is harmless but wasteful.

## 13. Decisions made in this session

- **Purpose:** a quick check that it's the expected document before opening, printing or emailing it. (owner)
- **Convert ahead of time** in the background, with on-the-spot conversion as the fallback. (call)
- **Convert existing documents** after the upgrade, and clear placeholders M1 cached. (call)
- **LibreOffice inside the app container,** one process per conversion, 2 slots, the backlog limited to 1. (call)
- **A hook in `ConvertPdf`** serves the conversion, the only upstream edit; Office previews need Read and Download, like PDF previews. (call, after the builder check found `ConvertPdf` never converts)
- **Formats:** Microsoft Office, OpenDocument, RTF and CSV; not Apple iWork or Visio. (call)
- **Metric-compatible fonts;** Aptos documents may reflow slightly. (call)
- **Macros never run; nothing is fetched.** (call)
- **No settings screen** and no on/off switch. (call)
- **Previews show markup:** tracked changes as redlines, comments in the margin. (owner, after the builder check)
- **CE's PDF buttons stay hidden.** (owner, after the builder check)
