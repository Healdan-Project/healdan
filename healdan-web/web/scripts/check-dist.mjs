// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Checks an app's build (`node check-dist.mjs [user|admin]`, the User app's without one): index.html links pressable.css
// under the id React Aria looks for (so it injects no <style>, which the page's Content-Security-Policy would block), with
// Vite's base applied (/app/ or /admin/), and the file is in dist/.
// The User app's: PDF.js's worker and its data files are there, which the preview fetches from /app/ (pdfjs-data.mjs puts
// them in), and no file holds ADMIN_MARKER, so none of the Admin app's code is downloaded by people who aren't admins.
// The Admin app's: no PDF.js, which it never loads and its page's policy has no worker for, and the marker is there,
// without which the User app's check would prove nothing.

import { existsSync, readdirSync, readFileSync, realpathSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

/** A key of the Admin app's catalogue (apps/admin/src/strings/en-US.ts), which only the Admin app's code holds */
export const ADMIN_MARKER = 'admin.dashboard.title'

const BASES = { user: '/app/', admin: '/admin/' }

// Every file under dir, at any depth
function filesIn(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => (entry.isDirectory() ? filesIn(join(dir, entry.name)) : [join(dir, entry.name)]))
}

export function distProblems(dist, { app = 'user' } = {}) {
  const base = BASES[app]
  if (base === undefined) return [`there's no app ${app}: ${Object.keys(BASES).join(' or ')}`]
  const problems = []
  const index = join(dist, 'index.html')
  if (!existsSync(index)) return [`${index} is missing`]
  const link = readFileSync(index, 'utf8').match(/<link\b[^>]*>/g)?.find((tag) => /\bid="react-aria-pressable-style"/.test(tag))
  if (!link) problems.push('index.html has no <link id="react-aria-pressable-style">')
  else {
    if (!/\brel="stylesheet"/.test(link)) problems.push('the pressable link is not a stylesheet')
    if (!new RegExp(`\\bhref="${base}pressable\\.css"`).test(link)) problems.push(`the pressable link's href is not ${base}pressable.css: ${link}`)
  }
  if (!existsSync(join(dist, 'pressable.css'))) problems.push('dist/pressable.css is missing')
  const assets = existsSync(join(dist, 'assets')) ? readdirSync(join(dist, 'assets')) : []
  const worker = assets.some((name) => /^pdf\.worker\.min-.+\.mjs$/.test(name))
  const marked = filesIn(dist).filter((file) => readFileSync(file).includes(ADMIN_MARKER))
  if (app === 'admin') {
    if (worker || existsSync(join(dist, 'pdfjs'))) problems.push("dist/ has PDF.js (assets/pdf.worker.min-<hash>.mjs or pdfjs/), which the Admin app never loads")
    if (marked.length === 0) problems.push(`no file holds ${ADMIN_MARKER}, the marker that shows the User app's build holds none of the Admin app's code`)
    return problems
  }
  if (!worker) problems.push("dist/assets has no PDF.js worker (pdf.worker.min-<hash>.mjs), which the preview's page can't draw without")
  for (const folder of ['cmaps', 'standard_fonts']) {
    if (!existsSync(join(dist, 'pdfjs', folder)) || readdirSync(join(dist, 'pdfjs', folder)).length === 0) problems.push(`dist/pdfjs/${folder}/ is missing or empty`)
  }
  for (const name of ['jbig2_nowasm_fallback.js', 'openjpeg_nowasm_fallback.js']) {
    if (!existsSync(join(dist, 'pdfjs', 'wasm', name))) problems.push(`dist/pdfjs/wasm/${name} is missing`)
  }
  for (const file of marked) problems.push(`dist/${relative(dist, file).split('\\').join('/')} holds ${ADMIN_MARKER}, the Admin app's code, which the User app's build never does`)
  return problems
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  const app = process.argv[2] ?? 'user'
  const problems = distProblems(join(realpathSync(fileURLToPath(import.meta.url)), '..', '..', 'apps', app, 'dist'), { app })
  if (problems.length > 0) {
    console.error(problems.join('\n'))
    process.exit(1)
  }
}
