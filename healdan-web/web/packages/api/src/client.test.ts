// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiPath, createApi } from './index'

const fetchMock = vi.fn()

function json(body: unknown, status = 200, type = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } })
}

function headersOf(call: number): Headers {
  const init = fetchMock.mock.calls[call]?.[1] as RequestInit
  return new Headers(init.headers)
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe('createApi', () => {
  it('gets from /api with the session and no client header', async () => {
    fetchMock.mockResolvedValue(json({ id: 1 }))
    const result = await createApi().get('/v1/me')
    expect(result).toEqual({ id: 1 })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/me')
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(init.credentials).toBe('same-origin')
    expect(init.method ?? 'GET').toBe('GET')
    expect(headersOf(0).has('X-Healdan-Client')).toBe(false)
  })

  it('hands a signal to fetch, so a request nobody waits for any more can be cancelled', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(json({ matters: [], documents: [] })))
    const controller = new AbortController()
    await createApi().get('/v1/suggest', { q: 'ch' }, { signal: controller.signal })
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).signal).toBe(controller.signal)
    await createApi().get('/v1/me')
    expect((fetchMock.mock.calls[1]?.[1] as RequestInit).signal).toBeUndefined()
  })

  it('sends JSON with the client header', async () => {
    fetchMock.mockResolvedValue(json({ ok: true }))
    await createApi().send('POST', '/v1/me/favorites', { folderId: 5 })
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('same-origin')
    expect(init.body).toBe('{"folderId":5}')
    expect(headersOf(0).get('X-Healdan-Client')).toBe('web')
    expect(headersOf(0).get('Content-Type')).toBe('application/json')
  })

  it('sends FormData without a Content-Type of its own', async () => {
    fetchMock.mockResolvedValue(json({}))
    const form = new FormData()
    form.append('file', new Blob(['x']), 'a.txt')
    await createApi().send('POST', '/v1/documents', form)
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(init.body).toBe(form)
    expect(headersOf(0).has('Content-Type')).toBe(false)
    expect(headersOf(0).get('X-Healdan-Client')).toBe('web')
  })

  it('repeats array names and leaves undefined out', async () => {
    fetchMock.mockResolvedValue(json([]))
    await createApi().get('/v1/search', { q: 'a b', type: ['pdf', 'docx'], skip: undefined, max: 5 })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/search?q=a+b&type=pdf&type=docx&max=5')
  })

  it('throws ApiError from a problem+json answer', async () => {
    fetchMock.mockResolvedValue(
      json({ type: 'x', title: 'Gone', status: 404, detail: 'No such folder', code: 'not-found' }, 404, 'application/problem+json'),
    )
    const error = await createApi().get('/v1/folders/9').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 404, code: 'not-found', detail: 'No such folder' })
  })

  it('throws server-error for a non-JSON failure', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } }))
    const error = await createApi().get('/v1/me').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 502, code: 'server-error' })
  })

  it('calls onSignedOut once for a burst of 401s, and again after a success', async () => {
    const onSignedOut = vi.fn()
    const api = createApi({ onSignedOut })
    const problem = () => json({ status: 401, code: 'unauthorized', detail: 'Sign in' }, 401, 'application/problem+json')
    fetchMock.mockImplementation(() => Promise.resolve(problem()))
    const results = await Promise.allSettled([api.get('/v1/a'), api.get('/v1/b'), api.get('/v1/c')])
    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    expect(onSignedOut).toHaveBeenCalledTimes(1)
    fetchMock.mockImplementation(() => Promise.resolve(json({})))
    await api.get('/v1/d')
    fetchMock.mockImplementation(() => Promise.resolve(problem()))
    await api.get('/v1/e').catch(() => undefined)
    expect(onSignedOut).toHaveBeenCalledTimes(2)
  })

  it('resolves undefined for 204', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    expect(await createApi().send('DELETE', '/v1/me/favorites/5')).toBeUndefined()
  })

  it('builds URLs for links and images', () => {
    expect(createApi().url('/v1/documents/1/content', { version: '1.0' })).toBe('/api/v1/documents/1/content?version=1.0')
    expect(createApi().url('/v1/me')).toBe('/api/v1/me')
    expect(createApi({ base: '/x' }).url('/v1/me')).toBe('/x/v1/me')
  })
})

describe('apiPath', () => {
  it('encodes each value it puts in a path, so data can never add a segment or a query', () => {
    expect(apiPath`/v1/folders/${7}/children`).toBe('/v1/folders/7/children')
    expect(apiPath`/v1/folders/${'1/../2?x=1#y'}/children`).toBe('/v1/folders/1%2F..%2F2%3Fx%3D1%23y/children')
    expect(apiPath`/v1/me/matters/${110}`).toBe('/v1/me/matters/110')
  })
})
