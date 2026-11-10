// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import assert from 'node:assert/strict'
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { noticesFor, writeNotices } from './notices.mjs'

function project(packages, files = {}) {
  const root = mkdtempSync(join(tmpdir(), 'notices-'))
  const entries = {
    '': { name: 'web' },
    'apps/user': { name: '@healdan/user', license: 'LGPL-3.0-only' },
    'node_modules/@healdan/user': { resolved: 'apps/user', link: true },
  }
  for (const [name, entry] of Object.entries(packages)) entries[`node_modules/${name}`] = entry
  writeFileSync(join(root, 'package-lock.json'), JSON.stringify({ lockfileVersion: 3, packages: entries }))
  for (const [path, text] of Object.entries(files)) {
    const full = join(root, path)
    mkdirSync(join(full, '..'), { recursive: true })
    writeFileSync(full, text)
  }
  return root
}

test('lists runtime packages with their licence text', () => {
  const root = project(
    { a: { version: '1.2.3', license: 'MIT' }, 'b/c': { version: '4.5.6', license: 'ISC' } },
    { 'node_modules/a/LICENSE': 'Licence of a', 'node_modules/b/c/NOTICE.txt': 'Notice of c' },
  )
  const text = noticesFor(root)
  assert.match(text, /a 1\.2\.3\nLicence: MIT\n/)
  assert.match(text, /Licence of a/)
  assert.match(text, /b\/c 4\.5\.6\nLicence: ISC\n/)
  assert.match(text, /Notice of c/)
})

test('leaves out dev packages and workspace packages', () => {
  const root = project(
    { a: { version: '1.0.0', license: 'MIT' }, dev: { version: '2.0.0', license: 'MIT', dev: true } },
    { 'node_modules/a/LICENSE': 'Licence of a' },
  )
  const text = noticesFor(root)
  assert.doesNotMatch(text, /dev 2\.0\.0/)
  assert.doesNotMatch(text, /@healdan\/user/)
})

test('fails on a runtime package without a licence file, naming it', () => {
  const root = project({ a: { version: '1.0.0', license: 'MIT' } })
  assert.throws(() => noticesFor(root), /a@1\.0\.0/)
})

test('writes apps/user/dist/notices.txt', () => {
  const root = project({ a: { version: '1.0.0', license: 'MIT' } }, { 'node_modules/a/LICENCE': 'Licence of a' })
  writeNotices(root)
  assert.match(readFileSync(join(root, 'apps/user/dist/notices.txt'), 'utf8'), /Licence of a/)
})

test('lists a package that declares MIT but ships no licence file, as the declared-only list allows', () => {
  const root = project({ 'client-only': { version: '0.0.1', license: 'MIT' } })
  const text = noticesFor(root)
  assert.match(text, /client-only 0\.0\.1\nLicence: MIT\n/)
  assert.match(text, /ships no licence file/)
})

test('the command writes notices.txt when run through a symlinked path', () => {
  const root = project({ a: { version: '1.0.0', license: 'MIT' } }, { 'node_modules/a/LICENSE': 'Licence of a' })
  mkdirSync(join(root, 'scripts'))
  copyFileSync(fileURLToPath(new URL('./notices.mjs', import.meta.url)), join(root, 'scripts', 'notices.mjs'))
  const link = join(mkdtempSync(join(tmpdir(), 'notices-link-')), 'web')
  symlinkSync(root, link)
  execFileSync(process.execPath, [join(link, 'scripts', 'notices.mjs')])
  assert.match(readFileSync(join(root, 'apps/user/dist/notices.txt'), 'utf8'), /Licence of a/)
})
