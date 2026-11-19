// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createApi, type components } from '@healdan/api'
import { InsecureAddress } from '@healdan/shell'
import { setLabels } from '@healdan/ui'
import { App } from './App'
import { createQueryClient } from './queryClient'
import { createAppRouter } from './router'
import { createSignedOut } from './signedOut'

/** How long the first draw waits for the server's word for a matter */
const SERVER_INFO_WAIT_MS = 5_000

/**
 * Draws the app in `root`, and gives back what takes it away again. At an address that isn't secure (plain http://, not
 * the machine itself) the browser sends no Sec-Fetch-Site, so the web session's route refuses every call: the app says
 * so on a page of its own, before it asks the server anything (R91).
 */
export async function start(root: HTMLElement, secure = window.isSecureContext): Promise<() => void> {
  const drawn = createRoot(root)
  if (!secure) {
    drawn.render(
      <StrictMode>
        <InsecureAddress />
      </StrictMode>,
    )
    return () => drawn.unmount()
  }
  const signedOut = createSignedOut()
  const api = createApi({ onSignedOut: signedOut.mark })
  const queryClient = createQueryClient()
  const router = createAppRouter()
  let gone = false
  const draw = () => {
    if (gone) return
    drawn.render(
      <StrictMode>
        <App api={api} queryClient={queryClient} signedOut={signedOut} router={router} />
      </StrictMode>,
    )
  }
  // The firm's word for a matter is set before the first render. If the server can't say, Matter and Matters stay. If it
  // hasn't said within SERVER_INFO_WAIT_MS, the app is drawn with them, so a server that hangs leaves the app's own waits
  // and errors, not a blank page; and drawn again with the firm's once they come, which whatever is drawn from then on shows.
  const named = api.get<components['schemas']['ServerInfo']>('/v1/server').then((info) => setLabels(info.labels))
  let timer: ReturnType<typeof setTimeout> | undefined
  const late = new Promise<boolean>((resolve) => (timer = setTimeout(() => resolve(false), SERVER_INFO_WAIT_MS)))
  const inTime = await Promise.race([named.then(() => true, () => true), late])
  clearTimeout(timer)
  draw()
  if (!inTime) void named.then(draw, () => {})
  return () => {
    gone = true
    drawn.unmount()
  }
}

const root = document.getElementById('root')
if (root) void start(root)
