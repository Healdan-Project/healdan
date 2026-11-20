// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The workspace's own settings, which nothing else reads before they matter: npm's in .npmrc, and Node's version in
// .nvmrc, which CI's Web check and a developer's nvm use, and which the POM names again for Maven's build. And how its
// apps stay apart: each is type-checked as a program of its own, and none imports another.

import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { ESLint } from 'eslint'

const web = join(fileURLToPath(import.meta.url), '..', '..')

// Reads .npmrc as npm's ini reader does: a key with no = is true, a quoted value loses its quotes, and an unquoted one
// ends at a ; or #
function npmrc() {
  const settings = {}
  for (const line of readFileSync(join(web, '.npmrc'), 'utf8').split('\n')) {
    const text = line.trim()
    if (text === '' || text.startsWith('#') || text.startsWith(';')) continue
    const at = text.indexOf('=')
    if (at === -1) {
      settings[text] = 'true'
      continue
    }
    const value = text.slice(at + 1).trim()
    const quoted = /^(["'])(.*)\1$/.exec(value)
    settings[text.slice(0, at).trim()] = quoted ? quoted[2] : value.split(/[;#]/)[0].trim()
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

// Each app registers its own router with TanStack Router (declare module … Register), which one program can't hold twice,
// so each app is a program of its own, and the root one (tsc -p .) holds only the packages.
test('every app has its own type check', () => {
  const steps = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8')).scripts.typecheck.split('&&').map((step) => step.trim())
  const apps = readdirSync(join(web, 'apps'), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
  assert.ok(apps.includes('user'), apps.join())
  for (const app of apps) assert.ok(steps.includes(`tsc -p apps/${app}`), `typecheck has no tsc -p apps/${app}: ${steps.join(' && ')}`)
  assert.ok(steps.includes('tsc -p .'), `typecheck has no tsc -p . for the packages: ${steps.join(' && ')}`)
  const root = JSON.parse(readFileSync(join(web, 'tsconfig.json'), 'utf8'))
  assert.deepEqual(root.include.filter((path) => path.startsWith('apps/')), [], 'the root program holds an app')
})

// What two apps both need is a package's (packages/shell), so neither app's build holds the other's code.
test('no app imports another', async () => {
  const eslint = new ESLint({ cwd: web })
  const rules = async (filePath, code) => (await eslint.lintText(code, { filePath }))[0].messages.map((message) => message.ruleId)
  const rule = 'healdan/no-app-imports-another'
  const refused = [
    ['apps/admin/src/x.ts', '../../user/src/x'],
    ['apps/user/src/x.ts', '@healdan/admin'],
    ['apps/admin/src/views/x.tsx', '@healdan/user'],
    ['apps/user/src/frame/x.tsx', '../../../admin/src/x'],
  ]
  for (const [file, source] of refused) {
    assert.ok((await rules(file, `import { x } from '${source}'\nexport { x }\n`)).includes(rule), `${source} from ${file}`)
    assert.ok((await rules(file, `export { x } from '${source}'\n`)).includes(rule), `export from ${source} in ${file}`)
    assert.ok((await rules(file, `export const x = () => import('${source}')\n`)).includes(rule), `import('${source}') in ${file}`)
  }
  const allowed = [
    ['apps/user/src/views/x.ts', '../frame/y'],
    ['apps/user/src/x.ts', '@healdan/shell'],
    ['apps/admin/src/x.ts', '@healdan/ui'],
    ['apps/admin/src/x.ts', '../../../packages/shell/src/x'],
  ]
  for (const [file, source] of allowed) {
    assert.ok(!(await rules(file, `import { x } from '${source}'\nexport { x }\n`)).includes(rule), `${source} from ${file}`)
  }
})
