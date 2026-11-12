// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, render, renderHook, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RightRegionProvider, RightRegionView, useRightRegion } from './RightRegion'

let selected: number | undefined
vi.mock('../selection', () => ({ useSelection: () => ({ selected, select: () => {} }) }))

beforeEach(() => {
  selected = undefined
})

const wrapper = RightRegionProvider

describe('useRightRegion', () => {
  it('shows nothing with nothing selected and nothing asked for', () => {
    const { result } = renderHook(useRightRegion, { wrapper })
    expect(result.current.show).toBeNull()
  })

  it('shows the details of a selected document, by default', () => {
    selected = 10388
    const { result } = renderHook(useRightRegion, { wrapper })
    expect(result.current.show).toBe('details')
  })

  it('shows Filters when asked, even with nothing selected, and closes them back to what the selection shows', () => {
    const { result, rerender } = renderHook(useRightRegion, { wrapper })
    act(() => result.current.open('filters'))
    expect(result.current.show).toBe('filters')
    act(() => result.current.close())
    expect(result.current.show).toBeNull()
    selected = 10388
    act(() => result.current.open('filters'))
    rerender()
    expect(result.current.show).toBe('filters')
    act(() => result.current.close())
    expect(result.current.show).toBe('details')
  })

  it('goes from Filters to the details when asked, and the funnel brings Filters back', () => {
    selected = 10388
    const { result } = renderHook(useRightRegion, { wrapper })
    act(() => result.current.open('filters'))
    act(() => result.current.open('details'))
    expect(result.current.show).toBe('details')
    act(() => result.current.open('filters'))
    expect(result.current.show).toBe('filters')
  })

  it('refuses to be used outside its provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => renderHook(useRightRegion)).toThrow(/RightRegionProvider/)
    vi.restoreAllMocks()
  })
})

describe('RightRegionView', () => {
  const view = <RightRegionView filters={<p>the filters</p>} details={<p>the details</p>} />

  it('is not there at all when it has nothing to show', () => {
    const { container } = render(<RightRegionProvider>{view}</RightRegionProvider>)
    expect(container.innerHTML).toBe('')
  })

  it("is a region named for what it shows", () => {
    selected = 10388
    render(<RightRegionProvider>{view}</RightRegionProvider>)
    expect(screen.getByRole('region', { name: 'Details' }).textContent).toBe('the details')
  })

  it('draws nothing for the reading pane until it exists', () => {
    selected = 10388
    function Open() {
      const region = useRightRegion()
      return <button onClick={() => region.open('preview')}>open</button>
    }
    const { container } = render(
      <RightRegionProvider>
        <Open />
        <RightRegionView filters={<p>the filters</p>} />
      </RightRegionProvider>,
    )
    act(() => screen.getByRole('button').click())
    expect(container.querySelector('section')).toBeNull()
  })

  it('falls back to the details when the view offers no Filters, instead of showing nothing', () => {
    selected = 10388
    function Open() {
      const region = useRightRegion()
      return <button onClick={() => region.open('filters')}>open</button>
    }
    render(
      <RightRegionProvider>
        <Open />
        <RightRegionView details={<p>the details</p>} />
      </RightRegionProvider>,
    )
    act(() => screen.getByRole('button').click())
    expect(screen.getByRole('region', { name: 'Details' }).textContent).toBe('the details')
  })

  it('names the reading pane as itself', () => {
    selected = 10388
    function Open() {
      const region = useRightRegion()
      return <button onClick={() => region.open('preview')}>open</button>
    }
    render(
      <RightRegionProvider>
        <Open />
        <RightRegionView details={<p>the details</p>} preview={<p>the page</p>} />
      </RightRegionProvider>,
    )
    act(() => screen.getByRole('button').click())
    expect(screen.getByRole('region', { name: 'Preview' }).textContent).toBe('the page')
  })
})
