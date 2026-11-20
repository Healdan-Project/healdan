// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import type { adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { useDebounced } from '../pickers/typing'
import { adminKeys } from '../queries'

type A = adminComponents['schemas']

export interface MatterCheckState {
  /** What creating the form as it was last checked would refuse; none before the first answer, and none when it failed */
  problems: A['MatterProblem'][]
  /**
   * Whether the problems are the form's as it is now: its last change has been checked, and no check is on its way. A check
   * that failed counts, so Create can still try, and the server still refuses what it would.
   */
  current: boolean
  /** Ask again for the form as it is, as after putting a problem right elsewhere */
  recheck: () => Promise<void>
}

/** A draft with nothing in it, which there's nothing to check in */
function isEmpty(draft: A['MatterDraft']): boolean {
  return Object.values(draft).every((value) => value === null || value === undefined)
}

/**
 * New Matter's check (spec 8.3): `POST /v1/admin/matters/check` with the form as it stands, once the typing pauses, so
 * what Create would refuse is said before its button (spec 7.2). The last answer stays while the next is asked, so the
 * problems don't blink; `current` says whether they're the form's as it is now.
 */
export function useMatterCheck(draft: A['MatterDraft']): MatterCheckState {
  const api = useApi()
  const queryClient = useQueryClient()
  // By its text, so a draft made afresh at each render is the same draft
  const asked = JSON.stringify(draft)
  const settled = useDebounced(asked)
  const sent = JSON.parse(settled) as A['MatterDraft']
  const check = useQuery({
    queryKey: [...adminKeys.matterCheck, settled],
    queryFn: () => api.send<A['MatterCheck']>('POST', '/v1/admin/matters/check', sent),
    enabled: !isEmpty(sent),
    placeholderData: keepPreviousData,
    // A check is of that moment: another administrator may take a number or change a Store meanwhile
    staleTime: 0,
    gcTime: 0,
  })
  return {
    // An emptied form has nothing in it to refuse, whatever the last check found
    problems: isEmpty(sent) ? [] : (check.data?.problems ?? []),
    current: settled === asked && !check.isFetching,
    recheck: () => queryClient.invalidateQueries({ queryKey: adminKeys.matterCheck }),
  }
}
