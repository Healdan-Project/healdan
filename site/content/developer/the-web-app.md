---
title: The Web App
weight: 10
---

The User app is Healdan's browser app, served at `/app/`, and the Admin app is the administrators' own, at `/admin/`.
This page is for working on them: how their source is laid out, how to run them against a local stack, how to add a
view, and the rules their code keeps. To build them and run their tests, see
[Building from Source](../building-from-source/#the-web-apps) and [Tests](../tests/#the-web-apps-tests). How the
server sends them and signs people in is in [How Healdan Extends LogicalDOC](../how-healdan-extends-logicaldoc/).

## The Workspace

The source is an npm workspace in `healdan-web/web`. React 19, TypeScript, Vite, React Aria Components, TanStack
Router and TanStack Query do the work, and every package version in `package.json` is exact.

| Folder | What's in it |
|---|---|
| `packages/api` | `@healdan/api`: the client for `/api/v1`, with its types generated from `healdan-api`'s `openapi.yaml` by `npm run generate`, and the admin API's from `openapi-admin.yaml`, exported as `adminPaths` and `adminComponents` (the generated files aren't committed). A change to a contract that breaks an app fails the type check |
| `packages/ui` | `@healdan/ui`: Healdan's components on React Aria Components, the colour, type and spacing settings, the icons, and the string catalogue |
| `packages/shell` | `@healdan/shell`: what both apps need that asks the server or draws the frame's shared parts: `useApi`, the query client, the signed-out state, the error messages (`messageFor`), the person and Help menus, the mark, the pages for being signed out, an address that isn't secure and no access, and the queries for the person, a folder and Recent Matters. It registers no router |
| `apps/user` | the User app: its frame (top bar, left pane), its router and its views |
| `apps/admin` | `@healdan/admin`, the Admin app (see [The Admin App](#the-admin-app)) |
| `scripts` | the build's checks, with their own tests, run by Node's test runner |
| `e2e` | the browser tests ([Tests](../tests/#the-web-apps-tests)) |

Each app is a TypeScript program of its own: `npm run typecheck` runs `tsc -p .` over the packages, then
`tsc -p apps/user` and `tsc -p apps/admin`, as each app registers its own router with TanStack Router, which one
program can't hold twice. `npm run build` builds both apps, into `apps/user/dist/` and `apps/admin/dist/`.

## Running It Against a Local Stack

The app talks to the server on its own address, so run it beside a stack, such as the [development
stack](../development-stack/), and let Vite's dev server pass the server's addresses on:

```bash
cd healdan-web/web
npm ci --ignore-scripts
npm run generate
cd apps/user
HEALDAN_DEV_TARGET=http://127.0.0.1:8080 npm run dev -- --host 127.0.0.1    # the stack's address; that one is the default
```

Sign in at the stack's own address, `http://127.0.0.1:8080/login.jsp`, then open `http://127.0.0.1:5173/app/`. A
cookie belongs to a host name, not a port, so the dev server's page on the same host name is signed in too; one at
`localhost` isn't, as that's another host name. The dev server sends `/api`, `/login.jsp` and
`/j_spring_security_check` to the stack. The API's web-session route refuses a request whose `Origin` isn't the
server's own, so the dev server rewrites `Origin` to the stack's address. The app's own **Sign In**, after a sign-out,
reloads the dev server's page, which only the stack's `AppServlet` would send to the sign-in page: sign in at the stack
again. Pages the app links to that the proxy doesn't pass, such as the Classic Interface, are on the stack's address.

The Admin app's dev server works the same way: run `npm run dev` in `apps/admin` and open `/admin/` at the address it
prints. It sends `/app` to the stack too.

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
segment: `` apiPath`/v1/folders/${id}/children` ``. The Admin app's screens are added as
[Adding a Screen](#adding-a-screen) says.

## A Secure Address Only

Browsers send `Sec-Fetch-Site` only to a potentially trustworthy address (HTTPS, or the machine itself), and the
web-session route needs it, so at a plain `http://` address off the machine every call would be `401`. `main.tsx`'s
`start(root, secure)` checks `window.isSecureContext` first and, without it, draws only `@healdan/shell`'s
`InsecureAddress`, in the catalogue's words, with a link to the Classic Interface, before anything asks the server.
The Admin app's `main.tsx` does the same. Nothing that runs as the app loads may need a secure context either: a
drag's page token (`organising/rows.ts`) is made with `crypto.getRandomValues`, as `crypto.randomUUID` exists only at
a secure address.

## The Admin App

`apps/admin` is the Admin app, for members of the `admin` group, at `/admin/` (the design is
`docs/superpowers/specs/2026-11-18-admin-app-design.md`). The server sends its files to administrators only (see
[How Healdan Extends LogicalDOC](../how-healdan-extends-logicaldoc/#healdans-code)), and it calls the admin API
under `/api/v1/admin` (see the [API](../api/#the-admin-apps-routes)).

- **Its router** has the base path `/admin` (`ADMIN_BASE` in `addresses.ts`), and reads and writes an address's
  parameters with the shell's `parseSearch` and `stringifySearch`, as plain strings. A page reads only the parameters
  its route takes, so others in an address are ignored.
- **Its addresses** are listed in `addresses.ts` (the spec's section 6.3). LogicalDOC's `UrlRewriteFilter` isn't
  anchored at the start of a path, and `login.jsp` drops some return paths, so `addresses.test.ts` fails on an
  address, routed or listed, with a segment `login`, `setup`, `frontend`, `license`, `sessions`, `mobile`, `view`,
  `webform`, `sc` or `ace`, or one starting `info`, or that ends in a slash, `/admin/` apart.
- **Its words** are a catalogue of its own, over the shared one (see [The Catalogue](#the-catalogue)), so the User
  app never downloads them.
- **No PDF.js.** It previews no document, and its page's policy allows no worker (`worker-src 'none'`), so its build
  has none (see [The Dependency Rules](#the-dependency-rules)).
- **Its components** are `@healdan/ui`'s, which has some the User app doesn't use: `ProgressBar` (React Aria's, whose
  count, such as "212 of 412", is what a screen reader hears), `StatusDot` (a status always said in words, the dot
  only decoration), `GroupChip`, and `SearchPicker`, a field with rows under it: starting rows, then the server's
  answer once the typing pauses, from two characters, with the words it matched marked as text and an older answer
  dropped. `TextField` takes `multiline`, a `PickField` option an `icon` and `meta` (secondary text that is part of
  its name), and `Table` `selectionMode="none"`, for a list whose rows lead to a page: a click or Enter opens a row,
  and none draws as selected.
- **To test it,** `test/renderApp.tsx` renders the whole app at an address under `/admin`, answering from a small
  fake API in `test/fakeApi.ts`, and `test/axe.ts` gives `packages/ui`'s `expectNoAxeViolations`.

At an `/admin` address the server answers someone who isn't an administrator with a `403` and the User app's own
`index.html`. The User app's `main.tsx` checks the address before anything else and draws only `@healdan/shell`'s
`NoAdminAccess` there, "You don't have access to Healdan Admin.", with **Open Healdan**, a link to `/app/`: it asks the
server nothing, at a secure address or not.

The way in is the User app's person menu (`apps/user/src/frame/PersonMenu.tsx`): **Admin**, a link to `/admin/` just
before **Classic Interface**, offered when `useMe()` says `admin`. It's a link and no more, so none of the Admin app's
code is in the User app's build, and only the server decides who gets in.

### The Frame

`frame/Frame.tsx` is the root route's component: the top bar (`TopBar.tsx`), the left pane (`LeftPane.tsx`) and
`<main>` with the route's view. Below 1,024 pixels (`useNarrow()`) the pane folds away behind the top bar's
**Navigation** button, as the User app's does: it opens over the main area, and following a link in it closes it, as
does Esc, which puts the focus back on the button (the shell's `openLayer` and `isInnermost`, so a menu in the pane
takes Esc first).

- **The top bar** has the mark, **Healdan** and the **Admin** tag, which link to the Dashboard; the find; **Open
  Healdan**, a link to `/app/`; the shell's `HelpMenu`, whose guide is `ADMIN_GUIDE`, the Admin Guide's
  [The Admin App](../../admin/the-admin-app/) (moving that page breaks Help's link); and the shell's `PersonMenu`,
  with no items of its own.
- **The find** (`FindBox.tsx`) is `@healdan/ui`'s `ComboBox`, as the User app's search box is. It asks
  `GET /v1/admin/find` once the typing has paused for 150 ms (`FIND_DELAY_MS`), from two characters, or a number of any
  length, as a client's is, sending at most 100 (`FIND_MAX`), and keeps the last answer on screen while the next comes.
  What's chosen opens where `DESTINATIONS` says for its kind, which is `/frontend.jsp` (`CLASSIC`) for clients, matters
  and people until their screens ship. A found matter can't open in the User app, whose addresses take its folder's
  id, which `FoundMatter` doesn't carry. A hidden `role="status"` says "Nothing found." or the error.
- **An address no route has** is `views/NotFoundView.tsx`, in the frame, with **Go to the Dashboard**. A view that
  fails as it draws is `RouteError` (`router.tsx`): the catalogue's message and Try Again, never the error's own text.

### Adding a Screen

`NAV`, in `frame/LeftPane.tsx`, is the left pane: the Dashboard, then the spec's groups in its order (Firm, People,
Storage, Search and Monitoring), each a heading from the catalogue and its `NavItem`s. A group with no items isn't
drawn, so the pane shows the Dashboard alone until a later phase adds a screen. **Classic Interface** is at the
pane's foot, apart from `NAV`. A screen joins when its phase ships:

1. Write the view in `views/`. Take its data through `useAdminQuery` (below), and its pieces from `@healdan/ui`.
2. Give it a route in `router.tsx`, a child of the root, at its path in `addresses.ts`' `routes`.
3. Add a `NavItem` to its group in `NAV`: `label`, an `admin.` key of the catalogue; `route`, typed `NavRoute`, the
   registered router's route ids, so only a routed path type-checks; `icon`; and, where something there can need
   attention, `count`, the attention codes whose counts its badge adds up from the Dashboard's answer. The pane asks
   `GET /v1/admin/dashboard` only while an entry it shows has a `count`. `aria-current="page"` marks the open entry:
   the Dashboard at `/` only, and any other at its address and the pages under it.
4. Point what led to the Classic Interface at it: the Dashboard's links, which the server gives (`AttentionItem.link`
   and `HealthTile.link`, from `healdan-core`'s `DashboardService.Screen`, `/frontend.jsp` until the screen ships),
   and the find's `DESTINATIONS`. An attention row's
   link takes its label from `NAV` (`screenAt`: the screen's name, **Classic Interface** for `/frontend.jsp`, or
   **Open**), so it follows by itself.
5. Add its words to the app's catalogue, and test it with `renderApp`, the fake API and `expectNoAxeViolations`.

### Asking the Server

`useAdminQuery(path, options)`, in `queries.ts`, makes every GET a screen asks. Its `path` is an admin route with a
JSON answer (`AdminGetPath`), and its query and answer are typed from `openapi-admin.yaml`. Its key is
`adminKey(path, query)`, `['admin', ...the segments after /v1/admin, the query]`, so `adminKeys.dashboard`,
`['admin', 'dashboard']`, covers the Dashboard and its Usage alike, and no key of the shell's is one of the app's.

| Option | What it does |
|---|---|
| `query` | the route's query parameters |
| `enabled` | `false` asks nothing, as for a find with nothing typed |
| `poll` | asks again every `POLL_MS`, 30 seconds, while the page is visible, never while it's hidden (`refetchIntervalInBackground: false`), and at each return to it however fresh the answer is (`ASK_ON_RETURN`) |
| `keepPrevious` | keeps the last answer on screen while a new question is asked, so a list doesn't blink while someone types |
| `staleTime` | TanStack Query's |

`useDashboard` and `useUsage` poll. The Dashboard's **Refresh** invalidates `adminKeys.dashboard`, which asks for both
again. Each part (`dashboard/`) draws its query through `PartState`: Loading, its own `ErrorPanel` with Try Again, or
what it shows, so a part that fails leaves the others whole. An attention code or a tile the app's catalogue doesn't
know, a newer server's, shows the server's own detail or name rather than failing.

### Refusals Are the App's

A screen never handles a `401` or a `403 admins-only` itself. `start`, in `main.tsx`, makes the app's one client with
`guardApi(api, access, signedOut)` (`access.tsx`) where it makes the raw one, and hands it and `access` to `App`, which
gives it to `ServicesProvider`, so every `useApi()` gets it and nothing in the app can reach a client that isn't
guarded; the tests' `renderApp` does the same over the fake API. Its `get`, `getResponse` and `send` mark `access` at a
`403 admins-only` from any call, and once that or a `401` has come (the shell's signed-out state, which `createApi`
marks), no call is sent again: each throws the refusal, made in the browser. `url()` makes an address, not a call, so it
isn't guarded. `Frame` reads `useSignedOut()` and `useRefused()`, and draws `SignedOut` or `NoAdminAccess` in place of
the frame and every screen in it, which stops their polls; the guard stops a call already on its way and one an effect
makes as it goes. `access` never goes back: only a page load, which the server answers, lets someone in again. Any other
refusal or failure is the screen's own. `access.test.ts` has the cases.

## Lists

A list of documents is `@healdan/ui`'s `Table`: React Aria's Table, virtualised so only the rows on screen are drawn,
with sortable headings and single or multiple selection. It passes Enter (or a double-click) out as `onOpen`, Space as
`onPreview`, F2 as `onRename`, and Delete or Backspace (the Mac's delete key) as `onDelete`, for the view to act on.
It takes those keys only from a row or its cells, so a field inside a row keeps them, and leaves them to the browser
with ⌘, Ctrl or Option held. A row whose `selectable` is false, such as a matter among a search's results, opens on a
click instead. So does a row whose `opensOnClick` is true, a folder among documents, though it selects as a document's
row does, with Shift- or ⌘-click, the arrows, Space and a right-click: the Table stops a plain press of the main button
on its way down to the row, so React Aria never selects it, and opens the row on the click. A touch or a pen is left to
React Aria, whose tap opens it. Pass `busy` while the rows shown are about to change, as `useListing`'s `isStale` says while a new order
loads: the old rows stay, and a screen reader waits for the new ones. Give it `loadMore` and `total`, and it calls `loadMore`
when the end of the loaded rows comes into view while fewer than `total` are loaded. A folder's list takes its rows
from `views/useListing.ts`, TanStack Query's infinite query over `GET /folders/{id}/children`, 100 at a time. Offset
pages shift when the folder changes between them, so it keeps one copy of a row a later page repeats.

Every list of documents takes its Title column from `views/listColumns.tsx`, whose cell ends with `HoldMarker`
(`actions/HoldMarker.tsx`) for a document whose `hold` isn't null: Check Out's pencil for a check-out, or the padlock for
a lock, in a `role="img"` span whose name and `title` are `holdLabel`'s: who has it and nothing more, such as "Checked
out by Priya Nair" or "Locked by you", with no time, computer or full stop. The span holds the icon alone, and no rule of
a hovered, focused or selected row changes it, so it never moves; a screen reader reads its name, and the details'
Check-Out Info has the rest. Only a held row asks who the person is (`useMe`), to tell their own holds from others'. The
Table colours a cell's icons `--hd-text-secondary`, and the row's own text colour on a selected row, so the marker meets
contrast in both themes. An icon that stands for a kind of item keeps its kind's colour (see
[Kind Colours](#kind-colours)) but on the accent fill of a selected row in a list that has the focus, where it takes the
text's colour too.

The virtualiser places the rows itself, so the Table needs a height: it fills a flex column, as the folder view's is.
jsdom has no IntersectionObserver, which notices the end of a list, so the tests' setup installs one that sees
nothing until a test calls `bringObservedIntoView()` from `test/intersection.ts`, as scrolling to the end would.

## Keeping the Page Up to Date

The app has no push updates yet (M6's spec §16). Instead it asks again for what's on screen each time the person comes
back to its window, as well as after their own changes. The rules are in `@healdan/shell`'s `queryClient.ts`, whose
`createQueryClient` makes each app's one `QueryClient`:

- **An answer stays fresh for 30 seconds** (`staleTime`), so moving between views doesn't ask again, and a return asks
  again for what's on screen and older than that (TanStack Query's `refetchOnWindowFocus: true`).
- **A return asks again for what shows a document's state, however fresh the answer is.** A query that shows it carries
  `meta: ASK_ON_RETURN`: the document, its properties and versions and Recent (`queries.ts`), and a folder's list (`views/useListing.ts`). The return marks them stale (`invalidateQueries` with `refetchType: 'none'`)
  before it tells TanStack Query, which then asks for them as for any stale answer. A document opened in Excel through
  the helper is checked out a few seconds after the click, and saved to minutes later, so an answer from just before
  would show it free, or at its old version. A new query keeps to the 30 seconds unless it carries the same `meta`.
- **A return is the window getting the focus back, or a hidden tab showing again.** TanStack Query listens only for
  `visibilitychange`, which moving from Excel back to a browser window that stayed on screen doesn't fire.
  `createQueryClient` gives `focusManager.setEventListener` the app's own `listenForReturn`, which listens for the
  window's own `focus` too. The focus coming back to the page from a frame in it, an email's body, fires that as well,
  and is no return: the window's `blur` notes whether the focus went into an `<iframe>`. A tab's return fires both, and a
  request already under way isn't sent twice.
- **The person's own changes** call `invalidateDocumentLists` (`actions/refresh.ts`) when they're done, which asks again
  for the document, its properties and versions, the folders' lists, search results, Recent, My Favorites, Recent Matters
  and Recent Clients. A folder's rename that worked calls `invalidateFolderNames` instead, which asks again for what shows
  a folder's name: the folders, their lists and My Matters' subfolders, search results, My Favorites and the documents,
  whose views carry their folder's path. A refused one asks again for nothing.
- **Open in App** calls `markDocumentListsStale` instead, which marks the same as stale without asking now
  (`refetchType: 'none'`), but for My Matters' folders, which a tree with many open would ask for one by one: the
  helper's check-out comes later, so an answer now would stay fresh for 30 seconds and show the document free. The return
  to the window asks again for what's on screen, and so does any view moved to.
- **A failed refresh keeps what's shown.** `DetailsPanel` gives way to an error only when the document or its properties
  never answered, or the document is gone (`404`), so an open properties form keeps what's typed in it.

What shows a document's state but isn't asked again on every return:

| Query | Setting | Why |
|---|---|---|
| A search folder's list (`views/FolderView.tsx`) | `useListing`'s `searchFolder`, which leaves out `ASK_ON_RETURN` | Each page runs the folder's saved search on the server, as its counts do |
| A list scrolled past its first page (`askedOnReturn` in `queryClient.ts`) | Not marked stale on a return | A return would ask for every page it has, one after another |
| Search results and their counts (`search/useSearchResults.ts`) | `refetchOnWindowFocus: false` | Each page and each count runs the whole search on the server; a search is asked again when it's run again |
| An email's message, a picture and a PDF in Quick Look (`preview/`) | `refetchOnWindowFocus: false`, `staleTime: Infinity` | They're read once for each file version, and a new version has a key of its own |
| The left pane's Checked Out to Me count (`useCheckouts` in `queries.ts`) | No `ASK_ON_RETURN`; the view itself asks with `refetchOnWindowFocus: 'always'` (`useCheckouts({ live: true })`) | The count is on every page, and the list reads every document checked out to the person, each with its device's hold; it's asked again after the person's own changes, as every list is |

The first two are asked again on a return once their answer is over 30 seconds old, as everything else is. Thumbnails
aren't queries: each is an `<img>` addressed by its file version. A test comes back to the window with `comeBack()` from
`src/test/comeBack.ts`, which dispatches a `focus` event on `window`, and `settle()` gives what would be asked a moment
before the test checks it wasn't. `e2e/working.spec.ts` dispatches the same in both browsers after checking a document
out and saving to it through the API with a device token, each once the page has every answer it asked for: a return
while one is on its way is met by that answer, from before the change.

## Filters and the Right Region

A folder's filters are `?type=pdf&type=word&author=pnair&class=…&edited=7d` (or `from` and `to` as `2026-11-02`) in the
address: `filters/useFilters.ts` reads them (`readFilters` leaves out what the API would refuse, so a pasted address
never makes the list an error), counts them for the funnel's badge, and writes them back with `navigate`, replacing the
entry. The router's `parseSearch` and `stringifySearch` carry the repeated ones as lists, and each route that takes
filters adds `filterSearch` to its `validateSearch`. `filterParams` adds `zone`, the browser's IANA time zone
(`Intl.DateTimeFormat().resolvedOptions().timeZone`), which the server cuts Today and a range's days in, so they are the
days the list's dates show. `useListing` sends them with every page; `filters/useFacets.ts`
asks for the counts on their own (`GET /folders/{id}/children` with `facets`, `limit=1`) only while the panel is open,
and for the folder's count with no filters only while one is in use, which is the "7" of "3 of 7 documents".
The chips name Authors, clients and matters from the list's rows and the panel's options (`useAuthorNames`,
`useMatterNames`), and show a username or a number they have no name for. The panel takes the same client and matter
names for a ticked option its counts don't offer.

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
list's row shows at once as the placeholder) and renders a fixed header, with the thumbnail, the name,
`# n · Version v · size` (`formatSize` in `@healdan/ui`), the actions, and a held document's `HoldInfo` and
`CheckoutControls`, then `@healdan/ui`'s `Tabs` (React Aria's tabs, drawn as the helper's segmented control): **Properties**,
with **Edit Properties** at its top and `Properties` or the form under it, and **Versions**, with `useVersions`'s
`total` in brackets once it's known. Only the selected tab's panel is drawn, except Properties while its form is open: `Tabs`' `keepMounted`
keeps it mounted, hidden and inert, so what's typed in it stays while the person looks at Versions. The tab is `details/DetailsTab.tsx`'s,
a context in the frame, so it stays as the person left it while each document's panel comes and goes, and a page
load starts on Properties; a `version` (a `#` that named one) switches it to Versions, and the filing tray's **Edit**
to Properties. A search view passes
`context="search"`, which adds Location, linking to `/app/folders/<id>`. The panel never reads a file: the thumbnail
is an `<img>` on `/api/v1/documents/{n}/thumbnail`, which the web session's cookie goes with, and an icon of the
file's kind (`kindIcons.ts`) shows when there's none or the person can't open the document. Properties come from the
list the server gives, by name; an email's From, To, Sent and Received show when any of them has a value, as the
server fills them from the message and the browser never parses a file. **Preview** opens Quick Look (see Previews,
below), and **Copy Link** (`actions/copyLink.ts`) writes `location.origin + '/app/doc/' + n` with the Clipboard API
and shows a Toast, either way. The other actions are in "Working with a Document", next.

`/app/doc/10388`, `/app/doc/10388.2` and the other forms of the lookup are `views/DocResult.tsx`: it asks
`GET /v1/lookup?q=%2310388.2` and shows a list of that one document (`views/listColumns.tsx`, the folder list's
columns), selected, with its details beside it on the Versions tab and the named version marked. `useSelection` takes the
number from the address on this route. A `404` shows "No document # 10388 that you can see.", the same for a
document that isn't there and one that can't be read, and says only the number as typed; a `400` shows the not-found
view.

## Working with a Document

`actions/` holds what a person does to a document, and every place that offers it goes through it.

- **`launch.ts`** is the one place the page follows an address: it clicks a transient `<a>`, so a `healdan:` link goes
  to the helper without leaving the page and a file's address downloads under its name. No script reads the bytes, and
  no blob is made. jsdom can't navigate, so `test/setup.ts` replaces it with a mock for every test, and a test that
  cares imports `launch` and reads its calls.
- **Open** (`openInApp.ts`, ported from the old UI's `HealdanOpenInApp` with the same cases in its test) builds
  `healdan://open/<doc #>?server=<host>` for the kinds the helper opens, and downloads any other. `OpenInAppProvider`,
  in the frame, shows the notice ("If nothing opened, install the Healdan Helper") as a toast with a link, for 15
  seconds (`toastWithLink` in `@healdan/ui`; the Toast region is above Quick Look and outside what its dialog hides), and gives `useOpenInApp()` to
  Open buttons, Quick Look and the lists. It then marks the document and the lists stale, for the return to the window
  to ask again (see [Keeping the Page Up to Date](#keeping-the-page-up-to-date)). Double-click and Enter on a row go through `useOpenKey`, on every list of
  documents, as Space goes through `usePreviewKey`; a document the person can't download (`canOpen`) is only selected.
- **Download** (`download.ts`) is `launch` on `GET /v1/documents/{n}/content`, with `?version=` for a version. The web
  session carries it, as it does for an email's attachment chip. A refusal the server makes after the click (a
  `503 storage-unreachable`, a `500`) is the browser's: it shows a failed download, or some browsers save the small
  answer as a file, and the app sees none of it. `canOpen` disables the button for the usual refusal, a missing
  permission or a password.
- **Check Out, Check In and Release** (`checkout.ts`, `useCheckout.ts`, `CheckoutControls.tsx`) follow the document's
  `hold`. The web session is no device, so the app never takes over a check-out a device holds (`409 held-elsewhere`);
  it only releases the person's own, after a warning (below). `details/HoldInfo.tsx` is the hold's own section above the
  tabs, **Check-Out Info** or **Lock Info**, a `Section` with `boxed`: User, Check-Out Time (or Lock Time), and, from
  `hold.heldOn`, Application and Computer, each but User left out when the API gives no value (User is "Someone" then),
  in `details/PropertyList.tsx`, which `Properties` draws its rows with too. The API gives `heldOn` to the holder and to
  administrators only, and the app never decides anything by it: `device` still says whether the check-out is the
  person's own on a computer. `holdSentence` says who has it and since when, as a sentence, for the drop-on-a-row
  question and the refusals (`filing/refusal.ts`). A check-in from the app is a file picked into
  `POST /v1/documents/{n}/versions` as a snapshot, with `ifHeld` and `ifVersion`. Every outcome asks for the document
  and the lists again.
- **Release** (`actions/ReleaseProvider.tsx`, in the frame) is every release, from the toolbar, a row's menu and
  Check-Out Info: `useReleasing().askRelease(documents)` decides once, by `holdState`'s `yourDevice`, which of them
  another of the person's computers holds. When any is, it shows `ConfirmRelease` first, an alert dialog that lists
  them by computer (by `hold.device.id`, not the name, so two computers of one name are two lists), with the red
  button first and Cancel focused; then it releases each in turn (`eachDocument`), sending `elsewhere` only for
  those, with the device id the warning named, so a check-out that has moved since stays where it is, and names the
  refusals in `Refusals`. The warning's title counts what it lists; its red button, everything it releases. Its
  mutation's variables are the documents, which `useHoldsBusy` reads to make every control that offers a change to
  one of them pending. It
  doesn't wait for the lists to answer, as useCheckout doesn't; the focus moves to the next row once they have, and
  only when the released rows were drawn.
- **Editing properties** is `details/PropertiesForm.tsx`, made from `GET /v1/documents/{n}/properties` and saved with
  `PATCH`, sending only the fields that changed. `frame/errors.ts`'s `fieldErrors` gives each field a 422 names its
  message from the catalogue (`fields.<code>`). **Overridden** shows only where the API's `overrides` names the folder a
  `local` value takes the place of, and **Follow Folder** sends `null`; a local value with no folder behind it is a plain
  value. Save compares with the list the form opened with, not the live one, which a refetch can change under it.

## Filing by Drag and Drop

`filing/` is filing in the app (M6 spec 6.5). `FilingProvider` sits in the frame and owns the pieces that outlive a view:

- **The queue.** `uploads.ts` (`createUploads`) runs at most three requests at once, each its own `POST /v1/documents`
  or, for a drop on a row, `POST /v1/documents/{n}/versions` (the call `actions/checkout.ts`'s `saveVersion` makes, shared with
  Check In). It lives in memory only, so uploads go on while the person moves between views, and a `beforeunload` handler
  asks before the page is left while any is running. A finished request asks for the lists again
  (`invalidateDocumentLists`), and a new document is `fresh` for a few seconds, which flashes its row.
- **The drop.** `dropTargets.ts` decides what a drag carries and where it may go: `refusalOf` says a matter's top level, a
  search folder and a Store hold no documents (`filing.chooseFolder`), and a folder the person may not write to takes none.
  `useDropZone` puts the handlers on a folder's list: a folder's row is a drop target for that folder (its kind and `canFile` decide, as for a tree folder),
  and one file on a document's row asks for a version only when its extension is the document's (`sameExtension`). The left
  pane's tree takes drops through `Tree`'s `acceptsDrop` and `onDrop`. Both count `dragenter` and `dragleave` rather than trust
  `relatedTarget`, which WebKit can leave null. A drag outside every target is cancelled by `FilingProvider`'s `window` listeners,
  so the browser never opens a dropped file in place of the app. A drop is read at once, as the browser empties its data transfer when the event ends. It handles only
  drops that carry `Files`; a drop that carries `ROWS_TYPE` (`application/x-healdan-documents`, the app's own rows,
  which a move takes) is left alone, and a drop of a link or text says to drag the email to Finder first. A dragover of a
  link or text is cancelled too, or the browser would follow the link and leave the page.
- **The form.** `GET /v1/folders/{id}/form` says what a new document would have. When a required property has no value, or
  Option is held, `FilingForm` appears first: a title for each file, and `details/FieldsForm.tsx`, the form of a document's
  properties (`PropertiesForm` is the same form over `PATCH`), so the two share their rules (required marks, lists, Subclass
  narrowing, dates, number checks). A user property, such as Author or Typist, is `details/UserField.tsx`: `@healdan/ui`'s
  `PickField` (React Aria's ComboBox with its label above it), which asks `GET /v1/users?q=` once the typing pauses
  (one character is enough; the server answers 20 at most), offers the field's own person first, and sends `{ id }`,
  as the helper does. A folder or document property stays text.
- **The tray.** `FilingTray` over `@healdan/ui`'s `Tray`, marked `data-react-aria-top-layer` so a modal's hiding of the page
  leaves it for a screen reader. A refusal is said from the catalogue (`refusal.ts`), from the problem's `code`, its
  `fields` or its `hold`, and an `already-filed` names the copy. **Edit** asks the details panel to open its form
  (`details/editRequest.ts`).

- **The Upload button** is the keyboard's route into filing, as a drop has none: it opens the file picker, and pressed with
  Option held (Option-Enter, or an Option click) the form comes first, as it does for a drop with Option. Dropping on a
  row and dragging rows are for a pointer; Check In and **More › Move To…** are their keyboard forms.

Test it with `filing/*.test.tsx`; jsdom has no `DragEvent`, so a test that needs Option puts `altKey` on the event by hand.
`e2e/filing.spec.ts` drops real `File`s in a real browser, which Playwright can only do by dispatching the events itself
with a `DataTransfer` built in the page.

## Organising

`organising/` is New Folder, rename, move and delete (M6 spec 6.7; folders and Trash spec 7.4 and 7.5).
`OrganisingProvider` sits in the frame under the filing's and owns what outlives a view: which document's title is a
field now, and the dialogs that ask (`ConfirmDelete`, `MovePicker`, `NewFolderDialog`) and say what was refused
(`Refusals`). Views reach it through `useOrganising`, whose `askDelete` and `askMove` take `OrganisingItems`, the
documents and folders together, and `useOrganisingTable(rows, selection)` gives a list its `onRename` (F2), `onDelete`
and `drag` props and the selected documents and folders for the toolbar's More menu (`actions/MoreMenu.tsx`).

- **One call each.** `actions/rename.ts`, `move.ts` and `remove.ts` make `PATCH /documents/{n}`, `POST /documents/{n}/move`
  and `DELETE /documents/{n}`, and `actions/folders.ts` makes `DELETE /folders/{id}`, `POST /folders/{id}/move` and
  `GET /folders/{id}/contents`. A change to several items (`actions/batch.ts`) is a call for each, one after another,
  the folders first, as a list shows them, then the documents (`eachItem`); it keeps going when one is refused, so what
  was done stays done, and the refusals come back as lines, each the item's name and why, from the catalogue
  (`filing/refusal.ts`: a hold says who holds it, an `already-filed` names the copy).
- **Deleting folders.** The Delete key on a folder's row or on a folder in the tree, the folder's right-click menu, the
  tree's ⋯ and the open folder's ⋯ all ask (**Move To…** and **Delete** follow **Rename** in each, and a matter's own
  folder has neither). For one folder, `canDelete` greys **Delete** out, and `whyNotDelete` says why the key does
  nothing, as `whyNotRename` does for F2. In a selection of several, **Delete** stays while anything in it can be
  deleted: a folder that can't makes no call and is a line of `Refusals` with `whyNotDelete`'s reason, while the rest is
  deleted. The question names one item, "Move "Old Drafts" to the Trash?", with what a folder holds
  from `GET /folders/{id}/contents`, which counts only what the person can read, and lists several. A refusal is a line
  of `Refusals` (`folderDeleteRefusal`): `items-not-allowed` names nothing, and `checked-out` lists only the problem's
  `holds`, the documents the person can read. An open folder that's deleted gives way to its parent.
- **Undo.** Every delete says what it did with **Undo** (`toastWithAction`), and `organising/undo.ts` restores each Trash
  item the delete answered with, back where it was (no `folderId`), then says what came back. One already restored
  (`404`) or whose place is gone (`409 place-gone`) is a line of `Refusals` in Undo's own words, which say what happened
  (`undo.alreadyRestored`, and `undo.placeGone`, which sends the person to the Trash, as `Refusals` only closes), and
  the rest is restored. The action spends itself on its first press, whatever presses it, as the server doesn't
  serialise two restores of one item.
- **Renaming folders** uses the same field. `RenameField.tsx` holds one field for documents and folders
  (`DocumentTitle`, `FolderName`), built on `useNameField`, which a favourite's name in My Favorites shares, and `OrganisingProvider`'s `renaming` is a row key and a place (`row`, `tree`,
  `heading`), where the focus goes back once it's done. F2 on a folder's row in a list or in the My Matters tree, the
  tree's ⋯, the ⋯ beside the open folder's name and the folder's right-click menu all start it, and `canRename` on the
  folder decides whether **Rename** is greyed out. A save that ends after another rename began leaves that one open. In
  the tree, which is narrow, a refusal goes below the field (`stacked`), and a row's ⋯ sits over the row's end
  (`Tree.module.css`), so a hidden one takes none of the name's width.
- **Rename in place.** `RenameField` replaces the title in the row. Enter saves, Esc cancels, and leaving the field with a
  new name saves it. A refusal keeps the field open with the message beside it (`renameRefusal` words another extension
  as what to do about it), and the focus goes back to the row when the field goes. The field stops its key and pointer
  events, so the Table's own keys (Space, Delete, the arrows) and a double-click don't reach the row.
- **The picker.** `MovePicker` takes the `OrganisingItems` being moved. It starts from Recent Matters and My Matters (the left
  pane's queries) and the search box's matter suggestions, then lists the chosen matter's folders from `fetchSubfolders` (also
  the tree's), a level at a time. It never browses Stores and lists no search folders. A folder being moved is a greyed-out
  choice ("The folder itself") that doesn't open, and **Move Here** is greyed out, with the reason as its description, only
  where nothing being moved can go: where every item already is ("Already there"), and where none of them can go, which for
  documents is a place that holds none (at a matter's top level, as before) and for folders a matter's top level for anyone
  but an administrator ("Administrators only"). While some of them can go, Move Here moves those and Refusals says the rest,
  as a drop does (`whyNotHere`, `planMove`). The Trash's restore picker passes no items, so it greys nothing out and the
  server decides.
- **Moving folders.** `actions/move.ts`'s `planMove` works out a move before any call: what's in the place already is left
  out, documents go nowhere that holds none, and a folder goes neither into its own tree nor anywhere its `canMove` or M1's
  placement rules refuse. What it refuses, and what the server refuses (`folderMoveRefusal`: `items-not-allowed` names
  nothing, and `checked-out` lists only the problem's `holds`), are lines of `Refusals`, while the rest is moved, the
  documents first and then the folders, each with its own call. A refusal names the check the server's `cause` says:
  for `admins-only` the folder's own top level, or the matter's top level it was going into (as a drop on a matter in the
  tree is refused before any call), and for `forbidden` no Move on the folder, or no Add where it was going. **Move To…** is there while anything selected can be moved (`anyMovable`). Before a folder moves into another matter (its `matterId` and the place's differ), `CrossMatter` asks, once
  for each such folder, in turn, counting its documents with `GET /folders/{id}/contents`; **Cancel** keeps only that folder
  where it is. The toast names a folder by the name the server answered, which CE changes on its way in when a folder the
  person can't see already has its name.
- **Dragging rows.** The Table's `drag` prop makes its selectable rows draggable, with the browser's own drag and drop (React
  Aria's would add some 140 kB to the app's chunk of it), so a drag of a selected row carries every selected row, and of another row only that one. A drag carries `ROWS_TYPE` with the documents, and the folders dragged with them, as JSON (`rows.ts`);
  the tree's own folders drag the same way (`Tree`'s `drag` prop), and a matter in it doesn't. `FilingProvider` leaves the
  drag alone and the tree takes it (`acceptsDrop`, `dropRows`, then `planMove`): the folder lights up as it does for files,
  and a place that holds no documents, or a folder dropped into its own tree, is refused before any call. There's no
  keyboard drag: More › Move To… is the way for a keyboard.
- **New Folder** is offered inside a matter's folders, and at a matter's top level only to an administrator (`useMe`).
- **Every list of documents** has it: a folder, search results, Recent Documents and Emails, Checked Out to Me and the
  `#` result, each with `useOrganisingTable` and a `Toolbar` holding `SelectionActions`, and Recent › Folders and a
  Folders search have F2 and Delete. `useRowSelection(rows)` puts one document selected alone in `?sel=`, and keeps
  several, or any with a folder, in the view, as a folder is never in the address and has no details. A matter's own
  folder, where a list shows one (Recent › Folders), isn't selected or dragged, as a matter's row isn't (`selectable` in
  `useListing.ts`), and a click opens it. It keeps only the selected rows still listed, so once they're deleted or moved
  F2 and Delete take the focused row.

Test it with `organising/*.test.tsx`; `testing.tsx` has routes for a folder that rename, move, delete and restore as the
server would, a second matter for moves out of a matter, a `DataTransfer` that keeps what a drag writes, and `closeMenu`,
which waits after Esc for React Aria to give the focus back a frame later, before the next control is pressed. `e2e/organising.spec.ts` does it in a real browser, and `e2e/folders.spec.ts` for renaming folders.

## The Trash

`views/TrashView.tsx` is `/app/trash`, from the **Trash** entry at the foot of the left pane, apart from its sections. Its
rows are `GET /trash`'s items, paged as a folder's list is (`useTrash` in `queries.ts`, keyed `['trash', view, sort, order]`),
each keyed `t<id>` and selectable; the selection isn't a document, so it isn't in `?sel=`, and `RightRegionView`'s
`selected` shows `TrashDetails` for one item as a selected document's details are shown. `?view=` is `everyone` or
`removed` only for an administrator (`useMe`): anyone else is asked for, and shown, Mine whatever the address says, and
the switch, `@healdan/ui`'s `ToggleButtons` (React Aria's toggle button group, labelled **Show**), is drawn for
administrators alone. Name, Deleted and Removed sort; `?sort=` and `?order=` are in the address.

- **Restore** (`actions/trash.ts`'s `restoreItems`) is a call for each item, one after another. Each one restored toasts
  where it went, with **Open** (`toastWithAction`); one answered `409 place-gone` opens `MovePicker` with the title
  "Restore "…" to…", one at a time, and its **Move Here** restores with `folderId`; one answered `409 refused` (a Store's
  item, or a shortcut deleted on its own) says it can't be restored from here. The server's own text is never shown.
- **Remove…** and **Empty Trash…** ask first (`organising/ConfirmRemove.tsx`, with `Button`'s `danger` variant),
  saying how long an administrator can still restore it from the page's `removedDays`, never a constant. It's an alert
  dialog with a warning sign, as `ConfirmRelease` is, as the one step a person can't undo themselves; Delete's question
  and `CrossMatter` can both be undone, and stay plain dialogs. A folder's
  counts come only from its `contents`, which take in only what the person can read.
- **One at a time.** Restore, Remove and Empty Trash are disabled while one of them runs, so a second press sends nothing:
  the server doesn't serialise two restores of one item. Once the rows have gone, a focus left on the page, a gone row or
  a disabled button goes to the row that followed them.
- **Refreshing.** `'trash'` is among the roots `invalidateDocumentLists` asks again, so a delete anywhere, and a restore or
  a removal here, refresh the Trash with the lists of documents.

Test it with `views/TrashView.test.tsx`, whose routes answer each view's items and restore each to its place.

## The Search Box and Search Results

`search/SearchBox.tsx` is the top bar's scope picker (`scopes.ts`: the API's six `scope` values, and the four Recent
kinds, which are scopes too) joined to `@healdan/ui`'s `ComboBox`, React Aria's, with the options the caller gives in
named sections and not filtered by it. Focus stays in the box and the arrow keys move through the options. The list
comes right after the box in the page, in the top bar's search landmark, drawn in a layer fixed over the window
(`.layer` in `ComboBox.module.css`, so an element round the box that clips or is positioned can't cut it off or move
it), where React Aria would draw it at the end of `<body>`. That holds while nothing round the box holds what is fixed
inside it: a transform, a filter or `backdrop-filter`, a perspective, or containment (`contain: layout` or `paint`,
`container-type`) would shrink the layer to that element and let it cut the list off, so a combo box never goes inside
one, and the a11y specs check that the layer covers the window and the list starts under the field (`e2e/helpers.ts`'s
`expectListUnder`). React Aria's combo box hides everything outside the box and its list from screen readers while the
list is open (`aria-hidden`), because a list at the end of `<body>` is far from the box in reading order; with the list
beside the box there's nothing to hide, and `ComboBox.tsx`'s `PageStaysShown` keeps React Aria from hiding anything, as
React Aria has no switch for it yet ([adobe/react-spectrum#4937](https://github.com/adobe/react-spectrum/issues/4937)).
`PickField` draws its list the same way, with the same `useListLayer` and `ListPopover`. What scrolls when the options
don't fit is an element of its own with a `tabindex` of -1, as Firefox and Chromium otherwise stop Tab on a scroller
with nothing focusable in it (the options have none, as the focus stays in the box). `ComboBox.test.tsx` runs axe on a
whole page with the list open, and fails if React Aria hides anything again. Enter with
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
document and folder rows, shared with search) putting a document in `?sel=` and its details in the region; a recent client opens
a Clients search. `views/CheckedOutView.tsx` is `/checked-out`, the same list page as Recent's documents
(`views/DocumentListPage.tsx`) over `GET /v1/me/checkouts` (`useCheckouts`), with its own columns (`checkedOutColumns`,
none sorting, as the server's order is the latest check-out first). The left pane's entry (`CheckedOutEntry` in
`frame/LeftPane.tsx`) reads the same query's `total` for its count.

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
picture in place of the body (`/message/parts/{id}?inline=true`, with **Back to Message**) or downloads anything else
through `launch`. It's no link, and neither is **Download** beside **Back to Message**: a link could be dragged out of
the page or saved from its menu, and a download started there can lack the `Sec-Fetch-Site` the web session needs, so
the refusal would be saved under the file's name. **Save to Matter** posts to
`/message/attachments/{id}/file`, is greyed out when the email's folder has `canFile: false` (asked only while an
attachment could be saved), and the chip shows the new document's # when the message is read again. `working.spec.ts` checks the whole path in both browsers, with a tracking
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
  `assets/` as hashed (and so immutable for a year). These keep PDF.js's own names, so they're served `no-cache` with
  an `ETag` of their bytes: the browser keeps each and asks again before using it, and gets a `304` until an upgrade of
  PDF.js changes it. `scripts/check-dist.mjs` fails the build when the worker or a folder is missing, and
  `scripts/notices.mjs` adds their licences (Foxit's and Liberation's fonts, OpenJPEG's and JBIG2's decoders) to
  `notices.txt`.
- **Fonts from the file** are made with the FontFace API from the file's bytes, which `font-src` doesn't govern.
- **One worker** is shared by every document opened, for the page's life, and **a copy of the bytes** is given to PDF.js,
  which hands its argument to the worker and leaves the caller's empty. PDF.js can't run its parsing under jsdom (its
  main-thread worker times out there), so `pdfEngine.ts` has no unit test: `working.spec.ts` is its test.

## Signed-in Devices

`devices/SignedInDevices.tsx` is the dialog the person menu opens: `GET /v1/auth/tokens` lists the person's device
tokens (name, created, last used, expiry), and **Revoke** asks first, in the same dialog, then calls
`DELETE /v1/auth/tokens/{id}`. The web page's own sign-in is no device (`Caller.token()` is null on the web-session
route), so it isn't in the list, and the dialog says **Sign Out** ends it. A name is shown as text, never as markup.

## A Narrow Window

Below 1,024 pixels (`NARROW` in `@healdan/shell`'s `frame/narrow.ts`, read with `useNarrow()`) the left pane and the
right region fold away behind buttons in the top bar, **Navigation** and **Details and Filters**, each saying with
`aria-expanded` whether it's open. The app is designed for 1,280 pixels and works at a tablet's 768; phones aren't a goal.

- **The left pane** isn't drawn while it's folded, so it isn't in the tab order. Opened, it covers the list's left edge
  (`Frame.module.css`), and following a link in it, or Esc, closes it. Esc puts the focus back on the button.
- **The right region** keeps its content (Filters, the details or the docked reading pane), shown by
  `RightRegionView` over the list from the right edge while `narrowOpen` is true, and `useRegionKind` gives a list no
  narrower columns for it. `RightRegion.open()` opens it, so the funnel, **Preview** and the tray's **Edit** show it, but a
  click on a row doesn't (`open('details', { reveal: false })`): selecting is not a request to see it. With nothing to
  show it says so.
- **To test it** in Vitest, `test/viewport.ts` gives jsdom a `matchMedia` for a width (`setViewport(768)` before a
  render, `resize()` after), and `frame/Narrow.test.tsx` has the cases. `e2e/a11y.spec.ts` looks at it at 768 × 1024.

## Resizing the Panes

In a wider window the left pane and the right region each have a divider, which a person drags or moves by keys, and
the list takes the rest.

- **The divider** is `@healdan/ui`'s `Splitter`: a focusable `role="separator"` with `aria-orientation="vertical"`,
  whose `aria-valuenow`, `-valuemin` and `-valuemax` are the pane's width and limits, and `aria-controls` the pane. A
  pointer drags it through `useMove` from `react-aria` (a direct dependency of `packages/ui`, at the version React Aria
  Components pins), with the pointer captured, so a mouse, a finger or a pen moves it, and an email's frame under the
  pointer doesn't take its moves. Its own keys replace `useMove`'s: Left and Right by 16 pixels (`SPLITTER_STEP`),
  64 with Shift, Home and End to the limits, and Enter, like a double-click, calls `onReset`. Left and Right follow
  the direction the divider is drawn in (its computed `direction`), not React Aria's locale, which is the browser's
  language: the app is drawn left to right whatever language the browser asks for. A drag reports each width with
  `onResize` and the last with `onResizeEnd`, and a key only `onResizeEnd`, and neither reports the width the pane is
  drawn at, so a press that only jitters keeps nothing. When the frame renders while a drag lasts (the window resized),
  it draws the widths it kept, and the divider reports the drag's width again before the page is painted. While a drag
  lasts, the page's root has `data-hd-resizing`, which keeps the cursor and stops text being selected. The visible line
  is the pane's own hairline edge, drawn over in the accent while dragged or focused. The target is 24 pixels wide: the
  left pane's divider covers only the pane's 1-pixel border, not its scrollbar, and 23 pixels of the main area's
  gutter, which the views' padding and the storage banner's, 24 each, keep clear; the right region's covers 4 pixels of
  the region and 20 of the list's gutter.
- **The widths** are `frame/PaneWidths.tsx`'s. `PaneFrame` is the frame's element: it holds each pane's width as a
  custom property, `--left-width`, `--right-width` (the details and Filters) and `--preview-width` (the docked reading
  pane), which `Frame.module.css`, `RightRegion.module.css` and the top bar's brand read, each with its old width as the
  fallback. A drag sets them on the element as it goes, so neither the frame nor a view renders again, and React Aria's
  virtualiser measures the list's new width itself (its scroll view watches its own size). Only the drag's end changes
  state. In a narrow window it sets none, and `PaneSplitter` draws nothing. The left pane's divider sits in `<main>`,
  fixed to the window, since axe wants all content in a landmark and the main area scrolls; the right region's is in a
  box of no width before the region (`RightRegionView`).
- **The limits** are `frame/paneLayout.ts`'s `layoutFor(windowWidth, chosen)`: the left pane 180 to 420 pixels (248 by
  default), the right region 260 pixels to half the window (300 by default; the reading pane 42% of the window, from 360
  to 640), and at least `MAIN_MIN`, 481 pixels, for the list: room for the three columns it keeps beside the reading
  pane (416 for a search's hits), the view's padding (48) and the list's own scrollbar (17, Windows'). The region's
  border is inside its width (`box-sizing: border-box`), so the list has the rest to the pixel.
  Room for the region at its narrowest is always left, so the left pane doesn't move as the region opens and closes, and
  the region takes what the list leaves. A chosen width that doesn't fit is drawn at the limit and not kept again.
- **What's kept** is `preferences.ts`, the User app's `preferences('healdan.')` from `@healdan/shell`, which keeps
  each app's preferences under a prefix of its own: numbers in `localStorage` under `healdan.pane.left`,
  `healdan.pane.right` and `healdan.pane.preview`, each call in `try`, so a browser that refuses storage (or whose
  `localStorage` getter throws) gets the defaults. Enter forgets the pane's, so the reading pane follows the window again. Preferences are
  the only thing the browser keeps (M6's spec §2.3).
- **A PDF waits:** `PdfView` draws its page at the first width it's given, and again only once a new width has stayed
  put for 150 milliseconds, as a canvas drawn again at each step of a resize would be blank for most of it. That holds
  for a divider dragged, the window's edge and the window snapped alike.
- **The window's width** is the root element's `clientWidth`, the layout width the frame's grid has, not
  `window.innerWidth`, which mobile Safari gives as the visual viewport's, smaller while the page is pinch-zoomed.
- **To test it,** `test/viewport.ts` also sets the root element's `clientWidth` and `window.innerWidth`, and sends the
  window's `resize` event, and the test
  setup clears `localStorage` after each test. `frame/paneLayout.test.ts` has the limits, `preferences.test.ts` storage
  that throws, `frame/Resizing.test.tsx` the dividers in the app, and `e2e/resizing.spec.ts` drags them in a real
  browser and reloads.

## The Look in the Dark

Both themes are defined as custom properties in `tokens.css` and chosen by the browser's `prefers-color-scheme`.
Anything that isn't a custom property is on purpose: the brand mark's blues (`@healdan/shell`'s `frame/Mark.tsx`) and
the white paper a PDF page or a sender's email is drawn on (`preview/preview.module.css`, `preview/email.module.css`), which keep their own
colours so a document reads as it was made. `tokens.test.ts` checks every text colour on every background it may sit
on, and every kind colour on every surface its icons sit on, in both themes. jsdom draws no colours, so `e2e/a11y.spec.ts` runs axe in a real browser in both, where contrast is
checked on what is drawn.

## The Keyboard

Rows 3 to 9 of the spec's manual acceptance run by keyboard in `e2e/keyboard.spec.ts`, with no click after the sign-in
page. A step that can't be done by keys is a finding to fix in the app, never to work round in the spec. The keys are in
the spec's section 10.5: `/` jumps to the search box, the arrow keys move through a list, Enter opens in the app, Space
opens Quick Look and Esc closes it, F2 renames, Delete moves to the trash after the question, and the tree, menus and
dialogs follow React Aria's, the details' tabs too (the arrow keys between them, Tab on into the selected one). The properties form (`details/PropertiesForm.tsx`) takes the focus in its first field when **Edit Properties** opens it, since the button goes grey, cancels on Esc, and gives the focus back to **Edit Properties** when it closes. A list's keys are in `@healdan/ui`'s `Table`, which takes them ahead of React Aria's so
Space previews rather than selects, and leaves them to a field inside a row.

## The Catalogue

Every word the app shows is in one typed catalogue, `packages/ui/src/strings/en-US.ts`, and a view gets it with
`t('key')`. A translation is a new file beside it. Labels are in US English and Title Case. `{matter}` and `{matters}`
are the firm's own words for a matter, which each app's `start` reads from `GET /server` before it first draws, waiting
5 seconds at most, and other `{name}`s are filled from the variables `t()` is given. Each of the API's problem `code`s
has a message here too, so the wording is decided in one place, and a test fails when one has none: the User app's
`frame/errors.test.ts` reads `openapi.yaml`'s codes, and the Admin app's `strings/problems.test.ts` reads
`openapi-admin.yaml`'s, which may have a message in the Admin app's catalogue or the shared one.

An app's own words are a catalogue of their own over the shared one, so neither app downloads the other's:
`createCatalogue(strings, plurals)` in `@healdan/ui` gives a `t()` and a `plural()` that look in the app's strings
first and then in the shared ones, with `{matter}` and `{matters}` still the firm's words. The Admin app's are in
`apps/admin/src/strings/en-US.ts`, each key starting `admin.`. `messageFor(error, catalogue)`, in `@healdan/shell`,
asks an app's catalogue for a problem's message before the shared one.

## The Colour, Type and Spacing Settings

They are CSS custom properties, prefixed `--hd-`, in `packages/ui/src/tokens.css`, and every component's CSS uses
them, never a colour of its own. Light and dark are both defined there, and follow the browser's setting. A test
checks the contrast of every text colour on every background it may sit on, so a new colour goes into that test's
lists as well.

### Kind Colours

An icon that stands for a kind of item is drawn in that kind's colour: `--hd-kind-word-processing`,
`--hd-kind-spreadsheet`, `--hd-kind-presentation`, `--hd-kind-pdf`, `--hd-kind-email`, `--hd-kind-picture`,
`--hd-kind-folder` (the accent, `--hd-accent-text`, as the Healdan Helper draws folders), `--hd-kind-matter` and
`--hd-kind-client`. A view gets the icon and its kind together from `apps/user/src/kindIcons.ts` and passes both to
`@healdan/ui`'s `Icon`, as `<Icon {...fileIcon(name)} />`, or `itemIcon` for a list's row. `fileIcon` goes by the
file's extension alone, which decides the icon and the colour. Its extensions are the server's kinds of file
(`FileKinds` in `healdan-core`, which the File Type filter uses), which `kindIcons.test.ts` reads from the Java source
and compares, so an extension added there fails the test until it has its colour here. LibreOffice's formats take their
Office counterparts' colours: an `.odt` is word processing's blue, an `.ods` spreadsheets' green and an `.odp`
presentations' orange-red, each with the plain icon of its kind, where Office's own formats have Tabler's DOCX, DOC, XLS
and PPT icons. A CSV, Other to the server, has Tabler's CSV icon in no kind's colour. Any other file, a drawing included,
is the plain document with no kind, and keeps the colour of what it sits in, as action and frame icons do. A matter's own
folder, as a favourite or a Folders search's hit may be, is the matter's briefcase (`folderIcon`).

`Icon` gives every icon `flex: none`, so it keeps its size beside text that a row cuts short, and a kind's icon a class
from `icons.module.css` and a `data-kind` attribute. A kind's colour fails on the accent fill (the folders' is the accent
itself), so each class draws in `var(--hd-icon-on-fill, <the kind's colour>)`, and every accent fill with text on it sets
`--hd-icon-on-fill` to `currentColor`, the text's own: a selected row in a list that has the focus (`Table.module.css`),
the item or option the keys are on in a menu or the search box's suggestions (`Menu.module.css`,
`ComboBox.module.css`), the accent button, a ticked box, and a date field's focused part and chosen day.
`icons.test.tsx` finds every rule in the components' stylesheets that fills with `--hd-accent-fill` and colours with
`--hd-accent-on-fill`, and fails on one that doesn't set it, so a new fill does too. On Inactive selection and
`--hd-pill` a kind's icon keeps its colour, and the Table leaves `[data-kind]` out of its muted icons with `:not()`.

The kind colours are icons, not text, so `tokens.test.ts` asks each for the 3:1 of WCAG 1.4.11, not text's 4.5:1, on
every surface such an icon sits on, in both themes: `--hd-window`, `--hd-row-alt`, `--hd-sidebar`, `--hd-line`,
`--hd-pill`, `--hd-selection-inactive` and `--hd-field`. A new kind needs its token, light and dark, its class in
`icons.module.css`, its name in `iconKinds` and, if it sits on a new surface, that surface in the test's list. jsdom
works out a computed colour from the stylesheets and the tokens, so a component test can check what colour an icon
takes (`Table.test.tsx`, `Menu.test.tsx`, `ComboBox.test.tsx`), though only a real browser draws it.

## The Dependency Rules

ESLint enforces these, so `npm run lint` fails on a break:

- **Only `packages/ui` imports React Aria** (`react-aria-components`, `react-aria`, `react-stately` and their
  `@react-aria` and `@react-stately` packages). A screen takes its components from `@healdan/ui`.
- **No `dangerouslySetInnerHTML`.** The server's text is never HTML: names, messages and fields are shown as text.
- **No app imports another's code,** by a relative path or by its package (an `@healdan/` name that isn't a folder of
  `packages/`), in an `import`, an `export … from` or an `import()`: the workspace's own rule,
  `healdan/no-app-imports-another` in `eslint.config.js`. What two apps need goes into a package, such as
  `packages/shell`.

And the build checks these:

- **Versions are exact,** and `npm ci --ignore-scripts` installs them with no install script run. The workspace's
  `.npmrc` sets `ignore-scripts` and `save-exact` for a plain `npm install` too, and `scripts/workspace.test.mjs`
  checks both, and that `.nvmrc` names the Node that `healdan-web/pom.xml` downloads. It also checks that
  `typecheck` has a `tsc -p apps/<app>` for every app and that the root program holds none, and that
  `healdan/no-app-imports-another` refuses and allows what it should.
- **Licences are checked** by `.github/scripts/check-web-licences.py`, and the build writes the notices for what the
  apps ship, which share the lockfile, into `apps/user/dist/notices.txt`. It fails on a shipped package with no
  licence file, and on an entry in `scripts/notices.mjs`' `DECLARED_ONLY` (a reviewed package that declares its
  licence without a file) that the apps no longer ship without one.
- **The page's Content-Security-Policy** allows no inline style, so a library that injects one is a problem:
  `scripts/check-dist.mjs` checks the one React Aria would inject, for pressed states, is a linked file under the
  app's base, `pressable.css`, which both apps take from `packages/ui/public/` (each app's Vite `publicDir`).
- **Each app's build holds only its own code.** Each app's `build` runs `scripts/check-dist.mjs` (with `admin` for the
  Admin app's), which fails when any file of the User app's build holds `admin.dashboard.title` (`ADMIN_MARKER`), a key
  of the Admin app's catalogue, so no one downloads the Admin app's code with the User app's, and when the Admin
  app's build has PDF.js, or lacks the marker, without which the first check would prove nothing.
- **The libraries are chunks of their own** (`vite.config.ts`): React Aria in one, PDF.js in another (loaded only
  when a PDF is previewed) and the rest in a third, each named with its hash, which `AppServlet` serves as immutable,
  so a browser keeps them across the app's updates. The Admin app's are the same, without PDF.js, and
  `AdminAppServlet` serves them `private`, so no proxy keeps them.
