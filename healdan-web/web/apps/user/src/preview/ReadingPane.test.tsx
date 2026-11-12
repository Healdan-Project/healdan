// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes } from '../test/details'
import { children, document, folder, matter, page, type Routes } from '../test/fakeApi'
import { pdf, resetPdf } from '../test/fakePdf'
import { pdfResponse } from '../test/renderPreview'
import { renderApp } from '../test/renderApp'

vi.mock('./pdfEngine', () => import('../test/fakePdf'))

beforeEach(resetPdf)

const docs = ['Alpha', 'Bravo', 'Charlie'].map((name, i) => document({ docNumber: 10388 + i, name: `${name}.pdf`, version: '1.0', fileVersion: '1.0' }))

function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': folder({ id: 7, name: 'Correspondence' }),
    'GET /v1/folders/7/children': children(docs),
    'GET /v1/documents/*/preview': () => pdfResponse(),
    ...Object.assign({}, ...docs.map((doc) => detailsRoutes(doc))),
    ...over,
  }
}

const rowOf = (name: string) => screen.findByRole('row', { name: new RegExp(name) })

/** Docks the pane from the details' Preview button, then Quick Look's dock button */
async function dock() {
  const details = await screen.findByRole('region', { name: 'Details' })
  fireEvent.click(await within(details).findByRole('button', { name: 'Preview' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Open as Reading Pane' }))
  return screen.findByRole('region', { name: 'Preview' })
}

describe('ReadingPane', () => {
  it('stays open while the selection moves, previewing each row', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const pane = await dock()
    expect(await within(pane).findByRole('heading', { name: 'Alpha.pdf' })).toBeTruthy()

    fireEvent.click(await rowOf('Bravo.pdf'))
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Preview' })).getByRole('heading', { name: 'Bravo.pdf' })).toBeTruthy())
    // The next document's own preview is asked for, and the page drawn again
    await waitFor(() => expect(pdf.opened.length).toBe(2))

    fireEvent.click(await rowOf('Charlie.pdf'))
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Preview' })).getByRole('heading', { name: 'Charlie.pdf' })).toBeTruthy())
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
  })

  it('previews the next row when the arrow keys move to it', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    await dock()
    const alpha = await rowOf('Alpha.pdf')
    alpha.focus()
    fireEvent.keyDown(alpha, { key: 'ArrowDown' })
    fireEvent.keyUp(alpha, { key: 'ArrowDown' })
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Preview' })).getByRole('heading', { name: 'Bravo.pdf' })).toBeTruthy())
  })

  it('gives way to Filters at the funnel until the next click on a row, which brings the pane back', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    await dock()

    fireEvent.click(await screen.findByRole('button', { name: /^Filters/ }))
    expect(await screen.findByRole('region', { name: 'Filters' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Preview' })).toBeNull()

    fireEvent.click(await rowOf('Bravo.pdf'))
    const pane = await screen.findByRole('region', { name: 'Preview' })
    expect(await within(pane).findByRole('heading', { name: 'Bravo.pdf' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Filters' })).toBeNull()
  })

  it('closes to the details of the selected document', async () => {
    await renderApp('/folders/7?sel=10389', routes())
    const pane = await dock()

    fireEvent.click(within(pane).getByRole('button', { name: 'Close Preview' }))

    const details = await screen.findByRole('region', { name: 'Details' })
    expect(await within(details).findByRole('heading', { name: 'Bravo.pdf' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Preview' })).toBeNull()
  })

  it('shows Title, Doc # and Edit Date in the list while it is open, and every column when it is not', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const headers = () => screen.getAllByRole('columnheader').map((header) => header.textContent)
    await rowOf('Alpha.pdf')
    expect(headers()).toEqual(['Title', 'Doc #', 'Version', 'Author', 'Edit Date'])

    const pane = await dock()
    expect(headers()).toEqual(['Title', 'Doc #', 'Edit Date'])

    fireEvent.click(within(pane).getByRole('button', { name: 'Close Preview' }))
    await waitFor(() => expect(headers()).toEqual(['Title', 'Doc #', 'Version', 'Author', 'Edit Date']))
  })

  it('leaves the list at its full columns where the view has no pane, as in the Matters and Folders scopes', async () => {
    const { router } = await renderApp('/folders/7?sel=10388', routes({ 'GET /v1/search': page([matter()]) }))
    const headers = () => screen.getAllByRole('columnheader').map((header) => header.textContent)
    await dock()
    expect(headers()).toEqual(['Title', 'Doc #', 'Edit Date'])

    await router.navigate({ to: '/search', search: { scope: 'matters', q: 'charter' } })
    await screen.findByRole('row', { name: /Charter Dispute/ })
    expect(headers()).toEqual(['Matter', 'Client'])
    expect(screen.queryByRole('region', { name: 'Preview' })).toBeNull()

    await router.navigate({ to: '/search', search: { scope: 'folders', q: 'charter' } })
    await waitFor(() => expect(headers()).toEqual(['Title', 'Matter']))
  })

  it('waits for a click when no document is selected', async () => {
    const { router } = await renderApp('/folders/7?sel=10388', routes())
    await dock()
    await router.navigate({ to: '/folders/$id', params: { id: '7' }, search: {} })
    const pane = await screen.findByRole('region', { name: 'Preview' })
    expect(within(pane).getByText('Select a document to preview it.')).toBeTruthy()

    fireEvent.click(await rowOf('Charlie.pdf'))
    expect(await within(await screen.findByRole('region', { name: 'Preview' })).findByRole('heading', { name: 'Charlie.pdf' })).toBeTruthy()
  })

  it('previews a picture from its content', async () => {
    Object.defineProperty(URL, 'createObjectURL', { value: () => 'blob:scan', configurable: true, writable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: () => {}, configurable: true, writable: true })
    const scan = document({ docNumber: 10400, name: 'Scan.png' })
    await renderApp(
      '/folders/7?sel=10400',
      routes({
        'GET /v1/folders/7/children': children([scan]),
        ...detailsRoutes(scan),
        'GET /v1/documents/10400/preview': () => new Response(new Uint8Array([1]), { headers: { 'Content-Type': 'image/png' } }),
      }),
    )
    const pane = await dock()
    expect((await within(pane).findByRole('img', { name: 'Picture: Scan.png' })).getAttribute('src')).toBe('blob:scan')
  })

  it('passes axe', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const pane = await dock()
    await within(pane).findByText('Page 1 of 3')
    await expectNoAxeViolations(window.document.body)
  })
})
