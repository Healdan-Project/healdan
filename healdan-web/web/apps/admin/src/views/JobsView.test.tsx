// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')

describe('Background Jobs', () => {
  it('opens at /admin/jobs under its heading, the open screen in the pane', async () => {
    const { view } = await renderApp('/jobs')
    expect(await screen.findByRole('heading', { level: 1, name: 'Background Jobs' })).toBeTruthy()
    expect(current()).toEqual([within(pane()).getByRole('link', { name: 'Background Jobs' })])
    await expectNoAxeViolations(view.container)
  })
})
