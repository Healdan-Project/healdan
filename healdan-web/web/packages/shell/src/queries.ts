// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { apiPath, type Api, type components } from '@healdan/api'
import type { PageOf } from './pages'
import { useApi } from './services'

type S = components['schemas']

// The keys of what both apps ask for. An app's own keys take these, so invalidating one refreshes what the shell asked.
export const shellKeys = {
  me: ['me'] as const,
  recentMatters: ['matters', 'recent'] as const,
  folder: (id: number) => ['folders', id] as const,
}

/**
 * An id or doc # from an address, or undefined when it isn't one: digits only, and no bigger than a number keeps
 * exactly (2^53 - 1), as a bigger one would turn into another number.
 */
export function idFrom(text: string | undefined): number | undefined {
  if (text === undefined || !/^\d{1,16}$/.test(text)) return undefined
  const id = Number(text)
  return Number.isSafeInteger(id) ? id : undefined
}

/** A folder, with its matter and its path from the Store; nothing is asked without an id. */
export function useFolder(id: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: shellKeys.folder(id ?? -1),
    queryFn: () => api.get<S['FolderView']>(apiPath`/v1/folders/${id ?? -1}`),
    enabled: id !== undefined,
  })
}

export function useMe() {
  const api = useApi()
  return useQuery({ queryKey: shellKeys.me, queryFn: () => api.get<S['UserView']>('/v1/me') })
}

export function useRecentMatters() {
  const api = useApi()
  return useQuery({ queryKey: shellKeys.recentMatters, queryFn: () => api.get<PageOf<S['MatterView']>>('/v1/matters/recent') })
}

// The API gives at most 200 children at a time (50 unless asked), so a folder with more is read page by page.
const CHILDREN_PAGE = 200

/** A folder's subfolders, search folders included: the tree's, and the move picker's. */
export async function fetchSubfolders(api: Api, id: string): Promise<S['FolderView'][]> {
  const all: S['FolderView'][] = []
  for (let offset = 0; ; ) {
    const page = await api.get<PageOf<S['FolderView'] | S['DocumentView']>>(`/v1/folders/${encodeURIComponent(id)}/children`, {
      type: 'folder',
      limit: CHILDREN_PAGE,
      offset,
    })
    all.push(...page.items.filter((item): item is S['FolderView'] => item.type === 'folder'))
    offset += page.items.length
    if (page.items.length < CHILDREN_PAGE || (page.total != null && offset >= page.total)) return all
  }
}
