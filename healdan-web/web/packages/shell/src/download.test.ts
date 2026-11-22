// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@healdan/api'

const toast = vi.hoisted(() => vi.fn())
vi.mock('@healdan/ui', async (original) => ({ ...(await original<typeof import('@healdan/ui')>()), toast }))

const { launchDownload } = await import('./download')

// The frame, the toast and the sign-out are the User app's tests' (apps/user/src/actions/launch.test.ts), which reach this
// helper through the User app; these are what an app that says refusals its own way gives it.

const FILE = '/api/v1/admin/logs/DMS/file'

beforeEach(() => toast.mockClear())

afterEach(() => {
  for (const frame of window.document.querySelectorAll('iframe')) frame.remove()
})

/** The frame as it is once the server has answered a page, not a file */
function answer(text: string): void {
  const frame = window.document.querySelector('iframe')!
  Object.defineProperty(frame, 'contentWindow', { configurable: true, value: { location: { href: `http://localhost${FILE}` } } })
  Object.defineProperty(frame, 'contentDocument', { configurable: true, value: { body: { textContent: text } } })
  frame.dispatchEvent(new Event('load'))
}

describe('launchDownload, for an app that says refusals its own way', () => {
  it('gives it the problem the server answered in place of the file, and shows no toast', () => {
    const onRefused = vi.fn()
    launchDownload(FILE, vi.fn(), onRefused)
    answer(JSON.stringify({ status: 403, code: 'admins-only', detail: 'Only administrators can do that.' }))
    expect(onRefused).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ status: 403, code: 'admins-only' }))
    expect(onRefused.mock.lastCall?.[0]).toBeInstanceOf(ApiError)
    expect(toast).not.toHaveBeenCalled()
    expect(window.document.querySelector('iframe')).toBeNull()
  })

  it('gives it nothing for a page that holds no problem', () => {
    const onRefused = vi.fn()
    launchDownload(FILE, vi.fn(), onRefused)
    answer('<h1>Bad Gateway</h1>')
    expect(onRefused).toHaveBeenCalledExactlyOnceWith(undefined)
    expect(toast).not.toHaveBeenCalled()
  })

  it('tells a sign-out to onSignedOut still, not to onRefused', () => {
    const onSignedOut = vi.fn()
    const onRefused = vi.fn()
    launchDownload(FILE, onSignedOut, onRefused)
    answer(JSON.stringify({ status: 401, code: 'unauthenticated', detail: 'Sign in first.' }))
    expect(onSignedOut).toHaveBeenCalledExactlyOnceWith()
    expect(onRefused).not.toHaveBeenCalled()
  })
})
