// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminPaths, Api } from '@healdan/api'

// The few admin routes the User app calls for an administrator (admin spec 13), through its own client: the same web
// session, and the same report of a 401, as every other call. Only the admin contract's types come in, which the build
// drops, so nothing of the Admin app's code or catalogue is downloaded with the User app.

type Post<P extends keyof adminPaths> = NonNullable<adminPaths[P]['post']>

/** What the route takes as its JSON body */
export type AdminBody<P extends keyof adminPaths> = Post<P> extends { requestBody: { content: { 'application/json': infer B } } } ? B : never

/** What the route answers with the status `S` */
export type AdminAnswer<P extends keyof adminPaths, S extends number> =
  Post<P> extends { responses: { [K in S]: { content: { 'application/json': infer A } } } } ? A : never

/** POSTs to an admin route, its path, body and answer as the admin contract has them */
export function adminPost<P extends keyof adminPaths, S extends number = 200>(api: Api, path: P, body: AdminBody<P>): Promise<AdminAnswer<P, S>> {
  return api.send('POST', path, body)
}
