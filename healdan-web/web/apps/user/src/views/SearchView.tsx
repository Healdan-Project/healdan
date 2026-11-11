// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useRef } from 'react'
import { t, Table, type TableSort } from '@healdan/ui'
import { DetailsPanel } from '../details/DetailsPanel'
import { FilterChips } from '../filters/FilterChips'
import { FiltersButton } from '../filters/FiltersButton'
import { FiltersPanel } from '../filters/FiltersPanel'
import { useFilters } from '../filters/useFilters'
import { ErrorPanel } from '../frame/ErrorPanel'
import { RightRegionView, useRightRegion } from '../frame/RightRegion'
import { searchesDocuments, type SearchScope } from '../search/scopes'
import { useSearchFacets, useSearchResults } from '../search/useSearchResults'
import { Toolbar, type ListCount } from './Toolbar'
import { hitColumns, rowOf } from './listColumns'
import { isSortColumn, type ListingOrder, type SortColumn } from './useListing'
import { useRowSelection } from './useRowSelection'
import styles from './views.module.css'

export interface SearchViewProps {
  scope: SearchScope
  /** The words from the address; none means nothing has been searched for yet */
  q?: string
  sort?: SortColumn
  order?: ListingOrder
}

/**
 * /search: what the search box found, in the scope it was run in (spec section 6.3). Documents come by relevance until
 * a header sorts them, with Filters beside them; folders and matters are lists to open. The words, the scope, the
 * filters, the sort and the selection are all in the address, so a search can be linked, reloaded and gone back to.
 */
export function SearchView({ scope, q, sort, order }: SearchViewProps) {
  const text = q?.trim() ?? ''
  const documents = searchesDocuments(scope)
  const headingId = useId()
  const navigate = useNavigate()
  const { filters, active, change, remove, clear } = useFilters()
  const asked = documents && sort ? { column: sort, direction: order === 'desc' ? ('descending' as const) : ('ascending' as const) } : undefined
  const results = useSearchResults({ scope, q: text, sort: asked, filters })
  const region = useRightRegion()
  const funnel = useRef<HTMLButtonElement>(null)
  const filtersOpen = region.show === 'filters'
  const facets = useSearchFacets({ scope, q: text, filters, enabled: filtersOpen })
  const { selected, selection, change: changeSelection } = useRowSelection()
  const rows = useMemo(() => results.rows.map(rowOf), [results.rows])
  const columns = useMemo(() => hitColumns(scope), [scope])
  const authors = useMemo(() => {
    const names = new Map<string, string>()
    for (const item of results.rows) if (item.type === 'document' && item.author) names.set(item.author.username, item.author.displayName)
    for (const option of facets.data?.author ?? []) names.set(option.value, option.displayName)
    return names
  }, [results.rows, facets.data])

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
    if (!isSortColumn(next.column)) return
    const column = next.column
    void navigate({ to: '/search', search: (prev) => ({ ...prev, sort: column, order: next.direction === 'descending' ? 'desc' : 'asc' }), replace: true })
  }

  // A folder or a matter opens; a document is selected until opening it in its app comes (Tasks 16 and 18).
  function open(key: string) {
    const row = rows.find((one) => one.id === key)
    if (row?.item.type === 'folder') void navigate({ to: '/folders/$id', params: { id: String(row.item.id) } })
    else if (row?.item.type === 'matter') void navigate({ to: '/folders/$id', params: { id: String(row.item.folderId) } })
    else changeSelection(new Set([key]))
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
            />
            {documents && <FilterChips filters={filters} authors={authors} onRemove={remove} onClear={clear} onEmpty={() => funnel.current?.focus()} />}
            <Table
              aria-labelledby={headingId}
              columns={columns}
              rows={rows}
              sort={asked}
              onSort={documents ? sortBy : undefined}
              selectionMode="multiple"
              selection={selection}
              onSelectionChange={changeSelection}
              onOpen={open}
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
              onChange={change}
              onClose={() => {
                region.close()
                funnel.current?.focus()
              }}
            />
          )
        }
        details={selected !== undefined && <DetailsPanel key={selected} docNumber={selected} row={selectedRow?.type === 'document' ? selectedRow : undefined} context="search" />}
      />
    </section>
  )
}
