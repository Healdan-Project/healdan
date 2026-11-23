// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import type { adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, Icon } from '@healdan/ui'
import { routes, searchId } from '../addresses'
import { useDownload } from '../download'
import { Refusal } from '../forms/Refusal'
import { AuditFilters } from '../monitoring/AuditFilters'
import { AuditList } from '../monitoring/AuditList'
import { auditCheck, auditExportHref, useAuditRefresh, type AuditQuery } from '../monitoring/queries'
import { auditKinds, inKindOrder } from '../monitoring/text'
import { typed } from '../pickers/typing'
import { t } from '../strings'
import styles from '../monitoring/Audit.module.css'

type A = adminComponents['schemas']

/** The kinds of row the Audit Trail has, in the server's order */
export { auditKinds }

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
 * The doc # and the words are one box, which shows one of them, so an address with both, as only a hand-made one has,
 * keeps the doc # alone: what filters is what the box shows.
 */
export function auditSearch(search: Record<string, unknown>): AuditSearch {
  const listed: unknown[] = Array.isArray(search.kind) ? search.kind : [search.kind]
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  const doc = searchId(search.doc)
  return {
    kind: inKindOrder(listed.filter((one): one is string => typeof one === 'string')),
    user: searchId(search.user),
    matter: searchId(search.matter),
    doc,
    from: dayIn(search.from),
    to: dayIn(search.to),
    q: q === '' || doc !== undefined ? undefined : q,
  }
}

/** The filters the address holds, as the list's route and the export take them */
function queryOf(search: AuditSearch): AuditQuery {
  return { kind: search.kind, userId: search.user, matterId: search.matter, docNumber: search.doc, from: search.from, to: search.to, q: search.q }
}

/**
 * The Audit Trail, at /admin/audit (spec 8.8): what was done, newest first, from the server's histories and the
 * administration record, with its filters, every one in the address, so Back and a reload come back to the same list.
 * Refresh asks for it again. Export CSV downloads what the filters keep, through a hidden frame once the list has been
 * asked for one row with the same filters (useDownload). The route hands in the address's parameters as `search`.
 */
export function AuditView({ search }: { search: AuditSearch }) {
  const api = useApi()
  const navigate = useNavigate({ from: routes.audit })
  const query = queryOf(search)
  const filtered = Object.values(query).some((value) => value !== undefined)
  const { refresh, refreshing } = useAuditRefresh(query)
  const download = useDownload(auditExportHref(api, query))

  /** The address with `change` made, kept in place of the one before: a filter isn't a step of its own */
  function change(next: Partial<AuditSearch>) {
    void navigate({ to: routes.audit, search: (prev) => ({ ...prev, ...next }), replace: true })
  }

  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 className={styles.title}>{t('admin.audit.title')}</h1>
        <div className={styles.actions}>
          <Button isPending={refreshing} onPress={refresh}>
            <span className={styles.label}>
              <Icon name="refresh" size={14} />
              {t('admin.audit.refresh')}
            </span>
          </Button>
          {/* With the browser's own session, so the file comes as the server sends it, row by row */}
          <Button isPending={download.checking} onPress={() => void download.start(() => auditCheck(api, query))}>
            <span className={styles.label}>
              <Icon name="download" size={14} />
              {t('admin.audit.export')}
            </span>
          </Button>
        </div>
      </div>
      <Refusal failure={download.failure} />
      <AuditFilters search={search} onChange={change} />
      <AuditList query={query} filtered={filtered} label={t('admin.audit.title')} fill />
    </div>
  )
}
