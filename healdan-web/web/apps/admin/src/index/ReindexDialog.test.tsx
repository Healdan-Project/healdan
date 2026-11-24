// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, MessagesProvider, ServicesProvider } from '@healdan/shell'
import type { ReindexKind } from '../addresses'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { problemLookup } from '../strings'
import { expectNoAxeViolations } from '../test/axe'
import { fakeApi, problem, reindexJobOf, reindexRoutes, searchIndexRoutes, workspaceRoutes, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { slowAnswer } from '../test/slowAnswer'
import { jobKeys } from '../monitoring/jobQueries'
import { adminKeys } from '../queries'
import { indexKeys } from './queries'
import { ReindexDialog } from './ReindexDialog'

type A = adminComponents['schemas']
type Sent = { body?: unknown }

// axe runs once for each state drawn. jsdom draws no colours, so the two themes' colours aren't checked here: every colour
// is a token, which test/colours.test.ts checks on each surface of both themes, and the phase's browser spec runs axe in
// the light theme and the dark one.

/** Long enough for the typing to pause and the answer to come: a search and the preview wait as the app's pickers do */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))

/** The dialog on its own, as the Search Index's address opens it, over the fake API */
function renderDialog(open: { kind: ReindexKind; id?: number; matter?: number }, routes: Routes = {}) {
  const api = fakeApi({ ...reindexRoutes(), ...routes })
  const queryClient = createQueryClient({ retry: false })
  // The reindexes waiting or running, as the Search Index has read them, which a start joins
  queryClient.setQueryData<A['ReindexJob'][]>(indexKeys.active, [])
  const onClose = vi.fn()
  const onStarted = vi.fn<(job: A['ReindexJob']) => void>()
  const view = render(
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={queryClient}>
        <MessagesProvider lookup={problemLookup}>
          <ReindexDialog {...open} onStarted={onStarted} onClose={onClose} />
        </MessagesProvider>
      </QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, queryClient, onClose, onStarted, view }
}

const dialog = () => screen.getByRole('dialog', { name: 'Reindex' })
const choice = (name: string) => within(dialog()).getByRole('radio', { name })
const pressed = () => within(within(dialog()).getByRole('radiogroup', { name: 'What to Reindex' })).getAllByRole('radio').filter((one) => one.getAttribute('aria-checked') === 'true').map((one) => one.textContent)
/** The Reindex button, whatever its count says, and with Empty the Index First ticked */
const start = () => within(dialog()).getByRole('button', { name: /^(Empty the Index and )?Reindex/ })
const isDisabled = (button: HTMLElement) => button.getAttribute('disabled') !== null
const options = () => within(within(dialog()).getByRole('listbox')).getAllByRole('option')
const option = (name: RegExp | string) => within(within(dialog()).getByRole('listbox')).getByRole('option', { name })
const selected = () => options().filter((one) => one.getAttribute('aria-selected') === 'true').map((one) => one.textContent)
const previews = (api: ReturnType<typeof fakeApi>) => api.callsTo('POST /v1/admin/reindex/preview').map((call) => call.body)
const status = () => within(dialog()).getAllByRole('status').map((one) => one.textContent).filter(Boolean)

describe('Reindex', () => {
  it('opens on the choice the address names: Matter, from Recent Matters, then a search by number, name or client', async () => {
    const { api, view } = renderDialog({ kind: 'matter' })
    expect(pressed()).toEqual(['Matter'])
    expect(within(dialog()).getAllByRole('radio').map((one) => one.textContent)).toEqual(['Documents', 'Folder', 'Matter', 'Store', 'Everything'])
    // Recent Matters first, each with its client; they have no count of their own
    expect(await within(dialog()).findByRole('heading', { name: 'Recent Matters' })).toBeTruthy()
    expect(options().map((one) => one.textContent)).toEqual([
      '1003.1 – Series A InvestmentKestrel Software Ltd',
      '1001.1 – Charter Dispute – MV Northern StarSaltmarsh Shipping Ltd',
      '1002.2 – Employment TribunalBrightwell Bakeries Ltd',
    ])
    // Nothing chosen: nothing to count, and nothing to start
    expect(start().textContent).toBe('Reindex')
    expect(isDisabled(start())).toBe(true)
    expect(previews(api)).toEqual([])
    expect(within(dialog()).getByText('Search by matter number, name or client.')).toBeTruthy()
    await expectNoAxeViolations(view.baseElement)

    // A search marks the words in each row, its client's name too, and counts each matter's documents
    await userEvent.type(within(dialog()).getByRole('textbox', { name: 'Matter' }), 'char')
    await pause()
    await waitFor(() => expect(within(dialog()).getByRole('heading', { name: '1 Result' })).toBeTruthy())
    expect(api.callsTo('GET /v1/admin/matters').map((call) => call.query)).toEqual([{ q: 'char', limit: 20 }])
    expect(options().map((one) => one.textContent)).toEqual(['1001.1 – Charter Dispute – MV Northern StarSaltmarsh Shipping Ltd412 documents'])
    expect([...within(dialog()).getByRole('listbox').querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['Char'])
    await expectNoAxeViolations(view.baseElement)

    await userEvent.click(option(/^1001\.1/))
    await pause()
    expect(previews(api)).toEqual([{ kind: 'matter', targetId: 11 }])
    await waitFor(() => expect(start().textContent).toBe('Reindex 412 Documents'))
    expect(isDisabled(start())).toBe(false)
    expect(status()).toContain('About 12 minutes for 412 documents. Search keeps working meanwhile.')
    // What it leaves out, as shortcuts
    expect(within(dialog()).getByText('2 more are left out: shortcuts, and documents set to Skip, archived or being changed.')).toBeTruthy()
    expect(selected()).toEqual(['1001.1 – Charter Dispute – MV Northern StarSaltmarsh Shipping Ltd412 documents'])
    await expectNoAxeViolations(view.baseElement)
  })

  it('starts the reindex, puts it among those waiting or running, asks for the lists of reindexes, Background Jobs and the Dashboard again, hands it on and closes', async () => {
    let sent: unknown
    const { onStarted, onClose, queryClient } = renderDialog(
      { kind: 'matter', id: 11 },
      {
        'POST /v1/admin/reindex': ({ body }: Sent) => {
          sent = body
          return reindexJobOf()
        },
      },
    )
    // Background Jobs' list and the Dashboard, as read before, which show the reindex job
    queryClient.setQueryData(jobKeys.jobs, [])
    queryClient.setQueryData(adminKeys.dashboard, {})
    await waitFor(() => expect(start().textContent).toBe('Reindex 412 Documents'))
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.click(start())
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(queryClient.getQueryState(jobKeys.jobs)?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(adminKeys.dashboard)?.isInvalidated).toBe(true)
    expect(sent).toEqual({ kind: 'matter', targetId: 11 } satisfies A['ReindexScope'])
    expect(onStarted).toHaveBeenCalledWith(reindexJobOf())
    expect(onStarted.mock.invocationCallOrder[0]).toBeLessThan(onClose.mock.invocationCallOrder[0]!)
    expect(queryClient.getQueryData(indexKeys.active)).toEqual([reindexJobOf()])
    expect(queryClient.getQueryState(indexKeys.active)?.isInvalidated).toBe(true)
  })

  it('opens with the matter the address names chosen, shown first though it is no recent one', async () => {
    const { api } = renderDialog({ kind: 'matter', id: 12 })
    await waitFor(() => expect(selected()).toEqual(['1001.2 – Vessel SaleSaltmarsh Shipping Ltd96 documents']))
    expect(options()[0]?.textContent).toBe('1001.2 – Vessel SaleSaltmarsh Shipping Ltd96 documents')
    await waitFor(() => expect(start().textContent).toBe('Reindex 96 Documents'))
    expect(previews(api)).toEqual([{ kind: 'matter', targetId: 12 }])
  })

  it("Folder finds the matter, then lists its folders, indented and filtered by words, and counts the one picked", async () => {
    const { api, view } = renderDialog({ kind: 'folder' })
    const finder = within(dialog()).getByRole('textbox', { name: 'Matter' })
    await waitFor(() => expect(options()).toHaveLength(3))
    await userEvent.click(option(/^1001\.1/))
    // The matter is chosen, and the focus goes on to its folders
    expect(await within(dialog()).findByText('Matter: 1001.1 – Charter Dispute – MV Northern Star')).toBeTruthy()
    const filter = await within(dialog()).findByRole('textbox', { name: 'Folder' })
    expect(document.activeElement).toBe(filter)
    expect(finder.isConnected).toBe(false)
    expect(within(dialog()).getByText('Includes every folder inside the one you pick.')).toBeTruthy()
    // Every folder below the matter's own, in the tree's order, with no count; search folders hold no documents
    expect(options().map((one) => one.textContent)).toEqual(['Correspondence', 'Drafts', 'Evidence', 'Pleadings', 'Privileged', "Counsel's Advice"])
    expect(option("Counsel's Advice").querySelector('[style]')?.getAttribute('style')).toContain('calc(1 * var(--hd-space-5))')
    expect(option('Pleadings').querySelector('[style]')?.getAttribute('style')).toContain('calc(0 * var(--hd-space-5))')
    expect(previews(api)).toEqual([])
    await expectNoAxeViolations(view.baseElement)

    // Filtered by words, a folder below another says what it's in
    await userEvent.type(filter, 'couns')
    await pause()
    expect(options().map((one) => one.textContent)).toEqual(["Counsel's Advicein Privileged"])
    await userEvent.clear(filter)
    await userEvent.type(filter, 'ev')
    await pause()
    expect(options().map((one) => one.textContent)).toEqual(['Evidence'])
    await userEvent.click(option('Evidence'))
    await pause()
    expect(previews(api)).toEqual([{ kind: 'folder', targetId: 113 }])
    await waitFor(() => expect(start().textContent).toBe('Reindex 171 Documents'))
    await expectNoAxeViolations(view.baseElement)

    // Another matter: back to the search, the focus with it, and nothing chosen
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Choose Another Matter' }))
    const again = within(dialog()).getByRole('textbox', { name: 'Matter' })
    expect(document.activeElement).toBe(again)
    expect(start().textContent).toBe('Reindex')
    expect(isDisabled(start())).toBe(true)
  })

  it('opens Folder with the folder and its matter the address names chosen', async () => {
    const { api } = renderDialog({ kind: 'folder', id: 114, matter: 11 })
    await waitFor(() => expect(selected()).toEqual(['Pleadings']))
    await waitFor(() => expect(start().textContent).toBe('Reindex 38 Documents'))
    expect(previews(api)).toEqual([{ kind: 'folder', targetId: 114 }])
  })

  it('Documents takes typed or pasted doc #s and shows each as matched or not, a repeat once', async () => {
    const { api, view } = renderDialog({ kind: 'documents' })
    expect(pressed()).toEqual(['Documents'])
    const field = within(dialog()).getByRole('textbox', { name: 'Doc #s' })
    await userEvent.click(field)
    await userEvent.paste('10412, 10398 10388.2\n99999, abc #10412 10377 10388')
    await pause()
    expect(previews(api)).toEqual([{ kind: 'documents', docNumbers: [10412, 10398, 10388, 99999, 10377] }])
    const rows = () => within(within(dialog()).getByRole('list', { name: 'Doc #s Given' })).getAllByRole('listitem').map((one) => one.textContent)
    await waitFor(() =>
      expect(rows()).toEqual([
        '# 10412 Witness Statement.pdf1001.1',
        '# 10398 Heads of Terms.pdf1003.1',
        '# 10388 Skeleton Argument.docx1001.1',
        '# 99999No such document',
        '# abcNo such document',
        "# 10377 Old Draft.docxLeft out: it's set to Skip, archived or being changed1001.1",
      ]),
    )
    // The one left out isn't counted
    expect(start().textContent).toBe('Reindex 3 Documents')
    await expectNoAxeViolations(view.baseElement)

    // One is one
    await userEvent.clear(field)
    await userEvent.type(field, '10412')
    await pause()
    await waitFor(() => expect(start().textContent).toBe('Reindex 1 Document'))
  })

  it('Documents takes at most 1,000 doc #s, as the server does, and asks nothing beyond them', async () => {
    const { api } = renderDialog({ kind: 'documents' })
    await userEvent.click(within(dialog()).getByRole('textbox', { name: 'Doc #s' }))
    await userEvent.paste(Array.from({ length: 1001 }, (_, i) => String(20000 + i)).join(' '))
    await pause()
    expect(within(dialog()).getByText('Reindex at most 1,000 documents at a time.')).toBeTruthy()
    expect(previews(api)).toEqual([])
    expect(isDisabled(start())).toBe(true)
    // The first 1,000 are drawn, and the rest counted
    const rows = within(within(dialog()).getByRole('list', { name: 'Doc #s Given' })).getAllByRole('listitem')
    expect(rows).toHaveLength(1001)
    expect(rows.at(-1)?.textContent).toBe('… and 1 more')
  })

  it('Store is a plain list, each with its documents, and opens with the Store the address names', async () => {
    const { api, view } = renderDialog({ kind: 'store', id: 3 })
    const firm = await within(dialog()).findByRole('radio', { name: 'Firm' })
    expect(within(dialog()).getByText('1,004 documents')).toBeTruthy()
    expect(within(dialog()).getByText('280 documents')).toBeTruthy()
    expect((firm as HTMLInputElement).checked).toBe(true)
    await waitFor(() => expect(start().textContent).toBe('Reindex 280 Documents'))
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(dialog()).getByRole('radio', { name: 'Client Work' }))
    await pause()
    await waitFor(() => expect(start().textContent).toBe('Reindex 1,004 Documents'))
    expect(previews(api)).toEqual([
      { kind: 'store', targetId: 3 },
      { kind: 'store', targetId: 4 },
    ])
  })

  it('Everything says how many and how long, and offers Empty the Index First, unticked, sent only when ticked', async () => {
    const bodies: unknown[] = []
    const { view, onClose } = renderDialog(
      { kind: 'everything' },
      {
        'POST /v1/admin/reindex': ({ body }: Sent) => {
          bodies.push(body)
          return bodies.length === 1 ? problem(409, 'refused', 'This is already being reindexed.') : reindexJobOf({ kind: 'everything', targetId: null })
        },
      },
    )
    await waitFor(() => expect(status()).toContain("All 1,284 documents, in about 37 minutes. Search keeps finding them while they're replaced."))
    const tick = within(dialog()).getByRole('checkbox', { name: 'Empty the Index First' }) as HTMLInputElement
    expect(tick.checked).toBe(false)
    expect(within(dialog()).getByText('Empty the Index First is for a damaged index. Search then misses documents until the run ends.')).toBeTruthy()
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    expect(start().textContent).toBe('Reindex Everything')
    await expectNoAxeViolations(view.baseElement)

    // Unticked, it isn't sent at all; refused, the dialog says why and stays
    await userEvent.click(start())
    expect((await within(dialog()).findByRole('alert')).textContent).toBe('This is already being reindexed.')
    expect(bodies).toEqual([{ kind: 'everything' }])
    expect(onClose).not.toHaveBeenCalled()
    await expectNoAxeViolations(view.baseElement)

    await userEvent.click(tick)
    // A change takes the refusal away, and the button says what it now does
    expect(within(dialog()).queryByRole('alert')).toBeNull()
    expect(start().textContent).toBe('Empty the Index and Reindex Everything')
    await userEvent.click(start())
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(bodies[1]).toEqual({ kind: 'everything', emptyFirst: true })
  })

  it('offers Empty the Index First under Everything only, and never sends it from another choice', async () => {
    let sent: unknown
    renderDialog(
      { kind: 'everything' },
      {
        'POST /v1/admin/reindex': ({ body }: Sent) => {
          sent = body
          return reindexJobOf({ kind: 'store', targetId: 4 })
        },
      },
    )
    await userEvent.click(await within(dialog()).findByRole('checkbox', { name: 'Empty the Index First' }))
    for (const name of ['Documents', 'Folder', 'Matter', 'Store']) {
      await userEvent.click(choice(name))
      expect(within(dialog()).queryByRole('checkbox', { name: 'Empty the Index First' })).toBeNull()
    }
    await userEvent.click(await within(dialog()).findByRole('radio', { name: 'Client Work' }))
    await waitFor(() => expect(start().textContent).toBe('Reindex 1,004 Documents'))
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.click(start())
    await waitFor(() => expect(sent).toEqual({ kind: 'store', targetId: 4 }))
  })

  it("says before the button what a start would be refused with, and greys it out, as when there's nothing to reindex", async () => {
    const { view } = renderDialog(
      { kind: 'matter', id: 11 },
      {
        'POST /v1/admin/reindex/preview': {
          name: 'Matter 1001.1 Charter Dispute – MV Northern Star',
          count: 412,
          skipped: 0,
          estimateSeconds: 721,
          documents: null,
          refusal: 'This is already being reindexed.',
        } satisfies A['ReindexPreview'],
      },
    )
    const sentence = await within(dialog()).findByText('This is already being reindexed.')
    await waitFor(() => expect(start().textContent).toBe('Reindex 412 Documents'))
    expect(isDisabled(start())).toBe(true)
    expect(start().getAttribute('aria-describedby')).toBe(sentence.id)
    await expectNoAxeViolations(view.baseElement)
  })

  it('says a target that has gone, as the preview finds it, and a field the start refused beside it', async () => {
    const { onClose } = renderDialog(
      { kind: 'documents' },
      {
        'POST /v1/admin/reindex': new ApiError(422, 'validation', 'Server words.', { code: 'validation', fields: [{ field: 'docNumbers', code: 'required', detail: 'Server words.' }] }),
      },
    )
    const field = within(dialog()).getByRole('textbox', { name: 'Doc #s' })
    await userEvent.type(field, '10412')
    await pause()
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.click(start())
    await waitFor(() => expect(field.getAttribute('aria-invalid')).toBe('true'))
    expect(within(dialog()).queryByText('Server words.')).toBeNull()
    expect(within(dialog()).queryByRole('alert')).toBeNull()
    expect(onClose).not.toHaveBeenCalled()
  })

  it("says a refusal of the doc #s' limit beside them, and Empty the Index First's above the buttons, in the app's words", async () => {
    const validation = (field: string, code: string) => new ApiError(422, 'validation', 'Server words.', { code: 'validation', fields: [{ field, code, detail: 'Server words.' }] })
    const documents = renderDialog({ kind: 'documents' }, { 'POST /v1/admin/reindex': validation('docNumbers', 'too-many') })
    await userEvent.type(within(dialog()).getByRole('textbox', { name: 'Doc #s' }), '10412')
    await pause()
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.click(start())
    expect(await within(dialog()).findByText('Reindex at most 1,000 documents at a time.')).toBeTruthy()
    expect(within(dialog()).queryByRole('alert')).toBeNull()
    documents.view.unmount()

    renderDialog({ kind: 'everything' }, { 'POST /v1/admin/reindex': validation('emptyFirst', 'invalid') })
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.click(start())
    const alert = await within(dialog()).findByRole('alert')
    expect(alert.textContent).not.toBe('')
    expect(alert.textContent).not.toContain('Server words.')
    expect(within(dialog()).queryByText("That name isn't allowed.")).toBeNull()
    expect(within(dialog()).getByRole('checkbox', { name: 'Empty the Index First' }).getAttribute('aria-invalid')).toBeNull()
  })

  it("says so when the preview can't find the target any more", async () => {
    renderDialog({ kind: 'matter', id: 11 }, { 'POST /v1/admin/reindex/preview': problem(404, 'not-found') })
    const alert = await within(dialog()).findByRole('alert')
    expect(alert.textContent).not.toContain('A server sentence')
    expect(alert.textContent).not.toBe('')
    expect(isDisabled(start())).toBe(true)
  })

  it("can't be cancelled, by Cancel or by Esc, while the start is on its way", async () => {
    const slow = slowAnswer<A['ReindexJob']>()
    const { api, onClose, onStarted } = renderDialog({ kind: 'matter', id: 11 }, { 'POST /v1/admin/reindex': () => slow.answered })
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.click(start())
    await waitFor(() => expect(api.callsTo('POST /v1/admin/reindex')).toHaveLength(1))
    const cancel = within(dialog()).getByRole('button', { name: 'Cancel' })
    expect(isDisabled(cancel)).toBe(true)
    await userEvent.click(cancel)
    await userEvent.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    // Its choices are held too: the segments, the search, and its rows, a click on another leaving the matter chosen
    expect(choice('Store').matches(':disabled')).toBe(true)
    expect((within(dialog()).getByRole('textbox', { name: 'Matter' }) as HTMLInputElement).disabled).toBe(true)
    await userEvent.click(option(/^1003\.1/))
    const chosen = ['1001.1 – Charter Dispute – MV Northern StarSaltmarsh Shipping Ltd']
    expect(selected()).toEqual(chosen)
    // Refused, it says so beside the choice it was about
    await act(async () => slow.refuse(problem(409, 'refused', 'This is already being reindexed.')))
    expect((await within(dialog()).findByRole('alert')).textContent).toBe('This is already being reindexed.')
    expect(selected()).toEqual(chosen)
    expect(onStarted).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it("holds Folder's rows too while the start is on its way", async () => {
    const slow = slowAnswer<A['ReindexJob']>()
    const { api } = renderDialog({ kind: 'folder', id: 114, matter: 11 }, { 'POST /v1/admin/reindex': () => slow.answered })
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.click(start())
    await waitFor(() => expect(api.callsTo('POST /v1/admin/reindex')).toHaveLength(1))
    await userEvent.click(option('Evidence'))
    expect(selected()).toEqual(['Pleadings'])
    expect(within(dialog()).getByRole('button', { name: 'Choose Another Matter' }).matches(':disabled')).toBe(true)
    await act(async () => slow.answer(reindexJobOf({ kind: 'folder', targetId: 114 })))
    expect(api.callsTo('POST /v1/admin/reindex').map((call) => call.body)).toEqual([{ kind: 'folder', targetId: 114 }])
  })

  it('searches matters from one character, as a number can be one digit, and filters folders by one letter', async () => {
    const { api } = renderDialog({ kind: 'matter' })
    await userEvent.type(within(dialog()).getByRole('textbox', { name: 'Matter' }), '9')
    await pause()
    await waitFor(() => expect(api.callsTo('GET /v1/admin/matters').map((call) => call.query)).toEqual([{ q: '9', limit: 20 }]))
    await waitFor(() => expect(options().map((one) => one.textContent)).toEqual(['998.1 – Firm PoliciesAshcombe Reeve LLP86 documents', '998.2 – IT and SystemsAshcombe Reeve LLP41 documents']))

    // Folder starts afresh from Recent Matters
    await userEvent.click(choice('Folder'))
    await waitFor(() => expect(options()).toHaveLength(3))
    await userEvent.click(option(/^1001\.1/))
    await userEvent.type(await within(dialog()).findByRole('textbox', { name: 'Folder' }), 'e')
    await pause()
    expect(options().map((one) => one.textContent)).toEqual(['Correspondence', 'Evidence', 'Pleadings', 'Privileged', "Counsel's Advicein Privileged"])
  })

  it("never starts on Enter in Matter's search or Folder's filter, whatever is already chosen", async () => {
    const { api, onClose } = renderDialog({ kind: 'matter', id: 12 })
    await waitFor(() => expect(start().textContent).toBe('Reindex 96 Documents'))
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    // Looking for another matter, with matter 12 still chosen and ready to start: Enter with no row on the arrow keys
    const finder = within(dialog()).getByRole('textbox', { name: 'Matter' })
    await userEvent.type(finder, '1001.1')
    await pause()
    await waitFor(() => expect(within(dialog()).getByRole('heading', { name: '1 Result' })).toBeTruthy())
    await userEvent.type(finder, '{Enter}')
    await pause()
    expect(api.callsTo('POST /v1/admin/reindex')).toEqual([])
    expect(onClose).not.toHaveBeenCalled()

    // Folder, with a folder chosen: Enter in its filter starts nothing either
    await userEvent.click(choice('Folder'))
    await userEvent.click(await within(dialog()).findByRole('button', { name: 'Choose Another Matter' }))
    await waitFor(() => expect(options()).toHaveLength(3))
    await userEvent.click(option(/^1001\.1/))
    const filter = await within(dialog()).findByRole('textbox', { name: 'Folder' })
    await userEvent.click(option('Evidence'))
    await waitFor(() => expect(start().textContent).toBe('Reindex 171 Documents'))
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    await userEvent.type(filter, 'pl{Enter}')
    await pause()
    expect(api.callsTo('POST /v1/admin/reindex')).toEqual([])
    expect(onClose).not.toHaveBeenCalled()
  })

  it('is used by keyboard alone: choose, search, pick and start', async () => {
    let sent: unknown
    const { onClose } = renderDialog(
      { kind: 'documents' },
      {
        'POST /v1/admin/reindex': ({ body }: Sent) => {
          sent = body
          return reindexJobOf()
        },
      },
    )
    // From the choices, by the arrows, to Matter
    while (document.activeElement !== choice('Documents')) await userEvent.tab()
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    expect(document.activeElement).toBe(choice('Matter'))
    await userEvent.keyboard(' ')
    expect(pressed()).toEqual(['Matter'])
    // On to the search, which finds the matter, the arrows and Enter picking it
    await userEvent.tab()
    expect(document.activeElement).toBe(within(dialog()).getByRole('textbox', { name: 'Matter' }))
    await userEvent.keyboard('1001.1')
    await pause()
    await waitFor(() => expect(options().map((one) => one.textContent)).toEqual(['1001.1 – Charter Dispute – MV Northern StarSaltmarsh Shipping Ltd412 documents']))
    await userEvent.keyboard('{ArrowDown}{Enter}')
    await pause()
    await waitFor(() => expect(start().textContent).toBe('Reindex 412 Documents'))
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    // And on to the button, which starts it
    while (document.activeElement !== start()) await userEvent.tab()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(sent).toEqual({ kind: 'matter', targetId: 11 })
  })
})

describe('Reindex on a matter and on its folders', () => {
  it("a matter's page links to the Search Index with the Reindex dialog open on it", async () => {
    const { router } = await renderApp('/matters/11', workspaceRoutes())
    const link = await screen.findByRole('link', { name: 'Reindex' })
    expect(link.getAttribute('href')).toBe('/admin/search-index?reindex=matter&id=11')
    await userEvent.click(link)
    // In the app, by the router
    await waitFor(() => expect(router.state.location.pathname).toBe('/search-index'))
    // As the address writes them, which the Search Index's route reads as numbers
    expect(router.state.location.search).toEqual({ reindex: 'matter', id: '11' })
  })

  it("a folder of a matter's Workspace links to it with the folder and its matter, from its panel and its menu", async () => {
    const { router } = await renderApp('/matters/11?tab=workspace', workspaceRoutes())
    const tree = await screen.findByRole('treegrid')
    // The matter's own folder has the page's Reindex, and none of its own
    expect(screen.getAllByRole('link', { name: 'Reindex' })).toHaveLength(1)
    await userEvent.click(within(tree).getByRole('row', { name: 'Evidence, Own Permissions' }))
    const links = await screen.findAllByRole('link', { name: 'Reindex' })
    expect(links.map((one) => one.getAttribute('href'))).toEqual(['/admin/search-index?reindex=matter&id=11', '/admin/search-index?reindex=folder&id=113&matter=11'])
    // A search folder holds no documents
    await userEvent.click(within(tree).getByRole('row', { name: 'Emails in This Matter' }))
    await waitFor(() => expect(screen.getAllByRole('link', { name: 'Reindex' })).toHaveLength(1))

    // The tree's menu offers it too
    await userEvent.pointer({ keys: '[MouseRight]', target: within(tree).getByRole('row', { name: 'Pleadings' }) })
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Reindex' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/search-index'))
    expect(router.state.location.search).toEqual({ reindex: 'folder', id: '114', matter: '11' })
  })

  it("a template's folders offer no Reindex", async () => {
    await renderApp('/templates/202', workspaceRoutes())
    const tree = await screen.findByRole('treegrid')
    await userEvent.click(within(tree).getByRole('row', { name: 'Pleadings' }))
    expect(screen.queryByRole('link', { name: 'Reindex' })).toBeNull()
    await userEvent.pointer({ keys: '[MouseRight]', target: within(tree).getByRole('row', { name: 'Pleadings' }) })
    await screen.findByRole('menu')
    expect(screen.queryByRole('menuitem', { name: 'Reindex' })).toBeNull()
  })
})

describe('Reindex on the Search Index page', () => {
  /** The page at an address, its reindexes and counts from 36a's fake, the dialog's reads and start from its own */
  async function openPage(path: string) {
    const fake = searchIndexRoutes({ jobs: [] })
    const rendered = await renderApp(path, {
      ...fake.routes,
      ...reindexRoutes(),
      // The server keeps the reindex it starts, so the page's next read of those waiting or running has it
      'POST /v1/admin/reindex': ({ body }: Sent) => {
        const scope = body as A['ReindexScope']
        const job = reindexJobOf({ kind: scope.kind, targetId: scope.targetId ?? null })
        fake.setJob(job)
        return job
      },
    })
    // Behind the dialog when the address opens it, so hidden from a screen reader then
    await screen.findByRole('heading', { level: 1, name: 'Search Index', hidden: true })
    return rendered
  }

  it('opens the dialog the address names, with what it chose, and Esc and Cancel take it out of the address in place', async () => {
    const { router, view } = await openPage('/search-index?tab=waiting&reindex=matter&id=11')
    await screen.findByRole('dialog', { name: 'Reindex' })
    expect(pressed()).toEqual(['Matter'])
    await waitFor(() => expect(selected()).toEqual(['1001.1 – Charter Dispute – MV Northern StarSaltmarsh Shipping Ltd']))
    await expectNoAxeViolations(view.baseElement)
    const length = router.history.length
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // The tab stays; the dialog's parameters go, in place, so Back doesn't open it again
    expect(router.state.location.search).toEqual({ tab: 'waiting' })
    expect(router.history.length).toBe(length)

    // Reindex… opens it on Matter, and Cancel closes it
    await userEvent.click(screen.getByRole('button', { name: /^Reindex/ }))
    await screen.findByRole('dialog', { name: 'Reindex' })
    expect(router.state.location.search).toEqual({ tab: 'waiting', reindex: 'matter' })
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(router.state.location.search).toEqual({ tab: 'waiting' })
  })

  it("gives the focus to the page's heading once a dialog a link opened closes, and back to Reindex… once one it opened does", async () => {
    await openPage('/search-index?reindex=matter&id=11')
    await screen.findByRole('dialog', { name: 'Reindex' })
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Search Index' })))

    const reindex = screen.getByRole('button', { name: 'Reindex…' })
    await userEvent.click(reindex)
    await screen.findByRole('dialog', { name: 'Reindex' })
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(reindex))
  })

  it('opens on a folder and its matter, as the Workspace Editor links to it, and Back closes it', async () => {
    const { router } = await openPage('/search-index')
    await act(() => router.navigate({ href: '/search-index?reindex=folder&id=114&matter=11' }))
    await screen.findByRole('dialog', { name: 'Reindex' })
    expect(pressed()).toEqual(['Folder'])
    await waitFor(() => expect(selected()).toEqual(['Pleadings']))
    await waitFor(() => expect(start().textContent).toBe('Reindex 38 Documents'))
    await act(async () => router.history.back())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(router.state.location.search).toEqual({})
  })

  it('closes once a reindex is started, which shows among the reindexes above the tabs at once', async () => {
    const { api, router } = await openPage('/search-index?reindex=store&id=4')
    await screen.findByRole('dialog', { name: 'Reindex' })
    await waitFor(() => expect(start().textContent).toBe('Reindex 1,004 Documents'))
    await waitFor(() => expect(isDisabled(start())).toBe(false))
    const reads = api.callsTo('GET /v1/admin/reindex').length
    await userEvent.click(start())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(router.state.location.search).toEqual({})
    expect(await screen.findByText('Waiting to Reindex Matter 1001.1 Charter Dispute – MV Northern Star')).toBeTruthy()
    // Every list of reindexes is asked again, and the card stays, as the server has it
    await waitFor(() => expect(api.callsTo('GET /v1/admin/reindex').length).toBeGreaterThan(reads))
    await pause()
    expect(screen.getByText('Waiting to Reindex Matter 1001.1 Charter Dispute – MV Northern Star')).toBeTruthy()
  })
})
