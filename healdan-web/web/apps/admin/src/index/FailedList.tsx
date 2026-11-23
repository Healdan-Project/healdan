// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState } from 'react'
import { ApiError, type adminComponents } from '@healdan/api'
import { ErrorPanel, isStorageUnreachable, useApi } from '@healdan/shell'
import { Button, formatDateTime, toast, VisuallyHidden } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { Refusal } from '../forms/Refusal'
import { useKeptFocus } from '../monitoring/useKeptFocus'
import { StaleNote } from '../people/StaleNote'
import { t } from '../strings'
import { countsStale, indexChanged, isOutOfDate, retryIndexing, skipIndexing, useIndexFailures } from './queries'
import { docNumberHeader, documentWords, matterWords } from './text'
import { ShowMore } from './ShowMore'
import styles from './SearchIndex.module.css'

type A = adminComponents['schemas']

/** The table's columns: Doc #, Title, Matter, Reason, When and the row's buttons */
const COLUMNS = 6

/**
 * What a row says under it: how Try Again went, or what was refused of a button pressed in it, with the row as it was when
 * the button was pressed (`against`, rowState's)
 */
interface Said {
  outcome?: A['IndexRetry']
  failure?: unknown
  against: string
}

/** A row's reason and when, which a later run of the Indexer changes */
function rowState(failure: A['IndexFailure']): string {
  return JSON.stringify([failure.when, failure.reason])
}

/**
 * Whether what a row says still holds: while the row is as it was when the button was pressed, and, for Try Again's
 * "still couldn't be indexed", while the row gives the reason it answered with. Once a later run of the Indexer gives the
 * row another reason or time, the line goes.
 */
function stillSaid(said: Said | undefined, failure: A['IndexFailure']): Said | undefined {
  if (said === undefined) return undefined
  if (rowState(failure) === said.against) return said
  return said.outcome?.outcome === 'failed' && said.outcome.reason === failure.reason ? said : undefined
}

/**
 * A refusal, said as the app says them (forms/Refusal); the storage that keeps the document's file can't be reached is said
 * in the server's own sentence, which names the Store and is written to be shown as it is (the contract's Try Again)
 */
function RowRefusal({ failure }: { failure: unknown }) {
  const sentence = isStorageUnreachable(failure) && failure instanceof ApiError && failure.detail.trim() !== '' ? failure.detail : undefined
  return <Refusal failure={failure}>{sentence}</Refusal>
}

interface RowProps {
  failure: A['IndexFailure']
  said: Said | undefined
  busy: boolean
  retry: (failure: A['IndexFailure']) => void
  skip: (failure: A['IndexFailure']) => void
}

function FailedRow({ failure, said: kept, busy, retry, skip }: RowProps) {
  const said = stillSaid(kept, failure)
  const nameId = useId()
  const whyId = useId()
  const { document } = failure
  // A document with no doc # can't be named by Try Again's and Skip's routes: why, which describes both buttons
  const why = document.docNumber === null ? { id: whyId, sentence: t('admin.searchIndex.noDocNumber') } : undefined
  const name = documentWords(document)
  const outcome = said?.outcome
  return (
    <>
      <tr data-doc={document.id}>
        <th scope="row" id={nameId} tabIndex={-1} className={styles.name}>
          {docNumberHeader(document)}
        </th>
        <td className={styles.wraps}>{document.fileName}</td>
        <td>{matterWords(document)}</td>
        <td className={styles.wraps}>{failure.reason ?? t('admin.searchIndex.noReason')}</td>
        <td>{failure.when === null ? '' : <time dateTime={failure.when}>{formatDateTime(failure.when)}</time>}</td>
        <td>
          <div className={styles.actions}>
            <Button aria-label={t('admin.searchIndex.retryName', { name })} isDisabled={why !== undefined} aria-describedby={why?.id} isPending={busy} onPress={() => retry(failure)} data-action="retry">
              {t('admin.searchIndex.retry')}
            </Button>
            <Button aria-label={t('admin.searchIndex.skipName', { name })} isDisabled={why !== undefined || busy} aria-describedby={why?.id} onPress={() => skip(failure)} data-action="skip">
              {t('admin.searchIndex.skip')}
            </Button>
          </div>
        </td>
      </tr>
      {(why !== undefined || outcome !== undefined || said?.failure !== undefined) && (
        <tr className={styles.more}>
          <td colSpan={COLUMNS} headers={nameId}>
            {why !== undefined && (
              <p id={why.id} className={styles.muted}>
                {why.sentence}
              </p>
            )}
            {outcome !== undefined && (
              <p role="status">
                {outcome.outcome === 'indexed'
                  ? t('admin.searchIndex.retried.indexed')
                  : t('admin.searchIndex.retried.failed', { reason: outcome.reason ?? t('admin.searchIndex.noReason') })}
              </p>
            )}
            {said?.failure !== undefined && <RowRefusal failure={said.failure} />}
          </td>
        </tr>
      )}
    </>
  )
}

/**
 * The Couldn't Be Indexed tab (spec 8.9, 9.6): each document that couldn't be indexed, the latest error first, with its
 * doc #, title, matter, reason and when, and Try Again and Skip. As each row holds its own buttons, the list is a plain
 * table, as Background Jobs' is, a page at a time with Show More. Try Again indexes it now and says how it went in its row;
 * Skip asks first, saying it won't be tried again. After either, the list, the counts and the Dashboard are read again; a
 * refusal stays in the row, and when it says the row is out of date only the counts and the Dashboard are read again, so
 * the row stays to say so until the list is next read (by its poll, a return to the window or a later change). When the row goes, the focus goes to the row that comes to its place. A document with no doc #
 * can't be named by those routes, so its buttons are greyed out with the reason. While a new read fails, the rows it had
 * stay, with a note (P4-R15).
 */
export function FailedList({ poll }: { poll: boolean }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const listing = useIndexFailures(poll)
  const about = useRef<HTMLParagraphElement>(null)
  const table = useRef<HTMLTableElement>(null)
  const [said, setSaid] = useState<Record<number, Said>>({})
  // The rows whose Try Again is on its way
  const [busy, setBusy] = useState<ReadonlySet<number>>(new Set())
  const [skipping, setSkipping] = useState<A['IndexFailure']>()
  useKeptFocus(table, about, { attribute: 'doc', toNextRow: true })

  const say = (failure: A['IndexFailure'], next: Omit<Said, 'against'> | undefined) =>
    setSaid((all) => {
      const rest = { ...all }
      if (next === undefined) delete rest[failure.document.id]
      else rest[failure.document.id] = { ...next, against: rowState(failure) }
      return rest
    })

  /**
   * A refusal said in the row; one that says it's out of date has the counts and the Dashboard read again, never the list,
   * which would take the row, and the refusal with it, away at once. One that came with no answer from the server, as when
   * a slow file outlasts a proxy's wait, has everything read again, as the server may have gone on and indexed it: the row
   * goes if it has, and keeps its line if not.
   */
  function refused(failure: A['IndexFailure'], error: unknown) {
    say(failure, { failure: error })
    if (!(error instanceof ApiError)) indexChanged(queryClient)
    else if (isOutOfDate(error)) countsStale(queryClient)
  }

  async function retry(failure: A['IndexFailure']) {
    const docNumber = failure.document.docNumber
    const id = failure.document.id
    if (docNumber === null || busy.has(id)) return
    setBusy((all) => new Set(all).add(id))
    say(failure, undefined)
    try {
      const outcome = await retryIndexing(api, docNumber)
      say(failure, { outcome })
      const name = documentWords(failure.document)
      toast(outcome.outcome === 'indexed' ? t('admin.searchIndex.indexedToast', { name }) : t('admin.searchIndex.stillFailedToast', { name }))
      indexChanged(queryClient)
    } catch (error) {
      refused(failure, error)
    } finally {
      setBusy((all) => {
        const left = new Set(all)
        left.delete(id)
        return left
      })
    }
  }

  // Skip's question closes once it's answered, either way: a refusal is said in the row, as Try Again's is
  async function skip(failure: A['IndexFailure']) {
    const docNumber = failure.document.docNumber
    if (docNumber === null) return
    say(failure, undefined)
    try {
      await skipIndexing(api, docNumber)
      toast(t('admin.searchIndex.skippedToast', { name: documentWords(failure.document) }))
      indexChanged(queryClient)
    } catch (error) {
      refused(failure, error)
    }
  }

  let body
  if (listing.isError && listing.total === undefined) body = <ErrorPanel error={listing.error} onRetry={listing.refetch} />
  else if (listing.items.length === 0) body = <p className={styles.muted}>{listing.isPending ? t('common.loading') : t('admin.searchIndex.failed.none')}</p>
  else {
    body = (
      <>
        <div className={styles.tableBox}>
          <table ref={table} className={styles.table} aria-label={t('admin.searchIndex.tab.failed')}>
            <thead>
              <tr>
                <th scope="col">{t('admin.searchIndex.column.docNumber')}</th>
                <th scope="col">{t('admin.searchIndex.column.title')}</th>
                <th scope="col">{t('admin.searchIndex.column.matter')}</th>
                <th scope="col">{t('admin.searchIndex.column.reason')}</th>
                <th scope="col">{t('admin.searchIndex.column.when')}</th>
                <th scope="col">
                  <VisuallyHidden>{t('admin.searchIndex.column.actions')}</VisuallyHidden>
                </th>
              </tr>
            </thead>
            <tbody>
              {listing.items.map((failure) => (
                <FailedRow
                  key={failure.document.id}
                  failure={failure}
                  said={said[failure.document.id]}
                  busy={busy.has(failure.document.id)}
                  retry={(one) => void retry(one)}
                  skip={setSkipping}
                />
              ))}
            </tbody>
          </table>
        </div>
        <ShowMore listing={listing} fallback={about} />
      </>
    )
  }

  return (
    <div className={styles.tab}>
      <p ref={about} tabIndex={-1} className={styles.about}>
        {t('admin.searchIndex.failed.about')}
      </p>
      <StaleNote failed={listing.isError && listing.total !== undefined} onRetry={listing.refetch} />
      {body}
      {skipping !== undefined && (
        <Confirm
          title={t('admin.searchIndex.skipTitle', { name: documentWords(skipping.document) })}
          confirm={t('admin.searchIndex.skip')}
          run={() => skip(skipping)}
          onClose={() => setSkipping(undefined)}
        >
          <p className={styles.line}>{t('admin.searchIndex.skipLine')}</p>
        </Confirm>
      )}
    </div>
  )
}
