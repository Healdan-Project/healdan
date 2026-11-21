// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, Dialog, TextField, toast } from '@healdan/ui'
import { useStores } from '../queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { nameProblem } from '../workspace/text'
import formStyles from '../forms/Form.module.css'
import { storeKeys, storesChanged } from './queries'

type A = adminComponents['schemas']

export interface RenameStoreDialogProps {
  store: A['StoreInfo']
  /** The dialog closes: Cancel, Esc, a click outside, or once renamed. While the rename is on its way it can't be. */
  onClose: () => void
}

/**
 * Rename (spec 8.6): the Store's name alone, its matters keeping theirs. A name another Store has, ignoring case, is said
 * before the button, as the server would refuse it, and so is a slash; the server's other refusals, a name LogicalDOC's
 * Default workspace or the Templates Store has among them, are said in the rule's words. Changing only the case of its
 * own name renames it. While the rename is on its way the dialog holds.
 */
export function RenameStoreDialog({ store, onClose }: RenameStoreDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const others = (useStores().data ?? []).filter((one) => one.id !== store.id)
  const [name, setName] = useState(store.name)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const trimmed = name.trim()
  const lower = trimmed.toLowerCase()
  const taken = others.find((one) => one.name.toLowerCase() === lower)
  const fields = fieldErrors(failure)
  const nameError = (taken ? t('admin.renameStore.taken', { name: taken.name }) : nameProblem(trimmed)) ?? fields.get('name')
  const refusal = failure !== undefined && !fields.has('name') ? refusalFor(failure) : undefined
  const ready = trimmed !== '' && nameError === undefined && trimmed !== store.name

  async function rename() {
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await api.send<A['StoreInfo']>('PATCH', apiPath`/v1/admin/stores/${store.id}`, { name: trimmed } satisfies A['FolderRename'])
      queryClient.setQueryData(storeKeys.store(saved.id), saved)
      // Every Store menu names it, and so do the matters' rows and pages
      storesChanged(queryClient, undefined, { matters: true })
      toast(t('admin.renameStore.done', { before: store.name, after: saved.name }))
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t('admin.renameStore.title', { name: store.name })} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void rename()
          }}
        >
          <TextField
            label={t('admin.renameStore.name')}
            autoFocus
            required
            value={name}
            error={nameError}
            onChange={(value) => {
              setName(value)
              setFailure(undefined)
            }}
          />
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
              {t('admin.renameStore.rename')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
