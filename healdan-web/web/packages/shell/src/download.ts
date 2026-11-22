// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError } from '@healdan/api'
import { toast } from '@healdan/ui'
import { messageFor } from './frame/errors'

/**
 * How long a download's frame stays, in milliseconds. Taking it away stops a download the server hasn't begun to answer,
 * and says nothing, so it stays far longer than any answer takes to start: a file in cloud storage that isn't in the
 * server's read cache is fetched whole before its first byte is sent. A download that has begun no longer needs it.
 */
export const FRAME_KEPT = 60 * 60 * 1000

/**
 * Downloads a file of the API's, as both apps do: a hidden frame goes to its address, a plain navigation the web session
 * carries. The answer's own `Content-Disposition` makes it a download and names the file. No script reads the bytes, and
 * no blob is made.
 *
 * Not a link with the `download` attribute: WebKit, Safari's too, sends such a link's request with the cookie but without
 * `Sec-Fetch-Site`, so the web session refuses it (`401`) and saves the refusal under the file's name. Nor a link without
 * the attribute, which would put a refusal's page in the app's place. The app's policy allows a frame of its own origin.
 *
 * When the server answers a page in place of a file (a problem, as while storage is unreachable), the frame shows it to
 * nobody, so the person is told here. Chromium and WebKit say when that page has loaded; Firefox says nothing. A `401`
 * goes to `onSignedOut`, as every request's does, for the app to show its signed-out page, which says so itself. Any other
 * refusal goes to `onRefused` when the app gives one (the problem, or undefined for a page that holds none), for the app to
 * say it as its own refusals are said; without one, it's a toast of the shared catalogue's message.
 */
export function launchDownload(href: string, onSignedOut?: () => void, onRefused?: (refusal: ApiError | undefined) => void): void {
  const frame = window.document.createElement('iframe')
  frame.hidden = true
  frame.tabIndex = -1
  frame.setAttribute('aria-hidden', 'true')
  // Whatever page the server answers in place of a file runs no script in the app's origin; the app can still read it
  frame.setAttribute('sandbox', 'allow-same-origin allow-downloads')
  // A download is no page, so the frame loads only what isn't one
  frame.addEventListener('load', () => {
    let text: string | null | undefined
    try {
      if (frame.contentWindow?.location.href === 'about:blank') return
      text = frame.contentDocument?.body?.textContent
    } catch {
      // A page the app can't read, such as the browser's own error page: still no file
      text = undefined
    }
    frame.remove()
    const refusal = problem(text)
    if (refusal?.status === 401 && onSignedOut) onSignedOut()
    else if (onRefused) onRefused(refusal)
    else toast(messageFor(refusal))
  })
  frame.src = href
  window.document.body.append(frame)
  window.setTimeout(() => frame.remove(), FRAME_KEPT)
}

/** The API's problem a page's text holds, or undefined if it holds none */
function problem(text: string | null | undefined): ApiError | undefined {
  let body: unknown
  try {
    body = JSON.parse(text ?? '')
  } catch {
    return undefined
  }
  if (body === null || typeof body !== 'object') return undefined
  const { status, code, detail } = body as { status?: unknown; code?: unknown; detail?: unknown }
  if (typeof code !== 'string') return undefined
  return new ApiError(typeof status === 'number' ? status : 0, code, typeof detail === 'string' ? detail : '', body)
}
