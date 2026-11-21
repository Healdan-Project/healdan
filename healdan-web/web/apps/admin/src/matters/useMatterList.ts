// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { usePagedList, type PagedListing, type SortOrder } from '../lists/usePagedList'

type A = adminComponents['schemas']

/** Rows come 100 at a time, as the end of the list comes into view, as the User app's lists do */
export const MATTERS_PAGE = 100

/** What the server sorts the Matters list by, which are also its columns' ids */
export const matterSorts = ['number', 'name', 'client', 'store', 'owner', 'template', 'documents', 'bytes', 'lastActivity'] as const
export type MatterSort = (typeof matterSorts)[number]
export type { SortOrder }

export function isMatterSort(value: unknown): value is MatterSort {
  return matterSorts.some((sort) => sort === value)
}

/** The filters and order of `GET /v1/admin/matters`: any left out is no filter, and the server's own order, by number */
export interface MatterListQuery {
  q?: string
  clientId?: number
  storeId?: number
  ownerId?: number
  sort?: MatterSort
  order?: SortOrder
}

/** The Matters list as far as it's loaded: usePagedList's listing, its rows as `matters` */
export type MatterListing = Omit<PagedListing<A['MatterRow']>, 'items'> & { matters: A['MatterRow'][] }

/**
 * The Matters list, paged (spec 8.3): `GET /v1/admin/matters`, 100 at a time, as usePagedList asks for every list of the
 * Admin app's.
 */
export function useMatterList(query: MatterListQuery): MatterListing {
  const { items, ...rest } = usePagedList('/v1/admin/matters', query, MATTERS_PAGE)
  return { matters: items, ...rest }
}
