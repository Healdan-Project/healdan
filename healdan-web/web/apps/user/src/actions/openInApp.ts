// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Open in App (spec 10.6), ported from the old UI's HealdanOpenInApp, which has the same list and the same link.

// The kinds of file the helper opens (M3 spec 5.11), and PDFs, which it opens in CoffeePDF or read-only in the default app
// (M3.6 spec 5.1), in lower case
const EXTENSIONS = new Set(['doc', 'docx', 'docm', 'dotx', 'dotm', 'rtf', 'odt', 'ott', 'xls', 'xlsx', 'xlsm', 'ods', 'ppt', 'pptx', 'pptm', 'odp', 'odg', 'pdf'])

/** Whether the helper opens this kind of file, whatever the case of its extension. Anything else is downloaded instead. */
export function handlesInApp(fileName: string | undefined): boolean {
  if (!fileName) return false
  const dot = fileName.lastIndexOf('.')
  if (dot < 0 || dot === fileName.length - 1) return false
  return EXTENSIONS.has(fileName.slice(dot + 1).toLowerCase())
}

/** The link that opens a document in the helper: `healdan://open/<doc #>?server=<host>`, the host with its port if it has one, no credentials. */
export function healdanLink(docNumber: number, host: string): string {
  return `healdan://open/${docNumber}?server=${host}`
}

/** Where the notice sends a person whose helper isn't installed */
export const HELPER_DOCS = 'https://docs.healdan.com/user/office/installing-the-helper/'
