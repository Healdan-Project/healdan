// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, Dialog, formatSize, RadioChoices, toast, type RadioChoice } from '@healdan/ui'
import { storeFilesChanged, storeKeys, useSwitchPreview } from '../stores/queries'
import { backendLabel } from '../stores/text'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { useBackends } from './queries'
import { filesOf } from './text'
import styles from './Storage.module.css'

type A = adminComponents['schemas']

export interface SwitchBackendDialogProps {
  store: Pick<A['StoreInfo'], 'id' | 'name'>
  /** The backend it keeps its files on now */
  current: A['StoreBackend']
  /** The dialog closes: Cancel, Esc, a click outside, or once changed. While the change is on its way it can't be. */
  onClose: () => void
}

/**
 * Change Backend (spec 8.6), as the Storage tab changes a Store's backend: every backend, Original first, the one it's on
 * now said so, and one no Store can be given yet (an encrypted backend whose recovery key isn't confirmed) greyed out
 * with why. Choosing another counts first what would move there, every file not on it now, and says so before the change
 * (`GET /stores/{id}/backend?backendId=`); none means the change takes effect at once. The change then says the files move
 * in the background. Its refusals are said in the dialog in the rule's words, and while it's on its way the dialog holds.
 */
export function SwitchBackendDialog({ store, current, onClose }: SwitchBackendDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const overview = useBackends()
  const [chosenId, setChosenId] = useState(current.id)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const backends = overview.data?.on === true ? overview.data.backends : []
  const chosen = backends.find((backend) => backend.id === chosenId)
  const other = chosen !== undefined && chosen.id !== current.id && chosen.offered
  const preview = useSwitchPreview(store.id, other ? chosen.id : undefined)
  const counted = other && preview.data?.switchPreview?.backendId === chosen.id ? preview.data.switchPreview : undefined
  const ready = counted !== undefined && !busy

  const choices: RadioChoice[] = backends.map((backend) => ({
    value: String(backend.id),
    label: backendLabel(backend),
    disabled: !backend.offered && backend.id !== current.id,
    description: backend.id === current.id ? t('admin.switchBackend.current') : backend.offered ? undefined : t('admin.backends.notOffered'),
  }))

  async function change() {
    if (!other) return
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await api.send<A['StoreStorage']>('PUT', apiPath`/v1/admin/stores/${store.id}/backend`, { backendId: chosen.id } satisfies A['StoreBackendChange'])
      queryClient.setQueryData(storeKeys.storage(store.id), saved)
      storeFilesChanged(queryClient, store.id)
      toast(t('admin.switchBackend.done', { store: store.name, backend: chosen.name }))
      // Not through the dialog's own close, which is held while busy
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  // The backends can't be listed: storage backends off since the page was drawn, or no storage module on the server
  const unlisted = overview.isError ? refusalFor(overview.error) : overview.data?.on === false ? t('admin.storeStorage.off') : undefined

  return (
    <Dialog title={t('admin.switchBackend.title', { name: store.name })} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready) void change()
          }}
        >
          {unlisted !== undefined ? (
            <p className={styles.line}>{unlisted}</p>
          ) : overview.data === undefined ? (
            <p className={styles.muted}>{t('common.loading')}</p>
          ) : (
            <fieldset disabled={busy} className={styles.form}>
              <RadioChoices
                label={t('admin.switchBackend.backend')}
                choices={choices}
                value={String(chosenId)}
                onChange={(value) => {
                  setChosenId(Number(value))
                  setFailure(undefined)
                }}
              />
            </fieldset>
          )}
          {/* What would move, said as it's counted */}
          <div aria-live="polite">
            {other &&
              (counted !== undefined ? (
                <p className={styles.line}>
                  {counted.files === 0
                    ? t('admin.switchBackend.atOnce')
                    : t('admin.switchBackend.moves', { files: filesOf(counted.files), size: formatSize(counted.bytes), backend: chosen.name })}
                </p>
              ) : preview.isError ? (
                <p className={styles.refusal} role="alert">
                  {refusalFor(preview.error)}
                </p>
              ) : (
                <p className={styles.muted}>{t('admin.switchBackend.counting')}</p>
              ))}
          </div>
          {failure !== undefined && (
            <p className={styles.refusal} role="alert">
              {refusalFor(failure)}
            </p>
          )}
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            {unlisted === undefined && (
              <Button type="submit" variant="default" isDisabled={counted === undefined} isPending={busy}>
                {t('admin.switchBackend.confirm')}
              </Button>
            )}
          </div>
        </form>
      )}
    </Dialog>
  )
}
