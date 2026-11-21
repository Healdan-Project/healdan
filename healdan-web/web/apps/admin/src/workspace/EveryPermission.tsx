// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { CheckField, formatList } from '@healdan/ui'
import { t } from '../strings'
import type { Grant } from './grants'
import { inOrder, PERMISSIONS, type Permission } from './levels'
import styles from './Workspace.module.css'

/** A permission's name, as Show Every Permission lists it */
export const permissionLabel = (permission: Permission) => t(`admin.permission.${permission}`)

export interface EveryPermissionProps {
  rows: readonly Grant[]
  /** Ticks to change, or the permissions only listed, for a folder that follows another's */
  editable: boolean
  /** A row whose ticks are shown but can't be changed, as the owner's on a matter's own folder */
  locked?: (row: Grant) => boolean
  onChange: (key: string, permissions: Permission[]) => void
}

/**
 * Show Every Permission (spec 8.4): each row's 25 permissions, in LogicalDOC's order, as tick boxes grouped under the
 * row's name, so a row can grant anything, which its level then shows as Custom. A folder that follows another's lists
 * what each row grants instead.
 */
export function EveryPermission({ rows, editable, locked, onChange }: EveryPermissionProps) {
  if (!editable) {
    return (
      <div className={styles.every}>
        {rows.map((row) => (
          <p key={row.key} className={styles.granted}>
            <strong>{row.name}</strong>: {formatList(row.permissions.map(permissionLabel))}
          </p>
        ))}
      </div>
    )
  }
  return (
    <div className={styles.every}>
      {rows.map((row) => (
        <fieldset key={row.key} disabled={locked?.(row)} className={styles.permissions}>
          <legend>{t('admin.access.everyOf', { name: row.name })}</legend>
          <div className={styles.grid}>
            {PERMISSIONS.map((permission) => (
              <CheckField
                key={permission}
                label={permissionLabel(permission)}
                checked={row.permissions.includes(permission)}
                onChange={(checked) => onChange(row.key, inOrder(checked ? [...row.permissions, permission] : row.permissions.filter((one) => one !== permission)))}
              />
            ))}
          </div>
        </fieldset>
      ))}
    </div>
  )
}
