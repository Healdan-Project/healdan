// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from './test/axe'
import { renderApp } from './test/renderApp'

describe('The Admin app', () => {
  it('draws the Dashboard at /admin/, in the frame that names the app', async () => {
    const { router, view } = await renderApp('/')
    expect(router.history.location.pathname).toBe('/admin/')
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
    expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Healdan Admin' })).toBeTruthy()
    await screen.findByRole('region', { name: 'Needs Attention' })
    await expectNoAxeViolations(view.container)
  })

  it('ignores parameters an address has that no page reads', async () => {
    const { router } = await renderApp('/?zzz=1&sel=10388&q=%22charter%22')
    expect(router.history.location.pathname).toBe('/admin/')
    // Plain strings, as the User app's addresses carry them, and nothing refused
    expect(router.state.location.search).toEqual({ zzz: '1', sel: '10388', q: '"charter"' })
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
  })
})
