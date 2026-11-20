// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { idFrom, useApi } from '@healdan/shell'
import { Button, Dialog, SelectField, toast } from '@healdan/ui'
import { useStores } from '../queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { matterChanged } from './changed'
import styles from '../clients/Clients.module.css'

type A = adminComponents['schemas']

export interface MoveMatterDialogProps {
  matter: A['Matter']
  /** The dialog closes: Cancel, Esc, a click outside, or once moved. The caller stops drawing it. */
  onClose: () => void
}

/**
 * Move to Another Store (spec 8.3): a menu of the Stores, a short list (spec 7.2), the one it's in named so. It says its
 * files follow in the background, and says before Move that the Store it's in moves nothing. The rules' refusals, a name
 * that clashes or a document checked out among them, show in the dialog in their own words (P2-R13). While the move is
 * on its way the dialog holds, as ClientDialog does.
 */
export function MoveMatterDialog({ matter, onClose }: MoveMatterDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const stores = useStores()
  const [storeId, setStoreId] = useState('')
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const chosen = idFrom(storeId)
  const same = chosen === matter.store.id
  const options = (stores.data ?? []).map((store) => ({
    value: String(store.id),
    label: store.id === matter.store.id ? t('admin.moveMatter.here', { name: store.name }) : store.name,
  }))
  const ready = chosen !== undefined && !same

  async function move() {
    if (chosen === undefined) return
    setBusy(true)
    setFailure(undefined)
    try {
      const moved = await api.send<A['Matter']>('POST', apiPath`/v1/admin/matters/${matter.id}/move`, { storeId: chosen } satisfies A['MatterMove'])
      matterChanged(queryClient, moved)
      toast(t('admin.moveMatter.moved', { matter: t('matter.label', { number: moved.number, name: moved.name }), store: moved.store.name }))
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t('admin.moveMatter.title', { number: matter.number })} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void move()
          }}
        >
          <SelectField
            label={t('admin.moveMatter.store')}
            required
            value={storeId}
            options={options}
            error={same ? t('admin.moveMatter.same') : undefined}
            onChange={(value) => {
              setStoreId(value)
              setFailure(undefined)
            }}
          />
          <p className={styles.note}>{t('admin.moveMatter.note')}</p>
          {failure !== undefined && (
            <p className={styles.refusal} role="alert">
              {refusalFor(failure)}
            </p>
          )}
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t('admin.moveMatter.confirm')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
