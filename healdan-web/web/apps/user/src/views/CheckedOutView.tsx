// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import { t, type TableSort } from '@healdan/ui'
import { useCheckouts } from '../queries'
import { DocumentListPage } from './DocumentListPage'
import type { AddressSort } from './useListColumns'

/** The list's own order, the server's: the latest check-out first */
const LATEST_CHECKED_OUT: TableSort = { column: 'checkedOut', direction: 'descending' }

/**
 * /checked-out: the documents the person has checked out. The server sends them whole, the latest check-out first, and
 * the browser sorts them by any column (spec 4.2), the sort in the address.
 */
export function CheckedOutView({ sort, order }: AddressSort) {
  const query = useCheckouts({ live: true })
  const address = useMemo(() => ({ sort, order }), [sort, order])
  return <DocumentListPage heading={t('checkedOut.title')} empty={t('checkedOut.empty')} actionsLabel={t('checkedOut.actions')} query={query} kind="checkedOut" own={LATEST_CHECKED_OUT} address={address} />
}
