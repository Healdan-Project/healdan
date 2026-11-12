// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import { ApiError } from '@healdan/api'
import { Menu, MenuItem, clearToasts, t, toast } from '@healdan/ui'
import { SignedInDevices } from '../devices/SignedInDevices'
import { useMe } from '../queries'
import { useApi } from '../services'
import { messageFor } from './errors'
import { goTo } from './navigate'
import styles from './TopBar.module.css'

function initials(name: string): string {
  const letters = name.split(/\s+/).filter(Boolean).map((part) => [...part][0] ?? '')
  return (letters.length > 1 ? (letters[0] ?? '') + (letters[letters.length - 1] ?? '') : (letters[0] ?? '')).toUpperCase()
}

export function PersonMenu() {
  const api = useApi()
  const name = useMe().data?.name ?? ''
  const [showDevices, setShowDevices] = useState(false)

  async function signOut() {
    try {
      await api.send('DELETE', '/v1/session')
    } catch (error) {
      // Already signed out (401: no session) is the goal reached. Anything else leaves the person signed in, so say so:
      // the route refusing the request is 403 web-session-refused, not a 401, for this reason.
      if (!(error instanceof ApiError && error.status === 401)) {
        toast(messageFor(error))
        return
      }
    }
    clearToasts()
    goTo('/login.jsp')
  }

  return (
    <>
      <Menu
        label={
          <>
            <span className={styles.avatar} aria-hidden="true">
              {initials(name)}
            </span>
            {/* The button's name is its content, so it can't differ from what shows (an aria-label that does fails WCAG 2.5.3). */}
            <span className={styles.visuallyHidden}>{name || t('person.menu')}</span>
          </>
        }
      >
        {name && (
          <MenuItem id="name" isDisabled>
            {name}
          </MenuItem>
        )}
        <MenuItem id="devices" onAction={() => setShowDevices(true)}>
          {t('person.devices')}
        </MenuItem>
        <MenuItem id="classic" href="/frontend.jsp">
          {t('person.classic')}
        </MenuItem>
        <MenuItem id="signOut" onAction={() => void signOut()}>
          {t('person.signOut')}
        </MenuItem>
      </Menu>
      {showDevices && <SignedInDevices onClose={() => setShowDevices(false)} />}
    </>
  )
}
