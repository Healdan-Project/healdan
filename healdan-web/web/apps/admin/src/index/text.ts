// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { plural, t } from '../strings'

type A = adminComponents['schemas']

/** A document's doc # as the lists show it, `# 10412`; nothing for one that has none */
export function docNumberWords(document: A['IndexDocument']): string {
  return document.docNumber === null ? '' : t('admin.searchIndex.docNumber', { n: String(document.docNumber) })
}

/** A document's doc # as a row's header: `# 10412`, or that it has none */
export function docNumberHeader(document: A['IndexDocument']): string {
  return docNumberWords(document) || t('admin.searchIndex.noNumber')
}

/** The matter stamped on a document, by its number, as the mockup's lists show it; nothing for none */
export function matterWords(document: A['IndexDocument']): string {
  return document.matter?.number ?? ''
}

/** A document named in a sentence: its doc # and file name, `# 10412 Witness Statement.pdf`, or its file name alone */
export function documentWords(document: A['IndexDocument']): string {
  const number = docNumberWords(document)
  return number === '' ? document.fileName : `${number} ${document.fileName}`
}

/**
 * About how long a reindex has left, from the server's estimate in seconds: less than a minute, so many minutes up to an
 * hour and a half, then so many hours
 */
export function timeLeftWords(seconds: number): string {
  if (seconds < 60) return t('admin.searchIndex.job.lessThanAMinute')
  const minutes = Math.ceil(seconds / 60)
  if (minutes < 90) return plural('admin.searchIndex.job.minutesLeft', minutes)
  return plural('admin.searchIndex.job.hoursLeft', Math.round(minutes / 60))
}

/** A reindex's card's title: what it's doing, or did, to what it covers */
export function jobTitle(job: A['ReindexJob']): string {
  switch (job.state) {
    case 'running':
      return t('admin.searchIndex.job.running', { name: job.name })
    case 'waiting':
      return t('admin.searchIndex.job.waiting', { name: job.name })
    case 'done':
      return t('admin.searchIndex.job.done', { name: job.name })
    case 'cancelled':
      return t('admin.searchIndex.job.cancelled', { name: job.name })
  }
}

/**
 * How a reindex went, or has gone so far: how many it reindexed, then how many couldn't be indexed and how many it skipped,
 * each only when there are any
 */
export function outcomeWords(job: A['ReindexJob']): string {
  const parts = [plural('admin.searchIndex.outcome.reindexed', job.done)]
  if (job.failed > 0) parts.push(plural('admin.searchIndex.outcome.failed', job.failed))
  if (job.skipped > 0) parts.push(plural('admin.searchIndex.outcome.skipped', job.skipped))
  return parts.join(', ')
}

/** A reindex's state, on the History tab: waiting, running, or how it ended */
export function stateWords(job: A['ReindexJob']): string {
  switch (job.state) {
    case 'waiting':
      return t('admin.searchIndex.state.waiting')
    case 'running':
      return job.cancelling ? t('admin.searchIndex.state.cancelling') : t('admin.searchIndex.state.running')
    case 'done':
      return t('admin.searchIndex.state.done')
    case 'cancelled':
      return t('admin.searchIndex.state.cancelled')
  }
}

/** Who started a reindex, and when, as its card says it */
export function startedWords(job: A['ReindexJob']): string {
  return t('admin.searchIndex.job.startedBy', { name: job.startedBy.name, when: formatDateTime(job.created) })
}
