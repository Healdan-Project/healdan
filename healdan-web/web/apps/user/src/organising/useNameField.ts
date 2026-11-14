// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useId, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react'

/**
 * A name edited in place: a document's title in a list (spec 6.7) or a favorite's name in My Favorites (spec 6.1). It
 * holds the field's text and refusal, and what its keys do. Enter calls `onEnter`. Esc cancels, but not while a save is
 * under way, which can't be taken back. Leaving the field with nothing in it, or with a refusal showing, cancels;
 * otherwise it calls `onLeave`. `onDone` is called once, when the field is finished, and told whether the focus is the
 * field's to give back; never once the field has gone, so a save that ends late can't close another field.
 */
export function useNameField({ initial, onEnter, onLeave, onDone }: { initial: string; onEnter: () => void; onLeave: () => void; onDone: (focus: boolean) => void }) {
  // The name the field opened with: what it's compared with, whatever the list says meanwhile
  const [opened] = useState(initial)
  const [text, setText] = useState(initial)
  const [error, setError] = useState<string>()
  const input = useRef<HTMLInputElement>(null)
  const errorId = useId()
  // Enter saves and the field goes, which makes it lose the focus: that must not save again
  const finished = useRef(false)
  const saving = useRef(false)

  useEffect(() => {
    finished.current = false
    return () => {
      finished.current = true
    }
  }, [])

  /** Finishes the field; nothing while a save is under way, which finishes it itself */
  function stop(focus: boolean) {
    if (finished.current || saving.current) return
    finished.current = true
    onDone(focus)
  }

  /**
   * Runs `work`, one at a time and never once the field is finished: then the field is done, or, when `work` throws, it
   * stays open with `refusal`'s words, and the focus back in it after Enter
   */
  async function run(fromKey: boolean, work: () => Promise<void>, refusal: (failure: unknown) => string) {
    if (finished.current || saving.current) return
    saving.current = true
    try {
      await work()
    } catch (failure) {
      saving.current = false
      if (finished.current) return
      setError(refusal(failure))
      if (fromKey) input.current?.focus()
      return
    }
    saving.current = false
    stop(fromKey)
  }

  const props = {
    ref: input,
    value: text,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error ? errorId : undefined,
    onChange: (event: ChangeEvent<HTMLInputElement>) => {
      setText(event.target.value)
      setError(undefined)
    },
    onKeyDown: (event: KeyboardEvent<HTMLInputElement>) => {
      // Not the list's or the pane's: Space, Delete, Shift+F10 and the arrows are the field's own while it has the focus
      event.stopPropagation()
      if (event.key === 'Enter') {
        event.preventDefault()
        onEnter()
      } else if (event.key === 'Escape') {
        event.preventDefault()
        stop(true)
      }
    },
    onBlur: () => {
      // Leaving the field with nothing changed, nothing in it, or a refusal showing is a cancel; with a new name, a save
      if (text.trim() === '' || error) stop(false)
      else onLeave()
    },
  }

  return { opened, text, error, setError, errorId, input, props, stop, run }
}
