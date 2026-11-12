// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { ToastRegion } from '@healdan/ui'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyLink } from './copyLink'

function clipboard(value: unknown) {
  Object.defineProperty(navigator, 'clipboard', { value, configurable: true })
}

afterEach(() => clipboard(undefined))

describe('copyLink', () => {
  it("puts the document's address on the clipboard and says so", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    clipboard({ writeText })
    render(<ToastRegion />)

    await copyLink(10388)

    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/app/doc/10388`)
    expect(await screen.findByText('Link copied')).toBeTruthy()
  })

  it("says the link couldn't be copied when the browser has no clipboard (a page on plain http)", async () => {
    clipboard(undefined)
    render(<ToastRegion />)

    await copyLink(10388)

    expect(await screen.findByText("The link couldn't be copied.")).toBeTruthy()
    expect(screen.queryByText('Link copied')).toBeNull()
  })

  it("says so when the browser refuses", async () => {
    clipboard({ writeText: vi.fn().mockRejectedValue(new DOMException('no', 'NotAllowedError')) })
    render(<ToastRegion />)

    await copyLink(10388)

    expect(await screen.findByText("The link couldn't be copied.")).toBeTruthy()
    expect(screen.queryByText('Link copied')).toBeNull()
  })
})
