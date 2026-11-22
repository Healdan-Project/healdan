// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const toast = vi.hoisted(() => vi.fn())
vi.mock('@healdan/ui', async (original) => ({ ...(await original<typeof import('@healdan/ui')>()), toast }))

// The setup replaces `launch` and `launchDownload` for every test; these are the real ones
const { launch, launchDownload, FRAME_KEPT } = await vi.importActual<typeof import('./launch')>('./launch')

const CONTENT = '/api/v1/documents/10388/content'

beforeEach(() => toast.mockClear())

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  for (const frame of frames()) frame.remove()
})

function frames(): HTMLIFrameElement[] {
  return [...window.document.querySelectorAll('iframe')]
}

/** The frame as it is once the server has answered a page, not a file: the browser shows the page in it and tells it so */
function answer(frame: HTMLIFrameElement, text: string | (() => never)): void {
  Object.defineProperty(frame, 'contentWindow', { configurable: true, value: { location: { href: `http://localhost${CONTENT}` } } })
  Object.defineProperty(frame, 'contentDocument', {
    configurable: true,
    get: () => (typeof text === 'string' ? { body: { textContent: text } } : text()),
  })
  frame.dispatchEvent(new Event('load'))
}

describe('launch', () => {
  it('follows the address it is given as a click on a link would, and leaves nothing behind', () => {
    const clicked: { href: string; download: string | null; rel: string }[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ href: this.getAttribute('href') ?? '', download: this.getAttribute('download'), rel: this.rel })
      // While it is clicked it is in the page, which some browsers need to follow it
      expect(window.document.body.contains(this)).toBe(true)
    })
    const before = window.document.body.innerHTML

    launch('healdan://open/10388?server=localhost:8080')

    expect(clicked).toEqual([{ href: 'healdan://open/10388?server=localhost:8080', download: null, rel: 'noopener' }])
    expect(window.document.body.innerHTML).toBe(before)
  })
})

describe('launchDownload', () => {
  it('sends a hidden frame to the address, and clicks no link', () => {
    // A link with the download attribute is what WebKit sends without Sec-Fetch-Site, which the web session needs
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    launchDownload(`${CONTENT}?version=1.0`)

    expect(click).not.toHaveBeenCalled()
    expect(window.document.querySelector('a[download]')).toBeNull()
    const [frame, ...others] = frames()
    expect(others).toEqual([])
    expect(frame?.getAttribute('src')).toBe(`${CONTENT}?version=1.0`)
    expect(frame?.hidden).toBe(true)
    // Out of the Tab order and of the accessibility tree: it never shows anything
    expect(frame?.tabIndex).toBe(-1)
    expect(frame?.getAttribute('aria-hidden')).toBe('true')
    // No page that comes in a file's place runs a script; the frame may download, and the app may read a refusal in it
    expect(frame?.getAttribute('sandbox')).toBe('allow-same-origin allow-downloads')
    expect(window.document.body.contains(frame ?? null)).toBe(true)
  })

  it('reads nothing: no request is made for the address', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    launchDownload(CONTENT)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('gives each download a frame of its own, so one started at once does not stop the first', () => {
    launchDownload(CONTENT)
    launchDownload('/api/v1/documents/10388/message/parts/3')
    expect(frames().map((frame) => frame.getAttribute('src'))).toEqual([CONTENT, '/api/v1/documents/10388/message/parts/3'])
  })

  it('says nothing while the file downloads, and takes the frame away later', () => {
    vi.useFakeTimers()
    launchDownload(CONTENT)

    vi.advanceTimersByTime(FRAME_KEPT - 1)
    expect(frames()).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(frames()).toEqual([])
    expect(toast).not.toHaveBeenCalled()
  })

  it('says nothing when the frame loads before it has gone anywhere', () => {
    launchDownload(CONTENT)
    const [frame] = frames()
    Object.defineProperty(frame, 'contentWindow', { configurable: true, value: { location: { href: 'about:blank' } } })
    frame?.dispatchEvent(new Event('load'))

    expect(toast).not.toHaveBeenCalled()
    expect(frames()).toHaveLength(1)
  })

  it("says the problem's own message when the server answers one in place of the file, and takes the frame away", () => {
    launchDownload(CONTENT)
    answer(frames()[0]!, JSON.stringify({ status: 503, code: 'storage-unreachable', detail: "The server's own words" }))

    expect(toast).toHaveBeenCalledExactlyOnceWith('Files are unavailable for now. Try again in a few minutes.')
    expect(frames()).toEqual([])
  })

  it('says a sign-out as one', () => {
    launchDownload(CONTENT)
    answer(frames()[0]!, JSON.stringify({ status: 401, code: 'unauthenticated', detail: 'Sign in first.' }))
    expect(toast).toHaveBeenCalledExactlyOnceWith("You've been signed out.")
  })

  it.each([
    ['a page that is no problem', '<h1>Bad Gateway</h1>'],
    ['a problem with no code', '{"status":500}'],
    ['JSON that is no object', 'null'],
    ['nothing', ''],
  ])('says something went wrong for %s', (_, text) => {
    launchDownload(CONTENT)
    answer(frames()[0]!, text)
    expect(toast).toHaveBeenCalledExactlyOnceWith('Something went wrong.')
    expect(frames()).toEqual([])
  })

  it("says something went wrong for a page it can't read", () => {
    launchDownload(CONTENT)
    answer(frames()[0]!, () => {
      throw new DOMException('Blocked a frame from accessing a cross-origin frame.', 'SecurityError')
    })
    expect(toast).toHaveBeenCalledExactlyOnceWith('Something went wrong.')
    expect(frames()).toEqual([])
  })
})
