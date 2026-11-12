// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createApi, type components } from '@healdan/api'
import { setLabels } from '@healdan/ui'
import { App } from './App'
import { InsecureAddress } from './frame/InsecureAddress'
import { createQueryClient } from './queryClient'
import { createAppRouter } from './router'
import { createSignedOut } from './signedOut'

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
  // The firm's word for a matter must be set before the first render. If the server can't say, the default words do.
  try {
    setLabels((await api.get<components['schemas']['ServerInfo']>('/v1/server')).labels)
  } catch {
    // Matter and Matters stay.
  }
  const queryClient = createQueryClient()
  drawn.render(
    <StrictMode>
      <App api={api} queryClient={queryClient} signedOut={signedOut} router={createAppRouter()} />
    </StrictMode>,
  )
  return () => drawn.unmount()
}

const root = document.getElementById('root')
if (root) void start(root)
