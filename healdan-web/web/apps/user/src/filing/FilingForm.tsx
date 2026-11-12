// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { components } from '@healdan/api'
import { Dialog, plural, t, TextField } from '@healdan/ui'
import { FieldsForm } from '../details/FieldsForm'
import { missingRequired, withUntickedAsNo } from './required'
import styles from './filing.module.css'
import { named, type FilingFile } from './uploads'

type S = components['schemas']

/** An email's title is its subject, which the server takes from the message whatever is sent */
const isEmail = (name: string) => /\.(eml|msg)$/i.test(name)

export interface FilingFormProps {
  files: File[]
  /** The folder's form: the properties a new document filed into it would have */
  fields: S['ProfileField'][]
  /** The matter of the folder they go into, which Client and Matter show as the properties form of a document does */
  matter?: S['DocumentView']['matter']
  onFile: (files: FilingFile[], properties?: Record<string, S['PropertyValue']>) => void
  onCancel: () => void
}

/**
 * The form that comes first when a required property has no default, or Option was held (spec 6.5): the files with a
 * title each, and one form of properties for them all, which is the properties form of a document (`FieldsForm`)
 * made from the folder's. Its button says how many it will file.
 */
export function FilingForm({ files, fields, matter, onFile, onCancel }: FilingFormProps) {
  const [titles, setTitles] = useState(() => named(files).map((one) => one.name))
  const [errors, setErrors] = useState<Set<number>>(() => new Set())

  async function submit(changed: Record<string, S['PropertyValue']>): Promise<Map<string, string> | void> {
    // An unticked yes-or-no is No, which is a value
    const values = withUntickedAsNo(fields, changed)
    // A title can't be empty, and a required property needs a value from the folder's or from the person
    const empty = new Set(titles.flatMap((title, i) => (title.trim() === '' && !isEmail(files[i]!.name) ? [i] : [])))
    const missing = missingRequired(fields, values)
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
      <FieldsForm fields={fields} matter={matter} submitLabel={plural('filingButton', files.length)} onSubmit={submit} onCancel={onCancel}>
        <fieldset className={styles.titles}>
          <legend className={styles.legend}>{t('filing.titles')}</legend>
          {files.map((file, i) =>
            isEmail(file.name) ? (
              <p key={i} className={styles.file}>
                <span>{file.name}</span> <span>{t('filing.fromSubject')}</span>
              </p>
            ) : (
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
            ),
          )}
        </fieldset>
      </FieldsForm>
    </Dialog>
  )
}
