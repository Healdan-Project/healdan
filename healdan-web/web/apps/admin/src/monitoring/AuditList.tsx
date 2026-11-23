// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel } from '@healdan/shell'
import { Button, formatDateTime, Table, VisuallyHidden, type TableColumn, type TableRow } from '@healdan/ui'
import { userAppDocHref, userAppFolderHref } from '../addresses'
import { focusLost } from '../focus'
import type { CursorListing } from '../lists/useCursorList'
import { StaleNote } from '../people/StaleNote'
import { plural, t } from '../strings'
import { useAuditList, type AuditQuery } from './queries'
import { changeLines, detailCell, fullTime, kindLabel } from './text'
import styles from './Audit.module.css'

type A = adminComponents['schemas']

/** A row of the list: the Audit Trail's row, keyed by its id, which no other row has */
interface AuditTableRow extends TableRow {
  row: A['AuditRow']
}

function tableRowOf(row: A['AuditRow']): AuditTableRow {
  return { id: row.id, textValue: [formatDateTime(row.when), row.who, row.what].filter(Boolean).join(' '), row }
}

/**
 * The columns (spec 8.8): Time, Who, Kind, What, Detail and From, every cell plain text on its one line, as a row's words
 * are what people typed. Nothing sorts: the list is newest first.
 */
function auditColumns(): TableColumn<AuditTableRow>[] {
  return [
    { id: 'time', name: t('admin.audit.column.time'), isRowHeader: true, width: 170, minWidth: 140, cell: ({ row }) => formatDateTime(row.when) },
    // A row that names no one, as a change to an account its row names no author of, says so as its details do
    { id: 'who', name: t('admin.audit.column.who'), width: '1fr', minWidth: 120, cell: ({ row }) => row.who ?? t('admin.audit.notRecorded') },
    { id: 'kind', name: t('admin.audit.column.kind'), width: 130, minWidth: 110, cell: ({ row }) => kindLabel(row.kind) },
    { id: 'what', name: t('admin.audit.column.what'), width: '1.5fr', minWidth: 140, cell: ({ row }) => row.what },
    { id: 'detail', name: t('admin.audit.column.detail'), width: '3fr', minWidth: 200, cell: ({ row }) => detailCell(row) },
    { id: 'from', name: t('admin.audit.column.from'), width: 140, minWidth: 100, cell: ({ row }) => row.from ?? '' },
  ]
}

/**
 * The selected row, whole, under the list, as the list's rows keep to one line (spec 8.8): when it was done to the second,
 * each of its words in full, an administration row's changed fields, old and new (a secret only as changed), and the
 * document or folder it names, to open in the User app. A box that scrolls, so a Tab stop of its own, by its name.
 */
function RowDetails({ row }: { row: A['AuditRow'] }) {
  const changesId = useId()
  const changes = changeLines(row.changes)
  const open =
    row.docNumber !== null
      ? { href: userAppDocHref(row.docNumber), label: t('admin.audit.openDocument') }
      : row.folderId !== null
        ? { href: userAppFolderHref(row.folderId), label: t('admin.audit.openFolder') }
        : undefined
  const recorded = (value: string | null) => value ?? t('admin.audit.notRecorded')
  return (
    <section className={styles.details} aria-label={t('admin.audit.details')} tabIndex={0}>
      <dl className={styles.facts}>
        <dt>{t('admin.audit.detail.when')}</dt>
        <dd>
          <time dateTime={row.when}>{fullTime(row.when)}</time>
        </dd>
        <dt>{t('admin.audit.detail.who')}</dt>
        <dd>{recorded(row.who)}</dd>
        <dt>{t('admin.audit.detail.kind')}</dt>
        <dd>{kindLabel(row.kind)}</dd>
        <dt>{t('admin.audit.detail.what')}</dt>
        <dd>{row.what}</dd>
        <dt>{t('admin.audit.detail.detail')}</dt>
        <dd>{recorded(row.detail)}</dd>
        <dt>{t('admin.audit.detail.from')}</dt>
        <dd>{recorded(row.from)}</dd>
      </dl>
      {changes.length > 0 && (
        <div className={styles.changes}>
          <p id={changesId} className={styles.changesTitle}>
            {t('admin.audit.detail.changes')}
          </p>
          <ul aria-labelledby={changesId}>
            {changes.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </div>
      )}
      {open && (
        <a className={styles.open} href={open.href}>
          {open.label}
        </a>
      )}
    </section>
  )
}

/** Where the list's reading stands, as Show More and an empty list say it */
export interface Reading {
  /** More may come for these filters, so Show More is there */
  offering: boolean
  /**
   * A page is on its way, or about to be asked for by the list itself, so Show More is pending: a page that has just come
   * back short, with a cursor, is followed a moment later, and the list says nothing of the end meanwhile
   */
  following: boolean
  /** What a list with no rows says: that it's loading, that nothing has been found yet in what was read, or that nothing is there */
  empty: 'loading' | 'noneYet' | 'none'
}

/**
 * Where the reading stands, from the list's state alone: between a page that came back short and the one the list asks for
 * itself, neither more is offered nor a page on its way, which is still loading, never the end
 */
export function readingOf(listing: Pick<CursorListing<unknown>, 'isLoaded' | 'isStale' | 'atEnd' | 'hasMore'>): Reading {
  const offering = listing.isLoaded && !listing.isStale && !listing.atEnd
  return { offering, following: offering && !listing.hasMore, empty: listing.hasMore ? 'noneYet' : listing.atEnd ? 'none' : 'loading' }
}

export interface AuditListProps {
  /** The filters, as the route takes them; the time zone is the list's own */
  query: AuditQuery
  /** A filter is set beyond what the page fixes, which is what an empty list says */
  filtered: boolean
  /** The list's name for a screen reader */
  label: string
  /** Fills what its parent leaves it, as on the Audit Trail; a box of its own height otherwise, as in an Activity tab */
  fill?: boolean
}

/**
 * The Audit Trail's list (spec 8.8), for its screen and the Activity tabs: the rows the filters keep, newest first, a page
 * at a time by cursor (P5-R22), the next as the end of the list comes into view, or with Show More, and the end said so.
 * How far back the reading has reached (`readBackTo`, P5-R33) is said beside Show More, and by a list that has found
 * nothing yet; how a run of Show More ended is said to a screen reader. Selecting a row shows it whole below the list.
 * While a new read fails, the rows it had stay, with a note that it couldn't refresh (P4-R15).
 */
export function AuditList({ query, filtered, label, fill = false }: AuditListProps) {
  const listing = useAuditList(query)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // Built once, and kept, so a new page doesn't draw every row again
  const columns = useMemo(() => auditColumns(), [])
  const rows = useMemo(() => listing.items.map(tableRowOf), [listing.items])
  const selection = useMemo(() => new Set(selectedId === null ? [] : [selectedId]), [selectedId])
  const selected = listing.items.find((row) => row.id === selectedId)

  const { offering, following, empty: emptyKind } = readingOf(listing)
  // How far back the last page read: every row the filters keep that's newer has been given. Null at the end.
  const readBackTo = listing.last?.readBackTo ?? null
  const noneYet = readBackTo === null ? t('admin.audit.noneYet') : t('admin.audit.noneBackTo', { when: formatDateTime(readBackTo) })
  // Show More goes at the end: the focus it had then goes to what says so, or to the list when there's nothing in it
  const frame = useRef<HTMLDivElement>(null)
  const end = useRef<HTMLParagraphElement>(null)
  const pressed = useRef(false)
  const shown = useRef(offering)
  useEffect(() => {
    const was = shown.current
    shown.current = offering
    if (!was || offering || !pressed.current) return
    pressed.current = false
    if (focusLost()) (end.current ?? frame.current?.querySelector<HTMLElement>('[role="grid"]'))?.focus()
  }, [offering])

  // How a run of Show More ended, said once it has: so many more rows, nothing found back to where it read, or the end.
  // A run is these filters'; a failed one is said by the note that the list couldn't refresh.
  const question = JSON.stringify(query)
  const run = useRef<{ question: string; count: number } | undefined>(undefined)
  const [said, setSaid] = useState<{ question: string; text: string }>()
  const { items, atEnd, isError, last } = listing
  useEffect(() => {
    const started = run.current
    if (started === undefined || following) return
    run.current = undefined
    if (started.question !== question || isError) return
    const added = items.length - started.count
    setSaid({ question, text: added > 0 ? plural('admin.audit.moreRows', added) : atEnd ? t('admin.audit.end') : noneYet })
  }, [following, items.length, atEnd, isError, last, question, noneYet])

  const empty =
    emptyKind === 'loading' ? (
      t('common.loading')
    ) : (
      <p className={styles.empty}>{emptyKind === 'noneYet' ? noneYet : t(filtered ? 'admin.audit.noneMatch' : 'admin.audit.none')}</p>
    )

  return (
    <div className={fill ? `${styles.list} ${styles.fill}` : `${styles.list} ${styles.fixed}`}>
      <StaleNote failed={listing.isError && listing.isLoaded} onRetry={listing.refetch} />
      {listing.isError && !listing.isLoaded ? (
        <ErrorPanel error={listing.error} onRetry={listing.refetch} />
      ) : (
        <>
          <div ref={frame} className={styles.rows}>
            <Table
              aria-label={label}
              columns={columns}
              rows={rows}
              selectionMode="single"
              selection={selection}
              onSelectionChange={(keys) => setSelectedId([...keys][0] ?? null)}
              // As the end comes into view, after a full page only, so a run stopped over short pages, or a page that failed,
              // waits for Show More or Try Again; a page on its way shows there as loading
              loadMore={offering ? listing.loadMoreOnScroll : undefined}
              isLoadingMore={listing.isLoadingMore}
              empty={empty}
              busy={listing.isStale}
            />
          </div>
          <div className={styles.footer}>
            {offering && (
              <Button
                isPending={following}
                onPress={() => {
                  pressed.current = true
                  run.current = { question, count: listing.items.length }
                  setSaid(undefined)
                  listing.showMore()
                }}
              >
                {t('admin.audit.showMore')}
              </Button>
            )}
            {offering && listing.items.length > 0 && readBackTo !== null && <p className={styles.end}>{t('admin.audit.reach', { when: formatDateTime(readBackTo) })}</p>}
            {listing.atEnd && listing.items.length > 0 && (
              <p ref={end} tabIndex={-1} className={styles.end}>
                {t('admin.audit.end')}
              </p>
            )}
            <VisuallyHidden>
              <span role="status">{said?.question === question ? said.text : ''}</span>
            </VisuallyHidden>
          </div>
          {selected && <RowDetails row={selected} />}
        </>
      )}
    </div>
  )
}
