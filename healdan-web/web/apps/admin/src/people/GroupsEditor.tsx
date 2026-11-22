// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, toast } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { GroupsField, type GroupsFieldItem } from '../pickers/GroupsField'
import { useAdminQuery } from '../queries'
import { t } from '../strings'
import { useEditState } from '../workspace/edits'
import { AddAdminQuestion } from './AddAdminQuestion'
import { peopleChanged, peopleKeys } from './queries'
import { EVERYONE, GUEST, isAdminGroup, providerOf } from './text'
import styles from './People.module.css'

type A = adminComponents['schemas']

/** The groups read for what each one's provider maps: every group, as a firm has far fewer than this */
const GROUPS_MAPPED = 200

function sameGroups(one: readonly A['GroupBrief'][], other: readonly A['GroupBrief'][]): boolean {
  const ids = new Set(one.map((group) => group.id))
  return one.length === other.length && other.every((group) => ids.has(group.id))
}

/**
 * A person's groups, on their page (spec 8.7): each group they're in, groups added from a search and any removed, and
 * Save, which sends the groups they're to be in (`PUT /users/{id}/groups`), or Discard Changes. Save asks first when it
 * adds someone to `admin`, saying they'll read every document. What can't be taken away has no Remove and says why: Everyone
 * for an account that isn't read-only, `guest` for one that is, and `admin` while the server would refuse it
 * (`refusals.leaveAdmin`). A read-only account is offered no group to add. For an account linked to a sign-in provider,
 * each group the provider's group map names says the map sets it again at their next sign-in. The server's refusal is
 * said beside Save, the change kept to put right. It tells the page where it stands (useEditState), so leaving the page
 * over a change asks first.
 */
export function GroupsEditor({ person }: { person: A['User'] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  // The groups being changed; null while they're the server's
  const [draft, setDraft] = useState<A['GroupBrief'][] | null>(null)
  const [mapped, setMapped] = useState(new Map<number, string[]>())
  const [asking, setAsking] = useState(false)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  // Where the focus goes once Discard Changes goes: Add a Group
  const addField = useRef<HTMLInputElement>(null)
  const refocus = useRef(false)
  const provider = providerOf(person)
  // What each group's provider maps, for an account linked to one: the groups read once, and each one picked
  const known: A['Group'][] | undefined = useAdminQuery('/v1/admin/groups', { query: { limit: GROUPS_MAPPED }, enabled: provider !== undefined, staleTime: 60_000 }).data?.items

  const groups = draft ?? person.groups
  const dirty = draft !== null && !sameGroups(draft, person.groups)
  useEditState('groups', dirty, busy)

  // Once Discard Changes has gone
  useEffect(() => {
    if (!refocus.current) return
    refocus.current = false
    addField.current?.focus()
  })

  function mappedBy(id: number): string[] {
    return mapped.get(id) ?? known?.find((group) => group.id === id)?.mappedBy ?? []
  }

  function itemOf(group: A['GroupBrief']): GroupsFieldItem {
    const admin = isAdminGroup(group)
    // Only while it's one of their groups on the server: one just added can be taken away again before Save
    const inAdmin = admin && person.groups.some((one) => one.id === group.id)
    const locked = person.readOnly
      ? group.name === GUEST
        ? t('admin.userGroups.guest')
        : undefined
      : group.name === EVERYONE
        ? t('admin.userGroups.everyone')
        : inAdmin && person.refusals.leaveAdmin !== null
          ? person.refusals.leaveAdmin
          : undefined
    const sets = provider !== undefined && mappedBy(group.id).some((label) => label.toLowerCase() === provider.label.toLowerCase())
    return {
      id: group.id,
      name: group.name,
      locked,
      tag: admin ? <span className={styles.warning}>{t('admin.userGroups.readsEverything')}</span> : undefined,
      note: sets ? t('admin.userGroups.mapped', { provider: provider.label }) : undefined,
    }
  }

  function change(next: A['GroupBrief'][]) {
    setDraft(next)
    setFailure(undefined)
  }

  function add(group: A['Group']) {
    setMapped((before) => new Map(before).set(group.id, group.mappedBy))
    if (!groups.some((one) => one.id === group.id)) change([...groups, { id: group.id, name: group.name }])
  }

  /** Save: adding them to `admin` asks first */
  function save() {
    const joins = groups.some(isAdminGroup) && !person.groups.some(isAdminGroup)
    if (joins) setAsking(true)
    else void send()
  }

  async function send() {
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await api.send<A['User']>('PUT', apiPath`/v1/admin/users/${person.id}/groups`, { groupIds: groups.map((group) => group.id) } satisfies A['UserGroups'])
      // A read still on its way, as on a return to the window, began before the save, so it's called off rather than land after it
      await queryClient.cancelQueries({ queryKey: peopleKeys.user(person.id), exact: true })
      peopleChanged(queryClient, saved)
      setDraft(null)
      toast(t('admin.userGroups.saved', { name: saved.name }))
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {/* Held while a save is on its way, so nothing changed meanwhile is dropped under its Saved */}
      <fieldset disabled={busy} className={styles.hold}>
        <GroupsField
          listLabel={t('admin.userGroups.list', { name: person.name })}
          items={groups.map(itemOf)}
          empty={t('admin.userGroups.none')}
          hint={t('admin.userGroups.hint')}
          adding={!person.readOnly}
          isDisabled={busy}
          onAdd={add}
          onRemove={(item) => change(groups.filter((group) => group.id !== item.id))}
          inputRef={addField}
        >
          {person.readOnly && <p className={styles.note}>{t('admin.userGroups.readOnly')}</p>}
          {provider !== undefined && <p className={styles.note}>{t('admin.userGroups.provider', { name: person.name, provider: provider.label })}</p>}
        </GroupsField>
      </fieldset>
      {!person.readOnly && (
        <div className={styles.buttons}>
          {dirty && (
            <Button
              isDisabled={busy}
              onPress={() => {
                setDraft(null)
                setFailure(undefined)
                // It goes as the change does, and Save is greyed out with it
                refocus.current = true
              }}
            >
              {t('admin.userGroups.discard')}
            </Button>
          )}
          <Button variant="default" isDisabled={!dirty} isPending={busy} onPress={save}>
            {t('admin.userGroups.save')}
          </Button>
        </div>
      )}
      <Refusal failure={failure} />
      {asking && <AddAdminQuestion name={person.name} onConfirm={() => void send()} onClose={() => setAsking(false)} />}
    </>
  )
}
