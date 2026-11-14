# M6 "New web UI: User app": design

- **Milestone:** M6 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-11-10
- **Status:** design agreed with the owner on 2026-11-10. The scope and the foundations were settled question by question, the layout through mockups the owner reviewed in the browser (§6, [`2026-11-10-m6-mockups/`](2026-11-10-m6-mockups/README.md)), and the rest section by section. The owner approved this written spec the same day. Implementation plan: [`docs/superpowers/plans/2026-11-10-m6-user-app.md`](../plans/2026-11-10-m6-user-app.md); approved the same day, for subagent-driven building; its "Spec corrections and calls this plan makes" lists where it departs from this spec. **Built** on 2026-11-10 to 2026-11-12, subagent-driven, each task reviewed against this spec before the next began, then the whole branch reviewed, in four stacked pull requests: [Healdan-Project/healdan#220](https://github.com/Healdan-Project/healdan/pull/220) (A, Tasks 1 to 8), [#221](https://github.com/Healdan-Project/healdan/pull/221) (B, Tasks 9 to 15), [#222](https://github.com/Healdan-Project/healdan/pull/222) (C, Tasks 16 to 18) and [#224](https://github.com/Healdan-Project/healdan/pull/224) (D, Tasks 19 to 21), with PR E for the records; the owner decided to merge them together. The plan's corrections and the build's rulings are folded in below, each marked "changed in the build" with its ruling number (R1 to R96, in the plan's decision log). Waiting for the owner's walkthrough (§15.2).
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
| **Dates and numbers** | In the browser's time zone and the person's locale, as the helper shows them. The Edit Date filters follow the browser's zone too: the app sends it as `zone` (changed in the build: R90, from the final review; the server had cut Today and the days in its own zone). Counts are written with US English's grouping, as 1,234, whatever the locale, as the catalogue is US English (changed in the build: R29). |
| **Light and dark** | Both from the start, following the system's setting. A switch in the app can come later. |
| **Screen sizes** | Designed for 1,280 pixels and wider; works at 768, with the side panels folding away. Phones aren't a goal. |
| **Accessibility** | WCAG 2.2 AA: contrast, a focus ring you can always see, everything by keyboard, labels for screen readers. |
| **Keyboard** | `/`, the arrow keys, Enter, Space, Esc, F2 and Delete from the start (§10.5). |
| **Browsers** | Current Safari, Chrome, Firefox and Edge: the releases of the last two years. The app needs a secure address, HTTPS or the machine itself: at any other, browsers send no `Sec-Fetch-Site`, so the web-session route (§8.1) can't work, and the app shows a page of its own saying so, with a link to the Classic Interface (changed in the build: R91, from the final review). |
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
- **The list's columns** (changed in the build): Version is the file's version, which the list and the doc ID show, and sorts by it, major then minor (R9, R36; CE's own version moves at every property edit). Edit Date is when the document's file last changed: CE's `ld_date`, set at filing and at each new file version, else the creation date, so a property change, a rename or a move doesn't change it (R85; CE's last-modified time, which every store rewrites, the Indexer's included, made every Edit Date the day the Indexer reached the document). Titles sort by file name, and doc #s as numbers (the plan's correction 7). A held document's title ends with a marker, on every list of documents: a pencil for a check-out, as Check Out's own icon, and a padlock for a lock, whose accessible name and tooltip say who has it and since when, as the details do, without where it's released, and which a hovered row, or one with the keyboard's focus, shows as text after the icon; rows stay 34 pixels (changed in the build: the owner's call after walkthrough row 7, 2026-11-13, made with §10.2's refresh, so the marker shows on coming back to the window).
- **The toolbar:** Upload and New Folder; then Open, Preview, Download, Check Out and More (⋯) for the selection, as icons with their labels beside them (changed in the build: R94, from the final review; the spec had labels on hover, but labels shown are easier to find and are the buttons' accessible names, and a toolbar too narrow for them wraps whole buttons onto a second row); the count ("7 documents", "3 of 7 documents", or "More than 10,000 documents" when the total is a floor, R5); and the **Filters** button (§6.2). Recent's Documents and Emails and the `#` result have the same toolbar for their selection (changed in the build: R95).
- **The right region:** the selected document's details, or Filters (§6.2), or the docked preview (§6.4). With nothing selected and Filters closed, it folds away and the list takes the width.
- **A narrower window** (changed in the build, Task 21): below 1,024 pixels the left pane and the right region fold away behind the top bar's **Navigation** and **Details and Filters** buttons and open over the list, where Esc closes the one opened last; a click on a row doesn't open the region.

### 6.2 Details and Filters (owner)

- **Filters open from a funnel button** at the right end of the list's toolbar, and show in the same region as the details. They aren't tabs beside the details: details only make sense when something is selected (owner).
- **Clicking a document shows its details** (owner). Once the filters are set and you click away, they count as set; the funnel brings them back to change them.
- **The button's badge counts the filters in use,** and **chips above the list** name each one, with × to drop it and Clear All. A hidden filter can never make a document seem lost.
- **Facets:** each option shows how many documents it would leave (§9.2). Edit Date offers Any Time, Today, Last 7 Days, Last 30 Days, This Year and Between…; File Type, Author and Class are tick lists. Inside a matter, Client and Matter aren't offered; in search results they are. Today, the presets and Between's days are cut in the browser's time zone (changed in the build: R90). When a search's counts cover only part of its hits, the panel says "Counts from the most relevant matches", with no number (changed in the build: R38), and an option ticked that the counts don't reach shows without a count.
- **The details panel:** a thumbnail with Preview; the name, with a star for Favorites (changed in the build: R93, from the final review; spec §4 has adding and removing Favorites and §6.1 a My Favorites of documents and folders, and the build had starred only folders); `# n · Version v · size`; Open, Download, Edit and Copy Link (Copy Link changed in the build: R44); the properties (Location in search results, which goes to the folder; Client, Matter, Class, Subclass, Author, Typist, Date Filed; an email's From, To, Sent and Received); and the versions, 200 a page with Show More Versions. Edit opens the properties for editing in place, with the same form rules as filing (one form, `FieldsForm`, serves both: R68). Author and Typist are picked from the server's people (`GET /v1/users`) by name (changed in the build: R92, from the final review; the build had shown them as text, so they couldn't be changed in the app). A value of the document's own that takes the place of a folder's shows "Overridden" with Follow Folder, from the properties' `overrides` (changed in the build: R66; `source: local` alone can't tell an override from a value no folder sets). Client and Matter, and the Message-ID, aren't editable (R84).

### 6.3 Search

- **Suggestions as you type** (call): matching matters first, then a few documents by title, then "Search Documents for "…"" with Enter. The arrow keys pick a suggestion.
- **Enter runs the search** in the chosen scope. Results come by relevance; any column header sorts them instead. Document results add a Matter column: the matter number with the client muted, and the matter's name on hover.
- **`#10388` then Enter** shows results with just that document, already selected with its details showing, ready to act on (roadmap principle 1), with the toolbar, F2 and Delete as in any list (changed in the build: R95). `#10388.2` selects version 2 in the details. A number the person can't read gets "No document # 10388 that you can see", the same words whether it exists or not. So does a legacy shortcut's number (changed in the build: R43; the API lists no shortcuts, so the lookup and `GET /documents/{n}` refuse them as unreadable).
- **With the Matters scope, a matter number** such as `1001.1` then Enter opens the matter (M1).
- **Nothing the person can't read appears,** in suggestions, results or counts.

### 6.4 Preview (owner: both)

- **Space opens Quick Look:** a large preview over the app, as Finder's does, from every document list (R52). ‹ › and the arrow keys move through the list behind it, except while the focus is in the page, where they scroll it (changed in the build: R55, as a zoomed page must scroll by keyboard); Space or Esc closes it. Nothing behind it reflows.
- **A button in Quick Look docks it** as a reading pane in the right region, which stays docked while you click or arrow down the list, until you close it. The list keeps Title, Doc # and Edit Date while it's narrow.
- **While it's docked, the reading pane takes the details' place:** clicking a row previews it. The funnel shows Filters there until you next click a row, as with the details (§6.2). Closing the reading pane brings the details back.
- **What's shown:** M1.5's PDF, drawn in the page with PDF.js; a picture as itself; an email as a message (§6.6). A type with no preview says so, with Open and Download.

### 6.5 Filing by drag and drop (owner: at once, unless something's missing)

- **Where:** anywhere on a folder's list, on a subfolder's row in it, which files into that subfolder (changed in the build: R69), or on a folder in the My Matters tree, each lit while dragged over. A matter's top level and search folders refuse: "Choose a folder inside the matter" (M1's filing rule). A drop outside every target is cancelled, so the browser never opens the file in place of the app.
- **A drop files at once** with the defaults: the folder's own Class if it sets one, else the template's default; you as Author and Typist; Client and Matter from the matter; the file's name as the title.
- **The form appears first** when a required field has no default, or when you hold ⌥ while dropping, or press Upload with ⌥ (the keyboard's way to it). It lists the files with editable titles and one form for them all, and its button says how many it will file. It is Edit's form (R68), so Author and Typist can be picked there too (changed in the build: R92).
- **A tray** in the corner shows each item: filed with its #, uploading, or refused. Each filed item has Edit. New rows flash in the list.
- **Emails** (`.eml`, `.msg`) take their subject as the title and fill their email fields themselves (M1). One already in the matter isn't filed again: the tray says "Already in this matter as # n" with Show (M5's `409 already-filed`).
- **Dropping one file on a document's row** asks "Save as Version n of # m, or File as a New Document?", only when the file's extension is the document's (any case); otherwise it files a new document, as a drop on the list does. File as a New Document is the question's default, for Enter (changed in the build: R70; a stray version is worse than a stray document). The version goes through Check In's call and never takes over a hold (R14).
- **Uploads go on** while you move about the app. Leaving the page while any are uploading asks first.
- **From Outlook:** the trial's first row (§12) decides whether dragging a message out of new Outlook for Mac gives the page a file to file. It does in Safari only (§12's results): the app files it as any dropped file, and Known Limitations says Chrome gets nothing, so a message is dragged to Finder first or filed from Outlook's Healdan pane there. A drop that carries a link or text says so.

### 6.6 Reading an email (owner on pictures)

- **Quick Look and the reading pane show a filed email as a message:** the subject; the sender and their address; To and Cc; Sent and Received; the attachments as chips; then the body. An `.msg` shows the same way, read through CE's own reader (changed in the build: R58); it keeps no Content-IDs, so its inline pictures show as attachments.
- **Attachments:** clicking a PDF or a picture previews it in place of the body, with Back to Message, through the same viewers as Quick Look's, and is recorded as no download; anything else downloads, which needs the Download permission and is recorded as a download of the email (changed in the build: R56). **Save to Matter** (Save to and the firm's word for a matter) files it as a document of its own in the email's folder, with an `attachment` link, as filing from Outlook does (M5 §6.5). Its properties are the email document's own, without its email fields, its title and its legacy doc #, and any the request sends win (changed in the build: R60, R62). One already filed shows its #: a copy pairs with its part by name and size, then by name, then a renamed copy by size, then the last of each left (changed in the build: R61, and the final review's fix for a copy renamed in the app).
- **Pictures from the internet are blocked** (owner), with **Show Pictures** for that viewing only, so the sender's trackers never learn the email was opened. Pictures sent inside the message always show.
- **Open** downloads the `.eml` or `.msg`, as double-click does (changed in the build: R64). The helper opens only office files and PDFs, so an email is a type it doesn't open (§10.6); opening it in Mail through the helper would be a helper change, for later.
- **How it's made safe:** §9.4.

### 6.7 Organising

- **New Folder** inside a matter (not at its top level, which stays admins' (M1)).
- **Rename** (F2, or More › Rename) edits the title in place. It keeps the document's extension: `Lease` becomes `Lease.docx`. Another extension is refused as `422 validation` with the cause `extension`, as a new version is, and `invalid` is kept for `/`, `\`, control characters and a character the database can't store (changed in the build: R74; the app shows each cause's own message, where it had guessed the extension rule from `invalid`). A refusal for a hold says who holds the document and where it's released.
- **Move:** drag rows onto a folder in the tree, or More › Move To…, which opens a picker that starts from Recent Matters and My Matters and searches matters (no Store browsing). Moving into another matter re-stamps the document's properties (M1), and once per matter applies (M5). Rows drag with the browser's own drag and drop, carrying a token of the page the drag began in, so a drop from another page moves nothing; Move To… is the keyboard's way (Task 20).
- **Delete** (the Delete key, or More › Delete) moves to the trash after a confirmation that names the documents. Restoring is the Trash's phase (§16); the confirmation says the Classic Interface has the Trash. A document checked out, by anyone, the person included, is refused, as the Classic Interface greys Delete out (changed in the build, Task 20).
- **Delete and Move act on the whole selection,** each document its own call, so what's refused is said per document while the rest is done; Rename takes one (changed in the build: R72). They work in a folder, in search results, on Recent's Documents and Emails and on the `#` result (changed in the build: R95, from the final review).
- **Copy Link** copies the document's `/app/doc/n` address (§10.1). It is in the details panel (R44) and More.

### 6.8 The look (owner on density)

- **Colours:** the helper's palette (its polish spec), as CSS custom properties defined for light and for dark. The dark accent is darkened so white text on it passes AA. The filled Favorites star keeps the helper's amber, with an edge of at least 3:1 in light mode (changed in the build: R37). Busy states keep AA contrast in both themes: a list being fetched again shows a pulsing strip rather than dimming, and only Filters' counts dim (changed in the build: R79).
- **Type:** the system's own, San Francisco on the Mac and Segoe UI on Windows, as the helper's.
- **Icons:** Tabler's, as the helper's, drawn as SVG.
- **Kind colours:** an icon that stands for a kind of item is drawn in that kind's colour, wherever it stands for one: list rows, the left pane's tree, its Recent entries and My Favorites (which had drawn every favourite with a star), with a matter's own folder as its briefcase wherever it shows as a folder (a favourite, or a hit of the Folders scope), suggestions, the Move To… picker, the details' thumbnail, a type with no preview, an email's attachments, and the scope picker's button and its options, which now show each scope's icon. The file's extension decides both its icon and its colour, by the server's kinds of file (`FileKinds`, the File Type filter's list), and LibreOffice's (ODF) formats take their Office counterparts' colours (owner, 2026-11-14): Word's formats show DOCX (`.docx`, `.docm`, `.dotx`, `.dotm`) or DOC (`.doc`, `.dot`) and RTF and ODF text documents the plain document, in blue; Excel's show XLS (`.xlsx`, `.xlsm`, `.xlsb`, `.xltx`, `.xltm`, `.xls`, `.xlt`) and ODF spreadsheets the spreadsheet, in green; PowerPoint's show PPT (`.pptx`, `.pptm`, `.ppsx`, `.ppsm`, `.potx`, `.potm`, `.ppt`, `.pps`, `.pot`) and ODF presentations the presentation, in orange-red; PDFs are red, emails blue and pictures purple. A CSV, which the File Type filter counts as Other, has its own icon in no kind's colour. Folders and search folders are the accent, as the helper draws them, matters amber and clients indigo. Any other file, a drawing included, keeps the plain document in the colour of what it sits in, as Documents and All do in the scope picker, and action and frame icons keep theirs. The colours are `--hd-kind-*`, light and dark, each at least 3:1, as an icon needs (WCAG 1.4.11), on every surface such an icon sits on. On the accent fill of a selected row or a focused option the icon turns white with the text; on the grey selection and the open entry it keeps its colour, which is 3:1 there too (changed after M6: the owner's dogfooding punch list, 2026-11-14).
- **Rows are Standard, 34 pixels** (owner), with a Compact (28 pixels) or Standard setting in a later phase.

### 6.9 You

The person's menu, from the avatar in the top bar, has: their name; **Signed-in Devices**, listing their device tokens with Revoke (`GET` and `DELETE /auth/tokens`); **Classic Interface**, which goes to `frontend.jsp` on the same session; and **Sign Out** (§8.5). Only the Healdan Helper signs in as a device: the Office add-ins and the LibreOffice extension work through it and hold no token, so the list is each computer's helper and any program signed in through the API, and the web page's own sign-in isn't in it (changed in the build: R78).

### 6.10 No helper indicator (call)

A first mockup showed whether the helper was running. The browser can't tell whether a `healdan:` link was taken, and probing the helper's loopback ports from the page needs a permission prompt in current browsers. So Open in App keeps the old UI's behaviour (§10.6), and there's no indicator.

## 7. Where the app lives and how it's built (call)

### 7.1 A new module, `healdan-web`

- **A JPF plugin,** id `healdan-web`, which requires `healdan-api`, in the four-piece plugin pattern, under `com.logicaldoc.healdan.app` so CE's scans find it (changed in the build: the plan's correction 1; `healdan-core` already has `com.logicaldoc.healdan.web`, for `PublicAddress` and its GWT RPC servlets).
- **Only the root context** is supported (changed in the build: R20): Vite's base is `/app/`, and the app signs in at `/login.jsp` and goes to `/frontend.jsp`, as the Docker image deploys Healdan. Known Limitations and Reverse Proxy say so; another path is a follow-up.
- **The source** is an npm workspace in `healdan-web/web/`:
  - `packages/ui`: Healdan's components on React Aria Components, the colour, type and spacing settings, the icons and the string catalogue;
  - `packages/api`: the TypeScript types generated from `openapi.yaml`, and a small client that sends `X-Healdan-Client: web` on every request that changes something;
  - `apps/user`: the User app.
- **Walls and Admin** later become `apps/walls` and `apps/admin`, each its own bundle, so a user never downloads their code (roadmap).

### 7.2 `AppServlet`

- **`@WebServlet("/app/*")`** serves the built files from the jar (`healdan/app/user/`, changed in the build: the plan's correction 1).
- **Any path under `/app/` that isn't a file** gets `index.html`, so addresses like `/app/doc/10388` and `/app/doc/10388.2` load the app. A file is one the build holds, or any path under `assets/`, where a missing one is `404`.
- **Caching:** `index.html` is never cached; the files under `assets/` carry a content hash in their names and are cached for a year, immutable; PDF.js's data under `pdfjs/`, named as PDF.js names it, is kept and checked again on each use (`no-cache` with an `ETag`, changed in the build: PR C's review); and the rest aren't cached.
- **Its own Content-Security-Policy,** in addition to CE's (both apply): `default-src 'self'`; `script-src 'self'`, with no inline script and no `eval`; `style-src 'self'`; `img-src 'self' data: blob:`; `worker-src 'self'` for PDF.js's worker; `connect-src 'self'`; `frame-src 'self'` for the email body (§9.4); `object-src 'none'`; `frame-ancestors 'none'`; `base-uri 'none'`; `form-action 'self'`. PDF.js 6 evaluates no code, so it has no `isEvalSupported` and isn't passed it; it runs with `useWasm: false`, with its worker, CMaps, standard fonts and image decoders served from `/app/`, and `scripts/pdfjs-policy.test.mjs` fails an upgrade that brings `new Function` back (changed in the build: Task 16; the spec named the older option). `AppServletTest` checks the policy's own text, so loosening it fails a test (the final review). CE's own `security.csp` header applies beside it.
- **Without a web session,** the servlet sets the return path and sends the browser to `login.jsp` (§8.3). The page itself is code, not data; everything it shows comes from the API.
- **No upstream file changes for this:** CE's chain already lets `/app/**` through (§3.2).

### 7.3 The build

- **`frontend-maven-plugin`** (Apache 2.0) installs a pinned Node into the build, then runs `npm ci --ignore-scripts`, the type check and the Vite build in `generate-resources`, writing into the jar's `healdan/app/user/` (changed in the build: the plan's correction 1). The Node download is trusted over HTTPS, as Maven Central's jars are, with no checksum check (R13; a follow-up).
- **`-Dhealdan.web.skip=true` skips it,** as `-Dgwt.skipCompilation` does for GWT. CI's `build` check passes it, so its shards don't need Node.
- **The Docker image builds it for real,** with npm's cache in a BuildKit cache mount, as Maven's is.
- **A new `Web` check** (`.github/workflows/web.yml`), required on master like `Clients`, runs the type check, lint, the app's tests and the build. The licence check (§7.4) runs in `build`'s `checks` job, beside the other quick checks, not in `Web` as well (changed in the build: the plan's correction 5). A pull request that touches neither `healdan-web/` nor `openapi.yaml` skips its steps and still reports it. The owner made `Web` required in master's ruleset once PR A had run it (2026-11-10; the plan's correction 11).
- **`code-changed.sh`** counts `healdan-web/` as code, because it ships in the WAR, and gains a `web` output for the `Web` check.

### 7.4 Dependencies

- **At runtime:** React and React DOM; React Aria Components; TanStack Query; TanStack Router; PDF.js (`pdfjs-dist`); Tabler's icons as tree-shaken SVG components. That's all, but for `@internationalized/date`, React Aria's own date library, a direct dependency of `packages/ui` for the date pickers, at the version React Aria already shipped (changed in the build: R41, Apache-2.0, nothing new ships).
- **For building and testing only:** Vite, TypeScript, Vitest, Testing Library, axe-core, ESLint, openapi-typescript and Playwright.
- **The rules:**
  - every version pinned exactly, with `package-lock.json` committed;
  - `npm ci --ignore-scripts`, so no package's install script ever runs;
  - `.github/scripts/check-web-licences.py` fails `build` when the lockfile has a package whose licence isn't MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC or 0BSD, naming it, with an `ALLOWED` list for reviewed exceptions, each with its reason. A package marked `dev` in the lockfile, which never ships, may also carry BlueOak-1.0.0, CC0-1.0, CC-BY-4.0, Python-2.0 or Unlicense, as Vite's and ESLint's trees do (changed in the build: the plan's correction 6). `isbot`, which TanStack Router ships, is in `ALLOWED` under the Unlicense, a public-domain dedication (R12, the owner agreeing on 2026-11-10), and `ALLOWED_DEV` holds the development-only exceptions under MPL-2.0 (`axe-core`, and `lightningcss` with its platform builds), which an entry fails on if the package ever ships. An expression such as `(MIT OR Apache-2.0) AND GPL-3.0` is read with its parentheses, so it fails (R11);
  - the runtime packages' notices ship with the app, at `/app/notices.txt`, and Help links to them;
  - adding a runtime package is a decision for the spec or the plan, not the builder.
- **Licence headers:** Healdan's TypeScript, TSX, CSS and HTML files, and its config files written in TypeScript or JavaScript, carry the SPDX header, as everywhere else. `package.json` and the lockfile are data and don't.

## 8. The session and signing in (owner: shared web session; CE's sign-in page for now)

### 8.1 The web-session route (call)

`TokenFilter` gets a second way in, used only when a request has no Bearer `Authorization` header (another scheme's, such as Basic credentials for a proxy in front, signs nothing in). It follows the Fetch Metadata pattern:

1. **Every request must say `Sec-Fetch-Site: same-origin`.** An `Origin` header, when present, must be the server's public address (`PublicAddress`, as M4's pages use it) or the scheme and host the request came to. A request without `Sec-Fetch-Site` comes from an old browser or isn't a browser, and is refused on this route.
2. **A request that changes something** (`POST`, `PATCH`, `PUT`, `DELETE`, in any case, as CXF matches them) must also carry `X-Healdan-Client: web`. Another origin can't send that header without a preflight, and the API's CORS never allows one with cookies (M2 §8). A `GET` changes nothing, and another site can neither send one as same-origin nor read its answer, so downloads, thumbnails, previews and the email body work as plain links and `<img>`s.
3. **The HTTP session,** found by Tomcat's HttpOnly `JSESSIONID` cookie, must hold an open CE session id in its `sid` attribute. The filter never takes `sid` from a parameter, a header or the `ldoc-sid` cookie, though `SessionManager.getSessionId` would.

From there it works as the token route does: it renews the CE session, puts an `LDAuthenticationToken` for it in `SecurityContextHolder` for the length of the request, and clears it afterwards. A request with a Bearer token goes the token route and ignores cookies. No one signs in on this route, so the throttle doesn't come into it. A same-origin request that breaks a rule is `403 web-session-refused`, which the app doesn't take for a sign-out; no open session, or a request no browser made from this origin, is `401 unauthenticated`.

The method the route checks is the request's own: CXF's `_method` parameter and `X-HTTP-Method-Override` header stay off, as they are by default, and `WebSessionRouteTest` pins it (the final review). Browsers send `Sec-Fetch-*` only to a secure address, so the route works only at HTTPS or the machine itself (§5, R91).

**The web session is no device** (changed in the build: R14; the plan's correction 8). `Caller.token()` is null on this route, so the hold code records no device: the app can't take over a check-out a device holds (`409 held-elsewhere`), and says who holds it and where it's released, on that device or with Unlock in the Classic Interface. `GET /auth/tokens` marks no token `current`, and `DELETE /auth/tokens/current` answers `403 token-only`.

### 8.2 Timeouts

CE's `session.timeout` applies (30 minutes idle as shipped). Every API call renews the session; the app sends no keep-alive, so a forgotten tab lets the session lapse. On a `401`, the app says you've been signed out and offers Sign In, which comes back to the same address.

### 8.3 The return path (call)

A full page load of an `/app/…` address without a web session reaches `AppServlet`. It stores the path and query as `RequestedUrl` in the HTTP session and redirects to `login.jsp`. Because `login.jsp` writes that value unescaped into a script, the servlet stores it only when:

- it starts with `/app/`;
- it has no `//`, no scheme and no backslash;
- every character is a letter, a digit or one of `/ . _ ~ % - ? & = + *` (`+` is a space in the app's own queries, and `*` a search's wildcard, which the app's addresses leave as it is; changed in the build, from the final review);
- it's at most 2,000 characters.

Anything else is stored as `/app/`. Both the password form and M4's provider buttons then come back to it (`SignInResource` already follows `RequestedUrl` under `login.jsp`'s conditions). Nothing in CE clears it after the sign-in, so `AppServlet` forgets it once a signed-in page loads.

### 8.4 Where a plain sign-in lands (call)

- **A new setting, `ui.start`,** in `hd_setting`: `app` (the default) or `classic`, editable in Healdan Settings.
- **A `@WebFilter` on `/login.jsp`** sets `RequestedUrl` to `/app/`, or to `frontend.jsp` for `classic`, when the HTTP session has none waiting: only a return path into the app waits, and an earlier landing is replaced. `login.jsp`'s `switch` parameter, which clears `RequestedUrl`, still works.
- **`SignInResource` falls back to the same setting** instead of `frontend.jsp`.
- **So with `app`, signing in again after the old UI's session lapsed lands in the app too.** Classic Interface takes you back. The old UI's own pages don't set `RequestedUrl`, so the filter can't tell where you were.
- No upstream file changes for this.

### 8.5 Signing out

**`DELETE /api/v1/session`** closes the CE session and ends the HTTP session; the app then goes to `login.jsp`. It's offered only on the web-session route (`403 web-session-only` on a token); a token signs out with `DELETE /auth/tokens/current`, as before. Nothing signs the person straight back in (M4 §5.1). The app takes a `401` for done and a `403` as still signed in.

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
| `PATCH /documents/{n}` | Rename; another extension is `422 validation` with `extension` (changed in the build: R74) |
| `POST /documents/{n}/move` | Move to another folder, with M1's re-stamping and M5's once per matter |
| `DELETE /documents/{n}` | To the trash; a checked-out document is refused, the caller's own check-out too (changed in the build, Task 20) |
| `GET /documents/{n}/preview?version=` | The preview PDF, or a picture as itself (§9.3) |
| `GET /documents/{n}/message` | A filed email's headers, its attachments (name, type, size, and the # of any already filed from it), and the address of its body |
| `GET /documents/{n}/message/body?pictures=` | The email's cleaned body as a page of its own (§9.4) |
| `GET /documents/{n}/message/parts/{id}` | An attachment, for its chip's preview or download: as a download under Download, recorded on the email as CE's download event, or with `inline=true`, a PDF or a picture only, under Preview and recorded as nothing (changed in the build: R56) |
| `POST /documents/{n}/message/attachments/{id}/file` | Save to Matter, reusing M5's filing (its properties: R60, R62) |
| Filters on `GET /search` and `GET /folders/{id}/children` | `type`, `author`, `client`, `matter`, `class`, `edited` (with `from` and `to`), and `zone`, the browser's IANA time zone, in which Today and the days are cut (`400 bad-request` for one the server doesn't know; the server's own without it; changed in the build: R90); `sort` and `order`; and `facets=` for the counts (§9.2) |

Everything else the app uses exists. Dropping a file on a row uses `POST /documents/{n}/versions`. The build also added fields where existing answers lacked what the app shows (changed in the build): a document's `author` (R27) and file `kind` (R49), the folder listing's `documents`, its count without subfolders (R28), and the properties' `overrides` (R66). The document's `lastModified` keeps its name, though since R85 it holds the file's date (changed in the build: R86; it has been in the contract since M2, and the released helper reads it as a required field). Author and Typist are picked through M2's `GET /users` (R92), and Signed-in Devices uses M2's `GET` and `DELETE /auth/tokens`. The inventory's M6 entries got their endpoints and tags in PR E, the records.

### 9.2 Filters and honest counts

- **Facet counts never count what the person can't read.** CE checks permissions after Solr answers, so Solr's own counts would include privileged documents. The counts come either from the hits that passed the read check or from a Solr filter on the folders the person can read. The trial (§12) tries both on the demo firm and picks.
- **File Type** groups extensions into kinds (Word, Excel, PowerPoint, PDF, Email, OpenDocument, Picture, Other). The list lives in one place on the server.
- **In a folder,** the counts come from the database, over the folder's documents the person can read.
- **In a search (changed in the build, Task 10),** every filter, Author, Class, Client and Matter as well as File Type and Edit Date, runs in the database after the read check, over the hits read so far, and the counts come from the database over the hits the person can read among the first 2,500 the engine ranks (`FACET_WINDOW`, §12's results; R26). Past it the counts are partial, taken from the hits that score above the window's lowest score band, so they are the same on every page (R32), or, when that leaves fewer than half the window, from the readable hits of the whole window (R33). The panel then says "Counts from the most relevant matches" (R38). The plan meant to send Author, Class, Client and Matter to Solr as filter queries on `ext_author`, `ext_docclass`, `ext_client` and `ext_matter`, but CE's index holds none of a document's properties: `StandardSearchEngine.addHit` adds them only when `getTemplateId()` is set, a transient field Hibernate never fills on the document CE's indexer loads. So a filter finds its matches among the first 10,000 hits (§9.5's window). Sending them to Solr would take a one-line upstream fix to CE's indexing and a full reindex of every install; it's a follow-up.

### 9.3 Preview

`GET /documents/{n}/preview?version=` answers M1.5's PDF of that version: `202` with `Retry-After` while it's still converting, `404 no-preview` for a type that has none, and `503 storage-unreachable` when the file can't be reached. A picture previews from the same endpoint as its own bytes (`200 image/*`, changed in the build: R54, Task 16's review), under the same checks and recorded as no download, where `/content` would record one and need the Download permission. The app asks again after `Retry-After` while Quick Look stays open.

### 9.4 Emails (refined while writing the spec)

The section design had the app clean an email's HTML with DOMPurify and show it in a sandboxed frame. A frame's document inherits the app page's Content-Security-Policy, so Show Pictures could only work by loosening that policy for the whole app. Instead:

- **The server cleans the body with jsoup** (already in the WAR): no script, no event handler, no form, no `<object>` or `<embed>`, no `javascript:` link, no `<base>`, no meta refresh. Links open in a new window with `noopener noreferrer`.
- **Inline pictures are embedded** in the page as `data:` addresses, up to a size the builder sets, past which they show as a placeholder. The frame is sandboxed without `allow-same-origin`, so its own requests come from an opaque origin, which the web-session route (§8.1) refuses as cross-site; embedding means it makes none, except for internet pictures once allowed.
- **`…/message/body` answers it as a page of its own,** with its own policy: `sandbox allow-popups allow-popups-to-escape-sandbox`; `default-src 'none'`; `style-src 'unsafe-inline'`; `img-src data:`, plus `https:` only with `pictures=true`; `font-src data:`; `frame-ancestors 'self'`. Without `pictures=true`, internet picture addresses are also removed from the HTML, so even a policy failure loads none. The app's own navigation of the frame is same-origin, so the body itself loads on the web-session route.
- **The app shows it in an `<iframe sandbox="allow-popups allow-popups-to-escape-sandbox">`**, so no script runs and links open in a new window. Show Pictures reloads the frame with `pictures=true`.
- **Every answer that serves stored bytes inline,** a picture's preview (§9.3) and an attachment with `inline=true`, carries `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox`, so a page opened at its address runs no script, as an SVG could (changed in the build: R57). The body page also sends `Referrer-Policy: no-referrer`, so a picture host never learns the server's address.
- **DOMPurify leaves the dependency list.**

### 9.5 Performance (M2's "before M6")

- **Folder listings** page, sort and filter in the database, keeping M2's batched read check: the folder's READ once, the documents with their own ACL in one query. A page reads only its own rows.
- **Search** pages through Solr with `start` and `rows` instead of stopping at about 1,000 engine hits, widening as M2's re-fetch loop does when the read check drops hits. The count stays honest: the real total, or "more than n" when counting exactly would be too slow; the plan measures which. (Changed in the build: R30 to R35.) Search reads up to 10,000 hits (`MAX_WINDOW`), and past that answers `totalAtLeast`, shown as "More than 10,000 documents" (R5). Without a sort, results stay in relevance order, and a page is cut only from hits scoring above its window's lowest band, so pages neither repeat nor skip a hit (R32); with a sort, the search reads until complete or 10,000 hits and orders the readable ones in the database as a folder does (R31; `order` without `sort` is `400`, R35). The API's document searches leave aliases out (R34).
- **Folder listings** sort titles by file name and doc #s as numbers, through one SQL helper written for MariaDB and HSQLDB alike (changed in the build: the plan's correction 7).
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
- **It refreshes** when the window comes back into focus, and after the person's own changes, which refresh what they touch, My Favorites, Recent Matters and Recent › Clients included (the final review). That stands in for push updates until their phase (§16). Coming back is the window getting the focus back, as it does when the person moves from Excel to a browser window that stayed on screen, or a hidden tab showing again, but not the focus coming back from an email's body, a frame in the page. It asks again for what shows a document's state however fresh it is: the document, its properties and versions, a folder's list and Recent. Anything else, and a search folder's list or a list scrolled past its first page, is asked again on coming back only once its answer is over 30 seconds old, the time an answer stays fresh for moving between views. Open in App marks the document and the lists stale without asking, but for My Matters' folders, as the helper checks the document out, and saves to it, after the click. A refresh that fails keeps the details shown, and an open properties form, unless the document is gone (changed in the build: walkthrough row 7, 2026-11-13, and its review; TanStack Query had heard only a tab's return, and asked nothing under 30 seconds old again, so a document opened in Excel showed neither its check-out nor the version its save made). Search results and their counts don't refresh on focus, as each page and each count runs the search again (changed in the build: R96, deliberate; the counts with row 7's fix). An open email's message is read once for its file version, not again when the lists refresh, and the lists are asked for again at most once a second while uploads finish (PR D's review).
- **Big lists** load 100 rows at a time as you scroll, and React Aria's virtualiser draws only the rows on screen. The count is always the real total.
- **The browser keeps only preferences** (row height, later), wrapped so the app works when storage is refused.

### 10.3 Components and styles

- `packages/ui` wraps React Aria Components as Healdan's own: Button, Menu, Table, Tree, Dialog, ComboBox, DatePicker, Toast, the filing Tray and the Quick Look frame. Screens use only these, never React Aria directly.
- Styles are CSS Modules reading the colour, type and spacing settings, which are CSS custom properties defined for light and for dark.

### 10.4 Strings

One typed catalogue, `en-US`, in `packages/ui`. Plurals use the browser's `Intl.PluralRules`; dates and numbers use `Intl` with the person's locale. The configurable labels come from `GET /server`. Labels follow the docs site's Title Case.

### 10.5 Keyboard

`/` jumps to the search box. The arrow keys move through the list. Enter opens in the app. Space opens Quick Look; Esc closes it. F2 renames. Delete moves to the trash, after the confirmation. The tree, menus and dialogs follow React Aria's standard keys. These work on every document list: a folder, search results, Recent's Documents and Emails, and the `#` result (changed in the build: R52, R63, R95). ⌥ with Upload opens the filing form, and Move To… is the keyboard's way to move, as rows drag only with the mouse. After a click React Aria focuses the cell, and arrows then move cell by cell (React Aria's grid pattern; the walkthrough judges it, Task 12).

### 10.6 Open in App

Double-click, Enter, or Open builds the same `healdan:` link as the old UI (`HealdanOpenInApp.link`), then shows the old UI's notice: "If nothing opened, install the Healdan Helper", linking to the docs. A type the helper doesn't open downloads instead.

### 10.7 Errors

- **Each of the API's problem `code`s** maps to a message in the catalogue, so wording is decided in one place.
- **Not found reads** "Not found, or you can't see it" (M2: unreadable means `404`). **A `403` has its own sentence,** "You don't have permission to do that." (changed in the build: R75; the spec had them read the same, but the API answers `404` for anything unreadable, so a `403` only comes for something the person can see, and saying so tells them nothing new).
- **A download the server refuses at the last moment** (`503 storage-unreachable` or a server fault) is the browser's failed download, or in some browsers a small saved file (changed in the build: R65). A download stays a plain link, so no script reads the bytes, and a `HEAD` check can't catch an outage.
- **`503 storage-unreachable`** shows a banner saying the files are unavailable for now.
- **Anything unexpected** gets a short message and Try Again, never a stack trace, a view that fails to draw included (the router's own error screen is replaced: the final review).
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
- **Session ids from CE's own sign-in (found in the build):** CE's `SessionFilter` can bind a session id it reads from the `ldoc-sid` cookie, or from `?sid=` while `security.acceptsid` is on (off as shipped), into a browser's HTTP session, and the web-session route takes the session it finds there. That predates M6, which doesn't change it. A follow-up makes the route accept only a session id that a real sign-in bound.

## 12. The trials (call)

Two questions could change the design, so they're answered before the build, as M5's trial was:

1. **Dragging from new Outlook for Mac into the browser.** Does a message dragged onto the page arrive as an `.eml` file, a link or nothing, in Safari and in Chrome? It runs on the owner's Mac. If nothing usable arrives, dragging from Outlook stays with M5's add-in, the app files `.eml` and `.msg` files dragged from Finder, and the User Guide says so.
2. **Facets in CE's Solr.** Whether Author, Class and the file's type are indexed so Solr can count them, and which of §9.2's two ways of counting only what the person can read answers in time on the demo firm with a privileged folder.

**Results of trial 1 (the owner, 2026-11-10, run by hand alongside the build; changed in the build: R10).** In Safari 27.0.1 on macOS, a message dragged out of new Outlook for Mac reaches the page as one `File`: type `message/rfc822`, an `.eml` named after the subject, about 77 KB, with `dataTransfer.types` `["Files"]`. Chrome gets no drop event at all for the same drag. So the trial gate's second row applies: the app files a dropped message as any dropped file (Task 19); Known Limitations says dragging from new Outlook works in Safari only, and that in Chrome the message can be dragged to Finder first or filed from Outlook's Healdan pane; and walkthrough row 8 drags from Outlook in Safari. The trial's page was never committed, so Task 19 had nothing to delete (R67).

**Results of trial 2 (changed in the build: Task 10, 2026-11-10).** None of a document's properties is in CE's index, and its search handler has no facet component, so Solr can neither count nor filter them (§9.2): the counts are taken in SQL over the hits that pass the read check, and every filter runs in SQL. Measured on the builder's Mac (Apple silicon, Docker Desktop, not the owner's server) on the demo firm's stack with 10,000 more documents sharing a word, in matters 1001.1 (3,000, and 1,000 in its partners-only Privileged folder), 1001.2 (3,000) and 1002.1 (3,000), all indexed and the preview queue empty, as `jclarke`, who can't read Privileged. `GET /api/v1/search?q=kittiwake&limit=100&facets=type,author,class,client,matter`, medians of ten after a warm-up, by counting window: 1,000: 0.62 s; **2,500: 0.93 s**; 5,000: 1.44 s; 10,000: 2.31 s. The window is 2,500 (`FACET_WINDOW`): past it the total says "at least" and the counts are partial. At 2,500, without facets 0.70 s, one hit 0.39 s (CE's own search takes about 0.30 s of it), `type=pdf` or `matter=1001.2` with facets 0.79 s and 0.80 s, but a filter that matches nothing (`author=treeve`) 2.39 s and a page at offset 4,900 2.28 s, as they read up to 10,000 hits, and the Emails scope 1.34 s, as the emails rank below every other file. These were measured before Task 10's review made a page cut only from the hits that score above its window's lowest, so pages neither repeat nor skip a hit, and the counts the same on every page; a first page reads no more for it, but a query whose hits mostly score the same widens further, up to 10,000 hits, and a sorted search always reads up to 10,000.

## 13. Delivery (call)

Four pull requests, each with its tests and its docs pages, and `test-web.sh` growing with each:

| PR | Holds | Ends with |
|---|---|---|
| **A · Foundation** | `healdan-web`, `AppServlet`, the web-session route, `DELETE /session`, the `login.jsp` filter and `ui.start`, the old UI's link back, `packages/ui` (the settings in light and dark, the core components, the strings), `packages/api`, the app's frame and addresses, the `Web` check and the licence check, the Docker build | Signing in lands in the app; the left pane works; links survive signing in |
| **B · Finding** | Folder paging in the database, search paging, filters and honest counts, `/suggest`, the Recent section, the My Matters tree with add and remove, the search page, the `#` result, the details panel, Copy Link | §4's Finding and Looking rows |
| **C · Working** | Preview (Quick Look, the reading pane, PDF.js), the email view and Save to Matter, Open in App, Download, check-out, Edit properties, versions | §4's Working rows |
| **D · Filing and organising** | Dropping files with the tray and the form, dropping on a row, New Folder, Rename, Move, Delete, Signed-in Devices | The rest of §4 |

A last small pull request records the result: the roadmap, the plan's ledger and the follow-ups.

**As built (changed in the build):** PR A is [#220](https://github.com/Healdan-Project/healdan/pull/220), PR B [#221](https://github.com/Healdan-Project/healdan/pull/221), PR C [#222](https://github.com/Healdan-Project/healdan/pull/222) and PR D [#224](https://github.com/Healdan-Project/healdan/pull/224), each stacked on the one before, and PR E the records. The owner decided on 2026-11-10 to merge the series together once the Daily Driver was built, rather than one at a time, as PR A alone would have landed everyone in a half-built app. Each PR also carries its review's fixes, and PR D the final review's. Two of PR D's fixes are to LogicalDOC's own code, found on the way (§14.1: the stemmer, R82, and the session, R88), and one of PR C's works around a third without an upstream edit: CE's `ParserFactory` fills its parsers in a plain `HashMap` on first use, where two first uses at once could lose `pdf` for good, and `HealdanStartup` now fills it once at a real start, before anything parses (PR C).

## 14. Upstream files, fork-owned files and docs pages

### 14.1 Upstream files

| File | Edit | Why |
|---|---|---|
| `pom.xml` (root) | `<module>healdan-web</module>` | The module joins the reactor |
| `logicaldoc-webapp/pom.xml` | An `artifactItem` for `healdan-web`'s plugin zip | Without it the plugin isn't shipped |
| `logicaldoc-gui/.../frontend/client/menu/MainMenu.java` (the builder picked it: changed in the build, Task 8) | A one-line hook in the constructor, before Sign Out, adding **Open in Healdan** (`HealdanAppLink.add(this)`, marked `// Healdan:`) | The way back to the app (§8.6) |
| `.gitignore` (changed in the build: Tasks 2 and 8) | Lines for the workspace's installed packages, its builds, the types generated from `openapi.yaml`, its `.tsbuildinfo` files, and the browser tests' results | So none of them is committed by mistake |
| `logicaldoc-core/.../searchengine/FulltextSearch.java` (changed in the build: owner, 2026-11-10) | `internalSearch` passes `options.getMaxHits()` to the engine as Solr's `rows` when it's over 1,000, capped at 10,000, and `null` as before otherwise; `FulltextSearchTest` covers all three and joins `UPSTREAM_TESTS` | CE passed no `rows`, so the search handler's default of 1,000 (`solrconfig.xml`, which CE never copies over an install's) capped every full-text search, and search couldn't page past it (§9.5). A maximum of 1,000 or less still gets the handler's 1,000 rows, so the old UI's pages of 40, which CE fills after its permission check, behave as before. The cap of 10,000, the API's own window, is there because CE's web service (`maxHits`), CMIS (`maxItems`) and RPC searches pass a maximum the client chooses, which would otherwise ask Solr for every match, highlighted, and CE's SQL for all of them (Task 10's review, R30). The owner also approved making `setQueryFilters` `protected`, for the Author, Class, Client and Matter filters as Solr filter queries; it was dropped in the build, since CE's index holds no properties (§9.2), so the edit would have had no use |
| `logicaldoc-core/.../i18n/Language.java` (changed in the build: R82, owner, 2026-11-12) | `getStemmer()` keeps the stemmer class's constructor and makes a new `SnowballProgram` at each call; `SnowballFilterFactoryTest` (new, in `searchengine/analyzer/`) analyses on eight threads at once and compares with one thread, and joins `UPSTREAM_TESTS`. `LanguageTest` checks that two calls give two stemmers | CE kept one stemmer per `Language`, and `SnowballFilterFactory.create` gave that one object to every token stream in the JVM. A Snowball program keeps the word it is stemming in its own fields, so two threads stemming together corrupted each other: a search failed with "faulty slice operation", which CE turns into no hits (the User app's list and its Filters' counts search together, and the smoke test saw it in about half its runs), and the Indexer's two threads could store a word under a wrong stem. `FilteredAnalyzer` is an `AnalyzerWrapper` on `GLOBAL_REUSE_STRATEGY`, which builds its components once per thread, so a new stemmer costs one small object per thread, not one per word. `SnowballFilterFactory` stays as it is. The Install manual's Upgrading page tells an install to rebuild its index once |
| `logicaldoc-core/.../security/Session.java` (changed in the build: R88, approved by the owner, 2026-11-12) | `lastRenew` is `volatile`; `SessionRenewalTest` (new) has four threads check one session and renew it for ten seconds (R89: on Apple-silicon Macs the race showed after up to 165,245 checks, and a shorter run might not reach it), and checks the field is `volatile`, and joins `UPSTREAM_TESTS` | Each request renews its session with a new `Date` in `lastRenew` while other requests read the field in `isTimedOut`. Through a plain field another thread could see the new `Date` before its time, as 0, so a session a few seconds old counted as idle since 1970 and expired: the User app, whose page sends several requests at once, said "You've been signed out." seconds after a sign-in. The expiry most likely came from `SessionManager.renew`, which doesn't save it, so the session's `ld_session` row stayed `OPEN`. With CE's own `Session` on the image's JVM, a harness counted one false timeout in every 1,800 to 4,400 checks, and none with the field `volatile` |

Nothing else upstream changes: not `context-security.xml`, `login.jsp` or `web.xml`. `HealdanAppLinkTest`, in `UPSTREAM_TESTS`, checks the hook is still in `MainMenu` before Sign Out, and the button's address. The upstream tests the edits change, `FulltextSearchTest` and `LanguageTest`, are in `UPSTREAM_TESTS` too, beside the new `SnowballFilterFactoryTest` and `SessionRenewalTest`. `Language.java`'s two edits are marked `// Healdan:`, as the others are (the final review). The drafted reports to LogicalDOC for the stemmer and the session, each with its test and the fix as a diff, aren't sent: that's the owner's call.

### 14.2 Fork-owned files

- **New:** the `healdan-web` module (its Java, its `plugin.xml`, its assembly and its `web/` workspace); `.github/workflows/web.yml`; `.github/scripts/check-web-licences.py` and its tests; `docker/tests/test-web.sh`; `docker/tests/bulk-documents.sh`, the bulk loader for the paging and counting measurements and walkthrough row 4 (changed in the build: the plan's correction 10); `logicaldoc-gui`'s `HealdanAppLink` and `HealdanAppLinkTest`; and, in `logicaldoc-core`'s tests, `SnowballFilterFactoryTest` and `SessionRenewalTest` (changed in the build: R82, R88).
- **Changed:** `healdan-api`'s `TokenFilter`, `SignInResource`, `openapi.yaml`, its resources for the new endpoints and their tests; `healdan-core` for `ui.start`, folder paging, search paging, facets, suggestions, the email body's cleaning, and Recent Clients, and (changed in the build) the preview state, the reading of `.msg` emails (`StoredMessage`), `HealdanStartup`'s filling of CE's parsers (PR C), and the smoke stack's seeds (`smoke-web-user`, and `smoke-organising`, which gives Everyone Rename, Move and Delete there as a step of its own, since a shipped step never changes: R73 had changed `smoke-fixture` itself, and PR D's review restored it as shipped); the Healdan Settings panel in `logicaldoc-gui` (Start In); the helper's model (`overrides`, R66); `.github/scripts/code-changed.sh`; `.github/workflows/build-logicaldoc.yml` (the skip flag, the licence check, and `FulltextSearchTest`, `LanguageTest`, `SnowballFilterFactoryTest`, `SessionRenewalTest` and `HealdanAppLinkTest` in `UPSTREAM_TESTS`); `.github/workflows/docker-image.yml` (npm's cache between runs); `docker/smoke-test.sh` (the new step, step 9, the last: changed in the build, R47); `docker/tests/test-signin.sh` (the app's landing); `.dockerignore` (`**/node_modules`, the plan's correction 9); the `Dockerfile` (npm's cache mount); `docs/reference/healdan-api-inventory.md`; `docs/ROADMAP.md`; `CLAUDE.md` (`healdan-web`'s build, test and CI facts).

### 14.3 Docs pages (the docs site, `site/content/`)

Labels are quoted exactly as the app shows them, in US English, while the prose stays British (a new rule on Writing Docs). Screenshots come from the demo firm.

- **User Guide, rewritten for the app:** Signing In; Getting Around; Searching; Finding by Doc #; My Matters and Recent Matters; Search Folders; Filing a Document; Emails; Properties; Stores and Matter Workspaces; Previews; Versions; Checking Out and Checking In; Downloading Documents; Copying, Moving and Deleting.
- **User Guide, new:** The Classic Interface: what's still there, how to get to it, and that it's the same sign-in. Signed-in Devices, a page of its own, linked from Getting Around's person menu and from the helper's pages where they speak of signing a device out (changed in the build: R77).
- **User Guide, unchanged but opening with a note that they're in the Classic Interface:** Aliases; Bookmarks; Links Between Documents; Notes; Sharing a Document; Tags; Locking a Document; Document History; The Trash; Document Permissions; Your Profile and Password; Messages.
- **Admin Guide:** Healdan Settings (`ui.start`, as Start In; and Device Sign-Ins: only the helper holds a token, R78). Scheduled Tasks' new section Finding New Documents Sooner, which Search and Indexing links to (changed in the build: the owner kept CE's Indexer at every 30 minutes on 2026-11-11, and asked for the docs to say how an admin shortens it).
- **Install manual:** Upgrading (the app arrives; `ui.start`); Reverse Proxy (pass `Origin` and `Sec-Fetch-*`, which nginx and NPM do by default, and HTTPS, which the app needs: R91); Known Limitations (what's still classic, the browsers supported, the root context only, and dragging from new Outlook in Safari only); Troubleshooting (being signed out; "Not found, or you can't see it"; a plain-HTTP address); Upgrading's new section Words Indexed Under a Wrong Stem (R82: rebuild the index once, with its cost).
- **Developer Guide:** a new page, The Web App (the workspace, running Vite's dev server against a local stack, adding a view, the catalogue, the colour, type and spacing settings, the dependency rules); API (the web-session route and the new endpoints); How Healdan Extends LogicalDOC (`healdan-web`, `AppServlet`, the `login.jsp` filter); Building from Source (Node through Maven, the skip flag); Tests (the app's tests, `test-web.sh`); Continuous Integration (the `Web` check); Writing Docs (the label rule). Development Stack too (what the smoke stack's documents allow).

Every page above shipped (checked for PR E against the branch). The User Guide's pictures of the app come from the demo firm, in light mode (Task 21b, R76).

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
  - **As built (changed in the build):** the step is step 9, the smoke test's last (R47), as it first sets CE's Indexer to every 5 seconds and restarts the app, and nothing after it may run with a fast Indexer. Its specs are `signin` (a link into the app and a doc # link, signed out), `finding`, `working`, `filing`, `organising`, `keyboard` (rows 3 to 9 of §15.2 by keyboard alone, but for Copy Link, whose clipboard differs between headless engines, and row 9's move into another matter, which would write through Garage after step 8 has checked its read cache: R80) and `a11y` (axe in both themes, in busy states and at 768 pixels), 106 tests in both browsers on the final run. They sign in as `webuser`, a non-admin the smoke fixture adds. Playwright stops after 10 failures, and every `curl` has a 30-second limit.
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
