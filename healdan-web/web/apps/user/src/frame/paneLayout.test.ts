// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { LEFT, MAIN_MIN, RIGHT, initialWidth, layoutFor, leftLimits, previewInitial, rightLimits, within } from './paneLayout'

describe('the panes\' widths', () => {
  it('start at the defaults, with the reading pane a share of the window', () => {
    expect(layoutFor(1280, {})).toMatchObject({ left: 248, right: 300, preview: 538 })
    expect(layoutFor(1920, {}).preview).toBe(640)
    expect(previewInitial(800)).toBe(360)
    expect(initialWidth('left', 1600)).toBe(LEFT.initial)
    expect(initialWidth('right', 1600)).toBe(RIGHT.initial)
    expect(initialWidth('preview', 1600)).toBe(previewInitial(1600))
  })

  it('keep the list room for the columns it keeps beside the reading pane, its padding and its own scrollbar', () => {
    // A search's hits: 220, 80 and 116 for the columns, 24 each side, and 17 for Windows' scrollbar
    expect(MAIN_MIN).toBe(220 + 80 + 116 + 2 * 24 + 17)
    // So on the narrowest wide window the details are a little narrower than their usual 300
    expect(layoutFor(1024, {}).right).toBe(1024 - MAIN_MIN - LEFT.initial)
  })

  it('let the left pane go from 180 to 420 pixels on a wide window', () => {
    expect(leftLimits(1600)).toEqual({ min: 180, max: 420 })
    expect(layoutFor(1600, { left: 100 }).left).toBe(180)
    expect(layoutFor(1600, { left: 1000 }).left).toBe(420)
    expect(layoutFor(1600, { left: 333.4 }).left).toBe(333)
  })

  it('let the right region go from 260 pixels to half the window', () => {
    expect(rightLimits(1600, 248)).toEqual({ min: 260, max: 800 })
    expect(layoutFor(1600, { right: 900 }).right).toBe(800)
    expect(layoutFor(1600, { preview: 100 }).preview).toBe(260)
  })

  it('shrink the panes\' limits on a small window, so the list keeps its room', () => {
    // 1,024 pixels is the narrowest wide window: room for the region at its narrowest is kept beside the list
    expect(leftLimits(1024)).toEqual({ min: 180, max: 1024 - MAIN_MIN - RIGHT.min })
    const layout = layoutFor(1024, { left: 420, right: 600, preview: 600 })
    expect(layout.left).toBe(1024 - MAIN_MIN - RIGHT.min)
    expect(layout.right).toBe(260)
    expect(1024 - layout.left - layout.right).toBe(MAIN_MIN)
    // The list never has less than its least, whatever was chosen
    for (const window of [1024, 1100, 1280, 1440, 1920]) {
      for (const chosen of [{}, { left: 420, right: 2000, preview: 2000 }, { left: 180, right: 2000, preview: 2000 }]) {
        const { left, right, preview } = layoutFor(window, chosen)
        expect(window - left - right, `${window} ${JSON.stringify(chosen)}`).toBeGreaterThanOrEqual(MAIN_MIN)
        expect(window - left - preview, `${window} ${JSON.stringify(chosen)}`).toBeGreaterThanOrEqual(MAIN_MIN)
      }
    }
  })

  it('give the right region what the left pane leaves, and the left pane first', () => {
    // A wide left pane narrows what the region may take, and a narrower one gives it back
    expect(layoutFor(1100, { left: 340, right: 500 }).right).toBe(1100 - MAIN_MIN - 340)
    expect(layoutFor(1100, { left: 180, right: 500 }).right).toBe(1100 - MAIN_MIN - 180)
  })

  it('never let a limit fall below the least, even on a window too small for both panes', () => {
    expect(leftLimits(600)).toEqual({ min: 180, max: 180 })
    expect(rightLimits(600, 180)).toEqual({ min: 260, max: 260 })
    expect(within(5000, { min: 260, max: 260 })).toBe(260)
  })
})
