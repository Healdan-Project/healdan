// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { apiPath, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { adminKey } from '../queries'

type A = adminComponents['schemas']

/** The Server Log's menu: the server's logs, the main log first (`GET /v1/admin/logs`), asked again on each return to the window */
export function useLogs() {
  const api = useApi()
  return useQuery({
    queryKey: adminKey('/v1/admin/logs'),
    queryFn: ({ signal }) => api.get<A['LogInfo'][]>('/v1/admin/logs', undefined, { signal }),
    meta: ASK_ON_RETURN,
  })
}

/** Download's link: the log's files, zipped, as the server keeps them, by the log's name and never a path */
export function logFileUrl(url: (path: string) => string, name: string): string {
  return url(apiPath`/v1/admin/logs/${name}/file`)
}
