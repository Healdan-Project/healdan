// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useRef, useState } from 'react'
import { ApiError, apiPath, type components } from '@healdan/api'
import { Button, Icon, t, Table, toast, type TableSort } from '@healdan/ui'
import { FilterChips } from '../filters/FilterChips'
import { FiltersButton } from '../filters/FiltersButton'
import { FiltersPanel } from '../filters/FiltersPanel'
import { useAuthorNames } from '../filters/useAuthorNames'
import { useFacets, useUnfilteredDocuments } from '../filters/useFacets'
import { useFilters } from '../filters/useFilters'
import { ErrorPanel } from '../frame/ErrorPanel'
import { messageFor } from '../frame/errors'
import { RightRegionView, useRegionKind, useRightRegion } from '../frame/RightRegion'
import { keys, useFavorites, useFolder } from '../queries'
import { useApi } from '../services'
import { SelectionActions } from '../actions/SelectionActions'
import { useOpenKey } from '../actions/useOpenKey'
import { placeOf } from '../filing/dropTargets'
import { NewFolderButton } from '../organising/NewFolder'
import { useOrganisingTable } from '../organising/useOrganisingTable'
import { useFiling } from '../filing/FilingProvider'
import { UploadButton } from '../filing/UploadButton'
import { useDropZone } from '../filing/useDropZone'
import filingStyles from '../filing/filing.module.css'
import { Breadcrumb } from './Breadcrumb'
import { DetailsPanel } from '../details/DetailsPanel'
import { QuickLook } from '../preview/QuickLook'
import { ReadingPane } from '../preview/ReadingPane'
import { documentsOf, usePreviewKey } from '../preview/usePreviewKey'
import { NotFound } from './NotFound'
import { Toolbar, type ListCount } from './Toolbar'
import { listColumns, narrowed, rowOf } from './listColumns'
import { useRowSelection } from './useRowSelection'
import { childKey, isSortColumn, useListing, type ListingOrder, type SortColumn } from './useListing'
import styles from './views.module.css'

type S = components['schemas']

export interface FolderViewProps {
  folderId: number
  sort?: SortColumn
  order?: ListingOrder
}

// /folders/{id}: a folder, a search folder or a matter's top level (spec section 6.1), with Filters or the selected
// document's details beside it. Tasks 14, 16 and 19 add the details, the preview and filing by drop to it.
export function FolderView({ folderId, sort, order }: FolderViewProps) {
  const folder = useFolder(folderId)
  const view = folder.data
  const store = view?.kind === 'store'
  const asked = sort && { column: sort, direction: order === 'desc' ? ('descending' as const) : ('ascending' as const) }
  // The folder and its list are asked for at once: the list doesn't wait for the folder.
  const { filters, active, change, remove, clear } = useFilters()
  const listing = useListing({ folderId, sort: asked, filters, enabled: !store })
  const region = useRightRegion()
  const funnel = useRef<HTMLButtonElement>(null)
  const filtersOpen = region.show === 'filters'
  // The options' counts only while the panel shows them; "of 7" only while a filter leaves some out
  const facets = useFacets({ folderId, filters, enabled: filtersOpen && !store })
  const unfiltered = useUnfilteredDocuments({ folderId, enabled: active > 0 && !store })
  const navigate = useNavigate()
  const headingId = useId()
  const { selected, selection, change: changeSelection } = useRowSelection()
  const organising = useOrganisingTable(listing.rows, selection)
  // What was just filed flashes in the list, and the row the files are over is lit
  const { fresh } = useFiling()
  const place = useMemo(() => (view ? placeOf(view) : { id: folderId, kind: 'store' as const }), [view, folderId])
  const zone = useDropZone(place, (key) => {
    const found = listing.rows.find((item) => childKey(item) === key)
    if (found?.type === 'document') return { document: found }
    return found?.type === 'folder' ? { place: placeOf(found) } : undefined
  })
  const rows = useMemo(
    () =>
      listing.rows.map(rowOf).map((row) => {
        const flash = row.item.type === 'document' && fresh.has(row.item.docNumber)
        const dropping = zone.overRow === row.id
        return flash || dropping ? { ...row, flash, dropping } : row
      }),
    [listing.rows, fresh, zone.overRow],
  )
  const documents = useMemo(() => documentsOf(listing.rows), [listing.rows])
  const preview = usePreviewKey(changeSelection)
  const openDocument = useOpenKey(documents, changeSelection)
  // The reading pane makes the list narrow: it keeps the columns that fit
  const columns = useRegionKind({ preview: !store, details: selected !== undefined }) === 'preview' ? narrowed(listColumns) : listColumns
  const authors = useAuthorNames(listing.rows, facets.data)

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

  // A folder opens; a document opens in its app (or downloads, if the helper doesn't open its kind), and is selected
  function open(key: string) {
    if (key.startsWith('f')) void navigate({ to: '/folders/$id', params: { id: key.slice(1) } })
    else openDocument(key)
  }

  const selectedRow = selected === undefined ? undefined : listing.rows.find((item) => item.type === 'document' && item.docNumber === selected)

  function toggleFilters() {
    if (filtersOpen) region.close()
    else region.open('filters')
  }

  return (
    <section className={styles.withRegion} aria-labelledby={headingId}>
      <div className={`${styles.view} ${styles.folder} ${filingStyles.zone}`} data-dropping={zone.dropping || undefined} {...zone.props}>
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
            <Toolbar
              label={t('folder.actions')}
              count={listing.isStale ? undefined : countOf(listing.documents, active > 0, unfiltered)}
              end={<FiltersButton ref={funnel} open={filtersOpen} active={active} onPress={toggleFilters} />}
            >
              <UploadButton place={place} />
              <NewFolderButton place={place} />
              <SelectionActions document={selectedRow?.type === 'document' ? selectedRow : undefined} selected={organising.selected} />
            </Toolbar>
            <FilterChips filters={filters} authors={authors} onRemove={remove} onClear={clear} onEmpty={() => funnel.current?.focus()} />
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
              onPreview={preview}
              {...organising.tableProps}
              loadMore={listing.loadMore}
              isLoadingMore={listing.isLoadingMore}
              total={listing.total}
              empty={listing.isPending ? t('common.loading') : active > 0 ? t('filters.empty') : t('folder.empty')}
              busy={listing.isStale}
            />
          </>
        )}
      </div>
      {!store && (
        <RightRegionView
          filters={
            <FiltersPanel
              filters={filters}
              facets={facets.data}
              unavailable={facets.isError}
                busy={facets.isPlaceholderData}
              context="folder"
              onChange={change}
              onClose={() => {
                region.close()
                // The panel's close button goes with it; the funnel is where focus belongs.
                funnel.current?.focus()
              }}
            />
          }
          details={selected !== undefined && <DetailsPanel key={selected} docNumber={selected} row={selectedRow?.type === 'document' ? selectedRow : undefined} />}
          preview={<ReadingPane docNumber={selected} row={selectedRow?.type === 'document' ? selectedRow : undefined} />}
        />
      )}
      <QuickLook documents={documents} total={listing.documents} loadMore={listing.loadMore} />
    </section>
  )
}

/** What the count says: all the documents, or, with filters in use, how many pass of how many there are. */
function countOf(documents: number | undefined, filtered: boolean, unfiltered: { data?: number; isError: boolean }): ListCount | undefined {
  if (documents === undefined) return undefined
  if (!filtered) return { documents }
  // Nothing says "3 of ?" while the other count comes; if it never does, the count is the filtered one alone
  if (unfiltered.data === undefined) return unfiltered.isError ? { documents } : undefined
  return { documents, of: unfiltered.data }
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
