// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'

export interface QuickLookState {
  /** The document Quick Look shows, by its number; undefined while it's closed */
  shown: number | undefined
  /** The document it was opened on, which has the focus back if it still does when it closes */
  from: number | undefined
  /** Opens Quick Look on a document: Space on its row, or a Preview button */
  open: (docNumber: number) => void
  /** Moves to another document of the list, as ‹ and › do */
  go: (docNumber: number) => void
  close: () => void
}

const Context = createContext<QuickLookState | null>(null)

/** Whether Quick Look is open and on which document, for the views' lists and the buttons that open it. */
export function QuickLookProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ shown: number; from: number }>()
  const value = useMemo<QuickLookState>(
    () => ({
      shown: state?.shown,
      from: state?.from,
      open: (docNumber) => setState({ shown: docNumber, from: docNumber }),
      go: (docNumber) => setState((now) => now && { ...now, shown: docNumber }),
      close: () => setState(undefined),
    }),
    [state],
  )
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function useQuickLook(): QuickLookState {
  const state = useContext(Context)
  if (!state) throw new Error('useQuickLook needs a QuickLookProvider')
  return state
}
