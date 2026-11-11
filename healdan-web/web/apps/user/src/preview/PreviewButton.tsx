// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, Icon, t } from '@healdan/ui'
import { useSelection } from '../selection'
import { useQuickLook } from './QuickLookState'

/** The toolbar's Preview: Quick Look on the selected document, as Space opens it; disabled with none selected. */
export function PreviewButton() {
  const { selected } = useSelection()
  const quickLook = useQuickLook()
  return (
    <Button isDisabled={selected === undefined} onPress={() => selected !== undefined && quickLook.open(selected)}>
      <Icon name="eye" size={14} /> {t('preview.button')}
    </Button>
  )
}
