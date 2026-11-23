// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, type RefObject } from 'react'
import { focusLost } from '../focus'

/** What had the focus in the list, the key of its row and where the row was, and whether a dialog opened from it has the focus now */
interface Held {
  element: HTMLElement
  job: string
  index: number
  dialog: boolean
}

export interface KeptFocusOptions {
  /** The data attribute that holds each row's key: `job`, as Background Jobs' rows have `data-job`, by default */
  attribute?: string
  /** What in a row takes the focus once the button that had it goes: its `th` by default */
  name?: string
  /**
   * Once the row goes too, the row that comes to its place (the next, or the last when it was last) takes the focus, at its
   * name, rather than `fallback`, as in a list a button's press takes rows from (the Search Index's Couldn't Be Indexed)
   */
  toNextRow?: boolean
}

/** The rows, each marked by the attribute */
function rowsOf(list: HTMLElement | null, attribute: string): HTMLElement[] {
  return [...(list?.querySelectorAll<HTMLElement>(`[data-${attribute}]`) ?? [])]
}

/** The row of a key, which the attribute holds */
function rowOf(list: HTMLElement | null, attribute: string, key: string): HTMLElement | undefined {
  return rowsOf(list, attribute).find((row) => row.getAttribute(`data-${attribute}`) === key)
}

/**
 * Keeps the focus on the page when a row's button that had it goes (`options` say what marks a row; Background Jobs' by
 * default): Run Now once its job runs, Stop once the run is asked
 * to stop or has ended, whether the change came from the button itself or a new read. The focus goes to the button that
 * says it follows the one that went (`data-follows`, naming the other's `data-action`: Stop, in Run Now's place), else to
 * the job's name (its row's `th`, with `tabIndex={-1}`), or to `fallback` when the row went too. A button kept as its
 * words change (Enable becoming Disable) keeps the focus as it is. A dialog opened from a row gives the focus back to its
 * button once it closes, as React Aria's own restore does where it runs, or to where the button's would go once the
 * button has gone. Focus the person moved elsewhere, or off the page's controls, is left where it is.
 *
 * Call it in the component that draws the list, so it looks again after each of its renders.
 */
export function useKeptFocus(list: RefObject<HTMLElement | null>, fallback: RefObject<HTMLElement | null>, options: KeptFocusOptions = {}) {
  const { attribute = 'job', name = 'th', toNextRow = false } = options
  const held = useRef<Held | undefined>(undefined)

  useEffect(() => {
    let left: ReturnType<typeof setTimeout> | undefined
    function onFocusIn(event: FocusEvent) {
      const target = event.target
      if (!(target instanceof HTMLElement)) return
      const row = list.current?.contains(target) ? target.closest<HTMLElement>(`[data-${attribute}]`) : null
      const key = row?.getAttribute(`data-${attribute}`)
      if (row && key !== null && key !== undefined) held.current = { element: target, job: key, index: rowsOf(list.current, attribute).indexOf(row), dialog: false }
      // A dialog opened from the row holds the focus for a while; anything else is the person's own choice
      else if (target.closest('[role="dialog"], [role="alertdialog"]') !== null) {
        if (held.current !== undefined) held.current.dialog = true
      } else held.current = undefined
    }
    function onFocusOut(event: FocusEvent) {
      const target = event.target
      if (!(target instanceof HTMLElement) || held.current?.element !== target) return
      // The focus left for nothing while the button stayed: the person moved it off the controls. A button taken away
      // is no longer on the page by then, however the browser said it lost the focus.
      clearTimeout(left)
      left = setTimeout(() => {
        if (held.current?.element === target && target.isConnected && focusLost()) held.current = undefined
      })
    }
    document.addEventListener('focusin', onFocusIn)
    document.addEventListener('focusout', onFocusOut)
    return () => {
      clearTimeout(left)
      document.removeEventListener('focusin', onFocusIn)
      document.removeEventListener('focusout', onFocusOut)
    }
  }, [list, attribute])

  useEffect(() => {
    const was = held.current
    if (was === undefined || !focusLost()) return
    if (was.element.isConnected) {
      // A dialog opened from it has closed. React Aria's dialog gives the focus back to its trigger itself, in an animation
      // frame, and this only covers where that restore doesn't run, as in jsdom; the browser specs check the focus there
      // after Stop, Disable and Cancel
      if (was.dialog) was.element.focus()
      return
    }
    const row = rowOf(list.current, attribute, was.job)
    const action = was.element.dataset.action
    const follower = action === undefined ? null : (row?.querySelector<HTMLElement>(`[data-follows="${action}"]`) ?? null)
    const rows = rowsOf(list.current, attribute)
    const instead = row === undefined && toNextRow ? rows[Math.min(was.index, rows.length - 1)] : undefined
    ;(follower ?? (row ?? instead)?.querySelector<HTMLElement>(name) ?? fallback.current)?.focus()
  })
}
