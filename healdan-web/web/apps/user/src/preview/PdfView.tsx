// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ApiError, type Api } from '@healdan/api'
import { Button, Icon, t } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { usePanesResizing } from '../frame/PaneWidths'
import { useApi } from '../services'
import { NoPreview } from './NoPreview'
import type { PdfFile } from './pdfEngine'
import styles from './preview.module.css'
import { previewSource, type PreviewProps, type PreviewSource } from './types'

type Preview = { kind: 'ready'; bytes: ArrayBuffer } | { kind: 'pending'; retryMs: number }

/** What the zoom steps are, as shares of the page fitted to the width (spec section 6.4) */
export const ZOOMS = [0.5, 0.75, 1, 1.25, 1.5, 2, 3]
const FIT = ZOOMS.indexOf(1)
/** Room round a page, in CSS pixels */
const MARGIN = 32

async function fetchPreview(api: Api, from: PreviewSource, signal: AbortSignal): Promise<Preview> {
  const response = await api.getResponse(from.path, from.query, { signal })
  if (response.status === 202) {
    // Asks again when the server says; it's 5 seconds, and never less than 1 second, which a bad value could make. No
    // header is 5 seconds too, not the 0 that Number(null) would make it
    const header = response.headers.get('Retry-After')?.trim()
    const seconds = header ? Number(header) : Number.NaN
    return { kind: 'pending', retryMs: Math.max(1, Number.isFinite(seconds) ? seconds : 5) * 1000 }
  }
  return { kind: 'ready', bytes: await response.arrayBuffer() }
}

/**
 * The width of an element, as it changes: give the callback to its `ref`, which may come after the first render. While a
 * pane's divider is dragged it stays the width the page was last drawn at, and the width the drag ends at comes once it's
 * over: a page drawn again at each step of a drag would be blank for most of it.
 */
function useWidth(): [(element: HTMLElement | null) => void, number] {
  const [element, setElement] = useState<HTMLElement | null>(null)
  const [width, setWidth] = useState(0)
  const resizing = usePanesResizing()
  const [shown, setShown] = useState(0)
  if (!resizing && shown !== width) setShown(width)
  useLayoutEffect(() => {
    // An observer says the width when it starts watching, so the first width comes with it
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => setWidth(element.clientWidth))
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])
  return [setElement, shown]
}

/**
 * A document's preview, M1.5's PDF drawn in the page by PDF.js (spec sections 6.4 and 9.3): one page at a time, with the
 * page and the zoom to move. While the server is still converting, it asks again as it is told. A type with no
 * preview, or one the person may not preview, says so. Give it a `key` of the document, so a new one starts at page 1. With a
 * `source` it draws that address's PDF instead, such as an email's attachment.
 */
export function PdfView({ document, version, showActions, source }: PreviewProps) {
  const api = useApi()
  const from = source ?? previewSource(document, version)
  const preview = useQuery({
    // An address, which includes the version asked for; the document's current file is named by its file version, so a
    // new one is a new key
    queryKey: ['preview', 'pdf', from.path, from.query ?? null, source ? (source.key ?? null) : document.fileVersion],
    queryFn: ({ signal }) => fetchPreview(api, from, signal),
    // A conversion that isn't made yet: asked again as the server said, for as long as this is on screen
    refetchInterval: (query) => (query.state.data?.kind === 'pending' ? query.state.data.retryMs : false),
    // The bytes of a version never change, and a few documents' worth are kept for a short while only
    refetchOnWindowFocus: false,
    staleTime: Infinity,
    gcTime: 30_000,
  })
  const bytes = preview.data?.kind === 'ready' ? preview.data.bytes : undefined

  const [file, setFile] = useState<PdfFile>()
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    if (!bytes) return
    let gone = false
    let opened: PdfFile | undefined
    // PDF.js is a chunk of its own, loaded when the first PDF is previewed
    import('./pdfEngine').then(({ openPdf }) => openPdf(bytes)).then(
      (next) => {
        if (gone) next.close()
        else {
          opened = next
          setFile(next)
        }
      },
      () => !gone && setFailed(true),
    )
    return () => {
      gone = true
      opened?.close()
      setFile(undefined)
    }
  }, [bytes])

  const [page, setPage] = useState(1)
  const [zoom, setZoom] = useState(FIT)
  const [scroller, width] = useWidth()
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const target = canvas.current
    if (!file || !target) return
    let gone = false
    let job: ReturnType<PdfFile['draw']> | undefined
    void (async () => {
      try {
        const size = await file.size(page)
        if (gone) return
        // Fitted to the width, as the zoom's 100%; before the first measure (width 0) the page's own size
        const fit = width > 0 ? Math.max(0.1, (width - MARGIN) / size.width) : 1
        job = file.draw(page, target, fit * (ZOOMS[zoom] ?? 1))
        await job.done
      } catch {
        if (!gone) setFailed(true)
      }
    })()
    return () => {
      gone = true
      job?.cancel()
    }
  }, [file, page, zoom, width])

  if (preview.isError) {
    const refused = preview.error instanceof ApiError && (preview.error.code === 'no-preview' || preview.error.status === 403 || preview.error.status === 404)
    return refused ? <NoPreview document={document} version={version} showActions={showActions} /> : <ErrorPanel error={preview.error} onRetry={() => void preview.refetch()} />
  }
  if (failed) return <NoPreview document={document} version={version} message={t('preview.failed')} showActions={showActions} />
  if (preview.data?.kind === 'pending') return <p role="status" className={styles.message}>{t('preview.preparing')}</p>
  if (!file) return <p role="status" className={styles.message}>{t('preview.loading')}</p>

  return (
    <div className={styles.view}>
      <div role="group" aria-label={t('preview.toolbar')} className={styles.controls}>
        <Button variant="quiet" aria-label={t('preview.previousPage')} isDisabled={page <= 1} onPress={() => setPage(page - 1)}>
          <Icon name="chevronUp" size={18} />
        </Button>
        <span role="status" className={styles.status}>
          {t('preview.page', { page, pages: file.pages })}
        </span>
        <Button variant="quiet" aria-label={t('preview.nextPage')} isDisabled={page >= file.pages} onPress={() => setPage(page + 1)}>
          <Icon name="chevronDown" size={18} />
        </Button>
        <span className={styles.spacer} />
        <Button variant="quiet" aria-label={t('preview.zoomOut')} isDisabled={zoom <= 0} onPress={() => setZoom(zoom - 1)}>
          <Icon name="zoomOut" size={18} />
        </Button>
        <span className={styles.zoom}>{t('preview.zoom', { percent: Math.round((ZOOMS[zoom] ?? 1) * 100) })}</span>
        <Button variant="quiet" aria-label={t('preview.zoomIn')} isDisabled={zoom >= ZOOMS.length - 1} onPress={() => setZoom(zoom + 1)}>
          <Icon name="zoomIn" size={18} />
        </Button>
      </div>
      {/* Focusable, so the keyboard can scroll a page that's bigger than the room; the arrow keys scroll it there, not walk the list */}
      <div ref={scroller} className={styles.scroller} role="region" aria-label={t('preview.scroll')} tabIndex={0} data-scrolls>
        <canvas ref={canvas} className={styles.canvas} role="img" aria-label={t('preview.pageLabel', { page })} />
      </div>
    </div>
  )
}
