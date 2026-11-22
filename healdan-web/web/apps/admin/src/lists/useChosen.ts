// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'

/**
 * What the person picked for a filter here (null for none), shown at once: the address, which names it by id, follows a
 * moment later, and a picker that waited for it would leave its list open. It's forgotten when the address names anything
 * else, as the pane's entry does, and undefined until something is picked.
 */
export function useChosen<T extends { id: number }>(id: number | undefined) {
  const [chosen, setChosen] = useState<T | null | undefined>(undefined)
  const [seen, setSeen] = useState(id)
  if (id !== seen) {
    setSeen(id)
    if (chosen !== undefined && chosen?.id !== id) setChosen(undefined)
  }
  return [chosen, setChosen] as const
}
