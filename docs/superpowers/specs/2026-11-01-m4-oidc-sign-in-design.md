# M4 "OIDC sign-in": design

- **Milestone:** M4 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-11-01
- **Status:** built on 2026-11-01 and 2026-11-02, following the implementation plan named below, in five pull requests: [Healdan-Project/healdan#154](https://github.com/Healdan-Project/healdan/pull/154) (PR A: the server's core), [#156](https://github.com/Healdan-Project/healdan/pull/156) (PR B: the endpoints, the re-check, unlinking, the `admin` alert and the stand-in provider), [#157](https://github.com/Healdan-Project/healdan/pull/157) (PR C: the sign-in page, the Sign-In tab and the Install pages), [#158](https://github.com/Healdan-Project/healdan/pull/158) (PR D: the helper's browser sign-in) and [#159](https://github.com/Healdan-Project/healdan/pull/159) (PR E: the records). Where the build departs from this spec, the section says "changed in the build", and the plan's decision log has the reasons. The owner's walkthrough (§16.2, the plan's Task 17) ran on 2026-11-02 on the owner's server over HTTPS, with the owner's Authentik and Healdan Helper 0.6.0-rc1: rows 1 to 8 passed on Authentik (§16.2 has each). Entra ID's rows weren't run, so its two questions stay open: the owner deferred them for lack of resources and accepted M4 the same day, with Entra ID shipped as untested and experimental in Healdan Helper 0.6.0. Design agreed with the owner on 2026-11-01, question by question and then section by section. While the written spec was being reviewed, the owner asked about break glass and unlinking in a side conversation, and then decided both: unlinking is in M4, and an alert when `admin` signs in is optional (§7.5, §7.7). Approved by the owner on 2026-11-01. Implementation plan: [`docs/superpowers/plans/2026-11-01-m4-oidc-sign-in.md`](../plans/2026-11-01-m4-oidc-sign-in.md), approved the same day; its "Spec corrections and calls this plan makes" lists where it departs from this spec.
- **Inputs:**
  - the roadmap's M4 section, and its glossary;
  - the [M2 spec](2026-10-23-m2-api-foundation-design.md), above all §5 (device tokens and the sign-in throttle) and §10.3 (Spring Security);
  - the [M3 spec](2026-10-24-m3-office-integration-design.md) §5.2 and §10, and the [M3.5 spec](2026-10-30-m3-5-microsoft-office-design.md) §5.8 and §6.2, for how the helper and the add-ins sign in today;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md) §5.4;
  - a design conversation with the owner on 2026-11-01;
  - a survey of this repository at `50039b696`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard and approved section by section. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace* and *template* mean Healdan's things here (roadmap glossary). "CE" is LogicalDOC Community Edition. "The provider" is an OpenID Connect identity provider, such as Microsoft Entra ID or Authentik. "A provider account" is a Healdan account linked to an identity at a provider (§7).

## 1. Intent

People sign in to Healdan with the identity provider their firm already uses: Microsoft Entra ID for the owner's Microsoft 365 tenant, or Authentik (roadmap). The provider handles passwords and two-factor. Healdan keeps a local `admin` with a password, as the way in when the provider is down (roadmap).

**Two things sign in:**

- **The web page,** through a button on its sign-in page, one per provider (§5).
- **The Healdan Helper,** through the same provider in the system browser, ending in the same device token it gets today (§6; M2 §5.6).

**The add-ins never sign in.** The Office add-in and the LibreOffice extension hold no token and talk only to the helper (M3 §10, M3.5 §6.2). So the roadmap's call that "add-ins sign in through the same provider in a pop-up" is met by the helper's browser sign-in, and nothing in either add-in changes.

**One standard OIDC implementation, configured per provider** (roadmap). Entra ID and Authentik get tested presets; other OIDC providers should work but aren't promised (roadmap).

## 2. Goals, non-goals, anti-goals

### 2.1 Goals (M4 is done when)

The walkthrough in §16.2 passes, against the owner's server over HTTPS, with Healdan Helper 0.6.0. In short:

1. **Set up.** An admin sets Entra ID up in Healdan Settings' Sign-In tab, following the Install pages, and its Check passes (§10).
2. **The web page.** Sign In with the provider's button works. The owner's day-to-day account is linked by email and keeps its history, check-outs and My Matters (§5, §7).
3. **No passwords for provider accounts.** A linked account has no Healdan password on any route, and can't be given one (owner, §7.3).
4. **Groups follow the provider** at every sign-in, and a sign-in that maps to no Healdan group is refused (owner, §8).
5. **The helper** signs in through the browser and works with Word and LibreOffice as before. A person disabled at the provider is asked to sign in again within 8 hours (owner, §6, §9).
6. **Break glass.** `admin` signs in with its password when the provider fails, and Healdan runs on without the provider (roadmap, §7.5).
7. **Unlinking.** An admin unlinks an account, which goes back to a password and keeps everything it had (owner, §7.7).
8. **Authentik.** The same on the owner's own Authentik, where a new person gets a new account (owner).
9. **In CI.** The automated tests in §16.1 run, including the whole flow against a stand-in provider on the Docker stack, on amd64 and arm64.
10. **Documented.** The docs pages in §15.3 are written.

### 2.2 Non-goals

- **LDAP and SAML** (roadmap).
- **Healdan's own two-factor for local accounts.** It stays in the backlog (roadmap).
- **Signing out of the provider.** Sign Out ends the Healdan session only; the person stays signed in at Microsoft or Authentik (call).
- **Provisioning ahead of the first sign-in** (SCIM or the like). An account is made at its first sign-in, or by an admin by hand and then linked by email (§7.2; call).
- **Re-checking web sessions with the provider.** A web session ends after 30 idle minutes, and the next sign-in goes to the provider (§9; recorded in Known Limitations).
- **Moving accounts from one provider to another in one step.** An account has one identity (§7.1). A firm that changes provider unlinks the accounts (§7.7), and each person's first sign-in through the new provider links them again by email (§7.2).
- **Passwords for provider accounts on WebDAV, CMIS, SOAP and REST** (owner). Provider accounts use the web page, the helper and the add-ins. A separate app password for those routes is a follow-up if anyone needs one.
- **Multi-tenant Entra apps.** One app registration in one tenant, with a fixed issuer, not `common` or `organizations` (call).
- **An embedded web view in the helper.** The system browser, as RFC 8252 recommends for native apps (call).
- **LogicalDOC tenants.** Healdan uses only the default tenant (roadmap glossary).

### 2.3 Anti-goals

- **A second way in that skips the provider:** a provider account with a password anywhere (owner). (changed in the build: ruling R31. Nor a CE session the account had before it became one: linking closes them all, §7.3.)
- **Trusting what a provider lets its users change** when linking accounts (owner, §7.2).
- **A route around CE's sign-in chain.** Every provider sign-in opens its CE session through `SessionManager.newSession`, so the throttle and CE's account checks apply, as for M2's tokens (call).
- **A secret where a client, a log or a database dump can read it** (§13).
- **Editing upstream's Spring Security configuration.** It isn't needed (§3.1).

## 3. Constraints

### 3.1 Facts from the code

- **Spring Security.** `context-security.xml` (upstream) has `<security:http pattern="/api/**" security="none" />` since M2 (M2 §10.3). So endpoints under `/api/` run outside Spring Security, answered by Healdan's own servlet. The roadmap's recon expected a hook in that file, which Spring's own OAuth2 login would need; Healdan's own endpoints don't.
- **The web page's session.** The password form's success handler (`LDAuthenticationSuccessHandler`) sets an HttpOnly cookie, `ldoc-sid`, holding the CE session's id (Secure when `cookies.secure` is on), and saves the id in the HTTP session. `SessionFilter` signs every later request in from it. Any code that opens a CE session can do the same.
- **One way into CE.** Every channel signs in through `SessionManager.newSession`, which runs the throttle (`HealdanThrottle`, M2 §5.7) and then the `Authentication` chain. M2's `HealdanTokenAuthenticator` opens a session for a device token by handing the chain a one-time key on the thread, and refuses every other key that starts `hd_` (M2 §5.1). (changed in the build: it leaves keys starting `hd_signin_` to `SignInAuthenticator`, §7.6.)
- **The chain can't veto.** It tries authenticators in `position` order, and a refusal by one doesn't stop the next (`AuthenticationChain`). So a Healdan authenticator can't overrule a password that `DefaultAuthenticator` accepts. A provider account is kept from passwords by having none (§7.3).
- **Where an account comes from.** `User.source` is CE's `UserSource` (`DEFAULT`, `LDAP`, `SAML`), stored as its number in `ld_user.ld_source`.
  - For any source but `DEFAULT`, CE allows an empty password (`HibernateUserDAO.store`) and skips its password history, rules and expiry. The old UI hides Change Password (`AccountMenu`).
  - `DefaultAuthenticator` refuses a user whose password is null.
- **Groups.**
  - CE's groups are `admin`, `author`, `guest`, `poweruser` and `publisher`, plus each user's own group.
  - Healdan adds Everyone, and `EveryoneUserListener` keeps every user except read-only ones in it.
  - A group carries a free-text `source` (`Group.getSource`).
- **Usernames.** CE's user editor can't change an existing user's username.
- **User listeners** (changed in the build: plan correction 1, and ruling R8 in the plan's decision log).
  - `HibernateUserDAO.store` logs and swallows a listener's `PersistenceException`, and rethrows only an `AuthenticationException`. So a `UserListener` can veto a save only with an `AuthenticationException`.
  - CE's user delete runs no listener, as `UserListener` has no delete hook. It marks the account deleted, renames it `<username>.<id>`, and deletes its group memberships, API keys and impersonators by JDBC.
- **No default language.** CE keeps no setting for the server's default language. Its setup wizard sets the language of every account that exists then, `_system` included (changed in the build: found in Task 5).
- **The sign-in page** is GWT's `login` module. `LoginPanel.initGUI` builds it, and `MobileLoginPanel` runs the same method, so one hook covers both layouts. It already carries a Healdan hook (`HealdanCredits`). `login.jsp` sends a browser on to the web page when its HTTP session holds an open `cas-sid`, which is LogicalDOC's own single sign-on; Healdan doesn't need it.
- **`GET /api/v1/server`** is public and lists the sign-in methods: `["password"]` since M2 (M2 §5.6).
- **Libraries.** The WAR ships no OAuth 2.0, OpenID Connect or JOSE library. `jose4j` 0.6.5 is in the build only as Solr's dependency, and isn't shipped. Spring Security 6.3.10 ships without its OAuth2 modules.
- **CE sessions** close after 30 idle minutes as shipped (`session.timeout`), or on a restart.

### 3.2 Facts from the providers

The plan checks each against the provider's current documentation, and the Install pages give the current steps.

**Entra ID:**

- A single-tenant app registration has a fixed issuer, `https://login.microsoftonline.com/<tenant id>/v2.0`, with its discovery document under it.
- `sub` is pairwise: unique to the person and the app registration. `oid` is the person's object id in the tenant, the same for every app.
- `preferred_username` is usually the UPN (`jane@firm.example`). Microsoft says it's mutable and not for authorisation.
- `email` is an optional claim, from the account's mail attribute, which the tenant's admins set and users can't.
- `groups` carries group object ids once the app registration is set to send groups. With too many groups, it's replaced by a pointer to Microsoft Graph (the "overage"), so the setting to use is "groups assigned to the application". App roles, in `roles`, are the alternative.
- Client secrets expire, at most 24 months after they're made.
- `offline_access` gives a refresh token. A refresh fails once the account is disabled or its sessions are revoked.
- Without "Assignment required" on the enterprise app, any account in the tenant, guests included, can sign in to it.
- (changed in the build: found while writing the Install pages.) Microsoft's documentation describes the ID token in a refresh's answer as unsigned (`alg: none`), so Healdan ignores such a token (§9). Healdan sends the client secret the way the discovery document lists (§13); Entra's lists `client_secret_post` as well as `client_secret_basic`, so post goes out (changed in the build: ruling R32, as Nimbus form-encodes a Basic header's secret and Entra's generated secrets hold a `~`). The owner's walkthrough (§16.2) will show what the tenant really does.

**Authentik:**

- Each application's provider has its own issuer, `https://<authentik>/application/o/<slug>/`, with its discovery document under it.
- `sub` is, by default, a hash of the user's id, which doesn't change.
- The default `profile` scope sends `groups`, by name.
- A provider with no signing key signs ID tokens with HMAC on the client secret, which Healdan refuses (§13), so the provider needs a signing key.
- Refresh tokens need the `offline_access` scope mapping on the provider.
- Users may change their own email unless an admin turns that off. (changed in the build: current releases ship with users unable to change their email, by System Settings' "Allow users to change email". An older instance, or one an admin changed, may allow it, so the Authentik page says to check that it's off before turning Link by Email on. The preset still leaves Link by Email off, §7.2.)

### 3.3 The setup (owner)

| Piece | What |
|---|---|
| Server | The owner's server, behind Nginx Proxy Manager with HTTPS, reached on the LAN or over VPN |
| Entra ID | The owner's Microsoft 365 tenant, of which the owner is the admin (M3.5 §3.2) |
| Authentik | The owner's own Authentik instance (owner, 2026-11-01) |
| Client | Healdan Helper on the owner's Mac, with Word and LibreOffice |

## 4. The pieces

| Piece | What M4 adds | Where it lives (call) |
|---|---|---|
| **Providers and accounts** | Provider settings and presets; discovery, the code flow and the ID token checks, with Nimbus; identity links; linking and making accounts; group sync; the re-check; the authenticator for provider sign-ins; encrypting secrets | `healdan-core`, `com.logicaldoc.healdan.signin` |
| **Sign-in endpoints** | The `/api/signin/` pages; the code exchange in `POST /api/v1/auth/tokens`; the providers in `GET /api/v1/server` | `healdan-api` |
| **The web page** | Provider buttons on the sign-in page; the Sign-In tab in Healdan Settings | `logicaldoc-gui`'s Healdan packages, through one hook in `LoginPanel` |
| **The helper** | Signing in through the browser, with a loopback redirect | `clients/helper/` |
| **Docs** | A sign-in section in the Install manual, and changes to the Admin, User and Developer guides | `site/` |

## 5. Signing in on the web page

### 5.1 The sign-in page (owner)

- **With a provider on,** the page shows one button per provider, in the Sign-In tab's order, each labelled as the tab sets it (owner: the label is a setting, not necessarily "Microsoft").
- **The password form is folded away** behind a link, also labelled from the settings: "Sign In with a Password" by default (owner). Choosing it shows the form as it is today, with Remember Me, the language and Lost Password.
- **With no provider on,** the page is as it is today.
- **A refusal** (§5.3) comes back to the sign-in page, which shows its message above the buttons.
- **Sign Out** comes back to this page, and nothing signs the person straight back in (owner).
- **How:** a hook in `LoginPanel.initGUI` hands the page's panels to Healdan's `HealdanSignInButtons`, which asks `GET /api/v1/server` for the providers and the labels (§11).

### 5.2 The flow (call)

1. **`GET /api/signin/<id>`** starts a sign-in with provider `<id>`.
   - Healdan makes a `state`, a `nonce` and a PKCE verifier (S256), and keeps them server-side for 10 minutes, keyed by the state.
   - It binds them to this browser with a random value in an HttpOnly, `SameSite=Lax` cookie on `/api/signin/`, Secure over HTTPS.
   - It redirects to the provider's authorisation endpoint: `response_type=code`, `response_mode=query`, `scope=openid profile email`, the client id, the redirect address, the state, the nonce and the code challenge.
   - (changed in the build: from the final review.) A start that came to another name than the public address (step 6), such as `http://127.0.0.1:8080` when the public address is `https://healdan.example`, is first sent to the same path and query at the public address, before anything is kept or the cookie set. The cookie belongs to the name the browser asked for, and the provider sends the browser back to the public address, so a sign-in started on another name would always come back `expired`. Only the host is compared (R36), as a cookie belongs to the host whatever the scheme or port. A proxy that doesn't pass the browser's `Host` header would loop, so the Install manual's Reverse Proxy page says to pass it.
2. **The provider sends the browser back** to `GET /api/signin/<id>/callback`.
   - Healdan checks that the state is known, unexpired and unused, that the browser's cookie matches it, and that it names this provider.
   - An error from the provider (`access_denied`, say) ends the sign-in there, with a message (§5.3).
3. **Healdan exchanges the code** at the provider's token endpoint, with the client secret and the verifier, and checks the ID token (§13).
4. **It decides the account and its groups** (§7, §8).
5. **It opens a CE session** through `SessionManager.newSession`, with a one-time key standing for the account (§7.6).
   - It gives the HTTP session a new id, then sets `ldoc-sid` and saves the session's id in the HTTP session, as the password form does.
   - It then goes where the password form would: the page asked for before signing in, or else the web page. (changed in the build: that's the HTTP session's `RequestedUrl` under `login.jsp`'s conditions, followed only for a path on this server's own address. Nothing in CE sets `RequestedUrl`, though `login.jsp` reads it, so in practice a sign-in lands on `frontend.jsp`.)
6. **The redirect address** an admin registers at the provider is `https://<server>/api/signin/<id>/callback`, one per provider. It's built from the server's public address (`LOGICALDOC_PUBLIC_URL` when set, as M3.5 §7's manifest is, else the address the request came to), and the Sign-In tab shows it (§10.3).
   - (Refined while writing the spec: Section 1 of the design said one callback address for every provider. One per provider means the callback always knows which provider it expects, which closes off "mix-up" attacks between providers.)
   - (changed in the build: plan correction 5, and ruling R2, with its pages' detail.) The address logic moved out of `AddinResource` into a shared `PublicAddress`, in `healdan-core`'s `com.logicaldoc.healdan.web` rather than `healdan-api`, so the Sign-In tab's RPC shows exactly the address the pages send. It takes `https`, or `http` on `localhost`, `127.0.0.1` or `[::1]`, or anywhere while `HEALDAN_SIGNIN_ALLOW_HTTP` is set. With no usable address, the sign-in pages answer a plain `409` page saying the server's public address isn't set, since they can't send the browser anywhere without one.

### 5.3 Refusals

A refusal comes back to the sign-in page, or to the helper (§6.2), with a code. The message says only what the person can do; the details go to Healdan's log.

| Code | When | The message says |
|---|---|---|
| `no-group` | The person's provider groups map to no Healdan group (§8) | "Your account doesn't give you access to Healdan. Ask an administrator." (owner, 2026-11-02; changed in the build: the messages don't name the provider, as the presets' labels read oddly in a sentence) |
| `email-taken` | The email matches an account that can't be linked: `admin`, one already linked, or more than one (§7.2) | Ask an admin |
| `username-taken` | A new account's username already exists, or isn't one Healdan can use (§7.2) | Ask an admin |
| `account` | CE's chain refused the account: disabled, expired, outside working hours | What the password form says for the same refusal |
| `provider` | The provider couldn't be reached, refused, or failed a check (§13) | "The sign-in provider couldn't sign you in. Try again, or ask an administrator." It's also what any code a page doesn't know says (owner, 2026-11-02; changed in the build: the messages don't name the provider, as the presets' labels read oddly in a sentence) |
| `expired` | The sign-in took more than 10 minutes, or its state was unknown or used | Start again |

(changed in the build) The messages are the plan's Global Constraints' table, but for the two the owner changed on 2026-11-02 (below). `account` has one message for every refusal of CE's chain, "This account can't sign in now. Ask an administrator.", rather than the password form's own for each. It also covers a disabled or expired account that linking by email would have matched (§7.2). `expired` also covers a callback from another browser, or at another provider's callback address. (owner, 2026-11-02; changed in the build) No message names the provider, in the sign-in page, the helper's done page or the helper's window: the presets' labels ("Sign In with Microsoft") read oddly in a sentence, and the person already sees which button was clicked. The first version put the provider's label in the `no-group` and `provider` messages, "your sign-in provider" where the sign-in page didn't know it. The done page therefore takes no `provider` parameter (one that comes is ignored), and the helper sends none.

## 6. Signing in from the helper

### 6.1 The window (call)

- **The sign-in window** asks for the server's address as today, then reads `GET /server`: a button for each provider, and the password link, labelled as on the web page.
- **A server with no provider** gets today's window. The password route stays for local accounts (owner, §7.3).

### 6.2 The flow (call; RFC 8252's loopback redirect)

1. **The helper prepares.** It makes a PKCE verifier, its challenge (S256) and a `state`, and listens on `http://127.0.0.1:<random port>/` for one request, for at most 5 minutes. (changed in the build: from the final review. The request is a `GET` of `/`; any other method or path, such as a browser's `/favicon.ico`, gets a `404` and the listener goes on waiting.)
2. **It opens the system browser** at `https://<server>/api/signin/<id>?client=helper&redirect_uri=http://127.0.0.1:<port>/&state=<state>&code_challenge=<challenge>&code_challenge_method=S256`.
3. **Healdan runs §5.2's steps 1 to 4.**
   - It accepts only a loopback `redirect_uri`, on `127.0.0.1` or `[::1]` at any port, so a code can never be sent to another machine.
   - It adds `offline_access` to the scope, and keeps the provider's refresh token with the pending code (§9).
   - (changed in the build: ruling R18.) The helper's parameters are checked first, before the public address and the provider: the redirect exactly `http://127.0.0.1:<port>/` or `http://[::1]:<port>/` (no user info, query, fragment or other path), a state of 1 to 128 unreserved characters, a 43-character base64url challenge, and the method `S256`. Any of them wrong is a `400` page with no redirect and no cookie. Only then does an unknown or turned-off provider go to the helper's address as `error=provider`. The helper's flow sets and checks the `hd_signin` cookie as the web page's does.
   - (changed in the build: from the final review.) A start whose challenge another sign-in already has, waiting or with a code issued and not yet redeemed, is refused, and its browser goes to its own `redirect_uri` with `error=expired` and its own state; nothing is kept for it. Another local user could read the helper's start address from the process list, run a sign-in of their own with the helper's `redirect_uri` and challenge, and race their code to the helper's port. Refusing the second start means whoever comes second loses. `PendingSignIns` and `OneTimeCodes` find a challenge by its SHA-256, and it's never logged.
4. **Healdan makes a one-time code** instead of a web session.
   - The code is random, lives 60 seconds and works once. It's bound to the account, the provider, the refresh token, the challenge and the redirect address.
   - Healdan redirects the browser to the helper's address with `code` and the helper's `state`. A refusal goes there too, as `error=<code>` from §5.3, so the helper can say why.
   - (changed in the build) Before a code is made, Healdan runs CE's account checks (`DefaultAuthenticator.validateUser`), so a linked account CE would refuse gets `error=account` at once, rather than a code that fails at the exchange. A callback from another browser, or at another provider's address, goes to the helper as `error=expired`. A state Healdan doesn't know, or that was used or expired, goes to `/login.jsp?signin=expired`, as no helper address is known for it. The CE session opens only at the exchange.
5. **The helper's listener** checks the state, then sends the browser on to `https://<server>/api/signin/done`, which says "Signed in to Healdan. You can close this tab." or what went wrong. Then it stops listening. (changed in the build: the helper adds `error=<code>` for a refusal. It first added `provider=<id>` too, so the page could name the provider in its message; the messages no longer do, so it doesn't, §5.3.)
6. **The helper exchanges the code** at `POST /api/v1/auth/tokens` with `{code, codeVerifier, redirectUri, name, device}`.
   - Healdan checks the code, the verifier and the address.
   - It opens a CE session through the chain (§7.6), issues the device token (M2 §5.1 to §5.4), and keeps the refresh token with it (§9).
   - The helper keeps the token in the keychain, as today (M3 §5.2).
   - (changed in the build) A code body missing a field, or one that also carries a username or password, is `422 validation`, and uses up no code. A throttled exchange is `429`, as for a password.

**Afterwards, as today:**

- A `401` (the token expired, was revoked, or the re-check refused it, §9) opens the sign-in window again, and saves wait meanwhile (M3 §5.2).
- Sign Out revokes the token. Nothing happens at the provider (§2.2).

**Why the loopback address, not a `healdan://` link:**

- RFC 8252 recommends it for desktop apps, and it works the same on macOS, Linux and, later, Windows.
- It needs no hand-over between processes, as `healdan://` links do on Linux (M3 §5.3).
- `healdan://` links stay able only to open documents.
- Another app can claim a URL scheme, but not a port the helper is listening on. PKCE makes an intercepted code useless anyway.

The browser moves from Healdan's HTTPS page to `http://127.0.0.1` as a top-level navigation, which browsers allow. That's unlike M3.5's add-in, whose requests WebKit blocks as mixed content (M3.5 §3.2). The plan's first task checks that Safari shows no warning. (changed in the build: no task checked it, and the stack test's browser is a fake. The owner's walkthrough, §16.2 row 4, is the first real browser to take the helper's sign-in.)

## 7. Accounts

### 7.1 The identity (call)

- **A person is identified by the provider's id and the value of its identity claim:** `oid` in the Entra preset, `sub` in Authentik's (§3.2). Entra's `oid` stays the same if the app registration is ever made again, where `sub` would change.
  - (changed in the build: rulings R33 and R34.) The identity claim can't be one some providers let people change: `email`, `preferred_username`, `upn`, `unique_name`, `name` or `nickname`, ignoring case; the refusal says to use a stable claim such as `sub` or `oid`. And as the link has no issuer, the issuer can't change while the provider has linked accounts, but for a trailing slash: a new tenant, or a provider whose `sub` values repeat, would map the links onto other people.
- **Links are stored in `hd_signin_identity`** (§12). An account has at most one identity, and an identity belongs to one account.

### 7.2 Which account a sign-in is (owner; call on the order)

1. **An identity already linked** is its account. A link an admin has unlinked doesn't count (§7.7). (changed in the build: ruling R8.) A link whose account was deleted in CE, which runs no listener (§3.1), is closed first: marked unlinked now, by nobody, and logged. The sign-in then carries on as for an identity with no link.
2. **The gate.** The person's provider groups are mapped (§8). If no mapped Healdan group results, the sign-in is refused (`no-group`) and nothing is made. A linked account refused here is also taken out of every mapped group, so nothing it held through the map stays (owner).
3. **Link by email**, when the provider's switch is on (owner).
   - The email claim must be present, and match exactly one account's email, ignoring case.
   - That account must not be `admin`, a system account or already linked. It's then linked.
   - An email that matches an account that can't be linked, or more than one account, is refused (`email-taken`), rather than making a second account with the same email.
   - The switch is on in the Entra preset, where the tenant's admins set emails, and off in Authentik's, where users may change theirs unless an admin turns that off (owner, §3.2).
   - (changed in the build: ruling R16.) An account that matches but is disabled, or past its expiry date, is refused as `account`, and nothing is linked or changed, so a refused sign-in never converts an account an admin turned off. Passing conditions, such as working hours, don't block linking.
4. **Otherwise, a new account.**
   - Its username is the username claim's value: the UPN in Entra's preset.
   - It's refused (`username-taken`) if that username exists, is `admin`, contains `>` (CE's impersonation separator) or is something CE can't store.
   - First name, last name and email come from `given_name`, `family_name` and `email`, falling back to `name` for the names. The language is the server's default, and the account is an ordinary one, not read-only. (changed in the build: CE has no default language (§3.1), so a new account takes `_system`'s, which the setup wizard set, else `en`.)
   - (changed in the build) "Taken" counts every account's username, deleted accounts' included, ignoring case.

An admin who wants someone's account ready before their first sign-in makes it by hand, with a password and the person's email, and the first sign-in links it by email (§7.3).

### 7.3 Provider accounts and passwords (owner)

- **Linking or making an account turns it into a provider account.** Its source becomes a new `UserSource.OIDC` and its password is removed (set to null).
- **It can't be given a password.** A Healdan `UserListener` refuses to store a password on a provider account, whichever way it comes: Change Password, the lost-password email, the user editor, SOAP's user calls. The builder lists every path that sets a password, and tests each one.
  - (changed in the build: plan correction 1.) The listener, `ProviderPasswordListener` at position 95, refuses with `ProviderPasswordException`, an `AuthenticationException`, as that's the only veto `HibernateUserDAO` passes on (§3.1). It also refuses moving a provider account to another source, except while unlinking (§7.7).
  - Every path ends in `UserDAO.store`, where the listener refuses it. The user editor's Change Password and Lost Password, in `logicaldoc-webapp`, which Healdan's tests can't load, were checked by hand on a stack.
- **So no password route signs a provider account in.** `DefaultAuthenticator` refuses a null password (§3.1), and nothing can set one. That covers the sign-in form, WebDAV, CMIS, SOAP, REST and the API's password sign-in.
- **Nor does impersonation** (changed in the build: ruling R14). CE signs `bob>jane` in as `jane` with `bob`'s password when `jane` allows `bob` to impersonate her, which would be another account's password route into a provider account. So a provider account has no impersonators: linking and every later sign-in or re-check clear them, the listener refuses storing a provider account with any, and it empties them when the account is loaded.
- **Nor does a LogicalDOC API key** (owner, 2026-11-01; changed in the build). A CE `ld-` key signs its account in with no password (`ApiKeyAuthenticator`), so a key made before linking, or made afterwards in the web page (`SecurityServiceImpl.createApiKey` needs only a session), would be a second way in that skips the provider (§2.3). So:
  - Linking an account by email, or a sign-in or re-check that finds a linked account with another source or a password, deletes the account's API keys, as CE's own user delete does, and logs how many went. Once the decision commits, it closes the CE sessions the keys opened, which CE's `X-API-KEY` lookup (`SessionManager.getSessionFromApiKey`) would otherwise go on finding from a deleted key.
  - (changed in the build: ruling R31.) It closes every other open CE session of the account on that node too, under the same lock: one opened with its old password, or by another account's key impersonating it (`<bob's key>>jane`, which `X-API-KEY` finds by that key's hash). The provider sign-in's own session opens after the decision, so it stays.
  - The fork-owned `HealdanThrottle`, which `SessionManager.newSession` already calls before CE's chain, refuses a key whose account is a provider account, on every route a key signs in by: `X-API-KEY` through `SessionFilter`, REST's and SOAP's `loginApiKey`, and a Basic or form sign-in carrying a key, as WebDAV and CMIS take one. It reads the key as CE's chain does, so `<key>>bob` is refused too. The refusal names the key by its label and isn't counted against the address or the key, as M2's refused tokens aren't.
  - Accounts that aren't linked keep their keys, `admin` included. The web page can still make a key for a provider account, and that key never signs in. No new upstream edit; `ProviderApiKeysTest` covers each route.
- **Accounts that aren't linked keep their passwords** on every route, `admin` included (owner).
- **Unlinking is the only way back to a password** (§7.7). Without the listener, a lost-password email could quietly give a provider account a password and get round this section.

### 7.4 At every sign-in

- Name and email follow the provider, where its claims carry them. The username never changes.
- Groups are synced (§8).
- CE records the sign-in as for a password: the last login, the account's history and the session.

### 7.5 `admin`, and break glass (roadmap; owner on the alert)

- **`admin` is the break-glass account.** It's never linked, and a provider username of `admin` is refused (§7.2). It keeps its password on every route, and the password link stays on the sign-in page whatever state the provider is in. Local accounts an admin keeps on purpose work the same way.
- **During a provider outage:**

  | Who | What happens |
  |---|---|
  | `admin` and other local accounts | Sign in with their passwords, as today |
  | Helper users already signed in | Keep working: the device token is Healdan's own, and an outage never revokes it (§9) |
  | Web page sessions already open | Last until 30 idle minutes, as any session |
  | New web page sign-ins through the provider | Wait for the provider: the button gives the `provider` refusal (§5.3) |

- **Healdan runs without the provider.** It fetches a provider's discovery document and keys when it first needs them, and caches them. A provider it can't reach never stops Healdan starting, and never counts as a refusal (§9, §13).
- **`admin` skips every permission check,** so it's for putting things right, not for daily work. Its password is a single factor. The docs advise a long random password kept offline, and the throttle (M2 §5.7) and the LAN or VPN limit its exposure.
- **An alert when `admin` signs in with its password** (owner: optional, an admin's choice, and only by email):
  - a system-wide switch in the Sign-In tab, off by default, with its recipients, `admin` by default (§10);
  - while it's on and any provider is on, each `admin` password sign-in sends the recipients an email, from a message template a seed step adds;
  - nothing is sent when outgoing email isn't set up, and the tab says the switch needs it; no system message is sent instead (owner);
  - with no provider on, `admin`'s password is an ordinary sign-in, and nothing is sent.
  - (changed in the build: plan corrections 3 and 4, and ruling R22.) "Set up" means `default.smtp.host` isn't blank, `localhost`, `127.0.0.1` or `::1`, and `default.smtp.sender` isn't blank or CE's shipped `logicaldoc@acme.com`. The alert is a CE `SessionListener`, `AdminSignInAlert`. It fires on every session opened with `admin`'s password: `admin`'s own, whatever key the form carried, and `admin` impersonating another account (`admin>bob`). It doesn't fire for a session a key really opened (one of `admin`'s own API keys, or the one-time key of a device token or a provider sign-in), nor for `x>admin`, which is `x`'s password. So `admin`'s password together with one of `admin`'s live API keys isn't alerted: whoever holds the key needs no password.

### 7.6 Opening the CE session (call)

- **On M2's pattern.** The endpoint puts a one-time key, standing for the decided account, on the thread, and calls `SessionManager.newSession(key, client)`.
- **The authenticator** returns that account only for the key that's pending. It first applies `DefaultAuthenticator.validateUser`'s checks: enabled, not expired, within working hours, legals confirmed. It refuses every other key of its form.
- **So the throttle, CE's checks and CE's session bookkeeping apply,** as for any sign-in. Whether this is a new authenticator beside `HealdanTokenAuthenticator` or the same one taught a second kind of pending key is the builder's choice. (changed in the build: a new one, `SignInAuthenticator` at position 106, for one-time keys starting `hd_signin_`, which `HealdanTokenAuthenticator` leaves alone. By ruling R15 it also refuses a pending account that isn't a provider account, uncounted, so the endpoints can't open a session for `admin` or a password account this way.)
- **Refusals made before the session** (the gate, linking, the username) aren't counted by the throttle, as the provider has already checked the person. The chain's own refusals count as a key sign-in's do (M2 §5.7).

### 7.7 Unlinking (owner)

A link is a record beside the CE account, never a new or replacement account. So unlinking loses nothing: the user id stays the same, and with it the documents, check-outs, history, My Matters, favourites and grants.

**Unlinking one account** is an admin's action, in the Sign-In tab's list of linked accounts (§10.3):

- **The link is kept as a record,** marked unlinked, with when and by whom, and no longer used (§12).
- **The account goes back to a password account.** Its source returns to `DEFAULT`, with a random temporary password marked expired, so the person changes it at their next sign-in. That's what CE's own admin Change Password does (`SecurityServiceImpl.changePassword`). It's one step, as CE refuses to store a `DEFAULT` account without a password.
- **The password reaches the person** by email when outgoing email is set up (CE's notify email). Otherwise the admin sees it once and passes it on, or the person uses Lost Password, whose link lasts 15 minutes.
  - (changed in the build) `SecurityServiceImpl`'s own notify needs a session, so Healdan builds the same email from CE's `psw.rec1` template and sends it through CE's sender. It escapes the password for HTML, and never files the email as a document. Unlinking waits up to 15 seconds for the mail server to take it, as CE's sender sets no timeouts. The admin sees the password when the email couldn't go: no outgoing email, no email address on the account, a failed send, or no answer in time.
- **Groups stay as they are,** and are managed in Healdan from then on.
- **Device tokens are revoked,** through M2's revoke on a password change (M2 §5.4), and the helper asks for the password. (changed in the build: plan correction 2. `TokenUserListener` notices a password change only when the stored password wasn't null, so unlinking revokes the account's device tokens itself.)
- **LogicalDOC API keys are deleted** (changed in the build: ruling R17). Keys made while the account was linked never worked (§7.3), and would come alive once it has a password again, so unlinking deletes them and closes the sessions they opened, as linking does.
- **Signing in through the provider again,** while it's on, links the account again by the usual rule (§7.2), and its password is removed again (§7.3).

(changed in the build) Unlinking runs under the same lock as a sign-in and a re-check for that person, so it can't land in the middle of one. Unlink Everyone unlinks each account in its own transaction: one that fails is logged and stays linked, and the rest still go.

**Taking a provider off an instance:**

1. **Turn it off.** Its button leaves the sign-in page, `GET /server` stops listing it, so the helper offers only the password, and Healdan stops contacting it. Turning it off unlinks nothing, so a temporary "off" keeps every link.
   - While it's off, its linked people can't start a sign-in, but helpers already signed in keep working (§9).
   - Before the admin confirms, the tab says how many linked people that affects.
2. **Unlink Everyone** on that provider runs the steps above for each of its accounts. Each gets a temporary password, by email when outgoing email is set up. Otherwise the admin gives each one a password in the user editor, which unlinking allows again.
3. **Remove the provider.** Its settings go, and its links stay in `hd_signin_identity` as history. Remove is offered only once the provider has no linked accounts (§10.3). (changed in the build: ruling R8. A deleted account's link isn't counted, as the tab's list can't show it to be unlinked.)

## 8. Groups (owner)

- **A map per provider:** a provider group's value (an Entra object id or app role, an Authentik group name) to a Healdan group, stored in `hd_signin_group` by the Healdan group's id, so renaming the group in Healdan keeps it. One provider group may map to several Healdan groups.
- **Any Healdan group can be mapped, `admin` included** (owner). The docs repeat M1's advice that a day-to-day account shouldn't be an admin, since admins skip every permission check. (changed in the build: ruling R9. "Any" means the ordinary groups, never a person's own, hidden group, which would hand everyone the map lets in that person's permissions.)
- **The sync runs at every sign-in, and at every re-check the provider accepts** (§9). For each mapped Healdan group:
  - the person is added when one of their provider groups maps to it;
  - otherwise they're removed from it.

  Groups outside the map, Everyone (kept by Healdan's listener) and the person's own group are left alone. (changed in the build) Everyone can be mapped, and then counts for the gate, so a map can let people in without giving them a group, but the sync never adds or removes it. A read-only account's groups aren't synced, as CE keeps it in its own group and `guest` only. With a provider whose refreshed ID tokens are unsigned, the sync runs only at sign-in (§9).
- **The gate.** A sign-in that maps to no Healdan group is refused (owner, §7.2). The map is therefore also the list of who may sign in. The Install pages still advise Entra's "Assignment required" and Authentik's application policies, so the provider refuses first.
- **Entra's overage.** If the ID token carries the overage pointer instead of the groups, the sign-in is refused as `no-group`, and the log says to send "groups assigned to the application". Healdan makes no Microsoft Graph call (call).
- **A deleted Healdan group:** its rows are ignored, and the Sign-In tab shows them as deleted. (changed in the build: they're dropped when the provider is next saved, unless another group is picked for them.)
- Every membership change is logged, with the provider and the account.

## 9. Checking back with the provider (owner)

**For helper sign-ins only.** The provider's refresh token is kept with the device token in `hd_api_token`, encrypted (§13), and never goes to the helper.

**When Healdan checks:**

- whenever the token's CE session is opened again (M2 §5.3), which happens after 30 idle minutes or a restart;
- on any request when the last check is more than 8 hours old (owner).

Only one check runs for a token at a time.

(changed in the build: plan correction 6, and ruling R20.) The 8 hours are `signin.recheck.minutes` (480), which the tab doesn't show and the smoke seed sets to 0. A token from a provider sign-in counts as checked when it's issued. While one request checks a token, another for it doesn't wait for the provider: it goes ahead with the token as it stands, so a helper's saves never stall on a slow provider. The cost is that a request can get through during the check that then revokes the token, at most one provider round trip of 10 seconds. The mark and the 15-minute wait are kept in memory, on one node.

**How:** a refresh at the provider's token endpoint, with the client secret.

**What happens:**

| The provider | Healdan |
|---|---|
| **Accepts** | Keeps the new refresh token if one came. If an ID token came too, it applies §7.4 and §8, the gate included, and revokes the device token if the gate refuses |
| **Refuses** (`invalid_grant` or similar: the account was disabled, or its sessions revoked) | Revokes the device token (M2 §5.4) and closes its CE sessions. The helper's next request gets `401` and the helper asks the person to sign in |
| **Can't be reached** (no connection, no answer in 10 seconds, or a server error) | Logs it and keeps the token. It tries again no sooner than 15 minutes later, and the next check that reaches the provider decides |
| **Is turned off** in the Sign-In tab | Isn't contacted. The token is kept, within M2's idle and absolute limits, and the checks resume when the provider is turned on again (§7.7). A provider can't be removed while it has linked accounts (§10.3), and unlinking revokes the account's tokens |

**Only an explicit refusal revokes a token.** "Can't reach the provider" never counts as "the provider refused" (§7.5).

(changed in the build: rulings R10, R12, R19 and R28.)
- **A refusal is `invalid_grant` alone.** Any other error answer (`invalid_client`, `unauthorized_client`, `invalid_request`, `invalid_scope`, or a `400` or `401` that can't be read) counts as can't be reached, and is logged as an error naming the provider, since it points to a setup problem. So an expired or broken client secret (§7.5, §16.2 row 5) signs no helper out. A `429` is logged as busy. The cost: a provider that refuses a disabled person with another code leaves their helper signed in until M2's idle and absolute limits. Keycloak's answer for a disabled user is `invalid_grant`, which the smoke test checks.
- **An ID token in the answer that fails its checks** counts as can't be reached, with a warning, as a malformed answer isn't a refusal.
- **An unsigned ID token in the answer** (`alg: none`, as Microsoft documents Entra's, §3.2) is ignored, as if none came: the check is accepted, and names, email and groups aren't updated, nor is the gate applied. Its claims are never read, and a sign-in still refuses an unsigned ID token (§13). It's logged once per provider. With such a provider, groups follow it only at sign-in.
- **A new refresh token is kept whatever the outcome,** since a provider that turns them over has already retired the old one.
- **A token whose account no longer has a link to that provider** in use, being unlinked or linked elsewhere, is revoked at its next check, without asking the provider.
- **A refresh token that can't be opened** while the client secret can is revoked (the key changed, §13). If neither opens, as when the key can't be read for a moment, nothing is decided and the token is kept.
- (changed in the build: from the final review, and ruling R35.) **Revoking a token clears its sealed refresh token,** so a database dump with the key holds no live one; unlinking revokes through the same call. **A new refresh token too long to keep** (more than 65,535 characters sealed) isn't stored: the token keeps the old one, and the log says so.

- **A provider that gave no refresh token.** The device token works as a password one does, within M2's idle and absolute limits, and isn't re-checked. The Sign-In tab's Check warns when the provider doesn't offer `offline_access`, and the log notes each such sign-in.
- **Web sessions aren't re-checked** (§2.2). They end after 30 idle minutes. Disabling the person in Healdan, or ending their session in the Sessions list, works at once, as today.

## 10. Settings and the Sign-In tab (owner)

### 10.1 Settings

In `hd_setting` (owner), apart from the group map (§8, §12):

| Setting | Default | Meaning |
|---|---|---|
| `signin.providers` | empty | The providers' ids, in button order |
| `signin.password.label` | Sign In with a Password | The password link's label (owner) |
| `signin.<id>.label` | from the preset | The button's label (owner) |
| `signin.<id>.preset` | | `entra`, `authentik` or `other` |
| `signin.<id>.enabled` | true | Off hides the button, refuses its sign-ins, and stops Healdan contacting it; nothing is unlinked, and helpers already signed in keep working (§7.7, §9) |
| `signin.<id>.issuer` | | The provider's issuer address, `https` (§13). (changed in the build: ruling R33) Fixed while accounts are linked, but for a trailing slash |
| `signin.<id>.clientId` | | From the app registration |
| `signin.<id>.clientSecret` | | Encrypted (§13); never shown again |
| `signin.<id>.claim.identity` | from the preset | §7.1. (changed in the build: rulings R21 and R34) Fixed while accounts are linked, and never a claim people can change |
| `signin.<id>.claim.username` | from the preset | §7.2 |
| `signin.<id>.claim.email` | from the preset | §7.2 |
| `signin.<id>.claim.groups` | from the preset | §8 |
| `signin.<id>.linkByEmail` | from the preset | §7.2 |
| `signin.adminAlert` | off | Email the recipients when `admin` signs in with its password while a provider is on (owner, §7.5) |
| `signin.adminAlert.recipients` | `admin` | Usernames to email, as for the throttle's alert |
| `signin.recheck.minutes` | 480 | The re-check's interval (§9); not in the tab (changed in the build: plan correction 6) |

A provider's id is lower-case letters, digits and hyphens, chosen when it's added. It appears in the redirect address, so it can't change. (changed in the build: `done`, the done page's address, can't be an id, nor can an id whose `signin.<id>.` settings already exist, such as `password` and `recheck`.)

### 10.2 Presets

| | Entra ID | Authentik | Other |
|---|---|---|---|
| Label | Sign In with Microsoft | Sign In with Authentik | Sign In |
| Issuer | `https://login.microsoftonline.com/<tenant id>/v2.0` | `https://<authentik>/application/o/<slug>/` | typed in |
| Identity claim | `oid` | `sub` | `sub` |
| Username claim | `preferred_username` | `preferred_username` | `preferred_username` |
| Email claim | `email` | `email` | `email` |
| Groups claim | `groups` (or `roles`) | `groups` | `groups` |
| Link by email | on | off | off |

### 10.3 The Sign-In tab

A new tab in the existing Healdan Settings dialog, for admins (owner):

- **The providers,** with Add (from a preset), Edit, Remove and their order.
- **Each provider's form:**
  - the label, issuer, client id and secret, the secret set or replaced but never read back;
  - the claims, folded away and filled in by the preset;
  - link by email; on or off;
  - the group map, as rows of provider group → Healdan group.
- **Check.** It fetches the discovery document and the keys, then confirms:
  - the issuer matches;
  - the provider signs with an RSA or EC algorithm;
  - it offers `offline_access`.

  It shows the redirect address to register (§5.2), and says plainly what's wrong.
- **The password link's label.**
- **The `admin` alert:** its switch and recipients (§7.5). The tab says the switch needs outgoing email, and shows whether it's set up.
- **Linked accounts** (owner, §7.7): each provider's linked accounts, with how and when each was linked and its last sign-in, and **Unlink** on each. **Unlink Everyone** unlinks all of a provider's accounts after a confirmation that names how many.
- **Turning a provider off** says first how many linked people can't sign in while it's off (§7.7).
- **Changes take effect at once,** with no restart. If a change breaks sign-in, `admin` signs in with its password and puts it right (owner).
- **Remove** is offered only for a provider with no linked accounts. With some, the tab says to turn it off or Unlink Everyone first. Its links stay as history (§7.7). (changed in the build: from the final review. It says: to stop its sign-ins and keep its links, turn it off; to remove it, open it and Unlink Everyone first.)

(changed in the build: rulings R5 and R21, and the tab as built.)
- **Add** asks for the id and the preset, refuses an id in use, and shows the preset's values and the redirect address before anything is saved (a `newProvider` RPC), so the admin can register the address at the provider before having a client id and secret.
- **The identity claim can't change** while the provider has linked accounts: the field is greyed out, and the server refuses it with "Unlink its accounts first". (Ruling R33, from the final review: nor can the issuer, but for a trailing slash, which the server refuses the same way; the field isn't greyed out, so the slash can be put right. The refusals name the provider by its id, never its label, as the tab shows them as HTML.) A changed claim would make every linked person a new identity, so their helpers would be revoked at the next check and their next sign-ins would make or refuse accounts.
- **Check** runs on the saved settings, so it asks for a save first. Its answer is the redirect address followed by the problems, and nothing else when it passes. Beyond the issuer, the algorithms and `offline_access`, it checks that the provider takes a client secret at its token endpoint (§13) and publishes an RSA or EC key. With no usable public address (§5.2), it says so and shows the address the request came to.
- **The secret** field always starts empty, saying whether one is set, and only a typed value is sent. The provider's window and Check's window are destroyed when closed, so a typed, unsaved secret doesn't linger in the page.
- **The group picker** offers only the groups §8 allows.

## 11. The API

- **`GET /server`:** `signIn` lists `password` and, with a provider on, `provider`. The answer gains `providers: [{id, label}]` and `passwordLabel`, which the sign-in page and the helper read.
- **`POST /auth/tokens`** takes a second body, `{code, codeVerifier, redirectUri, name, device}`, beside today's password body (`oneOf` in `openapi.yaml`).
  - A code that's unknown, used, expired, or doesn't match the verifier or address is `401 sign-in-failed`.
  - A provider account's password is `401 sign-in-failed` too, exactly as a wrong password is, so the answer doesn't say the account is linked.
  - (changed in the build) The two bodies are `PasswordSignIn` and `CodeSignIn`, each closed, so a body mixing them matches neither and is `422 validation`, as is a code body missing a field. Neither uses up the code. "A provider on" in `GET /server` means one that's turned on and has its issuer, client id and a secret that opens.
- **`/api/signin/<id>`, `/api/signin/<id>/callback` and `/api/signin/done` are pages,** not JSON API. They take no bearer token, and `openapi.yaml` and `RouteCoverageTest` leave them out, as they leave out `/api/addin/` (M3.5 §7).

## 12. Data model

One new Flyway script, `V6__signin.sql`, for MariaDB and HSQLDB (`healdan-core`):

| Table | Columns (beyond the standard ones) | Notes |
|---|---|---|
| `hd_signin_identity` (new) | `hd_provider`, `hd_identity`, `hd_userid`, `hd_linked` (when), `hd_how` (`email` or `new`), `hd_lastsignin`, `hd_unlinked` (when, nullable), `hd_unlinkedby` (nullable) | Among links in use, unique on provider and identity, and on the user (§7.1). An unlinked row stays as a record (§7.7) |
| `hd_signin_group` (new) | `hd_provider`, `hd_value`, `hd_groupid` | Unique on all three (§8) |
| `hd_api_token` (new columns) | `hd_provider` (nullable), `hd_refresh` (encrypted, nullable), `hd_checked` | A token from a provider sign-in, and its re-check (§9) |

(changed in the build: ruling R35.) V6's `hd_refresh` was `varchar(4000)`, which held a refresh token of up to about 2,969 characters once sealed. `V7__refresh_token_width.sql` widens it to `text` on MariaDB (65,535 bytes, a token of up to 49,121 characters) and `varchar(65535)` on HSQLDB, and clears the refresh tokens of tokens already revoked. V6 had shipped, so it wasn't edited. A sealed token too long even for that fails clearly: the code's exchange is `401 sign-in-failed`, logged, and a re-check keeps the old one, logged.

(changed in the build: plan correction 7.) "Among links in use" rests on two nullable unique columns in `hd_signin_identity`, `hd_linkkey` (`<provider>:<identity>`) and `hd_linkuser` (the user id). The DAO sets them from the row whenever it's stored: both while linked, both null once unlinked. MariaDB and HSQLDB let nulls repeat in a unique index, so unlinked rows repeat.

- **A new seed step,** `signin-settings`, writes the defaults of `signin.password.label` and the `admin` alert's settings, and adds the alert's message template (§7.5). A smoke-only seed configures the stand-in provider (§16.1). (changed in the build: it also writes `signin.providers`, empty, and `signin.recheck.minutes`. The smoke-only step is `smoke-signin`.)
- **Pending sign-ins and one-time codes are kept in memory,** for 10 minutes and 60 seconds. A restart drops them, and the person starts again (call). (changed in the build: each keeps at most 10,000, dropping the oldest, and sweeps the expired ones as it adds.)

## 13. Security (calls)

- **ID tokens are checked in full:**
  - the signature, against the provider's published keys (from its discovery document, cached, and fetched again when an unknown key id appears);
  - RSA and EC algorithms only, never `none` or HMAC;
  - `iss`, exactly the provider's configured issuer;
  - `aud`, which must hold the client id (with `azp` checked when there's more than one audience);
  - `exp` and `iat`, allowing two minutes of clock skew;
  - the `nonce`.

  (changed in the build) Nimbus 11.38.2 reads `azp` without comparing it, and never reads `nbf`, so Healdan checks both itself: an `azp` that isn't the client id is refused, so is more than one audience with no `azp`, and so is an `nbf` more than two minutes ahead. The identity claim must be a non-blank string. An encrypted ID token is refused. On a refresh only, an unsigned ID token is ignored rather than accepted (§9).
- **HTTPS only.** The issuer and every endpoint are reached over HTTPS, with the JVM's certificate checks (M1's validating client). The one exception is plain HTTP when the smoke overlay sets `HEALDAN_SIGNIN_ALLOW_HTTP`, which production never does.
  - (changed in the build: rulings R11 and R13.) "Every endpoint" is each one the discovery document names, the authorisation, token and keys endpoints, checked when it's fetched and at each use. Provider calls go through Nimbus's own HTTP client, as the plan has it, not M1's validating client. Its certificate and host name checks are the JVM's, the same ones, but LogicalDOC's proxy settings don't apply to it: an install that reaches the provider through a proxy sets the JVM's `https.proxyHost` and `https.proxyPort`, as Known Limitations says. Token requests don't follow redirects, as they carry the secret.
  - (changed in the build: ruling R29.) At the token endpoint, Healdan sends the client secret as `client_secret_basic` when the discovery document lists it or lists no methods, else as `client_secret_post` when it lists that. Otherwise it sends Basic, and Check reports the problem (§10.3).
  - (changed in the build: ruling R32, which reverses R29's order.) It sends `client_secret_post` when the discovery document lists it; otherwise `client_secret_basic` when that's listed or no methods are; otherwise Basic, and Check reports the problem. Nimbus form-encodes a Basic header's client id and secret (RFC 6749 §2.3.1), so a secret with a `~`, as Entra's generated secrets have, would go out as `%7E`, and whether Entra decodes it is unproven. Entra, Keycloak and Authentik all list post.
- **No dependence on the provider to run.** Discovery and keys are fetched when first needed, never at startup. A provider that can't be reached refuses only its own sign-ins, and never revokes anything (§7.5, §9).
  - (changed in the build: from the final review.) A discovery document that couldn't be fetched is remembered for 30 seconds per provider: meanwhile a start, an exchange, a refresh and Check fail at once, the same way, without a call. During an outage an unthrottled start would otherwise hold a Tomcat worker for up to 20 seconds each. Saving the provider in the tab forgets it.
- **The browser's flow** has state, nonce and PKCE, and is bound to the browser that started it (§5.2). One callback per provider (§5.2) stops a response meant for one provider being taken for another's.
- **The helper's flow** accepts only a loopback redirect, uses PKCE, and its code lives 60 seconds and works once (§6.2).
- **Secrets at rest.**
  - Client secrets and refresh tokens are encrypted with AES-GCM, under a key Healdan makes the first time it needs one. The key lives in the configuration folder (the `conf` volume, beside `context.properties`), readable only by the app's user. So a database dump on its own carries neither. (changed in the build: plan correction 8. `ContextProperties` doesn't say where it was loaded from, so the key file is the system property `healdan.secretKey`, which `docker/setenv.sh` sets to `/opt/logicaldoc/conf/healdan-secret.key`, else `~/logicaldoc/conf/healdan-secret.key`. `SecretBox` makes it with mode 600 and 32 random bytes, and a sealed value is `v1:` and the base64 of the IV, ciphertext and tag.)
  - **If the key is lost,** a secret reads as unset, the Check says so, and the admin enters it again. Refresh tokens can't be read, so their device tokens are revoked at their next check, and helpers sign in again.
  - The Backup and Restore page says to back the key up with the database.
- **Never logged:** tokens, codes, secrets, ID tokens, verifiers or states. Logged: the provider, the account, the outcome and group changes.
- **No open redirects.** A sign-in ends on a page of Healdan's own address, or at the helper's loopback address, nowhere else.
- **Session fixation.** Each sign-in makes a new CE session and gives the HTTP session a new id (§5.2).
- **Login CSRF.** Starting a sign-in is harmless. A callback that doesn't come back to the browser that started it is refused (§5.2).

## 14. Code organisation (calls)

- **The library:** the Nimbus OAuth 2.0 SDK with OpenID Connect extensions (`com.nimbusds:oauth2-oidc-sdk`) and `com.nimbusds:nimbus-jose-jwt`, by Connect2id. They bring `json-smart`, `content-type`, `lang-tag` and `jcip-annotations`.
  - All are under the Apache License 2.0, which is compatible with the LGPL-3.0 that Healdan's code is under (roadmap principle 8).
  - Spring Security's own OAuth2 support is built on `nimbus-jose-jwt`.
  - Their versions are pinned in `healdan-core`'s POM, as Flyway's are, at their current releases when the plan is written. (changed in the build: `oauth2-oidc-sdk` 11.38.2 and `nimbus-jose-jwt` 10.10, with `json-smart` and its `accessors-smart`, `content-type`, `lang-tag` and `jcip-annotations`: seven jars, about 1.9 MB, in the WAR. `asm` is excluded, as the WAR already ships it.)
  - **Why not Spring Security's OAuth2 client:** its providers are fixed when Spring starts, so the Sign-In tab couldn't change them without a restart; the helper's flow would still need Healdan's own code; and it would add `<oauth2-login>` and its beans to upstream's `context-security.xml`. **Why not none:** the token checks are security-critical code Healdan shouldn't own.
- **`healdan-core`**, in `com.logicaldoc.healdan.signin`, holds:
  - the providers' settings and presets, and their discovery cache;
  - the flow's state and the ID token checks;
  - accounts (linking, making, groups);
  - the authenticator, the re-check and the encryption of secrets.

  Its `plugin.xml` declares the authenticator with an extension id of its own (a duplicate id makes JPF drop the whole plugin).

  (changed in the build) The package also holds the user listener, which `plugin.xml` declares too (`healdanSignInPasswords`, beside `healdanSignInAuthentication`), the one-time codes and pending sign-ins, unlinking and the `admin` alert. `PublicAddress` moved to `healdan-core`'s `com.logicaldoc.healdan.web` (§5.2), beside the Sign-In tab's RPCs in `HealdanSettingsServiceImpl`.
- **`healdan-api`:** `SignInResource` for the pages, the exchange in `AuthResource`, and the providers in `ServerResource`. (changed in the build: and the re-check, called from `TokenFilter` before a token's session is used.)
- **`logicaldoc-gui`:**
  - `HealdanSignInButtons`, where the `login` module can reach it (in `common.client.healdan`, as `HealdanCredits` is);
  - the Sign-In tab in `frontend.client.healdan.settings`, with its calls on Healdan's settings service. (changed in the build: `SignInTab` and `ProviderDialog`, with eleven RPCs on `HealdanSettingsService`, all for admins only.)
- **`clients/helper/`:**
  - the browser sign-in in `Healdan.Helper.Core` (PKCE, the loopback listener, and opening the browser behind a seam, so tests replace it);
  - the sign-in window's buttons in the app;
  - released as Healdan Helper 0.6.0.
- **Pull requests** (the plan settles them):
  - A: the server side, with its tests and the smoke step;
  - B: the web page, the sign-in hook and the Sign-In tab;
  - C: the helper;
  - D: the records (CLAUDE.md, the capability map, this spec and the roadmap).

  Each carries its docs. (changed in the plan and the build: five, A to E, as the Status says. A is the server's core; B the endpoints, the re-check, unlinking, the alert and the stand-in provider, with Task 7b; C the sign-in page, the Sign-In tab and the Install pages, with Tasks 9b and 13c; D the helper; E the records.)

## 15. Upstream files, fork-owned files and docs pages

### 15.1 Upstream files

| File | Edit | Why |
|---|---|---|
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/login/client/LoginPanel.java` | One line in `initGUI` handing the page's panels to `HealdanSignInButtons`, which adds the provider buttons and folds the password form behind its link. `MobileLoginPanel` runs the same method. (changed in the build: with its `// Healdan:` comment, and an `import` beside `HealdanCredits`', as the class is in another package. The `login` module already inherits what the class uses, so no `.gwt.xml` changes) | §5.1 |
| `logicaldoc-core/src/main/java/com/logicaldoc/core/security/user/UserSource.java` | `OIDC` appended, after `SAML`, so the stored numbers of the others don't move | §7.3: CE skips its password rules for any source but `DEFAULT` |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/security/user/UsersPanel.java` | One `case` in the Source column's `switch`, for `OIDC` | Otherwise the Users list shows a provider account as `DEFAULT` |
| `logicaldoc-core/src/test/java/com/logicaldoc/core/security/user/UserSourceTest.java` | New: pins each value's stored number (`DEFAULT` 0, `LDAP` 1, `SAML` 2, `OIDC` 3) | Covers the edit above; in CI's `UPSTREAM_TESTS`, so a sync that moves them fails |

**`context-security.xml` isn't edited:** `/api/**` is already outside Spring Security (§3.1).

### 15.2 Fork-owned files

- `healdan-core`: the `signin` package, `plugin.xml`, `V6__signin.sql` for both databases, the seed steps, the POM, and a `UserListener` for §7.3; (changed in the build: ruling R35) and `V7__refresh_token_width.sql` for both;
  - (changed in the build) also `web/PublicAddress` (moved from `healdan-api`'s `AddinResource`, §5.2), `web/HealdanSettingsServiceImpl` and `web/SignInBeans` (the Sign-In tab's RPCs), `HealdanStartup` (registers the `admin` alert), `token/ApiTokens` and `token/ApiToken` (the provider, the sealed refresh token and the re-check), `token/HealdanTokenAuthenticator` (leaves `hd_signin_` keys to `SignInAuthenticator`), `setting/HealdanSettings` (deleting and listing keys), and, in the test-jar, `support/FakeProvider`, `support/FakeTls` and `signin/SignInTestHooks`;
- `healdan-api`: `SignInResource`, `AuthResource`, `ServerResource`, `openapi.yaml`, `RouteCoverageTest` and `Contract` (both leave `/api/signin/` out), and their tests;
  - (changed in the build) also `AddinResource` (uses `PublicAddress`), `TokenFilter` (the re-check, §9), the models `SignIn`, `ServerInfo` and `ProviderInfo`, the page `signin/done.html`, and the POM (the tests' `healdan.secretKey` under `target/`);
- `logicaldoc-gui`'s Healdan packages: `HealdanSignInButtons`, the Sign-In tab, and the settings bean and service;
  - (changed in the build: ruling R26) and `HealdanSignInButtonsTest`, which pins the refusal messages. It tests fork-owned code, as M3.6's `HealdanOpenInAppTest` does, so it isn't an upstream edit, but it's in `UPSTREAM_TESTS`, as tests in `logicaldoc-gui` run in CI only when named there;
- `logicaldoc-core`'s `HealdanThrottle` (M2's): refusing a provider account's API keys (§7.3; changed in the build), and logging a database's message as text (from the final review), with `SessionManagerThrottleTest`, M2's, in `UPSTREAM_TESTS`;
- `clients/helper/`: the core, the sign-in window, and its tests;
  - (changed in the build) including `Api/Models.cs`, whose password body is tagged `[Schema("PasswordSignIn")]` now that `SignIn` is a `oneOf`, for the helper's contract test;
- `docker/`: the test overlay with the stand-in provider, `docker/tests/test-signin.sh` (new), and a step in `docker/smoke-test.sh`;
  - (changed in the build) the overlay is `docker/tests/compose.signin.yaml`, with Keycloak's realm in `docker/tests/keycloak/healdan-realm.json`; `docker/setenv.sh` sets `-Dhealdan.secretKey` (§13); and `smoke-test.sh` runs step 5e and passes step 5d the helper's sign-in variables (§16.1);
- `.github/workflows/build-logicaldoc.yml`: `UserSourceTest` in `UPSTREAM_TESTS`; (changed in the build) and `HealdanSignInButtonsTest`;
- (changed in the build) `.github/workflows/docker-image.yml`: its comments on step 5d name both of the helper's stack tests;
- `CLAUDE.md`, `docs/ROADMAP.md` and `docs/reference/logicaldoc-ce-capability-map.md` (§5.4).

### 15.3 Docs pages (the docs site, `site/content/`)

**New:**

- `install/sign-in/`, a section of the Install manual, with a card on `install/_index.md`:
  - `_index.md`: how provider sign-in works; break glass, with what works during a provider outage and the advice for `admin`'s password (§7.5); what provider accounts can't do (no password routes); and taking a provider off an instance (§7.7);
  - `entra-id.md`:
    - the app registration and its redirect address;
    - the client secret and its expiry;
    - the groups claim, set to groups assigned to the application, or app roles;
    - the optional `email`, `given_name` and `family_name` claims;
    - Assignment Required;
    - the Sign-In tab's Entra preset;
  - `authentik.md`:
    - the provider and the application, and the redirect address;
    - a signing key;
    - the `offline_access` scope mapping;
    - groups;
    - stopping users changing their email before turning linking by email on;
    - the Sign-In tab's Authentik preset;
  - `other-providers.md`: what a provider needs, and the claims to set.
  - (changed in the build) `_index.md` also covers what to have before starting, setting a provider up and trying it before telling anyone, the re-check, the secret key, and what an admin looks for when a sign-in is refused. `entra-id.md` also covers where the four permissions are added before granting admin consent, groups between sign-ins when Entra's refreshed ID tokens are unsigned (§9), what to do when the client secret expires, and Entra's refusals. `authentik.md` says to check that people can't change their email (§3.2). `other-providers.md` names `client_secret_basic` or `client_secret_post`, whichever the discovery document lists (§13), and says refreshed ID tokens should carry the groups claim. The section sits at weight 6, so the Install pages after it moved down one.

**Changed:**

- `admin/healdan-settings/index.md`: the Sign-In tab, the group map and its gate, linking by email, readying an account before a first sign-in, the linked accounts with Unlink and Unlink Everyone, turning a provider off, the emergency `admin` and its alert, and the re-check. (changed in the build: with pictures of the Sign-In tab and a provider's window, and `healdan-settings.png` taken again with the Sign-In tab in its tab bar.)
- (changed in the build) `admin/system/outgoing-email.md`, the Outgoing Email page: the `admin` alert (`signin.adminalert`) and the temporary password an unlinked account gets (`psw.rec1`).
- `user/getting-started/signing-in/index.md`: the provider's button, the password link, and what a refusal means. (changed in the build: with a picture of the page with a provider, `sign-in-provider.png`, and Signing Out's words on the provider's session.)
- `user/office/installing-the-helper/`: signing in through the browser. (changed in the build: without a picture of the window with provider buttons, which nobody has looked at by hand yet.)
- `install/backup-and-restore.md`: the key in the configuration folder (§13).
- `install/known-limitations.md`:
  - provider accounts have no password for WebDAV, CMIS, SOAP or REST;
  - provider accounts can't use LogicalDOC's API keys (changed in the build, §7.3);
  - web sessions aren't re-checked with the provider;
  - an account has one identity;
  - Sign Out doesn't sign out of the provider;
  - (changed in the build) the `admin` alert needs outgoing email (§7.5), and providers are reached without LogicalDOC's proxy settings (§13).
- `developer/api.md`: the providers in `GET /server`, the helper's code exchange, and the sign-in pages; and that provider accounts can't use API keys (changed in the build, §7.3).
- `developer/desktop-clients.md`: the helper's browser sign-in.
- `developer/tests.md` and `continuous-integration.md`: the fake and stand-in providers, and the new smoke step.
- `developer/how-healdan-extends-logicaldoc.md`: the upstream edits in §15.1.

The screenshots are of the demo firm (CLAUDE.md, "The demo overlay"). (changed in the build: the demo stack had a provider set up by hand, with the Entra preset, against a Keycloak that no picture shows.)

## 16. Verification

### 16.1 Automated

**`healdan-core` and `healdan-api`, against a fake provider in the test.** A small JDK HTTP server serves discovery, keys and a token endpoint, and Nimbus signs its tokens. The tests cover:

- **the flow:** the state, the browser's cookie, the nonce and PKCE; an expired or reused state; a callback naming another provider;
- **ID tokens:** a bad signature; the wrong issuer or audience; expired; `none` and HMAC refused; a new key making Healdan fetch the keys again;
- **accounts:** an identity already linked; the gate; linking by email (switch off, no email, two matches, `admin`, already linked); a new account; a username already taken or containing `>`;
- **groups:** added and removed, groups outside the map left alone, a deleted group ignored;
- **passwords:** a provider account's password refused on every route (the sign-in form, SOAP, REST, WebDAV and CMIS Basic, the API), and a password refused on every path that sets one (§7.3);
- **API keys** (changed in the build): linking deletes an account's keys and closes the sessions they opened; a provider account's key refused, uncounted, on every route; unlinked accounts keep theirs (§7.3);
- **the helper's code:** used once only; expired after 60 seconds; a wrong verifier; a wrong address; a non-loopback address refused;
- **the re-check:** accepted (groups updated), refused (token revoked), unreachable (token kept, and not retried before 15 minutes), the 8-hour rule, and a provider turned off (not contacted, token kept);
- **break glass:** Healdan starts with every provider unreachable; `admin` signs in meanwhile; the alert is sent only while it's on, a provider is on and outgoing email is set up, and not for other accounts (§7.5);
- **unlinking:** the account goes back to `DEFAULT` with an expired temporary password, keeps its groups and everything it held, and loses its device tokens; its link is kept as a record; signing in through the provider again links it again; Unlink Everyone; Remove refused while a provider has linked accounts (§7.7);
- **secrets:** stored encrypted, and a lost key handled (§13);
- **the API:** `GET /server`'s providers, and the exchange, checked against `openapi.yaml` as every API test is (M2 §4.6).

**`UserSourceTest`** in `logicaldoc-core`, in `UPSTREAM_TESTS` (§15.1).

**On the Docker stack.** A test overlay adds a stand-in OIDC provider in a container, and a smoke-only seed points Healdan at it from environment variables, as the smoke fixture does. A new smoke step, `docker/tests/test-signin.sh`:

1. runs the web page's flow with `curl` and a cookie jar, and checks the web page opens signed in;
2. runs the helper's flow with a pretend loopback address (`curl` reads the redirect), exchanges the code, and calls `/me`;
3. checks the linked account's password is refused;
4. disables the person at the stand-in and checks the next re-check revokes the token.

CI runs it on amd64 and arm64, as it runs the rest of the smoke test. The plan picks the stand-in: open source, with amd64 and arm64 images, a groups claim, refresh tokens and a way to disable a user. The candidates are Keycloak (Apache 2.0), Dex (Apache 2.0) and mock-oauth2-server (MIT).

(changed in the build: plan correction 9, and rulings R23 and R24.)
- **The stand-in is Keycloak 26.7.5,** the one candidate with all four. `docker/tests/compose.signin.yaml` runs it, with a health check, and publishes it on `127.0.0.1:18180` only (`SMOKE_KEYCLOAK_PORT`; 18080 is the smoke app's own port). It names itself `http://keycloak:8080`, as the app reaches it, and the test's `curl` uses `--connect-to`. The `smoke-signin` seed adds provider `smoke`, maps `healdan-users` to a group `Smoke SSO`, makes the local account `sso-user` for the first sign-in to link by email, and sets `signin.recheck.minutes` to 0. (changed in the build: from the final review. It runs only with the smoke overlay's `HEALDAN_SMOKE_FIXTURE=true` beside its own three variables, tying it to the smoke test's stack.)
- **The realm** keeps `offline_access` an optional client scope, Keycloak's default, rather than a default one, since the helper asks for it and the web page doesn't need it. Its users have emails and the realm's default roles, or Keycloak would stop their sign-in at a form.
- **Step 5e runs `test-signin.sh`:** the local account signs in with its password first; then the web page's flow links it by email and opens the web page; its local password is then refused and the token from before is revoked; the gate refuses an outsider; the helper's flow hands over a code, and its exchange gives a token that works; and disabling the person at Keycloak revokes it at the next request. Keycloak answers that refresh with `invalid_grant`. The step takes about 3 seconds, and Keycloak starts in 14 to 19 seconds alongside the app.

**The helper**, in CI on Ubuntu and macOS, with fakes: PKCE, the loopback listener (one request, the state, the timeout), the browser seam, the exchange, and the window's buttons from `GET /server`. Its `Stack` test signs in through the stand-in provider, with a fake browser following the redirects. (changed in the build: `StackProviderSignInTests` signs in a realm user of its own, `sso-helper2`, posting Keycloak's form with an `HttpClient` and making the loopback request. Smoke step 5d passes it the provider, the user and Keycloak's host port, and needs both of the helper's stack tests to pass. The window's tests run on macOS only, as the helper's other window tests do.)

**The GUI** has no unit tests. The sign-in page and the Sign-In tab are checked on a stack. (changed in the build: ruling R25. `HealdanSignInButtonsTest` pins the sign-in page's refusal messages. The two stack checks ran once, after the Sign-In tab, on one image, as Task 13c, in a browser against Keycloak: every check passed after three layout fixes, and the password routes in `logicaldoc-webapp` (§7.3) and the impersonators (§7.3) were checked there too. CE's two-factor step can't be reached, as CE has no server side for it.)

### 16.2 Manual acceptance (owner)

On the owner's server over HTTPS, with Healdan Helper 0.6.0 from its signed release (owner, 2026-11-01):

1. **Entra setup.** Following the Install pages, set up Entra ID in the Sign-In tab, mapping a test group to a Healdan group. Check passes.
2. **Your account.**
   - Sign in to the web page with the button. Your day-to-day account is linked by email, with its history, check-outs and My Matters.
   - Change Password is gone, and the password link refuses that username's password.
3. **Groups.**
   - Take a test user out of one mapped group in Entra: their next sign-in takes them out of the Healdan group.
   - Take them out of every mapped group: the sign-in is refused.
4. **The helper.**
   - It signs in through the browser, and you open, edit and save a document in Word.
   - Disable the test user in Entra. Within 8 hours, or at once after a forced check, the helper asks them to sign in, and the sign-in is refused.
5. **Break glass.** Turn the `admin` alert on, then break the client secret on purpose in the Sign-In tab. The button fails with a message, and `admin` still signs in with its password and puts it right. The alert arrives by email.
6. **Unlinking.** Unlink the test user. They sign in with the temporary password, change it, and find their documents and favourites as before. Signing in through the provider again links them again.
7. **Authentik.** Rows 1 to 3 on your own Authentik, where a new test user gets a new account.
8. **Sign Out** comes back to the sign-in page, and doesn't sign straight back in.

(changed in the build) Rows the build left questions for:
- **Row 1:** Healdan sends the client secret the way the discovery document lists (§13). Entra's lists `client_secret_post` as well as `client_secret_basic`, so post goes out (ruling R32), and the first sign-in shows the tenant accepts it.
- **Row 4:** whether Entra signs the ID tokens it sends with a refresh. If they're unsigned, as Microsoft documents, a forced check is accepted without updating groups, which then follow Entra at sign-in only (§9); a disabled person is still refused, with `invalid_grant`. This row is also the first real browser to take the helper's sign-in (§6.2), and the first look at the helper's window with provider buttons, which Installing the Helper has no picture of.
- **Row 5:** with the secret broken, a refresh should get `invalid_client`, which counts as can't be reached (§9), so helpers stay signed in and the log has an error naming the provider.

(walkthrough, 2026-11-02) The owner ran the rows on their server over HTTPS, upgraded to master with its new key file backed up, with their own Authentik and Healdan Helper 0.6.0-rc1, signed and notarised. Entra ID wasn't set up, so the rows ran on Authentik:
- **Row 1:** the provider added with the Authentik preset and Link by Email on, and Check passed. The first sign-in then failed with the `provider` message: the client secret saved wasn't the provider's, and Authentik's token endpoint answered `invalid_client`, which the log named. Check doesn't test the secret (a follow-up). With the right one pasted, it worked.
- **Row 7 (rows 1 to 3 on Authentik):** a new test user got a new provider account at its first sign-in, in the groups its Authentik groups map to.
- **Row 2:** the owner's own account was linked by email, with its documents, history and My Matters as before. Change Password was gone, and the password link refused its old password. The link took it out of a mapped group it had been given by hand (§8's sync, as designed; the Admin Guide now says so). An earlier attempt, before the owner was in the application's Authentik binding, was refused by Authentik and never reached Healdan.
- **Row 3:** taken out of one mapped group, the test user lost its Healdan group at the next sign-in; out of every mapped group, it was refused with the `no-group` message and taken out of the rest.
- **Row 4:** the helper signed in through Authentik in Safari, and opened, edited and saved a document in Word. To sign in as another person, the owner first signed out of Authentik in Safari, as the browser's session decides who signs in (Installing the Helper now says so). With the test user deactivated in Authentik and the app restarted to force a check, Authentik answered `invalid_grant`, Healdan revoked the device token, and the helper showed its Sign In window, with no word of why (a follow-up). Authentik then refused the user's sign-in.
- **Row 5:** with the alert on, `admin`'s password sign-in emailed the recipient, the owner. `admin`'s own address was LogicalDOC's placeholder, so the owner named their own username (the Admin Guide now says so). The wrong secret of row 1 had already shown the button failing and `admin` putting it right; with the secret broken again and the app restarted, the helper's check got `invalid_client`, logged as an error naming the provider, and the helper stayed signed in and opened a document.
- **Row 6:** unlinking revoked the owner's helper and emailed a temporary password; signing in with it asked for a new one, the documents and favourites were as before, and the next sign-in through Authentik linked the account again.
- **Row 8:** Sign Out came back to the sign-in page and stayed there; the button then signed straight back in through Authentik's session, as expected.
- **Not run:** Entra ID's rows, so whether Entra accepts `client_secret_post` (row 1) and signs refreshed ID tokens (row 4) stays open. The owner deferred them for lack of resources, taking Entra ID as untested but likely to work, and shipped it as experimental (owner, 2026-11-02); the Install manual's Microsoft Entra ID page and Known Limitations say so. Safari's step from HTTPS to `http://127.0.0.1` went through, with no warning the owner reported.

## 17. Left to the builder

- Every path that sets a password, and the check on each (§7.3).
- Whether the provider sign-in's authenticator is new or `HealdanTokenAuthenticator` extended (§7.6).
- The browser cookie's name, the pending sign-ins' store, and the encryption key's file name (§5.2, §13).
- The exact problem codes and the wording of the messages (§5.3), within §5.3's table.
- The Sign-In tab's layout and the presets' labels (§10.2).
- The `openapi.yaml` shapes beyond §11.
- The stand-in provider (§16.1), and how its address works for both the app's container and the test's `curl`.
- Nimbus's versions (§14).

(changed in the build: what the builder chose.) A new `SignInAuthenticator` (§7.6); the cookie `hd_signin`, pending sign-ins in memory and the key file `healdan-secret.key` (§5.2, §13); the messages in the plan's Global Constraints (§5.3); Keycloak 26.7.5 as the stand-in, reached as `keycloak:8080` from both (§16.1); Nimbus 11.38.2 and 10.10 (§14). The password paths and their checks are in §7.3.

## 18. Decisions made in this session

**Owner (2026-11-01):**

- **Passwords.** Once a provider is on, only accounts that aren't linked keep a password: `admin` and any local account an admin keeps on purpose. Provider accounts have no password on any route, so they don't use WebDAV, CMIS, SOAP or REST with one (§7.3).
- **Linking.** A first sign-in links to an existing account by email, where the provider's emails can be trusted: on in the Entra preset, off in Authentik's. Never `admin`, never an account already linked. Otherwise a new account (§7.2).
- **Groups.** An explicit map per provider, synced at every sign-in, adding and removing only mapped groups. A sign-in that maps to no Healdan group is refused. Mapping to `admin` is allowed (§8).
- **Leavers.** Healdan keeps the provider's refresh token for a helper sign-in and checks with the provider when the token's session reopens, and at least every 8 hours. A refusal revokes the token; an unreachable provider signs nobody out (§9).
- **Settings.** A Sign-In tab in Healdan Settings, in `hd_setting`, with the secret write-only (§10).
- **The sign-in page.** A button per provider, with the password form behind a link. Both labels are settings, not necessarily "Microsoft" (§5.1, §10.1).
- **Acceptance.** Entra ID in the owner's tenant, and Authentik on the owner's own instance (§16.2).
- **The approach.** Healdan's own endpoints, with Nimbus's libraries (§14).
- **Unlinking** (after the written spec, from the owner's questions in a side conversation). An admin can unlink one account, or every account of a provider. The account goes back to a temporary, expired password and keeps everything it held. Turning a provider off unlinks nothing (§7.7).
- **The `admin` alert** (the same conversation). Optional: a system-wide setting an admin turns on, sent by email only, and only when outgoing email is set up (§7.5).
- **The helper's release** (2026-11-01, during the build). M4's helper is Healdan Helper 0.6.0, as M3.6 (PDFs in CoffeePDF) took 0.5.0 and released first (§2.1, §16.2).
- **The refusals don't name the provider** (2026-11-02, during the build). The `no-group` message is "Your account doesn't give you access to Healdan. Ask an administrator." and the `provider` message, and every code that falls back to it, "The sign-in provider couldn't sign you in. Try again, or ask an administrator." The other refusals' wording stays (§5.3).
- **LogicalDOC's API keys** (2026-11-01, during the build: "Delete on link, refuse"). Linking deletes an account's API keys, and a key sign-in for a provider account is refused on every route, as a key would be a second way in that skips the provider. Unlinked accounts, `admin` included, keep theirs. The web page can still make a key for a provider account, which never works, and the docs say so. No new upstream edit (§7.3).

**Calls (approved section by section; open to the owner's review of this spec):**

- the helper signs in through the system browser with a loopback redirect, and the server brokers the provider's flow and hands the helper a one-time code for a device token; the add-ins never sign in (§1, §6);
- the web page's flow under `/api/signin/`, outside Spring Security, ending in the same `ldoc-sid` cookie as the password form, with no edit to `context-security.xml` (§3.1, §5.2);
- one callback address per provider (§5.2; refined while writing the spec, from one shared address);
- the identity is Entra's `oid` and Authentik's `sub`; one identity per account (§7.1);
- a new `UserSource.OIDC`, the password removed, and a listener that refuses to set one (§7.3);
- provider sign-ins open their CE session through the chain with a one-time key, as M2's tokens do (§7.6);
- Entra's group overage refused rather than looked up in Microsoft Graph (§8);
- web sessions not re-checked; Sign Out doesn't sign out of the provider (§2.2, §9);
- break glass: Healdan runs without the provider, an outage never revokes anything, and a provider turned off keeps its helpers' tokens without contacting it (§7.5, §9, §13);
- a provider can be removed only once it has no linked accounts, and its links stay as history (§7.7, §10.3);
- secrets encrypted at rest with a key in the configuration folder (§13);
- the group map in its own table, the rest in `hd_setting` (§10, §12);
- `UserSourceTest` guarding the stored numbers (§15.1);
- a fake provider in the tests, and a stand-in in a container on the Docker stack (§16.1).
