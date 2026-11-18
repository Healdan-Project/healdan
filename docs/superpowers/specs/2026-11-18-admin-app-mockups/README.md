# Admin app mockups

The mockups the owner reviewed in the browser in the design session on 2026-11-18 ([spec](../2026-11-18-admin-app-design.md)). They use the demo firm's names (`docker/demo/manifest.json`), invented numbers, the User app's colours and Tabler's icons, drawn in the style of the [M6 mockups](../2026-11-10-m6-mockups/README.md). Each file is the page the owner saw, with a short head added so it opens in a browser on its own. It loads Tabler's icon webfont from jsDelivr. A small script scales each drawing of the app to fit the window.

They're illustrations. Where a mockup and the spec differ, the spec wins:

- `01-frame-and-dashboard.html`: the same Dashboard in two frames. The owner chose A, the left pane.
- `02-matters.html`: the Matters list, two ways of opening a matter, and New Matter. The owner chose B: a matter opens as its own page, with its workspace on a tab. New Matter's Client and Owner are drawn as menus here; the owner's later rule makes them searches.
- `03-monitoring.html`: two ways of showing errors, then the Server Log and the Audit Trail. The owner chose A: errors grouped by kind and kept until resolved.
- `04-index-users-storage.html`: Search Index, Reindex, Users, a person's page, and Stores with their backends, as proposals. The owner accepted them but for Reindex's matter picker, which was a drop-down.
- `05-reindex-search.html`: Reindex with a search for matters, the owner's correction, which became a rule for the whole app: anything that can grow long is a search.
