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
  every answer they get against it, and fail on an endpoint it leaves out. Generate your client's types from
  it. Its paths start at `/api`, so `/v1/server` in the file is `/api/v1/server`.
- **`/api/docs/`** shows the file as a page you can browse, with **Try it out** on each endpoint. Healdan serves the
  page itself, so it loads nothing from the internet. To call an endpoint that needs a sign-in, sign in with
  `POST /v1/auth/tokens` first, then paste the token into **Authorize**.

Every path below starts with `/api/v1`.

## Signing In

A client signs in once with a username and password, and gets a device token. From then on it sends only the
token, as `Authorization: Bearer`. With `curl`, against a stack on your own machine:

```bash
BASE=http://127.0.0.1:8080        # or your install's address, such as https://docs.example.org
curl -s -X POST "$BASE/api/v1/auth/tokens" -H 'Content-Type: application/json' \
  -d '{"username": "jsmith", "password": "…", "name": "Word on MacBook"}'
```

The answer is `201`:

```json
{
  "token": "hd_bm90LWEtcmVhbC1kZXZpY2UtdG9rZW4tZXhhbXBsZSE",
  "id": 12,
  "name": "Word on MacBook",
  "expiresAt": "2027-10-24T09:15:00Z",
  "user": { "id": 7, "username": "jsmith", "name": "Jane Smith", "email": "jane@example.org", "language": "en", "admin": false }
}
```

Then:

```bash
TOKEN=hd_…
curl -s "$BASE/api/v1/me" -H "Authorization: Bearer $TOKEN"
```

- **The token is shown only once,** in that answer. Keep it as you would a password, in the system's keychain for
  example. A client never needs to keep the password.
- **Only the token signs a request in.** The API ignores cookies, `sid`, Basic authentication and `X-API-KEY`, so a
  web page can't make your browser call it with your sign-in.
- **The token works only in the API,** under `/api`. LogicalDOC's own services refuse it (see
  [below](#logicaldocs-web-services-and-api-keys)).
- **`GET /server`** needs no sign-in. A client calls it first, to check that it's talking to Healdan.
- **A refused sign-in:** `401 sign-in-failed` never says whether the username or the password was wrong, or that
  the account can't sign in. `401 password-expired` means the password has to be changed in the web page first.

## Device Sign-Ins

Each token is one device's sign-in, such as Word on a MacBook. `name` labels it, so people can tell their devices
apart; a blank name is "Unnamed device".

- **A token stops working** when it hasn't been used for 30 days, 365 days after sign-in at the latest
  (`expiresAt`), when it's revoked, and when its user is disabled or deleted. An administrator can change the 30
  and the 365 in [Healdan Settings](../../admin/healdan-settings/).
- **Changing your password signs out every device.** For a lost device, do that, then delete any LogicalDOC API
  keys you don't recognise: in the web page, under the person icon in the top bar, **Security → API Keys**,
  right-click a key and choose **Delete**. A device token can't make a key, but whoever had the device may have had
  the web page too, and a key outlasts a password change. Then ask an administrator to end your open sessions
  (see [Healdan Settings](../../admin/healdan-settings/#device-sign-ins)).
- **Signing out** is `DELETE /auth/tokens/current`, which revokes the token making the request.
- **Your devices:** `GET /auth/tokens` lists your tokens that still work, newest first, never the tokens themselves.
  `current` marks the one making the request. `DELETE /auth/tokens/{id}` revokes one of them.
- **A token that no longer works** gets `401 unauthenticated`. Ask for the password and sign in again.

## Errors

Errors are RFC 9457 problems, sent as `application/problem+json`:

```json
{
  "type": "https://healdan.com/problems/checked-out",
  "title": "Checked out",
  "status": 409,
  "detail": "Document #1234 is checked out to Jane Smith.",
  "code": "checked-out",
  "hold": { "kind": "checkout", "by": { "id": 5, "name": "Jane Smith" }, "since": "2026-10-23T15:00:00Z" }
}
```

Switch on `code`, which is stable. `detail` is an English sentence you can show as it is. `type` needn't resolve.

| Status | `code` | When |
|---|---|---|
| 400 | `bad-request` | The request is malformed: bad JSON, a field the server doesn't know, a bad `offset` or `limit` |
| 401 | `unauthenticated` | No token, or one that no longer works |
| 401 | `sign-in-failed` | A wrong username or password, or an account that can't sign in |
| 401 | `password-expired` | The password has to be changed in the web page first |
| 403 | `forbidden` | You can see the object, but may not do this to it |
| 404 | `not-found` | It doesn't exist, **or you can't read it**: the two get the same answer |
| 409 | `checked-out`, `locked` | Someone else has the document checked out, or anyone has it locked with the web page's Lock action. The problem adds the `hold` |
| 409 | `filing-rule` | The folder can't hold documents: a Store, a matter's top folder or a search folder |
| 409 | `not-browsable` | A Store's contents were asked for. Find a matter by search, My Matters or Recent Matters instead |
| 409 | `refused` | Another of Healdan's rules refused it. `detail` says which |
| 413 | `too-large` | The file is over the upload limit |
| 422 | `validation` | Some values are wrong. `fields` lists each one, with its own `code` and `detail` |
| 429 | `too-many-attempts` | Too many failed sign-ins (see below) |
| 500 | `server-error` | Anything else. The detail never holds a stack trace |

Other client errors, such as `405` for a method an address doesn't take, keep their status, with the code
`bad-request`.

## Lists and Paging

Lists take `offset` (0 to begin with) and `limit` (50 to begin with, from 1 to 200), and answer:

```json
{ "items": [ … ], "offset": 0, "limit": 50, "total": 123 }
```

- `total` is `null` for a search, where counting would be slow. A page with fewer than `limit` items is the last.
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
- **Field names are camelCase.** A field with no value is `null`, never left out. Timestamps are ISO 8601 in UTC,
  such as `2026-10-23T14:05:00Z`.
- **Nothing caches the answers:** Healdan sends them with `no-store` in `Cache-Control`.

## What's There

| Area | Endpoints |
|---|---|
| The server and you | `GET /server`, `GET /me`, `GET /users?q=` for user fields such as Author |
| Signing in | `POST /auth/tokens`, `GET /auth/tokens`, `DELETE /auth/tokens/current`, `DELETE /auth/tokens/{id}` |
| Matters and folders | `GET /matters/{id}`, `/matters/mine`, `/matters/recent`, `GET /folders/{id}`, `/folders/{id}/children`, `/folders/recent`; `GET /stores` and `/stores/{id}/matters` for administrators only |
| Finding | `GET /search?scope=&q=`, `GET /lookup?q=` for a typed doc #, `GET /documents?legacyDocNumber=` |
| Filing | `GET /folders/{id}/form`, then `POST /documents` with the file |
| A document | `GET /documents/{docNumber}`, `/content`, `/versions`, `GET` and `PATCH /properties`; `GET /documents/recent` |
| Check-out and saves | `POST` and `DELETE /documents/{docNumber}/checkout`, `POST /documents/{docNumber}/versions`; `GET /me/checkouts` |

The docs page has each one's fields, rules and errors. Every endpoint makes the checks the web page makes, so a
document you can't read through the web page doesn't exist through the API either.

## The Sign-In Throttle

Healdan limits wrong passwords on every way in, the API included: five in a row block a username for 15 minutes,
and 20 in a row from one network address block that address for 15 minutes. An administrator can change the
limits ([Healdan Settings](../../admin/healdan-settings/#the-sign-in-throttle)).

- **A blocked sign-in** gets `429 too-many-attempts`, even with the right password. The detail says when to try
  again, such as "Too many failed sign-ins. Try again in 15 minutes.", so show it to the person.
- **Attempts during a block don't count,** so they don't make it longer. Still, don't retry by yourself: ask the
  person.
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
  cookies.
- **An unlisted origin's preflight** gets `403 forbidden`.

## Office Add-Ins Need HTTPS

Office loads an add-in's pages over HTTPS, and a page loaded over HTTPS can't call an address that's plain HTTP.
So an Office add-in needs Healdan behind a [reverse proxy](../../install/reverse-proxy/) that serves HTTPS, with a
certificate the computer running Office trusts.

## Trying It

- **A stack to try it on.** A throwaway stack with the smoke overlay has a matter to file into: see
  [Running a Development Stack](../development-stack/#a-matter-to-upload-into). Open `/api/docs/` on it.
- **A worked example.** `docker/tests/test-api.sh` runs an Office add-in's whole flow with `curl`: sign in, find a
  matter, file a `.docx`, check it out, save twice, download two of its versions, check that LogicalDOC's services
  refuse the token, and sign out. The smoke test runs it.

## LogicalDOC's Web Services and API Keys

LogicalDOC's own web services are still there: SOAP and REST under `/services/`. They take LogicalDOC's API keys,
which start `ld-` and are sent as `X-API-KEY`. People make them in the web page, under the person icon in the top
bar, in **Security → API Keys**.

They're LogicalDOC's older services. They speak LogicalDOC's words, so a Store is a workspace there, and an API key
never expires and survives a password change. New clients use `/api`, which doesn't take API keys.

**A device token isn't an API key.** It works only under `/api`. LogicalDOC's services refuse it however it's sent:
to `/services/rest/auth/loginApiKey` or the SOAP service's `loginApiKey`, as `X-API-KEY` on any of their routes, or
as the password or key of a Basic sign-in, which WebDAV and CMIS use. So a lost token can't be turned into an API
key, which would outlast it. Still, guard a token as you would the password: until it's revoked, it signs in to the
API as you.
