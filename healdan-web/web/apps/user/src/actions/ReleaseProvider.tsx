// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useState, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { plural, t, toast } from '@healdan/ui'
import { SignedInDevices } from '../devices/SignedInDevices'
import { Refusals, type RefusalsProps } from '../organising/Refusals'
import { focusLater, rowAfter, rowDrawn } from '../organising/rows'
import { keys, useMe } from '../queries'
import { useApi } from '../services'
import { eachDocument, refusalLines } from './batch'
import { holdState, release, RELEASE_KEY, type ReleaseTarget } from './checkout'
import { ConfirmRelease } from './ConfirmRelease'
import { invalidateDocumentLists } from './refresh'

type DocumentView = components['schemas']['DocumentView']

export interface Releasing {
  /**
   * Releases the person's own check-outs: at once when the web page holds them all, and after the warning when another of
   * their computers holds any (spec 5.3)
   */
  askRelease: (documents: DocumentView[]) => void
}

const Context = createContext<Releasing | null>(null)

export function useReleasing(): Releasing {
  const releasing = useContext(Context)
  if (!releasing) throw new Error('useReleasing needs a ReleaseProvider')
  return releasing
}

/**
 * Every release of a check-out, from the toolbar, a row's menu and Check-Out Info alike (spec 5.3, 5.4): the warning, the
 * Signed-in Devices it opens over itself, and the dialog that says what was refused. Each document is its own call, so a
 * refusal leaves the others released; one another of the person's computers holds is sent with `elsewhere`, which the
 * warning stands for.
 */
export function ReleaseProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const me = useMe()
  const [asking, setAsking] = useState<ReleaseTarget[] | undefined>()
  const [showDevices, setShowDevices] = useState(false)
  const [refused, setRefused] = useState<Pick<RefusalsProps, 'title' | 'lines'> | undefined>()

  // Its variables are the documents it releases, which every control that offers a change to one of them watches
  // (useHoldsBusy)
  const mutation = useMutation({
    mutationKey: RELEASE_KEY,
    mutationFn: async (targets: readonly ReleaseTarget[]) => {
      // Where the focus goes if the rows leave the list, as they do from Checked Out to Me: the dialog that had it is gone.
      // Only from a list that draws them: a release from Check-Out Info whose row isn't drawn leaves the focus alone.
      const rows = targets.map((one) => `d${one.docNumber}`)
      const fromList = rows.some(rowDrawn)
      const next = rowAfter(targets.map((one) => one.docNumber))
      const outcome = await eachDocument(targets, async (one) => {
        const updated = await release(api, one.docNumber, { elsewhere: one.elsewhere })
        queryClient.setQueryData(keys.document(one.docNumber), updated)
      })
      // Every list that shows a hold, whether it worked or not: a refusal usually means the hold isn't what the page showed.
      // Not waited for, so the controls aren't busy while every open list and tree answers, as after a check-out (useCheckout)
      const lists = invalidateDocumentLists(queryClient, [])
      if (outcome.done.length > 0) toast(plural('released', outcome.done.length))
      if (outcome.refused.length > 0) setRefused({ title: t('release.refusedTitle'), lines: refusalLines(outcome.refused, me.data?.id) })
      // Where they left the list, the focus goes to the row after them, as after a delete, once the lists have answered;
      // where they stay, it stays put
      if (outcome.done.length > 0 && fromList) void lists.then(() => focusLater(next, outcome.done.map((one) => `d${one.docNumber}`)))
    },
  })

  const value: Releasing = {
    askRelease: (documents) => {
      if (documents.length === 0) return
      // Decided once, here: the warning lists what this says another computer holds, under that computer, and only that
      // is sent with elsewhere, naming the computer, so a check-out that has moved on since stays where it is
      const targets = documents.map((document): ReleaseTarget => {
        const state = holdState(document.hold, me.data?.id)
        return { ...document, elsewhere: state.kind === 'yourDevice' ? state.hold.device?.id : undefined }
      })
      if (targets.some((one) => one.elsewhere)) setAsking(targets)
      else mutation.mutate(targets)
    },
  }

  return (
    <Context.Provider value={value}>
      {children}
      {asking && (
        <ConfirmRelease
          documents={asking}
          onCancel={() => setAsking(undefined)}
          onConfirm={() => {
            const targets = asking
            setAsking(undefined)
            mutation.mutate(targets)
          }}
          onShowDevices={() => setShowDevices(true)}
        />
      )}
      {/* Over the warning, which is still there once it's closed */}
      {showDevices && <SignedInDevices onClose={() => setShowDevices(false)} />}
      {refused && <Refusals title={refused.title} lines={refused.lines} onClose={() => setRefused(undefined)} />}
    </Context.Provider>
  )
}
