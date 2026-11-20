// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'

const bar = () => screen.getByRole('banner')

async function openMenu(name: string) {
  const trigger = await within(bar()).findByRole('button', { name })
  fireEvent.keyDown(trigger, { key: 'Enter' })
  fireEvent.keyUp(trigger, { key: 'Enter' })
  return screen.findByRole('menu')
}

// Spec 7.1: Healdan's mark, Healdan and an Admin tag; the find; Open Healdan, Help and the person's menu
describe('The top bar', () => {
  it('names the app, with its Admin tag, as a link to the Dashboard', async () => {
    const { view } = await renderApp('/no-such-page')
    const brand = within(bar()).getByRole('link', { name: 'Healdan Admin' })
    expect(brand.getAttribute('href')).toBe('/admin/')
    expect(within(brand).getByText('Admin')).toBeTruthy()
    expect(brand.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
    await expectNoAxeViolations(view.container)
  })

  it('has the find, named for what it finds', async () => {
    await renderApp('/')
    const find = within(bar()).getByRole('combobox', { name: 'Find a client, matter or person' })
    expect(find.getAttribute('placeholder')).toBe('Find a client, matter or person')
    expect(within(bar()).getByRole('search')).toBeTruthy()
  })

  it('opens Healdan, the User app', async () => {
    await renderApp('/')
    expect(within(bar()).getByRole('link', { name: 'Open Healdan' }).getAttribute('href')).toBe('/app/')
  })

  it('has Help, with the Admin Guide and the notices, each in a new window', async () => {
    await renderApp('/')
    await openMenu('Help')
    const guide = screen.getByRole('menuitem', { name: 'Admin Guide' })
    expect(guide.getAttribute('href')).toBe('https://docs.healdan.com/admin/the-admin-app/')
    expect(guide.getAttribute('target')).toBe('_blank')
    expect(guide.getAttribute('rel')).toBe('noopener noreferrer')
    const notices = screen.getByRole('menuitem', { name: 'Open-Source Notices' })
    expect(notices.getAttribute('href')).toBe('/app/notices.txt')
    expect(notices.getAttribute('target')).toBe('_blank')
    await expectNoAxeViolations(document.body)
  })

  it("has the person's menu: their name, Classic Interface and Sign Out, and no Admin, which is where they are", async () => {
    await renderApp('/')
    const menu = await openMenu('Alex Morgan')
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Alex Morgan', 'Classic Interface', 'Sign Out'])
    expect(screen.getByRole('menuitem', { name: 'Classic Interface' }).getAttribute('href')).toBe('/frontend.jsp')
  })
})
