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
`onPreview`, F2 as `onRename` and Delete as `onDelete`, for the view to act on; a row whose `selectable` is false,
such as a folder among documents, opens on a click instead. Give it `loadMore` and `total`, and it calls `loadMore`
when the end of the loaded rows comes into view while fewer than `total` are loaded. A folder's list takes its rows
from `views/useListing.ts`, TanStack Query's infinite query over `GET /folders/{id}/children`, 100 at a time.

The virtualiser places the rows itself, so the Table needs a height: it fills a flex column, as the folder view's is.
jsdom has no IntersectionObserver, which notices the end of a list, so the tests' setup installs one that sees
nothing until a test calls `bringObservedIntoView()` from `test/intersection.ts`, as scrolling to the end would.

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
