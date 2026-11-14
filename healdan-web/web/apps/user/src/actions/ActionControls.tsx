// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, Icon, MenuItem } from '@healdan/ui'
import type { DocumentAction, LinkAction } from './useDocumentActions'

/** A toolbar's button for an action: its icon and label, disabled by the action's own rule */
export function ActionButton({ action }: { action: DocumentAction }) {
  return (
    <Button isDisabled={action.isDisabled} onPress={action.perform}>
      {action.icon && <Icon name={action.icon} size={14} />} {action.label}
    </Button>
  )
}

/** A menu's item for an action, More's and a right-click menu's alike; a link opens in a new tab. Nothing for none. */
export function ActionItem({ action }: { action: DocumentAction | LinkAction | undefined }) {
  if (!action) return null
  if ('href' in action) {
    return (
      <MenuItem id={action.id} href={action.href} target="_blank" rel="noopener" isDisabled={action.isDisabled}>
        {action.label}
      </MenuItem>
    )
  }
  return (
    <MenuItem id={action.id} isDisabled={action.isDisabled} onAction={action.perform}>
      {action.label}
    </MenuItem>
  )
}
