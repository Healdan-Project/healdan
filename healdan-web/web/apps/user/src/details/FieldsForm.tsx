// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState, type FormEvent, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { Button, CheckField, DatePicker, SelectField, t, TextField } from '@healdan/ui'
import { textOf } from './Properties'
import styles from './details.module.css'

type S = components['schemas']
type Field = S['ProfileField']

// Client and Matter come from the matter, and the email fields from the message: never edited here, whatever `editable` says
const NEVER = new Set(['client', 'matter', 'emailfrom', 'emailto', 'emailcc', 'emailsubject', 'emailsent', 'emailreceived'])
const FORM_TYPES = new Set<Field['type']>(['string', 'integer', 'decimal', 'date', 'boolean'])

export const isEditable = (field: Field) => field.editable && !NEVER.has(field.name) && FORM_TYPES.has(field.type)

/** What the form shows for a field, as text: the day of a date (its UTC day, as `textOf` shows it), `true` or `false` for a yes or no */
function draftOf(field: Field): string {
  const value = field.value
  if (field.type === 'boolean') return value === true ? 'true' : 'false'
  if (value === null || value === undefined) return ''
  if (field.type === 'date') return typeof value === 'string' ? value.slice(0, 10) : ''
  return typeof value === 'string' || typeof value === 'number' ? String(value) : ''
}

const isNumber = (field: Field) => field.type === 'integer' || field.type === 'decimal'

/** Whether what was typed into a number field is a number of its kind: a whole number for an integer */
function numberOk(field: Field, draft: string): boolean {
  const number = Number(draft)
  return draft.trim() !== '' && Number.isFinite(number) && (field.type !== 'integer' || Number.isInteger(number))
}

/** What a changed field sends: null to clear it, or to follow the folder again */
function valueOf(field: Field, draft: string): S['PropertyValue'] {
  if (field.type === 'boolean') return draft === 'true'
  if (draft.trim() === '') return null
  if (isNumber(field)) return Number(draft)
  return field.type === 'date' ? `${draft}T00:00:00Z` : draft
}

export interface FieldsFormProps {
  /** The properties the server lists, for a document or for a new one (the folder's form) */
  fields: Field[]
  /** The document's matter, which Client and Matter show; without it they show the properties' own text */
  matter?: S['DocumentView']['matter']
  submitLabel: string
  /** Shown above the fields, inside the form */
  children?: ReactNode
  /** Gets only what changed, by property name; gives back the fields the server refused, by name, to mark them */
  onSubmit: (values: Record<string, S['PropertyValue']>) => Promise<Map<string, string> | void>
  onCancel: () => void
  /** True while something outside the form is working */
  busy?: boolean
}

/**
 * The form a document's properties (`PropertiesForm`) and the filing of new documents (`FilingForm`) are both made of
 * (spec 6.2, 6.5), made from what the server lists, so the firm's own properties are in it: a property picked from a
 * list offers only its options (a Subclass only its Class's), a required one is marked, and Client, Matter and the email
 * fields stay text. A value that differs from the inherited one becomes an override, and Follow Folder clears it so the
 * property follows its folder again. Submit hands over only what changed, and a number field that isn't a number is an
 * error to fix, not a value to clear.
 */
export function FieldsForm({ fields, matter, submitLabel, children, onSubmit, onCancel, busy = false }: FieldsFormProps) {
  // What the form opened with: Submit compares with it, not with the live list, which a refetch can change under the form
  // (after a check-out, or on returning to the window) and which would then send a value the person never touched
  const [opened] = useState(fields)
  const [drafts, setDrafts] = useState(() => new Map(opened.filter(isEditable).map((field) => [field.name, draftOf(field)])))
  const [following, setFollowing] = useState<Set<string>>(() => new Set())
  const [errors, setErrors] = useState<Map<string, string>>(() => new Map())
  const [working, setWorking] = useState(false)

  const draft = (name: string) => drafts.get(name) ?? ''
  function change(name: string, value: string) {
    setDrafts((now) => {
      const next = new Map(now).set(name, value)
      // A Subclass that doesn't belong to the new Class is cleared, as the server would refuse it
      if (name === 'docclass') {
        const subclass = opened.find((field) => field.name === 'docsubclass')
        const own = subclass?.options?.find((option) => option.value === next.get('docsubclass'))
        if (own?.class && own.class !== value) next.set('docsubclass', '')
      }
      return next
    })
    setErrors((now) => {
      if (!now.has(name)) return now
      const next = new Map(now)
      next.delete(name)
      return next
    })
  }

  function follow(name: string) {
    setFollowing((now) => new Set(now).add(name))
  }

  // Only what changed: the value, or null where the person chose to follow the folder
  function changes(): Record<string, S['PropertyValue']> {
    const values: Record<string, S['PropertyValue']> = {}
    for (const field of opened.filter(isEditable)) {
      if (following.has(field.name)) values[field.name] = null
      else if (draft(field.name) !== draftOf(field)) values[field.name] = valueOf(field, draft(field.name))
    }
    return values
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const values = changes()
    // A number field that isn't a number is an error to fix, not a value to clear
    const notNumbers = opened.filter((field) => isEditable(field) && isNumber(field) && field.name in values && values[field.name] !== null && !numberOk(field, draft(field.name)))
    if (notNumbers.length > 0) {
      setErrors(new Map(notNumbers.map((field) => [field.name, t('fields.type')])))
      return
    }
    setWorking(true)
    try {
      const marked = await onSubmit(values)
      if (marked) setErrors(marked)
    } finally {
      setWorking(false)
    }
  }

  const classDraft = draft('docclass')
  const byName = new Map(fields.map((field) => [field.name, field]))
  const isBusy = busy || working

  function readOnly(field: Field, text: string) {
    return (
      <div key={field.name} className={styles.property}>
        <dt>{field.label}</dt>
        <dd>{text || t('details.none')}</dd>
      </div>
    )
  }

  return (
    <form className={styles.form} onSubmit={(event) => void submit(event)} noValidate>
      {children}
      {fields.map((field) => {
        if (field.name === 'client') return <dl key="client" className={styles.formReadOnly}>{readOnly(field, matter ? `${matter.client.number} – ${matter.client.name}` : textOf(byName.get('client')))}</dl>
        if (field.name === 'matter') return <dl key="matter" className={styles.formReadOnly}>{readOnly(field, matter ? matter.display : textOf(byName.get('matter')))}</dl>
        if (!isEditable(field)) {
          const text = textOf(field)
          // The email's fields show only for an email
          return NEVER.has(field.name) && text === '' ? null : <dl key={field.name} className={styles.formReadOnly}>{readOnly(field, text)}</dl>
        }
        const name = field.name
        const error = errors.get(name)
        // A value takes the place of a folder's only when the API names that folder; any other local value is just a value
        const overridden = field.source === 'local' && field.overrides !== null && !following.has(name)
        const choices = (field.options ?? []).filter((option) => name !== 'docsubclass' || !option.class || option.class === classDraft || classDraft === '')
        return (
          <div key={name} className={styles.formField}>
            {following.has(name) ? (
              <>
                <span className={styles.formLabel}>{field.label}</span>
                <p className={styles.note}>{t('details.followsFolder')}</p>
              </>
            ) : field.options ? (
              <SelectField
                label={field.label}
                required={field.required}
                error={error}
                value={draft(name)}
                onChange={(value) => change(name, value)}
                options={choices.map((option) => ({ value: option.value, label: option.label }))}
                // A required property with no value shows that it has none, rather than the first option, which would look chosen
                empty={field.required ? (draft(name) === '' ? t('details.choose') : undefined) : t('details.none')}
              />
            ) : field.type === 'boolean' ? (
              <CheckField label={field.label} required={field.required} error={error} checked={draft(name) === 'true'} onChange={(checked) => change(name, String(checked))} />
            ) : field.type === 'date' ? (
              <DatePicker label={field.label} required={field.required} error={error} value={draft(name) || undefined} onChange={(day) => change(name, day ?? '')} />
            ) : (
              <TextField label={field.label} required={field.required} error={error} inputMode={isNumber(field) ? 'decimal' : undefined} value={draft(name)} onChange={(value) => change(name, value)} />
            )}
            {overridden && (
              <p className={styles.override}>
                <span className={styles.overridden}>{t('details.overridden')}</span>
                <Button variant="quiet" aria-label={`${t('details.followFolder')}: ${field.label}`} onPress={() => follow(name)}>
                  {t('details.followFolder')}
                </Button>
              </p>
            )}
          </div>
        )
      })}
      <div className={styles.formActions}>
        <Button type="submit" variant="default" isDisabled={isBusy}>
          {submitLabel}
        </Button>
        <Button isDisabled={isBusy} onPress={onCancel}>
          {t('details.cancel')}
        </Button>
      </div>
    </form>
  )
}
