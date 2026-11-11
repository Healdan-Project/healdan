// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type components } from '@healdan/api'

/** Where a view reads its bytes from: an API path and its query, as `Api.getResponse` takes them */
export interface PreviewSource {
  path: string
  query?: Record<string, string | undefined>
  /** What it is called, for a picture's description; the document's name by default */
  name?: string
  /** What changes when the same address has other bytes, such as the email's file version for its attachment */
  key?: string
}

/** What every view of a document's preview takes */
export interface PreviewProps {
  document: components['schemas']['DocumentView']
  /** A version of the document, such as 1.0; the current file by default */
  version?: string
  /** Whether a view that can't show the file offers Open and Download itself; Quick Look's bar has them already. Yes by default. */
  showActions?: boolean
  /** Read these bytes instead of the document's own preview: an email's attachment (R56). Then `version` is ignored. */
  source?: PreviewSource
}

/** The document's own preview, at a version, as the address to read */
export function previewSource(document: PreviewProps['document'], version?: string): PreviewSource {
  return { path: apiPath`/v1/documents/${document.docNumber}/preview`, query: version ? { version } : undefined }
}
