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
  open: (kind: RegionKind) => void
  close: () => void
}

const Context = createContext<RightRegion | null>(null)

/**
 * One region that shows one of Filters, the selected document's details or the docked reading pane. What was asked for
 * last decides, and details are the default, shown for as long as a document is selected; so closing what's open
 * brings them back, and the funnel brings Filters back over them.
 */
export function RightRegionProvider({ children }: { children: ReactNode }) {
  const { selected } = useSelection()
  const [asked, setAsked] = useState<'filters' | 'preview' | null>(null)
  const value = useMemo<RightRegion>(
    () => ({
      show: asked ?? (selected === undefined ? null : 'details'),
      open: (kind) => setAsked(kind === 'details' ? null : kind),
      close: () => setAsked(null),
    }),
    [asked, selected],
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
  /** Nothing yet: the reading pane comes with the preview */
  preview?: ReactNode
}

/** The region itself, beside a list. It isn't there at all when it has nothing to show, so the list takes its width. */
export function RightRegionView({ filters, details, preview }: RightRegionProps) {
  const { show } = useRightRegion()
  const content = show === 'filters' ? filters : show === 'details' ? details : preview
  if (!show || !content) return null
  return (
    <section className={styles.region} aria-label={show === 'filters' ? t('filters.region') : t('details.title')}>
      {content}
    </section>
  )
}
