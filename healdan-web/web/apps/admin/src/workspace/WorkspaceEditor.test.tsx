// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { charterAccess, charterTree, folderNode, problem, workspaceRoutes, type FakeApi, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { PERMISSIONS, permissionsOf } from './levels'

type A = adminComponents['schemas']

const LABEL = '1001.1 – Charter Dispute – MV Northern Star'
const main = () => screen.getByRole('main')
const tree = () => within(main()).getByRole('treegrid', { name: `Folders of ${LABEL}` })
const row = (name: string | RegExp) => within(tree()).getByRole('row', { name })
/** The panel of the folder chosen in the tree */
const panel = () => within(main()).getByRole('region', { name: 'Chosen Folder' })
const access = () => within(main()).getByRole('table', { name: /^Who Can Use / })
/** Each row of Who Can Use This Folder: its name and its access, as a menu's value or as text */
function accessRows() {
  return within(access())
    .getAllByRole('row')
    .slice(1)
    .map((tr) => {
      const select = tr.querySelector('select')
      const level = select ? select.options[select.selectedIndex]!.textContent : tr.querySelectorAll('td')[0]!.textContent
      return [tr.querySelector('th')!.textContent, level]
    })
}

/** Matter 1001.1's Workspace tab, once its tree and its own folder's access have come */
async function openWorkspace(routes: Routes = {}) {
  const rendered = await renderApp('/matters/11?tab=workspace', { ...workspaceRoutes(), ...routes })
  await within(main()).findByRole('treegrid', { name: `Folders of ${LABEL}` })
  await within(main()).findByRole('table', { name: `Who Can Use ${LABEL}` })
  return rendered
}

/** Chooses a folder in the tree, and waits for its access */
async function choose(name: string) {
  fireEvent.click(row(new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)))
  await within(main()).findByRole('table', { name: `Who Can Use ${name}` })
}

const putsTo = (api: FakeApi, id: number) => api.callsTo(`PUT /v1/admin/folders/${id}/access`).map((call) => call.body)

/** Presses Tab until the element has the focus, as a person with no mouse would, 40 presses at most */
async function tabTo(element: Element) {
  for (let i = 0; i < 40 && document.activeElement !== element; i++) await userEvent.tab()
  expect(document.activeElement).toBe(element)
}

describe('The Workspace Editor: the tree', () => {
  it("shows every folder and search folder, a lock on each with permissions of its own, and the matter's own folder chosen", async () => {
    const { view } = await openWorkspace()
    const names = within(tree())
      .getAllByRole('row')
      .map((one) => one.getAttribute('aria-label') ?? one.textContent)
    expect(names).toEqual([
      `${LABEL}, Own Permissions`,
      'Correspondence',
      'Drafts',
      'Evidence, Own Permissions',
      'Pleadings',
      'Privileged, Own Permissions',
      "Counsel's Advice",
      'Emails in This Matter',
      'My Recent Docs Here',
    ])
    // A lock, drawn, on just those three
    expect(within(tree()).getAllByTitle('Own Permissions')).toHaveLength(3)
    expect(row(/^Evidence/).querySelector('[title="Own Permissions"]')).toBeTruthy()
    expect(row(/^Drafts/).querySelector('[title="Own Permissions"]')).toBeNull()
    // The matter's own folder is chosen, and who can use it shown
    expect(row(new RegExp(`^${LABEL}`)).getAttribute('aria-current')).toBe('page')
    expect(within(panel()).getByRole('heading', { level: 2 }).textContent).toBe(LABEL)
    expect(accessRows()).toEqual([
      ['Everyone', 'Read/Write'],
      ['Partners', 'Read/Write'],
      ['Tom Reeve Owner', 'Full Access'],
    ])
    expect(within(panel()).getByText("This is the matter's own folder: Edit renames the matter, and Delete moves it to the Trash.")).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("loads a folder's access when it's chosen, Read alone and any other set shown as Custom", async () => {
    const { api, view } = await openWorkspace()
    await choose('Evidence')
    expect(api.callsTo('GET /v1/admin/folders/113/access')).toHaveLength(1)
    expect(row(/^Evidence/).getAttribute('aria-current')).toBe('page')
    expect(accessRows()).toEqual([
      ['Everyone', 'Custom'],
      ['Partners', 'Custom'],
      ['Tom Reeve Owner', 'Full Access'],
    ])
    // The menu offers the three levels, and Custom only for a row that is
    const everyone = within(access()).getByRole('combobox', { name: 'Access for Everyone' })
    expect(within(everyone).getAllByRole('option').map((option) => option.textContent)).toEqual(['Read Only', 'Read/Write', 'Full Access', 'Custom'])
    const tom = within(access()).getByRole('combobox', { name: 'Access for Tom Reeve' })
    expect(within(tom).getAllByRole('option').map((option) => option.textContent)).toEqual(['Read Only', 'Read/Write', 'Full Access'])
    await expectNoAxeViolations(view.container)
  })
})

describe('Who Can Use This Folder', () => {
  it('sets exactly a level’s permissions, and Save sends each row as flags, a person by their id', async () => {
    const { api } = await openWorkspace({ 'PUT /v1/admin/folders/110/access': charterAccess[110] })
    const save = within(panel()).getByRole('button', { name: 'Save' })
    expect(save.hasAttribute('disabled')).toBe(true)
    await userEvent.selectOptions(within(access()).getByRole('combobox', { name: 'Access for Everyone' }), 'read-only')
    expect(save.hasAttribute('disabled')).toBe(false)
    await userEvent.click(save)
    await waitFor(() =>
      expect(putsTo(api, 110)).toEqual([
        {
          rows: [
            { groupId: 2, permissions: ['read', 'download', 'print', 'email', 'preview'] },
            { groupId: 20, permissions: permissionsOf('read-write') },
            { userId: 6, permissions: [...PERMISSIONS] },
          ],
        },
      ]),
    )
    expect(await screen.findByText(`Saved who can use ${LABEL}`)).toBeTruthy()
  })

  it('edits the 25 permissions one by one under Show Every Permission, a row that matches no level shown as Custom', async () => {
    const { api, view } = await openWorkspace({ 'PUT /v1/admin/folders/110/access': charterAccess[110] })
    const every = within(panel()).getByRole('button', { name: 'Show Every Permission' })
    expect(every.getAttribute('aria-expanded')).toBe('false')
    await userEvent.click(every)
    expect(every.getAttribute('aria-expanded')).toBe('true')
    const everyone = within(panel()).getByRole('group', { name: 'Every Permission of Everyone' })
    const boxes = within(everyone).getAllByRole('checkbox')
    expect(boxes.map((box) => box.closest('label')!.textContent)).toEqual([
      'Read', 'Download', 'Write', 'Add', 'Security', 'Immutable', 'Delete', 'Rename', 'Import', 'Export', 'Sign', 'Archive', 'Workflow',
      'Calendar', 'Subscription', 'Print', 'Password', 'Move', 'Email', 'Automation', 'Store', 'Reading Request', 'Preview', 'Custom ID', 'Revision',
    ])
    expect(boxes.filter((box) => (box as HTMLInputElement).checked)).toHaveLength(10)
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(everyone).getByRole('checkbox', { name: 'Delete' }))
    expect(accessRows()[0]).toEqual(['Everyone', 'Custom'])
    await userEvent.click(within(everyone).getByRole('checkbox', { name: 'Sign' }))
    await userEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(putsTo(api, 110)).toHaveLength(1))
    const [sent] = putsTo(api, 110) as A['FolderAccessChange'][]
    expect(sent!.rows![0]).toEqual({ groupId: 2, permissions: ['read', 'download', 'write', 'add', 'rename', 'sign', 'print', 'move', 'email', 'preview'] })
  })

  it("says a row left with no permission before Save, which waits for it", async () => {
    await openWorkspace()
    await choose('Evidence')
    await userEvent.click(within(panel()).getByRole('button', { name: 'Show Every Permission' }))
    await userEvent.click(within(within(panel()).getByRole('group', { name: 'Every Permission of Everyone' })).getByRole('checkbox', { name: 'Read' }))
    expect(within(panel()).getByText('Everyone has no permission left. Give it one, or remove it.')).toBeTruthy()
    expect(within(panel()).getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true)
    await userEvent.click(within(access()).getByRole('button', { name: 'Remove Everyone' }))
    expect(within(panel()).queryByText('Everyone has no permission left. Give it one, or remove it.')).toBeNull()
    expect(within(panel()).getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(false)
  })

  it("keeps the owner's row on the matter's own folder, and lets it go from a folder inside", async () => {
    const { api } = await openWorkspace({ 'PUT /v1/admin/folders/110/access': charterAccess[110] })
    expect(within(access()).queryByRole('button', { name: 'Remove Tom Reeve' })).toBeNull()
    expect(within(panel()).getByText("The owner's row stays on the matter's own folder.")).toBeTruthy()
    await userEvent.click(within(access()).getByRole('button', { name: 'Remove Everyone' }))
    expect(accessRows().map(([name]) => name)).toEqual(['Partners', 'Tom Reeve Owner'])
    await userEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect((putsTo(api, 110)[0] as A['FolderAccessChange']).rows).toHaveLength(2))
    await choose('Privileged')
    expect(within(access()).getByRole('button', { name: 'Remove Tom Reeve' })).toBeTruthy()
  })

  it('takes back what was changed with Discard Changes', async () => {
    await openWorkspace()
    await userEvent.selectOptions(within(access()).getByRole('combobox', { name: 'Access for Partners' }), 'full-access')
    await userEvent.click(within(panel()).getByRole('button', { name: 'Discard Changes' }))
    expect(accessRows()[1]).toEqual(['Partners', 'Read/Write'])
    expect(within(panel()).queryByRole('button', { name: 'Discard Changes' })).toBeNull()
  })

  it('shows whose permissions a folder follows, and Set Own Permissions makes them its own', async () => {
    const { api, view } = await openWorkspace({
      'PUT /v1/admin/folders/111/access': { ...charterAccess[110]!, folderId: 111 },
    })
    await choose('Correspondence')
    expect(within(panel()).getByText(`It follows the permissions of ${LABEL}.`)).toBeTruthy()
    // Shown, not edited
    expect(within(access()).queryAllByRole('combobox')).toEqual([])
    expect(within(panel()).queryByRole('button', { name: 'Save' })).toBeNull()
    expect(within(panel()).queryByRole('button', { name: 'Add Person or Group' })).toBeNull()
    expect(accessRows()).toEqual([
      ['Everyone', 'Read/Write'],
      ['Partners', 'Read/Write'],
      ['Tom Reeve Owner', 'Full Access'],
    ])
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(panel()).getByRole('button', { name: 'Set Own Permissions' }))
    await userEvent.selectOptions(within(access()).getByRole('combobox', { name: 'Access for Everyone' }), 'read-only')
    await userEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(putsTo(api, 111)).toHaveLength(1))
    expect((putsTo(api, 111)[0] as A['FolderAccessChange']).rows![0]).toEqual({ groupId: 2, permissions: permissionsOf('read-only') })
  })

  it("asks before Follow Parent, which gives up the folder's own permissions, and isn't offered on the matter's own folder", async () => {
    const { api } = await openWorkspace({ 'PUT /v1/admin/folders/113/access': { ...charterAccess[111]!, folderId: 113 } })
    expect(within(panel()).queryByRole('button', { name: 'Follow Parent' })).toBeNull()
    await choose('Evidence')
    await userEvent.click(within(panel()).getByRole('button', { name: 'Follow Parent' }))
    const dialog = await screen.findByRole('alertdialog', { name: `Follow the Permissions of ${LABEL}?` })
    expect(within(dialog).getByText(`Evidence gives up its own permissions, and takes those of ${LABEL} from then on.`)).toBeTruthy()
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Follow Parent' }))
    await waitFor(() => expect(putsTo(api, 113)).toEqual([{ followParent: true }]))
    expect(await within(panel()).findByText(`It follows the permissions of ${LABEL}.`)).toBeTruthy()
  })

  it('adds a person or a group, found by one search of both, at Read Only, and sends each as itself', async () => {
    const { api } = await openWorkspace({ 'PUT /v1/admin/folders/110/access': charterAccess[110] })
    await userEvent.click(within(panel()).getByRole('button', { name: 'Add Person or Group' }))
    let dialog = await screen.findByRole('dialog', { name: 'Add Person or Group' })
    const find = within(dialog).getByRole('combobox', { name: 'Find a Person or Group' })
    await userEvent.type(find, 'a')
    await waitFor(() => expect(api.callsTo('GET /v1/admin/groups').at(-1)?.query).toEqual({ q: 'a', limit: 20 }))
    await waitFor(() => expect(api.callsTo('GET /v1/users').at(-1)?.query).toEqual({ q: 'a' }))
    const list = await screen.findByRole('listbox')
    // Partners is already in the list, so only Trainees is offered among the groups
    expect(within(list).getByRole('group', { name: 'Groups' })).toBeTruthy()
    expect(within(within(list).getByRole('group', { name: 'Groups' })).getAllByRole('option').map((one) => one.textContent)).toEqual(['Trainees'])
    expect(within(within(list).getByRole('group', { name: 'People' })).getAllByRole('option').map((one) => one.textContent)).toEqual(['Helen Ashcombehelen', 'Sam Okaforsam'])
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(list).getByRole('option', { name: /Sam Okafor/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(accessRows()).toEqual([
      ['Everyone', 'Read/Write'],
      ['Partners', 'Read/Write'],
      ['Sam Okafor', 'Read Only'],
      ['Tom Reeve Owner', 'Full Access'],
    ])
    await userEvent.click(within(panel()).getByRole('button', { name: 'Add Person or Group' }))
    dialog = await screen.findByRole('dialog', { name: 'Add Person or Group' })
    await userEvent.type(within(dialog).getByRole('combobox', { name: 'Find a Person or Group' }), 'train')
    await userEvent.click(await screen.findByRole('option', { name: 'Trainees' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await userEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(putsTo(api, 110)).toHaveLength(1))
    expect((putsTo(api, 110)[0] as A['FolderAccessChange']).rows).toEqual([
      { groupId: 2, permissions: permissionsOf('read-write') },
      { groupId: 20, permissions: permissionsOf('read-write') },
      { groupId: 21, permissions: permissionsOf('read-only') },
      { userId: 10, permissions: permissionsOf('read-only') },
      { userId: 6, permissions: [...PERMISSIONS] },
    ])
  })

  it("shows the server's refusal beside Save, in the rule's words, and keeps what was changed", async () => {
    await openWorkspace({ 'PUT /v1/admin/folders/110/access': problem(409, 'refused', 'guest can be given Read Only at most.') })
    await userEvent.selectOptions(within(access()).getByRole('combobox', { name: 'Access for Everyone' }), 'full-access')
    await userEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    const alert = await within(panel()).findByRole('alert')
    expect(alert.textContent).toBe('guest can be given Read Only at most.')
    expect(accessRows()[0]).toEqual(['Everyone', 'Full Access'])
    await expectNoAxeViolations(panel())
  })

  it('offers Apply to Subfolders only where the server does, and asks first, naming the folders below whose own permissions it replaces', async () => {
    const { api } = await openWorkspace({ 'POST /v1/admin/folders/110/apply-access': undefined })
    await userEvent.click(within(panel()).getByRole('button', { name: 'Apply to Subfolders' }))
    const dialog = await screen.findByRole('alertdialog', { name: `Apply to the Subfolders of ${LABEL}?` })
    expect(within(dialog).getByText(`Every folder and search folder below ${LABEL}, at every level, gives up permissions of its own and follows these from then on.`)).toBeTruthy()
    expect(within(dialog).getByText('These have permissions of their own now, which are replaced, so they are opened up to these: Evidence and Privileged.')).toBeTruthy()
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Apply to Subfolders' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/folders/110/apply-access')).toHaveLength(1))
    expect(await screen.findByText(`The subfolders of ${LABEL} now follow it`)).toBeTruthy()
    // A folder with nothing below it isn't offered it
    await choose('Correspondence')
    expect(within(panel()).queryByRole('button', { name: 'Apply to Subfolders' })).toBeNull()
  })

  it('waits for the changes to be saved before applying them to the subfolders', async () => {
    await openWorkspace()
    await userEvent.selectOptions(within(access()).getByRole('combobox', { name: 'Access for Partners' }), 'full-access')
    expect(within(panel()).getByRole('button', { name: 'Apply to Subfolders' }).hasAttribute('disabled')).toBe(true)
    expect(within(panel()).getByText('Save the changes before applying them to the subfolders.')).toBeTruthy()
  })
})

describe("The Workspace Editor's folders", () => {
  it('makes a folder in the folder chosen, saying first that a name is taken, and chooses it', async () => {
    let shown: A['WorkspaceNode'] = charterTree
    const made = folderNode(120, 'Exhibits')
    const { api } = await openWorkspace({
      'GET /v1/admin/workspaces/*/tree': () => shown,
      'POST /v1/admin/folders/110/children': () => {
        shown = { ...charterTree, children: [...charterTree.children.slice(0, 3), made, ...charterTree.children.slice(3)] }
        return made
      },
      'GET /v1/admin/folders/120/access': { ...charterAccess[111]!, folderId: 120 },
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'New Folder' }))
    const dialog = await screen.findByRole('dialog', { name: `New Folder in ${LABEL}` })
    const name = within(dialog).getByRole('textbox', { name: /Name/ })
    await userEvent.type(name, 'drafts')
    expect(within(dialog).getByText("There's already a folder called drafts here.")).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Create' }).hasAttribute('disabled')).toBe(true)
    await expectNoAxeViolations(dialog)
    await userEvent.clear(name)
    await userEvent.type(name, ' Exhibits ')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('POST /v1/admin/folders/110/children').map((call) => call.body)).toEqual([{ name: 'Exhibits' }])
    await waitFor(() => expect(row(/^Exhibits/).getAttribute('aria-current')).toBe('page'))
    expect(await within(main()).findByRole('table', { name: 'Who Can Use Exhibits' })).toBeTruthy()
  })

  it('renames a folder from its panel, or by F2 in the tree', async () => {
    const { api } = await openWorkspace({ 'PATCH /v1/admin/folders/*': undefined })
    await choose('Drafts')
    await userEvent.click(within(panel()).getByRole('button', { name: 'Rename' }))
    let dialog = await screen.findByRole('dialog', { name: 'Rename Drafts' })
    const name = within(dialog).getByRole('textbox', { name: /Name/ })
    expect(within(dialog).getByRole('button', { name: 'Rename' }).hasAttribute('disabled')).toBe(true)
    await userEvent.clear(name)
    await userEvent.type(name, 'Pleadings')
    expect(within(dialog).getByText("There's already a folder called Pleadings here.")).toBeTruthy()
    await userEvent.clear(name)
    await userEvent.type(name, 'Draft Pleadings')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/folders/112').map((call) => call.body)).toEqual([{ name: 'Draft Pleadings' }]))

    // A search folder too, by the same route
    fireEvent.keyDown(row(/^My Recent Docs Here/), { key: 'F2' })
    dialog = await screen.findByRole('dialog', { name: 'Rename My Recent Docs Here' })
    await userEvent.clear(within(dialog).getByRole('textbox', { name: /Name/ }))
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Mine, Lately')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/folders/119').map((call) => call.body)).toEqual([{ name: 'Mine, Lately' }]))
  })

  it("says why the matter's own folder isn't renamed or deleted here", async () => {
    const { api } = await openWorkspace()
    expect(within(panel()).queryByRole('button', { name: 'Rename' })).toBeNull()
    expect(within(panel()).queryByRole('button', { name: 'Delete' })).toBeNull()
    const note = "This is the matter's own folder: Edit renames the matter, and Delete moves it to the Trash."
    expect(screen.getAllByText(note)).toHaveLength(1)
    // F2 and Delete say it again, as a toast
    fireEvent.keyDown(row(new RegExp(`^${LABEL}`)), { key: 'F2' })
    await waitFor(() => expect(screen.getAllByText(note)).toHaveLength(2))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.callsTo('PATCH /v1/admin/folders/*')).toEqual([])
  })

  it('moves a folder within the workspace, saying first where it is already', async () => {
    const { api } = await openWorkspace({ 'POST /v1/admin/folders/112/move': undefined })
    await choose('Drafts')
    await userEvent.click(within(panel()).getByRole('button', { name: 'Move To…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move Drafts To…' })
    const into = within(dialog).getByRole('combobox', { name: /Into/ })
    // Folders only, and not the folder itself
    expect(within(into).getAllByRole('option').map((option) => option.textContent)).toEqual([
      LABEL,
      `${LABEL} › Correspondence`,
      `${LABEL} › Evidence`,
      `${LABEL} › Pleadings`,
      `${LABEL} › Privileged`,
      `${LABEL} › Privileged › Counsel's Advice`,
    ])
    await userEvent.selectOptions(into, '110')
    expect(within(dialog).getByText("It's in that folder already.")).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Move' }).hasAttribute('disabled')).toBe(true)
    await expectNoAxeViolations(dialog)
    await userEvent.selectOptions(into, '115')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/folders/112/move').map((call) => call.body)).toEqual([{ folderId: 115 }]))
    expect(await screen.findByText('Moved Drafts to Privileged')).toBeTruthy()
  })

  it('asks before a delete, naming what the folder holds, and chooses its parent after', async () => {
    const { api } = await openWorkspace({
      'GET /v1/folders/115/contents': { folders: 1, documents: 3 },
      'DELETE /v1/admin/folders/115': undefined,
    })
    await choose('Privileged')
    fireEvent.keyDown(row(/^Privileged/), { key: 'Delete' })
    const dialog = await screen.findByRole('alertdialog', { name: 'Move Privileged to the Trash?' })
    expect(await within(dialog).findByText('It holds 1 folder and 3 documents, which go with it.')).toBeTruthy()
    expect(within(dialog).getByText('An administrator can restore it from the Trash in Healdan.')).toBeTruthy()
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(api.callsTo('DELETE /v1/admin/folders/115')).toHaveLength(1))
    expect(await screen.findByText('Moved Privileged to the Trash')).toBeTruthy()
    await waitFor(() => expect(within(panel()).getByRole('heading', { level: 2 }).textContent).toBe(LABEL))
  })
})

describe('The values a folder sets', () => {
  it("shows each value, where it comes from, and saves the whole set, a Subclass offered only within its Class", async () => {
    const { api, view } = await openWorkspace({
      'PUT /v1/admin/folders/111/properties': ({ body }: { body?: unknown }) => ({ folderId: 111, properties: [] as A['FolderProperty'][], ...(body as object) }),
    })
    // The matter's own folder: the install's default Class
    const values = await within(panel()).findByRole('region', { name: 'Values It Sets' })
    expect((within(values).getByRole('combobox', { name: 'Class' }) as HTMLSelectElement).value).toBe('')
    expect(within(values).getByText("The install's default: Correspondence")).toBeTruthy()
    await choose('Correspondence')
    const own = await within(panel()).findByRole('region', { name: 'Values It Sets' })
    await waitFor(() => expect((within(own).getByRole('combobox', { name: 'Class' }) as HTMLSelectElement).value).toBe('Correspondence'))
    const subclass = within(own).getByRole('combobox', { name: 'Subclass' }) as HTMLSelectElement
    expect(subclass.value).toBe('Letters')
    expect(within(subclass).getAllByRole('option').map((option) => option.textContent)).toEqual(['Not Set Here', 'Emails', 'Letters'])
    await expectNoAxeViolations(view.container)
    await userEvent.selectOptions(subclass, 'Emails')
    await userEvent.click(within(own).getByRole('button', { name: 'Save Values' }))
    await waitFor(() =>
      expect(api.callsTo('PUT /v1/admin/folders/111/properties').map((call) => call.body)).toEqual([{ values: { docclass: 'Correspondence', docsubclass: 'Emails', confidential: null } }]),
    )
  })

  it('says a value comes from a folder above', async () => {
    await openWorkspace()
    await choose("Counsel's Advice")
    const values = await within(panel()).findByRole('region', { name: 'Values It Sets' })
    await waitFor(() => expect(within(values).getByText('From Privileged: Evidence')).toBeTruthy())
    expect(within(values).getByText('From Privileged: Yes')).toBeTruthy()
  })
})

describe("A search folder's rule", () => {
  it('shows the rule for editing, in place of values, and saves it whole, saying first what is wrong', async () => {
    const { api, view } = await openWorkspace({
      'PATCH /v1/admin/search-folders/119': ({ body }: { body?: unknown }) => ({ id: 119, name: 'My Recent Docs Here', ...(body as object) }),
    })
    await choose('My Recent Docs Here')
    const rule = await within(panel()).findByRole('region', { name: 'Rule' })
    expect(within(panel()).queryByRole('region', { name: 'Values It Sets' })).toBeNull()
    const days = within(rule).getByRole('textbox', { name: /Changed Within/ }) as HTMLInputElement
    await waitFor(() => expect(days.value).toBe('14'))
    expect((within(rule).getByRole('checkbox', { name: 'Only Changes by Whoever Opens It' }) as HTMLInputElement).checked).toBe(true)
    expect((within(rule).getByRole('combobox', { name: 'Kind' }) as HTMLSelectElement).value).toBe('all')
    // A Subclass only once there's a Class
    expect(within(rule).queryByRole('combobox', { name: 'Subclass' })).toBeNull()
    await expectNoAxeViolations(view.container)
    await userEvent.clear(days)
    await userEvent.type(days, '0')
    expect(within(rule).getByText('The days have to be a whole number, 1 or more.')).toBeTruthy()
    expect(within(rule).getByRole('button', { name: 'Save Rule' }).hasAttribute('disabled')).toBe(true)
    await userEvent.clear(days)
    await userEvent.type(days, '7')
    await userEvent.selectOptions(within(rule).getByRole('combobox', { name: 'Class' }), 'Evidence')
    await userEvent.selectOptions(within(rule).getByRole('combobox', { name: 'Subclass' }), 'Witness Statements')
    await userEvent.click(within(rule).getByRole('button', { name: 'Save Rule' }))
    await waitFor(() =>
      expect(api.callsTo('PATCH /v1/admin/search-folders/119').map((call) => call.body)).toEqual([
        { rule: { days: 7, onlyMine: true, kind: 'all', docClass: 'Evidence', docSubclass: 'Witness Statements' } },
      ]),
    )
  })

  it('makes a search folder in the folder chosen, its name checked as a folder’s is', async () => {
    const { api } = await openWorkspace({ 'POST /v1/admin/folders/115/search-folders': { id: 130, name: 'Advice Emails', rule: { days: 30, onlyMine: false, kind: 'emails', docClass: null, docSubclass: null } } })
    await choose('Privileged')
    await userEvent.click(within(main()).getByRole('button', { name: 'New Search Folder' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Search Folder in Privileged' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'A/B')
    expect(within(dialog).getByText("A name can't have / or \\ in it.")).toBeTruthy()
    await userEvent.clear(within(dialog).getByRole('textbox', { name: /Name/ }))
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Advice Emails')
    await userEvent.selectOptions(within(dialog).getByRole('combobox', { name: 'Kind' }), 'emails')
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    await waitFor(() =>
      expect(api.callsTo('POST /v1/admin/folders/115/search-folders').map((call) => call.body)).toEqual([
        { name: 'Advice Emails', rule: { days: 30, onlyMine: false, kind: 'emails', docClass: null, docSubclass: null } },
      ]),
    )
  })
})

describe('The Workspace Editor by keyboard', () => {
  it('walks the tree, chooses a folder and saves its access, with no mouse', async () => {
    const { api } = await openWorkspace({ 'PUT /v1/admin/folders/113/access': charterAccess[113] })
    // Into the tree from the tab, as Tab goes
    within(main()).getByRole('tab', { name: 'Workspace' }).focus()
    await tabTo(row(new RegExp(`^${LABEL}`)))
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}')
    expect(document.activeElement).toBe(row(/^Evidence/))
    await userEvent.keyboard('{Enter}')
    await within(main()).findByRole('table', { name: 'Who Can Use Evidence' })
    // On into the panel, to Partners' menu, and Save
    const partners = within(access()).getByRole('combobox', { name: 'Access for Partners' })
    await tabTo(partners)
    await userEvent.selectOptions(partners, 'read-write')
    await tabTo(within(panel()).getByRole('button', { name: 'Save' }))
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(putsTo(api, 113)).toHaveLength(1))
    expect((putsTo(api, 113)[0] as A['FolderAccessChange']).rows![1]).toEqual({ groupId: 20, permissions: permissionsOf('read-write') })
    // F2 on a row renames it, and Esc goes back to the tree
    row(/^Drafts/).focus()
    await userEvent.keyboard('{F2}')
    expect(await screen.findByRole('dialog', { name: 'Rename Drafts' })).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('opens a folder’s menu with Shift+F10, whose items act on it', async () => {
    await openWorkspace()
    row(/^Pleadings/).focus()
    await userEvent.keyboard('{Shift>}{F10}{/Shift}')
    const menu = await screen.findByRole('menu', { name: 'Actions for Pleadings' })
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['New Folder', 'New Search Folder', 'Rename', 'Move To…', 'Delete'])
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(await screen.findByRole('dialog', { name: 'Rename Pleadings' })).toBeTruthy()
  })
})
