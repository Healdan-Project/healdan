// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createApi } from '@healdan/api'
import { createQueryClient, createSignedOut, InsecureAddress } from '@healdan/shell'
import { App } from './App'
import { createAdminRouter } from './router'

/**
 * Draws the app in `root`, and gives back what takes it away again. At an address that isn't secure (plain http://, not
 * the machine itself) the browser sends no Sec-Fetch-Site, so the web session's route refuses every call: the app says
 * so on the User app's page for it, before it asks the server anything (R91).
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
  const router = createAdminRouter()
  drawn.render(
    <StrictMode>
      <App api={api} queryClient={queryClient} signedOut={signedOut} router={router} />
    </StrictMode>,
  )
  return () => drawn.unmount()
}

const root = document.getElementById('root')
if (root) void start(root)
