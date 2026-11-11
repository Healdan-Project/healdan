// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { Button, formatVersion, t, Tray, type TrayItem } from '@healdan/ui'
import { requestEdit } from '../details/editRequest'
import { useRightRegion } from '../frame/RightRegion'
import { useMe } from '../queries'
import { refusalText } from './refusal'
import { useFiling } from './FilingProvider'
import type { UploadItem } from './uploads'

function statusOf(item: UploadItem, meId: number | undefined): string {
  switch (item.status) {
    case 'waiting':
      return t('tray.waiting')
    case 'uploading':
      return t('tray.uploading')
    case 'filed':
      return item.version ? t('tray.versioned', { version: formatVersion(item.version), n: item.docNumber ?? 0 }) : t('tray.filed', { n: item.docNumber ?? 0 })
    case 'refused':
      return refusalText(item.problem, meId)
  }
}

/**
 * The tray (spec 6.5): each item filed with its #, uploading, waiting or refused. A filed item has Edit, which selects
 * the document in its folder and opens the properties form in the details; one refused because the email is already in
 * the matter has Show, which opens the copy.
 */
export function FilingTray() {
  const { items, uploads } = useFiling()
  const me = useMe()
  const navigate = useNavigate()
  const region = useRightRegion()

  const done = items.filter((item) => item.status === 'filed' || item.status === 'refused').length
  const busy = done < items.length
  const rows: TrayItem[] = items.map((item) => ({
    id: item.id,
    name: item.name,
    state: item.status,
    status: statusOf(item, me.data?.id),
    actions: (
      <>
        {item.status === 'filed' && item.docNumber !== undefined && (
          <Button
            aria-label={t('tray.editName', { name: item.name })}
            onPress={() => {
              requestEdit(item.docNumber!)
              region.open('details')
              void navigate({ to: '/folders/$id', params: { id: String(item.folderId) }, search: { sel: String(item.docNumber) } })
            }}
          >
            {t('tray.edit')}
          </Button>
        )}
        {item.existing && (
          <Button aria-label={t('tray.showName', { name: item.name })} onPress={() => void navigate({ to: '/doc/$ref', params: { ref: String(item.existing!.docNumber) } })}>
            {t('tray.show')}
          </Button>
        )}
      </>
    ),
  }))

  return (
    <Tray
      label={t('tray.label')}
      summary={t(busy ? 'tray.summaryBusy' : 'tray.summaryDone', { done: done, n: items.length })}
      items={rows}
      onDismiss={(id) => uploads.dismiss(Number(id))}
      dismissLabel={(name) => t('tray.dismiss', { name })}
    />
  )
}
