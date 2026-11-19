// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// What the browser keeps for the person, in its local storage: preferences only, such as the panes' widths, never a token,
// a password or a document's data (spec sections 2.3 and 10.2). A browser can refuse storage (a private window, a setting
// that keeps nothing, a full quota), and the getter or any call may then throw: each of these reads nothing or does
// nothing, and the app goes on with its defaults. Each app keeps its own under a prefix of its own, so two apps' pane
// widths are apart.

export interface Preferences {
  /** A number kept under the name, or undefined when there's none, it isn't a number, or storage is refused */
  readNumber(name: string): number | undefined
  /** Keeps a number under the name, if storage allows */
  keepNumber(name: string, value: number): void
  /** Forgets what is kept under the name, if storage allows */
  forget(name: string): void
}

/** An app's preferences, each kept under the prefix and the name */
export function preferences(prefix: string): Preferences {
  return {
    readNumber(name) {
      try {
        const text = window.localStorage.getItem(prefix + name)
        if (text === null || text.trim() === '') return undefined
        const value = Number(text)
        return Number.isFinite(value) ? value : undefined
      } catch {
        return undefined
      }
    },
    keepNumber(name, value) {
      try {
        window.localStorage.setItem(prefix + name, String(value))
      } catch {
        // Refused: the page keeps it until it's reloaded
      }
    },
    forget(name) {
      try {
        window.localStorage.removeItem(prefix + name)
      } catch {
        // Refused: there was nothing kept to forget
      }
    },
  }
}
