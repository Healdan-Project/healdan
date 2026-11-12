// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { pdfjsDataFiles } from '../apps/user/pdfjs-data.mjs'

// AppServlet's Content-Security-Policy (M6 spec section 7.2) allows no eval and no WebAssembly. These tests are the check
// that the PDF.js the app ships still runs under it, which an upgrade could change without any other test noticing; the
// end-to-end test (e2e/working.spec.ts) is the one that watches a real browser's console for a violation.
const root = dirname(createRequire(import.meta.url).resolve('pdfjs-dist/package.json'))
const engine = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'apps', 'user', 'src', 'preview', 'pdfEngine.ts'), 'utf8')

// The code the app ships of PDF.js: what `import ... from 'pdfjs-dist'` loads (its main file, which Vite bundles), the
// worker pdfEngine.ts names, and the JavaScript image decoders pdfjs-data.mjs puts in the build
const worker = /'pdfjs-dist\/(build\/[^'?]+)\?url'/.exec(engine)?.[1]
const shipped = [
  createRequire(import.meta.url).resolve('pdfjs-dist'),
  ...(worker ? [join(root, worker)] : []),
  ...pdfjsDataFiles().filter((one) => one.path.endsWith('.js')).map((one) => one.file),
]

test('the engine names the worker it loads', () => assert.ok(worker, 'pdfEngine.ts imports pdfjs-dist/build/<worker>?url'))

for (const path of shipped) {
  const file = relative(root, path)
  test(`${file} evaluates no text`, () => {
    const code = readFileSync(path, 'utf8')
    assert.doesNotMatch(code, /new Function\(/, 'it builds code with new Function, which the policy refuses: set the option that turns that off (it used to be isEvalSupported: false)')
    assert.doesNotMatch(code, /[^.\w]eval\(/, 'it calls eval, which the policy refuses')
  })
}

test('PDF.js has no isEvalSupported option to set, as the spec says it is set to false', () => {
  const types = readFileSync(join(root, 'types', 'src', 'display', 'api.d.ts'), 'utf8')
  // If a version brings it back, it is false in pdfEngine.ts from that day
  if (/isEvalSupported/.test(types)) assert.match(engine, /isEvalSupported:\s*false/)
})

test('the engine turns WebAssembly off, which the policy refuses without wasm-unsafe-eval', () => {
  assert.match(engine, /useWasm:\s*false/)
})

test('the engine takes everything PDF.js fetches from /app/, never from another address', () => {
  assert.doesNotMatch(engine, /https?:\/\//)
  assert.match(engine, /import\.meta\.env\.BASE_URL/)
})
