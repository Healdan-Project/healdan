// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link, useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useState } from 'react'
import { ApiError, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, formatCount, formatDateTime, Icon, Table, type TableSort } from '@healdan/ui'
import { matterHref, routes } from '../addresses'
import { ClientDialog } from '../clients/ClientDialog'
import { Fact } from '../facts/Fact'
import type { SortOrder } from '../lists/usePagedList'
import { matterColumns, matterRowOf } from '../matters/columns'
import { NewMatterDialog } from '../matters/NewMatterDialog'
import { isMatterSort, useMatterList, type MatterSort } from '../matters/useMatterList'
import { useClient } from '../queries'
import { plural, t } from '../strings'
import styles from '../clients/Clients.module.css'
import pageStyles from './views.module.css'

type A = adminComponents['schemas']

/** What a client's matters sort by: any column of the Matters list but Client, as every one here is this client's */
export type ClientMatterSort = Exclude<MatterSort, 'client'>

/** What a client's page's address holds: the order of its matters */
export interface ClientSearch {
  sort?: ClientMatterSort
  order?: SortOrder
}

function isClientMatterSort(value: unknown): value is ClientMatterSort {
  return value !== 'client' && isMatterSort(value)
}

/** The address's parameters the page reads; anything else, or anything that isn't what it should be, is left out */
export function clientSearch(search: Record<string, unknown>): ClientSearch {
  return {
    sort: isClientMatterSort(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
  }
}

/**
 * A client's page, at /admin/clients/{id} (spec 8.2): its number and name, which Rename changes (only the name ever
 * does), its facts, and its matters, as the Matters list shows them, without the Client column. An address that names
 * no client, or a client the server doesn't have, says so in the app's words.
 */
export function ClientView({ id, search }: { id: string; search: ClientSearch }) {
  const clientId = idFrom(id)
  return clientId === undefined ? <ClientNotFound /> : <ClientPage id={clientId} search={search} />
}

function ClientPage({ id, search }: { id: number; search: ClientSearch }) {
  const asked = useClient(id)
  if (asked.isError) {
    if (asked.error instanceof ApiError && asked.error.status === 404) return <ClientNotFound />
    return (
      <div className={styles.view}>
        <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
      </div>
    )
  }
  if (asked.data === undefined) {
    return (
      <div className={styles.view}>
        <p className={styles.empty}>{t('common.loading')}</p>
      </div>
    )
  }
  // By its id, so another client's page, by Back or Forward too, starts afresh: no dialog carried over
  return <Client key={asked.data.id} client={asked.data} search={search} />
}

function Client({ client, search }: { client: A['Client']; search: ClientSearch }) {
  const navigate = useNavigate()
  const mattersId = useId()
  const [renaming, setRenaming] = useState(false)
  const [adding, setAdding] = useState(false)
  const listing = useMatterList({ clientId: client.id, sort: search.sort, order: search.order })

  // Built when the page first draws, so the firm's own word for a matter heads its column, and kept
  const columns = useMemo(() => matterColumns(['client']), [])
  const rows = useMemo(() => listing.matters.map(matterRowOf), [listing.matters])
  const shown: TableSort = { column: search.sort ?? 'number', direction: search.order === 'desc' ? 'descending' : 'ascending' }

  function sortBy(next: TableSort) {
    if (!isClientMatterSort(next.column)) return
    const order = next.direction === 'descending' ? 'desc' : 'asc'
    // An order isn't a step of its own
    void navigate({ to: routes.client, params: { id: String(client.id) }, search: { sort: next.column, order }, replace: true })
  }

  // The figures come from the summary, which hasn't been worked out since the server started while they're null
  const pending = client.documents === null
  const empty = listing.isPending ? t('common.loading') : <p className={styles.empty}>{t('admin.client.noMatters')}</p>

  return (
    <div className={styles.view}>
      <nav aria-label={t('admin.crumbs.label')} className={styles.crumbs}>
        <ol>
          <li>
            <Link to={routes.clients} className={styles.crumbLink}>
              {t('admin.clients.title')}
            </Link>
          </li>
          <li aria-current="page">{client.number}</li>
        </ol>
      </nav>
      <div className={styles.head}>
        <h1 className={styles.title}>{t('admin.client.label', { number: client.number, name: client.name })}</h1>
        <div className={styles.actions}>
          <Button onPress={() => setRenaming(true)}>
            <Icon name="pencil" size={14} /> {t('admin.client.rename')}
          </Button>
          {/* Set to this client (spec 8.2) */}
          <Button variant="default" onPress={() => setAdding(true)}>
            <Icon name="plus" size={14} /> {t('admin.matters.new')}
          </Button>
        </div>
      </div>
      <dl className={styles.facts}>
        <Fact term="admin.client.number">{client.number}</Fact>
        <Fact term="admin.client.matters">{formatCount(client.matters)}</Fact>
        <Fact term="admin.client.documents">{pending ? t('admin.client.pending') : formatCount(client.documents ?? 0)}</Fact>
        <Fact term="admin.client.lastActivity">
          {pending ? t('admin.client.pending') : client.lastActivity === null ? t('admin.client.noActivity') : formatDateTime(client.lastActivity)}
        </Fact>
      </dl>
      <div className={styles.head}>
        <h2 id={mattersId} className={styles.section}>
          {t('admin.client.mattersTitle')}
        </h2>
        {!listing.isStale && listing.total !== undefined && <span className={styles.count}>{plural('matters', listing.total)}</span>}
      </div>
      {listing.figuresAt !== undefined && (
        <p className={styles.figures}>{listing.figuresAt === null ? t('admin.matters.figuresPending') : t('admin.matters.figuresAt', { when: formatDateTime(listing.figuresAt) })}</p>
      )}
      {listing.isError ? (
        <ErrorPanel error={listing.error} onRetry={listing.refetch} />
      ) : (
        <Table
          aria-labelledby={mattersId}
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
      {renaming && <ClientDialog client={client} onClose={() => setRenaming(false)} />}
      {adding && <NewMatterDialog client={{ id: client.id, number: client.number, name: client.name }} onClose={() => setAdding(false)} />}
    </div>
  )
}

/** No client at the address: in the app's words, with the way to the Clients list, as NotFoundView has the Dashboard's */
function ClientNotFound() {
  return (
    <div className={pageStyles.view}>
      <h1 className={pageStyles.title}>{t('admin.client.notFound.title')}</h1>
      <p className={pageStyles.line}>{t('admin.client.notFound.message')}</p>
      <Link to={routes.clients} className={pageStyles.link}>
        {t('admin.client.notFound.clients')}
      </Link>
    </div>
  )
}
