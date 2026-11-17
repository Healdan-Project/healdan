# Folders and the Trash mockups

The mockups the owner reviewed in the browser in the design session on 2026-11-16 ([spec](../2026-11-16-folders-and-trash-design.md)). They use the demo firm's data (`docker/demo/manifest.json`), the User app's colours and Tabler's icons, drawn in the style of the [M6 mockups](../2026-11-10-m6-mockups/README.md). Each file is the page the owner saw, with a short head added so it opens in a browser on its own. It loads Tabler's icon webfont from jsDelivr. A small script scales each drawing of the app to fit the window.

They're illustrations. Where a mockup and the spec differ, the spec wins:

- `01-trash-place.html`: the same Trash reached two ways. The owner chose A: an entry of its own at the foot of the left pane, not Recently Deleted under Recent.
- `02-admin-and-confirmations.html`: how an administrator reaches everyone's Trash and the removed items, with the Remove and Empty Trash confirmations and the toasts after a delete and a restore. The owner chose A: one Trash with a switch, Mine · Everyone's · Removed, shown to administrators only.
- `03-folders.html`: a folder's right-click menu, a folder dragged onto the tree, Move To… for a folder, the delete confirmation and its two refusals. The owner chose A: moving a folder into another matter asks first.
