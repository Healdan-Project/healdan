// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { FOLLOW_MS, MAX_KEPT, formatLogTime } from '../monitoring/useLogLines'
import monitoringCss from '../monitoring/Monitoring.module.css?raw'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import { fakeLog, logEntries, logLine, logRoutes, problem, type Routes } from '../test/fakeApi'
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

  it('offers Download, a link to the zip of the log shown', async () => {
    await openLog()
    const link = screen.getByRole('link', { name: 'Download' })
    expect(link.getAttribute('href')).toBe('/api/v1/admin/logs/DMS/file')
    expect(link.hasAttribute('download')).toBe(true)
    fireEvent.change(screen.getByRole('combobox', { name: 'Log' }), { target: { value: 'IndexerTask' } })
    await settle()
    expect(screen.getByRole('link', { name: 'Download' }).getAttribute('href')).toBe('/api/v1/admin/logs/IndexerTask/file')
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
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(2)
    failing = false
    await wait(FOLLOW_MS)
    await settle()
    expect(follows(api)).toHaveLength(3)
    expect(screen.queryByText("Couldn't read the newest lines, so this shows what was read last. Healdan keeps trying.")).toBeNull()
    expect(shown().at(-1)).toBe('Session opened for treeve')
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
