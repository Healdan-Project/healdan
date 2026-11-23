// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { FOLLOW_MS, MAX_KEPT, formatLogTime } from '../monitoring/useLogLines'
import monitoringCss from '../monitoring/Monitoring.module.css?raw'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import { fakeLog, logEntries, logInfos, logLine, logRoutes, problem, type Routes } from '../test/fakeApi'
import { answerFrame, downloadFrames } from '../test/frames'
import { hidePage, returnToPage } from '../test/visibility'
import { renderApp } from '../test/renderApp'
import { slowAnswer } from '../test/slowAnswer'
import { serverLogSearch } from './ServerLogView'

type A = adminComponents['schemas']
type Request = { params: string[]; query?: Record<string, unknown>; signal?: AbortSignal }

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
const box = (log = 'Main Log') => screen.getByRole('region', { name: `Lines of ${log}` })
const entries = (log = 'Main Log') => within(box(log)).queryAllByRole('listitem')
const shown = (log = 'Main Log') => entries(log).map((entry) => entry.querySelector('[data-message]')?.textContent)
const reads = (api: { callsTo: (pattern: string) => { query?: Record<string, unknown> }[] }, log = 'DMS') => api.callsTo(`GET /v1/admin/logs/${log}/lines`).map((call) => call.query)
const follows = (api: Parameters<typeof reads>[0]) => reads(api).filter((query) => query?.after !== undefined)
const earlier = (api: Parameters<typeof reads>[0]) => reads(api).filter((query) => query?.before !== undefined)

/** Moves the faked clock on, with what was waiting for it */
const wait = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms))
/** A moment for what was asked to be answered and drawn: a few milliseconds, well inside the 3 seconds of a follow */
const settle = async () => {
  for (let i = 0; i < 4; i++) await wait(5)
}

/** The whole app at the Server Log, its clock faked, answering from the log's routes and `over` */
async function openLog(path = '/server-log', over: Routes = {}) {
  vi.useFakeTimers()
  const pending = renderApp(path, { ...logRoutes(), ...over })
  await settle()
  const rendered = await pending
  await settle()
  return rendered
}

/**
 * The box laid out as a browser would, which jsdom doesn't: each entry `rowHeight` pixels tall, one under the other, and
 * the box `height` tall, so its scroll position means something. Returns its scroll position, to read and to scroll to.
 */
function layOut(element: HTMLElement, height = 200, rowHeight = 20) {
  let top = 0
  const items = () => [...element.querySelectorAll('li')]
  const index = (item: HTMLElement) => items().indexOf(item as HTMLLIElement)
  Object.defineProperty(element, 'clientHeight', { configurable: true, get: () => height })
  Object.defineProperty(element, 'scrollHeight', { configurable: true, get: () => items().length * rowHeight })
  Object.defineProperty(element, 'scrollTop', { configurable: true, get: () => top, set: (value: number) => (top = Math.max(0, Math.min(value, items().length * rowHeight - height))) })
  const offsetTop = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetTop')
  const offsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
  Object.defineProperty(HTMLElement.prototype, 'offsetTop', { configurable: true, get: function (this: HTMLElement) { return this.tagName === 'LI' ? index(this) * rowHeight : 0 } })
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: function (this: HTMLElement) { return this.tagName === 'LI' ? rowHeight : 0 } })
  restore.push(() => {
    if (offsetTop) Object.defineProperty(HTMLElement.prototype, 'offsetTop', offsetTop)
    if (offsetHeight) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', offsetHeight)
  })
  return {
    get top() {
      return top
    },
    scrollTo(value: number) {
      top = value
      fireEvent.scroll(element)
    },
  }
}
const restore: (() => void)[] = []
afterEach(() => restore.splice(0).forEach((undo) => undo()))

/**
 * axe over what's drawn, once. It waits on timers of its own, so the clock is real again first, which ends the test's own
 * waits. jsdom draws no colours, so the two themes' colours aren't checked here: every colour is a token, which
 * test/colours.test.ts checks on each surface of both themes, and the phase's browser spec runs axe in each theme.
 */
async function expectNoAxeViolationsAtTheEnd(container: Element) {
  vi.useRealTimers()
  await expectNoAxeViolations(container)
}

const ROLLED = 'The log rolled over to a new file, so this starts again at its top. Show Earlier reads the lines before it.'

/** `count` entries, the ones at the indexes `errorsAt` keeps errors, the rest the Indexer's runs, each saying its number */
function manyEntries(count: number, errorsAt: (index: number) => boolean = () => false): A['LogLine'][] {
  return Array.from({ length: count }, (_, i) => logLine('', { level: errorsAt(i) ? 'ERROR' : 'INFO', message: `Entry ${i}` }))
}

describe('The Server Log', () => {
  it('opens at /admin/server-log under its heading, the open screen in the pane', async () => {
    const { view } = await renderApp('/server-log', logRoutes())
    expect(await screen.findByRole('heading', { level: 1, name: 'Server Log' })).toBeTruthy()
    expect(current()).toEqual([within(pane()).getByRole('link', { name: 'Server Log' })])
    expect(await screen.findByRole('region', { name: 'Lines of Main Log' })).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('reads the log, the level and the words from the address', async () => {
    const { router } = await renderApp('/server-log?log=IndexerTask&level=warn&q=%20Indexer%20', logRoutes())
    expect(router.state.matches.at(-1)?.search).toMatchObject({ log: 'IndexerTask', level: 'warn', q: 'Indexer' })
  })

  it("leaves out what isn't one of its values: a log's name is never a path, nor dots alone", () => {
    for (const log of ['../dms.log', 'logs/DMS', 'a\\b', '..', '.', '']) expect(serverLogSearch({ log })).toEqual({})
    expect(serverLogSearch({ level: 'info', q: ' ' })).toEqual({})
    expect(serverLogSearch({ log: 'DMS', level: 'all' })).toEqual({ log: 'DMS', level: 'all' })
  })
})

describe("The Server Log's choices", () => {
  it("offers the main log and each task's log, Everything, Warnings and Errors, and Errors, and the words, all kept in the address", async () => {
    const { api, router, view } = await openLog()
    const menu = screen.getByRole('combobox', { name: 'Log' }) as HTMLSelectElement
    expect(within(menu).getAllByRole('option').map((option) => option.textContent)).toEqual(['Main Log', 'Indexer'])
    expect(menu.value).toBe('DMS')
    const levels = screen.getByRole('radiogroup', { name: 'Level' })
    expect(within(levels).getAllByRole('radio').map((one) => [one.textContent, one.getAttribute('aria-checked')])).toEqual([
      ['Everything', 'true'],
      ['Warnings and Errors', 'false'],
      ['Errors', 'false'],
    ])
    expect(reads(api)).toEqual([{ level: 'all' }])

    fireEvent.click(within(levels).getByRole('radio', { name: 'Warnings and Errors' }))
    await settle()
    expect(router.state.location.search).toEqual({ level: 'warn' })
    expect(reads(api).at(-1)).toEqual({ level: 'warn' })
    expect(shown()).toEqual([
      'Statistics took longer than expected: 42 s',
      'Upload to Wasabi EU failed: connection reset, will retry in 5 minutes',
      'Sign-in failed for username jclarke (2 of 5)',
      'Preview conversion timed out after 180 seconds, doc 10412 version 3',
    ])

    fireEvent.change(screen.getByRole('textbox', { name: 'Search the Log' }), { target: { value: '  wasabi ' } })
    await wait(TYPING_DELAY_MS)
    await settle()
    expect(router.state.location.search).toEqual({ level: 'warn', q: 'wasabi' })
    expect(reads(api).at(-1)).toEqual({ level: 'warn', q: 'wasabi' })
    expect(shown()).toEqual(['Upload to Wasabi EU failed: connection reset, will retry in 5 minutes'])

    fireEvent.change(menu, { target: { value: 'IndexerTask' } })
    await settle()
    expect(router.state.location.search).toEqual({ log: 'IndexerTask', level: 'warn', q: 'wasabi' })
    expect(reads(api, 'IndexerTask')).toEqual([{ level: 'warn', q: 'wasabi' }])
    expect(box('Indexer')).toBeTruthy()
    await expectNoAxeViolationsAtTheEnd(view.container)
  })

  it('opens the log, level and words the address names', async () => {
    const { api } = await openLog('/server-log?log=IndexerTask&level=error&q=Indexing')
    expect(reads(api, 'IndexerTask')).toEqual([{ level: 'error', q: 'Indexing' }])
    expect((screen.getByRole('combobox', { name: 'Log' }) as HTMLSelectElement).value).toBe('IndexerTask')
    expect(screen.getByRole('radio', { name: 'Errors' }).getAttribute('aria-checked')).toBe('true')
    expect((screen.getByRole('textbox', { name: 'Search the Log' }) as HTMLInputElement).value).toBe('Indexing')
    expect(reads(api)).toEqual([])
  })

  it('says so when the address names a log the server has none of', async () => {
    const { api } = await openLog('/server-log?log=Nope')
    expect(screen.getByText("The server has no log called Nope. Pick one from the Log menu.")).toBeTruthy()
    expect(reads(api, 'Nope')).toEqual([])
  })

  it('downloads the zip of the log shown through a hidden frame, once the logs have been asked for through the app, never by a link', async () => {
    const { api } = await openLog()
    expect(screen.queryByRole('link', { name: 'Download' })).toBeNull()
    const listed = api.callsTo('GET /v1/admin/logs').length
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await settle()
    expect(api.callsTo('GET /v1/admin/logs')).toHaveLength(listed + 1)
    expect(downloadFrames().map((frame) => frame.getAttribute('src'))).toEqual(['/api/v1/admin/logs/DMS/file'])
    expect(document.querySelector('a[download]')).toBeNull()

    fireEvent.change(screen.getByRole('combobox', { name: 'Log' }), { target: { value: 'IndexerTask' } })
    await settle()
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await settle()
    expect(downloadFrames().map((frame) => frame.getAttribute('src'))).toEqual(['/api/v1/admin/logs/DMS/file', '/api/v1/admin/logs/IndexerTask/file'])
  })

  it.each([
    [401, 'unauthenticated', "You've been signed out."],
    [403, 'admins-only', "You don't have access to Healdan Admin."],
  ])("downloads nothing when the app's own call is refused %i, and the app says so in place of the screen", async (status, code, heading) => {
    let refusing = false
    await openLog('/server-log', { 'GET /v1/admin/logs': () => (refusing ? problem(status, code) : logInfos) })
    refusing = true
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await settle()
    expect(screen.getByRole('heading', { level: 1, name: heading })).toBeTruthy()
    expect(downloadFrames()).toEqual([])
  })

  it('says beside Download what the server answered in place of the file', async () => {
    await openLog()
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await settle()
    act(() => answerFrame(downloadFrames()[0]!, JSON.stringify({ status: 404, code: 'not-found', detail: 'No log called DMS.' })))
    expect(screen.getByRole('alert').textContent).toBe("Not found, or you can't see it.")
    // Pressed again, it says nothing until there's something to say
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await settle()
    expect(screen.queryByRole('alert')).toBeNull()
    // Nor is one log's refusal said of another
    // The first frame went once it had loaded the refusal
    act(() => answerFrame(downloadFrames()[0]!, JSON.stringify({ status: 404, code: 'not-found', detail: 'No log called DMS.' })))
    expect(screen.getByRole('alert')).toBeTruthy()
    fireEvent.change(screen.getByRole('combobox', { name: 'Log' }), { target: { value: 'IndexerTask' } })
    await settle()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('reports a refusal for anyone but administrators that the frame met as the app does every other', async () => {
    await openLog()
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await settle()
    act(() => answerFrame(downloadFrames()[0]!, JSON.stringify({ status: 403, code: 'admins-only', detail: 'Only administrators can do that.' })))
    await settle()
    expect(screen.getByRole('heading', { level: 1, name: "You don't have access to Healdan Admin." })).toBeTruthy()
  })

  it("shows why the logs couldn't be listed, with Try Again", async () => {
    let failing = true
    const { api } = await openLog('/server-log', { 'GET /v1/admin/logs': () => (failing ? problem(500, 'server-error') : logRoutes()['GET /v1/admin/logs']) })
    expect(screen.getByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Lines of Main Log' })).toBeNull()
    expect(reads(api)).toEqual([])
    failing = false
    fireEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await settle()
    expect(shown()).toHaveLength(9)
  })

  it("shows why the lines couldn't be read, with Try Again", async () => {
    let failing = true
    await openLog('/server-log', { 'GET /v1/admin/logs/*/lines': (request: Request) => (failing ? problem(500, 'server-error') : fakeLog(logEntries())(request)) })
    expect(screen.getByText('Something went wrong.')).toBeTruthy()
    // The menu is there, as the logs could be listed
    expect(within(screen.getByRole('combobox', { name: 'Log' })).getAllByRole('option')).toHaveLength(2)
    failing = false
    fireEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await settle()
    expect(shown()).toHaveLength(9)
  })
})

describe("The Server Log's lines", () => {
  it('open on the newest, oldest first, each with its time, level, source and message, a stack under its line', async () => {
    const { view } = await openLog()
    expect(shown()).toEqual(logEntries().map((entry) => entry.message))
    const error = entries()[4]!
    expect(within(error).getByText(formatLogTime('2026-11-19T10:37:44Z')).tagName).toBe('TIME')
    // Said in words, never by colour alone
    expect(within(error).getByText('Error')).toBeTruthy()
    expect(within(entries()[0]!).getByText('Warn')).toBeTruthy()
    expect(within(entries()[1]!).getByText('Info')).toBeTruthy()
    // The class's own name and the line, the whole source in its title
    const source = within(error).getByText('Mover:322')
    expect(source.getAttribute('title')).toBe('com.logicaldoc.healdan.storage.Mover:322')
    const stack = error.querySelector('pre')!
    expect(stack.textContent).toBe('java.net.SocketException: Connection reset\n\tat java.base/sun.nio.ch.NioSocketImpl.implRead(NioSocketImpl.java:318)')
    await expectNoAxeViolationsAtTheEnd(view.container)
  })

  it("shows a line that starts no entry with no time, level or source, as a stack's own line", async () => {
    const log = [...logEntries(), logLine('', { time: null, level: null, source: null, message: '\tat com.logicaldoc.Foo.bar(Foo.java:1)' })]
    await openLog('/server-log', logRoutes(log))
    const last = entries().at(-1)!
    expect(last.querySelector('time')).toBeNull()
    expect(last.textContent).toBe('\tat com.logicaldoc.Foo.bar(Foo.java:1)')
    expect(last.getAttribute('data-continues')).toBe('true')
  })

  it('wraps long lines inside the box, without breaking the page', () => {
    const rule = (name: string) => new RegExp(`\\.${name}\\s*\\{([^}]*)\\}`).exec(monitoringCss)?.[1] ?? ''
    expect(rule('message')).toMatch(/white-space:\s*pre-wrap/)
    expect(rule('message')).toMatch(/overflow-wrap:\s*anywhere/)
    expect(rule('continuation')).toMatch(/white-space:\s*pre-wrap/)
    expect(rule('continuation')).toMatch(/overflow-wrap:\s*anywhere/)
    expect(rule('lines')).toMatch(/overflow:\s*auto/)
    expect(rule('lines')).toMatch(/min-width:\s*0/)
  })

  it('show an entry of a log that writes no level or source by its time and message alone, as an entry, not a stack line', async () => {
    await openLog('/server-log', logRoutes([logLine('', { level: null, source: null, message: 'Digest sent to 4 people' })]))
    const [entry] = entries()
    expect(entry!.hasAttribute('data-continues')).toBe(false)
    expect(entry!.querySelector('time')?.getAttribute('datetime')).toBe('2026-11-19T10:41:07Z')
    expect(entry!.querySelector('[data-level]')).toBeNull()
    expect(entry!.querySelector('[title]')).toBeNull()
    expect(shown()).toEqual(['Digest sent to 4 people'])
    expect(entry!.textContent).toBe(`${formatLogTime('2026-11-19T10:41:07Z')}Digest sent to 4 people`)
  })

  it('show a level they have no words for as the log writes it, and the rest of the box as ever', async () => {
    await openLog('/server-log', logRoutes([logLine('', { level: 'NOTICE' as A['LogLine']['level'], message: 'A new kind of line' }), logLine('', { level: 'WARN' })]))
    expect(shown()).toEqual(['A new kind of line', 'Finished indexing 5 documents in 4.2 s'])
    expect(entries()[0]!.querySelector('[data-level]')?.textContent).toBe('NOTICE')
    expect(entries()[1]!.querySelector('[data-level]')?.textContent).toBe('Warn')
  })

  it('show what the log holds as text, never as markup', async () => {
    const hostile = '<img src=x onerror="alert(1)"><b>bold</b>'
    const { view } = await openLog('/server-log', logRoutes([logLine('', { message: hostile, source: hostile, continuation: [hostile] })]))
    expect(view.container.querySelector('img')).toBeNull()
    expect(view.container.querySelector('b')).toBeNull()
    expect(shown()).toEqual([hostile])
    expect(entries()[0]!.querySelector('pre')?.textContent).toBe(hostile)
  })

  it("are in a box with a name of its own, which the keyboard reaches and scrolls, and which doesn't say each new line", async () => {
    await openLog()
    const lines = box()
    expect(lines.getAttribute('tabindex')).toBe('0')
    expect(screen.getAllByRole('region', { name: 'Lines of Main Log' })).toHaveLength(1)
    // Nothing in it is a live region but what a press of Show Earlier says, outside the list of lines
    expect(lines.closest('[aria-live], [role="log"], [role="status"], [role="alert"]')).toBeNull()
    const list = within(lines).getByRole('list')
    expect(list.querySelector('[aria-live], [role="log"], [role="status"], [role="alert"]')).toBeNull()
  })
})

describe('Following', () => {
  it("asks with the last answer's after every 3 seconds while it follows, and adds what's new at the end", async () => {
    const log = logEntries()
    const { api } = await openLog('/server-log', logRoutes(log))
    expect(screen.getByText('Following Live')).toBeTruthy()
    log.push(logLine('', { message: 'Session opened for treeve' }))
    await wait(FOLLOW_MS - 100)
    expect(follows(api)).toEqual([])
    await wait(100)
    await settle()
    expect(follows(api)).toEqual([{ after: 'p9', level: 'all' }])
    expect(shown().at(-1)).toBe('Session opened for treeve')
    expect(shown()).toHaveLength(10)

    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toEqual([
      { after: 'p9', level: 'all' },
      { after: 'p10', level: 'all' },
    ])
    expect(shown()).toHaveLength(10)
  })

  it('stops with Pause, which becomes Follow; Follow asks at once and goes on every 3 seconds', async () => {
    const log = logEntries()
    const { api } = await openLog('/server-log', logRoutes(log))
    fireEvent.click(screen.getByRole('button', { name: 'Pause' }))
    await settle()
    expect(screen.getByText('Paused')).toBeTruthy()
    expect(screen.queryByText('Following Live')).toBeNull()
    log.push(logLine('', { message: 'Session opened for treeve' }))
    await wait(FOLLOW_MS * 4)
    expect(follows(api)).toEqual([])
    expect(shown()).toHaveLength(9)

    fireEvent.click(screen.getByRole('button', { name: 'Follow' }))
    await settle()
    expect(follows(api)).toEqual([{ after: 'p9', level: 'all' }])
    expect(shown().at(-1)).toBe('Session opened for treeve')
    expect(screen.getByText('Following Live')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Pause' })).toBeTruthy()
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(2)
  })

  it('asks nothing while the page is hidden, and at once when it shows again', async () => {
    const { api } = await openLog()
    hidePage()
    await wait(FOLLOW_MS * 5)
    expect(follows(api)).toEqual([])
    returnToPage()
    await settle()
    expect(follows(api)).toHaveLength(1)
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(2)
  })

  it('stops when the page is left, dropping the request on its way', async () => {
    const sent: AbortSignal[] = []
    const { api, router } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        if (request.query?.after === undefined) return fakeLog(logEntries())(request)
        sent.push(request.signal!)
        return new Promise(() => {})
      },
    })
    await wait(FOLLOW_MS)
    await settle()
    expect(sent).toHaveLength(1)
    expect(sent[0]!.aborted).toBe(false)
    act(() => void router.navigate({ to: '/' }))
    await settle()
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
    expect(sent[0]!.aborted).toBe(true)
    await wait(FOLLOW_MS * 5)
    expect(follows(api)).toHaveLength(1)
  })

  it('cancels the read on its way when paused, or when the page is hidden, and shows nothing of its answer', async () => {
    const pending: { signal: AbortSignal; answer: (lines: A['LogLines']) => void }[] = []
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        if (request.query?.after === undefined) return fakeLog(logEntries())(request)
        const slow = slowAnswer<A['LogLines']>()
        pending.push({ signal: request.signal!, answer: slow.answer })
        return slow.answered
      },
    })
    const late = (message: string) => ({ lines: [logLine('p9', { message })], before: 'p9', after: 'p10', atStart: false, atEnd: true, rolled: false })
    await wait(FOLLOW_MS)
    await settle()
    expect(pending).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Pause' }))
    await settle()
    expect(pending[0]!.signal.aborted).toBe(true)
    await act(async () => pending[0]!.answer(late('Late, after Pause')))
    await settle()
    expect(shown()).not.toContain('Late, after Pause')

    fireEvent.click(screen.getByRole('button', { name: 'Follow' }))
    await settle()
    expect(pending).toHaveLength(2)
    hidePage()
    await settle()
    expect(pending[1]!.signal.aborted).toBe(true)
    await act(async () => pending[1]!.answer(late('Late, after hiding')))
    await settle()
    expect(shown()).not.toContain('Late, after hiding')
    await wait(FOLLOW_MS * 3)
    expect(follows(api)).toHaveLength(2)
  })

  it('starts afresh when the log or a filter changes, and drops an answer that comes late for the one before', async () => {
    const late = slowAnswer<A['LogLines']>()
    const sent: AbortSignal[] = []
    const log = fakeLog(logEntries())
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        if (request.query?.after === undefined) return log(request)
        if (request.query.level !== 'all') return log(request)
        sent.push(request.signal!)
        return late.answered
      },
    })
    await wait(FOLLOW_MS)
    await settle()
    expect(sent).toHaveLength(1)
    fireEvent.click(screen.getByRole('radio', { name: 'Errors' }))
    await settle()
    expect(sent[0]!.aborted).toBe(true)
    expect(reads(api).at(-1)).toEqual({ level: 'error' })
    expect(shown()).toEqual(['Upload to Wasabi EU failed: connection reset, will retry in 5 minutes', 'Preview conversion timed out after 180 seconds, doc 10412 version 3'])

    // The old follow's answer, should it come all the same, is the old filter's, and isn't shown
    await act(async () => late.answer({ lines: [logLine('p9', { level: 'ERROR', message: 'Late, for Everything' })], before: 'p9', after: 'p10', atStart: false, atEnd: true, rolled: false }))
    await settle()
    expect(shown()).not.toContain('Late, for Everything')
    // And the new one follows from its own answer
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api).at(-1)).toEqual({ after: 'p9', level: 'error' })
  })

  it.each([
    ['log', () => fireEvent.change(screen.getByRole('combobox', { name: 'Log' }), { target: { value: 'IndexerTask' } }), 'Indexer'],
    ['level', () => fireEvent.click(screen.getByRole('radio', { name: 'Errors' })), 'Main Log'],
  ])("shows Loading, never the old lines, and reads nothing more for the old ones, when the %s changes with the box read further up", async (_, choose, label) => {
    const slow = slowAnswer<A['LogLines']>()
    const main = fakeLog(manyEntries(250))
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => (request.params[0] === 'IndexerTask' || request.query?.level === 'error' ? slow.answered : main(request)),
    })
    // Reading further up: the entry at the box's top is the one kept in place
    layOut(box()).scrollTo(40)
    await settle()
    const old = () => reads(api).filter((query) => query?.level === 'all')
    const asked = old()
    choose()
    await settle()
    // Nothing more of the main log at Everything, as Show Earlier's read would be
    expect(old()).toEqual(asked)
    expect(entries(label)).toEqual([])
    expect(within(box(label)).getByText('Loading…')).toBeTruthy()

    await act(async () => slow.answer({ lines: [logLine('n0', { level: 'ERROR', message: 'Indexing failed' })], before: 'n0', after: 'n1', atStart: true, atEnd: true, rolled: false }))
    await settle()
    expect(shown(label)).toEqual(['Indexing failed'])
  })

  it("asks again at once while an answer isn't at the end, at most five times in a row, then every 3 seconds", async () => {
    let next = 100
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        if (request.query?.after === undefined) return fakeLog(logEntries())(request)
        const at = next++
        return { lines: [logLine(`q${at}`, { message: `Catching up ${at}` })], before: `q${at}`, after: `q${at + 1}`, atStart: false, atEnd: false, rolled: false } satisfies A['LogLines']
      },
    })
    await wait(FOLLOW_MS)
    await settle()
    // The one on the clock, then five at once
    expect(follows(api)).toHaveLength(6)
    expect(follows(api).map((query) => query?.after)).toEqual(['p9', 'q101', 'q102', 'q103', 'q104', 'q105'])
    await wait(FOLLOW_MS - 100)
    expect(follows(api)).toHaveLength(6)
    await wait(100)
    await settle()
    expect(follows(api)).toHaveLength(12)
    expect(shown().at(-1)).toBe('Catching up 111')
  })

  it('says a rolled log rolled over, and starts again at the top of its new file', async () => {
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        if (request.query?.after === 'p9')
          return { lines: [logLine('n0', { message: 'Healdan started' }), logLine('n1', { message: 'Indexer scheduled' })], before: 'n0', after: 'n2', atStart: false, atEnd: true, rolled: true } satisfies A['LogLines']
        if (request.query?.after === 'n2') return { lines: [], before: 'n2', after: 'n2', atStart: false, atEnd: true, rolled: false } satisfies A['LogLines']
        return fakeLog(logEntries())(request)
      },
    })
    await wait(FOLLOW_MS)
    await settle()
    expect(shown()).toEqual(['Healdan started', 'Indexer scheduled'])
    expect(screen.getByText('The log rolled over to a new file, so this starts again at its top. Show Earlier reads the lines before it.')).toBeTruthy()
    expect(within(box()).getByRole('button', { name: 'Show Earlier' })).toBeTruthy()
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api).at(-1)).toEqual({ after: 'n2', level: 'all' })
  })

  it.each([
    ['Show Earlier reads before the new top', async () => {
      fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
      await settle()
    }],
    ['Follow goes back to the newest after a pause', async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Pause' }))
      await settle()
      expect(screen.getByText(ROLLED)).toBeTruthy()
      fireEvent.click(screen.getByRole('button', { name: 'Follow' }))
      await settle()
    }],
    ['the new top is dropped as more come', async () => {
      await wait(FOLLOW_MS)
      await settle()
    }],
  ])("stops saying the log rolled over once it's no longer so: %s", async (_, then) => {
    const newFile = Array.from({ length: MAX_KEPT }, (_, i) => logLine(`n${i}`, { message: `New file ${i}` }))
    await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        if (request.query?.after === 'p9') return { lines: newFile.slice(0, 2), before: 'n0', after: 'n2', atStart: false, atEnd: true, rolled: true } satisfies A['LogLines']
        // Then the rest of the new file, past what's kept, so its top is dropped
        if (request.query?.after === 'n2') return { lines: newFile.slice(2).concat(logLine('n1000', { message: 'Newest' })), before: 'n2', after: 'n1001', atStart: false, atEnd: true, rolled: false } satisfies A['LogLines']
        if (request.query?.after !== undefined) return { lines: [], before: String(request.query.after), after: String(request.query.after), atStart: false, atEnd: true, rolled: false } satisfies A['LogLines']
        if (request.query?.before === 'n0') return { lines: logEntries().slice(0, 3).map((entry, i) => ({ ...entry, position: `o${i}` })), before: 'o0', after: 'n0', atStart: true, atEnd: false, rolled: false } satisfies A['LogLines']
        return fakeLog(logEntries())(request)
      },
    })
    await wait(FOLLOW_MS)
    await settle()
    expect(screen.getByText(ROLLED)).toBeTruthy()
    await then()
    expect(screen.queryByText(ROLLED)).toBeNull()
  })

  it("keeps the log's menu when a new read of it fails, saying it couldn't refresh, with Try Again", async () => {
    let failing = false
    await openLog('/server-log', { 'GET /v1/admin/logs': () => (failing ? problem(500, 'server-error') : logRoutes()['GET /v1/admin/logs']) })
    failing = true
    act(() => void window.dispatchEvent(new Event('focus')))
    await settle()
    expect(screen.getByText("Couldn't refresh this, so it shows what was read last.")).toBeTruthy()
    expect(within(screen.getByRole('combobox', { name: 'Log' })).getAllByRole('option')).toHaveLength(2)
    expect(shown()).toHaveLength(9)
    failing = false
    fireEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await settle()
    expect(screen.queryByText("Couldn't refresh this, so it shows what was read last.")).toBeNull()
  })

  it("keeps what's shown when a read fails, says so, and goes on trying", async () => {
    let failing = true
    const log = logEntries()
    const read = fakeLog(log)
    const { api } = await openLog('/server-log', { 'GET /v1/admin/logs/*/lines': (request: Request) => (failing && request.query?.after !== undefined ? problem(503, 'server-error') : read(request)) })
    log.push(logLine('', { message: 'Session opened for treeve' }))
    await wait(FOLLOW_MS)
    await settle()
    const note = screen.getByText("Couldn't read the newest lines, so this shows what was read last. Healdan keeps trying.")
    // Said once as it comes, not again at each try
    expect(note.closest('[role="status"]')).toBeTruthy()
    expect(shown()).toHaveLength(9)
    // The next try waits twice as long
    await wait(FOLLOW_MS * 2)
    await settle()
    expect(follows(api)).toHaveLength(2)
    failing = false
    await wait(FOLLOW_MS * 4)
    await settle()
    expect(follows(api)).toHaveLength(3)
    expect(screen.queryByText("Couldn't read the newest lines, so this shows what was read last. Healdan keeps trying.")).toBeNull()
    expect(shown().at(-1)).toBe('Session opened for treeve')
  })

  it('waits twice as long after each read that fails, a minute at most, and every 3 seconds again once one is answered', async () => {
    let failing = true
    const read = fakeLog(logEntries())
    const { api } = await openLog('/server-log', { 'GET /v1/admin/logs/*/lines': (request: Request) => (failing && request.query?.after !== undefined ? problem(500, 'server-error') : read(request)) })
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(1)
    // Each wait after a failure, then the try it ends in: ten failed tries in all
    for (const [index, waited] of [6_000, 12_000, 24_000, 48_000, 60_000, 60_000, 60_000, 60_000, 60_000].entries()) {
      await wait(waited - 1_000)
      expect(follows(api)).toHaveLength(index + 1)
      await wait(1_000)
      expect(follows(api)).toHaveLength(index + 2)
    }
    // Every 3 seconds, ten failures would have been some 130 tries by now
    expect(follows(api)).toHaveLength(10)
    failing = false
    await wait(60_000)
    await settle()
    expect(follows(api)).toHaveLength(11)
    expect(screen.queryByText("Couldn't read the newest lines, so this shows what was read last. Healdan keeps trying.")).toBeNull()
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(12)
  })

  it("waits twice as long after a quick re-read that fails, as after any follow's", async () => {
    let next = 100
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        if (request.query?.after === undefined) return fakeLog(logEntries())(request)
        const at = next++
        // The read on the clock isn't at the end, so the next is asked at once, and fails
        if (at > 100) return problem(500, 'server-error')
        return { lines: [logLine(`q${at}`, { message: `Catching up ${at}` })], before: `q${at}`, after: `q${at + 1}`, atStart: false, atEnd: false, rolled: false } satisfies A['LogLines']
      },
    })
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(2)
    await wait(FOLLOW_MS * 2 - 100)
    expect(follows(api)).toHaveLength(2)
    await wait(100)
    await settle()
    expect(follows(api)).toHaveLength(3)
  })

  it.each([
    [404, 'not-found'],
    [400, 'bad-request'],
  ])('ends the follow when the server answers %i, saying so, and Follow starts again from the newest lines', async (status, code) => {
    let gone = true
    const log = logEntries()
    const read = fakeLog(log)
    const { api } = await openLog('/server-log', { 'GET /v1/admin/logs/*/lines': (request: Request) => (gone && request.query?.after !== undefined ? problem(status, code) : read(request)) })
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(1)
    const note = screen.getByText("The server no longer knows this log, or the place it was read to, so following has stopped. Follow starts again from the newest lines.")
    expect(note.closest('[role="status"]')).toBeTruthy()
    expect(screen.queryByText("Couldn't read the newest lines, so this shows what was read last. Healdan keeps trying.")).toBeNull()
    expect(screen.getByText('Paused')).toBeTruthy()
    // What was read stays, and nothing more is asked
    expect(shown()).toHaveLength(9)
    await wait(5 * 60_000)
    expect(reads(api)).toHaveLength(2)

    gone = false
    log.push(logLine('', { message: 'Session opened for treeve' }))
    const follow = screen.getByRole('button', { name: 'Follow' })
    follow.focus()
    fireEvent.click(follow)
    await settle()
    // The newest lines, read afresh, then followed from there
    expect(reads(api).at(-1)).toEqual({ level: 'all' })
    expect(shown().at(-1)).toBe('Session opened for treeve')
    expect(screen.queryByText(/so following has stopped/)).toBeNull()
    expect(screen.getByText('Following Live')).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Pause' }))
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api).at(-1)).toEqual({ after: 'p10', level: 'all' })
  })

  it('stops for good when a follow is refused as admins only: the app says so, and nothing more is asked', async () => {
    const read = fakeLog(logEntries())
    const { api } = await openLog('/server-log', { 'GET /v1/admin/logs/*/lines': (request: Request) => (request.query?.after !== undefined ? problem(403, 'admins-only') : read(request)) })
    await wait(FOLLOW_MS)
    await settle()
    expect(screen.getByRole('heading', { level: 1, name: "You don't have access to Healdan Admin." })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Lines of Main Log' })).toBeNull()
    const asked = api.calls.length
    await wait(5 * 60_000)
    expect(api.calls).toHaveLength(asked)
  })

  it(`keeps ${MAX_KEPT} entries at most, the oldest dropped as new ones come, and Show Earlier reads back from the first kept`, async () => {
    const log = manyEntries(200)
    const { api } = await openLog('/server-log', logRoutes(log))
    expect(shown()).toHaveLength(200)
    for (let round = 0; round < 5; round++) {
      log.push(...manyEntries(200).map((entry, i) => ({ ...entry, message: `Entry ${200 + round * 200 + i}` })))
      await wait(FOLLOW_MS)
      await settle()
    }
    expect(shown()).toHaveLength(MAX_KEPT)
    expect(shown()[0]).toBe(`Entry ${1200 - MAX_KEPT}`)
    expect(shown().at(-1)).toBe('Entry 1199')
    fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
    await settle()
    expect(earlier(api)[0]).toEqual({ before: `p${1200 - MAX_KEPT}`, level: 'all' })
  })

  it(`doesn't move someone reading near the top of ${MAX_KEPT} entries as more follow in: it pauses, keeping their lines`, async () => {
    const log = manyEntries(200)
    const { api } = await openLog('/server-log', logRoutes(log))
    for (let round = 0; round < 4; round++) {
      log.push(...manyEntries(200).map((entry, i) => ({ ...entry, message: `Entry ${200 + round * 200 + i}` })))
      await wait(FOLLOW_MS)
      await settle()
    }
    expect(shown()).toHaveLength(MAX_KEPT)
    const lines = box()
    const scroll = layOut(lines)
    // Entry 5 at the top of the box
    scroll.scrollTo(5 * 20)
    log.push(...manyEntries(50).map((entry, i) => ({ ...entry, message: `Entry ${1000 + i}` })))
    await wait(FOLLOW_MS)
    await settle()
    // Not thrown to the end, nor anywhere else: entry 5 is where it was, with every line above it kept
    expect(scroll.top).toBeLessThan(lines.scrollHeight - lines.clientHeight)
    expect(scroll.top).toBe(5 * 20)
    expect(shown()[0]).toBe('Entry 0')
    expect(shown()).toHaveLength(MAX_KEPT)
    expect(screen.getByText('Paused')).toBeTruthy()
    expect(screen.getByText('Following paused, so the earlier lines stay. Follow goes back to the newest.')).toBeTruthy()
    await wait(FOLLOW_MS * 2)
    expect(follows(api)).toHaveLength(5)

    // Follow goes back to the newest, from where it paused, and drops the oldest again
    fireEvent.click(screen.getByRole('button', { name: 'Follow' }))
    await settle()
    expect(follows(api).at(-1)).toEqual({ after: `p${MAX_KEPT}`, level: 'all' })
    expect(shown().at(-1)).toBe('Entry 1049')
    expect(shown()[0]).toBe('Entry 50')
    expect(scroll.top).toBe(lines.scrollHeight - lines.clientHeight)
  })

  it("puts someone reading further up at the top of what's left, never the end, when the entry they read has gone", async () => {
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        // The log rolls over: ten entries of the new file, taller than the box
        if (request.query?.after === 'p9')
          return { lines: Array.from({ length: 10 }, (_, i) => logLine(`n${i}`, { message: `New file ${i}` })), before: 'n0', after: 'n10', atStart: false, atEnd: true, rolled: true } satisfies A['LogLines']
        if (request.query?.before === 'n0') return { lines: [], before: 'n0', after: 'n0', atStart: true, atEnd: false, rolled: false } satisfies A['LogLines']
        return fakeLog(logEntries())(request)
      },
    })
    const lines = box()
    const scroll = layOut(lines, 100)
    // Reading entry 2 of the old file, which the new one's entries replace
    scroll.scrollTo(40)
    await wait(FOLLOW_MS)
    await settle()
    expect(shown()).toHaveLength(10)
    expect(lines.scrollHeight - lines.clientHeight).toBe(100)
    expect(scroll.top).toBe(0)
    expect(earlier(api)).toEqual([{ before: 'n0', level: 'all' }])
  })

  it("doesn't take the focus, or the place of someone reading further up, as lines come; at the end, it keeps to the end", async () => {
    const log = logEntries()
    await openLog('/server-log', logRoutes(log))
    const lines = box()
    const scroll = layOut(lines, 100)
    lines.focus()
    // Reading further up
    scroll.scrollTo(60)
    log.push(logLine('', { message: 'Session opened for treeve' }))
    await wait(FOLLOW_MS)
    await settle()
    expect(shown()).toHaveLength(10)
    expect(scroll.top).toBe(60)
    expect(document.activeElement).toBe(lines)

    // At the end, the new line comes into view
    scroll.scrollTo(lines.scrollHeight - lines.clientHeight)
    log.push(logLine('', { message: 'Session opened for pnair' }))
    await wait(FOLLOW_MS)
    await settle()
    expect(scroll.top).toBe(lines.scrollHeight - lines.clientHeight)
    expect(document.activeElement).toBe(lines)
  })
})

describe('Earlier lines', () => {
  it('come with Show Earlier, from the first answer\'s before, keeping the place; the start of the log says so', async () => {
    const { api } = await openLog('/server-log', logRoutes(manyEntries(250)))
    expect(shown()).toHaveLength(200)
    expect(shown()[0]).toBe('Entry 50')
    expect(within(box()).queryByText('This is the start of the log: it holds nothing older.')).toBeNull()
    const scroll = layOut(box())
    // Entry 52 at the top of the box
    scroll.scrollTo(40)
    fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
    await settle()
    expect(earlier(api)).toEqual([{ before: 'p50', level: 'all' }])
    expect(shown()).toHaveLength(250)
    expect(shown()[0]).toBe('Entry 0')
    // Entry 52 is where it was
    expect(scroll.top).toBe(52 * 20)
    expect(within(box()).getByText('This is the start of the log: it holds nothing older.')).toBeTruthy()
    expect(within(box()).queryByRole('button', { name: 'Show Earlier' })).toBeNull()
  })

  it('give the focus Show Earlier had to the box when it goes at the start of the log', async () => {
    await openLog('/server-log', logRoutes(manyEntries(250)))
    const earlierButton = within(box()).getByRole('button', { name: 'Show Earlier' })
    earlierButton.focus()
    fireEvent.click(earlierButton)
    await settle()
    expect(within(box()).getByText('This is the start of the log: it holds nothing older.')).toBeTruthy()
    expect(earlierButton.isConnected).toBe(false)
    expect(document.activeElement).toBe(box())
  })

  it('take no focus when the log opens at its start', async () => {
    await openLog()
    expect(within(box()).getByText('This is the start of the log: it holds nothing older.')).toBeTruthy()
    expect(document.activeElement).toBe(document.body)
  })

  it('leave the focus where it is when the start of the log is reached with the focus elsewhere', async () => {
    await openLog('/server-log', logRoutes(manyEntries(250)))
    const words = screen.getByRole('textbox', { name: 'Search the Log' })
    words.focus()
    fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
    await settle()
    expect(within(box()).getByText('This is the start of the log: it holds nothing older.')).toBeTruthy()
    expect(document.activeElement).toBe(words)
  })

  it('come as the box is scrolled to its top', async () => {
    const { api } = await openLog('/server-log', logRoutes(manyEntries(250)))
    const scroll = layOut(box())
    scroll.scrollTo(400)
    await settle()
    expect(earlier(api)).toEqual([])
    scroll.scrollTo(0)
    await settle()
    expect(earlier(api)).toEqual([{ before: 'p50', level: 'all' }])
    expect(shown()).toHaveLength(250)
  })

  it('go on back by themselves while an answer is short and not at the start, a few reads in a row, then wait for Show Earlier', async () => {
    // 1,000 entries, one error in every hundred: each read looks through 200, and finds two
    const { api } = await openLog('/server-log?level=error', logRoutes(manyEntries(1000, (i) => i % 100 === 0)))
    expect(reads(api).map((query) => query?.before)).toEqual([undefined, 'p800', 'p600', 'p400'])
    expect(shown()).toEqual(['Entry 200', 'Entry 300', 'Entry 400', 'Entry 500', 'Entry 600', 'Entry 700', 'Entry 800', 'Entry 900'])
    expect(within(box()).getByRole('button', { name: 'Show Earlier' })).toBeTruthy()
    fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
    await settle()
    expect(earlier(api).map((query) => query?.before)).toEqual(['p800', 'p600', 'p400', 'p200'])
    expect(shown()[0]).toBe('Entry 0')
    expect(within(box()).getByText('This is the start of the log: it holds nothing older.')).toBeTruthy()
  })

  it(`pause the follow once more than ${MAX_KEPT} are kept, dropping the newest, which Follow reads again`, async () => {
    const { api } = await openLog('/server-log', logRoutes(manyEntries(1200)))
    const earlierButton = () => within(box()).getByRole('button', { name: 'Show Earlier' })
    for (let press = 0; press < 4; press++) {
      fireEvent.click(earlierButton())
      await settle()
    }
    expect(shown()).toHaveLength(MAX_KEPT)
    expect(screen.getByText('Following Live')).toBeTruthy()
    fireEvent.click(earlierButton())
    await settle()
    expect(shown()).toHaveLength(MAX_KEPT)
    expect(shown()[0]).toBe('Entry 0')
    expect(shown().at(-1)).toBe(`Entry ${MAX_KEPT - 1}`)
    expect(screen.getByText('Paused')).toBeTruthy()
    expect(screen.getByText('Following paused, so the earlier lines stay. Follow goes back to the newest.')).toBeTruthy()
    await wait(FOLLOW_MS * 2)
    expect(follows(api)).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: 'Follow' }))
    await settle()
    expect(follows(api)).toEqual([{ after: `p${MAX_KEPT}`, level: 'all' }])
    expect(shown().at(-1)).toBe('Entry 1199')
    expect(shown()).toHaveLength(MAX_KEPT)
    expect(screen.queryByText('Following paused, so the earlier lines stay. Follow goes back to the newest.')).toBeNull()
  })

  it('read the newest lines afresh on Follow when the first dropped is in an older file, and say no roll-over', async () => {
    const all = fakeLog(manyEntries(1200))
    const { api } = await openLog('/server-log', {
      'GET /v1/admin/logs/*/lines': (request: Request) => {
        // The first entry dropped is in the file the log rolled over to before: the server reads the current one's top
        if (request.query?.after === `p${MAX_KEPT}`)
          return { lines: [logLine('n0', { message: "The current file's top" })], before: 'n0', after: 'n1', atStart: false, atEnd: false, rolled: true } satisfies A['LogLines']
        return all(request)
      },
    })
    for (let press = 0; press < 5; press++) {
      fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
      await settle()
    }
    expect(screen.getByText('Paused')).toBeTruthy()
    const firstReads = reads(api).filter((query) => query?.after === undefined && query?.before === undefined).length

    fireEvent.click(screen.getByRole('button', { name: 'Follow' }))
    await settle()
    expect(follows(api)).toEqual([{ after: `p${MAX_KEPT}`, level: 'all' }])
    // The newest again, as when the log opened, following, with no note that the log rolled over
    expect(reads(api).filter((query) => query?.after === undefined && query?.before === undefined)).toHaveLength(firstReads + 1)
    expect(shown().at(-1)).toBe('Entry 1199')
    expect(shown()).not.toContain("The current file's top")
    expect(screen.queryByText(ROLLED)).toBeNull()
    expect(screen.getByText('Following Live')).toBeTruthy()
  })

  it('follow again once the log or a filter changes, after a pause that only kept the earlier lines', async () => {
    const { api } = await openLog('/server-log', logRoutes(manyEntries(1200)))
    for (let press = 0; press < 5; press++) {
      fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
      await settle()
    }
    expect(screen.getByText('Paused')).toBeTruthy()
    fireEvent.click(screen.getByRole('radio', { name: 'Errors' }))
    await settle()
    expect(screen.getByText('Following Live')).toBeTruthy()
    expect(screen.queryByText('Following paused, so the earlier lines stay. Follow goes back to the newest.')).toBeNull()
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api).at(-1)).toEqual({ after: 'p1200', level: 'error' })

    // A pause the person chose stays across a change
    fireEvent.click(screen.getByRole('button', { name: 'Pause' }))
    await settle()
    fireEvent.click(screen.getByRole('radio', { name: 'Everything' }))
    await settle()
    expect(screen.getByText('Paused')).toBeTruthy()
  })

  it('go back by themselves the same few reads after Show Earlier as after the first read', async () => {
    // 2,000 entries, one error in every hundred: each read looks through 200, and finds two
    const { api } = await openLog('/server-log?level=error', logRoutes(manyEntries(2000, (i) => i % 100 === 0)))
    // The first read, then three by themselves
    expect(reads(api).map((query) => query?.before)).toEqual([undefined, 'p1800', 'p1600', 'p1400'])
    fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
    await settle()
    // The read Show Earlier asked for, then three by themselves, and Show Earlier is offered again
    expect(earlier(api).map((query) => query?.before)).toEqual(['p1800', 'p1600', 'p1400', 'p1200', 'p1000', 'p800', 'p600'])
    expect(within(box()).getByRole('button', { name: 'Show Earlier' })).toBeTruthy()
  })

  it("say so when they couldn't be read, keeping what's shown", async () => {
    let failing = true
    const read = fakeLog(manyEntries(250))
    await openLog('/server-log', { 'GET /v1/admin/logs/*/lines': (request: Request) => (failing && request.query?.before !== undefined ? problem(500, 'server-error') : read(request)) })
    fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
    await settle()
    expect(within(box()).getByText("Couldn't read the earlier lines.")).toBeTruthy()
    expect(shown()).toHaveLength(200)
    failing = false
    fireEvent.click(within(box()).getByRole('button', { name: 'Show Earlier' }))
    await settle()
    expect(shown()).toHaveLength(250)
    expect(within(box()).queryByText("Couldn't read the earlier lines.")).toBeNull()
  })
})
