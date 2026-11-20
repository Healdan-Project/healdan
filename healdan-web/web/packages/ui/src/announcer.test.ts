// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { announce } from 'react-aria/private/live-announcer/LiveAnnouncer'
import { expect, test } from 'vitest'
import { resetAnnouncer } from './announcer'

const page = globalThis as unknown as { document?: Document }

// Without the reset, React Aria's announcement runs once the page has gone and the run fails, though the test passes
test('a reset leaves no announcement waiting for a page that may be gone', async () => {
  announce('1 row selected.')
  resetAnnouncer()
  expect(document.querySelector('[data-live-announcer]')).toBeNull()

  const document_ = page.document
  delete page.document
  try {
    await new Promise((resolve) => setTimeout(resolve, 150))
  } finally {
    page.document = document_
  }
})
