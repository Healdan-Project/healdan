// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { NoPreview } from './NoPreview'
import { PdfView } from './PdfView'
import { PictureView } from './PictureView'
import type { PreviewProps } from './types'

/**
 * What Quick Look and the reading pane show of a document, chosen by its kind (the server's, the one list of kinds): a
 * picture as itself, an email as a message (Task 17 gives it its view), and every other kind as M1.5's PDF, which says so
 * when its type has none. This is the one switch, so a new view of a kind goes in here and both places have it.
 */
export function PreviewBody({ document, version, showActions }: PreviewProps) {
  const key = `${document.docNumber}.${version ?? document.fileVersion}`
  switch (document.kind) {
    case 'picture':
      return <PictureView key={key} document={document} version={version} showActions={showActions} />
    case 'email':
      return <NoPreview key={key} document={document} showActions={showActions} />
    default:
      return <PdfView key={key} document={document} version={version} showActions={showActions} />
  }
}
