// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The workspace's own settings, which nothing else reads before they matter: npm's in .npmrc, and Node's version in
// .nvmrc, which CI's Web check and a developer's nvm use, and which the POM names again for Maven's build.

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

const web = join(fileURLToPath(import.meta.url), '..', '..')

function npmrc() {
  const settings = {}
  for (const line of readFileSync(join(web, '.npmrc'), 'utf8').split('\n')) {
    const text = line.trim()
    if (text === '' || text.startsWith('#') || text.startsWith(';')) continue
    const at = text.indexOf('=')
    settings[text.slice(0, at).trim()] = text.slice(at + 1).trim()
  }
  return settings
}

test("npm runs no package's install script and saves exact versions, for a developer's own npm install too", () => {
  const settings = npmrc()
  assert.equal(settings['ignore-scripts'], 'true')
  assert.equal(settings['save-exact'], 'true')
})

test("Node's version in .nvmrc is the one Maven's build downloads", () => {
  const nvmrc = readFileSync(join(web, '.nvmrc'), 'utf8').trim()
  const pom = readFileSync(join(web, '..', 'pom.xml'), 'utf8')
  const named = [...pom.matchAll(/<nodeVersion>([^<]*)<\/nodeVersion>/g)].map((match) => match[1].trim().replace(/^v/, ''))
  assert.deepEqual(named, [nvmrc.replace(/^v/, '')], "Change healdan-web/web/.nvmrc and the POM's <nodeVersion> together")
})
