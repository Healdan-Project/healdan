// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { refusalText } from '../filing/refusal'

/** What a move or a delete needs of a document: its number, and its name for what is said of it */
export interface Movable {
  docNumber: number
  name: string
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
export async function eachDocument(documents: readonly Movable[], call: (document: Movable) => Promise<unknown>): Promise<Outcome> {
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

/** Each refusal as the document's name and why, in the catalogue's words (a hold says who holds it, as the details do). */
export function refusalLines(refused: readonly Refusal[], meId: number | undefined): { key: number; name: string; reason: string }[] {
  return refused.map(({ document, error }) => ({ key: document.docNumber, name: document.name, reason: refusalText(error, meId) }))
}
