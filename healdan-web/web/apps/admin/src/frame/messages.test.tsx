// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { ErrorPanel, MessagesProvider } from '@healdan/shell'
import { createCatalogue } from '@healdan/ui'
import { adminEnUS, adminPlurals } from '../strings/en-US'
import { lookupFor } from '../strings'

describe("A shared component in the Admin app's provider", () => {
  it("shows a message only the Admin app's catalogue has", () => {
    const extra = { ...adminEnUS, 'problems.admin-only-case': 'Only the Admin app says this.' }
    const lookup = lookupFor(extra, createCatalogue(extra, adminPlurals).t)
    render(
      <MessagesProvider lookup={lookup}>
        <ErrorPanel error={new ApiError(409, 'admin-only-case', 'x', {})} onRetry={() => {}} />
      </MessagesProvider>,
    )
    expect(screen.getByText('Only the Admin app says this.')).toBeTruthy()
  })
})
