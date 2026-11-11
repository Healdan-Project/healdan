// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, apiPath, type components } from '@healdan/api'
import { Button, formatDateTime, formatSize, Icon, t, toast } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { messageFor } from '../frame/errors'
import { fileIcon } from '../fileIcon'
import { keys } from '../queries'
import { useApi } from '../services'
import { EmailBodyFrame } from './EmailBodyFrame'
import { NoPreview } from './NoPreview'
import { PdfView } from './PdfView'
import { PictureView } from './PictureView'
import styles from './email.module.css'
import type { PreviewProps, PreviewSource } from './types'

type Attachment = components['schemas']['MessageView']['attachments'][number]

/** What an attachment can be shown as in place (R56): a PDF or a picture; the server says no for anything else */
function shownAs(attachment: Attachment): 'pdf' | 'picture' | undefined {
  if (attachment.type === 'application/pdf') return 'pdf'
  return /^image\/[a-z0-9][a-z0-9.+-]*$/.test(attachment.type) ? 'picture' : undefined
}

function Detail({ label, value }: { label: string; value: string | null }) {
  if (!value) return null
  return (
    <div className={styles.detail}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

/**
 * One attachment: its name and size, which previews a PDF or a picture in place and downloads anything else, and Save to
 * Matter, or the # it was filed as.
 */
function Chip({ attachment, docNumber, onPreview }: { attachment: Attachment; docNumber: number; onPreview: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [saving, setSaving] = useState(false)
  // The size is beside the control, not in it, so the control's name (an action and the file's name) holds all its visible text
  const label = (
    <>
      <Icon name={fileIcon(attachment.name)} size={18} />
      <span className={styles.chipName}>{attachment.name}</span>
    </>
  )

  async function saveToMatter(): Promise<void> {
    setSaving(true)
    try {
      await api.send('POST', apiPath`/v1/documents/${docNumber}/message/attachments/${attachment.index}/file`)
      // The chip's # comes with the message, and the new document with its folder
      await queryClient.invalidateQueries({ queryKey: keys.document(docNumber) })
      await queryClient.invalidateQueries({ queryKey: keys.recentDocuments })
    } catch (error) {
      // Filed already is the matter's rule speaking: the message is read again, which shows the copy if it is this email's
      if (error instanceof ApiError && error.code === 'already-filed') await queryClient.invalidateQueries({ queryKey: keys.document(docNumber) })
      toast(`${t('email.saveFailed', { name: attachment.name })} ${messageFor(error)}`)
    } finally {
      setSaving(false)
    }
  }

  return (
    <li className={styles.chip}>
      {shownAs(attachment) ? (
        <button type="button" className={styles.chipMain} aria-label={t('email.previewAttachment', { name: attachment.name })} onClick={onPreview}>
          {label}
        </button>
      ) : (
        // A plain link to the attachment: the web session carries it, and the server records the download
        <a className={styles.chipMain} href={api.url(apiPath`/v1/documents/${docNumber}/message/parts/${attachment.index}`)} download={attachment.name} aria-label={t('email.downloadAttachment', { name: attachment.name })}>
          {label}
        </a>
      )}
      <span className={styles.chipSize}>{formatSize(attachment.size)}</span>
      {attachment.docNumber !== null ? (
        <span className={styles.saved}>{t('email.saved', { n: attachment.docNumber })}</span>
      ) : (
        <Button variant="quiet" aria-label={t('email.saveAttachment', { name: attachment.name })} isDisabled={saving} onPress={() => void saveToMatter()}>
          {t('email.saveToMatter')}
        </Button>
      )}
    </li>
  )
}

/**
 * A filed email as a message (spec section 6.6): the subject, who sent it and to whom, when, its attachments as chips,
 * then the body. The body is the server's cleaned page in a sandboxed frame, so no script runs and the sender's pictures
 * stay blocked until Show Pictures, for this viewing only. A PDF or a picture among the attachments shows in place of the
 * body, with Back to Message. Give it a `key` of the document, so a new one starts without pictures.
 */
export function EmailView({ document, showActions }: PreviewProps) {
  const api = useApi()
  const message = useQuery({
    queryKey: [...keys.document(document.docNumber), 'message', document.fileVersion],
    queryFn: ({ signal }) => api.get<components['schemas']['MessageView']>(apiPath`/v1/documents/${document.docNumber}/message`, undefined, { signal }),
    refetchOnWindowFocus: false,
    gcTime: 30_000,
  })
  const [pictures, setPictures] = useState(false)
  const [viewing, setViewing] = useState<Attachment>()

  if (message.isError) {
    const refused = message.error instanceof ApiError && (message.error.code === 'not-an-email' || message.error.status === 403 || message.error.status === 404)
    return refused ? <NoPreview document={document} showActions={showActions} /> : <ErrorPanel error={message.error} onRetry={() => void message.refetch()} />
  }
  if (!message.data) return <p role="status" className={styles.message}>{t('email.loading')}</p>
  const { subject, from, to, cc, sent, received, attachments } = message.data

  const body = api.url(apiPath`/v1/documents/${document.docNumber}/message/body`, pictures ? { pictures: 'true' } : undefined)
  const source: PreviewSource | undefined = viewing && {
    path: apiPath`/v1/documents/${document.docNumber}/message/parts/${viewing.index}`,
    query: { inline: 'true' },
    name: viewing.name,
  }

  return (
    <div className={styles.view}>
      <header className={styles.header}>
        <h2 className={styles.subject}>{subject || t('email.noSubject')}</h2>
        <div role="group" aria-label={t('email.details')}>
          <dl className={styles.details}>
            <Detail label={t('email.from')} value={from} />
            <Detail label={t('email.to')} value={to} />
            <Detail label={t('email.cc')} value={cc} />
            <Detail label={t('email.sent')} value={sent ? formatDateTime(sent) : null} />
            <Detail label={t('email.received')} value={received ? formatDateTime(received) : null} />
          </dl>
        </div>
        {attachments.length > 0 && (
          <ul className={styles.chips} aria-label={t('email.attachments')}>
            {attachments.map((attachment) => (
              <Chip key={attachment.index} attachment={attachment} docNumber={document.docNumber} onPreview={() => setViewing(attachment)} />
            ))}
          </ul>
        )}
      </header>
      {viewing && source ? (
        <section className={styles.attachment} aria-label={t('email.viewing', { name: viewing.name })}>
          <div className={styles.back}>
            <Button variant="quiet" onPress={() => setViewing(undefined)}>
              <Icon name="chevronLeft" size={18} />
              {t('email.backToMessage')}
            </Button>
          </div>
          {shownAs(viewing) === 'pdf' ? (
            <PdfView key={source.path} document={document} source={source} showActions={false} />
          ) : (
            <PictureView key={source.path} document={document} source={source} showActions={false} />
          )}
        </section>
      ) : (
        <>
          <div className={styles.pictures}>
            {pictures ? (
              <span role="status">{t('email.picturesShown')}</span>
            ) : (
              <>
                <span>{t('email.picturesBlocked')}</span>
                <Button variant="quiet" onPress={() => setPictures(true)}>
                  {t('email.showPictures')}
                </Button>
              </>
            )}
          </div>
          <EmailBodyFrame src={body} />
        </>
      )}
    </div>
  )
}
