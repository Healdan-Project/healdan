// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useCallback, useContext, type ReactNode } from 'react'
import { messageFor } from './errors'

/** An app's own messages: the string for a key, or undefined so the shared catalogue answers */
type Lookup = (key: string) => string | undefined

// No provider is the shared catalogue alone, as the User app has it
const MessagesContext = createContext<Lookup | undefined>(undefined)

/** An app with problems of its own wraps its tree in this, so the shared components say them too */
export function MessagesProvider({ lookup, children }: { lookup: Lookup; children: ReactNode }) {
  return <MessagesContext.Provider value={lookup}>{children}</MessagesContext.Provider>
}

/** messageFor with the app's catalogue, for a component the apps share */
export function useMessageFor(): (error: unknown) => string {
  const lookup = useContext(MessagesContext)
  return useCallback((error: unknown) => messageFor(error, lookup), [lookup])
}
