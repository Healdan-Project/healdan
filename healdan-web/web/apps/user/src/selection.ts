// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate, useSearch } from '@tanstack/react-router'

/** The selected document's number, which lives in the address as ?sel= so Back, bookmarks and pasted links keep it. */
export function useSelection(): { selected: number | undefined; select: (docNumber: number | undefined) => void } {
  const { sel } = useSearch({ strict: false }) as { sel?: string }
  const navigate = useNavigate()
  const selected = sel !== undefined && /^\d+$/.test(sel) ? Number(sel) : undefined
  return {
    selected,
    // Replacing keeps a click on each row out of the history: Back leaves the view, not the last row.
    select: (docNumber) =>
      void navigate({
        to: '.',
        replace: true,
        search: ((prev: Record<string, unknown>) => ({ ...prev, sel: docNumber === undefined ? undefined : String(docNumber) })) as never,
      }),
  }
}
