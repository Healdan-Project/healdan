// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useSyncExternalStore, type ReactNode } from 'react'
import { ApiError, type Api } from '@healdan/api'
import type { SignedOutState } from '@healdan/shell'

/**
 * Whether the server has said the person is no longer an administrator (`403 admins-only`), as someone taken out of
 * admin while the app is open is told at their next call (spec 6.1, Review Focus 1). One per app, as the signed-out
 * state is, and it never goes back: only a page load, which the server answers, lets them in again.
 */
export interface AccessState {
  refused(): boolean
  mark(): void
  subscribe(listener: () => void): () => void
}

export function createAccess(): AccessState {
  let refused = false
  const listeners = new Set<() => void>()
  return {
    refused: () => refused,
    mark() {
      if (refused) return
      refused = true
      for (const listener of [...listeners]) listener()
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

export function isAdminsOnly(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403 && error.code === 'admins-only'
}

// What a call gets once the app has stopped: the refusal the server gave, made here, as nothing is sent
const refusedHere = () => new ApiError(403, 'admins-only', 'Only administrators can do that.', null)
const signedOutHere = () => new ApiError(401, 'unauthenticated', 'You have been signed out.', null)

/**
 * The app's API client, which every screen's calls go through (ServicesProvider is given it): a `403 admins-only` from
 * any call marks `access`, and once that or a `401` (`signedOut`, which createApi marks) has come, no call is sent
 * again. The frame then shows the no-access panel or Signed Out in place of every screen, which stops their polls, and a
 * call already under way, or one an effect makes on its way out, fails here without reaching the server. `onChange` hears
 * of each change sent (`send`) that succeeded, as the administration record and the histories then hold more (P5-R34).
 */
export function guardApi(api: Api, access: AccessState, signedOut: SignedOutState, onChange?: () => void): Api {
  async function guarded<T>(call: () => Promise<T>): Promise<T> {
    if (access.refused()) throw refusedHere()
    if (signedOut.get()) throw signedOutHere()
    try {
      return await call()
    } catch (error) {
      if (isAdminsOnly(error)) access.mark()
      throw error
    }
  }
  return {
    get: <T,>(...args: Parameters<Api['get']>) => guarded(() => api.get<T>(...args)),
    getResponse: (...args) => guarded(() => api.getResponse(...args)),
    send: async <T,>(...args: Parameters<Api['send']>) => {
      const answer = await guarded(() => api.send<T>(...args))
      onChange?.()
      return answer
    },
    // An address for a link or a picture is no call
    url: (path, query) => api.url(path, query),
  }
}

const AccessContext = createContext<AccessState | null>(null)

export function AccessProvider({ access, children }: { access: AccessState; children: ReactNode }) {
  return <AccessContext.Provider value={access}>{children}</AccessContext.Provider>
}

function useAccess(): AccessState {
  const access = useContext(AccessContext)
  if (!access) throw new Error('AccessProvider is missing')
  return access
}

/** True once a call has been refused as `403 admins-only` */
export function useRefused(): boolean {
  const access = useAccess()
  return useSyncExternalStore(access.subscribe, access.refused)
}

/** Reports a `403 admins-only` that no call of the guarded client's saw, such as the answer to a download's navigation */
export function useReportRefused(): () => void {
  return useAccess().mark
}
