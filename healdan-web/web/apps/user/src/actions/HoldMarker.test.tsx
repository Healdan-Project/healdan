// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { children, document, folder, page, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import actionsCss from './actions.module.css?raw'

// Each document row in a list says, with a small icon beside its name, that the document is checked out or locked, and
// by whom (the owner's call after walkthrough row 7)
const since = '2026-11-03T10:00:00Z'
const when = formatDateTime(since)
const free = document({ docNumber: 10388, name: 'Letter from Dunmere Pryce.pdf' })
const theirs = document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx', hold: { kind: 'checkout', by: { id: 9, name: 'Alex Marsh' }, since, device: null } })
const onMyMac = document({
  docNumber: 10447,
  name: 'Cap Table.xlsx',
  hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since, device: { id: 'mac-1', name: 'Healdan Helper on MacBook', app: 'Excel' } },
})
const locked = document({ docNumber: 10500, name: 'Engrossment.docx', hold: { kind: 'lock', by: { id: 9, name: 'Alex Marsh' }, since, device: null } })

const routes: Routes = {
  'GET /v1/folders/7': folder({ id: 7, name: 'Correspondence' }),
  'GET /v1/folders/7/children': children([free, theirs, onMyMac, locked]),
}

const row = async (name: RegExp) => within(await within(screen.getByRole('main')).findByRole('grid')).findByRole('row', { name })
const marker = (target: HTMLElement) => within(target).queryByRole('img')

describe('the hold marker on a row', () => {
  it('shows no marker for a free document', async () => {
    await renderApp('/folders/7', routes)
    expect(marker(await row(/Letter from Dunmere/))).toBeNull()
  })

  it('marks a check-out with the pencil, named and titled by who has it and since when', async () => {
    await renderApp('/folders/7', routes)
    const icon = marker(await row(/Letter to Dunmere/))
    expect(icon?.getAttribute('aria-label')).toBe(`Checked out by Alex Marsh since ${when}.`)
    expect(icon?.getAttribute('title')).toBe(`Checked out by Alex Marsh since ${when}.`)
    expect(icon?.querySelector('svg.tabler-icon-pencil')).toBeTruthy()
  })

  // A tooltip alone can't be reached by keyboard. jsdom draws no style, so the stylesheet is read.
  it('has the same words as text, which the row shows when it has the keyboard focus, or the pointer', async () => {
    await renderApp('/folders/7', routes)
    const target = await row(/Letter to Dunmere/)
    expect(marker(target)?.textContent).toBe(`Checked out by Alex Marsh since ${when}.`)
    expect(actionsCss).toMatch(/\[role='row'\]:focus-within\)\s+\.holdText/)
    expect(actionsCss).toMatch(/\[role='row'\]:hover\)\s+\.holdText/)
  })

  it('names the computer for your own check-out on one, without where to release it', async () => {
    await renderApp('/folders/7', routes)
    const icon = marker(await row(/Cap Table/))
    expect(icon?.getAttribute('aria-label')).toBe(`Checked out by you on Healdan Helper on MacBook since ${when}.`)
  })

  it('marks a lock with the padlock', async () => {
    await renderApp('/folders/7', routes)
    const icon = marker(await row(/Engrossment/))
    expect(icon?.getAttribute('aria-label')).toBe(`Locked by Alex Marsh since ${when}.`)
    expect(icon?.querySelector('svg.tabler-icon-lock')).toBeTruthy()
  })

  it('sits in the title cell, after the name', async () => {
    await renderApp('/folders/7', routes)
    const header = within(await row(/Letter to Dunmere/)).getByRole('rowheader')
    expect(header.lastElementChild?.getAttribute('role')).toBe('img')
  })

  it('shows on Recent Documents too, as on every list of documents', async () => {
    await renderApp('/', { 'GET /v1/documents/recent': page([free, theirs]) })
    expect(marker(await row(/Letter to Dunmere/))?.getAttribute('aria-label')).toBe(`Checked out by Alex Marsh since ${when}.`)
    expect(marker(await row(/Letter from Dunmere/))).toBeNull()
  })

  it('passes axe', async () => {
    const { view } = await renderApp('/folders/7', routes)
    await row(/Engrossment/)
    await expectNoAxeViolations(view.container)
  })
})
