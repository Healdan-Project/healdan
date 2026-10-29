# Healdan's Maven repository: design

- **Tracking issue:** [Healdan-Project/healdan#107](https://github.com/Healdan-Project/healdan/issues/107)
- **Date:** 2026-10-29
- **Status:** approved by the owner on 2026-10-29.
- **Inputs:**
  - a design conversation with the owner on 2026-10-29;
  - the failed `master` runs of `build-logicaldoc.yml` that day (40877494760, 40891327931, 40894089832), and the
    passing ones before them;
  - a survey of this repository at `390288809`, and of a local Maven repository built from it. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks Claude's proposal.
The owner approved each section of the design in conversation.

## 1. Intent

Healdan's builds stop depending on LogicalDOC's infrastructure. (owner)

Today every build (CI's test shards, the Docker image and local builds) fetches 19 artifacts from LogicalDOC's
Maven repository at `https://logicaldoc.sourceforge.net/maven/`, because no other repository has them. SourceForge
sometimes answers 403. On 2026-10-29 a runner image change had made Maven treat CI's whole cache as foreign, so
every run fetched every artifact again. Two `master` runs that day failed because SourceForge refused the GUI's
artifacts, SmartGWT's POM among them. A separate change fixes the cache (§2, non-goals). This one removes
SourceForge from the build, so a refusal there can't fail Healdan's build however the cache behaves.

## 2. Goals, non-goals

### Goals (done when)

1. **Healdan hosts the 19 artifacts** at `https://maven.healdan.com/` (owner: the address), under the coordinates
   upstream's POMs already use (§4).
2. **No build contacts LogicalDOC.** A full build (GWT compile and WAR included) on an empty local repository
   records none of its artifacts as coming from the `logicaldoc` repository (§8).
3. **No upstream POM changes.** A mirror in `docker/maven-settings.xml` sends the `logicaldoc` repository's
   requests to Healdan's (§5). (owner: approach A)
4. **CI keeps it that way.** A check fails `build` when a POM or the settings file points a repository at a
   LogicalDOC host that the mirror doesn't cover (§6).
5. **Syncs have a procedure.** When an upstream sync needs an artifact Healdan doesn't host yet, the sync PR imports
   it with one script (§7).
6. **The docs say so,** in `CLAUDE.md` and the Developer Guide, in the same PR (§9).

### Non-goals

- **Moving artifacts to Maven Central coordinates.** Several have near equivalents on Central (§3). Switching means
  editing upstream POMs, and several switches are version changes that need checking against the GUI. It's a
  possible later clean-up, on top of this. (owner: approach B, later)
- **The CI cache.** Another session versions the Maven cache key by runner image. This design works with or without
  that change.
- **Runtime calls to LogicalDOC.** The shipped `context.properties` turns on `StatsCollector`, which posts usage
  statistics to `stat.logicaldoc.com`. The news feed comes from `logicaldoc.com`, and `SettingServiceImpl`
  references `activation.logicaldoc.com`. Those are server code and config, so they're a separate task. (owner)
- **`wagon-ssh` and `distributionManagement`.** The root POM's `wagon-ssh` extension and the parent's sftp deploy
  target exist for upstream's deploys to SourceForge. The extension resolves from Central, and Healdan never
  deploys, so neither makes a build depend on LogicalDOC.
- **Other repositories.** Terracotta's stays. The parent POM's other dead repositories (`java.net`, whose
  certificate fails in CI's logs, and `oss.sonatype.org`) aren't LogicalDOC's.

## 3. The artifacts

Every artifact a developer's local Maven repository, built from this repository, records as coming from
`logicaldoc`. Each POM is a bare stub, with no
parent and no dependencies, so the set is self-contained. They come to about 54 MB, 42 MB of it SmartGWT's jar.

| Coordinates | Used by | Notes |
|---|---|---|
| `betelio:betelio:1.0` | core (provided), webapp | No code references it |
| `com.allen_sauer.gwt.dnd:gwt-dnd:3.0.1` | gui | Used by the GUI's copy of gwt-links' source. Central has `com.allen-sauer.gwt.dnd:gwt-dnd` 3.2.3 and later |
| `com.google.code.ksoap2-android:ksoap2-android-assembly:3.3.0:jar-with-dependencies` | webserviceclient | Classifier `jar-with-dependencies` |
| `com.google.gwt:gwt-incubator:2.1.0` | gui | Central stops at 2.0.1 |
| `com.orange.links:gwt-links:1.4.2` | gui | Part of its source is copied into the GUI |
| `com.smartgwt:smartgwt:14.1p` | gui | 42 MB. Isomorphic doesn't publish the LGPL build to Central |
| `com.smartgwt:smartgwt-skins:14.1p` | gui (provided) | |
| `com.sun.media:jai-codec:1.1.3` | core | In core's plugin assembly |
| `com.timgroup:jgravatar:1.2.ld` | core | LogicalDOC's build. Central has 1.2 |
| `commons-fileupload:commons-fileupload:1.6.0-jakarta` | gui, webapp | LogicalDOC's Jakarta build. Central has 1.6.0 for `javax` only |
| `gdurelle.tagcloud:tagcloud:0.4` | gui | |
| `gwtchismes:gwtchismes:0.9.8` | gui | No code references it |
| `javax.media:jai-core:1.1.3` | core | In core's plugin assembly. Central has `jai_core` |
| `net.sf.jpf:jpf:1.5.1` | every module | Central stops at 1.5 |
| `net.sf.jpf:jpf-boot:1.5.1` | every module | |
| `org.apache.jackrabbit:jackrabbit-jcr-commons:2.22.0-jakarta` | webdav | LogicalDOC's Jakarta builds. Apache's 2.22.0 is `javax` only |
| `org.apache.jackrabbit:jackrabbit-jcr-server:2.22.0-jakarta` | webdav | |
| `org.apache.jackrabbit:jackrabbit-webdav:2.22.0-jakarta` | webdav | |
| `org.timepedia.exporter:gwtexporter:2.0.10` | gui | No code references it |

No POM declares `pluginRepositories`, so every plugin already comes from Central.

The first import takes its list from a fresh build, not from this table (§4.3).

## 4. The repository

### 4.1 Where it lives

A new public repository, `Healdan-Project/maven`, served by GitHub Pages from its default branch at
`https://maven.healdan.com/`. (owner: the address) A custom domain means old tags and branches keep building if the
hosting ever moves off Pages. (call)

- **Not GitHub Packages.** Its Maven registry needs a token even to read a public package, which would break
  anonymous local and Docker builds.
- **Not this repository.** About 54 MB of jars, plus about 46 MB for each SmartGWT bump, would sit in every clone's
  history for good. Git LFS's bandwidth quota wouldn't survive eight test shards. (owner: approach A over C)

### 4.2 What it holds

- The artifacts in Maven's standard layout: each with its `.jar`, `.pom` and `.sha1` files, plus any classifier
  jar (ksoap2's). There's no `maven-metadata.xml`, because nothing asks for a version range or `LATEST`.
- `CNAME` (`maven.healdan.com`) and `.nojekyll`, so Pages serves every path as it is.
- `README.md`, with a table of every artifact: its coordinates, its upstream project, its licence and where it came
  from (for the first import, `logicaldoc.sourceforge.net` on the import date). Healdan's image already ships these
  jars, so hosting them adds no new redistribution. The table makes that visible.
- `import.sh`, §4.3.

Old versions stay when a new one arrives, so old tags keep building.

### 4.3 `import.sh`

`import.sh <group>:<artifact>:<version>[:<classifier>]` fetches the artifact's jar and POM from
`https://logicaldoc.sourceforge.net/maven/`. It checks each against the `.sha1` SourceForge publishes beside it,
writes the files and their `.sha1`s into the layout, and adds the artifact's README row.

- `--file <jar>` imports a jar taken from a LogicalDOC CE release bundle instead, and writes a stub POM, for when
  SourceForge refuses or has gone.
- The script refuses to overwrite a version that's already there, so a hosted artifact never changes.
- Files only get in through the script, so the repository's history says where every artifact came from.

The first import's list comes from a full build (GWT compile and WAR included) on an empty local repository, with
today's settings. Every artifact that build records as coming from `logicaldoc` gets imported. It should match §3.

### 4.4 Setup

- Creating the repository is public and outward-facing, so Claude creates it with `gh` only once the owner says so.
- The owner adds the DNS record: `maven` CNAME to `healdan-project.github.io`.
- The owner turns on Pages for the custom domain and, once Pages has issued the certificate, Enforce HTTPS. If
  `healdan.com` isn't already a verified domain for the organisation, verifying it stops anyone else's Pages site
  from claiming the subdomain.

## 5. Wiring

`docker/maven-settings.xml` gains a mirror:

```xml
<mirrors>
  <mirror>
    <id>healdan</id>
    <name>Healdan's copies of artifacts only LogicalDOC's repository has</name>
    <mirrorOf>logicaldoc</mirrorOf>
    <url>https://maven.healdan.com/</url>
  </mirror>
</mirrors>
```

Its profile loses its own `logicaldoc` repository entry. The parent POM and `logicaldoc-webdav`'s POM still declare
`logicaldoc` (with `https://` and `http://` URLs). A mirror matches by id, so it takes every request for that
repository, whatever URL a POM gives it. Central and Terracotta stay as they are. (call)

- **No other file changes.** The Dockerfile, CI and local builds already copy this settings file, and no upstream POM
  changes.
- **The mirror's id shows in errors.** A missing artifact reads `Could not find artifact … in healdan
  (https://maven.healdan.com/)`, which says where to look.
- **Caches.** Local repositories, CI's cache and the Docker build's cache record the 19 as coming from `logicaldoc`,
  which doesn't match `healdan`. Each fetches them again from Healdan's repository once (about 54 MB). Artifacts from
  Central stay cached.

## 6. The guard

A new `.github/scripts/check-maven-repositories.py` runs in `build-logicaldoc.yml`'s `checks` job, next to the
icon and doc-links checks. It fails `build`, naming the file and line, when:

- `docker/maven-settings.xml` has no mirror of `logicaldoc` to `https://maven.healdan.com/`; or
- a `<repository>` or `<pluginRepository>` in a tracked `pom.xml`, or in the settings file, has a URL whose host
  contains `logicaldoc`, under an id the mirror's `mirrorOf` doesn't list. An example is a sync adding a
  `logicaldoc-snapshots` repository on SourceForge.

It ignores `<repository>` elements inside `<distributionManagement>`, and `<scm>` URLs, which a build never reads.
It checks every tracked POM, including `logicaldoc-webservicesamples`, which isn't in the reactor.

An artifact upstream adds that only SourceForge has isn't the guard's job: the build fails on it as "not found in
healdan" (§7).

## 7. Upstream syncs

When a sync bumps a hosted artifact (SmartGWT is the likeliest) or brings in a new one only SourceForge has, the
sync PR's build fails within a few minutes at dependency resolution, with "not found in healdan". The fix goes in
the sync PR itself, as a merge fix like the Font Awesome icons:

1. In a checkout of `Healdan-Project/maven`, run `import.sh` for each missing artifact and push.
2. Once Pages has published it, re-run the sync PR's CI.

This gives a deliberate point to look at what LogicalDOC ships before Healdan builds with it.

## 8. Testing

- **The guard's unit tests,** in `.github/scripts/tests/test_check_maven_repositories.py`, beside the other
  scripts' tests. Cases: a mirrored id passes, an `http://` and an `https://` LogicalDOC URL under an unmirrored id
  each fail with file and line, `<distributionManagement>` is ignored, and a settings file without the mirror fails.
- **A clean build before the PR.** `mvn -B -ntp -DskipTests install` (GWT compile and WAR included), with the new
  settings and `-Dmaven.repo.local` set to an empty directory. Afterwards no `_remote.repositories` record names
  `logicaldoc`, and the artifacts of §3 name `healdan`.
- **The PR's CI.** The eight test shards, both Docker image builds and the smoke test all resolve through the
  mirror.

## 9. Docs

In the same PR:

- `CLAUDE.md`:
  - "Build and test": the extra repositories are now Healdan's (via the mirror) and Terracotta's;
  - the CI cache bullet: drop the note that `logicaldoc.sourceforge.net` sometimes refuses requests;
  - "If CI fails within a minute": add the repository check and the "not found in healdan" error;
  - a bullet on importing a hosted artifact during a sync (§7).
- The Developer Guide:
  - Building from Source (`site/content/developer/building-from-source.md`, "Two extra Maven repositories");
  - Continuous Integration (`site/content/developer/continuous-integration.md`): the `build` row of the checks
    table, and "Things That Catch Upstream Syncs".

## 10. Rollout

1. Create `Healdan-Project/maven`, run the first import, and push (§4).
2. The owner adds the DNS record and turns on Pages and HTTPS (§4.4).
3. The clean build against `https://maven.healdan.com/` (§8).
4. The PR in this repository: the settings mirror, the guard and its tests, the `checks` step, and the docs.

The PR adds a step to `build-logicaldoc.yml`'s `checks` job, and the cache change edits that file's cache steps.
They touch different parts of the file, so at worst they make a small merge conflict.

**Risk.** Builds now depend on GitHub Pages instead of SourceForge. Pages is more reliable, and it's Healdan's to
move: the custom domain lets it go elsewhere without changing a settings file.

## 11. Upstream edits

None. Every change is in Healdan's own files: `docker/maven-settings.xml`, `.github/`, `CLAUDE.md`, `site/` and
this spec.
