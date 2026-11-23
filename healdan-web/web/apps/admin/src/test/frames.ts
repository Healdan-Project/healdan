// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/** The hidden frames downloads went through, in the order they were started */
export function downloadFrames(): HTMLIFrameElement[] {
  return [...window.document.querySelectorAll('iframe')]
}

/**
 * A download's frame as it is once the server has answered a page in place of the file: the browser shows the page in
 * it and tells it so, as Chromium and WebKit do. jsdom loads no frame, so a test says it here.
 */
export function answerFrame(frame: HTMLIFrameElement, text: string): void {
  Object.defineProperty(frame, 'contentWindow', { configurable: true, value: { location: { href: new URL(frame.getAttribute('src') ?? '', 'http://localhost').href } } })
  Object.defineProperty(frame, 'contentDocument', { configurable: true, value: { body: { textContent: text } } })
  frame.dispatchEvent(new Event('load'))
}

/** The frames a test's downloads left, which jsdom never loads */
export function removeDownloadFrames(): void {
  for (const frame of downloadFrames()) frame.remove()
}
