// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, formatDateTime, formatVersion, t } from '@healdan/ui'
import styles from './details.module.css'

type S = components['schemas']

export interface VersionsProps {
  versions: S['VersionView'][]
  /** The version a # named (`#10388.2`) */
  selected?: string
  /** There while more are to load */
  onShowMore?: () => void
  loading?: boolean
  /** Each row's Preview opens Quick Look on that version, and its Download saves that version's file; Download is disabled without `canOpen` (the Download permission); Preview needs the Preview permission instead, which the server checks */
  onPreview?: (version: string) => void
  onDownload?: (version: string) => void
  canOpen?: boolean
}

/** A document's timeline, newest first as the server gives it; the Versions tab names it (DetailsPanel). */
export function Versions({ versions, selected, onShowMore, loading = false, onPreview, onDownload, canOpen = true }: VersionsProps) {
  return (
    <>
      {versions.length === 0 ? (
        <p className={styles.note}>{t('details.noVersions')}</p>
      ) : (
        <ul className={styles.versions}>
          {versions.map((row) => (
            <li key={row.version} className={styles.version} aria-current={row.version === selected ? 'true' : undefined}>
              <span className={styles.versionName}>{t('details.version', { version: formatVersion(row.version) })}</span>
              {row.date && <span className={styles.versionMeta}>{formatDateTime(row.date)}</span>}
              {row.by?.name && <span className={styles.versionMeta}>{row.by.name}</span>}
              {row.comment && <span className={styles.versionComment}>{row.comment}</span>}
              {(onPreview || onDownload) && (
                <span className={styles.versionActions}>
                  {onPreview && (
                    <Button variant="quiet" aria-label={t('details.versionPreview', { version: formatVersion(row.version) })} onPress={() => onPreview(row.version)}>
                      {t('details.preview')}
                    </Button>
                  )}
                  {onDownload && (
                    <Button variant="quiet" isDisabled={!canOpen} aria-label={t('details.versionDownload', { version: formatVersion(row.version) })} onPress={() => onDownload(row.version)}>
                      {t('details.download')}
                    </Button>
                  )}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {onShowMore && (
        <div className={styles.more}>
          <Button isDisabled={loading} onPress={onShowMore}>
            {t('details.versionsMore')}
          </Button>
        </div>
      )}
    </>
  )
}
