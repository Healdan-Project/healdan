// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t, toast } from '@healdan/ui'
import { docHref } from '../addresses'

/**
 * Puts a document's link on the clipboard: its own address, which works in an email too (spec 10.1). A browser has no
 * clipboard on a page over plain http (off localhost), and may refuse one; the person is told either way.
 */
export async function copyLink(docNumber: number): Promise<void> {
  try {
    await navigator.clipboard.writeText(`${window.location.origin}${docHref(docNumber)}`)
    toast(t('details.linkCopied'))
  } catch {
    toast(t('details.linkNotCopied'))
  }
}
