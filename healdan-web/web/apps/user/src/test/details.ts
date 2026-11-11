// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { document, field, page, versionRow, type Routes } from './fakeApi'

type S = components['schemas']

export const letter = document({ docNumber: 10388, name: 'Letter from Dunmere Pryce.pdf', size: 214_000, version: '2.1', fileVersion: '2.1' })

/** A document's properties as the API lists them: every field of the profile, the email ones empty unless it's an email. */
export function propertiesOf(over: Record<string, S['ProfileField']['value']> = {}): S['ProfileField'][] {
  const values: Record<string, S['ProfileField']['value']> = {
    client: '1001',
    matter: '1',
    docclass: 'Letters',
    docsubclass: 'Incoming',
    author: { id: 3, name: 'Jamie Clarke' },
    typist: { id: 5, name: 'Priya Nair' },
    comments: 'Chase on Monday',
    legacydocnum: 'LEG-1',
    emailfrom: null,
    emailto: null,
    emailcc: null,
    emailsubject: null,
    emailsent: null,
    emailreceived: null,
    ...over,
  }
  const labels: Record<string, string> = {
    client: 'Client',
    matter: 'Matter',
    docclass: 'Class',
    docsubclass: 'Subclass',
    author: 'Author',
    typist: 'Typist',
    comments: 'Comments',
    legacydocnum: 'Legacy Doc #',
    emailfrom: 'From',
    emailto: 'To',
    emailcc: 'Cc',
    emailsubject: 'Subject',
    emailsent: 'Sent',
    emailreceived: 'Received',
  }
  const types: Record<string, S['ProfileField']['type']> = { author: 'user', typist: 'user', emailsent: 'date', emailreceived: 'date' }
  return Object.entries(values).map(([name, value]) => field({ name, label: labels[name] ?? name, type: types[name] ?? 'string', value, source: value === null ? null : 'local' }))
}

/** What the details of `doc` ask for: the document, its properties and its versions. */
export function detailsRoutes(doc: S['DocumentView'] = letter, over: Routes = {}): Routes {
  return {
    [`GET /v1/documents/${doc.docNumber}`]: doc,
    [`GET /v1/documents/${doc.docNumber}/properties`]: page(propertiesOf()),
    [`GET /v1/documents/${doc.docNumber}/versions`]: page([
      versionRow({ version: '2.1', fileVersion: '2.1', kind: 'snapshot', date: '2026-10-02T10:00:00Z', comment: 'Fixed the date' }),
      versionRow({ version: '2.0', fileVersion: '2.0', date: '2026-10-01T10:00:00Z', by: { id: 5, name: 'Priya Nair' } }),
      versionRow({ version: '1.0', fileVersion: '1.0', date: '2026-09-29T09:40:00Z' }),
    ]),
    ...over,
  }
}
