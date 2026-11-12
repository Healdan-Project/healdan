// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, Dialog, formatDateTime, t, toast } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { messageFor } from '../frame/errors'
import type { PageOf } from '../pages'
import { keys } from '../queries'
import { useApi } from '../services'
import styles from './devices.module.css'

type Token = components['schemas']['TokenView']

/**
 * The person's device tokens, with Revoke (spec 6.9; GET and DELETE /v1/auth/tokens). The web page's own sign-in is no
 * device (R14) and isn't listed: Sign Out ends it, and the dialog says so. Revoke asks first, in the dialog itself.
 */
export function SignedInDevices({ onClose }: { onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const list = useQuery({ queryKey: keys.devices, queryFn: () => api.get<PageOf<Token>>('/v1/auth/tokens') })
  const [asking, setAsking] = useState<Token>()
  const [refusal, setRefusal] = useState<string>()
  const [working, setWorking] = useState(false)
  // Where the focus goes once the list is back: a row's Revoke (by id) or Close. The button that had it is gone by then, and a
  // removed element fires no focusout in WebKit, so nothing else would give it back inside the dialog.
  const focusNext = useRef<number | 'close'>(undefined)
  const items = list.data?.items ?? []

  useEffect(() => {
    if (asking || focusNext.current === undefined) return
    const next = focusNext.current
    focusNext.current = undefined
    globalThis.document.querySelector<HTMLElement>(next === 'close' ? '[data-close]' : `[data-revoke="${next}"]`)?.focus()
  }, [asking])

  function afterRevoke(device: Token): number | 'close' {
    const at = items.findIndex((one) => one.id === device.id)
    return (items[at + 1] ?? items[at - 1])?.id ?? 'close'
  }

  async function revoke(device: Token) {
    if (working) return
    setWorking(true)
    try {
      await api.send('DELETE', apiPath`/v1/auth/tokens/${device.id}`)
      toast(t('devices.revoked', { name: device.name }))
      focusNext.current = afterRevoke(device)
      setAsking(undefined)
      setRefusal(undefined)
    } catch (error) {
      // Said where the person is looking, so it can't be missed behind the dialog
      setRefusal(messageFor(error))
    } finally {
      setWorking(false)
      await queryClient.invalidateQueries({ queryKey: keys.devices })
    }
  }

  return (
    <Dialog title={asking ? t('devices.revokeTitle', { name: asking.name }) : t('person.devices')} isOpen onOpenChange={(open) => !open && onClose()}>
      {(close) =>
        asking ? (
          <>
            <p className={styles.text}>{t('devices.revokeNote')}</p>
            {refusal && (
              <p role="alert" className={styles.refusal}>
                {refusal}
              </p>
            )}
            <div className={styles.actions}>
              <Button variant="default" autoFocus isDisabled={working} onPress={() => void revoke(asking)}>
                {t('devices.revoke')}
              </Button>
              <Button
                onPress={() => {
                  focusNext.current = asking.id
                  setAsking(undefined)
                  setRefusal(undefined)
                }}
              >
                {t('common.cancel')}
              </Button>
            </div>
          </>
        ) : (
          <>
            <p className={styles.text}>{t('devices.intro')}</p>
            {list.isError ? (
              <ErrorPanel error={list.error} onRetry={() => void list.refetch()} />
            ) : list.isPending ? (
              <p className={styles.text}>{t('common.loading')}</p>
            ) : list.data.items.length === 0 ? (
              <p className={styles.text}>{t('devices.none')}</p>
            ) : (
              <div className={styles.scroll}>
                <table className={styles.table} aria-label={t('devices.list')}>
                  <thead>
                    <tr>
                      <th scope="col">{t('devices.name')}</th>
                      <th scope="col">{t('devices.created')}</th>
                      <th scope="col">{t('devices.lastUsed')}</th>
                      <th scope="col">{t('devices.expires')}</th>
                      <th scope="col">
                        <span className={styles.visuallyHidden}>{t('devices.actions')}</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.data.items.map((device) => (
                      <tr key={device.id}>
                        <th scope="row" className={styles.name}>
                          {device.name}
                        </th>
                        <td>{formatDateTime(device.created)}</td>
                        <td>{formatDateTime(device.lastUsed)}</td>
                        <td>{formatDateTime(device.expiresAt)}</td>
                        <td>
                          <Button
                            aria-label={t('devices.revokeName', { name: device.name })}
                            onPress={() => {
                              setRefusal(undefined)
                              setAsking(device)
                            }}
                            {...{ 'data-revoke': device.id }}
                          >
                            {t('devices.revoke')}
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className={styles.actions}>
              <Button onPress={close} {...{ 'data-close': true }}>
                {t('devices.close')}
              </Button>
            </div>
          </>
        )
      }
    </Dialog>
  )
}
