// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import { PersonMenu as ShellPersonMenu, useMe } from '@healdan/shell'
import { MenuItem, t } from '@healdan/ui'
import { SignedInDevices } from '../devices/SignedInDevices'

/**
 * The shell's person menu, with the User app's own items: Signed-in Devices, and the dialog it opens; and for an
 * administrator Admin, the way to Healdan Admin, just before Classic Interface (admin spec 6.4). It's a link and no more,
 * so none of the Admin app's code is in this app's build, and the server decides who gets in.
 */
export function PersonMenu() {
  const [showDevices, setShowDevices] = useState(false)
  const admin = useMe().data?.admin === true
  return (
    <>
      <ShellPersonMenu>
        <MenuItem id="devices" icon="devices" onAction={() => setShowDevices(true)}>
          {t('person.devices')}
        </MenuItem>
        {admin && (
          <MenuItem id="admin" icon="settings" href="/admin/">
            {t('person.admin')}
          </MenuItem>
        )}
      </ShellPersonMenu>
      {showDevices && <SignedInDevices onClose={() => setShowDevices(false)} />}
    </>
  )
}
