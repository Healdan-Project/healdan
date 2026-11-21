// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useRef } from 'react'
import { t, Table, type TableSort } from '@healdan/ui'
import { DetailsPanel } from '../details/DetailsPanel'
import { SelectionActions } from '../actions/SelectionActions'
import { useOrganisingTable } from '../organising/useOrganisingTable'
import { useOpenKey } from '../actions/useOpenKey'
import { QuickLook } from '../preview/QuickLook'
import { ReadingPane } from '../preview/ReadingPane'
import { documentsOf, usePreviewKey } from '../preview/usePreviewKey'
import { FilterChips } from '../filters/FilterChips'
import { FiltersButton } from '../filters/FiltersButton'
import { FiltersPanel } from '../filters/FiltersPanel'
import { useAuthorNames } from '../filters/useAuthorNames'
import { useFilters } from '../filters/useFilters'
import { useMatterNames } from '../filters/useMatterNames'
import { ErrorPanel } from '../frame/ErrorPanel'
import { RightRegionView, useRegionKind, useRightRegion } from '../frame/RightRegion'
import { isScopeSort, searchesDocuments, type SearchScope } from '../search/scopes'
import { useSearchFacets, useSearchResults } from '../search/useSearchResults'
import { Toolbar, type ListCount } from './Toolbar'
import { hitColumns, rowOf, type ListRow } from './listColumns'
import { useDocumentListColumns, useListWidths, type DocumentListColumns, type ListWidths } from './useListColumns'
import type { ListingOrder, SortColumn } from './useListing'
import { useRowSelection } from './useRowSelection'
import styles from './views.module.css'

export interface SearchViewProps {
  scope: SearchScope
  /** The words from the address; none means nothing has been searched for yet */
  q?: string
  /** One the scope's search takes (`scopeSorts`), as the route keeps it */
  sort?: SortColumn
  order?: ListingOrder
}

/**
 * /search: what the search box found, in the scope it was run in (spec section 6.3). Documents come by relevance until
 * a header sorts them, and Sort by Relevance in a heading's menu takes them back, with Filters beside them; folders and
 * matters are lists to open, in the search's own order until a header sorts them by one of their scope's few sorts (list
 * columns spec 5.2), and with no relevance to go back to. The words, the scope, the filters, the sort and the selection
 * are all in the address, so a search can be linked, reloaded and gone back to.
 */
export function SearchView({ scope, q, sort, order }: SearchViewProps) {
  const text = q?.trim() ?? ''
  const documents = searchesDocuments(scope)
  const headingId = useId()
  const navigate = useNavigate()
  const { filters, active, change, remove, clear } = useFilters()
  const asked = isScopeSort(scope, sort) ? { column: sort, direction: order === 'desc' ? ('descending' as const) : ('ascending' as const) } : undefined
  const results = useSearchResults({ scope, q: text, sort: asked, filters })
  const region = useRightRegion()
  const funnel = useRef<HTMLButtonElement>(null)
  const filtersOpen = region.show === 'filters'
  const facets = useSearchFacets({ scope, q: text, filters, enabled: filtersOpen })
  const { selected, selection, change: changeSelection } = useRowSelection(results.rows)
  const rows = useMemo(() => results.rows.map(rowOf), [results.rows])
  const organising = useOrganisingTable(results.rows, selection, changeSelection)
  // Only a view that draws the pane has a list beside it, as the document scopes do
  const columns = useHitColumns(scope, useRegionKind({ preview: documents, details: selected !== undefined }) === 'preview')
  const hitDocuments = useMemo(() => documentsOf(results.rows), [results.rows])
  const preview = usePreviewKey(changeSelection)
  const openDocument = useOpenKey(hitDocuments, changeSelection)
  const authors = useAuthorNames(results.rows, facets.data)
  const { clients, matters } = useMatterNames(results.rows, facets.data)

  if (text === '') {
    return (
      <section className={styles.view}>
        <h1 className={styles.title}>{t('search.results')}</h1>
        <p className={styles.empty}>{t('search.noQuery')}</p>
      </section>
    )
  }

  const scopeName = t(`scope.${scope}`)
  const sub = documents && !asked ? t('search.inByRelevance', { scope: scopeName }) : t('search.in', { scope: scopeName })

  function sortBy(next: TableSort) {
    if (!isScopeSort(scope, next.column)) return
    const column = next.column
    void navigate({ to: '/search', search: (prev) => ({ ...prev, sort: column, order: next.direction === 'descending' ? 'desc' : 'asc' }), replace: true })
  }

  // Sort by Relevance, in a sorted search's heading menus (spec 4.4): the address without the sort, as a new search has
  const relevance = { onSelect: () => void navigate({ to: '/search', search: (prev) => ({ ...prev, sort: undefined, order: undefined }), replace: true }) }

  // A folder or a matter opens; a document opens in its app (or downloads, if the helper doesn't open its kind), and is selected
  function open(key: string) {
    const row = rows.find((one) => one.id === key)
    if (row?.item.type === 'folder') void navigate({ to: '/folders/$id', params: { id: String(row.item.id) } })
    else if (row?.item.type === 'matter') void navigate({ to: '/folders/$id', params: { id: String(row.item.folderId) } })
    else openDocument(key)
  }

  const selectedRow = selected === undefined ? undefined : results.rows.find((item) => item.type === 'document' && item.docNumber === selected)
  const count: ListCount | undefined =
    results.isStale || !documents ? undefined : results.total !== undefined ? { documents: results.total } : results.totalAtLeast !== undefined ? { atLeast: results.totalAtLeast } : undefined

  return (
    <section className={styles.withRegion} aria-labelledby={headingId}>
      <div className={`${styles.view} ${styles.folder}`}>
        <div className={styles.heading}>
          <h1 id={headingId} className={styles.title}>
            {t('search.query', { q: text })}
          </h1>
          <span className={styles.muted}>{sub}</span>
        </div>
        {results.isError ? (
          <ErrorPanel error={results.error} onRetry={results.refetch} />
        ) : (
          <>
            <Toolbar
              label={t('search.actions')}
              count={count}
              end={documents && <FiltersButton ref={funnel} open={filtersOpen} active={active} onPress={() => (filtersOpen ? region.close() : region.open('filters'))} />}
            >
              {documents && <SelectionActions document={selectedRow?.type === 'document' ? selectedRow : undefined} selected={organising.selected} />}
            </Toolbar>
            {documents && <FilterChips filters={filters} authors={authors} clients={clients} matters={matters} onRemove={remove} onClear={clear} onEmpty={() => funnel.current?.focus()} />}
            <Table
              aria-labelledby={headingId}
              {...columns}
              rows={rows}
              sort={asked}
              onSort={sortBy}
              relevance={documents ? relevance : undefined}
              selectionMode="multiple"
              selection={selection}
              onSelectionChange={changeSelection}
              onOpen={open}
              onPreview={documents ? preview : undefined}
              // The folders, matters and clients scopes take F2 and Delete: a folder renames or is deleted, and a matter says why it isn't
              {...(documents ? organising.tableProps : { onRename: organising.tableProps.onRename, onDelete: organising.tableProps.onDelete })}
              contextMenu={organising.contextMenu}
              loadMore={results.loadMore}
              isLoadingMore={results.isLoadingMore}
              total={results.total}
              empty={results.isPending ? t('common.loading') : active > 0 ? t('filters.empty') : t('search.empty')}
              busy={results.isStale}
            />
          </>
        )}
      </div>
      <RightRegionView
        filters={
          documents && (
            <FiltersPanel
              filters={filters}
              facets={facets.data}
              unavailable={facets.isError}
              busy={facets.isPlaceholderData}
              context="search"
              clients={clients}
              matters={matters}
              onChange={change}
              onClose={() => {
                region.close()
                funnel.current?.focus()
              }}
            />
          )
        }
        details={selected !== undefined && (
          <DetailsPanel key={selected} docNumber={selected} row={selectedRow?.type === 'document' ? selectedRow : undefined} context="search" onGone={() => changeSelection(new Set())} />
        )}
        preview={documents && <ReadingPane docNumber={selected} row={selectedRow?.type === 'document' ? selectedRow : undefined} />}
      />
      <QuickLook documents={hitDocuments} total={results.total} loadMore={results.loadMore} />
    </section>
  )
}

/**
 * A search's columns (spec 4.8): a document scope's are chosen, in the scope's own layout or beside the reading pane in that
 * one's, and sort on the server, every one; the Folders, Matters and Clients scopes keep their own columns, each scope its
 * widths, and sort by their scope's sorts. Both hooks run at every render, as hooks can't be called by turns: the one a scope
 * doesn't use reads a layout it never draws or writes.
 */
function useHitColumns(scope: SearchScope, beside: boolean): DocumentListColumns | ListWidths<ListRow> {
  const documentScope = searchesDocuments(scope) ? scope : undefined
  const otherScope = searchesDocuments(scope) ? undefined : scope
  const chosen = useDocumentListColumns(documentScope ?? 'documents', { beside, serverSorted: true })
  // Made again when the firm's word for a matter, a heading of these scopes, comes after the list first draws (setLabels)
  const matterWord = t('list.matter')
  // eslint-disable-next-line react-hooks/exhaustive-deps -- matterWord is read inside hitColumns, through the headings
  const own = useMemo(() => (otherScope ? hitColumns(otherScope) : []), [otherScope, matterWord])
  const widths = useListWidths(otherScope ?? 'folders', own)
  return documentScope ? chosen : widths
}
