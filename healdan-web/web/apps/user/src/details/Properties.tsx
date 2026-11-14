// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import type { components } from '@healdan/api'
import { formatDate, formatDateTime, formatDay, t } from '@healdan/ui'
import { PropertyList, type PropertyRow } from './PropertyList'

type S = components['schemas']

/** Folder: the folder is the view. Search: the results come from many, so Location says where this one is. */
export type DetailsContext = 'folder' | 'search'

// The fields of an email, which the server fills from the message; a document that isn't one has them empty.
const emailFields = ['emailfrom', 'emailto', 'emailsent', 'emailreceived']

// The date properties that are moments, the message's own; every other date property is a day, kept as its UTC midnight
// (as the form and the helper write it), so it shows as that day wherever the person is
const moments = new Set(['emailsent', 'emailreceived'])

export function textOf(field: S['ProfileField'] | undefined): string {
  const value = field?.value
  if (value === null || value === undefined || value === '') return ''
  if (typeof value === 'string' && field?.type === 'date') return moments.has(field.name) ? formatDateTime(value) : formatDay(value.slice(0, 10))
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  if (typeof value === 'boolean') return t(value ? 'details.yes' : 'details.no')
  return value.name ?? t('details.unnamedUser')
}

/**
 * A document's properties, read only (the form that edits them is `PropertiesForm`): Location in search results; Client, Matter,
 * Class, Subclass, Author, Typist and Date Filed; and an email's From, To, Sent and Received. Names and labels come
 * from the properties the server lists, so the firm's own words show.
 */
export function Properties({ document, fields, context }: { document: S['DocumentView']; fields: S['ProfileField'][]; context: DetailsContext }) {
  const byName = new Map(fields.map((field) => [field.name, field]))
  const entries: PropertyRow[] = []
  const labelOf = (name: string, fallback: string) => byName.get(name)?.label ?? fallback
  const add = (name: string) => {
    const field = byName.get(name)
    if (field) entries.push({ label: field.label, value: textOf(field) })
  }

  if (context === 'search') {
    entries.push({
      label: t('details.location'),
      value: (
        <Link to="/folders/$id" params={{ id: String(document.folder.id) }}>
          {document.folder.name}
        </Link>
      ),
    })
  }
  // Client and Matter come from the document's matter, which names them; the properties hold only their numbers
  const matter = document.matter
  entries.push({ label: labelOf('client', t('details.client')), value: matter ? `${matter.client.number} – ${matter.client.name}` : textOf(byName.get('client')) })
  entries.push({ label: labelOf('matter', t('details.matter')), value: matter ? matter.display : textOf(byName.get('matter')) })
  for (const name of ['docclass', 'docsubclass']) add(name)
  entries.push({ label: labelOf('author', t('details.author')), value: document.author?.displayName ?? textOf(byName.get('author')) })
  add('typist')
  entries.push({ label: t('details.dateFiled'), value: formatDate(document.dateFiled) })
  if (emailFields.some((name) => textOf(byName.get(name)) !== '')) for (const name of emailFields) add(name)

  // The Properties tab names them (DetailsPanel)
  return <PropertyList rows={entries} />
}
