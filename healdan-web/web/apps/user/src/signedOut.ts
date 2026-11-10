// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Whether a request has told the app the person is no longer signed in. One per app, so a test's app is its own.
export interface SignedOutState {
  mark(): void
  get(): boolean
  subscribe(listener: () => void): () => void
}

export function createSignedOut(): SignedOutState {
  let value = false
  const listeners = new Set<() => void>()
  return {
    mark() {
      if (value) return
      value = true
      for (const listener of [...listeners]) listener()
    },
    get: () => value,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
