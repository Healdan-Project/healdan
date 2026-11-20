// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useState } from 'react'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, formatDateTime, Icon, Table, TextField, type TableSort } from '@healdan/ui'
import { routes } from '../addresses'
import { ClientDialog } from '../clients/ClientDialog'
import { clientColumns, clientRowOf } from '../clients/columns'
import { isClientSort, useClientList, type ClientSort } from '../clients/useClientList'
import type { SortOrder } from '../lists/usePagedList'
import { useWords } from '../lists/useWords'
import { typed } from '../pickers/typing'
import { plural, t } from '../strings'
import styles from '../clients/Clients.module.css'

/** What the Clients list's address holds: its words and its order */
export interface ClientsSearch {
  q?: string
  sort?: ClientSort
  order?: SortOrder
}

/** The address's parameters the list reads; anything else, or anything that isn't what it should be, is left out */
export function clientsSearch(search: Record<string, unknown>): ClientsSearch {
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return {
    q: q === '' ? undefined : q,
    sort: isClientSort(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
  }
}

/**
 * The Clients list, at /admin/clients (spec 8.2): each client with its number, name, live matters and its matters'
 * figures, sorted by any column, found by its number or name, 100 rows at a time, with New Client. The address keeps the
 * words and the order, so Back from a client's page comes back to the same list. Selecting a row opens the client's page.
 */
export function ClientsView({ search }: { search: ClientsSearch }) {
  const navigate = useNavigate({ from: routes.clients })
  const headingId = useId()
  const listing = useClientList({ q: search.q, sort: search.sort, order: search.order })
  const [adding, setAdding] = useState(false)

  /** The address with `change` made, kept in place of the one before: an order or the words aren't a step of their own */
  function change(next: Partial<ClientsSearch>) {
    void navigate({ to: routes.clients, search: (prev) => ({ ...prev, ...next }), replace: true })
  }

  const words = useWords(search.q ?? '', (q) => change({ q: q === '' ? undefined : q }))

  // Built when the list first draws, so the firm's own word for matters heads its column, and kept, so typing in the
  // words doesn't draw every row again
  const columns = useMemo(() => clientColumns(), [])
  const rows = useMemo(() => listing.clients.map(clientRowOf), [listing.clients])
  const shown: TableSort = { column: search.sort ?? 'number', direction: search.order === 'desc' ? 'descending' : 'ascending' }

  function sortBy(next: TableSort) {
    if (!isClientSort(next.column)) return
    change({ sort: next.column, order: next.direction === 'descending' ? 'desc' : 'asc' })
  }

  function open(id: number) {
    void navigate({ to: routes.client, params: { id: String(id) } })
  }

  const empty = listing.isPending ? t('common.loading') : <p className={styles.empty}>{t(search.q === undefined ? 'admin.clients.none' : 'admin.clients.noneMatch')}</p>

  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 id={headingId} className={styles.title}>
          {t('admin.clients.title')}
        </h1>
        {!listing.isStale && listing.total !== undefined && <span className={styles.count}>{plural('admin.clients.count', listing.total)}</span>}
        <div className={styles.actions}>
          <Button variant="default" onPress={() => setAdding(true)}>
            <Icon name="plus" size={14} /> {t('admin.clients.new')}
          </Button>
        </div>
      </div>
      {listing.figuresAt !== undefined && (
        <p className={styles.figures}>{listing.figuresAt === null ? t('admin.clients.figuresPending') : t('admin.clients.figuresAt', { when: formatDateTime(listing.figuresAt) })}</p>
      )}
      <div role="group" aria-label={t('admin.clients.filters')} className={styles.filters}>
        <div className={styles.words}>
          <TextField label={t('admin.clients.text')} value={words.text} onChange={words.type} />
        </div>
      </div>
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
            if (id !== undefined) open(id)
          }}
          loadMore={listing.loadMore}
          isLoadingMore={listing.isLoadingMore}
          total={listing.total}
          empty={empty}
          busy={listing.isStale}
        />
      )}
      {adding && <ClientDialog onSaved={(client) => open(client.id)} onClose={() => setAdding(false)} />}
    </div>
  )
}
