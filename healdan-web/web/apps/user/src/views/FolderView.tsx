// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useState } from 'react'
import { ApiError, apiPath, type components } from '@healdan/api'
import { Button, formatDate, formatVersion, Icon, t, Table, toast, type IconName, type TableColumn, type TableRow, type TableSort } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { messageFor } from '../frame/errors'
import { keys, useFavorites, useFolder } from '../queries'
import { useSelection } from '../selection'
import { useApi } from '../services'
import { Breadcrumb } from './Breadcrumb'
import { NotFound } from './NotFound'
import { Toolbar } from './Toolbar'
import { childKey, isSortColumn, useListing, type Child, type ListingOrder, type SortColumn } from './useListing'
import styles from './views.module.css'

type S = components['schemas']

interface ListRow extends TableRow {
  item: Child
}

function rowOf(item: Child): ListRow {
  return { id: childKey(item), textValue: item.name, selectable: item.type !== 'folder', item }
}

const kinds: [RegExp, IconName][] = [
  [/\.pdf$/i, 'filePdf'],
  [/\.(docx?|docm|dotx?|rtf)$/i, 'fileWord'],
  [/\.(xlsx?|xlsm|csv)$/i, 'spreadsheet'],
  [/\.(pptx?|pptm)$/i, 'presentation'],
  [/\.(eml|msg)$/i, 'email'],
  [/\.(png|jpe?g|gif|tiff?|bmp|webp|heic)$/i, 'picture'],
]

function iconOf(item: Child): IconName {
  if (item.type === 'folder') return item.kind === 'searchFolder' ? 'folderSearch' : 'folder'
  return kinds.find(([pattern]) => pattern.test(item.name))?.[1] ?? 'document'
}

// Title shows the file name: CE has no title of its own, and the server sorts Title by it (plan correction 7).
const columns: TableColumn<ListRow>[] = [
  {
    id: 'title',
    name: t('list.title'),
    isRowHeader: true,
    allowsSorting: true,
    width: '3fr',
    cell: ({ item }) => (
      <>
        <Icon name={iconOf(item)} />
        <span className={styles.name}>{item.name}</span>
      </>
    ),
  },
  { id: 'docNumber', name: t('list.docNumber'), allowsSorting: true, align: 'end', width: 96, cell: ({ item }) => item.type === 'document' && String(item.docNumber) },
  { id: 'version', name: t('list.version'), allowsSorting: true, align: 'end', width: 84, cell: ({ item }) => item.type === 'document' && formatVersion(item.fileVersion) },
  { id: 'author', name: t('list.author'), allowsSorting: true, width: '1fr', cell: ({ item }) => item.type === 'document' && item.author?.displayName },
  { id: 'edited', name: t('list.edited'), allowsSorting: true, width: 132, cell: ({ item }) => item.type === 'document' && formatDate(item.lastModified) },
]

export interface FolderViewProps {
  folderId: number
  sort?: SortColumn
  order?: ListingOrder
}

// /folders/{id}: a folder, a search folder or a matter's top level (spec section 6.1). Tasks 13, 14, 16 and 19 add the
// filters, the details, the preview and filing by drop to it.
export function FolderView({ folderId, sort, order }: FolderViewProps) {
  const folder = useFolder(folderId)
  const view = folder.data
  const store = view?.kind === 'store'
  const asked = sort && { column: sort, direction: order === 'desc' ? ('descending' as const) : ('ascending' as const) }
  // The folder and its list are asked for at once: the list doesn't wait for the folder.
  const listing = useListing({ folderId, sort: asked, enabled: !store })
  const navigate = useNavigate()
  const headingId = useId()
  const { selected, select } = useSelection()
  // Several documents selected can't all be in the address, which holds one; the view keeps them while it has none.
  const [several, setSeveral] = useState<Set<string> | null>(null)
  const selection = several && selected === undefined ? several : new Set(selected === undefined ? [] : [`d${selected}`])
  const rows = useMemo(() => listing.rows.map(rowOf), [listing.rows])

  if (folder.isError) {
    if (folder.error instanceof ApiError && folder.error.status === 404) return <NotFound />
    return (
      <section className={styles.view}>
        <ErrorPanel error={folder.error} onRetry={() => void folder.refetch()} />
      </section>
    )
  }
  if (!view)
    return (
      <section className={styles.view}>
        <p className={styles.empty}>{t('common.loading')}</p>
      </section>
    )

  // The server's own order is by title, but for a search folder's, newest change first, which no column shows.
  const shown: TableSort | undefined = asked ?? (view.kind === 'searchFolder' ? undefined : { column: 'title', direction: 'ascending' })

  function sortBy(next: TableSort) {
    if (!isSortColumn(next.column)) return
    const column = next.column
    void navigate({
      to: '/folders/$id',
      params: { id: String(folderId) },
      search: (prev) => ({ ...prev, sort: column, order: next.direction === 'descending' ? 'desc' : 'asc' }),
      replace: true,
    })
  }

  function changeSelection(next: Set<string>) {
    const documents = [...next].filter((key) => key.startsWith('d'))
    setSeveral(documents.length > 1 ? new Set(documents) : null)
    select(documents.length === 1 ? Number(documents[0]!.slice(1)) : undefined)
  }

  // A folder opens; a document is selected until opening it in its app comes (Tasks 16 and 18).
  function open(key: string) {
    if (key.startsWith('f')) void navigate({ to: '/folders/$id', params: { id: key.slice(1) } })
    else changeSelection(new Set([key]))
  }

  return (
    <section className={`${styles.view} ${styles.folder}`} aria-labelledby={headingId}>
      <Breadcrumb folder={view} />
      <div className={styles.heading}>
        <h1 id={headingId} className={styles.title}>
          {view.name}
        </h1>
        {/* A matter goes in My Matters (the + in the left pane), not in Favorites */}
        {view.kind !== 'matter' && !store && <FavoriteStar folderId={folderId} />}
      </div>
      {store ? (
        <p>{t('problems.not-browsable')}</p>
      ) : listing.isError ? (
        <ErrorPanel error={listing.error} onRetry={listing.refetch} />
      ) : (
        <>
          <Toolbar label={t('folder.actions')} count={listing.documents === undefined ? undefined : { documents: listing.documents }} />
          <Table
            aria-labelledby={headingId}
            columns={columns}
            rows={rows}
            sort={shown}
            onSort={sortBy}
            selectionMode="multiple"
            selection={selection}
            onSelectionChange={changeSelection}
            onOpen={open}
            loadMore={listing.loadMore}
            isLoadingMore={listing.isLoadingMore}
            total={listing.total}
            empty={listing.isPending ? t('common.loading') : t('folder.empty')}
            busy={listing.isStale}
          />
        </>
      )}
    </section>
  )
}

/** The star beside a folder's name: in Favorites or not. */
function FavoriteStar({ folderId }: { folderId: number }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const favorites = useFavorites()
  const [busy, setBusy] = useState(false)
  const favorite = favorites.data?.items.find((f): f is S['FavoriteView'] => f.item.type === 'folder' && f.item.id === folderId)

  async function toggle() {
    setBusy(true)
    try {
      if (favorite) await api.send('DELETE', apiPath`/v1/me/favorites/${favorite.id}`)
      else await api.send('POST', '/v1/me/favorites', { folderId })
      await queryClient.invalidateQueries({ queryKey: keys.favorites })
    } catch (error) {
      toast(messageFor(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Button
      variant="quiet"
      aria-label={favorite ? t('folder.favorite.remove') : t('folder.favorite.add')}
      isDisabled={busy || favorites.isPending}
      onPress={() => void toggle()}
    >
      <span className={favorite ? styles.starred : styles.star}>
        <Icon name={favorite ? 'starFilled' : 'star'} size={18} />
      </span>
    </Button>
  )
}
