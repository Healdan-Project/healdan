// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { formatDateTime, formatSize } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import {
  backendOverview,
  dashboard,
  originalBackend,
  problem,
  storageOff,
  storageSettings,
  stuckFile,
  wasabiBackend,
} from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

const main = () => screen.getByRole('main')
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
const MB = 1024 ** 2

/** The backends' rows once the list has come, the header's left out */
async function rows() {
  const table = await within(main()).findByRole('grid', { name: 'Backends' })
  await within(table).findByRole('row', { name: /Original/ })
  return within(table).getAllByRole('row').slice(1)
}

/** The facts of a section, each term with what it says */
function factsIn(section: HTMLElement) {
  return within(section).getAllByRole('term').map((term) => [term.textContent, term.nextElementSibling?.textContent])
}

describe('The Backends list', () => {
  it('shows each backend with its kind, place, encryption, files, status and key, Original first, as the Storage tab does', async () => {
    const { view } = await renderApp('/backends')
    expect(await screen.findByRole('heading', { level: 1, name: 'Backends' })).toBeTruthy()
    const table = await within(main()).findByRole('grid', { name: 'Backends' })
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual([
      'Name',
      'Kind',
      'Where',
      'Encrypted',
      'Files',
      'Size',
      'Status',
      'Key Last Shown',
    ])
    expect((await rows()).map(cellsOf)).toEqual([
      ['Original', 'Local Folder', '/opt/logicaldoc/repository/docs', 'No', '812', formatSize(originalBackend.bytes), 'OK', ''],
      [
        'Wasabi EU',
        'S3',
        'ashcombe-reeve/healdan/ at https://s3.eu-central-1.wasabisys.com',
        'Yes',
        '3,902',
        formatSize(wasabiBackend.bytes),
        'OK',
        `${formatDateTime('2026-10-02T09:15:00Z')} to sam`,
      ],
      ['Archive', 'Local Folder', '/mnt/archive', 'No', '0', formatSize(0), 'Not Checked Yet', ''],
      [
        'Office NAS',
        'Local Folder',
        '/mnt/nas',
        'Yes',
        '0',
        formatSize(0),
        `Failing since ${formatDateTime('2026-11-19T09:30:00Z')}: The marker of this backend is missing. Its recovery key isn't confirmed, so no Store can use it yet.`,
        'Never',
      ],
    ])
    expect(within(main()).getByRole('button', { name: 'Add Backend' })).toBeTruthy()
    // Backends is the open screen, in the left pane's Storage group
    expect(within(pane()).getByRole('region', { name: 'Storage' })).toBeTruthy()
    expect(within(pane()).getByRole('link', { name: 'Backends' }).getAttribute('aria-current')).toBe('page')
    await expectNoAxeViolations(view.container)
  })

  it("says a backend that can't be reached is so, and since when, with a status that isn't checked yet otherwise", async () => {
    const down = { ...wasabiBackend, status: 'unreachable' as const, error: "This backend can't be reached.", since: '2026-11-19T10:00:00Z' }
    await renderApp('/backends', { 'GET /v1/admin/backends': backendOverview({ backends: [originalBackend, down] }) })
    const all = await rows()
    expect(cellsOf(all[1]!)[6]).toBe(`Can't Be Reached since ${formatDateTime('2026-11-19T10:00:00Z')}: This backend can't be reached.`)
  })

  it("names an S3 backend's bucket alone when its endpoint can't be read", async () => {
    await renderApp('/backends', { 'GET /v1/admin/backends': backendOverview({ backends: [originalBackend, { ...wasabiBackend, endpoint: null }] }) })
    const all = await rows()
    expect(cellsOf(all[1]!)[2]).toBe('ashcombe-reeve/healdan/')
  })

  it("opens a backend's page from its row", async () => {
    const { router } = await renderApp('/backends')
    const all = await rows()
    await userEvent.click(within(all[1]!).getByRole('rowheader'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/backends/2'))
  })

  it('asks for the backends again when Refresh is pressed', async () => {
    const { api } = await renderApp('/backends')
    await rows()
    expect(api.callsTo('GET /v1/admin/backends')).toHaveLength(1)
    await userEvent.click(within(main()).getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/backends')).toHaveLength(2))
  })

  it('counts the backends that need attention beside Backends in the left pane, from the Dashboard', async () => {
    const attention = [...dashboard.attention, { code: 'backend-unreachable' as const, count: 2, detail: 'Wasabi EU, Office NAS', link: '/admin/backends' }]
    await renderApp('/', { 'GET /v1/admin/dashboard': { ...dashboard, attention } })
    expect(await within(pane()).findByRole('link', { name: 'Backends, 2 need attention' })).toBeTruthy()
  })
})

describe('The Backends page when storage backends are off', () => {
  it("says the server keeps its files in LogicalDOC's own store, with no controls", async () => {
    const { view, api } = await renderApp('/backends', { 'GET /v1/admin/backends': storageOff, 'GET /v1/admin/storage/settings': storageSettings({ on: false }) })
    expect(await within(main()).findByText(/Storage backends are off on this server/)).toBeTruthy()
    expect(within(main()).queryByRole('grid')).toBeNull()
    expect(within(main()).queryByRole('button')).toBeNull()
    expect(within(main()).queryByRole('textbox')).toBeNull()
    expect(within(main()).queryByRole('heading', { name: 'Settings' })).toBeNull()
    expect(api.callsTo('GET /v1/admin/storage/stuck-files')).toEqual([])
    await expectNoAxeViolations(view.container)
  })

  it("says the screen can't be shown on a server without Healdan's storage module, rather than offer to try again", async () => {
    const unavailable = () => problem(503, 'storage-unavailable')
    const { view } = await renderApp('/backends', { 'GET /v1/admin/backends': unavailable, 'GET /v1/admin/storage/settings': unavailable })
    expect(await within(main()).findByText("The Backends screen can't be shown on this server, as it doesn't have Healdan's storage module.")).toBeTruthy()
    expect(within(main()).queryByRole('button')).toBeNull()
    expect(within(main()).queryByText('A server sentence that must not be shown raw.')).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it('offers to try again when the backends fail to load for another reason', async () => {
    await renderApp('/backends', { 'GET /v1/admin/backends': () => problem(500, 'server-error') })
    expect(await within(main()).findByRole('button', { name: 'Try Again' })).toBeTruthy()
  })
})

describe('The files with no Store', () => {
  it('says none wait or are stuck, with no Stuck Files or Retry', async () => {
    await renderApp('/backends')
    const section = await within(main()).findByRole('region', { name: 'Files with No Store' })
    expect(factsIn(section)).toEqual([
      ['Waiting to Move', 'None'],
      ['Stuck', 'None'],
    ])
    expect(within(section).queryByRole('button')).toBeNull()
  })

  it('lists the stuck ones with Stuck Files, how many of them, and retries them all', async () => {
    const files = [stuckFile(), stuckFile({ docId: 10390, fileName: null, resource: '1.0-thumb.png', attempts: 12, lastError: null })]
    const { api, view } = await renderApp('/backends', {
      'GET /v1/admin/backends': backendOverview({ otherFiles: { waiting: 12, waitingBytes: Math.round(3.1 * MB), stuck: 1500 } }),
      'GET /v1/admin/storage/stuck-files': files,
      'POST /v1/admin/storage/stuck-files/retry': undefined,
    })
    const section = await within(main()).findByRole('region', { name: 'Files with No Store' })
    expect(factsIn(section)).toEqual([
      ['Waiting to Move', `12 files (${formatSize(Math.round(3.1 * MB))})`],
      ['Stuck', '1,500 files'],
    ])
    await userEvent.click(within(section).getByRole('button', { name: 'Stuck Files' }))
    const dialog = await screen.findByRole('dialog', { name: 'Stuck Files with No Store' })
    const table = await within(dialog).findByRole('table')
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Document ID', 'File Name', 'Resource', 'Tries', 'Last Error'])
    const listed = within(table).getAllByRole('row').slice(1)
    expect(listed.map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))).toEqual([
      ['10388', 'Heads of Terms.pdf', '1.2', '10', 'Access denied by the S3 service.'],
      ['10390', 'Document deleted', '1.0-thumb.png', '12', ''],
    ])
    // The list holds the newest documents' files only, and says so
    expect(within(dialog).getByText('Listed: the files of the newest documents, 2 of 1,500.')).toBeTruthy()
    await expectNoAxeViolations(view.container)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/storage/stuck-files/retry')).toHaveLength(1))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Stuck Files with No Store' })).toBeNull())
    expect(await screen.findByText('The stuck files with no Store wait to move again')).toBeTruthy()
    // The counts are asked for again
    await waitFor(() => expect(api.callsTo('GET /v1/admin/backends').length).toBeGreaterThanOrEqual(2))
  })

  it('retries them from the page too, and says a refusal in its own words', async () => {
    const { api } = await renderApp('/backends', {
      'GET /v1/admin/backends': backendOverview({ otherFiles: { waiting: 0, waitingBytes: 0, stuck: 3 } }),
      'POST /v1/admin/storage/stuck-files/retry': () => problem(409, 'refused', 'Storage backends are off on this server.'),
    })
    const section = await within(main()).findByRole('region', { name: 'Files with No Store' })
    await userEvent.click(within(section).getByRole('button', { name: 'Retry' }))
    expect((await within(section).findByRole('alert')).textContent).toBe('Storage backends are off on this server.')
    expect(api.callsTo('POST /v1/admin/storage/stuck-files/retry')).toHaveLength(1)
  })
})

describe('The storage settings', () => {
  const field = (name: string) => within(within(main()).getByRole('region', { name: 'Settings' })).getByRole('textbox', { name: new RegExp(`^${name}`) }) as HTMLInputElement
  const save = () => within(within(main()).getByRole('region', { name: 'Settings' })).getByRole('button', { name: 'Save' })

  it('shows each setting with its limits, and saves all three', async () => {
    const saved = storageSettings({ holdDays: 45 })
    const { api, view } = await renderApp('/backends', { 'PUT /v1/admin/storage/settings': saved })
    await within(main()).findByRole('region', { name: 'Settings' })
    await waitFor(() => expect(field('Holding Period').value).toBe('30'))
    expect(field('Read Cache').value).toBe('20')
    expect(field('Parallel Transfers').value).toBe('4')
    // Each says its limits, as the server gives them
    expect(field('Holding Period').getAttribute('aria-describedby')).toBeTruthy()
    expect(within(main()).getByText(/from 1 to 365\./)).toBeTruthy()
    expect(within(main()).getByText(/from 1 to 10,000 GB\./)).toBeTruthy()
    expect(within(main()).getByText(/from 1 to 16\./)).toBeTruthy()
    // Nothing to save until something changes
    expect(save().getAttribute('disabled')).not.toBeNull()
    await userEvent.clear(field('Holding Period'))
    await userEvent.type(field('Holding Period'), '45')
    await userEvent.click(save())
    await waitFor(() => expect(api.callsTo('PUT /v1/admin/storage/settings')).toHaveLength(1))
    expect(api.callsTo('PUT /v1/admin/storage/settings')[0]!.body).toEqual({ holdDays: 45, cacheGb: 20, transfers: 4 })
    expect(await screen.findByText('Saved the storage settings')).toBeTruthy()
    expect(field('Holding Period').value).toBe('45')
    await expectNoAxeViolations(view.container)
  })

  it('says a setting outside its limits before Save, which is greyed out', async () => {
    const { api } = await renderApp('/backends', { 'GET /v1/admin/storage/settings': storageSettings({ limits: { holdDays: { min: 1, max: 365 }, cacheGb: { min: 1, max: 500 }, transfers: { min: 1, max: 16 } } }) })
    await within(main()).findByRole('region', { name: 'Settings' })
    await waitFor(() => expect(field('Read Cache').value).toBe('20'))
    await userEvent.clear(field('Read Cache'))
    await userEvent.type(field('Read Cache'), '501')
    expect(within(main()).getByText('Enter a whole number from 1 to 500.')).toBeTruthy()
    expect(field('Read Cache').getAttribute('aria-invalid')).toBe('true')
    expect(save().getAttribute('disabled')).not.toBeNull()
    await userEvent.clear(field('Read Cache'))
    await userEvent.type(field('Read Cache'), '0')
    expect(within(main()).getByText('Enter a whole number from 1 to 500.')).toBeTruthy()
    await userEvent.clear(field('Read Cache'))
    await userEvent.type(field('Read Cache'), '2.5')
    expect(within(main()).getByText('Enter a whole number from 1 to 500.')).toBeTruthy()
    await userEvent.clear(field('Read Cache'))
    await userEvent.type(field('Read Cache'), '500')
    expect(within(main()).queryByText('Enter a whole number from 1 to 500.')).toBeNull()
    expect(save().getAttribute('disabled')).toBeNull()
    expect(api.callsTo('PUT /v1/admin/storage/settings')).toEqual([])
  })

  it("says the server's refusal of a setting out of its range beside the setting", async () => {
    const refusal = new ApiError(422, 'validation', 'Some details need fixing.', {
      code: 'validation',
      fields: [{ field: 'transfers', code: 'out-of-range', detail: 'Parallel transfers is out of range.' }],
    })
    await renderApp('/backends', { 'PUT /v1/admin/storage/settings': () => refusal })
    await within(main()).findByRole('region', { name: 'Settings' })
    await waitFor(() => expect(field('Parallel Transfers').value).toBe('4'))
    await userEvent.clear(field('Parallel Transfers'))
    await userEvent.type(field('Parallel Transfers'), '8')
    await userEvent.click(save())
    expect(await within(main()).findByText('Enter a whole number from 1 to 16.')).toBeTruthy()
    expect(field('Parallel Transfers').getAttribute('aria-invalid')).toBe('true')
    expect(within(main()).queryByText('Parallel transfers is out of range.')).toBeNull()
  })

  it('keeps what is typed when the window comes back', async () => {
    await renderApp('/backends')
    await within(main()).findByRole('region', { name: 'Settings' })
    await waitFor(() => expect(field('Holding Period').value).toBe('30'))
    await userEvent.clear(field('Holding Period'))
    await userEvent.type(field('Holding Period'), '60')
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(field('Holding Period').value).toBe('60')
  })
})
