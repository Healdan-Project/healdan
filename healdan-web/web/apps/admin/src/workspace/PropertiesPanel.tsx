// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, fieldErrors, useApi } from '@healdan/shell'
import { Button, DatePicker, formatDay, SelectField, TextField, toast } from '@healdan/ui'
import { PersonPicker } from '../pickers/PersonPicker'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { useEditState } from './edits'
import { useFolderProperties, workspaceChanged, workspaceKeys } from './queries'
import type { WorkspaceNode } from './tree'
import styles from './Workspace.module.css'

type A = adminComponents['schemas']
type Property = A['FolderProperty']

export interface PropertiesPanelProps {
  node: WorkspaceNode
  root: WorkspaceNode
  kind: 'matter' | 'template'
}

/** The property a Subclass's options belong to by their `class` */
const CLASS = 'docclass'

const isNumber = (property: Property) => property.type === 'integer' || property.type === 'decimal'
/** A folder or a document, which the field holds as its id, a whole number */
const isId = (property: Property) => property.type === 'folder' || property.type === 'document'
/** Typed as a number: a number, or a folder's or a document's id */
const isNumeric = (property: Property) => isNumber(property) || isId(property)

/** A person a `user` property names */
function personOf(value: Property['value']): A['PropertyUser'] | null {
  return value !== null && typeof value === 'object' && 'id' in value ? value : null
}

/** What the form holds for the folder's own value, as text: empty while it sets none and inherits */
function draftOf(property: Property): string {
  if (property.source !== 'local' || property.value === null) return ''
  const value = property.value
  if (property.type === 'boolean') return value === true ? 'true' : 'false'
  if (property.type === 'user') return String(personOf(value)?.id ?? '')
  if (property.type === 'date') return typeof value === 'string' ? value.slice(0, 10) : ''
  return typeof value === 'string' || typeof value === 'number' ? String(value) : ''
}

/**
 * What a save sends for the property: null to inherit. A field left as it came sends the server's own value, so a day,
 * a folder, a document or a person nobody touched goes back exactly as it was; only what was changed is converted.
 */
function valueOf(property: Property, draft: string, initial: string): A['FolderPropertyValue'] {
  if (draft === initial) return property.source === 'local' ? property.value : null
  if (draft.trim() === '') return null
  if (property.type === 'boolean') return draft === 'true'
  if (property.type === 'user') return { id: Number(draft) }
  if (isNumeric(property)) return Number(draft)
  return property.type === 'date' ? `${draft}T00:00:00Z` : draft
}

/** A value as words: an option's label, Yes or No, a person's name, a day */
function textOf(property: Property, value: Property['value']): string {
  if (value === null) return ''
  if (property.type === 'boolean') return t(value === true ? 'admin.properties.yes' : 'admin.properties.no')
  if (property.type === 'user') return personOf(value)?.name ?? ''
  if (property.type === 'date' && typeof value === 'string') return formatDay(value.slice(0, 10))
  if (typeof value === 'string') return property.options?.find((option) => option.value === value)?.label ?? value
  return String(value)
}

/** Whether what was typed into a number field is a number of its kind: a whole number for an integer or an id */
function numberOk(property: Property, draft: string): boolean {
  const number = Number(draft)
  return Number.isFinite(number) && (property.type === 'decimal' || Number.isInteger(number))
}

/**
 * The values a folder sets for what's filed below it (spec 8.4): each property that passes down from folders, such as
 * a Class, with where its value comes from when the folder sets none. A Subclass is offered only within its Class.
 * Save sends the whole set, as the route takes it: a property set here is the folder's own, one Not Set Here inherits,
 * and one left alone goes back as the server gave it.
 */
export function PropertiesPanel({ node, root, kind }: PropertiesPanelProps) {
  const headingId = useId()
  const asked = useFolderProperties(node.id)
  return (
    <section aria-labelledby={headingId} className={styles.section}>
      <h3 id={headingId} className={styles.sectionTitle}>
        {t('admin.properties.title')}
      </h3>
      {kind === 'template' && <p className={styles.note}>{t('admin.properties.template')}</p>}
      {asked.isError ? (
        <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
      ) : asked.data === undefined ? (
        <p className={styles.note}>{t('common.loading')}</p>
      ) : asked.data.properties.length === 0 ? (
        <p className={styles.note}>{t('admin.properties.none')}</p>
      ) : (
        <ValuesForm values={asked.data} node={node} root={root} kind={kind} />
      )}
    </section>
  )
}

function ValuesForm({ values, node, root, kind }: { values: A['FolderProperties'] } & PropertiesPanelProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const properties = values.properties
  const initial = Object.fromEntries(properties.map((property) => [property.name, draftOf(property)]))
  const [draft, setDraft] = useState<Record<string, string> | null>(null)
  // The people picked in this form, by id, as a user property's draft holds only the id
  const [people, setPeople] = useState<Record<string, A['PersonBrief']>>({})
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const shown = draft ?? initial
  const dirty = draft !== null && properties.some((property) => draft[property.name] !== initial[property.name])
  useEditState('values', dirty, busy)

  // The Class a Subclass is offered within: the one set here, or else the one the folder inherits
  const classProperty = properties.find((property) => property.name === CLASS)
  const chosenClass = shown[CLASS] !== '' ? shown[CLASS] : classProperty && classProperty.source !== 'local' && typeof classProperty.value === 'string' ? classProperty.value : undefined

  const fields = fieldErrors(failure)
  const errorOf = (property: Property): string | undefined => {
    const text = shown[property.name] ?? ''
    if (isNumeric(property) && text.trim() !== '' && !numberOk(property, text)) return t('fields.type')
    return fields.get(property.name) ?? fields.get(`values.${property.name}`)
  }
  const invalid = properties.some((property) => isNumeric(property) && (shown[property.name] ?? '').trim() !== '' && !numberOk(property, shown[property.name]!))
  const refusal = failure !== undefined && !properties.some((property) => fields.has(property.name) || fields.has(`values.${property.name}`)) ? refusalFor(failure) : undefined

  function set(name: string, value: string) {
    const next = { ...shown, [name]: value }
    // A Subclass of another Class goes with the Class it belonged to
    if (name === CLASS) {
      for (const property of properties) {
        const option = property.options?.find((one) => one.value === next[property.name])
        if (option?.class && value !== '' && option.class !== value) next[property.name] = ''
      }
    }
    setDraft(next)
    setFailure(undefined)
  }

  async function save() {
    setBusy(true)
    setFailure(undefined)
    try {
      const body: A['FolderPropertiesChange'] = {
        values: Object.fromEntries(properties.map((property) => [property.name, valueOf(property, shown[property.name] ?? '', initial[property.name] ?? '')])),
      }
      const answer = await api.send<A['FolderProperties']>('PUT', apiPath`/v1/admin/folders/${node.id}/properties`, body)
      queryClient.setQueryData(workspaceKeys.properties(node.id), answer)
      setDraft(null)
      workspaceChanged(queryClient, root.id, kind, workspaceKeys.properties(node.id))
      toast(t('admin.properties.saved', { name: node.name }))
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  /** Where the value comes from, under the field */
  function sourceOf(property: Property): string | undefined {
    if ((shown[property.name] ?? '') !== '') return t('admin.properties.local')
    // Taken off here: what it then inherits is the server's to say once saved
    if (property.source === 'local' || property.value === null) return undefined
    const value = textOf(property, property.value)
    if (property.source === 'default') return t('admin.properties.default', { value })
    return property.inheritedFrom ? t('admin.properties.inherited', { folder: property.inheritedFrom.name, value }) : t('admin.properties.inheritedAbove', { value })
  }

  function field(property: Property) {
    const value = shown[property.name] ?? ''
    const error = errorOf(property)
    const onChange = (next: string) => set(property.name, next)
    if (property.options) {
      const options = property.options.filter((option) => option.class === null || chosenClass === undefined || option.class === chosenClass)
      return <SelectField label={property.label} value={value} empty={t('admin.properties.notSet')} options={options.map((option) => ({ value: option.value, label: option.label }))} error={error} onChange={onChange} />
    }
    if (property.type === 'boolean') {
      const options = [
        { value: 'true', label: t('admin.properties.yes') },
        { value: 'false', label: t('admin.properties.no') },
      ]
      return <SelectField label={property.label} value={value} empty={t('admin.properties.notSet')} options={options} error={error} onChange={onChange} />
    }
    if (property.type === 'date') return <DatePicker label={property.label} value={value || undefined} error={error} onChange={(day) => onChange(day ?? '')} />
    if (property.type === 'user') {
      const local = property.source === 'local' ? personOf(property.value) : null
      const person = value === '' ? null : (people[value] ?? (local && String(local.id) === value ? { id: local.id, username: '', name: local.name ?? '' } : null))
      return (
        <PersonPicker
          label={property.label}
          accounts="enabled"
          value={person}
          error={error}
          onChange={(next) => {
            if (next) setPeople({ ...people, [String(next.id)]: next })
            onChange(next ? String(next.id) : '')
          }}
        />
      )
    }
    return <TextField label={property.label} value={value} inputMode={isNumeric(property) ? 'decimal' : undefined} error={error} onChange={onChange} />
  }

  return (
    <form
      className={styles.fields}
      onSubmit={(event) => {
        event.preventDefault()
        if (dirty && !invalid && !busy) void save()
      }}
    >
      {properties.map((property) => {
        const source = sourceOf(property)
        return (
          <div key={property.name} className={styles.field}>
            {field(property)}
            {source && <p className={styles.note}>{source}</p>}
          </div>
        )
      })}
      {refusal !== undefined && (
        <p className={styles.problem} role="alert">
          {refusal}
        </p>
      )}
      <div className={styles.buttons}>
        <Button type="submit" variant="default" isDisabled={!dirty || invalid} isPending={busy}>
          {t('admin.properties.save')}
        </Button>
      </div>
    </form>
  )
}
