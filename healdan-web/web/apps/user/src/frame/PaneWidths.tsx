// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useMemo, useRef, useState, useSyncExternalStore, type CSSProperties, type ReactNode } from 'react'
import { Splitter } from '@healdan/ui'
import { forget, keepNumber, readNumber } from '../preferences'
import { useNarrow } from './narrow'
import { layoutFor, type Chosen, type PaneLayout, type PaneName } from './paneLayout'

/** What the frame's dividers work with: the panes' widths now, and how a divider changes one */
interface PaneWidths {
  /** A wide window: the panes beside the list have dividers. In a narrow one they fold away, and their widths are their own. */
  resizable: boolean
  layout: PaneLayout
  /** A drag has moved a divider: the pane is drawn at the width, with nothing rendered again */
  resize: (name: PaneName, width: number) => void
  /** A drag has ended, or a key has moved the divider: the width is kept, in this browser too */
  keep: (name: PaneName, width: number) => void
  /** Back to the pane's default, which the browser then forgets */
  reset: (name: PaneName) => void
}

/** The preference each pane's width is kept under (preferences.ts adds the app's prefix) */
const KEYS: Record<PaneName, string> = { left: 'pane.left', right: 'pane.right', preview: 'pane.preview' }
const NAMES: PaneName[] = ['left', 'right', 'preview']

/** The custom properties the frame's CSS reads each pane's width from; without them each has its own fallback */
const PROPERTIES: Record<PaneName, string> = { left: '--left-width', right: '--right-width', preview: '--preview-width' }

function remembered(): Chosen {
  const chosen: Chosen = {}
  for (const name of NAMES) {
    const width = readNumber(KEYS[name])
    if (width !== undefined) chosen[name] = width
  }
  return chosen
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener('resize', onChange)
  return () => window.removeEventListener('resize', onChange)
}

/**
 * The width the page is laid out in, as it changes: the layout viewport's, which the frame's grid has. Not innerWidth, which
 * mobile Safari gives as the visual viewport's, smaller while the page is pinch-zoomed.
 */
function useWindowWidth(): number {
  return useSyncExternalStore(subscribe, () => document.documentElement.clientWidth, () => 1280)
}

const NOT_RESIZABLE: PaneWidths = {
  resizable: false,
  layout: layoutFor(1280, {}),
  resize: () => {},
  keep: () => {},
  reset: () => {},
}

const Context = createContext<PaneWidths>(NOT_RESIZABLE)

function usePaneWidths(): PaneWidths {
  return useContext(Context)
}

function properties(layout: PaneLayout): CSSProperties {
  return Object.fromEntries(NAMES.map((name) => [PROPERTIES[name], `${layout[name]}px`])) as CSSProperties
}

/**
 * The frame's element, which holds the panes' widths as custom properties (`--left-width`, `--right-width` and
 * `--preview-width`) for the CSS of the left pane, the top bar and the right region. A drag sets them on the element as it
 * goes, so the list follows without the views being rendered again; only the drag's end changes state. A render while a drag
 * lasts (the window resized) draws what was kept, and the Splitter then reports the drag's width again. In a narrow window it
 * sets none, so the folded panes keep their own widths, and what was chosen is used again when the window widens.
 */
export function PaneFrame({ className, children }: { className: string; children: ReactNode }) {
  const narrow = useNarrow()
  const windowWidth = useWindowWidth()
  const [chosen, setChosen] = useState<Chosen>(remembered)
  const element = useRef<HTMLDivElement>(null)
  const layout = useMemo(() => layoutFor(windowWidth, chosen), [windowWidth, chosen])

  const value = useMemo<PaneWidths>(
    () => ({
      resizable: !narrow,
      layout,
      resize(name, width) {
        // The other panes too: a wider left pane can narrow the right region, down to its least, for the list to keep its room
        const drawn = layoutFor(windowWidth, { ...chosen, [name]: width })
        for (const pane of NAMES) element.current?.style.setProperty(PROPERTIES[pane], `${drawn[pane]}px`)
      },
      keep(name, width) {
        setChosen((before) => ({ ...before, [name]: width }))
        keepNumber(KEYS[name], width)
      },
      reset(name) {
        setChosen((before) => {
          const after = { ...before }
          delete after[name]
          return after
        })
        forget(KEYS[name])
      },
    }),
    [narrow, windowWidth, chosen, layout],
  )

  return (
    <Context.Provider value={value}>
      <div ref={element} className={className} style={narrow ? undefined : properties(layout)}>
        {children}
      </div>
    </Context.Provider>
  )
}

export interface PaneSplitterProps {
  name: PaneName
  /** The divider's name: "Resize" and the pane's */
  'aria-label': string
  /** The pane's id */
  controls?: string
  className?: string
  /** A box of its own to put the divider in, as one with no width at the pane's edge */
  boxClassName?: string
}

/** A pane's divider, in a wide window: none in a narrow one */
export function PaneSplitter({ name, controls, className, boxClassName, ...labels }: PaneSplitterProps) {
  const panes = usePaneWidths()
  if (!panes.resizable) return null
  const limits = name === 'left' ? panes.layout.leftLimits : panes.layout.rightLimits
  const splitter = (
    <Splitter
      aria-label={labels['aria-label']}
      pane={name === 'left' ? 'start' : 'end'}
      value={panes.layout[name]}
      minValue={limits.min}
      maxValue={limits.max}
      onResize={(width) => panes.resize(name, width)}
      onResizeEnd={(width) => panes.keep(name, width)}
      onReset={() => panes.reset(name)}
      controls={controls}
      className={className}
    />
  )
  return boxClassName ? <div className={boxClassName}>{splitter}</div> : splitter
}
