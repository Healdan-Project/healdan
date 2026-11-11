// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { t } from '@healdan/ui'
import { useSelection } from '../selection'
import styles from './RightRegion.module.css'

/** What the right region shows (spec sections 6.1, 6.2 and 6.4) */
export type RegionKind = 'filters' | 'details' | 'preview'

export interface RightRegion {
  /** Null when nothing is selected and neither Filters nor the reading pane is open: the region folds away. */
  show: RegionKind | null
  /** Whether the reading pane is docked, though Filters may be shown over it for now */
  docked: boolean
  /** Opens Filters or docks the reading pane; 'details' shows what a click on a row shows (the pane if docked) */
  open: (kind: RegionKind) => void
  /** Closes what is shown: Filters, else the reading pane, which brings the details back */
  close: () => void
}

const Context = createContext<RightRegion | null>(null)

/**
 * One region that shows one of Filters, the selected document's details or the docked reading pane. Filters, when asked
 * for, come first, until the next click on a row. Then the reading pane, once docked, for as long as it's open (it
 * previews each row you click or arrow to). Last, the details, shown for as long as a document is selected; so closing
 * the reading pane brings them back.
 */
export function RightRegionProvider({ children }: { children: ReactNode }) {
  const { selected } = useSelection()
  const [filters, setFilters] = useState(false)
  const [docked, setDocked] = useState(false)
  const value = useMemo<RightRegion>(
    () => ({
      show: filters ? 'filters' : docked ? 'preview' : selected === undefined ? null : 'details',
      docked,
      open: (kind) => {
        setFilters(kind === 'filters')
        if (kind === 'preview') setDocked(true)
      },
      close: () => {
        if (filters) setFilters(false)
        else setDocked(false)
      },
    }),
    [filters, docked, selected],
  )
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function useRightRegion(): RightRegion {
  const region = useContext(Context)
  if (!region) throw new Error('useRightRegion needs a RightRegionProvider')
  return region
}

export interface RightRegionProps {
  filters?: ReactNode
  details?: ReactNode
  /** The docked reading pane */
  preview?: ReactNode
}

/** The region itself, beside a list. It isn't there at all when it has nothing to show, so the list takes its width. */
export function RightRegionView({ filters, details, preview }: RightRegionProps) {
  const { show } = useRightRegion()
  const content = { filters, details, preview }
  // What was asked for may not be something this view offers; the details (when a document is selected) are the default.
  const kind: RegionKind | null = show && content[show] ? show : show && details ? 'details' : null
  if (!kind) return null
  return (
    <section className={`${styles.region} ${kind === 'preview' ? styles.wide : ''}`} aria-label={labels[kind]()}>
      {content[kind]}
    </section>
  )
}

const labels: Record<RegionKind, () => string> = {
  filters: () => t('filters.region'),
  details: () => t('details.title'),
  preview: () => t('preview.region'),
}
