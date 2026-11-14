// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
import type { components } from '@healdan/api'
import { toast } from '@healdan/ui'
import { fieldErrors, messageFor } from '../frame/errors'
import { keys, useMe } from '../queries'
import { useApi } from '../services'
import { invalidateDocumentLists } from './refresh'
import { checkIn, checkOut, holdState, release, type HoldState } from './checkout'

type DocumentView = components['schemas']['DocumentView']

export interface Checkout {
  state: HoldState
  /** A check-out, check-in or release of the document is under way, from here or from any other control that offers one */
  busy: boolean
  checkOut: () => Promise<void>
  release: () => Promise<void>
  checkIn: (file: File) => Promise<void>
}

/** What marks a document's check-out, check-in and release while it runs, so every control that offers one waits for it */
const holdKey = (docNumber: number | undefined) => ['hold', docNumber] as const

/**
 * Check Out, Check In and Release for a document, which follow its `hold`; none is offered without a document. What the
 * server says is shown from the catalogue, and the document and every list that shows its hold are asked for again,
 * whether it worked or not, as a refusal usually means the hold isn't what the page showed. While one runs, each control
 * that offers them is busy: the toolbar's, the details' and a right-click menu's alike.
 */
export function useCheckout(document: DocumentView | undefined): Checkout {
  const api = useApi()
  const queryClient = useQueryClient()
  const me = useMe()
  const busy = useIsMutating({ mutationKey: holdKey(document?.docNumber) }) > 0 && document !== undefined
  // The work is all in the mutation, which goes on when the control that started it goes, as a menu's item does
  const mutation = useMutation({
    mutationKey: holdKey(document?.docNumber),
    mutationFn: async ({ docNumber, action }: { docNumber: number; action: () => Promise<DocumentView> }) => {
      try {
        const updated = await action()
        queryClient.setQueryData(keys.document(docNumber), updated)
      } catch (error) {
        // A refusal that names a field (a file of another kind than the document) says what is wrong with it
        const [first] = fieldErrors(error).values()
        toast(first ?? messageFor(error))
      } finally {
        // The folder's list, search results and Recent show the hold too. They're asked for again without waiting, so the
        // controls aren't busy while every open list and tree answers: the document's own answer is set above.
        void invalidateDocumentLists(queryClient)
      }
    },
  })

  const run = async (action: (doc: DocumentView) => Promise<DocumentView>) => {
    if (document) await mutation.mutateAsync({ docNumber: document.docNumber, action: () => action(document) })
  }

  return {
    state: holdState(document?.hold ?? null, me.data?.id),
    busy,
    checkOut: () => run((doc) => checkOut(api, doc.docNumber)),
    release: () => run((doc) => release(api, doc.docNumber)),
    checkIn: (file) => run((doc) => checkIn(api, doc, file)),
  }
}
