// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Icon, t } from '@healdan/ui'
import { OpenDownload } from '../actions/OpenDownload'
import { fileIcon } from '../kindIcons'
import type { components } from '@healdan/api'
import styles from './preview.module.css'

/** What a type with no preview shows (spec section 6.4): it says so, with Open and Download. */
export function NoPreview({ document, message = t('preview.none'), showActions = true, version }: { document: components['schemas']['DocumentView']; message?: string; showActions?: boolean; version?: string }) {
  return (
    <div className={styles.none}>
      <Icon {...fileIcon(document.name)} size={48} />
      <p className={styles.noneMessage}>{message}</p>
      {showActions && (
        <div className={styles.noneActions}>
          <OpenDownload document={document} version={version} />
        </div>
      )}
    </div>
  )
}
