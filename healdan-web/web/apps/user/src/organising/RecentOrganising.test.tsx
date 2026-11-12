// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { detailsRoutes } from '../test/details'
import { page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { lease, letter, plan, routes } from './testing'

// Recent's lists and the # result are lists of documents like any other (R95): F2, Delete and More work there too
function recentRoutes() {
  const { all, written } = routes()
  let recent = [letter, lease, plan]
  return {
    written,
    all: {
      ...all,
      ...detailsRoutes(lease),
      'GET /v1/documents/recent': () => {
        recent = recent.filter((one) => !written.deleted.includes(one.docNumber))
        return page(recent.map((one) => ({ ...one, name: written.renamed.find((r) => r.docNumber === one.docNumber)?.name ?? one.name })))
      },
      'GET /v1/lookup': () => ({ docNumber: 10432, version: '1.0' }),
    },
  }
}

const hit = async (name: RegExp) => within(await within(screen.getByRole('main')).findByRole('grid')).findByRole('row', { name })
const select = async (name: RegExp) => userEvent.click(within(await hit(name)).getByRole('rowheader'))

describe('Organising in Recent', () => {
  it.each(['/', '/recent/emails'])('renames with F2 at %s', async (path) => {
    const { all, written } = recentRoutes()
    await renderApp(path, all)
    await select(/Lease/)
    await userEvent.keyboard('{F2}')
    await userEvent.keyboard('Lease Two{Enter}')
    await waitFor(() => expect(written.renamed).toEqual([{ docNumber: 10432, name: 'Lease Two.docx' }]))
  })

  it('deletes with the Delete key, after the question', async () => {
    const { all, written } = recentRoutes()
    await renderApp('/', all)
    await select(/Plan/)
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByRole('dialog', { name: 'Move 1 document to the Trash?' })).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(written.deleted).toEqual([10433]))
  })

  it('has the toolbar, whose More moves the selection', async () => {
    const { all } = recentRoutes()
    await renderApp('/recent/documents', all)
    await select(/Lease/)
    const toolbar = await screen.findByRole('toolbar', { name: 'Recent Actions' })
    for (const name of ['Open', 'Download']) expect(within(toolbar).getByRole('button', { name })).toBeTruthy()
    await userEvent.click(within(toolbar).getByRole('button', { name: 'More' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Move To…' }))
    expect(await screen.findByRole('dialog', { name: 'Move 1 Document' })).toBeTruthy()
  })
})

describe('Organising the # result', () => {
  it('renames with F2', async () => {
    const { all, written } = recentRoutes()
    await renderApp('/doc/10432', all)
    await userEvent.click(within(await hit(/Lease/)).getByRole('rowheader'))
    await userEvent.keyboard('{F2}')
    await userEvent.keyboard('Lease Two{Enter}')
    await waitFor(() => expect(written.renamed).toEqual([{ docNumber: 10432, name: 'Lease Two.docx' }]))
  })

  it('deletes with the Delete key, and has More in its toolbar', async () => {
    const { all, written } = recentRoutes()
    await renderApp('/doc/10432', all)
    const toolbar = await screen.findByRole('toolbar', { name: 'Document Actions' })
    expect(within(toolbar).getByRole('button', { name: 'More' })).toBeTruthy()
    await userEvent.click(within(await hit(/Lease/)).getByRole('rowheader'))
    await userEvent.keyboard('{Delete}')
    await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(written.deleted).toEqual([10432]))
  })
})
