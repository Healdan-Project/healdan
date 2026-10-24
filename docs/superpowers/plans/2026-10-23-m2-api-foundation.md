# M2 "API foundation" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A JSON API under `/api/v1` that M3's Office add-ins can be built on: device-token sign-in, finding matters and folders, the properties form, filing, check-out and saves. Sign-in is throttled on every route, as the approved spec describes.

**Architecture:**
- **Sign-in.** A small upstream hook in `SessionManager` runs CE's dormant login throttle on every route, and a seed turns it on.
- **Tokens.** Device tokens live in `healdan-core` (`hd_api_token`, Flyway `V3`). A new `Authentication` extension turns a token into a CE session, so the API's work runs through CE's own sessions, history and permission checks.
- **The HTTP layer** is a new plugin module, `healdan-api`. It's a CXF JAX-RS server on its own bus behind `@WebServlet("/api/*")`, and Spring Security leaves `/api/**` alone. Resources are Spring beans in `com.logicaldoc.healdan.api`, which `healdan-core`'s existing component scan already covers.
- **Read models.** Recent folders, recent documents, a Store's matters, legacy doc # lookup and the properties form (with the folder each value comes from) are new `healdan-core` services that the old UI could use too.
- **Contract.** `openapi.yaml` is written by hand. Every test response is validated against it, and a test fails when routes and file drift apart.

**Tech Stack:**
- LogicalDOC CE 9.3.1 on JDK 25: Spring 6.2.8, Spring Security 6.3, Hibernate 7, JPF plugins, Flyway.
- CXF 4.0.10 JAX-RS with Jackson 2.20.1, both already in the WAR.
- Swagger UI 5.32.15 from `org.webjars:swagger-ui`, bundled in the plugin zip.
- Tests: JUnit 4.13.2, HSQLDB, Spring's servlet mocks (`spring-test` 6.2.8) and `com.atlassian.oai:openapi-request-validator-core` 3.0.0. Bash, curl, jq and python3 for the Docker test.

**Status:** the owner approved this plan, including its spec corrections, on 2026-10-23, and chose subagent-driven execution.

**Spec:** [`docs/superpowers/specs/2026-10-23-m2-api-foundation-design.md`](../specs/2026-10-23-m2-api-foundation-design.md), approved by the owner on 2026-10-23. Every § reference below points into it. Where the two disagree, the spec wins, except for the corrections under "Spec corrections and calls this plan makes".

## Global Constraints

Every task's requirements include these.

- **Code location.**
  - The HTTP layer is in `healdan-api` under `com.logicaldoc.healdan.api` (models in `.api.model`).
  - Domain code is in `healdan-core` under `com.logicaldoc.healdan.*`.
  - No second `@PluginContext`: `HealdanContext`'s `@ComponentScan("com.logicaldoc.healdan")` already finds `healdan-api`'s beans.
- **Paths.**
  - The API is under `/api/v1`.
  - The docs page is `/api/docs/`.
  - The description file is `GET /api/v1/openapi.yaml`, from `healdan-api/src/main/resources/healdan/api/openapi.yaml` (OpenAPI 3.1.0, `servers: [{url: /api}]`, paths written as `/v1/...`).
- **Upstream edits, and only these** (spec §13; keep each file's line endings):
  - `logicaldoc-core/.../security/SessionManager.java` (LF);
  - `logicaldoc-webapp/src/main/resources/context-security.xml` (CRLF, no final newline);
  - root `pom.xml` (CRLF);
  - `logicaldoc-webapp/pom.xml` (CRLF).

  Use the Edit tool on CRLF files and check with `file <path>` afterwards.
- **New Maven dependencies, and only these:**
  - `org.webjars:swagger-ui:5.32.15`, bundled in `healdan-api`'s plugin zip;
  - test scope: `org.springframework:spring-test` (managed), `com.atlassian.oai:openapi-request-validator-core:3.0.0`, and `healdan-core`'s new test-jar.

  CXF, Jackson, `jakarta.ws.rs-api` and `jakarta.servlet-api` are `provided`: the WAR already has them.
- **Java sources are ASCII.** Write `–` as `–`, and check with `LC_ALL=C grep -nP '[^\x00-\x7F]' <files>`.
- **Schema.**
  - One new Flyway script, `V3__api_tokens.sql`, under `healdan-core/src/main/resources/healdan/db/migration/mariadb` and `.../hsqldb`, in V1's `PersistentObject` style.
  - Never edit `V1` or `V2`.
- **Seeds.** Two new step names, `throttle-signin` and `api-settings`. They go into `SeedRunner.ORDER` after `count-office-pages` and before `smoke-fixture`, in that order.
- **Settings** (spec §5.5), in `hd_setting`:
  - `api.token.idledays`: default 30, range 1–3650;
  - `api.token.maxdays`: default 365, range 1–3650, and at least `idledays`;
  - `api.cors.origins`: default empty; comma-separated exact origins `http(s)://host[:port]`, no path, no `*`.
- **Tokens** (spec §5.1):
  - format: `hd_` plus the 43-character base64url form, without padding, of 32 `SecureRandom` bytes;
  - storage: the lower-case hex SHA-256 only;
  - `lastUsed` is written at most once every 5 minutes per token.
- **Throttle** (spec §5.7):
  - `throttle.enabled = true`;
  - `throttle.username.max = 5` and `throttle.username.wait = 15`;
  - `throttle.ip.max = 20` and `throttle.ip.wait = 15`;
  - `throttle.username.disableuser = false`.
- **JSON** (spec §4.3–§4.5):
  - camelCase field names; `null` written, never omitted; dates ISO 8601 in UTC with `Z`.
  - Errors are `application/problem+json` with the spec §4.4 codes. Unknown request fields get `400 bad-request`.
  - Paging is `offset` (default 0) and `limit` (default 50). A `limit` outside 1–200 or a negative `offset` gets `400 bad-request`.
- **Every response object that names a resource carries `"type"`**: `"document"`, `"folder"` or `"matter"`. Folder children and search hits mix them, so clients switch on it.
- **Every endpoint a task adds also goes into `openapi.yaml`.** `RouteCoverageTest` and the contract check enforce it.
- **`plugin.xml`.** Extension ids must be unique, or JPF silently drops the plugin. `PluginLoadTest` and `ApiPluginXmlTest` must pass.
- **CI time.** State in each PR how many seconds it adds to `build` and to the `Test` jobs.

## Review Focus

These are the inputs most likely to hurt a person using the add-ins that no task's main tests reach. Each one has a test in the task named.

1. **A file name with spaces, an en dash and an accent**, such as `Board minutes – é.docx`. It's filed under exactly that name, and downloading it sends an RFC 6266 `Content-Disposition` that keeps it (Tasks 7 and 12).
2. **An extension that differs only in case** when saving, e.g. Word sends `Lease.DOCX` for `Lease.docx`. That counts as the same extension, not `422` (Task 13).
3. **A username blocked by the throttle** doesn't stop that user's device tokens. An attacker guessing passwords can't lock the owner's add-ins out (Tasks 1 and 6).
4. **Two saves of one document at once**, from two windows or a retry. Both are applied one after the other, the document stays checked out to its user, and nothing is lost (Task 13).
5. **Paging edges.** An `offset` past the end returns an empty `items` with the right `total`. `limit=200` works, and `limit=201` is `400` (Task 8).

## Spec corrections and calls this plan makes

The owner hears about 1–3 and 19 when this plan is handed over. Task 15 folds the corrections into the spec.

1. **Tests drive the real servlet through Spring's servlet mocks**, not CXF's local transport (spec §14.1). The token filter and sign-in need an `HttpServletRequest` for the client's address, which CE's sessions and throttle use. The mocks run the real `ApiServlet`, providers and filters. Neither `cxf-rt-transports-local` nor a test servlet container is needed.
2. **The response validator is `openapi-request-validator-core` 3.0.0.** It's the artifact `swagger-request-validator-core` 3.0.0 relocates to, and it supports OpenAPI 3.1. If it clashes with CE's Jackson 2.20 on the test classpath, fall back to `swagger-request-validator-core` 2.46.1, write the file as OpenAPI 3.0.3, and record the change in the decision log.
3. **`healdan-core` publishes a test-jar.** `healdan-api`'s tests reuse `AbstractHealdanTestCase`, `HealdanFixtures` and `PluginFixtures` through `<type>test-jar</type>`.
4. **The API's CXF server runs on its own `ExtensionManagerBus`.** `/services/*` uses CXF's Spring bus, and every `/rest` server hangs off it. A Spring-bus servlet on `/api/*` would expose them all at `/api/rest/...`. A test proves `/api/rest/...` is `404`.
5. **Swagger UI is served by the API servlet** from the webjar on the classpath, at `/api/docs/<file>`. That keeps it behind `/api/**`'s `security="none"`, with no reliance on Tomcat's static jar resources.
6. **Where an inherited value comes from is computed** (spec §6.7). `Inheritance.inheritedFrom(folderId)` gives the values a new document would get, but not their source. `ProfileView` walks the folder's parents to the matter workspace and finds the first with the attribute in `LocalValueDAO.locals('F', id)`.
7. **`source: "default"`** also covers Author and Typist on a new document, which default to the creator. The spec names only the install's default Class. A field with no value has `source: null`.
8. **Folders outside Stores are `404`**: the root, LogicalDOC's Default workspace, and the Templates Store with everything in it. The API's `kind` has only `store`, `matter`, `folder` and `searchFolder` (spec §6.2).
9. **`/search` always returns `total: null`.** A page shorter than `limit` is the last one. A search error is `500 server-error`: the old UI's `SearchServiceImpl` swallows errors and shows nothing, and the API doesn't.
10. **A lock is a hold for saving, even your own.** A document locked with the old UI's Lock action can't be saved through the API until it's released (`DELETE …/checkout`), whoever locked it (spec §6.6 names only someone else's hold).
11. **Unpublished documents are hidden** from users outside the `admin` and `publisher` groups, as CE's downloads and Healdan's `#` lookup already do (`AccessChecks.readableDocument`).
12. **Uploads obey the old UI's limits.** That's `<tenant>.upload.maxsize` (`413`) and the `<tenant>.upload.disallow` extensions (`422`), plus CE's refusal of empty files (`422`).
13. **The content endpoint records CE's `event.downloaded`**, with the same 30-second de-duplication as `ServletUtil.saveHistory`. Recent documents then picks up documents opened through the API.
14. **Other 4xx errors that JAX-RS raises,** such as `405` and `415`, keep their status and get code `bad-request`.
15. **`/data/lockeddocs.xml` is broken upstream.** Its SQL is missing a comma, and it does no permission filtering. `/me/checkouts` uses `DocumentDAO.findByLockUserAndStatus` and filters by read permission instead. Task 15 records the defect in the capability map.
16. **An admin releasing someone else's hold also clears `immutable`.** CE's `DocumentManager.unlock` does that for admins. Healdan doesn't use immutability, so this is accepted and recorded.
17. **The shipped `context.properties` has the throttle keys** (`throttle.enabled = false` and the rest, at about line 610; it's ISO-8859, so use `grep -a`). The seed overwrites those values once.
18. **CE's `XssFilter` canonicalizes every request header**, including `Authorization`. Base64url tokens pass through unchanged. The Docker test proves it through the real filter chain.
19. **Only a password sign-in clears the failure counts** (spec §5.7 says "when a sign-in succeeds"). A session opened from an API key or device token doesn't clear them. Otherwise an add-in reconnecting in the background would reset an attacker's count for the owner's username every half hour.

## Conventions

- **Running Maven.** Use the container wrapper in the M1 plan's Conventions section: `maven:3.9-eclipse-temurin-25`, the repo at `/src`, a copy of the `healdan-m2` volume for this branch, the CI `settings.xml`, and `touch /root/logicaldoc-dev.properties`.
  - `healdan-core`: `mvn -B -ntp -pl healdan-core test -Dtest=<Class>`.
  - `healdan-api`: first `mvn -B -ntp -pl healdan-core install -DskipTests` (it installs the test-jar), then `mvn -B -ntp -pl healdan-api test -Dtest=<Class>`.
  - Core's throttle test: `mvn -B -ntp -pl logicaldoc-core test -Dtest=SessionManagerThrottleTest`. Then `install` core before running `healdan-core`'s tests (CLAUDE.md).
- **Users in tests.**
  - `healdan-core` and `healdan-api` create users with `fx.user("clerk")` (non-admin) and `fx.user("boss", "admin")`, password `HealdanFixtures.PASSWORD`. `admin`/`admin` exists too.
  - Core's tests use `data.sql`'s users: `author` (id 4) is a non-admin, and `test` (id 5) is disabled. Their stored hashes predate CE's SHA-256 passwords, so the throttle test gives `author` a known one in `setUp`: `update ld_user set ld_password = '<Encrypter.encryptSHA256("Thr0ttle-Test!")>' where ld_id = 4`, by JDBC.
  - **The throttle is on in every `healdan-core` and `healdan-api` test.** `throttle-signin` runs at each `setUp`, because each test gets a fresh database. So a test that fails one user's password 5 times blocks that user for the rest of the test.
- **A matter to work in:**

  ```java
  Folder store = fx.store("Client Work");
  Client c = fx.client(998, "Healdan");
  Matter m = fx.matter(c, 1, "Demo", store, "Documents");
  ```

  Documents go into `Documents`, never into the workspace itself. Each document costs about 1.1 s (`saveVersionAsync`), so keep fixtures small.
- **API tests** extend `ApiTestCase` (Task 5) and call `get(...)`, `post(...)` and so on. Every response passes through the contract check.
- **Commits.** One per task, in the repo's style: an imperative subject, a body that says why, and the `Co-Authored-By` trailer.

## PRs

All four target `master`; stack B–D on the one before, as the stacked-PR convention says.

- **PR A, Tasks 1–6: sign-in and the API's frame.**
  - It ships the throttle, the token store, the module, the conventions, and the sign-in and `/me` endpoints.
  - On its own it's usable: a client can sign in and read `/me`.
- **PR B, Tasks 7–10: finding things.** Documents, folders, matters, the lists, search and lookup.
- **PR C, Tasks 11–13: writing.** The properties form, filing, check-out and saves.
- **PR D, Tasks 14–15: proof and docs.** The Docker flow test, the README, and the capability map, inventory, spec and roadmap.
- **Task 16:** the owner's acceptance, after all four merge.

---

### Task 1: Sign-in throttle on every route

**Files:**
- Modify: `logicaldoc-core/src/main/java/com/logicaldoc/core/security/SessionManager.java` (`newSession(String, String, String, Client)` ~161 and `newSession(String, Client)` ~202)
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SignInThrottleSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` (`ORDER`)
- Modify: `.github/workflows/build-logicaldoc.yml` (add `SessionManagerThrottleTest` to the `-Dtest` list, line 140)
- Test: `logicaldoc-core/src/test/java/com/logicaldoc/core/security/SessionManagerThrottleTest.java`, `healdan-core/src/test/java/com/logicaldoc/healdan/seed/SignInThrottleSeedTest.java`

**Interfaces:**
- Produces:
  - `SessionManager.newSession(String username, String password, String key, Client client)` calls `LoginThrottle.checkLoginThrottle(username, key, address)` before its `try`, and `LoginThrottle.clearFailures(user.getUsername(), address)` after `createSession` succeeds.
  - `newSession(String apikey, Client client)` only checks, with a `null` username. It never clears (correction 19).
  - In both, `address` is `client != null ? client.getAddress() : null`.
  - `SignInThrottleSeed implements SeedStep`, `name()` `"throttle-signin"`.

- [ ] **Step 1: Write the failing tests**

`SessionManagerThrottleTest extends AbstractCoreTestCase`. In `setUp`, set the six Global Constraints throttle values with `Context.get().getConfig().setProperty(...)`. Build a `Client` whose address is `10.0.0.9`, and one whose address is `127.0.0.1`. Restore the config in `tearDown`.
- `testFiveWrongPasswordsBlockTheUsername`: 5 × `newSession("author", "wrong", c)` throw `WrongPasswordException`. The 6th, with the right password, throws `UsernameBlockedException`.
- `testBlockedAttemptIsNotCounted`:
  1. After the block, set the `loginfail-username-author` sequence's `ld_lastmodified` to now − 14 min by JDBC.
  2. A blocked attempt throws `UsernameBlockedException`.
  3. The sequence still holds 5, and its `ld_lastmodified` is unchanged.
- `testBlockLiftsAfterTheWait`: with `ld_lastmodified` at now − 16 min, the right password signs in, and the counter is gone.
- `testSuccessClearsTheCount`: 4 wrong, 1 right, 4 wrong; the next right one signs in.
- `testTwentyFailuresBlockTheAddress`:
  1. 20 wrong passwords from `10.0.0.9`, spread over 5 usernames, 4 each.
  2. `author` with the right password from `10.0.0.9` throws `IPBlockedException`.
  3. From `10.0.0.10`, it signs in.
- `testAdminFromTheServerIsNeverBlocked`: 6 × wrong for `admin` from `127.0.0.1`, then `admin`/`admin` from `127.0.0.1` signs in.
- `testApiKeyIgnoresTheUsernameBlock` (Review Focus 3):
  1. Create an API key for `author` (as `SessionManagerTest` does).
  2. Block `author` with 5 wrong passwords.
  3. `newSession(key, c)` returns a session for `author`.
- `testKeySessionDoesNotClear`: 4 wrong passwords for `author`, then a session from `author`'s API key, then 1 more wrong password. The right password is now refused with `UsernameBlockedException`.
- `testFormLoginIsBlocked`:
  1. Block `author`.
  2. `new LDAuthenticationProvider().authenticate(...)` with an `UsernamePasswordAuthenticationToken("author", <right password>)`, built the way the provider reads it (details, client), throws a Spring `AuthenticationException`.
- `testOffWhenDisabled`: with `throttle.enabled=false`, 10 wrong passwords don't block.

`SignInThrottleSeedTest extends AbstractHealdanTestCase`:
- `testWritesTheValues`:
  1. Set the six keys to CE's shipped values (`false`, 3, 30, 10, 30, `false`).
  2. Delete the marker `seed.throttle-signin`.
  3. Run `SeedRunner.runPending()`.
  4. The config holds the six Global Constraints values.
- `testRunsOnce`:
  1. After the seed has run, set `throttle.username.max=9`.
  2. Run `runPending()` again.
  3. It's still 9.
- Restore the config in `tearDown`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest=SessionManagerThrottleTest` and `mvn -B -ntp -pl healdan-core test -Dtest=SignInThrottleSeedTest`
Expected: the throttle tests fail (no block happens). The seed test doesn't compile.

- [ ] **Step 3: Add the two calls to `SessionManager`**

Place each check as the first statement, outside the `try`. That way a blocked attempt never reaches `recordFailure`.

- [ ] **Step 4: Implement `SignInThrottleSeed` and add `throttle-signin` to `ORDER`**

It writes the six values with `Context.get().getConfig().setProperty(...)`, then `write()`, as `SearchAttributesSeed` does.

- [ ] **Step 5: Run the tests, plus `SessionManagerTest` and `PluginLoadTest`**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest='SessionManagerThrottleTest,SessionManagerTest'`, `mvn -B -ntp -pl logicaldoc-core install -DskipTests`, then `mvn -B -ntp -pl healdan-core test -Dtest='SignInThrottleSeedTest,PluginLoadTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add logicaldoc-core/src/main/java/com/logicaldoc/core/security/SessionManager.java logicaldoc-core/src/test/java/com/logicaldoc/core/security/SessionManagerThrottleTest.java healdan-core/src/main/java/com/logicaldoc/healdan/seed healdan-core/src/test/java/com/logicaldoc/healdan/seed/SignInThrottleSeedTest.java .github/workflows/build-logicaldoc.yml
git commit -m "Throttle sign-in on every route"
```

---

### Task 2: Device tokens in healdan-core

**Files:**
- Create: `healdan-core/src/main/resources/healdan/db/migration/mariadb/V3__api_tokens.sql`, `.../hsqldb/V3__api_tokens.sql`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/token/ApiToken.java`, `ApiTokenDAO.java`, `HibernateApiTokenDAO.java`, `ApiTokens.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/ApiSettingsSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/setting/HealdanSettings.java`, `seed/SeedRunner.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/token/ApiTokensTest.java`

**Interfaces:**
- Produces:
  - **Table `hd_api_token`:** the six `ld_` columns, plus:
    - `hd_userid bigint not null`;
    - `hd_hash varchar(64) not null unique`;
    - `hd_name varchar(255) not null`;
    - `hd_lastused` and `hd_expires`, both `datetime`/`timestamp not null`;
    - `hd_sid varchar(255)`, nullable.

    Plus an index on `hd_userid`, and the sequence `hd_api_token_SEQ` (start 100, increment 50).
  - **`ApiToken extends PersistentObject`** (`@Entity @Table(name = "hd_api_token")`): `long userId`, `String hash`, `String name`, `Date lastUsed`, `Date expires`, `String sid`.
  - **`ApiTokenDAO extends PersistentObjectDAO<ApiToken>`:**
    - `static ApiTokenDAO get()`;
    - `ApiToken findByHash(String hash) throws PersistenceException`;
    - `List<ApiToken> findByUser(long userId) throws PersistenceException`.

    Its implementation is `HibernateApiTokenDAO`, `@Repository("healdanApiTokenDAO")`.
  - **`ApiTokens` (`@Component`):**
    - `static ApiTokens get()`;
    - `static final String PREFIX = "hd_"`;
    - `static String hash(String token)`;
    - `static boolean looksLikeToken(String key)`;
    - `record Issued(String token, ApiToken row)`;
    - `Issued issue(User user, String name, String sid) throws PersistenceException`;
    - `Optional<ApiToken> find(String token)`;
    - `boolean usable(ApiToken token, Date now)`;
    - `void touch(ApiToken token, Date now)`;
    - `void bind(ApiToken token, String sid)`;
    - `void revoke(ApiToken token)`;
    - `int revokeAll(long userId)`;
    - `List<ApiToken> list(long userId)`.
  - **`HealdanSettings`:**
    - the constants `API_TOKEN_IDLE_DAYS = "api.token.idledays"`, `API_TOKEN_MAX_DAYS = "api.token.maxdays"` and `API_CORS_ORIGINS = "api.cors.origins"`;
    - the defaults `DEFAULT_API_TOKEN_IDLE_DAYS = 30` and `DEFAULT_API_TOKEN_MAX_DAYS = 365`;
    - the getters `int apiTokenIdleDays()`, `int apiTokenMaxDays()` and `List<String> apiCorsOrigins()` (split on commas, trimmed, blanks dropped).
  - **`ApiSettingsSeed`**, `name()` `"api-settings"`. It calls `setIfMissing` for the three settings (origins `""`).

- [ ] **Step 1: Write the failing tests** (`ApiTokensTest extends AbstractHealdanTestCase`)

- `testIssueStoresOnlyTheHash`:
  - The token matches `^hd_[A-Za-z0-9_-]{43}$`.
  - The row's `hash` equals the hex SHA-256 of the token.
  - No column of the row (read by JDBC) contains the token.
  - `expires` is `ld_creation` + 365 days.
- `testFind`:
  - `find(token)` is present.
  - Empty for a different `hd_` token, `ld-abc`, `""` and `null`.
  - Empty after `revoke`.
- `testIdleExpiry`: with `hd_lastused` at now − 31 days, `usable` is false; at now − 29 days, it's true.
- `testAbsoluteExpiry`: `usable(t, expires + 1 s)` is false.
- `testSettingsDriveExpiry`:
  - With `api.token.idledays=1`, a token unused for 2 days isn't usable.
  - With `api.token.maxdays=10`, a token issued now expires in 10 days.
- `testTouchAtMostEveryFiveMinutes`: `touch` leaves a `lastUsed` of now − 1 min alone, and updates one of now − 6 min.
- `testRevokeClosesTheSession`: after `issue(user, "x", sid)` with a real CE session, `revoke` sets `ld_deleted = 1`, and `SessionManager.get().isOpen(sid)` is false.
- `testRevokeAll`: of two tokens for `clerk` and one for `boss`, `revokeAll(clerk)` returns 2, and `boss`'s is still found.
- `testListShowsOnlyUsable`: `list` leaves out revoked and expired tokens.
- `testNames`:
  - A blank name becomes `Unnamed device`.
  - 101 characters throw `HealdanRuleException("A device name can't be longer than 100 characters.")`.
  - An emoji throws `HealdanRuleException`, as the other name checks do (`BasicPlane.holds`).
- `testSettingDefaults`:
  - After the seeds, the three settings read `30`, `365` and `""`.
  - `apiCorsOrigins()` turns `" https://a.test , ,http://b.test:3000 "` into `[https://a.test, http://b.test:3000]`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=ApiTokensTest`
Expected: compilation failure.

- [ ] **Step 3: Write `V3__api_tokens.sql` for both dialects**

Follow V1's `hd_client` style: MariaDB `datetime` with the `ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin` suffix, HSQLDB `timestamp`.

- [ ] **Step 4: Implement the entity, DAO, `ApiTokens`, the settings and the seed, and add `api-settings` to `ORDER`**

`ApiTokens.revoke` kills the token's sid through `SessionManager.get().kill(sid)` when it's set.

- [ ] **Step 5: Run the tests, plus the migration and seed tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='ApiTokensTest,HealdanMigratorTest,SeedRunnerTest'`
Expected: PASS. Update any test that asserts the migration version or the seed list, if one does.

- [ ] **Step 6: Commit**

```bash
git add healdan-core/src/main/resources/healdan/db/migration healdan-core/src/main/java/com/logicaldoc/healdan/token healdan-core/src/main/java/com/logicaldoc/healdan/setting/HealdanSettings.java healdan-core/src/main/java/com/logicaldoc/healdan/seed healdan-core/src/test/java/com/logicaldoc/healdan/token
git commit -m "Store revocable device tokens for the API"
```

---

### Task 3: The token authenticator and automatic revocation

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/token/HealdanTokenAuthenticator.java`, `TokenUserListener.java`
- Modify: `healdan-core/src/main/resources/plugin.xml`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/token/HealdanTokenAuthenticatorTest.java`, `TokenUserListenerTest.java`

**Interfaces:**
- Consumes: `ApiTokens` (Task 2).
- Produces:
  - **`HealdanTokenAuthenticator extends AbstractAuthenticator`**, `@Component("healdanTokenAuthenticator")`.
    - `authenticate(String username, String password, String key, Client client)` returns `null` unless `ApiTokens.looksLikeToken(key)`.
    - An unknown or unusable token throws `AuthenticationException("invalidtoken")`.
    - Otherwise it loads the user with `UserDAO.get().findById(userId, true)`, runs `Context.get(DefaultAuthenticator.class).validateUser(user)` and returns the user.
    - `authenticate(String, String)` and `pickUser(String)` return `null`.
  - **`TokenUserListener implements UserListener`**, with a public no-arg constructor (CE creates it by reflection).
    - `beforeSave` compares the stored `ld_password` (JDBC) with `user.getPassword()`, for an existing user. When they differ, it puts `healdan.tokens.passwordChanged` = `true` into the dictionary.
    - `afterSave` calls `ApiTokens.get().revokeAll(user.getId())` when that flag is set, or when `user.getEnabled() == 0`.
    - Both catch and log everything: a user save never fails because of tokens.
  - **`plugin.xml`:**
    - `<extension plugin-id="logicaldoc-core" point-id="Authentication" id="healdanTokenAuthentication">` with `authenticatorId` `healdanTokenAuthenticator` and `position` `105`;
    - `<extension plugin-id="logicaldoc-core" point-id="UserListener" id="healdanTokens">` with `class` `com.logicaldoc.healdan.token.TokenUserListener` and `position` `110`.

    Give each a comment pointing at spec §5.

- [ ] **Step 1: Write the failing tests**

`HealdanTokenAuthenticatorTest`:
- `testTokenOpensASession`: issue a token for `clerk`. `SessionManager.get().newSession(token, (Client) null)` returns a session whose user is `clerk`.
- `testRevokedOrExpiredTokenRefused`: each of these throws `AuthenticationException`: a revoked token, a token with `hd_expires` in the past, and a token with `hd_lastused` 31 days ago.
- `testDisabledUserRefused`: disable `clerk` by JDBC (`update ld_user set ld_enabled = 0`), which bypasses the listener. `newSession(token, null)` throws `AccountDisabledException`.
- `testOtherKeysIgnored`: `authenticate("", "", "ld-abc", null)` and `authenticate("u", "p", null, null)` return `null`.
- `testApiKeysStillWork`: a CE API key (`ld-…`) still opens a session.

`TokenUserListenerTest`:
- `testPasswordChangeRevokes`: give `clerk` two tokens. `setDecodedPassword("N3w-Pass-word!")`, `setPasswordChanged(new Date())` and `UserDAO.store(user, history)` revoke both.
- `testDisableRevokes`: `user.setEnabled(0)` plus `store` revokes all.
- `testOtherSavesKeepTokens`: changing the email and storing leaves the tokens.
- `testSignInKeepsTokens`: `newSession("clerk", PASSWORD, null)`, which sets the decoded password, leaves the tokens.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='HealdanTokenAuthenticatorTest,TokenUserListenerTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement both classes and add the two extensions**

- [ ] **Step 4: Run the tests and the plugin load test**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='HealdanTokenAuthenticatorTest,TokenUserListenerTest,PluginLoadTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/token healdan-core/src/main/resources/plugin.xml healdan-core/src/test/java/com/logicaldoc/healdan/token
git commit -m "Sign in with device tokens and revoke them with the password"
```

---

### Task 4: API settings in the Healdan Settings panel

**Files:**
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/beans/GUIHealdanSettings.java`
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/settings/HealdanSettingsPanel.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/web/HealdanSettingsServiceImpl.java` (`current()`, `check()`, `write()`, `Values`)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/web/HealdanSettingsServiceImplTest.java`

**Interfaces:**
- Consumes: the `HealdanSettings` constants and getters (Task 2).
- Produces:
  - **`GUIHealdanSettings`** gets `int apiTokenIdleDays`, `int apiTokenMaxDays` and `String apiCorsOrigins`, with getters and setters.
  - **`check()`** refuses with these messages:
    - idle days outside 1–3650: `"A device sign-in must expire after between 1 and 3650 unused days."`;
    - max days outside 1–3650: `"A device sign-in must last between 1 and 3650 days."`;
    - max below idle: `"A device sign-in can't last fewer days than it may go unused."`;
    - each origin not matching `^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$`, including `*`: `"'%s' isn't an origin. Write it as https://host or https://host:port, with no path."`.

- [ ] **Step 1: Write the failing tests** in `HealdanSettingsServiceImplTest`

- `testApiSettingsRoundTrip`: `save` with 10, 100 and `"https://localhost:3000, https://addin.example.org"`. `load` returns them, and `HealdanSettings.get().apiCorsOrigins()` has both origins.
- `testApiSettingsRefused`: each message above, for 0, 3651, max 5 below idle 10, `*`, `https://a.test/path` and `ftp://a.test`. Nothing is written for any of them.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=HealdanSettingsServiceImplTest`
Expected: compilation failure.

- [ ] **Step 3: Implement the bean fields, `current`/`check`/`write`, and three form items in the panel**

The form items:
- two spinners, 1–3650: "Device sign-in expires after (unused days)" and "Device sign-in lasts at most (days)";
- one text item: "API origins allowed (CORS)", with the hint "Comma-separated, e.g. https://localhost:3000. Leave empty unless an add-in is served from another address."

Follow the panel's existing item style. Add them to `form.setItems(...)` and to `save()`.

- [ ] **Step 4: Run the tests and the GUI checks**

Run:
- `mvn -B -ntp -pl healdan-core test -Dtest=HealdanSettingsServiceImplTest`
- `mvn -B -ntp -pl logicaldoc-gui install -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true`
- `python3 .github/scripts/check-fontawesome-icons.py`

Expected: PASS, and no flagged icons. The Docker image CI compiles the GWT module.

- [ ] **Step 5: Commit**

```bash
git add logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan healdan-core/src/main/java/com/logicaldoc/healdan/web/HealdanSettingsServiceImpl.java healdan-core/src/test/java/com/logicaldoc/healdan/web/HealdanSettingsServiceImplTest.java
git commit -m "Let admins set device sign-in limits and API origins"
```

---

### Task 5: The healdan-api module, its servlet and conventions

**Files:**
- Create: `healdan-api/pom.xml` (copy `healdan-core/pom.xml`'s parent, plugins and assembly configuration), `healdan-api/src/main/assembly/assemblyplugin.xml`, `healdan-api/src/main/resources/plugin.xml`
- Create in `healdan-api/src/main/java/com/logicaldoc/healdan/api/`: `HealdanApiPlugin.java`, `ApiServlet.java`, `ApiServer.java`, `ApiJson.java`, `ApiProblem.java`, `ProblemMapper.java`, `Public.java`, `Paging.java`, `ServerResource.java`, `DocsResource.java`, `model/FieldError.java`, `model/Page.java`, `model/ServerInfo.java`
- Create: `healdan-api/src/main/resources/healdan/api/openapi.yaml`, `healdan-api/src/main/resources/healdan/api/docs/index.html`
- Modify: root `pom.xml` (add `<module>healdan-api</module>` after `healdan-core` and before `logicaldoc-webapp`)
- Modify: `logicaldoc-webapp/pom.xml` (an `artifactItem` for `healdan-api`'s plugin zip next to `healdan-core`'s at ~616)
- Modify: `logicaldoc-webapp/src/main/resources/context-security.xml` (after line 113: `<security:http pattern="/api/**" security="none" />`)
- Modify: `healdan-core/pom.xml` (a `maven-jar-plugin` execution with goal `test-jar`)
- Modify: `.github/workflows/build-logicaldoc.yml` (line 136: `mvn -B -ntp -pl healdan-core,healdan-api test`)
- Test, in `healdan-api/src/test/java/com/logicaldoc/healdan/api/`: `ApiTestCase.java`, `ApiCall.java`, `ApiResponse.java`, `Contract.java`, `ServerResourceTest.java`, `DocsResourceTest.java`, `ProblemMapperTest.java`, `RouteCoverageTest.java`, `OpenApiFileTest.java`, `ApiPluginXmlTest.java`

**Interfaces:**
- Produces:
  - **`plugin.xml`:** `<plugin id="healdan-api" version="9.3.1" class="com.logicaldoc.healdan.api.HealdanApiPlugin">`, requiring `logicaldoc-core` and `healdan-core`, with no extensions.
  - **The assembly** bundles `healdan-api-<version>-plugin.jar` and `org.webjars:swagger-ui`.
  - **`ApiServlet extends CXFNonSpringServlet`**, `@WebServlet(urlPatterns = "/api/*", loadOnStartup = 2)`.
    - Constructors: a public no-arg one for Tomcat, and a package-private `ApiServlet(List<Object> extraBeans)` for tests.
    - Its `loadBus(ServletConfig)` gives the servlet a new `org.apache.cxf.bus.extension.ExtensionManagerBus` before calling `super`, then calls `ApiServer.start(getBus(), extraBeans)`.
  - **`ApiServer.start(Bus bus, List<Object> extraBeans)`** returns a `Server`. It builds a `JAXRSServerFactoryBean` with:
    - address `/`;
    - as resources, every Spring bean of a class in `com.logicaldoc.healdan.api` annotated `@Path`, plus `extraBeans`;
    - as providers, `ApiJson.provider()`, `new ProblemMapper()`, and (from Task 6) `new CorsFilter()` and `new TokenFilter()`;
    - the CXF properties `attachment-directory` = the temp dir, `attachment-memory-threshold` = `1048576`, and `attachment-max-size` = `upload.maxsize` MB in bytes.
  - **`ApiJson`:** `static ObjectMapper mapper()` and `static JacksonJsonProvider provider()`. The mapper writes `java.util.Date` as ISO 8601 UTC with `Z`, includes nulls, and fails on unknown properties.
  - **`ApiProblem extends RuntimeException`:**
    - constructor `ApiProblem(int status, String code, String detail)`;
    - `ApiProblem with(String name, Object value)`;
    - factories `notFound(String)`, `forbidden(String)`, `badRequest(String)`, `unauthenticated(String)`, `conflict(String code, String detail)` and `validation(List<FieldError> fields)`.
  - **`record FieldError(String field, String code, String detail)`.**
  - **`ProblemMapper implements ExceptionMapper<Throwable>`** writes `application/problem+json` with `type` `https://healdan.com/problems/<code>`, `title`, `status`, `detail`, `code` and any extras. It maps:
    - `ApiProblem` as itself;
    - `HealdanVetoException` to `409 refused`;
    - JSON parse and mapping errors to `400 bad-request`;
    - `WebApplicationException` to its own status (`404 not-found`, `401 unauthenticated`, `403 forbidden`, `413 too-large`, any other 4xx `bad-request`);
    - anything else to `500 server-error`, logged, with the detail `"Something went wrong on the server."`.
  - **`@Public`**, a method annotation: no token needed.
  - **`record Paging(int offset, int limit)`**, with `static Paging of(Integer offset, Integer limit)`, which throws `ApiProblem.badRequest` outside the ranges, and `<T> Page<T> slice(List<T> all)`.
  - **`record Page<T>(List<T> items, int offset, int limit, Long total)`**, with `static <T> Page<T> whole(List<T> items)` (offset 0, limit = size, total = size).
  - **`ServerResource`**, `@Path("/v1/server")`: `@GET @Public ServerInfo get()`.
  - **`record ServerInfo(String product, String version, String apiVersion, Map<String, String> labels, List<String> signIn)`.**
  - **`DocsResource`:**
    - `GET /v1/openapi.yaml` (`application/yaml`);
    - `GET /docs`, a `303` to `<context>/api/docs/`;
    - `GET /docs/`, `index.html`;
    - `GET /docs/{file: [A-Za-z0-9.-]+}`, from `META-INF/resources/webjars/swagger-ui/5.32.15/`, or `404`.

    All four are `@Public`.
  - **Test support:**
    - `abstract class ApiTestCase extends AbstractHealdanTestCase`. Its `setUp` starts an `ApiServlet` with `MockServletConfig`, and it offers:
      - `protected List<Object> extraBeans()` (default empty);
      - `protected ApiCall get(String path)`, and likewise `post`, `patch`, `delete` and `options`;
      - `protected String signIn(User user)`;
      - `protected String signInAdmin()`;
      - `protected final HealdanFixtures fx`.
    - `ApiCall`, built with:
      - `token(String)`, `header(String, String)` and `remote(String address)`;
      - `json(Object body)`;
      - `part(String name, String json)` and `file(String name, String fileName, byte[] content)`;
      - `send()`, which returns an `ApiResponse`.
    - `ApiResponse`: `int status()`, `String header(String)`, `byte[] body()`, `JsonNode json()` and `String code()`.
    - `Contract.check(...)`, which runs on every `send()` and fails the test when the response doesn't match `openapi.yaml`. It skips `/api/docs/**`, which the file doesn't describe.
    - `ApiTestCase.setUp` builds the servlet with `new ApiServlet(extraBeans())`.

- [ ] **Step 1: Write the failing tests**

- **`ServerResourceTest`:**
  - `testServerInfo`: `GET /api/v1/server` with no token answers `200` with:
    - `product` `Healdan`;
    - `version` equal to `SystemInfo.get().getRelease()`;
    - `apiVersion` `v1`;
    - `labels` `{matter: Matter, matters: Matters}`;
    - `signIn` `["password"]`.
  - `testLabelsFollowSettings`: after setting the labels to `Project` and `Projects`, the labels say so.
- **`DocsResourceTest`:**
  - `/api/v1/openapi.yaml` starts with `openapi: 3.1.0`.
  - `/api/docs` answers `303` with `Location` ending in `/api/docs/`.
  - `/api/docs/` is HTML that contains `swagger-ui-bundle.js` and `../v1/openapi.yaml`.
  - `/api/docs/swagger-ui-bundle.js` is `200` with a JavaScript content type.
  - `/api/docs/..%2Fpom.xml` is `404`.
- **`ProblemMapperTest`**, with a test-only resource from `extraBeans()` whose methods throw:
  - `GET /api/v1/nowhere` is `404 not-found` as `application/problem+json`.
  - A `HealdanRuleException("No.")` gives `409 refused` with detail `No.`.
  - A `RuntimeException("secret")` gives `500 server-error`, and its body doesn't contain `secret`.
  - A `POST` to `/api/v1/server` is `405` with code `bad-request`.
  - `GET /api/rest/system/getInfo` is `404` (spec correction 4).
- **`OpenApiFileTest`:** the file parses with no messages, and it declares a `bearerAuth` HTTP bearer scheme as the default security.
- **`RouteCoverageTest`:** the set of `METHOD /v1/path` pairs from the `@Path` resources (path templates normalized, docs routes excluded) equals the set in `openapi.yaml`.
- **`ApiPluginXmlTest`:** `healdan-api`'s `plugin.xml` parses, requires `healdan-core`, and has the same `version` as `healdan-core`'s.

- [ ] **Step 2: Create the module skeleton so the tests compile, and see them fail**

This means the POM, the test-jar in `healdan-core`, and the root `<module>` line.

Run: `mvn -B -ntp -pl healdan-core install -DskipTests && mvn -B -ntp -pl healdan-api test`
Expected: the tests fail (no servlet, no resources).

- [ ] **Step 3: Implement the servlet, server, JSON, problems, paging, `ServerResource` and `DocsResource`**

- [ ] **Step 4: Write `openapi.yaml`**

It has:
- `info.title` "Healdan API";
- `servers: [{url: /api}]`;
- `components.securitySchemes.bearerAuth` (`http`/`bearer`) as the default `security`;
- the schemas `Problem`, `FieldError`, `ServerInfo`, and a `Page` pattern using `allOf`;
- `/v1/server` (`security: []`) and `/v1/openapi.yaml` (`security: []`).

- [ ] **Step 5: Wire it into the build**

Add the webapp `artifactItem`, the `context-security.xml` line and the CI change.

- [ ] **Step 6: Run the tests and a WAR build**

Run:
- `mvn -B -ntp -pl healdan-api test`
- `mvn -B -ntp -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -pl '!logicaldoc-webapp' install`

Expected: PASS, and `healdan-api/target/healdan-api-9.3.1-plugin.zip` contains `WEB-INF/lib/healdan-api-9.3.1-plugin.jar` and `WEB-INF/lib/swagger-ui-5.32.15.jar`.

- [ ] **Step 7: Commit**

```bash
git add pom.xml healdan-api healdan-core/pom.xml logicaldoc-webapp/pom.xml logicaldoc-webapp/src/main/resources/context-security.xml .github/workflows/build-logicaldoc.yml
git commit -m "Add the healdan-api module with its servlet, errors and docs page"
```

---

### Task 6: Bearer tokens, sign-in, `/me`, `/users` and CORS

**Files:**
- Create in `healdan-api/src/main/java/com/logicaldoc/healdan/api/`:
  - `Caller.java`, `TokenFilter.java`, `CorsFilter.java`;
  - `AuthResource.java`, `MeResource.java`, `UserResource.java`, `ApiViews.java`;
  - `model/SignIn.java`, `model/SignedIn.java`, `model/TokenView.java`, `model/UserView.java`, `model/UserRef.java`, `model/UserHit.java`.
- Modify: `ApiServer.java` (register both filters), `openapi.yaml`
- Test: `AuthResourceTest.java`, `TokenFilterTest.java`, `MeResourceTest.java`, `UserResourceTest.java`, `CorsFilterTest.java`

**Interfaces:**
- Consumes: `ApiTokens`, `HealdanSettings.apiCorsOrigins()` (Task 2); `ApiProblem`, `@Public` (Task 5).
- Produces:
  - **`record Caller(User user, Session session, ApiToken token)`**, with `static Caller current()` (`IllegalStateException` outside a request), and package-private `set` and `clear`.
  - **`TokenFilter implements ContainerRequestFilter, ContainerResponseFilter`**, using `@Context ResourceInfo` and `@Context HttpServletRequest`. For a resource method that isn't `@Public`:
    1. Read `Authorization: Bearer <token>`; the scheme matches case-insensitively. A missing header gets `401 unauthenticated` with `WWW-Authenticate: Bearer`.
    2. Find the token and check that it's usable.
    3. Load the user with `findById(id, true)`. A missing, deleted or disabled user gets `401`.
    4. Renew the bound CE session if it's open. Otherwise call `SessionManager.get().newSession(token, SessionManager.get().buildClient(request))` and `bind` the new sid.
    5. `touch` the token.
    6. `Caller.set(...)`, and put an `LDAuthenticationToken` (the user's groups as authorities, `setSid`) into `SecurityContextHolder`.

    The response filter clears both.
  - **`CorsFilter`** (`@PreMatching` request filter plus response filter), for an `Origin` in `apiCorsOrigins()`:
    - It answers a preflight (`OPTIONS` with `Access-Control-Request-Method`) with `204` and these headers:
      - `Access-Control-Allow-Origin: <origin>`;
      - `Access-Control-Allow-Methods: GET, POST, PATCH, DELETE`;
      - `Access-Control-Allow-Headers: Authorization, Content-Type`;
      - `Access-Control-Max-Age: 600`;
      - `Vary: Origin`.
    - It adds `Access-Control-Allow-Origin`, `Vary: Origin` and `Access-Control-Expose-Headers: Location, Content-Disposition` to other responses.
    - A preflight from an unlisted origin gets `403 forbidden`. Other requests from an unlisted origin get no CORS headers.
  - **`AuthResource`**, `@Path("/v1/auth/tokens")`:
    - `@POST @Public` → `201 SignedIn`;
    - `@GET` → `Page<TokenView>` (whole);
    - `@DELETE @Path("current")` → `204`;
    - `@DELETE @Path("{id}")` → `204`, or `404` when the token isn't the caller's.
  - **`MeResource`**, `@Path("/v1/me")`: `@GET UserView me()`.
  - **`UserResource`**, `@Path("/v1/users")`: `@GET Page<UserHit> find(@QueryParam("q") String q)` (whole, at most 20).
  - **The records:**
    - `SignIn(String username, String password, String name)`;
    - `SignedIn(String token, long id, String name, Date expiresAt, UserView user)`;
    - `TokenView(long id, String name, Date created, Date lastUsed, Date expiresAt, boolean current)`;
    - `UserView(long id, String username, String name, String email, String language, boolean admin)`;
    - `UserRef(long id, String name)`;
    - `UserHit(long id, String username, String name)`.
  - **`ApiViews`:** `static UserView user(User user)` and `static UserRef userRef(Long id, String name)`.
  - **Sign-in error mapping:**
    - `UsernameBlockedException`, `IPBlockedException` and `ApiKeyBlockedException` → `429 too-many-attempts`, detail `"Too many failed sign-ins. Try again in 15 minutes."` (the wait comes from the config);
    - `PasswordExpiredException` → `401 password-expired`, detail `"Your password has expired. Change it in Healdan's web page, then sign in again."`;
    - any other `AuthenticationException` → `401 sign-in-failed`, detail `"The username or password is wrong, or this account can't sign in."`;
    - a missing `username` or `password` → `422 validation`.
  - **`/users` matching:** active (`enabled = 1`), not deleted, type `DEFAULT`. Matches `q` case-insensitively against the username, first name, last name and "first last". Sorted by full name. A blank `q` is `400`.

- [ ] **Step 1: Write the failing tests**

- **`AuthResourceTest`:**
  - `testSignIn`: `clerk` gets `201`; the token matches the pattern; `user.username` is `clerk`; `expiresAt` is now + 365 d ± 1 min.
  - `testWrongPasswordUnknownUserAndDisabledLookAlike`: all three get `401 sign-in-failed` with the same detail.
  - `testExpiredPassword`: a user with `ld_passwordexpired = 1` gets `401 password-expired`.
  - `testThrottled`: after 5 wrong passwords, the 6th sign-in gets `429 too-many-attempts`.
  - `testMissingFields`: `422` with `fields` naming `username` and `password`.
  - `testUnknownField`: `{"username": …, "password": …, "colour": "red"}` gets `400 bad-request`.
  - `testListAndRevoke`:
    - Two sign-ins give two tokens, listed with `current` set only on the calling one.
    - `DELETE /{otherId}` is `204`, and that token now gets `401`.
    - `DELETE` of `boss`'s token id is `404`.
  - `testSignOut`: `DELETE /current` is `204`, and the same token then gets `401`.
- **`TokenFilterTest`:**
  - `testNoToken`: `401 unauthenticated` with `WWW-Authenticate: Bearer`.
  - `testCookieAndSidIgnored`: a valid CE session for `clerk`, sent as the `ldoc-sid` cookie and as the `sid` header, gets `401`.
  - `testTokenOutlivesItsSession`: after killing the bound sid, `GET /me` is `200`, the row's `hd_sid` changes, and the new session is open with client address `10.1.2.3` (from `remote(...)`).
  - `testDisabledAndDeletedUsers`: disabling by JDBC gets `401`, and so does `UserDAO.delete`.
  - `testExpiredToken`: `hd_lastused` at now − 31 d gets `401`.
  - `testSecurityContextCleared`: after a request, `SecurityContextHolder.getContext().getAuthentication()` is `null` on the test thread.
  - `testBlockedUsernameKeepsTokensWorking` (Review Focus 3): sign `clerk` in, make 5 wrong `POST /auth/tokens` for `clerk`, and kill the bound sid. `GET /me` with the token is still `200`.
- **`MeResourceTest`:** `testMe` returns the fields for `clerk` (`admin` false) and for `boss` (`admin` true).
- **`UserResourceTest`:**
  - `testFind`:
    - `q=cle` finds `clerk`;
    - a first-name match finds the user;
    - disabled users and `_system` are left out;
    - 25 matching users return 20.
  - `testBlankQuery`: `400`.
- **`CorsFilterTest`:**
  - With no setting, a preflight from `https://localhost:3000` is `403`.
  - With the setting, it's `204` with exactly the headers above, and it needs no token.
  - With the setting, a `GET /me` with the token and that `Origin` echoes it, with `Vary` and the expose header.
  - An unlisted origin gets no `Access-Control-Allow-Origin`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='AuthResourceTest,TokenFilterTest,MeResourceTest,UserResourceTest,CorsFilterTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the classes, register the filters, and describe the five operations in `openapi.yaml`**

- [ ] **Step 4: Run the whole `healdan-api` suite**

Run: `mvn -B -ntp -pl healdan-api test`
Expected: PASS, including `RouteCoverageTest`.

- [ ] **Step 5: Commit**

```bash
git add healdan-api
git commit -m "Sign API clients in with device tokens"
```

---

### Task 7: Reading documents

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/security/AccessChecks.java`
- Create in `healdan-api/.../api/`: `DocumentResource.java`, `VersionResource.java`, `model/DocumentView.java`, `model/HoldView.java`, `model/FolderRef.java`, `model/MatterRef.java`, `model/VersionView.java`
- Modify: `ApiViews.java`, `openapi.yaml`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/security/AccessChecksTest.java`, `healdan-api/.../DocumentResourceTest.java`, `VersionResourceTest.java`

**Interfaces:**
- Consumes: `Caller` (Task 6), `Paging`/`Page` (Task 5).
- Produces:
  - **`AccessChecks`** (final, static):
    - `static boolean isAdmin(User user) throws PersistenceException`, which is `FilingRules.get().isAdmin(user)`;
    - `static Optional<Document> readableDocument(long docNumber, User user) throws PersistenceException`;
    - `static Optional<Document> readableDocument(Document document, User user) throws PersistenceException`;
    - `static boolean allowed(User user, Permission permission, Document document) throws PersistenceException`;
    - `static Optional<Folder> readableFolder(long folderId, User user) throws PersistenceException`;
    - `static boolean allowed(User user, Permission permission, Folder folder) throws PersistenceException`.

    A readable document exists, isn't deleted, passes `DocumentDAO.isReadAllowed`, and is published unless the user is in `admin` or `publisher`.
  - **The records:**
    - `DocumentView(String type, long docNumber, String name, long size, String version, String fileVersion, String reference, FolderRef folder, MatterRef matter, Date dateFiled, Date lastModified, HoldView hold, boolean canEdit)`, with `type` = `"document"`;
    - `HoldView(String kind, UserRef by, Date since)`, where `kind` is `checkout` or `lock`;
    - `FolderRef(long id, String name, String kind)`;
    - `MatterRef(long id, String display)`;
    - `VersionView(String version, String fileVersion, String kind, Date date, UserRef by, String comment, String event)`.
  - **`ApiViews`:**
    - `static DocumentView document(Document d, User caller) throws PersistenceException`;
    - `static HoldView hold(Document d) throws PersistenceException`, whose `since` is the latest `event.checkedout` or `event.locked` date in `ld_history` for the document;
    - `static MatterRef matterOf(long folderId) throws PersistenceException`;
    - `static String folderKind(FolderPosition position)`.

    `canEdit` is true when the user has WRITE, the document isn't immutable, and there's no hold or the hold is the caller's own check-out.
  - **`DocumentResource`**, `@Path("/v1/documents")`:
    - `@GET @Path("{docNumber: [0-9]+}") DocumentView get(...)`;
    - `@GET @Path("{docNumber: [0-9]+}/content") Response content(@PathParam long docNumber, @QueryParam("version") String version)`.
  - **`VersionResource`**, `@Path("/v1/documents/{docNumber: [0-9]+}/versions")`: `@GET Page<VersionView> list(...)`, newest first by `versionDate`. `kind` is `version` when the minor number is 0, `snapshot` otherwise.
  - **Content:**
    - It needs READ (else `404`) and DOWNLOAD (else `403`).
    - `?version=` resolves through `VersionDAO.findByVersion(...).getFileVersion()`; a missing version is `404` with `"Document #%d has no version %s"`.
    - It streams `Store.get().getStream(StoreResource.builder().docId(id).fileVersion(fv).build())` with `Content-Type` from the file name, `Content-Length`, and `Content-Disposition: attachment; filename="<ASCII fallback>"; filename*=UTF-8''<percent-encoded>`.
    - It records `event.downloaded` as `ServletUtil.saveHistory` does, including the 30-second de-duplication.

- [ ] **Step 1: Write the failing tests**

- **`AccessChecksTest`:**
  - `readableDocument` is empty for an unknown number, a deleted document, a document without READ, and an unpublished one (unless the user is in `admin` or `publisher`).
  - It's present through a document-only grant (folder unreadable).
- **`DocumentResourceTest`:**
  - `testGet`: filed `a.docx` gives `docNumber` (the doc #), `version` `1.0`, `fileVersion` `1.0`, `reference` `<n>.1.0`, the folder `Documents`, the matter display `998.1 – Demo`, `hold` null, `canEdit` true.
  - `testHoldShown`: after `checkout` by `boss`, `clerk` sees `hold.kind` `checkout`, `hold.by.name` = `boss`'s full name, `since` ≈ now, and `canEdit` false.
  - `testUnreadable`: a document `clerk` can't read is `404` with the same body as an unknown number.
  - `testContent`:
    - The bytes match the upload.
    - `?version=1.0` after a check-in returns the old bytes.
    - `?version=9.9` is `404`.
    - Without DOWNLOAD it's `403`.
    - An `event.downloaded` row exists, and only once for two downloads 1 s apart.
  - `testContentDispositionKeepsTheName` (Review Focus 1): for `Board minutes – é.docx`, the header contains `filename*=UTF-8''Board%20minutes%20%E2%80%93%20%C3%A9.docx`.
- **`VersionResourceTest`:** after a snapshot and a version (made with `DocumentManager` in the fixture), the list is `2.0`, `1.1`, `1.0` with kinds `version`, `snapshot`, `version`, and `by` and `event` are set. Unreadable is `404`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=AccessChecksTest`, then install `healdan-core` and run `mvn -B -ntp -pl healdan-api test -Dtest='DocumentResourceTest,VersionResourceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement them, and describe the three operations in `openapi.yaml`**

- [ ] **Step 4: Run the tests**

Run: the same commands, plus `mvn -B -ntp -pl healdan-api test -Dtest=RouteCoverageTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/security/AccessChecks.java healdan-core/src/test/java/com/logicaldoc/healdan/security/AccessChecksTest.java healdan-api
git commit -m "Read documents, versions and content through the API"
```

---

### Task 8: Folders and matters

**Files:**
- Create in `healdan-api/.../api/`: `FolderResource.java`, `MatterResource.java`, `model/FolderView.java`, `model/MatterView.java`, `model/StoreRef.java`, `model/ClientRef.java`
- Modify: `ApiViews.java`, `openapi.yaml`
- Test: `FolderResourceTest.java`, `MatterResourceTest.java`

**Interfaces:**
- Consumes: `AccessChecks`, `ApiViews.document`, `FolderRef`, `MatterRef` (Task 7); `MatterLocator`, `MyMatters`, `RecentMatters`, `SearchFolderService` (healdan-core).
- Produces:
  - **The records:**
    - `FolderView(String type, long id, String name, String kind, MatterRef matter, StoreRef store, List<FolderRef> path, boolean canFile)`, with `type` = `"folder"`;
    - `MatterView(String type, long id, long number, String display, String name, String description, ClientRef client, StoreRef store, long folderId, Date lastChange)`, with `type` = `"matter"`;
    - `StoreRef(long id, String name)`;
    - `ClientRef(long id, long number, String name)`.
  - **`ApiViews`:**
    - `static Optional<FolderView> folder(Folder f, User caller) throws PersistenceException`, empty for folders outside Stores (correction 8);
    - `static MatterView matter(Matter m, Date lastChange) throws PersistenceException`.
  - **`FolderResource`**, `@Path("/v1/folders")`:
    - `@GET @Path("{id: [0-9]+}") FolderView get(...)`;
    - `@GET @Path("{id: [0-9]+}/children") Page<Object> children(@PathParam long id, @QueryParam("type") String type, @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit)`.
  - **`MatterResource`**, `@Path("/v1/matters")`:
    - `@GET @Path("{id: [0-9]+}") MatterView get(...)`;
    - `@GET @Path("mine") Page<MatterView> mine()`;
    - `@GET @Path("recent") Page<MatterView> recent()`.
  - **Rules:**
    - `kind` comes from `MatterLocator.locate`: `STORE` → `store`; `MATTER_WORKSPACE` → `matter`; `IN_MATTER` → `searchFolder` when `position.searchFolder()`, else `folder`.
    - `path` runs from the Store down to the folder itself (`FolderDAO.findParents` plus the folder).
    - `canFile` is `kind == folder`, `depth >= 1`, and `FolderDAO.isWriteAllowed`.
  - **Children:**
    - A Store gets `409 not-browsable`, detail `"A Store's matters aren't listed here. Search, or use My Matters or Recent Matters."`
    - A matter or folder gives its readable, non-alias, non-hidden subfolders (by name, ignoring case), then its readable documents (by name). `?type=folder` gives folders only. `?type=` with any other value is `400`.
    - A search folder gives `SearchFolderService.evaluate(id, user)`, capped at 1000, in its order.
    - `total` is the full count.
    - An unknown, unreadable or outside folder is `404`.

- [ ] **Step 1: Write the failing tests**

- **`FolderResourceTest`:**
  - `testKindsAndPath`: the Store, the matter workspace, `Documents` and a search folder each give the right `kind` and `matter`. The path of `Documents/Sub` is `[Store, 998.1 – Demo, Documents, Sub]`.
  - `testCanFile`: false on the workspace, a search folder and the Store; true on `Documents` with WRITE; false on `Documents` with READ only.
  - `testOutsideStoresIsNotFound`: the Default workspace and the Templates Store are `404`.
  - `testUnreadableIsNotFound`.
  - `testChildren`:
    - two subfolders `b` and `A`, then documents `y.docx` and `x.docx`, come back as `A`, `b`, `x.docx`, `y.docx`, with `type`s `folder`, `folder`, `document`, `document`;
    - `?type=folder` returns 2;
    - an unreadable subfolder and an unreadable document are left out.
  - `testStoreNotBrowsable`: `409 not-browsable`.
  - `testSearchFolderChildren`: they're the rule's documents.
  - `testPagingEdges` (Review Focus 5):
    - `offset=10&limit=2` on 4 items gives an empty `items` and `total` 4;
    - `limit=200` works;
    - `limit=201` and `offset=-1` are `400`.
- **`MatterResourceTest`:**
  - `testGet`: the fields, including `client.name` and `store.name`. An unreadable workspace is `404`.
  - `testMine`: after `MyMatters.add`, the matter is listed and `lastChange` is null.
  - `testRecent`: after filing into two matters, both are listed, newest first, with `lastChange` set.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='FolderResourceTest,MatterResourceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement them, and describe the five operations in `openapi.yaml`**

- [ ] **Step 4: Run the `healdan-api` suite**

Run: `mvn -B -ntp -pl healdan-api test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-api
git commit -m "Browse matters and their folders through the API"
```

---

### Task 9: Recent folders, recent documents, check-outs and Stores

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/navigation/RecentFolders.java`, `RecentDocuments.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/matter/MatterDAO.java`, `HibernateMatterDAO.java`
- Create: `healdan-api/.../api/StoresResource.java`
- Modify: `FolderResource.java` (`recent`), `DocumentResource.java` (`recent`), `MeResource.java` (`checkouts`), `openapi.yaml`
- Test: `healdan-core/.../navigation/RecentFoldersTest.java`, `RecentDocumentsTest.java`, `healdan-core/.../matter/MatterDAOStoreTest.java`, `healdan-api/.../ListsTest.java`, `StoresResourceTest.java`

**Interfaces:**
- Consumes: `AccessChecks`, `ApiViews` (Tasks 7 and 8).
- Produces:
  - **`RecentFolders`** (`@Component`):
    - `static RecentFolders get()`;
    - `static final int MAX_ENTRIES = 20`;
    - `List<RecentFolder> list(long userId) throws PersistenceException`;
    - `record RecentFolder(long folderId, Date lastChange)`.

    It uses `RecentMatters`' query (`CHANGE_EVENT_KEYS`, the `recentmatters.days` window), keeping folders that are readable, live, `IN_MATTER` and not search folders.
  - **`RecentDocuments`** (`@Component`):
    - `static RecentDocuments get()`;
    - `static final int MAX_ENTRIES = 50`;
    - `List<RecentDocument> list(long userId) throws PersistenceException`;
    - `record RecentDocument(long docId, Date lastAccess)`.

    The events are `CHANGE_EVENT_KEYS` plus `event.checkedout` and `event.downloaded`, grouped by document, newest first, and kept when `AccessChecks.readableDocument` passes.
  - **`MatterDAO`:**
    - `List<Matter> findByStore(long storeId, int offset, int limit) throws PersistenceException`, for live matters whose workspace's parent is `storeId`, ordered by client number, then matter number;
    - `long countByStore(long storeId) throws PersistenceException`.
  - **Endpoints:**
    - `FolderResource`: `@GET @Path("recent") Page<FolderView> recent()`.
    - `DocumentResource`: `@GET @Path("recent") Page<DocumentView> recent()`.
    - `MeResource`: `@GET @Path("checkouts") Page<DocumentView> checkouts()`. It uses `DocumentDAO.findByLockUserAndStatus(userId, DocumentStatus.CHECKEDOUT)`, keeps readable documents, newest hold first.
    - `StoresResource`, `@Path("/v1/stores")`:
      - `@GET Page<StoreRef> list(...)`, from `MatterService.stores()`;
      - `@GET @Path("{id: [0-9]+}/matters") Page<MatterView> matters(...)`.

      Both are admins only (`AccessChecks.isAdmin`); anyone else gets `403 forbidden`. An id that isn't a Store is `404`.

- [ ] **Step 1: Write the failing tests**

- **`RecentFoldersTest`:**
  - Filings in `Documents`, then in `Sub`, list `Sub` first.
  - A change outside the window is left out.
  - A folder the user can no longer read is left out.
  - Another user's changes don't count.
- **`RecentDocumentsTest`:**
  - Filing, check-out, check-in and a recorded `event.downloaded` each put a document on the list, newest first.
  - Unreadable documents are left out.
  - At most 50 are listed.
- **`MatterDAOStoreTest`:**
  - Matters 998.2, 998.1 and 997.5 in one Store, plus one in another Store, come back as 997.5, 998.1, 998.2.
  - Counting gives 3.
  - A deleted workspace is left out.
- **`ListsTest`** (API):
  - `/folders/recent`, `/documents/recent` and `/me/checkouts` return the `FolderView` and `DocumentView` shapes.
  - `/me/checkouts` leaves out documents checked out by others and documents the user can't read.
- **`StoresResourceTest`:**
  - For admin `boss`, the list leaves out Default and Templates, and `matters` pages in order, with `total`.
  - `clerk` gets `403` on both.
  - Matters of a non-Store folder id are `404`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='RecentFoldersTest,RecentDocumentsTest,MatterDAOStoreTest'`, then install `healdan-core` and run `mvn -B -ntp -pl healdan-api test -Dtest='ListsTest,StoresResourceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement them, and describe the five operations in `openapi.yaml`**

- [ ] **Step 4: Run the tests**

Run: the same commands, plus the `healdan-api` suite.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/navigation healdan-core/src/main/java/com/logicaldoc/healdan/matter healdan-core/src/test/java/com/logicaldoc/healdan healdan-api
git commit -m "List recent folders and documents, check-outs and a Store's matters"
```

---

### Task 10: Search, `#` lookup and legacy doc #

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/docnumber/LegacyDocNumbers.java`
- Create: `healdan-api/.../api/SearchResource.java`, `model/LookupView.java`
- Modify: `DocumentResource.java` (`GET ?legacyDocNumber=`), `openapi.yaml`
- Test: `healdan-core/.../docnumber/LegacyDocNumbersTest.java`, `healdan-api/.../SearchResourceTest.java`, `LookupTest.java`

**Interfaces:**
- Consumes: `AccessChecks`, `ApiViews.document`/`folder`/`matter` (Tasks 7 and 8); `Search`, `DocNumberLookup`, `DocNumberQuery`, `HealdanConstants.SEARCH_*`.
- Produces:
  - **`LegacyDocNumbers`** (final, static): `static List<Long> find(String legacyNumber, long tenantId) throws PersistenceException`. It returns the ids of live documents whose `legacydocnum` string value equals the input exactly.
  - **`SearchResource`**, `@Path("/v1")`:
    - `@GET @Path("search") Page<Object> search(@QueryParam("scope") String scope, @QueryParam("q") String q, offset, limit)`;
    - `@GET @Path("lookup") LookupView lookup(@QueryParam("q") String q)`.
  - **`record LookupView(long docNumber, String version)`.**
  - **`DocumentResource`:** `@GET Page<DocumentView> byLegacy(@QueryParam("legacyDocNumber") String legacy)`. A missing or blank parameter is `400`.
  - **Scopes** (spec §6.3). `scope` defaults to `documents`; any other value is `400`. A blank `q` is `400`. The search type for each scope:

    | `scope` | Search type |
    |---|---|
    | `documents` | 101 when `DocNumberQuery.looksLikeNumber(q)`, otherwise 104 |
    | `emails` | 105 |
    | `all` | 0 |
    | `folders` | 3, with a case-insensitive `name contains q` criterion |
    | `matters` | 102 |
    | `clients` | 103 |
  - **Search options:** `userId`, `tenantId`, `maxHits = offset + limit`, and `transaction = new UserHistory(caller.session())`. For full text, `expressionLanguage` is the user's language.
  - **Hits:**
    - Document hits become `ApiViews.document`, after `AccessChecks.readableDocument`.
    - Folder hits become `ApiViews.folder`; folders outside Stores are dropped.
    - Matter hits (types 102 and 103) become `MatterDAO.findByFolderId(hit.getId())` → `ApiViews.matter`.
    - `total` is `null`.
    - A `SearchException` is `500`.
  - **Lookup:**
    - Not `looksLikeNumber` → `400 bad-request` with `DocNumberLookup`'s `NOT_A_NUMBER` message.
    - Not found → `404` with the result's message.
    - Otherwise `{docNumber, version}`.

- [ ] **Step 1: Write the failing tests**

- **`LegacyDocNumbersTest`:**
  - Two documents with legacy `ABC-1` are both found.
  - `ABC` finds nothing.
  - A deleted document is left out.
- **`SearchResourceTest`:**
  - `testDocNumber`: `q=#<n>` returns exactly that document. `#<n>` for an unreadable document returns empty `items`.
  - `testFullText`: index the fixtures as `healdan-core`'s `FilteredFulltextTest` does. A token in a `.docx` is found in `documents` and in `all`, but not in `emails`. An `.eml` token is found in `emails`.
  - `testFolders`: `q=docu` finds `Documents`, ignoring case.
  - `testMattersAndClients`:
    - `q=998.1` returns the matter with `type` `matter`;
    - the name text returns it;
    - `scope=clients` with the client's name returns its matters.
  - `testBadInput`: `scope=nope` and an empty `q` are `400`.
  - `testPaging`: `offset=1&limit=1` returns the second hit, and `total` is `null`.
- **`LookupTest`:**
  - `#n`, `#n.1`, `#n.1.0` and `#nv1` resolve.
  - An unknown number is `404 "No document #<n>"`, and a missing version is `404 "Document #<n> has no version 9"`.
  - `1234`, with no `#`, is `400`.
  - `?legacyDocNumber=ABC-1` lists both readable documents.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=LegacyDocNumbersTest`, then install `healdan-core` and run `mvn -B -ntp -pl healdan-api test -Dtest='SearchResourceTest,LookupTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement them, and describe the three operations in `openapi.yaml`**

- [ ] **Step 4: Run the tests and the `healdan-api` suite**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/docnumber healdan-core/src/test/java/com/logicaldoc/healdan/docnumber healdan-api
git commit -m "Search, look up doc numbers and find legacy numbers through the API"
```

---

### Task 11: The properties form and document properties

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/profile/ProfileView.java`
- Create in `healdan-api/.../api/`: `FormResource.java`, `PropertiesResource.java`, `PropertyValues.java`, `model/ProfileFieldView.java`, `model/OptionView.java`, `model/PropertyPatch.java`
- Modify: `openapi.yaml`
- Test: `healdan-core/.../profile/ProfileViewTest.java`, `healdan-api/.../FormResourceTest.java`, `PropertiesResourceTest.java`

**Interfaces:**
- Consumes: `AccessChecks`, `ApiViews` (Tasks 7 and 8); `Inheritance`, `LocalValueDAO`, `ProfileSchema`, `MatterLocator`, `HealdanSettings`.
- Produces:
  - **`ProfileView`** (`@Component`):
    - `static ProfileView get()`;
    - `List<ProfileField> forNewDocument(long folderId, User user) throws PersistenceException`;
    - `List<ProfileField> forDocument(Document document) throws PersistenceException`;
    - `record ProfileField(String name, String label, int type, boolean required, boolean editable, Object value, Source source, Long inheritedFromId, String inheritedFromName, List<Option> options)`;
    - `enum Source { INHERITED, LOCAL, DEFAULT }`;
    - `record Option(String value, String label, String docClass)`.
  - **`ProfileView` rules:**
    - **Fields** are the profile template's attributes in `position` order, skipping `TYPE_SECTION` and hidden ones. `required` = `isMandatory()`.
    - **`editable`** is false for `client`, `matter`, the six email fields and read-only attributes.
    - **Values on a new document:** inheritable values come from `Inheritance.inheritedFrom(folderId)`. `author` and `typist` are the user, with `source` `DEFAULT`.
    - **Values on an existing document:** `document.getValue(name)`.
    - **Source, on a document:** an attribute in `LocalValueDAO.locals('D', docId)` is `LOCAL`.
    - **Source, otherwise:** walk from the folder up to the matter workspace. The first folder with the attribute in `locals('F', id)` makes it `INHERITED` from that folder. `client` and `matter` are `INHERITED` from the workspace.
    - **Defaults and empty values:** `docclass` with no source and the install default as its value is `DEFAULT`. Document-only attributes that have a value are `LOCAL`. A field with no value has a `null` source.
    - **Options:** listbox attributes (`EDITOR_LISTBOX`) get `AttributeOptionDAO.findByAttribute(setId, name)`; the `label` falls back to the value, and `docClass` is the option's `category`. Other attributes get `null`.
  - **API side:**
    - `ProfileFieldView(String name, String label, String type, boolean required, boolean editable, Object value, String source, FolderRef inheritedFrom, List<OptionView> options)`. The type strings are `string`, `integer`, `decimal`, `date`, `boolean`, `user`, `folder` and `document`. A `user` value is `UserRef`; dates are ISO.
    - `OptionView(String value, String label, @JsonProperty("class") String docClass)`.
    - `PropertyPatch(Map<String, JsonNode> values)`.
    - `PropertyValues`: `static Object fromJson(Attribute attribute, JsonNode node, List<FieldError> errors)` and `static Object toJson(Attribute attribute, Object value)`. JSON `null` clears. A wrong JSON type adds `FieldError(name, "type", …)`. User, folder and document ids are loaded, and an unknown id adds `FieldError(name, "unknown", …)`.
  - **Endpoints:**
    - `FormResource`, `@Path("/v1/folders/{folderId: [0-9]+}/form")`: `@GET List<ProfileFieldView>` wrapped as `Page.whole`. It needs a readable folder inside a matter (else `409 filing-rule` or `404`).
    - `PropertiesResource`, `@Path("/v1/documents/{docNumber: [0-9]+}/properties")`:
      - `@GET`, which needs READ;
      - `@PATCH`, which needs WRITE (else `403`) and no hold by someone else (else `409`).

      `PATCH` validates every value first (`422` with all `fields`):
      - an unknown name is `unknown`;
      - a non-editable field is `read-only`;
      - a type mismatch is `type`;
      - a required field left with no value is `required`;
      - a Subclass not in the Class is `class-mismatch` (`ProfileSchema.subclassBelongs`).

      Then it updates through `DocumentManager.get().update(document, docVO, history)`, with event `CHANGED`. The value object is a copy of the document with the values set. It returns the new properties.

- [ ] **Step 1: Write the failing tests**

- **`ProfileViewTest`:**
  - `testNewDocumentInDocuments`: `client` and `matter` hold the numbers, are `INHERITED` from the workspace and aren't editable. `docclass` is `Docs`, `DEFAULT`. `author` is the user, `DEFAULT`. The email fields aren't editable, and `docclass` is required.
  - `testInheritedFromTheNearestFolder`: `Documents` sets Class `Letters`. In `Documents/Sub/Deeper`, the field is `Letters`, `INHERITED` from `Documents`.
  - `testDocumentOverride`: a document filed with Class `Memos` is `LOCAL`. After updating it back to the inherited value, it's `INHERITED`.
  - `testSubclassOptionsCarryTheirClass`.
  - `testAdminAddedAttribute`: add a mandatory string attribute `court` to the profile template. It appears, `required`, `editable`.
- **`FormResourceTest`:**
  - The JSON shape and type strings match.
  - The workspace's form is `409 filing-rule`.
  - An unreadable folder is `404`.
- **`PropertiesResourceTest`:**
  - `testOverrideAndInheritAgain`: `docclass` `Letters` becomes `source` `local`. Sending the inherited value makes it `inherited`. `null` makes it `inherited`.
  - `testLegacyDocNumber`: set and clear.
  - `testRefusals`, all in one `422` per case:
    - `colour` is `unknown`;
    - `client` is `read-only`;
    - `docclass: 5` is `type`;
    - a Subclass of another Class is `class-mismatch`;
    - `author: 99999` is `unknown`;
    - `court: null` is `required`.

    Nothing changes in any of them.
  - `testUserAndDateFields`: `author` set to `boss`'s id comes back as `{id, name}`. A date attribute added to the template round-trips `2026-01-31T00:00:00Z`.
  - `testPermissions`: read-only gives `403`; unreadable gives `404`; another user's check-out gives `409 checked-out`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=ProfileViewTest`, then install `healdan-core` and run `mvn -B -ntp -pl healdan-api test -Dtest='FormResourceTest,PropertiesResourceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement them, and describe the three operations in `openapi.yaml`**

The `ProfileField` schema uses `oneOf` for `value`.

- [ ] **Step 4: Run the tests and both suites' affected tests**

Run: the commands above, plus `mvn -B -ntp -pl healdan-core test -Dtest='Inheritance*Test,Profile*Test'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/profile/ProfileView.java healdan-core/src/test/java/com/logicaldoc/healdan/profile/ProfileViewTest.java healdan-api
git commit -m "Show and edit document properties, with where each value comes from"
```

---

### Task 12: Filing a new document

**Files:**
- Modify: `healdan-api/.../api/DocumentResource.java` (`@POST`)
- Create: `healdan-api/.../api/model/NewDocument.java`, `Uploads.java` (multipart helpers)
- Modify: `openapi.yaml`
- Test: `healdan-api/.../FilingTest.java`

**Interfaces:**
- Consumes: `ProfileView`, `PropertyValues` (Task 11); `AccessChecks`, `ApiViews` (Tasks 7 and 8).
- Produces:
  - `record NewDocument(Long folderId, String name, Map<String, JsonNode> properties, Boolean checkOut)`.
  - `DocumentResource`: `@POST @Consumes("multipart/form-data") Response create(@Multipart("document") String document, @Multipart("file") Attachment file)`, returning `201` with the `DocumentView` and `Location: /api/v1/documents/<n>`.
  - `Uploads`:
    - `static File toTempFile(Attachment file) throws IOException`;
    - `static String fileName(Attachment file)` (from `Content-Disposition`; RFC 5987 `filename*` wins).
  - **Order of checks.** Nothing is stored unless every check passes.
    1. Parse `document`: bad JSON or an unknown field is `400`. A missing `file` part is `400`.
    2. The folder must be readable, else `404`.
    3. Its kind must be `folder` at depth ≥ 1, else `409 filing-rule` with `FilingRules`' message for the place.
    4. The user needs WRITE on the folder, else `403`.
    5. **The name** is `name`, or the file part's name when that's blank. It must have an extension, and must not contain `/`, `\` or control characters. Its extension must not be in `<tenant>.upload.disallow`. Each failure is `422`, on field `name`, with codes `extension`, `invalid` and `disallowed`.
    6. **The file.** An empty file is `422` (`file`, `empty`). One over `upload.maxsize` is `413 too-large`.
    7. **Properties.** Convert them, then compute the effective values over `ProfileView.forNewDocument`. A required field without a value is `422 required`.
    8. **Create.** Build `Document vo` with the folder, the file name, `tenantId`, `language` = the user's, and the values. Call `DocumentManager.get().create(file, vo, history).get()` with a `DocumentHistory` that has `setSession(caller.session())` and `STORED`.
    9. When `checkOut` is true, `DocumentManager.get().checkout(docId, new DocumentHistory(...))`.

- [ ] **Step 1: Write the failing tests** (`FilingTest`)

- `testFiles`: a `.docx` into `Documents` gives `201`, the next doc #, `Location`, `version` `1.0`, `dateFiled` ≈ now, an inherited Class, and `author` = the caller.
- `testOverrideOnCreate`: `properties: {docclass: "Memos"}` makes `docclass` `local`.
- `testCheckOut`: `checkOut: true` leaves `hold` as the caller's `checkout`.
- `testFilingRule`:
  - the workspace is `409 filing-rule` with `"Documents can't be saved at the top level of a matter. Choose one of its folders."`;
  - a search folder is `409 filing-rule`;
  - the Store is `409 filing-rule`.
- `testPermissions`: READ only gives `403`; unreadable gives `404`.
- `testNothingStoredOnRefusal`: a missing required `court` is `422`, and the document count in the folder doesn't change.
- `testFileRules`:
  - an empty file is `422`;
  - with `default.upload.maxsize=1`, a 2 MB file is `413`;
  - with `default.upload.disallow=exe`, `a.exe` is `422 disallowed`;
  - a name without an extension is `422 extension`.
- `testBadRequests`: an unknown field in `document` is `400`; no `file` part is `400`.
- `testEmail`: an `.eml` named `x.eml` comes back named after its subject, with `emailfrom` set.
- `testNameKept` (Review Focus 1): `Board minutes – é.docx`, sent only as the file part's `filename*`, is filed under exactly that name.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest=FilingTest`
Expected: failure (no POST route).

- [ ] **Step 3: Implement it, and describe the operation in `openapi.yaml`**

The schema is `multipart/form-data` with an `encoding` for `document: application/json`.

- [ ] **Step 4: Run the `healdan-api` suite**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-api
git commit -m "File new documents through the API"
```

---

### Task 13: Check-out, release and saves

**Files:**
- Create in `healdan-api/.../api/`: `CheckoutResource.java`, `DocumentLocks.java`, `model/NewVersion.java`
- Modify: `VersionResource.java` (`@POST`), `openapi.yaml`
- Test: `CheckoutResourceTest.java`, `SaveTest.java`

**Interfaces:**
- Consumes: `Uploads` (Task 12); `AccessChecks`, `ApiViews` (Task 7).
- Produces:
  - `DocumentLocks` (`@Component`): `<T> T withLock(long docId, Callable<T> work) throws Exception`. It's one `ReentrantLock` per document id, removed once nobody holds it.
  - `CheckoutResource`, `@Path("/v1/documents/{docNumber: [0-9]+}/checkout")`: `@POST DocumentView checkout()` and `@DELETE DocumentView release()`.
  - `record NewVersion(String kind, String comment, Boolean keepCheckedOut)`, where `kind` is `snapshot` or `version` and is required.
  - `VersionResource`: `@POST @Consumes("multipart/form-data") Response save(@Multipart("version") String version, @Multipart("file") Attachment file)`, returning `201` with the `DocumentView`.
  - **Check-out:**
    - It needs READ (else `404`) and WRITE (else `403`).
    - The caller's own check-out is a `200` with no change.
    - Someone else's check-out is `409 checked-out`, with the `hold`. Any lock is `409 locked`, with the `hold`.
    - An immutable document is `409 refused`.
    - Otherwise `DocumentManager.checkout`.
  - **Release:**
    - A free document is a `200` with no change.
    - The holder, or a member of `admin`, gets `DocumentManager.unlock`.
    - Anyone else gets `409` with the hold's code.
  - **Save,** all inside `DocumentLocks.withLock(docId, …)`:
    1. Check READ, then WRITE.
    2. Someone else's check-out, admins included, is `409 checked-out`. Any lock is `409 locked` (correction 10).
    3. The new name is the file part's name, or the current one. Its extension, compared ignoring case, must equal the current one, else `422` (`file`, `extension`).
    4. Apply the empty and size rules from Task 12.
    5. If the document is free, `checkout`.
    6. `DocumentManager.get().checkin(docId, file, name, kind == version, null, history).get()`, with the history's comment set from `comment`.
    7. If `keepCheckedOut`, `checkout` again.

- [ ] **Step 1: Write the failing tests**

- **`CheckoutResourceTest`:**
  - `testCheckOutAndAgain`: checking out gives the caller's hold. A second check-out is `200` with the same `since`.
  - `testHeldByOthers`:
    - `boss`'s check-out gives `409 checked-out` with `hold.by.id` = `boss`;
    - a lock from the old UI (`DocumentManager.lock(..., LOCKED, ...)`) gives `409 locked`.
  - `testPermissions`: READ only gives `403`; unreadable gives `404`.
  - `testRelease`:
    - the holder releases;
    - `clerk` releasing `boss`'s check-out gets `409`;
    - admin `boss` releases `clerk`'s, and `event.unlocked` is recorded;
    - releasing a free document is `200`.
- **`SaveTest`:**
  - `testSnapshotThenVersion`: on a free document, a `snapshot` gives `1.1` and a `version` gives `2.0`; afterwards the document is free, and the content is the last upload.
  - `testKeepCheckedOut`: with `keepCheckedOut: true`, the hold is still the caller's check-out. The history has `event.checkedin` then `event.checkedout`.
  - `testComment`: the version's `comment` is the one sent.
  - `testHeldByOthersEvenForAdmins`: admin `boss` saving over `clerk`'s check-out gets `409 checked-out`.
  - `testOwnLockRefused`: the caller's own old-UI lock gives `409 locked`.
  - `testExtension`:
    - `a.pdf` over `a.docx` is `422`;
    - `Lease.DOCX` over `Lease.docx` is `201` (Review Focus 2);
    - `Renamed.docx` renames the document.
  - `testConcurrentSaves` (Review Focus 4): two threads each `POST` a snapshot with `keepCheckedOut: true` on a document checked out to `clerk`. Both get `201`, the final version is `1.2`, and the document is still checked out to `clerk`.
  - `testMissingKind`: `400`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='CheckoutResourceTest,SaveTest'`
Expected: failure (no routes).

- [ ] **Step 3: Implement them, and describe the three operations in `openapi.yaml`**

- [ ] **Step 4: Run the whole `healdan-api` suite and the `healdan-core` suite**

Run: `mvn -B -ntp -pl healdan-core,healdan-api test`
Expected: PASS. Note the suite times for the PR description.

- [ ] **Step 5: Commit**

```bash
git add healdan-api
git commit -m "Check out, release and save documents through the API"
```

---

### Task 14: The add-in flow in the Docker smoke test

**Files:**
- Create: `docker/tests/test-api.sh` (executable, `set -euo pipefail`, sources `lib.sh`, ends with `finish`)
- Modify: `docker/smoke-test.sh` (step "5c" after 5b, line ~183)

**Interfaces:**
- Consumes: the whole API (Tasks 5–13); the smoke fixture's Store "Smoke" › `1.1 – Smoke Matter` › `Documents`.
- Produces: `docker/tests/test-api.sh`, called as:

  ```bash
  check "API: the add-in flow (M2 spec §14.1)" env BASE="$BASE" bash docker/tests/test-api.sh
  ```

- [ ] **Step 1: Write `test-api.sh`**

**Setup.** Create a non-admin user `apiuser` over SOAP `storeUser` at `/services/Security`: use a sid from `POST /services/rest/auth/loginForm` as `admin`, put a `<password>` in the WSUser, and check `ld_usergroup` for Everyone, as the stack notes describe. Then run `check` on each of these, in order:

1. `GET /api/v1/server` is `200` with `product` `Healdan`.
2. `GET /api/docs/` is `200`, and `/api/v1/openapi.yaml` is `200`.
3. `GET /api/v1/me` with `Cookie: ldoc-sid=<admin sid>` and no token is `401`.
4. `GET /api/rest/system/getInfo` is `404`.
5. Sign in as `apiuser` (`POST /api/v1/auth/tokens`), getting `201` and a token. Every later call sends `Authorization: Bearer <token>` through CE's real filter chain (correction 18).
6. `GET /api/v1/search?scope=matters&q=1.1` returns one hit, `1.1 – Smoke Matter`. Take its `folderId`.
7. `GET /api/v1/folders/<folderId>/children?type=folder` contains `Documents`, with `canFile` false on the workspace itself (`GET /folders/<folderId>`).
8. `GET /api/v1/folders/<documents>/form` has `docclass` `required`.
9. `POST /api/v1/documents`, multipart, with `api.docx` (built in Python as `test-fulltext.sh` does) and `checkOut: false`, gives `201` and a doc #.
10. `GET /api/v1/matters/recent` lists the smoke matter.
11. `GET /api/v1/lookup?q=%23<n>` resolves to the doc #.
12. `POST …/checkout` gives `200`, and `GET /api/v1/me/checkouts` lists the document.
13. `POST …/versions` with `{kind: snapshot, keepCheckedOut: true}` gives `201`, version `1.1`.
14. `POST …/versions` with `{kind: version, keepCheckedOut: false}` gives `201`, version `2.0`, and `hold` null.
15. `GET /api/v1/documents/recent` lists the document.
16. `GET …/content` returns the last upload's bytes, and `…/content?version=1.0` returns the first upload's (`cmp`).
17. Six `POST /auth/tokens` with a wrong password for a throwaway username `nosuchuser` get 401 five times, then `429`.
18. `DELETE /api/v1/auth/tokens/current` gives `204`, and the same token then gets `401` on `/me`.

- [ ] **Step 2: Add step 5c to `smoke-test.sh` and run the smoke test locally**

Run: `bash docker/smoke-test.sh` (it builds the image; about 10 minutes).
Expected: `ALL PASSED`, including step 5c.

- [ ] **Step 3: Commit**

```bash
git add docker/tests/test-api.sh docker/smoke-test.sh
git commit -m "Run the add-in flow against the API in the smoke test"
```

---

### Task 15: Docs

**Files:**
- Modify: `docker/README.md` (a new section "The API", after "Configuration")
- Modify: `CLAUDE.md` ("Healdan's code": `healdan-api`; "Build and test": its test command and CI line)
- Modify: `docs/reference/logicaldoc-ce-capability-map.md`
- Modify: `docs/reference/healdan-api-inventory.md`
- Modify: `docs/superpowers/specs/2026-10-23-m2-api-foundation-design.md` (status, and the corrections above)
- Modify: `docs/ROADMAP.md` (M2's row and design paragraph)

**Interfaces:**
- Consumes: the built API and the Task 14 results.

- [ ] **Step 1: Write the README section**

It covers:
- the base URL;
- the docs page;
- a `curl` sign-in example and a `GET /me` with the token;
- device sign-ins (30 days unused, 365 at most; changing your password signs out every device);
- `api.cors.origins`;
- HTTPS for Office add-ins through the proxy (spec §3.2);
- the sign-in throttle (5 per username and 20 per address, 15 minutes each), and that `admin` signing in from the server itself is never blocked.

- [ ] **Step 2: Update `CLAUDE.md`**

- `healdan-api` is the HTTP layer at `/api/*`.
- CI runs `-pl healdan-core,healdan-api test`.
- Its tests need `healdan-core` installed for the test-jar.

- [ ] **Step 3: Update the capability map**

- The throttle defect row becomes **Fixed** (`SessionManagerThrottleTest`). The authentication table says the throttle runs on every route.
- Add an **Open** row for `LockedDocsDataServlet`: its SQL is missing a comma, and it does no permission filtering (correction 15).
- §4.1 gets a one-line pointer to Healdan's API.

- [ ] **Step 4: Update the inventory**

- §0.1's heading notes that M2 built these.
- `/data/lockeddocs.xml`'s row notes that it's broken upstream.
- Add `GET /folders/{id}/form`, `GET /documents/recent` and `GET /me/checkouts` where they're missing.

- [ ] **Step 5: Update the spec and the roadmap**

- **The spec:** mark it built, and fold in corrections 1, 2, 6–10, 12 and 19 as "changed in the build" notes in the sections they touch, including §13's `SessionManager` row.
- **The roadmap:** M2's row and design paragraph say "built", with the PR links.

- [ ] **Step 6: Commit**

```bash
git add docker/README.md CLAUDE.md docs
git commit -m "Document the API and record what M2 built"
```

---

### Task 16: The owner's acceptance

After PRs A–D merge, the owner runs spec §14.2 on their own stack, upgraded from M1.5:
1. Open `/api/docs/` and see every M2 endpoint described.
2. Sign in there with "Try it out", authorise with the token, and call `GET /me` and `GET /matters/recent`.
3. Read the inventory and agree that nothing M3 needs is tagged later than M2.

Record the result in the decision log below, then mark M2 done in the roadmap.

---

## Decision log (filled in while executing)

Every call made while building, in order. "Where" is the task or review that raised it; "Cost if wrong" is what undoing it would take.

| Date | Where | Decision | Why | Cost if wrong |
|---|---|---|---|---|
| 2026-10-23 | Pre-flight | `ApiTestCase.signIn(User)` and `signInAdmin()` are added in Task 6, not Task 5 | they call `POST /v1/auth/tokens`, which Task 6 builds; Task 5's base class has everything else | costs nothing if wrong (one helper moves). |
| 2026-10-23 | Task 1 | delete the clearFailures call in LDAuthenticationProvider.authenticateCredentials | SessionManager.newSession now clears after a real password sign-in, so CE's call is redundant when no session was found and wrong when one was; adds LDAuthenticationProvider.java (ASCII/LF) to the upstream-edit list (Task 15 records it in spec §13) | costs one more upstream line at each sync if wrong. |
| 2026-10-23 | Task 3 | fix inside healdan-core, no upstream edit | a `TokenRefusedException extends AuthenticationException` with mustRecordFailure()==false (LoginThrottle.recordFailure returns early on it); HealdanTokenAuthenticator throws it for unknown/unusable tokens and rethrows any AuthenticationException from validateUser (and the user-lookup data error) wrapped in it, keeping the original code as message/cause. Guessing 256-bit tokens is hopeless, so not counting token failures loses nothing | costs: a throttled attacker spraying tokens isn't counted (infeasible anyway); CE's own exception types for token refusals are hidden behind one type (API maps all to 401 regardless). |
| 2026-10-23 | Task 3 | testSignInKeepsTokens (plan-mandated) never reaches the listener because sign-in doesn't store the user; amend it to store the user after setDecodedPassword(PASSWORD) so it pins "same password isn't a change" | the plan's intent | costs nothing. |
| 2026-10-23 | Task 5 | accept spring-web (test scope) and cxf-rt-rs-service-description (provided) beyond the Global Constraints list | both managed and already in the WAR, nothing new ships; the second switches CXF's WADL description off | costs nothing if wrong. |
| 2026-10-23 | Task 5 | a JAX-RS 404 caused by a failed query-parameter conversion (e.g. ?limit=abc) maps to 400 bad-request | it's a bad request, consistent with other paging errors | costs nothing if wrong. |
| 2026-10-23 | Task 5 | a 5xx WebApplicationException keeps its status with code server-error (brief said "anything else → 500") | harmless | costs nothing. |
| 2026-10-23 | Task 5 | carry three Task 5 minors into Task 6 because later tasks build on them: (a) ApiJson's Date serializer uses value.toInstant(), which throws for java.sql.Date/Time → use Instant.ofEpochMilli(value.getTime()) + a test (Task 11's date attributes); (b) drop `persistAuthorization: true` from docs/index.html so the docs page doesn't keep a device token in localStorage on the old GUI's origin; (c) a ContractTest that feeds Contract.check a bad ServerInfo response and expects AssertionError | costs a few lines in Task 6. |  |
| 2026-10-23 | Task 6 | accept spring-security-core (provided, already in the WAR) | costs nothing. |  |
| 2026-10-23 | Task 6 | accept the four unbriefed security calls (password-expired only after a correct password; impersonation '>' usernames refused; 429 for a blocked address on the token path; 422 + session closed for a bad device name) | each narrows what an attacker learns or can do | costs a less specific error in rare cases. |
| 2026-10-23 | Task 6 | carry Task 6 minor #3 into Task 7 | TokenFilter's response filter clears Caller/security context before the entity is serialized, so a StreamingOutput (Task 7's content download) would see no caller; remove the response-filter clear (ApiServlet.invoke's finally already clears) | costs nothing. |
| 2026-10-23 | PR A review | fix wave for PR A covers Important 1 (also kill every open CE session whose key equals the token's SHA-256, ignoring case, in revoke/revokeAll | no upstream edit), Important 2 (in SessionManager's hook, write a LOGIN_FAILED user history with the block code without touching counters, then rethrow; assert it in testFormLoginIsBlocked), plus cheap minors: device name validated before the password check (AuthResource), a `curl -f $BASE/api/v1/server` check in docker/smoke-test.sh, and make `-Dmaven.test.skip=true` builds work or document `-DskipTests` in CLAUDE.md | costs one fix round. |
| 2026-10-23 | PR A review | Important 3 (internalProxies) and Important 4 (alert dedupe) go to a follow-up PR proposed to the owner as its own task | 3 needs the owner's decision on which proxy addresses to trust; both are fork-owned or one-line upstream; called out in PR A's description | costs: until it lands, a LAN attacker can brute-force admin unthrottled by spoofing X-Forwarded-For (no production deploy yet). |
| 2026-10-23 | Task 7 | GET …/content answers 403 forbidden for a password-protected document unless the caller's CE session has unprotected it (API sessions can't, so in practice always 403) | spec §7 "same permission checks" (a spec gap: neither spec nor plan mention password protection) | costs: add-ins can't open protected documents (they couldn't in Word either without the password). |
| 2026-10-23 | Task 7 | carry into Task 13: refuse check-out and saves of password-protected documents (403) | an add-in can't edit what it can't download. |  |
| 2026-10-23 | Task 7 | check Store.exists before recording the download; missing file → 500 server-error ("The document's file is missing from storage."), no history; Content-Length from Store.size | costs nothing. |  |
| 2026-10-23 | Task 7 | DocNumberLookup.shown() and AccessChecks.readableDocument disagree on a published document with null startPublishing; spec §7 wants one helper and Task 10's /lookup and /search must agree with GET /documents/{n} → make DocNumberLookup use AccessChecks' publishing rule (CE's isPublishing, as ServletUtil uses) | costs: a small edit to M1's DocNumberLookup; its tests must still pass. |  |
| 2026-10-23 | Task 8 | fix round 1 batches the read check: a shared healdan-core helper (folder READ once; documents without their own ACL inherit it; one batched ld_document_acl query for those with their own ACL | as SearchFolderService.evaluate already does; then AccessChecks.visible), used by plain-folder children; search-folder children apply only `visible` after one bulk load and cap at 1000 AFTER filtering (Minor 3 folded in); alias folders (TYPE_ALIAS) are 404 for GET and children instead of a NoSuchElementException 500 (Minor 2 ruled in, same file) | costs a fix round. |
| 2026-10-23 | Task 10 | accept SearchResource @Path("/v1/search") and a separate LookupResource @Path("/v1/lookup") instead of one root at "/v1" | CXF let the "/v1" root swallow /v1/openapi.yaml; paths are unchanged | costs nothing. |
| 2026-10-23 | Task 10 | fix round 1 | (1) a widening re-fetch loop (double the window and re-run while the page isn't filled and CE's window was full), setRetrieveAliases(false), only the first run records the SEARCH history, folder results ordered by name then id; (2) rethrow the SearchException wrapped so ProblemMapper logs it (same 500 body); (3) parse q with Lucene's classic QueryParser for the full-text scopes and answer 400 bad-request for unparsable input; index failures inside CE still show as empty results, as in the old UI — openapi.yaml must say so instead of promising a 500; folded minors: dispatch folder hits by scope (not by hit.getType() extension), /lookup trims like /search, build views only for the page and cap offset+limit with long math | costs one fix round; residual: an index failure is indistinguishable from no results (CE behaviour). |
| 2026-10-23 | Task 10 | accept reusing the same UserHistory on search re-runs instead of clearing the transaction | CE's Search.search() stores a SEARCH row on every call and a null transaction makes a new row each time; reuse updates the one row (testSearchIsRecordedOnce) | costs nothing. |
| 2026-10-23 | PR B review | PR B fix wave covers Important 1–2 plus Minors: version list ordered `versionDate desc, id desc`; openapi.yaml corrections (content 403 protected / 500 missing file; recent lists' window is recentmatters.days; full-text ceiling ~1000 engine hits (Solr rows default) | confirm and state; folders aliases 404; leading wildcards accepted; `not-browsable` in code examples); syntax-error message adds "or put the words in quotes"; lucene-queryparser declared provided; documents without a numeric doc # skipped in lists instead of a 500 | costs one fix wave. |
| 2026-10-23 | Task 11 | a listbox attribute's non-empty value must be one of its options (422 code `not-an-option`), in PATCH and (Task 12) on filing | a reasonable person expects the Class list enforced; free text would break search folders by Class | costs: a value an admin later removes from the list can't be re-sent unchanged (see next ruling). |
| 2026-10-23 | Task 11 | a field sent with a value equal to its current value is ignored (no 422), for read-only fields and for options no longer in the list | so an add-in can echo the whole form back | costs nothing. |
| 2026-10-23 | Task 11 | folder- and document-type references must be readable by the caller (else 422 `unknown`, same as missing) | avoids an existence oracle | costs nothing. |
| 2026-10-23 | Task 11 | accept `required` checked only for the properties a PATCH names | a partial update shouldn't fail on an unrelated missing field | costs: a document can stay missing a required value until someone edits that field. |
| 2026-10-23 | Task 11 | accept "unchanged values ignored for every field" | an echoed form must not create a snapshot; edge: an override that equals its inherited value stays local when sent back (null still resets) | costs that rare edge. |
| 2026-10-24 | Task 13 | Task 15 also updates the docs site (CLAUDE.md now says docs change in the same PR as the code): site/content/developer/api.md replaces its interim with the API page (link /api/docs, tokens, errors, paging); admin/healdan-settings.md gets the API settings and the sign-in throttle; user/getting-started/signing-in.md says what a block looks like; install/reverse-proxy.md notes the address throttle; developer/tests.md, continuous-integration.md and building-from-source.md get healdan-api, the test-jar and `-pl healdan-core,healdan-api` | the plan predates the site | costs one more docs task's worth of review. |
| 2026-10-24 | Task 13 | accept Concern 1 | a save keeps the sent base name but the document's spelling of the extension (Lease.DOCX over Lease.docx stays Lease.docx) — Review Focus 2 calls it the same extension and a case-only rename is a visible surprise; openapi.yaml says so | costs a one-line change if the owner wants the name as sent. |
| 2026-10-24 | Task 13 | accept Concerns 2–6 | check-out and release also take DocumentLocks; immutable checked before the implicit check-out (409 refused); release needs READ (CE's unlock checks nothing; holder or admin); a failed re-check-out after an applied save answers 201 with the real hold; a refused/failed check-in leaves the caller's check-out — each avoids a half state or a duplicating retry | costs nothing visible. |
| 2026-10-24 | PR C review | fix I1 | sanitize the save comment and TYPE_STRING property values with HTMLSanitizer.sanitizeSimpleText exactly where the old UI does; compare "unchanged" and run checkOption after cleaning — spec §2 anti-goal (skipping a check the old UI makes) | costs nothing. |
| 2026-10-24 | PR C review | fix the Task 13 carry-overs | narrow the "refused or fails" wording (a refusal leaves it as it was and checked out to you; a 500 may mean the save was applied: read the versions before retrying); ARCHIVED is refused for check-out/save (409 refused) and canEdit false; DocumentLocks Javadoc says which requests take it | costs nothing. |
| 2026-10-24 | PR C review | defer re-check-out-in-finally after a post-store failure (Recommendation 2) | no Healdan listener can fail after CE stores the file today | costs a free document while Word still edits it, on a 500 that can't happen yet. |
| 2026-10-24 | PR C review | M1 | an immutable document is 409 refused everywhere (PATCH too), admins still may PATCH — it's a document state, not a permission | costs a contract line. |
| 2026-10-24 | PR C review | M2 | PATCH /properties takes DocumentLocks and re-reads inside | costs nothing. |
| 2026-10-24 | PR C review | M3 | refuse over-long text with 422 too-long before any check-out: save comment (1000), file names on save and filing (255), string property values (4000); maxLength in the schemas | costs nothing. |
| 2026-10-24 | PR C review | M4 | add optional `name` to NewVersion, winning over the file part's name (with the same extension rule); don't percent-decode plain filenames (ambiguous with literal names; `name` covers browsers); saveVersion's description tells browser clients to send `name` | costs nothing. |
| 2026-10-24 | PR C review | M5 | GET /properties marks every field editable:false when the caller can't PATCH (no Write, someone else's hold or any lock, immutable for non-admins, password-protected) — M3's form learns the gate before a 403/409 | costs one caller-aware mapping in the API layer. |
| 2026-10-24 | PR C review | M6 | PATCH /properties of a password-protected document is 403 (same rule and wording as content/check-out/save); GET stays allowed — anything that changes a protected document goes through the web page | costs: an add-in can't edit a protected document's properties. |
| 2026-10-24 | PR C review | M7 | the contract says protected documents are always 403 through the API | costs nothing. |
| 2026-10-24 | PR C review | defer M8 (Subclass rule re-derived in PropertyInput.checkSubclass) until the next Subclass rule change | core still enforces it | costs a 422-vs-409 drift risk. |
| 2026-10-24 | PR C review | M9 | add one test that drives filing, a save and check-out/release and reads /documents/recent and /me/checkouts | costs ~a few seconds of test time. |
| 2026-10-24 | PR C review | accept PATCH refusing the caller's own lock (409 locked) | correction 10 (a lock is a hold, even your own) and one predicate for GET and PATCH; the caller's own check-out still allows PATCH | costs: a user must release their old-UI lock before editing properties through the API. |
| 2026-10-24 | PR C review | accept CXF's part-header line limit raised to 8 KB (Tomcat's) | a ~240-char or 26-CJK-letter filename* gave a misleading 413 and hid the 255 check; Task 12 defect | costs nothing (bounded). |
| 2026-10-24 | PR C review | accept "unchanged" matching text as sent or once cleaned | an email's From (Ann <ann@...>) echoed back must stay a no-op | costs nothing (a changed value is still cleaned). |
| 2026-10-24 | PR C review | fix now (load-bearing residual) | CE's saveVersionAsync swallows a failed version insert and .get() returns normally, so a save could answer 201 with nothing stored; after .get(), re-read and require the new version's row (and a changed document version), else 500 server-error saying the save wasn't stored and the document is still checked out to the caller — a false "saved" to a Word user is silent data loss | costs one re-read per save. |
| 2026-10-24 | PR C review | PR D starts by merging origin/master into its branch (merge, not rebase), and Task 15's docker/README.md API section goes to the site instead (Install guide for the stack's API facts, Developer Guide's API page for clients) | docker/README.md is now a pointer on master | costs nothing. |
| 2026-10-24 | PR D | Task 15 runs from task-15-addendum.md alongside its brief (docs site pages instead of docker/README.md; CLAUDE.md + Developer Guide; the spec notes collected from this ledger; capability map/inventory additions; roadmap says built, acceptance pending) | the plan predates the site | costs a larger docs task. |
| 2026-10-24 | Task 14 | accept no explicit ld_usergroup check for Everyone (brief Setup) | step 6 fails if apiuser lacks Everyone, and the script has no DB container | costs a less specific failure message. |
| 2026-10-24 | Task 15 | accept the docs sending admins to context.properties for the throttle (CE's Brute Force screen is hidden: Feature_39 undeclared) and api.* settings documented as hd_setting values with no context.properties key | the code says so | costs nothing. |
| 2026-10-24 | Task 15 | accept the roadmap's "built … awaiting acceptance" (M1.5's precedent) | costs nothing. |  |
| 2026-10-24 | Task 15 | the stale Healdan Settings screenshot is retaken in PR D's fix wave on a throwaway stack (the image is built) | docs must match the screen | costs a stack start. |
| 2026-10-24 | Task 15 | fix round 1 takes I1, I2, all six minors (cheap wording) and the screenshot retake (throwaway stack on its own compose project and port; never touch the hdpages stack, which belongs to another session) | docs must not promise more protection than the code gives | costs one round. |
| 2026-10-24 | Final review | I1 | fix the contract and spec §6.6 wording: a save's new version follows `version`, not `fileVersion` (snapshot = version's minor + 1; version = next major .0); rows whose fileVersion differs from version are property edits/moves/renames carrying fileVersion's file; `ifVersion` precondition left to M3 (noted in the spec) — M3's footer needs the rule | costs nothing. |
| 2026-10-24 | Final review | I2 | device tokens authenticate only /api: HealdanTokenAuthenticator claims an hd_ key only when the API's TokenFilter asks, and no CE route (loginApiKey SOAP/REST, X-API-KEY reuse of an open session, Basic/WebDAV) accepts one; minimal or no upstream edit (if any: listed in §13 and CI -Dtest); smoke gets a check that a token is refused on a CE route; docs reverted to "only /api" and the lost-device advice says change the password and delete LogicalDOC API keys you don't recognise — closes token→permanent ld- key escalation and makes the lost-laptop promise true; spec §5.1 "Elsewhere" was a technical call (Claude's) | costs: if M3's spike picks WebDAV with tokens, it adds a narrow allowance then. |
| 2026-10-24 | Final review | I3 | CI split is its own PR to master (a separate session card), not M2 code; PR D corrects the CI-time lines in #62–#64's descriptions with the measured numbers — the split is independent of M2 and every PR benefits | costs: M2's PRs keep the slower build until the split lands. |
| 2026-10-24 | Final review | Minors fixed now: M1 (device name over 100 → too-long), M2 (spec §6.4 `by`), M3 (problem type URIs under healdan.com: https://healdan.com/problems/<code>; no compat promises yet), M4 (docs give a command to edit context.properties in the conf volume), M5 (filing confirms CE stored 1.0, like saves), M6 (contract says hold.since is the latest check-out or lock event, so it moves on keepCheckedOut saves). Deferred: M7 (Retry-After / time left in 429). M8: ledger corrected below. |  |  |
| 2026-10-24 | Final review | accept item 19 running before 18 (18 revokes the token) and the session key label reading hd_api_… | costs nothing. |  |
| 2026-10-24 | Final review | fix the docs residual now (controller) | the lost-device advice ends with killing open sessions (Administration → System → General → Sessions → Kill), and Healdan Settings no longer says disabling stops a person's keys — a security fact a reader acts on | costs nothing. Commit ac111bb63; docs checks pass. |
| 2026-10-24 | PR D | don't touch A or B while their review sessions run; once they're done, merge up once (A→B→C→D, each with origin/master), resolve, run healdan-core+healdan-api tests and the smoke test on D, set the roadmap's "PR D (this PR)" to #66 | two writers on one branch would fork the stack | costs waiting for the reviews. |

## Follow-ups

Left open when the owner accepted M2 on 2026-10-24. None blocks M3.

**Security and sign-in**
- Harden the sign-in throttle behind the proxy: `RemoteIpValve` in `docker/server.xml` trusts every private range, so anything on the Docker host or the LAN can claim another address, `127.0.0.1` included, which exempts `admin`. CE also sends one alert per blocked attempt, and raw `ld-` API keys end up in `ld_sequence` counter names. Proposed as its own task. Land it before any production deploy.
- A revoked token's API session opened in the same moment can stay open until it idles out; no client can reach it. Fix: `bind` reports whether it updated a row, and `openSession` kills the session if not. `killOpenedSessions` could take a set.
- A token issued while its user's password changes relies on MariaDB's default REPEATABLE READ to be revoked; nothing sets the isolation level.
- `429` answers give the configured wait, not the time left, and no `Retry-After`.
- CE: `SecurityServiceImpl.deleteApiKey` deletes any key id with no owner check (upstream, pre-existing).

**Rules outside the API (pre-existing, recorded in the capability map)**
- Healdan's listeners don't enforce required properties on create, so SOAP, REST and WebDAV can file without them; only the API checks.
- The old UI shows HTML in file names unescaped; its own upload and check-in don't clean names either.
- Breadcrumbs and a document's folder and matter names aren't read-checked for document-only grants or pre-M1 ACLs (M1's P2).

**For M3's design session**
- Holds are per user, not per device: two of one person's devices can both hold and save a document.
- A save takes at least about 1.1 s on the server (CE's version write), and the API waits for it.
- A doc-ID footer stamped before upload works out the new number from `version`, not `fileVersion`. An `ifVersion` precondition on saves is M3's call.
- Browser clients send `NewDocument.name` and `NewVersion.name`, because a `Blob` arrives as `blob`.
- `type: date` property values are instants, so a date-only value set at midnight UTC shows as the day before west of UTC. M3 decides the convention.
- `hold.since` is the latest check-out or lock event, so it moves on every `keepCheckedOut` save.
- Tokens work only at `/api`. If M3's spike picks WebDAV with tokens, it needs a narrow allowance there.

**Performance, before M6**
- Folder listings load the whole folder for each page, and full-text search tops out at about 1000 engine hits (Solr's default `rows`).

**Docs**
- Office Previews and What Runs still say "edit and restart" for the converter timeout; Configuration's note on stopping the app first covers it.
