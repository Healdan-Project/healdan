# M4 "OIDC sign-in" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** People sign in to Healdan's web page and the Healdan Helper through Microsoft Entra ID, Authentik or another OpenID Connect provider, with `admin` kept as a password break-glass account, as the approved spec describes.

**Architecture:**
- **`healdan-core`** (`com.logicaldoc.healdan.signin`) holds the domain:
  - provider settings in `hd_setting`, and a Flyway script for identity links, group maps and new token columns;
  - the protocol, through Nimbus's OAuth 2.0 / OpenID Connect SDK;
  - deciding the account (link by email, make, refuse), group sync and the gate;
  - a user listener that keeps passwords off provider accounts;
  - a CE authenticator that opens a session for a decided account with a one-time key;
  - the re-check of helper tokens, unlinking, and the `admin` alert.
- **`healdan-api`** serves the browser pages at `/api/signin/`, which run the provider's redirect and end in the same `ldoc-sid` cookie as the password form. It also takes the helper's one-time code at `POST /api/v1/auth/tokens`, and lists the providers in `GET /api/v1/server`. `context-security.xml` isn't touched.
- **The old web page:**
  - one hook in `LoginPanel` adds the provider buttons and folds the password form behind a link;
  - a Sign-In tab in Healdan Settings sets providers up, maps groups, lists and unlinks accounts, and holds the `admin` alert.
- **The helper** signs in through the system browser with a loopback redirect and PKCE, then exchanges Healdan's one-time code for its device token.
- **CI** runs a fake provider inside the Java tests, and Keycloak as a stand-in provider on the Docker smoke stack.

**Tech Stack:**
- **Server:** JDK 25, CXF 4.0 JAX-RS, Spring 6.2, Hibernate 7, Flyway 13.10.3, JUnit 4 with Spring's servlet mocks, as M2.
  - New: `com.nimbusds:oauth2-oidc-sdk` **11.38.2** and `com.nimbusds:nimbus-jose-jwt` **10.10** (Apache 2.0), with their `json-smart`, `accessors-smart`, `content-type`, `lang-tag` and `jcip-annotations` (Apache 2.0).
  - The fake provider in tests is `com.sun.net.httpserver.HttpServer`.
- **GUI:** GWT 2.12 and SmartGWT 14.1, as M1.
- **Helper:** .NET 10 and Avalonia 12.1.4, as M3. No new NuGet packages: `System.Net.HttpListener`, `System.Security.Cryptography`.
- **Stand-in provider:** `quay.io/keycloak/keycloak:26.7.5` (Apache 2.0, amd64 and arm64), only in the smoke stack.

**Status:** written on 2026-11-01, after the spec's approval. For the owner's review.

**Spec:** [`docs/superpowers/specs/2026-11-01-m4-oidc-sign-in-design.md`](../specs/2026-11-01-m4-oidc-sign-in-design.md), approved by the owner on 2026-11-01. Every § below points into it.
- Where the two disagree, the spec wins, except for the corrections under "Spec corrections and calls this plan makes".
- M2's spec and plan still hold for device tokens, the throttle and the API's frame; M3's and M3.5's for the helper.

## Global Constraints

Every task's requirements include these.

- **Upstream edits are only these four** (spec §15.1):
  - `LoginPanel.initGUI`: one line;
  - `UserSource`: `OIDC` appended;
  - `UsersPanel`: one `case`;
  - `logicaldoc-core`'s `UserSourceTest`: new, and in `UPSTREAM_TESTS`.

  `context-security.xml` isn't edited. Any other upstream edit needs the owner's say-so, and a line in the spec's §15.1.
- **`UserSource`'s stored numbers:** `DEFAULT` 0, `LDAP` 1, `SAML` 2, `OIDC` 3.
- **Code location:** `healdan-core`'s `com.logicaldoc.healdan.signin`; `healdan-api`'s `com.logicaldoc.healdan.api`; the GUI's `common.client.healdan` (the sign-in page) and `frontend.client.healdan.settings` (the tab); the helper's `Healdan.Helper.Core/Session/`.
- **Settings in `hd_setting`** (spec §10.1):

  | Key | Default |
  |---|---|
  | `signin.providers` | empty: comma-separated ids, in button order |
  | `signin.password.label` | `Sign In with a Password` |
  | `signin.adminAlert` | `false` |
  | `signin.adminAlert.recipients` | `admin` |
  | `signin.recheck.minutes` | `480`; not shown in the tab; the smoke seed sets `0` |
  | `signin.<id>.label`, `.preset` (`entra`, `authentik`, `other`), `.enabled`, `.issuer`, `.clientId`, `.clientSecret` (sealed), `.claim.identity`, `.claim.username`, `.claim.email`, `.claim.groups`, `.linkByEmail` | per §10.2's presets |

- **A provider's id** matches `[a-z0-9-]{1,32}`.
- **Presets** (spec §10.2):

  | | `entra` | `authentik` | `other` |
  |---|---|---|---|
  | label | `Sign In with Microsoft` | `Sign In with Authentik` | `Sign In` |
  | identity | `oid` | `sub` | `sub` |
  | username | `preferred_username` | `preferred_username` | `preferred_username` |
  | email | `email` | `email` | `email` |
  | groups | `groups` | `groups` | `groups` |
  | linkByEmail | `true` | `false` | `false` |

- **Addresses:**
  - `GET /api/signin/<id>` starts a sign-in (`?client=helper&redirect_uri=…&state=…&code_challenge=…&code_challenge_method=S256` for the helper);
  - `GET /api/signin/<id>/callback` is what an admin registers;
  - `GET /api/signin/done` (`?error=<code>` optional) is the page that tells the person to close the tab.
- **Timings:**
  - a pending sign-in lives 10 minutes;
  - a helper's one-time code, 60 seconds;
  - the re-check runs every `signin.recheck.minutes` (480), and not again within 15 minutes after the provider couldn't be reached;
  - every provider call has a 10-second timeout;
  - ID tokens are allowed 2 minutes of clock skew;
  - the helper listens for at most 5 minutes.
- **The protocol:**
  - code flow with `response_mode=query` and PKCE `S256`;
  - scope `openid profile email`, plus `offline_access` for the helper;
  - algorithms `RS256`, `RS384`, `RS512`, `PS256`, `PS384`, `PS512`, `ES256`, `ES384`, `ES512` only;
  - HTTPS only, unless the environment variable `HEALDAN_SIGNIN_ALLOW_HTTP=true`, which only `docker/tests/compose.signin.yaml` sets.
- **The browser cookie:** `hd_signin`: HttpOnly, `SameSite=Lax`, `Path=/api/signin/`, `Secure` when the request is HTTPS. It holds 32 random bytes in base64url, and the pending sign-in keeps its SHA-256.
- **The one-time key** a provider sign-in hands CE's chain starts `hd_signin_`. `HealdanTokenAuthenticator` leaves those to `SignInAuthenticator`.
- **Plugin extensions:**
  - `Authentication`, id `healdanSignInAuthentication`, `authenticatorId` `signInAuthenticator`, position `106`;
  - `UserListener`, id `healdanSignInPasswords`, class `com.logicaldoc.healdan.signin.ProviderPasswordListener`, position `95`.

  Every id must be unique: JPF drops the whole plugin on a duplicate.
- **Refusal codes and messages** (spec §5.3). `{label}` is the provider's label.

  | Code | Message |
  |---|---|
  | `no-group` | Your account at {label} doesn't give you access to Healdan. Ask an administrator. |
  | `email-taken` | Healdan can't tell which account is yours. Ask an administrator. |
  | `username-taken` | Healdan already has an account with your username. Ask an administrator. |
  | `account` | This account can't sign in now. Ask an administrator. |
  | `provider` | {label} couldn't sign you in. Try again, or ask an administrator. |
  | `expired` | That sign-in took too long or was already used. Start again. |

- **Secrets:**
  - sealed by `SecretBox` with AES-256-GCM, under a key file at the system property `healdan.secretKey`, else `~/logicaldoc/conf/healdan-secret.key`;
  - `docker/setenv.sh` sets `/opt/logicaldoc/conf/healdan-secret.key`, the conf volume;
  - the key file is mode 600 and 32 random bytes;
  - a sealed value is `v1:` and the base64 of IV (12 bytes), ciphertext and tag;
  - never logged, never sent to a client, never shown back in the GUI.
- **Never logged:** tokens, codes, secrets, ID tokens, verifiers, states, the `hd_signin` cookie.
- **Docs ship in the task that changes the behaviour** (CLAUDE.md). Title Case for titles and labels, British spelling.
- **CI time.** Each PR states how many seconds it adds to `build`, the `Tests` jobs, the Docker smoke test and `Clients`.

## Review Focus

These are the inputs most likely to hurt someone that a task's main tests could miss. Each has a test in the task named.

1. **A provider account getting a password by any route:** the user editor, Change Password, Lost Password, SOAP. Or a password sign-in succeeding for one. "Unlinked accounts only" rests on this (Task 5).
2. **Linking the wrong account:** email case, two matches, `admin`, an account already linked, the switch off. A refused sign-in must make nothing and link nothing (Task 5).
3. **A sign-in finished in another browser, or answered by another provider** (login CSRF, mix-up), or a state used twice (Task 7).
4. **The helper's code** sent anywhere but `127.0.0.1` or `[::1]`, redeemed twice, after 60 seconds, or without the verifier (Task 8).
5. **An outage taken for a refusal,** revoking everyone's helpers when the provider is merely down (Task 9).
6. **Secrets** in a log, an answer or the GUI (Tasks 2, 3, 13).

## Spec corrections and calls this plan makes

The owner hears about these when this plan is handed over. Task 16 folds them into the spec, each marked "changed in the build".

1. **The user listener vetoes with an `AuthenticationException`, not a `HealdanVetoException`.** `HibernateUserDAO.invokeListenersBeforeSave` logs and swallows a listener's `PersistenceException`, and rethrows only `AuthenticationException`, a `RuntimeException`. So `ProviderPasswordListener` throws `ProviderPasswordException extends AuthenticationException` (spec §7.3).
2. **Unlinking revokes the account's device tokens itself.** `TokenUserListener` only notices a password change when the stored password wasn't null, so going from none to a temporary password wouldn't revoke them (spec §7.7). Linking does revoke the tokens an account had, as removing a password is a change it sees. So a helper signed in with the old password asks for a sign-in again, now through the provider.
3. **"Outgoing email is set up"** (spec §7.5) means `default.smtp.host` isn't blank, `localhost`, `127.0.0.1` or `::1`, and `default.smtp.sender` isn't blank or CE's shipped `logicaldoc@acme.com`. CE ships `localhost` and that sender, and a stack nobody set up keeps them.
4. **The `admin` alert hangs on CE's `SessionListener`.** It acts on a new session for user id 1 that has no key and no impersonator, which is a password sign-in by `admin`.
5. **The public address logic moves out of `AddinResource`** into a shared `PublicAddress` in `healdan-api`, used by the add-in's manifest and the sign-in pages alike.
6. **`signin.recheck.minutes`** (480, not in the tab) sets the re-check's interval, so the smoke test can force a check by setting it to 0.
7. **The links' uniqueness** among links in use rests on two nullable unique columns, `hd_linkkey` (`<provider>:<identity>`) and `hd_linkuser`, set while linked and nulled when unlinked. MariaDB and HSQLDB let nulls repeat in a unique index.
8. **The key's place:** a system property, `healdan.secretKey`, which `docker/setenv.sh` sets to the conf volume, as `ContextProperties` doesn't say where it was loaded from.
9. **The stand-in provider is Keycloak 26.7.5.** It's the one candidate with amd64 and arm64 images, a groups claim, refresh tokens and a way to disable a user (spec §16.1).

## Conventions

- **Running Maven:** `mvn -B -ntp -pl logicaldoc-core -DskipTests -Dgwt.skipCompilation=true install` after an upstream edit; `mvn -B -ntp -pl healdan-core install -DskipTests`, then `mvn -B -ntp -pl healdan-core test -Dtest=<Class>` or `mvn -B -ntp -pl healdan-api test -Dtest=<Class>`. `~/logicaldoc-dev.properties` must exist (an empty file works).
- **The helper's tests:** `dotnet test clients/helper/Healdan.Helper.sln`, one class with `--filter "FullyQualifiedName~<Class>"`.
- **The smoke test:** `docker/smoke-test.sh`; it needs a fresh stack.
- **Commits:** one per task, with an imperative subject, a body that says why, and the `Co-Authored-By` trailer.

## PRs

All target `master`, each stacked on the one before.

- **PR A, Tasks 1–6:** the server's core. No visible change: no endpoint and no page uses it yet.
- **PR B, Tasks 7–11:** the endpoints, the re-check, unlinking and the alert, and the stand-in provider in the smoke test. Usable with a provider set up by the smoke seed only.
- **PR C, Tasks 12–13:** the sign-in page and the Sign-In tab, with the Install manual's sign-in section. Usable by an admin from here.
- **PR D, Tasks 14–15:** the helper's browser sign-in.
- **PR E, Task 16:** CLAUDE.md, the capability map, the spec's corrections and the roadmap.
- **Task 17:** Healdan Helper 0.5.0, the owner's server upgraded, and the owner's walkthrough.

---

### Task 1: `UserSource.OIDC`, and its guard

**Files:**
- Modify: `logicaldoc-core/src/main/java/com/logicaldoc/core/security/user/UserSource.java`
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/security/user/UsersPanel.java` (the Source column's `switch`, ~310)
- Create: `logicaldoc-core/src/test/java/com/logicaldoc/core/security/user/UserSourceTest.java`
- Modify: `.github/workflows/build-logicaldoc.yml` (`UPSTREAM_TESTS`, ~90)
- Docs: `site/content/developer/how-healdan-extends-logicaldoc.md` (the upstream edits list gains `UserSource` and `UsersPanel`; `LoginPanel` joins it in Task 12)

**Interfaces:**
- Produces: `UserSource.OIDC`, stored as 3.

- [ ] **Step 1: Write the failing test**

```java
package com.logicaldoc.core.security.user;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins each source's stored number: ld_user.ld_source holds the ordinal, so a value inserted before another moves
 * every account after it (Healdan's M4 spec section 15.1).
 */
public class UserSourceTest {

	@Test
	public void testStoredNumbersNeverMove() {
		assertEquals(0, UserSource.DEFAULT.ordinal());
		assertEquals(1, UserSource.LDAP.ordinal());
		assertEquals(2, UserSource.SAML.ordinal());
		assertEquals(3, UserSource.OIDC.ordinal());
		assertEquals(4, UserSource.values().length);
	}

	@Test
	public void testUserReadsOidcBackFromItsNumber() {
		User user = new User();
		user.setSource(3);
		assertEquals(UserSource.OIDC, user.getSource());
	}
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest=UserSourceTest`
Expected: FAIL to compile (`OIDC` doesn't exist).

- [ ] **Step 3: Append `OIDC`**

```java
public enum UserSource {
	
	DEFAULT, LDAP, SAML,
	// Healdan: an account linked to an OpenID Connect provider (M4 spec section 7.3); appended, so the others keep their numbers
	OIDC;
}
```

In `UsersPanel`'s `switch (user.getSource())`, after `case 2`:

```java
			case 3: // Healdan: an account linked to a sign-in provider (M4 spec section 15.1)
				rec.setAttribute(SOURCE, "OIDC");
				break;
```

Add `UserSourceTest` to `UPSTREAM_TESTS` in `build-logicaldoc.yml`.

- [ ] **Step 4: Run it, and install core**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest=UserSourceTest && mvn -B -ntp -pl logicaldoc-core -DskipTests -Dgwt.skipCompilation=true install`
Expected: PASS.

- [ ] **Step 5: Docs, then commit:** "Add an OIDC source for accounts linked to a sign-in provider"

---

### Task 2: The data, the libraries and the secret box

**Files:**
- Modify: `healdan-core/pom.xml` (the two Nimbus dependencies, as properties `nimbus-oidc.version` 11.38.2 and `nimbus-jose.version` 10.10, and `<exclusions>` for `org.ow2.asm:asm`, which the WAR already ships at 9.9)
- Modify: `healdan-core/src/main/assembly/assemblyplugin.xml` (the `WEB-INF/lib` dependency set gains `com.nimbusds:*`, `net.minidev:*`, `com.github.stephenc.jcip:*`)
- Create: `healdan-core/src/main/resources/healdan/db/migration/mariadb/V6__signin.sql`, `…/hsqldb/V6__signin.sql`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/SignInIdentity.java`, `SignInIdentityDAO.java`, `HibernateSignInIdentityDAO.java`, `SignInGroup.java`, `SignInGroupDAO.java`, `HibernateSignInGroupDAO.java`, `SecretBox.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SignInSettingsSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/token/ApiToken.java` (three fields)
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` (`ORDER` gains `"signin-settings"` after `"hide-dropbox"`, and `"smoke-signin"` after `"smoke-fixture"`, which Task 11 uses)
- Modify: `docker/setenv.sh` (`-Dhealdan.secretKey=/opt/logicaldoc/conf/healdan-secret.key`)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/signin/SignInDataTest.java`, `SecretBoxTest.java`; `seed/SignInSettingsSeedTest.java`
- Docs: `site/content/install/backup-and-restore.md` (the key in the conf volume must be backed up with the database; losing it means re-entering client secrets and helpers signing in again)

**Interfaces:**
- Produces:

```java
@Entity @Table(name = "hd_signin_identity")
public class SignInIdentity extends PersistentObject {
	String provider; String identity; long userId; Date linked; String how; // "email" | "new"
	Date lastSignIn; Date unlinked; Long unlinkedBy;
	String linkKey;   // "<provider>:<identity>" while linked, else null (unique)
	Long linkUser;    // userId while linked, else null (unique)
	public boolean isLinked() { return unlinked == null; }
}
public interface SignInIdentityDAO extends PersistentObjectDAO<SignInIdentity> {
	static SignInIdentityDAO get();
	Optional<SignInIdentity> findLinked(String provider, String identity) throws PersistenceException;
	Optional<SignInIdentity> findLinkedByUser(long userId) throws PersistenceException;
	List<SignInIdentity> findLinked(String provider) throws PersistenceException; // by username
	int countLinked(String provider) throws PersistenceException;
}
@Entity @Table(name = "hd_signin_group")
public class SignInGroup extends PersistentObject { String provider; String value; long groupId; }
public interface SignInGroupDAO extends PersistentObjectDAO<SignInGroup> {
	static SignInGroupDAO get();
	List<SignInGroup> findByProvider(String provider) throws PersistenceException;
	void replace(String provider, List<SignInGroup> rows) throws PersistenceException; // deletes the provider's rows, stores these
}
// ApiToken gains: String provider (hd_provider), String refresh (hd_refresh, sealed), Date checked (hd_checked)

@Component
public class SecretBox {
	public static SecretBox get();
	public SecretBox();                 // the key file from -Dhealdan.secretKey, else ~/logicaldoc/conf/healdan-secret.key
	public SecretBox(Path keyFile);
	public String seal(String plain);   // makes the key file on first use; null in, null out
	public String open(String sealed);  // null when there's no key file, the key is wrong, or the value is damaged
	public boolean hasKey();
}
```

- [ ] **Step 1: Write the failing tests**

`SignInDataTest extends AbstractHealdanTestCase`:
- `testMigrationMadeTheTables`: `hd_signin_identity`, `hd_signin_group` and the three new `hd_api_token` columns exist (query `INFORMATION_SCHEMA`), and `hd_schema_history` has version 6.
- `testIdentityRoundTrip`: a stored `SignInIdentity` for user `author` is found by `findLinked("entra", "oid-1")` and `findLinkedByUser`.
- `testOneLinkPerIdentityAndUser`: a second linked row with the same `linkKey`, or the same `linkUser`, fails to store.
- `testUnlinkedRowsRepeat`: two unlinked rows for the same identity and user (`linkKey` and `linkUser` null) both store, and neither is found by `findLinked`.
- `testCountLinked`: counts only linked rows of that provider.
- `testGroupReplace`: `replace("entra", …)` leaves exactly the given rows for `entra`, and `authentik`'s alone.
- `testTokenColumns`: an `ApiToken` stored with `provider`, `refresh` and `checked` reads them back.

`SecretBoxTest` (plain JUnit, a `TemporaryFolder`):
- `testSealOpens`: `open(seal("s3cret"))` is `s3cret`, and the sealed value starts `v1:` and doesn't contain `s3cret`.
- `testSealTwiceDiffers`: two seals of one value differ (fresh IV).
- `testKeyFileMadeOnceWithMode600`: the first `seal` makes the file, 32 bytes, `rw-------` (POSIX file systems only, `Assume` elsewhere); a second box on the same path opens the first's values.
- `testNoKeyOpensNothing`: a box whose key file doesn't exist opens a value sealed by another box as `null`, and `hasKey()` is false.
- `testDamagedValueOpensNothing`: a flipped byte, a missing `v1:` and `null` each open as `null`, with no exception.

`SignInSettingsSeedTest extends AbstractHealdanTestCase`:
- `testDefaults`: after the seeds, `signin.password.label` is `Sign In with a Password`, `signin.adminAlert` `false`, `signin.adminAlert.recipients` `admin`, `signin.recheck.minutes` `480`, and the message template `signin.adminalert` exists in English.
- `testKeepsAnAdminsValues`: with `signin.password.label` already `Use a Password`, running the step again (delete its marker) leaves it.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SignInDataTest,SecretBoxTest,SignInSettingsSeedTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Write the migration**

MariaDB (`V6__signin.sql`; HSQLDB's is the same with `timestamp` for `datetime` and no `ENGINE` clause):

```sql
-- M4: links between accounts and identities at sign-in providers (spec section 7). A row stays when unlinked, as a
-- record; hd_linkkey and hd_linkuser are set only while linked, so the unique indexes allow one link in use per
-- identity and per user while unlinked rows repeat.
create table hd_signin_identity (
	ld_id bigint not null,
	ld_lastmodified datetime not null,
	ld_creation datetime not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_provider varchar(32) not null,
	hd_identity varchar(255) not null,
	hd_userid bigint not null,
	hd_linked datetime not null,
	hd_how varchar(10) not null,
	hd_lastsignin datetime,
	hd_unlinked datetime,
	hd_unlinkedby bigint,
	hd_linkkey varchar(300),
	hd_linkuser bigint,
	primary key (ld_id),
	unique (hd_linkkey),
	unique (hd_linkuser)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create index hd_signin_identity_provider on hd_signin_identity (hd_provider);
create sequence hd_signin_identity_SEQ start with 100 increment by 50;

-- M4: each provider's group map (spec section 8), by the Healdan group's id
create table hd_signin_group (
	ld_id bigint not null,
	ld_lastmodified datetime not null,
	ld_creation datetime not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_provider varchar(32) not null,
	hd_value varchar(255) not null,
	hd_groupid bigint not null,
	primary key (ld_id),
	unique (hd_provider, hd_value, hd_groupid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_signin_group_SEQ start with 100 increment by 50;

-- M4: a device token from a provider sign-in, its sealed refresh token, and its last re-check (spec section 9)
alter table hd_api_token add column hd_provider varchar(32);
alter table hd_api_token add column hd_refresh varchar(4000);
alter table hd_api_token add column hd_checked datetime;
```

- [ ] **Step 4: Write the entities, DAOs, `SecretBox` and the seed**

- The entities and DAOs follow `ApiToken` and `HibernateApiTokenDAO`: `@Entity`, `@Table`, columns as above; `HibernateXxxDAO extends HibernatePersistentObjectDAO<Xxx>`, `@Repository("signInIdentityDAO")`.
- **`SecretBox`:**

```java
public String seal(String plain) {
	if (plain == null)
		return null;
	try {
		byte[] iv = new byte[12];
		RANDOM.nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, iv));
		byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
		return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
	} catch (GeneralSecurityException | IOException e) {
		throw new IllegalStateException("Can't seal a secret", e); // never the value itself
	}
}
```

`key(true)` reads the 32-byte file, or makes it with `Files.createFile` and `PosixFilePermissions.asFileAttribute(rw-------)` where the file system supports it, then writes 32 random bytes. It holds a lock so two first uses don't make two keys. `open` returns `null` on any failure, and logs once at warn that the key is missing or doesn't fit, never the value.
- **`SignInSettingsSeed`** (`name()` = `signin-settings`) calls `HealdanSettings.setIfMissing` for the five defaults, and adds the template as `ThrottleAlertSeed` does:
  - name `signin.adminalert`, type `SYSTEM`, language `en`;
  - subject `$product - admin signed in with its password`;
  - body `The emergency account <b>admin</b> signed in to $product with its password.<br/><br/>$I18N.get('ipaddress'): <b>$ip</b><br/>$I18N.get('dateandtime'): $DateTool.formatDate($date)`.

- [ ] **Step 5: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SignInDataTest,SecretBoxTest,SignInSettingsSeedTest,HealdanMigratorTest'`
Expected: PASS.

- [ ] **Step 6: Check the packaging**

Run: `mvn -B -ntp -pl healdan-core dependency:tree -Dscope=runtime | grep -E "nimbus|minidev|jcip|content-type|lang-tag|asm"`
Expected: the five libraries, and no `asm`. Then `mvn -B -ntp -pl healdan-core -DskipTests package` and `unzip -l healdan-core/target/healdan-core-*-plugin.zip | grep WEB-INF/lib` lists them. Say in the PR which jars the WAR gains and their total size.

- [ ] **Step 7: Docs, then commit:** "Add the tables, libraries and secret box for provider sign-in"

---

### Task 3: Providers' settings

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/Preset.java`, `SignInProvider.java`, `GroupMapping.java`, `SignInProviders.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/signin/SignInProvidersTest.java`

**Interfaces:**
- Consumes: `SecretBox`, `SignInIdentityDAO.countLinked`, `SignInGroupDAO` (Task 2), `HealdanSettings`.
- Produces:

```java
public enum Preset {
	ENTRA("entra", "Sign In with Microsoft", "oid", true),
	AUTHENTIK("authentik", "Sign In with Authentik", "sub", false),
	OTHER("other", "Sign In", "sub", false);
	public String key(); public String label(); public String identityClaim(); public boolean linkByEmail();
	public static Preset of(String key); // OTHER for anything else
	// username "preferred_username", email "email", groups "groups" for all three
}

public record SignInProvider(String id, Preset preset, String label, boolean enabled, String issuer, String clientId,
		boolean hasSecret, String identityClaim, String usernameClaim, String emailClaim, String groupsClaim,
		boolean linkByEmail) {
	public static SignInProvider fromPreset(String id, Preset preset); // the preset's values, enabled, no issuer
}

public record GroupMapping(String value, long groupId) {}

@Component
public class SignInProviders {
	public static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");
	public static SignInProviders get();
	public List<SignInProvider> all();                    // in signin.providers order
	public List<SignInProvider> enabled();                // all() that are enabled and have issuer, client id and secret
	public Optional<SignInProvider> find(String id);
	public String secret(String id);                      // opened, or null
	public void save(SignInProvider p, String newSecret) throws HealdanRuleException; // newSecret null or blank keeps the stored one
	public void remove(String id) throws HealdanRuleException;
	public void reorder(List<String> ids) throws HealdanRuleException;
	public List<GroupMapping> groups(String id) throws PersistenceException;
	public void saveGroups(String id, List<GroupMapping> mappings) throws PersistenceException, HealdanRuleException;
	public String passwordLabel();
	public boolean adminAlert();
	public List<String> adminAlertRecipients();
	public int recheckMinutes();
	public void saveOptions(String passwordLabel, boolean adminAlert, List<String> recipients) throws HealdanRuleException;
	public static boolean httpAllowed();                  // HEALDAN_SIGNIN_ALLOW_HTTP=true
}
```

- [ ] **Step 1: Write the failing tests**

`SignInProvidersTest extends AbstractHealdanTestCase`:
- `testNoProvidersAtFirst`: `all()` and `enabled()` are empty.
- `testSaveAndFindEntra`: saving `fromPreset("entra", ENTRA)` with issuer `https://login.microsoftonline.com/t1/v2.0`, client id `c1` and secret `s1` gives `find("entra")` with the preset's claims, `hasSecret` true, and `secret("entra")` = `s1`. `hd_setting`'s `signin.entra.clientSecret` starts `v1:` and doesn't contain `s1`.
- `testSecretKeptWhenNotGiven`: saving again with `newSecret` null keeps `s1`; with `"s2"` replaces it.
- `testOrder`: two providers come back in `signin.providers` order, and `reorder` changes it; a reorder naming an unknown id is refused.
- `testRefusals`, each a `HealdanRuleException` with a message naming the field: an id outside `ID`; an `http://` issuer (and one with a fragment or query); a blank client id; a blank label; a label over 60 characters; a claim name blank or over 100 characters; a new provider with no secret; an id already used, when adding (`save` of an id `find` doesn't know adds it).
- `testHttpOnlyWhenAllowed`: with `HEALDAN_SIGNIN_ALLOW_HTTP` faked to `true` (the class reads it through a package-private `Supplier<String>` the test replaces), `http://keycloak:8080/realms/healdan` is accepted.
- `testEnabledNeedsEverything`: a provider turned off, or missing its secret, isn't in `enabled()`.
- `testRemoveRefusedWhileLinked`: with one linked `SignInIdentity` for `entra`, `remove("entra")` is refused with "Unlink its accounts first"; after unlinking it, `remove` deletes every `signin.entra.*` key and its group rows, takes it out of `signin.providers`, and keeps the identity rows.
- `testGroups`: `saveGroups` stores and returns the rows; a mapping to a group id that doesn't exist is refused; a blank value is refused.
- `testOptions`: `saveOptions("Use a Password", true, List.of("admin", "author"))` reads back; a blank label, or a recipient who isn't a user, is refused.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=SignInProvidersTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write `Preset`, `SignInProvider`, `GroupMapping` and `SignInProviders`**

Keys are built as `"signin." + id + "." + name`. `save` validates everything before writing anything. The issuer is parsed with `URI`, must be absolute with a host, no query or fragment, and `https` unless `httpAllowed()`. Booleans are written `true`/`false`. `enabled()` filters `all()` on `enabled && issuer != null && clientId != null && secret(id) != null`, so a lost key turns a provider off rather than failing (spec §13).

- [ ] **Step 4: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=SignInProvidersTest`
Expected: PASS.

- [ ] **Step 5: Commit:** "Keep sign-in providers' settings in hd_setting"

---

### Task 4: Talking to the provider

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/IdClaims.java`, `ProviderException.java`, `ProviderClient.java`
- Create: `healdan-core/src/test/java/com/logicaldoc/healdan/support/FakeProvider.java` (in the test-jar, for `healdan-api`'s tests too)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/signin/ProviderClientTest.java`

**Interfaces:**
- Consumes: `SignInProvider`, `SignInProviders.secret` (Task 3).
- Produces:

```java
public record IdClaims(String issuer, String identity, String username, String email, String givenName,
		String familyName, String name, List<String> groups, boolean groupsOverage) {}

public class ProviderException extends Exception {
	public ProviderException(String message, Throwable cause); // message for the log; the person sees the `provider` refusal
}

@Component
public class ProviderClient {
	public static ProviderClient get();

	public record Start(URI authorization, String state, String nonce, String verifier) {}
	public Start start(SignInProvider p, URI redirect, boolean offline) throws ProviderException;

	public record Tokens(IdClaims claims, String refreshToken) {} // refreshToken null when none came
	public Tokens exchange(SignInProvider p, URI redirect, String code, String verifier, String nonce) throws ProviderException;

	public enum Outcome { ACCEPTED, REFUSED, UNREACHABLE }
	public record Refreshed(Outcome outcome, IdClaims claims, String refreshToken) {} // claims and token may be null
	public Refreshed refresh(SignInProvider p, String refreshToken);

	public record Check(boolean ok, List<String> problems, URI redirect) {}
	public Check check(SignInProvider p, URI redirect); // the Sign-In tab's Check (spec section 10.3)

	public void forget(String providerId); // drops the cached discovery and keys, after a save
}
```

`FakeProvider` (test-jar):

```java
public final class FakeProvider implements AutoCloseable {
	public static FakeProvider start();                 // HttpServer on 127.0.0.1, a random port, an RSA key "k1"
	public String issuer();                             // http://127.0.0.1:<port>
	public String clientId();                           // "healdan-test"
	public String secret();                             // "test-secret"
	public FakeProvider claims(Map<String, Object> c);  // claims for the next sign-in: sub, oid, email, groups, ...
	public String authorize(URI authorization);         // the code the provider would send back; checks the client id, redirect and S256 challenge
	public FakeProvider refuseRefresh();                // the next refresh answers 400 invalid_grant
	public FakeProvider downForRefresh();               // the next refresh answers 503
	public FakeProvider signWith(String alg);           // e.g. "HS256", "none"; default RS256
	public FakeProvider rotateKey();                    // a new key "k2" signs from now on, served in the keys
	public FakeProvider issuerInToken(String iss);      // a wrong iss
	public FakeProvider audience(String aud);           // a wrong aud
	public FakeProvider expiresIn(Duration d);          // e.g. negative for expired
	public FakeProvider nonceInToken(String nonce);     // a wrong nonce
	public FakeProvider noRefreshToken();
	public int tokenRequests();
	public SignInProvider asProvider(String id);        // preset OTHER, this issuer, client id; saves it with the secret
}
```

It serves `/.well-known/openid-configuration`, `/keys`, `/authorize` (not called over HTTP: `authorize()` reads the parameters and answers the code) and `/token` (`authorization_code` and `refresh_token`, checking `client_secret_basic` and the verifier against the challenge), and signs ID tokens with `nimbus-jose-jwt`. Tests set `HEALDAN_SIGNIN_ALLOW_HTTP` through `SignInProviders`' supplier.

- [ ] **Step 1: Write `FakeProvider`, then the failing tests**

`ProviderClientTest extends AbstractHealdanTestCase`, against `FakeProvider`:
- `testStartBuildsTheRequest`: `start(p, redirect, false)` gives an authorisation URI with `response_type=code`, `response_mode=query`, `scope=openid profile email`, `client_id`, `redirect_uri`, `state`, `nonce` and `code_challenge` with `code_challenge_method=S256`; `state`, `nonce` and `verifier` are each at least 43 characters and differ between two starts. With `offline` true the scope adds `offline_access`.
- `testExchangeReadsTheClaims`: after `authorize`, `exchange` gives `IdClaims` with the identity from the provider's identity claim (`oid` for an Entra-preset provider, `sub` otherwise), `username`, `email`, the names and `groups`, and the refresh token.
- The ID token checks (spec §13), each a `ProviderException` and no claims: `testWrongSignatureRefused` (a token signed by another key with the same `kid`), `testHmacRefused` (`HS256` with the secret), `testNoneRefused`, `testWrongIssuerRefused`, `testWrongAudienceRefused`, `testExpiredRefused` (`expiresIn(-3 minutes)`), `testSkewAllowed` (`expiresIn(-1 minute)` passes), `testWrongNonceRefused`.
- `testKeyRotation`: after `rotateKey()`, a sign-in still works (the unknown `kid` makes the client fetch the keys again).
- `testDiscoveryOnlyWhenNeeded`: building `ProviderClient` and `SignInProviders` makes no request to the fake provider (`tokenRequests()` 0 and no discovery hit); the first `start` fetches discovery once, and a second `start` doesn't fetch it again.
- `testUnreachableAtStart`: with the fake provider closed, `start` throws `ProviderException` within 11 seconds.
- `testRefresh`: `refresh` gives `ACCEPTED`, with the new refresh token and claims; after `refuseRefresh()`, `REFUSED`; after `downForRefresh()`, `UNREACHABLE`; with the provider closed, `UNREACHABLE`.
- `testOverage`: an ID token with `_claim_names: {"groups": "src1"}` and no `groups` gives `groupsOverage` true and an empty `groups`.
- `testCheck`: `check` is OK for the fake provider and gives the redirect; with `signWith("HS256")` only in its discovery's `id_token_signing_alg_values_supported`, or without `offline_access` in `scopes_supported`, the problems say so; with a wrong issuer in discovery, the problems name both issuers.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=ProviderClientTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write `ProviderClient`**

- **Discovery** with Nimbus's `OIDCProviderMetadata.resolve(new Issuer(issuer), connectTimeout, readTimeout)`, at 10 s each, cached per provider id until `forget`. **Keys** through `JWKSourceBuilder.create(jwksUri.toURL(), new DefaultResourceRetriever(10000, 10000))` with its own cache, which fetches again for an unknown `kid`.
- **The ID token check:**

```java
IDTokenValidator validator = new IDTokenValidator(metadata.getIssuer(), new ClientID(p.clientId()),
		new JWSVerificationKeySelector<>(ALGORITHMS, jwkSource), null);
validator.setMaxClockSkew(120);
IDTokenClaimsSet claims = validator.validate(idToken, nonce == null ? null : new Nonce(nonce));
```

  `ALGORITHMS` is the Global Constraints' set. A `PlainJWT` (`none`) or an HMAC algorithm fails before the validator. `nonce` is null only for a refresh, which checks everything else.
- **The exchange:** `TokenRequest` with `AuthorizationCodeGrant(code, redirect, new CodeVerifier(verifier))` and `ClientSecretBasic`, sent with `HTTPRequest.setConnectTimeout(10000)` and `setReadTimeout(10000)`.
- **The refresh:** `RefreshTokenGrant`. A `TokenErrorResponse` with `invalid_grant`, or any 400 or 401, is `REFUSED`. An `IOException`, a 5xx or a timeout is `UNREACHABLE`.
- **Logging:** never a token, code, verifier, state or secret: only the provider id and the outcome.

- [ ] **Step 4: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=ProviderClientTest`
Expected: PASS.

- [ ] **Step 5: Commit:** "Talk to sign-in providers through Nimbus's OIDC SDK"

---

### Task 5: Accounts, groups and passwords

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/SignInAccounts.java`, `GroupSync.java`, `ProviderPasswordListener.java`, `ProviderPasswordException.java`
- Modify: `healdan-core/src/main/resources/plugin.xml` (the `healdanSignInPasswords` user listener, position 95)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/signin/SignInAccountsTest.java`, `GroupSyncTest.java`, `ProviderPasswordListenerTest.java`

**Interfaces:**
- Consumes: `IdClaims` (Task 4); `SignInProvider`, `SignInProviders.groups` (Task 3); `SignInIdentityDAO` (Task 2).
- Produces:

```java
@Component
public class SignInAccounts {
	public static SignInAccounts get();
	public sealed interface Decision permits Allowed, Refused {}
	public record Allowed(User user, boolean linkedNow, boolean madeNow) implements Decision {}
	public record Refused(String code) implements Decision {} // "no-group" | "email-taken" | "username-taken"
	public Decision decide(SignInProvider p, IdClaims claims) throws PersistenceException; // spec section 7.2
	public void refresh(User user, SignInProvider p, IdClaims claims) throws PersistenceException; // names, email, groups; for a re-check
}

public final class GroupSync {
	/** The mapped Healdan group ids the claims give; empty means the gate refuses. */
	public static Set<Long> mapped(List<GroupMapping> map, List<String> providerGroups);
	/** Adds the user to every mapped group in {@code granted}, removes it from every other mapped group, and leaves the rest. */
	public static boolean apply(User user, List<GroupMapping> map, Set<Long> granted) throws PersistenceException; // true if it changed
}

public class ProviderPasswordException extends AuthenticationException {} // "A provider account can't have a password."

public class ProviderPasswordListener implements UserListener {
	/** Set by unlinking alone (Task 10), so that save may move the account off OIDC with a temporary password. */
	public static <T> T unlinking(Callable<T> work) throws Exception;
}
```

- [ ] **Step 1: Write the failing tests**

`SignInAccountsTest extends AbstractHealdanTestCase`, with an `entra` provider (link by email on), `authentik` (off), a group `Litigation` mapped from `g-lit`, and users made with `fx.user(...)`:
- `testLinkedIdentityIsItsAccount`: with a linked row for `oid-1` → `author`, `decide` gives `Allowed(author, false, false)` whatever the email.
- `testGateRefusesAndMakesNothing`: claims with groups `["g-other"]` give `Refused("no-group")`; no user, identity or membership was made (row counts before and after).
- `testGateEmptiesALinkedAccountsMappedGroups`: `author` linked and in `Litigation`; claims with no mapped group give `no-group`, and `author` is no longer in `Litigation`, but still in its own group, Everyone and an unmapped group.
- `testLinksByEmailIgnoringCase`: `jane` (`Jane@Firm.example`, a password) and claims `email: jane@firm.example` give `Allowed(jane, true, false)`; `jane`'s source is `OIDC`, its password null, an identity row with `how` `email`; `jane` is in `Litigation`.
- `testLinkByEmailOffMakesANewAccount`: the same through `authentik` gives a new account named by `preferred_username`, and `jane` is untouched.
- `testEmailMatchingTwoAccountsRefused`, `testEmailMatchingAdminRefused`, `testEmailMatchingALinkedAccountRefused`, `testEmailMatchingASystemAccountRefused`: each `Refused("email-taken")`, nothing changed.
- `testNewAccount`: no match gives a user with the username claim, `given_name` and `family_name` (and `name` split when they're missing), the email, source `OIDC`, no password, type `DEFAULT`, the server's default language, in Everyone and `Litigation`; an identity row with `how` `new`.
- `testUsernameTakenRefused`: an unlinked `bob` exists and claims carry `preferred_username: bob` with no email match: `Refused("username-taken")`. So are `admin`, `a>b`, and a blank username.
- `testEveryLaterSignInUpdatesNamesAndEmailNotUsername`: a linked account's next `decide` takes the new names and email, keeps its username, and updates `lastSignIn`.
- `testRefreshAppliesGroupsAndGate`: `refresh` with fewer groups removes the account from the dropped mapped group.
- `testOverageCountsAsNoGroup`: `groupsOverage` true with an empty list gives `no-group`, and the log line names "groups assigned to the application".

`GroupSyncTest` (plain unit tests on `mapped` and `apply`): one provider group mapped to two Healdan groups; a deleted Healdan group's mapping ignored; `apply` returns false when nothing changed.

`ProviderPasswordListenerTest extends AbstractHealdanTestCase` (Review Focus 1), with `jane` linked:
- `testStoreWithPasswordRefused`: `jane.setDecodedPassword("Abcdef-123456!")`, then `UserDAO.get().store(jane)` throws `ProviderPasswordException`, and the stored password is still null.
- `testMovingOffOidcRefused`: `setSource(DEFAULT)` and a password, outside `unlinking`, is refused.
- `testUnlinkingMayMoveOff`: the same inside `ProviderPasswordListener.unlinking(...)` stores.
- `testOrdinaryAccountsUnaffected`: `bob` changes its password as before.
- **Every password route**, each refused for `jane` and still allowed for `bob`:
  - `SessionManager.get().newSession("jane", "<anything>", client)` throws (a null password never matches);
  - `LDAuthenticationProvider.authenticate` with a `UsernamePasswordAuthenticationToken` (the form login, and WebDAV, CMIS and REST Basic) throws;
  - `SoapAuthService.login("jane", …)` throws;
  - `SecurityServiceImpl.changePassword(admin's id, jane's id, null, "Abcdef-123456!", false)` doesn't change it, and answers an error;
  - `LoginServiceImpl.changePassword(jane's id, null, …)` doesn't change it;
  - `PswRecovery`'s reset (the lost-password link's servlet, with a `PSW_RECOVERY` ticket for `jane`) doesn't set one;
  - SOAP `SoapSecurityService.changePassword` and `storeUser` with a password, as `admin`, don't.

  Where a route can't run in `healdan-core`'s tests, the builder says so in the PR and covers it in Task 7's API tests or by hand on the stack.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SignInAccountsTest,GroupSyncTest,ProviderPasswordListenerTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Write `SignInAccounts`, `GroupSync` and the listener**

- **`decide`** runs in one transaction, under a lock per provider and identity, so two first sign-ins of one person can't make two accounts. The order is spec §7.2's: linked, gate, email, new.
- **Linking** sets `source` `OIDC`, `password` null (`setPassword(null)`, `setDecodedPassword` untouched), names and email, then `UserDAO.store`. The account's old device tokens are revoked by `TokenUserListener` (correction 2). The method then stores the identity row with `linkKey` and `linkUser`.
- **The listener's `beforeSave`:**

```java
@Override
public void beforeSave(User user, UserHistory transaction, Map<String, Object> dictionary) {
	if (UNLINKING.get() != null)
		return;
	boolean provider = user.getSource() == UserSource.OIDC;
	boolean wasProvider = user.getId() != 0 && storedSource(user.getId()) == UserSource.OIDC.ordinal();
	if ((provider && StringUtils.isNotEmpty(user.getPassword())) || (wasProvider && !provider))
		throw new ProviderPasswordException();
}
```

  `storedSource` reads `select ld_source from ld_user where ld_id = ?` with a bound parameter. It's registered in `plugin.xml` at position 95, before `TokenUserListener` (110).
- **Logging:** every link, new account and group membership change is logged at info, with the provider, the account's username and, for groups, the group's name (spec §8).

- [ ] **Step 4: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SignInAccountsTest,GroupSyncTest,ProviderPasswordListenerTest'`
Expected: PASS.

- [ ] **Step 5: Commit:** "Decide provider sign-ins' accounts, sync their groups, and keep passwords off them"

---

### Task 6: Opening the CE session

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/SignInAuthenticator.java`, `SignIns.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/token/HealdanTokenAuthenticator.java` (`authenticate` returns null for a key starting `hd_signin_`)
- Modify: `healdan-core/src/main/resources/plugin.xml` (`healdanSignInAuthentication`, position 106)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/signin/SignInsTest.java`

**Interfaces:**
- Produces:

```java
public final class SignIns {
	public static final String PREFIX = "hd_signin_";
	/** Opens a CE session for an account a provider sign-in decided, through CE's whole chain and throttle (spec section 7.6). */
	public static Session openSession(User user, Client client) throws AuthenticationException;
}
@Component("signInAuthenticator")
public class SignInAuthenticator extends AbstractAuthenticator { /* claims only the pending hd_signin_ key */ }
```

- [ ] **Step 1: Write the failing tests**

`SignInsTest extends AbstractHealdanTestCase`:
- `testOpensASessionForTheAccount`: `openSession(jane, client)` gives an open session for `jane`, keyed by a hash no client knows (not `jane`'s id or username).
- `testChainChecksApply`: a disabled `jane` gets `AccountDisabledException`; outside working hours, `OutsideWorkingTimeException`.
- `testOtherKeysRefused`: `SessionManager.get().newSession("hd_signin_" + 43 random characters, client)` with nothing pending throws, and so does a pending key from another thread.
- `testThrottleBlocksTheAddress`: with the address blocked (20 failures counted), `openSession` throws `IPBlockedException`.
- `testDeviceTokensStillWork`: a device token's `HealdanTokenAuthenticator.openSession` still works (M2's tests pass unchanged: run `TokenFilterTest` in Step 4).

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=SignInsTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write it on M2's pattern**

`SignIns.openSession` puts a `Pending(key, userId)` in a `ThreadLocal`, calls `SessionManager.get().newSession(key, client)`, and removes it in `finally`. `SignInAuthenticator.authenticate` returns null for keys not starting `hd_signin_`. It refuses with `TokenRefusedException` (M2's, so the throttle doesn't count it against the address) unless the key is the pending one. It then loads the user and runs `DefaultAuthenticator.validateUser`. `HealdanTokenAuthenticator.authenticate` returns null for `hd_signin_` keys.

- [ ] **Step 4: Run the tests, M2's included**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SignInsTest,HealdanTokenAuthenticatorTest'` and `mvn -B -ntp -pl healdan-core install -DskipTests && mvn -B -ntp -pl healdan-api test -Dtest='TokenFilterTest,LogicalDocRoutesTest'`
Expected: PASS.

- [ ] **Step 5: Commit:** "Open a CE session for a provider sign-in through the whole chain"

---

### Task 7: The web page's flow

**Files:**
- Create: `healdan-api/src/main/java/com/logicaldoc/healdan/api/PublicAddress.java` (moved out of `AddinResource`)
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/AddinResource.java` (uses `PublicAddress`)
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/PendingSignIns.java`
- Create: `healdan-api/src/main/java/com/logicaldoc/healdan/api/SignInResource.java`, and its pages `healdan-api/src/main/resources/healdan/api/signin/done.html`
- Modify: `healdan-api/src/test/java/com/logicaldoc/healdan/api/RouteCoverageTest.java`, `Contract.java` (leave `/api/signin` out, as `/api/addin`)
- Test: `healdan-api/src/test/java/com/logicaldoc/healdan/api/SignInResourceTest.java`, `PublicAddressTest.java`
- Docs: `site/content/developer/api.md` (a section "Sign-in Pages": `/api/signin/` runs a provider's sign-in for the web page and the helper, isn't JSON API, and takes no token)

**Interfaces:**
- Consumes: `ProviderClient` (Task 4), `SignInAccounts` (Task 5), `SignIns` (Task 6), `SignInProviders` (Task 3).
- Produces:

```java
public final class PublicAddress {
	public record Address(String origin, String base) {}
	/** server.url when set, else the request's; https, or http on this machine (or anywhere when HEALDAN_SIGNIN_ALLOW_HTTP). */
	public static Address of(HttpServletRequest request); // throws ApiProblem 409 no-public-address
}

@Component
public class PendingSignIns {
	public record Pending(String provider, String state, String nonce, String verifier, String browserHash,
			Instant expires, String helperRedirect, String helperState, String helperChallenge) {}
	public void put(Pending p);
	public Optional<Pending> take(String state); // once; empty when unknown, used or expired
}
// SignInResource: @Path("/signin")
//   GET {id: [a-z0-9-]{1,32}}            start
//   GET {id: [a-z0-9-]{1,32}}/callback   callback (code, state, error, error_description)
//   GET done                             the "close this tab" page (error optional)
```

- [ ] **Step 1: Write the failing tests**

`SignInResourceTest extends ApiTestCase`, with a `FakeProvider` saved as provider `fake`, `Litigation` mapped from `g-lit`, and `jane` (email `jane@firm.example`) a local account:
- `testStartRedirectsAndSetsTheCookie`: `GET /api/signin/fake` is `302` to the fake provider's authorisation endpoint with `redirect_uri=http://localhost:8080/api/signin/fake/callback`, and sets `hd_signin` (HttpOnly, `SameSite=Lax`, `Path=/api/signin/`).
- `testUnknownOrOffProvider`: `GET /api/signin/nope` and a turned-off provider redirect to `/login.jsp?signin=provider`.
- `testCallbackSignsTheBrowserIn`: start, `authorize`, then the callback with the same `hd_signin` cookie is `302` to `/frontend.jsp`. It sets `ldoc-sid` (HttpOnly, `Path=/`, Secure when `cookies.secure`) to a session that is open for `jane`, linked by email, and gives the HTTP session a new id.
- `testGoesToThePageAskedFor`: with the HTTP session's `RequestedUrl` set to `/frontend.jsp?docId=5` before the start, the callback goes there. With one `login.jsp` would ignore (containing `/sc/`, or ending `/frontend`), or one on another host, it goes to `/frontend.jsp` (spec §5.2 step 5).
- `testCallbackFromAnotherBrowserRefused`: the callback without the cookie, or with another browser's, is `302` to `/login.jsp?signin=expired`, and no session or link is made (Review Focus 3).
- `testStateUsedTwiceRefused`: the same callback twice: the second is `expired`.
- `testStateForAnotherProviderRefused`: a state started for `fake` arriving at `/api/signin/other/callback` is `expired` (mix-up).
- `testExpiredState`: a state older than 10 minutes (the test moves `PendingSignIns`' clock) is `expired`.
- `testProviderError`: `?error=access_denied&state=…` is `302` to `/login.jsp?signin=provider`.
- `testRefusalsReachThePage`: claims with no mapped group give `?signin=no-group`; an email matching two accounts `email-taken`; a taken username `username-taken`; a disabled linked account `account`.
- `testDonePage`: `GET /api/signin/done` is `200` HTML saying "Signed in to Healdan. You can close this tab."; with `?error=no-group`, it says the `no-group` message with the label, HTML-escaped.
- `testNoTokenNeeded`, and `testNeverLogsSecrets`: with `LogCapture`, no log line contains the code, state, nonce, verifier, the ID token or the secret.

`PublicAddressTest`: the `AddinResourceTest` cases still pass through `PublicAddress`, plus `http://keycloak:8080` accepted only with `HEALDAN_SIGNIN_ALLOW_HTTP`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='SignInResourceTest,PublicAddressTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Write `PublicAddress`, `PendingSignIns` and `SignInResource`**

- **The redirect** is `PublicAddress.of(request).base() + "/api/signin/" + id + "/callback"`.
- **`start`** for the web page (no `client` parameter):

```java
String browser = ApiTokens.randomPart();
ProviderClient.Start s = ProviderClient.get().start(provider, redirect, false);
pending.put(new Pending(id, s.state(), s.nonce(), s.verifier(), ApiTokens.hash(browser),
		Instant.now().plus(Duration.ofMinutes(10)), null, null, null));
return Response.seeOther(s.authorization())
		.header("Set-Cookie", "hd_signin=" + browser + "; Path=/api/signin/; HttpOnly; SameSite=Lax"
				+ (request.isSecure() ? "; Secure" : ""))
		.build();
```

- **`callback`:** `take(state)`; the provider must match the path's id; the cookie's hash must match `browserHash`. Then `exchange`, `decide` and `SignIns.openSession(user, buildClient(request))`. Then `request.changeSessionId()` (creating the HTTP session first if there isn't one), set `ldoc-sid` as `LDAuthenticationSuccessHandler` does, call `SessionManager.get().saveSid(request, response, sid)`, clear `hd_signin` (`Max-Age=0`), and send `302` to where the password form would go. That's the HTTP session's `RequestedUrl` under `login.jsp`'s conditions, and only a path on this server, never another host; otherwise `<base>/frontend.jsp`.
  - A refusal at any step is `302` to `<base>/login.jsp?signin=<code>`.
  - An `AuthenticationException` from the chain is `account`; a `ProviderException` is `provider`.
  - Each refusal is logged with the provider, the identity, if known, and the code.
- **`done.html`** is read from the jar, its message picked by code from Global Constraints' table, and served with `DocsResource`'s headers and a `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'`.

- [ ] **Step 4: Run the tests, the add-in's included**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='SignInResourceTest,PublicAddressTest,AddinResourceTest,RouteCoverageTest,ContractTest'`
Expected: PASS.

- [ ] **Step 5: Docs, then commit:** "Sign the web page in through a provider at /api/signin/"

---

### Task 8: The helper's flow on the server, and `GET /server`

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/OneTimeCodes.java`
- Modify: `SignInResource.java` (the `client=helper` branch), `AuthResource.java` (the code body), `ServerResource.java`
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/model/SignIn.java` (`code`, `codeVerifier`, `redirectUri`), `ServerInfo.java` (`providers`, `passwordLabel`); create `ProviderInfo.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/token/ApiTokens.java` (`issue` takes the provider and the sealed refresh token)
- Modify: `healdan-api/src/main/resources/healdan/api/openapi.yaml` (`SignIn` as `oneOf` a password body and a code body; `ServerInfo`'s `signIn` items `password` or `provider`, `providers`, `passwordLabel`)
- Test: `healdan-api/src/test/java/com/logicaldoc/healdan/api/HelperSignInTest.java`, `ServerResourceTest.java` (new cases)
- Docs: `site/content/developer/api.md` ("Signing In Through a Provider": `GET /server`'s providers, the loopback redirect, the code exchange, its refusals)

**Interfaces:**
- Produces:

```java
@Component
public class OneTimeCodes {
	public record Redeemed(long userId, String provider, String refreshToken) {}
	public String issue(long userId, String provider, String refreshToken, String challenge, String redirect); // 60 s, once
	public Optional<Redeemed> redeem(String code, String verifier, String redirect); // empty on any mismatch; used up either way
}
public record ProviderInfo(String id, String label) {}
public record ServerInfo(String product, String version, String apiVersion, Map<String, String> labels,
		List<String> signIn, List<ProviderInfo> providers, String passwordLabel) {}
public record SignIn(String username, String password, String name, DeviceRef device, String code,
		String codeVerifier, String redirectUri) {}
```

`POST /api/v1/auth/tokens` with `{code, codeVerifier, redirectUri, name, device}` gives `201` and the same `SignedIn` as a password; the token's row has `provider` and the sealed `refresh`.

- [ ] **Step 1: Write the failing tests**

`HelperSignInTest extends ApiTestCase`, with the fake provider as `fake`:
- `testHelperStartNeedsALoopbackRedirect`: `GET /api/signin/fake?client=helper&redirect_uri=http://127.0.0.1:50123/&state=h1&code_challenge=<S256 of v>&code_challenge_method=S256` is `302` to the provider, with `offline_access` in the scope. With `redirect_uri` `http://localhost:50123/`, `http://evil.example/`, `https://127.0.0.1:1/` or `http://127.0.0.1.evil.example/`, it's `400` and no redirect (Review Focus 4). `http://[::1]:50123/` is accepted.
- `testHelperStartNeedsAChallenge`: a missing challenge, or a method other than `S256`, is `400`.
- `testCallbackHandsTheHelperACode`: after `authorize`, the callback is `302` to `http://127.0.0.1:50123/?code=<c>&state=h1`, sets no `ldoc-sid`, and opens no CE session.
- `testExchangeIssuesADeviceToken`: `POST /api/v1/auth/tokens {code, codeVerifier: v, redirectUri, name: "Helper on Mac", device: {id}}` is `201`; the token works on `GET /me` as `jane`; its row has `provider` `fake` and a sealed refresh token.
- `testCodeOnce`: a second exchange of the same code is `401 sign-in-failed`.
- `testCodeExpires`: after 61 seconds (the test moves `OneTimeCodes`' clock), `401`.
- `testWrongVerifierOrRedirect`: either is `401`, and the code is used up anyway.
- `testHelperRefusals`: no mapped group: `302` to `http://127.0.0.1:50123/?error=no-group&state=h1`.
- `testPasswordBodyUnchanged`: M2's password sign-in still works, and `jane`'s (now linked) password is `401 sign-in-failed`, with the same detail as a wrong password.
- `testBodiesDontMix`: a body with both a password and a code is `422 validation`.

`ServerResourceTest`:
- `testNoProviders`: `signIn` is `["password"]`, `providers` `[]`, `passwordLabel` `Sign In with a Password`.
- `testProviders`: with two enabled providers and a turned-off one, `signIn` is `["password", "provider"]` and `providers` lists the two, in order, with their labels.

Every response is checked against `openapi.yaml` (`Contract`), as every API test is.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='HelperSignInTest,ServerResourceTest'`
Expected: FAIL.

- [ ] **Step 3: Write the helper's branch, `OneTimeCodes` and the exchange**

- **The loopback check:** `URI` parse, scheme exactly `http`, host exactly `127.0.0.1` or `[::1]`, a port, path `/` or empty, and no query, fragment or user info.
- **`OneTimeCodes`:** codes are `ApiTokens.randomPart()`, stored in a `ConcurrentHashMap` keyed by their SHA-256, and removed on the first `redeem`, whatever the outcome. The verifier is checked as `Base64Url(SHA-256(verifier)) == challenge`.
- **The exchange in `AuthResource`:**
  - it checks the name and device as for a password;
  - it redeems the code, loads the user and opens the session with `SignIns.openSession`;
  - it then calls `ApiTokens.issue(user, name, device, sid, provider, SecretBox.get().seal(refreshToken))`;
  - when the provider gave no refresh token, it logs at info that this token won't be re-checked (spec §9);
  - any failure is `401 sign-in-failed`.

- [ ] **Step 4: Run the tests, the contract's included**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='HelperSignInTest,ServerResourceTest,AuthResourceTest,OpenApiFileTest,RouteCoverageTest,ContractTest'`
Expected: PASS.

- [ ] **Step 5: Docs, then commit:** "Hand the helper a one-time code for its device token, and list providers in GET /server"

---

### Task 9: Checking back with the provider

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/ProviderRecheck.java`
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/TokenFilter.java` (asks `ProviderRecheck` for a token from a provider, before the session)
- Test: `healdan-api/src/test/java/com/logicaldoc/healdan/api/ProviderRecheckTest.java`

**Interfaces:**
- Produces:

```java
@Component
public class ProviderRecheck {
	public static ProviderRecheck get();
	/** False when the provider refused and the token is now revoked (spec section 9). */
	public boolean keep(ApiToken token, boolean sessionReopening);
}
```

- [ ] **Step 1: Write the failing tests**

`ProviderRecheckTest extends ApiTestCase`, with a helper token from `fake` (Task 8's flow, as a helper in the test):
- `testNoCheckWithinTheInterval`: a request soon after sign-in makes no refresh (`tokenRequests()` unchanged).
- `testCheckWhenTheSessionReopens`: after its CE session is killed, the next request refreshes once and answers `200`; `checked` moves on, and a new refresh token is stored if one came.
- `testCheckAfterTheInterval`: with `signin.recheck.minutes` 0, each request refreshes.
- `testRefusalRevokes`: `refuseRefresh()`, then a due check: `401 unauthenticated`, the token's row is revoked, and its CE sessions are closed (Review Focus 5's other half).
- `testOutageKeeps`: `downForRefresh()`, or the provider closed: `200`, the token is kept, and no refresh is tried again for 15 minutes (the test moves the clock), then it is (Review Focus 5).
- `testGroupsFollow`: the refreshed claims drop `g-lit`: `jane` leaves `Litigation`; no mapped group left: `401` and revoked.
- `testAnotherIdentityRefused`: a refreshed ID token whose identity isn't the linked one counts as refused.
- `testProviderTurnedOff`: no refresh is tried, the token is kept, and checks resume when it's turned on again.
- `testNoRefreshToken`: a token issued with `noRefreshToken()` is never re-checked.
- `testPasswordTokensUntouched`: a password device token makes no refresh.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest=ProviderRecheckTest`
Expected: FAIL.

- [ ] **Step 3: Write `ProviderRecheck`, and call it from `TokenFilter`**

`keep` returns true at once for a token with no `provider` or no `refresh`, a provider turned off or unknown, or a check that isn't due. A check is due when `sessionReopening`, or `checked` is older than `signin.recheck.minutes`, and in either case not within 15 minutes of an unreachable attempt (kept in memory per token id).

It holds a per-token lock, opens the refresh token with `SecretBox`, and calls `ProviderClient.refresh`:
- **`ACCEPTED`:** stores the new sealed refresh token and `checked`. With claims, it applies `SignInAccounts.refresh`, and revokes on a gate refusal or a different identity.
- **`REFUSED`:** `ApiTokens.revoke(token)`.
- **`UNREACHABLE`:** notes the time and logs a warning with the provider id.

`TokenFilter` calls `keep(token, sessionClosed)` after `findUsable` and before `session(...)`, and answers `refused()` when it's false.

- [ ] **Step 4: Run the tests, M2's token tests included**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='ProviderRecheckTest,TokenFilterTest,AuthResourceTest'`
Expected: PASS.

- [ ] **Step 5: Commit:** "Re-check helper sign-ins with their provider, and revoke only on a refusal"

---

### Task 10: Unlinking, and the `admin` alert

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/SignInAdmin.java`, `AdminSignInAlert.java`, `OutgoingEmail.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/HealdanStartup.java` (register `AdminSignInAlert` with `SessionManager.get().addListener` once the seeds ran)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/signin/SignInAdminTest.java`, `AdminSignInAlertTest.java`, `OutgoingEmailTest.java`
- Docs: `site/content/install/known-limitations.md` (provider accounts have no password for WebDAV, CMIS, SOAP or REST; web sessions aren't re-checked with the provider; an account has one identity; Sign Out doesn't sign out of the provider; the `admin` alert needs outgoing email)

**Interfaces:**
- Produces:

```java
@Component
public class SignInAdmin {
	public static SignInAdmin get();
	public record Linked(long userId, String username, String name, String how, Date linked, Date lastSignIn) {}
	public List<Linked> linked(String provider) throws PersistenceException;
	/** Unlinks one account (spec section 7.7). Returns the temporary password when it wasn't emailed, else null. */
	public String unlink(long userId, User by) throws PersistenceException;
	/** Returns how many were unlinked, and the temporary passwords that weren't emailed, by username. */
	public record Unlinked(int count, Map<String, String> passwords) {}
	public Unlinked unlinkEveryone(String provider, User by) throws PersistenceException;
}
public final class OutgoingEmail { public static boolean isSetUp(); } // correction 3
public class AdminSignInAlert implements SessionListener {}
```

- [ ] **Step 1: Write the failing tests**

`SignInAdminTest extends AbstractHealdanTestCase`, with `jane` linked, a device token for her, and a document she has checked out:
- `testUnlink`:
  - `unlink(jane, admin)` leaves `jane` with source `DEFAULT`, a password that `newSession("jane", <it>)` accepts and that is marked expired (so the sign-in is `PasswordExpiredException`), and her groups as they were;
  - her device token is revoked (correction 2);
  - the identity row has `unlinked`, `unlinkedBy` admin's id, and null `linkKey` and `linkUser`;
  - her check-out, history and favourites are untouched (same user id).
- `testUnlinkEmailsWhenSetUp`: with outgoing email set up (the test's config), `unlink` returns null and an email is queued through CE's `notifyAccount` path; without, it returns the password.
- `testRelinkAfterUnlink`: `decide` with `jane`'s claims through a provider with link by email on links her again, removes the password, and adds a second identity row.
- `testUnlinkEveryone`: two linked accounts of `fake` and one of `other`: `unlinkEveryone("fake", admin)` gives count 2, and `other`'s account is still linked; `SignInProviders.remove("fake")` then works.
- `testUnlinkNotLinked`: an account with no link is refused with a `HealdanRuleException`.

`OutgoingEmailTest`: `localhost`, `127.0.0.1`, `::1` or blank host, or sender `logicaldoc@acme.com` or blank, is not set up; `smtp.healdan.example` with `office@healdan.example` is.

`AdminSignInAlertTest extends AbstractHealdanTestCase`, with a provider on and the alert on:
- `testAlertOnAdminPasswordSignIn`: `newSession("admin", "admin", client)` schedules one email per recipient with the subject `… - admin signed in with its password`, the address HTML-escaped, and no system message (owner).
- `testNoAlertWhenOff`, `testNoAlertWithoutAProvider`, `testNoAlertWithoutOutgoingEmail` (correction 3), `testNoAlertForOtherAccounts` (`bob`), `testNoAlertForKeySessions` (`admin`'s device token reopening its session), `testNoAlertForImpersonation`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SignInAdminTest,AdminSignInAlertTest,OutgoingEmailTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Write them**

- **`unlink`**, inside `ProviderPasswordListener.unlinking(...)`, in one transaction:
  - `PasswordGenerator.generate("default")`, then `setSource(DEFAULT)`, `setDecodedPassword(password)` and `setPasswordExpired(true)`;
  - `UserDAO.store(user, history)`, with a `PASSWORDCHANGED` `UserHistory` naming the admin;
  - the identity row marked unlinked;
  - `ApiTokens.revokeAll(userId)`;
  - when `OutgoingEmail.isSetUp()`, CE's credentials email, as `SecurityServiceImpl.changePassword`'s `notify` sends it (factor out nothing upstream: build the same `EMail` from the `psw.rec1` template's dictionary, or call `SecurityServiceImpl.notifyAccount` if it's reachable without a session; say which in the PR).
- **`AdminSignInAlert.onSessionCreated`** sends only when:
  - the session's user id is `User.USERID_ADMIN`;
  - the session has no key and no impersonator;
  - `adminAlert()` is on, `enabled()` isn't empty, and `OutgoingEmail.isSetUp()`.

  It then schedules, on `ThreadPools`, one `EMail` per recipient from the `signin.adminalert` template, as `LoginThrottle.notifyBruteForceAttack` does, but with no `SystemMessage`.

- [ ] **Step 4: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SignInAdminTest,AdminSignInAlertTest,OutgoingEmailTest,SignInAccountsTest'`
Expected: PASS.

- [ ] **Step 5: Docs, then commit:** "Unlink accounts, and alert the admins when admin signs in with its password"

---

### Task 11: The stand-in provider on the smoke stack

**Files:**
- Create: `docker/tests/compose.signin.yaml`, `docker/tests/keycloak/healdan-realm.json`, `docker/tests/test-signin.sh`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SmokeSignInSeed.java` (`smoke-signin`)
- Modify: `docker/smoke-test.sh` (`compose()` adds `-f docker/tests/compose.signin.yaml`; a step `5e`; step 1 waits for Keycloak too; step 5d's environment gains the helper's sign-in variables)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/SmokeSignInSeedTest.java`
- Docs: `site/content/developer/tests.md` (the fake provider, the stand-in, step 5e), `site/content/developer/continuous-integration.md` (Keycloak's image in the smoke test, and its time)

- [ ] **Step 1: The overlay and the realm**

`compose.signin.yaml`:

```yaml
# Smoke test only: a throwaway Keycloak as a stand-in OpenID Connect provider (M4 spec section 16.1), and the switches
# that make Healdan set it up as provider "smoke" and allow its plain-HTTP issuer.
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.7.5
    command: ["start-dev", "--import-realm", "--http-port=8080", "--hostname=http://keycloak:8080"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: smoke-admin-pw
      KC_HEALTH_ENABLED: "true"
    volumes:
      - ./docker/tests/keycloak:/opt/keycloak/data/import:ro
    ports:
      - "127.0.0.1:18080:8080"
  app:
    environment:
      HEALDAN_SIGNIN_ALLOW_HTTP: "true"
      HEALDAN_SMOKE_SIGNIN_ISSUER: http://keycloak:8080/realms/healdan
      HEALDAN_SMOKE_SIGNIN_CLIENT_ID: healdan
      HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET: smoke-client-secret
```

The realm `healdan`:
- a confidential client `healdan` with secret `smoke-client-secret`, redirect URIs `["*"]` (a throwaway realm on a throwaway stack), standard flow only, and PKCE `S256` required;
- a group-membership protocol mapper on the client: claim `groups`, full path off, in the ID token;
- `offline_access` as a default client scope;
- groups `healdan-users` and `healdan-other`;
- users, each with password `Smoke-Sso-Pw-42!x`:
  - `sso-user` (email `sso-user@smoke.example`, in `healdan-users`), whom the smoke seed also makes as a local Healdan account with that email, so the first sign-in links it;
  - `sso-helper` (in `healdan-users`);
  - `sso-outsider` (in `healdan-other`).

- [ ] **Step 2: Write the failing seed test, then the seed**

`SmokeSignInSeedTest`: with the three variables set (through the seed's `Supplier`), the step saves provider `smoke` (preset `other`, link by email on), maps `healdan-users` to a new group `Smoke SSO`, makes local user `sso-user` (email `sso-user@smoke.example`, a password, in `Smoke SSO`), and sets `signin.recheck.minutes` to `0`. Without them, `enabled()` is false and nothing is written.

Run: `mvn -B -ntp -pl healdan-core test -Dtest=SmokeSignInSeedTest`
Expected: FAIL, then PASS once `SmokeSignInSeed` is written. It follows `SmokeFixtureSeed`'s pattern.

- [ ] **Step 3: Write `test-signin.sh`**

It needs `curl`, `jq` and `python3`. Every request to Keycloak from the host uses `--connect-to keycloak:8080:127.0.0.1:18080`. Its checks, each a `check` line as in `test-api.sh`:

1. **The web page:** `GET $BASE/api/signin/smoke` with a cookie jar, following redirects as far as Keycloak's login form. `POST` the form (its `action` read with `python3`'s `html.parser`) with `sso-user`. The redirects end at `$BASE/frontend.jsp` answering `200`, and the jar holds `ldoc-sid`.
2. **Linked:** `POST $BASE/api/v1/auth/tokens` with `sso-user` and its local password is `401 sign-in-failed`.
3. **The gate:** the same flow as `sso-outsider` ends at `$BASE/login.jsp?signin=no-group`.
4. **The helper:** `GET $BASE/api/signin/smoke?client=helper&redirect_uri=http://127.0.0.1:9/&state=s1&code_challenge=<S256 of the verifier>&code_challenge_method=S256` with `sso-helper` through Keycloak's form. The last redirect's `Location` (not followed) is `http://127.0.0.1:9/?code=…&state=s1`. The exchange is `201`, and `GET /api/v1/me` with the token is `200` naming `sso-helper`.
5. **Leavers:** disable `sso-helper` through Keycloak's admin API (a master-realm token for `admin`, then `PUT /admin/realms/healdan/users/<id>` with `{"enabled": false}`). The next `GET /api/v1/me` is `401` (`signin.recheck.minutes` is 0).

`smoke-test.sh` runs it as step 5e, after 5d.

- [ ] **Step 4: Run the smoke test**

Run: `docker/smoke-test.sh` (with `SMOKE_HELPER_TEST=1` once Task 15 lands)
Expected: every check passes; record step 5e's seconds and Keycloak's start time in the PR.

- [ ] **Step 5: Docs, then commit:** "Run provider sign-in against a stand-in Keycloak on the smoke stack"

---

### Task 12: The sign-in page

**Files:**
- Create: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/common/client/healdan/HealdanSignInButtons.java`
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/login/client/LoginPanel.java` (one line in `initGUI`)
- Docs: `site/content/user/getting-started/signing-in/index.md` (the provider's button, the password link, and each refusal's message and what to do), `site/content/developer/how-healdan-extends-logicaldoc.md` (`LoginPanel` joins the list)

**Interfaces:**
- Consumes: `GET /api/v1/server`'s `providers` and `passwordLabel` (Task 8); `/api/signin/<id>` (Task 7); `?signin=<code>` (Task 7).
- Produces:

```java
public final class HealdanSignInButtons {
	/**
	 * Asks the server for its providers. With none, leaves the page as it is. With some, puts one button per provider
	 * above the credentials, folds the credentials behind the password link, and shows a refusal's message.
	 */
	public static void install(VLayout inputsPanel, VLayout credentialsPanel);
}
```

- [ ] **Step 1: The hook**

In `LoginPanel.initGUI`, straight after `inputsPanel.setMembers(logoLogin, spacer15, credentialsPanel, secretKeyPanel, licensing);`:

```java
        // Healdan: the sign-in providers' buttons, with the password form behind a link (M4 spec section 5.1)
        HealdanSignInButtons.install(inputsPanel, credentialsPanel);
```

- [ ] **Step 2: Write `HealdanSignInButtons`**

- A `RequestBuilder` `GET` to `GWT.getHostPageBaseURL() + "api/v1/server"`. On failure, or with `providers` empty, it does nothing, so the page stays the password form.
- **With providers:**
  - one `IButton` per provider, its label the provider's, at `FORM_WIDTH`, opening `api/signin/<id>` in the same window (`Window.Location.assign`);
  - the credentials panel hidden, with an `HTMLFlow` link labelled `passwordLabel` that shows it and hides itself;
  - when the page carries `?signin=<code>` (`Window.Location.getParameter`), a message above the buttons from the Global Constraints' table, with `{label}` "your sign-in provider", since the page doesn't know which; an unknown code shows nothing.
- The labels come from the server and are set as text, never as HTML.

- [ ] **Step 3: Build the GUI, and check it on a stack**

Run: `mvn -B -ntp -pl logicaldoc-gui -DskipTests install` (the GWT compile), then a stack with `-f docker/tests/compose.smoke.yaml -f docker/tests/compose.signin.yaml`.
Expected:
- the page shows Sign In and the password link;
- the link shows the form, and `admin` signs in with it;
- the button signs `sso-user` in;
- `?signin=no-group` shows its message;
- Sign Out comes back to the buttons;
- with the provider turned off, the page is the plain form.

Take the docs' screenshots on the demo stack with a provider set up.

- [ ] **Step 4: Docs, then commit:** "Show sign-in providers' buttons on the sign-in page"

---

### Task 13: The Sign-In tab

**Files:**
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/services/HealdanSettingsService.java`, `HealdanSettingsServiceAsync.java`
- Create: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/beans/GUISignInProvider.java`, `GUISignInOptions.java`, `GUILinkedAccount.java`, `GUIGroupMapping.java`
- Create: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/settings/SignInTab.java`, `ProviderDialog.java`
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/settings/HealdanSettingsPanel.java` (`tabs.addTab(new SignInTab())`)
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/web/HealdanSettingsServiceImpl.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/web/HealdanSettingsSignInTest.java`
- Docs:
  - new `site/content/install/sign-in/_index.md`, `entra-id.md`, `authentik.md`, `other-providers.md` (spec §15.3's contents), and a card in `site/content/install/_index.md`;
  - `site/content/admin/healdan-settings/index.md`: the Sign-In tab, the group map and gate, linking by email, readying an account before a first sign-in, linked accounts with Unlink and Unlink Everyone, turning a provider off, the emergency `admin` and its alert, and the re-check.

**Interfaces:**
- Produces, on `HealdanSettingsService` (all admin-only, as `load` and `save` are):

```java
GUISignInOptions loadSignIn() throws ServerException;                 // providers, password label, alert, recipients, outgoing email set up
GUISignInProvider saveProvider(GUISignInProvider p, String secret) throws ServerException; // secret null keeps it
void removeProvider(String id) throws ServerException;
void reorderProviders(String[] ids) throws ServerException;
String[] checkProvider(String id) throws ServerException;             // problems, or empty; the redirect address first
void saveSignInOptions(String passwordLabel, boolean adminAlert, String[] recipients) throws ServerException;
GUILinkedAccount[] linkedAccounts(String id) throws ServerException;
String unlink(long userId) throws ServerException;                    // the temporary password when not emailed
String[] unlinkEveryone(String id) throws ServerException;            // "username: password" for those not emailed
int countLinked(String id) throws ServerException;
```

`GUISignInProvider` carries everything in `SignInProvider` but the secret, plus `hasSecret`, `redirect` and the group map (`GUIGroupMapping[]`: value, group id, group name, `deleted`).

- [ ] **Step 1: Write the failing server tests**

`HealdanSettingsSignInTest extends AbstractHealdanTestCase`, calling `HealdanSettingsServiceImpl` with a session (as its existing tests do):
- `testAdminsOnly`: `author` gets a `ServerException` from every method.
- `testSecretNeverComesBack`: after `saveProvider(p, "s1")`, no field of `loadSignIn()` or any answer, serialised, contains `s1`; `hasSecret` is true.
- `testSaveAndLoad`, `testRemoveRefusedWhileLinked`, `testUnlinkAndCount`, `testOptions`, and `testCheckGivesTheRedirectFirst` (against `FakeProvider`): each through the RPC, as Tasks 3 and 10 test the services.

- [ ] **Step 2: Run them to see them fail, then write the RPCs**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=HealdanSettingsSignInTest`
Expected: FAIL, then PASS once the methods delegate to `SignInProviders`, `ProviderClient.check` (with `ProviderClient.forget` after each save) and `SignInAdmin`.

- [ ] **Step 3: Write the tab**

- **The tab, `SignInTab`:**
  - a `ListGrid` of providers (label, id, preset, on or off, linked accounts), with Add (asking for an id and preset), Edit, Remove, Move Up and Move Down;
  - below it, the password link's label, the `admin` alert's switch and recipients, with "Needs outgoing email, which isn't set up" when `outgoingEmail` is false, and Save.
- **The provider dialog, `ProviderDialog`:**
  - the label, issuer, client id, and a secret field (a `PasswordItem`, empty, with the hint "set" or "not set");
  - On;
  - Claims, a collapsed section;
  - Link by Email;
  - the group map as an editable grid, with a group picker;
  - Check, which shows `checkProvider`'s lines, with the redirect address copyable;
  - Linked Accounts, a grid with Unlink on each row and Unlink Everyone, each confirmed with the count. A temporary password is shown once, in a dialog that says to pass it on.
- **Turning a provider off,** or Remove, first shows `countLinked` with the spec's §7.7 wording.

- [ ] **Step 4: Build the GUI, and check the tab on a stack**

Run: `mvn -B -ntp -pl logicaldoc-gui -DskipTests install`, then on a smoke stack with Keycloak:
- add a provider for the Keycloak realm, and Check passes;
- map a group;
- sign in through it on the sign-in page;
- see the account under Linked Accounts, and unlink it;
- sign in with the temporary password and change it.

- [ ] **Step 5: Docs, then commit:** "Set sign-in providers up in Healdan Settings' Sign-In tab"

---

### Task 14: The helper's browser sign-in

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Session/Pkce.cs`, `LoopbackReceiver.cs`, `IBrowser.cs`, `SystemBrowser.cs`
- Modify: `clients/helper/Healdan.Helper.Core/Api/Models.cs` (`ServerInfo` gains `Providers` and `PasswordLabel`; `ProviderInfo`), `HealdanApi.cs` (`ExchangeCodeAsync`), `Session/SignIn.cs` (`SignInWithProviderAsync`)
- Test: `clients/helper/Healdan.Helper.Core.Tests/Session/PkceTests.cs`, `LoopbackReceiverTests.cs`, `ProviderSignInTests.cs`
- Docs: `site/content/developer/desktop-clients.md` (the helper's browser sign-in: the loopback redirect, PKCE, the exchange)

**Interfaces:**
- Produces:

```csharp
public sealed record ProviderInfo(string Id, string Label);
public sealed record ServerInfo(string Product, string Version, string ApiVersion, ServerLabels Labels,
    List<string> SignIn, List<ProviderInfo>? Providers = null, string? PasswordLabel = null);

public static class Pkce
{
    public static (string Verifier, string Challenge) Make(); // 32 random bytes base64url; SHA-256 base64url
}

public interface IBrowser { void Open(Uri address); }
public sealed class SystemBrowser : IBrowser { public void Open(Uri address) => SystemOpener.Open(address.AbsoluteUri); }

/// <summary>The provider sign-in was refused; <see cref="Code"/> is one of the server's refusal codes, such as <c>no-group</c>.</summary>
public sealed class ProviderRefusedException(string code) : Exception($"The sign-in was refused ({code}).")
{
    public string Code { get; } = code;
}

public sealed record LoopbackResult(string? Code, string? State, string? Error);
public sealed class LoopbackReceiver : IAsyncDisposable
{
    public static LoopbackReceiver Start();                 // http://127.0.0.1:<free port>/
    public Uri RedirectUri { get; }
    /// <summary>The first request to the port: its code, state or error. The browser is sent on to <paramref name="done"/>.</summary>
    public Task<LoopbackResult> WaitAsync(Uri done, TimeSpan timeout, CancellationToken ct);
}

// HealdanApi
public Task<string> ExchangeCodeAsync(string code, string verifier, Uri redirectUri, string deviceName, string deviceId,
    CancellationToken ct = default);
// SignIn
public Task SignInWithProviderAsync(string providerId, IBrowser browser, CancellationToken ct = default);
```

- [ ] **Step 1: Write the failing tests**

- `PkceTests.ChallengeIsTheVerifiersSha256`: for a fixed verifier, the challenge matches RFC 7636's appendix B example (`dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` → `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`). Two `Make()`s differ.
- `LoopbackReceiverTests`:
  - `RedirectIsLoopback`: the address is `http://127.0.0.1:<port>/`.
  - `ReceivesTheCode`: a GET with `?code=c&state=s` gives them, and answers `302` to `done`.
  - `ReceivesAnError`: `?error=no-group&state=s` gives the error.
  - `TimesOut`: after the timeout, `TimeoutException`.
  - `OneRequestOnly`: a second request after the first is refused (the listener has stopped).
  - `DisposeFreesThePort`.
- `ProviderSignInTests`, with `SessionServer` (the existing fake API) taught `GET /api/signin/…` and the code exchange, and a fake `IBrowser` that, when opened, checks the address's `client=helper`, `redirect_uri` (the receiver's), `state` and `code_challenge`, then makes the receiver's GET with a code:
  - `SignsInAndKeepsTheToken`: the keychain holds the token with this computer's device id; the exchange carried the verifier, the redirect and the device.
  - `StateMismatchRefused`: a fake browser that sends another state: `InvalidOperationException` ("The sign-in came back for another request. Try again."), and no exchange is made.
  - `RefusalReported`: `error=no-group` gives `ProviderRefusedException` with the code, and no exchange.
  - `BrowserNeverComesBack`: after the timeout, an `OperationCanceledException` with the message "The sign-in in your browser didn't finish within 5 minutes."
  - `NoTokenInAnyLog`: `HelperLog` never holds the code, verifier or token.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~Pkce|FullyQualifiedName~LoopbackReceiver|FullyQualifiedName~ProviderSignIn"`
Expected: FAIL to compile.

- [ ] **Step 3: Write them**

- **`LoopbackReceiver`** binds an `HttpListener` on `http://127.0.0.1:<port>/`. It first finds a free port by binding a `TcpListener` to port 0, reading it and closing it, and tries three times if `HttpListener` loses the race. It takes one request, answers `302 Location: done`, and stops.
- **`SignInWithProviderAsync`:**
  1. `ServerAsync` checks the server is Healdan, as `SignInAsync` does.
  2. `Pkce.Make`, a random state, `LoopbackReceiver.Start`.
  3. `browser.Open(server/api/signin/<id>?client=helper&redirect_uri=…&state=…&code_challenge=…&code_challenge_method=S256)`.
  4. `WaitAsync(server/api/signin/done, 5 minutes)`; the state must match; an error is a `ProviderRefusedException`.
  5. `ExchangeCodeAsync`, then keep the token as `SignInAsync` does (`KeptToken`).

- [ ] **Step 4: Run the tests, on macOS and Linux**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 5: Docs, then commit:** "Sign the helper in through a provider in the system browser"

---

### Task 15: The helper's sign-in window, and the stack test

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Ui/SignInPrompt.cs` (adds `ProvidersAsync` and `SignInWithProviderAsync`)
- Modify: `clients/helper/Healdan.Helper.Core/Session/HelperSession.cs` (`TrySignInWithProviderAsync`, beside `TrySignInAsync`)
- Modify: `clients/helper/Healdan.Helper/Windows/SignInWindow.axaml`, `SignInWindow.axaml.cs`
- Create: `clients/helper/Healdan.Helper.Core.Tests/Session/StackProviderSignInTests.cs` (`[Trait("Category", "Stack")]`)
- Modify: `docker/smoke-test.sh` (step 5d passes `HEALDAN_STACK_SIGNIN_PROVIDER=smoke`, `HEALDAN_STACK_SIGNIN_USER=sso-helper2`, `HEALDAN_STACK_SIGNIN_PASSWORD` and `HEALDAN_STACK_KEYCLOAK=127.0.0.1:18080`), `docker/tests/keycloak/healdan-realm.json` (user `sso-helper2`, in `healdan-users`)
- Test: `clients/helper/Healdan.Helper.Tests/Windows/SignInWindowTests.cs`
- Docs: `site/content/user/office/installing-the-helper/` (signing in through the browser: the button, the browser tab, "you can close this tab", and the password link for local accounts)

**Interfaces:**
- Produces:

```csharp
public sealed record SignInPrompt(string? Server, bool ServerFixed,
    Func<string, string, string, Task<string?>> SignInAsync,
    Func<string, Task<ProviderChoice?>> ProvidersAsync,              // the server's providers and password label, or null when unreachable
    Func<string, string, Task<string?>> SignInWithProviderAsync);    // address, provider id; null when signed in, else the reason
public sealed record ProviderChoice(IReadOnlyList<ProviderInfo> Providers, string PasswordLabel);
```

- [ ] **Step 1: Write the failing tests**

`SignInWindowTests` (headless Avalonia, as `SetUpOfficeWindowTests`):
- `NoProvidersShowsThePasswordForm`.
- `ProvidersShowButtonsAndFoldThePassword`: two providers give two buttons with their labels, the password fields hidden behind the link with the server's label, and clicking the link shows them.
- `ButtonSignsIn`: clicking a button calls `SignInWithProviderAsync` with the address and the id, shows "Finish signing in in your browser…", and closes on null.
- `RefusalShowsTheReason`: a reason string appears on the error line.
- `AddressChangeReloadsProviders`: editing the address and leaving the field asks `ProvidersAsync` again.

`StackProviderSignInTests` (skips without `HEALDAN_STACK_URL` and `HEALDAN_STACK_SIGNIN_PROVIDER`):
- `SignsInThroughTheStandIn`: `SignInWithProviderAsync` with a fake browser that follows the redirects with an `HttpClient`. Its `SocketsHttpHandler.ConnectCallback` maps `keycloak:8080` to `HEALDAN_STACK_KEYCLOAK`. It posts Keycloak's login form as `sso-helper2`, and makes the loopback request. `/me` then names `sso-helper2`.

- [ ] **Step 2: Run them to see them fail, then write the window and the session's method**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~SignInWindow"`
Expected: FAIL, then PASS.

- `TrySignInWithProviderAsync` mirrors `TrySignInAsync`: the same address checks, copies set aside and settings update, and the same messages for unreachable servers. A `ProviderRefusedException` maps to the Global Constraints' message with the provider's label.
- The window keeps the password form's behaviour as it is.

- [ ] **Step 3: The stack test against the smoke stack**

Run: `SMOKE_HELPER_TEST=1 docker/smoke-test.sh`
Expected: step 5d shows `Passed: 2` (the cycle and the provider sign-in), `Skipped: 0`.

- [ ] **Step 4: Docs, then commit:** "Offer the server's sign-in providers in the helper's Sign In window"

---

### Task 16: Records

**Files:**
- Modify: `CLAUDE.md`:
  - "Healdan's code" gains the sign-in package: providers in `hd_setting`, links in `hd_signin_identity`, `SecretBox`'s key, `UserSource.OIDC`, the listener's `AuthenticationException` veto, `/api/signin/`;
  - "Docker" gains `compose.signin.yaml` and step 5e;
  - "Upstream edits" gains M4's spec §15.1;
  - "Build and test" gains Keycloak in the smoke stack.
- Modify: `docs/reference/logicaldoc-ce-capability-map.md` §5.4 (OIDC sign-in: present, Healdan's; `UserSource.OIDC`; user listeners can veto only with an `AuthenticationException`)
- Modify: `docs/superpowers/specs/2026-11-01-m4-oidc-sign-in-design.md` (each correction above, and anything the build changed, marked "changed in the build"; Status)
- Modify: `docs/ROADMAP.md` (M4's row, status line and section: built, with the PRs)

- [ ] **Step 1: Write them, run the docs checks, and commit:** "Record M4's build"

Run: `python3 -m unittest discover -s .github/scripts/tests && python3 .github/scripts/check-logicaldoc-doc-links.py && python3 .github/scripts/check-logicaldoc-hosts.py`
Expected: PASS.

---

### Task 17: Healdan Helper 0.5.0 and the owner's acceptance

- [ ] **Step 1:** Once PRs A to E are merged, tag `helper-v0.5.0`. `clients.yml` signs, notarises and attaches the `.dmg`.
- [ ] **Step 2:** The owner upgrades their server, and keeps the `conf` volume's new key in its backups.
- [ ] **Step 3:** The owner's walkthrough, spec §16.2, rows 1 to 8, on Entra ID and the owner's Authentik. Record each row's result and any punch-list item in the spec and this plan's decision log.

## Decision log

(Empty until the build. Each ruling made while building goes here with its task, and into the spec where it changes what the spec says.)

## Follow-ups

(Empty until the build.)
