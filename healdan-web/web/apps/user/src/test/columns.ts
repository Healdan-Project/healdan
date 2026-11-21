// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect } from 'vitest'

/** A heading's edge's slider, which React Aria resizes by: out of the keys and of a screen reader's reading until a resize */
export const sliderOf = (heading: HTMLElement) => heading.querySelector<HTMLInputElement>('input[type="range"]')!

/**
 * Resizes a heading's column by the keys, as a person does (spec 4.3 of the list columns' design): Resize Column in the
 * heading's menu, here opened by a right-click, then the keys on its edge's slider, which has the focus while the resize
 * lasts. Enter, Esc or Tab among the keys ends it.
 */
export async function resizeByKeys(heading: HTMLElement, keys: string[]) {
  expect(fireEvent.contextMenu(heading)).toBe(false)
  await userEvent.click(await screen.findByRole('menuitem', { name: 'Resize Column' }))
  const slider = sliderOf(heading)
  await waitFor(() => expect(document.activeElement).toBe(slider))
  for (const key of keys) {
    fireEvent.keyDown(slider, { key })
    fireEvent.keyUp(slider, { key })
  }
}
