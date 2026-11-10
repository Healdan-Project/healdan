// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createApi, type components } from '@healdan/api'
import { setLabels } from '@healdan/ui'
import { App } from './App'
import { createQueryClient } from './queryClient'
import { createAppRouter } from './router'
import { createSignedOut } from './signedOut'

async function start(root: HTMLElement) {
  const signedOut = createSignedOut()
  const api = createApi({ onSignedOut: signedOut.mark })
  // The firm's word for a matter must be set before the first render. If the server can't say, the default words do.
  try {
    setLabels((await api.get<components['schemas']['ServerInfo']>('/v1/server')).labels)
  } catch {
    // Matter and Matters stay.
  }
  const queryClient = createQueryClient({ onSignedOut: signedOut.mark })
  createRoot(root).render(
    <StrictMode>
      <App api={api} queryClient={queryClient} signedOut={signedOut} router={createAppRouter()} />
    </StrictMode>,
  )
}

const root = document.getElementById('root')
if (root) void start(root)
