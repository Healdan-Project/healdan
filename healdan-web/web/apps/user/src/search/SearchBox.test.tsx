// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Dialog } from '@healdan/ui'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { document as doc, folder, matter, page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

const suggestions = {
  matters: [{ id: 11, number: 1, name: 'Charter Dispute', client: { number: 1001, name: 'Saltmarsh Shipping Ltd' } }],
  documents: [{ docNumber: 10392, name: 'Charterparty – MV Northern Star.docx', matter: { id: 11, number: 1, display: '1001.1 – Charter Dispute' } }],
}

const routes = {
  'GET /v1/suggest': suggestions,
  'GET /v1/matters/*': matter({ id: 11, folderId: 110 }),
  'GET /v1/folders/110': folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }),
}

function box() {
  return screen.getByRole('combobox', { name: 'Search' })
}

const where = (router: { history: { location: { pathname: string; search: string } } }) => router.history.location.pathname + router.history.location.search

async function openScopes() {
  const trigger = screen.getByRole('button', { name: /Search In/ })
  fireEvent.keyDown(trigger, { key: 'Enter' })
  fireEvent.keyUp(trigger, { key: 'Enter' })
  return screen.findByRole('menu')
}

describe('suggestions', () => {
  it('appear after two characters, matters first, then documents, then the search itself', async () => {
    const { api } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'c')
    await new Promise((resolve) => setTimeout(resolve, 250))
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(api.callsTo('GET /v1/suggest')).toEqual([])
    await user.keyboard('h')
    const list = await screen.findByRole('listbox')
    await waitFor(() => expect(within(list).getAllByRole('option')).toHaveLength(3))
    const options = within(list).getAllByRole('option').map((o) => o.textContent)
    expect(options[0]).toContain('1001.1 – Charter Dispute')
    expect(options[0]).toContain('Saltmarsh Shipping Ltd')
    expect(options[1]).toContain('Charterparty – MV Northern Star.docx')
    expect(options[1]).toContain('# 10392 · 1001.1')
    expect(options[2]).toBe('Search Documents for "ch"')
    expect(api.callsTo('GET /v1/suggest').map((c) => c.query)).toEqual([{ q: 'ch' }])
  })

  it('open a matter on Enter after the arrow keys, and clear the box', async () => {
    const { router, api } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await screen.findByRole('option', { name: /1001\.1 – Charter Dispute/ })
    await user.keyboard('{ArrowDown}{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/folders/110'))
    expect(api.callsTo('GET /v1/matters/11')).toHaveLength(1)
    expect((box() as HTMLInputElement).value).toBe('')
  })

  it('open a document at its # result on Enter', async () => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await screen.findByRole('option', { name: /Charterparty/ })
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/doc/10392'))
  })

  it('run the search in the scope on Enter when none is picked', async () => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await screen.findByRole('option', { name: /Charterparty/ })
    await user.keyboard('{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/search?scope=documents&q=charter'))
  })

  it('close once the search is run from the last option', async () => {
    await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await screen.findByRole('option', { name: 'Search Documents for "charter"' })
    await user.click(screen.getByRole('option', { name: 'Search Documents for "charter"' }))
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
  })

  it('run the search from the last option too, named for the scope', async () => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    const trigger = await openScopes()
    fireEvent.click(within(trigger).getByRole('menuitem', { name: 'Docs & Emails' }))
    await user.type(box(), 'charter')
    await screen.findByRole('option', { name: 'Search Docs & Emails for "charter"' })
    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/search?scope=all&q=charter'))
  })

  it('are asked for once the typing stops, with at most 100 characters', async () => {
    const { api } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await user.click(box())
    await waitFor(() => expect(api.callsTo('GET /v1/suggest')).toHaveLength(1))
    expect(api.callsTo('GET /v1/suggest')[0]?.query).toEqual({ q: 'charter' })
    fireEvent.change(box(), { target: { value: 'x'.repeat(150) } })
    await waitFor(() => expect(api.callsTo('GET /v1/suggest')).toHaveLength(2))
    expect((api.callsTo('GET /v1/suggest')[1]?.query?.q as string).length).toBe(100)
  })

  it('cancel the request a newer keystroke makes unwanted', async () => {
    const { api } = await renderApp('/', { 'GET /v1/suggest': () => new Promise(() => {}) })
    const user = userEvent.setup()
    await user.type(box(), 'cha')
    await waitFor(() => expect(api.callsTo('GET /v1/suggest')).toHaveLength(1))
    const first = api.callsTo('GET /v1/suggest')[0]!.signal
    expect(first?.aborted).toBe(false)
    await user.type(box(), 'r')
    await waitFor(() => expect(api.callsTo('GET /v1/suggest')).toHaveLength(2))
    expect(first?.aborted).toBe(true)
    expect(api.callsTo('GET /v1/suggest')[1]!.signal?.aborted).toBe(false)
  })

  it('are not asked for a #, which goes straight to its document', async () => {
    const { api } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), '#1038')
    await new Promise((resolve) => setTimeout(resolve, 300))
    expect(api.callsTo('GET /v1/suggest')).toHaveLength(0)
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('go away when the box is emptied', async () => {
    await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await screen.findByRole('listbox')
    await user.clear(box())
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
  })

  it('pass axe while open', async () => {
    await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await screen.findByRole('option', { name: /Charterparty/ })
    await expectNoAxeViolations(screen.getByRole('banner'))
  })
})

describe('a # typed in the box', () => {
  it.each([
    ['#10388', '/app/doc/10388'],
    ['#10388.2', '/app/doc/10388.2'],
    ['# 10388', '/app/doc/10388'],
    ['#10388v2', '/app/doc/10388v2'],
    ['#10388V2', '/app/doc/10388v2'],
    ['#10388 V2.1', '/app/doc/10388v2.1'],
    ['#10388 v2.1', '/app/doc/10388v2.1'],
    ['#10388.2.1', '/app/doc/10388.2.1'],
  ])('%s then Enter goes to %s', async (typed, to) => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), `${typed}{Enter}`)
    await waitFor(() => expect(where(router)).toBe(to))
  })

  it('goes the same way in any scope', async () => {
    const { router } = await renderApp('/recent/folders', routes)
    const user = userEvent.setup()
    await user.type(box(), '#10388{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/doc/10388'))
  })

  it('that is no doc # is a search', async () => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), '#abc{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/search?scope=documents&q=%23abc'))
  })
})

describe('a matter number in the Matters scope', () => {
  async function inMatters() {
    const rendered = await renderApp('/', {
      ...routes,
      'GET /v1/search': ({ query }: { query?: Record<string, unknown> }) =>
        query?.q === '1001.1' ? page([matter({ id: 11, number: 1, folderId: 110 }), matter({ id: 13, number: 1, folderId: 130, client: { id: 3, number: 1011, name: 'Other', } })]) : page([]),
    })
    const menu = await openScopes()
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Matters' }))
    return rendered
  }

  it('opens that matter on Enter', async () => {
    const { router, api } = await inMatters()
    const user = userEvent.setup()
    await user.type(box(), '1001.1{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/folders/110'))
    expect(api.callsTo('GET /v1/search')[0]?.query).toMatchObject({ scope: 'matters', q: '1001.1' })
  })

  it('shows the results when no matter has that number', async () => {
    const { router } = await inMatters()
    const user = userEvent.setup()
    await user.type(box(), '1005.9{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/search?scope=matters&q=1005.9'))
  })

  it('shows the results for words, without asking first', async () => {
    const { router, api } = await inMatters()
    const user = userEvent.setup()
    await user.type(box(), 'charter{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/search?scope=matters&q=charter'))
    expect(api.callsTo('GET /v1/search').length).toBeLessThanOrEqual(1)
  })
})

describe('the scope and the words', () => {
  it('follow the address of a search, and its Back', async () => {
    await renderApp('/search?scope=folders&q=pleadings', routes)
    expect(screen.getByRole('button', { name: /Search In.*Folders/ })).toBeTruthy()
    expect((box() as HTMLInputElement).value).toBe('pleadings')
  })

  it('offer every scope', async () => {
    await renderApp('/', routes)
    const menu = await openScopes()
    expect(within(menu).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Documents', 'Emails', 'Docs & Emails', 'Folders', 'Matters', 'Clients'])
    // Each with its scope's icon, as the button shows it, in its kind's colour where it has one
    expect(within(menu).getAllByRole('menuitem').map((i) => i.querySelector('svg')!.getAttribute('data-kind'))).toEqual([null, 'email', null, 'folder', 'matter', 'client'])
  })

  it('are Documents on the start page', async () => {
    await renderApp('/', routes)
    expect(screen.getByRole('button', { name: /Search In.*Documents/ })).toBeTruthy()
  })
})

describe('the / shortcut', () => {
  it('focuses the box from anywhere but a text field', async () => {
    await renderApp('/', routes)
    fireEvent.keyDown(document.body, { key: '/' })
    expect(document.activeElement).toBe(box())
    box().blur()
    const area = document.createElement('textarea')
    document.body.append(area)
    area.focus()
    fireEvent.keyDown(area, { key: '/' })
    expect(document.activeElement).toBe(area)
    area.remove()
    const editable = document.createElement('div')
    editable.setAttribute('contenteditable', 'true')
    editable.tabIndex = 0
    document.body.append(editable)
    editable.focus()
    fireEvent.keyDown(editable, { key: '/' })
    expect(document.activeElement).toBe(editable)
    editable.remove()
  })

  it('leaves a modal dialog alone', async () => {
    await renderApp('/', routes)
    render(
      <Dialog title="Rename" isOpen onOpenChange={() => {}}>
        <button type="button">Cancel</button>
      </Dialog>,
    )
    const dialog = await screen.findByRole('dialog', { name: 'Rename' })
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
    // fireEvent says false when the page called preventDefault, as it does to take a / for the box
    let left = false
    act(() => {
      left = fireEvent.keyDown(document.activeElement ?? dialog, { key: '/' })
    })
    expect(left).toBe(true)
    // The dialog hides the page from assistive technology, so the box is found by its markup
    expect(document.activeElement).not.toBe(document.querySelector('input[aria-label="Search"]'))
    expect(dialog.contains(document.activeElement)).toBe(true)
  })
})

describe('the search box', () => {
  it('has a result for a document opened from a suggestion', async () => {
    const { router } = await renderApp('/', { ...routes, 'GET /v1/lookup': { docNumber: 10392, version: '1.0' }, 'GET /v1/documents/10392': doc({ docNumber: 10392 }) })
    const user = userEvent.setup()
    await user.type(box(), 'charter')
    await screen.findByRole('option', { name: /Charterparty/ })
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => expect(where(router)).toBe('/app/doc/10392'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Document # 10392' })).toBeTruthy()
  })
})
