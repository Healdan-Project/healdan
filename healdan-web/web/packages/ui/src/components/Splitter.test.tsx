// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { I18nProvider } from 'react-aria-components'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { RESIZING_ATTRIBUTE, Splitter, type SplitterProps } from './Splitter'

type Props = Omit<SplitterProps, 'value' | 'onResizeEnd' | 'onReset' | 'aria-label'> & Partial<Pick<SplitterProps, 'onResizeEnd' | 'onReset'>>

// A pane whose width the divider changes, as the frame keeps it: what a drag ends on, or a key moves it to
function Pane({ start = 248, onResizeEnd, onReset, ...props }: Props & { start?: number }) {
  const [width, setWidth] = useState(start)
  return (
    <Splitter
      aria-label="Resize Navigation"
      {...props}
      value={width}
      onResizeEnd={(next) => {
        setWidth(next)
        onResizeEnd?.(next)
      }}
      onReset={() => {
        setWidth(start)
        onReset?.()
      }}
    />
  )
}

const separator = () => screen.getByRole('separator', { name: 'Resize Navigation' })
const valueOf = () => Number(separator().getAttribute('aria-valuenow'))

async function press(keys: string) {
  separator().focus()
  await userEvent.keyboard(keys)
}

describe('Splitter', () => {
  it('is a focusable vertical separator whose value is the pane\'s width, within its limits', () => {
    render(<Pane pane="start" minValue={180} maxValue={420} controls="pane" />)
    const element = separator()
    expect(element.tabIndex).toBe(0)
    expect(element.getAttribute('aria-orientation')).toBe('vertical')
    expect(element.getAttribute('aria-valuenow')).toBe('248')
    expect(element.getAttribute('aria-valuemin')).toBe('180')
    expect(element.getAttribute('aria-valuemax')).toBe('420')
    expect(element.getAttribute('aria-valuetext')).toBe('248 pixels')
    expect(element.getAttribute('aria-controls')).toBe('pane')
  })

  it('shows the focus ring when the keyboard brings the focus to it', async () => {
    render(<Pane pane="start" minValue={180} maxValue={420} />)
    await userEvent.tab()
    expect(document.activeElement).toBe(separator())
    expect(separator().getAttribute('data-focus-visible')).toBe('true')
  })

  it('says a width that no longer fits as the limit, without keeping it, and moves on from there', async () => {
    const kept = vi.fn()
    render(<Splitter aria-label="Resize Navigation" pane="start" value={500} minValue={180} maxValue={420} onResizeEnd={kept} onReset={() => {}} />)
    expect(valueOf()).toBe(420)
    expect(kept).not.toHaveBeenCalled()
    await press('{End}')
    expect(kept).not.toHaveBeenCalled()
    await press('{ArrowLeft}')
    expect(kept).toHaveBeenCalledWith(404)
  })

  it('moves 16 pixels an arrow, 64 with Shift, widening a pane before it to the right', async () => {
    const kept = vi.fn()
    render(<Pane pane="start" minValue={180} maxValue={420} onResizeEnd={kept} />)
    await press('{ArrowRight}')
    expect(valueOf()).toBe(264)
    await press('{Shift>}{ArrowRight}{/Shift}')
    expect(valueOf()).toBe(328)
    await press('{ArrowLeft}')
    expect(valueOf()).toBe(312)
    expect(kept.mock.calls.map(([width]) => width)).toEqual([264, 328, 312])
  })

  it('widens a pane after it to the left', async () => {
    render(<Pane pane="end" start={300} minValue={260} maxValue={640} />)
    await press('{ArrowLeft}')
    expect(valueOf()).toBe(316)
    await press('{ArrowRight}{ArrowRight}')
    expect(valueOf()).toBe(284)
  })

  it('follows the direction of a page drawn right to left', async () => {
    render(
      <div dir="rtl">
        <Pane pane="start" minValue={180} maxValue={420} />
      </div>,
    )
    await press('{ArrowLeft}')
    expect(valueOf()).toBe(264)
    fireEvent.pointerDown(separator(), { pointerId: 1, button: 0, clientX: 600, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 580, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerUp(window, { pointerId: 1, clientX: 580, clientY: 300, pointerType: 'mouse' })
    expect(valueOf()).toBe(284)
  })

  it('follows the page, not the browser\'s language, which a page drawn left to right may not share', async () => {
    render(
      <I18nProvider locale="ar-EG">
        <Pane pane="start" minValue={180} maxValue={420} />
      </I18nProvider>,
    )
    await press('{ArrowRight}')
    expect(valueOf()).toBe(264)
    fireEvent.pointerDown(separator(), { pointerId: 1, button: 0, clientX: 600, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 620, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerUp(window, { pointerId: 1, clientX: 620, clientY: 300, pointerType: 'mouse' })
    expect(valueOf()).toBe(284)
  })

  it('stops at the limits, goes to them with Home and End, and keeps nothing when it is already there', async () => {
    const kept = vi.fn()
    render(<Pane pane="start" start={412} minValue={180} maxValue={420} onResizeEnd={kept} />)
    await press('{ArrowRight}')
    expect(valueOf()).toBe(420)
    await press('{ArrowRight}')
    expect(kept).toHaveBeenCalledTimes(1)
    await press('{Home}')
    expect(valueOf()).toBe(180)
    await press('{End}')
    expect(valueOf()).toBe(420)
  })

  it('goes back to the default width on Enter and on a double-click', async () => {
    const reset = vi.fn()
    render(<Pane pane="start" minValue={180} maxValue={420} onReset={reset} />)
    await press('{End}')
    await press('{Enter}')
    expect(valueOf()).toBe(248)
    await press('{End}')
    await userEvent.dblClick(separator())
    expect(valueOf()).toBe(248)
    expect(reset).toHaveBeenCalledTimes(2)
  })

  it('leaves other keys, and keys with a modifier, to the page', async () => {
    const kept = vi.fn()
    render(<Pane pane="start" minValue={180} maxValue={420} onResizeEnd={kept} />)
    await press('{ArrowDown}{Control>}{ArrowRight}{/Control}{Meta>}{ArrowRight}{/Meta}')
    expect(kept).not.toHaveBeenCalled()
  })

  it('is dragged by a pointer: each width as it moves, the last one kept, and the page told while it lasts', () => {
    const moved = vi.fn()
    const kept = vi.fn()
    render(<Pane pane="start" minValue={180} maxValue={420} onResize={moved} onResizeEnd={kept} />)
    const element = separator()
    fireEvent.pointerDown(element, { pointerId: 1, button: 0, clientX: 248, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 268, clientY: 300, pointerType: 'mouse' })
    expect(document.documentElement.hasAttribute(RESIZING_ATTRIBUTE)).toBe(true)
    expect(element.getAttribute('data-dragging')).toBe('true')
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 300, clientY: 310, pointerType: 'mouse' })
    // Past the limit, and back: it stays at the limit until the pointer comes back to where that was
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 900, clientY: 310, pointerType: 'mouse' })
    expect(valueOf()).toBe(420)
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 410, clientY: 310, pointerType: 'mouse' })
    expect(valueOf()).toBe(410)
    expect(kept).not.toHaveBeenCalled()
    fireEvent.pointerUp(window, { pointerId: 1, clientX: 410, clientY: 310, pointerType: 'mouse' })
    expect(moved.mock.calls.map(([width]) => width)).toEqual([268, 300, 420, 410])
    expect(kept).toHaveBeenCalledWith(410)
    expect(document.documentElement.hasAttribute(RESIZING_ATTRIBUTE)).toBe(false)
    expect(element.hasAttribute('data-dragging')).toBe(false)
    expect(valueOf()).toBe(410)
  })

  it('reports and keeps nothing for a press that only jitters, moves up and down, or comes back where it began', () => {
    const moved = vi.fn()
    const kept = vi.fn()
    // A width drawn at the limit, as a kept one is on a smaller window, which a press mustn't keep in its place
    render(<Splitter aria-label="Resize Navigation" pane="start" value={500} minValue={180} maxValue={420} onResize={moved} onResizeEnd={kept} onReset={() => {}} />)
    const element = separator()
    fireEvent.pointerDown(element, { pointerId: 1, button: 0, clientX: 420, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 420, clientY: 320, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 421, clientY: 320, pointerType: 'mouse' })
    expect(element.hasAttribute('data-dragging')).toBe(false)
    fireEvent.pointerUp(window, { pointerId: 1, clientX: 421, clientY: 320, pointerType: 'mouse' })
    expect(moved).not.toHaveBeenCalled()
    expect(kept).not.toHaveBeenCalled()

    fireEvent.pointerDown(element, { pointerId: 2, button: 0, clientX: 420, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 2, clientX: 380, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 2, clientX: 420, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerUp(window, { pointerId: 2, clientX: 420, clientY: 300, pointerType: 'mouse' })
    // The caller was told the drag came back, so it draws the pane as it was
    expect(moved.mock.calls.map(([width]) => width)).toEqual([380, 420])
    expect(kept).not.toHaveBeenCalled()
    expect(document.documentElement.hasAttribute(RESIZING_ATTRIBUTE)).toBe(false)
  })

  it('reports the width a drag has reached again when its limits change while it lasts', () => {
    const moved = vi.fn()
    const { rerender } = render(<Splitter aria-label="Resize Navigation" pane="start" value={248} minValue={180} maxValue={420} onResize={moved} onResizeEnd={() => {}} onReset={() => {}} />)
    fireEvent.pointerDown(separator(), { pointerId: 1, button: 0, clientX: 248, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 400, clientY: 300, pointerType: 'mouse' })
    expect(moved).toHaveBeenLastCalledWith(400)
    // The window narrowed: the caller drew what it kept, and is told the drag's width again, within the new limit
    rerender(<Splitter aria-label="Resize Navigation" pane="start" value={248} minValue={180} maxValue={360} onResize={moved} onResizeEnd={() => {}} onReset={() => {}} />)
    expect(moved).toHaveBeenLastCalledWith(360)
    expect(valueOf()).toBe(360)
    fireEvent.pointerUp(window, { pointerId: 1, clientX: 400, clientY: 300, pointerType: 'mouse' })
  })

  it('narrows a pane after it as a touch drags it to the right', () => {
    render(<Pane pane="end" start={300} minValue={260} maxValue={640} />)
    const element = separator()
    fireEvent.pointerDown(element, { pointerId: 7, button: 0, clientX: 900, clientY: 300, pointerType: 'touch' })
    fireEvent.pointerMove(window, { pointerId: 7, clientX: 930, clientY: 300, pointerType: 'touch' })
    fireEvent.pointerUp(window, { pointerId: 7, clientX: 930, clientY: 300, pointerType: 'touch' })
    expect(valueOf()).toBe(270)
  })

  it('keeps the width a drag reached when it goes before the drag ends', () => {
    const kept = vi.fn()
    const { unmount } = render(<Pane pane="start" minValue={180} maxValue={420} onResizeEnd={kept} />)
    fireEvent.pointerDown(separator(), { pointerId: 1, button: 0, clientX: 248, clientY: 300, pointerType: 'mouse' })
    fireEvent.pointerMove(window, { pointerId: 1, clientX: 280, clientY: 300, pointerType: 'mouse' })
    // As useMove's start does on iOS, which only its end, never reached here, undoes
    document.documentElement.style.webkitUserSelect = 'none'
    unmount()
    expect(kept).toHaveBeenCalledWith(280)
    expect(document.documentElement.hasAttribute(RESIZING_ATTRIBUTE)).toBe(false)
    expect(document.documentElement.style.webkitUserSelect).toBe('')
  })

  it('passes axe', async () => {
    const { container } = render(
      <div style={{ position: 'relative' }}>
        <nav id="pane" aria-label="Navigation" />
        <Pane pane="start" minValue={180} maxValue={420} controls="pane" />
      </div>,
    )
    await expectNoAxeViolations(container)
  })
})
