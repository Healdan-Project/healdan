// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act } from '@testing-library/react'

// jsdom's page is always visible. These hide it, as a browser does when its tab is in the background or its window
// minimised, and show it again, which is what TanStack Query reads before a poll (its focusManager) and what the shell's
// query client hears as the person coming back (listenForReturn).

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (hidden ? 'hidden' : 'visible') })
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
}

/** The page goes into the background */
export function hidePage() {
  setHidden(true)
  act(() => void document.dispatchEvent(new Event('visibilitychange')))
}

/** The page is visible again, as jsdom has it, without saying so to anyone: for after a test */
export function showPage() {
  delete (document as { visibilityState?: unknown }).visibilityState
  delete (document as { hidden?: unknown }).hidden
}

/** The person comes back to the page */
export function returnToPage() {
  showPage()
  act(() => void document.dispatchEvent(new Event('visibilitychange')))
}
