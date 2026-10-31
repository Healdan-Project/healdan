---
title: Building from Source
weight: 1
---

Healdan is a Maven project. To run it, you don't need any of this: the [Docker stack](../../install/quick-start/)
builds it for you. This page is for working on the code.

## What You Need

- **JDK 25.** It's the JDK the `Dockerfile`'s `MAVEN_IMAGE` and `TOMCAT_IMAGE` name. CI's `build` check reads it
  from there, and fails if the two differ.
- **Maven 3.9 or newer.**
- **Two extra Maven repositories.** Some dependencies aren't on Maven Central. They come from Healdan's copies at
  `https://maven.healdan.com/`, of the artifacts only LogicalDOC's repository has, and from
  `https://repo.terracotta.org/maven2`. `docker/maven-settings.xml` lists them. CI and the Docker build copy it to
  `~/.m2/settings.xml`; copy it there for local builds.
- **`~/logicaldoc-dev.properties`.** Spring-based tests fail in `setUp` without it. An empty file works.

## Commands

```bash
mvn -B -ntp -DskipTests install                            # the whole build, as the Docker image runs it
mvn -B -ntp -pl logicaldoc-webapp -am -DskipTests package  # the deployable WAR, in logicaldoc-webapp/target/
mvn -pl healdan-core test                                  # healdan-core's tests
mvn -pl healdan-core test -Dtest=FilingRulesTest           # one test class
mvn -pl logicaldoc-core test -Dtest='DocumentManagerTest#testCopyToFolder'  # one test method
mvn -pl healdan-core -DskipTests install                   # healdan-core, with the test-jar healdan-api's tests use
mvn -pl healdan-api test                                   # the API's tests
```

A module's tests run against the installed jars of the modules it uses, so install those first.

- **`healdan-api`'s tests need `healdan-core` installed,** including its test-jar: they reuse `healdan-core`'s base
  test case and fixtures. Install it again after changing `healdan-core`.
- **`-DskipTests` and `-Dmaven.test.skip=true` differ here.** `-DskipTests` compiles the tests without running them,
  so it still builds and installs `healdan-core`'s test-jar. `-Dmaven.test.skip=true` doesn't compile them, so
  there's no test-jar. `healdan-api`'s `with-tests` profile then leaves the test-jar out, and the build still
  works, but `healdan-api`'s tests can't run until `healdan-core` is installed with `-DskipTests`.

## Skipping the GWT Compile

`logicaldoc-gui` runs a GWT compile for each GWT module (frontend, login and setup), which is slow. When you
aren't changing the UI, skip it with `-Dgwt.skipCompilation=true`. The module's Java still compiles, which
Healdan's modules need.

- Up to `test`, that's enough.
- For `package` or `install`, also add `-Dmaven.antrun.skip=true` and `-pl '!logicaldoc-webapp'`, as CI's
  `build` check does. The GUI's antrun step fails without the GWT output, and skipping it leaves every plugin zip
  unfinished, so a WAR built from them is broken.
- After changing `logicaldoc-core`, `logicaldoc-webapp` or `logicaldoc-gui`, re-install that module before running
  `healdan-core`'s or `healdan-api`'s tests:

  ```bash
  mvn -pl logicaldoc-gui -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install
  ```

## The Desktop Clients

The desktop clients are under `clients/`, and build separately from Maven. [Desktop Clients](../desktop-clients/)
describes how they work.

- **The helper's core** is a .NET project in `clients/helper/Healdan.Helper.Core`, with its tests in
  `clients/helper/Healdan.Helper.Core.Tests`. The app's own tests, which need Avalonia, are in
  `clients/helper/Healdan.Helper.Tests`, and run only on macOS. You need the .NET 10 SDK;
  `clients/helper/global.json` pins the version, and `clients/helper/nuget.config` restores packages from nuget.org
  only.
- **The LibreOffice extension** is Python, in `clients/libreoffice`. Its tests are Python's `unittest`, in
  `clients/libreoffice/tests`. You need Python 3 and nothing else: the tests use the standard library only.
  `build.sh` packages the extension as `clients/libreoffice/healdan.oxt`, which the tests also build and check.
  The same sources give the same bytes; `SOURCE_DATE_EPOCH`, when set, is the time every entry carries.
- **The Office add-in** is plain JavaScript, in `clients/office`. Its logic is one file with no Office and no page in it,
  `clients/office/web/healdan.js`, and its tests are Node's built-in runner, in `clients/office/test`. You need Node 22
  or later and nothing else: there is no `package.json` and nothing to install. Keep the pattern quoted, as Node 21 and
  later read `--test`'s arguments as file patterns, and a folder fails.

```bash
dotnet test clients/helper/Healdan.Helper.sln                                  # the helper's tests
dotnet test clients/helper/Healdan.Helper.sln --filter "Category=MacKeychain"  # the real macOS keychain, on a Mac
HEALDAN_MAC_OFFICE=1 dotnet test clients/helper/Healdan.Helper.sln --filter "Category=MacOffice"  # the real Word, Excel and PowerPoint, on a Mac with Office
dotnet test clients/helper/Healdan.Helper.sln --filter "Category=Stack"        # needs HEALDAN_STACK_URL
python3 -m unittest discover -s clients/libreoffice/tests -v                   # the extension's tests
node --test 'clients/office/test/*.test.js'                                  # the add-in's tests
clients/libreoffice/build.sh                                                   # the extension's healdan.oxt
clients/helper/packaging/mac/package.sh 1.2.3                                  # the macOS app and disk image, on a Mac
```

`package.sh` signs only with `SIGN_IDENTITY` set (see
[Releasing the macOS Helper](../continuous-integration/#releasing-the-macos-helper)). Without it, the app gets only
an ad-hoc signature, which Gatekeeper refuses on any other Mac. A `SIGN_IDENTITY` that isn't in the keychains
stops it.

Tests marked `Stack` run against a live Healdan and skip without `HEALDAN_STACK_URL`, the stack's base URL, such as
`http://localhost:8080`. Start a throwaway stack with the smoke fixture overlay first (see
[Tests](../tests/)). Tests marked `MacKeychain` touch your real keychain, so they run only when you ask for them.
Tests marked `MacOffice` open Word, Excel and PowerPoint on your screen, and the apps ask for Automation and Grant File
Access the first time, so they run by hand on a Mac with Office, and are skipped unless `HEALDAN_MAC_OFFICE=1` is set.
When Grant File Access asks, go up one level from the folder it opens in and grant `healdan-office-tests`, which
holds every test's folder, so the app doesn't ask again.
The one that only compiles the AppleScript (`EveryScriptCompiles`) starts nothing and needs no variable.

## Good to Know

- `logicaldoc-parent` is a module of the build, and the root POM inherits from it, so dependency and plugin
  versions live there. Its `source.level` sets the Java language level. The GWT client compiles at level 17.
- `logicaldoc-webservicesamples` and `logicaldoc-webserviceclient` aren't part of the build.
