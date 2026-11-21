// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useMemo, useState, type ReactNode } from 'react'
import { ApiError, apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom, useApi } from '@healdan/shell'
import { Button, formatDateTime, formatSize, Icon, StatusDot, Table, Tabs, toast, type TableSort } from '@healdan/ui'
import { matterHref, routes } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import type { SortOrder } from '../lists/usePagedList'
import { matterColumns, matterRowOf } from '../matters/columns'
import { NewMatterDialog } from '../matters/NewMatterDialog'
import { isMatterSort, useMatterList, type MatterSort } from '../matters/useMatterList'
import { SwitchBackendDialog } from '../storage/SwitchBackendDialog'
import { storeKeys, storesChanged, useStore } from '../stores/queries'
import { RenameStoreDialog } from '../stores/RenameStoreDialog'
import { StoreAccessPanel } from '../stores/StoreAccessPanel'
import { StoreStoragePanel } from '../stores/StoreStoragePanel'
import { backendLabel, filesState, mattersInSentence, readersOf } from '../stores/text'
import { plural, t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'
import clientStyles from '../clients/Clients.module.css'
import page from '../matters/MatterPage.module.css'
import styles from '../storage/Storage.module.css'
import pageStyles from './views.module.css'

type A = adminComponents['schemas']

/** A Store's page's tabs: its matters first, who can see it, and its storage where storage backends are in use */
export type StoreTab = 'matters' | 'access' | 'storage'

/** What a Store's matters sort by: any column of the Matters list but Store, as every one here is in this Store */
export type StoreMatterSort = Exclude<MatterSort, 'store'>

/** What a Store's page's address holds: the tab open, when it isn't Matters, and the order of its matters */
export interface StoreSearch {
  tab?: Exclude<StoreTab, 'matters'>
  sort?: StoreMatterSort
  order?: SortOrder
}

function isStoreMatterSort(value: unknown): value is StoreMatterSort {
  return value !== 'store' && isMatterSort(value)
}

/** The address's parameters the page reads; anything else, or anything that isn't what it should be, is left out */
export function storeSearch(search: Record<string, unknown>): StoreSearch {
  return {
    tab: search.tab === 'access' || search.tab === 'storage' ? search.tab : undefined,
    sort: isStoreMatterSort(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
  }
}

/**
 * A Store's page, at /admin/stores/{id} (spec 8.6): its name, its facts (its backend and the state of its files, its
 * matters, documents and size, who can read it), Rename, Change Backend, Delete and New Matter set to it, and its tabs:
 * its matters, as the Matters list shows them without the Store column; Who Can See It; and its storage, with its stuck
 * files and Retry. An address that names no live Store, LogicalDOC's Default workspace, the Templates Store or the root
 * among them, says so in the app's words.
 */
export function StoreView({ id, search }: { id: string; search: StoreSearch }) {
  const storeId = idFrom(id)
  return storeId === undefined ? <StoreNotFound /> : <StorePage id={storeId} search={search} />
}

function StorePage({ id, search }: { id: number; search: StoreSearch }) {
  const asked = useStore(id)
  if (asked.isError) {
    if (asked.error instanceof ApiError && asked.error.status === 404) return <StoreNotFound />
    return (
      <div className={page.page}>
        <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
      </div>
    )
  }
  if (asked.data === undefined) {
    return (
      <div className={page.page}>
        <p className={page.muted}>{t('common.loading')}</p>
      </div>
    )
  }
  // By its id, so another Store's page, by Back or Forward too, starts afresh: no dialog, tab or change carried over
  return <Store key={asked.data.id} store={asked.data} search={search} />
}

const counts = new Intl.NumberFormat('en-US')

function Fact({ term, children }: { term: AdminStringKey; children: ReactNode }) {
  return (
    <div className={clientStyles.fact}>
      <dt>{t(term)}</dt>
      <dd>{children}</dd>
    </div>
  )
}

type Dialog = 'rename' | 'backend' | 'delete' | 'matter'

function Store({ store, search }: { store: A['StoreInfo']; search: StoreSearch }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate({ from: routes.store })
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const close = () => setDialog(null)
  // Its storage is its backend's: none to show on a server that doesn't use storage backends
  const storage = store.backend !== null
  const tab: StoreTab = search.tab === 'storage' && !storage ? 'matters' : (search.tab ?? 'matters')
  // Once opened, Who Can See It stays mounted behind the other tabs, with what's being changed in it and a save on its way
  const [accessOpened, setAccessOpened] = useState(tab === 'access')
  if (tab === 'access' && !accessOpened) setAccessOpened(true)
  // The figures come from the summary, which hasn't been worked out since the server started while they're null
  const pending = store.documents === null

  function choose(next: string) {
    // A tab isn't a step of its own, as an order isn't
    void navigate({ to: routes.store, params: { id: String(store.id) }, search: (prev) => ({ ...prev, tab: next === 'access' || next === 'storage' ? next : undefined }), replace: true })
  }

  async function remove() {
    await api.send<A['DeletedFolder']>('DELETE', apiPath`/v1/admin/stores/${store.id}`)
    toast(t('admin.deleteStore.done', { name: store.name }))
    // Off its page first, so nothing asks for it again
    await navigate({ to: routes.stores })
    queryClient.removeQueries({ queryKey: storeKeys.store(store.id) })
    storesChanged(queryClient, undefined, { matters: true })
  }

  const tabs = [
    { id: 'matters', label: t('admin.store.tab.matters'), content: <StoreMatters store={store} search={search} /> },
    { id: 'access', label: t('admin.store.tab.access'), keepMounted: accessOpened, content: <StoreAccessPanel store={store} /> },
    ...(storage ? [{ id: 'storage', label: t('admin.store.tab.storage'), content: <StoreStoragePanel store={store} /> }] : []),
  ]

  return (
    <div className={page.page}>
      <nav aria-label={t('admin.crumbs.label')} className={clientStyles.crumbs}>
        <ol>
          <li>
            <Link to={routes.stores} className={clientStyles.crumbLink}>
              {t('admin.stores.title')}
            </Link>
          </li>
          <li aria-current="page">{store.name}</li>
        </ol>
      </nav>
      <div className={clientStyles.head}>
        <h1 className={clientStyles.title}>{store.name}</h1>
        <div className={clientStyles.actions}>
          <Button onPress={() => setDialog('rename')}>
            <span className={page.withIcon}>
              <Icon name="pencil" size={14} />
              {t('admin.store.rename')}
            </span>
          </Button>
          {storage && (
            <Button onPress={() => setDialog('backend')}>
              <span className={page.withIcon}>
                <Icon name="database" size={14} />
                {t('admin.store.changeBackend')}
              </span>
            </Button>
          )}
          <Button onPress={() => setDialog('delete')}>
            <span className={page.withIcon}>
              <Icon name="trash" size={14} />
              {t('admin.store.delete')}
            </span>
          </Button>
          {/* Set to this Store, as a client's page sets its client (spec 8.2) */}
          <Button variant="default" onPress={() => setDialog('matter')}>
            <span className={page.withIcon}>
              <Icon name="plus" size={14} />
              {t('admin.matters.new')}
            </span>
          </Button>
        </div>
      </div>
      <dl className={clientStyles.facts}>
        {store.backend !== null && <Fact term="admin.store.backend">{backendLabel(store.backend)}</Fact>}
        {store.pending !== null && (
          <Fact term="admin.store.files">
            <StatusDot status={filesState(store.pending).status}>{filesState(store.pending).words}</StatusDot>
          </Fact>
        )}
        <Fact term="admin.store.matters">{counts.format(store.matters)}</Fact>
        <Fact term="admin.store.documents">{pending ? t('admin.store.pending') : counts.format(store.documents ?? 0)}</Fact>
        <Fact term="admin.store.size">{pending || store.bytes === null ? t('admin.store.pending') : formatSize(store.bytes)}</Fact>
        <Fact term="admin.store.readers">{readersOf(store)}</Fact>
      </dl>
      {store.figuresAt !== null && <p className={clientStyles.figures}>{t('admin.stores.figuresAt', { when: formatDateTime(store.figuresAt) })}</p>}
      <Tabs label={t('admin.store.tabs')} selected={tab} onSelect={choose} tabs={tabs} />
      {dialog === 'rename' && <RenameStoreDialog store={store} onClose={close} />}
      {dialog === 'backend' && store.backend !== null && <SwitchBackendDialog store={store} current={store.backend} onClose={close} />}
      {dialog === 'delete' && (
        <Confirm title={t('admin.deleteStore.title', { name: store.name })} confirm={t('admin.deleteStore.confirm')} run={remove} onClose={close}>
          <p className={page.line}>
            {store.matters > 0 ? t('admin.deleteStore.holds', { name: store.name, count: mattersInSentence(store.matters) }) : t('admin.deleteStore.empty', { name: store.name })}
          </p>
          <p className={page.line}>{t('admin.deleteStore.restore')}</p>
        </Confirm>
      )}
      {dialog === 'matter' && <NewMatterDialog storeId={store.id} onClose={close} />}
    </div>
  )
}

/** --row-height in tokens.css, which the list's height is counted in: the virtualiser needs one of its own */
const ROW = 34
/** The rows the list shows before it scrolls, and asks for more as its end comes into view */
const ROWS_SHOWN = 12

/** The Store's matters, through the Matters list's own route, sorted by any column but Store, the order in the address */
function StoreMatters({ store, search }: { store: A['StoreInfo']; search: StoreSearch }) {
  const navigate = useNavigate({ from: routes.store })
  const listing = useMatterList({ storeId: store.id, sort: search.sort, order: search.order })
  // Built when the list first draws, so the firm's own word for a matter heads its column, and kept
  const columns = useMemo(() => matterColumns(['store']), [])
  const rows = useMemo(() => listing.matters.map(matterRowOf), [listing.matters])
  const shown: TableSort = { column: search.sort ?? 'number', direction: search.order === 'desc' ? 'descending' : 'ascending' }
  const height = (Math.min(Math.max(rows.length, 1), ROWS_SHOWN) + 1) * ROW + 2

  function sortBy(next: TableSort) {
    if (!isStoreMatterSort(next.column)) return
    const order = next.direction === 'descending' ? 'desc' : 'asc'
    // An order isn't a step of its own
    void navigate({ to: routes.store, params: { id: String(store.id) }, search: (prev) => ({ ...prev, sort: next.column as StoreMatterSort, order }), replace: true })
  }

  if (listing.isError) return <ErrorPanel error={listing.error} onRetry={listing.refetch} />
  return (
    <div className={styles.section}>
      {!listing.isStale && listing.total !== undefined && <p className={clientStyles.count}>{plural('matters', listing.total)}</p>}
      <div className={styles.list} style={{ height }}>
        <Table
          aria-label={t('admin.store.mattersOf', { name: store.name })}
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
          empty={<p className={clientStyles.empty}>{listing.isPending ? t('common.loading') : t('admin.store.noMatters')}</p>}
          busy={listing.isStale}
        />
      </div>
    </div>
  )
}

/** No Store at the address: in the app's words, with the way to the Stores list */
function StoreNotFound() {
  return (
    <div className={pageStyles.view}>
      <h1 className={pageStyles.title}>{t('admin.store.notFound.title')}</h1>
      <p className={pageStyles.line}>{t('admin.store.notFound.message')}</p>
      <Link to={routes.stores} className={pageStyles.link}>
        {t('admin.store.notFound.stores')}
      </Link>
    </div>
  )
}
