// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { resetAnnouncer } from '@healdan/ui'
import { routes, row, select } from '../organising/testing'
import { expectNoAxeViolations } from '../test/axe'
import { me, problem, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

// Reindex, on an administrator's right-click menus (admin spec 13, P6-R20): the question names what the server says the
// scope is and how many documents it would try (POST /v1/admin/reindex/preview), and its button starts the reindex
// (POST /v1/admin/reindex), which a toast says, with View Progress opening the Admin app's Search Index in a new tab.
// Who's offered it is ContextMenus.test.tsx's.

type A = adminComponents['schemas']

function preview(over: Partial<A['ReindexPreview']> = {}): A['ReindexPreview'] {
  return { name: '1 Document', count: 1, skipped: 0, estimateSeconds: 2, documents: null, refusal: null, ...over }
}

function job(over: Partial<A['ReindexJob']> = {}): A['ReindexJob'] {
  return {
    id: 7,
    kind: 'documents',
    targetId: null,
    name: '1 Document',
    startedBy: { id: 5, username: 'priya', name: 'Priya Nair' },
    state: 'waiting',
    cancelling: false,
    emptyFirst: false,
    total: 1,
    done: 0,
    failed: 0,
    skipped: 0,
    unreachable: 0,
    leftOut: 0,
    created: '2026-11-23T10:00:00Z',
    started: null,
    ended: null,
    stalledSince: null,
    failingSince: null,
    estimateSecondsLeft: 2,
    ...over,
  }
}

interface Asked {
  previews: unknown[]
  starts: unknown[]
}

/** Folder 7's routes for an administrator, with the two reindex routes answering `previewed` and `started`, and what each was sent */
function adminRoutes(previewed: unknown = preview(), started: unknown = job(), over: Routes = {}) {
  const asked: Asked = { previews: [], starts: [] }
  const answer = (value: unknown, body: unknown) => (typeof value === 'function' ? (value as (body: unknown) => unknown)(body) : value)
  const all: Routes = {
    ...routes().all,
    'GET /v1/me': { ...me, admin: true },
    'POST /v1/admin/reindex/preview': ({ body }: { body?: unknown }) => {
      asked.previews.push(body)
      return answer(previewed, body)
    },
    'POST /v1/admin/reindex': ({ body }: { body?: unknown }) => {
      asked.starts.push(body)
      return answer(started, body)
    },
    ...over,
  }
  return { all, asked }
}

const menu = () => screen.findByRole('menu')
const item = async (name: string) => within(await menu()).findByRole('menuitem', { name })
const question = () => screen.findByRole('dialog', { name: 'Reindex' })
const toastOf = async (text: RegExp) => (await screen.findByText(text)).closest('[role="alertdialog"]') as HTMLElement

/**
 * Opens the target's menu where another has just closed: it waits up to 5 seconds for it, and fires the right-click again
 * while none has opened, as one that lands while the menu or question before it is still closing can be lost on a slow
 * machine (as in ContextMenus.test.tsx)
 */
async function openMenu(target: () => Promise<HTMLElement> | HTMLElement) {
  await waitFor(
    async () => {
      if (!screen.queryByRole('menu')) fireEvent.contextMenu(await target())
      expect(screen.getByRole('menu')).toBeTruthy()
    },
    { timeout: 5000 },
  )
}

/** Opens the row's menu and picks Reindex */
async function reindexRow(name: RegExp) {
  fireEvent.contextMenu(await row(name))
  await userEvent.click(await item('Reindex'))
  return question()
}

describe('the question', () => {
  it('says it’s counting while the preview is on its way, then names what it covers and how many documents', async () => {
    let answer: (value: unknown) => void = () => {}
    const { all, asked } = adminRoutes(() => new Promise((resolve) => (answer = resolve)))
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Lease/)
    expect(within(dialog).getByText('Counting the documents…')).toBeTruthy()
    // Its button has the focus, and is pending, so Enter starts nothing yet
    const pending = within(dialog).getByRole('button', { name: /Reindex/ })
    await waitFor(() => expect(document.activeElement).toBe(pending))
    expect(pending.getAttribute('aria-disabled')).toBe('true')
    expect(asked.previews).toEqual([{ kind: 'documents', docNumbers: [10432] }])

    answer(preview({ name: '1 Document', count: 1 }))
    expect(await within(dialog).findByText('1 Document')).toBeTruthy()
    expect(within(dialog).getByText('1 document will be indexed again. Search keeps finding it meanwhile.')).toBeTruthy()
    const confirm = within(dialog).getByRole('button', { name: 'Reindex 1 Document' })
    expect(document.activeElement).toBe(confirm)
    expect(asked.starts).toEqual([])
    await expectNoAxeViolations(document.body)
  })

  it('counts a selection of documents by their doc #s, and a large count as US English groups it', async () => {
    const { all, asked } = adminRoutes(preview({ name: '2 Documents', count: 2 }))
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    fireEvent.contextMenu(await row(/Plan/))
    await userEvent.click(await item('Reindex'))
    const dialog = await question()
    expect(await within(dialog).findByRole('button', { name: 'Reindex 2 Documents' })).toBeTruthy()
    expect(asked.previews).toEqual([{ kind: 'documents', docNumbers: [10432, 10433] }])
  })

  it('asks for a folder by its id, and for a matter by the matter’s own id, not its folder’s', async () => {
    const { all, asked } = adminRoutes(preview({ name: 'Matter 1001.1 Charter Dispute', count: 1412 }))
    await renderApp('/folders/7', all)
    let dialog = await reindexRow(/Drafts/)
    expect(await within(dialog).findByText('1,412 documents will be indexed again. Search keeps finding them meanwhile.')).toBeTruthy()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    await openMenu(() => within(tree).findByRole('row', { name: /1001\.1/ }))
    await userEvent.click(await item('Reindex'))
    dialog = await question()
    expect(await within(dialog).findByText('Matter 1001.1 Charter Dispute')).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Reindex 1,412 Documents' })).toBeTruthy()
    expect(asked.previews).toEqual([
      { kind: 'folder', targetId: 8 },
      { kind: 'matter', targetId: 11 },
    ])
    expect(asked.starts).toEqual([])
  })

  it('shows the preview’s refusal in place of the count, with only Cancel, which takes the focus', async () => {
    const { all, asked } = adminRoutes(preview({ name: 'Folder Drafts', count: 4, refusal: 'This is already being reindexed.' }))
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Drafts/)
    expect(await within(dialog).findByText('This is already being reindexed.')).toBeTruthy()
    expect(within(dialog).queryByText(/will be indexed again/)).toBeNull()
    expect(within(dialog).getAllByRole('button').map((one) => one.textContent)).toEqual(['Cancel'])
    await waitFor(() => expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' })))
    await expectNoAxeViolations(document.body)
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(asked.starts).toEqual([])
  })

  it('says a preview that failed as the catalogue does, never the server’s own words', async () => {
    const { all } = adminRoutes(problem(404, 'not-found'))
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Drafts/)
    expect(await within(dialog).findByText("Not found, or you can't see it.")).toBeTruthy()
    expect(within(dialog).queryByText(/must not be shown raw/)).toBeNull()
    expect(within(dialog).getAllByRole('button').map((one) => one.textContent)).toEqual(['Cancel'])
  })

  it('says a selection over the server’s limit in its own words', async () => {
    const tooMany = problem(422, 'validation')
    Object.assign(tooMany, { body: { status: 422, code: 'validation', fields: [{ field: 'docNumbers', code: 'too-many' }] } })
    const { all } = adminRoutes(tooMany)
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Lease/)
    expect(await within(dialog).findByText('Reindex at most 1,000 documents at a time.')).toBeTruthy()
  })

  it('starts nothing on Cancel or Esc, and gives the row the focus back', async () => {
    const { all, asked } = adminRoutes()
    await renderApp('/folders/7', all)
    const target = await row(/Lease/)
    target.focus()
    await userEvent.keyboard('{Shift>}{F10}{/Shift}')
    await waitFor(async () => expect(document.activeElement).toBe(await item('Open')))
    await userEvent.keyboard('{End}{Enter}')
    const dialog = await question()
    await within(dialog).findByRole('button', { name: 'Reindex 1 Document' })
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(target))
    expect(asked.starts).toEqual([])
  })
})

describe('the start', () => {
  it('sends the scope, then says so in a toast whose View Progress opens the Search Index in a new tab, by keys alone', async () => {
    const { all, asked } = adminRoutes(preview({ name: 'Folder Drafts', count: 12 }), job({ kind: 'folder', targetId: 8, name: 'Folder Drafts', total: 12 }))
    await renderApp('/folders/7', all)
    const target = await row(/Drafts/)
    target.focus()
    await userEvent.keyboard('{Shift>}{F10}{/Shift}')
    await waitFor(async () => expect(document.activeElement).toBe(await item('Open')))
    // Reindex is the menu's last item
    await userEvent.keyboard('{End}')
    expect(document.activeElement).toBe(await item('Reindex'))
    await userEvent.keyboard('{Enter}')
    const dialog = await question()
    await waitFor(() => expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Reindex 12 Documents' })))
    await userEvent.keyboard('{Enter}')

    const toast = await toastOf(/Reindexing 12 documents\./)
    expect(asked.starts).toEqual([{ kind: 'folder', targetId: 8 }])
    const progress = within(toast).getByRole('link', { name: 'View Progress' })
    expect(progress.getAttribute('href')).toBe('/admin/search-index')
    expect(progress.getAttribute('target')).toBe('_blank')
    expect(progress.getAttribute('rel')).toContain('noopener')
    expect(screen.queryByRole('dialog')).toBeNull()
    // The person stays where they were
    await waitFor(() => expect(document.activeElement).toBe(target))
    // React Aria announced the button as it went pending, naming it by aria-labelledby, which outlives the closed question
    // for its 7 seconds (as the Admin app's browser specs wait out): its own region, not the page's
    resetAnnouncer()
    await expectNoAxeViolations(document.body)
  })

  it('says one document in the singular', async () => {
    const { all } = adminRoutes()
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Lease/)
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Reindex 1 Document' }))
    expect(await toastOf(/Reindexing 1 document\./)).toBeTruthy()
  })

  it('says a refusal in a toast: a 409’s sentence, which is the rule’s own', async () => {
    const { all } = adminRoutes(preview(), problem(409, 'refused', "There's nothing here to reindex."))
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Lease/)
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Reindex 1 Document' }))
    expect(await screen.findByText("There's nothing here to reindex.")).toBeTruthy()
    expect(screen.queryByText(/Reindexing/)).toBeNull()
  })

  it('says any other failure as the catalogue does, never the server’s own words', async () => {
    const { all } = adminRoutes(preview(), problem(500, 'server-error'))
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Lease/)
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Reindex 1 Document' }))
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByText(/must not be shown raw/)).toBeNull()
  })

  it('shows the signed-out page on a 401, as every other call does', async () => {
    const { all } = adminRoutes(preview(), problem(401, 'unauthenticated'))
    await renderApp('/folders/7', all)
    const dialog = await reindexRow(/Lease/)
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Reindex 1 Document' }))
    expect(await screen.findByText("You've been signed out.")).toBeTruthy()
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('counts afresh each time it’s asked', async () => {
    let count = 3
    const { all, asked } = adminRoutes(() => preview({ name: 'Folder Drafts', count: count++ }))
    await renderApp('/folders/7', all)
    let dialog = await reindexRow(/Drafts/)
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Reindex 3 Documents' }))
    await toastOf(/Reindexing 1 document\./)
    await openMenu(() => row(/Drafts/))
    await userEvent.click(await item('Reindex'))
    dialog = await question()
    expect(await within(dialog).findByRole('button', { name: 'Reindex 4 Documents' })).toBeTruthy()
    expect(asked.previews).toHaveLength(2)
  })
})
