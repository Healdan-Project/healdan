// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@healdan/api'
import { createSignedOut } from '@healdan/shell'
import { createAccess, guardApi } from './access'
import { fakeApi, problem } from './test/fakeApi'

function guarded(routes: Parameters<typeof fakeApi>[0] = {}) {
  const signedOut = createSignedOut()
  const api = fakeApi(routes, signedOut.mark)
  const access = createAccess()
  return { api, access, signedOut, guarded: guardApi(api, access, signedOut) }
}

describe('The Admin app\'s calls', () => {
  it('reach the server and bring back its answers while the person is an administrator', async () => {
    const { api, access, guarded: client } = guarded({ 'POST /v1/admin/x': { done: true } })
    expect(await client.get('/v1/me')).toMatchObject({ username: 'admin' })
    expect(await client.send('POST', '/v1/admin/x', { a: 1 })).toEqual({ done: true })
    expect(api.calls.map((call) => `${call.method} ${call.path}`)).toEqual(['GET /v1/me', 'POST /v1/admin/x'])
    expect(access.refused()).toBe(false)
  })

  it('stop at the first 403 admins-only: the app is told, and no later call of any kind reaches the server', async () => {
    const { api, access, guarded: client } = guarded({ 'GET /v1/admin/dashboard': problem(403, 'admins-only') })
    const told = vi.fn()
    access.subscribe(told)

    await expect(client.get('/v1/admin/dashboard')).rejects.toMatchObject({ status: 403, code: 'admins-only' })
    expect(access.refused()).toBe(true)
    expect(told).toHaveBeenCalledTimes(1)

    const sent = api.calls.length
    await expect(client.get('/v1/me')).rejects.toMatchObject({ status: 403, code: 'admins-only' })
    await expect(client.send('POST', '/v1/admin/x', {})).rejects.toBeInstanceOf(ApiError)
    await expect(client.getResponse('/v1/documents/1/preview')).rejects.toBeInstanceOf(ApiError)
    expect(api.calls).toHaveLength(sent)
    // Told once, however many calls it refuses
    expect(told).toHaveBeenCalledTimes(1)
  })

  it('pass any other refusal or failure through as it is, and go on', async () => {
    const { api, access, guarded: client } = guarded({
      'GET /v1/admin/dashboard': problem(403, 'forbidden'),
      'GET /v1/admin/dashboard/usage': problem(500, 'server-error'),
    })
    await expect(client.get('/v1/admin/dashboard')).rejects.toMatchObject({ status: 403, code: 'forbidden' })
    await expect(client.get('/v1/admin/dashboard/usage')).rejects.toMatchObject({ status: 500 })
    await expect(client.get('/v1/admin/nowhere')).rejects.toMatchObject({ status: 404 })
    expect(access.refused()).toBe(false)
    expect(await client.get('/v1/me')).toMatchObject({ admin: true })
    expect(api.calls).toHaveLength(4)
  })

  it('stop once the person is signed out (a 401), so nothing more is asked for', async () => {
    const { api, signedOut, guarded: client } = guarded({ 'GET /v1/admin/dashboard': problem(401, 'unauthenticated') })
    await expect(client.get('/v1/admin/dashboard')).rejects.toMatchObject({ status: 401 })
    expect(signedOut.get()).toBe(true)
    const sent = api.calls.length
    await expect(client.get('/v1/admin/dashboard/usage')).rejects.toMatchObject({ status: 401, code: 'unauthenticated' })
    expect(api.calls).toHaveLength(sent)
  })

  it('make addresses as the API does, refused or not', () => {
    const { access, guarded: client } = guarded()
    expect(client.url('/v1/documents/1/preview')).toBe('/api/v1/documents/1/preview')
    access.mark()
    expect(client.url('/v1/documents/1/preview', { v: 2 })).toBe('/api/v1/documents/1/preview?v=2')
  })
})
