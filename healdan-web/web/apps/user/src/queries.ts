// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { apiPath, type components } from '@healdan/api'
import type { PageOf } from './pages'
import { useApi } from './services'

type S = components['schemas']

// Each list has a key of its own. Folder children are keyed by folder, so opening one matter in the tree asks once, and
// everything of a folder's starts with ['folders', id], so one invalidation refreshes it all.
export const keys = {
  me: ['me'] as const,
  recentDocuments: ['documents', 'recent'] as const,
  recentMatters: ['matters', 'recent'] as const,
  myMatters: ['matters', 'mine'] as const,
  favorites: ['favorites'] as const,
  folder: (id: number) => ['folders', id] as const,
  subfolders: (id: number) => ['folders', id, 'subfolders'] as const,
  // Which facets, counted over what the filters leave, and how many documents the folder holds without filters
  facets: (id: number, options: Record<string, unknown>) => ['folders', id, 'facets', options] as const,
  unfiltered: (id: number) => ['folders', id, 'unfiltered'] as const,
  document: (docNumber: number) => ['documents', docNumber] as const,
  properties: (docNumber: number) => ['documents', docNumber, 'properties'] as const,
  versions: (docNumber: number) => ['documents', docNumber, 'versions'] as const,
  lookup: (ref: string) => ['lookup', ref] as const,
  children: (id: number, options: Record<string, unknown>) => ['folders', id, 'children', options] as const,
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
    queryKey: keys.folder(id ?? -1),
    queryFn: () => api.get<S['FolderView']>(apiPath`/v1/folders/${id ?? -1}`),
    enabled: id !== undefined,
  })
}

export function useMe() {
  const api = useApi()
  return useQuery({ queryKey: keys.me, queryFn: () => api.get<S['UserView']>('/v1/me') })
}

export function useRecentDocuments() {
  const api = useApi()
  return useQuery({ queryKey: keys.recentDocuments, queryFn: () => api.get<PageOf<S['DocumentView']>>('/v1/documents/recent') })
}

export function useRecentMatters() {
  const api = useApi()
  return useQuery({ queryKey: keys.recentMatters, queryFn: () => api.get<PageOf<S['MatterView']>>('/v1/matters/recent') })
}

export function useMyMatters() {
  const api = useApi()
  return useQuery({ queryKey: keys.myMatters, queryFn: () => api.get<PageOf<S['MatterView']>>('/v1/matters/mine') })
}

export function useFavorites() {
  const api = useApi()
  return useQuery({ queryKey: keys.favorites, queryFn: () => api.get<PageOf<S['FavoriteView']>>('/v1/me/favorites') })
}

/** A document; `placeholder` (its row in a list) shows at once while the answer comes. */
export function useDocument(docNumber: number, placeholder?: S['DocumentView']) {
  const api = useApi()
  return useQuery({
    queryKey: keys.document(docNumber),
    queryFn: () => api.get<S['DocumentView']>(apiPath`/v1/documents/${docNumber}`),
    placeholderData: placeholder?.docNumber === docNumber ? placeholder : undefined,
  })
}

export function useProperties(docNumber: number) {
  const api = useApi()
  return useQuery({
    queryKey: keys.properties(docNumber),
    queryFn: () => api.get<PageOf<S['ProfileField']>>(apiPath`/v1/documents/${docNumber}/properties`),
  })
}

export function useVersions(docNumber: number) {
  const api = useApi()
  return useQuery({
    queryKey: keys.versions(docNumber),
    queryFn: () => api.get<PageOf<S['VersionView']>>(apiPath`/v1/documents/${docNumber}/versions`),
  })
}

/** What a typed # (`10388`, `10388.2`, as the address has it) means: the document and the version it names. */
export function useLookup(ref: string) {
  const api = useApi()
  return useQuery({
    queryKey: keys.lookup(ref),
    queryFn: () => api.get<S['LookupView']>('/v1/lookup', { q: `#${ref}` }),
    // A missing or unreadable document answers 404 for good, and retrying a 400 only repeats it
    retry: false,
  })
}
