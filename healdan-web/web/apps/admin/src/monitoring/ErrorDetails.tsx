// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Fragment, useId, useState, type Ref } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Icon, toast } from '@healdan/ui'
import { routes } from '../addresses'
import { Refusal } from '../forms/Refusal'
import { t } from '../strings'
import { errorChanged, errorKeys, isGone, markError } from './errorQueries'
import { documentWords, errorDetailsText, errorFacts, logSearchWords } from './errorText'
import styles from './Monitoring.module.css'

type A = adminComponents['schemas']

/**
 * Copy Details: the details as plain text, through the browser's clipboard where it has one (the app is served only from a
 * secure address or the machine itself). A copy it refuses says so beside the button, and the details stay to select.
 */
function CopyDetails({ detail }: { detail: A['ErrorDetail'] }) {
  const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard
  const [refused, setRefused] = useState(false)
  if (clipboard === undefined) return null

  async function copy(to: Clipboard) {
    setRefused(false)
    try {
      await to.writeText(errorDetailsText(detail))
      toast(t('admin.errors.copied'))
    } catch {
      setRefused(true)
    }
  }

  return (
    <>
      <Button onPress={() => void copy(clipboard)}>
        <span className={styles.row}>
          <Icon name="copy" size={14} />
          {t('admin.errors.copy')}
        </span>
      </Button>
      {refused && <Refusal>{t('admin.errors.copyFailed')}</Refusal>}
    </>
  )
}

/** What the server logged: a fault's message or stack, its own lines kept, as text in a box named by its heading */
function LoggedText({ title, text }: { title: string; text: string }) {
  const titleId = useId()
  return (
    <div>
      <h3 id={titleId} className={styles.partTitle}>
        {title}
      </h3>
      <pre role="group" aria-labelledby={titleId} className={styles.text}>
        {text}
      </pre>
    </div>
  )
}

/** The fault, once read: its facts, the documents it names, its message and stack, and what can be done with it */
function Fault({ detail }: { detail: A['ErrorDetail'] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  // Mark Resolved and Reopen are one button, whose words follow the fault, so the focus stays on it as it changes. A fault
  // the server no longer has (`404`) is asked again, so the details say it has gone rather than go on showing it.
  async function mark() {
    setBusy(true)
    setFailure(undefined)
    try {
      await errorChanged(queryClient, await markError(api, detail.id, !detail.resolved))
    } catch (error) {
      if (isGone(error)) void queryClient.invalidateQueries({ queryKey: errorKeys.error(detail.id), exact: true })
      else setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  const search = detail.log === null ? undefined : { log: detail.log, q: logSearchWords(detail.message) || undefined }
  return (
    <>
      <dl className={styles.facts}>
        {errorFacts(detail).map((fact) => (
          <Fragment key={fact.name}>
            <dt>{fact.name}</dt>
            <dd>{fact.value}</dd>
          </Fragment>
        ))}
        <dt>{t('admin.errors.documents')}</dt>
        <dd>
          {detail.documents.length === 0 ? (
            t('admin.errors.noDocuments')
          ) : (
            <ul className={styles.documents} aria-label={t('admin.errors.documents')}>
              {detail.documents.map((document, index) => (
                <li key={`${document.id}-${index}`}>{documentWords(document)}</li>
              ))}
            </ul>
          )}
        </dd>
      </dl>
      <LoggedText title={t('admin.errors.message')} text={detail.message} />
      {detail.stack === null ? <p className={styles.note}>{t('admin.errors.noStack')}</p> : <LoggedText title={t('admin.errors.stack')} text={detail.stack} />}
      <div className={styles.buttons}>
        <Button isPending={busy} onPress={() => void mark()}>
          <span className={styles.row}>
            <Icon name={detail.resolved ? 'refresh' : 'check'} size={14} />
            {t(detail.resolved ? 'admin.errors.reopen' : 'admin.errors.resolve')}
          </span>
        </Button>
        {search !== undefined && (
          <Link to={routes.serverLog} search={search} className={styles.linkButton}>
            <Icon name="document" size={14} />
            {t('admin.errors.showInLog')}
          </Link>
        )}
        <CopyDetails detail={detail} />
      </div>
      <Refusal failure={failure} />
    </>
  )
}

export interface ErrorDetailsProps {
  /** The fault's read, which the page holds, so its note that a re-read failed is the list's too */
  query: UseQueryResult<A['ErrorDetail']>
  /** Close Details: the fault out of the address */
  onClose: () => void
  /** The region, for the focus when a row is opened by the keyboard */
  regionRef?: Ref<HTMLElement>
}

/**
 * A fault's details, in a region of their own below the list (spec 8.8: the list's rows keep their height): its facts,
 * the documents it names, its latest message and stack, Mark Resolved or Reopen, Show in Server Log and Copy Details.
 * The region scrolls inside, so it's a Tab stop of its own, named by its heading. Everything from the server is drawn as
 * text: a fault's message, stack and documents come from what was logged. While a re-read fails, the last answer stays
 * (the page says so); a fault the server no longer has says so, even when it was read before.
 */
export function ErrorDetails({ query, onClose, regionRef }: ErrorDetailsProps) {
  const headingId = useId()
  let body
  // Gone first: a re-read's 404 means the fault read before is no more, not that it couldn't refresh
  if (isGone(query.error)) body = <p className={styles.note}>{t('admin.errors.gone')}</p>
  // A fault of its own for each, so what one's button was doing doesn't stay on the next one's
  else if (query.data !== undefined) body = <Fault key={query.data.id} detail={query.data} />
  else if (query.isError) body = <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
  else body = <p className={styles.note}>{t('common.loading')}</p>

  return (
    <section ref={regionRef} className={styles.details} aria-labelledby={headingId} tabIndex={0}>
      <div className={styles.detailsHead}>
        <h2 id={headingId} className={styles.detailsTitle}>
          {t('admin.errors.details')}
        </h2>
        <span className={styles.close}>
          <Button variant="quiet" aria-label={t('admin.errors.close')} onPress={onClose}>
            <Icon name="close" size={14} />
          </Button>
        </span>
      </div>
      {body}
    </section>
  )
}
