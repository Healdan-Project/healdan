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
- **Two extra Maven repositories.** Some dependencies aren't on Maven Central. They come from
  `https://logicaldoc.sourceforge.net/maven/` and `https://repo.terracotta.org/maven2`.
  `.github/workflows/build-logicaldoc.yml` writes them into `~/.m2/settings.xml`; copy that block for local builds.
- **`~/logicaldoc-dev.properties`.** Spring-based tests fail in `setUp` without it. An empty file works.

## Commands

```bash
mvn -B -ntp -DskipTests install                            # the whole build, as the Docker image runs it
mvn -B -ntp -pl logicaldoc-webapp -am -DskipTests package  # the deployable WAR, in logicaldoc-webapp/target/
mvn -pl healdan-core test                                  # Healdan's tests
mvn -pl healdan-core test -Dtest=FilingRulesTest           # one test class
mvn -pl logicaldoc-core test -Dtest='DocumentManagerTest#testCopyToFolder'  # one test method
```

A module's tests run against the installed jars of the modules it uses, so install those first.

## Skipping the GWT Compile

`logicaldoc-gui` runs a GWT compile for each GWT module (frontend, login and setup), which is slow. When you
aren't changing the UI, skip it with `-Dgwt.skipCompilation=true`. The module's Java still compiles, which
`healdan-core` needs.

- Up to `test`, that's enough.
- For `package` or `install`, also add `-Dmaven.antrun.skip=true` and `-pl '!logicaldoc-webapp'`, as CI's
  `build` check does. The GUI's antrun step fails without the GWT output, and skipping it leaves every plugin zip
  unfinished, so a WAR built from them is broken.
- After changing `logicaldoc-core`, `logicaldoc-webapp` or `logicaldoc-gui`, re-install that module before running
  `healdan-core`'s tests:

  ```bash
  mvn -pl logicaldoc-gui -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install
  ```

## Good to Know

- `logicaldoc-parent` is a module of the build, and the root POM inherits from it, so dependency and plugin
  versions live there. Its `source.level` sets the Java language level. The GWT client compiles at level 17.
- `logicaldoc-webservicesamples` isn't part of the build.
