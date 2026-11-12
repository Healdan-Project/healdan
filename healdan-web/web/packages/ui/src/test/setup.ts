// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { installIntersectionObserver } from './intersection'

installIntersectionObserver()

// Vitest has no globals here, so Testing Library can't register its own cleanup.
afterEach(cleanup)
