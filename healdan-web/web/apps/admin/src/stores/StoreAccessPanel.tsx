// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Highlight, Icon, SearchPicker, toast } from '@healdan/ui'
import { plural, t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { useEditState } from '../workspace/edits'
import { andList } from '../workspace/text'
import workspace from '../workspace/Workspace.module.css'
import { storeKeys, storesChanged, useStoreAccess } from './queries'
import { useGroupPicker } from './useGroupPicker'
import styles from './Stores.module.css'

type A = adminComponents['schemas']

/** One who can see the Store: a group, or a person's own group, and whether its row grants more than Can See */
interface Reader {
  groupId: number
  name: string
  person: boolean
  custom: boolean
}

/** Who can see it, as the server's rows say: those whose row grants Read, as only Read lets anyone see a Store */
function readersOf(access: A['StoreAccess']): Reader[] {
  return access.rows
    .filter((row) => row.permissions.includes('read'))
    .map((row) => ({ groupId: row.groupId, name: row.name, person: row.person, custom: row.level === 'custom' }))
}

/**
 * The rows that grant something on the Store but not Read: they can't see it, so the list doesn't show them, and Save,
 * which keeps only the rows of those it sends, removes theirs, unless one is added back
 */
function unlistedOf(access: A['StoreAccess']): A['AccessRow'][] {
  return access.rows.filter((row) => row.permissions.length > 0 && !row.permissions.includes('read'))
}

function sameReaders(one: readonly Reader[], other: readonly Reader[]): boolean {
  const ids = new Set(one.map((reader) => reader.groupId))
  return one.length === other.length && other.every((reader) => ids.has(reader.groupId))
}

/** Where the focus goes once the last reader is removed, or Discard Changes goes: Add a Group */
const ADD = 'add'

/**
 * Who Can See It, on a Store's page (spec 8.6): each group and person that can see the Store, its one level, Can See, and
 * Custom for a row with more, which Save keeps. Groups are added from a search, as in New Store, and anyone is removed;
 * Save sends who can see it from then on (`PUT /stores/{id}/access`), and Discard Changes puts back what the server has.
 * The server refuses to shut out a group that can read one of the Store's matters (P2), and its refusal is said beside
 * Save in the rule's words, the change kept to put right. While the save is on its way the list is held. Only groups are
 * added (P3-R30): a person's row already there is kept, and can be removed. A row that grants something but not Read
 * isn't listed, as it can't see the Store, so a line names each, as Save removes them. It tells the page where it stands
 * (useEditState), so leaving the page over a change asks first.
 */
export function StoreAccessPanel({ store }: { store: Pick<A['StoreInfo'], 'id' | 'name'> }) {
  const headingId = useId()
  const asked = useStoreAccess(store.id)
  return (
    <section aria-labelledby={headingId} className={workspace.section}>
      <h2 id={headingId} className={workspace.sectionTitle}>
        {t('admin.storeAccess.title')}
      </h2>
      {asked.isError ? (
        <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
      ) : asked.data === undefined ? (
        <p className={workspace.note}>{t('common.loading')}</p>
      ) : (
        <AccessEditor store={store} access={asked.data} />
      )}
    </section>
  )
}

function AccessEditor({ store, access }: { store: Pick<A['StoreInfo'], 'id' | 'name'>; access: A['StoreAccess'] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const groups = useGroupPicker()
  // Who can see it, being changed; null while it's the server's
  const [draft, setDraft] = useState<Reader[] | null>(null)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  // Where the focus goes once a reader is removed, and its Remove with it: the next one's Remove, or Add a Group
  const removeButtons = useRef(new Map<number, HTMLButtonElement>())
  const addField = useRef<HTMLInputElement>(null)
  const refocus = useRef<number | typeof ADD | undefined>(undefined)

  const original = readersOf(access)
  const unlisted = unlistedOf(access)
  const readers = draft ?? original
  const dirty = draft !== null && !sameReaders(draft, original)
  const taken = new Set(readers.map((reader) => reader.groupId))
  // Those Save would remove: one added back is given Read, keeping what else it has
  const removed = unlisted.filter((row) => !taken.has(row.groupId)).map((row) => row.name)
  // The page asks before it's left over a change unsaved, or a save on its way
  useEditState('access', dirty, busy)

  // Once the list is drawn without the one removed, or Discard Changes has gone
  useEffect(() => {
    const key = refocus.current
    if (key === undefined) return
    refocus.current = undefined
    ;(key === ADD ? addField.current : removeButtons.current.get(key))?.focus()
  })

  function change(next: Reader[]) {
    setDraft(next)
    setFailure(undefined)
  }

  function remove(reader: Reader) {
    const index = readers.findIndex((one) => one.groupId === reader.groupId)
    const rest = readers.filter((one) => one.groupId !== reader.groupId)
    refocus.current = rest[index]?.groupId ?? ADD
    change(rest)
  }

  async function save() {
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await api.send<A['StoreAccess']>('PUT', apiPath`/v1/admin/stores/${store.id}/access`, { groupIds: readers.map((reader) => reader.groupId) } satisfies A['StoreAccessChange'])
      // A read still on its way, as on a return to the window, began before the save, so it's called off rather than land after it
      await queryClient.cancelQueries({ queryKey: storeKeys.access(store.id), exact: true })
      queryClient.setQueryData(storeKeys.access(store.id), saved)
      setDraft(null)
      // Who can read it, as the list and the page's facts say
      storesChanged(queryClient, store.id)
      toast(t('admin.storeAccess.saved', { name: store.name }))
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <p className={workspace.note}>{t('admin.storeAccess.about')}</p>
      {/* Held while a save is on its way, so nothing changed meanwhile is dropped under its Saved */}
      <fieldset disabled={busy} className={workspace.hold}>
        {readers.length === 0 ? (
          <p className={workspace.note}>{t('admin.newStore.nobody')}</p>
        ) : (
          <ul aria-label={t('admin.storeAccess.list', { name: store.name })} className={styles.chosen}>
            {readers.map((reader) => (
              <li key={reader.groupId} className={styles.group}>
                <span className={`${styles.groupName} ${workspace.who}`}>
                  <Icon name={reader.person ? 'user' : 'usersGroup'} size={14} />
                  {reader.name}
                  {reader.custom && (
                    <>
                      {' '}
                      <span className={workspace.chip}>{t('admin.storeAccess.custom')}</span>
                    </>
                  )}
                </span>
                <Button
                  variant="quiet"
                  isDisabled={busy}
                  aria-label={t('admin.newStore.remove', { name: reader.name })}
                  ref={(button) => {
                    if (button) removeButtons.current.set(reader.groupId, button)
                    else removeButtons.current.delete(reader.groupId)
                  }}
                  onPress={() => remove(reader)}
                >
                  <Icon name="close" size={14} />
                </Button>
              </li>
            ))}
          </ul>
        )}
        {readers.some((reader) => reader.custom) && <p className={workspace.note}>{t('admin.storeAccess.customNote')}</p>}
        {removed.length > 0 && <p className={workspace.note}>{plural('admin.storeAccess.unlisted', removed.length, { names: andList(removed) })}</p>}
        <SearchPicker<A['Group']>
          label={t('admin.newStore.add')}
          placeholder={t('admin.newStore.find')}
          hint={t('admin.newStore.hint')}
          initialHeading={t('admin.newStore.groups')}
          initial={groups.initial}
          search={groups.search}
          id={(group) => String(group.id)}
          render={(group, query) => (
            <span className={styles.option}>
              <Highlight text={group.name} query={query} />
              {group.description && (
                <>
                  {' '}
                  <span className={styles.description}>
                    <Highlight text={group.description} query={query} />
                  </span>
                </>
              )}
            </span>
          )}
          onPick={(group) => {
            if (!taken.has(group.id)) change([...readers, { groupId: group.id, name: group.name, person: false, custom: false }])
          }}
          leaveOut={(group) => taken.has(group.id)}
          inputRef={addField}
        />
      </fieldset>
      <div className={workspace.buttons}>
        <span className={`${workspace.buttons} ${workspace.push}`}>
          {dirty && (
            <Button
              isDisabled={busy}
              onPress={() => {
                setDraft(null)
                setFailure(undefined)
                // It goes as the change does, and Save is greyed out with it
                refocus.current = ADD
              }}
            >
              {t('admin.storeAccess.discard')}
            </Button>
          )}
          <Button variant="default" isDisabled={!dirty} isPending={busy} onPress={() => void save()}>
            {t('admin.storeAccess.save')}
          </Button>
        </span>
      </div>
      {failure !== undefined && (
        <p className={workspace.problem} role="alert">
          {refusalFor(failure)}
        </p>
      )}
    </>
  )
}
