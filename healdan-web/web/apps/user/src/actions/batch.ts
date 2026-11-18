// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { refusalText } from '../filing/refusal'
import type { MovingFolder } from './folders'

/** What a move or a delete needs of a document: its number, and its name for what is said of it */
export interface Movable {
  docNumber: number
  name: string
  /** Where it is, when that's known, so a move to there is known to change nothing */
  folder?: { id: number }
}

export interface Refusal {
  document: Movable
  error: unknown
}

/** What a change to several documents did: each is its own call, so some can be done while others are refused */
export interface Outcome {
  done: Movable[]
  refused: Refusal[]
}

/**
 * Makes `call` for each document in turn, one after another, so the server never has two at once, and keeps going when
 * one is refused: a refusal doesn't undo what was done, nor stop the rest.
 */
export async function eachDocument<T extends Movable>(documents: readonly T[], call: (document: T) => Promise<unknown>): Promise<Outcome> {
  const outcome: Outcome = { done: [], refused: [] }
  for (const document of documents) {
    try {
      await call(document)
      outcome.done.push(document)
    } catch (error) {
      outcome.refused.push({ document, error })
    }
  }
  return outcome
}

/** Folders and documents being moved or deleted together: a selection, a row, the tree's folder or the heading's */
export interface OrganisingItems {
  documents: readonly Movable[]
  folders: readonly MovingFolder[]
}

/** One of them, with what its call answered or why it was refused */
export type OneItem = { document: Movable } | { folder: MovingFolder }
export type ItemDone<T> = OneItem & { answer: T }
export type ItemRefusal = OneItem & { error: unknown }

/** What a change to folders and documents did: each is its own call, so some can be done while others are refused */
export interface ItemOutcome<T> {
  done: ItemDone<T>[]
  refused: ItemRefusal[]
}

/** The item's name, as what is said of it names it */
export function nameOf(item: OneItem): string {
  return 'document' in item ? item.document.name : item.folder.name
}

/**
 * Makes each item's own call in turn, the folders first, as a list shows them, then the documents, one after another, so
 * the server never has two at once, and keeps going when one is refused, as eachDocument does.
 */
export async function eachItem<T>(
  items: OrganisingItems,
  calls: { document: (document: Movable) => Promise<T>; folder: (folder: MovingFolder) => Promise<T> },
): Promise<ItemOutcome<T>> {
  const outcome: ItemOutcome<T> = { done: [], refused: [] }
  const all: OneItem[] = [...items.folders.map((folder) => ({ folder })), ...items.documents.map((document) => ({ document }))]
  for (const item of all) {
    try {
      const answer = 'document' in item ? await calls.document(item.document) : await calls.folder(item.folder)
      outcome.done.push({ ...item, answer })
    } catch (error) {
      outcome.refused.push({ ...item, error })
    }
  }
  return outcome
}

/** A line of the Refusals dialog: who was refused and why, with what the reason names under it and what can be done */
export interface RefusalLine {
  key: number | string
  name: string
  reason: string
  details?: string[]
  after?: string
}

/** Each refusal as the document's name and why, in the catalogue's words (a hold says who holds it, as the details do). */
export function refusalLines(refused: readonly Refusal[], meId: number | undefined): RefusalLine[] {
  return refused.map(({ document, error }) => ({ key: document.docNumber, name: document.name, reason: refusalText(error, meId) }))
}
