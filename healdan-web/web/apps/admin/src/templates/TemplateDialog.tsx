// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { fieldErrors, idFrom, useApi } from '@healdan/shell'
import { Button, Dialog, SelectField, TextField, toast } from '@healdan/ui'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { useTemplates, workspaceKeys } from '../workspace/queries'
import { nameProblem } from '../workspace/text'
import styles from '../clients/Clients.module.css'

type A = adminComponents['schemas']

export interface TemplateDialogProps {
  /** The template to rename; without one, the dialog makes a new one, empty or a copy */
  template?: A['TemplateInfo']
  /** The template as the server answered, made or renamed, just before the dialog closes; where to go next is the caller's */
  onSaved?: (template: A['TemplateInfo']) => void
  /** The dialog closes: Cancel, Esc, a click outside, or once saved. While a change is on its way it can't be. */
  onClose: () => void
}

/**
 * New Template (spec 8.5), empty or a copy of another, chosen from a menu as the templates are a short list (spec 7.2),
 * or renaming one. A name another template has, ignoring case, is said before the button, as the server would refuse
 * it, and so is a slash. A field the server names is said beside it, any other refusal in the rule's words. While the
 * change is on its way the dialog holds, as ClientDialog does.
 */
export function TemplateDialog({ template, onSaved, onClose }: TemplateDialogProps) {
  const isNew = template === undefined
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const templates = useTemplates().data ?? []
  const [name, setName] = useState(template?.name ?? '')
  const [copyOf, setCopyOf] = useState('')
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const trimmed = name.trim()
  const others = templates.filter((one) => one.id !== template?.id)
  const taken = others.find((one) => one.name.toLowerCase() === trimmed.toLowerCase())
  const fields = fieldErrors(failure)
  const nameError = (taken ? t('admin.templateDialog.taken', { name: taken.name }) : nameProblem(trimmed)) ?? fields.get('name')
  const refusal = failure !== undefined && !fields.has('name') ? refusalFor(failure) : undefined
  const ready = trimmed !== '' && nameError === undefined && (isNew || trimmed !== template.name)

  async function save() {
    setBusy(true)
    setFailure(undefined)
    try {
      const copy = idFrom(copyOf)
      const body: A['NewTemplate'] = copy === undefined ? { name: trimmed } : { name: trimmed, copyOf: copy }
      const saved = template
        ? await api.send<A['TemplateInfo']>('PATCH', apiPath`/v1/admin/templates/${template.id}`, { name: trimmed } satisfies A['FolderRename'])
        : await api.send<A['TemplateInfo']>('POST', '/v1/admin/templates', body)
      queryClient.setQueryData(workspaceKeys.template(saved.id), saved)
      void queryClient.invalidateQueries({ queryKey: workspaceKeys.templates, predicate: ({ queryKey }) => queryKey.length === 2 })
      // A template's name is its folder's
      void queryClient.invalidateQueries({ queryKey: workspaceKeys.tree(saved.id) })
      if (isNew) toast(t('admin.templateDialog.created', { name: saved.name }))
      onSaved?.(saved)
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t(isNew ? 'admin.templateDialog.newTitle' : 'admin.templateDialog.renameTitle')} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void save()
          }}
        >
          <TextField
            label={t('admin.templateDialog.name')}
            autoFocus
            required
            value={name}
            error={nameError}
            onChange={(value) => {
              setName(value)
              setFailure(undefined)
            }}
          />
          {isNew && (
            <>
              <SelectField
                label={t('admin.templateDialog.start')}
                value={copyOf}
                empty={t('admin.templateDialog.empty')}
                options={templates.map((one) => ({ value: String(one.id), label: t('admin.templateDialog.copy', { name: one.name }) }))}
                onChange={(value) => {
                  setCopyOf(value)
                  setFailure(undefined)
                }}
              />
              {copyOf !== '' && <p className={styles.note}>{t('admin.templateDialog.copyNote')}</p>}
            </>
          )}
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
              {t(isNew ? 'admin.templateDialog.create' : 'admin.templateDialog.rename')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
