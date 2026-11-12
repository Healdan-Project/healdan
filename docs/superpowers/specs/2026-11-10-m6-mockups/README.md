# M6 User app mockups

The mockups the owner reviewed in the browser in the M6 design session on 2026-11-10 ([spec](../2026-11-10-m6-user-app-design.md), §6). They use the demo firm's data (`docker/demo/manifest.json`), the helper's palette and Tabler's icons. Each file is the page the owner saw, with a short head added so it opens in a browser on its own. It loads Tabler's icon webfont from jsDelivr. A small script scales each drawing of the app to fit the window.

They're illustrations. Where a mockup and the spec differ, the spec wins:

- `01-layout.html`: the overall layout, with matter 1001.1's Correspondence folder open and a letter selected. The owner chose none of its options for the right side and asked for a Filters button instead, drawn in the next file. The section headed "Search" in the left pane became "Recent" (owner) from `04-preview.html` on, and the "Helper" indicator in the top bar was dropped (spec §6.10).
- `02-details-and-filters.html`: the Filters button, with its badge, and the chips above the list, in two states. The owner chose B: clicking a document shows its details.
- `03-search.html`: suggestions as you type, and a page of results. The owner chose A for the left pane's four entries, each opening its recent items, under the heading "Recent". The spec's `#` result keeps roadmap principle 1 (§6.3).
- `04-preview.html`: a reading pane and Quick Look. The owner chose C, both: Quick Look on Space, with a button to dock it.
- `05-filing.html`: dragging onto a folder, a form first, and filing at once with a tray. The owner chose C: at once, unless a required field has no default or ⌥ is held.
- `06-email.html`: a filed email in Quick Look. The owner chose A: pictures from the internet blocked, with Show Pictures. The spec cleans the body on the server and embeds inline pictures (§9.4).
- `07-look.html`: the dark theme, and three row heights at actual size. The owner chose D: Standard rows, with a Compact setting later.
