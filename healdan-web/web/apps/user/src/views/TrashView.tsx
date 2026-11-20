// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useRef, useState } from 'react'
import { ApiError, type components } from '@healdan/api'
import { Button, formatDate, Icon, MenuItem, plural, t, Table, toast, toastWithAction, ToggleButtons, type ItemMenu, type TableColumn, type TableRow, type TableSort } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import { emptyTrash, isTrashSort, removeTrashItem, restoreItems, restoreTrashItem, trashViews, type TrashSort, type TrashViewName } from '../actions/trash'
import { ErrorPanel } from '../frame/ErrorPanel'
import { messageFor } from '../frame/errors'
import { RightRegionView } from '../frame/RightRegion'
import { ConfirmRemove, type RemoveQuestion } from '../organising/ConfirmRemove'
import { MovePicker } from '../organising/MovePicker'
import { focusIfLost, rowAfterKeys } from '../organising/rows'
import { useMe, useTrash } from '../queries'
import { useApi } from '../services'
import { Toolbar } from './Toolbar'
import { deletedFromText, DestroyIn, itemCount, placeText, TrashDetails, trashIcon } from './TrashDetails'
import { useColumnLayout, type ListKind } from './columnLayouts'
import { pagedState, type ListingOrder } from './useListing'
import styles from './views.module.css'

type S = components['schemas']
type Item = S['TrashItem']
/** What the restore picker moves: nothing of its own, so it greys out no place, and the server decides where an item may go back */
const NOTHING = { documents: [], folders: [] }
/** What's running: one at a time */
type Busy = 'restore' | 'remove' | 'empty'

interface TrashRow extends TableRow {
  item: Item
}

const keyOf = (item: Item) => `t${item.id}`

function rowOf(item: Item): TrashRow {
  return { id: keyOf(item), textValue: item.name, item }
}

// The columns (folders and Trash spec 7.2, 7.3): Name, Doc #, Deleted From and Deleted; Everyone's adds Deleted By, and
// Removed has Deleted By, Removed By, Removed and Destroyed in place of Deleted. Name, Deleted and Removed sort.
const name: TableColumn<TrashRow> = {
  id: 'name',
  name: t('trash.name'),
  isRowHeader: true,
  allowsSorting: true,
  width: '3fr',
  cell: ({ item }) => (
    <>
      <Icon {...trashIcon(item)} />
      {/* A deleted folder is one row, with how many items it holds right after its name */}
      <span className={item.contents ? styles.trashName : styles.name}>{item.name}</span>
      {item.contents && <span className={styles.trashCount}>{plural('trash.items', itemCount(item.contents))}</span>}
    </>
  ),
}
const docNumber: TableColumn<TrashRow> = { id: 'docNumber', name: t('trash.docNumber'), align: 'end', width: 96, cell: ({ item }) => (item.docNumber === null ? '' : String(item.docNumber)) }
const deletedFrom: TableColumn<TrashRow> = { id: 'deletedFrom', name: t('trash.deletedFrom'), width: '2fr', cell: ({ item }) => deletedFromText(item) }
const deleted: TableColumn<TrashRow> = { id: 'deleted', name: t('trash.deleted'), allowsSorting: true, newestFirst: true, width: 132, cell: ({ item }) => formatDate(item.deletedAt) }
const deletedBy: TableColumn<TrashRow> = { id: 'deletedBy', name: t('trash.deletedBy'), width: '1fr', cell: ({ item }) => item.deletedBy?.name ?? '' }
const removedBy: TableColumn<TrashRow> = { id: 'removedBy', name: t('trash.removedBy'), width: '1fr', cell: ({ item }) => item.removedBy?.name ?? t('trash.notRecorded') }
const removed: TableColumn<TrashRow> = { id: 'removed', name: t('trash.removed'), allowsSorting: true, newestFirst: true, width: 132, cell: ({ item }) => (item.removedAt ? formatDate(item.removedAt) : '') }
const destroyed: TableColumn<TrashRow> = { id: 'destroyed', name: t('trash.destroyed'), width: 120, cell: ({ item }) => <DestroyIn destroyAfter={item.destroyAfter} /> }

/** The kept widths of each view are its own (spec 4.8): the Trash's columns aren't chosen */
const kindOf: Record<TrashViewName, ListKind> = { mine: 'trashMine', everyone: 'trashEveryone', removed: 'trashRemoved' }

const columnsOf: Record<TrashViewName, TableColumn<TrashRow>[]> = {
  mine: [name, docNumber, deletedFrom, deleted],
  everyone: [name, docNumber, deletedFrom, deletedBy, deleted],
  removed: [name, docNumber, deletedFrom, deletedBy, removedBy, removed, destroyed],
}

export interface TrashViewProps {
  /** As the address asks; anyone who isn't an administrator sees Mine whatever it says */
  view: TrashViewName
  sort?: TrashSort
  order?: ListingOrder
}

/**
 * /trash (folders and Trash spec 7.2 to 7.4): what the person deleted, newest first, one row for each delete, with Restore,
 * Remove… and Empty Trash…, and the selected item's details beside the list. An administrator switches to everyone's, or to
 * what was removed, which has Restore only. Rows select as in any list; Delete is Remove…, and Enter opens nothing, as a
 * deleted item has no page.
 */
export function TrashView({ view: asked, sort, order }: TrashViewProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const me = useMe()
  const admin = me.data?.admin === true
  // Everyone's and Removed are administrators' alone: never asked for anyone else, and not before it's known who's asking
  const view: TrashViewName = admin ? asked : 'mine'
  const enabled = asked === 'mine' || !me.isPending
  const query = useTrash(view, sort, order, enabled)
  const paged = pagedState(query, enabled)
  const pages = query.data?.pages
  const items = useMemo(() => distinct(pages?.flatMap((page) => page.items ?? []) ?? []), [pages])
  const last = pages?.at(-1)
  const total = last?.total ?? undefined
  // The days an administrator can still restore what's removed, as the administrators set them
  const removedDays = last?.removedDays
  const headingId = useId()

  // The selection, by row key: the rows still listed, so a restored or removed item isn't selected any more
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const selection = useMemo(() => new Set(items.map(keyOf).filter((key) => chosen.has(key))), [items, chosen])
  const selected = items.filter((item) => selection.has(keyOf(item)))
  const one = selected.length === 1 ? selected[0] : undefined

  // Restore, Remove and Empty Trash send one request at a time: a second press while one runs is ignored, as the server
  // doesn't serialise two restores of one item
  const [busy, setBusy] = useState<Busy>()
  const running = useRef(false)
  const [asking, setAsking] = useState<RemoveQuestion>()
  // The items whose place is gone, asked about one at a time
  const [picking, setPicking] = useState<Item[]>([])
  const canRemove = view !== 'removed'

  async function run(kind: Busy, work: () => Promise<void>) {
    if (running.current) return
    running.current = true
    setBusy(kind)
    try {
      await work()
    } finally {
      running.current = false
      setBusy(undefined)
    }
  }

  /**
   * What changed is asked again: the Trash's views and what shows documents, with the restored documents named, and for a
   * folder, whose documents aren't named here, every message that shows a copy (invalidateDocumentLists)
   */
  function refresh(restored: readonly Item[] = []) {
    const named = restored.some((item) => item.type === 'folder') ? 'any' : restored.flatMap((item) => (item.docNumber === null ? [] : [item.docNumber]))
    return invalidateDocumentLists(queryClient, named)
  }

  function toastRestored(done: S['RestoredItem']) {
    const place = done.place
    toastWithAction(t('trash.restored', { name: done.item.name, place: placeText(place) }), {
      label: t('trash.open'),
      onAction: () => void navigate({ to: '/folders/$id', params: { id: String(place.id) } }),
    })
  }

  /** A refusal, in the catalogue's words: never the server's own text */
  function refused(item: Item, error: unknown) {
    if (error instanceof ApiError && error.status === 409 && error.code === 'refused') toast(t('trash.cantRestore', { name: item.name }))
    else toast(t('organising.refused', { name: item.name, reason: messageFor(error) }))
  }

  function restore(targets: readonly Item[]) {
    if (targets.length === 0) return
    const next = rowAfter(targets)
    void run('restore', async () => {
      const outcome = await restoreItems(api, targets)
      await refresh(outcome.done.map((done) => done.item))
      for (const done of outcome.done) toastRestored(done)
      for (const { item, error } of outcome.refused) refused(item, error)
      if (outcome.placeGone.length > 0) setPicking(outcome.placeGone)
      if (outcome.done.length > 0) focusIfLost(next)
    })
  }

  /** Asks no more about the item: chosen, or cancelled. By its id, as the picker's choice closes it, which cancels too */
  function picked(item: Item) {
    setPicking((now) => now.filter((one) => one.id !== item.id))
  }

  /** The item whose place is gone, restored into the folder the person chose; the next one is asked about once it's done */
  function restoreInto(item: Item, folderId: number) {
    picked(item)
    const next = rowAfter([item])
    void run('restore', async () => {
      try {
        const done = await restoreTrashItem(api, item.id, folderId)
        await refresh([item])
        toastRestored(done)
        focusIfLost(next)
      } catch (error) {
        await refresh()
        refused(item, error)
      }
    })
  }

  function remove(question: RemoveQuestion) {
    const next = 'empty' in question ? undefined : rowAfter(question.items)
    void run('empty' in question ? 'empty' : 'remove', async () => {
      if ('empty' in question) {
        try {
          await emptyTrash(api)
        } catch (error) {
          toast(messageFor(error))
        }
      } else {
        for (const item of question.items) {
          try {
            await removeTrashItem(api, item.id)
          } catch (error) {
            toast(t('organising.refused', { name: item.name, reason: messageFor(error) }))
          }
        }
      }
      await refresh()
      focusIfLost(next)
    })
  }

  function askRemove(targets: readonly Item[]) {
    if (canRemove && targets.length > 0 && !running.current) setAsking({ items: targets })
  }

  const columns = columnsOf[view]
  const layout = useColumnLayout(kindOf[view], columns.map((column) => column.id))
  const rows = useMemo(() => items.map(rowOf), [items])
  const byKey = useMemo(() => new Map(items.map((item) => [keyOf(item), item])), [items])
  // What a key acts on: the selection when the row is in it, else the row alone
  const targetsOf = (key: string): Item[] => (selection.has(key) ? selected : [byKey.get(key)].filter((item): item is Item => item !== undefined))
  const contextMenu: ItemMenu = {
    label: (key) => t('menu.for', { name: byKey.get(key)?.name ?? '' }),
    items: (key) =>
      byKey.has(key) ? (
        <>
          <MenuItem id="restore" isDisabled={busy !== undefined} onAction={() => restore(targetsOf(key))}>
            {t('trash.restore')}
          </MenuItem>
          {canRemove && (
            <MenuItem id="remove" isDisabled={busy !== undefined} onAction={() => askRemove(targetsOf(key))}>
              {t('trash.remove')}
            </MenuItem>
          )}
        </>
      ) : undefined,
    // A right-click on a row that isn't selected selects it, in place of the selection, as in any list
    onOpen: (key) => {
      if (!selection.has(key)) setChosen(new Set([key]))
    },
  }

  // The server's own order is newest first: by when it was removed in Removed, else by when it was deleted
  const shown: TableSort = sort
    ? { column: sort, direction: order === 'asc' ? 'ascending' : order === 'desc' ? 'descending' : sort === 'name' ? 'ascending' : 'descending' }
    : { column: view === 'removed' ? 'removed' : 'deleted', direction: 'descending' }

  function sortBy(next: TableSort) {
    if (!isTrashSort(next.column)) return
    const column = next.column
    void navigate({ to: '/trash', search: (prev) => ({ ...prev, sort: column, order: next.direction === 'descending' ? 'desc' : 'asc' }), replace: true })
  }

  function showView(next: string) {
    // Each view has its own order; Mine is the address without one
    void navigate({ to: '/trash', search: { view: next === 'mine' ? undefined : (next as TrashViewName) } })
  }

  // Loading, too, while it isn't known yet which view the person may see. Each view says it's empty in its own words: only
  // Mine says where what the person deletes goes, and Removed holds what people removed, not what's in the Trash
  const empty = query.isPending ? (
    t('common.loading')
  ) : (
    <div className={styles.trashEmpty}>
      <p className={styles.trashEmptyTitle}>{view === 'removed' ? t('trash.emptyRemovedTitle') : t('trash.emptyTitle')}</p>
      {view === 'mine' && <p>{t('trash.emptyLine')}</p>}
    </div>
  )

  return (
    <section className={styles.withRegion} aria-labelledby={headingId}>
      <div className={`${styles.view} ${styles.folder}`}>
        <div className={styles.heading}>
          <h1 id={headingId} className={styles.title}>
            {t('trash.title')}
          </h1>
          {/* Administrators only (/v1/me's admin); others never see it */}
          {admin && <ToggleButtons label={t('trash.show')} choices={trashViews.map((one) => ({ value: one, label: t(`trash.view.${one}`) }))} value={view} onChange={showView} />}
        </div>
        {view === 'removed' && removedDays !== undefined && <p className={styles.lede}>{t('trash.removedLede', { days: plural('trash.days', removedDays) })}</p>}
        {paged.isError ? (
          <ErrorPanel error={paged.error} onRetry={paged.refetch} />
        ) : (
          <>
            <Toolbar
              label={t('trash.actions')}
              count={paged.isStale || total === undefined ? undefined : { items: total }}
              end={
                view === 'mine' && (
                  <Button isPending={busy === 'empty'} isDisabled={!total || (busy !== undefined && busy !== 'empty')} onPress={() => total && setAsking({ empty: total })}>
                    {t('trash.empty')}
                  </Button>
                )
              }
            >
              <Button variant="default" isPending={busy === 'restore'} isDisabled={selected.length === 0 || (busy !== undefined && busy !== 'restore')} onPress={() => restore(selected)}>
                {t('trash.restore')}
              </Button>
              {canRemove && (
                <Button isPending={busy === 'remove'} isDisabled={selected.length === 0 || (busy !== undefined && busy !== 'remove')} onPress={() => askRemove(selected)}>
                  {t('trash.remove')}
                </Button>
              )}
            </Toolbar>
            <Table
              aria-labelledby={headingId}
              columns={columns}
              rows={rows}
              sort={shown}
              onSort={sortBy}
              resizing={{ widths: layout.widths, onResizeEnd: layout.resized, onReset: layout.resetWidth }}
              onResetColumns={layout.reset}
              selectionMode="multiple"
              selection={selection}
              onSelectionChange={setChosen}
              onDelete={canRemove ? (keys) => askRemove(items.filter((item) => keys.has(keyOf(item)))) : undefined}
              contextMenu={contextMenu}
              loadMore={paged.loadMore}
              isLoadingMore={paged.isLoadingMore}
              total={total}
              empty={empty}
              busy={paged.isStale}
            />
          </>
        )}
      </div>
      <RightRegionView
        selected={one !== undefined}
        details={
          one && <TrashDetails key={one.id} item={one} view={view} busy={busy} onRestore={() => restore([one])} onRemove={canRemove ? () => askRemove([one]) : undefined} />
        }
      />
      {asking && removedDays !== undefined && (
        <ConfirmRemove
          question={asking}
          removedDays={removedDays}
          onCancel={() => setAsking(undefined)}
          onConfirm={() => {
            const question = asking
            setAsking(undefined)
            remove(question)
          }}
        />
      )}
      {/* One at a time, once what's running is done */}
      {picking[0] && busy === undefined && (
        <MovePicker key={picking[0].id} items={NOTHING} title={t('trash.restoreTo', { name: picking[0].name })} onCancel={() => picked(picking[0]!)} onChoose={(folder) => restoreInto(picking[0]!, folder.id)} />
      )}
    </section>
  )
}

/** The key of the row that follows the items' rows in the list drawn now, else the one before them, else none */
function rowAfter(items: readonly Item[]): string | undefined {
  return rowAfterKeys(items.map(keyOf))?.key
}

/** The items, each once: offset pages shift when items come or go between them, so a page can repeat the last one's end */
function distinct(items: Item[]): Item[] {
  const seen = new Set<number>()
  return items.filter((item) => {
    if (seen.has(item.id)) return false
    seen.add(item.id)
    return true
  })
}
