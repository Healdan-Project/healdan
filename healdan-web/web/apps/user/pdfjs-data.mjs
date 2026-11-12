// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// PDF.js fetches its data files by name while it draws (M6 spec section 7.2: from /app/, never a CDN, under the page's
// Content-Security-Policy). This Vite plugin puts the ones the app uses in the build as /app/pdfjs/..., and serves them
// from node_modules while `npm run dev` runs, so none is copied into the repository:
// - cmaps/: the predefined character maps, for CJK text whose fonts aren't in the PDF;
// - standard_fonts/: the fourteen fonts every viewer has, for PDFs that don't embed them;
// - wasm/*_nowasm_fallback.js: JPEG 2000 and JBIG2 decoders in JavaScript, which PDF.js loads because the page's policy
//   refuses WebAssembly and src/preview/pdfEngine.ts turns it off. The .wasm files, the ICC profiles that need them and
//   the licence files (notices.mjs carries those) aren't shipped.

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'

/** The files to ship, as { path: where under /app/pdfjs/, file: where on disk }, in a stable order */
export function pdfjsDataFiles() {
  const root = dirname(createRequire(import.meta.url).resolve('pdfjs-dist/package.json'))
  const files = []
  for (const folder of ['cmaps', 'standard_fonts']) {
    for (const name of readdirSync(join(root, folder)).sort()) {
      if (/^licen[sc]e/i.test(name) || !statSync(join(root, folder, name)).isFile()) continue
      files.push({ path: `${folder}/${name}`, file: join(root, folder, name) })
    }
  }
  for (const name of ['jbig2_nowasm_fallback.js', 'openjpeg_nowasm_fallback.js']) {
    files.push({ path: `wasm/${name}`, file: join(root, 'wasm', name) })
  }
  return files
}

const TYPES = { '.js': 'text/javascript;charset=UTF-8', '.bcmap': 'application/octet-stream', '.pfb': 'application/octet-stream', '.ttf': 'font/ttf' }

export function pdfjsData() {
  return {
    name: 'healdan-pdfjs-data',
    generateBundle() {
      for (const { path, file } of pdfjsDataFiles()) {
        this.emitFile({ type: 'asset', fileName: `pdfjs/${path}`, source: readFileSync(file) })
      }
    },
    configureServer(server) {
      // In Vite 8 this middleware runs before the base is taken off the address, so it's mounted under the base
      server.middlewares.use(`${server.config.base}pdfjs/`, (request, response, next) => {
        const wanted = decodeURIComponent((request.url ?? '').split('?')[0] ?? '').replace(/^\//, '')
        const found = pdfjsDataFiles().find((one) => one.path === wanted)
        if (!found || !existsSync(found.file)) return next()
        const extension = found.path.slice(found.path.lastIndexOf('.'))
        response.setHeader('Content-Type', TYPES[extension] ?? 'application/octet-stream')
        response.end(readFileSync(found.file))
      })
    },
  }
}
