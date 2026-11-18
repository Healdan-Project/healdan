// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Api, components } from '@healdan/api'
import { plural, t, toast, type ToastAction } from '@healdan/ui'
import type { RefusalLine } from '../actions/batch'
import { restoreItems } from '../actions/trash'
import { refusalText } from '../filing/refusal'

type S = components['schemas']

/** What an Undo needs of the page it was made on */
export interface UndoReport {
  /** Asks again for what the restored items show in, once they're back */
  refresh: (restored: readonly S['RestoredItem'][]) => Promise<unknown>
  /** Says what wasn't restored, and why, in the Refusals dialog */
  refuse: (refused: { title: string; lines: RefusalLine[] }) => void
  /** The person, so a hold they have is said as theirs */
  meId?: number
}

/**
 * Restores each item a delete made, back where it was, one after another (actions/trash.ts), then says what came back:
 * "Restored "Old Drafts"", or how many. The world can have moved on since the delete (the plan's Review Focus 5): an
 * item already restored, by the person in the Trash or by an administrator, answers 404, and one whose place is gone
 * answers place-gone. Each is said in the Refusals dialog, and the rest is restored all the same.
 */
export async function undoDeletes(api: Api, items: readonly S['TrashItem'][], report: UndoReport): Promise<void> {
  const outcome = await restoreItems(api, items)
  await report.refresh(outcome.done)
  const [one] = outcome.done
  if (outcome.done.length === 1 && one) toast(t('undo.done', { name: one.item.name }))
  else if (outcome.done.length > 1) toast(plural('undo.doneMany', outcome.done.length))
  const why = new Map<number, string>([
    ...outcome.placeGone.map((item): [number, string] => [item.id, t('problems.place-gone')]),
    ...outcome.refused.map(({ item, error }): [number, string] => [item.id, refusalText(error, report.meId)]),
  ])
  // In the delete's order, as the toast's items are
  const lines = items.flatMap((item) => {
    const reason = why.get(item.id)
    return reason === undefined ? [] : [{ key: item.id, name: item.name, reason }]
  })
  if (lines.length > 0) report.refuse({ title: t('undo.refusedTitle'), lines })
}

/**
 * The toast's Undo for the items a delete made. It restores them once, however often it's pressed, and from wherever:
 * the server doesn't serialise two restores of one item, so the guard is the action's own, not only the button's.
 */
export function undoAction(api: Api, items: readonly S['TrashItem'][], report: UndoReport): ToastAction {
  let used = false
  return {
    label: t('undo.label'),
    onAction: () => {
      if (used) return
      used = true
      void undoDeletes(api, items, report)
    },
  }
}
