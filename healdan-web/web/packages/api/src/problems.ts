// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/** An error answer from the API: an RFC 9457 problem, or a stand-in for an answer that wasn't one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly body: unknown,
  ) {
    super(detail || `HTTP ${status}`)
    this.name = 'ApiError'
  }
}

/** Reads a failed response into an ApiError; anything but problem+json gets the code `server-error`. */
export async function readProblem(response: Response): Promise<ApiError> {
  const type = response.headers.get('Content-Type') ?? ''
  let body: unknown = undefined
  try {
    body = type.includes('json') ? await response.json() : await response.text()
  } catch {
    body = undefined
  }
  if (type.includes('problem+json') && body !== null && typeof body === 'object') {
    const problem = body as { code?: unknown; detail?: unknown; title?: unknown }
    const code = typeof problem.code === 'string' ? problem.code : 'server-error'
    const detail =
      typeof problem.detail === 'string' ? problem.detail : typeof problem.title === 'string' ? problem.title : ''
    return new ApiError(response.status, code, detail, body)
  }
  return new ApiError(response.status, 'server-error', response.statusText, body)
}
