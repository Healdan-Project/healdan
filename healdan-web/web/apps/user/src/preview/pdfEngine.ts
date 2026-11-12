// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The one place that imports PDF.js, and the settings that let it run under AppServlet's Content-Security-Policy
// (M6 spec section 7.2: no eval, no WebAssembly, scripts, workers and fetches from the app's own address only).
import { getDocument, GlobalWorkerOptions, PDFWorker, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

// The worker is a file of the build, hashed by Vite and served from /app/assets/ (worker-src 'self').
GlobalWorkerOptions.workerSrc = workerUrl

/** Where vite.config.ts puts PDF.js's data files in the build (and serves them in dev): /app/pdfjs/. */
const DATA = `${import.meta.env.BASE_URL}pdfjs/`

// One worker for the page's life, shared by every document opened: a worker of its own for each document walked through
// would start and stop a thread, and parse PDF.js's own code, for each. A loading task that is given a worker doesn't
// destroy it when the document is closed.
let shared: PDFWorker | undefined
function worker(): PDFWorker {
  if (!shared || shared.destroyed) shared = new PDFWorker()
  return shared
}

/** An open PDF, as the preview needs it. */
export interface PdfFile {
  pages: number
  /** A page's size at scale 1, in CSS pixels */
  size(page: number): Promise<{ width: number; height: number }>
  /**
   * Draws a page into the canvas at the scale (1 is the page's own size in CSS pixels), sharp on a dense screen. The
   * promise ends when it is drawn, and a draw that is cancelled ends quietly.
   */
  draw(page: number, canvas: HTMLCanvasElement, scale: number): { done: Promise<void>; cancel: () => void }
  close(): void
}

/**
 * Opens a PDF from its bytes. What keeps it inside the policy, and why:
 * - No `isEvalSupported: false`, which the spec names: PDF.js 6 dropped the option along with the `new Function` it
 *   guarded, so nothing in it evaluates text (pdfjs-policy.test.mjs fails if a version of it does).
 * - `useWasm: false`: no WebAssembly, which the policy refuses without 'wasm-unsafe-eval' (a refused compile would be
 *   a violation report even though PDF.js recovers). JPEG 2000 and JBIG2 images, which have JavaScript versions, are
 *   decoded by those, loaded from `wasmUrl`. ICC colour profiles then aren't used, and the alternate colour space is.
 * - `enableXfa: false` (the default): no XFA forms, which would need their own layer.
 * The CMaps (CJK text with fonts not in the file) and the standard fonts (the fourteen every viewer has) come from /app/
 * too, never a CDN.
 */
export async function openPdf(bytes: ArrayBuffer): Promise<PdfFile> {
  const task = getDocument({
    worker: worker(),
    // A copy: PDF.js hands the bytes to its worker, which would leave the cached ones empty
    data: new Uint8Array(bytes.slice(0)),
    useWasm: false,
    wasmUrl: `${DATA}wasm/`,
    cMapUrl: `${DATA}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${DATA}standard_fonts/`,
    enableXfa: false,
  })
  const doc: PDFDocumentProxy = await task.promise

  return {
    pages: doc.numPages,
    async size(page) {
      const { width, height } = (await doc.getPage(page)).getViewport({ scale: 1 })
      return { width, height }
    },
    draw(page, canvas, scale) {
      let task: RenderTask | undefined
      let cancelled = false
      const done = (async () => {
        const proxy = await doc.getPage(page)
        if (cancelled) return
        const density = window.devicePixelRatio || 1
        const viewport = proxy.getViewport({ scale: scale * density })
        canvas.width = Math.floor(viewport.width)
        canvas.height = Math.floor(viewport.height)
        canvas.style.width = `${Math.floor(viewport.width / density)}px`
        canvas.style.height = `${Math.floor(viewport.height / density)}px`
        task = proxy.render({ canvas, viewport })
        try {
          await task.promise
        } catch (error) {
          // A render that was cancelled (another page or zoom came) is not a failure
          if (!(cancelled && error instanceof Error && error.name === 'RenderingCancelledException')) throw error
        }
      })()
      return {
        done,
        cancel() {
          cancelled = true
          task?.cancel()
        },
      }
    },
    close() {
      void task.destroy()
    },
  }
}
