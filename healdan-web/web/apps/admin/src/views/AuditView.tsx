// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { searchId } from '../addresses'
import { typed } from '../pickers/typing'
import { t } from '../strings'
import styles from './views.module.css'

type A = adminComponents['schemas']

/** The kinds of row the Audit Trail has, in the server's order */
export const auditKinds = ['sign-in', 'document', 'folder', 'user', 'administration'] as const satisfies readonly A['AuditKind'][]

/**
 * What the Audit Trail's address holds: its kinds, the person, the matter and the doc # it keeps the rows of, its days
 * and its words. Never a name (spec 6.3).
 */
export interface AuditSearch {
  /** The kinds of row kept, each once, in the server's order (`?kind=document&kind=folder`); every kind without it */
  kind?: A['AuditKind'][]
  /** A person's id: the rows they did, and those about them */
  user?: number
  /** A matter's id, not its folder's */
  matter?: number
  /** A doc # */
  doc?: number
  /** The first and the last day, as 2026-11-02, in the browser's time zone */
  from?: string
  to?: string
  q?: string
}

/** A day as 2026-11-02, one the calendar has */
function dayIn(value: unknown): string | undefined {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined
  const made = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(made.getTime()) && made.toISOString().startsWith(value) ? value : undefined
}

/**
 * The address's parameters the Audit Trail reads; anything else, or anything that isn't what it should be, is left out.
 * The router reads a repeated `kind` as a list, and validates what it validated before too, so the ids may come as numbers.
 */
export function auditSearch(search: Record<string, unknown>): AuditSearch {
  const listed: unknown[] = Array.isArray(search.kind) ? search.kind : [search.kind]
  const kind = auditKinds.filter((one) => listed.includes(one))
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return {
    kind: kind.length === 0 ? undefined : kind,
    user: searchId(search.user),
    matter: searchId(search.matter),
    doc: searchId(search.doc),
    from: dayIn(search.from),
    to: dayIn(search.to),
    q: q === '' ? undefined : q,
  }
}

/**
 * The Audit Trail, at /admin/audit (spec 8.8): what was done, newest first. The route hands in the address's parameters
 * as `search`.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars -- the list and its filters read it
export function AuditView({ search }: { search: AuditSearch }) {
  return (
    <div className={styles.view}>
      <h1 className={styles.title}>{t('admin.audit.title')}</h1>
    </div>
  )
}
