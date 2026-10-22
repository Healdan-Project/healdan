# M1.5 "Office previews and thumbnails" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Word, Excel, PowerPoint and OpenDocument files preview in the browser and get thumbnails, converted to PDF ahead of time by headless LibreOffice, as the approved spec describes.

**Architecture:**
- **Converter.** A Healdan `FormatConverter` (`OfficeConverter`) runs `soffice --headless --convert-to pdf` once per file, in one of two profile *slots*, through CE's `Exec` and its allow-list.
- **Thumbnails.** A Healdan `ThumbnailBuilder` (`OfficeThumbnailBuilder`) renders the first page of the cached PDF.
- **Serving.** A small hook in CE's `ConvertPdf` serves a non-PDF version's conversion when a real converter exists for it. It's M1.5's only upstream edit.
- **Ahead of time.** A document listener queues each new Office file version in a Flyway table, `hd_preview_job`. A background worker converts the queue one file at a time. A seed queues the documents already in Healdan.
- **Image.** The runtime image installs LibreOffice and metric-compatible fonts, and the smoke test checks real conversions on both architectures.

**Tech Stack:**
- LogicalDOC CE 9.3.1 on JDK 25: Spring 6.2.8, Hibernate 7.4.4, JPF plugins, Flyway.
- LibreOffice 26.2.6 from Ubuntu 26.04's archive, the base of `tomcat:11.0-jre25-temurin`.
- JUnit 4.13.2 and HSQLDB 2.7.1 for tests. Bash, curl, jq and python3 for the Docker tests.

**Status:** the owner approved this plan, including its spec corrections, on 2026-10-22, and chose subagent-driven execution. Tasks 1–9 are built ([Healdan-Project/healdan#48](https://github.com/Healdan-Project/healdan/pull/48) and PR B). Task 10, the owner's walkthrough, is pending.

**Spec:** [`docs/superpowers/specs/2026-10-22-m1-5-office-previews-design.md`](../specs/2026-10-22-m1-5-office-previews-design.md), approved by the owner on 2026-10-22. Every § reference below points into it. Where the two disagree, the spec wins, except for the corrections under "Spec corrections and calls this plan makes".

## Global Constraints

Every task's requirements include these.

- **Code.** New server code lives in `healdan-core` under `com.logicaldoc.healdan.preview`, except the seed (`com.logicaldoc.healdan.seed`) and the startup change.
- **No new Maven dependencies.**
- **Java sources are ASCII.** Put any non-ASCII character in a `\u` escape, and check with `LC_ALL=C grep -nP '[^\x00-\x7F]' <files>`.
- **Upstream edits.** Only `logicaldoc-webapp/.../web/ConvertPdf.java` and its test `ConvertPdfTest.java` (spec §9). `ConvertPdf.java` has CRLF line endings: edit it with the Edit tool and keep them.
- **Formats** (spec §4), compared case-insensitively:
  - text, PDF filter `writer_pdf_Export`: `doc`, `docx`, `docm`, `dot`, `dotx`, `dotm`, `rtf`, `odt`, `ott`;
  - spreadsheet, filter `calc_pdf_Export`: `xls`, `xlsx`, `xlsm`, `xlt`, `xltx`, `xltm`, `csv`, `ods`, `ots`;
  - presentation, filter `impress_pdf_Export`: `ppt`, `pptx`, `pptm`, `pps`, `ppsx`, `pot`, `potx`, `odp`, `otp`.
- **Runner limits** (spec §5.1): 2 slots; background work takes at most 1; timeout 120 s. They're read from `converter.OfficeConverter.slots`, `converter.OfficeConverter.timeout` and `converter.OfficeConverter.path`, with defaults 2, 120 and `soffice` built in.
- **Retries** (spec §7.2): after a failure, the next try is 1 minute, then 10 minutes, then 1 hour later. After the fourth failure a job is never tried again.
- **Schema.** One new Flyway script, `V2__preview_jobs.sql`, under both `healdan/db/migration/mariadb` and `.../hsqldb`. Never edit `V1`.
- **Seed.** The new step's name is `queue-office-previews`. It goes into `SeedRunner.ORDER` right after `reindex-office`.
- **`plugin.xml`.** Every extension id must be unique, because JPF silently drops the whole plugin on a duplicate. `PluginLoadTest` must still pass.
- **Image.** `apt-get install --no-install-recommends` adds exactly `libreoffice-writer-nogui libreoffice-calc-nogui libreoffice-impress-nogui fonts-crosextra-carlito fonts-crosextra-caladea fonts-liberation`. `docker/allowed-commands.txt` gets the line `soffice`.
- **CI time.** State in each PR how many seconds it adds to `build` and to the `Test` jobs.

## Review Focus

These are the inputs most likely to hurt the owner that no task's main tests reach. Each has a test in the task named.

1. **A file name with spaces, an en dash and an accent**, such as `Board minutes – é.docx`, previews like any other (Task 8: upload one).
2. **An upper-case extension** such as `REPORT.DOCX` is an Office file everywhere: formats, listener and seed (Tasks 1, 5 and 6).
3. **A restart in the middle of the backlog.** Queued jobs survive and the next worker processes them. A slot directory left by the previous run is replaced, not reused (Tasks 1 and 6).
4. **A conversion that never ends**, such as a huge spreadsheet. It's killed at the timeout, its job is marked failed, and the next job still runs (Tasks 1 and 6).
5. **Previewing an alias of an Office document** serves the target's conversion (Task 4).

## Spec corrections and calls this plan makes

A spike on 2026-10-22 in a throwaway container ran LibreOffice 26.2.6 on the runtime base image. Its results correct the spec in four places. Task 9 folds them into the spec, and the owner hears about 1 and 2 when this plan is handed over.

1. **Comments show as PDF notes, not in the margin** (spec §4.1). Headless LibreOffice refuses `ExportNotesInMargin`: the export fails with write error `0xc10`. `ExportNotes` works: each comment becomes a PDF text note, with the comment as its text. The viewer shows an icon at the commented text, and clicking it shows the comment. Thumbnails show at most the icon.
2. **Tracked changes are forced to show only in Word's XML formats** (spec §4.1). A `.docx` saved with markup hidden converts without its deletions. Removing `<w:revisionView .../>` from a copy's `word/settings.xml` before converting brings them back. The same treatment covers `docm`, `dotx` and `dotm`. Legacy `doc`, `dot` and `rtf` and the OpenDocument formats follow the document's own setting. A format-independent fix would need a profile macro, and command-line `macro:///` URLs don't run in headless 26.2 (tried three ways), or a UNO bridge, which §5.1 rules out.
3. **LibreOffice fetches linked images by default.** Converting a `.docx` that links an image to an HTTP URL made two `GET`s to it. `BlockUntrustedRefererLinks = true` in the profile stops them (spike: 0 requests). This is spec §5.3 working as designed, but the profile setting is mandatory, not optional.
4. **Killing on timeout.** `Exec.execPB` destroys only the process it started. `soffice` is a launcher, so `soffice.bin` can outlive it. After every run, the runner force-kills any process whose command line contains its slot's profile path.

Further calls:

5. **The macro test uses an OpenDocument file** (spec §5.3 and §11.1 say `.docm`). A `.docm`'s macros live in a binary `vbaProject.bin` that a test script can't write. An `.odt` whose LibreOffice Basic macro is bound to the document's open event tests the same promise.
6. **A replaced file queues the document's current file version.** CE's `replaceFile` deletes the conversions of the file version it replaces. The listener only sees the document, so it queues `document.getFileVersion()`. Replacing an older version's file converts that version on the spot instead.
7. **The worker drops a job** when the document is deleted, isn't an Office file, or is over the preview size limit. A document that isn't found counts as a failure, so it's retried and eventually given up on (spec §7.1: "retried later, not dropped").
8. **Indexing conversions count as interactive.** `CatchAllParser` converts the Office formats Healdan has no parser for (`dotm`, `xltx`, `xltm`, `pptm` and `potx`) on the indexer's thread, outside `inBackground`. Those formats are rare, so this is accepted rather than routing the indexer through the background permit.

## Conventions

- **Running tests:**
  - Healdan's: `mvn -B -ntp -pl healdan-core test -Dtest=<Class>`.
  - The webapp's: `mvn -B -ntp -pl logicaldoc-webapp test -Dmaven.antrun.skip=true -Dtest=ConvertPdfTest`.
  - Both need `~/logicaldoc-dev.properties` (an empty file will do) and the sibling modules installed (CLAUDE.md).
  - On the owner's Mac, run Maven in the `maven:3.9-eclipse-temurin-25` container, as the local-build notes describe.
- **Fake LibreOffice.** Tests never need a real `soffice`. A test helper, `FakeSoffice`, writes bash scripts into `target/fake-soffice/` at run time and marks them executable. Resource files lose their execute bit, so it doesn't use those. `healdan-core/src/test/resources/allowed-commands.txt` holds `*`, as `logicaldoc-core`'s test resources already do.
- **Document fixtures** come from `HealdanFixtures` (`upload(folder, name, text, user)`, `matter(...)`). A document costs about 1.1 s to create, because of CE's `saveVersionAsync`, so keep fixtures small.
- **Commits:** one per task, in the repo's style (an imperative subject, and a body that says why), ending with the `Co-Authored-By` trailer.

## PRs

- **PR A, Tasks 1–6:** the server side: `healdan-core` and the `ConvertPdf` hook. Without LibreOffice in the image it's inert:
  - Office previews say "not available", and nothing is cached.
  - The worker pauses for 10 minutes at a time without counting attempts.
  - Nothing is lost when LibreOffice arrives.
- **PR B, Tasks 7–9:** the image, the Docker tests and the docs. Stack it on PR A, targeting `master`, as the stacked-PR convention says.
- **Task 10:** the owner's acceptance walkthrough, after both merge.

---

### Task 1: Office formats and the LibreOffice runner

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/OfficeFormats.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/LibreOfficeRunner.java`
- Create: `healdan-core/src/main/resources/healdan/libreoffice/registrymodifications.xcu`
- Create: `healdan-core/src/test/java/com/logicaldoc/healdan/preview/FakeSoffice.java`
- Create: `healdan-core/src/test/resources/allowed-commands.txt` (one line: `*`)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/preview/OfficeFormatsTest.java`, `LibreOfficeRunnerTest.java`

**Interfaces:**
- Produces:
  - `OfficeFormats.Kind`, an enum `{TEXT, SPREADSHEET, PRESENTATION}` with `String pdfFilter()`.
  - `static Optional<OfficeFormats.Kind> OfficeFormats.kindOf(String fileName)`.
  - `static boolean OfficeFormats.isOffice(String fileName)`.
  - `static Set<String> OfficeFormats.extensions()`, all 27 extensions in lower case.
  - `static boolean OfficeFormats.hasHideableMarkup(String fileName)`, true for `docx`, `docm`, `dotx` and `dotm`.
  - `LibreOfficeRunner`, a Spring `@Component` with `static LibreOfficeRunner get()`, and:
    - `void toPdf(File src, OfficeFormats.Kind kind, File dest) throws IOException`;
    - `<T> T inBackground(Callable<T> work) throws Exception`. While `work` runs on this thread, `toPdf` first takes the single background permit.
    - package-private `List<String> commandLine(File src, OfficeFormats.Kind kind, File slotDir, File outDir)`, for tests.
  - Test helper `FakeSoffice` with `static File ok()`, `fail()`, `empty()`, `hang()` and `slow(int seconds, File log)`.
    - `ok` writes the PDF named by the environment variable `FAKE_PDF`, or a fixed minimal PDF when it's unset, to `<outdir>/<input base name>.pdf`.
    - `fail` exits 1.
    - `empty` exits 0 and writes nothing.
    - `hang` starts a child `sh -c 'sleep 600' <its -env:UserInstallation argument>` and sleeps 600 s.
    - `slow` appends `start <epoch ms>` and `end <epoch ms>` lines to the log around a sleep, then behaves like `ok`.

- [ ] **Step 1: Write the failing tests**

`OfficeFormatsTest`:
- `testKinds`: `kindOf("a.docx")` is `TEXT` with filter `writer_pdf_Export`. `kindOf("b.XLSX")` is `SPREADSHEET` (`calc_pdf_Export`). `kindOf("c.Ppsx")` is `PRESENTATION` (`impress_pdf_Export`). `kindOf("d.ods")` is `SPREADSHEET`, and `kindOf("e.csv")` is `SPREADSHEET`.
- `testNotOffice`: `kindOf` is empty for `a.pdf`, `a.txt`, `a.eml`, `docx` with no dot, and `null`.
- `testExtensions`: `extensions()` has exactly the 27 extensions in Global Constraints.
- `testHideableMarkup`: true for `X.DOCX` and `a.dotm`; false for `a.doc`, `a.odt` and `a.rtf`.

`LibreOfficeRunnerTest`, extending `AbstractHealdanTestCase`. Each test sets `converter.OfficeConverter.path` to a fake.
- `testCommandLine`: for `src = .../in.docx`, kind `TEXT`, slot `s` and out dir `o`, the package-private `commandLine(src, kind, s, o)` equals `[<path>, "--headless", "--norestore", "--nologo", "--nodefault", "--nolockcheck", "-env:UserInstallation=" + s.toURI(), "--convert-to", "pdf:writer_pdf_Export:{\"ExportNotes\":{\"type\":\"boolean\",\"value\":\"true\"}}", "--outdir", o.getPath(), src.getPath()]`.
- `testConverts`: with `ok()`, `toPdf(in.docx, TEXT, dest)` leaves `dest` starting with `%PDF`.
- `testProfileFromTemplate`: after one conversion, `slot-0/user/registrymodifications.xcu` under `<java.io.tmpdir>/healdan-libreoffice/` equals the classpath template.
- `testStaleSlotReplaced` (Review Focus 3): put a file `stale` in `slot-0/` before the runner's first use in a new `LibreOfficeRunner` instance. After one conversion `stale` is gone.
- `testFailureThrows`: `fail()` and `empty()` each make `toPdf` throw `IOException`. `dest` is untouched.
- `testMissingCommandThrows`: path `/nonexistent/soffice` makes `toPdf` throw `IOException`.
- `testTimeoutKillsEverything` (Review Focus 4): with `hang()` and `converter.OfficeConverter.timeout=2`, `toPdf` throws `IOException` whose message contains `timed out` within 10 s. Afterwards no process from `ProcessHandle.allProcesses()` has a command line containing the slot's path.
- `testBackgroundWaitsForBackground`: with `slow(2, log)`, start two `inBackground(() -> toPdf(...))` calls on two threads. Their start/end lines in the log don't overlap.
- `testInteractiveNotBlockedByBackground`: with `slow(3, log)` running in the background, an interactive `toPdf` with `ok()` on another thread finishes within 2 s.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='OfficeFormatsTest,LibreOfficeRunnerTest'`
Expected: compilation failure, because `OfficeFormats` and `LibreOfficeRunner` don't exist.

- [ ] **Step 3: Write the profile template**

Write `registrymodifications.xcu` in LibreOffice's `oor:items` format, with every item `oor:op="fuse"`:
- `/org.openoffice.Office.Common/Security/Scripting`: `BlockUntrustedRefererLinks` true (spike-verified: no remote fetch); `MacroSecurityLevel` 3; `DisableMacrosExecution` true.
- `/org.openoffice.Office.Writer/Content/Update` `Link` and `/org.openoffice.Office.Calc/Content/Update` `Link`: never update. Look up the schema's value for "never" in LibreOffice's `officecfg` and cite it in a comment. Task 8's probes are the arbiter.
- `/org.openoffice.Office.Common/Misc` `UseOpenCL` false, and the first-start and update-check items, so a slot's first run shows nothing interactive (spec §5.3).

- [ ] **Step 4: Implement `OfficeFormats` and `LibreOfficeRunner`**

`LibreOfficeRunner`:
- **Slots.** A `BlockingQueue<Integer>` of free slot numbers `0..slots-1`, plus a `Semaphore(1)` that background calls take before a slot, recorded in a `ThreadLocal<Boolean>` by `inBackground`. Slot directories are `<java.io.tmpdir>/healdan-libreoffice/slot-<n>`. On a slot's first use in this JVM, delete its directory and recreate `user/registrymodifications.xcu` from the template.
- **Running.** Convert into a fresh temp out directory. Run `commandLine(...)` through `new Exec().execPB(cmd, null, outDir, timeout)`, which applies `allowed-commands.txt`.
- **Sweep.** Then, whatever happened, force-kill every `ProcessHandle` whose `info().commandLine()` contains the slot directory's path, and wait up to 5 s for each to exit.
- **Result.** Success is `<outdir>/<src base name>.pdf` existing and non-empty: move it to `dest`. Otherwise throw `IOException`: "timed out after <n> s" when the run took at least the timeout, or "LibreOffice exited <code> without a PDF" otherwise.
- Release the slot and the background permit in `finally`.

- [ ] **Step 5: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='OfficeFormatsTest,LibreOfficeRunnerTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/preview healdan-core/src/main/resources/healdan/libreoffice healdan-core/src/test/java/com/logicaldoc/healdan/preview healdan-core/src/test/resources/allowed-commands.txt
git commit -m "Run headless LibreOffice for Office previews"
```

---

### Task 2: Markup and the Office converter

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/MarkupPreparer.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/OfficeConverter.java`
- Modify: `healdan-core/src/main/resources/plugin.xml` (one `FormatConverter` extension)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/preview/MarkupPreparerTest.java`, `OfficeConverterTest.java`

**Interfaces:**
- Consumes: `OfficeFormats`, `LibreOfficeRunner.toPdf` (Task 1).
- Produces:
  - `static File MarkupPreparer.prepare(File src, File workDir) throws IOException`. For `hasHideableMarkup` files it returns a copy, with the same name, in `workDir`, whose `word/settings.xml` has no `w:revisionView` element and whose other entries are byte-identical. For anything else, or a file that isn't a readable zip, it returns `src`.
  - `OfficeConverter extends AbstractFormatConverter`, a no-arg public class. CE instantiates it by reflection.

- [ ] **Step 1: Write the failing tests**

`MarkupPreparerTest` (build `.docx` zips in the test, as `docker/tests/test-fulltext.sh` does in Python):
- `testRevisionViewRemoved`: a `.docx` whose settings hold `<w:revisionView w:markup="false" w:insDel="false"/>` and `<w:zoom w:percent="100"/>`. The copy's settings still contain `w:zoom` and no `revisionView`, and `word/document.xml` is byte-identical.
- `testOtherFormatsUntouched`: `prepare` returns the same `File` for `a.doc`, `a.odt` and `a.xlsx`.
- `testUnreadableZipUntouched`: a `bad.docx` holding random bytes comes back as `src`, with no exception.
- `testNoSettingsPart`: a `.docx` without `word/settings.xml` gives a copy identical entry for entry.

`OfficeConverterTest`, extending `AbstractHealdanTestCase`, with `FakeSoffice.ok()` and a matter folder from `HealdanFixtures`:
- `testRegisteredForEveryFormat`: for every extension `e` in `OfficeFormats.extensions()`, `FormatConversionManager.get().getConverter("a." + e, "a.pdf")` is an `OfficeConverter`.
- `testAlwaysEnabled`: with `converter.OfficeConverter.enabled=false` in the config, `isEnabled()` is still true, and `getConverter("a.docx", "a.pdf")` is still an `OfficeConverter`.
- `testConvertsAndCaches`: upload `a.docx`. `FormatConversionManager.get().convertToPdf(doc, null)` leaves a `conversion.pdf` resource, which `Store.get().exists(...)` confirms, whose bytes start with `%PDF`.
- `testFailureCachesNothing`: with `FakeSoffice.fail()`, `convertToPdf` throws `IOException`, and no `conversion.pdf` resource exists.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='MarkupPreparerTest,OfficeConverterTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement `MarkupPreparer` and `OfficeConverter`, and register the converter**

`MarkupPreparer` streams the zip entry by entry with `java.util.zip`. In `word/settings.xml` it removes the matches of `<w:revisionView\b[^>]*/>`.

`OfficeConverter`:
- `isEnabled()` returns `true` (spec §5.5). `setEnabled` logs that it's ignored.
- `getParameterNames()` returns `path`, `timeout` and `slots`.
- `internalConvert(sid, document, src, dest)`:
  1. Find the kind from `src`'s name. `FormatConversionManager` names its temp file with the document's extension. No kind means `IOException`.
  2. `prepare` into a temp work directory.
  3. Call `LibreOfficeRunner.get().toPdf(prepared, kind, dest)`.
  4. Delete the work directory.

In `plugin.xml`, add `<extension plugin-id="logicaldoc-core" point-id="FormatConverter" id="healdanOfficeConverter">` with `in` set to the 27 extensions comma-separated, `out` set to `pdf`, and `class` set to `com.logicaldoc.healdan.preview.OfficeConverter`. Give it a comment pointing at spec §5.

- [ ] **Step 4: Run the tests, plus the plugin load test**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='MarkupPreparerTest,OfficeConverterTest,PluginLoadTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/preview healdan-core/src/main/resources/plugin.xml healdan-core/src/test/java/com/logicaldoc/healdan/preview
git commit -m "Convert Office files to PDF, showing tracked changes"
```

---

### Task 3: Office thumbnails

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/OfficeThumbnailBuilder.java`
- Modify: `healdan-core/src/main/resources/plugin.xml` (one `ThumbnailBuilder` extension per extension)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/preview/OfficeThumbnailBuilderTest.java`

**Interfaces:**
- Consumes: `OfficeConverter` through `FormatConversionManager` (Task 2).
- Produces: `OfficeThumbnailBuilder implements ThumbnailBuilder`, a no-arg public class with `protected void renderFirstPage(String sid, Document document, String fileVersion, File pdf, File dest, int size, int quality) throws IOException`, which delegates to `new PdfThumbnailBuilder().buildThumbnail(...)`.

- [ ] **Step 1: Write the failing tests**

`OfficeThumbnailBuilderTest`, extending `AbstractHealdanTestCase`. It uses `FakeSoffice.slow(0, log)`, so the log counts runs, and a subclass that overrides `renderFirstPage` to record the PDF bytes it gets and write a one-byte `dest`.
- `testRegisteredForEveryFormat`: `ThumbnailManager.get().getBuilder("a." + e)` is an `OfficeThumbnailBuilder` for every extension `e`, and for `"A.DOCX"`.
- `testUsesTheCachedConversion`: upload `a.docx` and build its thumbnail twice. The log shows one LibreOffice run. Both calls hand `renderFirstPage` bytes equal to the stored `conversion.pdf`.
- `testFailureThrows`: with `FakeSoffice.fail()`, `buildThumbnail` throws `IOException`, and `renderFirstPage` is never called.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=OfficeThumbnailBuilderTest`
Expected: compilation failure.

- [ ] **Step 3: Implement the builder and register it**

`buildThumbnail` does three things:
1. `FormatConversionManager.get().convertToPdf(document, fileVersion, sid)`;
2. `writePdfToFile(document, fileVersion, tmpPdf, sid)`;
3. `renderFirstPage(...)`, then delete the temp file.

It wraps `PersistenceException` in `IOException`.

`plugin.xml`: CE reads one extension per `ThumbnailBuilder` extension, so add 27 of them, ids `healdanThumb<Ext>` (`healdanThumbDocx`, …), each with `extension` and `class`.

- [ ] **Step 4: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='OfficeThumbnailBuilderTest,PluginLoadTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/preview/OfficeThumbnailBuilder.java healdan-core/src/main/resources/plugin.xml healdan-core/src/test/java/com/logicaldoc/healdan/preview/OfficeThumbnailBuilderTest.java
git commit -m "Build Office thumbnails from the cached PDF"
```

---

### Task 4: `ConvertPdf` serves the conversion (upstream edit)

**Files:**
- Modify: `logicaldoc-webapp/src/main/java/com/logicaldoc/web/ConvertPdf.java` (~118–124; CRLF)
- Modify: `logicaldoc-webapp/src/test/java/com/logicaldoc/web/ConvertPdfTest.java`

**Interfaces:**
- Consumes: CE's `FormatConversionManager.getConverter`, `convertToPdf` and `StoreResource.Builder.suffixPdfConversion()`. Nothing from Healdan.
- Produces: `/convertpdf` answers a non-PDF version with its PDF conversion when `getConverter(<version file name>, "preview.pdf")` isn't a `NotAvailableConverter`.

- [ ] **Step 1: Write the failing tests in `ConvertPdfTest`**

Register a stand-in converter in `setUp` with `FormatConversionManager.get().getConverters().put("abc-pdf", List.of(stub))`, and remove the key in `tearDown`. The stub implements `FormatConverter` directly, is enabled, and writes fixed bytes `STUB_PDF` (a minimal PDF), or throws when a flag is set.
- `testConvertibleVersionServesItsConversion`: check in `three.abc`. `preview("fileVersion", <its file version>)` returns `STUB_PDF`, and the `conversion.pdf` resource for that file version exists.
- `testConversionFailureServesPlaceholderAndCachesNothing`: with the stub set to throw, the response has the placeholder's length, as in `testFileTypeIsTheVersions`, and no `conversion.pdf` resource exists.
- `testAliasServesTargetsConversion` (Review Focus 5): an alias of the `.abc` document serves `STUB_PDF`, and the resource is stored under the target's id.
- Keep `testFileTypeIsTheVersions` as it is: `.txt` has no converter and is still refused.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl logicaldoc-webapp test -Dmaven.antrun.skip=true -Dtest=ConvertPdfTest`
Expected: the two conversion tests and the alias test FAIL with placeholder bytes.

- [ ] **Step 3: Edit `ConvertPdf`**

Replace the `.pdf` check and the download that follows with:
- a resource for the version, as now;
- when the version's file isn't a PDF:
  - take `FormatConversionManager.get().getConverter(version.getFileName(), "preview.pdf")`;
  - if it's a `NotAvailableConverter`, throw `IOException("Unsupported format")` as now;
  - otherwise call `convertToPdf(document, version.getFileVersion(), session.getSid())` and switch the resource to `.suffixPdfConversion()`;
- then `ServletUtil.downloadDocument` as before.

Mark it with a one-line `// Healdan:` comment citing M1.5 spec §5.6. Keep CRLF.

- [ ] **Step 4: Run the tests**

Run: `mvn -B -ntp -pl logicaldoc-webapp test -Dmaven.antrun.skip=true -Dtest=ConvertPdfTest`
Expected: PASS. The class is already in CI's `-Dtest` list, so there's no workflow change.

- [ ] **Step 5: Commit**

```bash
git add logicaldoc-webapp/src/main/java/com/logicaldoc/web/ConvertPdf.java logicaldoc-webapp/src/test/java/com/logicaldoc/web/ConvertPdfTest.java
git commit -m "Serve a non-PDF version's PDF conversion in previews"
```

---

### Task 5: The preview queue

**Files:**
- Create: `healdan-core/src/main/resources/healdan/db/migration/mariadb/V2__preview_jobs.sql`, `.../hsqldb/V2__preview_jobs.sql`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/PreviewJob.java`, `PreviewJobs.java`, `PreviewDocumentListener.java`
- Modify: `healdan-core/src/main/resources/plugin.xml` (one `DocumentListener` extension)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/preview/PreviewJobsTest.java`, `PreviewDocumentListenerTest.java`

**Interfaces:**
- Consumes: `OfficeFormats.isOffice` (Task 1).
- Produces:
  - `record PreviewJob(long docId, String fileVersion, int attempts, String lastError)`.
  - `PreviewJobs`, a `@Component` over the `DataSource` with `static PreviewJobs get()`, and:
    - `void add(long docId, String fileVersion, Instant now)`, which ignores a duplicate;
    - `List<PreviewJob> due(Instant now, int limit)`: jobs with `attempts < 4` and a next try at or before `now`, oldest first by creation, then doc id;
    - `void done(long docId, String fileVersion)`;
    - `void failed(long docId, String fileVersion, String error, Instant now)`;
    - `Optional<PreviewJob> find(long docId, String fileVersion)`;
    - `static final int MAX_ATTEMPTS = 4`.
  - Table `hd_preview_job`: `hd_docid bigint not null`, `hd_fileversion varchar(10) not null` (as `ld_version.ld_fileversion`), `hd_attempts int not null`, `hd_nexttry` (`datetime` on MariaDB, `timestamp` on HSQLDB) not null, `hd_lasterror varchar(4000)`, `hd_created` (same type) not null. Primary key `(hd_docid, hd_fileversion)`, plus an index on `(hd_attempts, hd_nexttry)`. MariaDB tables use `ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin`, as `V1` does.

- [ ] **Step 1: Write the failing tests**

`PreviewJobsTest`, extending `AbstractHealdanTestCase`, with fixed `Instant`s:
- `testAddIsIdempotent`: `add(7, "1.0", t)` twice leaves one job with 0 attempts.
- `testDueOrderAndLimit`: three jobs added at `t`, `t+1s` and `t+2s`. `due(t+3s, 2)` returns the first two, in that order.
- `testBackoff`: after `failed` at `t`, the job isn't due at `t+59s` but is at `t+60s`. After a second failure it's due at `t+10min`, and after a third at `t+1h`. After the fourth it's never due (`due(t+100d, 10)` is empty), and `find` shows 4 attempts and the last error.
- `testLongErrorTruncated`: a 5,000-character error is stored as its first 4,000 characters.
- `testMigrationRecorded`: `hd_schema_history` has version `2`, successful.

`PreviewDocumentListenerTest`, extending `AbstractHealdanTestCase`, with a matter folder:
- `testUploadQueuesOffice`: uploading `a.docx` and `B.XLSX` queues `(id, "1.0")` for each (Review Focus 2). Uploading `c.txt` and `d.pdf` queues nothing.
- `testCheckinQueuesNewVersion`: checking in a new `a.docx` queues its new file version too.
- `testCopyQueuesTheCopy`: `DocumentManager.get().copyToFolder(...)` of `a.docx` queues the copy's id.
- `testReplaceFileQueuesCurrentVersion`: `replaceFile(id, "1.0", ...)` on a single-version `a.docx` leaves job `(id, "1.0")` due again (call 6).
- `testListenerNeverBlocksTheSave`: with `hd_preview_job` dropped for the test, uploading `e.docx` still succeeds. Recreate the table afterwards.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='PreviewJobsTest,PreviewDocumentListenerTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the migration, `PreviewJobs` and the listener, and register it**

**`PreviewJobs`** works through a `JdbcTemplate` on its own connection, as `DocNumberCounter` does. It isn't part of the document's transaction (spec §7.1).

**`PreviewDocumentListener`** has a public no-arg constructor, because CE creates it by reflection. It doesn't refuse anything: every method catches `Throwable` and logs a warning.
- `afterFileStore` covers upload, check-in and copy: when `OfficeFormats.isOffice(document.getFileName())`, it calls `PreviewJobs.get().add(document.getId(), document.getFileVersion(), Instant.now())`.
- `afterStore` does the same when `transaction` isn't null and its event is `DocumentEvent.VERSION_REPLACED`. Compare with the key format CE's `History.setEvent` stores.
- Every other method is a no-op.

**`plugin.xml`:** add `<extension plugin-id="logicaldoc-core" point-id="DocumentListener" id="healdanPreview">` with `position` `150`, after the doc # listener at 140. Give it a comment pointing at spec §7.1.

- [ ] **Step 4: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='PreviewJobsTest,PreviewDocumentListenerTest,PluginLoadTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/resources/healdan/db/migration healdan-core/src/main/java/com/logicaldoc/healdan/preview healdan-core/src/main/resources/plugin.xml healdan-core/src/test/java/com/logicaldoc/healdan/preview
git commit -m "Queue new Office file versions for conversion"
```

---

### Task 6: The worker, startup and the backfill seed

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/PreviewWorker.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/QueueOfficePreviewsSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` (`ORDER`)
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/HealdanStartup.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/preview/PreviewWorkerTest.java`, and new methods in `healdan-core/src/test/java/com/logicaldoc/healdan/seed/SeedsTest.java`

**Interfaces:**
- Consumes: `PreviewJobs` (Task 5), `LibreOfficeRunner.inBackground` (Task 1), CE's `FormatConversionManager.convertToPdf` and `ThumbnailManager.createTumbnail(doc, fileVersion, sid)` / `createTile(doc, fileVersion, sid)`.
- Produces:
  - `PreviewWorker`, a `@Component`, with:
    - `void start()`, which starts one daemon thread, `healdan-preview`;
    - `@PreDestroy void stop()`;
    - package-private `int runOnce(Instant now)`, which processes up to 10 due jobs and returns how many;
    - package-private `void process(PreviewJob job, Instant now)`;
    - `static boolean isPlaceholder(byte[] pdf)`.
  - `QueueOfficePreviewsSeed implements SeedStep`, a `@Component` with an `@Autowired` constructor `QueueOfficePreviewsSeed(DataSource dataSource)`.
  - `HealdanStartup.run()` now returns `boolean`: true when migrations and seeds ran. `start()` (the `@PostConstruct`) calls `previewWorker.start()` only when `run()` returns true. So tests, whose `@PostConstruct` finds no CE schema and which call `run()` themselves, never start the thread.

- [ ] **Step 1: Write the failing tests**

`PreviewWorkerTest`, extending `AbstractHealdanTestCase`, with `FakeSoffice.ok()`, a matter folder and fixed `Instant`s. Each test uploads its documents, which queues them, and then calls `runOnce` or `process` directly.
- `testConvertsAndFinishes`: after `runOnce`, `a.docx` has a `conversion.pdf` resource for `1.0`, and its job is gone. Thumbnail failures in the test environment, where ImageMagick may be missing, don't fail the job.
- `testDeletedDocumentDropped`: delete `a.docx`, then `runOnce`. The job is gone and nothing is converted.
- `testOversizeDropped`: with `default.gui.preview.maxfilesize=1`, a `big.docx` of 1.1 MB is dropped unconverted.
- `testPlaceholdersCleared`: check in a second version of `a.docx`, then remove the `1.0` job so only `1.1` is queued. Store the bytes of the classpath `/pdf/notavailable.pdf` as the `conversion.pdf` of versions `1.0` and `1.1`. After `runOnce`, version `1.1`'s conversion is the fake's PDF and version `1.0` has no `conversion.pdf`. `isPlaceholder` is true for the classpath bytes and false for the fake's.
- `testFailureRetriesAndMovesOn` (Review Focus 4): with `FakeSoffice.hang()` and timeout 2 for `a.docx`, then `ok()` for `b.docx`, one `runOnce` leaves `a`'s job with 1 attempt and an error containing `timed out`, and converts `b`.
- `testMissingDocumentRetried`: `PreviewJobs.add(999999, "1.0", t)`, then `runOnce(t)`. The job has 1 attempt and the error `document not found`.
- `testJobsSurviveRestart` (Review Focus 3): queue `a.docx`, then build a new `PreviewWorker` from the context's beans and call its `runOnce`. `a.docx` is converted.

New `SeedsTest` methods:
- `testQueueOfficePreviewsIsABeanAndDoneAfterStartup`: the marker `seed.queue-office-previews` is `done`.
- `testQueueOfficePreviewsQueuesCurrentOfficeVersions`:
  - set-up: create `a.docx` (then check in version 1.1), `b.XLSX`, `c.pptx`, `d.odt`, `e.txt`, `f.pdf`, `g.eml`, a deleted `h.docx`, and an alias of `a.docx`;
  - empty `hd_preview_job`, then run the seed;
  - assert the queued `(file name, file version)` pairs are exactly `a.docx` at its current file version, plus `b.XLSX`, `c.pptx` and `d.odt` at `1.0`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='PreviewWorkerTest,SeedsTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the worker, the seed and the startup change**

**The worker thread** waits 30 s after `start()`, so the webapp finishes starting before the first conversion. Then it loops on `runOnce(Instant.now())`. When nothing was due it sleeps 5 s. It catches and logs everything, so one bad job never ends the thread. `stop()` interrupts and joins it for up to 10 s.

**`process`:**
1. Load the document with `DocumentDAO.get().findById`.
   - Not found: `failed(..., "document not found", now)`.
   - Deleted: `done`.
   - The job's file version isn't an Office file (take the name from that version): `done`.
   - Over `<tenant>.gui.preview.maxfilesize` MB, when that's above 0: `done`. Use the size of the version with that file version.
2. Clear placeholders: for every distinct file version among the document's versions, delete the `conversion.pdf` resource when `isPlaceholder(store.getBytes(resource))`.
3. Inside `LibreOfficeRunner.get().inBackground(...)`, call `convertToPdf(doc, fileVersion, null)`, then `createTumbnail` and `createTile` for that file version.
4. `done`. On any exception, call `failed` with the exception's message.

**`isPlaceholder`** compares the bytes with `/pdf/notavailable.pdf` from the classpath, which `logicaldoc-core` ships. Load it once.

**`QueueOfficePreviewsSeed`** has `name()` `queue-office-previews` and runs one statement:

```sql
insert into hd_preview_job (hd_docid, hd_fileversion, hd_attempts, hd_nexttry, hd_created)
select ld_id, ld_fileversion, 0, current_timestamp, current_timestamp from ld_document
where ld_deleted = 0 and ld_docref is null and lower(ld_type) in (<the 27 extensions>)
```

Build the extension list from `OfficeFormats.extensions()`. Skip rows that are already queued: a `not exists` subquery works on both databases.

- [ ] **Step 4: Run the tests, then the whole Healdan suite**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='PreviewWorkerTest,SeedsTest,HealdanStartupOrderTest'`
Expected: PASS.

Run: `mvn -B -ntp -pl healdan-core test`
Expected: all tests PASS. Note the suite's duration against the last run (~19 min) in the PR.

- [ ] **Step 5: Commit, then open PR A**

```bash
git add healdan-core/src/main/java healdan-core/src/test/java
git commit -m "Convert queued Office files in the background, and queue existing ones"
```

Open PR A against `master` with the spec link, the upstream edit (§9), the CI time it adds and a test plan.

---

### Task 7: LibreOffice in the image

**Files:**
- Modify: `Dockerfile` (the runtime stage's `apt-get install` line and its comment)
- Modify: `docker/allowed-commands.txt`
- Modify: `docker/README.md`

**Interfaces:**
- Produces: an image where uid 1000 can run `soffice --headless --convert-to pdf`, with Carlito, Caladea and Liberation installed.

- [ ] **Step 1: Add the packages and the allowed command**

Add the six packages from Global Constraints to the existing `apt-get install -y --no-install-recommends` line, and extend the comment above it to say LibreOffice converts Office files for previews. Add `soffice` to `docker/allowed-commands.txt`, and extend the header comment: Office previews run it, one process per conversion (M1.5 spec §5.1).

- [ ] **Step 2: Build the image and check it by hand**

Run: `docker build -t healdan-m15:test .`

Then: `docker run --rm --user 1000:1000 --entrypoint bash healdan-m15:test -c 'cd /tmp && echo hi > t.txt && soffice --headless -env:UserInstallation=file:///tmp/p --convert-to pdf t.txt && test -s t.pdf && fc-list | grep -ci -E "carlito|caladea|liberation"'`

Expected: exit 0 and a count of at least 3.

Record the image size from `docker image ls` before and after. The plan's decision log keeps both numbers. CI's compressed sizes for both architectures go in PR B.

- [ ] **Step 3: Document it**

In `docker/README.md`, add a short "Office previews" section:
- nothing needs configuring;
- conversions run in the background after upload;
- a failed conversion logs `OfficeConverter` / `PreviewWorker` lines in `dms.log`, and the preview says "not available";
- `soffice` is in `allowed-commands.txt` for this.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile docker/allowed-commands.txt docker/README.md
git commit -m "Install LibreOffice and Office-compatible fonts in the image"
```

---

### Task 8: Preview checks in the smoke test

**Files:**
- Create: `docker/tests/test-previews.sh`
- Modify: `docker/smoke-test.sh` (a step `5b` after `5a`)

**Interfaces:**
- Consumes: the environment variables `BASE`, `FOLDER_ID`, `DOC_PATH`, `SID`, `APP_CONTAINER` and `DB_CONTAINER`. The smoke test passes its `doc_path`, and takes the containers from `compose ps -q app` and `compose ps -q db`. Also `docker/tests/lib.sh` (`check`, `finish`, `FAILED`).
- Produces: PASS/FAIL lines in the style of `test-fulltext.sh`, and a non-zero exit on any failure.

- [ ] **Step 1: Write the script**

**Fixtures.** Build them with `python3` and `zipfile`, as `test-fulltext.sh` does. Each carries a unique token:
- `preview.docx` (`zqxword`), `preview.xlsx` (`zqxcell`) and `preview.pptx` (`zqxslide`);
- `Board minutes – é.docx` (`zqxname`, Review Focus 1);
- `markup.docx`:
  - a tracked insertion `zqxinserted` and a tracked deletion `zqxdeleted`;
  - a comment `zqxcomment`;
  - `<w:revisionView w:markup="false" w:insDel="false"/>` in its settings;
- `link.docx`, whose picture links to `http://127.0.0.1:18999/zqxprobe-image.png` (relationship `TargetMode="External"`, `r:link` on the blip);
- `webservice.xlsx`, with cell A1 `=WEBSERVICE("http://127.0.0.1:18999/zqxprobe-calc")` and no cached value;
- `macro.odt`, whose `Standard.Module1.Main` Basic macro writes `/tmp/healdan-macro-ran`, bound to the document's open event (`dom:load`, `vnd.sun.star.script:Standard.Module1.Main?language=Basic&location=document`);
- `bad.docx`, holding 2 KB of random bytes.

**Probe.** Before uploading, start a probe in the app container as uid 1000: `docker exec -d -u 1000 "$APP_CONTAINER" sh -c 'mkdir -p /tmp/probe && jwebserver -b 127.0.0.1 -p 18999 -d /tmp/probe -o info > /tmp/probe.log 2>&1'`. The JRE ships `jwebserver`.

**Checks:**
1. **Ahead of time** (goal 1): upload everything over REST. Then poll the database until `hd_preview_job` has no row for the five readable `.docx`/`.xlsx`/`.pptx` uploads, within `PREVIEW_TIMEOUT` (default 120 s). Don't request any preview first.
2. **Content:** for each of those five, fetch `"$BASE/convertpdf?docId=$id&sid=$SID"`. Copy it into the app container and run `pdftotext`; the text contains its token.
3. **Markup** (goal 8): `markup.docx`'s text contains `zqxinserted` and `zqxdeleted`. Its PDF bytes contain the comment as a note, either the UTF-16 hex `FEFF007A00710078...` of `zqxcomment` or `(zqxcomment)`, checked with `python3` on the host.
4. **Thumbnails:** `services/rest/document/thumbnail/thumb<path>/preview.docx` and `tile` return PNGs (`is_png`, as in the smoke test).
5. **No fetch** (goal 6): once `link.docx` and `webservice.xlsx` have been converted, `/tmp/probe.log` has no `zqxprobe`.
6. **No macro** (goal 6): `docker exec "$APP_CONTAINER" test ! -e /tmp/healdan-macro-ran`.
7. **Bad file** (goal 5): `bad.docx`'s `/convertpdf` text has no token. Its job has at least 1 attempt. No `/proc/*/cmdline` in the app container contains `soffice` once the job has failed.

**Negative controls, run once by hand and not committed:**
- In a throwaway container from the image, convert `link.docx` with a profile lacking `BlockUntrustedRefererLinks`. The probe log shows `zqxprobe-image`.
- Convert `macro.odt` with `MacroSecurityLevel` 0 and macros enabled. The marker file appears.

Record both outcomes in the decision log. If a control can't make its probe fire, say so in the log and in PR B: that check then guards nothing.

- [ ] **Step 2: Wire it into the smoke test**

After step `5a` in `docker/smoke-test.sh`, add `echo "== 5b. Office previews (M1.5 spec §11.1)"` and run `check "Office previews, markup and hardening" env BASE="$BASE" FOLDER_ID="$doc_folder" DOC_PATH="$doc_path" SID="$sid" APP_CONTAINER="$(compose ps -q app)" DB_CONTAINER="$(compose ps -q db)" bash docker/tests/test-previews.sh`.

- [ ] **Step 3: Run the smoke test locally**

Run: `bash docker/smoke-test.sh`
Expected: every line PASS. Note how long step `5b` takes.

- [ ] **Step 4: Commit, then open PR B**

```bash
git add docker/tests/test-previews.sh docker/smoke-test.sh
git commit -m "Check Office previews, markup and conversion hardening in the smoke test"
```

Open PR B against `master`, stacked on PR A. Include:
- the image size before and after;
- the seconds `5b` adds to `Test (amd64)` and `Test (arm64)`;
- the negative controls' outcomes.

---

### Task 9: Docs

**Files:**
- Modify: `docs/superpowers/specs/2026-10-22-m1-5-office-previews-design.md` (§4.1, §5.3, §11.1, status)
- Modify: `docs/reference/logicaldoc-ce-capability-map.md` (§5.4's "what happens to those files" bullets; the `ConvertPdf` row)
- Modify: `docs/ROADMAP.md` (M1.5 status)
- Modify: this plan (decision log)

- [ ] **Step 1: Fold the spec corrections in**

Fold in corrections 1, 2 and 5 from "Spec corrections and calls this plan makes":
- §4.1: comments as PDF notes; forced redlines only for `docx`, `docm`, `dotx` and `dotm`;
- §5.3 and §11.1: the `.odt` macro test.

Add a status line that names this plan.

- [ ] **Step 2: Update the capability map and roadmap**

In the capability map:
- Office formats now convert through Healdan's `OfficeConverter`, with LibreOffice in the image.
- `ConvertPdf` serves conversions of non-PDF versions (M1.5).

In the roadmap's M1.5 row: "built", with links to this plan and both PRs.

- [ ] **Step 3: Commit**

```bash
git add docs
git commit -m "Record M1.5 as built, with the spike's spec corrections"
```

---

### Task 10: The owner's acceptance walkthrough

Run spec §11.2 with the owner on a stack upgraded from M1, using a handful of the owner's own Word, Excel and PowerPoint files:

1. Files uploaded under M1 show previews and thumbnails after the upgrade.
2. A newly uploaded file previews within a minute without being opened first, and is recognisably the document.
3. A new version previews as the new version, and the old one as the old one.
4. Printing from the preview works.
5. A draft with tracked changes and comments shows them. Comments show as note icons, after correction 1.

Record each row's result, and any punch-list items, in this plan's decision log. Then mark M1.5 done in the roadmap.

## Decision log (filled in while executing)

| Date | Task | Decision or finding |
|---|---|---|
| 2026-10-22 | spike | LibreOffice 26.2.6 converts a 20-page `.docx` in 0.2–0.3 s including start-up; the packages add 407 MB on disk (arm64). |
| 2026-10-22 | spike | `ExportNotesInMargin` fails headless (`0xc10`); `ExportNotes` makes comments PDF text notes. |
| 2026-10-22 | spike | Removing `w:revisionView` makes a hidden-markup `.docx` show its redlines; command-line `macro:///` doesn't run headless. |
| 2026-10-22 | spike | Default LibreOffice fetched a linked image twice; `BlockUntrustedRefererLinks` stopped it. |
| 2026-10-22 | 1 | The link-update value for "never" is `1` in Calc and `2` in Writer (verified in `Calc.xcs` and `Writer.xcs`). A run that reached the timeout fails even with a PDF on disk. |
| 2026-10-22 | 3 | Preview tests clear the store in `setUp`, through `PreviewTests.clearStore()`, so results don't depend on test order or earlier runs. |
| 2026-10-22 | 6 | `findById` hides deleted documents, so the worker queries `ld_deleted` directly. Placeholder clearing includes the job's own file version. |
| 2026-10-22 | final review | F1: `ConvertPdf`, the worker and the thumbnail builder pass the `Version`. F2: `MarkupPreparer` is bounded. F3: interactive conversions fail fast after a recorded timeout, and slot waits are capped at 30 s. F4: the worker pauses for 10 minutes without counting an attempt when LibreOffice is unavailable. F5: the worker takes newest jobs first. F6: smaller fixes (the seed skips a null file version, a timeout of 0 or less falls back to 120 s, a WARN on give-up, a slot rebuilt after a timeout, a test that the worker uses `inBackground`). |
| 2026-10-22 | 6b | A bounded `revisionView` match, so a crafted settings part can't pin a thread, and the worker pause from F4. |
| 2026-10-22 | 7 | `tini` is PID 1 in the image, in place of the compose `init: true` the plan first called for, so `docker run` deployments reap killed `soffice.bin` too. The runtime image is 1.43 GB on arm64; the apt layer is 552 MB, including packages that were already there. |
| 2026-10-22 | 8 | Negative controls: unhardened, the linked image was fetched twice; hardened, 0 times. The `WEBSERVICE` cell and the `.odt` macro never fire under `--convert-to`, even unhardened (`Err:540`), so those two checks guard nothing today and stay as tripwires. |
| 2026-10-22 | 8 | `bad.docx` is a zip signature plus seeded random bytes, because bare random bytes import as text. |
| 2026-10-22 | 8 | `/convertpdf` authenticates with the `ldoc-sid` cookie, because the `sid` parameter is ignored unless `security.acceptsid` is set. |
| 2026-10-22 | 8 | Step 5b of the smoke test takes about 26 s locally. |
| 2026-10-22 | CI | PR A's `build` took 24 min, the same as recent code PRs (24–26 min), so no measurable increase. |

## Follow-ups

- **SOAP `uploadResource` can overwrite a cached conversion.** It lets a user with Write upload any suffix as a resource of a document. Since M1.5 serves an Office document's `conversion.pdf` as its preview, such a user could make the preview differ from the file. They can already replace the file itself, so the risk is low. Decide whether to refuse the `conversion.pdf` suffix there.
- **Legacy `.doc` and `.rtf`, and OpenDocument files,** don't get forced redlines (correction 2). Revisit if the owner meets one in practice.
- **Replacing a file while it's being converted** can cache the old content, or lose the re-queue.
- **`/uploadresource`,** which needs the Sign permission, can overwrite `conversion.pdf`, like SOAP's `uploadResource`.
- **A fresh upload's job reached before CE saves its version row** converts the `Document`, so CE writes a CONVERTED history row for it.
- **CE paths that pass a `Document` with an older file version** (SOAP, PDF alias downloads) still convert with the current extension, and the fail-fast check reads the current version's job.
- **The preview test classes take about 165 s,** mostly fake-LibreOffice timeouts and sleeps. They could be trimmed if CI time matters.
