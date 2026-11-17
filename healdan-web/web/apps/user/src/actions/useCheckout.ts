// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
import type { components } from '@healdan/api'
import { toast } from '@healdan/ui'
import { fieldErrors, messageFor } from '../frame/errors'
import { keys, useMe } from '../queries'
import { useApi } from '../services'
import { invalidateDocumentLists } from './refresh'
import { checkIn, checkOut, holdKey, holdState, RELEASE_KEY, type HoldState, type ReleaseTarget } from './checkout'

type DocumentView = components['schemas']['DocumentView']

export interface Checkout {
  state: HoldState
  /** A check-out, check-in or release of the document is under way, from here or from any other control that offers one */
  busy: boolean
  checkOut: () => Promise<void>
  checkIn: (file: File) => Promise<void>
}

/**
 * Whether a check-out, check-in or release of any of the documents is under way: one of its own (holdKey), or a release
 * of several that includes it (RELEASE_KEY, whose variables are the documents it releases). Nothing with none.
 */
export function useHoldsBusy(docNumbers: readonly number[]): boolean {
  const running = useIsMutating({
    mutationKey: ['hold'],
    predicate: (mutation) => {
      const which = mutation.options.mutationKey?.[1]
      const touched = which === RELEASE_KEY[1] ? ((mutation.state.variables as readonly ReleaseTarget[] | undefined) ?? []).map((one) => one.docNumber) : [which]
      return touched.some((docNumber) => docNumbers.includes(docNumber as number))
    },
  })
  return running > 0
}

/**
 * Check Out and Check In for a document, which follow its `hold`; none is offered without a document. Release is the
 * ReleaseProvider's, which warns first when another of the person's computers holds the check-out. What the server says
 * is shown from the catalogue, and the document and every list that shows its hold are asked for again, whether it worked
 * or not, as a refusal usually means the hold isn't what the page showed. While one runs, or a release of the document,
 * each control that offers them is busy: the toolbar's, the details' and a right-click menu's alike.
 */
export function useCheckout(document: DocumentView | undefined): Checkout {
  const api = useApi()
  const queryClient = useQueryClient()
  const me = useMe()
  const busy = useHoldsBusy(document ? [document.docNumber] : [])
  // The work is all in the mutation, which goes on when the control that started it goes, as a menu's item does
  const mutation = useMutation({
    mutationKey: holdKey(document?.docNumber),
    mutationFn: async ({ docNumber, action, newFile }: { docNumber: number; action: () => Promise<DocumentView>; newFile: boolean }) => {
      let saved = false
      try {
        const updated = await action()
        queryClient.setQueryData(keys.document(docNumber), updated)
        saved = newFile
      } catch (error) {
        // A refusal that names a field (a file of another kind than the document) says what is wrong with it
        const [first] = fieldErrors(error).values()
        toast(first ?? messageFor(error))
      } finally {
        // The folder's list, search results and Recent show the hold too. They're asked for again without waiting, so the
        // controls aren't busy while every open list and tree answers: the document's own answer is set above. A check-in's
        // new file can change the name and size that pair the document with an email's attachment, so it's named.
        void invalidateDocumentLists(queryClient, saved ? [docNumber] : [])
      }
    },
  })

  const run = async (action: (doc: DocumentView) => Promise<DocumentView>, newFile = false) => {
    if (document) await mutation.mutateAsync({ docNumber: document.docNumber, action: () => action(document), newFile })
  }

  return {
    state: holdState(document?.hold ?? null, me.data?.id),
    busy,
    checkOut: () => run((doc) => checkOut(api, doc.docNumber)),
    checkIn: (file) => run((doc) => checkIn(api, doc, file), true),
  }
}
