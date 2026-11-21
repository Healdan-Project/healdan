// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, Dialog, TextField, toast } from '@healdan/ui'
import { PersonPicker } from '../pickers/PersonPicker'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { FOLDER_NAME_MAX, nameProblem } from '../workspace/text'
import { matterChanged } from './changed'
import styles from '../clients/Clients.module.css'

type A = adminComponents['schemas']

/** The longest description the server keeps (`MatterService`) */
const DESCRIPTION_MAX = 4000

export interface EditMatterDialogProps {
  matter: A['Matter']
  /** The dialog closes: Cancel, Esc, a click outside, or once saved. The caller stops drawing it. */
  onClose: () => void
}

/**
 * Edit (spec 8.3): the name, the description and the owner, the only things `MatterService.update` changes, and only
 * what changed is sent. The owner is found among the people who can sign in, as only they can own a matter. Problems the
 * form can see are said before Save; the rule's refusal is said in the dialog, and a field the server names beside it.
 * While the change is on its way the dialog holds, as ClientDialog does.
 */
export function EditMatterDialog({ matter, onClose }: EditMatterDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [name, setName] = useState(matter.name)
  const [description, setDescription] = useState(matter.description ?? '')
  const [owner, setOwner] = useState<A['PersonBrief'] | null>(matter.owner)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const trimmed = name.trim()
  const text = description.trim()
  const change: A['MatterChange'] = {}
  if (trimmed !== matter.name) change.name = trimmed
  if (text !== (matter.description ?? '').trim()) change.description = text === '' ? null : text
  if (owner !== null && owner.id !== matter.owner?.id) change.ownerId = owner.id

  const fields = fieldErrors(failure)
  // The folder's name is the number, a dash and the name ("1001.1 – Name"), which has to fit a folder's
  const nameError = nameProblem(trimmed, [], FOLDER_NAME_MAX - matter.number.length - 3) ?? fields.get('name')
  const descriptionError = text.length > DESCRIPTION_MAX ? t('admin.editMatter.descriptionTooLong') : fields.get('description')
  const ownerError = owner === null ? (matter.owner === null ? t('admin.editMatter.ownerGone') : t('fields.required')) : fields.get('ownerId')
  const refusal = failure !== undefined && !['name', 'description', 'ownerId'].some((field) => fields.has(field)) ? refusalFor(failure) : undefined
  const ready = Object.keys(change).length > 0 && trimmed !== '' && nameError === undefined && descriptionError === undefined && !(owner === null && matter.owner !== null)

  function edited<T>(set: (value: T) => void) {
    return (value: T) => {
      set(value)
      // A change takes the last refusal away
      setFailure(undefined)
    }
  }

  async function save() {
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await api.send<A['Matter']>('PATCH', apiPath`/v1/admin/matters/${matter.id}`, change)
      matterChanged(queryClient, saved)
      toast(t('admin.editMatter.saved', { matter: t('matter.label', { number: saved.number, name: saved.name }) }))
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t('admin.editMatter.title', { number: matter.number })} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void save()
          }}
        >
          <TextField label={t('admin.editMatter.name')} autoFocus required value={name} error={nameError} onChange={edited(setName)} />
          <TextField label={t('admin.editMatter.description')} multiline value={description} error={descriptionError} onChange={edited(setDescription)} />
          <PersonPicker label={t('admin.editMatter.owner')} accounts="enabled" required value={owner} error={ownerError} onChange={edited(setOwner)} />
          <p className={styles.note}>{t('admin.editMatter.fixed')}</p>
          {refusal !== undefined && (
            <p className={styles.refusal} role="alert">
              {refusal}
            </p>
          )}
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t('admin.editMatter.save')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
