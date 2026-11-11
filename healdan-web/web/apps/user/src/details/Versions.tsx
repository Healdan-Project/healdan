// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { formatDateTime, formatVersion, t } from '@healdan/ui'
import { Section } from './Section'
import styles from './details.module.css'

type S = components['schemas']

/** A document's timeline, newest first as the server gives it. `selected` is the version a # named (`#10388.2`). */
export function Versions({ versions, selected }: { versions: S['VersionView'][]; selected?: string }) {
  return (
    <Section title={t('details.versions')}>
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
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}
