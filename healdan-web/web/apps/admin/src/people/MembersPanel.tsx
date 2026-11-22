// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useId, useRef, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi, useMe } from '@healdan/shell'
import { Button, formatCount, Icon, StatusDot, TextField, toast, VisuallyHidden } from '@healdan/ui'
import { routes } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import { useFocusWhenGone } from '../focus'
import { typed, useDebounced } from '../pickers/typing'
import { plural, t } from '../strings'
import page from '../matters/MatterPage.module.css'
import { AddMemberDialog } from './AddMemberDialog'
import { membersChanged, useGroupMembers } from './groupQueries'
import { isEveryone, mappersOf, memberLock } from './groupText'
import { StaleNote } from './StaleNote'
import { statusDot, statusWords } from './text'
import groupStyles from './Groups.module.css'
import styles from './People.module.css'

type A = adminComponents['schemas']

/**
 * A group's members, on its page (spec 8.7): each person in it, by name, with their username and status, their name
 * opening their page; found by the words typed (a name, a username or an email), 100 at a time, with Show More for the
 * next. Add Member adds someone found by name, and Remove takes one out, asking first. What the server would refuse is
 * said in place of a member's Remove: a read-only account stays in `guest`, and the `admin` account and the person signed
 * in here in `admin`. Everyone keeps everyone, so it offers neither. Once a member taken out has gone from the list, and
 * their Remove with it, the focus goes to the section's heading. While a new read fails, the list it had stays, with a
 * note (P4-R15).
 */
export function MembersPanel({ group }: { group: A['GroupDetail'] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const own = useMe().data?.id
  const [text, setText] = useState('')
  const words = useDebounced(typed(text))
  const listing = useGroupMembers(group.id, words === '' ? undefined : words)
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<A['GroupMember']>()
  const [removed, setRemoved] = useState<number>()
  const members = listing.items
  useFocusWhenGone(removed !== undefined && listing.total !== undefined && !members.some((member) => member.id === removed), heading)
  // Every account but a read-only one is in Everyone, and a read-only one is in no group but its own and guest
  const changes = !isEveryone(group)
  const kept = listing.total !== undefined

  async function remove(member: A['GroupMember']) {
    await api.send<void>('DELETE', apiPath`/v1/admin/groups/${group.id}/members/${member.id}`)
    toast(t('admin.removeMember.done', { name: member.name, group: group.name }))
    setRemoved(member.id)
    membersChanged(queryClient)
  }

  function list() {
    if (listing.isError && !kept) return <ErrorPanel error={listing.error} onRetry={listing.refetch} />
    if (listing.isPending) return <p className={styles.note}>{t('common.loading')}</p>
    if (members.length === 0) return <p className={styles.line}>{t(words === '' ? 'admin.groupMembers.none' : 'admin.groupMembers.noneMatch')}</p>
    return (
      <div className={styles.tableBox}>
        <table className={styles.table} aria-label={t('admin.groupMembers.list', { name: group.name })} aria-busy={listing.isStale || undefined}>
          <thead>
            <tr>
              <th scope="col">{t('admin.groupMembers.name')}</th>
              <th scope="col">{t('admin.groupMembers.username')}</th>
              <th scope="col">{t('admin.groupMembers.status')}</th>
              {changes && (
                <th scope="col">
                  <VisuallyHidden>{t('admin.groupMembers.actions')}</VisuallyHidden>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {members.map((member) => {
              const lock = memberLock(group, member, own)
              return (
                <tr key={member.id}>
                  <th scope="row" className={styles.name}>
                    <Link to={routes.user} params={{ id: String(member.id) }} className={styles.link}>
                      {member.name}
                    </Link>
                    {member.readOnly && (
                      <>
                        {' '}
                        <span className={styles.tag}>{t('admin.users.readOnly')}</span>
                      </>
                    )}
                  </th>
                  <td>{member.username}</td>
                  <td>
                    <StatusDot status={statusDot(member.status)}>{statusWords({ status: member.status, blockedUntil: null })}</StatusDot>
                  </td>
                  {changes && (
                    <td className={styles.end}>
                      {lock !== undefined ? (
                        <span className={styles.note}>{lock}</span>
                      ) : (
                        <Button aria-label={t('admin.groupMembers.removeName', { name: member.name })} onPress={() => setRemoving(member)}>
                          {t('admin.groupMembers.remove')}
                        </Button>
                      )}
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <section aria-labelledby={headingId} className={`${styles.section} ${styles.wide}`}>
      <div className={groupStyles.head}>
        <h2 id={headingId} ref={heading} tabIndex={-1} className={styles.sectionTitle}>
          {t('admin.group.members')}
        </h2>
        {changes && (
          <Button onPress={() => setAdding(true)}>
            <span className={page.withIcon}>
              <Icon name="plus" size={14} />
              {t('admin.groupMembers.add')}
            </span>
          </Button>
        )}
      </div>
      <StaleNote failed={listing.isError && kept} onRetry={listing.refetch} />
      <div role="group" aria-label={t('admin.groupMembers.filters')} className={groupStyles.filter}>
        <TextField label={t('admin.groupMembers.text')} value={text} onChange={setText} />
      </div>
      {list()}
      {listing.loadMore !== undefined && (
        <div className={groupStyles.more}>
          <span className={styles.note}>{t('admin.groupMembers.shown', { shown: formatCount(members.length), total: formatCount(listing.total ?? members.length) })}</span>
          <Button isPending={listing.isLoadingMore} onPress={listing.loadMore}>
            {t('admin.groupMembers.more')}
          </Button>
        </div>
      )}
      {adding && <AddMemberDialog group={group} onClose={() => setAdding(false)} />}
      {removing !== undefined && (
        <Confirm
          title={t('admin.removeMember.title', { name: removing.name, group: group.name })}
          confirm={t('admin.removeMember.confirm')}
          run={() => remove(removing)}
          onClose={() => setRemoving(undefined)}
        >
          <p className={styles.line}>{t('admin.removeMember.line', { group: group.name })}</p>
          {group.admin && <p className={styles.line}>{t('admin.removeMember.admin')}</p>}
          {group.mappedBy.length > 0 && <p className={styles.line}>{plural('admin.addMember.mapped', group.mappedBy.length, { providers: mappersOf(group), group: group.name })}</p>}
        </Confirm>
      )}
    </section>
  )
}
