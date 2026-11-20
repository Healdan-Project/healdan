// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { deletedFolder, litigationAccess, problem, templateInfos, workspaceRoutes, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { permissionsOf } from '../workspace/levels'

type A = adminComponents['schemas']

const main = () => screen.getByRole('main')
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const grid = () => within(main()).findByRole('grid', { name: 'Workspace Templates' })
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
/** A toast, by its message: an alert dialog of the toast region */
const toastOf = async (text: string) => (await screen.findByText(text)).closest('[role="alertdialog"]') as HTMLElement

async function openList(routes: Routes = {}) {
  const rendered = await renderApp('/templates', { ...workspaceRoutes(), ...routes })
  const table = await grid()
  return { ...rendered, table }
}

/** The Litigation template's page, once its title and tree have come */
async function openTemplate(routes: Routes = {}) {
  const rendered = await renderApp('/templates/202', { ...workspaceRoutes(), ...routes })
  await screen.findByRole('heading', { level: 1, name: 'Litigation' })
  await within(main()).findByRole('treegrid', { name: 'Folders of Litigation' })
  return rendered
}

describe('Workspace Templates', () => {
  it('lists every template with its folders and the matters made from it, and is in the pane after Matters', async () => {
    const { table, view } = await openList()
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Name', 'Folders', 'Matters Made From It'])
    await within(table).findByRole('row', { name: /Litigation/ })
    expect(within(table).getAllByRole('row').slice(1).map(cellsOf)).toEqual([
      ['Firm', '4', '2'],
      ['Litigation', '5', '2'],
      ['Transactional', '6', '4'],
    ])
    const links = within(pane()).getAllByRole('link').map((link) => link.textContent)
    expect(links.slice(1, 4)).toEqual(['Clients', 'Matters', 'Workspace Templates'])
    expect(within(pane()).getByRole('link', { name: 'Workspace Templates' }).getAttribute('aria-current')).toBe('page')
    await expectNoAxeViolations(view.container)
  })

  it("explains on a fresh install that a template is needed before the first matter", async () => {
    const { view } = await openList({ 'GET /v1/admin/templates': [] })
    expect(await within(main()).findByText('No workspace template has been made yet.')).toBeTruthy()
    expect(within(main()).getByText('Each matter is made from a template, so make one before the first matter.')).toBeTruthy()
    expect(within(main()).getByRole('button', { name: 'New Template' })).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("opens a template's page from its row", async () => {
    const { router, table } = await openList()
    fireEvent.click(await within(table).findByRole('row', { name: /Litigation/ }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/templates/202'))
  })

  it('makes an empty template, and opens its page', async () => {
    const made: A['TemplateInfo'] = { id: 204, name: 'Probate', folders: 0, matters: 0 }
    const { api, router } = await openList({ 'POST /v1/admin/templates': made, 'GET /v1/admin/templates/204': made })
    await userEvent.click(within(main()).getByRole('button', { name: 'New Template' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Template' })
    const start = within(dialog).getByRole('combobox', { name: 'Start From' }) as HTMLSelectElement
    expect(within(start).getAllByRole('option').map((option) => option.textContent)).toEqual(['Nothing (Empty)', 'A Copy of Firm', 'A Copy of Litigation', 'A Copy of Transactional'])
    expect(start.value).toBe('')
    await expectNoAxeViolations(dialog)
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), ' Probate ')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Template' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/templates/204'))
    expect(api.callsTo('POST /v1/admin/templates').map((call) => call.body)).toEqual([{ name: 'Probate' }])
    expect(await screen.findByText('Created the template Probate')).toBeTruthy()
  })

  it("makes a copy of another, saying first that a name is a template's already", async () => {
    const made: A['TemplateInfo'] = { id: 205, name: 'Litigation (Arbitration)', folders: 5, matters: 0 }
    const { api } = await openList({ 'POST /v1/admin/templates': made, 'GET /v1/admin/templates/205': made })
    await userEvent.click(within(main()).getByRole('button', { name: 'New Template' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Template' })
    const name = within(dialog).getByRole('textbox', { name: /Name/ })
    await userEvent.type(name, 'litigation')
    expect(within(dialog).getByText("There's already a template called Litigation.")).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Create Template' }).hasAttribute('disabled')).toBe(true)
    await userEvent.type(name, ' (Arbitration)')
    await userEvent.selectOptions(within(dialog).getByRole('combobox', { name: 'Start From' }), '202')
    expect(within(dialog).getByText('A copy takes its folders, their permissions and values, and its search folders with their rules.')).toBeTruthy()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Template' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/templates').map((call) => call.body)).toEqual([{ name: 'litigation (Arbitration)', copyOf: 202 }]))
  })
})

describe("A template's page", () => {
  it("shows the template, says matters made from it don't change, lists its problems as warnings, and its workspace", async () => {
    const { view } = await openTemplate()
    const crumbs = within(main()).getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Workspace Templates' }).getAttribute('href')).toBe('/admin/templates')
    expect(within(main()).getAllByRole('term').map((term) => [term.textContent, term.nextElementSibling?.textContent])).toEqual([
      ['Folders', '5'],
      ['Matters Made From It', '2'],
    ])
    expect(within(main()).getByText("Changing this template doesn't change Matters already made from it.")).toBeTruthy()
    const problems = await within(main()).findByRole('region', { name: 'What New Matter Would Refuse' })
    expect(within(problems).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Privileged grants more than its parent (group Trainees: WRITE).',
      "Trainees could read a matter made from it, but can't read the Store Firm.",
    ])
    expect(within(problems).getByText('Saving still works: these are refused only when a matter is made from the template.')).toBeTruthy()
    expect(within(main()).getByRole('button', { name: 'Rename' })).toBeTruthy()
    expect(within(pane()).getByRole('link', { name: 'Workspace Templates' }).getAttribute('aria-current')).toBe('page')
    await expectNoAxeViolations(view.container)
  })

  it("warns of a folder's problems beside its Save, which still saves, and the problems are asked again after", async () => {
    let problems: A['TemplateProblem'][] | undefined
    const { api } = await openTemplate({
      'PUT /v1/admin/folders/2024/access': () => {
        problems = []
        return litigationAccess[2024]
      },
      'GET /v1/admin/templates/*/problems': () => problems ?? workspaceRoutesProblems(),
    })
    fireEvent.click(within(within(main()).getByRole('treegrid', { name: 'Folders of Litigation' })).getByRole('row', { name: /^Privileged/ }))
    const chosen = within(main()).getByRole('region', { name: 'Chosen Folder' })
    await within(chosen).findByRole('table', { name: 'Who Can Use Privileged' })
    const warning = within(chosen).getByRole('note')
    expect(within(warning).getByText('A new matter made from this template would refuse this:')).toBeTruthy()
    expect(within(warning).getByText('Privileged grants more than its parent (group Trainees: WRITE).')).toBeTruthy()
    expect(within(chosen).queryByText('A folder inside can narrow this list, never widen it.')).toBeNull()
    await expectNoAxeViolations(chosen)
    // A change that doesn't fix it still saves
    await userEvent.selectOptions(within(chosen).getByRole('combobox', { name: 'Access for Partners' }), 'full-access')
    const save = within(chosen).getByRole('button', { name: 'Save' })
    expect(save.hasAttribute('disabled')).toBe(false)
    await userEvent.click(save)
    await waitFor(() => expect(api.callsTo('PUT /v1/admin/folders/2024/access')).toHaveLength(1))
    expect((api.callsTo('PUT /v1/admin/folders/2024/access')[0]!.body as A['FolderAccessChange']).rows![0]).toEqual({ groupId: 20, permissions: permissionsOf('full-access') })
    await waitFor(() => expect(api.callsTo('GET /v1/admin/templates/202/problems').length).toBeGreaterThanOrEqual(2))
    await waitFor(() => expect(within(main()).queryByRole('region', { name: 'What New Matter Would Refuse' })).toBeNull())
  })

  it('lists two warnings alike in code, group and Store, each once', async () => {
    const twice: A['TemplateProblem'] = { code: 'wider-than-parent', message: 'Privileged grants more than its parent.', folderId: 2024, storeId: null, groupId: 21, groupName: 'Trainees' }
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await openTemplate({ 'GET /v1/admin/templates/*/problems': [twice, { ...twice, message: 'Privileged grants more than its parent, again.' }] })
      fireEvent.click(within(within(main()).getByRole('treegrid', { name: 'Folders of Litigation' })).getByRole('row', { name: /^Privileged/ }))
      const warning = await within(within(main()).getByRole('region', { name: 'Chosen Folder' })).findByRole('note')
      expect(within(warning).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Privileged grants more than its parent.', 'Privileged grants more than its parent, again.'])
      expect(errors.mock.calls.filter(([message]) => String(message).includes('same key'))).toEqual([])
    } finally {
      errors.mockRestore()
    }
  })

  it('renames the template, saying first that a name is taken', async () => {
    let info = templateInfos[1]!
    const { api } = await openTemplate({
      'GET /v1/admin/templates/202': () => info,
      'PATCH /v1/admin/templates/202': ({ body }: { body?: unknown }) => (info = { ...info, ...(body as A['FolderRename']) }),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Rename' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rename Template' })
    const name = within(dialog).getByRole('textbox', { name: /Name/ })
    await userEvent.clear(name)
    await userEvent.type(name, 'Firm')
    expect(within(dialog).getByText("There's already a template called Firm.")).toBeTruthy()
    await userEvent.clear(name)
    await userEvent.type(name, 'Disputes')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/templates/202').map((call) => call.body)).toEqual([{ name: 'Disputes' }]))
    expect(await screen.findByRole('heading', { level: 1, name: 'Disputes' })).toBeTruthy()
  })

  it("asks before a delete, saying matters made from it are unchanged and the Trash can't put it back, then goes to the list, with no Undo", async () => {
    const { api, router } = await openTemplate({ 'DELETE /v1/admin/templates/202': deletedFolder({ trashItemId: 907, folders: 5 }) })
    // A change left in its workspace goes with it, so the list opens without asking about it
    await userEvent.selectOptions(await within(main()).findByRole('combobox', { name: 'Access for Everyone' }), 'read-only')
    await userEvent.click(within(main()).getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Move the Template Litigation to the Trash?' })
    expect(within(dialog).getByText('Its 5 folders go with it.')).toBeTruthy()
    expect(within(dialog).getByText("Matters already made from it are unchanged, and New Matter won't offer it any more.")).toBeTruthy()
    expect(within(dialog).getByText("Healdan's Trash will list it but can't put it back in the Templates Store; the Classic Interface's Trash can.")).toBeTruthy()
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/templates'))
    expect(screen.queryByRole('alertdialog', { name: /^Leave the Changes/ })).toBeNull()
    expect(api.callsTo('DELETE /v1/admin/templates/202')).toHaveLength(1)
    // The Trash can't put a template back (409 place-gone), so the toast offers nothing to press
    expect(within(await toastOf('Moved the template Litigation to the Trash')).queryByRole('button', { name: 'Undo' })).toBeNull()
  })

  it("deletes a folder of the template with no Undo, as the Trash can't put it back", async () => {
    const { api } = await openTemplate({
      'GET /v1/folders/2021/contents': { folders: 0, documents: 0 },
      'DELETE /v1/admin/folders/2021': deletedFolder({ trashItemId: 908 }),
    })
    const tree = within(main()).getByRole('treegrid', { name: 'Folders of Litigation' })
    fireEvent.keyDown(within(tree).getByRole('row', { name: /^Correspondence/ }), { key: 'Delete' })
    const dialog = await screen.findByRole('alertdialog', { name: 'Move Correspondence to the Trash?' })
    expect(within(dialog).getByText('Matters already made from this template keep their own copy of it.')).toBeTruthy()
    expect(within(dialog).getByText("Healdan's Trash will list it but can't put it back in the template; the Classic Interface's Trash can.")).toBeTruthy()
    expect(within(dialog).queryByText(/restore/)).toBeNull()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(api.callsTo('DELETE /v1/admin/folders/2021')).toHaveLength(1))
    expect(within(await toastOf('Moved Correspondence to the Trash')).queryByRole('button', { name: 'Undo' })).toBeNull()
    expect(api.callsTo('POST /v1/trash/*/restore')).toEqual([])
  })

  it("shows the rule's refusal of a delete in the dialog", async () => {
    await openTemplate({ 'DELETE /v1/admin/templates/202': problem(409, 'refused', 'A document in it is checked out.') })
    await userEvent.click(within(main()).getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Move the Template Litigation to the Trash?' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('A document in it is checked out.')
  })

  it("starts afresh on another template's page, by Back too, with no dialog carried from the one before", async () => {
    const { router } = await openTemplate()
    await act(() => router.navigate({ href: '/templates/203' }))
    await screen.findByRole('heading', { level: 1, name: 'Transactional' })
    await userEvent.click(within(main()).getByRole('button', { name: 'Rename' }))
    expect(await screen.findByRole('dialog', { name: 'Rename Template' })).toBeTruthy()
    await act(async () => router.history.back())
    await waitFor(() => expect(router.state.location.pathname).toBe('/templates/202'))
    expect(screen.queryAllByRole('dialog').map((one) => one.getAttribute('aria-label') ?? one.textContent?.slice(0, 20))).toEqual([])
    expect(await screen.findByRole('heading', { level: 1, name: 'Litigation' })).toBeTruthy()
  })

  it("says there's no such template", async () => {
    const { view } = await renderApp('/templates/999', workspaceRoutes())
    expect(await screen.findByRole('heading', { level: 1, name: 'Template Not Found' })).toBeTruthy()
    expect(within(main()).getByRole('link', { name: 'Go to Workspace Templates' }).getAttribute('href')).toBe('/admin/templates')
    await expectNoAxeViolations(view.container)
  })
})

/** The Litigation template's problems as the default route gives them */
function workspaceRoutesProblems(): A['TemplateProblem'][] {
  const answer = (workspaceRoutes()['GET /v1/admin/templates/*/problems'] as (request: { params: string[] }) => A['TemplateProblem'][])({ params: ['202'] })
  return answer
}
