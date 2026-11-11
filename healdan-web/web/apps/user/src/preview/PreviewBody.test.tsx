// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { document } from '../test/fakeApi'
import { resetPdf } from '../test/fakePdf'
import { pdfResponse, renderPreview } from '../test/renderPreview'
import { PreviewBody } from './PreviewBody'

vi.mock('./pdfEngine', () => import('../test/fakePdf'))

beforeEach(() => {
  resetPdf()
  asPicture = false
  Object.defineProperty(URL, 'createObjectURL', { value: () => 'blob:x', configurable: true, writable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: () => {}, configurable: true, writable: true })
})

// The picture's own bytes come from the same address as a PDF
let asPicture = false
const routes = {
  'GET /v1/documents/10388/preview': () => (asPicture ? new Response(new Uint8Array([1]), { headers: { 'Content-Type': 'image/png' } }) : pdfResponse()),
}

describe('PreviewBody', () => {
  it.each([
    ['Lease.pdf', 'pdf'],
    ['Lease.docx', 'word'],
    ['Model.xlsx', 'excel'],
    ['Deck.pptx', 'powerpoint'],
    ['Notes.odt', 'opendocument'],
    ['Archive.zip', 'other'],
  ])('draws %s, a %s, as the PDF the server prepares', async (name) => {
    const { api } = renderPreview(<PreviewBody document={document({ name })} />, routes)
    expect(await screen.findByText('Page 1 of 3')).toBeTruthy()
    expect(api.callsTo('GET /v1/documents/10388/preview')).toHaveLength(1)
  })

  it('shows a picture from its own bytes, which the preview answers, never the content', async () => {
    asPicture = true
    const { api } = renderPreview(<PreviewBody document={document({ name: 'Scan.png' })} />, routes)
    await screen.findByRole('img', { name: 'Picture: Scan.png' })
    expect(api.callsTo('GET /v1/documents/10388/preview')).toHaveLength(1)
    expect(api.callsTo('GET /v1/documents/10388/content')).toHaveLength(0)
  })

  it('says there is no preview of an email, until it has a view of its own, and asks for nothing', async () => {
    const { api } = renderPreview(<PreviewBody document={document({ name: 'Re Lease.eml' })} />, routes)
    expect(await screen.findByText("There's no preview for this kind of file.")).toBeTruthy()
    await waitFor(() => expect(api.calls).toHaveLength(0))
  })
})
