// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { IconName } from '@healdan/ui'

/** What the scope picker offers, in the API's own words for `scope` (spec section 4). */
export const searchScopes = ['documents', 'emails', 'all', 'folders', 'matters', 'clients'] as const
export type SearchScope = (typeof searchScopes)[number]

export function isSearchScope(value: unknown): value is SearchScope {
  return searchScopes.some((scope) => scope === value)
}

export const scopeIcons: Record<SearchScope, IconName> = {
  documents: 'document',
  emails: 'email',
  all: 'search',
  folders: 'folder',
  matters: 'briefcase',
  clients: 'building',
}

/** The Recent section's entries; each is also a scope, which opening it sets (spec section 6.1). */
export const recentKinds = ['documents', 'emails', 'folders', 'clients'] as const
export type RecentKind = (typeof recentKinds)[number]

export function isRecentKind(value: unknown): value is RecentKind {
  return recentKinds.some((kind) => kind === value)
}

/** The scopes that search documents, which take filters and sorting. */
export function searchesDocuments(scope: SearchScope): boolean {
  return scope === 'documents' || scope === 'emails' || scope === 'all'
}
