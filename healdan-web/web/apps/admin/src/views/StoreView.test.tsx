// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDateTime, formatSize } from '@healdan/ui'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import {
  accessRow,
  clientWorkInfo,
  matterList,
  matterRows,
  partners,
  problem,
  storeInfoOf,
  storeStorage,
  storeStorageOf,
  stuckFile,
  type FakeApi,
  type Routes,
} from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']
type Sent = { body?: unknown }

const main = () => screen.getByRole('main')
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
const isDisabled = (element: HTMLElement) => element.getAttribute('disabled') !== null || element.closest('fieldset[disabled]') !== null
/** Long enough for the typing to pause and the answer to come */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const GB = 1024 ** 3

/** The matters the server lists for a storeId */
const MATTERS: Routes = {
  'GET /v1/admin/matters': ({ query }: { query?: Record<string, unknown> }) =>
    matterList(query?.storeId === undefined ? matterRows : matterRows.filter((one) => one.store.id === query.storeId)),
}

/** Client Work's page (Store 4), once its title has come */
async function openStore(path = '/stores/4', routes: Routes = {}) {
  const rendered = await renderApp(path, { ...MATTERS, ...routes })
  await screen.findByRole('heading', { level: 1, name: path.startsWith('/stores/3') ? 'Firm' : 'Client Work' })
  return rendered
}

/** Each term of the facts with what it says, in `within` (the page by default) */
const factsIn = (where: HTMLElement = main()) => within(where).getAllByRole('term').map((term) => [term.textContent, term.nextElementSibling?.textContent])

/** A request that waits until the test lets it answer */
function held<T>() {
  let answer!: (value: T) => void
  const promise = new Promise<T>((resolve) => (answer = resolve))
  return { promise, answer }
}

const lastQuery = (api: FakeApi) => api.callsTo('GET /v1/admin/matters').at(-1)?.query

describe("A Store's page", () => {
  it('shows its name, its facts, its actions and its tabs, Matters first, with the way back to Stores, which stays the open screen', async () => {
    const { api, view } = await openStore()
    for (const name of ['Rename', 'Change Backend', 'Delete', 'New Matter']) expect(within(main()).getByRole('button', { name })).toBeTruthy()
    const crumbs = within(main()).getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Stores' }).getAttribute('href')).toBe('/admin/stores')
    expect(within(pane()).getByRole('link', { name: 'Stores' }).getAttribute('aria-current')).toBe('page')
    expect(factsIn()).toEqual([
      ['Backend', 'Wasabi EU (Encrypted)'],
      ['Files', '3 files stuck'],
      ['Matters', '6'],
      ['Documents', '1,004'],
      ['Size', formatSize(clientWorkInfo.bytes!)],
      ['Who Can Read', 'Everyone'],
    ])
    expect(within(main()).getByText(`Documents and Size as of ${formatDateTime('2026-11-19T10:41:00Z')}`)).toBeTruthy()

    const tabs = within(main()).getByRole('tablist', { name: 'Sections' })
    expect(within(tabs).getAllByRole('tab').map((tab) => [tab.textContent, tab.getAttribute('aria-selected')])).toEqual([
      ['Matters', 'true'],
      ['Who Can See It', 'false'],
      ['Storage', 'false'],
    ])
    // Its matters, through the Matters list's own route, without a Store column, which would say the same on every row
    const table = await within(main()).findByRole('grid', { name: 'Matters in Client Work' })
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Matter', 'Name', 'Client', 'Owner', 'Template', 'Documents', 'Size', 'Last Activity'])
    await within(table).findByRole('row', { name: /1001\.1/ })
    const listed = within(table).getAllByRole('row').slice(1)
    expect(listed.map((row) => cellsOf(row)[0])).toEqual(['1001.1', '1001.2', '1002.1', '1002.2', '1003.1', '1003.2'])
    expect(api.callsTo('GET /v1/admin/matters').map((call) => call.query)).toEqual([{ storeId: 4, offset: 0, limit: 100 }])
    expect(within(main()).getByText('6 Matters')).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("opens a matter's page from its row, and sorts the matters by a column, the address keeping the order", async () => {
    const { api, router } = await openStore()
    const table = await within(main()).findByRole('grid', { name: 'Matters in Client Work' })
    await within(table).findByRole('row', { name: /1001\.1/ })
    await userEvent.click(within(table).getByRole('columnheader', { name: 'Documents' }))
    await waitFor(() => expect(lastQuery(api)).toEqual({ storeId: 4, sort: 'documents', order: 'asc', offset: 0, limit: 100 }))
    expect(router.state.location.search).toEqual({ sort: 'documents', order: 'asc' })
    await userEvent.click(within(table).getByRole('rowheader', { name: '1001.1' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/matters/11'))
  })

  it("opens New Matter set to this Store", async () => {
    await openStore()
    await userEvent.click(within(main()).getByRole('button', { name: 'New Matter' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Matter' })
    const store = within(dialog).getByRole('combobox', { name: 'Store' }) as HTMLSelectElement
    await waitFor(() => expect(store.selectedOptions[0]?.textContent).toBe('Client Work'))
  })

  it('says a Store with no matter has none, and that only administrators can read one nobody else can', async () => {
    await openStore('/stores/4', { 'GET /v1/admin/stores/4': storeInfoOf({ id: 4, name: 'Client Work' }), 'GET /v1/admin/matters': matterList([]) })
    expect(factsIn().slice(1)).toEqual([
      ['Files', 'Up to Date'],
      ['Matters', '0'],
      ['Documents', '0'],
      ['Size', formatSize(0)],
      ['Who Can Read', 'Administrators Only'],
    ])
    expect(await within(main()).findByText('No matter is in this Store yet.')).toBeTruthy()
  })

  it("leaves out its backend, its files and Change Backend on a server that doesn't use storage backends", async () => {
    const { view } = await openStore('/stores/4', { 'GET /v1/admin/stores/4': { ...clientWorkInfo, backend: null, pending: null } })
    expect(within(main()).queryByRole('button', { name: 'Change Backend' })).toBeNull()
    expect(factsIn().map(([term]) => term)).toEqual(['Matters', 'Documents', 'Size', 'Who Can Read'])
    expect(within(within(main()).getByRole('tablist')).getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Matters', 'Who Can See It'])
    await expectNoAxeViolations(view.container)
  })

  it('asks again for the Store when the person comes back to the window', async () => {
    let store = clientWorkInfo
    const { api } = await openStore('/stores/4', { 'GET /v1/admin/stores/4': () => store })
    store = { ...clientWorkInfo, name: 'Client Matters' }
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findByRole('heading', { level: 1, name: 'Client Matters' })).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/stores/4')).toHaveLength(2)
  })

  it("says there's no such Store, for one in the Trash or an address that names none", async () => {
    const { view } = await renderApp('/stores/99')
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Store Not Found' })).toBeTruthy()
    expect(within(main()).getByText("There's no such Store, or it's in the Trash.")).toBeTruthy()
    expect(within(main()).getByRole('link', { name: 'Go to Stores' }).getAttribute('href')).toBe('/admin/stores')
    await expectNoAxeViolations(view.container)
    view.unmount()
    await renderApp('/stores/abc')
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Store Not Found' })).toBeTruthy()
  })
})

describe("A Store's Rename", () => {
  it('renames it, saying before Rename that another Store has the name', async () => {
    let sent: unknown
    const { view } = await openStore('/stores/4', {
      'PATCH /v1/admin/stores/4': ({ body }: Sent) => {
        sent = body
        return { ...clientWorkInfo, name: 'Client Matters' } satisfies A['StoreInfo']
      },
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Rename' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rename Client Work' })
    const name = within(dialog).getByRole('textbox', { name: 'Name' }) as HTMLInputElement
    expect(name.value).toBe('Client Work')
    const rename = within(dialog).getByRole('button', { name: 'Rename' })
    // Its own name changes nothing
    expect(isDisabled(rename)).toBe(true)
    await userEvent.clear(name)
    await userEvent.type(name, 'firm')
    expect(within(dialog).getByText("There's already a Store called Firm.")).toBeTruthy()
    expect(isDisabled(rename)).toBe(true)
    await expectNoAxeViolations(view.baseElement)
    await userEvent.clear(name)
    await userEvent.type(name, ' Client Matters ')
    await userEvent.click(rename)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rename Client Work' })).toBeNull())
    expect(sent).toEqual({ name: 'Client Matters' } satisfies A['FolderRename'])
    expect(await screen.findByRole('heading', { level: 1, name: 'Client Matters' })).toBeTruthy()
    expect(await screen.findByText('Renamed Client Work to Client Matters')).toBeTruthy()
  })

  it("says the server's refusal in the rule's words, and the dialog stays", async () => {
    await openStore('/stores/4', { 'PATCH /v1/admin/stores/4': () => problem(409, 'refused', 'LogicalDOC\'s Default workspace is called Default.') })
    await userEvent.click(within(main()).getByRole('button', { name: 'Rename' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rename Client Work' })
    const name = within(dialog).getByRole('textbox', { name: 'Name' })
    await userEvent.clear(name)
    await userEvent.type(name, 'Default')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe("LogicalDOC's Default workspace is called Default.")
  })
})

describe("A Store's Delete", () => {
  it('asks first, naming the matters it takes and where it can be restored from, then moves it to the Trash and opens the Stores list', async () => {
    const { api, router, view } = await openStore('/stores/4', { 'DELETE /v1/admin/stores/4': { trashItemId: 960, folders: 48, documents: 1004 } satisfies A['DeletedFolder'] })
    await userEvent.click(within(main()).getByRole('button', { name: 'Delete' }))
    const question = await screen.findByRole('alertdialog', { name: 'Move Client Work to the Trash?' })
    expect(within(question).getByText('Client Work goes to the Trash with everything in it: its 6 matters, and everything in them. Their numbers stay taken.')).toBeTruthy()
    expect(within(question).getByText('It can be restored, with everything it took, from Deleted Stores on the Stores page.')).toBeTruthy()
    expect(api.callsTo('DELETE /v1/admin/stores/4')).toEqual([])
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/stores'))
    expect(api.callsTo('DELETE /v1/admin/stores/4')).toHaveLength(1)
    expect(await screen.findByText('Moved Client Work to the Trash')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores/deleted').length).toBeGreaterThanOrEqual(1))
  })

  it('says a Store with no matter holds none', async () => {
    await openStore('/stores/4', { 'GET /v1/admin/stores/4': storeInfoOf({ id: 4, name: 'Client Work' }) })
    await userEvent.click(within(main()).getByRole('button', { name: 'Delete' }))
    const question = await screen.findByRole('alertdialog', { name: 'Move Client Work to the Trash?' })
    expect(within(question).getByText('Client Work goes to the Trash. It holds no matter.')).toBeTruthy()
  })

  it("says a refusal, a document checked out inside, in the rule's words, and stays", async () => {
    const { router } = await openStore('/stores/4', { 'DELETE /v1/admin/stores/4': () => problem(409, 'refused', 'Heads of Terms.pdf in 1001.1 is checked out.') })
    await userEvent.click(within(main()).getByRole('button', { name: 'Delete' }))
    const question = await screen.findByRole('alertdialog', { name: 'Move Client Work to the Trash?' })
    await userEvent.click(within(question).getByRole('button', { name: 'Move to Trash' }))
    expect((await within(question).findByRole('alert')).textContent).toBe('Heads of Terms.pdf in 1001.1 is checked out.')
    expect(router.state.location.pathname).toBe('/stores/4')
  })
})

describe('Who can see a Store', () => {
  const readers = [accessRow(2, 'Everyone', ['read']), accessRow(partners.id, partners.name, ['read', 'download']), accessRow(105, 'Helen Ashcombe', ['read'], 5)]
  const ACCESS: Routes = { 'GET /v1/admin/stores/4/access': { storeId: 4, rows: readers } satisfies A['StoreAccess'] }
  const panel = () => within(main()).getByRole('tabpanel', { name: 'Who Can See It' })
  const listed = () => within(within(panel()).getByRole('list', { name: 'Who Can See Client Work' })).getAllByRole('listitem').map((item) => item.textContent)

  it('lists each group and person that can see it, Custom where it has more than Can See, and saves who can from then on', async () => {
    let sent: unknown
    const { api, router, view } = await openStore('/stores/4?tab=access', {
      ...ACCESS,
      'PUT /v1/admin/stores/4/access': ({ body }: Sent) => {
        sent = body
        return { storeId: 4, rows: [readers[1]!, accessRow(4, 'Trainees', ['read'])] } satisfies A['StoreAccess']
      },
    })
    expect(within(main()).getByRole('tab', { name: 'Who Can See It' }).getAttribute('aria-selected')).toBe('true')
    expect(within(panel()).getByRole('heading', { level: 2, name: 'Who Can See This Store' })).toBeTruthy()
    await waitFor(() => expect(listed()).toEqual(['Everyone', 'Partners Custom', 'Helen Ashcombe']))
    expect(within(panel()).getByText("Custom: it has permissions besides Read on the Store, which Save keeps.")).toBeTruthy()
    const save = within(panel()).getByRole('button', { name: 'Save' })
    expect(isDisabled(save)).toBe(true)
    await expectNoAxeViolations(view.container)

    await userEvent.click(within(panel()).getByRole('button', { name: 'Remove Everyone' }))
    expect(listed()).toEqual(['Partners Custom', 'Helen Ashcombe'])
    // The focus goes to the next one's Remove
    expect(document.activeElement).toBe(within(panel()).getByRole('button', { name: 'Remove Partners' }))
    await userEvent.click(within(panel()).getByRole('button', { name: 'Remove Helen Ashcombe' }))
    await userEvent.type(within(panel()).getByRole('textbox', { name: 'Add a Group' }), 'train')
    await pause()
    await within(panel()).findByRole('listbox', { name: '1 Result' })
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(listed()).toEqual(['Partners Custom', 'Trainees'])
    expect(isDisabled(save)).toBe(false)
    await userEvent.click(save)
    await waitFor(() => expect(api.callsTo('PUT /v1/admin/stores/4/access')).toHaveLength(1))
    expect(sent).toEqual({ groupIds: [partners.id, 4] } satisfies A['StoreAccessChange'])
    expect(await screen.findByText('Saved who can see Client Work')).toBeTruthy()
    await waitFor(() => expect(listed()).toEqual(['Partners Custom', 'Trainees']))
    expect(isDisabled(within(panel()).getByRole('button', { name: 'Save' }))).toBe(true)
    // The Store's own facts are asked for again, as who can read it changed
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores/4').length).toBeGreaterThanOrEqual(2))
    expect(router.state.location.search).toEqual({ tab: 'access' })
  })

  it("says the server's refusal beside Save in the rule's words: a group that can read one of its matters can't be taken off it", async () => {
    const refusal = 'Everyone can read 1001.1 Charter Dispute – MV Northern Star, so it has to be able to see the Store Client Work.'
    const { view } = await openStore('/stores/4?tab=access', { ...ACCESS, 'PUT /v1/admin/stores/4/access': () => problem(409, 'refused', refusal) })
    await waitFor(() => expect(listed()).toHaveLength(3))
    await userEvent.click(within(panel()).getByRole('button', { name: 'Remove Everyone' }))
    await userEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    expect((await within(panel()).findByRole('alert')).textContent).toBe(refusal)
    // What was changed stays, to put right
    expect(listed()).toEqual(['Partners Custom', 'Helen Ashcombe'])
    await expectNoAxeViolations(view.container)
    // Discard Changes puts back what the server has
    await userEvent.click(within(panel()).getByRole('button', { name: 'Discard Changes' }))
    expect(listed()).toEqual(['Everyone', 'Partners Custom', 'Helen Ashcombe'])
    expect(within(panel()).queryByRole('alert')).toBeNull()
  })

  it('holds its list while the save is on its way', async () => {
    const answer = held<A['StoreAccess']>()
    await openStore('/stores/4?tab=access', { ...ACCESS, 'PUT /v1/admin/stores/4/access': () => answer.promise })
    await waitFor(() => expect(listed()).toHaveLength(3))
    await userEvent.click(within(panel()).getByRole('button', { name: 'Remove Everyone' }))
    await userEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(isDisabled(within(panel()).getByRole('button', { name: 'Remove Partners' }))).toBe(true))
    expect(isDisabled(within(panel()).getByRole('textbox', { name: 'Add a Group' }))).toBe(true)
    await act(async () => answer.answer({ storeId: 4, rows: readers.slice(1) }))
    await waitFor(() => expect(isDisabled(within(panel()).getByRole('button', { name: 'Remove Partners' }))).toBe(false))
  })

  it('says only administrators can see a Store nobody else can', async () => {
    await openStore('/stores/4?tab=access', { 'GET /v1/admin/stores/4/access': { storeId: 4, rows: [] } satisfies A['StoreAccess'] })
    expect(await within(panel()).findByText('Only administrators can see it until a group is added.')).toBeTruthy()
  })
})

describe("A Store's storage", () => {
  const panel = () => within(main()).getByRole('tabpanel', { name: 'Storage' })

  it('shows its backend, what it holds, what waits and what is stuck, and retries the stuck files', async () => {
    const files = [stuckFile(), stuckFile({ docId: 10390, resource: '1.0-thumb.png' })]
    const { api, view } = await openStore('/stores/4?tab=storage', {
      'GET /v1/admin/stores/4/stuck-files': files,
      'POST /v1/admin/stores/4/stuck-files/retry': undefined,
    })
    await waitFor(() =>
      expect(factsIn(panel())).toEqual([
        ['Backend', 'Wasabi EU (Encrypted)'],
        ['Holds', `3,902 files · ${formatSize(Math.round(11.8 * GB))}`],
        ['Waiting to Move', 'None'],
        ['Stuck', '3 files'],
      ]),
    )
    const stuck = within(panel()).getByRole('region', { name: 'Stuck Files' })
    const table = await within(stuck).findByRole('table')
    expect(within(table).getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell')[0]?.textContent)).toEqual(['10388', '10390'])
    expect(within(stuck).getByText('Listed: the files of the newest documents, 2 of 3.')).toBeTruthy()
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(stuck).getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/stores/4/stuck-files/retry')).toHaveLength(1))
    expect(await screen.findByText('The stuck files of Client Work wait to move again')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores/4/stuck-files')).toHaveLength(2))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores/4/backend').length).toBeGreaterThanOrEqual(2))
  })

  it("says a refusal of Retry in the rule's words", async () => {
    await openStore('/stores/4?tab=storage', { 'POST /v1/admin/stores/4/stuck-files/retry': () => problem(409, 'refused', 'Storage backends are off on this server.') })
    const stuck = await within(panel()).findByRole('region', { name: 'Stuck Files' })
    await userEvent.click(within(stuck).getByRole('button', { name: 'Retry' }))
    expect((await within(stuck).findByRole('alert')).textContent).toBe('Storage backends are off on this server.')
  })

  it('shows no stuck files where none are', async () => {
    await openStore('/stores/3?tab=storage')
    await waitFor(() => expect(factsIn(panel()).map(([term]) => term)).toEqual(['Backend', 'Holds', 'Waiting to Move', 'Stuck']))
    expect(within(panel()).queryByRole('region', { name: 'Stuck Files' })).toBeNull()
    expect(within(panel()).queryByRole('button')).toBeNull()
  })
})

describe('Change Backend', () => {
  const choice = (dialog: HTMLElement, name: RegExp) => within(dialog).getByRole('radio', { name }) as HTMLInputElement

  it('offers the backends a Store can use, greys out one whose key is not confirmed with why, and counts what would move before the change', async () => {
    let sent: unknown
    const { api, view } = await openStore('/stores/4', {
      'PUT /v1/admin/stores/4/backend': ({ body }: Sent) => {
        sent = body
        return storeStorageOf(clientWorkInfo, { backend: { id: 3, name: 'Archive', kind: 'local', encrypted: false }, pending: { waiting: 3902, waitingBytes: Math.round(11.8 * GB), stuck: 0 } })
      },
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Change Backend' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change the Backend of Client Work' })
    const group = await within(dialog).findByRole('radiogroup', { name: 'Backend' })
    // Each by its name, with why it's one the Store can't be given said after it
    const said = (radio: HTMLElement) => document.getElementById(radio.getAttribute('aria-describedby') ?? '')?.textContent
    expect(within(group).getAllByRole('radio').map((radio) => [radio.closest('label')?.textContent, said(radio)])).toEqual([
      ['Original', undefined],
      ['Wasabi EU (Encrypted)', 'Where its files are kept now.'],
      ['Archive', undefined],
      ['Office NAS (Encrypted)', "Its recovery key isn't confirmed, so no Store can use it yet."],
    ])
    expect(choice(dialog, /^Wasabi EU/).checked).toBe(true)
    expect(choice(dialog, /^Office NAS/).disabled).toBe(true)
    const change = within(dialog).getByRole('button', { name: 'Change Backend' })
    expect(isDisabled(change)).toBe(true)
    await expectNoAxeViolations(view.baseElement)

    await userEvent.click(choice(dialog, /^Archive/))
    expect(await within(dialog).findByText(`3,902 files (${formatSize(Math.round(11.8 * GB))}) will move to Archive in the background. Until each has moved, it's read from where it is now.`)).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/stores/4/backend').at(-1)?.query).toEqual({ backendId: 3 })
    expect(api.callsTo('PUT /v1/admin/stores/4/backend')).toEqual([])
    await waitFor(() => expect(isDisabled(change)).toBe(false))
    await userEvent.click(change)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Change the Backend of Client Work' })).toBeNull())
    expect(sent).toEqual({ backendId: 3 } satisfies A['StoreBackendChange'])
    expect(await screen.findByText('Client Work now keeps its files on Archive')).toBeTruthy()
    // What shows the Store's backend is asked again
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores/4').length).toBeGreaterThanOrEqual(2))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/backends').length).toBeGreaterThanOrEqual(2))
  })

  it('says the change takes effect at once when no file has to move', async () => {
    await openStore('/stores/3', { 'GET /v1/admin/stores/3/backend': ({ query }: { query?: Record<string, unknown> }) => ({ ...storeStorage[3]!, files: 0, bytes: 0, switchPreview: query?.backendId === undefined ? null : { backendId: Number(query.backendId), files: 0, bytes: 0 } }) })
    await userEvent.click(within(main()).getByRole('button', { name: 'Change Backend' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change the Backend of Firm' })
    await userEvent.click(await within(dialog).findByRole('radio', { name: /^Archive/ }))
    expect(await within(dialog).findByText('No file has to move, so the change takes effect at once.')).toBeTruthy()
  })

  it("says the server's refusal in the rule's words, and stays; it can't be cancelled while the change is on its way", async () => {
    let answer: unknown = problem(409, 'refused', 'Archive is still being added.')
    const later = held<A['StoreStorage']>()
    const { api } = await openStore('/stores/4', { 'PUT /v1/admin/stores/4/backend': () => (answer === 'hold' ? later.promise : answer) })
    await userEvent.click(within(main()).getByRole('button', { name: 'Change Backend' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change the Backend of Client Work' })
    await userEvent.click(await within(dialog).findByRole('radio', { name: /^Archive/ }))
    const change = within(dialog).getByRole('button', { name: 'Change Backend' })
    await waitFor(() => expect(isDisabled(change)).toBe(false))
    await userEvent.click(change)
    expect((await within(dialog).findByRole('alert')).textContent).toBe('Archive is still being added.')

    answer = 'hold'
    await userEvent.click(change)
    await waitFor(() => expect(api.callsTo('PUT /v1/admin/stores/4/backend')).toHaveLength(2))
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' })
    expect(isDisabled(cancel)).toBe(true)
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Change the Backend of Client Work' })).toBeTruthy()
    await act(async () => later.answer(storeStorage[4]!))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Change the Backend of Client Work' })).toBeNull())
  })

  it("says the backends can't be listed on a server without Healdan's storage module", async () => {
    await openStore('/stores/4', { 'GET /v1/admin/backends': () => problem(503, 'storage-unavailable') })
    await userEvent.click(within(main()).getByRole('button', { name: 'Change Backend' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change the Backend of Client Work' })
    expect(await within(dialog).findByText("This server doesn't have Healdan's storage module.")).toBeTruthy()
    expect(within(dialog).queryByRole('button', { name: 'Change Backend' })).toBeNull()
  })
})

describe("Another Store's page", () => {
  it("starts afresh, by Back too, with no dialog carried from the one before", async () => {
    const { router } = await openStore('/stores/4')
    await userEvent.click(within(main()).getByRole('button', { name: 'Rename' }))
    expect(await screen.findByRole('dialog', { name: 'Rename Client Work' })).toBeTruthy()
    await act(async () => void router.navigate({ to: '/stores/$id', params: { id: '3' } }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Firm' })).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(factsIn()[0]).toEqual(['Backend', 'Original'])
  })
})
