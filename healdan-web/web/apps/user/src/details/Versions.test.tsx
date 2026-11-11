// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { versionRow } from '../test/fakeApi'
import { Versions } from './Versions'

const rows = [
  versionRow({ version: '2.1', fileVersion: '2.1', kind: 'snapshot' }),
  versionRow({ version: '2.0', fileVersion: '2.0' }),
  versionRow({ version: '1.0', fileVersion: '1.0', by: null, date: null }),
]

describe('Versions', () => {
  it('marks the version asked for as the current one in the list', () => {
    render(<Versions versions={rows} selected="2.0" />)

    const items = within(screen.getByRole('group', { name: 'Versions' })).getAllByRole('listitem')
    expect(items.map((item) => item.getAttribute('aria-current'))).toEqual([null, 'true', null])
  })

  it('marks none when none is asked for', () => {
    render(<Versions versions={rows} />)

    const items = within(screen.getByRole('group', { name: 'Versions' })).getAllByRole('listitem')
    expect(items.every((item) => item.getAttribute('aria-current') === null)).toBe(true)
  })

  it("copes with a version nobody saved and one that has no date", () => {
    render(<Versions versions={rows} />)

    const last = within(screen.getByRole('group', { name: 'Versions' })).getAllByRole('listitem')[2]!
    expect(last.textContent).toBe('Version 1')
  })

  it('says so when there is nothing', () => {
    render(<Versions versions={[]} />)

    expect(screen.getByText('No versions yet.')).toBeTruthy()
  })
})
