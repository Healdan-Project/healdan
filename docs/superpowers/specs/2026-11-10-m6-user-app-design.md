# M6 "New web UI: User app": design

- **Milestone:** M6 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-11-10
- **Status:** design agreed with the owner on 2026-11-10. The scope and the foundations were settled question by question, the layout through mockups the owner reviewed in the browser (§6, [`2026-11-10-m6-mockups/`](2026-11-10-m6-mockups/README.md)), and the rest section by section. The owner approved this written spec the same day. Implementation plan: [`docs/superpowers/plans/2026-11-10-m6-user-app.md`](../plans/2026-11-10-m6-user-app.md); approved the same day, for subagent-driven building; its "Spec corrections and calls this plan makes" lists where it departs from this spec.
- **Inputs:**
  - the roadmap's M6 section, and its glossary;
  - the [M1 spec](2026-10-17-m1-move-in-design.md) §5 (Stores, matters, templates and search folders), §6 (doc #s, properties and dates) and §7 (the rules on every route);
  - the [M2 spec](2026-10-23-m2-api-foundation-design.md) §4 to §9 (the API's rules, sign-in, endpoints, permissions, CORS and push), and its plan's "Performance, before M6" follow-up;
  - the [API inventory](../../reference/healdan-api-inventory.md), whose `M6` tags are the old UI's calls this milestone weighs;
  - the [M1.5 spec](2026-10-22-m1-5-office-previews-design.md), for previews;
  - the [M3 spec](2026-10-24-m3-office-integration-design.md) §8 (Open in App);
  - the [M4 spec](2026-11-01-m4-oidc-sign-in-design.md) §5 (signing in on the web page);
  - the [M5 spec](2026-11-03-m5-email-design.md) §6 (the Message-ID, once per matter, attachments);
  - the [S3 storage spec](2026-11-01-s3-storage-backends-design.md), for `503 storage-unreachable`;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md);
  - a design conversation with the owner on 2026-11-10, with mockups drawn from the demo firm (`docker/demo/manifest.json`);
  - a survey of this repository at `75046ac14`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard and approved section by section. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace*, *matter* and *template* mean Healdan's things here (roadmap glossary). "CE" is LogicalDOC Community Edition. "The app" is the User app this milestone builds. "The old UI" is CE's GWT web page, which the app calls the **Classic Interface**. "The web session" is the CE session a browser holds after signing in on `login.jsp`, through Tomcat's `JSESSIONID` cookie.

## 1. Intent

A modern web UI for everyday work, built without Java and branded Healdan (roadmap). M6 builds the first of its three apps, **User**, to the point where the owner does a whole working day in it: the **Daily Driver** (owner). It isn't a one-off: it's the base that Healdan's UI is built on from now on. Later phases move the remaining features over from the old UI until the old UI can retire, and the Walls (M7) and Admin apps reuse its foundations (owner, 2026-11-10: "this is foundational… it is important to get this right").

## 2. Goals, non-goals, anti-goals

### 2.1 Goals (M6 is done when)

1. On the owner's server, signing in lands in the app, and a `/app/doc/n` link followed while signed out comes back to that document after signing in, by password and through Authentik.
2. Every Daily Driver feature (§4) works in Safari, in light and dark, from the keyboard as well as the mouse.
3. A matter folder of 2,000 documents opens in under a second, sorted any way, and search pages past 1,000 hits.
4. Filter counts never count a document the person can't read.
5. CI runs the `Web` check, and `docker/tests/test-web.sh` on amd64 and arm64.
6. The docs pages in §14.3 ship with the code.
7. The owner works a full working day in the app, going to the Classic Interface only for the features §16 leaves to later phases. That day is the acceptance walkthrough's last row (§15.2).

### 2.2 Non-goals

- **The features left to later phases** (§16): the Trash, permissions, history, notes and links, sharing, your profile and password, making search folders, choosing columns, setting properties on many documents, folder rename, move and delete, copying, locking, push updates and the Compact setting. Each is one click away in the Classic Interface meanwhile.
- **Healdan's own sign-in page** (owner). CE's `login.jsp` stays for M6 (§8). Healdan's page comes in the phase before the old UI retires.
- **The Admin app and the Walls app.** Admin stays in the backlog; Walls is M7. Both will build on `packages/ui` (§7.1).
- **Phones.** The app is designed for screens 1,280 pixels wide and works on a tablet's 768; a phone needs a design of its own (owner).
- **Languages other than US English** (owner). Every string is in one catalogue, so a translation is a new file (§10.4).
- **Knowing whether the helper is running** (call, §6.10).

### 2.3 Anti-goals

- **No reliance on the app for any rule.** The server enforces everything on every route (roadmap principle 4). Splitting the apps gives no security of its own.
- **No list of every matter in a Store** (roadmap, owner 2026-10-23). The app starts from Recent, My Matters, Favorites or a search.
- **Nothing secret in the browser's storage.** The app keeps no token, password or document data in `localStorage` or `sessionStorage`; only preferences.
- **No count, suggestion or result that reveals a document the person can't read** (§9.2).
- **No dependency that hasn't passed the licence check** (§7.4).

## 3. Constraints

### 3.1 What earlier milestones give M6

- **M1:** Stores, clients, matters and their workspaces from templates; doc #s; the profile (Author, Typist, Class, Subclass, inherited values and overrides); search folders; the filing rule; My Matters and Recent Matters; the search scopes and `#` lookup.
- **M1.5:** a PDF of each Office document, made in the background, which previews show.
- **M2:** the JSON API at `/api/v1`, its contract in `openapi.yaml`, and the endpoints the add-ins use: search, lookup, folders and their children, documents, properties, the filing form, filing, versions, check-out, Favorites, recent documents, folders and matters, tokens.
- **M3:** Open in App through a `healdan:` link that the helper takes.
- **M4:** provider sign-in on the web page, ending in a CE web session.
- **M5:** the Message-ID, once per matter (`409 already-filed`), and attachments filed as documents with an `attachment` link.
- **S3 storage:** `503 storage-unreachable` when a file's backend is down.

### 3.2 Facts from the code

- **The API takes only Bearer tokens.** `TokenFilter` signs a request in from `Authorization: Bearer hd_…` and nothing else (M2 §5.1). `/api/**` is `security="none"` in `context-security.xml`.
- **CE's default security chain lets `/app/**` through.** Its last `http` block ends in `/** permitAll()`, and adds CE's `security.csp` header and `X-Frame-Options: SAMEORIGIN` to every response it passes.
- **The web session.** `login.jsp`'s form and M4's `SignInResource` keep the CE session's id in the HTTP session's `sid` attribute. `SessionManager.getSessionId` also reads `sid` from a request parameter, a request header and the `ldoc-sid` cookie. Tomcat sends cookies `SameSite=Lax` (`logicaldoc-webapp/src/main/webapp/META-INF/context.xml`). `session.timeout` is 30 minutes as shipped.
- **`login.jsp` honours `RequestedUrl`,** an HTTP session attribute that nothing in CE sets, and goes to `frontend.jsp` without it. It writes the value unescaped into a script. M4's `SignInResource` follows the same attribute under the same conditions, and falls back to `frontend.jsp`.
- **Open in App can't be confirmed.** The old UI opens a `healdan:` link (`HealdanDocumentOpener`), and the browser gives no sign whether the helper took it.
- **Search.** CE's engine is embedded Solr 8.11. CE checks read permission after Solr answers, and full-text results stop at about 1,000 engine hits (Solr's default `rows`). CE never asks Solr for facets.
- **Folder listings** (`GET /folders/{id}/children`) load the whole folder for every page (M2 plan, "Performance, before M6").
- **`POST /documents/{n}/versions`** checks the document out, saves and checks it in, so a new version needs no check-out first.
- **The server already ships jsoup** (`logicaldoc-util`), which can clean HTML.
- **The Office add-in's pages** are packaged into `healdan-api`'s jar and served at `/api/addin/`, a precedent for serving client files from a Healdan jar.

## 4. The Daily Driver (owner)

The test for each feature was whether the owner would reach for it on an ordinary working day. Everything else stays one click away in the Classic Interface until its phase (§16). *Roadmap* marks rows the roadmap had already decided.

| Area | Feature | API | In M6 |
|---|---|---|---|
| **Finding** | The search box with its scope picker (Documents, Emails, Docs & Emails, Folders, Matters, Clients) | exists | yes (roadmap) |
| | `#n`, `#n.N` and a matter number going straight to the item; legacy doc # | exists | yes |
| | The left pane: the Recent section, Recent Matters, the My Matters tree, My Favorites | mostly exists | yes (roadmap) |
| | Adding and removing My Matters and Favorites | Favorites exists | yes |
| | Recent Documents, Emails, Folders and Clients | Documents and Folders exist | yes |
| | Filters: Edit Date presets, File Type, Author, Class, and Client and Matter in search results | new | yes (roadmap) |
| | Search folders in the tree, showing their contents | exists | yes |
| | Suggestions as you type | new | yes (call) |
| | Making or editing a search folder's rule | new | later |
| | CE's saved searches and tags | – | never (M1 turned saved searches off; search folders cover them) |
| **Looking** | The document list: Title, Doc #, Version, Author and Edit Date, sorted by any column | exists | yes (roadmap) |
| | Choosing and reordering columns | new | later |
| | The breadcrumb, with the matter as `client.matter – name` | exists | yes (roadmap) |
| | The details panel: properties and versions | exists | yes |
| | A document's history | new | later |
| | Preview | new | yes |
| | Reading an email: its body and attachments; saving an attachment as a document | new | yes |
| **Working** | Double-click opens in the app, through the helper | exists | yes (roadmap) |
| | Download | exists | yes |
| | Check out, check in, release; a new version | exists | yes |
| | CE's Lock with a comment | new | later |
| **Filing** | Dropping files and emails on a folder, or picking them; the properties form; the once-per-matter refusal | exists | yes (roadmap) |
| | Editing properties, inherited values and overrides | exists | yes |
| | New Folder inside a matter | exists | yes |
| | Setting properties on many documents at once | new | later |
| **Organising** | Renaming a document | new | yes |
| | Moving documents between folders and matters | new | yes |
| | Deleting to the trash | new | yes |
| | The Trash: restoring and purging | new | later |
| | Renaming, moving and deleting folders | new | later |
| | Copying documents, links between documents, notes | new | later |
| **Sharing** | Permissions on a document or folder | new | later |
| | Shared links (tickets), emailing from the server, zip export | new | later |
| **You** | Sign Out; Classic Interface | new | yes |
| | Signed-in Devices: listing and revoking tokens (M2 §5.4 left it for M6) | exists | yes |
| | Profile, password, picture | new | later |
| | CE's home dashlets | – | never; the Recent section is the start page |
| **Admin** | Making matters, clients and Stores | – | the Admin app (backlog) |

## 5. The baseline every screen meets (owner on the language; calls otherwise)

| Item | Rule |
|---|---|
| **Language** | US English by default (owner). Every string is in one typed catalogue (§10.4). The configurable labels ("Matter", "Project") come from `GET /server`. |
| **Dates and numbers** | In the browser's time zone and the person's locale, as the helper shows them. |
| **Light and dark** | Both from the start, following the system's setting. A switch in the app can come later. |
| **Screen sizes** | Designed for 1,280 pixels and wider; works at 768, with the side panels folding away. Phones aren't a goal. |
| **Accessibility** | WCAG 2.2 AA: contrast, a focus ring you can always see, everything by keyboard, labels for screen readers. |
| **Keyboard** | `/`, the arrow keys, Enter, Space, Esc, F2 and Delete from the start (§10.5). |
| **Browsers** | Current Safari, Chrome, Firefox and Edge: the releases of the last two years. |
| **Speed** | The app loads once, then each view fetches only its data. A matter's folder opens in under a second on the owner's server. |

## 6. The layout (owner, from the mockups)

The owner reviewed the layout in the browser, from mockups drawn with the demo firm's data, the helper's palette and Tabler's icons. They're in [`2026-11-10-m6-mockups/`](2026-11-10-m6-mockups/README.md). Where a mockup and this section differ, this section wins.

### 6.1 The frame

- **The top bar:** Healdan's mark and name; the scope picker joined to the search box, with `/` to jump to it; Help; and the person's menu (§6.9). The frame of reference is iManage Work (cloud) (roadmap).
- **The left pane**, top to bottom:
  - **Recent** (owner): Documents, Emails, Folders and Clients. Each opens its own recent items, and sets the scope picker so the next search runs in that scope. Documents is your recent documents, newest first, and is the start page. Clients is the clients of your recent matters and My Matters, since Healdan has no client list (M1).
  - **Recent Matters**, as today's list, with the client smaller and muted.
  - **My Matters** as a tree that opens into each matter's folders and search folders, with a **+** to add the open matter.
  - **My Favorites**: documents and folders.
- **The main area:** the breadcrumb (client › `client.matter – name` › folder), the folder's name with a star for Favorites, the toolbar, and the list.
- **The toolbar:** Upload and New Folder; then Open, Preview, Download, Check Out and More (⋯) for the selection, as icons with labels on hover; the count ("7 documents", "3 of 7 documents"); and the **Filters** button (§6.2).
- **The right region:** the selected document's details, or Filters (§6.2), or the docked preview (§6.4). With nothing selected and Filters closed, it folds away and the list takes the width.

### 6.2 Details and Filters (owner)

- **Filters open from a funnel button** at the right end of the list's toolbar, and show in the same region as the details. They aren't tabs beside the details: details only make sense when something is selected (owner).
- **Clicking a document shows its details** (owner). Once the filters are set and you click away, they count as set; the funnel brings them back to change them.
- **The button's badge counts the filters in use,** and **chips above the list** name each one, with × to drop it and Clear All. A hidden filter can never make a document seem lost.
- **Facets:** each option shows how many documents it would leave (§9.2). Edit Date offers Any Time, Today, Last 7 Days, Last 30 Days, This Year and Between…; File Type, Author and Class are tick lists. Inside a matter, Client and Matter aren't offered; in search results they are.
- **The details panel:** a thumbnail with Preview; the name; `# n · Version v · size`; Open, Download and Edit; the properties (Location in search results, which goes to the folder; Client, Matter, Class, Subclass, Author, Typist, Date Filed; an email's From, To, Sent and Received); and the versions. Edit opens the properties for editing in place, with the same form rules as filing.

### 6.3 Search

- **Suggestions as you type** (call): matching matters first, then a few documents by title, then "Search Documents for "…"" with Enter. The arrow keys pick a suggestion.
- **Enter runs the search** in the chosen scope. Results come by relevance; any column header sorts them instead. Document results add a Matter column: the matter number with the client muted, and the matter's name on hover.
- **`#10388` then Enter** shows results with just that document, already selected with its details showing, ready to act on (roadmap principle 1). `#10388.2` selects version 2 in the details. A number the person can't read gets "No document # 10388 that you can see", the same words whether it exists or not.
- **With the Matters scope, a matter number** such as `1001.1` then Enter opens the matter (M1).
- **Nothing the person can't read appears,** in suggestions, results or counts.

### 6.4 Preview (owner: both)

- **Space opens Quick Look:** a large preview over the app, as Finder's does. ‹ › and the arrow keys move through the list behind it; Space or Esc closes it. Nothing behind it reflows.
- **A button in Quick Look docks it** as a reading pane in the right region, which stays docked while you click or arrow down the list, until you close it. The list keeps Title, Doc # and Edit Date while it's narrow.
- **While it's docked, the reading pane takes the details' place:** clicking a row previews it. The funnel shows Filters there until you next click a row, as with the details (§6.2). Closing the reading pane brings the details back.
- **What's shown:** M1.5's PDF, drawn in the page with PDF.js; a picture as itself; an email as a message (§6.6). A type with no preview says so, with Open and Download.

### 6.5 Filing by drag and drop (owner: at once, unless something's missing)

- **Where:** anywhere on a folder's list, or on a folder in the My Matters tree, which lights up. A matter's top level and search folders refuse: "Choose a folder inside the matter" (M1's filing rule).
- **A drop files at once** with the defaults: the folder's own Class if it sets one, else the template's default; you as Author and Typist; Client and Matter from the matter; the file's name as the title.
- **The form appears first** when a required field has no default, or when you hold ⌥ while dropping. It lists the files with editable titles and one form for them all, and its button says how many it will file.
- **A tray** in the corner shows each item: filed with its #, uploading, or refused. Each filed item has Edit. New rows flash in the list.
- **Emails** (`.eml`, `.msg`) take their subject as the title and fill their email fields themselves (M1). One already in the matter isn't filed again: the tray says "Already in this matter as # n" with Show (M5's `409 already-filed`).
- **Dropping one file on a document's row** asks "Save as Version n of # m, or File as a New Document?".
- **Uploads go on** while you move about the app. Leaving the page while any are uploading asks first.
- **From Outlook:** the trial's first row (§12) decides whether dragging a message out of new Outlook for Mac gives the page a file to file.

### 6.6 Reading an email (owner on pictures)

- **Quick Look and the reading pane show a filed email as a message:** the subject; the sender and their address; To and Cc; Sent and Received; the attachments as chips; then the body.
- **Attachments:** clicking a PDF or a picture previews it; anything else downloads. **Save to Matter** files it as a document of its own in the email's folder, with the email's sent properties and an `attachment` link, as filing from Outlook does (M5 §6.5). One already filed shows its #.
- **Pictures from the internet are blocked** (owner), with **Show Pictures** for that viewing only, so the sender's trackers never learn the email was opened. Pictures sent inside the message always show.
- **Open** opens the `.eml` or `.msg` through the helper, as double-click does.
- **How it's made safe:** §9.4.

### 6.7 Organising

- **New Folder** inside a matter (not at its top level, which stays admins' (M1)).
- **Rename** (F2, or More › Rename) edits the title in place.
- **Move:** drag rows onto a folder in the tree, or More › Move To…, which opens a picker that starts from Recent Matters and My Matters and searches matters (no Store browsing). Moving into another matter re-stamps the document's properties (M1), and once per matter applies (M5).
- **Delete** (the Delete key, or More › Delete) moves to the trash after a confirmation that names the documents. Restoring is the Trash's phase (§16); the confirmation says the Classic Interface has the Trash.
- **Copy Link** copies the document's `/app/doc/n` address (§10.1).

### 6.8 The look (owner on density)

- **Colours:** the helper's palette (its polish spec), as CSS custom properties defined for light and for dark. The dark accent is darkened so white text on it passes AA.
- **Type:** the system's own, San Francisco on the Mac and Segoe UI on Windows, as the helper's.
- **Icons:** Tabler's, as the helper's, drawn as SVG.
- **Rows are Standard, 34 pixels** (owner), with a Compact (28 pixels) or Standard setting in a later phase.

### 6.9 You

The person's menu, from the avatar in the top bar, has: their name; **Signed-in Devices**, listing their device tokens with Revoke (`GET` and `DELETE /auth/tokens`); **Classic Interface**, which goes to `frontend.jsp` on the same session; and **Sign Out** (§8.5).

### 6.10 No helper indicator (call)

A first mockup showed whether the helper was running. The browser can't tell whether a `healdan:` link was taken, and probing the helper's loopback ports from the page needs a permission prompt in current browsers. So Open in App keeps the old UI's behaviour (§10.6), and there's no indicator.

## 7. Where the app lives and how it's built (call)

### 7.1 A new module, `healdan-web`

- **A JPF plugin,** id `healdan-web`, which requires `healdan-api`, in the four-piece plugin pattern, under `com.logicaldoc.healdan.web` so CE's scans find it.
- **The source** is an npm workspace in `healdan-web/web/`:
  - `packages/ui`: Healdan's components on React Aria Components, the colour, type and spacing settings, the icons and the string catalogue;
  - `packages/api`: the TypeScript types generated from `openapi.yaml`, and a small client that sends `X-Healdan-Client: web` on every request that changes something;
  - `apps/user`: the User app.
- **Walls and Admin** later become `apps/walls` and `apps/admin`, each its own bundle, so a user never downloads their code (roadmap).

### 7.2 `AppServlet`

- **`@WebServlet("/app/*")`** serves the built files from the jar (`healdan/web/user/`).
- **Any path under `/app/` that isn't a file** gets `index.html`, so addresses like `/app/doc/10388` load the app.
- **Caching:** `index.html` is never cached; the other files carry a content hash in their names and are cached for a year, immutable.
- **Its own Content-Security-Policy,** in addition to CE's (both apply): `default-src 'self'`; `script-src 'self'`, with no inline script and no `eval`; `style-src 'self'`; `img-src 'self' data: blob:`; `worker-src 'self'` for PDF.js's worker; `connect-src 'self'`; `frame-src 'self'` for the email body (§9.4); `object-src 'none'`; `frame-ancestors 'none'`; `base-uri 'none'`; `form-action 'self'`. PDF.js runs with `isEvalSupported: false`.
- **Without a web session,** the servlet sets the return path and sends the browser to `login.jsp` (§8.3). The page itself is code, not data; everything it shows comes from the API.
- **No upstream file changes for this:** CE's chain already lets `/app/**` through (§3.2).

### 7.3 The build

- **`frontend-maven-plugin`** (Apache 2.0) installs a pinned Node into the build, then runs `npm ci --ignore-scripts`, the type check and the Vite build in `generate-resources`, writing into the jar's `healdan/web/`.
- **`-Dhealdan.web.skip=true` skips it,** as `-Dgwt.skipCompilation` does for GWT. CI's `build` check passes it, so its shards don't need Node.
- **The Docker image builds it for real,** with npm's cache in a BuildKit cache mount, as Maven's is.
- **A new `Web` check** (`.github/workflows/web.yml`), required on master like `Clients`, runs the type check, lint, the app's tests, the build and the licence check (§7.4). A pull request that touches neither `healdan-web/` nor `openapi.yaml` skips its steps and still reports it.
- **`code-changed.sh`** counts `healdan-web/` as code, because it ships in the WAR, and gains a `web` output for the `Web` check.

### 7.4 Dependencies

- **At runtime:** React and React DOM; React Aria Components; TanStack Query; TanStack Router; PDF.js (`pdfjs-dist`); Tabler's icons as tree-shaken SVG components. That's all.
- **For building and testing only:** Vite, TypeScript, Vitest, Testing Library, axe-core, ESLint, openapi-typescript and Playwright.
- **The rules:**
  - every version pinned exactly, with `package-lock.json` committed;
  - `npm ci --ignore-scripts`, so no package's install script ever runs;
  - `.github/scripts/check-web-licences.py` fails `build` when the lockfile has a package whose licence isn't MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC or 0BSD, naming it, with an `ALLOWED` list for reviewed exceptions, each with its reason;
  - the runtime packages' notices ship with the app, at `/app/notices.txt`, and Help links to them;
  - adding a runtime package is a decision for the spec or the plan, not the builder.
- **Licence headers:** Healdan's TypeScript, TSX, CSS and HTML files, and its config files written in TypeScript or JavaScript, carry the SPDX header, as everywhere else. `package.json` and the lockfile are data and don't.

## 8. The session and signing in (owner: shared web session; CE's sign-in page for now)

### 8.1 The web-session route (call)

`TokenFilter` gets a second way in, used only when a request has no `Authorization` header. It follows the Fetch Metadata pattern:

1. **Every request must say `Sec-Fetch-Site: same-origin`.** An `Origin` header, when present, must be the server's public address (`PublicAddress`, as M4's pages use it) or, without one, the scheme and host the request came to. A request without `Sec-Fetch-Site` comes from an old browser or isn't a browser, and is refused on this route.
2. **A request that changes something** (`POST`, `PATCH`, `PUT`, `DELETE`) must also carry `X-Healdan-Client: web`. Another origin can't send that header without a preflight, and the API's CORS never allows one with cookies (M2 §8). A `GET` changes nothing, and another site can neither send one as same-origin nor read its answer, so downloads, thumbnails, previews and the email body work as plain links and `<img>`s.
3. **The HTTP session,** found by Tomcat's HttpOnly `JSESSIONID` cookie, must hold an open CE session id in its `sid` attribute. The filter never takes `sid` from a parameter, a header or the `ldoc-sid` cookie, though `SessionManager.getSessionId` would.

From there it works as the token route does: it renews the CE session, puts an `LDAuthenticationToken` for it in `SecurityContextHolder` for the length of the request, and clears it afterwards. A request with a Bearer token goes the token route and ignores cookies. No one signs in on this route, so the throttle doesn't come into it. A refusal is `401 unauthenticated`.

### 8.2 Timeouts

CE's `session.timeout` applies (30 minutes idle as shipped). Every API call renews the session; the app sends no keep-alive, so a forgotten tab lets the session lapse. On a `401`, the app says you've been signed out and offers Sign In, which comes back to the same address.

### 8.3 The return path (call)

A full page load of an `/app/…` address without a web session reaches `AppServlet`. It stores the path and query as `RequestedUrl` in the HTTP session and redirects to `login.jsp`. Because `login.jsp` writes that value unescaped into a script, the servlet stores it only when:

- it starts with `/app/`;
- it has no `//`, no scheme and no backslash;
- every character is a letter, a digit or one of `/ . _ ~ % - ? & =`;
- it's at most 2,000 characters.

Anything else is stored as `/app/`. Both the password form and M4's provider buttons then come back to it (`SignInResource` already follows `RequestedUrl` under `login.jsp`'s conditions).

### 8.4 Where a plain sign-in lands (call)

- **A new setting, `ui.start`,** in `hd_setting`: `app` (the default) or `classic`, editable in Healdan Settings.
- **A `@WebFilter` on `/login.jsp`** sets `RequestedUrl` to `/app/`, or to `frontend.jsp` for `classic`, when the HTTP session has none. `login.jsp`'s `switch` parameter, which clears `RequestedUrl`, still works.
- **`SignInResource` falls back to the same setting** instead of `frontend.jsp`.
- **So with `app`, signing in again after the old UI's session lapsed lands in the app too.** Classic Interface takes you back. The old UI's own pages don't set `RequestedUrl`, so the filter can't tell where you were.
- No upstream file changes for this.

### 8.5 Signing out

**`DELETE /api/v1/session`** closes the CE session and ends the HTTP session; the app then goes to `login.jsp`. It's offered only on the web-session route; a token signs out with `DELETE /auth/tokens/current`, as before. Nothing signs the person straight back in (M4 §5.1).

### 8.6 Between the two UIs

- **The app's Classic Interface** goes to `frontend.jsp` on the same session.
- **The old UI gets an Open in Healdan link back,** through a one-line hook in its top bar (§14.1).

## 9. The API's additions (call)

### 9.1 New endpoints

All under `/api/v1`, all on both routes (token and web session) unless the table says otherwise, all checking permissions as M2 §7 does.

| Endpoint | For |
|---|---|
| `DELETE /session` | Signing out (§8.5); web-session route only |
| `PUT /me/matters/{id}`, `DELETE /me/matters/{id}` | Adding to and removing from My Matters |
| `GET /documents/recent?kind=email` | Recent › Emails |
| `GET /clients/recent` | Recent › Clients: the clients of your recent matters and My Matters |
| `GET /suggest?q=` | Suggestions as you type: matters, then a few documents by title |
| `PATCH /documents/{n}` | Rename |
| `POST /documents/{n}/move` | Move to another folder, with M1's re-stamping and M5's once per matter |
| `DELETE /documents/{n}` | To the trash |
| `GET /documents/{n}/preview?version=` | The preview PDF (§9.3) |
| `GET /documents/{n}/message` | A filed email's headers, its attachments (name, type, size, and the # of any already filed from it), and the address of its body |
| `GET /documents/{n}/message/body?pictures=` | The email's cleaned body as a page of its own (§9.4) |
| `GET /documents/{n}/message/parts/{id}` | An attachment, for its chip's preview or download |
| `POST /documents/{n}/message/attachments/{id}/file` | Save to Matter, reusing M5's filing |
| Filters on `GET /search` and `GET /folders/{id}/children` | `type`, `author`, `client`, `matter`, `class`, `edited`; and `facets=` for the counts (§9.2) |

Everything else the app uses exists. Dropping a file on a row uses `POST /documents/{n}/versions`. The inventory's M6 entries get their endpoints and tags in the same pull requests.

### 9.2 Filters and honest counts

- **Facet counts never count what the person can't read.** CE checks permissions after Solr answers, so Solr's own counts would include privileged documents. The counts come either from the hits that passed the read check or from a Solr filter on the folders the person can read. The trial (§12) tries both on the demo firm and picks.
- **File Type** groups extensions into kinds (Word, Excel, PowerPoint, PDF, Email, OpenDocument, Picture, Other). The list lives in one place on the server.
- **In a folder,** the counts come from the database, over the folder's documents the person can read.

### 9.3 Preview

`GET /documents/{n}/preview?version=` answers M1.5's PDF of that version: `202` with `Retry-After` while it's still converting, `404 no-preview` for a type that has none, and `503 storage-unreachable` when the file can't be reached. A picture previews from `/content`. The app asks again after `Retry-After` while Quick Look stays open.

### 9.4 Emails (refined while writing the spec)

The section design had the app clean an email's HTML with DOMPurify and show it in a sandboxed frame. A frame's document inherits the app page's Content-Security-Policy, so Show Pictures could only work by loosening that policy for the whole app. Instead:

- **The server cleans the body with jsoup** (already in the WAR): no script, no event handler, no form, no `<object>` or `<embed>`, no `javascript:` link, no `<base>`, no meta refresh. Links open in a new window with `noopener noreferrer`.
- **Inline pictures are embedded** in the page as `data:` addresses, up to a size the builder sets, past which they show as a placeholder. The frame is sandboxed without `allow-same-origin`, so its own requests come from an opaque origin, which the web-session route (§8.1) refuses as cross-site; embedding means it makes none, except for internet pictures once allowed.
- **`…/message/body` answers it as a page of its own,** with its own policy: `sandbox allow-popups allow-popups-to-escape-sandbox`; `default-src 'none'`; `style-src 'unsafe-inline'`; `img-src data:`, plus `https:` only with `pictures=true`; `font-src data:`; `frame-ancestors 'self'`. Without `pictures=true`, internet picture addresses are also removed from the HTML, so even a policy failure loads none. The app's own navigation of the frame is same-origin, so the body itself loads on the web-session route.
- **The app shows it in an `<iframe sandbox="allow-popups allow-popups-to-escape-sandbox">`**, so no script runs and links open in a new window. Show Pictures reloads the frame with `pictures=true`.
- **DOMPurify leaves the dependency list.**

### 9.5 Performance (M2's "before M6")

- **Folder listings** page, sort and filter in the database, keeping M2's batched read check: the folder's READ once, the documents with their own ACL in one query. A page reads only its own rows.
- **Search** pages through Solr with `start` and `rows` instead of stopping at about 1,000 engine hits, widening as M2's re-fetch loop does when the read check drops hits. The count stays honest: the real total, or "more than n" when counting exactly would be too slow; the plan measures which.
- **The target:** a matter folder of 2,000 documents opens, sorted any way, in under a second on the owner's server.

### 9.6 The contract

Every new endpoint goes into `openapi.yaml` in the same change. `RouteCoverageTest` and `ApiTestCase` check them as today, and the file describes both ways in (§8.1). `packages/api`'s types are generated from it at build time, so a change to the contract that breaks the app fails its type check.

## 10. Inside the app (call)

### 10.1 Addresses

| Address | Shows |
|---|---|
| `/app/` | The start page: Recent › Documents |
| `/app/recent/documents`, `…/emails`, `…/folders`, `…/clients` | The Recent section |
| `/app/folders/{id}` | A folder, or a matter's top level, with the tree opened to it |
| `/app/search?scope=…&q=…&type=…` | Results, with the filters in the address |
| `/app/doc/10388`, `/app/doc/10388.2` | The `#` result |

The selected row goes in the address (`?sel=10388`); Quick Look and the open panels don't. Back, bookmarks and pasted links all work. **Copy Link** gives `/app/doc/n`, so the doc # handle works as a link in an email too.

### 10.2 Data

- **TanStack Query** keeps each view's data, keyed by its address and filters.
- **It refreshes** when the window comes back into focus, and after the person's own changes, which refresh what they touch. That stands in for push updates until their phase (§16).
- **Big lists** load 100 rows at a time as you scroll, and React Aria's virtualiser draws only the rows on screen. The count is always the real total.
- **The browser keeps only preferences** (row height, later), wrapped so the app works when storage is refused.

### 10.3 Components and styles

- `packages/ui` wraps React Aria Components as Healdan's own: Button, Menu, Table, Tree, Dialog, ComboBox, DatePicker, Toast, the filing Tray and the Quick Look frame. Screens use only these, never React Aria directly.
- Styles are CSS Modules reading the colour, type and spacing settings, which are CSS custom properties defined for light and for dark.

### 10.4 Strings

One typed catalogue, `en-US`, in `packages/ui`. Plurals use the browser's `Intl.PluralRules`; dates and numbers use `Intl` with the person's locale. The configurable labels come from `GET /server`. Labels follow the docs site's Title Case.

### 10.5 Keyboard

`/` jumps to the search box. The arrow keys move through the list. Enter opens in the app. Space opens Quick Look; Esc closes it. F2 renames. Delete moves to the trash, after the confirmation. The tree, menus and dialogs follow React Aria's standard keys.

### 10.6 Open in App

Double-click, Enter, or Open builds the same `healdan:` link as the old UI (`HealdanOpenInApp.link`), then shows the old UI's notice: "If nothing opened, install the Healdan Helper", linking to the docs. A type the helper doesn't open downloads instead.

### 10.7 Errors

- **Each of the API's problem `code`s** maps to a message in the catalogue, so wording is decided in one place.
- **Not found and not allowed read the same:** "Not found, or you can't see it" (M2: unreadable means `404`).
- **`503 storage-unreachable`** shows a banner saying the files are unavailable for now.
- **Anything unexpected** gets a short message and Try Again, never a stack trace.
- **React escapes every value it shows,** so a name can't inject markup into the page (the lesson of the group names spec).

## 11. Security (calls)

- **Forgery:** §8.1's rule, with Tomcat's `SameSite=Lax` cookies behind it.
- **Theft:** the session cookie is HttpOnly, and the app keeps no token or secret in the browser.
- **Script injection:** `AppServlet`'s policy (§7.2) allows only the server's own scripts, with no inline script or `eval`; React escapes values; email bodies are cleaned on the server and shown in a sandboxed frame with their own policy (§9.4).
- **The return path** is checked before `login.jsp` sees it (§8.3).
- **Information leaks:** nothing unreadable appears in suggestions, results or counts (§9.2); unreadable is `404`; a `#` the person can't read gets the same words as one that doesn't exist.
- **Email trackers:** blocked unless the person asks (§6.6).
- **Dependencies:** pinned, without install scripts, and licence-checked (§7.4).
- **Framing:** `frame-ancestors 'none'` for the app; the email body only inside the app.
- **Session fixation (found in the build):** CE's `SessionFilter` binds a `sid` from a request into a signed-out browser's HTTP session. It takes one from `?sid=` only while `security.acceptsid` is on, which it isn't as shipped, or from an `ldoc-sid` cookie planted from a sibling subdomain or over plain HTTP. That's login CSRF on CE's pages and, through the HTTP session, on the web-session route: the victim's browser works as the attacker's account, so what the victim files lands where the attacker can read it. The attacker needs an account of their own on the install, and gets no access to the victim's. It predates M6, which doesn't widen it. A follow-up may refuse the web route while `security.acceptsid` is on, or accept only a `sid` that a real sign-in bound.

## 12. The trials (call)

Two questions could change the design, so they're answered before the build, as M5's trial was:

1. **Dragging from new Outlook for Mac into the browser.** Does a message dragged onto the page arrive as an `.eml` file, a link or nothing, in Safari and in Chrome? It runs on the owner's Mac. If nothing usable arrives, dragging from Outlook stays with M5's add-in, the app files `.eml` and `.msg` files dragged from Finder, and the User Guide says so.
2. **Facets in CE's Solr.** Whether Author, Class and the file's type are indexed so Solr can count them, and which of §9.2's two ways of counting only what the person can read answers in time on the demo firm with a privileged folder.

## 13. Delivery (call)

Four pull requests, each with its tests and its docs pages, and `test-web.sh` growing with each:

| PR | Holds | Ends with |
|---|---|---|
| **A · Foundation** | `healdan-web`, `AppServlet`, the web-session route, `DELETE /session`, the `login.jsp` filter and `ui.start`, the old UI's link back, `packages/ui` (the settings in light and dark, the core components, the strings), `packages/api`, the app's frame and addresses, the `Web` check and the licence check, the Docker build | Signing in lands in the app; the left pane works; links survive signing in |
| **B · Finding** | Folder paging in the database, search paging, filters and honest counts, `/suggest`, the Recent section, the My Matters tree with add and remove, the search page, the `#` result, the details panel, Copy Link | §4's Finding and Looking rows |
| **C · Working** | Preview (Quick Look, the reading pane, PDF.js), the email view and Save to Matter, Open in App, Download, check-out, Edit properties, versions | §4's Working rows |
| **D · Filing and organising** | Dropping files with the tray and the form, dropping on a row, New Folder, Rename, Move, Delete, Signed-in Devices | The rest of §4 |

A last small pull request records the result: the roadmap, the plan's ledger and the follow-ups.

## 14. Upstream files, fork-owned files and docs pages

### 14.1 Upstream files

| File | Edit | Why |
|---|---|---|
| `pom.xml` (root) | `<module>healdan-web</module>` | The module joins the reactor |
| `logicaldoc-webapp/pom.xml` | An `artifactItem` for `healdan-web`'s plugin zip | Without it the plugin isn't shipped |
| An old-UI top-bar class in `logicaldoc-gui` (the builder picks it) | A one-line hook adding **Open in Healdan** | The way back to the app (§8.6) |

Nothing else upstream changes: not `context-security.xml`, `login.jsp` or `web.xml`.

### 14.2 Fork-owned files

- **New:** the `healdan-web` module (its Java, its `plugin.xml`, its assembly and its `web/` workspace); `.github/workflows/web.yml`; `.github/scripts/check-web-licences.py` and its tests; `docker/tests/test-web.sh`.
- **Changed:** `healdan-api`'s `TokenFilter`, `SignInResource`, `openapi.yaml`, its resources for the new endpoints and their tests; `healdan-core` for `ui.start`, folder paging, search paging, facets, suggestions, the email body's cleaning, and Recent Clients; `.github/scripts/code-changed.sh`; `.github/workflows/build-logicaldoc.yml` (the skip flag); `docker/smoke-test.sh` (the new step); the `Dockerfile` (npm's cache mount); `docs/reference/healdan-api-inventory.md`; `docs/ROADMAP.md`; `CLAUDE.md` (`healdan-web`'s build, test and CI facts).

### 14.3 Docs pages (the docs site, `site/content/`)

Labels are quoted exactly as the app shows them, in US English, while the prose stays British (a new rule on Writing Docs). Screenshots come from the demo firm.

- **User Guide, rewritten for the app:** Signing In; Getting Around; Searching; Finding by Doc #; My Matters and Recent Matters; Search Folders; Filing a Document; Emails; Properties; Stores and Matter Workspaces; Previews; Versions; Checking Out and Checking In; Downloading Documents; Copying, Moving and Deleting.
- **User Guide, new:** The Classic Interface: what's still there, how to get to it, and that it's the same sign-in.
- **User Guide, unchanged but opening with a note that they're in the Classic Interface:** Aliases; Bookmarks; Links Between Documents; Notes; Sharing a Document; Tags; Locking a Document; Document History; The Trash; Document Permissions; Your Profile and Password; Messages.
- **Admin Guide:** Healdan Settings (`ui.start`).
- **Install manual:** Upgrading (the app arrives; `ui.start`); Reverse Proxy (pass `Origin` and `Sec-Fetch-*`, which nginx and NPM do by default); Known Limitations (what's still classic, the browsers supported); Troubleshooting (being signed out; "Not found, or you can't see it").
- **Developer Guide:** a new page, The Web App (the workspace, running Vite's dev server against a local stack, adding a view, the catalogue, the colour, type and spacing settings, the dependency rules); API (the web-session route and the new endpoints); How Healdan Extends LogicalDOC (`healdan-web`, `AppServlet`, the `login.jsp` filter); Building from Source (Node through Maven, the skip flag); Tests (the app's tests, `test-web.sh`); Continuous Integration (the `Web` check); Writing Docs (the label rule).

## 15. Verification

### 15.1 Automated

- **Server (JUnit, in CI's shards):**
  - every new endpoint through `ApiTestCase`, against `openapi.yaml`;
  - the web-session route: cross-site and missing `Sec-Fetch-Site`; a change without `X-Healdan-Client`; a stale session; a token beside a cookie; `sid` as a parameter, a header or the `ldoc-sid` cookie;
  - `AppServlet`: the fallback to `index.html`, the cache headers, its policy, and every rejected return path (quotes, `//`, a scheme, a backslash, over-long) becoming `/app/`;
  - the `login.jsp` filter and `ui.start`, with `SignInResource` landing in the same place;
  - facet counts on a fixture with a privileged folder, never counting what the caller can't read;
  - paging on a 2,000-document folder, asserting a page reads only its own rows;
  - the email body: scripts, handlers, forms and internet pictures removed, inline pictures embedded, the page's policy, and `pictures=true`.
- **App (Vitest and Testing Library, in the `Web` check):** each `packages/ui` component with an axe check; each view against a small fake API typed from `openapi.yaml`, written by hand; the catalogue and the problem-code mapping.
- **End to end (Playwright, in Chromium and WebKit, in Playwright's container, as a new smoke-test step running `docker/tests/test-web.sh` on amd64 and arm64):** with the smoke fixture, follow a deep link, sign in on `login.jsp` and land on it; open matter 1.1; file a file by drop and see its #; find it with `#n`; Quick Look; rename, move and delete; the filters' counts; the keyboard paths; an axe pass on each main view; sign out. `curl` checks that a cross-site request and a change without the header are refused.
- **The licence check's own tests,** in `.github/scripts/tests`.

### 15.2 Manual acceptance (owner)

On the owner's server, in Safari, against an image built from the last PR:

1. **Signing in.** A plain sign-in lands on Recent › Documents. A `/app/doc/n` link opened while signed out comes back to that document after signing in, by password and through Authentik. With `ui.start` = `classic`, a sign-in lands in the old UI.
2. **The left pane.** Recent's four entries; Recent Matters; the My Matters tree, adding and removing a matter; My Favorites.
3. **Search.** Suggestions; results for a word; Filters with honest counts, checked as someone who can't read a privileged folder; `#n`, `#n.N`, a `#` you can't read, a matter number with the Matters scope; Copy Link pasted into an email.
4. **Speed.** A 2,000-document folder opens in under a second, sorted by each column; a search with more than 1,000 hits pages to its end.
5. **Preview.** Space, the arrow keys, Esc; docking as a reading pane; a document still converting.
6. **Email.** Quick Look on an email; Show Pictures; Save to Matter on an attachment; Open.
7. **Working.** Open in App by double-click and Enter; check-out, editing in Word, a save, the new version showing after coming back to the window; Download; Edit properties, with an override.
8. **Filing.** Three files from Finder, one an email already in the matter; the tray; ⌥ for the form; a drop on a row as a new version; a drop on a matter's top level refused; from Outlook as the trial found.
9. **Organising.** New Folder; F2; Move by dragging to the tree and by the picker, including into another matter; Delete.
10. **The look and the keyboard.** Light and dark, switched in macOS; one pass through rows 3 to 9 by keyboard alone.
11. **You.** Signed-in Devices, revoking a helper token; Classic Interface and Open in Healdan; Sign Out landing on the sign-in page without signing back in.
12. **A full working day** in the app, going to the Classic Interface only for §16's features. Anything that sent the owner there for another reason is a punch-list item.

## 16. After M6

Later phases, each designed when its turn comes, move features off the old UI (owner: "we will migrate any needed remaining features in phases with the goal to eventually retire the old UI completely"):

- the Trash (restore and purge, and an Undo after Delete);
- permissions on documents and folders;
- history;
- notes and links between documents;
- sharing: shared links, emailing from the server, zip export;
- your profile and password;
- making and editing search folders;
- choosing and reordering columns;
- setting properties on many documents;
- renaming, moving and deleting folders;
- copying and locking documents;
- push updates (Server-Sent Events at `/api/v1/events`, M2 §9);
- Healdan's own sign-in page;
- the Compact row setting.

Then the Admin app (backlog), and then the old UI retires. M7's Walls app builds on `packages/ui` and the web-session route.

## 17. Left to the builder

- The exact names of the new endpoints' fields and problem codes, within §9 and M2's conventions.
- Which old-UI class takes the Open in Healdan hook.
- The suggestion list's sizes and its debounce.
- The PDF.js, Vite and React Aria settings that keep `AppServlet`'s policy (§7.2) without loosening it. Styles set from script through the CSSOM, as React does, are allowed by `style-src 'self'`; a library that adds `<style>` elements or `style` attributes in markup isn't, and needs a hash or a different setting rather than `'unsafe-inline'`.
- The order of work inside each PR.

## 18. Decisions made in this session

| # | Decision | By |
|---|---|---|
| 1 | M6 is the Daily Driver: a working day in the app. It's the foundation later phases build on until the old UI retires | owner |
| 2 | The app shares the browser's CE web session; the API accepts it on a narrow route | owner |
| 3 | CE's sign-in page stays for M6, with a checked return path; Healdan's own page comes before the old UI retires | owner |
| 4 | Components are React Aria Components with Healdan's own styles | owner |
| 5 | The Daily Driver's feature list (§4) | owner |
| 6 | The baseline (§5), with US English as the default language | owner |
| 7 | Filters open from a funnel button above the list, in the details' region; not as tabs | owner |
| 8 | With Filters open, clicking a document shows its details; the funnel brings Filters back | owner |
| 9 | The left pane's top section is "Recent"; each entry opens its recent items | owner |
| 10 | Suggestions as you type | call |
| 11 | Preview is both: Quick Look on Space, dockable as a reading pane | owner |
| 12 | A drop files at once, unless a required field has no default or ⌥ is held | owner |
| 13 | Pictures from the internet in emails are blocked, with Show Pictures | owner |
| 14 | Rows are Standard (34 px), with a Compact setting later | owner |
| 15 | No helper indicator | call |
| 16 | `healdan-web`: an npm workspace for User, Walls and Admin; built through Maven with a skip flag; a required `Web` check; a short, pinned, licence-checked dependency list | call |
| 17 | The web-session route follows Fetch Metadata: same-origin always, and a custom header for changes | call |
| 18 | `ui.start` decides where a plain sign-in lands, `app` by default | call |
| 19 | The API's additions (§9), with honest counts and the one-second target | call |
| 20 | Every view has an address; Copy Link gives `/app/doc/n` | call |
| 21 | Testing in three layers, with Playwright in Chromium and WebKit on the smoke stack | call |
| 22 | The User Guide is rewritten for the app; labels are quoted in US English, prose stays British | call |
| 23 | Two trials before the build; four pull requests | call |
| 24 | Email bodies are cleaned on the server with jsoup and served as their own sandboxed page; DOMPurify isn't used | call (refined while writing the spec) |
