// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, formatDateTime, Icon, Table, type TableSort } from '@healdan/ui'
import { matterHref, routes } from '../addresses'
import { matterColumns, matterRowOf } from '../matters/columns'
import { MatterFilters } from '../matters/MatterFilters'
import { NewMatterDialog } from '../matters/NewMatterDialog'
import { isMatterSort, useMatterList, type MatterListQuery, type MatterSort, type SortOrder } from '../matters/useMatterList'
import { useWords } from '../lists/useWords'
import { typed } from '../pickers/typing'
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
 * to the same list. Selecting a row opens the matter's page, and New Matter makes one, then opens its page.
 */
export function MattersView({ search }: { search: MattersSearch }) {
  const navigate = useNavigate({ from: routes.matters })
  const headingId = useId()
  const [adding, setAdding] = useState(false)
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
  const [chosenClient, chooseClient] = useChosen<A['ClientBrief']>(search.client)
  const [chosenOwner, chooseOwner] = useChosen<A['PersonBrief']>(search.owner)
  const rowClient = search.client === undefined ? undefined : listing.matters.find((matter) => matter.client.id === search.client)?.client
  // Asked only once the rows for these filters have come, and none of them names it
  const asked = useClient(search.client, chosenClient === undefined && rowClient === undefined && !listing.isPending && !listing.isStale)
  const client =
    chosenClient !== undefined ? chosenClient : search.client === undefined ? null : (rowClient ?? (asked.data?.id === search.client ? asked.data : undefined) ?? null)
  const owner =
    chosenOwner !== undefined
      ? chosenOwner
      : search.owner === undefined
        ? null
        : (listing.matters.find((matter) => matter.owner?.id === search.owner)?.owner ?? null)

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
        <div className={styles.actions}>
          <Button variant="default" onPress={() => setAdding(true)}>
            <Icon name="plus" size={14} /> {t('admin.matters.new')}
          </Button>
        </div>
      </div>
      {listing.figuresAt !== undefined && (
        <p className={styles.figures}>{listing.figuresAt === null ? t('admin.matters.figuresPending') : t('admin.matters.figuresAt', { when: formatDateTime(listing.figuresAt) })}</p>
      )}
      <MatterFilters
        text={words.text}
        onTextChange={words.type}
        client={client}
        onClientChange={(next) => {
          chooseClient(next)
          change({ client: next?.id })
        }}
        storeId={search.store}
        onStoreChange={(store) => change({ store })}
        owner={owner}
        onOwnerChange={(next) => {
          chooseOwner(next)
          change({ owner: next?.id })
        }}
        onClear={
          filtered
            ? () => {
                words.clear()
                chooseClient(null)
                chooseOwner(null)
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
      {adding && <NewMatterDialog onClose={() => setAdding(false)} />}
    </div>
  )
}

/**
 * What the person picked for a filter here (null for none), shown at once: the address, which names it by id, follows a
 * moment later, and a picker that waited for it would leave its list open. It's forgotten when the address names anything
 * else, as the pane's Matters does, and undefined until something is picked.
 */
function useChosen<T extends { id: number }>(id: number | undefined) {
  const [chosen, setChosen] = useState<T | null | undefined>(undefined)
  const [seen, setSeen] = useState(id)
  if (id !== seen) {
    setSeen(id)
    if (chosen !== undefined && chosen?.id !== id) setChosen(undefined)
  }
  return [chosen, setChosen] as const
}
