// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { components } from '@healdan/api'
import { Dialog, plural, t, TextField } from '@healdan/ui'
import { FieldsForm, isEditable } from '../details/FieldsForm'
import styles from './filing.module.css'
import { named, type FilingFile } from './uploads'

type S = components['schemas']

export interface FilingFormProps {
  files: File[]
  /** The folder's form: the properties a new document filed into it would have */
  fields: S['ProfileField'][]
  onFile: (files: FilingFile[], properties?: Record<string, S['PropertyValue']>) => void
  onCancel: () => void
}

/**
 * The form that comes first when a required property has no default, or Option was held (spec 6.5): the files with a
 * title each, and one form of properties for them all, which is the properties form of a document (`FieldsForm`)
 * made from the folder's. Its button says how many it will file.
 */
export function FilingForm({ files, fields, onFile, onCancel }: FilingFormProps) {
  const [titles, setTitles] = useState(() => named(files).map((one) => one.name))
  const [errors, setErrors] = useState<Set<number>>(() => new Set())

  async function submit(values: Record<string, S['PropertyValue']>): Promise<Map<string, string> | void> {
    // A title can't be empty, and a required property needs a value from the folder's or from the person
    const empty = new Set(titles.flatMap((title, i) => (title.trim() === '' ? [i] : [])))
    const missing = fields.filter((field) => isEditable(field) && field.required && !field.value && (values[field.name] ?? null) === null)
    if (empty.size > 0 || missing.length > 0) {
      setErrors(empty)
      return new Map(missing.map((field) => [field.name, t('fields.required')]))
    }
    onFile(
      files.map((file, i) => ({ file, name: titles[i]!.trim() })),
      Object.keys(values).length > 0 ? values : undefined,
    )
  }

  return (
    <Dialog title={t('filing.formTitle')} isOpen onOpenChange={(open) => !open && onCancel()}>
      <FieldsForm fields={fields} submitLabel={plural('filingButton', files.length)} onSubmit={submit} onCancel={onCancel}>
        <fieldset className={styles.titles}>
          <legend className={styles.legend}>{t('filing.titles')}</legend>
          {files.map((file, i) => (
            <TextField
              key={i}
              label={t('filing.fileTitle', { name: file.name })}
              value={titles[i] ?? ''}
              error={errors.has(i) ? t('fields.required') : undefined}
              onChange={(value) => {
                setTitles((now) => now.map((title, j) => (j === i ? value : title)))
                setErrors((now) => new Set([...now].filter((j) => j !== i)))
              }}
            />
          ))}
        </fieldset>
      </FieldsForm>
    </Dialog>
  )
}
