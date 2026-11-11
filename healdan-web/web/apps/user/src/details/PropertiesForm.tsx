// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, CheckField, DatePicker, SelectField, t, TextField, toast } from '@healdan/ui'
import { fieldErrors, messageFor } from '../frame/errors'
import { keys } from '../queries'
import { useApi } from '../services'
import { Section } from './Section'
import { textOf } from './Properties'
import styles from './details.module.css'

type S = components['schemas']
type Field = S['ProfileField']

// Client and Matter come from the matter, and the email fields from the message: never edited here, whatever `editable` says
const NEVER = new Set(['client', 'matter', 'emailfrom', 'emailto', 'emailcc', 'emailsubject', 'emailsent', 'emailreceived'])
// Properties that belong to one document and don't pass down from a folder (the User Guide's Properties Pass Down), so a
// value of theirs is never an override
const OWN = new Set(['author', 'typist', 'comments', 'legacydocnum'])
const FORM_TYPES = new Set<Field['type']>(['string', 'integer', 'decimal', 'date', 'boolean'])

const isEditable = (field: Field) => field.editable && !NEVER.has(field.name) && FORM_TYPES.has(field.type)

/** What the form shows for a field, as text: the day of a date, `true` or `false` for a yes or no */
function draftOf(field: Field): string {
  const value = field.value
  if (field.type === 'boolean') return value === true ? 'true' : 'false'
  if (value === null || value === undefined) return ''
  if (field.type === 'date') return typeof value === 'string' ? value.slice(0, 10) : ''
  return typeof value === 'string' || typeof value === 'number' ? String(value) : ''
}

/** What a changed field sends: null to clear it, or to follow the folder again */
function valueOf(field: Field, draft: string): S['PropertyValue'] {
  if (field.type === 'boolean') return draft === 'true'
  if (draft === '') return null
  if (field.type === 'integer' || field.type === 'decimal') {
    const number = Number(draft)
    // Not a number: the server says so for this field, in its own words from the catalogue
    return Number.isFinite(number) ? number : draft
  }
  return field.type === 'date' ? `${draft}T00:00:00Z` : draft
}

export interface PropertiesFormProps {
  document: S['DocumentView']
  fields: Field[]
  onClose: () => void
}

/**
 * A document's properties as a form (spec 6.2), made from what the server lists, so the firm's own properties are in it:
 * a property picked from a list offers only its options (a Subclass only its Class's), a required one is marked, and
 * Client, Matter and the email fields stay text. A value that differs from the inherited one becomes an override, and
 * Follow Folder clears it so the property follows its folder again. Save sends only what changed, and a 422 marks each field.
 */
export function PropertiesForm({ document, fields, onClose }: PropertiesFormProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [drafts, setDrafts] = useState(() => new Map(fields.filter(isEditable).map((field) => [field.name, draftOf(field)])))
  const [following, setFollowing] = useState<Set<string>>(() => new Set())
  const [errors, setErrors] = useState<Map<string, string>>(() => new Map())
  const [busy, setBusy] = useState(false)

  const draft = (name: string) => drafts.get(name) ?? ''
  function change(name: string, value: string) {
    setDrafts((now) => {
      const next = new Map(now).set(name, value)
      // A Subclass that doesn't belong to the new Class is cleared, as the server would refuse it
      if (name === 'docclass') {
        const subclass = fields.find((field) => field.name === 'docsubclass')
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
    for (const field of fields.filter(isEditable)) {
      if (following.has(field.name)) values[field.name] = null
      else if (draft(field.name) !== draftOf(field)) values[field.name] = valueOf(field, draft(field.name))
    }
    return values
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    const values = changes()
    if (Object.keys(values).length === 0) return onClose()
    setBusy(true)
    try {
      const saved = await api.send<{ items: Field[] }>('PATCH', apiPath`/v1/documents/${document.docNumber}/properties`, { values })
      queryClient.setQueryData(keys.properties(document.docNumber), saved)
      // The save is a new version, and the lists show its date and number
      await Promise.all(['documents', 'folders', 'search'].map((root) => queryClient.invalidateQueries({ queryKey: [root] })))
      onClose()
    } catch (error) {
      const marked = fieldErrors(error)
      setErrors(marked)
      // A refusal that marks no field of the form (a hold, a permission) is a message of its own
      if (![...marked.keys()].some((name) => drafts.has(name))) toast(messageFor(error))
    } finally {
      setBusy(false)
    }
  }

  const classDraft = draft('docclass')
  const byName = new Map(fields.map((field) => [field.name, field]))
  const matter = document.matter

  function readOnly(field: Field, text: string) {
    return (
      <div key={field.name} className={styles.property}>
        <dt>{field.label}</dt>
        <dd>{text || t('details.none')}</dd>
      </div>
    )
  }

  return (
    <Section title={t('details.properties')}>
      <form className={styles.form} onSubmit={(event) => void save(event)} noValidate>
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
          const overridden = field.source === 'local' && !OWN.has(name) && !following.has(name)
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
                  empty={field.required ? undefined : t('details.none')}
                />
              ) : field.type === 'boolean' ? (
                <CheckField label={field.label} required={field.required} error={error} checked={draft(name) === 'true'} onChange={(checked) => change(name, String(checked))} />
              ) : field.type === 'date' ? (
                <DatePicker label={field.required ? `${field.label} *` : field.label} error={error} value={draft(name) || undefined} onChange={(day) => change(name, day ?? '')} />
              ) : (
                <TextField label={field.label} required={field.required} error={error} type={field.type === 'string' ? 'text' : 'number'} value={draft(name)} onChange={(value) => change(name, value)} />
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
          <Button type="submit" variant="default" isDisabled={busy}>
            {t('details.save')}
          </Button>
          <Button isDisabled={busy} onPress={onClose}>
            {t('details.cancel')}
          </Button>
        </div>
      </form>
    </Section>
  )
}
