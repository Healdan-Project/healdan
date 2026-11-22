// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useId, useState, type ReactNode } from 'react'
import { ApiError, apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, formatCount, Icon, toast, type IconName } from '@healdan/ui'
import { groupIdFrom, routes } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import { Fact } from '../facts/Fact'
import { Problems, type Problem } from '../forms/Problems'
import { GroupDescriptionDialog, RenameGroupDialog } from '../people/GroupDialogs'
import { groupChanged, groupKeys, useGroup } from '../people/groupQueries'
import { deleteRefusal, isEveryone, isGuest, mappersOf, renameRefusal } from '../people/groupText'
import { MembersPanel } from '../people/MembersPanel'
import { StaleNote } from '../people/StaleNote'
import { plural, t } from '../strings'
import clientStyles from '../clients/Clients.module.css'
import page from '../matters/MatterPage.module.css'
import groupStyles from '../people/Groups.module.css'
import styles from '../people/People.module.css'
import pageStyles from './views.module.css'

type A = adminComponents['schemas']

/**
 * A group's page, at /admin/groups/{id} (spec 8.7): its name, its facts (its description, how many people are in it, how
 * many folders give it a permission of their own, and the sign-in providers whose group map names it), Rename, Edit
 * Description and Delete, and its members. What the server would refuse is greyed out, with why under the actions, which
 * describes it: a fixed group keeps its name and is never deleted (one sentence, for Rename and Delete alike), and a
 * group a provider's group map names, or whose name a deleted group couldn't keep, isn't deleted. Delete asks first and
 * names what it takes. A group a provider maps says that a change to the membership of someone who signs in through it
 * is set again at their next sign-in. CE's inherit from group isn't offered. While a new read fails, the page and any
 * dialog open on it stay, with a note that it couldn't refresh (P4-R15). An address that names no group, a person's own
 * or a deleted one among them, says so in the app's words; LogicalDOC's own groups, `publisher` among them, have
 * negative ids (groupIdFrom).
 */
export function GroupView({ id }: { id: string }) {
  // LogicalDOC's own groups have negative ids
  const groupId = groupIdFrom(id)
  return groupId === undefined ? <GroupNotFound /> : <GroupPage id={groupId} />
}

function GroupPage({ id }: { id: number }) {
  const asked = useGroup(id)
  if (asked.isError && asked.error instanceof ApiError && asked.error.status === 404) return <GroupNotFound />
  if (asked.data === undefined) {
    return <div className={page.page}>{asked.isError ? <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} /> : <p className={page.muted}>{t('common.loading')}</p>}</div>
  }
  // By its id, so another group's page, by Back or Forward too, starts afresh: no dialog or words carried over
  return <Group key={asked.data.id} group={asked.data} stale={asked.isError} onRetry={() => void asked.refetch()} />
}

type Open = 'rename' | 'description' | 'delete'

/** A page's action: its icon and words */
function Action({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <span className={page.withIcon}>
      <Icon name={icon} size={14} />
      {children}
    </span>
  )
}

function Group({ group, stale, onRetry }: { group: A['GroupDetail']; stale: boolean; onRetry: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate({ from: routes.group })
  const [open, setOpen] = useState<Open | null>(null)
  const close = () => setOpen(null)
  const renameRefused = renameRefusal(group)
  const deleteRefused = deleteRefusal(group)
  const renameWhy = useId()
  const deleteWhy = useId()
  // A fixed group's sentence says why for Rename and Delete alike, so it describes both
  const refused: Problem[] = [
    ...(renameRefused === undefined ? [] : [{ key: 'rename', id: renameWhy, sentence: renameRefused }]),
    ...(deleteRefused === undefined ? [] : [{ key: 'delete', id: deleteWhy, sentence: deleteRefused }]),
  ]
  const deleteDescribedBy = group.fixed ? renameWhy : deleteRefused !== undefined ? deleteWhy : undefined
  const mapped = group.mappedBy.length > 0

  async function remove() {
    await api.send<void>('DELETE', apiPath`/v1/admin/groups/${group.id}`)
    toast(t('admin.deleteGroup.done', { name: group.name }))
    // Off its page first, so nothing asks for it again
    await navigate({ to: routes.groups })
    queryClient.removeQueries({ queryKey: groupKeys.group(group.id) })
    // Its name was in the people's groups, the group menus and the folders' and Stores' access
    groupChanged(queryClient)
  }

  return (
    <div className={page.page}>
      <nav aria-label={t('admin.crumbs.label')} className={clientStyles.crumbs}>
        <ol>
          <li>
            <Link to={routes.groups} className={clientStyles.crumbLink}>
              {t('admin.groups.title')}
            </Link>
          </li>
          <li aria-current="page">{group.name}</li>
        </ol>
      </nav>
      <div className={clientStyles.head}>
        <h1 className={clientStyles.title}>{group.name}</h1>
        {group.fixed && <span className={styles.tag}>{t('admin.groups.fixed')}</span>}
        <div className={clientStyles.actions}>
          <Button isDisabled={renameRefused !== undefined} aria-describedby={renameRefused === undefined ? undefined : renameWhy} onPress={() => setOpen('rename')}>
            <Action icon="pencil">{t('admin.group.rename')}</Action>
          </Button>
          <Button onPress={() => setOpen('description')}>
            <Action icon="pencil">{t('admin.group.editDescription')}</Action>
          </Button>
          <Button isDisabled={group.fixed || deleteRefused !== undefined} aria-describedby={deleteDescribedBy} onPress={() => setOpen('delete')}>
            <Action icon="trash">{t('admin.group.delete')}</Action>
          </Button>
        </div>
      </div>
      <StaleNote failed={stale} onRetry={onRetry} />
      {refused.length > 0 && (
        <div className={styles.refused}>
          <Problems problems={refused} />
        </div>
      )}
      <dl className={clientStyles.facts}>
        <Fact term="admin.group.description">{group.description ?? t('admin.group.noDescription')}</Fact>
        <Fact term="admin.group.members">{formatCount(group.members)}</Fact>
        <Fact term="admin.group.folders">{formatCount(group.folders)}</Fact>
        {mapped && <Fact term="admin.group.mappedBy">{mappersOf(group)}</Fact>}
      </dl>
      <div className={groupStyles.notes}>
        {group.admin && <p className={styles.note}>{t('admin.group.adminNote')}</p>}
        {isEveryone(group) && <p className={styles.note}>{t('admin.group.everyoneNote')}</p>}
        {isGuest(group) && <p className={styles.note}>{t('admin.group.guestNote')}</p>}
        {mapped && <p className={styles.note}>{plural('admin.group.mappedNote', group.mappedBy.length, { providers: mappersOf(group), name: group.name })}</p>}
      </div>
      <div className={groupStyles.members}>
        <MembersPanel group={group} />
      </div>
      {open === 'rename' && <RenameGroupDialog group={group} onClose={close} />}
      {open === 'description' && <GroupDescriptionDialog group={group} onClose={close} />}
      {open === 'delete' && (
        <Confirm title={t('admin.deleteGroup.title', { name: group.name })} confirm={t('admin.deleteGroup.confirm')} run={remove} onClose={close}>
          <p className={styles.line}>{group.members > 0 ? plural('admin.deleteGroup.members', group.members) : t('admin.deleteGroup.nobody')}</p>
          <p className={styles.line}>{group.folders > 0 ? plural('admin.deleteGroup.folders', group.folders) : t('admin.deleteGroup.noFolders')}</p>
          <p className={styles.line}>{t('admin.deleteGroup.final')}</p>
        </Confirm>
      )}
    </div>
  )
}

/** No group at the address: in the app's words, with the way to the Groups list */
function GroupNotFound() {
  return (
    <div className={pageStyles.view}>
      <h1 className={pageStyles.title}>{t('admin.group.notFound.title')}</h1>
      <p className={pageStyles.line}>{t('admin.group.notFound.message')}</p>
      <Link to={routes.groups} className={pageStyles.link}>
        {t('admin.group.notFound.groups')}
      </Link>
    </div>
  )
}
