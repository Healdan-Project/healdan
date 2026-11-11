// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { apiPath, type components } from '@healdan/api'
import { t, toast } from '@healdan/ui'
import { fieldErrors, messageFor } from '../frame/errors'
import { invalidateDocumentLists } from '../actions/refresh'
import { keys } from '../queries'
import { useApi } from '../services'
import { FieldsForm, isEditable } from './FieldsForm'
import { Section } from './Section'

type S = components['schemas']
type Field = S['ProfileField']

export interface PropertiesFormProps {
  document: S['DocumentView']
  fields: Field[]
  onClose: () => void
}

/**
 * A document's properties as a form (spec 6.2): `FieldsForm` over what the server lists. Save sends only what changed,
 * and a 422 marks each field.
 */
export function PropertiesForm({ document, fields, onClose }: PropertiesFormProps) {
  const api = useApi()
  const queryClient = useQueryClient()

  async function save(values: Record<string, S['PropertyValue']>): Promise<Map<string, string> | void> {
    if (Object.keys(values).length === 0) return onClose()
    try {
      const saved = await api.send<{ items: Field[] }>('PATCH', apiPath`/v1/documents/${document.docNumber}/properties`, { values })
      queryClient.setQueryData(keys.properties(document.docNumber), saved)
      // The save is a new version, and the lists show its date and number
      await invalidateDocumentLists(queryClient)
      onClose()
    } catch (error) {
      const marked = fieldErrors(error)
      // A refusal that marks no field of the form (a hold, a permission) is a message of its own
      const names = new Set(fields.filter(isEditable).map((field) => field.name))
      if (![...marked.keys()].some((name) => names.has(name))) toast([...marked.values()][0] ?? messageFor(error))
      return marked
    }
  }

  return (
    <Section title={t('details.properties')}>
      <FieldsForm fields={fields} matter={document.matter} submitLabel={t('details.save')} onSubmit={save} onCancel={onClose} />
    </Section>
  )
}
