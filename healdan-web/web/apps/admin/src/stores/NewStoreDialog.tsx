// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, Dialog, SelectField, TextField, toast } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { GroupsField } from '../pickers/GroupsField'
import { isStorageUnavailable, useBackends } from '../storage/queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
// A Store's name is a folder's: the same rule, and the same words, as every other folder name the app takes
import { nameProblem } from '../workspace/text'
import formStyles from '../forms/Form.module.css'
import { storesChanged } from './queries'
import { backendLabel } from './text'

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
 * be given another backend later; a Store made that couldn't then be given the backend chosen is on Original, and the
 * toast says so. What the form can see is wrong with the name is said before its button; the
 * server's refusal is said in the dialog, and a field it names beside it. Once made, every Store menu and the Stores list
 * ask for the Stores again, as the create answers only the new one's id and name (P3-R23).
 */
export function NewStoreDialog({ onSaved, onClose }: NewStoreDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [name, setName] = useState('')
  const [chosen, setChosen] = useState<A['Group'][]>([])
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  // The backends a Store can be given, Original first; none to choose from while storage backends are off, or the server
  // has no storage module (its 503 is said nowhere here: the Store is made on Original), or the backends couldn't be read
  // (which is said, as the Store is made on Original then too, and can be moved)
  const overview = useBackends()
  const unread = overview.data === undefined && overview.isError && !isStorageUnavailable(overview.error)
  const offered = overview.data?.on === true ? overview.data.backends.filter((backend) => backend.offered) : []
  const original = offered.find((backend) => backend.original) ?? offered[0]
  const [backendId, setBackendId] = useState<number>()
  const backend = offered.find((one) => one.id === backendId) ?? original

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

  function remove(id: number) {
    setChosen(chosen.filter((one) => one.id !== id))
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
      // Made, but the backend chosen couldn't be given it, as when it was removed meanwhile: it's on Original, where its
      // files would go, so that's said
      const elsewhere = backend !== undefined && body.backendId !== undefined && store.backendId !== body.backendId
      toast(elsewhere ? t('admin.newStore.createdOnOriginal', { name: store.name, backend: backend.name }) : t('admin.newStore.created', { name: store.name }))
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
          <GroupsField
            label={t('admin.newStore.who')}
            items={chosen}
            empty={t('admin.newStore.nobody')}
            hint={t('admin.newStore.hint')}
            onAdd={add}
            onRemove={(item) => remove(item.id)}
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
          <Refusal>{refusal}</Refusal>
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
