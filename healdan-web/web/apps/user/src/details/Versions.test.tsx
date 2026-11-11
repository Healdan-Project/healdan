// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
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

  it('offers more when there are more, and says it is loading them', () => {
    const more = vi.fn()
    const { rerender } = render(<Versions versions={rows} onShowMore={more} />)
    fireEvent.click(screen.getByRole('button', { name: 'Show More Versions' }))
    expect(more).toHaveBeenCalledTimes(1)

    rerender(<Versions versions={rows} onShowMore={more} loading />)
    expect((screen.getByRole('button', { name: 'Show More Versions' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('says so when there is nothing', () => {
    render(<Versions versions={[]} />)

    expect(screen.getByText('No versions yet.')).toBeTruthy()
  })
})
