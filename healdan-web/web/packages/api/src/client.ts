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

/**
 * A path with each value encoded as one segment, so an id from data can never add a segment or a query:
 * apiPath`/v1/folders/${id}/children`.
 */
export function apiPath(parts: TemplateStringsArray, ...values: (string | number)[]): string {
  return parts.reduce((path, part, i) => path + part + (i < values.length ? encodeURIComponent(String(values[i])) : ''), '')
}

export function createApi(options: ApiOptions = {}): Api {
  const base = options.base ?? '/api'
  // A burst of 401s calls onSignedOut once: the flag is set on the first and
  // cleared by the next successful answer, so a later sign-out calls it again.
  let signedOutReported = false

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
      if (response.status === 401 && !signedOutReported) {
        signedOutReported = true
        options.onSignedOut?.()
      }
      throw await readProblem(response)
    }
    signedOutReported = false
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  return {
    get: (path, query) => request('GET', path, query),
    send: (method, path, body) => request(method, path, undefined, body),
    url: (path, query) => `${base}${path}${queryString(query)}`,
  }
}
