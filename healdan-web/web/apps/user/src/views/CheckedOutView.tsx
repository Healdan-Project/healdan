// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import { t } from '@healdan/ui'
import { useCheckouts } from '../queries'
import { DocumentListPage } from './DocumentListPage'
import { checkedOutColumns } from './listColumns'

/** /checked-out: the documents the person has checked out, the latest first (the server's order, so no header sorts). */
export function CheckedOutView() {
  const query = useCheckouts()
  const columns = useMemo(() => checkedOutColumns(), [])
  return <DocumentListPage heading={t('checkedOut.title')} empty={t('checkedOut.empty')} actionsLabel={t('checkedOut.actions')} query={query} columns={columns} />
}
