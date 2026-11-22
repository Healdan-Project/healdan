// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'
import { errorsSearch } from './ErrorsView'

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')

describe('Errors', () => {
  it('opens at /admin/errors under its heading, the open screen in the pane, with the count of the errors to resolve', async () => {
    const { view } = await renderApp('/errors')
    expect(await screen.findByRole('heading', { level: 1, name: 'Errors' })).toBeTruthy()
    const entry = await within(pane()).findByRole('link', { name: 'Errors, 14 need attention' })
    expect(current()).toEqual([entry])
    await expectNoAxeViolations(view.container)
  })

  it('reads how far back it goes, its words, its area, Unresolved and the open fault from the address', async () => {
    const { router } = await renderApp('/errors?window=7d&q=%20timed%20out%20&area=Search%20Index&unresolved=true&id=12')
    expect(router.state.matches.at(-1)?.search).toMatchObject({ window: '7d', q: 'timed out', area: 'Search Index', unresolved: true, id: 12 })
  })

  it("leaves out what isn't one of its values, and takes back what it gave the router before", () => {
    expect(errorsSearch({ window: '2h', q: '   ', area: 'search index', unresolved: 'yes', id: '-3', offset: '100' })).toEqual({})
    expect(errorsSearch({ unresolved: 'false', id: 'x' })).toEqual({})
    expect(errorsSearch({ window: '1h', area: 'Sign-In', unresolved: true, id: 12 })).toEqual({ window: '1h', area: 'Sign-In', unresolved: true, id: 12 })
  })
})
