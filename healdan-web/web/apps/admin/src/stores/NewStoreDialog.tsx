// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, Dialog, Highlight, Icon, SearchPicker, TextField, toast } from '@healdan/ui'
import { adminKey, adminKeys, useAdminQuery } from '../queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import formStyles from '../forms/Form.module.css'
import styles from './Stores.module.css'

type A = adminComponents['schemas']

/** The longest name a folder takes (`FolderName`'s `too-long`), in UTF-16 units as Java counts them */
const NAME_MAX = 255
/** The groups offered while nothing is typed, the first by name: enough for most firms' every group */
const GROUPS_SHOWN = 50
/** The groups offered for what is typed */
const GROUPS_FOUND = 20

/** What the form can see is wrong with a Store's name, in the words `FolderName`'s 422 would get, or undefined */
function nameProblem(trimmed: string): string | undefined {
  if (trimmed.length > NAME_MAX) return t('fields.too-long')
  if (/[/\\]/.test(trimmed)) return t('fields.invalid')
  return undefined
}

/** Stores as the menus list them, by name, in any case */
function byName(one: A['Store'], other: A['Store']): number {
  return one.name.localeCompare(other.name, 'en-US', { sensitivity: 'base' })
}

export interface NewStoreDialogProps {
  /** The Store as the server answered, just before the dialog closes; what to do with it is the caller's */
  onSaved?: (store: A['Store']) => void
  /** The dialog closes: Cancel, Esc, a click outside, or once saved. The caller stops drawing it. */
  onClose: () => void
}

/**
 * New Store (spec 8.6): its name, and who can see it, the groups each given Read, the one level a Store has (Can See), so
 * a matter can be made in it at once. The groups are a list to pick from, with a search for a firm that has many; one
 * added leaves the list, and Remove puts it back. With none, only administrators can see the Store. A new Store keeps its
 * files on Original until its storage backend is changed (the plan's Spec Correction 1: no backend field yet). What the
 * form can see is wrong with the name is said before its button; the server's refusal is said in the dialog, and a field
 * it names beside it. Once made, every Store menu has it at once.
 */
export function NewStoreDialog({ onSaved, onClose }: NewStoreDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const whoId = useId()
  const [name, setName] = useState('')
  const [chosen, setChosen] = useState<A['Group'][]>([])
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  // Where the focus goes once a group is removed and its button with it: the next group's Remove, or Add a Group
  const removeButtons = useRef(new Map<number, HTMLButtonElement>())
  const addField = useRef<HTMLInputElement>(null)
  const refocus = useRef<number | 'add' | undefined>(undefined)

  const listed = useAdminQuery('/v1/admin/groups', { query: { limit: GROUPS_SHOWN } })
  const search = useCallback(
    async (q: string) => {
      const query = { q, limit: GROUPS_FOUND }
      const page = await queryClient.fetchQuery({
        queryKey: adminKey('/v1/admin/groups', query),
        queryFn: ({ signal }) => api.get<A['GroupList']>('/v1/admin/groups', query, { signal }),
        // A key back and forth needn't ask twice
        staleTime: 30_000,
      })
      return page.items ?? []
    },
    [api, queryClient],
  )

  // Once the list is drawn without the group removed
  useEffect(() => {
    if (refocus.current === undefined) return
    ;(refocus.current === 'add' ? addField.current : removeButtons.current.get(refocus.current))?.focus()
    refocus.current = undefined
  })

  const trimmed = name.trim()
  const fields = fieldErrors(failure)
  const nameError = nameProblem(trimmed) ?? fields.get('name')
  // A refusal that names no field of the form is said above its buttons, in the rule's words or the catalogue's
  const refusal = failure !== undefined && !fields.has('name') ? refusalFor(failure) : undefined
  const ready = trimmed !== '' && nameProblem(trimmed) === undefined
  const chosenIds = new Set(chosen.map((group) => group.id))

  function change() {
    // A change takes the last refusal away
    setFailure(undefined)
  }

  function add(group: A['Group']) {
    if (chosenIds.has(group.id)) return
    setChosen([...chosen, group])
    change()
  }

  function remove(group: A['Group']) {
    const index = chosen.findIndex((one) => one.id === group.id)
    const rest = chosen.filter((one) => one.id !== group.id)
    setChosen(rest)
    refocus.current = rest[index]?.id ?? 'add'
    change()
  }

  async function save() {
    setBusy(true)
    setFailure(undefined)
    try {
      const store = await api.send<A['Store']>('POST', '/v1/admin/stores', { name: trimmed, groupIds: chosen.map((group) => group.id) } satisfies A['NewStore'])
      // Every Store menu has it at once, then asks the server again
      queryClient.setQueryData<A['Store'][]>(adminKeys.stores, (stores) => stores && [...stores.filter((one) => one.id !== store.id), store].sort(byName))
      void queryClient.invalidateQueries({ queryKey: adminKeys.stores })
      toast(t('admin.newStore.created', { name: store.name }))
      onSaved?.(store)
      // Not through the dialog's own close, which is held while busy
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    // Held while Create Store is on its way, by Cancel, Esc or a click outside alike: the Store would still be made
    <Dialog title={t('admin.newStore.title')} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void save()
          }}
        >
          <TextField
            label={t('admin.newStore.name')}
            autoFocus
            required
            value={name}
            error={nameError}
            onChange={(value) => {
              setName(value)
              change()
            }}
          />
          <div className={styles.who}>
            <p id={whoId} className={styles.label}>
              {t('admin.newStore.who')}
            </p>
            {chosen.length === 0 ? (
              <p className={formStyles.note}>{t('admin.newStore.nobody')}</p>
            ) : (
              <ul aria-labelledby={whoId} className={styles.chosen}>
                {chosen.map((group) => (
                  <li key={group.id} className={styles.group}>
                    <span className={styles.groupName}>{group.name}</span>
                    <Button
                      variant="quiet"
                      aria-label={t('admin.newStore.remove', { name: group.name })}
                      ref={(button) => {
                        if (button) removeButtons.current.set(group.id, button)
                        else removeButtons.current.delete(group.id)
                      }}
                      onPress={() => remove(group)}
                    >
                      <Icon name="close" size={14} />
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <SearchPicker<A['Group']>
            label={t('admin.newStore.add')}
            placeholder={t('admin.newStore.find')}
            hint={t('admin.newStore.hint')}
            initialHeading={t('admin.newStore.groups')}
            initial={listed.data?.items ?? []}
            search={search}
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
            onPick={add}
            leaveOut={(group) => chosenIds.has(group.id)}
            inputRef={addField}
          />
          <p className={formStyles.note}>{t('admin.newStore.backend')}</p>
          {refusal !== undefined && (
            <p className={formStyles.refusal} role="alert">
              {refusal}
            </p>
          )}
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t('admin.newStore.create')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
