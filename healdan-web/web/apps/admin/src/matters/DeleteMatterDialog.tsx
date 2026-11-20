// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { apiPath, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { toastWithLink } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { t } from '../strings'
import { useFolderContents } from '../workspace/queries'
import { holdsWhat } from '../workspace/text'
import { matterDeleted } from './changed'
import page from './MatterPage.module.css'

type A = adminComponents['schemas']

/** The User app's Trash, where an administrator restores a matter (spec 8.3) */
const USER_TRASH = '/app/trash'

export interface DeleteMatterDialogProps {
  matter: A['Matter']
  /** It's in the Trash: where to go next is the caller's, which the matter's page is forgotten after, so it isn't asked again */
  onDeleted: () => Promise<void> | void
  onClose: () => void
}

/**
 * Delete (spec 8.3): asks first, naming what the matter holds, counted afresh (an administrator's counts take
 * everything), and saying its number stays taken. Its folder then goes to the caller's Trash, from which the User app
 * restores it. The rules' refusals, a document checked out or immutable, show in the dialog in their own words (P2-R13).
 */
export function DeleteMatterDialog({ matter, onDeleted, onClose }: DeleteMatterDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const contents = useFolderContents(matter.folderId)
  const label = t('matter.label', { number: matter.number, name: matter.name })
  const what = holdsWhat(contents.data)

  async function run() {
    await api.send<A['DeletedMatter']>('DELETE', apiPath`/v1/admin/matters/${matter.id}`)
    toastWithLink(t('admin.deleteMatter.done', { matter: label }), USER_TRASH)
    await onDeleted()
    matterDeleted(queryClient, matter.id)
  }

  return (
    <Confirm title={t('admin.deleteMatter.title', { number: matter.number })} confirm={t('admin.deleteMatter.confirm')} run={run} onClose={onClose}>
      <p className={page.line}>{what === undefined ? t('admin.deleteMatter.holdsAll', { matter: label }) : t('admin.deleteMatter.holds', { matter: label, what })}</p>
      <p className={page.line}>{t('admin.deleteMatter.number', { number: matter.number })}</p>
    </Confirm>
  )
}
