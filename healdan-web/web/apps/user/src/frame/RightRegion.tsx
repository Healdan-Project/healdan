// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { t } from '@healdan/ui'
import { useSelection } from '../selection'
import { useNarrow } from './narrow'
import styles from './RightRegion.module.css'

/** What the right region shows (spec sections 6.1, 6.2 and 6.4) */
export type RegionKind = 'filters' | 'details' | 'preview'

export interface RightRegion {
  /** Null when nothing is selected and neither Filters nor the reading pane is open: the region folds away. */
  show: RegionKind | null
  /** Whether the reading pane is docked, though Filters may be shown over it for now */
  docked: boolean
  /**
   * Opens Filters or docks the reading pane; 'details' shows what a click on a row shows (the pane if docked). In a narrow
   * window the region is folded away and this opens it over the list, unless `reveal` is false (a click on a row isn't a
   * request to see the region).
   */
  open: (kind: RegionKind, options?: { reveal?: boolean }) => void
  /** Closes what is shown: Filters, else the reading pane, which brings the details back */
  close: () => void
  /** A narrow window: the region is folded away behind its button, and shows over the list while `narrowOpen` */
  narrow: boolean
  narrowOpen: boolean
  setNarrowOpen: (open: boolean) => void
}

/** The top bar's button for the region: where the focus goes back to when Esc closes it */
export const REGION_BUTTON = 'data-region-button'

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
  const narrow = useNarrow()
  const [narrowOpen, setNarrowOpen] = useState(false)

  // Esc closes the region shown over the list, and the focus goes back to the button that opened it. A popup inside the region
  // that takes Esc for itself stops the event first.
  useEffect(() => {
    if (!narrow || !narrowOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      setNarrowOpen(false)
      document.querySelector<HTMLElement>(`[${REGION_BUTTON}]`)?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [narrow, narrowOpen])

  const value = useMemo<RightRegion>(
    () => ({
      narrow,
      narrowOpen,
      setNarrowOpen,
      show: filters ? 'filters' : docked ? 'preview' : selected === undefined ? null : 'details',
      docked,
      open: (kind, { reveal = true } = {}) => {
        setFilters(kind === 'filters')
        if (kind === 'preview') setDocked(true)
        if (reveal) setNarrowOpen(true)
      },
      close: () => {
        if (filters) setFilters(false)
        else setDocked(false)
      },
    }),
    [filters, docked, selected, narrow, narrowOpen],
  )
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function useRightRegion(): RightRegion {
  const region = useContext(Context)
  if (!region) throw new Error('useRightRegion needs a RightRegionProvider')
  return region
}

/** What a view offers the region: each is whether the view has that to show */
export interface RegionOffers {
  filters?: boolean
  details?: boolean
  preview?: boolean
}

/**
 * What the region draws for a view: what was asked for, or, when the view offers no such thing, the details (when it
 * has them); none when there's nothing to draw. One decision, for the region itself and for a list that narrows when it
 * sits beside the reading pane, so a list never narrows for a pane that isn't there.
 */
export function regionKind(show: RegionKind | null, offers: RegionOffers): RegionKind | null {
  return show && offers[show] ? show : show && offers.details ? 'details' : null
}

/** What the region draws for a view that offers these (see {@link regionKind}) */
export function useRegionKind(offers: RegionOffers): RegionKind | null {
  const region = useRightRegion()
  // Folded away, it takes no width from the list (it shows over it while it's open)
  return region.narrow ? null : regionKind(region.show, offers)
}

export interface RightRegionProps {
  filters?: ReactNode
  details?: ReactNode
  /** The docked reading pane */
  preview?: ReactNode
}

/** The region itself, beside a list. It isn't there at all when it has nothing to show, so the list takes its width. */
export function RightRegionView({ filters, details, preview }: RightRegionProps) {
  const content = { filters, details, preview }
  const region = useRightRegion()
  const kind = regionKind(region.show, { filters: !!filters, details: !!details, preview: !!preview })
  if (region.narrow) {
    // Folded away behind the top bar's button. Opened, it covers the list, and says what it's for when it has nothing.
    if (!region.narrowOpen) return null
    return (
      <section className={`${styles.region} ${styles.over} ${kind === 'preview' ? styles.wide : ''}`} aria-label={kind ? labels[kind]() : t('frame.side')}>
        {kind ? content[kind] : <p className={styles.empty}>{t('frame.sideEmpty')}</p>}
      </section>
    )
  }
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
