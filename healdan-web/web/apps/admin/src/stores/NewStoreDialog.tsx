// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, Dialog, Highlight, Icon, SearchPicker, SelectField, TextField, toast } from '@healdan/ui'
import { isStorageUnavailable, useBackends } from '../storage/queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
// A Store's name is a folder's: the same rule, and the same words, as every other folder name the app takes
import { nameProblem } from '../workspace/text'
import formStyles from '../forms/Form.module.css'
import { storesChanged } from './queries'
import { backendLabel } from './text'
import { useGroupPicker } from './useGroupPicker'
import styles from './Stores.module.css'

type A = adminComponents['schemas']

export interface NewStoreDialogProps {
  /** The Store as the server answered, just before the dialog closes; what to do with it is the caller's */
  onSaved?: (store: A['Store']) => void
  /** The dialog closes: Cancel, Esc, a click outside, or once saved. The caller stops drawing it. */
  onClose: () => void
}

/**
 * New Store (spec 8.6): its name, who can see it, the groups each given Read, the one level a Store has (Can See), so a
 * matter can be made in it at once, and the backend it keeps its files on. The groups are a list to pick from, with a
 * search for a firm that has many; one added leaves the list, and Remove puts it back. With none, only administrators can
 * see the Store. Backend is a menu of the backends a Store can be given, Original first and chosen, and isn't asked on a
 * server that doesn't use storage backends; when the backends can't be read, it says the Store is made on Original and can
 * be given another backend later. What the form can see is wrong with the name is said before its button; the
 * server's refusal is said in the dialog, and a field it names beside it. Once made, every Store menu and the Stores list
 * ask for the Stores again, as the create answers only the new one's id and name (P3-R23).
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

  // The backends a Store can be given, Original first; none to choose from while storage backends are off, or the server
  // has no storage module (its 503 is said nowhere here: the Store is made on Original), or the backends couldn't be read
  // (which is said, as the Store is made on Original then too, and can be moved)
  const overview = useBackends()
  const unread = overview.data === undefined && overview.isError && !isStorageUnavailable(overview.error)
  const offered = overview.data?.on === true ? overview.data.backends.filter((backend) => backend.offered) : []
  const original = offered.find((backend) => backend.original) ?? offered[0]
  const [backendId, setBackendId] = useState<number>()
  const backend = offered.find((one) => one.id === backendId) ?? original

  const groups = useGroupPicker()

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
      const body: A['NewStore'] = { name: trimmed, groupIds: chosen.map((group) => group.id) }
      // Original needs no naming: it's where a Store keeps its files unless another is named
      if (backend !== undefined && !backend.original) body.backendId = backend.id
      const store = await api.send<A['Store']>('POST', '/v1/admin/stores', body)
      // The list's rows carry far more than the answer, so none is made up from it: the menus and the list ask again
      storesChanged(queryClient)
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
            onPick={add}
            leaveOut={(group) => chosenIds.has(group.id)}
            inputRef={addField}
          />
          {backend !== undefined && (
            <div className={formStyles.field}>
              <SelectField
                label={t('admin.newStore.backend')}
                required
                value={String(backend.id)}
                options={offered.map((one) => ({ value: String(one.id), label: backendLabel(one) }))}
                onChange={(value) => {
                  setBackendId(Number(value))
                  change()
                }}
              />
              <p className={formStyles.note}>{t('admin.newStore.backendNote')}</p>
            </div>
          )}
          {unread && <p className={formStyles.note}>{t('admin.newStore.backendsUnread')}</p>}
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
