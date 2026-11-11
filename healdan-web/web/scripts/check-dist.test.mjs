// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { distProblems } from './check-dist.mjs'

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
