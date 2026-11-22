// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect } from 'vitest'

/** Every document column a folder's list and a search of documents offer, as kept in a layout's `shown` */
export const EVERY_COLUMN = ['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type', 'class', 'subclass', 'typist', 'from', 'to', 'sent']

/**
 * The document columns beyond Title, Doc #, Version, Author and Edit Date, each with its sort's name and its first click's
 * order: a date newest first, the others A to Z (spec 4.2 of the list columns' design)
 */
export const FIRST_SORTS: [name: string, sort: string, order: 'asc' | 'desc'][] = [
  ['Matter', 'matter', 'asc'],
  ['Client', 'client', 'asc'],
  ['Location', 'location', 'asc'],
  ['Date Filed', 'filed', 'desc'],
  ['Size', 'size', 'asc'],
  ['File Type', 'type', 'asc'],
  ['Class', 'class', 'asc'],
  ['Subclass', 'subclass', 'asc'],
  ['Typist', 'typist', 'asc'],
  ['From', 'from', 'asc'],
  ['To', 'to', 'asc'],
  ['Sent', 'sent', 'desc'],
]

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
