// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useState } from 'react'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, Icon, Table, TextField } from '@healdan/ui'
import { groupHref, routes } from '../addresses'
import { useWords } from '../lists/useWords'
import { groupColumns, groupRowOf } from '../people/groupColumns'
import { NewGroupDialog } from '../people/GroupDialogs'
import { useGroupList } from '../people/groupQueries'
import { StaleNote } from '../people/StaleNote'
import { typed } from '../pickers/typing'
import { plural, t } from '../strings'
import styles from '../matters/Matters.module.css'

/** What the Groups list's address holds: its words */
export interface GroupsSearch {
  q?: string
}

/** The address's parameters the list reads; anything else, or anything that isn't what it should be, is left out */
export function groupsSearch(search: Record<string, unknown>): GroupsSearch {
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return { q: q === '' ? undefined : q }
}

/**
 * The Groups list, at /admin/groups (spec 8.7): every group but the people's own, by name, with its description and how
 * many people are in it, a fixed group and one a sign-in provider's group map names marked as such, found by the words of
 * its name, 100 rows at a time. The address keeps the words, so Back from a group's page comes back to the same list.
 * Selecting a row opens the group's page, and New Group makes one, then opens its page. While a new read fails, the rows
 * it had stay, with a note that it couldn't refresh (P4-R15).
 */
export function GroupsView({ search }: { search: GroupsSearch }) {
  const navigate = useNavigate({ from: routes.groups })
  const headingId = useId()
  const [adding, setAdding] = useState(false)
  const listing = useGroupList(search.q)

  /** The address with `change` made, kept in place of the one before: the words aren't a step of their own */
  function change(next: Partial<GroupsSearch>) {
    void navigate({ to: routes.groups, search: (prev) => ({ ...prev, ...next }), replace: true })
  }

  const words = useWords(search.q ?? '', (q) => change({ q: q === '' ? undefined : q }))

  // Built when the list first draws, and kept, so typing in the words doesn't draw every row again
  const columns = useMemo(() => groupColumns(), [])
  const rows = useMemo(() => listing.items.map(groupRowOf), [listing.items])
  // A page came, so its rows stay while a new read fails
  const kept = listing.total !== undefined

  const empty = listing.isPending ? t('common.loading') : <p className={styles.empty}>{t(search.q === undefined ? 'admin.groups.none' : 'admin.groups.noneMatch')}</p>

  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 id={headingId} className={styles.title}>
          {t('admin.groups.title')}
        </h1>
        {!listing.isStale && listing.total !== undefined && <span className={styles.count}>{plural('admin.groups.count', listing.total)}</span>}
        <div className={styles.actions}>
          <Button variant="default" onPress={() => setAdding(true)}>
            <Icon name="plus" size={14} /> {t('admin.groups.new')}
          </Button>
        </div>
      </div>
      <StaleNote failed={listing.isError && kept} onRetry={listing.refetch} />
      <div role="group" aria-label={t('admin.groups.filters')} className={styles.filters}>
        <div className={styles.words}>
          <TextField label={t('admin.groups.text')} value={words.text} onChange={words.type} />
        </div>
      </div>
      {listing.isError && !kept ? (
        <ErrorPanel error={listing.error} onRetry={listing.refetch} />
      ) : (
        <Table
          aria-labelledby={headingId}
          columns={columns}
          rows={rows}
          selectionMode="none"
          onOpen={(key) => {
            const id = idFrom(key)
            if (id !== undefined) void navigate({ href: groupHref(id) })
          }}
          loadMore={listing.loadMore}
          isLoadingMore={listing.isLoadingMore}
          total={listing.total}
          empty={empty}
          busy={listing.isStale}
        />
      )}
      {adding && <NewGroupDialog onCreated={(group) => void navigate({ href: groupHref(group.id) })} onClose={() => setAdding(false)} />}
    </div>
  )
}
