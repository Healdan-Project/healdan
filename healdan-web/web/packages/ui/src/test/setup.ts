// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { resetAnnouncer } from '../announcer'
import { installIntersectionObserver } from './intersection'
import { resetListSize } from './listSize'

installIntersectionObserver()

// Vitest has no globals here, so Testing Library can't register its own cleanup.
afterEach(() => {
  cleanup()
  // React Aria's first announcement waits 100 ms, which can outlast the test file's page
  resetAnnouncer()
  resetListSize()
})
