// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { hasString, t } from '../strings'

type A = adminComponents['schemas']

/** The kinds of row the Audit Trail has, in the server's order */
export const auditKinds = ['sign-in', 'document', 'folder', 'user', 'administration'] as const satisfies readonly A['AuditKind'][]

/** The kinds of row a matter's can be: no sign-in or user row is a matter's (P5-R9) */
export const matterKinds = ['document', 'folder', 'administration'] as const satisfies readonly A['AuditKind'][]

/** The kinds given, each once, in the server's order; undefined for none, which is every kind */
export function inKindOrder(kinds: readonly string[]): A['AuditKind'][] | undefined {
  const ordered = auditKinds.filter((kind) => kinds.includes(kind))
  return ordered.length === 0 ? undefined : ordered
}

/**
 * A kind of row as the Audit Trail names it: Sign-In, Document, Folder, User or Administration; a kind the catalogue doesn't
 * know, as one a newer server adds, as the server names it, so the row is still drawn (P6-R17)
 */
export function kindLabel(kind: A['AuditKind']): string {
  const key = `admin.audit.kind.${kind}`
  return hasString(key) ? t(key as `admin.audit.kind.${A['AuditKind']}`) : kind
}

/**
 * An administration row's changed fields, one line each, in the order the server gives them (their names'): `name: old →
 * new`, a value that was none as `(none)`, and a secret as `name: changed`, as no value of it is kept. Plain text, which
 * the page draws as text: the values are what someone typed.
 */
export function changeLines(changes: A['AuditRow']['changes']): string[] {
  return Object.entries(changes ?? {}).map(([name, change]) =>
    'secret' in change
      ? t('admin.audit.secretChanged', { name })
      : t('admin.audit.change', { name, before: change.before ?? t('admin.audit.noValue'), after: change.after ?? t('admin.audit.noValue') }),
  )
}

/** A row's Detail cell, on its one line: the server's detail, then an administration row's changes, each after a dot */
export function detailCell(row: A['AuditRow']): string {
  return [row.detail, ...changeLines(row.changes)].filter((part): part is string => part !== null && part !== '').join(' · ')
}

const FULL = new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeStyle: 'medium' })

/** When a row was done, whole, to the second, as its details give it: the list's Time says only the minute */
export function fullTime(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : FULL.format(date)
}
