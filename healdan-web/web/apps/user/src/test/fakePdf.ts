// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { PdfFile } from '../preview/pdfEngine'

// A stand-in for PDF.js, which needs a worker and a real canvas: tests that mock '../preview/pdfEngine' with this module
// see which page was drawn at which scale, and whether the file was closed. The real thing runs in the end-to-end tests.
export const pdf = {
  pages: 3,
  /** A page is 600 by 800 */
  opened: [] as ArrayBuffer[],
  drawn: [] as { page: number; scale: number }[],
  closed: 0,
  failOpen: false,
}

export function resetPdf(): void {
  pdf.pages = 3
  pdf.opened = []
  pdf.drawn = []
  pdf.closed = 0
  pdf.failOpen = false
}

export async function openPdf(bytes: ArrayBuffer): Promise<PdfFile> {
  if (pdf.failOpen) throw new Error('Invalid PDF structure.')
  pdf.opened.push(bytes)
  return {
    pages: pdf.pages,
    size: async () => ({ width: 600, height: 800 }),
    draw(page, _canvas, scale) {
      pdf.drawn.push({ page, scale })
      return { done: Promise.resolve(), cancel: () => {} }
    },
    close() {
      pdf.closed++
    },
  }
}
