// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import type { components } from '@healdan/api'
import type { PageOf } from './pages'
import { useApi } from './services'

type S = components['schemas']

// Each list has a key of its own. Folder children are keyed by folder, so opening one matter in the tree asks once.
export const keys = {
  me: ['me'] as const,
  recentDocuments: ['documents', 'recent'] as const,
  recentMatters: ['matters', 'recent'] as const,
  myMatters: ['matters', 'mine'] as const,
  favorites: ['favorites'] as const,
  subfolders: (id: number) => ['folders', id, 'subfolders'] as const,
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
