// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useEffect } from 'react'

/** Where a panel of the chosen folder stands: nothing changed, changed and unsaved, or its save on its way */
export type EditState = 'clean' | 'dirty' | 'saving'

/** What a panel tells the Workspace Editor of its state, under the panel's own name (`access`, `values`, `rule`) */
export type ReportEdit = (part: string, state: EditState) => void

export const EditsContext = createContext<ReportEdit | null>(null)

/**
 * Tells the Workspace Editor where the panel stands, as it changes, and that it's clean once it goes, so the editor can
 * ask before another folder is chosen over an unsaved change, and wait for a save on its way.
 */
export function useEditState(part: string, dirty: boolean, saving: boolean) {
  const report = useContext(EditsContext)
  const state: EditState = saving ? 'saving' : dirty ? 'dirty' : 'clean'
  useEffect(() => {
    report?.(part, state)
  }, [report, part, state])
  useEffect(() => () => report?.(part, 'clean'), [report, part])
}
