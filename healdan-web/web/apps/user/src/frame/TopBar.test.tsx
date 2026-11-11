// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'

describe('the top bar', () => {
  it('shows the mark and name, the scope picker joined to the search box, Help and the person', async () => {
    await renderApp()
    const bar = screen.getByRole('banner')
    expect(bar.textContent).toContain('Healdan')
    expect(screen.getByRole('button', { name: /Search In/ })).toBeTruthy()
    expect(screen.getByRole('combobox', { name: 'Search' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Help' })).toBeTruthy()
    expect(await screen.findByRole('button', { name: 'Priya Nair' })).toBeTruthy()
    expect(bar.textContent).not.toContain('Helper')
  })

  it('jumps to the search box on /, but not while typing elsewhere', async () => {
    await renderApp()
    const box = screen.getByRole('combobox', { name: 'Search' })
    fireEvent.keyDown(document.body, { key: '/' })
    expect(document.activeElement).toBe(box)
    const other = document.createElement('input')
    document.body.append(other)
    other.focus()
    fireEvent.keyDown(other, { key: '/' })
    expect(document.activeElement).toBe(other)
    other.remove()
  })

  it('searches in the chosen scope at /search, with the words in the address', async () => {
    const { router } = await renderApp()
    const box = screen.getByRole('combobox', { name: 'Search' })
    fireEvent.change(box, { target: { value: 'charter 123' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/search'))
    expect(new URLSearchParams(router.history.location.search).get('q')).toBe('charter 123')
    expect(new URLSearchParams(router.history.location.search).get('scope')).toBe('documents')
  })

  it('does nothing for a blank search, which the server refuses', async () => {
    const { router } = await renderApp()
    const box = screen.getByRole('combobox', { name: 'Search' })
    fireEvent.change(box, { target: { value: '   ' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(router.history.location.pathname).toBe('/app/')
  })

  it('takes its scope from the Recent entry that is open', async () => {
    await renderApp('/recent/emails')
    expect(screen.getByRole('button', { name: /Search In.*Emails/ })).toBeTruthy()
  })

  it('passes axe', async () => {
    await renderApp()
    await screen.findByRole('button', { name: 'Priya Nair' })
    await expectNoAxeViolations(screen.getByRole('banner'))
  })
})
