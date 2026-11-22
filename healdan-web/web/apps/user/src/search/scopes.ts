// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { clientIcon, documentIcon, emailIcon, folderIcon, matterIcon, type KindIcon } from '../kindIcons'
import { sortColumns, type SortColumn } from '../views/useListing'

/** What the scope picker offers, in the API's own words for `scope` (spec section 4). */
export const searchScopes = ['documents', 'emails', 'all', 'folders', 'matters', 'clients'] as const
export type SearchScope = (typeof searchScopes)[number]

export function isSearchScope(value: unknown): value is SearchScope {
  return searchScopes.some((scope) => scope === value)
}

/** The scope an address names: one the API doesn't know is the default, Documents, as an address without one is */
export function scopeOf(value: unknown): SearchScope {
  return isSearchScope(value) ? value : 'documents'
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

/** The scopes that search documents, which take filters and sort by every document column */
export type DocumentScope = 'documents' | 'emails' | 'all'
/** The scopes that search folders, matters or clients, which sort by their own few columns */
export type OtherScope = Exclude<SearchScope, DocumentScope>

/** The scopes that search documents, which take filters and sort by every document column. */
export function searchesDocuments(scope: SearchScope): scope is DocumentScope {
  return scope === 'documents' || scope === 'emails' || scope === 'all'
}

/** What the Folders, Matters and Clients scopes sort by (list columns spec 5.2), in the API's words, which are their columns' ids */
const otherSorts: Record<OtherScope, readonly SortColumn[]> = {
  folders: ['title', 'matter'],
  matters: ['matter', 'client'],
  clients: ['client'],
}

/** What the scope's search sorts by: a document scope by what a folder sorts by, the others by their own */
export function scopeSorts(scope: SearchScope): readonly SortColumn[] {
  return searchesDocuments(scope) ? sortColumns : otherSorts[scope]
}

/** Whether the scope's search sorts by the value: the API refuses any other with 400 */
export function isScopeSort(scope: SearchScope, value: unknown): value is SortColumn {
  return scopeSorts(scope).some((sort) => sort === value)
}
