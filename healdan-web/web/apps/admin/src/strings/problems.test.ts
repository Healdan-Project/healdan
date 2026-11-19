// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { messageFor } from '@healdan/shell'
import { createCatalogue } from '@healdan/ui'
import yaml from '../../../../../../healdan-api/src/main/resources/healdan/api/openapi-admin.yaml?raw'
import { adminEnUS, adminPlurals } from './en-US'
import { t } from './index'

// The admin contract's problem codes, read from its Problem schema's code, so a new one can't go unmapped. The User app's
// errors.test.ts reads the first contract's; these are the Admin app's (spec 12).
const problem = /\n {4}Problem:\n {6}type: object\n[\s\S]*?\n {8}code:\n[\s\S]*?examples: \[([^\]]*)\]/.exec(yaml)?.[1]
const listed = problem?.split(',').map((code) => code.trim()) ?? []

// Codes the app never meets: it signs in by the web page, and only a device token gets web-session-only.
const NEVER_MET = ['web-session-only']

const catalogue = createCatalogue(adminEnUS, adminPlurals)

describe("The admin contract's problem codes", () => {
  it('are read from the contract', () => {
    expect(listed).toContain('admins-only')
    expect(listed.length).toBeGreaterThan(3)
  })

  it('include every code the app is said never to meet', () => {
    for (const code of NEVER_MET) expect(listed).toContain(code)
  })

  for (const code of listed.filter((c) => !NEVER_MET.includes(c))) {
    it(`has a message for ${code}, in the Admin app's catalogue or the shared one`, () => {
      expect(catalogue.has(`problems.${code}`), code).toBe(true)
      // As the app's screens ask for it, never the server's own text
      const message = messageFor(new ApiError(409, code, 'server text', {}))
      expect(message).toBe(t(`problems.${code}` as Parameters<typeof t>[0]))
      expect(message).not.toBe('server text')
    })
  }
})
