// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { clientIcon, documentIcon, emailIcon, folderIcon, matterIcon, type KindIcon } from '../kindIcons'

/** What the scope picker offers, in the API's own words for `scope` (spec section 4). */
export const searchScopes = ['documents', 'emails', 'all', 'folders', 'matters', 'clients'] as const
export type SearchScope = (typeof searchScopes)[number]

export function isSearchScope(value: unknown): value is SearchScope {
  return searchScopes.some((scope) => scope === value)
}

/** Each scope's icon, in its kind's colour; Documents and All take in every kind, so they have none (`<Icon {...scopeIcons[scope]} />`). */
export const scopeIcons: Record<SearchScope, KindIcon> = {
  documents: documentIcon,
  emails: emailIcon,
  all: { name: 'search' },
  folders: folderIcon(),
  matters: matterIcon,
  clients: clientIcon,
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
