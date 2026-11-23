// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { RefObject } from 'react'
import { Button } from '@healdan/ui'
import { useFocusWhenGone } from '../focus'
import type { PagedListing } from '../lists/usePagedList'
import { t } from '../strings'

/**
 * Show More under a plain table of a paged list, while it has more to load: the next page, the button held while it comes.
 * Once the last page is in, the button goes, and the focus it had goes to `fallback`, the list's line above it.
 */
export function ShowMore({ listing, fallback }: { listing: PagedListing<unknown>; fallback: RefObject<HTMLElement | null> }) {
  const more = listing.loadMore !== undefined || listing.isLoadingMore
  useFocusWhenGone(!more, fallback)
  if (!more) return null
  return (
    <Button isPending={listing.isLoadingMore} onPress={() => listing.loadMore?.()}>
      {t('admin.searchIndex.showMore')}
    </Button>
  )
}
