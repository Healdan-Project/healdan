// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup } from '@testing-library/react'
import { clearToasts, resetAnnouncer, setLabels } from '@healdan/ui'
import { afterEach, vi } from 'vitest'
import { installIntersectionObserver } from './intersection'
import { resetListSize } from './listSize'
import { resetViewport } from './viewport'

// The page follows an address (a healdan: link, a download) in one place, which jsdom can't do; a test that cares imports
// `launch` or `launchDownload` from actions/launch and reads what it was asked to follow.
vi.mock('../actions/launch', () => ({ launch: vi.fn(), launchDownload: vi.fn() }))

installIntersectionObserver()
// The router scrolls to the top on each address, which jsdom doesn't do and says so on every test.
window.scrollTo = () => {}

// Vitest has no globals here, so Testing Library can't register its own cleanup.
afterEach(() => {
  cleanup()
  // React Aria's first announcement waits 100 ms, which can outlast the test file's page
  resetAnnouncer()
  clearToasts()
  resetViewport()
  resetListSize()
  // What a test kept as a preference (the panes' widths) isn't the next one's
  window.localStorage.clear()
  setLabels({ matter: 'Matter', matters: 'Matters' })
})
