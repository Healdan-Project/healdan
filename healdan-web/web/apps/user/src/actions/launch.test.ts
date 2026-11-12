// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, describe, expect, it, vi } from 'vitest'

// The setup replaces `launch` for every test; this one is the real one
const { launch } = await vi.importActual<typeof import('./launch')>('./launch')

afterEach(() => vi.restoreAllMocks())

describe('launch', () => {
  it('follows the address it is given as a click on a link would, and leaves nothing behind', () => {
    const clicked: { href: string; download: string; rel: string }[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ href: this.getAttribute('href') ?? '', download: this.getAttribute('download') ?? '', rel: this.rel })
      // While it is clicked it is in the page, which some browsers need to follow it
      expect(window.document.body.contains(this)).toBe(true)
    })
    const before = window.document.body.innerHTML

    launch('healdan://open/10388?server=localhost:8080')

    expect(clicked).toEqual([{ href: 'healdan://open/10388?server=localhost:8080', download: '', rel: 'noopener' }])
    expect(window.document.body.innerHTML).toBe(before)
  })

  it('names a download, and only a download', () => {
    const downloads: (string | null)[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push(this.getAttribute('download'))
    })
    launch('/api/v1/documents/10388/content', 'Letter.pdf')
    launch('/api/v1/documents/10388/content')
    expect(downloads).toEqual(['Letter.pdf', null])
  })

  it('reads nothing: no request is made for the address', () => {
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    launch('/api/v1/documents/10388/content', 'Letter.pdf')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
