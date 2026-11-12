// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/**
 * Follows an address the way a click on a link does: a `healdan:` link goes to the helper without leaving the page, and a
 * file's address (with a name) downloads. No script reads the bytes of a download, and no blob is made. This is the one
 * place the page navigates, so tests replace it.
 */
export function launch(href: string, download?: string): void {
  const link = window.document.createElement('a')
  link.href = href
  if (download !== undefined) link.download = download
  link.rel = 'noopener'
  link.hidden = true
  window.document.body.append(link)
  link.click()
  link.remove()
}
