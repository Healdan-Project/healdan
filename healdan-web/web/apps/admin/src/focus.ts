// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, type RefObject } from 'react'

/** Whether the focus is on nothing, as when what had it was taken off the page */
export function focusLost(): boolean {
  const active = document.activeElement
  return active === null || active === document.body
}

/**
 * Moves the focus to `target`, such as its section's heading (with `tabIndex={-1}`), once `gone` turns true, when the
 * focus went with what went: a button pressed that the change it made took off the page, as a Retry that leaves nothing
 * stuck. Focus the person has moved elsewhere meanwhile stays where it is.
 */
export function useFocusWhenGone(gone: boolean, target: RefObject<HTMLElement | null>) {
  const before = useRef(gone)
  useEffect(() => {
    const was = before.current
    before.current = gone
    if (gone && !was && focusLost()) target.current?.focus()
  }, [gone, target])
}
