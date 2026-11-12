// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { page, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

describe('Recent Documents', () => {
  it('is the start page, in the order the server gives (newest first)', async () => {
    const { api } = await renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Recent Documents' })).toBeTruthy()
    const list = await screen.findByRole('list', { name: 'Recent Documents' })
    const names = within(list).getAllByRole('listitem').map((li) => li.textContent)
    expect(names[0]).toContain('Letter to Dunmere Pryce.docx')
    expect(names[1]).toContain('Letter from Dunmere Pryce.pdf')
    expect(api.callsTo('GET /v1/documents/recent')).toHaveLength(1)
  })

  it('is also at /recent/documents', async () => {
    await renderApp('/recent/documents')
    expect(await screen.findByRole('list', { name: 'Recent Documents' })).toBeTruthy()
  })

  it('says so when there are none', async () => {
    await renderApp('/', { 'GET /v1/documents/recent': page([]) })
    expect(await screen.findByText('No recent documents.')).toBeTruthy()
  })

  it('puts the selected row in the address', async () => {
    const { router } = await renderApp('/')
    const list = await screen.findByRole('list', { name: 'Recent Documents' })
    fireEvent.click(within(list).getByRole('button', { name: /Letter from Dunmere Pryce/ }))
    await screen.findByRole('button', { name: /Letter from Dunmere Pryce/, pressed: true })
    expect(router.history.location.search).toBe('?sel=10388')
  })

  it('reads the selected row from the address', async () => {
    await renderApp('/?sel=10431')
    expect(await screen.findByRole('button', { name: /Letter to Dunmere Pryce/, pressed: true })).toBeTruthy()
  })

  it('shows a failure with Try Again, which asks again', async () => {
    let fail = true
    const { api } = await renderApp('/', {
      'GET /v1/documents/recent': () => {
        if (fail) return problem(500, 'server-error')
        return page([])
      },
    })
    const retry = await screen.findByRole('button', { name: 'Try Again' })
    fail = false
    fireEvent.click(retry)
    expect(await screen.findByText('No recent documents.')).toBeTruthy()
    expect(api.callsTo('GET /v1/documents/recent').length).toBe(2)
  })

  it('passes axe', async () => {
    const { view } = await renderApp('/')
    await screen.findByRole('list', { name: 'Recent Documents' })
    await expectNoAxeViolations(view.container)
  })
})
