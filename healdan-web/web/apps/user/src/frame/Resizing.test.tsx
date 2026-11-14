// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes } from '../test/details'
import { children, document, folder, type Routes } from '../test/fakeApi'
import { resetPdf } from '../test/fakePdf'
import { pdfResponse } from '../test/renderPreview'
import { renderApp } from '../test/renderApp'
import { resize, setViewport } from '../test/viewport'

vi.mock('../preview/pdfEngine', () => import('../test/fakePdf'))

beforeEach(resetPdf)

// The dividers between the left pane, the list and the right region (spec section 6.1): dragged or moved by keys in a wide
// window, remembered in this browser, and gone in a narrow one, where the panes fold away.
const docs = ['Alpha', 'Bravo'].map((name, i) => document({ docNumber: 10388 + i, name: `${name}.pdf`, version: '1.0', fileVersion: '1.0' }))

function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': folder({ id: 7, name: 'Correspondence' }),
    'GET /v1/folders/7/children': children(docs),
    'GET /v1/documents/*/preview': () => pdfResponse(),
    ...Object.assign({}, ...docs.map((doc) => detailsRoutes(doc))),
    ...over,
  }
}

const navigation = () => screen.findByRole('separator', { name: 'Resize Navigation' })
const valueOf = (separator: HTMLElement) => Number(separator.getAttribute('aria-valuenow'))
/** The frame's element, which holds the panes' widths: the top bar is its first child */
const frame = () => screen.getByRole('banner').parentElement!
const drawn = (property: string) => frame().style.getPropertyValue(property)
const kept = (name: string) => window.localStorage.getItem(`healdan.pane.${name}`)

async function press(separator: HTMLElement, keys: string) {
  separator.focus()
  await userEvent.keyboard(keys)
}

describe('the panes\' dividers', () => {
  it('resize the left pane from the keyboard, keep its width in this browser, and go back to the default on Enter', async () => {
    setViewport(1280)
    await renderApp('/folders/7', routes())
    const separator = await navigation()
    expect(separator.getAttribute('aria-orientation')).toBe('vertical')
    expect([valueOf(separator), separator.getAttribute('aria-valuemin'), separator.getAttribute('aria-valuemax')]).toEqual([248, '180', '420'])
    // It names the pane it resizes, which holds the left pane
    const pane = globalThis.document.getElementById(separator.getAttribute('aria-controls')!)
    expect(within(pane!).getByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    expect(drawn('--left-width')).toBe('248px')

    await press(separator, '{ArrowRight}')
    expect(valueOf(separator)).toBe(264)
    expect(drawn('--left-width')).toBe('264px')
    expect(kept('left')).toBe('264')
    await press(separator, '{Shift>}{ArrowLeft}{/Shift}')
    expect(valueOf(separator)).toBe(200)
    expect(kept('left')).toBe('200')

    await press(separator, '{Enter}')
    expect(valueOf(separator)).toBe(248)
    expect(drawn('--left-width')).toBe('248px')
    expect(kept('left')).toBeNull()
  })

  it('draws a drag as it goes, and keeps the width only once it ends', async () => {
    setViewport(1280)
    await renderApp('/folders/7', routes())
    const separator = await navigation()
    fireEvent.pointerDown(separator, { pointerId: 1, button: 0, clientX: 248, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 340, pointerType: 'mouse' })
    expect(drawn('--left-width')).toBe('340px')
    expect(kept('left')).toBeNull()
    fireEvent.pointerUp(window, { pointerId: 1, clientX: 340, pointerType: 'mouse' })
    await waitFor(() => expect(kept('left')).toBe('340'))
    expect(valueOf(separator)).toBe(340)
    expect(drawn('--left-width')).toBe('340px')
  })

  it('start from the widths kept in this browser', async () => {
    window.localStorage.setItem('healdan.pane.left', '320')
    setViewport(1280)
    await renderApp('/folders/7', routes())
    expect(valueOf(await navigation())).toBe(320)
    expect(drawn('--left-width')).toBe('320px')
  })

  it('draw a kept width that no longer fits at the limit, without keeping that, and use it again on a wider window', async () => {
    window.localStorage.setItem('healdan.pane.left', '420')
    setViewport(1100)
    await renderApp('/folders/7', routes())
    const separator = await navigation()
    // 1,100 less the list's 464 and the right region's least, 260
    expect(valueOf(separator)).toBe(376)
    expect(separator.getAttribute('aria-valuemax')).toBe('376')
    expect(kept('left')).toBe('420')
    resize(1400)
    await waitFor(() => expect(valueOf(separator)).toBe(420))
    expect(drawn('--left-width')).toBe('420px')
  })

  it('aren\'t there in a narrow window, where the panes keep their own widths, and come back when it widens', async () => {
    window.localStorage.setItem('healdan.pane.left', '320')
    window.localStorage.setItem('healdan.pane.right', '400')
    setViewport(768)
    await renderApp('/folders/7?sel=10388', routes())
    await screen.findByRole('button', { name: 'Navigation' })
    expect(screen.queryByRole('separator')).toBeNull()
    expect(drawn('--left-width')).toBe('')
    expect(drawn('--right-width')).toBe('')
    // The region opened over the list has no divider either
    await userEvent.click(await screen.findByRole('button', { name: 'Details and Filters' }))
    await screen.findByRole('region', { name: 'Details' })
    expect(screen.queryByRole('separator')).toBeNull()

    resize(1280)
    expect(valueOf(await navigation())).toBe(320)
    expect(valueOf(await screen.findByRole('separator', { name: 'Resize Details' }))).toBe(400)
    expect(drawn('--right-width')).toBe('400px')
  })

  it('work, at the defaults, when the browser refuses storage', async () => {
    const refused = () => {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(refused)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(refused)
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(refused)
    setViewport(1280)
    await renderApp('/folders/7', routes())
    const separator = await navigation()
    expect(valueOf(separator)).toBe(248)
    await press(separator, '{ArrowRight}')
    expect(valueOf(separator)).toBe(264)
    expect(drawn('--left-width')).toBe('264px')
    await press(separator, '{Enter}')
    expect(valueOf(separator)).toBe(248)
    vi.restoreAllMocks()
  })

  it('resize the details from their edge, widening them to the left, up to what the list leaves', async () => {
    setViewport(1280)
    await renderApp('/folders/7?sel=10388', routes())
    const region = await screen.findByRole('region', { name: 'Details' })
    const separator = await screen.findByRole('separator', { name: 'Resize Details' })
    expect(separator.getAttribute('aria-controls')).toBe(region.id)
    // Half the window is 640, but the list keeps 464 beside the left pane's 248
    expect([valueOf(separator), separator.getAttribute('aria-valuemin'), separator.getAttribute('aria-valuemax')]).toEqual([300, '260', '568'])
    await press(separator, '{ArrowLeft}')
    expect(valueOf(separator)).toBe(316)
    expect(drawn('--right-width')).toBe('316px')
    expect(kept('right')).toBe('316')
    await press(separator, '{End}')
    expect(valueOf(separator)).toBe(568)
    // A wider left pane leaves the region less
    await press(await navigation(), '{ArrowRight}')
    expect(valueOf(screen.getByRole('separator', { name: 'Resize Details' }))).toBe(552)
    expect(kept('right')).toBe('568')
  })

  it('give the reading pane a width of its own, which follows the window until it is chosen', async () => {
    setViewport(1280)
    await renderApp('/folders/7?sel=10388', routes())
    const details = await screen.findByRole('separator', { name: 'Resize Details' })
    await press(details, '{ArrowLeft}')
    expect(valueOf(details)).toBe(316)

    fireEvent.click(await within(await screen.findByRole('region', { name: 'Details' })).findByRole('button', { name: 'Preview' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Open as Reading Pane' }))
    await screen.findByRole('region', { name: 'Preview' })
    // Quick Look has closed, and given the focus back to the list's row
    await waitFor(() => expect(globalThis.document.activeElement?.getAttribute('data-row-key')).toBe('d10388'))
    const preview = await screen.findByRole('separator', { name: 'Resize Preview' })
    // 42% of the window
    expect(valueOf(preview)).toBe(538)
    await press(preview, '{Home}')
    expect(valueOf(preview)).toBe(260)
    expect(drawn('--preview-width')).toBe('260px')
    expect(kept('preview')).toBe('260')
    expect(kept('right')).toBe('316')

    fireEvent.click(screen.getByRole('button', { name: 'Close Preview' }))
    expect(valueOf(await screen.findByRole('separator', { name: 'Resize Details' }))).toBe(316)
  })

  it('pass axe beside the list and the details', async () => {
    setViewport(1280)
    await renderApp('/folders/7?sel=10388', routes())
    await screen.findByRole('separator', { name: 'Resize Details' })
    await navigation()
    await expectNoAxeViolations(globalThis.document.body)
  })
})
