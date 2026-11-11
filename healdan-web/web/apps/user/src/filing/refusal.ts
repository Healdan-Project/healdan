// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, type components } from '@healdan/api'
import { t } from '@healdan/ui'
import { holdSentence, holdState } from '../actions/checkout'
import { fieldErrors, messageFor } from '../frame/errors'

type S = components['schemas']

/** The codes that name who holds a document, in a `hold` member */
const HOLDS = new Set(['checked-out', 'locked', 'held-elsewhere'])

/** The copy an `already-filed` refusal names, when the person can read it */
export function copyOf(error: unknown): S['DocumentView'] | undefined {
  if (!(error instanceof ApiError) || error.code !== 'already-filed') return undefined
  const copy = (error.body as { document?: S['DocumentView'] } | null)?.document
  return copy && typeof copy.docNumber === 'number' ? copy : undefined
}

/**
 * What the tray says of a refused item, all from the catalogue (the server's own text is never shown): an email already
 * in the matter names its copy; a refusal that names a field says what is wrong with it; one because of a check-out or a
 * lock says who holds the document and where it's released (R14, as the details say).
 */
export function refusalText(error: unknown, meId: number | undefined): string {
  const copy = copyOf(error)
  if (copy) return t('tray.alreadyFiled', { n: copy.docNumber })
  const [first] = fieldErrors(error).values()
  if (first) return first
  const message = messageFor(error)
  if (error instanceof ApiError && HOLDS.has(error.code)) {
    const hold = (error.body as { hold?: S['HoldView'] } | null)?.hold
    const sentence = hold ? holdSentence(holdState(hold, meId)) : undefined
    if (sentence) return `${message} ${sentence}`
  }
  return message
}
