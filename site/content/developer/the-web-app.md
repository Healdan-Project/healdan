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
HEALDAN_DEV_TARGET=http://127.0.0.1:8080 npm run dev -- --host 127.0.0.1    # the stack's address; that one is the default
```

Sign in at the stack's own address, `http://127.0.0.1:8080/login.jsp`, then open `http://127.0.0.1:5173/app/`. A
cookie belongs to a host name, not a port, so the dev server's page on the same host name is signed in too; one at
`localhost` isn't, as that's another host name. The dev server sends `/api`, `/login.jsp` and
`/j_spring_security_check` to the stack. The API's web-session route refuses a request whose `Origin` isn't the
server's own, so the dev server rewrites `Origin` to the stack's address. The app's own **Sign In**, after a sign-out,
reloads the dev server's page, which only the stack's `AppServlet` would send to the sign-in page: sign in at the stack
again. Pages the app links to that the proxy doesn't pass, such as the Classic Interface, are on the stack's address.

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

## A Secure Address Only

Browsers send `Sec-Fetch-Site` only to a potentially trustworthy address (HTTPS, or the machine itself), and the
web-session route needs it, so at a plain `http://` address off the machine every call would be `401`. `main.tsx`'s
`start(root, secure)` checks `window.isSecureContext` first and, without it, draws only `frame/InsecureAddress.tsx`,
in the catalogue's words, with a link to the Classic Interface, before anything asks the server. Nothing that runs as
the app loads may need a secure context either: a drag's page token (`organising/rows.ts`) is made with
`crypto.getRandomValues`, as `crypto.randomUUID` exists only at a secure address.

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

Every list of documents takes its Title column from `views/listColumns.tsx`, whose cell ends with `HoldMarker`
(`actions/HoldMarker.tsx`) for a document whose `hold` isn't null: Check Out's pencil for a check-out, or the padlock for
a lock, in a `role="img"` span whose name and `title` are `holdLabel`'s. That's who has it and since when, from the `hold.*`
strings the details use, without where it's released, which `holdSentence` adds for the details. The same words are in
the span as text, drawn away, which a hovered row, or one with the keyboard's focus, shows after the icon
(`actions.module.css`), as `MatterCell` shows a matter's name: a tooltip alone can't be reached by keyboard. Only a held
row asks who the person is (`useMe`), to tell their own holds from others'. The Table colours a cell's icons
`--hd-text-secondary`, and the row's own text colour on a selected row, so the marker meets contrast in both themes.

The virtualiser places the rows itself, so the Table needs a height: it fills a flex column, as the folder view's is.
jsdom has no IntersectionObserver, which notices the end of a list, so the tests' setup installs one that sees
nothing until a test calls `bringObservedIntoView()` from `test/intersection.ts`, as scrolling to the end would.

## Keeping the Page Up to Date

The app has no push updates yet (M6's spec §16). Instead it asks again for what's on screen each time the person comes
back to its window, as well as after their own changes. The rules are in `queryClient.ts`, where the one `QueryClient`
is made:

- **An answer stays fresh for 30 seconds** (`staleTime`), so moving between views doesn't ask again, and a return asks
  again for what's on screen and older than that (TanStack Query's `refetchOnWindowFocus: true`).
- **A return asks again for what shows a document's state, however fresh the answer is.** A query that shows it carries
  `meta: ASK_ON_RETURN`: the document, its properties and versions and Recent (`queries.ts`), and a folder's list
  (`views/useListing.ts`). The return marks them stale (`invalidateQueries` with `refetchType: 'none'`) before it tells
  TanStack Query, which then asks for them as for any stale answer. A document opened in Excel through the helper is
  checked out a few seconds after the click, and saved to minutes later, so an answer from just before would show it
  free, or at its old version. A new query keeps to the 30 seconds unless it carries the same `meta`.
- **A return is the window getting the focus back, or a hidden tab showing again.** TanStack Query listens only for
  `visibilitychange`, which moving from Excel back to a browser window that stayed on screen doesn't fire.
  `createQueryClient` gives `focusManager.setEventListener` the app's own `listenForReturn`, which listens for the
  window's own `focus` too. The focus coming back to the page from a frame in it, an email's body, fires that as well,
  and is no return: the window's `blur` notes whether the focus went into an `<iframe>`. A tab's return fires both, and a
  request already under way isn't sent twice.
- **The person's own changes** call `invalidateDocumentLists` (`actions/refresh.ts`) when they're done, which asks again
  for the document, its properties and versions, the folders' lists, search results, Recent, My Favorites, Recent Matters
  and Recent Clients.
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
server fills them from the message and the browser never parses a file. **Preview** opens Quick Look (see Previews,
below), and **Copy Link** (`actions/copyLink.ts`) writes `location.origin + '/app/doc/' + n` with the Clipboard API
and shows a Toast, either way. The other actions are in "Working with a Document", next.

`/app/doc/10388`, `/app/doc/10388.2` and the other forms of the lookup are `views/DocResult.tsx`: it asks
`GET /v1/lookup?q=%2310388.2` and shows a list of that one document (`views/listColumns.tsx`, the folder list's
columns), selected, with its details beside it and the named version marked in Versions. `useSelection` takes the
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
  `hold`. The web session is no device, so the app never takes over a check-out a device holds (`409
  held-elsewhere`): it says who has it and since when, and that it's released on that computer or in the Classic
  Interface. A check-in from the app is a file picked into `POST /v1/documents/{n}/versions` as a snapshot, with
  `ifHeld` and `ifVersion`. Every outcome asks for the document and the lists again.
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

`organising/` is New Folder, rename, move and delete (M6 spec 6.7). `OrganisingProvider` sits in the frame under the
filing's and owns what outlives a view: which document's title is a field now, and the dialogs that ask (`ConfirmDelete`,
`MovePicker`, `NewFolderDialog`) and say what was refused (`Refusals`). Views reach it through `useOrganising`, and
`useOrganisingTable(rows, selection)` gives a list its `onRename` (F2), `onDelete` and `drag` props and the selected documents
for the toolbar's More menu (`actions/MoreMenu.tsx`).

- **One call each.** `actions/rename.ts`, `move.ts` and `remove.ts` make `PATCH /documents/{n}`, `POST /documents/{n}/move`
  and `DELETE /documents/{n}`. A change to several documents (`actions/batch.ts`) is a call for each, one after another, which
  keeps going when one is refused, so what was done stays done; the refusals come back as lines, each the document's name
  and why, from the catalogue (`filing/refusal.ts`: a hold says who holds it, an `already-filed` names the copy).
- **Rename in place.** `RenameField` replaces the title in the row. Enter saves, Esc cancels, and leaving the field with a
  new name saves it. A refusal keeps the field open with the message beside it (`renameRefusal` words another extension
  as what to do about it), and the focus goes back to the row when the field goes. The field stops its key and pointer
  events, so the Table's own keys (Space, Delete, the arrows) and a double-click don't reach the row.
- **The picker.** `MovePicker` starts from Recent Matters and My Matters (the left pane's queries) and the search box's
  matter suggestions, then lists the chosen matter's folders from `fetchSubfolders` (also the tree's), a level at a time. It
  never browses Stores, and **Move Here** needs a folder inside the matter.
- **Dragging rows.** The Table's `drag` prop makes its selectable rows draggable, with the browser's own drag and drop (React
  Aria's would add some 140 kB to the app's chunk of it), so a drag of a selected row carries every selected row, and of another row only that one. A drag carries `ROWS_TYPE` with the documents as JSON (`rows.ts`);
  `FilingProvider` leaves it alone and the tree takes it (`acceptsDrop`, `dropRows`): the folder lights up as it does for
  files, and a place that holds no documents is refused with the filing rule's words before any call. There's no keyboard
  drag: More › Move To… is the way for a keyboard.
- **New Folder** is offered inside a matter's folders, and at a matter's top level only to an administrator (`useMe`).
- **Every list of documents** has it: a folder, search results, Recent Documents and Emails, and the `#` result, each with
  `useOrganisingTable` and a `Toolbar` holding `SelectionActions`. `useRowSelection(rows)` keeps only the selected rows
  still listed, so once they're deleted or moved F2 and Delete take the focused row.

Test it with `organising/*.test.tsx`; `testing.tsx` has routes for a folder that rename, move and delete as the server
would, and a `DataTransfer` that keeps what a drag writes. `e2e/organising.spec.ts` does it in a real browser.

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

Below 1,024 pixels (`NARROW` in `frame/narrow.ts`, read with `useNarrow()`) the left pane and the right region fold
away behind buttons in the top bar, **Navigation** and **Details and Filters**, each saying with `aria-expanded` whether
it's open. The app is designed for 1,280 pixels and works at a tablet's 768; phones aren't a goal.

- **The left pane** isn't drawn while it's folded, so it isn't in the tab order. Opened, it covers the list's left edge
  (`Frame.module.css`), and following a link in it, or Esc, closes it. Esc puts the focus back on the button.
- **The right region** keeps its content (Filters, the details or the docked reading pane), shown by
  `RightRegionView` over the list from the right edge while `narrowOpen` is true, and `useRegionKind` gives a list no
  narrower columns for it. `RightRegion.open()` opens it, so the funnel, **Preview** and the tray's **Edit** show it, but a
  click on a row doesn't (`open('details', { reveal: false })`): selecting is not a request to see it. With nothing to
  show it says so.
- **To test it** in Vitest, `test/viewport.ts` gives jsdom a `matchMedia` for a width (`setViewport(768)` before a
  render, `resize()` after), and `frame/Narrow.test.tsx` has the cases. `e2e/a11y.spec.ts` looks at it at 768 × 1024.

## The Look in the Dark

Both themes are defined as custom properties in `tokens.css` and chosen by the browser's `prefers-color-scheme`.
Anything that isn't a custom property is on purpose: the brand mark's blues (`frame/Mark.tsx`) and the white paper a PDF
page or a sender's email is drawn on (`preview/preview.module.css`, `preview/email.module.css`), which keep their own
colours so a document reads as it was made. `tokens.test.ts` checks every text colour on every background it may sit
on, in both themes. jsdom draws no colours, so `e2e/a11y.spec.ts` runs axe in a real browser in both, where contrast is
checked on what is drawn.

## The Keyboard

Rows 3 to 9 of the spec's manual acceptance run by keyboard in `e2e/keyboard.spec.ts`, with no click after the sign-in
page. A step that can't be done by keys is a finding to fix in the app, never to work round in the spec. The keys are in
the spec's section 10.5: `/` jumps to the search box, the arrow keys move through a list, Enter opens in the app, Space
opens Quick Look and Esc closes it, F2 renames, Delete moves to the trash after the question, and the tree, menus and
dialogs follow React Aria's. The properties form (`details/PropertiesForm.tsx`) takes the focus in its first field when **Edit** opens it, since Edit goes grey, cancels on Esc, and gives the focus back to **Edit** when it closes. A list's keys are in `@healdan/ui`'s `Table`, which takes them ahead of React Aria's so
Space previews rather than selects, and leaves them to a field inside a row.

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
