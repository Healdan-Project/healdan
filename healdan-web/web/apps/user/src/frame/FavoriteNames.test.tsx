// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { client, document as doc, favorite, folder, page, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

// A favorite's own name (changed after M6: the owner's dogfooding punch list, 2026-11-14): My Favorites shows it, its
// tooltip says what it really is and where, and F2 or its menu's Rename… changes it in place.

const harbour = { id: 14, display: '1001.4 – Harbour Lease', client: client() }
const documents = folder({ id: 130, name: 'Documents', matter: harbour })
const capTable = doc({ docNumber: 10500, name: 'Cap Table.xlsx' })

const pane = () => screen.getByRole('region', { name: 'My Favorites' })
const link = (name: string | RegExp) => within(pane()).findByRole('link', { name })
const field = (name: string) => screen.findByRole('textbox', { name: `Name of Favorite ${name}` })
const menu = () => screen.findByRole('menu')
const names = async () => within(await menu()).getAllByRole('menuitem').map((one) => one.textContent)
const item = async (name: string) => within(await menu()).findByRole('menuitem', { name })

/**
 * My Favorites holds the Documents folder of matter 1001.4 under a name of its own, and Cap Table.xlsx under its own
 * name. A PATCH records its body and answers with the favorite renamed; the list then answers with the new name.
 */
function favoriteRoutes(over: Routes = {}) {
  const list: S['FavoriteView'][] = [favorite(documents, 902, 'Harbour Lease Docs'), favorite(capTable, 901)]
  const sent: { id: number; name: unknown }[] = []
  const routes: Routes = {
    'GET /v1/me/favorites': () => page([...list]),
    'PATCH /v1/me/favorites/*': ({ params, body }: { params: string[]; body?: unknown }) => {
      const id = Number(params[0])
      const name = (body as { name: string | null }).name
      sent.push({ id, name })
      const at = list.findIndex((one) => one.id === id)
      list[at] = { ...list[at]!, name: name ?? list[at]!.item.name }
      return list[at]
    },
    ...over,
  }
  return { routes, sent }
}

describe('My Favorites', () => {
  it("shows each favorite's own name, in the server's order, with what it really is and where as its tooltip", async () => {
    const { routes } = favoriteRoutes()
    await renderApp('/folders/1', routes)

    const folderLink = await link('Harbour Lease Docs')
    expect(folderLink.getAttribute('href')).toBe('/app/folders/130')
    expect(folderLink.getAttribute('title')).toBe('Documents · 1001.4 – Harbour Lease')
    const docLink = await link('Cap Table.xlsx')
    expect(docLink.getAttribute('title')).toBe('Cap Table.xlsx · 1001.1 – Charter Dispute')
    expect(within(pane()).queryByRole('link', { name: 'Documents' })).toBeNull()

    // As the server sorts them: the folder first, though its name comes after the document's
    expect(within(pane()).getAllByRole('link').map((one) => one.textContent)).toEqual(['Harbour Lease Docs', 'Cap Table.xlsx'])
  })

  it('F2 on a favorite makes its name a field with the name selected; Enter saves it, and the list asks again', async () => {
    const { routes, sent } = favoriteRoutes()
    const { api } = await renderApp('/folders/1', routes)
    ;(await link('Cap Table.xlsx')).focus()
    await userEvent.keyboard('{F2}')

    const input = (await field('Cap Table.xlsx')) as HTMLInputElement
    expect(document.activeElement).toBe(input)
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, 'Cap Table.xlsx'.length])
    const asked = api.callsTo('GET /v1/me/favorites').length

    await userEvent.keyboard('Acme Cap Table{Enter}')

    await waitFor(() => expect(sent).toEqual([{ id: 901, name: 'Acme Cap Table' }]))
    const renamed = await link('Acme Cap Table')
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(api.callsTo('GET /v1/me/favorites').length).toBeGreaterThan(asked)
    await waitFor(() => expect(document.activeElement).toBe(renamed))
  })

  it('Esc cancels without a call, and the favorite has the focus again', async () => {
    const { routes, sent } = favoriteRoutes()
    await renderApp('/folders/1', routes)
    ;(await link('Harbour Lease Docs')).focus()
    await userEvent.keyboard('{F2}')
    await field('Harbour Lease Docs')

    await userEvent.keyboard('Something Else{Escape}')

    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(sent).toEqual([])
    await waitFor(() => expect(document.activeElement?.textContent).toBe('Harbour Lease Docs'))
  })

  it("a blank name puts back the item's own, and the same name makes no call", async () => {
    const { routes, sent } = favoriteRoutes()
    await renderApp('/folders/1', routes)
    ;(await link('Harbour Lease Docs')).focus()
    await userEvent.keyboard('{F2}')
    await field('Harbour Lease Docs')
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(sent).toEqual([])

    ;(await link('Harbour Lease Docs')).focus()
    await userEvent.keyboard('{F2}')
    await userEvent.clear(await field('Harbour Lease Docs'))
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(sent).toEqual([{ id: 902, name: null }]))
    expect(await link('Documents')).toBeTruthy()
  })

  it("keeps the field open with a refusal under it, in the catalogue's words", async () => {
    const detail = 'A server sentence that must not be shown raw.'
    const tooLong = new ApiError(422, 'validation', detail, { status: 422, code: 'validation', detail, fields: [{ field: 'name', code: 'too-long', detail }] })
    const { routes } = favoriteRoutes({ 'PATCH /v1/me/favorites/*': tooLong })
    await renderApp('/folders/1', routes)
    ;(await link('Cap Table.xlsx')).focus()
    await userEvent.keyboard('{F2}')
    await userEvent.keyboard('x{Enter}')

    expect((await screen.findByRole('alert')).textContent).toBe("A favorite's name can't be longer than 255 characters.")
    const input = await field('Cap Table.xlsx')
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(document.body.textContent).not.toContain('server sentence')
    await expectNoAxeViolations(pane())
  })

  it('the menu offers Rename…, which changes the name in place', async () => {
    const { routes, sent } = favoriteRoutes()
    await renderApp('/folders/1', routes)
    fireEvent.contextMenu(await link('Cap Table.xlsx'))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename…', 'Remove from Favorites'])

    await userEvent.click(await item('Rename…'))
    const input = await field('Cap Table.xlsx')
    await waitFor(() => expect(document.activeElement).toBe(input))
    await userEvent.keyboard('Acme Cap Table{Enter}')

    await waitFor(() => expect(sent).toEqual([{ id: 901, name: 'Acme Cap Table' }]))
    expect(await link('Acme Cap Table')).toBeTruthy()
  })

  it('the menu offers Use Original Name only for a favorite with a name of its own', async () => {
    const { routes, sent } = favoriteRoutes()
    await renderApp('/folders/1', routes)
    fireEvent.contextMenu(await link('Harbour Lease Docs'))
    expect(await screen.findByRole('menu', { name: 'Actions for Harbour Lease Docs' })).toBeTruthy()
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename…', 'Use Original Name', 'Remove from Favorites'])

    await userEvent.click(await item('Use Original Name'))

    await waitFor(() => expect(sent).toEqual([{ id: 902, name: null }]))
    fireEvent.contextMenu(await link('Documents'))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename…', 'Remove from Favorites'])
  })
})
