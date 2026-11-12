// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { routes, select } from '../organising/testing'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'
import { resize, setViewport } from '../test/viewport'

// At 768 pixels the left pane and the right region fold away behind buttons in the top bar (spec 5): opening one overlays the
// list, and Esc closes it.
const navButton = () => screen.findByRole('button', { name: 'Navigation' })
const sideButton = () => screen.findByRole('button', { name: 'Details and Filters' })

describe('the frame at a tablet width', () => {
  it('keeps the left pane open and has no fold buttons at a desktop width', async () => {
    setViewport(1280)
    await renderApp('/folders/7', routes().all)
    expect(await screen.findByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Navigation' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Details and Filters' })).toBeNull()
  })

  it('folds the left pane behind a button of the top bar, which opens it over the list and Esc closes it', async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    const button = await navButton()
    expect(within(screen.getByRole('banner')).getByRole('button', { name: 'Navigation' })).toBe(button)
    // Folded away: not drawn, so not in the tab order either
    expect(screen.queryByRole('navigation', { name: 'Navigation' })).toBeNull()
    expect(button.getAttribute('aria-expanded')).toBe('false')

    await userEvent.click(button)
    const pane = await screen.findByRole('navigation', { name: 'Navigation' })
    expect(button.getAttribute('aria-expanded')).toBe('true')
    expect(within(pane).getByRole('link', { name: /Documents/ })).toBeTruthy()

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Navigation' })).toBeNull())
    expect(button.getAttribute('aria-expanded')).toBe('false')
    // The focus is back on the button that opened it
    expect(document.activeElement).toBe(button)
  })

  it('closes the left pane when a link in it is followed', async () => {
    setViewport(768)
    const { router } = await renderApp('/folders/7', routes().all)
    await userEvent.click(await navButton())
    const pane = await screen.findByRole('navigation', { name: 'Navigation' })
    await userEvent.click(within(pane).getByRole('link', { name: /Emails/ }))
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/recent/emails'))
    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Navigation' })).toBeNull())
  })

  it("folds the right region away, whatever is selected, and shows the details over the list on its button; Esc closes it", async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    await select(/Letter to Dunmere/)
    // Selected, and still folded: the list has the width
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
    const button = await sideButton()
    expect(button.getAttribute('aria-expanded')).toBe('false')

    await userEvent.click(button)
    expect(await screen.findByRole('region', { name: 'Details' })).toBeTruthy()
    expect(button.getAttribute('aria-expanded')).toBe('true')

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Details' })).toBeNull())
    expect(document.activeElement).toBe(button)
  })

  it('says what the region is for when nothing is selected and no Filters are open', async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    await userEvent.click(await sideButton())
    const region = await screen.findByRole('region', { name: 'Details and Filters' })
    expect(region.textContent).toContain('Select a document to see its details, or open Filters from the list.')
  })

  it("opens Filters over the list from the funnel, since that is what its person asked for", async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    await userEvent.click(await screen.findByRole('button', { name: 'Filters' }))
    expect(await screen.findByRole('region', { name: 'Filters' })).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Filters' })).toBeNull())
  })

  it('does not open the region at a click on a row, even when Filters were open before it was folded', async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    await userEvent.click(await screen.findByRole('button', { name: 'Filters' }))
    await userEvent.keyboard('{Escape}')
    await select(/Letter to Dunmere/)
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
    expect(screen.queryByRole('region', { name: 'Filters' })).toBeNull()
    expect(screen.queryByRole('region', { name: 'Details and Filters' })).toBeNull()
  })

  it('goes back to the open pane and region when the window widens', async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    await select(/Letter to Dunmere/)
    expect(screen.queryByRole('navigation', { name: 'Navigation' })).toBeNull()
    resize(1280)
    expect(await screen.findByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    expect(await screen.findByRole('region', { name: 'Details' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Navigation' })).toBeNull()
  })

  it('passes axe with the pane open', async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    await userEvent.click(await navButton())
    await screen.findByRole('navigation', { name: 'Navigation' })
    await expectNoAxeViolations(document.body)
  })

  describe('layers and focus', () => {
    it('moves the focus into the pane when it opens, and into the region when it opens', async () => {
      setViewport(768)
      await renderApp('/folders/7', routes().all)
      await select(/Letter to Dunmere/)
      await userEvent.click(await navButton())
      const pane = await screen.findByRole('navigation', { name: 'Navigation' })
      await waitFor(() => expect(pane.contains(document.activeElement)).toBe(true))
      await userEvent.keyboard('{Escape}')
      await userEvent.click(await sideButton())
      const region = await screen.findByRole('region', { name: 'Details' })
      await waitFor(() => expect(region.contains(document.activeElement), String(document.activeElement?.outerHTML.slice(0,150))).toBe(true))
    })

    it('closes one layer for each Esc, the one opened last first', async () => {
      setViewport(768)
      await renderApp('/folders/7', routes().all)
      await select(/Letter to Dunmere/)
      await userEvent.click(await navButton())
      await screen.findByRole('navigation', { name: 'Navigation' })
      await userEvent.click(await sideButton())
      await screen.findByRole('region', { name: 'Details' })

      await userEvent.keyboard('{Escape}')
      await waitFor(() => expect(screen.queryByRole('region', { name: 'Details' })).toBeNull())
      expect(screen.getByRole('navigation', { name: 'Navigation' })).toBeTruthy()
      await userEvent.keyboard('{Escape}')
      await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Navigation' })).toBeNull())
    })
  })
})
