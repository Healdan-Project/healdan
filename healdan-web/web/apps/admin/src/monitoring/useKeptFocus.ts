// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, type RefObject } from 'react'
import { focusLost } from '../focus'

/** What had the focus in the list, the job of its row, and whether a dialog opened from it has the focus now */
interface Held {
  element: HTMLElement
  job: string
  dialog: boolean
}

/** The row of a job, by its name, which `data-job` holds */
function rowOf(list: HTMLElement | null, job: string): HTMLElement | undefined {
  return [...(list?.querySelectorAll<HTMLElement>('tr[data-job]') ?? [])].find((row) => row.dataset.job === job)
}

/**
 * Keeps the focus on the page when a row's button that had it goes: Run Now once its job runs, Stop once the run is asked
 * to stop or has ended, whether the change came from the button itself or a new read. The focus goes to the button that
 * says it follows the one that went (`data-follows`, naming the other's `data-action`: Stop, in Run Now's place), else to
 * the job's name (its row's `th`, with `tabIndex={-1}`), or to `fallback` when the row went too. A button kept as its
 * words change (Enable becoming Disable) keeps the focus as it is. A dialog opened from a row gives the focus back to its
 * button once it closes, or to where the button's would go once the button has gone. Focus the person moved elsewhere,
 * or off the page's controls, is left where it is.
 *
 * Call it in the component that draws the list, so it looks again after each of its renders.
 */
export function useKeptFocus(list: RefObject<HTMLElement | null>, fallback: RefObject<HTMLElement | null>) {
  const held = useRef<Held | undefined>(undefined)

  useEffect(() => {
    let left: ReturnType<typeof setTimeout> | undefined
    function onFocusIn(event: FocusEvent) {
      const target = event.target
      if (!(target instanceof HTMLElement)) return
      const row = list.current?.contains(target) ? target.closest<HTMLElement>('tr[data-job]') : null
      if (row?.dataset.job !== undefined) held.current = { element: target, job: row.dataset.job, dialog: false }
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
  }, [list])

  useEffect(() => {
    const was = held.current
    if (was === undefined || !focusLost()) return
    if (was.element.isConnected) {
      // A dialog opened from it has closed
      if (was.dialog) was.element.focus()
      return
    }
    const row = rowOf(list.current, was.job)
    const action = was.element.dataset.action
    const follower = action === undefined ? null : (row?.querySelector<HTMLElement>(`[data-follows="${action}"]`) ?? null)
    ;(follower ?? row?.querySelector<HTMLElement>('th') ?? fallback.current)?.focus()
  })
}
