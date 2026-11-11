// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useCallback, useMemo, useState, useSyncExternalStore } from 'react'
import { ApiError, apiPath } from '@healdan/api'
import { t } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { useApi } from '../services'
import { NoPreview } from './NoPreview'
import styles from './preview.module.css'
import type { PreviewProps } from './types'

/**
 * A blob: address that exists while something is subscribed to it: made when the first one subscribes and revoked when
 * the last leaves, so a picture replaced or unmounted lets its address go, and React's strict mode (which subscribes,
 * leaves and subscribes again) ends with exactly one.
 */
function objectUrlStore(blob: Blob) {
  let url: string | undefined
  const listeners = new Set<() => void>()
  return {
    subscribe(listener: () => void) {
      listeners.add(listener)
      if (url === undefined) {
        url = URL.createObjectURL(blob)
        listeners.forEach((one) => one())
      }
      return () => {
        listeners.delete(listener)
        if (listeners.size === 0 && url !== undefined) {
          URL.revokeObjectURL(url)
          url = undefined
        }
      }
    },
    get: () => url,
  }
}

function useObjectUrl(blob: Blob | undefined): string | undefined {
  const store = useMemo(() => (blob ? objectUrlStore(blob) : undefined), [blob])
  // Stable functions: a new subscribe each render would leave and join the store each time, and its address with it
  const subscribe = useCallback((listener: () => void) => store?.subscribe(listener) ?? (() => {}), [store])
  const snapshot = useCallback(() => store?.get(), [store])
  return useSyncExternalStore(subscribe, snapshot)
}

/**
 * A picture previews as itself (spec section 6.4): the preview endpoint answers its own bytes (R54), read through the API
 * client so a failure is an answer this app reads (a storage outage has its banner), and shown from a blob: address. The
 * server records no download for it. A picture the browser can't draw, such as a TIFF or a HEIC in Chrome, says there is
 * no preview.
 */
export function PictureView({ document, version, showActions }: PreviewProps) {
  const api = useApi()
  const picture = useQuery({
    queryKey: ['documents', document.docNumber, 'preview', 'picture', version ?? document.fileVersion],
    queryFn: async ({ signal }) => (await api.getResponse(apiPath`/v1/documents/${document.docNumber}/preview`, version ? { version } : undefined, { signal })).blob(),
    refetchOnWindowFocus: false,
    staleTime: Infinity,
    gcTime: 30_000,
  })
  const source = useObjectUrl(picture.data)
  const [broken, setBroken] = useState(false)

  if (picture.isError) {
    const refused = picture.error instanceof ApiError && (picture.error.status === 403 || picture.error.status === 404)
    return refused ? <NoPreview document={document} showActions={showActions} /> : <ErrorPanel error={picture.error} onRetry={() => void picture.refetch()} />
  }
  if (broken) return <NoPreview document={document} showActions={showActions} />
  if (!source) return <p role="status" className={styles.message}>{t('preview.loading')}</p>
  return (
    <div className={styles.scroller}>
      <img src={source} alt={t('preview.picture', { name: document.name })} className={styles.picture} onError={() => setBroken(true)} />
    </div>
  )
}
