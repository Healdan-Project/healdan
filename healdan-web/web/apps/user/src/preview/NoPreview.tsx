// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Icon, t } from '@healdan/ui'
import { OpenDownload } from '../actions/OpenDownload'
import { fileIcon } from '../fileIcon'
import type { components } from '@healdan/api'
import styles from './preview.module.css'

/** What a type with no preview shows (spec section 6.4): it says so, with Open and Download. */
export function NoPreview({ document, message = t('preview.none'), showActions = true }: { document: components['schemas']['DocumentView']; message?: string; showActions?: boolean }) {
  return (
    <div className={styles.none}>
      <Icon name={fileIcon(document.name)} size={48} />
      <p className={styles.noneMessage}>{message}</p>
      {showActions && (
        <div className={styles.noneActions}>
          <OpenDownload />
        </div>
      )}
    </div>
  )
}
