// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'

/** The details panel's tabs (changed after M6: the owner's dogfooding punch list, 2026-11-14) */
export type DetailsTab = 'properties' | 'versions'

interface State {
  tab: DetailsTab
  setTab: (tab: DetailsTab) => void
}

const Context = createContext<State | null>(null)

/**
 * Which tab the details show, as the Healdan Helper's Open window keeps it: as the person left it while they select other
 * documents, each of which has a panel of its own, and Properties again on a new page load. It's in the frame, which the
 * views share, so it outlives each panel.
 */
export function DetailsTabProvider({ children }: { children: ReactNode }) {
  const [tab, setTab] = useState<DetailsTab>('properties')
  const value = useMemo(() => ({ tab, setTab }), [tab])
  return <Context.Provider value={value}>{children}</Context.Provider>
}

/** The details' tab, and a way to change it; a panel shown outside the frame keeps its own. */
export function useDetailsTab(): State {
  const shared = useContext(Context)
  const [tab, setTab] = useState<DetailsTab>('properties')
  return shared ?? { tab, setTab }
}
