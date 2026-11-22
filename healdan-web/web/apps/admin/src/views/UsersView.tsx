// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom, useApi } from '@healdan/shell'
import { Button, Icon, SelectField, Table, TextField, type TableSort } from '@healdan/ui'
import { groupIdFrom, routes, userHref } from '../addresses'
import type { SortOrder } from '../lists/usePagedList'
import { useChosen } from '../lists/useChosen'
import { useWords } from '../lists/useWords'
import { userColumns, userRowOf } from '../people/columns'
import { NewUserDialog } from '../people/NewUserDialog'
import { isUserSort, isUserStatus, useUserList, userStatuses, type UserSort } from '../people/queries'
import { StaleNote } from '../people/StaleNote'
import { GroupPicker } from '../pickers/GroupPicker'
import { typed } from '../pickers/typing'
import { adminKeys } from '../queries'
import { plural, t } from '../strings'
import styles from '../matters/Matters.module.css'

type A = adminComponents['schemas']

/** What the Users list's address holds: its words, the group and status it filters by, and its order */
export interface UsersSearch {
  q?: string
  group?: number
  status?: A['UserStatus']
  sort?: UserSort
  order?: SortOrder
}

/**
 * The address's parameters the list reads; anything else, or anything that isn't what it should be, is left out. The
 * router validates what it validated before too, as it builds the next address from it, so an id may come as a number.
 */
export function usersSearch(search: Record<string, unknown>): UsersSearch {
  const group = search.group
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return {
    q: q === '' ? undefined : q,
    // A group's id, which is negative for LogicalDOC's own groups
    group: groupIdFrom(typeof group === 'number' || typeof group === 'string' ? group : undefined),
    status: isUserStatus(search.status) ? search.status : undefined,
    sort: isUserSort(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
  }
}

/**
 * The group the address filters by, by name: as picked here, else as a row names it, else as the server does, asked only
 * once the rows for these filters have come and none of them names it
 */
function useGroupNamed(id: number | undefined, chosen: A['GroupBrief'] | null | undefined, rows: A['UserRow'][], settled: boolean): A['GroupBrief'] | null {
  const api = useApi()
  const fromRow = id === undefined ? undefined : rows.flatMap((row) => row.groups).find((group) => group.id === id)
  const asked = useQuery({
    queryKey: [...adminKeys.groups, id ?? -1],
    queryFn: ({ signal }) => api.get<A['GroupDetail']>(apiPath`/v1/admin/groups/${id ?? -1}`, undefined, { signal }),
    enabled: id !== undefined && chosen === undefined && fromRow === undefined && settled,
  })
  if (chosen !== undefined) return chosen
  if (id === undefined) return null
  if (fromRow !== undefined) return fromRow
  return asked.data?.id === id ? { id: asked.data.id, name: asked.data.name } : null
}

/**
 * The Users list, at /admin/users (spec 8.7): each person with their username, groups, how they sign in, status, last
 * sign-in and devices, sorted by name, username or last sign-in, filtered by the words typed (a name, a username or an
 * email), a group and a status, 100 rows at a time. The address keeps the order and the filters, so Back from a person's
 * page comes back to the same list. Selecting a row opens the person's page, and New User adds a password account, then
 * opens theirs. While a new read fails, the rows it had stay, with a note that it couldn't refresh (P4-R15).
 */
export function UsersView({ search }: { search: UsersSearch }) {
  const navigate = useNavigate({ from: routes.users })
  const headingId = useId()
  const [adding, setAdding] = useState(false)
  const listing = useUserList({ q: search.q, groupId: search.group, status: search.status, sort: search.sort, order: search.order })
  const filtered = search.q !== undefined || search.group !== undefined || search.status !== undefined
  const [chosenGroup, chooseGroup] = useChosen<A['GroupBrief']>(search.group)
  const group = useGroupNamed(search.group, chosenGroup, listing.items, !listing.isPending && !listing.isStale)

  /** The address with `change` made, kept in place of the one before: an order or a filter isn't a step of its own */
  function change(next: Partial<UsersSearch>) {
    void navigate({ to: routes.users, search: (prev) => ({ ...prev, ...next }), replace: true })
  }

  const words = useWords(search.q ?? '', (q) => change({ q: q === '' ? undefined : q }))

  // Built when the list first draws, and kept, so typing in the filters doesn't draw every row again
  const columns = useMemo(() => userColumns(), [])
  const rows = useMemo(() => listing.items.map(userRowOf), [listing.items])
  const shown: TableSort = { column: search.sort ?? 'name', direction: search.order === 'desc' ? 'descending' : 'ascending' }
  // A page came, so its rows stay while a new read fails
  const kept = listing.total !== undefined

  function sortBy(next: TableSort) {
    if (!isUserSort(next.column)) return
    change({ sort: next.column, order: next.direction === 'descending' ? 'desc' : 'asc' })
  }

  const empty = listing.isPending ? t('common.loading') : <p className={styles.empty}>{t(filtered ? 'admin.users.noneMatch' : 'admin.users.none')}</p>

  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 id={headingId} className={styles.title}>
          {t('admin.users.title')}
        </h1>
        {!listing.isStale && listing.total !== undefined && <span className={styles.count}>{plural('admin.users.count', listing.total)}</span>}
        <div className={styles.actions}>
          <Button variant="default" onPress={() => setAdding(true)}>
            <Icon name="plus" size={14} /> {t('admin.users.new')}
          </Button>
        </div>
      </div>
      <StaleNote failed={listing.isError && kept} onRetry={listing.refetch} />
      <div role="group" aria-label={t('admin.users.filters')} className={styles.filters}>
        <div className={`${styles.filter} ${styles.words}`}>
          <TextField label={t('admin.users.text')} value={words.text} onChange={words.type} />
        </div>
        <div className={styles.filter}>
          <GroupPicker
            label={t('admin.users.group')}
            value={group}
            onChange={(next) => {
              chooseGroup(next)
              change({ group: next?.id })
            }}
          />
        </div>
        <div className={styles.filter}>
          <SelectField
            label={t('admin.users.status')}
            value={search.status ?? ''}
            onChange={(value) => change({ status: isUserStatus(value) ? value : undefined })}
            options={userStatuses.map((status) => ({ value: status, label: t(`admin.user.status.${status}`) }))}
            empty={t('admin.users.allStatuses')}
          />
        </div>
        {filtered && (
          <Button
            onPress={() => {
              words.clear()
              chooseGroup(null)
              change({ q: undefined, group: undefined, status: undefined })
            }}
          >
            {t('admin.users.clear')}
          </Button>
        )}
      </div>
      {listing.isError && !kept ? (
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
            if (id !== undefined) void navigate({ href: userHref(id) })
          }}
          loadMore={listing.loadMore}
          isLoadingMore={listing.isLoadingMore}
          total={listing.total}
          empty={empty}
          busy={listing.isStale}
        />
      )}
      {adding && <NewUserDialog onCreated={(user) => void navigate({ href: userHref(user.id) })} onClose={() => setAdding(false)} />}
    </div>
  )
}
