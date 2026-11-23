// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useIsMutating, useMutation, useQuery } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { ApiError, type adminComponents } from '@healdan/api'
import { Button, Dialog, plural, t, toast, toastWithLink } from '@healdan/ui'
import { ADMIN_SEARCH_INDEX } from '../addresses'
import { adminPost } from '../adminApi'
import { messageFor } from '../frame/errors'
import { useMe } from '../queries'
import { useApi } from '../services'
import type { DocumentAction } from './useDocumentActions'
import styles from './actions.module.css'

/** What a reindex covers, as the admin contract takes it: documents by doc #, a folder by its id, a matter by the matter's own id */
export type ReindexScope = adminComponents['schemas']['ReindexScope']

/** Every start's mutation key, which the menus' items watch: they're greyed out while one is on its way */
const START_KEY = ['reindex', 'start'] as const

interface Reindexing {
  /** Asks before reindexing what the scope covers, with what the server says that is */
  ask: (scope: ReindexScope) => void
}

const Context = createContext<Reindexing | null>(null)

function useReindexing(): Reindexing {
  const reindexing = useContext(Context)
  if (!reindexing) throw new Error('useReindex needs a ReindexProvider')
  return reindexing
}

/**
 * Reindex, for an administrator (admin spec 13): the item a right-click menu offers for the scope, or none for anyone else
 * and for a scope of nothing. `useMe` only decides what's offered: the server refuses anyone else. Only the menus offer
 * it, as Favorites and Open in New Tab are only there (favoriteAction, newTabAction).
 */
export function useReindex(scope: ReindexScope | undefined): DocumentAction | undefined {
  const admin = useMe().data?.admin === true
  const reindexing = useReindexing()
  const starting = useIsMutating({ mutationKey: START_KEY }) > 0
  if (!admin || !scope) return undefined
  return { id: 'reindex', label: t('reindex.menu'), isDisabled: false, isPending: starting, perform: () => reindexing.ask(scope) }
}

/**
 * What to say of a refused reindex or count: a `409 refused`'s sentence, which is the rule's own (there's nothing to
 * reindex, it's being reindexed already); a selection over the server's limit; anything else as the catalogue says it
 */
export function reindexRefusal(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409 && error.code === 'refused' && error.detail) return error.detail
    const fields = (error.body as { fields?: { field?: unknown; code?: unknown }[] } | null)?.fields
    if (error.code === 'validation' && Array.isArray(fields) && fields.some((one) => one?.code === 'too-many')) return t('reindex.tooMany')
  }
  return messageFor(error)
}

/**
 * Every reindex the User app starts (admin spec 13, P6-R20): the question, and the start, which runs on once the question
 * has gone, so the menu that asked can close and the person stays where they were. A start is said in a toast whose
 * View Progress opens the Admin app's Search Index in a new tab, and a refusal in a toast of its own; a 401 is the app's
 * signed-out state, which the client reports.
 */
export function ReindexProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  // A number of its own for each question, so asking again counts again
  const [asking, setAsking] = useState<{ scope: ReindexScope; asked: number } | undefined>()
  const asked = useRef(0)
  const start = useMutation({
    mutationKey: START_KEY,
    mutationFn: (scope: ReindexScope) => adminPost<'/v1/admin/reindex', 201>(api, '/v1/admin/reindex', scope),
    onSuccess: (job) => toastWithLink(plural('reindex.started', job.total), ADMIN_SEARCH_INDEX),
    onError: (error) => {
      if (!(error instanceof ApiError && error.status === 401)) toast(reindexRefusal(error))
    },
  })
  const value: Reindexing = { ask: (scope) => setAsking({ scope, asked: ++asked.current }) }
  return (
    <Context.Provider value={value}>
      {children}
      {asking && (
        <ReindexQuestion
          key={asking.asked}
          scope={asking.scope}
          asked={asking.asked}
          onCancel={() => setAsking(undefined)}
          onConfirm={() => {
            const scope = asking.scope
            setAsking(undefined)
            start.mutate(scope)
          }}
        />
      )}
    </Context.Provider>
  )
}

interface ReindexQuestionProps {
  scope: ReindexScope
  asked: number
  onConfirm: () => void
  onCancel: () => void
}

/**
 * "Reindex": what the server says the scope is ("Matter 1001.1 Charter Dispute") and how many documents it would try, from
 * the preview, which is asked as the question opens. While it's on its way the question says so, and its button, which
 * has the focus, is pending; a refusal (nothing here to reindex, already being reindexed, or the preview's own) shows in
 * place of the count, with no button but Cancel, which then takes the focus. Enter reindexes, Esc cancels.
 */
function ReindexQuestion({ scope, asked, onConfirm, onCancel }: ReindexQuestionProps) {
  const api = useApi()
  const textId = useId()
  const cancel = useRef<HTMLButtonElement>(null)
  const preview = useQuery({
    queryKey: ['reindex', 'preview', asked, scope],
    queryFn: () => adminPost(api, '/v1/admin/reindex/preview', scope),
    // One count for the question: none asked again on a return to the window, and none kept after it
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  })
  const answer = preview.data
  const offered = answer !== undefined && answer.refusal === null && answer.count > 0
  const refused = preview.isError || (answer !== undefined && !offered)
  // The button that had the focus is gone
  useEffect(() => {
    if (refused) cancel.current?.focus()
  }, [refused])

  let text: ReactNode
  if (preview.isError) text = <p>{reindexRefusal(preview.error)}</p>
  else if (!answer) text = <p>{t('reindex.counting')}</p>
  else if (!offered) text = <p>{answer.refusal ?? reindexRefusal(undefined)}</p>
  else
    text = (
      <>
        <p className={styles.reindexName}>{answer.name}</p>
        <p>{plural('reindex.count', answer.count)}</p>
      </>
    )

  return (
    <Dialog title={t('reindex.title')} aria-describedby={textId} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <>
          {/* Said as it opens, and again once the count comes */}
          <div id={textId} aria-live="polite" className={styles.reindexText}>
            {text}
          </div>
          <div className={styles.releaseActions}>
            {!refused && (
              <Button
                variant="default"
                autoFocus
                isPending={!offered}
                onPress={() => {
                  close()
                  onConfirm()
                }}
              >
                {offered ? plural('reindex.confirm', answer.count) : t('reindex.menu')}
              </Button>
            )}
            <Button ref={cancel} onPress={close}>
              {t('common.cancel')}
            </Button>
          </div>
        </>
      )}
    </Dialog>
  )
}
