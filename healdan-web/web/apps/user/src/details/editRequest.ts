// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useSyncExternalStore } from 'react'

// A request from outside the details panel (the filing tray's Edit) to open a document's properties form. It is in
// memory only: the panel takes it once it has the properties, whichever way the document came to be selected.
let wanted: number | undefined
const listeners = new Set<() => void>()

function change(next: number | undefined) {
  wanted = next
  for (const listener of [...listeners]) listener()
}

export function requestEdit(docNumber: number): void {
  change(docNumber)
}

export function clearEditRequest(): void {
  if (wanted !== undefined) change(undefined)
}

/** Whether the properties form of the document is asked for */
export function useEditRequest(docNumber: number): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => void listeners.delete(listener)
    },
    () => wanted === docNumber,
  )
}
