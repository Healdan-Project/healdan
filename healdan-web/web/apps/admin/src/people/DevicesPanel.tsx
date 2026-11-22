// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState, type RefObject } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, formatDate, formatDateTime, toast, VisuallyHidden } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { useFocusWhenGone } from '../focus'
import { t } from '../strings'
import { personChanged, useUserDevices } from './queries'
import { StaleNote } from './StaleNote'
import styles from './People.module.css'

type A = adminComponents['schemas']

/**
 * A person's signed-in devices (spec 8.7): each device token of theirs that still works, newest first, with what it
 * signed in with, when, its last use and when it expires, and Revoke, which asks first and signs that device out alone.
 * The web page's own sign-in isn't a device. Once the device revoked has gone from the list, and its Revoke with it, the
 * focus goes to `heading`. While a new read fails, the list it had stays, with a note (P4-R15).
 */
export function DevicesPanel({ person, heading }: { person: A['User']; heading: RefObject<HTMLElement | null> }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const asked = useUserDevices(person.id)
  const [revoking, setRevoking] = useState<A['UserDevice']>()
  const [revoked, setRevoked] = useState<number>()
  useFocusWhenGone(revoked !== undefined && asked.data !== undefined && !asked.data.some((device) => device.id === revoked), heading)

  async function revoke(device: A['UserDevice']) {
    await api.send<void>('DELETE', apiPath`/v1/admin/users/${person.id}/devices/${device.id}`)
    toast(t('admin.userDevices.revoked', { name: device.name }))
    setRevoked(device.id)
    // The count on their page and in the list
    personChanged(queryClient, person.id, { devices: true })
  }

  if (asked.data === undefined) {
    return asked.isError ? <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} /> : <p className={styles.note}>{t('common.loading')}</p>
  }
  const devices = asked.data
  return (
    <>
      <p className={styles.note}>{t('admin.userDevices.about')}</p>
      <StaleNote failed={asked.isError} onRetry={() => void asked.refetch()} />
      {devices.length === 0 ? (
        <p className={styles.line}>{t('admin.userDevices.none')}</p>
      ) : (
        <div className={styles.tableBox}>
          <table className={styles.table} aria-label={t('admin.userDevices.list', { name: person.name })}>
            <thead>
              <tr>
                <th scope="col">{t('admin.userDevices.name')}</th>
                <th scope="col">{t('admin.userDevices.through')}</th>
                <th scope="col">{t('admin.userDevices.created')}</th>
                <th scope="col">{t('admin.userDevices.lastUsed')}</th>
                <th scope="col">{t('admin.userDevices.expires')}</th>
                <th scope="col">
                  <VisuallyHidden>{t('admin.userDevices.actions')}</VisuallyHidden>
                </th>
              </tr>
            </thead>
            <tbody>
              {devices.map((device) => (
                <tr key={device.id}>
                  <th scope="row" className={styles.name}>
                    {device.name}
                  </th>
                  <td>{device.provider?.label ?? t('admin.users.password')}</td>
                  <td>{formatDateTime(device.created)}</td>
                  <td>{formatDateTime(device.lastUsed)}</td>
                  <td>{formatDate(device.expires)}</td>
                  <td className={styles.end}>
                    <Button aria-label={t('admin.userDevices.revokeName', { name: device.name })} onPress={() => setRevoking(device)}>
                      {t('admin.userDevices.revoke')}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {revoking !== undefined && (
        <Confirm title={t('admin.userDevices.revokeTitle', { name: revoking.name })} confirm={t('admin.userDevices.revoke')} run={() => revoke(revoking)} onClose={() => setRevoking(undefined)}>
          <p className={styles.line}>{t('admin.userDevices.revokeLine', { name: person.name })}</p>
        </Confirm>
      )}
    </>
  )
}
