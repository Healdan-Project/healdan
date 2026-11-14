// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type Api, type components } from '@healdan/api'
import { formatDateTime, t } from '@healdan/ui'

type S = components['schemas']

/**
 * What a document's `hold` means to the person looking (R14). The web page is no device, so it can't take over a
 * check-out a device holds: it says who holds it, and never offers a take-over.
 */
export type HoldState =
  | { kind: 'free' }
  /** The person's own check-out from the web page, which belongs to no computer: Check In and Release work */
  | { kind: 'yours'; hold: S['HoldView'] }
  /** The person's own check-out on a computer, which releases it */
  | { kind: 'yourDevice'; hold: S['HoldView'] }
  | { kind: 'theirs'; hold: S['HoldView'] }
  /** `mine` when the person took the lock themselves */
  | { kind: 'locked'; hold: S['HoldView']; mine: boolean }

export function holdState(hold: S['HoldView'] | null, meId: number | undefined): HoldState {
  if (!hold) return { kind: 'free' }
  const mine = meId !== undefined && hold.by?.id === meId
  if (hold.kind === 'lock') return { kind: 'locked', hold, mine }
  if (mine) return hold.device ? { kind: 'yourDevice', hold } : { kind: 'yours', hold }
  return { kind: 'theirs', hold }
}

/**
 * Who has it, as a list row's marker says it, in its name and its tooltip: "Checked out by Priya Nair", "Locked by you".
 * No time and no computer, which the details' Check-Out Info has; nothing for a free document.
 */
export function holdLabel(state: HoldState): string | undefined {
  if (state.kind === 'free') return undefined
  const who = state.hold.by?.name ?? t('hold.someone')
  switch (state.kind) {
    case 'yours':
    case 'yourDevice':
      return t('hold.checkedOutByYou')
    case 'theirs':
      return t('hold.checkedOutBy', { who })
    case 'locked':
      return state.mine ? t('hold.lockedByYou') : t('hold.lockedBy', { who })
  }
}

/**
 * Who has it and since when, as a sentence, where a question or a refusal says why something can't be done: "Checked out
 * by Jamie Clarke since 3 Nov 2026, 10:00."; nothing for a free document.
 */
export function holdSentence(state: HoldState): string | undefined {
  if (state.kind === 'free') return undefined
  const { hold } = state
  const since = hold.since ? formatDateTime(hold.since) : undefined
  const who = hold.by?.name ?? t('hold.someone')
  switch (state.kind) {
    case 'yours':
      return since ? t('hold.yours', { since }) : t('hold.yoursUndated')
    case 'yourDevice':
      return since ? t('hold.yourDevice', { device: hold.device?.name ?? '', since }) : t('hold.yourDeviceUndated', { device: hold.device?.name ?? '' })
    case 'theirs':
      return since ? t('hold.theirs', { who, since }) : t('hold.theirsUndated', { who })
    case 'locked':
      return state.mine ? (since ? t('hold.lockedYou', { since }) : t('hold.lockedYouUndated')) : since ? t('hold.locked', { who, since }) : t('hold.lockedUndated', { who })
  }
}

export function checkOut(api: Api, docNumber: number): Promise<S['DocumentView']> {
  return api.send('POST', apiPath`/v1/documents/${docNumber}/checkout`)
}

export function release(api: Api, docNumber: number): Promise<S['DocumentView']> {
  return api.send('DELETE', apiPath`/v1/documents/${docNumber}/checkout`)
}

export interface SaveOptions {
  /** `snapshot` for an ordinary save (2.1 becomes 2.2), `version` for the next major number (2.2 becomes 3.0) */
  kind: 'snapshot' | 'version'
  /** True when the save is only to be made while the document is the person's own check-out; false saves a free document and checks it in */
  ifHeld: boolean
}

/** Saves a file as the document's next version, from the version the person saw (`ifVersion`), under the document's own name. */
export function saveVersion(api: Api, document: S['DocumentView'], file: File, options: SaveOptions): Promise<S['DocumentView']> {
  const form = new FormData()
  // The document's own name, so a browser's name for the file can't change it
  const version = { kind: options.kind, name: document.name, ifHeld: options.ifHeld, ifVersion: document.version }
  form.append('version', new Blob([JSON.stringify(version)], { type: 'application/json' }))
  form.append('file', file, file.name)
  return api.send('POST', apiPath`/v1/documents/${document.docNumber}/versions`, form)
}

/** Saves a file as the document's next snapshot and checks it in; it must be the person's own check-out (`ifHeld`) and the version they saw. */
export function checkIn(api: Api, document: S['DocumentView'], file: File): Promise<S['DocumentView']> {
  return saveVersion(api, document, file, { kind: 'snapshot', ifHeld: true })
}
