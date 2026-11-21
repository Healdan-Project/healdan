// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatDateTime, formatSize, type Status } from '@healdan/ui'
import { plural, t } from '../strings'

type A = adminComponents['schemas']

/** "Local Folder" or "S3" */
export function kindOf(backend: Pick<A['Backend'], 'kind'>): string {
  return t(backend.kind === 's3' ? 'admin.backends.kind.s3' : 'admin.backends.kind.local')
}

/** A folder's path, or an S3 bucket and its prefix at its endpoint, as the Storage tab says it; the bucket alone when the endpoint can't be read */
export function whereOf(backend: A['Backend']): string {
  if (backend.kind !== 's3') return backend.path ?? ''
  const place = { bucket: backend.bucket ?? '', prefix: backend.prefix ?? '' }
  return backend.endpoint === null ? t('admin.backends.where.s3Bucket', place) : t('admin.backends.where.s3', { ...place, endpoint: backend.endpoint })
}

/** The dot a status draws beside its words: OK, something to look at, or not checked yet */
export function statusDot(backend: A['Backend']): Status {
  return backend.status === 'ok' ? 'ok' : backend.status === 'unchecked' ? 'off' : 'attention'
}

/** "OK", "Not Checked Yet", or "Failing since …: why", from the backend's last use, Test or check */
export function statusWords(backend: A['Backend']): string {
  const name = t(`admin.backends.status.${backend.status}`)
  if (backend.status === 'ok' || backend.status === 'unchecked') return name
  const since = backend.since === null ? name : t('admin.backends.status.since', { status: name, when: formatDateTime(backend.since) })
  return backend.error === null ? since : t('admin.backends.status.why', { status: since, error: backend.error })
}

/**
 * The status, and, for an encrypted backend whose recovery key isn't confirmed, that no Store can use it yet, as the
 * Storage tab's Status column says them
 */
export function statusLine(backend: A['Backend']): string {
  const words = statusWords(backend)
  if (backend.offered) return words
  // The server's error may end its own sentence
  return `${words}${words.endsWith('.') ? '' : '.'} ${t('admin.backends.notOffered')}`
}

/** When its recovery key was last shown, and to whom; Never for an encrypted one never shown, nothing for one not encrypted */
export function keyShownOf(backend: A['Backend']): string {
  if (backend.keyLastShown === null) return backend.encrypted ? t('admin.backends.keyNever') : ''
  return t('admin.backends.keyShown', { when: formatDateTime(backend.keyLastShown), username: backend.keyLastShownBy ?? '' })
}

/** "1 file", "3,902 files" */
export function filesOf(count: number): string {
  return plural('admin.storage.files', count)
}

/** "None", or "12 files (3.1 MB)": files waiting to move, with their size */
export function waitingOf(files: number, bytes: number): string {
  return files === 0 ? t('admin.backends.other.none') : t('admin.backends.other.size', { files: filesOf(files), size: formatSize(bytes) })
}

/** "None", or "3 files": files the mover gave up on */
export function stuckOf(files: number): string {
  return files === 0 ? t('admin.backends.other.none') : filesOf(files)
}
