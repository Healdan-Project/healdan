// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { readProblem } from './problems'

type Query = Record<string, string | number | string[] | undefined>

export interface Api {
  get<T>(path: string, query?: Query): Promise<T>
  send<T>(method: 'POST' | 'PATCH' | 'PUT' | 'DELETE', path: string, body?: unknown | FormData): Promise<T>
  /** A URL for links, `<img>` and frames, which can't send headers. */
  url(path: string, query?: Record<string, string | number | undefined>): string
}

export interface ApiOptions {
  base?: string
  /** Called on every 401, from reads and changes alike; the app's signed-out state takes the first. */
  onSignedOut?: () => void
}

function queryString(query?: Query): string {
  const params = new URLSearchParams()
  for (const [name, value] of Object.entries(query ?? {})) {
    if (value === undefined) continue
    for (const one of Array.isArray(value) ? value : [value]) params.append(name, String(one))
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}

export function createApi(options: ApiOptions = {}): Api {
  const base = options.base ?? '/api'

  async function request<T>(method: string, path: string, query?: Query, body?: unknown): Promise<T> {
    const headers = new Headers()
    const init: RequestInit = { method, credentials: 'same-origin', headers }
    if (method !== 'GET') headers.set('X-Healdan-Client', 'web')
    if (body instanceof FormData) {
      init.body = body // the browser sets the multipart boundary
    } else if (body !== undefined) {
      headers.set('Content-Type', 'application/json')
      init.body = JSON.stringify(body)
    }
    const response = await fetch(`${base}${path}${queryString(query)}`, init)
    if (!response.ok) {
      if (response.status === 401) options.onSignedOut?.()
      throw await readProblem(response)
    }
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  return {
    get: (path, query) => request('GET', path, query),
    send: (method, path, body) => request(method, path, undefined, body),
    url: (path, query) => `${base}${path}${queryString(query)}`,
  }
}
