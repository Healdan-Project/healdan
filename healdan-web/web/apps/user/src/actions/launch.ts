// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The two places the page goes to an address, a `healdan:` link and a download, so tests replace this module.

/** Follows a `healdan:` link the way a click on a link does: it goes to the helper without leaving the page. */
export function launch(href: string): void {
  const link = window.document.createElement('a')
  link.href = href
  link.rel = 'noopener'
  link.hidden = true
  window.document.body.append(link)
  link.click()
  link.remove()
}

// A download goes through a hidden frame, as the Admin app's do: the helper is the shell's, and the User app's importers
// and tests reach it here
export { FRAME_KEPT, launchDownload } from '@healdan/shell'
