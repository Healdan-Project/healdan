// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { typed } from '../pickers/typing'
import { plural, t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'

type A = adminComponents['schemas']

const AREAS: Record<A['ErrorArea'], AdminStringKey> = {
  Previews: 'admin.errors.area.previews',
  Storage: 'admin.errors.area.storage',
  'Search Index': 'admin.errors.area.searchIndex',
  Email: 'admin.errors.area.email',
  'Sign-In': 'admin.errors.area.signIn',
  'Background Jobs': 'admin.errors.area.backgroundJobs',
  Server: 'admin.errors.area.server',
}

/** A fault's area, by its name */
export function areaWords(area: A['ErrorArea']): string {
  return t(AREAS[area])
}

/** The level a fault was first logged at, in words */
export function levelWords(level: A['ErrorDetail']['level']): string {
  return t(level === 'FATAL' ? 'admin.errors.level.FATAL' : 'admin.errors.level.ERROR')
}

/** A document a fault's message names: by its doc # and file name, by its file name and id with no doc #, or by its id once gone */
export function documentWords(document: A['ErrorDocument']): string {
  if (document.fileName === null) return t('admin.errors.documentGone', { id: String(document.id) })
  if (document.docNumber === null) return t('admin.errors.documentNoNumber', { file: document.fileName, id: String(document.id) })
  return t('admin.errors.document', { number: String(document.docNumber), file: document.fileName })
}

/** Whether, and by whom and when, the fault was marked resolved */
export function statusWords(detail: A['ErrorDetail']): string {
  if (!detail.resolved) return t('admin.errors.statusUnresolved')
  return t('admin.errors.statusResolved', { who: detail.resolvedBy ?? '', when: detail.resolvedAt === null ? '' : formatDateTime(detail.resolvedAt) })
}

/**
 * The words Show in Server Log searches the log for: the first line of the fault's latest message with words on it, cut as
 * the Server Log's text filter takes it (TYPED_MAX, 100 characters). The log writes that line as its entry's message, so
 * the search finds the latest time it was logged; the server compares it with each line's message as answered, which has
 * the same URL credentials cut out as the fault's.
 */
export function logSearchWords(message: string): string {
  const line = message.split(/\r?\n/).find((one) => one.trim() !== '') ?? ''
  return typed(line)
}

/** The facts of the details, in their order: each name with its value, as the page shows them and Copy Details copies them */
export function errorFacts(detail: A['ErrorDetail']): { name: string; value: string }[] {
  return [
    { name: t('admin.errors.level'), value: levelWords(detail.level) },
    { name: t('admin.errors.area'), value: areaWords(detail.area) },
    { name: t('admin.errors.logger'), value: detail.logger },
    { name: t('admin.errors.seen'), value: plural('admin.errors.timesInAll', detail.total) },
    { name: t('admin.errors.firstSeen'), value: formatDateTime(detail.firstSeen) },
    { name: t('admin.errors.lastSeen'), value: formatDateTime(detail.lastSeen) },
    { name: t('admin.errors.status'), value: statusWords(detail) },
  ]
}

/** What Copy Details copies: the message, the facts a line each, the documents named and the stack, as plain text */
export function errorDetailsText(detail: A['ErrorDetail']): string {
  const facts = errorFacts(detail).map((fact) => t('admin.errors.copyFact', fact))
  const documents = detail.documents.length === 0 ? t('admin.errors.noDocuments') : detail.documents.map(documentWords).join('; ')
  const lines = [detail.message, '', ...facts, t('admin.errors.copyFact', { name: t('admin.errors.documents'), value: documents })]
  if (detail.stack !== null) lines.push('', t('admin.errors.copyStack'), detail.stack)
  return lines.join('\n')
}
