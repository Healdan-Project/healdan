// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState, type RefObject } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, formatDateTime, toast, VisuallyHidden } from '@healdan/ui'
import { routes } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import { useFocusWhenGone } from '../focus'
import { t } from '../strings'
import { personChanged, useUserCheckouts, type Checkout } from './queries'
import { StaleNote } from './StaleNote'
import styles from './People.module.css'

type A = adminComponents['schemas']

/**
 * What a person has checked out (spec 8.7): each document's doc #, title, file version and matter, since when, and the
 * computer and app that hold it, so an administrator can say where to check it in; and Release, which asks first, naming
 * what's lost, and releases it through the public API's `DELETE /v1/documents/{n}/checkout`, as an administrator may.
 * Once the document released has gone from the list, the focus goes to `heading`. While a new read fails, the list it had
 * stays, with a note (P4-R15).
 */
export function CheckoutsPanel({ person, heading }: { person: A['User']; heading: RefObject<HTMLElement | null> }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const asked = useUserCheckouts(person.id)
  const [releasing, setReleasing] = useState<Checkout>()
  const [released, setReleased] = useState<number>()
  useFocusWhenGone(released !== undefined && asked.data !== undefined && !asked.data.some((one) => one.docNumber === released), heading)

  async function release(checkout: Checkout) {
    await api.send('DELETE', apiPath`/v1/documents/${checkout.docNumber}/checkout`)
    toast(t('admin.userCheckouts.released', { title: checkout.title }))
    setReleased(checkout.docNumber)
    // The count on their page
    personChanged(queryClient, person.id, { checkouts: true })
  }

  if (asked.data === undefined) {
    return asked.isError ? <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} /> : <p className={styles.note}>{t('common.loading')}</p>
  }
  const checkouts = asked.data
  return (
    <>
      <StaleNote failed={asked.isError} onRetry={() => void asked.refetch()} />
      {checkouts.length === 0 ? (
        <p className={styles.line}>{t('admin.userCheckouts.none')}</p>
      ) : (
        <div className={styles.tableBox}>
          <table className={styles.table} aria-label={t('admin.userCheckouts.list', { name: person.name })}>
            <thead>
              <tr>
                <th scope="col">{t('admin.userCheckouts.docNumber')}</th>
                <th scope="col">{t('admin.userCheckouts.document')}</th>
                <th scope="col">{t('admin.userCheckouts.version')}</th>
                <th scope="col">{t('admin.userCheckouts.matter')}</th>
                <th scope="col">{t('admin.userCheckouts.since')}</th>
                <th scope="col">{t('admin.userCheckouts.computer')}</th>
                <th scope="col">{t('admin.userCheckouts.app')}</th>
                <th scope="col">
                  <VisuallyHidden>{t('admin.userCheckouts.actions')}</VisuallyHidden>
                </th>
              </tr>
            </thead>
            <tbody>
              {checkouts.map((checkout) => (
                <tr key={checkout.docNumber}>
                  <td>{checkout.docNumber}</td>
                  <th scope="row" className={styles.name}>
                    {checkout.title}
                  </th>
                  <td>{checkout.fileVersion}</td>
                  <td>
                    {checkout.matter !== null && (
                      <Link to={routes.matter} params={{ id: String(checkout.matter.id) }} className={styles.link}>
                        {t('matter.label', { number: checkout.matter.number, name: checkout.matter.name })}
                      </Link>
                    )}
                  </td>
                  <td>{checkout.since === null ? '' : formatDateTime(checkout.since)}</td>
                  <td>{checkout.device ?? ''}</td>
                  <td>{checkout.app ?? ''}</td>
                  <td className={styles.end}>
                    <Button aria-label={t('admin.userCheckouts.releaseName', { title: checkout.title })} onPress={() => setReleasing(checkout)}>
                      {t('admin.userCheckouts.release')}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {releasing !== undefined && (
        <Confirm
          title={t('admin.userCheckouts.releaseTitle', { title: releasing.title })}
          confirm={t('admin.userCheckouts.releaseConfirm')}
          run={() => release(releasing)}
          onClose={() => setReleasing(undefined)}
        >
          <p className={styles.line}>
            {releasing.device !== null
              ? t('admin.userCheckouts.releaseOn', { name: person.name, computer: releasing.device })
              : t('admin.userCheckouts.releaseLine', { name: person.name })}
          </p>
        </Confirm>
      )}
    </>
  )
}
