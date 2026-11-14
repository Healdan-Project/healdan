// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// What the browser keeps for the person, in its local storage: preferences only, such as the panes' widths, never a token,
// a password or a document's data (spec sections 2.3 and 10.2). A browser can refuse storage (a private window, a setting
// that keeps nothing, a full quota), and the getter or any call may then throw: each of these reads nothing or does
// nothing, and the app goes on with its defaults.

const PREFIX = 'healdan.'

/** A number kept under the name, or undefined when there's none, it isn't a number, or storage is refused */
export function readNumber(name: string): number | undefined {
  try {
    const text = window.localStorage.getItem(PREFIX + name)
    if (text === null || text.trim() === '') return undefined
    const value = Number(text)
    return Number.isFinite(value) ? value : undefined
  } catch {
    return undefined
  }
}

/** Keeps a number under the name, if storage allows */
export function keepNumber(name: string, value: number): void {
  try {
    window.localStorage.setItem(PREFIX + name, String(value))
  } catch {
    // Refused: the page keeps it until it's reloaded
  }
}

/** Forgets what is kept under the name, if storage allows */
export function forget(name: string): void {
  try {
    window.localStorage.removeItem(PREFIX + name)
  } catch {
    // Refused: there was nothing kept to forget
  }
}
