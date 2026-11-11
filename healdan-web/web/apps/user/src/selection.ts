// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { idFrom } from './queries'

/** The selected document's number, which lives in the address as ?sel= so Back, bookmarks and pasted links keep it. */
export function useSelection(): { selected: number | undefined; select: (docNumber: number | undefined) => void } {
  // Every route has the root's ?sel= (router.tsx), so its search is read from wherever this is used.
  const { sel } = useSearch({ strict: false })
  // On /doc/10388 and /doc/10388.2 the document the address names is the selection, whatever ?sel= says
  const { ref } = useParams({ strict: false })
  const navigate = useNavigate()
  const selected = idFrom(/^\d+/.exec(ref ?? '')?.[0]) ?? idFrom(sel)
  return {
    selected,
    // Replacing keeps a click on each row out of the history: Back leaves the view, not the last row.
    select: (docNumber) =>
      void navigate({
        to: '.',
        replace: true,
        search: (prev) => ({ ...prev, sel: docNumber === undefined ? undefined : String(docNumber) }),
      }),
  }
}
