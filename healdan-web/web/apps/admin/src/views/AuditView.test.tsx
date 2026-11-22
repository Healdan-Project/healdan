// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'
import { auditSearch } from './AuditView'

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')

describe('The Audit Trail', () => {
  it('opens at /admin/audit under its heading, the open screen in the pane', async () => {
    const { view } = await renderApp('/audit')
    expect(await screen.findByRole('heading', { level: 1, name: 'Audit Trail' })).toBeTruthy()
    expect(current()).toEqual([within(pane()).getByRole('link', { name: 'Audit Trail' })])
    await expectNoAxeViolations(view.container)
  })

  it('reads its kinds, each repeated, the person, the matter, the doc #, the days and the words from the address', async () => {
    const { router } = await renderApp('/audit?kind=folder&kind=document&user=6&matter=11&doc=10388&from=2026-11-01&to=2026-11-19&q=%20192.0.2%20')
    expect(router.state.matches.at(-1)?.search).toMatchObject({ kind: ['document', 'folder'], user: 6, matter: 11, doc: 10388, from: '2026-11-01', to: '2026-11-19', q: '192.0.2' })
  })

  it('reads one kind as a list of one', async () => {
    const { router } = await renderApp('/audit?kind=sign-in')
    expect(router.state.matches.at(-1)?.search).toMatchObject({ kind: ['sign-in'] })
  })

  it("keeps each kind once, in the server's order, and leaves out what isn't one of its values", () => {
    expect(auditSearch({ kind: ['administration', 'nope', 'sign-in', 'administration'] })).toEqual({ kind: ['sign-in', 'administration'] })
    expect(auditSearch({ kind: ['nope'], user: '-6', matter: '1.5', doc: '# 10388', from: '2026-02-30', to: '2026-1-5', q: '  ' })).toEqual({})
    expect(auditSearch({ kind: 'user', user: 6, matter: 11, doc: 10388, to: '2026-11-19' })).toEqual({ kind: ['user'], user: 6, matter: 11, doc: 10388, to: '2026-11-19' })
  })
})
