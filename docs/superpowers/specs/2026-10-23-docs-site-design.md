# Healdan's docs site: design

- **Tracking issue:** [Healdan-Project/healdan#56](https://github.com/Healdan-Project/healdan/issues/56)
- **Date:** 2026-10-23
- **Status:** approved by the owner on 2026-10-23. Being built from [`docs/superpowers/plans/2026-10-23-docs-site.md`](../plans/2026-10-23-docs-site.md): PR A (the site, its checks, CI, and the Install and Developer manuals) is in review. The plan's "Spec corrections and calls this plan makes" amend §10.1 and §10.3, chiefly moving the Help URL into `HealdanSystemInfo`. Its decision log records the build's rulings.
- **Inputs:**
  - issue #56, which lists every link to LogicalDOC's docs in the app and the repo;
  - a design conversation with the owner on 2026-10-23, with mockups and a throwaway spike built with Hugo and Hextra in Healdan's colours;
  - a survey of this repository at `67b549fec`. Line numbers drift;
  - the source of Hextra v0.13.0.

As in the roadmap, **(owner)** marks what the owner decided or described, and **(call)** marks Claude's proposal. The owner saw the calls in §4–§9 played back, in mockups and in the spike, and said "looks good".

## 1. Intent

Healdan gets its own documentation site, so that nothing in the app or the repo depends on LogicalDOC's docs. (owner)

Today the Help menu, two in-app hints, the release-check page, the REST API's description and the README all link to docs.logicaldoc.com or wiki.logicaldoc.com. Those pages describe LogicalDOC's Enterprise edition, use *Store* and *workspace* in LogicalDOC's sense rather than Healdan's, and live in a CMS that LogicalDOC doesn't publish. Healdan has also shipped M1 and M1.5 with no user docs of its own.

**Who it's for.** Anyone who wants to know more about Healdan. (owner) Four manuals cover them for now: Install, User, Admin and Developer. (owner)

**Docs move with the code.** Docs are written and amended in the same pull request as the code they describe, so the two stay aligned. (owner)

## 2. Goals, non-goals, anti-goals

### Goals (done when)

1. **The site is live.** `https://docs.healdan.com` serves the site over HTTPS, built from `site/` on `master`. (owner: the address)
2. **Landing page.** Three large cards for Install, User Guide and Admin Guide, with a smaller "Developer Guide" link underneath (§4.1).
3. **Manuals.** Tabs across the top switch manuals. The left nav shows only the current manual, on desktop and on phones (§4.2–§4.3).
4. **Look.** Healdan's logo, its two blues and Healdan's favicon, in light and dark modes, with nothing left of Hextra's branding (§5).
5. **Footer.** Guide and project links, then © The Healdan Project, the docs under CC BY 4.0, the code under LGPL-3.0, and LogicalDOC's credit (§5.4).
6. **Launch content.** Every page listed in §6.1 is written. Every other page in the contents is an interim page (§6.2).
7. **CI.** A `Docs site` check builds the site on every pull request and is one of `master`'s required checks. A pull request that changes only `site/` or docs skips the Java and Docker builds. A broken internal link fails the check (§8).
8. **The app and the repo link to Healdan's site.** After the link switch (§10), `python3 .github/scripts/check-logicaldoc-doc-links.py` passes. It allows links to LogicalDOC's docs only in interim pages and the leave-alone list (§10.3).
9. **The same-PR rule is written down** in `CLAUDE.md` and a pull request template (§9).

Issue #56 stays open after that, until no interim page is left and the `interim` shortcode is deleted.

### Non-goals

- **Versioned docs.** The site describes `master` until Healdan has releases of its own (§7.4).
- **Translations.** English only (call).
- **A marketing site** at `healdan.com`.
- **The new UI's user manual.** M6 adds it as a second User manual (§4.4).
- **A generated API reference.** M2's spec decides how its JSON API is documented. Until then the Developer manual's API page is an interim page.
- **Automated screenshots** (§6.4).
- **Analytics, comments, cookie banners** (call: none).

### Anti-goals

- **Copying LogicalDOC's text or screenshots.** Every page is written fresh, from Healdan's code, specs and behaviour. This is roadmap principle 6, "Re-implement, never copy", and LogicalDOC's site states no licence. (owner's standing direction)
- **A left nav that mixes manuals.** It makes manuals hard to add and retire (§4.4). (owner)
- **Docs that describe something Healdan doesn't do,** such as LogicalDOC Enterprise features, or behaviour that isn't on `master` yet.
- **Slower code PRs.** The `Docs site` check must stay well under a minute, and docs-only PRs must keep skipping the Java and Docker builds. The owner wants time-to-green low.
- **Calling Healdan a registered trademark.** It isn't one (CLAUDE.md).

## 3. Constraints

### 3.1 Facts from the repo

- **CI's docs filter.** `.github/scripts/code-changed.sh:17` counts a pull request as docs-only when every file is under `docs/` or is Markdown outside a `src/` directory. Both CI workflows skip their build steps then, but still report `build`, `Test (amd64)` and `Test (arm64)`, which `master`'s ruleset requires.
- **Push filters.** `build-logicaldoc.yml` and `docker-image.yml` skip pushes to `master` that touch only `paths-ignore: [ 'docs/**', '**/*.md' ]`.
- **The help URL is hard-coded,** not read from `context.properties`: `SystemInfo.java:39`, `GUIBranding.java:47` and `WSSystemInfo.java:31`. `MainMenu.java:685` appends `?lang=<locale>`. A static site ignores the query string.
- **The two install hints** (`webstarthintlauncher`, `officeaddinhintlauncher`) link to LogicalDOC's pages in all 34 bundles under `logicaldoc-i18n`. They only show after "Edit with Office", "Scan" or "Bulk checkout". The capability map lists Feature 20 (OFFICE) as absent in CE, and Scan and Bulk checkout are behind feature gates too, so CE users don't see the hints.
- **No plugin can override a GUI message.** The GUI's strings come from one bundle, `ResourceBundle.getBundle("i18n.messages", …)` (`InfoServiceImpl.java:319`, `I18N.java:30`). Changing a CE message means editing `logicaldoc-i18n`'s bundles.
- **GitHub Discussions are off** for `Healdan-Project/healdan`.
- **No images are published yet.** The repository has no Actions variables, so `GHCR_PUBLISH` is off and `ghcr.io/healdan-project/healdan` gets nothing. Healdan has no release tags of its own; the repo's `v*` tags are upstream's.
- **The logo** exists only as SVG data URIs in `GUIBranding.java`: `logo`, `logoMenu` and `logoHead` (the wordmark, 1384 × 270) and `favicon` (the mark alone, 260 × 260). Its colours are `#0b5dab` (dark blue), `#22a2e0` (light blue) and `#1d2125` (the wordmark's text).
- **README's notice** credits LogicalDOC, says Healdan is LGPL-3.0, and adds "LogicalDOC is a trademark of LOGICALDOC Srl, which doesn't endorse Healdan."
- **The code's licence.** The roadmap's §7 listed "Healdan's own new modules need a license" as an open decision. The owner settled it in this design session: all of Healdan's code, its own modules included, is LGPL-3.0. The roadmap now records that as principle 8, and the open item is gone.
- **`docker/README.md`** is today's install and operations guide, and also holds the developer material on the dev overlay, tests and CI.

### 3.2 Facts about Hextra v0.13.0 (checked in its source and in the spike)

- MIT-licensed, released 2026-09-29, needs Hugo 0.146 or newer. Hugo alone builds it: no Node or npm. It isn't Hugo "extended".
- **Its left nav.** On desktop the sidebar is rooted at the page's top-level section (`$context.FirstSection` in `layouts/_partials/sidebar.html`), so one folder per manual already gives a per-manual nav.
- **Its phone menu.** It lists every `menu.main` entry, with each entry's whole tree under it. Every tree starts open, because `$shouldOpen := or (...) | default true` turns `false` into `true`.
- **The primary colour** comes from `--primary-hue`, `--primary-saturation` and `--primary-lightness`. Links and the current page use the 600 shade, which is 0.9 × the lightness.
- **Its own branding.** Hextra ships its own `favicon.ico`, PNG favicons, `apple-touch-icon.png`, Android icons and `site.webmanifest`, plus a "Powered by Hextra" footer line (`params.footer.displayPoweredBy`).
- **CSS file names.** `hugo server` doesn't fingerprint them, so a browser can keep stale CSS while previewing. Production builds fingerprint them.
- **Links.** Its link render hook resolves links to `.md` files, but doesn't fail on a link to a missing page.
- **The `#` traps.** In YAML front matter, ` #` starts a comment, so `title: Finding a document by doc #` loses its `#`. In Markdown, a `#` at the end of a heading is read as closing the heading, so `## Legacy doc #` renders as "Legacy doc".
- **Fetching the theme.** As a Hugo module, Hextra needs Go to download. `hugo mod vendor` copies it into `_vendor/`, which is 1.2 MB in 209 files, none of them under a `src/` directory. Hugo then builds from `_vendor/` without Go.

### 3.3 Colour contrast (WCAG)

| Colour | Background | Ratio | Use |
|---|---|---|---|
| `#0b5dab` | white | 6.6:1 | text and links in light mode (passes AA) |
| `#22a2e0` | white | 2.9:1 | **decoration only:** borders, icons, gradients. Never body text in light mode |
| `#0b5dab` | `#111` | 2.9:1 | **not for text in dark mode** |
| `#4aa8f0` | `#111` | 7.3:1 | links and highlights in dark mode |

## 4. Structure (owner, except where marked)

### 4.1 Landing page

- A short hero: the headline "Healdan Documentation", a one-line description, and a badge that reads "Self-hosted · open source · based on LogicalDOC CE".
- Three large cards: **Install**, **User Guide** and **Admin Guide**. Each has an icon, a one-line description and a link.
- Under the cards, a smaller link: "Building or extending Healdan? Developer Guide →".
- The search box is in the top bar, as on every page.

The mockup and the spike's `content/_index.md` used Hextra's `hextra-home` layout with `hextra/hero-*` and `hextra/feature-grid` shortcodes, which is enough.

### 4.2 Inside a manual

- **Top bar:** the logo (linking to the landing page), the tabs "Install", "User", "Admin" and "Developer" from `menu.main`, then search, the GitHub link and the theme toggle. The current manual's tab is highlighted.
- **Left nav:** only the current manual. It never shows another manual's pages. (owner)
- **The page:** breadcrumbs, the page, previous and next links at the foot, and an "On this page" list on wide screens. These are Hextra's defaults, and the owner accepted them in the mockups.
- **Each manual is one top-level folder** under `site/content/`, whose `_index.md` sets `cascade: { type: docs }`. The manual's landing page lists its sections as cards.

### 4.3 Phones (call)

On phones the menu doubles as the manual switcher. It lists the four manuals, and opens only the current one, with its tree. The other manuals are plain links to their landing pages, without the open/close arrow.

This needs one override: `site/layouts/_partials/sidebar.html`, a copy of Hextra's with two changes in the `$useMainMenuEntries` branch of `sidebar-tree`.

1. `$shouldOpen` becomes "this entry is the current page, or an ancestor of it", without `| default true`.
2. The nested `sidebar-tree` call runs only for that entry. The other entries' links don't get the open/close arrow.

Every change is marked `Healdan:` in a comment, and the file's header names the Hextra version it was copied from. The spike made changes 1 and 2 and confirmed them on a 375 px viewport. It didn't remove the arrow.

### 4.4 Manuals come and go (owner)

Manuals are independent so they can be added and retired. The expected case is M6: while Healdan builds its own front end there will be two User manuals, one for the classic LogicalDOC-based UI and one for the new UI, and the classic one is retired when that UI goes. (owner)

- **Adding a manual:** a new top-level folder, a `menu.main` entry and, if it belongs on the landing page, a card. Nothing in another manual changes.
- **Retiring a manual (call):** delete its folder, its menu entry and its card. Each retired page's URL goes into the `aliases` front matter of its nearest page in the replacement manual, so old links and bookmarks redirect. A page with no counterpart redirects to the replacement manual's landing page.
- **Names:** M6's spec decides which manual keeps the plain "User" tab.

### 4.5 Search (call)

Hextra's built-in FlexSearch, which runs in the browser and needs no service. It searches every manual. Only the nav is per manual.

## 5. Look

### 5.1 Theme (call)

**Hextra v0.13.0**, chosen over Docsy, Hugo Book, Relearn, Lotus Docs and Doks.

- It looks clean and modern.
- One folder per manual gives the per-manual nav (§3.2).
- It needs only Hugo. Docsy needs Node and PostCSS, and Doks is built on npm.
- Search, dark mode, callouts, cards, steps and tabs are built in.
- It's actively maintained and MIT-licensed.

The owner handed the choice to Claude and asked for "clean and professional", in the logo's colours.

### 5.2 Colours (owner: the logo's colours; call: where each goes)

In `site/assets/css/custom.css`:

- **Primary:** `--primary-hue: 209deg; --primary-saturation: 88%; --primary-lightness: 40%;`. That puts the 600 shade, used for links, the current tab and the current page, on `#0b5ead`, one step from the logo's `#0b5dab`. Hextra derives its lighter and darker shades from the same three values.
- **Light blue `#22a2e0`:** the hero headline's gradient (`#0b5dab` → `#22a2e0`), interim-page borders and callout accents. It's never used for text on white (§3.3).
- **Dark mode:** links and highlights in `#4aa8f0` or lighter. The headline gradient starts at a light blue, not `#0b5dab`, which is unreadable on black (2.9:1). The spike missed this.
- **Headings** keep Hextra's near-black, close to the logo's `#1d2125`.

### 5.3 Logo and favicons (call)

- `site/static/images/logo.svg` is the `logoMenu` SVG from `GUIBranding.java`, decoded. `logo-dark.svg` is the same with the wordmark's `#1d2125` changed to white. Set `params.navbar.displayTitle: false`, and show the logo at a height of 28 px.
- **Favicons** come from `GUIBranding.java`'s `favicon` SVG, the square mark the app already uses. That's `favicon.svg` plus every file Hextra's `favicons.html` links (`favicon.ico`, the 16 and 32 px PNGs, `apple-touch-icon.png`, the two Android icons, `site.webmanifest`). No Hextra icon may be left. The spike did only `favicon.svg`, from a rough crop of the wordmark.
- If the owner's logo artwork changes, the app and the site change in the same PR.

### 5.4 Footer (owner: copyright, licence; call: layout)

Two parts, on every page.

1. **Links**, in a Healdan partial (`layouts/_partials/custom/footer.html`, which Hextra includes):
   - the logo and "Matter-centric document management you host yourself.";
   - a **Guides** column: Install, User Guide, Admin Guide, Developer Guide;
   - a **Project** column: GitHub, "Report a Problem with These Docs" (the repo's issues), "Docs Licence: CC BY 4.0", "Code Licence: LGPL-3.0".
2. **The copyright line**, from `site/i18n/en.yaml`'s `copyright` key, with `params.footer.displayCopyright: true` and `displayPoweredBy: false`:

   > © 2026 The Healdan Project. This documentation is licensed under CC BY 4.0; Healdan's code is licensed under the LGPL-3.0.
   > Healdan is a modified version of LogicalDOC Community Edition, © 2006-2027 LOGICALDOC Srl. LogicalDOC is a trademark of LOGICALDOC Srl, which doesn't endorse Healdan.

   (owner: "© The Healdan Project" and CC BY 4.0.) The LogicalDOC credit and the trademark sentence follow `README.md`'s notice word for word (call). CLAUDE.md requires LogicalDOC's credit to be kept. The code-licence clause states the owner's decision (§3.1). (owner)

On phones the columns stack.

## 6. Content

### 6.1 Written at launch (owner: "a good foundation")

The line: Install written in full, and User and Admin pages for everything M1 and M1.5 built, so that nothing already shipped goes undocumented. (owner) Developer is written too, because its material already exists. Everything else in the contents is an interim page.

**Install**, from the user-facing parts of `docker/README.md`:

| Page | From `docker/README.md` |
|---|---|
| Quick Start | Quick start; Healdan's first start. Builds from source only, while no images are published |
| Using a Published Image | Using a published image. **Only once images are published** (call): today none are (§3.1), and the site documents only what Healdan does. Turning on `GHCR_PUBLISH` is the owner's decision (issue #9). The PR that documents it adds this page, the Quick Start's published-image option, and Upgrading's advice on pinning `sha-…` tags (and `v…` tags once Healdan has releases) |
| What Runs | What runs, including Office previews |
| Configuration | Configuration; Which settings `.env` controls; Passwords and special characters |
| Reverse Proxy | Behind Nginx Proxy Manager; Other reverse proxies |
| Backup and Restore | Backup and restore |
| Upgrading | Upgrading |
| Host Folders Instead of Volumes | the section of that name |
| Troubleshooting | Troubleshooting |
| Known Limitations | Known limitations |

**User Guide**, from the M1 and M1.5 specs, the roadmap and the running app:

- **Getting Started:** Signing In; Getting Around (Stores, My Matters and Recent Matters in the left pane; the search box); Clearing Your Browser's Cache. `releasecheck.jsp` will link to that last page (§10.1).
- **Working with Matters:** Stores and Matter Workspaces; Filing a Document (only into a workspace's folders: never its top level, never a search folder); Properties (Author, Typist, Class and Subclass; inherited values; overrides; Date filed); Emails (uploading `.eml` and `.msg`; sent and received times; From, To, CC and Subject); Document Permissions (granting a document to someone who can't see its folder).
- **Finding Documents:** Searching (the scope picker; matter numbers; full-text search in Word files and emails); Finding a Document by Doc # (`#n`, `#n.N`, `#n.N.M`, `#nvN`); Search Folders; My Matters and Recent Matters.
- **Documents:** Versions (versions and snapshots, and the footer stamp `# 1234.2.1`); Previews (Office previews with tracked changes and comments; thumbnails).

**Admin Guide**:

- **Stores:** creating one, permissions and visibility, and M1's single storage backend.
- **Clients and Matters:** the managed client and matter lists; creating a matter from a Store's menu (client, number, name, Store, owner, template); per-client or system-wide matter numbers; the configurable "Matter" label.
- **Workspace Templates:** folders, search folders and properties. Changing a template doesn't rebuild existing workspaces.
- **Top-Level Folders:** only admins add, rename, move or delete them.
- **Users and Groups:** the Everyone group, and why a day-to-day account shouldn't be an admin (CE admins skip every permission check).
- **Permissions:** Store permissions; folders can restrict access but not loosen it; one-off grants on a document.
- **Healdan Settings:** what the Healdan settings panel (`logicaldoc-gui/.../frontend/client/healdan/settings`) offers.
- **Office Previews:** what converts, timeouts, and LibreOffice in the image.

**Developer Guide**, from `CLAUDE.md` and the developer parts of `docker/README.md`:

- Building from Source, from CLAUDE.md's "Build and test".
- Running a Development Stack, from `docker/README.md`'s Development section.
- Tests: Maven, `docker/tests/` and the smoke test.
- Continuous Integration.
- How Healdan Extends LogicalDOC: `healdan-core`, Flyway and the `hd_` tables, seeds, `HealdanHooks`, the GUI hooks.
- Upstream Syncs.
- Writing Docs: running the site locally, the writing rules in §6.3, interim pages, and the same-PR rule.
- API, an interim page until M2.

The builder decides which unchanged CE features get interim pages. Anything a Healdan user can reach in the UI should have one. That includes check-in and check-out, workflows if they're visible, the trash and sharing.

### 6.2 Interim pages (owner: interim links to LogicalDOC's site)

A page that isn't written yet holds a single shortcode:

```
{{< interim href="https://docs.logicaldoc.com/en/…" title="Administrator's Guide" >}}
```

`site/layouts/_shortcodes/interim.html` renders a fixed box: "This page isn't written yet. Until it is, LogicalDOC's [title] covers similar ground." Under that, a warning that LogicalDOC's docs describe its Enterprise edition and that their *workspace* isn't a Healdan Workspace. It's styled in `custom.css` (light-blue border, pale-blue background, readable in both modes).

- The shortcode is the **only** place `site/content/` may link to LogicalDOC's docs. A search for `{{< interim` counts what's left to write.
- Links go to the nearest LogicalDOC page. If there isn't one, they go to its user guide (`/en/entering-in-the-system`), administrator's guide (`/en/system/general-informations`), installation guide (`/en/installation`) or web services page (`/en/web-services-api`). The builder checks each link resolves.
- When the last interim page is written, delete the shortcode and its CSS, and close #56.

### 6.3 Writing rules

- **Title Case** for page titles, nav labels (`linkTitle`), headings, card titles, tab labels, buttons and footer links: "Healdan Documentation", "User Guide", "Finding a Document by Doc #". (owner) The small words follow Chicago style (call): articles, coordinating conjunctions and prepositions stay lowercase unless they come first or last, as in "Working with Matters" and "Backup and Restore".
- **Body text** in sentence case (call), in plain, direct English, matching the repo's docs. British spelling, as in the roadmap and specs: "licence", "colour", "organisation" (call).
- **Healdan's terms** as the roadmap's glossary defines them: Store, Workspace (matter workspace), doc #, legacy doc #, properties, storage backend. When a page has to mention LogicalDOC's meaning of a word, it says so.
- **The `#` traps** (§3.2): quote any front-matter value containing ` #`, and write a heading that ends in `#` as `\#`.
- **Each page describes `master`.** A page about something not built yet doesn't exist; its place in the contents is an interim page or nothing.
- **No LogicalDOC text or screenshots** (§2, anti-goals).

### 6.4 Screenshots (call)

- Taken by hand from a throwaway stack with the smoke fixture overlay (`docker/tests/compose.smoke.yaml`). The builder may seed nicer demo data for them, such as a client and matter with realistic names, using only test values.
- Light mode, at a fixed browser width. PNG or WebP, kept next to the page in a page bundle (`page-name/index.md` with its images beside it).
- No real client data.
- A PR that changes a screen updates the screenshots it makes wrong. Automating screenshots is later work, if their upkeep becomes a burden.

## 7. Repository and publishing

### 7.1 Layout (call)

```
site/
  README.md                 how to run the site locally, and where the writing rules are
  LICENSE                   CC BY 4.0 for the content (see §7.2)
  hugo.yaml                 config: menu, logo, footer, search, banner off
  go.mod, go.sum            the Hextra module, pinned to v0.13.0
  _vendor/                  Hextra, from `hugo mod vendor`, committed
  assets/css/custom.css     colours (§5.2), interim box, footer
  i18n/en.yaml              the copyright line
  layouts/_partials/sidebar.html          phone-menu override (§4.3)
  layouts/_partials/custom/footer.html    footer links (§5.4)
  layouts/_shortcodes/interim.html        interim pages (§6.2)
  static/images/logo.svg, logo-dark.svg
  static/favicon.*, apple-touch-icon.png, android-chrome-*.png, site.webmanifest
  content/_index.md         landing page
  content/install/ user/ admin/ developer/
```

- **The theme is vendored** (call). CI and contributors need only Hugo, the build doesn't depend on the network, and a theme upgrade shows up as a reviewable diff. To upgrade, run `cd site && hugo mod get github.com/imfing/hextra@vX.Y.Z && hugo mod vendor`, which needs Go. Then compare the overridden `sidebar.html` against the new version's and re-apply the `Healdan:` changes.
- **The Hugo version is pinned** in the workflow (0.168.0 when written) and stated in `site/README.md`.

### 7.2 Licences (call)

`site/LICENSE` states:

- `site/content/` is under CC BY 4.0, and includes the licence's legal code;
- `site/_vendor/` keeps Hextra's own MIT licence file;
- Healdan's templates and CSS in `site/` are under the LGPL-3.0, like the rest of Healdan's code (§3.1).

### 7.3 Publishing (owner: docs.healdan.com; call: how)

GitHub Pages, deployed from GitHub Actions (§8.3), at the custom domain `docs.healdan.com`. Deployment through Actions needs no `CNAME` file. **The owner's steps**, before go-live:

1. At `healdan.com`'s DNS host, add a `CNAME` record pointing `docs` at `healdan-project.github.io`.
2. In the Healdan-Project organisation's settings, verify `healdan.com` for GitHub Pages (a TXT record). Then no other account's Pages site can claim the name.
3. In the repository's Pages settings, set the source to "GitHub Actions" and the custom domain to `docs.healdan.com`. Turn on "Enforce HTTPS" once GitHub has issued the certificate.
4. Set the repository variable `DOCS_PUBLISH` to `true`. Like `GHCR_PUBLISH`, it keeps the deploy job off until the owner says go (§8.3).
5. Optionally, set the repository's website to `https://docs.healdan.com`.
6. **Keep `healdan.com` renewing.** It's registered at Namecheap and expires on 2026-11-30 (whois, checked 2026-10-23). From PR C on, every install's Help link points at `docs.healdan.com`, so turn on auto-renew before PR C merges.

### 7.4 Versions (call)

There's one version, built from `master`. Once Healdan tags releases, a later spec can add versioned docs. Until then, `master`'s docs can describe behaviour newer than a server pinned to an older image. Install's Upgrading page says so.

## 8. CI

### 8.1 Docs-only filters

- `code-changed.sh:17` counts `site/` as docs, as it does `docs/`. Change `'^docs/|\.md$'` to `'^docs/|^site/|\.md$'`. Keep the `src/` rule; the site has no `src/` directory, and neither does the vendored theme.
- Add `'site/**'` to the push `paths-ignore` lists of `build-logicaldoc.yml` and `docker-image.yml`.
- Update the comments in `code-changed.sh` and CLAUDE.md that describe what counts as docs.

### 8.2 The `Docs site` check

A new workflow, `.github/workflows/docs-site.yml`, with one job named **`Docs site`**:

- **Triggers:** every pull request to `master`, with no paths filter, so the check always reports and can be required; pushes to `master` that touch `site/**` or the workflow file; `workflow_dispatch`.
- **Steps:** check out; install the pinned Hugo (a release tarball with its checksum checked, or an action pinned by SHA: builder's call); `hugo --source site --gc --minify`, with warnings that point at a broken site, such as a missing page or a bad shortcode, failing the build; then check the built site's internal links and anchors offline. External links aren't checked, because they're flaky. The link checker is the builder's call, for example `htmltest`, or `lychee --offline`.
- **Time:** well under a minute. A Hugo build of the spike took about a second, so it doesn't use code-changed.sh: it's cheap enough to always run.
- **On pull requests,** it uploads the built `site/public` as a workflow artifact, kept for a few days, so a reviewer can open the pages without building them.
- **After PR A merges,** the owner adds `Docs site` to the "master: require CI" ruleset's required checks. A required check that doesn't exist yet would block every PR.

### 8.3 Deploy

In the same workflow, for pushes to `master` and `workflow_dispatch` only, and only when `vars.DOCS_PUBLISH == 'true'`:

- upload `site/public` with `actions/upload-pages-artifact`;
- deploy with `actions/deploy-pages`, with `permissions: pages: write, id-token: write`, the `github-pages` environment, and a `concurrency` group that doesn't cancel a deploy in progress.

Actions are pinned the way the existing workflows pin theirs.

## 9. Docs in the same pull request (owner)

A pull request that changes behaviour a page describes, or adds behaviour a manual should describe, updates those pages in the same PR. (owner) How the rule is held (call), with no CI rule demanding changes under `site/`:

- **`CLAUDE.md`** gets a "Docs site" section covering:
  - where the site lives and how to run it (`hugo server --source site`);
  - the same-PR rule;
  - the writing rules (pointing at the Developer Guide's Writing Docs page);
  - interim pages;
  - that the link check flags LogicalDOC doc links an upstream sync brings in, and swapping them is a merge fix, like the icon check.
- **`.github/pull_request_template.md`** gets a checkbox: "Docs: I updated the pages this change affects, or it affects none."
- **Every milestone spec** names the pages it adds or changes, and its plan has tasks for them. The roadmap's "Before building" bullet says so.
- **CLAUDE.md and the Developer Guide both stay** (call). The Developer Guide is the readable account for people. CLAUDE.md stays the brief Claude sessions load automatically, and keeps its facts; this work doesn't trim it. A change to the build, CI or architecture updates both, wherever each states the changed fact. The rule says so in both places.

## 10. Switching the app's links (after go-live)

Once the site is published, the UI's links are repointed to it. (owner) The switch is its own PR, after go-live, so no link ever points at a site that isn't up.

### 10.1 Upstream edits

Each is a small edit, listed here as CLAUDE.md asks.

| File | Change |
|---|---|
| `logicaldoc-core/.../core/SystemInfo.java:39` | `help` defaults to `https://docs.healdan.com` |
| `logicaldoc-gui/.../common/client/beans/GUIBranding.java:47` | the same |
| `logicaldoc-webservice/.../webservice/model/WSSystemInfo.java:31`, `:40` | `help` as above. `forum` becomes `https://github.com/Healdan-Project/healdan/issues`, since Discussions are off (call) |
| `logicaldoc-gui/war/releasecheck.jsp:107` | the User Guide's Clearing Your Browser's Cache page |
| `logicaldoc-webservice/.../rest/endpoint/RestAuthService.java:66` | the Developer Guide's API page (an interim page until M2) |
| `logicaldoc-webservicedoc/.../context/context-webservicedoc.xml:53` | the same |
| `logicaldoc-i18n`'s 34 bundles, keys `webstarthintlauncher` and `officeaddinhintlauncher` | `https://docs.healdan.com`. Healdan doesn't ship LogicalDOC's Webstart Launcher or Office add-in, and CE never shows these hints (§3.1). M3's Office add-ins get their own pages (call). No plugin can override a GUI message (§3.1), so the 34 files are edited. Edit only the URL in each line, keeping the rest of the line byte for byte, so a sync conflicts only where upstream changes these two keys |

- **Tests.** CLAUDE.md asks for a test when a change edits upstream code. Add one asserting `SystemInfo`'s and `WSSystemInfo`'s defaults, and add it to the `-Dtest` list in `build-logicaldoc.yml`.
- **Where the GUI's link comes from.** `InfoServiceImpl.java:260` copies `SystemInfo`'s help URL into the GUI's branding, so the `SystemInfo` default is what users see. Per-tenant branding sits behind the branding feature gates (`Feature.BRANDING_STANDARD`, `BRANDING_FULL`). The builder checks on a stack that nothing overrides the default.

### 10.2 Repo docs (call)

- **`README.md`:** keep Healdan's notice. Replace the upstream README that follows it with a short Healdan README: what Healdan is, links to the four manuals, the licence and LogicalDOC's credit. Upstream rarely edits its README. When a sync brings an edit, keep Healdan's.
- **`docker/README.md`:** becomes a short pointer to the Install and Developer manuals. It doesn't change before go-live. Until then, a PR that changes the Docker stack updates both `docker/README.md` and the site.
- **`Build_this_Sources.md`:** replace its content with a pointer to the Developer Guide's Building from Source.
- **CLAUDE.md:** where it calls `docker/README.md` the user guide, point at the site instead.

### 10.3 The LogicalDOC-links check

`.github/scripts/check-logicaldoc-doc-links.py` runs early in `build-logicaldoc.yml`, as the icon check does. It fails on `https?://(docs|wiki|help|forums)\.logicaldoc\.com` anywhere in the repo except:

- `site/content/`, on lines with the `interim` shortcode;
- `docs/` (internal specs and plans, which discuss these links);
- upstream's release history: `ReleaseNotes.md`, `ReleaseNotes.txt`, `CHANGELOG.md`;
- test fixtures: `logicaldoc-core/src/test/resources/README.md` (`FormatConverterTest` reads it) and `logicaldoc-dropbox/src/test/resources/context.properties`;
- the script itself.

Not in scope, and not matched by the pattern: LogicalDOC's credit and `www.logicaldoc.com` links; `ws.logicaldoc.com`, the SOAP API's XML namespace, which must never change; `bugs.logicaldoc.com`.

## 11. Verification

### 11.1 Automated

- The `Docs site` check: the build, with warnings failing it, and the offline internal-link check.
- `check-logicaldoc-doc-links.py`, after the link switch.
- The new `SystemInfo` and `WSSystemInfo` test.

### 11.2 Manual acceptance (owner)

On `https://docs.healdan.com` after go-live:

1. The landing page shows the three cards and the Developer Guide link. The browser tab shows Healdan's favicon.
2. Each tab opens its manual, and the left nav holds only that manual.
3. On a phone, the menu lists the four manuals, opens only the current one, and switches manuals.
4. Light and dark modes both read well, including the headline and the interim box.
5. Search finds "doc #" on the Finding a Document by Doc # page.
6. Every page in §6.1 exists, and a sample of interim pages links to a live LogicalDOC page.
7. The footer shows the links and the copyright line as in §5.4.
8. After the link switch, Help in the app opens `https://docs.healdan.com`.

## 12. Phasing (call)

| Step | What | Who |
|---|---|---|
| PR A | `site/` with the theme, look, footer, landing page, the Install and Developer manuals, the contents' interim pages, the `Docs site` workflow (deploy off), the CI filter changes, the CLAUDE.md section, the PR template and the roadmap's docs-site entry | builder |
| | Add `Docs site` to the required checks | owner |
| PR B | The User and Admin launch pages, with screenshots | builder |
| | DNS, domain verification, Pages settings, `DOCS_PUBLISH=true` (§7.3). The site goes live. | owner |
| PR C | The link switch (§10): upstream edits, tests, `README.md`, `docker/README.md`, `Build_this_Sources.md`, CLAUDE.md, and the link check | builder |
| after | Interim pages replaced as features are documented, until #56 closes | everyone, in feature PRs |

Between PR A and go-live, a code PR that changes Install or Developer behaviour updates `site/` and, for the Docker stack, `docker/README.md` too.

**The owner looks before PR A and PR B merge** (call). `master` merges on green, so the builder shows the built pages to the owner, as the spike was shown, before turning on auto-merge for those two PRs. That's the landing page, the manuals and the screenshots. After that, docs changes ride with their code PRs and get those PRs' review.

**The spike** is kept as a reference in this worktree's `.superpowers/docs-site-spike/`, which git ignores. It holds the sidebar override, footer, `interim` shortcode, colours, logos and sample pages. The builder may start from it, but everything in it is re-checked against this spec: it's throwaway work, not reviewed code.

## 13. Left to the builder

- Choosing among Hextra's icons for the cards.
- The link checker, and how Hugo is installed in CI (pinned either way).
- The exact pages within each launch section, as long as §6.1's topics are covered.
- Which unchanged CE features get interim pages, and the nearest LogicalDOC page for each.
- The demo data for screenshots.
- Whether `site/content/` uses page bundles everywhere or only for pages with images.

## 14. Decisions made in this session (2026-10-23)

| Decision | Source |
|---|---|
| Healdan builds its own docs site, in `site/` in this repo, with interim links to LogicalDOC's pages; the goal is no dependence on LogicalDOC's docs | owner (issue #56) |
| For anyone who wants to know about Healdan; manuals Install, User, Admin, Developer | owner |
| Landing page: three doors (Install, User, Admin), Developer as a smaller link | owner (mockup A) |
| Tabs switch manuals; the left nav only ever shows the selected manual, so manuals can be added and retired (e.g. two User manuals during M6) | owner (mockup A) |
| Hugo | owner ("leaning a little") |
| Hextra v0.13.0, vendored; Healdan's logo and colours | call (theme handed to Claude) |
| Footer with guide and project links; © The Healdan Project; docs under CC BY 4.0 | owner |
| All of Healdan's code, its own modules included, is LGPL-3.0; the footer says so, and the roadmap's open licence item closes | owner |
| Title Case for titles, headings and labels | owner |
| Chicago-style small words; sentence-case body; British spelling | call |
| Published at docs.healdan.com | owner |
| One version, following `master` | call |
| Docs written and amended in the same PR as the code | owner |
| Launch: Install in full, User and Admin for M1 and M1.5, Developer from existing material, everything else interim | owner ("a good foundation") |
| The UI's links are repointed once the site is published | owner |
| Phone menu as manual switcher; retiring by aliases; FlexSearch; English only; no analytics; screenshots by hand | call |
| After the builder check: Using a Published Image waits for published images; CLAUDE.md and the Developer Guide both kept; the trademark sentence in the footer; the 34 bundles edited; PR artifacts and an owner look before PR A and PR B merge | call |
