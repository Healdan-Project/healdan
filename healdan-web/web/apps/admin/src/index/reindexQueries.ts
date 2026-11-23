// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import type { adminComponents } from '@healdan/api'
import { useApi, useRecentMatters } from '@healdan/shell'
import { MATTER_SEARCH_LIMIT } from '../pickers/MatterPicker'
import { useDebounced } from '../pickers/typing'
import { adminKey } from '../queries'

type A = adminComponents['schemas']

/**
 * The dialog's previews, by the scope asked: apart from `['admin', 'reindex']`, the reindexes' own keys (indexKeys), so a
 * start's re-read of every reindex never asks a preview again as the dialog closes
 */
export const previewKey = ['admin', 'reindex-preview'] as const

export interface PreviewState {
  /** The last answer, for this choice; kept while the next is asked, so the count doesn't blink */
  preview: A['ReindexPreview'] | undefined
  /** Whether that answer is the choice's as it is now, with nothing on its way */
  current: boolean
  /** Why the last ask failed, as a target that has gone (`404`) */
  failure: unknown
}

/**
 * The Reindex dialog's count, estimate and refusal (`POST /v1/admin/reindex/preview`), asked once a choice is complete and
 * again once a change to it settles (the typing paused, as the pickers wait), its last answer kept while the next comes,
 * but only for the same kind of choice: a matter's count is never shown under Store.
 */
export function useReindexPreview(scope: A['ReindexScope'] | undefined): PreviewState {
  const api = useApi()
  // By its text, so a scope made afresh at each render is the same scope
  const asked = scope === undefined ? '' : JSON.stringify(scope)
  const settled = useDebounced(asked)
  const sent = settled === '' ? undefined : (JSON.parse(settled) as A['ReindexScope'])
  const answer = useQuery({
    queryKey: [...previewKey, settled],
    queryFn: () => api.send<A['ReindexPreview']>('POST', '/v1/admin/reindex/preview', sent),
    enabled: sent !== undefined,
    placeholderData: (previous, previousQuery) => {
      const before = previousQuery?.queryKey[2]
      return typeof before === 'string' && before !== '' && (JSON.parse(before) as A['ReindexScope']).kind === scope?.kind ? previous : undefined
    },
    // A preview is of that moment: another reindex may start, or documents be filed, meanwhile
    staleTime: 0,
    gcTime: 0,
  })
  // The answer for the kind chosen now: until a change of kind settles, the last one is the other kind's
  const preview = scope !== undefined && sent?.kind === scope.kind ? answer.data : undefined
  return {
    preview,
    current: scope !== undefined && settled === asked && !answer.isFetching && !answer.isPlaceholderData && answer.data !== undefined,
    failure: scope !== undefined && settled === asked && !answer.isFetching ? (answer.error ?? undefined) : undefined,
  }
}

/** A matter as the dialog offers it, from Recent Matters, a search or the address */
export interface MatterChoice {
  /** The matter's id, not its folder's */
  id: number
  /** With its client's: 1001.1 */
  number: string
  name: string
  client: string
  /** Its documents, where what it came from counts them; Recent Matters doesn't */
  documents: number | null
  /** Its own folder, the top of its workspace */
  folderId: number
}

export function choiceOfRow(row: A['MatterRow'] | A['Matter']): MatterChoice {
  return { id: row.id, number: row.number, name: row.name, client: row.client.name, documents: row.documents, folderId: row.folderId }
}

/**
 * The matters a reindex starts from: Recent Matters (the public `/v1/matters/recent`, whose ids are the matters' own), and
 * a search by number, name or client (`GET /v1/admin/matters`, under the Matters list's key, as MatterPicker asks it).
 */
export function useMatterChoices() {
  const api = useApi()
  const queryClient = useQueryClient()
  const recent = useRecentMatters()
  const initial: MatterChoice[] = (recent.data?.items ?? []).map((matter) => ({
    id: matter.id,
    number: `${matter.client.number}.${matter.number}`,
    name: matter.name,
    client: matter.client.name,
    documents: null,
    folderId: matter.folderId,
  }))
  const search = useCallback(
    async (q: string) => {
      const query = { q, limit: MATTER_SEARCH_LIMIT }
      const page = await queryClient.fetchQuery({
        queryKey: adminKey('/v1/admin/matters', query),
        queryFn: ({ signal }) => api.get<A['MatterList']>('/v1/admin/matters', query, { signal }),
        // A key back and forth needn't ask twice
        staleTime: 30_000,
      })
      return (page.items ?? []).map(choiceOfRow)
    },
    [api, queryClient],
  )
  return { initial, recent, search }
}
