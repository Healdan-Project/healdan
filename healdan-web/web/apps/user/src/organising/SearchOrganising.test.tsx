// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { detailsRoutes } from '../test/details'
import { renderApp } from '../test/renderApp'
import { lease, letter, plan, routes } from './testing'

// The same organising works in a list of search results: they are rows of the same Table with the same toolbar
function searchRoutes() {
  const { all, written } = routes()
  return {
    written,
    all: {
      ...all,
      ...detailsRoutes(letter),
      'GET /v1/search': { items: [letter, lease, plan], offset: 0, limit: 100, total: 3 },
    },
  }
}

const hit = async (name: RegExp) => within(await within(screen.getByRole('main')).findByRole('grid')).findByRole('row', { name })
const select = async (name: RegExp) => userEvent.click(within(await hit(name)).getByRole('rowheader'))

describe('Organising in search results', () => {
  it('renames with F2', async () => {
    const { all, written } = searchRoutes()
    await renderApp('/search?scope=documents&q=lease', all)
    await select(/Lease/)
    await userEvent.keyboard('{F2}')
    await userEvent.keyboard('Lease Two{Enter}')
    await waitFor(() => expect(written.renamed).toEqual([{ docNumber: 10432, name: 'Lease Two.docx' }]))
  })

  it('deletes with the Delete key, after the question', async () => {
    const { all, written } = searchRoutes()
    await renderApp('/search?scope=documents&q=lease', all)
    await select(/Plan/)
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByRole('dialog', { name: 'Move 1 document to the Trash?' })).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(written.deleted).toEqual([10433]))
  })

  it('has More, whose Move To… opens the picker', async () => {
    const { all } = searchRoutes()
    await renderApp('/search?scope=documents&q=lease', all)
    await select(/Lease/)
    await userEvent.click(within(await screen.findByRole('toolbar')).getByRole('button', { name: 'More' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Move To…' }))
    expect(await screen.findByRole('dialog', { name: 'Move 1 Document' })).toBeTruthy()
  })
})
