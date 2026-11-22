// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'
import { serverLogSearch } from './ServerLogView'

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')

describe('The Server Log', () => {
  it('opens at /admin/server-log under its heading, the open screen in the pane', async () => {
    const { view } = await renderApp('/server-log')
    expect(await screen.findByRole('heading', { level: 1, name: 'Server Log' })).toBeTruthy()
    expect(current()).toEqual([within(pane()).getByRole('link', { name: 'Server Log' })])
    await expectNoAxeViolations(view.container)
  })

  it('reads the log, the level and the words from the address', async () => {
    const { router } = await renderApp('/server-log?log=IndexerTask&level=warn&q=%20Indexer%20')
    expect(router.state.matches.at(-1)?.search).toMatchObject({ log: 'IndexerTask', level: 'warn', q: 'Indexer' })
  })

  it("leaves out what isn't one of its values: a log's name is never a path, nor dots alone", () => {
    for (const log of ['../dms.log', 'logs/DMS', 'a\\b', '..', '.', '']) expect(serverLogSearch({ log })).toEqual({})
    expect(serverLogSearch({ level: 'info', q: ' ' })).toEqual({})
    expect(serverLogSearch({ log: 'DMS', level: 'all' })).toEqual({ log: 'DMS', level: 'all' })
  })
})
