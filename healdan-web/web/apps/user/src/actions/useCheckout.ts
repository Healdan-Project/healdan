// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { components } from '@healdan/api'
import { toast } from '@healdan/ui'
import { messageFor } from '../frame/errors'
import { keys, useMe } from '../queries'
import { useApi } from '../services'
import { checkIn, checkOut, holdState, release, type HoldState } from './checkout'

type DocumentView = components['schemas']['DocumentView']

export interface Checkout {
  state: HoldState
  busy: boolean
  checkOut: () => Promise<void>
  release: () => Promise<void>
  checkIn: (file: File) => Promise<void>
}

/**
 * Check Out, Check In and Release for a document, which follow its `hold`. What the server says is shown from the
 * catalogue, and the document and every list that shows its hold are asked for again, whether it worked or not, as a
 * refusal usually means the hold isn't what the page showed.
 */
export function useCheckout(document: DocumentView): Checkout {
  const api = useApi()
  const queryClient = useQueryClient()
  const me = useMe()
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<DocumentView>): Promise<void> {
    setBusy(true)
    try {
      const updated = await action()
      queryClient.setQueryData(keys.document(document.docNumber), updated)
    } catch (error) {
      toast(messageFor(error))
    } finally {
      setBusy(false)
      // The folder's list, search results and Recent show the hold too
      await Promise.all(['documents', 'folders', 'search'].map((root) => queryClient.invalidateQueries({ queryKey: [root] })))
    }
  }

  return {
    state: holdState(document.hold, me.data?.id),
    busy,
    checkOut: () => run(() => checkOut(api, document.docNumber)),
    release: () => run(() => release(api, document.docNumber)),
    checkIn: (file) => run(() => checkIn(api, document, file)),
  }
}
