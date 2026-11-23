// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { auditPage, auditRoutes, auditRow, peopleRoutes, priyaRow, workspaceRoutes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']

// What the Search Index records, as the Audit Trail and the Activity tabs draw it (P6-R16, P6-R17): an administration row of
// each of the four new actions, whose words the server gives, and a document's `event.indexed.error` row.
const indexRows: A['AuditRow'][] = [
  auditRow({ id: 'a60', kind: 'administration', event: 'reindex.started', what: 'Started a reindex', detail: 'Matter 1001.1 Charter Dispute', docNumber: null, folderId: null, changes: { documents: { before: null, after: '412' }, emptyFirst: { before: null, after: 'false' }, kind: { before: null, after: 'matter' } } }),
  auditRow({ id: 'a61', kind: 'administration', event: 'reindex.cancelled', what: 'Cancelled a reindex', detail: 'Matter 1001.1 Charter Dispute', docNumber: null, folderId: null }),
  auditRow({ id: 'a62', kind: 'administration', event: 'index.retried', what: 'Tried to index a document again', detail: '# 10412 Witness Statement.pdf', docNumber: null, folderId: null, changes: { outcome: { before: null, after: 'failed' } } }),
  auditRow({ id: 'a63', kind: 'administration', event: 'index.skipped', what: 'Set a document not to be indexed', detail: '# 10412 Witness Statement.pdf', docNumber: null, folderId: null }),
  auditRow({ id: 'd70', kind: 'document', event: 'event.indexed.error', what: "Couldn't be indexed", detail: '# 10412, Witness Statement.pdf, version 1.0', docNumber: 10412 }),
]

const routes = { 'GET /v1/admin/audit': auditPage(indexRows) }
const whats = (grid: HTMLElement) => within(grid).getAllByRole('row').slice(1).map((row) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent))

describe("The Search Index's records", () => {
  it('are drawn on the Audit Trail with their own words, kinds and changes', async () => {
    await renderApp('/audit', { ...auditRoutes(), ...routes })
    const grid = await screen.findByRole('grid', { name: 'Audit Trail' })
    await within(grid).findAllByText('Started a reindex')
    const rows = whats(grid)
    expect(rows.map((row) => [row[2], row[3]])).toEqual([
      ['Administration', 'Started a reindex'],
      ['Administration', 'Cancelled a reindex'],
      ['Administration', 'Tried to index a document again'],
      ['Administration', 'Set a document not to be indexed'],
      ['Document', "Couldn't be indexed"],
    ])
    expect(rows[0]![4]).toBe('Matter 1001.1 Charter Dispute · documents: (none) → 412 · emptyFirst: (none) → false · kind: (none) → matter')
    expect(rows[2]![4]).toBe('# 10412 Witness Statement.pdf · outcome: (none) → failed')
  })

  it("are drawn on a matter's Activity tab", async () => {
    await renderApp('/matters/11?tab=activity', { ...workspaceRoutes(), ...routes })
    const grid = await screen.findByRole('grid', { name: /^Activity of/ })
    await within(grid).findAllByText('Started a reindex')
    expect(whats(grid).map((row) => row[3])).toEqual(['Started a reindex', 'Cancelled a reindex', 'Tried to index a document again', 'Set a document not to be indexed', "Couldn't be indexed"])
  })

  it("are drawn on a person's Activity tab", async () => {
    await renderApp(`/users/${priyaRow.id}?tab=activity`, { ...peopleRoutes(), ...routes })
    const grid = await screen.findByRole('grid', { name: 'Activity of Priya Nair' })
    await within(grid).findAllByText('Started a reindex')
    expect(whats(grid)).toHaveLength(5)
  })
})
