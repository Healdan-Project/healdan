// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { formatDateTime, Table, type TableSort } from '@healdan/ui'
import { matterHref, routes } from '../addresses'
import { matterColumns, matterRowOf } from '../matters/columns'
import { MatterFilters } from '../matters/MatterFilters'
import { isMatterSort, useMatterList, type MatterListQuery, type MatterSort, type SortOrder } from '../matters/useMatterList'
import { TYPING_DELAY_MS, typed } from '../pickers/typing'
import { useClient } from '../queries'
import { plural, t } from '../strings'
import styles from '../matters/Matters.module.css'

type A = adminComponents['schemas']

/** What the Matters list's address holds: its words, the ids it filters by, and its order. Never a name (spec 6.3). */
export interface MattersSearch {
  q?: string
  client?: number
  store?: number
  owner?: number
  sort?: MatterSort
  order?: SortOrder
}

/**
 * The address's parameters the list reads; anything else, or anything that isn't what it should be, is left out. The
 * router validates what it validated before too, as it builds the next address from it, so an id may come as a number.
 */
export function mattersSearch(search: Record<string, unknown>): MattersSearch {
  const id = (name: string) => {
    const value = search[name]
    return idFrom(typeof value === 'number' ? String(value) : typeof value === 'string' ? value : undefined)
  }
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return {
    q: q === '' ? undefined : q,
    client: id('client'),
    store: id('store'),
    owner: id('owner'),
    sort: isMatterSort(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
  }
}

/**
 * The Matters list, at /admin/matters (spec 8.3), the one place that lists every matter: each with its number, name,
 * client, Store, owner, template and figures, sorted by any column, filtered by the words typed, a client, a Store and an
 * owner, 100 rows at a time. The address keeps the order and the filters, by id, so Back from a matter's page comes back
 * to the same list. Selecting a row opens the matter's page.
 */
export function MattersView({ search }: { search: MattersSearch }) {
  const navigate = useNavigate()
  const headingId = useId()
  const query: MatterListQuery = { q: search.q, clientId: search.client, storeId: search.store, ownerId: search.owner, sort: search.sort, order: search.order }
  const listing = useMatterList(query)
  const filtered = search.q !== undefined || search.client !== undefined || search.store !== undefined || search.owner !== undefined

  /** The address with `change` made, kept in place of the one before: an order or a filter isn't a step of its own */
  function change(next: Partial<MattersSearch>) {
    void navigate({ to: routes.matters, search: (prev) => ({ ...prev, ...next }), replace: true })
  }

  const words = useWords(search.q ?? '', (q) => change({ q: q === '' ? undefined : q }))

  // The client and the owner the address filters by, by name: as picked here, else as a row names them, else (a client
  // only) as the server does. Nothing names an owner the list has no row of, as the server has no person by id here.
  const [picked, setPicked] = useState<{ client?: A['ClientBrief']; owner?: A['PersonBrief'] }>({})
  const rowClient = search.client === undefined ? undefined : listing.matters.find((matter) => matter.client.id === search.client)?.client
  const knownClient = picked.client?.id === search.client ? picked.client : rowClient
  // Asked only once the rows for these filters have come, and none of them names it
  const asked = useClient(search.client, knownClient === undefined && !listing.isPending && !listing.isStale)
  const client = search.client === undefined ? null : (knownClient ?? (asked.data?.id === search.client ? asked.data : undefined) ?? null)
  const owner =
    search.owner === undefined
      ? null
      : ((picked.owner?.id === search.owner ? picked.owner : listing.matters.find((matter) => matter.owner?.id === search.owner)?.owner) ?? null)

  // Built when the list first draws, so the firm's own word for a matter heads its column, and kept, so typing in the
  // filters doesn't draw every row again
  const columns = useMemo(() => matterColumns(), [])
  const rows = useMemo(() => listing.matters.map(matterRowOf), [listing.matters])
  const shown: TableSort = { column: search.sort ?? 'number', direction: search.order === 'desc' ? 'descending' : 'ascending' }

  function sortBy(next: TableSort) {
    if (!isMatterSort(next.column)) return
    change({ sort: next.column, order: next.direction === 'descending' ? 'desc' : 'asc' })
  }

  const empty = listing.isPending ? t('common.loading') : <p className={styles.empty}>{t(filtered ? 'admin.matters.noneMatch' : 'admin.matters.none')}</p>

  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 id={headingId} className={styles.title}>
          {t('admin.matters.title')}
        </h1>
        {!listing.isStale && listing.total !== undefined && <span className={styles.count}>{plural('matters', listing.total)}</span>}
      </div>
      {listing.figuresAt !== undefined && (
        <p className={styles.figures}>{listing.figuresAt === null ? t('admin.matters.figuresPending') : t('admin.matters.figuresAt', { when: formatDateTime(listing.figuresAt) })}</p>
      )}
      <MatterFilters
        text={words.text}
        onTextChange={words.type}
        client={client}
        onClientChange={(next) => {
          setPicked((now) => ({ ...now, client: next ?? undefined }))
          change({ client: next?.id })
        }}
        storeId={search.store}
        onStoreChange={(store) => change({ store })}
        owner={owner}
        onOwnerChange={(next) => {
          setPicked((now) => ({ ...now, owner: next ?? undefined }))
          change({ owner: next?.id })
        }}
        onClear={
          filtered
            ? () => {
                words.clear()
                change({ q: undefined, client: undefined, store: undefined, owner: undefined })
              }
            : undefined
        }
      />
      {listing.isError ? (
        <ErrorPanel error={listing.error} onRetry={listing.refetch} />
      ) : (
        <Table
          aria-labelledby={headingId}
          columns={columns}
          rows={rows}
          selectionMode="none"
          sort={shown}
          onSort={sortBy}
          onOpen={(key) => {
            const id = idFrom(key)
            if (id !== undefined) void navigate({ href: matterHref(id) })
          }}
          loadMore={listing.loadMore}
          isLoadingMore={listing.isLoadingMore}
          total={listing.total}
          empty={empty}
          busy={listing.isStale}
        />
      )}
    </div>
  )
}

/**
 * The words field: what is typed shows as typed, and goes into the address (`send`, trimmed) once the typing pauses. When
 * the address's words change from outside, as the pane's Matters takes them off, the field follows them; its own, which
 * arrive a moment after they're sent, leave what's typed since as it is.
 */
function useWords(q: string, send: (q: string) => void) {
  const [text, setText] = useState(q)
  // The words this field last put in the address, and the address's words when last drawn
  const [sent, setSent] = useState(q)
  const [seen, setSeen] = useState(q)
  if (q !== seen) {
    setSeen(q)
    if (q !== sent) {
      setText(q)
      setSent(q)
    }
  }
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(timer.current), [])

  function type(next: string) {
    setText(next)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      const words = typed(next)
      setSent(words)
      send(words)
    }, TYPING_DELAY_MS)
  }

  function clear() {
    clearTimeout(timer.current)
    setText('')
    setSent('')
  }

  return { text, type, clear }
}
