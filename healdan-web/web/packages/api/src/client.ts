// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, readProblem } from './problems'

type Query = Record<string, string | number | string[] | undefined>

export interface Api {
  /** `signal` cancels the request, as when the answer is no longer wanted. */
  get<T>(path: string, query?: Query, options?: RequestOptions): Promise<T>
  send<T>(method: 'POST' | 'PATCH' | 'PUT' | 'DELETE', path: string, body?: unknown | FormData): Promise<T>
  /**
   * The response itself, for a binary answer (a PDF, a picture) or one that is read by its status and headers (a 202
   * with Retry-After). A failure is thrown as for `get`: a 401 reports a sign-out, and a problem is an ApiError.
   */
  getResponse(path: string, query?: Query, options?: RequestOptions): Promise<Response>
  /** A URL for links, `<img>` and frames, which can't send headers. */
  url(path: string, query?: Record<string, string | number | undefined>): string
}

export interface RequestOptions {
  signal?: AbortSignal
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

/**
 * A path with each value encoded as one segment, so an id from data can never add a segment or a query:
 * apiPath`/v1/folders/${id}/children`.
 */
export function apiPath(parts: TemplateStringsArray, ...values: (string | number)[]): string {
  return parts.reduce((path, part, i) => path + part + (i < values.length ? encodeURIComponent(String(values[i])) : ''), '')
}

export function createApi(options: ApiOptions = {}): Api {
  const base = options.base ?? '/api'

  async function fetchOk(method: string, path: string, query?: Query, body?: unknown, signal?: AbortSignal): Promise<Response> {
    const headers = new Headers()
    const init: RequestInit = { method, credentials: 'same-origin', headers, signal }
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
    return response
  }

  async function request<T>(method: string, path: string, query?: Query, body?: unknown, signal?: AbortSignal): Promise<T> {
    const response = await fetchOk(method, path, query, body, signal)
    // No body to read: a 204, or a 202 that only says to wait
    const text = response.status === 204 ? '' : await response.text()
    if (text === '') return undefined as T
    try {
      return JSON.parse(text) as T
    } catch {
      // Something that isn't the API answered, such as a proxy's page
      throw new ApiError(response.status, 'server-error', response.statusText, text)
    }
  }

  return {
    get: (path, query, options) => request('GET', path, query, undefined, options?.signal),
    send: (method, path, body) => request(method, path, undefined, body),
    getResponse: (path, query, options) => fetchOk('GET', path, query, undefined, options?.signal),
    url: (path, query) => `${base}${path}${queryString(query)}`,
  }
}
