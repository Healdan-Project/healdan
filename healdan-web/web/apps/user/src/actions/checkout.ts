// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type Api, type components } from '@healdan/api'
import { formatDateTime, t } from '@healdan/ui'

type S = components['schemas']

/**
 * What a document's `hold` means to the person looking (R14). The web page is no device, so it can't take over a
 * check-out a device holds: it says who holds it and where it's released, and never offers a take-over.
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

/** Who has it and since when, and where it's released; nothing for a free document. */
export function holdSentence(state: HoldState): string | undefined {
  if (state.kind === 'free') return undefined
  const { hold } = state
  const since = hold.since ? formatDateTime(hold.since) : undefined
  const who = hold.by?.name ?? t('hold.someone')
  switch (state.kind) {
    case 'yours':
      return since ? t('hold.yours', { since }) : t('hold.yoursUndated')
    case 'yourDevice':
      return [since ? t('hold.yourDevice', { device: hold.device?.name ?? '', since }) : t('hold.yourDeviceUndated', { device: hold.device?.name ?? '' }), t('hold.releaseOnDevice')].join(' ')
    case 'theirs':
      return [since ? t('hold.theirs', { who, since }) : t('hold.theirsUndated', { who }), t('hold.releaseTheirs')].join(' ')
    case 'locked':
      return [state.mine ? (since ? t('hold.lockedYou', { since }) : t('hold.lockedYouUndated')) : since ? t('hold.locked', { who, since }) : t('hold.lockedUndated', { who }), t('hold.unlock')].join(' ')
  }
}

export function checkOut(api: Api, docNumber: number): Promise<S['DocumentView']> {
  return api.send('POST', apiPath`/v1/documents/${docNumber}/checkout`)
}

export function release(api: Api, docNumber: number): Promise<S['DocumentView']> {
  return api.send('DELETE', apiPath`/v1/documents/${docNumber}/checkout`)
}

/** Saves a file as the document's next snapshot and checks it in; it must be the person's own check-out (`ifHeld`) and the version they saw. */
export function checkIn(api: Api, document: S['DocumentView'], file: File): Promise<S['DocumentView']> {
  const form = new FormData()
  // The document's own name, so a browser's name for the file can't change it
  const version = { kind: 'snapshot', name: document.name, ifHeld: true, ifVersion: document.version }
  form.append('version', new Blob([JSON.stringify(version)], { type: 'application/json' }))
  form.append('file', file, file.name)
  return api.send('POST', apiPath`/v1/documents/${document.docNumber}/versions`, form)
}
