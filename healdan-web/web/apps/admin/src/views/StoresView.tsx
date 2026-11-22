// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom, useApi } from '@healdan/shell'
import { Button, formatCount, formatDate, formatDateTime, formatSize, Icon, StatusDot, Table, toast, VisuallyHidden, type TableColumn, type TableRow } from '@healdan/ui'
import { storeHref } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import { focusLost } from '../focus'
import { useStores } from '../queries'
import { useRowsHeight } from '../lists/useRowsHeight'
import { NewStoreDialog } from '../stores/NewStoreDialog'
import { storesChanged, useDeletedStores } from '../stores/queries'
import { backendLabel, filesState, mattersInSentence, readersOf } from '../stores/text'
import { plural, t } from '../strings'
import clientStyles from '../clients/Clients.module.css'
import page from '../matters/MatterPage.module.css'
import styles from '../storage/Storage.module.css'

type A = adminComponents['schemas']

interface StoreRow extends TableRow {
  store: A['StoreInfo']
}

/** The rows the list shows before it scrolls */
const ROWS_SHOWN = 10

/**
 * The list's columns (spec 8.6), built when it first draws, so the firm's own word for a matter heads its column. On a
 * server that doesn't use storage backends, every Store's backend and files are null, and their columns are left out.
 */
function storeColumns(storage: boolean): TableColumn<StoreRow>[] {
  const columns: (TableColumn<StoreRow> | false)[] = [
    {
      id: 'name',
      name: t('admin.stores.column.name'),
      isRowHeader: true,
      width: '2fr',
      minWidth: 140,
      cell: ({ store }) => (
        <span className={styles.name}>
          <Icon name="archive" />
          <span className={clientStyles.muted}>{store.name}</span>
        </span>
      ),
    },
    storage && { id: 'backend', name: t('admin.stores.column.backend'), width: '2fr', minWidth: 140, cell: ({ store }) => (store.backend === null ? '' : backendLabel(store.backend)) },
    { id: 'matters', name: t('admin.stores.column.matters'), align: 'end', width: 96, cell: ({ store }) => formatCount(store.matters) },
    { id: 'documents', name: t('admin.stores.column.documents'), align: 'end', width: 104, cell: ({ store }) => (store.documents === null ? '' : formatCount(store.documents)) },
    { id: 'size', name: t('admin.stores.column.size'), align: 'end', width: 90, cell: ({ store }) => (store.bytes === null ? '' : formatSize(store.bytes)) },
    { id: 'readers', name: t('admin.stores.column.readers'), width: '2fr', minWidth: 140, cell: ({ store }) => <span className={clientStyles.muted}>{readersOf(store)}</span> },
    storage && {
      id: 'files',
      name: t('admin.stores.column.files'),
      width: '2fr',
      minWidth: 160,
      cell: ({ store }) => {
        if (store.pending === null) return ''
        const state = filesState(store.pending)
        return (
          <span className={clientStyles.muted}>
            <StatusDot status={state.status}>{state.words}</StatusDot>
          </span>
        )
      },
    },
  ]
  return columns.filter((column): column is TableColumn<StoreRow> => column !== false)
}

/**
 * The Stores list, at /admin/stores (spec 8.6): each live Store, by name, with the backend it keeps its files on, its
 * matters, documents and size, who can read it and the state of its files (up to date, waiting or stuck); New Store; and
 * Refresh, as files waiting to move fall meanwhile. A row opens its page. Under it, the Stores in the Trash, each with when
 * and by whom it was deleted and the matters it took, and Restore, which asks first. LogicalDOC's Default workspace, the
 * Templates Store and the root are never among them.
 */
export function StoresView() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const headingId = useId()
  const stores = useStores()
  const [making, setMaking] = useState(false)

  const list = useMemo(() => stores.data ?? [], [stores.data])
  // Every Store's backend is null on a server that doesn't use storage backends, so the list can't say one
  const storage = list.length === 0 || list.some((store) => store.backend !== null)
  const columns = useMemo(() => storeColumns(storage), [storage])
  const rows = useMemo(() => list.map((store): StoreRow => ({ id: String(store.id), textValue: store.name, store })), [list])
  const { boxRef, height } = useRowsHeight(rows.length, ROWS_SHOWN)
  // One summary gives every Store's figures
  const figuresAt = list.find((store) => store.figuresAt !== null)?.figuresAt ?? null

  return (
    <div className={styles.page}>
      <div className={clientStyles.head}>
        <h1 id={headingId} className={clientStyles.title}>
          {t('admin.stores.title')}
        </h1>
        {stores.data !== undefined && <span className={clientStyles.count}>{plural('admin.stores.count', list.length)}</span>}
        <div className={clientStyles.actions}>
          <Button onPress={() => storesChanged(queryClient)}>
            <Icon name="refresh" size={14} /> {t('admin.stores.refresh')}
          </Button>
          <Button variant="default" onPress={() => setMaking(true)}>
            <Icon name="plus" size={14} /> {t('admin.stores.new')}
          </Button>
        </div>
      </div>
      {stores.isError ? (
        <ErrorPanel error={stores.error} onRetry={() => void stores.refetch()} />
      ) : (
        <>
          {list.length > 0 && <p className={clientStyles.figures}>{figuresAt === null ? t('admin.stores.figuresPending') : t('admin.stores.figuresAt', { when: formatDateTime(figuresAt) })}</p>}
          {!storage && <p className={styles.about}>{t('admin.stores.storageOff')}</p>}
          <div ref={boxRef} className={styles.list} style={{ height }}>
            <Table
              aria-labelledby={headingId}
              columns={columns}
              rows={rows}
              selectionMode="none"
              onOpen={(key) => {
                const id = idFrom(key)
                if (id !== undefined) void navigate({ href: storeHref(id) })
              }}
              empty={<p className={styles.muted}>{stores.isPending ? t('common.loading') : t('admin.stores.none')}</p>}
            />
          </div>
        </>
      )}
      <DeletedStores />
      {making && <NewStoreDialog onSaved={(made) => void navigate({ href: storeHref(made.id) })} onClose={() => setMaking(false)} />}
    </div>
  )
}

/** Where a deleted Store is: in the Trash, or removed from it, and when it's destroyed for good */
function stateOf(store: A['DeletedStore']): string {
  if (store.state === 'deleted') return t('admin.deletedStores.deleted')
  return store.destroyAfter === null ? t('admin.deletedStores.removedUndated') : t('admin.deletedStores.removed', { when: formatDate(store.destroyAfter) })
}

/**
 * The Stores in the Trash (spec 8.6), deleted or removed from it and not destroyed yet, the most recently deleted first,
 * each with Restore. Restore asks first, naming what comes back; a refusal, as when another administrator restored it
 * first, is said in the question in the rule's words, and both lists are asked for again. Once the list is drawn without
 * the Store restored, the focus its Restore had goes to the next one's Restore, or, with none after it, to the heading.
 */
function DeletedStores() {
  const api = useApi()
  const queryClient = useQueryClient()
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const deleted = useDeletedStores()
  const [restoring, setRestoring] = useState<A['DeletedStore']>()
  const restoreButtons = useRef(new Map<number, HTMLButtonElement>())
  // The Store just restored and where its row was, until the list is drawn without it
  const refocus = useRef<{ id: number; index: number } | undefined>(undefined)

  // Once the question has gone and the list is drawn without the Store restored, if its Restore took the focus with it
  useEffect(() => {
    const restored = refocus.current
    const list = deleted.data
    if (restored === undefined || restoring !== undefined || list === undefined || list.some((one) => one.id === restored.id)) return
    refocus.current = undefined
    if (!focusLost()) return
    const next = list[restored.index]
    ;(next === undefined ? heading.current : restoreButtons.current.get(next.id))?.focus()
  })

  async function restore(store: A['DeletedStore']) {
    try {
      // By its folder's id, not its Trash item's
      await api.send<A['StoreInfo']>('POST', apiPath`/v1/admin/stores/${store.id}/restore`)
      toast(t('admin.restoreStore.done', { name: store.name }))
      refocus.current = { id: store.id, index: Math.max(deleted.data?.findIndex((one) => one.id === store.id) ?? 0, 0) }
    } finally {
      // Refused or not, the lists may have changed: another administrator restored it, or the destroy took it
      storesChanged(queryClient, undefined, { matters: true })
    }
  }

  return (
    <section aria-labelledby={headingId} className={styles.section}>
      <h2 id={headingId} ref={heading} tabIndex={-1}>
        {t('admin.deletedStores.title')}
      </h2>
      {deleted.isError ? (
        <ErrorPanel error={deleted.error} onRetry={() => void deleted.refetch()} />
      ) : deleted.data === undefined ? (
        <p className={styles.muted}>{t('common.loading')}</p>
      ) : deleted.data.length === 0 ? (
        <p className={styles.muted}>{t('admin.deletedStores.none')}</p>
      ) : (
        <>
          <p className={styles.muted}>{t('admin.deletedStores.about')}</p>
          <div className={styles.tableBox}>
            <table className={styles.table} aria-labelledby={headingId}>
              <thead>
                <tr>
                  <th scope="col">{t('admin.deletedStores.column.name')}</th>
                  <th scope="col">{t('admin.deletedStores.column.deletedAt')}</th>
                  <th scope="col">{t('admin.deletedStores.column.deletedBy')}</th>
                  <th scope="col" className={styles.number}>
                    {t('admin.deletedStores.column.matters')}
                  </th>
                  <th scope="col">{t('admin.deletedStores.column.state')}</th>
                  <th scope="col">
                    <VisuallyHidden>{t('admin.deletedStores.column.restore')}</VisuallyHidden>
                  </th>
                </tr>
              </thead>
              <tbody>
                {deleted.data.map((store) => (
                  <tr key={store.id}>
                    <td className={styles.wrap}>{store.name}</td>
                    <td>{formatDateTime(store.deletedAt)}</td>
                    <td className={store.deletedBy === null ? styles.muted : undefined}>{store.deletedBy?.name ?? t('admin.deletedStores.byUnknown')}</td>
                    <td className={styles.number}>{formatCount(store.matters)}</td>
                    <td>{stateOf(store)}</td>
                    <td>
                      <Button
                        aria-label={t('admin.deletedStores.restoreName', { name: store.name })}
                        ref={(button) => {
                          if (button) restoreButtons.current.set(store.id, button)
                          else restoreButtons.current.delete(store.id)
                        }}
                        onPress={() => setRestoring(store)}
                      >
                        {t('admin.deletedStores.restore')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {restoring !== undefined && (
        <Confirm
          title={t('admin.restoreStore.title', { name: restoring.name })}
          confirm={t('admin.restoreStore.confirm')}
          run={() => restore(restoring)}
          onClose={() => setRestoring(undefined)}
        >
          <p className={page.line}>
            {restoring.matters > 0
              ? t('admin.restoreStore.with', { name: restoring.name, count: mattersInSentence(restoring.matters) })
              : t('admin.restoreStore.withAll', { name: restoring.name })}
          </p>
          <p className={page.line}>{t('admin.restoreStore.alone')}</p>
        </Confirm>
      )}
    </section>
  )
}
