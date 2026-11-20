// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useCallback } from 'react'
import { ApiError } from '@healdan/api'
import { useMessageFor } from '@healdan/shell'

/**
 * The rule's own sentence a refusal carries, which Healdan writes to be shown as it is (P2-R12), or undefined when it
 * carries none: a `409 refused`'s `detail`, and the `message` of a problem a check found (`MatterProblem`,
 * `TemplateProblem`). Nothing else the server says is ever shown, and an ordinary Error's message never is.
 */
export function ruleSentence(refusal: unknown): string | undefined {
  if (refusal instanceof ApiError) return refusal.code === 'refused' && refusal.detail.trim() !== '' ? refusal.detail : undefined
  if (refusal instanceof Error || refusal === null || typeof refusal !== 'object') return undefined
  const { code, message } = refusal as { code?: unknown; message?: unknown }
  return typeof code === 'string' && typeof message === 'string' && message.trim() !== '' ? message : undefined
}

/**
 * What every Admin form and dialog says of a refusal (P2-R12): the rule's own sentence when it has one (ruleSentence),
 * else the catalogue's message for the problem's code, with the Admin app's catalogue (useMessageFor). React draws the
 * sentence as text. A `422 validation` names its fields, which a form shows beside them (`fieldErrors`) rather than here.
 */
export function useRefusalFor(): (refusal: unknown) => string {
  const messageFor = useMessageFor()
  return useCallback((refusal: unknown) => ruleSentence(refusal) ?? messageFor(refusal), [messageFor])
}
