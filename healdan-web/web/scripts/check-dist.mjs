// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Checks the built app: index.html links pressable.css under the id React Aria looks for (so it injects no <style>,
// which the page's Content-Security-Policy would block), with Vite's base applied, and the file is in dist/. And that
// PDF.js's worker and its data files are there, which the preview fetches from /app/ (pdfjs-data.mjs puts them in).

import { existsSync, readdirSync, readFileSync, realpathSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

export function distProblems(dist) {
  const problems = []
  const index = join(dist, 'index.html')
  if (!existsSync(index)) return [`${index} is missing`]
  const link = readFileSync(index, 'utf8').match(/<link\b[^>]*>/g)?.find((tag) => /\bid="react-aria-pressable-style"/.test(tag))
  if (!link) problems.push('index.html has no <link id="react-aria-pressable-style">')
  else {
    if (!/\brel="stylesheet"/.test(link)) problems.push('the pressable link is not a stylesheet')
    if (!/\bhref="\/app\/pressable\.css"/.test(link)) problems.push(`the pressable link's href is not /app/pressable.css: ${link}`)
  }
  if (!existsSync(join(dist, 'pressable.css'))) problems.push('dist/pressable.css is missing')
  const assets = existsSync(join(dist, 'assets')) ? readdirSync(join(dist, 'assets')) : []
  if (!assets.some((name) => /^pdf\.worker\.min-.+\.mjs$/.test(name))) problems.push("dist/assets has no PDF.js worker (pdf.worker.min-<hash>.mjs), which the preview's page can't draw without")
  for (const folder of ['cmaps', 'standard_fonts']) {
    if (!existsSync(join(dist, 'pdfjs', folder)) || readdirSync(join(dist, 'pdfjs', folder)).length === 0) problems.push(`dist/pdfjs/${folder}/ is missing or empty`)
  }
  for (const name of ['jbig2_nowasm_fallback.js', 'openjpeg_nowasm_fallback.js']) {
    if (!existsSync(join(dist, 'pdfjs', 'wasm', name))) problems.push(`dist/pdfjs/wasm/${name} is missing`)
  }
  return problems
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  const problems = distProblems(join(realpathSync(fileURLToPath(import.meta.url)), '..', '..', 'apps', 'user', 'dist'))
  if (problems.length > 0) {
    console.error(problems.join('\n'))
    process.exit(1)
  }
}
