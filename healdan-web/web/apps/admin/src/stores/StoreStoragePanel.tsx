// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel } from '@healdan/shell'
import { Button, formatSize } from '@healdan/ui'
import { Fact } from '../facts/Fact'
import { useFocusWhenGone } from '../focus'
import { isStorageUnavailable } from '../storage/queries'
import { StuckFiles, useStuckRetry } from '../storage/StuckFiles'
import { filesOf, stuckOf, waitingOf } from '../storage/text'
import { t } from '../strings'
import { Refusal } from '../forms/Refusal'
import clientStyles from '../clients/Clients.module.css'
import styles from '../storage/Storage.module.css'
import { storeFilesChanged, storeStuckRoutes, useStoreStorage } from './queries'
import { backendLabel } from './text'

type A = adminComponents['schemas']

/**
 * A Store's storage, on its page (spec 8.6): the backend it keeps its files on, what it holds there, what waits to move
 * there and what the mover gave up on, as the Storage tab's Stores list says them, and the stuck files listed, with Retry.
 * Once a Retry leaves none stuck, their section stays, saying so, and takes the focus Retry had. On a server that keeps
 * its files in LogicalDOC's own store, or has no storage module, it says so.
 */
export function StoreStoragePanel({ store }: { store: Pick<A['StoreInfo'], 'id' | 'name'> }) {
  const asked = useStoreStorage(store.id)
  // Once a Retry here, the stuck files' section stays when none are left, so its heading can take the focus
  const [retried, setRetried] = useState(false)
  if (asked.isError) {
    if (isStorageUnavailable(asked.error)) return <p className={styles.line}>{t('problems.storage-unavailable')}</p>
    return <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
  }
  if (asked.data === undefined) return <p className={styles.muted}>{t('common.loading')}</p>
  const storage = asked.data
  if (!storage.on || storage.backend === null) return <p className={styles.line}>{t('admin.storeStorage.off')}</p>
  return (
    <div className={styles.section}>
      <p className={styles.muted}>{t('admin.storeStorage.about')}</p>
      <dl className={clientStyles.facts}>
        <Fact term="admin.storeStorage.backend">{backendLabel(storage.backend)}</Fact>
        <Fact term="admin.storeStorage.holds">{t('admin.backend.holdsWhat', { files: filesOf(storage.files), size: formatSize(storage.bytes) })}</Fact>
        <Fact term="admin.storeStorage.waiting">{waitingOf(storage.pending.waiting, storage.pending.waitingBytes)}</Fact>
        <Fact term="admin.storeStorage.stuck">{stuckOf(storage.pending.stuck)}</Fact>
      </dl>
      {(storage.pending.stuck > 0 || retried) && <Stuck store={store} stuck={storage.pending.stuck} onRetried={() => setRetried(true)} />}
    </div>
  )
}

/**
 * The Store's stuck files, the newest documents' first, with Retry, which puts them all back to waiting. With none stuck
 * (after a Retry) it says so, with no Retry, and its heading takes the focus Retry had.
 */
function Stuck({ store, stuck, onRetried }: { store: Pick<A['StoreInfo'], 'id' | 'name'>; stuck: number; onRetried: () => void }) {
  const queryClient = useQueryClient()
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const routes = storeStuckRoutes(store.id)
  const retrying = useStuckRetry({
    ...routes,
    retried: t('admin.stuck.retried', { name: store.name }),
    onRetried: () => {
      onRetried()
      storeFilesChanged(queryClient, store.id, { stuck: false })
    },
  })
  useFocusWhenGone(stuck === 0, heading)

  return (
    <section aria-labelledby={headingId} className={styles.section}>
      <h2 id={headingId} ref={heading} tabIndex={-1}>
        {t('admin.storeStorage.stuckTitle')}
      </h2>
      {stuck > 0 && <p className={styles.muted}>{t('admin.stuck.about')}</p>}
      <StuckFiles list={routes.list} stuck={stuck} />
      {stuck > 0 && (
        <div className={styles.row}>
          <Button isPending={retrying.busy} onPress={() => void retrying.run()}>
            {t('admin.storeStorage.retry')}
          </Button>
        </div>
      )}
      <Refusal failure={retrying.failure} />
    </section>
  )
}
