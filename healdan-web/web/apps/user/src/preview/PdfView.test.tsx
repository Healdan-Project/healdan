// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PaneFrame, PaneSplitter } from '../frame/PaneWidths'
import { expectNoAxeViolations } from '../test/axe'
import { document, problem } from '../test/fakeApi'
import { pdf, resetPdf } from '../test/fakePdf'
import { pdfResponse, pendingResponse, renderPreview } from '../test/renderPreview'
import { PdfView } from './PdfView'

vi.mock('./pdfEngine', () => import('../test/fakePdf'))

const letter = document()
const path = 'GET /v1/documents/10388/preview'

beforeEach(resetPdf)
afterEach(() => vi.useRealTimers())

describe('PdfView', () => {
  it('draws page 1 and says which page it is of how many', async () => {
    renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    expect(await screen.findByText('Page 1 of 3')).toBeTruthy()
    await waitFor(() => expect(pdf.drawn.map((d) => d.page)).toContain(1))
    expect(pdf.opened).toHaveLength(1)
    expect(screen.getByRole('img', { name: 'Page 1' })).toBeTruthy()
    // Its controls are a group, not a toolbar, whose arrow keys the page's own would clash with
    expect(screen.getByRole('group', { name: 'Preview Controls' })).toBeTruthy()
    expect(screen.queryByRole('toolbar')).toBeNull()
    expect(screen.getByRole('region', { name: 'Scrollable Page' }).hasAttribute('data-scrolls')).toBe(true)
  })

  it('asks for the version it is given', async () => {
    const { api } = renderPreview(<PdfView document={letter} version="1.0" />, { [path]: () => pdfResponse() })
    await screen.findByText('Page 1 of 3')
    expect(api.callsTo(path)[0]?.query).toEqual({ version: '1.0' })
  })

  it("fits the page to the room's width, which is the zoom's 100%", async () => {
    const width = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1232)
    // jsdom has no ResizeObserver; this one says the width as it starts watching, as a browser's does
    vi.stubGlobal('ResizeObserver', class { constructor(private callback: () => void) {} observe() { this.callback() } disconnect() {} })
    renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    await screen.findByText('Page 1 of 3')
    // Pages are 600 wide, and 32 is left round them
    await waitFor(() => expect(pdf.drawn.at(-1)?.scale).toBeCloseTo(2))
    width.mockRestore()
    vi.unstubAllGlobals()
  })

  it('keeps the page as it was drawn while a divider is dragged, and draws it at the width the drag ends at', async () => {
    let room = 632
    const width = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => room)
    const observers: (() => void)[] = []
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private callback: () => void) {
          observers.push(callback)
        }
        observe() {
          this.callback()
        }
        disconnect() {}
      },
    )
    renderPreview(
      <PaneFrame className="">
        <PaneSplitter name="preview" aria-label="Resize Preview" />
        <PdfView document={letter} />
      </PaneFrame>,
      { [path]: () => pdfResponse() },
    )
    await screen.findByText('Page 1 of 3')
    // Pages are 600 wide, and 32 is left round them
    await waitFor(() => expect(pdf.drawn.at(-1)?.scale).toBeCloseTo(1))
    const divider = screen.getByRole('separator', { name: 'Resize Preview' })
    fireEvent.pointerDown(divider, { pointerId: 1, button: 0, clientX: 600, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 560, pointerType: 'mouse' })
    room = 932
    act(() => observers.forEach((callback) => callback()))
    await new Promise((done) => setTimeout(done, 50))
    expect(pdf.drawn.at(-1)?.scale).toBeCloseTo(1)
    fireEvent.pointerUp(window, { pointerId: 1, clientX: 560, pointerType: 'mouse' })
    await waitFor(() => expect(pdf.drawn.at(-1)?.scale).toBeCloseTo(1.5))
    width.mockRestore()
    vi.unstubAllGlobals()
  })

  it('moves between pages, and stops at the first and the last', async () => {
    renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    await screen.findByText('Page 1 of 3')
    expect((screen.getByRole('button', { name: 'Previous Page' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Next Page' }))
    expect(await screen.findByText('Page 2 of 3')).toBeTruthy()
    await waitFor(() => expect(pdf.drawn.at(-1)?.page).toBe(2))
    fireEvent.click(screen.getByRole('button', { name: 'Next Page' }))
    expect(await screen.findByText('Page 3 of 3')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Next Page' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Previous Page' }))
    expect(await screen.findByText('Page 2 of 3')).toBeTruthy()
  })

  it('zooms in and out, drawing the page again at the new scale', async () => {
    renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    await screen.findByText('Page 1 of 3')
    await waitFor(() => expect(pdf.drawn.length).toBeGreaterThan(0))
    expect(screen.getByText('100%')).toBeTruthy()
    const first = pdf.drawn.at(-1)!.scale

    fireEvent.click(screen.getByRole('button', { name: 'Zoom In' }))
    expect(await screen.findByText('125%')).toBeTruthy()
    await waitFor(() => expect(pdf.drawn.at(-1)!.scale).toBeCloseTo(first * 1.25))

    fireEvent.click(screen.getByRole('button', { name: 'Zoom Out' }))
    fireEvent.click(screen.getByRole('button', { name: 'Zoom Out' }))
    expect(await screen.findByText('75%')).toBeTruthy()
    await waitFor(() => expect(pdf.drawn.at(-1)!.scale).toBeCloseTo(first * 0.75))
  })

  it('stops zooming at the ends', async () => {
    renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    await screen.findByText('Page 1 of 3')
    const out = screen.getByRole('button', { name: 'Zoom Out' }) as HTMLButtonElement
    for (let i = 0; i < 6 && !out.disabled; i++) fireEvent.click(out)
    expect(out.disabled).toBe(true)
    expect(screen.getByText('50%')).toBeTruthy()
  })

  it('says it is preparing the preview for a 202, and asks again after Retry-After', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'] })
    let answers = 0
    const { api } = renderPreview(<PdfView document={letter} />, { [path]: () => (++answers === 1 ? pendingResponse(7) : pdfResponse()) })
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(screen.getByText('Preparing a preview…')).toBeTruthy()
    expect(api.callsTo(path)).toHaveLength(1)

    await act(() => vi.advanceTimersByTimeAsync(6_900))
    expect(api.callsTo(path)).toHaveLength(1)
    await act(() => vi.advanceTimersByTimeAsync(200))
    expect(api.callsTo(path)).toHaveLength(2)
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(screen.getByText('Page 1 of 3')).toBeTruthy()
    expect(screen.queryByText('Preparing a preview…')).toBeNull()
  })

  it('stops asking when it goes away', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'] })
    const { api, unmount } = renderPreview(<PdfView document={letter} />, { [path]: () => pendingResponse(5) })
    await act(() => vi.advanceTimersByTimeAsync(0))
    unmount()
    await act(() => vi.advanceTimersByTimeAsync(30_000))
    expect(api.callsTo(path)).toHaveLength(1)
  })

  it('shows there is no preview, with Open and Download, for a 404 no-preview', async () => {
    renderPreview(<PdfView document={document({ name: 'Archive.zip' })} />, { 'GET /v1/documents/10388/preview': problem(404, 'no-preview') })
    expect(await screen.findByText("There's no preview for this kind of file.")).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Open' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Download' })).toBeTruthy()
  })

  it('says the same for a document the person may not preview, as it does for one that is not there', async () => {
    renderPreview(<PdfView document={letter} />, { [path]: problem(403, 'forbidden') })
    expect(await screen.findByText("There's no preview for this kind of file.")).toBeTruthy()
  })

  it('says a preview could not be drawn when the file is not a PDF it can read', async () => {
    pdf.failOpen = true
    renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    expect(await screen.findByText("The preview couldn't be drawn.")).toBeTruthy()
  })

  it('offers Try Again when storage cannot be reached', async () => {
    renderPreview(<PdfView document={letter} />, { [path]: problem(503, 'storage-unreachable') })
    expect(await screen.findByRole('button', { name: 'Try Again' })).toBeTruthy()
  })

  it('closes the file when it goes away', async () => {
    const { unmount } = renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    await screen.findByText('Page 1 of 3')
    unmount()
    expect(pdf.closed).toBe(1)
  })

  it('passes axe', async () => {
    const { container } = renderPreview(<PdfView document={letter} />, { [path]: () => pdfResponse() })
    await screen.findByText('Page 1 of 3')
    await expectNoAxeViolations(container)
  })
})
