// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes } from '../test/details'
import { children, document, folder, type Routes } from '../test/fakeApi'
import { resetPdf } from '../test/fakePdf'
import { pdfResponse } from '../test/renderPreview'
import { renderApp } from '../test/renderApp'

vi.mock('./pdfEngine', () => import('../test/fakePdf'))

beforeEach(resetPdf)

// Seven documents in a folder, # 10391 the fourth
const docs = ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo', 'Foxtrot', 'Golf'].map((name, i) => document({ docNumber: 10388 + i, name: `${name}.pdf` }))

function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': folder({ id: 7, name: 'Correspondence' }),
    'GET /v1/folders/7/children': children(docs),
    'GET /v1/documents/*/preview': () => pdfResponse(),
    ...Object.assign({}, ...docs.map((doc) => detailsRoutes(doc))),
    ...over,
  }
}

const row = (name: string) => screen.findByRole('row', { name: new RegExp(name) })
const quickLook = (name: string) => screen.findByRole('dialog', { name: `Quick Look: ${name}` })

/** Space on the row, as a person at the keyboard does it */
async function spaceOn(name: string) {
  const target = await row(name)
  target.focus()
  fireEvent.keyDown(target, { key: ' ' })
  return target
}

describe('QuickLook', () => {
  it('opens on Space over the app, with the name, the place in the list and the doc #', async () => {
    await renderApp('/folders/7?sel=10391', routes())
    await spaceOn('Delta.pdf')

    const dialog = await quickLook('Delta.pdf')
    expect(within(dialog).getByText('Delta.pdf')).toBeTruthy()
    expect(within(dialog).getByText('4 of 7')).toBeTruthy()
    expect(within(dialog).getByText('# 10391')).toBeTruthy()
    // The page is drawn in it
    expect(await within(dialog).findByText('Page 1 of 3')).toBeTruthy()
  })

  it('selects the row it opens on, if another was selected', async () => {
    const { router } = await renderApp('/folders/7?sel=10388', routes())
    await spaceOn('Charlie.pdf')

    await quickLook('Charlie.pdf')
    await waitFor(() => expect(router.state.location.search).toMatchObject({ sel: '10390' }))
  })

  it('moves through the list with ‹ and ›, the selection with it', async () => {
    const { router } = await renderApp('/folders/7?sel=10391', routes())
    await spaceOn('Delta.pdf')
    const dialog = await quickLook('Delta.pdf')

    fireEvent.click(within(dialog).getByRole('button', { name: 'Next Document' }))
    await quickLook('Echo.pdf')
    expect(screen.getByText('5 of 7')).toBeTruthy()
    expect(router.state.location.search).toMatchObject({ sel: '10392' })

    fireEvent.click(screen.getByRole('button', { name: 'Previous Document' }))
    fireEvent.click(screen.getByRole('button', { name: 'Previous Document' }))
    await quickLook('Charlie.pdf')
    expect(screen.getByText('3 of 7')).toBeTruthy()
  })

  it('moves with the arrow keys too, and stops at the ends of the list', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    await spaceOn('Alpha.pdf')
    const dialog = await quickLook('Alpha.pdf')
    expect((within(dialog).getByRole('button', { name: 'Previous Document' }) as HTMLButtonElement).disabled).toBe(true)

    fireEvent.keyDown(dialog, { key: 'ArrowRight' })
    await quickLook('Bravo.pdf')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'ArrowDown' })
    await quickLook('Charlie.pdf')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'ArrowLeft' })
    await quickLook('Bravo.pdf')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'ArrowUp' })
    await quickLook('Alpha.pdf')
    // Nothing before the first
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'ArrowLeft' })
    expect(screen.getByRole('dialog', { name: 'Quick Look: Alpha.pdf' })).toBeTruthy()
  })

  it('loads the next page at the end of the loaded rows, and moves on when it comes', async () => {
    const first = docs.slice(0, 3)
    const { api } = await renderApp(
      '/folders/7?sel=10390',
      routes({
        'GET /v1/folders/7/children': ({ query }: { query?: Record<string, unknown> }) =>
          query?.offset ? children(docs.slice(3, 5), { offset: 3, total: 5, documents: 5 }) : children(first, { total: 5, documents: 5 }),
      }),
    )
    await spaceOn('Charlie.pdf')
    const dialog = await quickLook('Charlie.pdf')
    expect(within(dialog).getByText('3 of 5')).toBeTruthy()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Next Document' }))

    await quickLook('Delta.pdf')
    expect(screen.getByText('4 of 5')).toBeTruthy()
    expect(api.callsTo('GET /v1/folders/7/children').some((call) => call.query?.offset === 3)).toBe(true)
  })

  it('closes on Space and on Esc, and the focus is back on the row', async () => {
    await renderApp('/folders/7?sel=10391', routes())
    const target = await spaceOn('Delta.pdf')
    const dialog = await quickLook('Delta.pdf')

    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(target.contains(window.document.activeElement)).toBe(true))

    target.focus()
    fireEvent.keyDown(target, { key: ' ' })
    fireEvent.keyDown(await quickLook('Delta.pdf'), { key: ' ' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(target.contains(window.document.activeElement)).toBe(true))
  })

  it('puts the focus on the row it ended on, when it was walked away from', async () => {
    await renderApp('/folders/7?sel=10391', routes())
    await spaceOn('Delta.pdf')
    fireEvent.keyDown(await quickLook('Delta.pdf'), { key: 'ArrowRight' })
    fireEvent.keyDown(await quickLook('Echo.pdf'), { key: 'Escape' })

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const echo = await row('Echo.pdf')
    await waitFor(() => expect(echo.contains(window.document.activeElement)).toBe(true))
  })

  it('has Open and Download with the details panel’s buttons, which wait for the task that builds them', async () => {
    await renderApp('/folders/7?sel=10391', routes())
    await spaceOn('Delta.pdf')
    const dialog = await quickLook('Delta.pdf')
    for (const name of ['Open', 'Download']) expect((within(dialog).getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('docks as the reading pane in the right region', async () => {
    await renderApp('/folders/7?sel=10391', routes())
    await spaceOn('Delta.pdf')
    const dialog = await quickLook('Delta.pdf')

    fireEvent.click(within(dialog).getByRole('button', { name: 'Open as Reading Pane' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const pane = await screen.findByRole('region', { name: 'Preview' })
    expect(await within(pane).findByText('Page 1 of 3')).toBeTruthy()
    expect(within(pane).getByRole('heading', { name: 'Delta.pdf' })).toBeTruthy()
  })

  it('opens from the details panel’s Preview and the toolbar’s', async () => {
    await renderApp('/folders/7?sel=10391', routes())
    const panel = await screen.findByRole('region', { name: 'Details' })
    fireEvent.click(await within(panel).findByRole('button', { name: 'Preview' }))
    fireEvent.keyDown(await quickLook('Delta.pdf'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    const toolbar = await screen.findByRole('toolbar', { name: 'Folder Actions' })
    fireEvent.click(within(toolbar).getByRole('button', { name: /Preview/ }))
    await quickLook('Delta.pdf')
  })

  it('does not open on a folder', async () => {
    await renderApp('/folders/7', routes({ 'GET /v1/folders/7/children': children([folder({ id: 8, name: 'Drafts' }), ...docs]) }))
    const target = await row('Drafts')
    target.focus()
    fireEvent.keyDown(target, { key: ' ' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('passes axe', async () => {
    await renderApp('/folders/7?sel=10391', routes())
    await spaceOn('Delta.pdf')
    const dialog = await quickLook('Delta.pdf')
    await within(dialog).findByText('Page 1 of 3')
    await expectNoAxeViolations(window.document.body)
  })
})
