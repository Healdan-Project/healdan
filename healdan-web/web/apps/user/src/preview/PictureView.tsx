// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, apiPath } from '@healdan/api'
import { t } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { useApi } from '../services'
import { NoPreview } from './NoPreview'
import styles from './preview.module.css'
import type { PreviewProps } from './types'

/** The picture's bytes as a data: address, which needs no revoking and which the page's policy allows for images */
async function asDataUrl(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let binary = ''
  // In slices, as a spread of megabytes would overflow the call stack
  for (let start = 0; start < bytes.length; start += 0x8000) binary += String.fromCharCode(...bytes.subarray(start, start + 0x8000))
  return `data:${blob.type || 'application/octet-stream'};base64,${btoa(binary)}`
}

/**
 * A picture previews as itself, from the document's content (spec section 6.4). It comes by the API, not as the image's
 * own address, so a failure is an answer this app reads (a storage outage has its banner). A picture the browser can't
 * draw, such as a TIFF or a HEIC in Chrome, says there is no preview.
 */
export function PictureView({ document, version, showActions }: PreviewProps) {
  const api = useApi()
  const content = useQuery({
    queryKey: ['documents', document.docNumber, 'content', version ?? document.fileVersion],
    queryFn: async ({ signal }) => asDataUrl(await (await api.getResponse(apiPath`/v1/documents/${document.docNumber}/content`, version ? { version } : undefined, { signal })).blob()),
    refetchOnWindowFocus: false,
    staleTime: Infinity,
    gcTime: 30_000,
  })
  const source = content.data
  const [broken, setBroken] = useState(false)

  if (content.isError) {
    const refused = content.error instanceof ApiError && (content.error.status === 403 || content.error.status === 404)
    return refused ? <NoPreview document={document} showActions={showActions} /> : <ErrorPanel error={content.error} onRetry={() => void content.refetch()} />
  }
  if (broken) return <NoPreview document={document} showActions={showActions} />
  if (!source) return <p role="status" className={styles.message}>{t('preview.loading')}</p>
  return (
    <div className={styles.scroller}>
      <img src={source} alt={t('preview.picture', { name: document.name })} className={styles.picture} onError={() => setBroken(true)} />
    </div>
  )
}
