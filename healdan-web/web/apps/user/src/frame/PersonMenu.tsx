// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import { PersonMenu as ShellPersonMenu } from '@healdan/shell'
import { MenuItem, t } from '@healdan/ui'
import { SignedInDevices } from '../devices/SignedInDevices'

/** The shell's person menu, with the User app's own item: Signed-in Devices, and the dialog it opens. */
export function PersonMenu() {
  const [showDevices, setShowDevices] = useState(false)
  return (
    <>
      <ShellPersonMenu>
        <MenuItem id="devices" onAction={() => setShowDevices(true)}>
          {t('person.devices')}
        </MenuItem>
      </ShellPersonMenu>
      {showDevices && <SignedInDevices onClose={() => setShowDevices(false)} />}
    </>
  )
}
