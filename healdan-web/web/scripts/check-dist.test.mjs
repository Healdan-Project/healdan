// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { ADMIN_MARKER, distProblems } from './check-dist.mjs'

// A User app's build: its page, the pressable file and PDF.js's worker and data
function dist(html, css = true) {
  const dir = mkdtempSync(join(tmpdir(), 'dist-'))
  writeFileSync(join(dir, 'index.html'), html)
  if (css) writeFileSync(join(dir, 'pressable.css'), '@layer {}')
  mkdirSync(join(dir, 'assets'))
  writeFileSync(join(dir, 'assets', 'pdf.worker.min-AbCd1234.mjs'), '')
  for (const folder of ['cmaps', 'standard_fonts', 'wasm']) mkdirSync(join(dir, 'pdfjs', folder), { recursive: true })
  writeFileSync(join(dir, 'pdfjs', 'cmaps', 'Adobe-Japan1-UCS2.bcmap'), '')
  writeFileSync(join(dir, 'pdfjs', 'standard_fonts', 'FoxitSerif.pfb'), '')
  for (const name of ['jbig2_nowasm_fallback.js', 'openjpeg_nowasm_fallback.js']) writeFileSync(join(dir, 'pdfjs', 'wasm', name), '')
  return dir
}

const good = '<head><link rel="stylesheet" id="react-aria-pressable-style" href="/app/pressable.css"></head>'

test('accepts a build that links pressable.css under the id, with the base applied', () => {
  assert.deepEqual(distProblems(dist(good)), [])
})

test('refuses a link without the base, without the id, or without the file', () => {
  assert.equal(distProblems(dist(good.replace('/app/', '/'))).length, 1)
  assert.equal(distProblems(dist(good.replace(' id="react-aria-pressable-style"', ''))).length, 1)
  assert.equal(distProblems(dist(good, false)).length, 1)
})

test("refuses a build without PDF.js's worker or its data files", () => {
  const noWorker = dist(good)
  rmSync(join(noWorker, 'assets', 'pdf.worker.min-AbCd1234.mjs'))
  assert.match(distProblems(noWorker).join('\n'), /PDF\.js worker/)

  const noFonts = dist(good)
  rmSync(join(noFonts, 'pdfjs', 'standard_fonts', 'FoxitSerif.pfb'))
  assert.match(distProblems(noFonts).join('\n'), /standard_fonts/)

  const noCmaps = dist(good)
  rmSync(join(noCmaps, 'pdfjs', 'cmaps'), { recursive: true })
  assert.match(distProblems(noCmaps).join('\n'), /cmaps/)

  const noDecoder = dist(good)
  rmSync(join(noDecoder, 'pdfjs', 'wasm', 'jbig2_nowasm_fallback.js'))
  assert.match(distProblems(noDecoder).join('\n'), /jbig2_nowasm_fallback/)
})

test("refuses a User app's build with the Admin app's code in it, in any file", () => {
  const inScript = dist(good)
  writeFileSync(join(inScript, 'assets', 'index-AbCd1234.js'), `const s={"${ADMIN_MARKER}":"Dashboard"}`)
  assert.match(distProblems(inScript).join('\n'), /assets\/index-AbCd1234\.js.*admin\.dashboard\.title/)

  const deeper = dist(good)
  writeFileSync(join(deeper, 'pdfjs', 'cmaps', 'Extra.bcmap'), Buffer.concat([Buffer.from([0, 255, 1]), Buffer.from(ADMIN_MARKER)]))
  assert.match(distProblems(deeper).join('\n'), /pdfjs\/cmaps\/Extra\.bcmap/)
})

// An Admin app's build: its page, the pressable file and a script holding its catalogue, and no PDF.js
function adminDist(html, { css = true, marker = true } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'dist-admin-'))
  writeFileSync(join(dir, 'index.html'), html)
  if (css) writeFileSync(join(dir, 'pressable.css'), '@layer {}')
  mkdirSync(join(dir, 'assets'))
  writeFileSync(join(dir, 'assets', 'index-EfGh5678.js'), marker ? `const s={"${ADMIN_MARKER}":"Dashboard"}` : 'const s={}')
  return dir
}

const goodAdmin = good.replace('/app/', '/admin/')

test("accepts an Admin app's build under /admin/, without PDF.js", () => {
  assert.deepEqual(distProblems(adminDist(goodAdmin), { app: 'admin' }), [])
})

test("refuses an Admin app's build whose pressable link isn't under /admin/, or that has no pressable file", () => {
  assert.match(distProblems(adminDist(good), { app: 'admin' }).join('\n'), /not \/admin\/pressable\.css/)
  assert.match(distProblems(adminDist(goodAdmin.replace('/admin/', '/')), { app: 'admin' }).join('\n'), /not \/admin\/pressable\.css/)
  assert.equal(distProblems(adminDist(goodAdmin, { css: false }), { app: 'admin' }).length, 1)
  // The User app's check still wants /app/
  assert.match(distProblems(dist(goodAdmin)).join('\n'), /not \/app\/pressable\.css/)
})

test("refuses an Admin app's build with PDF.js in it", () => {
  const worker = adminDist(goodAdmin)
  writeFileSync(join(worker, 'assets', 'pdf.worker.min-AbCd1234.mjs'), '')
  assert.match(distProblems(worker, { app: 'admin' }).join('\n'), /PDF\.js/)

  const data = adminDist(goodAdmin)
  mkdirSync(join(data, 'pdfjs', 'cmaps'), { recursive: true })
  assert.match(distProblems(data, { app: 'admin' }).join('\n'), /PDF\.js/)
})

test("refuses an Admin app's build without the marker, which would leave the User app's check proving nothing", () => {
  assert.match(distProblems(adminDist(goodAdmin, { marker: false }), { app: 'admin' }).join('\n'), /admin\.dashboard\.title/)
})

test('refuses an app it doesn\'t know', () => {
  assert.match(distProblems(adminDist(goodAdmin), { app: 'reports' }).join('\n'), /reports/)
})
