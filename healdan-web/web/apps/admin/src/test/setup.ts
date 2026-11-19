// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup } from '@testing-library/react'
import { clearToasts, setLabels } from '@healdan/ui'
import { afterEach } from 'vitest'

// The router scrolls to the top on each address, which jsdom doesn't do and says so on every test.
window.scrollTo = () => {}

// Vitest has no globals here, so Testing Library can't register its own cleanup.
afterEach(() => {
  cleanup()
  clearToasts()
  // What a test kept as a preference isn't the next one's
  window.localStorage.clear()
  setLabels({ matter: 'Matter', matters: 'Matters' })
})
