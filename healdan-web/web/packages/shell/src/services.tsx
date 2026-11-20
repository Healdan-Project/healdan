// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useSyncExternalStore, type ReactNode } from 'react'
import type { Api } from '@healdan/api'
import type { SignedOutState } from './signedOut'

interface Services {
  api: Api
  signedOut: SignedOutState
}

const ServicesContext = createContext<Services | null>(null)

export function ServicesProvider({ api, signedOut, children }: Services & { children: ReactNode }) {
  return <ServicesContext.Provider value={{ api, signedOut }}>{children}</ServicesContext.Provider>
}

function useServices(): Services {
  const value = useContext(ServicesContext)
  if (!value) throw new Error('ServicesProvider is missing')
  return value
}

export function useApi(): Api {
  return useServices().api
}

/** True once any request has answered 401. */
export function useSignedOut(): boolean {
  const { signedOut } = useServices()
  return useSyncExternalStore(signedOut.subscribe, signedOut.get)
}
