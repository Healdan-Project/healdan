// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { isDrawnPicture } from './drawn'
import { EmailView } from './EmailView'
import { PdfView } from './PdfView'
import { PictureView } from './PictureView'
import type { PreviewProps } from './types'

/**
 * What Quick Look and the reading pane show of a document, chosen by its kind (the server's, the one list of kinds): a
 * picture every browser draws as itself, an email as a message (`EmailView`), at the version asked for, and every other
 * kind, a TIFF or a HEIC included, as its PDF, which says so when there's none. This is the one switch, so a new view of a kind goes in here and both places have it.
 */
export function PreviewBody({ document, version, showActions }: PreviewProps) {
  const key = `${document.docNumber}.${version ?? document.fileVersion}`
  switch (document.kind) {
    case 'picture':
      return isDrawnPicture(document.name) ? (
        <PictureView key={key} document={document} version={version} showActions={showActions} />
      ) : (
        <PdfView key={key} document={document} version={version} showActions={showActions} />
      )
    case 'email':
      return <EmailView key={key} document={document} version={version} showActions={showActions} />
    default:
      return <PdfView key={key} document={document} version={version} showActions={showActions} />
  }
}
