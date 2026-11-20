// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { formatDateTime, formatSize } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { firmStore, helen, matterPage, matterRow, matterRows, problem, workspaceRoutes, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']

const main = () => screen.getByRole('main')
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const TITLE = '1001.1 Charter Dispute – MV Northern Star'
const LABEL = '1001.1 – Charter Dispute – MV Northern Star'

/** Matter 11's page, once its title has come */
async function openMatter(path = '/matters/11', routes: Routes = {}) {
  const rendered = await renderApp(path, { ...workspaceRoutes(), ...routes })
  await screen.findByRole('heading', { level: 1, name: TITLE })
  return rendered
}

/** The Overview's facts, each term with what it says */
const facts = () => within(main()).getAllByRole('term').map((term) => [term.textContent, term.nextElementSibling?.textContent])

/** A `422 validation` naming one field */
const fieldProblem = (field: string, code: string) => new ApiError(422, 'validation', '', { status: 422, code: 'validation', fields: [{ field, code }] })

/** A request that waits until the test lets it answer */
function held<T>() {
  let answer!: (value: T) => void
  const promise = new Promise<T>((resolve) => (answer = resolve))
  return { promise, answer }
}

describe("A matter's page", () => {
  it('shows its title, the line under it, its actions and its tabs, Overview first, with the way back to Matters, which stays the open screen', async () => {
    const { view } = await openMatter()
    expect(within(main()).getByText(`Saltmarsh Shipping Ltd · Client Work · owner Tom Reeve · from template Litigation · 412 documents, ${formatSize(matterRows[2]!.bytes!)}`)).toBeTruthy()
    for (const name of ['Edit', 'Move to Another Store', 'Delete']) expect(within(main()).getByRole('button', { name })).toBeTruthy()
    // The User app's folder of the matter
    expect(within(main()).getByRole('link', { name: 'Open in Healdan' }).getAttribute('href')).toBe('/app/folders/110')
    const crumbs = within(main()).getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Matters' }).getAttribute('href')).toBe('/admin/matters')
    expect(within(pane()).getByRole('link', { name: 'Matters' }).getAttribute('aria-current')).toBe('page')

    const tabs = within(main()).getByRole('tablist', { name: 'Sections' })
    expect(within(tabs).getAllByRole('tab').map((tab) => [tab.textContent, tab.getAttribute('aria-selected')])).toEqual([
      ['Overview', 'true'],
      ['Workspace', 'false'],
    ])
    // The Overview: the description, the facts and what's stuck or failing
    const overview = within(main()).getByRole('tabpanel', { name: 'Overview' })
    expect(within(overview).getByText('Charter party dispute over the MV Northern Star.')).toBeTruthy()
    expect(facts()).toEqual([
      ['Client', '1001 – Saltmarsh Shipping Ltd'],
      ['Store', 'Client Work'],
      ['Owner', 'Tom Reeve'],
      ['Template', 'Litigation'],
      ['Documents', '412'],
      ['Size', formatSize(matterRows[2]!.bytes!)],
      ['Last Activity', formatDateTime('2026-11-19T10:12:00Z')],
    ])
    expect(within(overview).getByText(`Documents, Size and Last Activity as of ${formatDateTime('2026-11-19T10:41:00Z')}`)).toBeTruthy()
    expect(within(overview).getByRole('link', { name: '1001 – Saltmarsh Shipping Ltd' }).getAttribute('href')).toBe('/admin/clients/1')
    expect(within(overview).getByText('Nothing in this matter is stuck or failing.')).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("says what's stuck or failing in it", async () => {
    const { view } = await openMatter('/matters/11', { 'GET /v1/admin/matters/11': matterPage(matterRow(), { indexFailed: 2, previewsGivenUp: 1 }) })
    const overview = within(main()).getByRole('tabpanel', { name: 'Overview' })
    expect(within(overview).getByText("2 documents couldn't be indexed, so search won't find their text")).toBeTruthy()
    expect(within(overview).getByText('1 preview was given up on')).toBeTruthy()
    expect(within(overview).queryByText('Nothing in this matter is stuck or failing.')).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it("says so of an owner whose account has gone, a matter made from no template and figures not worked out yet", async () => {
    await openMatter('/matters/11', {
      'GET /v1/admin/matters/11': matterPage(matterRow({ owner: null, template: null, documents: null, bytes: null, lastActivity: null }), { description: null, figuresAt: null }),
    })
    expect(within(main()).queryByText(/as of/)).toBeNull()
    expect(within(main()).getByText('Saltmarsh Shipping Ltd · Client Work · no owner')).toBeTruthy()
    expect(within(main()).getByText('No description.')).toBeTruthy()
    expect(facts().slice(2)).toEqual([
      ['Owner', "None: the owner's account has been deleted"],
      ['Template', 'None'],
      ['Documents', 'Not worked out yet'],
      ['Size', 'Not worked out yet'],
      ['Last Activity', 'Not worked out yet'],
    ])
  })

  it('keeps its tab in the address, and opens at the tab the address names', async () => {
    const { router, view } = await openMatter()
    await userEvent.click(within(main()).getByRole('tab', { name: 'Workspace' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'workspace' }))
    expect(await within(main()).findByRole('treegrid', { name: `Folders of ${LABEL}` })).toBeTruthy()
    await userEvent.click(within(main()).getByRole('tab', { name: 'Overview' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    expect(router.state.location.pathname).toBe('/matters/11')
    view.unmount()

    await openMatter('/matters/11?tab=workspace')
    expect(within(main()).getByRole('tab', { name: 'Workspace' }).getAttribute('aria-selected')).toBe('true')
    expect(await within(main()).findByRole('treegrid', { name: `Folders of ${LABEL}` })).toBeTruthy()
  })

  it("opens at the Overview for a tab it doesn't know", async () => {
    await openMatter('/matters/11?tab=activity')
    expect(within(main()).getByRole('tab', { name: 'Overview' }).getAttribute('aria-selected')).toBe('true')
  })

  it("starts afresh on another matter's page, by Back too, with no dialog carried from the one before", async () => {
    const { router } = await openMatter('/matters/11', { 'GET /v1/admin/matters/12': matterPage(matterRow({ id: 12, number: '1001.2', name: 'Crew Wages', folderId: 120 })) })
    await act(() => router.navigate({ href: '/matters/12' }))
    await screen.findByRole('heading', { level: 1, name: '1001.2 Crew Wages' })
    await userEvent.click(within(main()).getByRole('button', { name: 'Edit' }))
    expect(await screen.findByRole('dialog', { name: 'Edit 1001.2' })).toBeTruthy()
    // Back to the matter before, whose answer is at hand
    await act(async () => router.history.back())
    await waitFor(() => expect(router.state.location.pathname).toBe('/matters/11'))
    expect(screen.queryAllByRole('dialog').map((one) => one.getAttribute('aria-label') ?? one.textContent?.slice(0, 20))).toEqual([])
    expect(await screen.findByRole('heading', { level: 1, name: TITLE })).toBeTruthy()
  })

  it("says there's no such matter, for an id that isn't one and one the server doesn't have", async () => {
    const first = await renderApp('/matters/99', workspaceRoutes())
    expect(await screen.findByRole('heading', { level: 1, name: 'Matter Not Found' })).toBeTruthy()
    expect(within(main()).getByRole('link', { name: 'Go to Matters' }).getAttribute('href')).toBe('/admin/matters')
    await expectNoAxeViolations(first.view.container)
    first.view.unmount()
    const { api } = await renderApp('/matters/abc', workspaceRoutes())
    expect(await screen.findByRole('heading', { level: 1, name: 'Matter Not Found' })).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/matters/*')).toEqual([])
  })
})

describe('Edit', () => {
  it('changes the name and the description, sending only what changed, and the page shows the answer', async () => {
    let saved: A['Matter'] = matterPage()
    const { api, view } = await openMatter('/matters/11', {
      'GET /v1/admin/matters/11': () => saved,
      'PATCH /v1/admin/matters/11': ({ body }: { body?: unknown }) => (saved = { ...saved, ...(body as A['MatterChange']) } as A['Matter']),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit 1001.1' })
    expect(within(dialog).getByText("Its client, number and template never change, and Move to Another Store changes its Store.")).toBeTruthy()
    const name = within(dialog).getByRole('textbox', { name: /Name/ }) as HTMLInputElement
    expect(name.value).toBe('Charter Dispute – MV Northern Star')
    // Nothing changed yet
    expect(within(dialog).getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true)
    await expectNoAxeViolations(dialog)
    await userEvent.clear(name)
    await userEvent.type(name, 'Charter Dispute')
    const description = within(dialog).getByRole('textbox', { name: 'Description' })
    await userEvent.clear(description)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('PATCH /v1/admin/matters/11').map((call) => call.body)).toEqual([{ name: 'Charter Dispute', description: null }])
    expect(await screen.findByRole('heading', { level: 1, name: '1001.1 Charter Dispute' })).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('changes the owner, found by name among those who can sign in', async () => {
    const { api } = await openMatter('/matters/11', { 'PATCH /v1/admin/matters/11': matterPage(matterRow({ owner: helen })) })
    await userEvent.click(within(main()).getByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit 1001.1' })
    const owner = within(dialog).getByRole('combobox', { name: /Owner/ })
    await userEvent.clear(owner)
    await userEvent.type(owner, 'hel')
    await waitFor(() => expect(api.callsTo('GET /v1/users').at(-1)?.query).toEqual({ q: 'hel' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Helen Ashcombe helen' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/matters/11').map((call) => call.body)).toEqual([{ ownerId: 5 }]))
    expect(await within(main()).findByText(/owner Helen Ashcombe/)).toBeTruthy()
  })

  it("says what's wrong before Save: a name with a slash, and a description that's too long", async () => {
    await openMatter()
    await userEvent.click(within(main()).getByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit 1001.1' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), '/B')
    expect(within(dialog).getByText("A name can't have / or \\ in it.")).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true)
  })

  it("shows the server's refusal in the dialog and a field it names beside the field, and can't be cancelled while it saves", async () => {
    const answer = held<unknown>()
    let refusal: unknown = problem(409, 'refused', "Helen Ashcombe can't read the Store Client Work.")
    const { api } = await openMatter('/matters/11', {
      'PATCH /v1/admin/matters/11': () => (refusal === undefined ? answer.promise : refusal),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit 1001.1' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), ' 2')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe("Helen Ashcombe can't read the Store Client Work.")
    await expectNoAxeViolations(dialog)

    refusal = fieldProblem('name', 'too-long')
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), '3')
    expect(within(dialog).queryByRole('alert')).toBeNull()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByText('That is too long.')).toBeTruthy()

    // While the change is on its way, Cancel and Esc do nothing
    refusal = undefined
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), '4')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/matters/11')).toHaveLength(3))
    expect(within(dialog).getByRole('button', { name: 'Cancel' }).hasAttribute('disabled')).toBe(true)
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Edit 1001.1' })).toBeTruthy()
    answer.answer(matterPage(matterRow({ name: 'Charter Dispute – MV Northern Star 234' })))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})

describe('Move to Another Store', () => {
  it('moves the matter to the Store chosen, saying its files follow in the background', async () => {
    let saved = matterPage()
    const { api } = await openMatter('/matters/11', {
      'GET /v1/admin/matters/11': () => saved,
      'POST /v1/admin/matters/11/move': () => (saved = matterPage(matterRow({ store: firmStore }))),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Move to Another Store' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move 1001.1 to Another Store' })
    expect(within(dialog).getByText(/Their files follow to the new Store's storage in the background/)).toBeTruthy()
    const store = within(dialog).getByRole('combobox', { name: /Store/ })
    expect(within(store).getAllByRole('option').map((option) => option.textContent)).toEqual(['Client Work (Where It Is Now)', 'Firm'])
    // Where it is now moves nothing
    await userEvent.selectOptions(store, '4')
    expect(within(dialog).getByText("It's in that Store already.")).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Move' }).hasAttribute('disabled')).toBe(true)
    await expectNoAxeViolations(dialog)
    await userEvent.selectOptions(store, '3')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('POST /v1/admin/matters/11/move').map((call) => call.body)).toEqual([{ storeId: 3 }])
    expect(await screen.findByText(`Moved ${LABEL} to Firm`)).toBeTruthy()
    expect(await within(main()).findByText(/Saltmarsh Shipping Ltd · Firm ·/)).toBeTruthy()
  })

  it("shows the rule's refusal in the dialog, and stays", async () => {
    await openMatter('/matters/11', {
      'POST /v1/admin/matters/11/move': problem(409, 'refused', 'A folder in Firm is already called 1001.1 – Charter Dispute – MV Northern Star.'),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Move to Another Store' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move 1001.1 to Another Store' })
    await userEvent.selectOptions(within(dialog).getByRole('combobox', { name: /Store/ }), '3')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('A folder in Firm is already called 1001.1 – Charter Dispute – MV Northern Star.')
    await expectNoAxeViolations(dialog)
  })
})

describe('Delete', () => {
  it('asks first, naming what the matter holds and that its number stays taken, then goes to the Matters list', async () => {
    const { api, router } = await openMatter('/matters/11', {
      'DELETE /v1/admin/matters/11': { trashItemId: 77, folders: 8, documents: 412 } satisfies A['DeletedMatter'],
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Move 1001.1 to the Trash?' })
    // What it holds, counted live
    expect(await within(dialog).findByText(`${LABEL} goes to the Trash with everything in it: 8 folders and 412 documents.`)).toBeTruthy()
    expect(api.callsTo('GET /v1/folders/110/contents')).toHaveLength(1)
    expect(within(dialog).getByText('Its number, 1001.1, stays taken, and an administrator can restore it from the Trash in Healdan.')).toBeTruthy()
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/matters'))
    expect(api.callsTo('DELETE /v1/admin/matters/11')).toHaveLength(1)
    expect(await screen.findByText(/Moved 1001\.1 – Charter Dispute – MV Northern Star to the/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Trash' }).getAttribute('href')).toBe('/app/trash')
  })

  it("shows the rule's refusal, such as a document checked out, and stays on the page", async () => {
    const { router } = await openMatter('/matters/11', {
      'DELETE /v1/admin/matters/11': problem(409, 'refused', 'A document in it is checked out: Witness Statement.docx.'),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Move 1001.1 to the Trash?' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('A document in it is checked out: Witness Statement.docx.')
    expect(router.state.location.pathname).toBe('/matters/11')
  })
})
