// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, useState } from 'react'
import { apiPath, type Api, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import type { LogLevel } from './logQueries'

type A = adminComponents['schemas']

/** While following, how long the screen waits between two reads of what's new (P5-R21) */
export const FOLLOW_MS = 3_000
/** An answer not at the end of the log is followed by another at once, at most this many in a row (P5-R21, amended) */
export const QUICK_READS = 5
/**
 * Going back, a short answer not at the start is followed by another by itself, at most this many after the read that
 * started the run, whichever started it: the first read or Show Earlier (P5-R27)
 */
export const EARLIER_RUN = 3
/** The most entries a read gives (the contract's `maxItems`): an answer with fewer, not at the start, is short */
export const LOG_PAGE = 200
/**
 * The most entries the screen keeps: five full reads. Following drops the oldest past it while the box is at its end; with
 * the reader further up, it pauses there instead, as Show Earlier does past it (dropping the newest), so the lines being
 * read stay.
 */
export const MAX_KEPT = 1_000

/** What the screen shows of a log's lines, and how it reads them */
export interface LogView {
  /** The entries kept, oldest first, each once by its position */
  entries: A['LogLine'][]
  /** The first read: on its way, answered, or failed (`error`) */
  state: 'loading' | 'loaded' | 'failed'
  error: unknown
  /** The entries reach the start of the oldest file: the log holds nothing older */
  atStart: boolean
  /** The log rolled over to a new file while followed, so the entries start again at its top */
  rolled: boolean
  /** The person's choice: Follow, or Pause. The screen reads what's new only while this and the page is visible. */
  following: boolean
  /** The last read of what's new failed: what's shown is what was read last, and the follow goes on trying */
  pollFailed: boolean
  /** A read of earlier entries: none, on its way, or failed */
  earlier: 'idle' | 'loading' | 'failed'
  /** Following was paused so that the entries read further back aren't dropped as new ones come */
  pausedToKeep: boolean
}

export interface LogLines extends LogView {
  /** Pause: no more reads of what's new until Follow */
  pause: () => void
  /** Follow: a read of what's new at once, then every FOLLOW_MS */
  follow: () => void
  /** Show Earlier, or the box scrolled to its top: the entries before the first kept, then on by itself while short */
  showEarlier: () => void
  /**
   * Whether the reader is further up than the box's end, which the box says as it's scrolled: past MAX_KEPT, what's new
   * then pauses the follow rather than drop the oldest, which may be the lines being read
   */
  readingUp: (up: boolean) => void
  /** Try Again, after the first read failed */
  retry: () => void
}

function viewOf(following: boolean): LogView {
  return { entries: [], state: 'loading', error: undefined, atStart: false, rolled: false, following, pollFailed: false, earlier: 'idle', pausedToKeep: false }
}

/** An answer going back that holds fewer than a read can give and isn't at the start: a level or words kept few of a read */
function isShort(answer: A['LogLines']): boolean {
  return !answer.atStart && answer.lines.length < LOG_PAGE
}

/**
 * `older`, then those of `newer` whose positions it hasn't (positions are unique within a log), oldest first. Nothing
 * added keeps the list as it was, the same array, so a read that brought nothing draws nothing again.
 */
function joined(older: A['LogLine'][], newer: A['LogLine'][]): A['LogLine'][] {
  const known = new Set(older.map((entry) => entry.position))
  const added = newer.filter((entry) => !known.has(entry.position))
  if (added.length === 0) return older
  return older.length === 0 ? newer : [...older, ...added]
}

interface Session {
  pause(): void
  follow(): void
  showEarlier(): void
  readingUp(up: boolean): void
  retry(): void
  stop(): void
}

/**
 * Reads one log with one level and words: the newest entries first, then what's new every FOLLOW_MS while following and
 * the page is visible, and earlier entries when asked. Each request carries its own signal, and an answer comes through
 * only while it's still the one asked for: `stop`, a pause, the page hidden or a new read of the same kind cancels the one
 * on its way, and drops its answer should it come all the same.
 */
function openSession(api: Api, name: string, level: LogLevel, q: string | undefined, following: boolean, publish: (view: LogView) => void): Session {
  const path = apiPath`/v1/admin/logs/${name}/lines`
  const view = viewOf(following)
  // Where the next read of earlier entries ends, and where the next read of what's new starts
  let before: string | undefined
  let after: string | undefined
  let stopped = false
  let visible = document.visibilityState !== 'hidden'
  let timer: ReturnType<typeof setTimeout> | undefined
  let firstCall: AbortController | undefined
  let followCall: AbortController | undefined
  let earlierCall: AbortController | undefined
  // Reads of what's new asked at once, in a row, because the one before wasn't at the end
  let quick = 0
  // The reader is further up than the box's end
  let up = false

  const show = () => {
    if (!stopped) publish({ ...view })
  }
  const live = () => !stopped && view.following && visible && view.state === 'loaded'
  const read = (from: { before?: string; after?: string }, signal: AbortSignal) => api.get<A['LogLines']>(path, { ...from, level, q }, { signal })

  function stopFollowing() {
    clearTimeout(timer)
    timer = undefined
    followCall?.abort()
    followCall = undefined
    quick = 0
  }

  function stopEarlier() {
    earlierCall?.abort()
    earlierCall = undefined
    if (view.earlier === 'loading') view.earlier = 'idle'
  }

  function schedule() {
    clearTimeout(timer)
    timer = undefined
    if (live() && followCall === undefined) timer = setTimeout(poll, FOLLOW_MS)
  }

  /** What's new is added at the end, the oldest dropped past MAX_KEPT; a log that rolled over starts again at its new top */
  function append(answer: A['LogLines']) {
    if (answer.rolled) {
      // What was read further back is the older file's, which Show Earlier reaches again from the new file's top
      stopEarlier()
      view.entries = answer.lines.slice(-MAX_KEPT)
      before = answer.before
      view.atStart = answer.atStart
      view.rolled = true
    } else {
      const entries = joined(view.entries, answer.lines)
      if (entries.length > MAX_KEPT && up) {
        // The oldest may be the lines being read: keep them, and what fits after them, and pause there. Follow reads the
        // rest again, from the first left out.
        view.entries = view.entries.length >= MAX_KEPT ? view.entries : entries.slice(0, MAX_KEPT)
        after = entries[MAX_KEPT]!.position
        pauseToKeep()
        return
      }
      if (entries.length > MAX_KEPT) {
        // Show Earlier reads the dropped ones again, from the first kept
        stopEarlier()
        view.entries = entries.slice(-MAX_KEPT)
        before = view.entries[0]!.position
        view.atStart = false
      } else view.entries = entries
    }
    after = answer.after
  }

  /** The follow pauses so the lines read further back stay, and says why */
  function pauseToKeep() {
    if (!view.following) return
    stopFollowing()
    view.following = false
    view.pausedToKeep = true
  }

  /** Earlier entries are added at the start; past MAX_KEPT the newest go, and the follow pauses so these stay */
  function prepend(answer: A['LogLines']) {
    const entries = joined(answer.lines, view.entries)
    before = answer.before
    view.atStart = answer.atStart
    if (entries.length > MAX_KEPT) {
      // A follow reads them again, from the first dropped, once Follow is pressed
      after = entries[MAX_KEPT]!.position
      view.entries = entries.slice(0, MAX_KEPT)
      pauseToKeep()
    } else view.entries = entries
  }

  function poll() {
    timer = undefined
    if (!live() || followCall !== undefined || after === undefined) return
    const call = new AbortController()
    followCall = call
    read({ after }, call.signal).then(
      (answer) => {
        if (stopped || call !== followCall) return
        followCall = undefined
        append(answer)
        view.pollFailed = false
        show()
        if (!answer.atEnd && quick < QUICK_READS) {
          quick++
          poll()
        } else {
          quick = 0
          schedule()
        }
      },
      () => {
        // A follow cancelled (paused, hidden, stopped) is no longer the one asked for, so it never says it failed
        if (stopped || call !== followCall) return
        followCall = undefined
        quick = 0
        // What's shown stays, and the next read goes on trying
        view.pollFailed = true
        show()
        schedule()
      },
    )
  }

  /**
   * After a read going back (`byItself` of them asked by the screen itself since the read that started the run): on back by
   * itself while the answer is short and not at the start, at most EARLIER_RUN reads after the one that started it
   */
  function goOnBack(answer: A['LogLines'], byItself: number) {
    if (isShort(answer) && byItself < EARLIER_RUN) earlier(byItself + 1)
  }

  /** The entries before the first kept; `byItself` is how many reads of this run the screen asked for by itself, this one included */
  function earlier(byItself: number) {
    if (view.state !== 'loaded' || view.atStart || before === undefined || earlierCall !== undefined) return
    const call = new AbortController()
    earlierCall = call
    view.earlier = 'loading'
    show()
    read({ before }, call.signal).then(
      (answer) => {
        if (stopped || call !== earlierCall) return
        earlierCall = undefined
        prepend(answer)
        view.earlier = 'idle'
        show()
        // A short answer goes on back by itself, a few in a row; then Show Earlier asks again
        goOnBack(answer, byItself)
      },
      () => {
        if (stopped || call !== earlierCall) return
        earlierCall = undefined
        view.earlier = 'failed'
        show()
      },
    )
  }

  function first() {
    firstCall?.abort()
    const call = new AbortController()
    firstCall = call
    read({}, call.signal).then(
      (answer) => {
        if (stopped || call !== firstCall) return
        firstCall = undefined
        view.entries = answer.lines.slice(-MAX_KEPT)
        before = answer.before
        after = answer.after
        view.atStart = answer.atStart
        view.state = 'loaded'
        view.error = undefined
        show()
        // The first read starts a run, as Show Earlier does
        goOnBack(answer, 0)
        schedule()
      },
      (error: unknown) => {
        if (stopped || call !== firstCall) return
        firstCall = undefined
        view.state = 'failed'
        view.error = error
        show()
      },
    )
  }

  function onVisibility() {
    visible = document.visibilityState !== 'hidden'
    // Hidden, nothing is asked; shown again, what's new is asked for at once
    if (!visible) stopFollowing()
    else poll()
  }

  document.addEventListener('visibilitychange', onVisibility)
  first()

  return {
    pause() {
      stopFollowing()
      view.following = false
      view.pausedToKeep = false
      show()
    },
    follow() {
      // Follow goes back to the newest: the box goes to its end
      up = false
      view.following = true
      view.pausedToKeep = false
      show()
      if (timer === undefined) poll()
    },
    showEarlier() {
      earlier(0)
    },
    readingUp(next: boolean) {
      up = next
    },
    retry() {
      if (view.state !== 'failed') return
      view.state = 'loading'
      show()
      first()
    },
    stop() {
      stopped = true
      stopFollowing()
      firstCall?.abort()
      earlierCall?.abort()
      document.removeEventListener('visibilitychange', onVisibility)
    },
  }
}

/**
 * A log's lines for the Server Log (spec 8.8): the newest entries of `name` at `level` with `q`, then, while following,
 * the page is visible and the person hasn't paused, what's new every FOLLOW_MS (asked again at once while an answer isn't
 * at the end, at most QUICK_READS in a row), and earlier entries with Show Earlier, which go on back by themselves while
 * they come short (P5-R21, P5-R27). At most MAX_KEPT entries are kept. A new log, level or words starts afresh, keeping
 * only whether the person follows; leaving the page stops everything, and an answer that comes late is dropped. Nothing
 * is read without a name.
 */
export function useLogLines(name: string | undefined, level: LogLevel, q: string | undefined): LogLines {
  const api = useApi()
  // What's read is this log's, with these filters; a new one starts from nothing, keeping Follow or Pause
  const key = name === undefined ? undefined : JSON.stringify([name, level, q ?? ''])
  const [view, setView] = useState<LogView>(() => viewOf(true))
  const [shownKey, setShownKey] = useState(key)
  // A pause made only to keep the lines read further back ends with them: the new log or filters follow again
  const followNext = view.following || view.pausedToKeep
  if (key !== shownKey) {
    setShownKey(key)
    setView(viewOf(followNext))
  }
  // Read by a new session, which a change of log or filters opens after a pause
  const following = useRef(followNext)
  useEffect(() => {
    following.current = followNext
  }, [followNext])

  const session = useRef<Session | undefined>(undefined)
  useEffect(() => {
    if (name === undefined) return undefined
    const opened = openSession(api, name, level, q, following.current, setView)
    session.current = opened
    return () => {
      opened.stop()
      if (session.current === opened) session.current = undefined
    }
  }, [api, name, level, q])

  return {
    ...view,
    pause: () => session.current?.pause(),
    follow: () => session.current?.follow(),
    showEarlier: () => session.current?.showEarlier(),
    readingUp: (up: boolean) => session.current?.readingUp(up),
    retry: () => session.current?.retry(),
  }
}

const TIME = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
const DAY_AND_TIME = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })

/** When an entry was logged, to the second, in the browser's time zone: its time alone today, with its day before then */
export function formatLogTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const today = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
  return (today ? TIME : DAY_AND_TIME).format(date)
}

/** Where an entry was logged from, as the log writes it (`com.logicaldoc.healdan.storage.Mover:322`), as its class's own name and the line */
export function shortSource(source: string): string {
  const colon = source.lastIndexOf(':')
  const where = colon < 0 ? source : source.slice(0, colon)
  const line = colon < 0 ? '' : source.slice(colon)
  return where.slice(where.lastIndexOf('.') + 1) + line
}
