// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { memo, useLayoutEffect, useRef } from 'react'
import type { adminComponents } from '@healdan/api'
import { Button } from '@healdan/ui'
import { t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'
import { formatLogTime, shortSource, type LogLines as Lines } from './useLogLines'
import styles from './Monitoring.module.css'

type A = adminComponents['schemas']

/** Within this many pixels of its end the box is at its end, and keeps to it as lines come */
const END_SLACK = 4
/** Scrolled to within this many pixels of its top, the box reads earlier lines */
const TOP_SLACK = 8

const LEVELS: Record<NonNullable<A['LogLine']['level']>, AdminStringKey> = {
  TRACE: 'admin.serverLog.entry.TRACE',
  DEBUG: 'admin.serverLog.entry.DEBUG',
  INFO: 'admin.serverLog.entry.INFO',
  WARN: 'admin.serverLog.entry.WARN',
  ERROR: 'admin.serverLog.entry.ERROR',
  FATAL: 'admin.serverLog.entry.FATAL',
}

/** A line that starts no entry, as the rest of a stack an earlier read ended in: no time, level or source */
function continues(entry: A['LogLine']): boolean {
  return entry.time === null && entry.level === null && entry.source === null
}

/**
 * An entry: its time, its level in words (and in its colour, never by that alone), where it was logged from and its
 * message, with any stack under it. All of it is text: what the server logged is never drawn as markup.
 */
const Entry = memo(function Entry({ entry }: { entry: A['LogLine'] }) {
  if (continues(entry))
    return (
      <li className={styles.entry} data-position={entry.position} data-continues="true">
        <span data-message className={`${styles.message} ${styles.continues}`}>
          {entry.message}
        </span>
      </li>
    )
  return (
    <li className={styles.entry} data-position={entry.position}>
      {entry.time === null ? <span /> : (
        <time className={styles.time} dateTime={entry.time}>
          {formatLogTime(entry.time)}
        </time>
      )}
      {entry.level === null ? <span /> : (
        <span className={styles.badge} data-level={entry.level}>
          {t(LEVELS[entry.level])}
        </span>
      )}
      {entry.source === null ? <span /> : (
        <span className={styles.source} title={entry.source}>
          {shortSource(entry.source)}
        </span>
      )}
      <span data-message className={styles.message}>
        {entry.message}
      </span>
      {entry.continuation.length > 0 && <pre className={styles.continuation}>{entry.continuation.join('\n')}</pre>}
    </li>
  )
})

/** The first entry still in view at the box's top, and how far below the top it starts, to keep it there as lines come */
function firstInView(box: HTMLElement): { position: string; offset: number } | undefined {
  const items = box.querySelectorAll<HTMLElement>('li[data-position]')
  let low = 0
  let high = items.length - 1
  let found: HTMLElement | undefined
  while (low <= high) {
    const middle = (low + high) >> 1
    const item = items[middle]!
    if (item.offsetTop + item.offsetHeight > box.scrollTop) {
      found = item
      high = middle - 1
    } else low = middle + 1
  }
  const position = found?.getAttribute('data-position')
  return found && position !== null && position !== undefined ? { position, offset: found.offsetTop - box.scrollTop } : undefined
}

export interface LogLinesProps {
  /** The log's label, as its menu shows it, which names the box */
  label: string
  lines: Lines
  /** The level and the words keep only some entries, so an empty box says none match */
  filtered: boolean
}

/**
 * The box of a log's lines (spec 8.8): oldest at the top, newest at the bottom, with Show Earlier above them, or the start
 * of the log said. It scrolls inside, so it's a Tab stop the arrows scroll, named apart from every other region on the
 * page, and it isn't a live region: lines arriving aren't said one by one, nor do they take the focus. At its end it keeps
 * to its end as lines come; read further up, the entry at its top stays where it is, whatever is added or dropped, and the
 * follow pauses at MAX_KEPT rather than drop the lines being read (it's told, `readingUp`). Should the entry at its top go
 * all the same, as when the log rolls over, the reader is put at the top of what's left, never the end, and earlier lines
 * are read. Scrolled to its top, it reads earlier lines.
 */
export function LogLines({ label, lines, filtered }: LogLinesProps) {
  const box = useRef<HTMLDivElement>(null)
  const atEnd = useRef(true)
  const anchor = useRef<{ position: string; offset: number } | undefined>(undefined)
  const wasFollowing = useRef(lines.following)
  // The lines as last drawn, for the effects below, which run as the entries change and call into what reads them
  const latest = useRef(lines)
  useLayoutEffect(() => {
    latest.current = lines
  })

  function measure() {
    const element = box.current
    if (!element) return
    atEnd.current = element.scrollHeight - element.scrollTop - element.clientHeight <= END_SLACK
    anchor.current = firstInView(element)
    latest.current.readingUp(!atEnd.current)
  }

  // As entries are added or dropped: at the end, to the end; else the entry at the top where it was. Should it have gone,
  // the top of what's left, and the lines before it read, so the reader is never thrown to the newest.
  useLayoutEffect(() => {
    const element = box.current
    if (!element) return
    const kept = anchor.current
    if (atEnd.current || kept === undefined) element.scrollTop = element.scrollHeight
    else {
      const item = [...element.querySelectorAll<HTMLElement>('li[data-position]')].find((one) => one.getAttribute('data-position') === kept.position)
      if (item) element.scrollTop = item.offsetTop - kept.offset
      else {
        element.scrollTop = 0
        latest.current.showEarlier()
      }
    }
    measure()
  }, [lines.entries])

  // Follow, pressed: back to the newest, and kept there
  useLayoutEffect(() => {
    const element = box.current
    if (lines.following && !wasFollowing.current && element) {
      element.scrollTop = element.scrollHeight
      measure()
    }
    wasFollowing.current = lines.following
  }, [lines.following])

  function onScroll() {
    measure()
    const element = box.current
    // Not after a read of them failed: Show Earlier asks again, so a box held at its top doesn't ask at every scroll
    if (element && element.scrollTop <= TOP_SLACK && lines.state === 'loaded' && !lines.atStart && lines.earlier === 'idle') lines.showEarlier()
  }

  const loaded = lines.state === 'loaded'
  return (
    <div ref={box} role="region" aria-label={t('admin.serverLog.lines', { log: label })} tabIndex={0} className={styles.lines} onScroll={onScroll}>
      {loaded && (
        <div className={styles.top}>
          {lines.atStart ? (
            <p>{t('admin.serverLog.atStart')}</p>
          ) : (
            <Button onPress={lines.showEarlier} isPending={lines.earlier === 'loading'}>
              {t('admin.serverLog.earlier')}
            </Button>
          )}
          {lines.earlier === 'failed' && <p role="alert">{t('admin.serverLog.earlierFailed')}</p>}
        </div>
      )}
      {!loaded ? (
        <p className={styles.empty}>{t('common.loading')}</p>
      ) : lines.entries.length === 0 ? (
        <p className={styles.empty}>{t(filtered ? 'admin.serverLog.noneMatch' : 'admin.serverLog.empty')}</p>
      ) : (
        <ol role="list" className={styles.entries}>
          {lines.entries.map((entry) => (
            <Entry key={entry.position} entry={entry} />
          ))}
        </ol>
      )}
    </div>
  )
}
