---
title: Writing Docs
weight: 7
---

These docs live in `site/` in Healdan's repository, and are published at https://docs.healdan.com. They're built
with [Hugo](https://gohugo.io) and the [Hextra](https://github.com/imfing/hextra) theme.

## Docs Change with the Code

A pull request that changes something these docs describe, or adds something a manual should describe, updates
those pages in the same pull request. The pull request template asks. Each milestone's design spec names the pages
it adds or changes, and its plan has tasks for them.

The build, test and CI facts in this manual are also in the repository's `CLAUDE.md`, the brief Claude sessions
read. A change to the build, the tests or CI updates both, wherever each states the fact that changed.

## Running the Site

You need Hugo 0.168.0, the standard edition (`brew install hugo` on macOS). The theme is in `site/_vendor/`, so
you don't need Go or a network connection. From the repository's root:

```bash
hugo server --source site                                    # preview at http://localhost:1313
hugo --source site --gc --minify --panicOnWarning            # the strict build CI runs
python3 -m unittest discover -s .github/scripts/tests -v     # the site's tests
```

The preview server doesn't version its CSS file names, so after changing `site/assets/css/custom.css`,
force-reload the page or restart the server.

## How the Site Is Laid Out

- **Each manual is one folder** under `site/content/`: `install/`, `user/`, `admin/` and `developer/`. Its
  `_index.md` sets `cascade: { type: docs }`, and the left nav shows only the manual you're in.
- **A section is a folder with an `_index.md`.** A page is a Markdown file, or a folder with an `index.md` and its
  images beside it.
- **`weight`** in the front matter sets the order in the nav.
- **The tabs** come from `menu.main` in `site/hugo.yaml`, and the landing page's cards from
  `site/content/_index.md`.

## Writing Rules

- **Title Case** for page titles, `linkTitle`s, headings, card titles, tab names and buttons: "Finding a Document
  by Doc #", "Working with Matters". Articles, short conjunctions and prepositions stay lowercase unless they come
  first or last, as in Chicago style.
- **Sentence case** for body text, in plain, direct English, with British spelling: "licence", "colour".
- **Healdan's words.** A *Store* is a top-level container. A *workspace* is a matter's workspace, made from a
  workspace template. The *doc #* is a document's number. When a page has to mention what LogicalDOC means by a
  word, it says so.
- **Describe Healdan as it is on `master`,** and only what Healdan does. Nothing about features that aren't built,
  and nothing about LogicalDOC Enterprise.
- **Write every page fresh.** Don't copy text or screenshots from LogicalDOC's docs.

### The `#` Traps

The doc # puts `#` in a lot of titles and headings, and two tools drop it without an error:

| Write | Not | Because |
|---|---|---|
| `title: "Finding a Document by Doc #"` | `title: Finding a Document by Doc #` | YAML reads ` #` as the start of a comment |
| `## Legacy Doc \#` | `## Legacy Doc #` | Markdown reads a `#` at the end of a heading as closing it |

`.github/scripts/check-site-sources.py` catches both, and the `Docs site` check runs it.

### Shortcodes

Hextra's shortcodes cover most needs: `callout` (with `type="info"` or `type="warning"`), `steps`, `tabs` and
`tab`, and `cards` and `card`. Give each tab its name with `{{</* tab name="Caddy" */>}}`. The older
`{{</* tabs items="…" */>}}` fails the strict build.

## Pages Not Written Yet

A page that isn't written yet holds one shortcode, which links to LogicalDOC's nearest page in the meantime:

```text
{{</* interim href="https://docs.logicaldoc.com/en/working-with-documents/versioning" title="Versioning" */>}}
```

It's the only place `site/content/` may link to LogicalDOC's docs. To write the page, replace the shortcode with
the page, keeping the file where it is so its address doesn't change. `grep -rl 'interim href' site/content` lists
what's left.

## Screenshots

- Take them from the [demo stack](../development-stack/#a-demo-firm-for-screenshots), signed in as the user the
  page is about: `pnair` for most User Guide pages, `sokafor` for the Admin Guide. Never use real client data.
- Light mode, with the browser at 1280 × 800, cropped to the part that matters. Save them as PNG beside the page,
  and give each one alt text.
- Take them at a true 1280 × 800, with headless Chrome: the browser pane scales the page down. Start Chrome with
  `TZ=Europe/London`, the demo stack's time zone, so the times it shows are the firm's, not your computer's.
- A pull request that changes a screen updates the screenshots it makes wrong.

## Adding or Retiring a Manual

- **Adding one:** a new folder under `site/content/`, a `menu.main` entry in `site/hugo.yaml`, and, if it belongs
  on the landing page, a card in `site/content/_index.md`.
- **Retiring one:** delete its folder, its menu entry and its card. Put each retired page's address in the
  `aliases` front matter of its nearest page in the replacement manual, so old links and bookmarks still work.

## Upgrading Hextra

Upgrading needs Go, because Hugo fetches modules with it:

```bash
cd site
hugo mod get github.com/imfing/hextra@vX.Y.Z
hugo mod vendor
```

`site/layouts/_partials/sidebar.html` is a copy of Hextra's, with Healdan's changes marked `Healdan:`. Copy the new
version's file over it, put those changes back, and run the tests: `test_site_build.py` checks the nav.
