// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Api } from '@healdan/api'
import type { AccessState } from './access'
import type { AppProps } from './App'
import { start } from './main'

// What start handed the app, and each client guardApi made: the app itself isn't drawn here
const seen = vi.hoisted(() => ({ props: [] as AppProps[], guarded: [] as { api: Api; access: AccessState }[] }))

vi.mock('./App', () => ({
  App: (props: AppProps) => {
    seen.props.push(props)
    return null
  },
}))

vi.mock('./access', async (original) => {
  const real = await original<typeof import('./access')>()
  return {
    ...real,
    guardApi: (...args: Parameters<typeof real.guardApi>) => {
      const api = real.guardApi(...args)
      seen.guarded.push({ api, access: args[1] })
      return api
    },
  }
})

describe("The client the Admin app's start hands the app", () => {
  let root: HTMLElement | undefined
  let unmount: (() => void) | undefined

  afterEach(() => {
    act(() => unmount?.())
    unmount = undefined
    root?.remove()
    vi.restoreAllMocks()
    seen.props.length = 0
    seen.guarded.length = 0
  })

  it('is the guarded one, with the access state it marks, so no screen or loader can reach the raw client', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
      String(input).endsWith('/api/v1/server')
        ? new Response(JSON.stringify({ labels: { matter: 'Matter', matters: 'Matters' } }), { headers: { 'Content-Type': 'application/json' } })
        : new Promise<Response>(() => {}),
    )
    root = document.body.appendChild(document.createElement('div'))
    await act(async () => {
      unmount = await start(root!, true)
    })

    expect(seen.guarded).toHaveLength(1)
    expect(seen.props.length).toBeGreaterThan(0)
    for (const props of seen.props) {
      expect(props.api).toBe(seen.guarded[0]!.api)
      expect(props.access).toBe(seen.guarded[0]!.access)
    }
  })
})
