---
title: API
weight: 8
---

Healdan has a JSON API for clients such as Office add-ins. The same server runs it as runs the web page, under
`/api` at your install's address.

{{< callout type="warning" >}}
**No compatibility promises yet.** Until Healdan's initial
[roadmap](https://github.com/Healdan-Project/healdan/blob/master/docs/ROADMAP.md) is complete, any endpoint can be
renamed or reshaped when one of Healdan's own clients needs it to. Build against the description file of the
server you're talking to.
{{< /callout >}}

## The Description File and the Docs Page

- **`/api/v1/openapi.yaml`** describes every endpoint, in OpenAPI 3.1. It's the contract: Healdan's tests check
  every answer an endpoint gives them against it, and fail on an endpoint it leaves out. Generate your client's
  types from it. Its paths start at `/api`, so `/v1/server` in the file is `/api/v1/server`.
- **`/api/docs/`** shows the file as a page you can browse, with **Try it out** on each endpoint. Healdan serves the
  page itself, so it loads nothing from the internet. To call an endpoint that needs a sign-in, sign in with
  `POST /v1/auth/tokens` first, then paste the token into **Authorize**.

Every path below starts with `/api/v1`.

## Two Ways In

The API signs a request in one of two ways:

- **A device token,** for a client on a computer, such as the Healdan Helper. The client sends it as
  `Authorization: Bearer`. Most of this page is about tokens. The Office add-ins and the LibreOffice extension hold
  none: they work through the helper.
- **The browser's web session,** for Healdan's own pages, which the server serves from its own address. The page
  sends no token: the browser sends its session cookie, and the session is the one the sign-in page opened, the
  same as the web page's.

A request with a Bearer `Authorization` header goes by that header alone, and never by its cookies. Another scheme's,
such as the Basic credentials a browser sends to a proxy in front of Healdan, signs nothing in. The web session is
let in only when all of these hold:

1. **No Bearer `Authorization` header.**
2. **`Sec-Fetch-Site: same-origin`.** Browsers send this Fetch Metadata header with every request, and say
   `same-origin` only when a page from the same origin made it. A request without the header, from an old browser
   or from no browser, isn't let in.
3. **An `Origin`, when there is one, is the server's own.** That's the origin of the public address
   (`LOGICALDOC_PUBLIC_URL`) when Healdan has one it can use (see [Sign-In Pages](#sign-in-pages)), or the scheme
   and `Host` the request came to, so another name for the server, such as its address on the office network, works
   too. A port that is the scheme's own, 443 for `https` and 80 for `http`, is the same whether it's written or not.
   Behind a proxy that rewrites `Host`, set the public address.
4. **A change sends `X-Healdan-Client: web`.** That's every `POST`, `PATCH`, `PUT` and `DELETE`, in any case. Another site's page
   can't add a header to a request to Healdan without a CORS preflight, and the API never allows one with cookies
   (see [Calling the API from Another Origin](#calling-the-api-from-another-origin)). A `GET` needs no header, so
   downloads, thumbnails and previews work as plain links and images. The method is the request's own: CXF's
   `_method` parameter and `X-HTTP-Method-Override` header are off, so a `GET` never becomes a change.
5. **The HTTP session holds an open web-page session.** Tomcat finds the HTTP session by its `JSESSIONID` cookie,
   which is HttpOnly, and the sign-in page keeps the web-page session's id in it. The API never takes that id from
   a `sid` parameter or header, or from the `ldoc-sid` cookie, though LogicalDOC's own routes do.

A same-origin request that breaks one of these rules is `403 web-session-refused`: signing in again wouldn't mend
it, so a page mustn't take it for a sign-out. One with no open session is `401 unauthenticated`, with no
`WWW-Authenticate` challenge. A request with no Bearer `Authorization` header that isn't a same-origin browser
request is answered as a client with no token is, `401` with `WWW-Authenticate: Bearer`.

- **The account is checked on every request,** as for a token: one disabled, expired or deleted since the sign-in
  is `401`, though its session is still open.
- **Every request renews the session,** so it stays open while the page is in use, and lapses after
  `session.timeout` without one (30 minutes as shipped). No one signs in on this route, so the
  [sign-in throttle](#the-sign-in-throttle) doesn't come into it.
- **There's no token.** `GET /auth/tokens` lists your tokens with none `current`, and
  `DELETE /auth/tokens/current` is `403 token-only`.
- **Signing out** is `DELETE /session`: it closes the web-page session and ends the HTTP session, so that browser
  is signed out of the web page too. A token gets `403 web-session-only` there.
- **The web session is no device** (see [Check-Outs and Devices](#check-outs-and-devices)).

## Signing In

A client signs in once with a username and password, and gets a device token. From then on it sends only the
token, as `Authorization: Bearer`. With `curl`, against a stack on your own machine:

```bash
BASE=http://127.0.0.1:8080        # or your install's address, such as https://docs.example.org
curl -s -X POST "$BASE/api/v1/auth/tokens" -H 'Content-Type: application/json' \
  -d '{"username": "pnair", "password": "…", "name": "Word on Office PC"}'
```

The answer is `201`:

```json
{
  "token": "hd_bm90LWEtcmVhbC1kZXZpY2UtdG9rZW4tZXhhbXBsZSE",
  "id": 12,
  "name": "Word on Office PC",
  "expiresAt": "2027-10-24T09:15:00Z",
  "user": { "id": 7, "username": "pnair", "name": "Priya Nair", "email": "priya.nair@ashcombe-reeve.example", "language": "en", "admin": false }
}
```

Then:

```bash
TOKEN=hd_…
curl -s "$BASE/api/v1/me" -H "Authorization: Bearer $TOKEN"
```

- **The token is shown only once,** in that answer. Keep it as you would a password, in the system's keychain for
  example. A client never needs to keep the password.
- **Only the token signs a client's request in.** The API ignores `sid`, Basic authentication and `X-API-KEY`, and
  takes the browser's session only from Healdan's own pages, on the rule in [Two Ways In](#two-ways-in), so another
  site's page can't make your browser call it with your sign-in.
- **The token works only in the API,** under `/api`. LogicalDOC's own services refuse it (see
  [below](#logicaldocs-web-services-and-api-keys)).
- **`GET /server`** needs no sign-in. A client calls it first, to check that it's talking to Healdan and to see how
  it can sign in (see [Signing In Through a Provider](#signing-in-through-a-provider)).
- **A refused sign-in:** `401 sign-in-failed` never says whether the username or the password was wrong, or that
  the account can't sign in. `401 password-expired` means the password has to be changed in the web page first.

## Signing In Through a Provider

When an administrator turns a sign-in provider on, such as Microsoft Entra ID, `GET /server` lists it:

```json
{
  "product": "Healdan",
  "version": "9.3.2",
  "apiVersion": "v1",
  "labels": { "matter": "Matter", "matters": "Matters" },
  "signIn": ["password", "provider"],
  "providers": [{ "id": "entra", "label": "Sign In with Microsoft" }],
  "passwordLabel": "Sign In with a Password"
}
```

- **`providers`** are the providers people can sign in through, in the order their buttons go in. With none on,
  it's empty and `signIn` is `["password"]`. A client shows a button for each, labelled with its `label`, and
  the password form behind a link labelled `passwordLabel`.
- **An account that signs in through a provider has no password.** A password sign-in for it gets
  `401 sign-in-failed`, in the same words as a wrong password, so the answer doesn't say the account is linked.

A desktop client, such as the Healdan Helper, signs in through the system browser and a loopback redirect
(RFC 8252), then exchanges a one-time code for its device token:

1. **It gets ready.** It makes a PKCE verifier and its `S256` challenge (RFC 7636), and a state of 1 to 128
   letters, digits, `-`, `.`, `_` and `~`. It listens on `http://127.0.0.1:<port>/` or `http://[::1]:<port>/`.
2. **It opens the system browser** at
   `/api/signin/<id>?client=helper&redirect_uri=http://127.0.0.1:<port>/&state=<state>&code_challenge=<challenge>&code_challenge_method=S256`.
   The `redirect_uri` must be exactly that: `http`, the host `127.0.0.1` or `[::1]`, a port, a path of `/` or
   none, and nothing else. Another address, a challenge that isn't 43 characters of base64url, a method other than
   `S256`, or a missing or wrong state is `400`, with a page, and the browser goes nowhere. Make a new verifier for
   each sign-in: a start whose challenge another sign-in already has, still waiting or with a code waiting, goes
   to its own `redirect_uri` with `error=expired`, so that another program on the computer that saw the start's
   address can't race a sign-in of its own to the client's port.
3. **The person signs in at the provider,** in that browser. Healdan sends the browser to the loopback address with
   `?code=<code>&state=<state>`, or with `?error=<code>&state=<state>` when it refuses, with one of the codes in
   [Sign-In Pages](#sign-in-pages). A provider that isn't on is `error=provider` straight away.
4. **It checks the state,** then sends the browser on to `/api/signin/done`, with `?error=<code>`
   after a refusal. The page tells the person to close the tab, or what went wrong. Only a `GET` of `/` at the
   loopback address is the answer: the Healdan Helper answers anything else, such as a browser's `/favicon.ico`,
   with `404` and goes on waiting.
5. **It exchanges the code** within 60 seconds:

   ```bash
   curl -s -X POST "$BASE/api/v1/auth/tokens" -H 'Content-Type: application/json' \
     -d '{"code": "…", "codeVerifier": "…", "redirectUri": "http://127.0.0.1:50123/",
          "name": "Healdan Helper on Office PC", "device": {"id": "…"}}'
   ```

   The answer is `201`, as for a password.

- **The code works once,** for 60 seconds, with the verifier of the challenge the sign-in started with and the same
  `redirectUri`, character for character, while its provider is still on. Anything else is `401 sign-in-failed`, as
  a wrong password is, and a wrong verifier or address, or a provider turned off since the code was issued, uses the
  code up. A sign-in the server can't keep, as when its secret key file is missing, is `500 server-error`, whose
  `detail` says so; the code is used up, and the helper starts again.
- **A body has a password or a code, never both.** One with fields of each is `422 validation`, and so is a code
  without its `codeVerifier` or `redirectUri` (`required`), or with a `codeVerifier` that isn't 43 to 128 letters,
  digits, `-`, `.`, `_` and `~`, as RFC 7636 has it (`invalid`). `name` and `device` are checked as for a password.
  All of these are checked before the code is used, so it still works with a body put right.
- **The token is a device token like any other,** with the same limits and the same routes to sign it out. Healdan
  keeps the provider's refresh token with it, encrypted, and never sends it to the client.

## Device Sign-Ins

Each token is one device's sign-in, such as Word on a laptop. `name` labels it, so people can tell their devices
apart; a blank name is "Unnamed device".

- **A token stops working** when it hasn't been used for 30 days, 365 days after sign-in at the latest
  (`expiresAt`), when it's revoked, and when its user is disabled or deleted. An administrator can change the 30
  and the 365 in [Healdan Settings](../../admin/healdan-settings/).
- **Changing your password signs out every device.** For a lost device, do that, then delete any LogicalDOC API
  keys you don't recognise: in the web page, under the person icon in the top bar, **Security → API Keys**,
  right-click a key and choose **Delete**. A device token can't make a key, but whoever had the device may have had
  the web page too, and a key outlasts a password change. Then ask an administrator to end your open sessions
  (see [Healdan Settings](../../admin/healdan-settings/#device-sign-ins)).
- **An account that signs in through a provider** has no password to change, and its API keys never sign in. For
  a lost device, ask an administrator to disable the account and enable it again, which signs out every device,
  and to end your open sessions.
- **Signing out** is `DELETE /auth/tokens/current`, which revokes the token making the request.
- **Your devices:** `GET /auth/tokens` lists your tokens that still work, newest first, never the tokens themselves.
  `current` marks the one making the request, and none on the web session. `DELETE /auth/tokens/{id}` revokes one
  of them.
- **A token that no longer works** gets `401 unauthenticated`. Ask for the password and sign in again.
- **A computer says who it is** with `device` in the sign-in: `{"id": "…"}`, 1 to 64 letters, digits and hyphens,
  such as a random UUID the client keeps. Send the same id on every sign-in from that computer. A bad id is
  `422 validation` with the field `device.id`. A token with no `device` belongs to no computer (see
  [Check-Outs and Devices](#check-outs-and-devices)).

## Errors

Errors are RFC 9457 problems, sent as `application/problem+json`:

```json
{
  "type": "https://healdan.com/problems/checked-out",
  "title": "Checked out",
  "status": 409,
  "detail": "Document #1234 is checked out to Jamie Clarke.",
  "code": "checked-out",
  "hold": { "kind": "checkout", "by": { "id": 8, "name": "Jamie Clarke" }, "since": "2026-10-23T15:00:00Z",
            "device": null, "heldOn": null }
}
```

Switch on `code`, which is stable. `detail` is an English sentence you can show as it is. `type` needn't resolve.

| Status | `code` | When |
|---|---|---|
| 400 | `bad-request` | The request is malformed: bad JSON, a field the server doesn't know, a bad `offset` or `limit` |
| 401 | `unauthenticated` | No token and no web session the API lets in, or a token that no longer works |
| 401 | `sign-in-failed` | A wrong username or password, or an account that can't sign in |
| 401 | `password-expired` | The password has to be changed in the web page first |
| 403 | `forbidden` | You can see the object, but may not do this to it |
| 403 | `token-only` | `DELETE /auth/tokens/current` on the web session, which has no token |
| 403 | `web-session-only` | `DELETE /session` with a token, which signs out with `DELETE /auth/tokens/current` |
| 403 | `web-session-refused` | A same-origin request on the web session that breaks one of its rules, such as a change without `X-Healdan-Client: web` (see [Two Ways In](#two-ways-in)). Signing in again doesn't mend it |
| 404 | `not-found` | It doesn't exist, **or you can't read it**: the two get the same answer |
| 404 | `no-preview` | `GET /documents/{docNumber}/preview` for a file with no preview: its type has none, or its conversion failed (see [Previews](#previews)); `GET /documents/{docNumber}/message/parts/{id}?inline=true` for an attachment that isn't a PDF or a picture |
| 404 | `not-an-email` | A `/documents/{docNumber}/message` route for a document that isn't an email, or whose file can't be read as one (see [Reading a Filed Email](#reading-a-filed-email)) |
| 409 | `checked-out`, `locked` | Someone else has the document checked out, or anyone has it locked with the web page's Lock action. The problem adds the `hold` |
| 409 | `held-elsewhere` | You have the document checked out on another of your devices. The problem adds the `hold` and `lastSaved` |
| 409 | `not-held` | A save with `ifHeld: true` found the document free: its check-out was released |
| 409 | `version-moved` | A save with `ifVersion` found the document at another version. The problem adds the `document` |
| 409 | `filing-rule` | The folder can't hold documents: a Store, a matter's top folder or a search folder |
| 409 | `already-filed` | The matter already holds the email's message, in a copy you can read. `detail` names its doc #, and the problem adds the copy as `document` |
| 409 | `filing-in-progress` | Another request was filing the same email into the matter, and it didn't end within 30 seconds. Nothing was kept: send the request again |
| 409 | `not-browsable` | A Store's contents were asked for. Find a matter by search, My Matters or Recent Matters instead |
| 409 | `refused` | Another of Healdan's rules refused it. `detail` says which |
| 413 | `too-large` | The file is over the upload limit |
| 422 | `validation` | Some values are wrong. `fields` lists each one, with its own `code` and `detail` |
| 429 | `too-many-attempts` | Too many failed sign-ins (see below) |
| 500 | `server-error` | Anything else. The detail never holds a stack trace |
| 503 | `storage-unreachable` | A file is in storage that can't be reached right now (cloud storage, or a local backend such as a network share), and isn't among the recently read files the server keeps. `detail` names the Store and can be shown as it is. Nothing is wrong with the file: ask again later |

Other client errors, such as `405` for a method an address doesn't take, keep their status, with the code
`bad-request`. A request whose connection broke, or stalled past the server's timeout, while the server read it or
answered it gets an empty `500`, not a problem.

`storage-unreachable` is sent before any of the file, never as a `200` that stops short. A thumbnail that can't be read
for the same reason is `404 not-found`, as one that isn't made yet is, so clients show their placeholder.

## Lists and Paging

Lists take `offset` (0 to begin with) and `limit` (50 to begin with, from 1 to 200), and answer:

```json
{ "items": [ … ], "offset": 0, "limit": 50, "total": 123 }
```

- `total` is `null` for the `folders`, `matters` and `clients` searches, where counting would be slow. A page with
  fewer than `limit` items is the last. The `documents`, `emails` and `all` searches count, up to a point: see
  [Searching](#searching).
- Some lists, such as Recent Matters and your check-outs, come back whole, in the same shape, and take no paging
  parameters. The docs page says which.

## Conventions

- **Healdan's words.** Names say `store`, `matter`, `client`, `docNumber`, `legacyDocNumber`, `dateFiled` and
  `snapshot`, never LogicalDOC's `workspace` or `customId`. The API always says `matter`; `GET /server` gives the
  word your install shows people instead, such as Project.
- **Documents are addressed by doc #:** `/documents/1234` is document #1234.
- **Versions use Healdan's numbering:** `2.0` is version 2, and `2.1` is a snapshot on its timeline. A document's
  `version` moves on every save, and on a change of properties, a move or a rename too, so it can be ahead of
  `fileVersion`, the save the file comes from. A save's new number counts from `version`: a document saved as 1.0
  whose properties then changed is at `version` 1.1, and its next snapshot is 1.2. In the timeline, a row whose
  `fileVersion` isn't its `version` is one of those changes, not a save.
- **Every document, folder and matter carries `type`:** `document`, `folder` or `matter`. Folder listings and
  search results mix them, so switch on it.
- **A document's or folder's `matter` names its client,** as `{ "id", "display", "client": { "id", "number",
  "name" } }`, so a list can show the client beside the matter without asking for each one. An older server sends
  only `id` and `display`, so a client that may meet one reads `client` as optional.
- **A document's `author`** is its Author property, as `{ "username": "pnair", "displayName": "Priya Nair" }`: the
  name to show, and the username the `author` filter takes. An Author who is no longer a user shows by the username
  kept with the property, as the Author counts do. It's `null` for a document with no Author. A folder's list and
  the search results read their page's Authors in one query.
- **Field names are camelCase.** A field with no value is `null`, never left out, but for a list's `facets`,
  which is there only when asked for, and a search's `totalAtLeast`, there only when `total` isn't. Timestamps are ISO 8601 in UTC, such as `2026-10-23T14:05:00Z`.
- **A doc ID is `#<doc #> v<version>`,** the form of the title bar and the filed files: `#1234 v2.1`.
- **Nothing caches the answers:** Healdan sends them with `no-store` in `Cache-Control`.

## What's There

| Area | Endpoints |
|---|---|
| The server and you | `GET /server`, `GET /me`, `GET /users?q=` for user fields such as Author |
| Signing in | `POST /auth/tokens`, `GET /auth/tokens`, `DELETE /auth/tokens/current`, `DELETE /auth/tokens/{id}`; `DELETE /session` for the web session |
| Matters and folders | `GET /matters/{id}`, `/matters/mine`, `/matters/recent`, `GET /clients/recent`, `PUT` and `DELETE /me/matters/{folderId}`, `GET /folders/{id}`, `/folders/{id}/children`, `/folders/recent`, `POST /folders`; `GET /stores` and `/stores/{id}/matters` for administrators only |
| Finding | `GET /search?scope=&q=&within=`, with filters and `facets`, `GET /suggest?q=` for the search box as someone types, `GET /lookup?q=` for a typed doc #, `GET /documents?legacyDocNumber=` |
| Filing | `GET /folders/{id}/form`, then `POST /documents` with the file |
| A document | `GET /documents/{docNumber}`, `/content`, `/preview`, `/versions`, `/thumbnail`, `GET` and `PATCH /properties`; `GET /documents/recent`, with `?kind=email` for emails alone; `PATCH /documents/{docNumber}` to rename, `POST /documents/{docNumber}/move` and `DELETE /documents/{docNumber}` |
| Email | `POST /emails/inspect` for what is in an `.eml`, `POST /emails` to file it with its attachments, `GET /emails?messageId=` for where its copies are; `GET /documents/{docNumber}/message` and its `/body`, `/parts/{id}` and `POST /attachments/{id}/file` for reading a filed email |
| Check-out and saves | `POST` and `DELETE /documents/{docNumber}/checkout`, `POST /documents/{docNumber}/versions`; `GET /me/checkouts` |
| Favorites | `GET` and `POST /me/favorites`, `DELETE /me/favorites/{id}`: the user's bookmarks, apart from My Matters |

The sections below say more about the parts a client needs to know before it starts.

## Finding

- **A typed doc #.** `GET /lookup?q=` takes what a person types and returns the document and version it names:
  `#1234`, `#1234.2`, `#1234v2`, `#1234.2.1`, and the title bar's `#1234 v2.1`, which may have spaces after the
  `#` and before the `v`. Encode the `#` as `%23`. Input that isn't a doc # is `400`; a document the caller can't
  read, a version it hasn't got, and a legacy shortcut (a document row from before Healdan that points to another
  document) are `404`, and `GET /documents/{docNumber}` of a shortcut's own doc # is the same `404`.
- **Suggestions as someone types.** `GET /suggest?q=` answers `{ "matters": [...], "documents": [...] }`: up to 5
  matters whose number or name matches `q` (as the `matters` search scope finds them), then up to 5 documents whose
  title has every word of `q`, the last as the start of a word. Each matter has `id`, `number`, `name` and its
  `client` (`number` and `name`); each document has `docNumber`, `name` and its `matter` (`id`, `number` and
  `display`). It shows only what the caller can read. A `q` of fewer than two characters answers two empty lists.
  Punctuation never makes an error: only the letters and digits of `q` reach the full-text engine. Documents come
  in the engine's score order, with a lift for those dated in the last year, and each is checked for the caller
  until 5 are found, so titles in matters the caller can't read don't crowd out their own. The engine hands back at
  most 1,000 hits per request: a caller who can read only titles ranked after 1,000 others gets fewer, or none,
  until the word is longer.
- **Searching inside a folder.** `GET /search` takes `within`, the id of a folder, usually a matter's workspace
  (its `folderId`) or one of its folders. The `documents` and `folders` scopes then return only what is inside it,
  at any depth, and a `#` lookup keeps its document only if it is in there. A Store as `within` is
  `409 not-browsable`; the other scopes with `within` are `400`.
- **Comments.** Every document has a Comments property (`comments`), free text of up to 4000 characters that comes
  after Subclass in the form and the properties. The `documents` search scope finds a document by its Comments: it
  matches those whose Comments contain every word of `q`, in any case, along with the full-text hits.

- **Where a property's value comes from.** `GET /documents/{docNumber}/properties` gives each property a `source`:
  `local` for a value set on the document, `inherited` with `inheritedFrom` naming the folder it comes from, or
  `default`. A `local` value also has `overrides`, the folder whose value it takes the place of, which the document
  would inherit if its own value went. It is null for a value no folder would supply (Author, Comments, a value no
  folder sets), for every value that isn't `local`, and in the filing form. A client shows a value as overridden only
  when `overrides` isn't null, and `PATCH` with `null` for it makes the property follow that folder again.

## Searching

`GET /search?q=` searches the `scope` given: `documents` (the default, every file but emails), `emails`, `all`,
`folders`, `matters` or `clients`. The hits are what the caller can read, best first.

- **Paging to the end.** The `documents`, `emails` and `all` scopes page through every hit the full-text engine
  finds, up to its first 10,000, each on one page only. Past those, a page is empty. The engine ranks hits by a
  score rounded to a percentage of the best one's, then by file name, so a search cuts a page only from the hits
  ranked above its window's lowest score, which come in the same order however far it reads, and reads further when
  those don't reach the page's end. When many hits score the same, a page can read up to 10,000 hits.
- **How many.** Each of those searches reads at least the engine's first 2,500 hits, more for a page further on.
  When it has found every hit, `total` says how many there are. When it stopped before the last, `total` is `null`
  and `totalAtLeast` says how many it found that the caller can read: there are more, so a client shows "more than"
  that number.
- **Filters.** The same as a folder's (see [Listing a Folder](#listing-a-folder)): `type` takes file kinds, and
  `author`, `class`, `client`, `matter`, `edited`, `from`, `to` and `zone` as there. They're applied in the database, after
  the read check, to the hits read, so they find their matches among the first 10,000 hits, and `total` and
  `totalAtLeast` count the hits that pass. `type=folder` is `400`: search the `folders` scope instead.
- **Sorting.** `sort` and `order` take a folder's values (see [Listing a Folder](#listing-a-folder)), and sort the
  hits the caller can read the same way. A sorted search reads the engine's first 10,000 hits and sorts those, so
  past 10,000 hits it sorts the 10,000 most relevant, and answers `totalAtLeast`. Without `sort` the best hits come
  first, and `order` alone is `400`.
- **Counts.** `facets=` adds the counts, as for a folder, each applying every filter but its own. They're taken in
  the database over the hits the caller can read that rank above the engine's 2,500th hit, whatever the page or
  the sort, never by the full-text engine, whose counts would include documents the caller can't read. When the
  engine has more than 2,500 hits, `facets` has `"partial": true`, so a client can say the counts come from the
  first results. When fewer than 1,250 hits rank above the 2,500th, as when most hits score the same, they're taken
  over the readable hits among the first 2,500 the search read instead, and then can differ slightly from page to
  page.
- **The other scopes,** `folders`, `matters` and `clients`, look at the first 10,000 hits, and their `total` is
  always `null`: a page with fewer than `limit` items is the last. They take no filters, `sort` or `facets`; any
  is `400 bad-request`.

```json
{
  "items": [ … ],
  "offset": 0, "limit": 100, "total": null, "totalAtLeast": 2500,
  "facets": {
    "type": [{ "value": "pdf", "count": 1210 }, { "value": "word", "count": 845 }],
    "partial": true
  }
}
```

## Listing a Folder

`GET /folders/{id}/children` lists the subfolders the caller can read, by name, then the documents the caller can
read. A search folder lists its own documents, at most 1,000. The server pages, sorts, filters and counts in the
database, so a folder of thousands of documents answers as fast as a small one.

- **Sorting.** `sort` is `title` (the file name, ignoring case; the default), `docNumber` (as a number, so #9 comes
  before #10), `version` (the file version, `fileVersion`, as the list shows it: by its major, then its minor
  number, so 2.9 comes before 2.10), `author` (by the
  Author's name as `author.displayName` shows it, not the username) or `edited` (when its file last changed: filed, or a new version saved, as the view's `lastModified` says; a change to its
  properties, a rename or a move doesn't count). `order` is `asc` (the default) or `desc`.
  Documents with nothing to sort by, such as no Author, come last either way, and ties go by the document's age,
  so no document shows on two pages. Subfolders always come first, by name. A search folder keeps its own order,
  newest change first, unless `sort` says otherwise.
- **Filters.** `type` takes file kinds: `word`, `excel`, `powerpoint`, `pdf`, `email`, `opendocument`, `picture`
  and `other`, for any extension the others don't name. `author` takes usernames, `class` classes, `client` client
  numbers such as `998`, and `matter` matters such as `998.1`. `edited` is `today`, `7d`, `30d` or `year`; or give
  `from` and `to`, either or both, as days such as `2026-06-01`, both included. `zone` says whose days they are, as
  an IANA time zone such as `Europe/London`: today, and where each day starts and ends. The User app sends the
  browser's, so the days match the dates it shows. Without `zone` they're the server's, and one the server doesn't
  know is `400 bad-request`.
  Repeat a parameter for more than one value, `type=pdf&type=word`: any value passes, and the filters all apply
  together. With any filter set, the list holds documents alone, and `total` counts the documents that pass.
- **How many.** `total` counts the subfolders and the documents, and `documents` the documents alone, so a client
  can say "7 documents" without asking twice. With a filter set, or in a search folder, the two are the same.
- **`type=folder`** still lists the subfolders alone, for a folder tree. It takes no filters or `facets`.
- **Counts.** `facets=type,author,class,client,matter`, or any of them, adds `facets` to the answer: for each one
  asked for, its options, most documents first, each with how many documents it would leave. Each facet applies
  every filter but its own, so ticking Word leaves the File Type counts as they were and narrows the others. They
  count only documents the caller can read. Without `facets` the answer has none.

```json
"facets": {
  "type": [{ "value": "pdf", "count": 12 }, { "value": "word", "count": 7 }],
  "author": [{ "value": "pnair", "displayName": "Priya Nair", "count": 9 }]
}
```

An Author's `value` is the username, which `author` takes, and `displayName` the name to show. A value the
filters don't take, such as `sort=size` or `type=docx`, is `400 bad-request`.

## Creating a Folder

`POST /folders` with `{"parentId": 1234, "name": "Drafts"}` creates a folder, as the web page's New Folder does,
and answers `201` with the folder and its address in `Location`. The new folder inherits its parent's properties
and permissions. It needs the Add permission on the parent (`403` without it). A Store is `409 not-browsable`, a
search folder is `409 filing-rule`, and a matter's top level is `409 refused` unless the caller is an
administrator. A name is cleaned of `/` and markup; one that is empty then is `422 validation`.

## Renaming, Moving and Deleting

These three go through the calls the old web page uses (`DocumentManager`'s rename and move, and the document DAO's
delete), so Healdan's rules decide as they do on every route: M1's re-stamping when a document moves into another
matter, once per matter for an email, and the filing rules. Each runs under the document's lock, as a save does, and
the checks come in this order: `404` for a document the caller can't read; for a move, `404` for a folder the caller
can't read and `409 filing-rule` for one that can't hold documents (a Store, a matter's top level, a search folder);
`403` without the permission (Rename, Move on the document and Write on the folder, or Delete) and for a document
protected by a password; the holds a save is refused for (`409 checked-out`, `locked` and `held-elsewhere`); `409
refused` for an immutable document, unless the caller is an administrator, as CE allows.

- **`PATCH /documents/{docNumber}`** with `{"name": "Lease"}` answers `200` with the document. A name that doesn't
  end in the document's extension keeps it, so `Lease` is `Lease.docx` and `Lease v1.2` is `Lease v1.2.docx`; one that
  ends in another extension (`Lease.pdf`) is `422 validation` with `extension` for `name`, as a save's name is. A name
  with a `/`, a `\`, a control character or a character the database can't store, such as an emoji, is `invalid`, checked
  first; one over 255 characters, with the extension it keeps, is `too-long`, and a blank one `required`. The same name
  changes nothing and makes no version. Once a deleted document is in the trash the answer is `204`, and a shortcut that
  can't follow it is left and logged.
- **`POST /documents/{docNumber}/move`** with `{"folderId": 1234}` answers `200` with the document. Into another matter
  it takes that matter's Client and Matter and the values its folders pass down; a matter that holds the same email
  already answers `409 already-filed` with the copy as `document`. A move into the folder it's in changes nothing.
- **`DELETE /documents/{docNumber}`** answers `204`. The document, and any shortcuts to it, go to the trash, which
  only the Classic Interface restores from, and the document is `404` from then on. A checked-out document is
  `409 checked-out` for everyone, the caller too: release it first. The document goes first and then its shortcuts, so a rule
  that refuses it leaves them.

## Favorites

The user's bookmarks in LogicalDOC, apart from those on matters' workspaces, which are My Matters.
`GET /me/favorites` lists them in one page, folders first, then documents, each by name. `POST /me/favorites` takes
exactly one of `folderId` and `docNumber`, and answers `201`; adding a favorite twice answers `201` with the
existing one. `DELETE /me/favorites/{id}` takes the bookmark's `id` from the list. A matter's workspace is `400`,
and what the user can't read is `404`.

## My Matters and Recent Clients

`PUT /me/matters/{folderId}` adds a matter to the caller's My Matters, taking the matter's workspace folder
(`folderId` in a matter) and answering `204`; adding one already there is `204` too. A folder that isn't a matter's
workspace, one the caller can't read and one that doesn't exist are all `404`. `DELETE /me/matters/{folderId}`
removes it, and `204` is the answer when it wasn't there. `GET /matters/mine` lists the result.

`GET /clients/recent` lists the clients of the caller's Recent Matters and My Matters, each once, by name then
number, as a plain array of `number` and `name`. Healdan has no list of all clients, and a client of a matter the
caller can no longer read isn't listed.

## Check-Outs and Devices

A check-out is held on a **device**: the `device` the token signed in with. A client that edits a local copy needs
this, because the same person may have the document open on two computers.

- **The hold names its device.** `hold.device` (`id`, `name`, `app`) is shown only to the user who holds the
  check-out. It is `null` for anyone else, for a lock, and when no device holds it: a check-out from the web page,
  from Healdan's own pages on the web session, or from a token that signed in with no device. A client treats a
  check-out whose `device` is its own as its own, so nobody else gets it.
- **The hold says where it's held, for showing.** `hold.heldOn` (`name`, `app`) is the computer's name and the app,
  with no device id. It's given to the user who holds the check-out and to administrators, so an administrator can
  say which computer to check it in on, and it's `null` for anyone else, for a lock, and when no device holds it.
  Problems that carry the `hold`, such as `409 checked-out` and `held-elsewhere`, give it on the same terms. Show
  it; never decide anything by it.
- **`POST /documents/{docNumber}/checkout`** takes an optional body: `{"app": "LibreOffice"}` records the app the
  document is opened in, and `{"move": true}` moves your own check-out from another of your devices to this one.
  The move is recorded in the history as a check-out. Edits the other device hasn't saved are not in Healdan.
- **`409 held-elsewhere`** is the answer, from any of your other devices, to a check-out without `move`, a save, a
  check-in, a release and a change of properties on a document you have checked out on one device. The problem
  carries the `hold` and `lastSaved`, when the content was last stored, so a client can warn that what the other
  device hasn't sent is not in Healdan. The web page itself, which doesn't go through the API, is never refused for
  a device.
- **A check-out with no device** (from the web page, from Healdan's own pages, or from a token that signed in with
  none) works from any of your devices until a check-out or a save through the API records one.
- **Healdan's own pages are no device.** On the web session, a document you have checked out on one of your devices
  is `409 held-elsewhere`, as for a token with no device, and `move` can't bring the check-out to the page.
- **Filing with `checkOut: true`** holds the new document on the filing token's device, with no `app`, as
  `POST /documents` takes none.
- **A free document** is checked out for a save, saved and checked in again, so a client needn't check out
  first. A client that edits for a while checks out when it opens the file.

## Saving Safely

`POST /documents/{docNumber}/versions` takes three optional members in its `version` part, so that a client
working from a local copy doesn't overwrite something it hasn't seen, and can send a save again after a lost
answer.

- **`ifVersion`**, the document's `version` the save started from, such as `"2.1"`. A document at another version
  is `409 version-moved`, nothing is stored, and the problem carries the `document` as it is now. If its
  `fileVersion` is still the one the client started from, nobody stored newer content: someone changed its
  properties or name, and the client may send the save again with the new `ifVersion`.
- **`ifHeld: true`** makes the save apply only while the document is checked out to you. A free document is
  `409 not-held`, because its check-out was released or checked in from the web page. Without it, a free document
  is checked out for the save. Another device's check-out is `409 held-elsewhere` either way.
- **`saveId`**, a UUID the client makes for a save and sends again, unchanged, if it didn't hear the answer. The
  server keeps the last one stored for each document, with the version it stored. A save that repeats it stores
  nothing. It's answered `201` with the document as it is now while the document's file is still that version's,
  even if its properties have changed. Once newer content has been stored, from the web page say, or that version
  has been deleted, it's `409 version-moved`, so a late retry is never answered with someone else's content. A
  `saveId` that isn't a UUID is `422 validation`.

Nothing is stored unless every check passes. They run in this order, and the first that fails gives the answer
(the docs page has the whole list):

1. read access (`404`);
2. a repeated `saveId`, answered `201` or `409 version-moved` as above;
3. the Write permission (`403`);
4. someone else's check-out or a lock (`409 checked-out` or `locked`), and your own on another device
   (`409 held-elsewhere`);
5. `ifHeld` (`409 not-held`), then `ifVersion` (`409 version-moved`);
6. an immutable or archived document (`409 refused`);
7. the name, the file and the comment (`413`, `422`).

A rule that refuses the check-in itself is `409 refused`. A new version that is an email of another message than the
document's is `409 already-filed` when the document's matter holds that message in a copy you can read. Either way the
document is left as it was, and checked out to you.

## Filing Email

A client that files email from a mail program asks two things before it files: what is in the message, and whether
it is filed already. Then it files the message, with any attachments the person ticked. Each call counts only the
documents the caller can read, so none shows that a hidden document exists.

### Looking Inside an Email

`POST /emails/inspect` takes the `.eml` in a multipart part named `file` and says what is in it. Nothing is stored,
so the upload limits apply as for `POST /documents`, and nothing is left on the server afterwards.

```json
{
  "messageId": "abc123@mail.example.com",
  "subject": "Contract for review",
  "from": "Ann Smith <ann@example.com>",
  "to": "Bob Jones <bob@example.com>, carol@example.com",
  "cc": null,
  "sent": "2026-11-03T10:00:00Z",
  "received": "2026-11-03T10:00:05Z",
  "attachments": [
    { "index": 0, "name": "logo.png", "type": "image/png", "size": 70, "inline": true, "message": false },
    { "index": 1, "name": "Contract.pdf", "type": "application/pdf", "size": 48213, "inline": false, "message": false }
  ],
  "copies": [ { "type": "document", "docNumber": 1234, … } ]
}
```

- **The Message-ID** is the header's value without its angle brackets (every pair of them, as some mail programs add
  their own) or surrounding space, compared exactly, case included. It is `null` when the message has none or one
  over 998 characters, and a message without one is never taken for a copy of another. A field the message lacks or
  has damaged is `null`.
- **The recipients,** `to` and `cc`, are each as `from` is, joined with `, `. `received` is the date of the message's
  most recent `Received` header, else its `Delivery-Date`.
- **The attachments** are the message's attachments and inline parts, numbered by `index` in a walk of its parts
  that always gives the same list for the same bytes. An attachment has a file name and is sent as an attachment,
  or has no disposition and isn't shown by the message's HTML; an attached message (`message/rfc822`) is one too,
  with `message: true`, named after its subject when it has no name. A part the HTML shows by `cid:` is `inline`:
  it is listed so that a client can say it is there, and isn't an attachment. Each name is made safe for a Store and an operating system.
  A message that is encrypted (S/MIME) or rights-protected has no readable parts, and lists none, nor does one
  signed opaquely (S/MIME) more than three times over.
- **The copies** are the documents that are copies of this message and that the caller can read, oldest first, as
  `GET /emails` finds them below.
- **Refusals.** A request without a `file` part is `400`, a file over the upload limit `413 too-large`, and a file
  that isn't an email (empty, not MIME, or with no header lines) `422 validation` with the field `file` and the
  code `not-an-email`. Only an `.eml` is read: an Outlook `.msg` is `422`.

### Finding a Message's Copies

`GET /emails` takes `messageId`, once for each message, from 1 to 100 times, and says for each where it is filed:

```text
GET /api/v1/emails?messageId=abc123%40mail.example.com&messageId=%3Cdef456%40mail.example.com%3E
```

```json
{
  "items": [
    {
      "messageId": "abc123@mail.example.com",
      "copies": [
        { "document": { "type": "document", "docNumber": 1234, … }, "address": "https://healdan.example/frontend.jsp?docId=5678" }
      ]
    },
    { "messageId": "def456@mail.example.com", "copies": [] }
  ]
}
```

- **Percent-encode every value.** A Message-ID can hold a `+`, `&`, `=` or `#`, which a query string reads in its
  own way. With or without angle brackets, it is the same Message-ID.
- **One item for each different Message-ID,** in the order they were first asked, with the Message-ID as Healdan
  compares it. A value that isn't a Message-ID (blank, or over 998 characters) is answered as it was sent, with no
  copies. No `messageId`, or more than 100, is `400`.
- **The copies** are the documents the caller can read, oldest first. A deleted document and a shortcut are left
  out, and so is a document from before Healdan with no numeric doc #, as in every list.
- **`address`** is where the web page opens the document: the server's public address (`LOGICALDOC_PUBLIC_URL`),
  or, when there is none, the address the request was made to, then `/frontend.jsp?docId=` and the document's
  internal id. It lets a client link to a copy without knowing that id. It isn't the doc #, which `docNumber` gives.

### Filing an Email and Its Attachments

`POST /emails` files an `.eml` whole, as received, attachments inside. It takes two multipart parts: `email`, JSON
with `folderId`, `properties` (as `POST /documents` takes them) and `attachments`, and `file`, the `.eml`:

```text
email={"folderId": 812, "properties": {"docclass": "Letters"}, "attachments": [1, 2]};type=application/json
file=@message.eml
```

```json
{
  "email": { "type": "document", "docNumber": 1234, "name": "Contract for review.eml", … },
  "attachments": [ { "type": "document", "docNumber": 1235, "name": "Contract.pdf", … } ],
  "skipped": [ { "index": 2, "code": "already-filed", "document": { "type": "document", "docNumber": 998, … } } ]
}
```

The answer is `201`, with the email's address as `Location`.

- **The email** is filed as `POST /documents` files a document: the next doc #, named after its subject, with its
  email fields read from the message.
- **Each attachment ticked** in `attachments`, by the `index` `POST /emails/inspect` gave it, becomes a document of
  its own in the same folder, with its own doc #, named by its file name and linked to the email (a link of type
  `attachment`, the email first, which the web page's Links tab shows). Leave `attachments` out to file the email
  alone. An inline part, such as a logo the message shows, can't be ticked.
- **The properties** sent apply to the email and to each attachment. Anything not sent inherits from the folder.
  The email fields can't be sent: each document takes its own from its file, and a value for one is `422` with the
  code `read-only`.
- **An attached message** (a forward) is filed as an `.eml` of its own, so it gets its own email fields. One whose
  message the matter already holds is skipped, not refused, and listed in `skipped` with its copy; the email still
  files. So is an attached `.eml` or `.msg` file the matter holds. A copy from before Healdan has no doc #, so its
  `document` is `null`.
- **All or nothing.** Everything is checked before anything is stored: the folder (`404`, `409 filing-rule`,
  `403`), the email (`413 too-large`, `422 not-an-email`), whether the matter holds it already, each ticked
  attachment against the upload rules, then the properties. A `422` names a ticked attachment's problem under the
  field `attachments[<index>]`, such as `attachments[2]`, so a client can show it by that attachment, with its index
  and name in the `detail` too, and an index that isn't an attachment with the code `not-an-attachment`. An index
  sent as `null` is `attachments`. If storing then fails part way, everything stored is taken back to the trash before the
  answer, so the answer is either everything filed or nothing filed.
- **`409 already-filed`** means the matter already holds this message, by its Message-ID; the problem's `document`
  is the copy. A client that repeats a filing whose answer it lost meets this, naming the copy its first try made,
  and can take it as done. A repeat that arrives while the first try is still filing waits for it to end, then meets
  this, or files the email if the first try was taken back, and so does a `POST /documents` of the same message.
  One that waits more than 30 seconds is `409 filing-in-progress`: send it again. The same refusal comes from the
  web page and every other way a copy of the message could reach the matter.

### Reading a Filed Email

A filed `.eml` or `.msg` can be read as a message, for a client that shows email without opening a mail program. All
of these need the Preview permission on top of reading (`/parts/{id}` needs Download unless it is `inline`, and Save
to Matter needs Download on the email and Write on its folder), and answer `404 not-an-email` for a document that
isn't an email, `404 not-found` first for one the caller can't read, and `403 forbidden` for one protected by a
password.

| Route | Answers |
|---|---|
| `GET /documents/{docNumber}/message` | The subject, From, To, Cc, Sent and Received, the attachments (`index`, `name`, `type`, `size`, `message`, and the `docNumber` of a document already filed from it that the caller can read), and the `body` address. Pictures the body shows aren't attachments |
| `GET /documents/{docNumber}/message/body?pictures=` | The body as a page of its own, `text/html;charset=utf-8` |
| `GET /documents/{docNumber}/message/parts/{id}` | An attachment, by its `index`, as a download: `Content-Disposition: attachment`, recorded as a download of the email. With `?inline=true`, a PDF or a picture every browser draws (JPEG, PNG, GIF, WebP, BMP or SVG), known by its type or by its name, to show in place as that type, under Preview, recorded as nothing; any other, a TIFF or a HEIC included, is `404 no-preview` |
| `POST /documents/{docNumber}/message/attachments/{id}/file` | Save to Matter: files the attachment in the email's folder as a document of its own, linked to the email as `attachment`, as `POST /emails` does. It takes the properties the email was filed with (its own values of the properties that can be changed, without the email fields, the legacy doc # and any a new document there can't take now, such as an author whose account was deleted), and an optional JSON body, `{"properties": {…}}`, as `POST /documents` takes them, is laid over them. `201`; saving it again files another copy, but an attached message the matter holds is `409 already-filed` with the copy |

Each of them takes `?version=`, a version from the email's timeline such as `1.0`, as `/preview` does: that version's
message, its body, its attachments and Save to Matter of one of them. `/message`'s `body` address names it too. A
version the document doesn't have is `404 not-found`.

The body page is cleaned by the server: no script, event handler, form, `object`, `embed`, `iframe`, `base` or meta
refresh, no link but `http`, `https` and `mailto` (each opening in a new window, `noopener noreferrer`), and styles
with no `url()` or `@import`. Pictures the message carries are embedded as `data:` addresses, up to 2 MiB in all
(a placeholder past that); pictures from the internet are removed unless `pictures=true`. Its own
`Content-Security-Policy` sandboxes it (`sandbox allow-popups allow-popups-to-escape-sandbox`), allows no fetch but
embedded pictures (`default-src 'none'`, `img-src data:`, plus `https:` with `pictures=true`) and lets only the
same origin frame it, so a client shows it in `<iframe sandbox="allow-popups allow-popups-to-escape-sandbox">`, never
with `allow-scripts` or `allow-same-origin`. It is a `GET` that changes nothing, so the web session may ask for it as
a frame's address. Every answer that serves stored bytes inline, an attachment with `inline=true` or a picture from
`/preview`, carries `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox`, so going to one directly
runs no script.

`/message` pairs each attachment with the document filed from it by the name and size it was filed with (CE numbers a second file of one name, `Name(1).ext`), then by order for identical ones, so two attachments of one name are told apart. The body's answer is `Referrer-Policy: no-referrer`. An inline part (one the body shows) isn't an attachment: `/parts` and Save to Matter answer `404` for it, as `/message` doesn't list it. An `.msg` is read through CE's reader into the same views. It keeps no Content-IDs, so every file in it is an
attachment and a `cid:` picture in its HTML shows as a placeholder. Its body is its HTML, whatever the HTML starts
with, or else its text, or else HTML made from its RTF, never the RTF itself, which CE's reader would put before the
text.

## Doc IDs in Word and LibreOffice Files

A Word or LibreOffice file (`.docx`, `.docm`, `.dotx`, `.dotm`, `.odt` or `.ott`) that carries the document
property **`Healdan Doc ID`** has it filled in by the server when it stores the file: on filing (`POST
/documents`) and on every save. The property becomes the doc ID of the version being stored, such as `#1234 v2.1`,
and so does the shown text of each field in the file that displays it, so that the stored file, its preview and
every download show the number.

- **Only files that carry the property** are changed, and nothing else in them. Any other file, and one the server
  can't read as such, is stored as sent, byte for byte.
- **A client that stamps its own copy,** to show the number before the save, works it out from the `version` it
  last read: a save's new number counts from `version` (see [Conventions](#conventions)).

## Thumbnails

`GET /documents/{docNumber}/thumbnail` returns a small PNG of the current file's first page. It needs the Download
permission, as the content does, and isn't recorded as a download. The server makes thumbnails in the background
after each save, so a document whose thumbnail isn't ready yet, like one whose file has none, is `404`: show a
placeholder, and ask again later.

The docs page has each one's fields, rules and errors. Every endpoint makes the checks the web page makes, so a
document you can't read through the web page doesn't exist through the API either.

## Previews

`GET /documents/{docNumber}/preview` answers what to show of a file in a page: a PDF's own file, the own bytes
(`image/*`) of a picture every browser draws (JPEG, PNG, GIF, WebP, BMP or SVG), or the PDF conversion of any other
file, a TIFF or a HEIC included (M1.5's queue makes one in the background for Office files; asking never makes one). `HEAD` answers the headers alone. `?version=` asks for a version's, as
for `/content`. It needs the Preview permission on top of reading (`403` without it, and for a document protected by a
password), and isn't recorded as a download, for a picture as for a PDF (it needs no Download permission).

| Answer | When |
|---|---|
| `200`, `application/pdf` or `image/*` | There is a PDF, or the file is a picture every browser draws |
| `202`, with `Retry-After` in seconds (5) and no body | The conversion is queued: ask again after that |
| `404 no-preview` | The type has none, the conversion failed (the "not available" PDF CE stores counts), or its job was given up on |
| `404 not-found` | The document or the version isn't there, or the caller can't read it |
| `503 storage-unreachable` | The file's storage can't be reached, as for `/content` |

A document's `kind` (the one list the File Type filter uses: `word`, `excel`, `powerpoint`, `pdf`, `email`,
`opendocument`, `picture` and `other`) says how a client previews it. The app draws a `picture` and every
other kind from `/preview`, a `picture` that isn't one every browser draws as a PDF, and asks again while it is `202`;
it has no list of types that convert.

## The Sign-In Throttle

Healdan limits wrong passwords on every way in, the API included: five in a row block a username for 15 minutes,
and 20 in a row from one network address block that address for 15 minutes. An administrator can change the
limits ([Healdan Settings](../../admin/healdan-settings/#the-sign-in-throttle)).

- **A blocked sign-in** gets `429 too-many-attempts`, even with the right password. The detail says when to try
  again, such as "Too many failed sign-ins. Try again in 15 minutes.", so show it to the person.
- **Attempts during a block don't count,** so they don't make it longer. Still, don't retry by yourself: ask the
  person.
- **`401 password-expired`** comes only for the right password, and doesn't count: it clears the count as a
  sign-in does. The person changes the password in the web page, then signs in with the new one.
- **A request with a token** can get `429` too, while its network address is blocked.
- **A token that no longer works** doesn't count against anyone, so an old token left in a client doesn't block
  its user.

## Calling the API from Another Origin

A page served from Healdan's own address needs nothing. A page served from somewhere else, such as an add-in's
development server, needs its origin in **API origins allowed (CORS)** in
[Healdan Settings](../../admin/healdan-settings/), the `api.cors.origins` setting. It's empty to begin with.

- **Exact origins only:** scheme, host and an optional port, such as `https://localhost:3000`, separated by commas.
  No path, and no `*`.
- **For a listed origin,** the API answers preflight requests and adds the CORS headers. It allows `GET`, `POST`,
  `PATCH` and `DELETE`, the `Authorization` and `Content-Type` request headers, and lets the page read `Location`
  and `Content-Disposition`. Preflights are cached for 10 minutes. No credentials are allowed: tokens aren't
  cookies, and the web session works only from Healdan's own origin.
- **An unlisted origin's preflight** gets `403 forbidden`.

## Office Add-Ins Need HTTPS

Office loads an add-in's pages over HTTPS, and a page loaded over HTTPS can't call an address that's plain HTTP.
So an Office add-in needs Healdan behind a [reverse proxy](../../install/reverse-proxy/) that serves HTTPS, with a
certificate the computer running Office trusts.

## The Office Add-In's Files

`/api/addin/` isn't part of the JSON API. It serves the Microsoft Office add-in's manifest, pages and icons, which
Office loads with no sign-in, so nothing there takes a token or follows the API's conventions: no `/v1`, and
`openapi.yaml` doesn't describe it. A missing file, or an address Office can't use, is still answered with a problem
as everywhere under `/api`. [Office Integration](../../install/office-integration/#microsoft-office) says what
the server needs for it.

## Sign-In Pages

`/api/signin/` isn't part of the JSON API either. It runs a sign-in through a provider, such as Microsoft Entra ID,
for the web page and for the Healdan Helper. Its addresses are pages a browser is sent through, so they take no token,
answer with redirects and HTML rather than JSON, and `openapi.yaml` doesn't describe them.

- **`/api/signin/<id>`** starts a sign-in through the provider with that id, and sends the browser to the provider.
  A random value in an `hd_signin` cookie ties the sign-in to that browser, and it has 10 minutes to come back.
  With `?client=helper` and the helper's parameters, it starts the helper's sign-in (see
  [Signing In Through a Provider](#signing-in-through-a-provider)).
- **`/api/signin/<id>/callback`** is where the provider sends the browser back, and the redirect address an
  administrator registers at the provider: your install's public address followed by `/api/signin/<id>/callback`.
  Each sign-in comes back once, to the browser that started it and to its own provider's address, or it's refused.
  The web page's sign-in ends signed in, with the same session cookie as the password form, on the page asked for
  or the web page. A refusal goes back to the sign-in page as `/login.jsp?signin=<code>`. The helper's sign-in opens
  no session in the browser: it ends at the helper's loopback address with a one-time code, or a refusal's code. A
  sign-in that's unknown, used or expired has no helper's address, so it goes to the sign-in page.
- **`/api/signin/done`** is the page that says "Signed in to Healdan. You can close this tab." With
  `?error=<code>`, it says what went wrong instead. No message names the provider, and a `provider` parameter is
  ignored.

| Code | When |
|---|---|
| `no-group` | The person's groups at the provider map to no Healdan group |
| `email-taken` | The email matches more than one account, or one that can't be linked |
| `username-taken` | A new account's username is taken, or isn't one Healdan can use |
| `account` | LogicalDOC's sign-in refused the account: disabled, expired or outside its working hours, say |
| `provider` | The provider couldn't be reached, refused, or what it sent failed a check |
| `expired` | The sign-in took more than 10 minutes, was already used, or came back to another browser or provider; or a client's start whose challenge another sign-in already has |

The addresses are built from the server's public address, as the Office add-in's are: `LOGICALDOC_PUBLIC_URL`
when it's set, otherwise the address the request came to. It must be `https://`, or plain `http://` on the machine
itself. Without one, the pages answer `409` with a page that says to set `LOGICALDOC_PUBLIC_URL`. A start that
comes to another host name than `LOGICALDOC_PUBLIC_URL`'s, such as the server's own name on the office network, is
sent to the same path and query at the public address first, with nothing kept and no cookie set: the cookie
belongs to the name the browser asked for, and the provider sends the browser back to the public address. Only
the host name is compared, as a cookie ignores the scheme and port.

## Healdan's Own Client

The Healdan Helper, which brings Healdan into LibreOffice, is a client of this API, and uses most of what's above:
device sign-ins, the windows' lists and searches, filing, device check-outs, and saves with `ifVersion`, `ifHeld`
and `saveId`. It too refuses a plain `http://` server other than `localhost`. [Desktop Clients](../desktop-clients/)
describes it, and its tests fail when it calls a route or reads a field the description file doesn't have.

## Trying It

- **A stack to try it on.** A throwaway stack with the smoke overlay has a matter to file into: see
  [Running a Development Stack](../development-stack/#a-matter-to-upload-into). Open `/api/docs/` on it.
- **A worked example.** `docker/tests/test-api.sh` runs a client's whole flow with `curl`: sign in, find a
  matter, file a `.docx`, check it out, save twice, download two of its versions, check that LogicalDOC's services
  refuse the token, and sign out. It also covers M3's additions: searching inside a folder, creating a folder,
  favorites, a stamped `.docx`, a second device, moving a check-out, `ifVersion` and `saveId`, and the
  `#1234 v2.1` lookup. The smoke test runs it.

## LogicalDOC's Web Services and API Keys

LogicalDOC's own REST web services are still there, under `/services/rest/`; Healdan doesn't offer LogicalDOC's SOAP
services. They take LogicalDOC's API keys, which start `ld-` and are sent as `X-API-KEY`. People make them in the
web page, under the person icon in the top bar, in **Security → API Keys**.

They're LogicalDOC's older services. They speak LogicalDOC's words, so a Store is a workspace there, and an API key
never expires and survives a password change. New clients use `/api`, which doesn't take API keys.

An account that signs in through a provider can't use an API key at all: linking it deletes the keys it had, and a
key it makes afterwards in the web page is refused on every route.

**A device token isn't an API key.** It works only under `/api`. LogicalDOC's services refuse it however it's sent:
to `/services/rest/auth/loginApiKey`, as `X-API-KEY` on any of their routes, or as the password or key of a Basic
sign-in, which WebDAV and CMIS use. So a lost token can't be turned into an API key, which would outlast it. Still,
guard a token as you would the password: until it's revoked, it signs in to the API as you.
