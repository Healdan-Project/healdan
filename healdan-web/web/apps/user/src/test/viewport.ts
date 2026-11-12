// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act } from '@testing-library/react'

// jsdom has no matchMedia. This one answers the `(max-width: Npx)` queries the app asks, for the width a test sets, and tells
// the listeners when `resize` changes it.
let width = 1280
const listeners = new Set<() => void>()

function install() {
  window.matchMedia = (query: string) => {
    const max = /\(max-width:\s*(\d+)px\)/.exec(query)
    const result = {
      media: query,
      onchange: null,
      get matches() {
        return max !== null && width <= Number(max[1])
      },
      addEventListener: (_: string, listener: () => void) => void listeners.add(listener),
      removeEventListener: (_: string, listener: () => void) => void listeners.delete(listener),
      addListener: (listener: () => void) => void listeners.add(listener),
      removeListener: (listener: () => void) => void listeners.delete(listener),
      dispatchEvent: () => false,
    }
    return result as unknown as MediaQueryList
  }
}

/** Sets the window's width for the media queries, before a render */
export function setViewport(pixels: number) {
  width = pixels
  install()
}

/** Changes the width while the app is shown */
export function resize(pixels: number) {
  width = pixels
  act(() => listeners.forEach((listener) => listener()))
}

/** Back to a window with no matchMedia and the wide default, which is what jsdom has */
export function resetViewport() {
  width = 1280
  listeners.clear()
  // @ts-expect-error jsdom has none, and the app copes without
  delete window.matchMedia
}
