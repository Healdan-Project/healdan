// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { distProblems } from './check-dist.mjs'

function dist(html, css = true) {
  const dir = mkdtempSync(join(tmpdir(), 'dist-'))
  writeFileSync(join(dir, 'index.html'), html)
  if (css) writeFileSync(join(dir, 'pressable.css'), '@layer {}')
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
