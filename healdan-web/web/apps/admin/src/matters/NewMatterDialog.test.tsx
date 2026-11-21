// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { setLabels } from '@healdan/ui'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import {
  accessRow,
  clientOf,
  clients,
  clientWork,
  everyone,
  firmStore,
  marshLane,
  matterOf,
  partners,
  problem,
  regionalOffice,
  trainees,
  transactional,
  type FakeApi,
  type Routes,
} from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']
type Query = { query?: Record<string, unknown> }
type Sent = { body?: unknown }

const main = () => screen.getByRole('main')
/** Long enough for the typing to pause and the answer to come */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const box = (dialog: HTMLElement, name: string) => within(dialog).getByRole('combobox', { name }) as HTMLInputElement
const select = (dialog: HTMLElement, name: string) => within(dialog).getByRole('combobox', { name }) as HTMLSelectElement
const field = (dialog: HTMLElement, name: string) => within(dialog).getByRole('textbox', { name }) as HTMLInputElement
const create = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Create Matter' })
const isDisabled = (button: HTMLElement) => button.getAttribute('disabled') !== null
/** What each check sent, in order */
const drafts = (api: FakeApi) => api.callsTo('POST /v1/admin/matters/check').map((call) => call.body as A['MatterDraft'])

/** The client search answers as the server does */
const CLIENTS: Routes = { 'GET /v1/admin/clients': ({ query }: Query) => clients(query) }

/** The Matters list, with New Matter open over it */
async function openNewMatter(routes: Routes = {}) {
  const rendered = await renderApp('/matters', { ...CLIENTS, ...routes })
  await userEvent.click(await within(main()).findByRole('button', { name: 'New Matter' }))
  const dialog = await screen.findByRole('dialog', { name: 'New Matter' })
  return { ...rendered, dialog }
}

/** A search's option, found once the typing pauses and the list opens by itself */
async function pick(dialog: HTMLElement, name: string, typed: string, option: string) {
  await userEvent.type(box(dialog, name), typed)
  await pause()
  await userEvent.click(await screen.findByRole('option', { name: option }))
}

/** Every field, for 1004.1 Community Hall Lease, in Client Work, owned by Helen, from Transactional */
async function fillIn(dialog: HTMLElement) {
  await pick(dialog, 'Client', 'marsh', '1004 – Marsh Lane Community Trust')
  await waitFor(() => expect(field(dialog, 'Number').value).toBe('1'))
  await userEvent.selectOptions(select(dialog, 'Store'), 'Client Work')
  await userEvent.type(field(dialog, 'Name'), 'Community Hall Lease')
  await userEvent.type(field(dialog, 'Description'), 'Lease of the hall on Marsh Lane')
  await pick(dialog, 'Owner', 'helen', 'Helen Ashcombe helen')
  await userEvent.selectOptions(select(dialog, 'Template'), 'Transactional')
}

/** What "It Will Contain" draws, a line for each folder, indented two spaces for each level below the matter's own */
function outline(dialog: HTMLElement): string[] {
  const region = within(dialog).getByRole('region', { name: 'It Will Contain' })
  const lines: string[] = []
  const walk = (list: Element, depth: number) => {
    for (const item of Array.from(list.children)) {
      const own = item.querySelector(':scope > [data-node]')
      lines.push(`${'  '.repeat(depth)}${own?.textContent ?? ''}`)
      const below = item.querySelector(':scope > ul')
      if (below) walk(below, depth + 1)
    }
  }
  const top = region.querySelector('ul')
  if (top) walk(top, 0)
  return lines
}

const unreadable: A['MatterProblem'] = {
  code: 'store-unreadable',
  message: "Everyone can't read the Store Regional Office.",
  fixAction: 'grant-store-read',
  storeId: regionalOffice.id,
  groupId: everyone.id,
  groupName: everyone.name,
}

describe('New Matter', () => {
  it('opens from the Matters list: Client and Owner are searches, Store and Template menus, each search or menu with what makes a new one', async () => {
    const { dialog, view } = await openNewMatter()
    // A search is typed into, and finds as it goes; a menu offers every Store or template
    for (const name of ['Client', 'Owner']) {
      expect(box(dialog, name).tagName).toBe('INPUT')
      expect(box(dialog, name).getAttribute('aria-autocomplete')).toBe('list')
    }
    expect(select(dialog, 'Store').tagName).toBe('SELECT')
    await waitFor(() => expect(within(select(dialog, 'Store')).getAllByRole('option').map((one) => one.textContent)).toEqual(['Client Work', 'Firm']))
    expect(select(dialog, 'Template').tagName).toBe('SELECT')
    await waitFor(() => expect(within(select(dialog, 'Template')).getAllByRole('option').map((one) => one.textContent)).toEqual(['Firm', 'Litigation', 'Transactional']))
    expect(within(dialog).getByRole('button', { name: 'New Client…' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'New Store…' })).toBeTruthy()
    expect(field(dialog, 'Name')).toBeTruthy()
    expect(field(dialog, 'Description')).toBeTruthy()
    // Nothing to make yet
    expect(isDisabled(create(dialog))).toBe(true)
    await expectNoAxeViolations(view.baseElement)
  })

  it('fills Number with the next free one for the client picked, which can be typed over, and a client picked after takes its own', async () => {
    const { api, dialog } = await openNewMatter()
    expect(field(dialog, 'Number').value).toBe('')
    expect(api.callsTo('GET /v1/admin/matters/next-number')).toEqual([])
    await pick(dialog, 'Client', 'marsh', '1004 – Marsh Lane Community Trust')
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1'))
    expect(api.callsTo('GET /v1/admin/matters/next-number').map((call) => call.query)).toEqual([{ clientId: marshLane.id }])
    expect(within(dialog).getByText('The next free number is 1.')).toBeTruthy()
    await userEvent.clear(field(dialog, 'Number'))
    await userEvent.type(field(dialog, 'Number'), '7')
    expect(field(dialog, 'Number').value).toBe('7')
    // Another client's numbers are its own
    await userEvent.clear(box(dialog, 'Client'))
    await pick(dialog, 'Client', 'salt', '1001 – Saltmarsh Shipping Ltd')
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('3'))
    expect(within(dialog).getByText('The next free number is 3.')).toBeTruthy()
  })

  it('says a number has to be a whole number, 1 or more, before Create Matter', async () => {
    const { dialog } = await openNewMatter()
    await fillIn(dialog)
    await waitFor(() => expect(isDisabled(create(dialog))).toBe(false))
    for (const typed of ['0', '1.5', 'two']) {
      await userEvent.clear(field(dialog, 'Number'))
      await userEvent.type(field(dialog, 'Number'), typed)
      expect(within(dialog).getByText('The number has to be a whole number, 1 or more.')).toBeTruthy()
      expect(isDisabled(create(dialog))).toBe(true)
    }
  })

  it("shows what the matter will contain: the chosen template's folders and search folders, under the matter's own folder", async () => {
    const { api, dialog, view } = await openNewMatter()
    const region = within(dialog).getByRole('region', { name: 'It Will Contain' })
    expect(within(region).getByText('Choose a template to see what it will contain.')).toBeTruthy()
    await pick(dialog, 'Client', 'marsh', '1004 – Marsh Lane Community Trust')
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1'))
    await userEvent.type(field(dialog, 'Name'), 'Community Hall Lease')
    await userEvent.selectOptions(select(dialog, 'Template'), 'Litigation')
    await waitFor(() =>
      expect(outline(dialog)).toEqual([
        '1004.1 – Community Hall Lease',
        '  Correspondence',
        '  Evidence',
        '    Witness Statements',
        '  Pleadings',
        '  Privileged',
        '  My Recent Docs Here, Search Folder',
      ]),
    )
    expect(api.callsTo('GET /v1/admin/workspaces/*/tree').map((call) => call.path)).toEqual(['/v1/admin/workspaces/202/tree'])
    await expectNoAxeViolations(view.baseElement)
    // Another template, another tree; the matter's own folder follows the form
    await userEvent.selectOptions(select(dialog, 'Template'), 'Transactional')
    await userEvent.clear(field(dialog, 'Number'))
    await userEvent.type(field(dialog, 'Number'), '2')
    await waitFor(() => expect(outline(dialog)[0]).toBe('1004.2 – Community Hall Lease'))
    await waitFor(() => expect(outline(dialog).slice(1)).toEqual(['  Correspondence', '  Drafts', '  Due Diligence', '  Emails', '  Executed', '  Emails in This Matter, Search Folder', '  My Recent Docs Here, Search Folder']))
  })

  it("asks again for the templates and the chosen template's folders when the person comes back to the window, as the templates' screens do", async () => {
    const { api, dialog } = await openNewMatter()
    await userEvent.selectOptions(select(dialog, 'Template'), 'Litigation')
    await waitFor(() => expect(outline(dialog)).toHaveLength(7))
    expect(api.callsTo('GET /v1/admin/templates')).toHaveLength(1)
    expect(api.callsTo('GET /v1/admin/workspaces/202/tree')).toHaveLength(1)
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/templates')).toHaveLength(2))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/workspaces/202/tree')).toHaveLength(2))
  })

  it('checks the form with the server as it changes, once the typing pauses, never for each key', async () => {
    const { api, dialog } = await openNewMatter()
    await pick(dialog, 'Client', 'marsh', '1004 – Marsh Lane Community Trust')
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1'))
    await userEvent.type(field(dialog, 'Name'), '  Community Hall Lease')
    await pause()
    // Only whole names were sent, trimmed, never what was typed on the way
    expect(drafts(api).map((draft) => draft.name).filter((name) => name !== null)).toEqual(['Community Hall Lease'])
    expect(drafts(api).at(-1)).toEqual({ clientId: marshLane.id, number: 1, name: 'Community Hall Lease', description: null, storeId: null, ownerId: null, templateId: null })
    await userEvent.selectOptions(select(dialog, 'Store'), 'Firm')
    await waitFor(() => expect(drafts(api).at(-1)?.storeId).toBe(firmStore.id))
  })

  it("says a problem the check finds beside the field it's about, and Create Matter waits until there's none", async () => {
    const { dialog, view } = await openNewMatter({
      'POST /v1/admin/matters/check': ({ body }: Sent) => ({
        problems:
          (body as A['MatterDraft']).number === 1
            ? [{ code: 'number-taken', message: 'Matter number 1004.1 is already used.', fixAction: null, storeId: null, groupId: null, groupName: null }]
            : [],
      }),
    })
    await fillIn(dialog)
    expect(await within(dialog).findByText('Matter number 1004.1 is already used.')).toBeTruthy()
    expect(field(dialog, 'Number').getAttribute('aria-invalid')).toBe('true')
    expect(isDisabled(create(dialog))).toBe(true)
    await expectNoAxeViolations(view.baseElement)
    await userEvent.clear(field(dialog, 'Number'))
    await userEvent.type(field(dialog, 'Number'), '2')
    await waitFor(() => expect(isDisabled(create(dialog))).toBe(false))
    expect(within(dialog).queryByText('Matter number 1004.1 is already used.')).toBeNull()
  })

  it("says when a group the template lets in can't read the Store, and Let Everyone Read the Store lets it, keeping who could, then checks again", async () => {
    let granted = false
    let put: unknown
    const { api, dialog, view } = await openNewMatter({
      'GET /v1/admin/stores': [clientWork, firmStore, regionalOffice],
      'POST /v1/admin/matters/check': ({ body }: Sent) => {
        const draft = body as A['MatterDraft']
        return { problems: !granted && draft.storeId === regionalOffice.id && draft.templateId === transactional.id ? [unreadable] : [] }
      },
      'GET /v1/admin/stores/5/access': {
        storeId: regionalOffice.id,
        rows: [
          accessRow(partners.id, partners.name, ['read']),
          // A row without Read lets nobody see the Store, so it isn't sent as if it did
          accessRow(trainees.id, trainees.name, ['download']),
          accessRow(105, 'Tom Reeve', ['read', 'write'], 6),
        ],
      } satisfies A['StoreAccess'],
      'PUT /v1/admin/stores/5/access': ({ body }: Sent) => {
        put = body
        granted = true
        return { storeId: regionalOffice.id, rows: [] } satisfies A['StoreAccess']
      },
    })
    await fillIn(dialog)
    await userEvent.selectOptions(select(dialog, 'Store'), 'Regional Office')
    expect(await within(dialog).findByText("Everyone can't read the Store Regional Office.")).toBeTruthy()
    expect(within(dialog).getByText('Or choose another Store.')).toBeTruthy()
    expect(isDisabled(create(dialog))).toBe(true)
    await expectNoAxeViolations(view.baseElement)
    const checks = drafts(api).length
    await userEvent.click(within(dialog).getByRole('button', { name: 'Let Everyone Read the Store' }))
    await waitFor(() => expect(within(dialog).queryByText("Everyone can't read the Store Regional Office.")).toBeNull())
    expect(put).toEqual({ groupIds: [partners.id, 105, everyone.id] })
    expect(api.callsTo('GET /v1/admin/stores/5/access')).toHaveLength(1)
    // The same form, checked again
    expect(drafts(api).length).toBeGreaterThan(checks)
    expect(drafts(api).at(-1)?.storeId).toBe(regionalOffice.id)
    expect(await screen.findByText('Everyone can now see Regional Office.')).toBeTruthy()
    await waitFor(() => expect(isDisabled(create(dialog))).toBe(false))
  })

  it("says it couldn't let the group read the Store, in the rule's words, and leaves the problem for another try", async () => {
    const { dialog } = await openNewMatter({
      'GET /v1/admin/stores': [clientWork, firmStore, regionalOffice],
      'POST /v1/admin/matters/check': { problems: [unreadable] },
      'GET /v1/admin/stores/5/access': { storeId: regionalOffice.id, rows: [] } satisfies A['StoreAccess'],
      'PUT /v1/admin/stores/5/access': problem(409, 'refused', 'The group Everyone no longer exists.'),
    })
    await fillIn(dialog)
    const grant = await within(dialog).findByRole('button', { name: 'Let Everyone Read the Store' })
    // Offered once the check is of the form as it is
    await waitFor(() => expect(isDisabled(grant)).toBe(false))
    await userEvent.click(grant)
    expect((await within(dialog).findByRole('alert')).textContent).toBe('The group Everyone no longer exists.')
    expect(within(dialog).getByText("Everyone can't read the Store Regional Office.")).toBeTruthy()
    expect(isDisabled(create(dialog))).toBe(true)
  })

  it('offers no grant while the check is of the form as it was, as the Store it names may no longer be the form’s', async () => {
    const { dialog } = await openNewMatter({
      'GET /v1/admin/stores': [clientWork, firmStore, regionalOffice],
      'POST /v1/admin/matters/check': { problems: [unreadable] },
    })
    await fillIn(dialog)
    const grant = await within(dialog).findByRole('button', { name: 'Let Everyone Read the Store' })
    await waitFor(() => expect(isDisabled(grant)).toBe(false))
    await userEvent.type(field(dialog, 'Name'), 's')
    expect(isDisabled(within(dialog).getByRole('button', { name: 'Let Everyone Read the Store' }))).toBe(true)
    await pause()
    await waitFor(() => expect(isDisabled(within(dialog).getByRole('button', { name: 'Let Everyone Read the Store' }))).toBe(false))
  })

  it('lets one group read the Store at a time, so a second grant never reads who can see it before the first is saved', async () => {
    const traineesUnreadable: A['MatterProblem'] = { ...unreadable, message: "Trainees can't read the Store Regional Office.", groupId: trainees.id, groupName: trainees.name }
    const readers: A['AccessRow'][] = []
    const puts: unknown[] = []
    let answer!: () => void
    const { dialog } = await openNewMatter({
      'GET /v1/admin/stores': [clientWork, firmStore, regionalOffice],
      'POST /v1/admin/matters/check': () => ({ problems: [unreadable, traineesUnreadable].filter((one) => !readers.some((row) => row.groupId === one.groupId)) }),
      'GET /v1/admin/stores/5/access': () => ({ storeId: regionalOffice.id, rows: [...readers] }) satisfies A['StoreAccess'],
      'PUT /v1/admin/stores/5/access': ({ body }: Sent) => {
        puts.push(body)
        return new Promise<A['StoreAccess']>((resolve) => {
          answer = () => {
            for (const id of (body as A['StoreAccessChange']).groupIds) if (!readers.some((row) => row.groupId === id)) readers.push(accessRow(id, String(id), ['read']))
            resolve({ storeId: regionalOffice.id, rows: [...readers] })
          }
        })
      },
    })
    await fillIn(dialog)
    await userEvent.selectOptions(select(dialog, 'Store'), 'Regional Office')
    const everyoneGrant = await within(dialog).findByRole('button', { name: 'Let Everyone Read the Store' })
    await waitFor(() => expect(isDisabled(everyoneGrant)).toBe(false))
    await userEvent.click(everyoneGrant)
    await waitFor(() => expect(puts).toHaveLength(1))
    // The other waits while the first is on its way
    expect(isDisabled(within(dialog).getByRole('button', { name: 'Let Trainees Read the Store' }))).toBe(true)
    answer()
    await waitFor(() => expect(within(dialog).queryByRole('button', { name: 'Let Everyone Read the Store' })).toBeNull())
    const traineesGrant = within(dialog).getByRole('button', { name: 'Let Trainees Read the Store' })
    await waitFor(() => expect(isDisabled(traineesGrant)).toBe(false))
    await userEvent.click(traineesGrant)
    await waitFor(() => expect(puts).toHaveLength(2))
    // Read after the first was saved, so it keeps Everyone
    expect(puts[1]).toEqual({ groupIds: [everyone.id, trainees.id] })
  })

  it("creates the matter, then goes to the matter's page", async () => {
    let sent: unknown
    const { router, dialog } = await openNewMatter({
      'POST /v1/admin/matters': ({ body }: Sent) => {
        sent = body
        return matterOf()
      },
    })
    await fillIn(dialog)
    await waitFor(() => expect(isDisabled(create(dialog))).toBe(false))
    await userEvent.click(create(dialog))
    await waitFor(() => expect(router.state.location.pathname).toBe('/matters/34'))
    expect(sent).toEqual({
      clientId: marshLane.id,
      number: 1,
      name: 'Community Hall Lease',
      description: 'Lease of the hall on Marsh Lane',
      storeId: clientWork.id,
      ownerId: 5,
      templateId: transactional.id,
    } satisfies A['NewMatter'])
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await screen.findByText('Created 1004.1 – Community Hall Lease')).toBeTruthy()
  })

  it("can't be cancelled, by Cancel or by Esc, while Create Matter is on its way, so nobody is taken to a matter they think they cancelled", async () => {
    let answer!: (matter: A['Matter']) => void
    const answered = new Promise<A['Matter']>((resolve) => {
      answer = resolve
    })
    const { api, router, dialog } = await openNewMatter({ 'POST /v1/admin/matters': () => answered })
    await fillIn(dialog)
    await waitFor(() => expect(isDisabled(create(dialog))).toBe(false))
    await userEvent.click(create(dialog))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/matters')).toHaveLength(1))
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' })
    expect(isDisabled(cancel)).toBe(true)
    await userEvent.click(cancel)
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'New Matter' })).toBeTruthy()
    expect(router.state.location.pathname).toBe('/matters')

    // The matter is made: the dialog closes and its page opens, as when nothing held it up
    await act(async () => answer(matterOf()))
    await waitFor(() => expect(router.state.location.pathname).toBe('/matters/34'))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it("shows the server's refusal in the rule's words, and a field it says is wrong beside the field", async () => {
    let answer: ApiError = problem(409, 'refused', 'Privileged grants more than its parent (group Partners: WRITE).')
    const { dialog, view } = await openNewMatter({ 'POST /v1/admin/matters': () => answer })
    await fillIn(dialog)
    await waitFor(() => expect(isDisabled(create(dialog))).toBe(false))
    await userEvent.click(create(dialog))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('Privileged grants more than its parent (group Partners: WRITE).')
    await expectNoAxeViolations(view.baseElement)

    answer = new ApiError(422, 'validation', 'Server words.', { code: 'validation', fields: [{ field: 'name', code: 'required', detail: 'Server words.' }] })
    await userEvent.type(field(dialog, 'Name'), ' Two')
    // A change takes the refusal away
    expect(within(dialog).queryByRole('alert')).toBeNull()
    await waitFor(() => expect(isDisabled(create(dialog))).toBe(false))
    await userEvent.click(create(dialog))
    await waitFor(() => expect(field(dialog, 'Name').getAttribute('aria-invalid')).toBe('true'))
    expect(within(dialog).getByText('This needs a value.')).toBeTruthy()
    expect(within(dialog).queryByText('Server words.')).toBeNull()
  })

  it('makes a client with New Client…, from what was typed in the client search, and puts it in the form', async () => {
    const made = clientOf({ id: 12, number: 1005, name: 'Hall Trust' }, { matters: 0, documents: null, lastActivity: null })
    const { api, dialog } = await openNewMatter({ 'POST /v1/admin/clients': made })
    await userEvent.type(box(dialog, 'Client'), 'Hall Trust')
    // By keys: the next stop after the search is New Client…
    await userEvent.tab()
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'New Client…' }))
    await userEvent.keyboard('{Enter}')
    expect(field(await screen.findByRole('dialog', { name: 'New Client' }), 'Name').value).toBe('Hall Trust')
    await userEvent.click(within(screen.getByRole('dialog', { name: 'New Client' })).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New Client' })).toBeNull())
    // And by a click
    await userEvent.click(within(dialog).getByRole('button', { name: 'New Client…' }))
    const inner = await screen.findByRole('dialog', { name: 'New Client' })
    expect(field(inner, 'Name').value).toBe('Hall Trust')
    await waitFor(() => expect(field(inner, 'Number').value).toBe('1005'))
    await userEvent.click(within(inner).getByRole('button', { name: 'Create Client' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New Client' })).toBeNull())
    const outer = screen.getByRole('dialog', { name: 'New Matter' })
    expect(box(outer, 'Client').value).toBe('1005 – Hall Trust')
    await waitFor(() => expect(field(outer, 'Number').value).toBe('1'))
    expect(api.callsTo('GET /v1/admin/matters/next-number').at(-1)?.query).toEqual({ clientId: 12 })
  })

  it('makes a Store with New Store…, and puts it in the form', async () => {
    let made = false
    const { dialog } = await openNewMatter({
      'POST /v1/admin/stores': () => {
        made = true
        return regionalOffice
      },
      // The list asked again after has it
      'GET /v1/admin/stores': () => (made ? [clientWork, firmStore, regionalOffice] : [clientWork, firmStore]),
    })
    await userEvent.click(within(dialog).getByRole('button', { name: 'New Store…' }))
    const inner = await screen.findByRole('dialog', { name: 'New Store' })
    await userEvent.type(field(inner, 'Name'), 'Regional Office')
    await userEvent.click(within(inner).getByRole('button', { name: 'Create Store' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New Store' })).toBeNull())
    const outer = screen.getByRole('dialog', { name: 'New Matter' })
    expect(select(outer, 'Store').value).toBe(String(regionalOffice.id))
    expect(select(outer, 'Store').selectedOptions[0]?.textContent).toBe('Regional Office')
  })

  it("says a template is needed when there's none yet", async () => {
    const { dialog } = await openNewMatter({ 'GET /v1/admin/templates': [] })
    expect(await within(dialog).findByText("There's no workspace template yet. A matter is made from one, so one is needed first.")).toBeTruthy()
  })

  it('closes with Cancel, making nothing', async () => {
    const { api, dialog } = await openNewMatter()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.callsTo('POST /v1/admin/matters')).toEqual([])
  })

  it("uses the firm's own word for a matter", async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    await renderApp('/matters', CLIENTS)
    await userEvent.click(await within(main()).findByRole('button', { name: 'New Project' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Project' })
    expect(within(dialog).getByRole('button', { name: 'Create Project' })).toBeTruthy()
  })
})
