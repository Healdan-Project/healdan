// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup } from '@testing-library/react'
import { clearToasts, resetAnnouncer, setLabels } from '@healdan/ui'
import { afterEach, vi } from 'vitest'
import { removeDownloadFrames } from './frames'
import { installIntersectionObserver } from './intersection'
import { resetListSize } from './listSize'
import { showPage } from './visibility'
import { resetViewport } from './viewport'

// A list's next page loads as its end comes into view, which a test says with bringObservedIntoView()
installIntersectionObserver()
// The router scrolls to the top on each address, which jsdom doesn't do and says so on every test.
window.scrollTo = () => {}

// Vitest has no globals here, so Testing Library can't register its own cleanup.
afterEach(() => {
  cleanup()
  // A download's frame is the page's, not the app's, so it outlives the app drawn
  removeDownloadFrames()
  // React Aria's first announcement waits 100 ms, which can outlast the test file's page
  resetAnnouncer()
  vi.useRealTimers()
  clearToasts()
  resetViewport()
  resetListSize()
  showPage()
  // What a test kept as a preference isn't the next one's
  window.localStorage.clear()
  setLabels({ matter: 'Matter', matters: 'Matters' })
})
