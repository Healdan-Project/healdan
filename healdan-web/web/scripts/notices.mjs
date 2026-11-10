// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Writes apps/user/dist/notices.txt: for each package the app ships (the lockfile's packages not marked dev, apart from
// the workspace's own), its name, version, licence and the text of its licence and notice files. About shows it.
// Fails when a shipped package has no such file, so nothing goes out without its notice.

import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

// Packages that declare their licence in package.json and ship no file for it. Reviewed one by one; the text says so.
// client-only is the React team's marker package (react-aria-components depends on it).
const DECLARED_ONLY = new Set(['client-only'])

const LICENCE_FILE = /^(licen[sc]e|notice)([-._].*)?$/i

function licenceFiles(dir) {
  if (!existsSync(dir)) return []
  return readdirSync(dir)
    .filter((name) => LICENCE_FILE.test(name) && statSync(join(dir, name)).isFile())
    .sort()
}

export function noticesFor(root) {
  const lock = JSON.parse(readFileSync(join(root, 'package-lock.json'), 'utf8'))
  const sections = []
  const missing = []
  for (const [key, entry] of Object.entries(lock.packages ?? {})) {
    if (!key.includes('node_modules/') || entry.link || entry.dev) continue
    const name = key.slice(key.lastIndexOf('node_modules/') + 'node_modules/'.length)
    const dir = join(root, key)
    const files = licenceFiles(dir)
    if (files.length === 0 && DECLARED_ONLY.has(name) && entry.license) {
      sections.push(`${name} ${entry.version}\nLicence: ${entry.license}\n\nThis package ships no licence file. Its package.json declares the ${entry.license} licence.\n`)
      continue
    }
    if (files.length === 0) {
      missing.push(`${name}@${entry.version}`)
      continue
    }
    const texts = files.map((file) => readFileSync(join(dir, file), 'utf8').trim())
    sections.push(`${name} ${entry.version}\nLicence: ${entry.license ?? 'unknown'}\n\n${texts.join('\n\n')}\n`)
  }
  if (missing.length > 0) {
    throw new Error(`No LICENSE, LICENCE or NOTICE file for: ${missing.join(', ')}`)
  }
  return sections.join('\n----\n\n')
}

export function writeNotices(root) {
  const text = noticesFor(root)
  const out = join(root, 'apps', 'user', 'dist')
  mkdirSync(out, { recursive: true })
  writeFileSync(join(out, 'notices.txt'), text)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    writeNotices(join(fileURLToPath(import.meta.url), '..', '..'))
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
}
