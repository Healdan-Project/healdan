---
title: The Web App
weight: 10
---

The User app is Healdan's browser app, served at `/app/`. This page is for working on it: how its source is laid
out, how to run it against a local stack, how to add a view, and the rules its code keeps. To build it and run its
tests, see [Building from Source](../building-from-source/#the-user-app) and [Tests](../tests/#the-user-apps-tests).
How the server sends it and signs people in is in [How Healdan Extends LogicalDOC](../how-healdan-extends-logicaldoc/).

## The Workspace

The source is an npm workspace in `healdan-web/web`. React 19, TypeScript, Vite, React Aria Components, TanStack
Router and TanStack Query do the work, and every package version in `package.json` is exact.

| Folder | What's in it |
|---|---|
| `packages/api` | `@healdan/api`: the client for `/api/v1`, with its types generated from `healdan-api`'s `openapi.yaml` by `npm run generate` (the generated file isn't committed). A change to the contract that breaks the app fails the type check |
| `packages/ui` | `@healdan/ui`: Healdan's components on React Aria Components, the colour, type and spacing settings, the icons, and the string catalogue |
| `apps/user` | the app itself: its frame (top bar, left pane), its router and its views |
| `scripts` | the build's checks, with their own tests, run by Node's test runner |
| `e2e` | the browser tests ([Tests](../tests/#the-user-apps-tests)) |

## Running It Against a Local Stack

The app talks to the server on its own address, so run it beside a stack, such as the [development
stack](../development-stack/), and let Vite's dev server pass the server's addresses on:

```bash
cd healdan-web/web
npm ci --ignore-scripts
npm run generate
cd apps/user
HEALDAN_DEV_TARGET=http://127.0.0.1:8080 npm run dev    # the stack's address; that one is the default
```

Open `http://localhost:5173/app/`. The dev server sends `/api`, `/login.jsp` and `/j_spring_security_check` to the
stack. The API's web-session route refuses a request whose `Origin` isn't the server's own, so the dev server
rewrites `Origin` to the stack's address. Cookies don't depend on the port, so you can sign in at the stack's own
address, and the dev server's page is signed in too. Pages the app links to that the proxy doesn't pass, such as
the Classic Interface, are on the stack's address.

## Adding a View

1. Write the view in `apps/user/src/views/`. Take its data from the API client through `useApi()` and the queries
   in `queries.ts`, and its pieces from `@healdan/ui`.
2. Give it a route in `router.tsx`. The selected document's number, `?sel=`, is part of every address.
3. Add its words to the catalogue, and a left-pane entry if it needs one.
4. Test it in `views/`, with `renderApp(path, routes)` from `test/renderApp.tsx`, which renders the whole app at an
   address and answers from a small fake API, and `expectNoAxeViolations`. Add what the fake API doesn't answer to
   `test/fakeApi.ts`, typed from the contract.

The router's types are registered (`router.tsx`), so `Link`, `navigate()` and the search hooks check each address's
parameters. Build an API path from data with `apiPath`, from `@healdan/api`, which encodes each value as one
segment: `` apiPath`/v1/folders/${id}/children` ``.

## Lists

A list of documents is `@healdan/ui`'s `Table`: React Aria's Table, virtualised so only the rows on screen are drawn,
with sortable headings and single or multiple selection. It passes Enter (or a double-click) out as `onOpen`, Space as
`onPreview`, F2 as `onRename`, and Delete or Backspace (the Mac's delete key) as `onDelete`, for the view to act on.
It takes those keys only from a row or its cells, so a field inside a row keeps them, and leaves them to the browser
with ⌘, Ctrl or Option held. A row whose `selectable` is false, such as a folder among documents, opens on a click
instead. Pass `busy` while the rows shown are about to change, as `useListing`'s `isStale` says while a new order
loads: the old rows stay, and a screen reader waits for the new ones. Give it `loadMore` and `total`, and it calls `loadMore`
when the end of the loaded rows comes into view while fewer than `total` are loaded. A folder's list takes its rows
from `views/useListing.ts`, TanStack Query's infinite query over `GET /folders/{id}/children`, 100 at a time. Offset
pages shift when the folder changes between them, so it keeps one copy of a row a later page repeats.

The virtualiser places the rows itself, so the Table needs a height: it fills a flex column, as the folder view's is.
jsdom has no IntersectionObserver, which notices the end of a list, so the tests' setup installs one that sees
nothing until a test calls `bringObservedIntoView()` from `test/intersection.ts`, as scrolling to the end would.

## Filters and the Right Region

A folder's filters are `?type=pdf&type=word&author=pnair&class=…&edited=7d` (or `from` and `to` as `2026-11-02`) in the
address: `filters/useFilters.ts` reads them (`readFilters` leaves out what the API would refuse, so a pasted address
never makes the list an error), counts them for the funnel's badge, and writes them back with `navigate`, replacing the
entry. The router's `parseSearch` and `stringifySearch` carry the repeated ones as lists, and each route that takes
filters adds `filterSearch` to its `validateSearch`. `useListing` sends them with every page; `filters/useFacets.ts`
asks for the counts on their own (`GET /folders/{id}/children` with `facets`, `limit=1`) only while the panel is open,
and for the folder's count with no filters only while one is in use, which is the "7" of "3 of 7 documents".

The right of a list is one region (`frame/RightRegion.tsx`): `useRightRegion()` gives `show` (`'filters'`, `'details'`,
`'preview'` or null), `docked`, `open(kind)` and `close()`. Details are the default while a document is selected; the
funnel opens Filters over them, and a view opens the details again when a row is clicked with Filters showing. The
reading pane, once docked (`open('preview')`), is what `'details'` means until `close()` closes it, so the funnel's
Filters give way to it again at the next click on a row. A view renders
`RightRegionView` beside its list with what each kind shows, and the region isn't in the page at all when there's
nothing to show. The panel's groups are `@healdan/ui`'s `CheckGroup` and `RadioChoices`, and its dates `DatePicker`
(React Aria's, taking and giving days as `2026-11-02`). A search view offers the panel with `context="search"`, which
adds Client and Matter.

## Details and the \# Result

`details/DetailsPanel.tsx` is the region's details: `<DetailsPanel docNumber row version context />` asks for the
document, its properties and its versions (`useDocument`, `useProperties` and `useVersions` in `queries.ts`; the
list's row shows at once as the placeholder) and renders the thumbnail, the name, `# n · Version v · size`
(`formatSize` in `@healdan/ui`), the actions and `Properties` and `Versions`. A search view passes
`context="search"`, which adds Location, linking to `/app/folders/<id>`. The panel never reads a file: the thumbnail
is an `<img>` on `/api/v1/documents/{n}/thumbnail`, which the web session's cookie goes with, and an icon of the
file's kind (`fileIcon.ts`) shows when there's none or the person can't open the document. Properties come from the
list the server gives, by name; an email's From, To, Sent and Received show when any of them has a value, as the
server fills them from the message and the browser never parses a file. Open, Download and Edit are
disabled until their tasks; **Preview** opens Quick Look (see Previews, below), and **Copy Link** (`actions/copyLink.ts`) writes `location.origin + '/app/doc/' + n` with the
Clipboard API and shows a Toast, either way.

`/app/doc/10388`, `/app/doc/10388.2` and the other forms of the lookup are `views/DocResult.tsx`: it asks
`GET /v1/lookup?q=%2310388.2` and shows a list of that one document (`views/listColumns.tsx`, the folder list's
columns), selected, with its details beside it and the named version marked in Versions. `useSelection` takes the
number from the address on this route. A `404` shows "No document # 10388 that you can see.", the same for a
document that isn't there and one that can't be read, and says only the number as typed; a `400` shows the not-found
view.

## The Search Box and Search Results

`search/SearchBox.tsx` is the top bar's scope picker (`scopes.ts`: the API's six `scope` values, and the four Recent
kinds, which are scopes too) joined to `@healdan/ui`'s `ComboBox`, React Aria's, with the options the caller gives in
named sections and not filtered by it. Focus stays in the box and the arrow keys move through the options. Enter with
none under the arrow keys is `onSubmit`, which React Aria itself never reports (it only commits a custom value), so the
wrapper reads the focused option from the combobox state. The scope follows the address (the start page and Recent's
entries are Documents or their kind, `/search` names its own), and so do the words, so Back and a reload show them.
`/` focuses the box unless the target is a field, an editable element, or the box is hidden from assistive technology,
which React Aria does to everything outside a modal dialog.

Suggestions (`search/Suggestions.tsx`) are `GET /v1/suggest`, asked 150 ms after the typing stops, from two characters,
never for a `#`, with the first 100 characters. The request gets TanStack Query's `signal`, which the Api client's
`get(path, query, { signal })` hands to `fetch`, so a newer ask aborts the older one in flight. Enter on words runs
`/search?scope=…&q=…`; a `#` and a doc # (`#10388`, `#10388.2`, `#10388 v2.1`) goes to `/doc/<reference>`, made without
spaces; in the Matters scope a `998.1` asks `GET /search?scope=matters` for that number and opens the matter's
`folderId`, else shows the results.

`views/SearchView.tsx` is `/app/search`: the scope, words, filters (`useFilters`), `sort`, `order` and `sel` are all in the
address. `search/useSearchResults.ts` is the infinite query over `GET /search` (100 a page) and the twin of
`useFacets` for the panel's counts (`facets=type,author,class,client,matter`, `limit=1`); only the Documents, Emails and
Docs & Emails scopes send filters or a sort, as the API refuses them for the others. A page is the last when the
answer's `total` is reached, or, when it's `null`, when it's shorter than the 100 asked. `totalAtLeast` is the count's
"More than". `FiltersPanel` shows no made-up `0` for a ticked value no option offers when the counts are `partial`.
`views/RecentView.tsx` is the start page and `/recent/<kind>`, a Table each, with `useRowSelection` (the selection of
document rows, shared with search) putting a document in `?sel=` and its details in the region; a recent client opens
a Clients search.

## Previews

`preview/PreviewBody.tsx` is the one switch that picks the view of a document by its `kind`, for both places that show
one: a `picture` is `PictureView` (the preview's own bytes, `image/*`, read through the API client and shown from a `blob:`
address that is revoked when it's replaced or unmounted), an `email` is `EmailView` (below), and every other
kind is `PdfView`, which asks `GET /documents/{n}/preview`, shows "Preparing a preview…" and asks again after
`Retry-After` while it's `202`, and shows `NoPreview` for `404 no-preview`. The app has no list of types that convert. Neither a picture's nor a PDF's preview is recorded as a download.
The client's `getResponse` gives the `Response` for such an answer (its bytes, its status and its headers), with the
sign-out and problem handling `get` has. `PdfView` and `PictureView` read a `PreviewSource` (an API path, its query and a
name) that is the document's own preview by default, so an email's attachment is drawn by the same views, not a second viewer.

### Reading an Email

`preview/EmailView.tsx` shows a filed email as a message from `GET /documents/{n}/message` (see
[Reading a Filed Email](../api/#reading-a-filed-email)): the subject and the sender, To, Cc, Sent and Received, the
attachments as chips, then the body. The body is `EmailBodyFrame`, an `<iframe>` of the server's page at
`/message/body`, with `sandbox="allow-popups allow-popups-to-escape-sandbox"` and never `allow-scripts` or
`allow-same-origin`: `EmailBodyFrame.test.tsx` asserts both on the element. The server cleans the page and gives it its own
Content-Security-Policy, so the app's (`AppServlet`'s) stays as it is. Pictures from the internet are removed from it
until **Show Pictures**, which reloads the frame with `?pictures=true`, for that viewing only (`EmailView` has no
state that outlives its document: `PreviewBody` keys it on the document). A chip is a button that previews a PDF or a
picture in place of the body (`/message/parts/{id}?inline=true`, with **Back to Message**) or a plain link that
downloads anything else; **Save to Matter** posts to `/message/attachments/{id}/file`, and the chip shows the new
document's # when the message is read again. `working.spec.ts` checks the whole path in both browsers, with a tracking
picture on a host that never resolves.

`preview/QuickLook.tsx` is the modal over a list, in `@healdan/ui`'s `Overlay` (a React Aria modal with ‹ ›, the arrow
keys, Space and Esc). A view renders `<QuickLook documents total loadMore />` with the documents of its list, in order,
and passes Space on a row to `usePreviewKey`; which document is shown is in `QuickLookState`, a context in the frame, so
the details' and the toolbar's Preview buttons open it too. ‹ › move the selection with them, and at the end of the
loaded rows › calls the list's `loadMore` and moves on when the page comes. `ReadingPane` is the same view docked in the
right region, and `narrowed()` in `views/listColumns.tsx` gives the list its three columns while it's there.

### PDF.js Under the Page's Policy

`AppServlet`'s Content-Security-Policy allows no `eval`, no WebAssembly and nothing but the server's own address.
`preview/pdfEngine.ts` is the only file that imports `pdfjs-dist`, and it's loaded on demand, as a chunk of its own,
when the first PDF is previewed. These are the settings that keep it inside the policy; change none without running
`working.spec.ts` in both browsers and watching for a violation:

- **The worker** is `pdf.worker.min.mjs`, imported with `?url`, so Vite emits it under `/app/assets/` with a hash
  (`worker-src 'self'`). Pages draw on the main thread, in a canvas; there's no text layer.
- **`isEvalSupported: false`** is what the spec names. PDF.js 6 dropped the option along with the `new Function` it
  guarded, so it isn't passed, and `scripts/pdfjs-policy.test.mjs` fails if a version of PDF.js brings an eval back.
- **`useWasm: false`.** PDF.js compiles WebAssembly for ICC colour profiles, PostScript functions and JPEG 2000 and JBIG2
  images, which the policy refuses without `'wasm-unsafe-eval'` (a refused compile is a violation report even though
  PDF.js recovers). With it off, the two image decoders run as JavaScript, loaded from `wasmUrl`, and an ICC colour
  space uses its alternate.
- **Its data comes from `/app/pdfjs/`**, never a CDN: `cMapUrl` (`cmaps/`, for CJK text whose fonts aren't in the PDF),
  `standardFontDataUrl` (`standard_fonts/`, for the fourteen fonts a PDF may not embed) and `wasmUrl` (`wasm/`, the two
  JavaScript decoders). `apps/user/pdfjs-data.mjs` is a Vite plugin that emits those files into the build, and serves
  them from `node_modules` in `npm run dev`, so none is committed. `AppServlet` treats only what Vite writes under
  `assets/` as hashed (and so immutable for a year), so these, whose names only sometimes look like hashes, are served
  `no-store`. `scripts/check-dist.mjs` fails the build when the worker or a folder is missing, and
  `scripts/notices.mjs` adds their licences (Foxit's and Liberation's fonts, OpenJPEG's and JBIG2's decoders) to
  `notices.txt`.
- **Fonts from the file** are made with the FontFace API from the file's bytes, which `font-src` doesn't govern.
- **One worker** is shared by every document opened, for the page's life, and **a copy of the bytes** is given to PDF.js,
  which hands its argument to the worker and leaves the caller's empty. PDF.js can't run its parsing under jsdom (its
  main-thread worker times out there), so `pdfEngine.ts` has no unit test: `working.spec.ts` is its test.

## The Catalogue

Every word the app shows is in one typed catalogue, `packages/ui/src/strings/en-US.ts`, and a view gets it with
`t('key')`. A translation is a new file beside it. Labels are in US English and Title Case. `{matter}` and
`{matters}` are the firm's own words for a matter, which the app reads from `GET /server`, and other `{name}`s are
filled from the variables `t()` is given. Each of the API's problem `code`s has a message here too, so the wording
is decided in one place, and a test fails when one has none.

## The Colour, Type and Spacing Settings

They are CSS custom properties, prefixed `--hd-`, in `packages/ui/src/tokens.css`, and every component's CSS uses
them, never a colour of its own. Light and dark are both defined there, and follow the browser's setting. A test
checks the contrast of every text colour on every background it may sit on, so a new colour goes into that test's
lists as well.

## The Dependency Rules

ESLint enforces these, so `npm run lint` fails on a break:

- **Only `packages/ui` imports React Aria** (`react-aria-components`, `react-aria`, `react-stately` and their
  `@react-aria` and `@react-stately` packages). A screen takes its components from `@healdan/ui`.
- **No `dangerouslySetInnerHTML`.** The server's text is never HTML: names, messages and fields are shown as text.

And the build checks these:

- **Versions are exact,** and `npm ci --ignore-scripts` installs them with no install script run.
- **Licences are checked** by `.github/scripts/check-web-licences.py`, and the build writes the notices for what the
  app ships into `apps/user/dist/notices.txt`.
- **The page's Content-Security-Policy** allows no inline style, so a library that injects one is a problem:
  `scripts/check-dist.mjs` checks the one React Aria would inject, for pressed states, is a linked file.
- **The libraries are chunks of their own** (`vite.config.ts`): React Aria in one, PDF.js in another (loaded only
  when a PDF is previewed) and the rest in a third, each named with its hash, which `AppServlet` serves as immutable,
  so a browser keeps them across the app's updates.
