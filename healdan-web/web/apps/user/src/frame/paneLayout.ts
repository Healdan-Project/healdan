// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// How wide the panes beside the list are drawn in a wide window, from what the person chose by dragging their dividers
// (or the defaults), and how far each divider may go. The list takes the rest, and is never squeezed for them: on a small
// window the panes' limits shrink instead. In a narrow window the panes fold away, and none of this applies.

import { clampWidth } from '@healdan/ui'

/** The left pane, Navigation */
export const LEFT = { min: 180, max: 420, initial: 248 }

/** The right region with the details or Filters; the docked reading pane has the same limits and a default of its own */
export const RIGHT = { min: 260, initial: 300 }

/**
 * The widest scrollbar that takes room, Windows' (macOS shows one too when set to, and Linux's are a little narrower): the
 * list scrolls itself, so its own scrollbar takes from its columns
 */
const SCROLLBAR = 17

/**
 * The least the list between them keeps: room for the three columns a list keeps beside the reading pane (spec section
 * 6.4), which for a search's hits are at least 416 pixels, the view's padding of 24 on each side, and the list's scrollbar.
 * The right region's border is inside its width (RightRegion.module.css).
 */
export const MAIN_MIN = 416 + 2 * 24 + SCROLLBAR

/** Each pane whose width is remembered: the left pane, the right region with the details or Filters, and the reading pane */
export type PaneName = 'left' | 'right' | 'preview'

/** What the person has chosen for each pane; one not chosen takes its default */
export type Chosen = Partial<Record<PaneName, number>>

export interface Limits {
  min: number
  max: number
}

export interface PaneLayout {
  left: number
  right: number
  preview: number
  leftLimits: Limits
  rightLimits: Limits
}

/** A page needs more room than a document's details do: a share of the window, as the reading pane had before it could be resized */
export function previewInitial(window: number): number {
  return Math.round(Math.min(640, Math.max(360, window * 0.42)))
}

/** A pane's width when the person hasn't chosen one, or has gone back to the default */
export function initialWidth(name: PaneName, window: number): number {
  return name === 'left' ? LEFT.initial : name === 'right' ? RIGHT.initial : previewInitial(window)
}

/** The width within the limits, in whole pixels, as the divider holds it */
export function within(width: number, { min, max }: Limits): number {
  return clampWidth(width, min, max)
}

/**
 * How far the left divider goes in a window this wide. Room for the right region at its narrowest is always left beside the
 * list, so the left pane keeps its width as the region opens and closes.
 */
export function leftLimits(window: number): Limits {
  return { min: LEFT.min, max: Math.max(LEFT.min, Math.min(LEFT.max, window - MAIN_MIN - RIGHT.min)) }
}

/** How far the right divider goes beside a left pane this wide: half the window at most, and what the list leaves */
export function rightLimits(window: number, left: number): Limits {
  return { min: RIGHT.min, max: Math.max(RIGHT.min, Math.min(Math.floor(window / 2), window - MAIN_MIN - left)) }
}

/**
 * The widths the panes are drawn at. A chosen width that no longer fits the window is drawn at the limit, and what was chosen
 * stays as it was, so a window made wide again gets it back. The left pane comes first, and the right region takes what the
 * list leaves it, down to its narrowest.
 */
export function layoutFor(window: number, chosen: Chosen): PaneLayout {
  const left = within(chosen.left ?? LEFT.initial, leftLimits(window))
  const right = rightLimits(window, left)
  return {
    left,
    right: within(chosen.right ?? RIGHT.initial, right),
    preview: within(chosen.preview ?? previewInitial(window), right),
    leftLimits: leftLimits(window),
    rightLimits: right,
  }
}
