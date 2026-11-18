// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId } from 'react'
import type { components } from '@healdan/api'
import { Button, Dialog, plural, t } from '@healdan/ui'
import styles from './organising.module.css'

type S = components['schemas']

/** What is to be removed: some of the Trash's items, or all the person deleted (Empty Trash), which `count` says how many */
export type RemoveQuestion = { items: readonly S['TrashItem'][] } | { empty: number }

/** "3 folders and 11 documents", of what the person can read in a deleted folder (spec 4.2): the question's words */
export function holdsLine(contents: S['TrashContents']): string {
  return t('holds.line', { folders: plural('holds.folders', contents.folders), documents: plural('holds.documents', contents.documents) })
}

export interface ConfirmRemoveProps {
  question: RemoveQuestion
  /** How long an administrator can still restore what's removed, as the administrators set it (from the Trash's page) */
  removedDays: number
  onConfirm: () => void
  onCancel: () => void
}

/**
 * Remove… and Empty Trash… ask first (folders and Trash spec 7.4): "Remove "Old Drafts" from the Trash?" with what it holds,
 * "Remove 3 items from the Trash?" naming them, or "Empty the Trash?" with how many; each says the person won't be able to
 * restore it, and an administrator can, for the days set. The red button removes; Cancel, which has the focus, and Esc
 * don't. It's the one step a person can't undo themselves, so it's an alert, with the warning sign, as the release's
 * warning is (ConfirmRelease), and a screen reader reads that line as it opens; Delete's question and a folder leaving
 * its matter can both be undone, and stay plain questions.
 */
export function ConfirmRemove({ question, removedDays, onConfirm, onCancel }: ConfirmRemoveProps) {
  const empty = 'empty' in question
  const items = empty ? [] : question.items
  const one = items.length === 1 ? items[0] : undefined
  const title = empty ? t('remove.emptyTitle') : one ? t('remove.oneTitle', { name: one.name }) : plural('remove.manyTitle', items.length)
  // Only what the person can read is counted (TrashItem.contents), and an empty folder says nothing of it
  const holds = one?.contents && one.contents.folders + one.contents.documents > 0 ? holdsLine(one.contents) : undefined
  const warningId = useId()
  return (
    <Dialog
      warning
      title={title}
      aria-describedby={warningId}
      isOpen
      onOpenChange={(open) => !open && onCancel()}
    >
      {(close) => (
        <>
          {empty && <p>{plural('remove.emptyLine', question.empty)}</p>}
          {holds && <p>{holds}</p>}
          {items.length > 1 && (
            <ul className={styles.names}>
              {items.map((item) => (
                <li key={item.id}>{item.name}</li>
              ))}
            </ul>
          )}
          {/* "it" for one item, "them" for several or all the person deleted, and the days counted as days are */}
          <p id={warningId}>{plural('remove.adminCan', empty ? question.empty : items.length, { days: plural('trash.days', removedDays) })}</p>
          <div className={styles.actions}>
            <Button autoFocus onPress={close}>
              {t('common.cancel')}
            </Button>
            <Button
              variant="danger"
              onPress={() => {
                close()
                onConfirm()
              }}
            >
              {empty ? t('remove.emptyConfirm') : t('remove.confirm')}
            </Button>
          </div>
        </>
      )}
    </Dialog>
  )
}
