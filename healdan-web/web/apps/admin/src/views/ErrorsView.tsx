// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useCallback, useEffect, useId, useMemo, useRef } from 'react'
import type { adminComponents, adminPaths } from '@healdan/api'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, CheckField, SelectField, Table, TextField, ToggleButtons } from '@healdan/ui'
import { routes, searchId } from '../addresses'
import { focusLost, useFocusWhenGone } from '../focus'
import { useWords } from '../lists/useWords'
import { errorColumns, errorRowOf } from '../monitoring/errorColumns'
import { ErrorDetails } from '../monitoring/ErrorDetails'
import { isGone, useErrorDetail, useErrorList, type ErrorListQuery } from '../monitoring/errorQueries'
import { areaWords } from '../monitoring/errorText'
import { StaleNote } from '../people/StaleNote'
import { typed } from '../pickers/typing'
import { plural, t } from '../strings'
import listStyles from '../matters/Matters.module.css'
import styles from '../monitoring/Monitoring.module.css'

type A = adminComponents['schemas']

/** How far back the Errors list goes, as `GET /v1/admin/errors` takes it: the last hour, 24 hours, 7 days or 30 days */
export type ErrorWindow = NonNullable<NonNullable<adminPaths['/v1/admin/errors']['get']['parameters']['query']>['window']>

export const errorWindows = ['1h', '24h', '7d', '30d'] as const satisfies readonly ErrorWindow[]

/** The areas a fault can be of, in the server's order */
export const errorAreas = ['Previews', 'Storage', 'Search Index', 'Email', 'Sign-In', 'Background Jobs', 'Server'] as const satisfies readonly A['ErrorArea'][]

/** What the Errors list's address holds: how far back it goes, its words, its area, Unresolved, and the fault that's open */
export interface ErrorsSearch {
  window?: ErrorWindow
  q?: string
  area?: A['ErrorArea']
  /** Only the faults nobody has marked resolved; left out for every fault */
  unresolved?: true
  /** The fault whose details are open, by its id */
  id?: number
}

/**
 * The address's parameters the list reads; anything else, or anything that isn't what it should be, is left out. The
 * router validates what it validated before too, as it builds the next address from it, so `unresolved` and the id may
 * come as a boolean and a number.
 */
export function errorsSearch(search: Record<string, unknown>): ErrorsSearch {
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return {
    window: errorWindows.find((one) => one === search.window),
    q: q === '' ? undefined : q,
    area: errorAreas.find((one) => one === search.area),
    unresolved: search.unresolved === true || search.unresolved === 'true' ? true : undefined,
    id: searchId(search.id),
  }
}

/** How far back the list goes when the address doesn't say: the last 24 hours, as the server's own default */
const DEFAULT_WINDOW: ErrorWindow = '24h'

/**
 * Errors, at /admin/errors (spec 8.8; the owner's A, grouped and kept): one row for each kind of fault the server logged,
 * the one last seen first, with What Happened, Area, Times, First Seen and Last Seen, for the last hour, 24 hours, 7 days
 * or 30 days, filtered by the words typed, an area and Unresolved, 100 rows at a time. The address keeps the window, the
 * filters and the open fault, so a reload or a link opens the same. Selecting a row opens its details in a region below
 * the list (ErrorDetails), as the rows keep their height; Enter there takes the focus into it. While a new read fails, the
 * rows and the details it had stay, with a note that it couldn't refresh (P4-R15). The route hands in the address's
 * parameters as `search`.
 */
export function ErrorsView({ search }: { search: ErrorsSearch }) {
  const navigate = useNavigate({ from: routes.errors })
  const headingId = useId()
  const span = search.window ?? DEFAULT_WINDOW
  const query: ErrorListQuery = { window: span, q: search.q, area: search.area, unresolved: search.unresolved }
  const listing = useErrorList(query)
  const detail = useErrorDetail(search.id)
  const filtered = search.q !== undefined || search.area !== undefined || search.unresolved !== undefined
  const region = useRef<HTMLElement>(null)
  const list = useRef<HTMLDivElement>(null)
  // A row opened by Enter or a double-click: the details take the focus once they're drawn
  const focusDetails = useRef(false)
  // The fault Close Details closed, whose row takes the focus the button had
  const closed = useRef<number | undefined>(undefined)

  /** The address with `change` made, kept in place of the one before: a window, a filter or a fault isn't a step of its own */
  function change(next: Partial<ErrorsSearch>) {
    void navigate({ to: routes.errors, search: (prev) => ({ ...prev, ...next }), replace: true })
  }

  const words = useWords(search.q ?? '', (q) => change({ q: q === '' ? undefined : q }))

  // Clear Filters goes once nothing is filtered, so the focus it had goes to the words
  const wordsInput = useRef<HTMLInputElement | null>(null)
  const wordsBox = useCallback((element: HTMLDivElement | null) => {
    wordsInput.current = element?.querySelector('input') ?? null
  }, [])
  useFocusWhenGone(!filtered, wordsInput)
  // A fault found gone takes the details' buttons with it, so the focus they had goes to the details, which say so
  useFocusWhenGone(search.id !== undefined && isGone(detail.error), region)

  useEffect(() => {
    if (search.id === undefined) {
      // Close Details went with the details: its row takes the focus, or the list when it isn't drawn
      const id = closed.current
      closed.current = undefined
      if (id === undefined || !focusLost()) return
      const row = list.current?.querySelector<HTMLElement>(`[data-row-key="${id}"]`)
      ;(row ?? list.current?.querySelector<HTMLElement>('[role="grid"]'))?.focus()
      return
    }
    if (!focusDetails.current) return
    focusDetails.current = false
    region.current?.focus()
  }, [search.id])

  // Built when the list first draws, and kept, so typing in the filters doesn't draw every row again
  const columns = useMemo(() => errorColumns(), [])
  const rows = useMemo(() => listing.items.map(errorRowOf), [listing.items])
  const open = useMemo(() => new Set(search.id === undefined ? [] : [String(search.id)]), [search.id])
  // A page came, so its rows stay while a new read fails; and so do the details, unless the fault has gone, which they say
  const kept = listing.total !== undefined
  const stale = (listing.isError && kept) || (detail.isError && detail.data !== undefined && !isGone(detail.error))

  function openFault(key: string, focus: boolean) {
    const id = idFrom(key)
    if (id === undefined) return
    focusDetails.current = focus
    if (id === search.id && focus) {
      focusDetails.current = false
      region.current?.focus()
    } else change({ id })
  }

  const empty = listing.isPending ? (
    t('common.loading')
  ) : (
    <p className={listStyles.empty}>{filtered ? t('admin.errors.noneMatch') : t(`admin.errors.none.${span}`)}</p>
  )
  const total = listing.total
  const errors = listing.errors

  return (
    <div className={listStyles.view}>
      <div className={listStyles.head}>
        <h1 id={headingId} className={listStyles.title}>
          {t('admin.errors.title')}
        </h1>
        {!listing.isStale && total !== undefined && total > 0 && errors !== undefined && (
          <span className={listStyles.count}>{t('admin.errors.count', { errors: plural('admin.errors.errors', errors), kinds: plural('admin.errors.kinds', total) })}</span>
        )}
        <div className={listStyles.actions}>
          <ToggleButtons
            label={t('admin.errors.window')}
            choices={errorWindows.map((one) => ({ value: one, label: t(`admin.errors.window.${one}`) }))}
            value={span}
            onChange={(value) => {
              const next = errorWindows.find((one) => one === value)
              if (next !== undefined) change({ window: next === DEFAULT_WINDOW ? undefined : next })
            }}
          />
        </div>
      </div>
      <StaleNote
        failed={stale}
        onRetry={() => {
          listing.refetch()
          if (search.id !== undefined) void detail.refetch()
        }}
      />
      <div role="group" aria-label={t('admin.errors.filters')} className={listStyles.filters}>
        <div ref={wordsBox} className={`${listStyles.filter} ${listStyles.words}`}>
          <TextField label={t('admin.errors.text')} value={words.text} onChange={words.type} />
        </div>
        <div className={listStyles.filter}>
          <SelectField
            label={t('admin.errors.area')}
            value={search.area ?? ''}
            onChange={(value) => change({ area: errorAreas.find((one) => one === value) })}
            options={errorAreas.map((area) => ({ value: area, label: areaWords(area) }))}
            empty={t('admin.errors.allAreas')}
          />
        </div>
        <CheckField label={t('admin.errors.unresolved')} checked={search.unresolved === true} onChange={(checked) => change({ unresolved: checked ? true : undefined })} />
        {filtered && (
          <Button
            onPress={() => {
              words.clear()
              change({ q: undefined, area: undefined, unresolved: undefined })
            }}
          >
            {t('admin.errors.clear')}
          </Button>
        )}
      </div>
      <div ref={list} className={styles.list}>
        {listing.isError && !kept ? (
          <ErrorPanel error={listing.error} onRetry={listing.refetch} />
        ) : (
          <Table
            aria-labelledby={headingId}
            columns={columns}
            rows={rows}
            selectionMode="single"
            selection={open}
            onSelectionChange={(keys) => {
              const [key] = [...keys]
              if (key === undefined) change({ id: undefined })
              else openFault(key, false)
            }}
            onOpen={(key) => openFault(key, true)}
            loadMore={listing.loadMore}
            isLoadingMore={listing.isLoadingMore}
            total={listing.total}
            empty={empty}
            busy={listing.isStale}
          />
        )}
      </div>
      {search.id !== undefined && (
        <ErrorDetails
          query={detail}
          onClose={() => {
            closed.current = search.id
            change({ id: undefined })
          }}
          regionRef={region}
        />
      )}
    </div>
  )
}
