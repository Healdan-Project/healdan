// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, useState } from 'react'
import { TYPING_DELAY_MS, typed } from '../pickers/typing'

/**
 * A list's words field (the Matters list's, the Clients list's): what is typed shows as typed, and goes into the address
 * (`send`, trimmed) once the typing pauses. When the address's words change from outside, as the pane's entry takes them
 * off, the field follows them; its own, which arrive a moment after they're sent, leave what's typed since as it is.
 */
export function useWords(q: string, send: (q: string) => void) {
  const [text, setText] = useState(q)
  // The words this field last put in the address, and the address's words when last drawn
  const [sent, setSent] = useState(q)
  const [seen, setSeen] = useState(q)
  if (q !== seen) {
    setSeen(q)
    if (q !== sent) {
      setText(q)
      setSent(q)
    }
  }
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(timer.current), [])

  function type(next: string) {
    setText(next)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      const words = typed(next)
      setSent(words)
      send(words)
    }, TYPING_DELAY_MS)
  }

  function clear() {
    clearTimeout(timer.current)
    setText('')
    setSent('')
  }

  return { text, type, clear }
}
