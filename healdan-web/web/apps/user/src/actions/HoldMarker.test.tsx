// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { children, document, folder, page, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import actionsCss from './actions.module.css?raw'

// Each document row in a list says, with a small icon beside its name, that the document is checked out or locked, and
// by whom (the owner's call after walkthrough row 7). It's the icon alone, which never moves, and its name and tooltip
// say who has it, without the time or the computer (the owner's dogfooding punch list, 2026-11-14).
const since = '2026-11-03T10:00:00Z'
const free = document({ docNumber: 10388, name: 'Letter from Dunmere Pryce.pdf' })
const theirs = document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx', hold: { kind: 'checkout', by: { id: 9, name: 'Alex Marsh' }, since, device: null, heldOn: null } })
const onMyMac = document({
  docNumber: 10447,
  name: 'Cap Table.xlsx',
  hold: {
    kind: 'checkout',
    by: { id: 5, name: 'Priya Nair' },
    since,
    device: { id: 'mac-1', name: 'Healdan Helper on MacBook', app: 'Excel' },
    heldOn: { name: 'Healdan Helper on MacBook', app: 'Excel' },
  },
})
const locked = document({ docNumber: 10500, name: 'Engrossment.docx', hold: { kind: 'lock', by: { id: 9, name: 'Alex Marsh' }, since, device: null, heldOn: null } })

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

  it('marks a check-out with the pencil, named and titled by who has it', async () => {
    await renderApp('/folders/7', routes)
    const icon = marker(await row(/Letter to Dunmere/))
    expect(icon?.getAttribute('aria-label')).toBe('Checked out by Alex Marsh')
    expect(icon?.getAttribute('title')).toBe('Checked out by Alex Marsh')
    expect(icon?.querySelector('svg.tabler-icon-pencil')).toBeTruthy()
  })

  it('says "you" for your own check-out, without the computer or the time', async () => {
    await renderApp('/folders/7', routes)
    const icon = marker(await row(/Cap Table/))
    expect(icon?.getAttribute('aria-label')).toBe('Checked out by you')
    expect(icon?.getAttribute('title')).toBe('Checked out by you')
  })

  it('marks a lock with the padlock', async () => {
    await renderApp('/folders/7', routes)
    const icon = marker(await row(/Engrossment/))
    expect(icon?.getAttribute('aria-label')).toBe('Locked by Alex Marsh')
    expect(icon?.querySelector('svg.tabler-icon-lock')).toBeTruthy()
  })

  // Clicking a row gave its cell the focus, and the revealed sentence squeezed the title and shifted the icon
  it('is the icon alone, with no words in it to show on hover, focus or selection', async () => {
    await renderApp('/folders/7', routes)
    const target = await row(/Letter to Dunmere/)
    await userEvent.click(within(target).getByRole('rowheader'))
    const icon = marker(target)!
    expect(icon.textContent).toBe('')
    expect(icon.children).toHaveLength(1)
    expect(icon.firstElementChild?.tagName.toLowerCase()).toBe('svg')
    // jsdom draws no style, so the stylesheet is read: nothing about a row's state changes the marker
    expect(actionsCss).not.toMatch(/holdText/)
    expect(actionsCss).not.toMatch(/\[role='row'\]/)
  })

  it('sits in the title cell, after the name', async () => {
    await renderApp('/folders/7', routes)
    const header = within(await row(/Letter to Dunmere/)).getByRole('rowheader')
    expect(header.lastElementChild?.getAttribute('role')).toBe('img')
  })

  it('shows on Recent Documents too, as on every list of documents', async () => {
    await renderApp('/', { 'GET /v1/documents/recent': page([free, theirs]) })
    expect(marker(await row(/Letter to Dunmere/))?.getAttribute('aria-label')).toBe('Checked out by Alex Marsh')
    expect(marker(await row(/Letter from Dunmere/))).toBeNull()
  })

  it('passes axe', async () => {
    const { view } = await renderApp('/folders/7', routes)
    await row(/Engrossment/)
    await expectNoAxeViolations(view.container)
  })
})
