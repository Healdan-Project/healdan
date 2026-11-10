// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ESLint } from 'eslint'

const eslint = new ESLint()

async function messages(filePath, code) {
  const [result] = await eslint.lintText(code, { filePath })
  return result.messages.map((m) => m.ruleId)
}

// Screens reach React Aria only through @healdan/ui, so every one of its packages is refused outside it.
const BANNED = [
  'react-aria-components',
  'react-aria-components/Button',
  'react-aria',
  'react-aria/useButton',
  'react-stately',
  'react-stately/useToggleState',
  '@react-aria/button',
  '@react-stately/toggle',
]

for (const source of BANNED) {
  test(`${source} is refused in the app`, async () => {
    const found = await messages('apps/user/src/x.ts', `import * as x from '${source}'\nexport { x }\n`)
    assert.ok(found.includes('no-restricted-imports'), found.join())
  })
}

test('packages/ui may import React Aria Components', async () => {
  const found = await messages('packages/ui/src/x.ts', `import * as x from 'react-aria-components'\nexport { x }\n`)
  assert.deepEqual(found, [])
})
