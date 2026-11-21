// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useState } from 'react'

/** A search waits this long for the typing to pause, and sends at most TYPED_MAX characters, as the User app's do */
export const TYPING_DELAY_MS = 150
export const TYPED_MAX = 100

/** What the text asks the server for: trimmed, at most TYPED_MAX characters, '' for nothing */
export function typed(text: string): string {
  return text.trim().slice(0, TYPED_MAX).trim()
}

/** The value once it has stayed the same for `delay` milliseconds */
export function useDebounced<V>(value: V, delay: number = TYPING_DELAY_MS): V {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return settled
}
