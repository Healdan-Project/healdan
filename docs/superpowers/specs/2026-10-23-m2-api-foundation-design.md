# M2 "API foundation": design

- **Milestone:** M2 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-10-23
- **Status:** approved by the owner on 2026-10-23, with one amendment. Implementation plan: [`docs/superpowers/plans/2026-10-23-m2-api-foundation.md`](../plans/2026-10-23-m2-api-foundation.md), approved the same day; its "Spec corrections" section lists where the build departs from this spec. The amendment was: admin-only lists of the Stores and a Store's matters (§6.2). The owner asked for the technical calls to be made in the draft and settled by iterating on it. A builder check by a reviewer who hadn't seen the conversation found six statements the code contradicts, all now corrected (§3.1), two endpoints M3 would have missed (§6.4), and one question the owner answered: sign-in throttling (§5.7).
- **Inputs:**
  - the roadmap's M2 section, and its M3, M5 and M6 sections for what the API has to carry;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md) §4 and §5.4;
  - a design conversation with the owner on 2026-10-23;
  - an inventory of every server call the old UI makes, now [`docs/reference/healdan-api-inventory.md`](../../reference/healdan-api-inventory.md) (§12);
  - a survey of this repository at `808d5395b`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks Claude's proposal. The owner confirmed the scope in §1 and §2 when it was played back, and handed the technical choices to Claude, to be reviewed in this document. The owner approved the spec, calls included, on 2026-10-23. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace* and *template* mean something different in Healdan and in LogicalDOC (see the roadmap glossary). This spec says "LogicalDOC workspace", "CE session" and so on whenever it means LogicalDOC's sense. The API itself uses only Healdan's words (§4.3).

## 1. Intent

A JSON API that Healdan's own new clients are built on: the Office add-ins first (M3), then the Outlook add-in (M5) and the new web UI (M6).

**M2 is plumbing (owner).** Nobody uses it on its own, and no Healdan instance is in production yet, so the API has no outside consumers. The day M2 is finished, work on M3 begins. M2 is done when the add-in work can start without having to stop and ask the server for anything.

**So M2 builds two things:**

- **What the add-ins need** (the roadmap's first slice): sign-in without storing a password, finding a matter or folder, the properties form, filing a new document, opening one, saving snapshots and versions, and check-out and check-in.
- **The rules every later endpoint follows:** addresses, errors, paging, sign-in, CORS, the description file, and the same server-side rules the old UI gets. M5 and M6 add endpoints under those rules instead of inventing their own.

Everything else the old UI can do is listed and tagged with the milestone that first needs it (§12), but not built.

## 2. Goals, non-goals, anti-goals

### Goals (M2 is done when)

On the Docker stack with the smoke fixture (`compose.smoke.yaml`), signed in through the API as an ordinary non-admin user:

1. **Sign-in.** A client exchanges a username and password for a token, and from then on sends only the token. Signing out, revoking the token or changing the password makes it stop working (§5).
2. **Finding a place to file.** The client finds matter 1.1 by searching for `1.1` and by the matter's name, sees it in Recent Matters after filing into it, and lists its folders (§6.2, §6.3).
3. **Filing.** The client asks for the properties form of the matter's Documents folder, sees which fields are required and which values are inherited, and files a `.docx` with them. The new document gets a doc #, the inherited properties and a Date filed, exactly as an upload in the old UI does (§6.5, §6.7).
4. **Editing.** The client checks the document out, saves two snapshots and then a new version while keeping it checked out, and checks it in. While it's checked out, it's in the user's checked-out list, and another user sees that it's checked out and who has it, and can't save over it (§6.4, §6.6).
5. **Lookup.** `#n`, `#n.2` and the legacy doc # each find the document, and it's in the user's recent documents. Its content downloads byte for byte as saved, for the current version and an older one (§6.3, §6.4).
6. **Rules hold.** Every rule the old UI enforces holds through the API: the filing rule, permissions, the doc # rules, inheritance and overrides, and Healdan's vetoes. A document the user can't read behaves as if it doesn't exist (§7).
7. **Described.** `openapi.yaml` describes every endpoint. The server is tested against it, and a docs page on the server shows it (§4.6).
8. **In CI.** The automated tests in §14.1 run in CI, including the whole flow above against the Docker stack on amd64 and arm64.
9. **Inventory.** The inventory of the old UI's server calls is committed, with every entry tagged and the M2 entries mapped to their endpoints (§12).

### Non-goals

- **The add-ins.** M3 and M5 build them. M2 builds no client beyond its tests.
- **Fixing WebDAV locking.** If M3's trial build picks WebDAV for moving files, M3 fixes it (owner: M3's trial build doesn't have to come first).
- **OIDC sign-in.** That's M4. §5.6 says how it fits the token design.
- **Push updates.** §9 records the direction for M6.
- **Endpoints for M5, M6, M7 or the Admin app.** They're tagged in the inventory and built when their milestone needs them.
- **Compatibility promises (owner).** Until the initial roadmap is complete, any endpoint can be renamed or reshaped when a later milestone needs it. The `/api/v1` prefix is kept anyway, because it costs nothing.
- **Changing the existing APIs.** SOAP, `/services/rest`, WebDAV and CMIS stay as they are (roadmap).
- **Browsing a Store in the user apps.** The new clients start from search, My Matters or Recent Matters, never from a list of every matter in a Store (owner, 2026-10-23, for M6; the add-in's save dialog works the same way per the roadmap's M3 flow). The API still lists a Store's matters, for admins only, because the admin side may need it (owner, §6.2).
- **TLS in the application.** HTTPS comes from the reverse proxy the Docker guide already documents (§8).

### Anti-goals

- **A route around a rule.** An endpoint that lets a client do something the old UI refuses, or skips a check the old UI makes, is a failure even if it works. The API calls the same CE managers and DAOs, so Healdan's listeners and `HealdanHooks` run, and it makes the same permission checks the RPCs make (§7).
- **A client that stores a password.** Clients keep a revocable token, never the password (roadmap).
- **LogicalDOC's quirks in the API.** No CE session IDs, no `customId`, no "workspace" meaning a Store, no uploads parked in a session by file name. A client developer shouldn't need to know LogicalDOC.
- **A second copy of Healdan's rules.** The API reads and writes through `healdan-core`'s existing services (matter lookup, Recent Matters, inheritance). Where a read model is missing, it's added to `healdan-core` for both the old UI and the API, not written into the API layer.

## 3. Constraints

### 3.1 Facts from the code that shape the design

- **Sign-in.** Every channel signs in through `SessionManager.newSession`, which runs the `Authentication` extension chain (`AuthenticationChain`). The chain refuses disabled and expired accounts and expired passwords. Authenticators are tried in `position` order until one returns a user. CE declares `DefaultAuthenticator` (100) and `ApiKeyAuthenticator` (110). `ApiKeyAuthenticator` only claims keys that start with `ld-`.
- **The login throttle never runs.** `LoginThrottle.checkLoginThrottle` exists but nothing calls it, and the shipped `context.properties` sets `throttle.enabled = false`, which also stops failures being counted. So no sign-in route has brute-force protection today. §5.7 deals with it.
- **CE sessions.** A CE session (`ld_session`) is what CE code expects behind every action: history rows carry its ID, and `SessionManager.getCurrentSid()` reads it from Spring Security's `SecurityContextHolder` (an `LDAuthenticationToken`). A session stores only the SHA-256 of its key, plus a label of its first 10 and last 4 characters. A session closes after `session.timeout` idle minutes (30 as shipped), or on restart.
- **CE's API keys.** An `X-API-KEY` request reuses the open CE session whose key hash matches (`SessionManager.getSessionFromApiKey`), and opens a new one through the chain when there's none. But a key never expires, isn't revoked when the password changes, and sits in the same list as keys the user made for scripts. That's why M2 adds device tokens (§5) rather than reusing API keys.
- **Users.** Saving a user (`UserDAO.store`, which a password change goes through) fires `UserListener`s. Deleting one (`HibernateUserDAO.delete`) fires none.
- **Spring Security.** `context-security.xml` (upstream) has chains for `/setup`, `/sessions`, `/webdav`, `/service/cmis` and `/services/rest`, and a catch-all chain for everything else. `/api/**` would fall into the catch-all, which runs `SessionFilter` (which signs a request in from the `ldoc-sid` cookie), `AnonymousLoginFilter` and form login.
- **Uploads in the old UI are stateful.** `/upload` keeps files in the HTTP session keyed by file name, and the RPCs pick them up from there. Two files with the same name collide.
- **Check-in releases the check-out.** `DocumentManager.checkin(docId, content, filename, release, docVO, transaction)` stores a new version (`release` makes it the next major) and leaves the document checked in. CE has no "save and stay checked out".
- **Permissions.** CE's managers and DAOs don't check permissions; the RPC and SOAP layers do. M1 closed many gaps there (capability map §6). The API has to make its own checks, the same way. M1's RPC checks live in `HealdanRpcChecks`, in `logicaldoc-webapp`, which builds only the WAR, so `healdan-api` can't call them. Core's `HealdanTreeChecks` can be called.
- **Holds on a document.** A document is unlocked, checked out (`CHECKEDOUT`, by check-out) or locked (`LOCKED`, by the old UI's Lock action). Either hold stops anyone else saving, and a locked document can't be checked out.
- **What doesn't exist yet for the add-in slice.** No call tells a client which properties are inherited, or from where (`profile.Inheritance` is server-side only). Nothing tracks recent folders or recent documents, only recent matters. Healdan's matter RPCs for Stores and templates are admin-only.
- **The live-update channel.** `/wk-event` sends GWT-serialized messages, which only GWT can read. The shipped `context.properties` turns it on (`default.gui.serverpush=true`). When this spec was written it sent them to every connected user without checking permissions; [Healdan-Project/healdan#58](https://github.com/Healdan-Project/healdan/pull/58) fixed that the same day (§9).
- **Libraries in the WAR.** CXF 4.0 with JAX-RS, the Jackson JAX-RS provider, Jackson 2.20 and the swagger 2.2 annotations are already shipped for `/services/rest`. Spring MVC isn't.
- **Upload size.** The old UI's limit is `<tenant>.upload.maxsize`, in MB: 100 as shipped, 10 if the setting is missing.
- **Code location.** M1 settled it: Healdan's server code lives under `com.logicaldoc.healdan`, which CE's Spring and Hibernate scans already cover (M1 spec §10.2). Healdan's servlets register with `@WebServlet` (M1 spec §10.3).

### 3.2 Other constraints

- **Network.** The server is reachable only on the LAN or over a VPN (roadmap). HTTPS comes from a reverse proxy (`docker/README.md`, "Behind Nginx Proxy Manager").
- **Office add-ins need HTTPS.** Office.js add-ins load their pages over HTTPS, and a page loaded over HTTPS can't call an HTTP API. So M3's Word add-in needs the server behind the HTTPS proxy, with a certificate the Mac trusts. The LibreOffice extension doesn't. M2 doesn't change this; it's recorded here so M3 isn't surprised.
- **One tenant.** Healdan uses only LogicalDOC's default tenant (roadmap glossary). The API has no tenant concept.

## 4. Shape of the API (calls)

### 4.1 Base path and stability

- Everything is under **`/api/v1`**, served by the same server as the rest of Healdan (roadmap).
- **No compatibility promises until the initial roadmap is complete (owner).** Endpoints change when a later milestone needs them to. The clients are Healdan's own and change in the same pull request.
- Once the roadmap is complete, the API gets a compatibility policy. That's for a later design session.

### 4.2 Resources and identifiers

| Resource | Path | Identified by |
|---|---|---|
| Document | `/documents/{docNumber}` | its **doc #** |
| Folder | `/folders/{id}` | CE's folder ID |
| Store | `/stores/{id}` | its folder's ID (a Store is a LogicalDOC workspace folder) |
| Matter | `/matters/{id}` | Healdan's matter ID |
| Token | `/auth/tokens/{id}` | its row ID |
| User | `/users` | CE's user ID |

**Documents are addressed by doc # (call).** The doc # is the handle people already use (roadmap principle 1). It's unique across the system, never changes and is never reused (M1 spec §6.1–§6.3), so it's a safe permanent ID. `/api/v1/documents/1234` is document #1234, and a client never sees CE's internal document ID. Inside Stores, every document has a doc # and shortcuts are refused (M1 spec §6.3), so a doc # names exactly one document.

**Versions use Healdan's numbering** (M1 spec §6.4): `2.0` is version 2, `2.1` is a snapshot on its timeline.

### 4.3 JSON conventions (call)

- **Healdan's words.** Fields say `store`, `matter`, `client`, `docNumber`, `legacyDocNumber`, `dateFiled`, `snapshot`, never `workspace`, `customId` or `creation`. The configurable matter label ("Project") is for display only. The API always says `matter`, and `GET /server` returns the labels.
- camelCase field names. Timestamps are ISO 8601 in UTC (`2026-10-23T14:05:00Z`). IDs are numbers. Doc #s are numbers.
- A field with no value is `null`, not missing.
- A matter always carries its display string (`998.1 – Healdan Demo`) and its parts (`client.number`, `number`, `name`), so clients never build the display themselves.

### 4.4 Errors (call)

Errors use **RFC 9457 problem details** (`application/problem+json`):

```json
{
  "type": "https://healdan.org/problems/checked-out",
  "title": "Checked out",
  "status": 409,
  "detail": "Document #1234 is checked out to Jane Smith.",
  "code": "checked-out"
}
```

- `code` is a short stable string that clients switch on. `detail` is an English sentence a client can show as it is.
- The `type` URI doesn't have to resolve.
- Validation failures (422) add `fields`: a list of `{ "field": "docclass", "code": "required", "detail": "Class is required." }`.

| HTTP | `code` | When |
|---|---|---|
| 400 | `bad-request` | malformed input |
| 401 | `unauthenticated` | no token, or a token that's unknown, expired or revoked |
| 401 | `sign-in-failed` | wrong username or password, or a disabled account; the detail never says which |
| 401 | `password-expired` | the password must be changed in the web UI first |
| 403 | `forbidden` | the user can read the object but not do this to it |
| 404 | `not-found` | doesn't exist, **or the user can't read it** (§7) |
| 409 | `checked-out` | checked out to someone else; the problem includes the `hold` (§6.4) |
| 409 | `locked` | locked with the old UI's Lock action; the problem includes the `hold` |
| 409 | `filing-rule` | the folder can't hold documents: a matter's top level, a search folder, outside a matter |
| 409 | `refused` | any other Healdan veto (`HealdanVetoException`), with its message as `detail` |
| 413 | `too-large` | over the upload limit |
| 422 | `validation` | with `fields` |
| 429 | `too-many-attempts` | the sign-in throttle blocked the sign-in (§5.7) |
| 500 | `server-error` | anything else; the detail never includes a stack trace |

### 4.5 Paging (call)

Lists take `offset` (default 0) and `limit` (default 50, at most 200), and return:

```json
{ "items": [ ... ], "offset": 0, "limit": 50, "total": 123 }
```

`total` is `null` where counting would be expensive, such as full-text search. A short list with a fixed cap, such as Recent Matters, returns the same shape without paging parameters.

### 4.6 OpenAPI first (roadmap call, with calls on the mechanics)

- **The description file is the source of truth.** `healdan-api/src/main/resources/healdan/api/openapi.yaml` (OpenAPI 3.1) is written by hand, before or with the code. The JAX-RS resources implement it. (call: design-first, as the roadmap asks; generating the file from annotations would let the code's shape lead.)
- **Served.** `GET /api/v1/openapi.yaml` returns it. M3 and M6 generate their TypeScript types from it.
- **Docs page.** `GET /api/docs` shows it with Swagger UI, including "Try it out" with a token. (owner, in the playback: a browsable docs page.) The builder chooses how to package Swagger UI; it must not load anything from the internet, because the server may have no internet access.
- **Kept honest by tests** (§14.1):
  - every response a test receives is validated against the file;
  - a test fails if the file describes an operation the server doesn't implement, or the server answers an `/api/v1` route the file doesn't describe;
  - the file must parse with no errors.

## 5. Sign-in and tokens (call)

### 5.1 Device tokens

**A client signs in once per device and keeps a token.** Each token is one sign-in: Word on the MacBook, LibreOffice on the Linux desktop. The user can see and revoke each one.

- **Format:** `hd_` followed by 43 characters, the base64url form of 32 random bytes. The prefix lets Healdan's authenticator recognise its own tokens, as CE recognises its `ld-` API keys, and lets secret scanners spot a leaked one.
- **Storage:** only the SHA-256 of the token is stored (`hd_api_token`, §11). The token itself is shown once, in the sign-in response.
- **Sent as** `Authorization: Bearer hd_…` on every request. The API accepts no other way to authenticate: no cookies, no `sid`, no Basic, no `X-API-KEY`. That rules out cross-site request forgery, because a browser never attaches a bearer token by itself.
- **Elsewhere.** A token signs in through CE's chain as a key (§5.3), so CE's own routes that take an `X-API-KEY` (`/services/rest`, `/download` and the other servlets behind `SessionFilter`) accept it too. That gives the token no more than the password it stands for. Revoking it closes its CE session, so it stops working there as well.

### 5.2 Endpoints

| Method and path | Auth | What it does |
|---|---|---|
| `POST /auth/tokens` | none | `{ "username", "password", "name" }` → `201 { "token", "id", "name", "expiresAt", "user" }`. `name` labels the device, e.g. "Word on MacBook". |
| `GET /auth/tokens` | token | The user's own tokens: `id`, `name`, `created`, `lastUsed`, `expiresAt`, `current` (true for the one making the request). Never the token itself. |
| `DELETE /auth/tokens/current` | token | Sign out: revokes the token making the request. |
| `DELETE /auth/tokens/{id}` | token | Revokes one of the user's own tokens. |

### 5.3 How a token signs a request in

1. **Signing in** calls `SessionManager.newSession(username, password, client)`. The whole CE chain applies: disabled and expired accounts, password expiry, and later LDAP or anything else added to the chain. §5.7 adds the throttle. On success, Healdan creates the token and binds it to the CE session that sign-in created.
2. **Each request:**
   - The API's filter hashes the bearer token and finds its row. An unknown, expired or revoked token gets `401 unauthenticated`, and so does a token whose user has since been deleted or disabled. The filter checks the user on every request, because deleting a user fires no listener (§3.1).
   - If the token's CE session is still open, the filter renews it. Otherwise it opens a new one with `SessionManager.newSession` and the token as the key, which runs the chain again, so a user disabled since sign-in is refused here. Healdan declares a new `Authentication` extension for this, `HealdanTokenAuthenticator`, which claims only keys starting with `hd_`.
   - For the length of the request, the filter puts an `LDAuthenticationToken` with that session's ID in `SecurityContextHolder`, so CE code that asks for the current session finds it, and clears it afterwards.
3. **Result:** the API makes about one CE session per device per working session. History, audit and "who's online" show API activity as ordinary sessions with the client's address.

### 5.4 Expiry and revocation

- **Idle expiry:** a token unused for **30 days** stops working. (call)
- **Absolute expiry:** every token stops working **365 days** after sign-in. (call) The add-in then asks for the password again.
- **Revoked automatically** when the user changes their password or is disabled (a Healdan `UserListener`). A deleted user's tokens stop working at once through the per-request check (§5.3). Changing your password is therefore the simple way to sign out a lost laptop until M6 has a "Signed-in devices" screen.
- Revoking a token also closes its CE session.
- `lastUsed` is written at most once every few minutes per token, not on every request. The builder chooses the interval.

### 5.5 Settings

New Healdan settings in `hd_setting`, editable in the existing Healdan Settings dialog (admins):

| Setting | Default | Meaning |
|---|---|---|
| `api.token.idledays` | 30 | §5.4 |
| `api.token.maxdays` | 365 | §5.4 |
| `api.cors.origins` | empty | §8 |

### 5.6 What M4 changes later

OIDC sign-in (M4) ends the same way: the provider's pop-up signs the user in, then Healdan issues the same device token. Nothing else in the API changes, and `GET /server` lists which sign-in methods are on (`["password"]` in M2).

### 5.7 Sign-in throttling (owner)

`POST /auth/tokens` is the add-ins' password entry point, and today no sign-in route is throttled (§3.1). The owner decided on 2026-10-23 to throttle **every** route, not just the API's.

**Limits (owner):**
- **5** wrong passwords for one username block that username for **15 minutes**.
- **20** failed sign-ins from one network address block that address for **15 minutes**.
- The account is **never disabled** (`throttle.username.disableuser = false`). A block simply runs out.

**How (calls):**
- **One hook for every route.** Every sign-in ends in `SessionManager.newSession`: the old UI's form login, WebDAV, CMIS and REST through `LDAuthenticationProvider`, SOAP's `SoapAuthService`, `X-API-KEY` through `SessionFilter`, and the API. A small upstream edit calls CE's existing `LoginThrottle.checkLoginThrottle(username, key, address)` at the start of the two `newSession` methods the others delegate to, and `LoginThrottle.clearFailures(username, address)` when a sign-in succeeds. Today only the form login's `LDAuthenticationProvider` clears them, so a success through SOAP or the API would leave old failures counting. Clearing on success is what makes the limit "5 in a row".
- **A blocked attempt isn't another failure.** The check runs before CE's failure recording, so an add-in retrying during a block doesn't push the block further out.
- Nothing else in the throttle changes: CE already counts failures, blocks, and emails `throttle.alert.recipients` (admin) about a suspected attack.
- **Turned on by a seed step.** CE reads the throttle from `context.properties`, which ships `throttle.enabled = false`, and DockerInit never overwrites an existing value. So a new Healdan seed step writes `throttle.enabled = true`, `throttle.username.max = 5`, `throttle.username.wait = 15`, `throttle.ip.max = 20`, `throttle.ip.wait = 15` and `throttle.username.disableuser = false` into the install's `context.properties`, as CE's own settings screen does. It runs once per install, fresh or upgraded, so an admin can change the values afterwards and they stay.
- **API keys and tokens.** CE's API-key limit (`throttle.apikey.*`) stays as shipped. The API's filter refuses an unknown or revoked device token itself, before the sign-in chain, so a stale token in an add-in never counts against the user's address.
- **The real address.** Behind the reverse proxy, Tomcat's `RemoteIpValve` (`docker/server.xml`) already gives the client's own address, so users don't share the proxy's. CE also never blocks `admin` signing in from the server itself (`127.0.0.1` or `::1`). That's the way back in if the admin account is ever blocked.
- **In the API,** a blocked sign-in gets `429 too-many-attempts` (§4.4), and the detail says when to try again.

## 6. Endpoints in M2 (calls, within the roadmap's first slice)

All paths are under `/api/v1`. "Token" means the request needs a valid bearer token. Every endpoint checks permissions as §7 describes. The JSON shapes here are the substance; `openapi.yaml` fixes the details.

### 6.1 The server and the current user

| Method and path | Auth | Returns |
|---|---|---|
| `GET /server` | none | `{ "product": "Healdan", "version", "apiVersion": "v1", "labels": { "matter", "matters" }, "signIn": ["password"] }`. A client calls it first, to check that it's talking to Healdan and how to sign in. |
| `GET /me` | token | `{ "id", "username", "name", "email", "language", "admin" }` |
| `GET /users?q=` | token | Active users whose name or username contains `q`, at most 20: `{ "id", "username", "name" }`. For user fields such as Author and Typist. |

### 6.2 Matters and folders

| Method and path | Returns |
|---|---|
| `GET /stores` | **Admins only.** The Stores: `id`, `name`. Leaves out LogicalDOC's Default workspace and the Templates Store, as the New Matter dialog does. |
| `GET /stores/{id}/matters` | **Admins only.** Paged. The Store's matters, in the matter shape below, sorted by client number, then matter number. |
| `GET /matters/{id}` | A matter: `id`, `number`, `display`, `name`, `description`, `client { id, number, name }`, `store { id, name }`, `folderId` (its workspace). |
| `GET /matters/mine` | My Matters, as the old UI lists them (M1 spec §8.2). |
| `GET /matters/recent` | Recent Matters: matters where the user created or changed a document within `recentmatters.days`, newest first, at most 20, as the old UI lists them. |
| `GET /folders/{id}` | A folder: `id`, `name`, `kind`, `matter` (or `null`), `store { id, name }`, `path` (a breadcrumb of `{ id, name, kind }` from the Store down), `canFile`. |
| `GET /folders/{id}/children` | Paged. For a matter workspace or a folder inside one: its subfolders and its documents, folders first, each with a `type` of `folder` or `document`. `?type=folder` lists only folders, which is all a save dialog needs. For a search folder: the documents its rule finds (at most 1000, as in M1). For a Store: `409` with code `not-browsable` (§2 non-goals); an admin lists its matters with `GET /stores/{id}/matters`. |
| `GET /folders/recent` | Recent folders: the folders the user created or changed a document in within `recentmatters.days`, newest first, at most 20. |

- **`kind`** is `store`, `matter`, `folder` or `searchFolder`.
- **`canFile`** says whether the user can file a new document into the folder: it's inside a matter, it isn't the matter's top level or a search folder (M1 spec §7.1), and the user may add documents to it. A save dialog uses it to enable its Save button.
- **The Store lists are for the admin side (owner, 2026-10-23).** The user apps don't call them. In M2 only admins may: a non-admin gets `403 forbidden`. That's the rule Healdan's existing matter-admin calls follow (`HealdanMatterService.listStores` and the others check for an admin), and it's the simplest role CE has. (call) When the Admin app's design adds a delegated role for managing matters, these two follow it. The lists come from `healdan-core`: `MatterService.stores()` already exists, and the Store's matters are a new query beside it.
- **Recent folders is new (call).** It uses the same history query as Recent Matters (`navigation.RecentMatters`), without collapsing folders into their matter. It goes into `healdan-core` next to `RecentMatters`, so the old UI could use it too.

### 6.3 Search and lookup

| Method and path | Returns |
|---|---|
| `GET /search?scope=&q=` | Paged results, in the same scopes and with the same behaviour as the old UI's search box (M1 spec §8.1). |
| `GET /lookup?q=` | Resolves `#1234`, `#1234.2`, `#1234v2`, `#1234.2.1` (M1 spec §6.5) to `{ "docNumber", "version" }`, or `404` with the old UI's message ("No document #1234", "Document #1234 has no version 2.1"). |
| `GET /documents?legacyDocNumber=` | Documents whose legacy doc # equals the value exactly. Usually one; more than one is possible, because legacy numbers aren't unique. |

- **Scopes:** `documents` (default), `emails`, `all` (docs and emails), `folders`, `matters`, `clients`. A hit is `{ "type": "document" | "folder" | "matter", ... }` with the same fields as the resource it names.
- **The search box's `#`.** In the `documents` scope, `q=#1234` returns just that document, as in the old UI. The client needs no special case; `/lookup` is there for a client that already knows it has a number.
- **One code path.** `/search` runs the same Healdan `Search` implementations the old UI's search box uses (M1 spec §8.1), so the two can't drift apart.

### 6.4 Documents: reading

| Method and path | Returns |
|---|---|
| `GET /documents/{docNumber}` | A document (below). |
| `GET /documents/{docNumber}/content` | The file. `?version=2.1` returns that version's file. `Content-Disposition` carries the file name. |
| `GET /documents/{docNumber}/versions` | The timeline, newest first: `version`, `fileVersion`, `kind` (`version` or `snapshot`), `date`, `user { id, name }`, `comment`, `event`. |
| `GET /documents/recent` | Recent documents: the documents the user filed, saved, checked out, checked in or opened (downloaded) within `recentmatters.days`, newest first, at most 50. |
| `GET /me/checkouts` | The documents the user has checked out, newest first. |

- **Why these two (call).** M3's "Open from Healdan" dialog offers recent documents, and the add-ins need to know what you still have checked out, for example after Word crashed. Without them M3 would have to stop and ask the server for more, which §1 rules out.
- **Recent documents is new.** It reads the same document history as Recent Matters (`ld_history`), with the download event added, so it's a sibling of `navigation.RecentMatters` in `healdan-core`. The checked-out list is what the old UI's `/data/lockeddocs.xml` returns.

A document is:

```json
{
  "docNumber": 1234,
  "name": "Lease draft.docx",
  "size": 48213,
  "version": "2.1",
  "fileVersion": "2.1",
  "reference": "1234.2.1",
  "folder": { "id": 88, "name": "Documents" },
  "matter": { "id": 7, "display": "998.1 – Healdan Demo" },
  "dateFiled": "2026-10-23T14:05:00Z",
  "lastModified": "2026-10-23T15:12:09Z",
  "hold": { "kind": "checkout", "by": { "id": 5, "name": "Jane Smith" }, "since": "2026-10-23T15:00:00Z" },
  "canEdit": true
}
```

- `version` is CE's version, which moves on property edits, moves and renames too. `fileVersion` is the version the file content was last saved as, which is what the Versions column and the M3 footer show (M1 spec §6.4).
- `reference` is the footer form without the `#`: doc #, then `fileVersion`.
- `hold` is `null` when the document is free. Otherwise its `kind` is `checkout` or `lock` (§3.1), with who holds it and since when. M3's "others get a read-only copy and see who has it" reads it.
- `canEdit` says whether the user may check it out and save to it.

### 6.5 Filing a new document

`POST /documents` takes a multipart request with two parts:

- `document`: JSON `{ "folderId", "name", "properties": { … }, "checkOut": false }`
- `file`: the content

It returns `201` with the document, and `Location: /api/v1/documents/{docNumber}`.

- **Rules.** The folder must pass `canFile` (§6.2). Required properties must have values after inheritance. Otherwise the request fails with `filing-rule` or `validation`, and nothing is stored.
- **Properties.** A value the client sends that differs from the inherited one becomes an override; a blank or equal value inherits. This is M1's rule for every creation route (M1 spec §6.7), applied by the same listener.
- **`checkOut: true`** files the document and checks it out to the user in the same request. A client uses it when the document stays open in Word after "Save to Healdan".
- **Save as new document** (M3) is this same call: the add-in files its current file as a new document. It gets a new doc #, as a copy does in the old UI.
- **Emails.** An `.eml` or `.msg` file gets its sent and received dates, From, To, CC and Subject, and is named after its subject, as an upload in the old UI does (M1 spec §6.8). M5 builds on this.

### 6.6 Check-out, saves and check-in

| Method and path | What it does |
|---|---|
| `POST /documents/{docNumber}/checkout` | Checks the document out to the user and returns it. `409 checked-out` if someone else has it checked out, `409 locked` if it's locked. Checking out a document you already have checked out succeeds and changes nothing. |
| `DELETE /documents/{docNumber}/checkout` | Releases the hold, a check-out or a lock, without saving anything. The user who holds it can do this, and so can anyone CE lets unlock it today, admins included. |
| `POST /documents/{docNumber}/versions` | Saves new content (below). |

`POST /documents/{docNumber}/versions` takes a multipart request:

- `version`: JSON `{ "kind": "snapshot" | "version", "comment": "…", "keepCheckedOut": true }`
- `file`: the content

It returns `201` with the document.

- **`kind: "snapshot"`** is an ordinary save: 2.1 becomes 2.2. **`kind: "version"`** is "Save as new version": 2.2 becomes 3.0. (M1 spec §6.4, matching the M3 flow.)
- **`keepCheckedOut: true`** is a save while editing. **`false`** is the save on closing, which checks the document in.
- **Allowed when** nobody else holds the document. If it's free, the request checks it out, saves and, unless `keepCheckedOut` is true, checks it in again.
- **Never over someone else's hold, admins included (call).** CE lets an admin check in a document someone else has checked out. The API doesn't, so a save from Word can never overwrite another person's open edit. An admin who needs to break a hold releases it first (`DELETE …/checkout`), which leaves a record in the history.
- **How it maps to CE.** CE's check-in always releases the check-out (§3.1). With `keepCheckedOut`, the API checks the document in and out again inside one request, holding a per-document lock in the API so two API requests can't interleave. The history shows a check-in followed by a check-out. The old UI could still take the check-out in the millisecond between them; that's accepted.
- The file name may change. Its extension may not; a different extension gets `422`.

### 6.7 Properties

| Method and path | Returns |
|---|---|
| `GET /folders/{folderId}/form` | The properties form for filing a new document into that folder. |
| `GET /documents/{docNumber}/properties` | The document's properties, in the same shape. |
| `PATCH /documents/{docNumber}/properties` | `{ "values": { "docclass": "Letters", "legacydocnum": null } }` sets values. Sending a value equal to the inherited one, or `null`, makes the property inherit again (M1 spec §6.7). Returns the properties. |

Each field is:

```json
{
  "name": "docclass",
  "label": "Class",
  "type": "string",
  "required": true,
  "editable": true,
  "value": "Correspondence",
  "source": "inherited",
  "inheritedFrom": { "id": 88, "name": "Documents" },
  "options": [ { "value": "Correspondence", "label": "Correspondence" } ]
}
```

- **`type`** is the value's type: `string`, `integer`, `decimal`, `date`, `boolean`, `user`, `folder` or `document`, which covers every CE attribute type. A field with `options` is picked from that list; `options` is `null` otherwise. Admins can add attributes to the profile later (M1 spec §6.6), so a client renders any form from these fields without knowing the attributes in advance. CE's layout-only section attributes aren't fields.
- **`source`** is `inherited`, `local` (an override on a document, or a value set on a folder) or `default` (the install's default Class). `inheritedFrom` names the folder the value comes from, or is `null`.
- **`editable: false`** for Client and Matter, which can't be overridden, and for the email fields, which come from the message. Legacy doc # is editable, as in the old UI, so it can be typed in during migration.
- **Subclass options** carry the Class they belong to (`"class": "Correspondence"`), so a client narrows the list as the user picks a Class. The server rejects a Subclass that doesn't belong to the Class either way (M1 spec §6.6).
- **The read model is new.** It goes into `healdan-core` (`profile`), built on `Inheritance` and `hd_local_value`, so that the old UI could show the same information later.

## 7. Rules on every request

Roadmap principle 4: the server enforces every rule, whichever way a request comes in.

- **Same code paths.** Endpoints create, check in, check out and edit through CE's `DocumentManager`, `FolderDAO` and `DocumentDAO`, and through `healdan-core`'s services. Healdan's listeners and `HealdanHooks` therefore apply as they do for the old UI, WebDAV and SOAP: the doc #, Date filed, inheritance, the filing rule, structure rules and every veto.
- **Same permission checks.** Each endpoint makes the check the matching RPC makes after M1's fixes. `HealdanRpcChecks`, `RpcPermissionTest` and capability map §6 say which check each call makes. `healdan-api` can't call `HealdanRpcChecks` (§3.1), so the builder writes one helper in `healdan-core`, next to what core's `HealdanTreeChecks` already offers, and every endpoint uses it.
- **Unreadable means not found.** A document, folder or matter the user can't read gets `404 not-found`, exactly like one that doesn't exist (M1 spec §6.5). `403 forbidden` is only for something the user can see but can't change.
- **Search results** contain only what the user can read, because they come from the same `Search` implementations.
- **Admins** get no special treatment in the API beyond what CE already gives them, with one exception: no save over someone else's hold (§6.6).

## 8. CORS (call)

- **Off by default.** If the add-ins' pages are served from the Healdan server itself, they're on the same origin and need no CORS. M3 decides where they're served from.
- **`api.cors.origins`** (§5.5) is a comma-separated list of exact origins, such as `https://localhost:3000` for an add-in's development server. For a listed origin, the API answers preflights and adds the CORS headers: methods `GET`, `POST`, `PATCH`, `DELETE`; request headers `Authorization` and `Content-Type`; exposed headers `Location` and `Content-Disposition`; no credentials, because tokens aren't cookies; preflights cached for 10 minutes.
- **No wildcard.** `*` isn't accepted.

## 9. Push updates (call, for M6 to confirm)

- **M2 has none.** The add-ins don't need push: they read a document's state when they open it, and the server refuses a save that conflicts.
- **Direction for M6:** Server-Sent Events at `/api/v1/events`, carrying JSON, with each event checked against the recipient's permissions before it's sent. That's simpler than a WebSocket, works through the reverse proxy, and needs no new library.
- **`/wk-event` stays** for the old UI; M2 doesn't use or change it.
- **Its leak was live, and fixing it wasn't M2's job.** Server push ships turned on (§3.1), so every signed-in user of the old UI received the name, path and properties of every document changed anywhere, including ones they couldn't read. It was a security fix to the old UI, so it went in its own pull request, like M1's security fixes, rather than waiting for M2: [Healdan-Project/healdan#58](https://github.com/Healdan-Project/healdan/pull/58) fixed it on 2026-10-23, and the capability map records it.

## 10. Code organisation (calls)

### 10.1 A new module, `healdan-api`

- **The HTTP layer goes in a new plugin module, `healdan-api`**, under `com.logicaldoc.healdan.api`. Its JPF id is `healdan-api`, and it requires `healdan-core`. It follows the four-piece plugin pattern in CLAUDE.md. The API will grow to cover M5, M6 and an Admin app, so keeping it apart from the domain rules keeps both readable.
- **Domain code stays in `healdan-core`:** the tokens (entity, DAO, Flyway script, authenticator, the listener that revokes them), the recent-folders query and the properties read model. M4's OIDC sign-in and the old UI can use them without depending on the API module. (§2 anti-goals)

### 10.2 Framework: CXF JAX-RS

- **The API uses CXF's JAX-RS stack, which the WAR already ships** for `/services/rest`, with the Jackson provider that's already there. (roadmap open question; call)
- **Why not Spring MVC:** it would be a second web framework with its own dependency to keep in step with upstream's Spring version. CXF is already maintained by upstream's dependency updates, and JAX-RS annotations are a standard.
- **Its own servlet.** `healdan-api` registers a servlet at `/api/*` with `@WebServlet`, extending CXF's `CXFNonSpringServlet` and building its JAX-RS server in code, with resource objects taken from Healdan's Spring context. It doesn't touch `/services/*` or CXF's bus for SOAP and REST. If Tomcat doesn't pick up the annotation from the plugin jar, the fallback is a static `web.xml` entry, as M1 planned.
- **Multipart** uses CXF's attachment support. Uploads stream to a temporary file, never into memory, and the size limit is the old UI's (`upload.maxsize`).

### 10.3 Spring Security

One line in `context-security.xml` (upstream): `<security:http pattern="/api/**" security="none" />`, next to the existing `security="none"` lines. The API then authenticates only by bearer token (§5.1). Without it, the catch-all chain would sign API requests in from the `ldoc-sid` cookie (§3.1). That cookie-based sign-in is what would make cross-site requests possible, and it would be a second way in that the token rules don't govern.

## 11. Data model

One new table, in a new Flyway script `V3__api_tokens.sql` for MariaDB and HSQLDB (`healdan-core`).

| Table | Columns (beyond the standard `PersistentObject` ones) | Notes |
|---|---|---|
| `hd_api_token` | `hd_userid`, `hd_hash` (SHA-256 hex, unique), `hd_name`, `hd_lastused`, `hd_expires` (absolute), `hd_sid` (the bound CE session, nullable) | Revoking soft-deletes the row (`ld_deleted = 1`) and closes `hd_sid`. Idle expiry is computed from `hd_lastused`. |

New `hd_setting` defaults (§5.5) ship as a new seed step, per CLAUDE.md's rule that a shipped seed step never changes. A second new seed step turns on the sign-in throttle in `context.properties` (§5.7).

## 12. The inventory

- **Where:** [`docs/reference/healdan-api-inventory.md`](../../reference/healdan-api-inventory.md), committed with this spec. It lists all 50 GWT-RPC services (475 methods), the 52 `/data/*.xml` servlets, the 23 other servlets and endpoints, and `/wk-event`, with what each does.
- **Tags:** each entry is tagged with the milestone that first needs it: `M2`, `M3`, `M5`, `M6`, `M7`, `Admin`, or `none`. `none` covers the 29 services (192 methods) that are enterprise stubs with no server code, and things Healdan doesn't use. The tags are judgements, and a milestone's design session can move them.
- **What M2 takes:** 31 RPC methods, 6 data servlets and 3 other endpoints (`/upload`, `/download` and form login), plus what's new: recent folders, recent documents and the properties read model. M3, M5 and M7 need nothing else that exists: email filing uses M2's filing, and walls are new server code.
- **Mapping (call):** the inventory's §0.1 maps each old call M2 replaces to its endpoint. Every other service and servlet names only the resource area it'll probably land in (`/documents/{docNumber}/notes`, say), not a designed endpoint. Designing 500 endpoints now would be guesswork that M5 and M6 would redo.
- **Kept current:** each milestone that adds endpoints updates its entries' tags and endpoints in the same pull request.

## 13. Upstream files M2 edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-webapp/src/main/resources/context-security.xml` | one `security="none"` line for `/api/**` | §10.3 |
| `logicaldoc-core/src/main/java/com/logicaldoc/core/security/SessionManager.java` | in the two `newSession` methods the others delegate to: a `LoginThrottle.checkLoginThrottle` call before the chain, and `LoginThrottle.clearFailures` on success | §5.7 |
| `pom.xml` (root) | the `healdan-api` module | §10.1 |
| `logicaldoc-webapp/pom.xml` | an `artifactItem` for `healdan-api`'s plugin zip | CLAUDE.md's plugin pattern |

Fork-owned files it changes: `.github/workflows/build-logicaldoc.yml` (run `healdan-api`'s tests), `docker/smoke-test.sh` (the flow in §14.1), `docker/README.md` (the API and its docs page), and the Healdan Settings dialog.

## 14. Verification

### 14.1 Automated

**`healdan-api` unit and integration tests**, in CI next to `healdan-core`'s. They run each endpoint through CXF's in-process transport against the test database with Healdan's migrations and seeds. For each endpoint:

- the success case;
- a user who can't read the object gets `404`, and a user who can read but not change it gets `403`;
- each rule the endpoint can hit: `filing-rule` at a matter's top level and in a search folder; a required attribute with no value (one the test adds to the profile, because Class always has a value: a blank Class takes the install default); a Subclass from another Class; `checked-out` and `locked` by another user, admins included; a changed extension on save.

Plus:

- **Doc # and profile:** a document filed through the API gets the next doc #, inherited values, an override where the client sent a different value, and a Date filed of "now" whatever the client sends.
- **Versions:** snapshot and version saves produce `2.1` and `3.0`; `keepCheckedOut` leaves the document checked out to the same user; content downloads byte for byte for each version.
- **Tokens:** sign-in through the chain (a wrong password and a disabled user are each refused), idle and absolute expiry, revocation, revocation on password change and on disabling, a deleted user's token refused, a token that outlives its CE session gets a new one, and a cookie with a valid session doesn't authenticate an API request.
- **Throttle (§5.7):** after 5 wrong passwords in a row for one username, the next sign-in gets `429` even with the right password, and so does the 21st failure in a row from one address; the block lifts 15 minutes after the last counted failure, and retrying during it doesn't extend it; a success through the API or SOAP clears the counts; the account stays enabled; a revoked token doesn't count. A test in `logicaldoc-core`, added to CI's `-Dtest` list, shows the same block on the old UI's route (`LDAuthenticationProvider`) and on an API key. The seed step writes the six values into a configuration that has `throttle.enabled = false`, and never runs twice.
- **Lists:** recent documents and recent folders pick up a filing, a save and a download, newest first; the checked-out list follows check-out and check-in; each leaves out what the user can't read. An admin gets the Stores (without the Default workspace and the Templates Store) and a Store's matters in order, paged; a non-admin gets `403` from both.
- **Contract:** every response a test receives validates against `openapi.yaml`; routes and file match both ways; the file parses (§4.6).
- **CORS:** a listed origin gets the headers and an unlisted one doesn't.

**End to end on the Docker stack.** `docker/smoke-test.sh` runs the add-in flow with `curl` against the stack with the smoke fixture, as a non-admin user the test creates: `GET /server` → sign in → find matter 1.1 → recent matters → the Documents folder's form → file a `.docx` → look it up by `#` → check out → it's in the checked-out list → save a snapshot, then a version, keeping it checked out → check in → it's in recent documents → download the current and an older version and compare bytes → sign out → the token is refused. CI runs it on amd64 and arm64, as it runs the rest of the smoke test.

### 14.2 Manual acceptance (owner)

Short, because M2 is judged by M3 being able to start (owner):

1. Open `/api/docs` on the owner's stack and see every M2 endpoint described.
2. Sign in there with "Try it out", authorise with the token, and call `GET /me` and `GET /matters/recent`.
3. Read the inventory and agree that nothing M3 needs is tagged later than M2.

## 15. Left to the builder

- JSON field names beyond those fixed here, and the exact shape of each `openapi.yaml` schema.
- How resource objects reach Spring beans, and how the per-document lock in §6.6 is held.
- The `HealdanTokenAuthenticator`'s `position` in the chain, as long as only `hd_` keys are claimed.
- How often `lastUsed` is written, and whether token lookups are cached briefly.
- How Swagger UI is packaged, as long as it loads nothing from the internet.
- Sorting within `/folders/{id}/children` beyond "folders first".
- The non-admin user the smoke test creates, and how.

## 16. Decisions made in this session

**Owner:**

- M2 has no consumer but M3: it's done when M3 can start, and success is judged by tests and documentation, not a demo.
- No compatibility promises until the initial roadmap is complete; no Healdan instance is in production.
- M2 builds the add-in slice and the rules every later endpoint follows. The rest of the inventory is tagged, not built.
- M3's trial build doesn't have to come first: M2 builds upload and download either way, and WebDAV locking is M3's if the trial picks WebDAV.
- A browsable docs page is part of done.
- The API lists the Stores and a Store's matters for the admin side, restricted by role; the user apps don't use them (§6.2).
- Sign-in is throttled on every route: 5 wrong passwords block a username for 15 minutes, 20 failures block an address for 15 minutes, and the account is never disabled (§5.7).
- The technical choices are Claude's calls, reviewed in this spec.

**Calls (approved with the spec):**

- Documents are addressed by doc # (§4.2).
- RFC 9457 errors with stable codes; offset paging (§4.4, §4.5).
- Design-first `openapi.yaml`, tested both ways, with a docs page at `/api/docs` (§4.6).
- Revocable device tokens bound to CE sessions through a new authenticator; 30 days idle, 365 days at most; revoked on password change or disabling, and refused once the user is deleted (§5).
- The Store lists are admin-only in M2, following a delegated role once the Admin app defines one; folder browsing still refuses a Store (§6.2).
- Recent folders added (§6.2).
- Recent documents and the checked-out list added for M3's Open dialog (§6.4).
- A document's `hold` covers check-outs and locks; no save over someone else's hold, admins included (§6.4, §6.6).
- One `/search` with the old UI's scopes and `#` behaviour; `/lookup` and the legacy doc # query beside it (§6.3).
- Saves as `POST …/versions` with `snapshot` or `version` and `keepCheckedOut` (§6.6).
- A properties form that names its source and the folder it inherits from (§6.7).
- CORS off by default, with an exact allow-list (§8).
- No push in M2; Server-Sent Events proposed for M6; `/wk-event`'s leak fixed in its own pull request (§9).
- A new `healdan-api` module on CXF JAX-RS, with domain code in `healdan-core` (§10).
- Inventory entries outside M2 name a resource area, not an endpoint (§12).
