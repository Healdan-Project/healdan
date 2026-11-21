// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { usePagedList, type PagedListing, type SortOrder } from '../lists/usePagedList'

type A = adminComponents['schemas']

/** Rows come 100 at a time, as the end of the list comes into view, as the Matters list's do */
export const CLIENTS_PAGE = 100

/** What the server sorts the Clients list by, which are also its columns' ids */
export const clientSorts = ['number', 'name', 'matters', 'documents', 'lastActivity'] as const
export type ClientSort = (typeof clientSorts)[number]

export function isClientSort(value: unknown): value is ClientSort {
  return clientSorts.some((sort) => sort === value)
}

/**
 * The words and order of `GET /v1/admin/clients`: no words is every client, and no order the server's own, by number.
 * Ties go by number, ascending whichever the order (the server's rule).
 */
export interface ClientListQuery {
  q?: string
  sort?: ClientSort
  order?: SortOrder
}

/** The Clients list as far as it's loaded: usePagedList's listing, its rows as `clients` */
export type ClientListing = Omit<PagedListing<A['Client']>, 'items'> & { clients: A['Client'][] }

/** The Clients list, paged (spec 8.2): `GET /v1/admin/clients`, 100 at a time, as usePagedList asks for every list */
export function useClientList(query: ClientListQuery): ClientListing {
  const { items, ...rest } = usePagedList('/v1/admin/clients', query, CLIENTS_PAGE)
  return { clients: items, ...rest }
}
