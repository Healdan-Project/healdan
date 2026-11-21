// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Dialog, toast } from '@healdan/ui'
import { adminKeys } from '../queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { stuckKey, useStuckFiles } from './queries'
import styles from './Storage.module.css'

const counts = new Intl.NumberFormat('en-US')

export interface StuckFilesProps {
  /** The list's route: `/v1/admin/storage/stuck-files` for the files with no Store, or a Store's `/v1/admin/stores/{id}/stuck-files` */
  list: string
  /** Its Retry's route: `/v1/admin/storage/stuck-files/retry`, or a Store's `/v1/admin/stores/{id}/stuck-files/retry` */
  retry: string
  /** How many are stuck in all (`pending.stuck` or `otherFiles.stuck`), as the list holds the newest documents' 1,000 at most */
  stuck: number
  /** What the toast says once they wait again, such as "The stuck files of Client Work wait to move again" */
  retried: string
  /** Once they wait again: what else to ask again, such as the counts beside them. The list itself is asked again. */
  onRetried?: () => void
}

/**
 * Retry (spec 8.6): every stuck file at the route goes back to waiting, its tries counted from nought again, and the
 * mover is woken. It changes something, so the caller holds what it's in while `busy`; a refusal is `failure`, said with
 * useRefusalFor. Once done, the list at `list` and the Dashboard are asked again, and `onRetried` runs.
 */
export function useStuckRetry({ list, retry, retried, onRetried }: Pick<StuckFilesProps, 'list' | 'retry' | 'retried' | 'onRetried'>) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  async function run(): Promise<boolean> {
    setBusy(true)
    setFailure(undefined)
    try {
      await api.send<void>('POST', retry)
      toast(retried)
      void queryClient.invalidateQueries({ queryKey: stuckKey(list) })
      void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
      onRetried?.()
      return true
    } catch (error) {
      setFailure(error)
      return false
    } finally {
      setBusy(false)
    }
  }

  return { run, busy, failure }
}

/**
 * The stuck files at a route (spec 8.6), the Storage tab's Stuck Files: each file the mover gave up on after ten failures
 * in a row, the newest documents first, with LogicalDOC's internal id of its document (not its doc #), its file name,
 * which of its files it is, its tries and why its last try failed, all drawn as text, which an author or an S3 service
 * fills. It says how many it lists when there are more than it holds. A Store's page can draw it as it is, with a Retry
 * of its own from useStuckRetry; StuckFilesDialog draws it in a dialog.
 */
export function StuckFiles({ list, stuck }: Pick<StuckFilesProps, 'list' | 'stuck'>) {
  const files = useStuckFiles(list)
  if (files.isError) return <ErrorPanel error={files.error} onRetry={() => void files.refetch()} />
  if (files.data === undefined) return <p className={styles.muted}>{t('common.loading')}</p>
  if (files.data.length === 0) return <p className={styles.muted}>{t('admin.stuck.none')}</p>
  const shown = files.data
  return (
    <>
      {shown.length < stuck && <p className={styles.note}>{t('admin.stuck.listed', { shown: counts.format(shown.length), stuck: counts.format(stuck) })}</p>}
      <div className={styles.tableBox}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col" className={styles.number}>
                {t('admin.stuck.column.document')}
              </th>
              <th scope="col">{t('admin.stuck.column.fileName')}</th>
              <th scope="col">{t('admin.stuck.column.resource')}</th>
              <th scope="col" className={styles.number}>
                {t('admin.stuck.column.attempts')}
              </th>
              <th scope="col">{t('admin.stuck.column.lastError')}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((file) => (
              <tr key={`${file.docId}/${file.resource}`}>
                <td className={styles.number}>{file.docId}</td>
                <td className={file.fileName === null ? `${styles.wrap} ${styles.muted}` : styles.wrap}>{file.fileName ?? t('admin.stuck.deleted')}</td>
                <td className={styles.wrap}>{file.resource}</td>
                <td className={styles.number}>{counts.format(file.attempts)}</td>
                <td className={styles.wrap}>{file.lastError ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

export interface StuckFilesDialogProps extends StuckFilesProps {
  /** Its title, such as "Stuck Files of Client Work" */
  title: string
  /** The dialog closes: Close, Esc, a click outside, or once retried. While a retry is on its way it can't be closed. */
  onClose: () => void
}

/**
 * The stuck files in a dialog, as the Storage tab opens them from a count, with Retry, which closes it once they wait
 * again, and stays to say why when it's refused. While a retry is on its way, Close is greyed out and Esc and a click
 * outside do nothing.
 */
export function StuckFilesDialog({ title, onClose, ...props }: StuckFilesDialogProps) {
  const refusalFor = useRefusalFor()
  const retrying = useStuckRetry(props)
  const files = useStuckFiles(props.list)
  const any = props.stuck > 0 || (files.data?.length ?? 0) > 0

  return (
    <Dialog title={title} size="wide" isOpen onOpenChange={(open) => !open && !retrying.busy && onClose()}>
      {(close) => (
        <div className={styles.form}>
          <p className={styles.line}>{t('admin.stuck.about')}</p>
          <StuckFiles list={props.list} stuck={props.stuck} />
          {retrying.failure !== undefined && (
            <p className={styles.refusal} role="alert">
              {refusalFor(retrying.failure)}
            </p>
          )}
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={retrying.busy}>
              {t('admin.recoveryKey.close')}
            </Button>
            {any && (
              <Button
                variant="default"
                isPending={retrying.busy}
                onPress={() =>
                  void retrying.run().then((done) => {
                    if (done) onClose()
                  })
                }
              >
                {t('admin.stuck.retry')}
              </Button>
            )}
          </div>
        </div>
      )}
    </Dialog>
  )
}
