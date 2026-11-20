// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, useLayoutEffect, useRef, useState, type DragEvent, type FocusEvent, type KeyboardEvent, type RefObject } from 'react'
import { Checkbox, CheckboxGroup, Heading, Label, Text } from 'react-aria-components'
import { announcePolitely } from '../announcer'
import { Icon } from '../icons'
import { t } from '../strings'
import { Button } from './Button'
import styles from './ColumnsPanel.module.css'

/** What a drag of a shown column carries: a type of its own, which no list, row or folder takes */
const PANEL_COLUMN_TYPE = 'application/x-healdan-panel-column'

export interface ColumnsPanelProps {
  /** "For Recent Documents" */
  forLabel: string
  /** Every column the list offers, in spec §4.7's order */
  offered: { id: string; name: string }[]
  /** The shown columns' ids, in their order: Title's first, then only ids `offered` has (any other isn't drawn) */
  shown: string[]
  onShow: (id: string) => void
  onHide: (id: string) => void
  /** Moves a shown column to the index it ends at among the shown ones, never 0 (Title's) */
  onMove: (id: string, to: number) => void
  onReset: () => void
}

// The keys that move through the columns, and where each goes from the column at `from` of `count`
const STEPS: Record<string, (from: number, count: number) => number> = {
  ArrowDown: (from) => from + 1,
  ArrowRight: (from) => from + 1,
  ArrowUp: (from) => from - 1,
  ArrowLeft: (from) => from - 1,
  Home: () => 0,
  End: (_, count) => count - 1,
}

/** A column's tick box in the panel */
function checkboxOf(panel: HTMLElement | null, id: string | undefined): HTMLInputElement | undefined {
  const row = [...(panel?.querySelectorAll<HTMLElement>('[data-column]') ?? [])].find((element) => element.dataset.column === id)
  return row?.querySelector('input') ?? undefined
}

function focusOn(element: HTMLElement | undefined | null) {
  if (element && element.ownerDocument.activeElement !== element) element.focus()
}

/**
 * The content of a list's Columns popover (spec §4.5), a dialog's: its title names the dialog, and the line under it, the
 * list it's for, describes it as the `description` slot wherever the dialog gives that slot an id, as Table's does. Shown,
 * the shown columns in their order, Title first and always shown, then More Columns, the rest the list offers, in its
 * order. A tick shows a column at the end of Shown and an untick hides it, at once: the caller keeps the columns, and the
 * panel draws what it is given. Move Up and Move Down move the column the keys are on (or were last on); a shown column
 * but Title also drags within Shown, by the browser's own drag and drop, so React Aria's isn't loaded for it.
 *
 * The columns are two groups of tick boxes, one stop for Tab across both: the column last focused, Title at first.
 * The arrow keys, Home and End move through both groups, and Space ticks. A tick box rather than a list box's options, as
 * ticking is what the panel asks of a person, and Title's stays focusable, read-only, saying it is Always Shown.
 */
export function ColumnsPanel({ forLabel, offered, shown, onShow, onHide, onMove, onReset }: ColumnsPanelProps) {
  const ids = useId()
  const names = new Map(offered.map((column) => [column.id, column.name]))
  const nameOf = (id: string) => names.get(id) ?? id
  const title = shown[0]
  // The shown columns drawn: Title, always, and the others the list offers. Every index the panel reports is in these.
  const listed = shown.filter((id, index) => index === 0 || names.has(id))
  const more = offered.filter((column) => !listed.includes(column.id))
  /** A shown column and its place among them: "Version, 3 of 4" */
  const place = (id: string, index: number) => t('columns.place', { column: nameOf(id), n: index + 1, total: listed.length })

  // The column the keys are on, or were last on: what Move Up and Move Down move, and the list's stop for Tab
  const [current, setCurrent] = useState<string>()
  const stop = current !== undefined && names.has(current) ? current : title
  const at = current === undefined ? -1 : listed.indexOf(current)
  const canMoveUp = at >= 2
  const canMoveDown = at >= 1 && at < listed.length - 1
  // What Move Up and Move Down would move, which they are described by: the list's own focus needn't be where a screen
  // reader's cursor last read
  const moving = current !== undefined && at >= 1 ? place(current, at) : undefined

  const panel = useRef<HTMLDivElement>(null)
  const up = useRef<HTMLButtonElement>(null)
  const down = useRef<HTMLButtonElement>(null)
  // Where the focus goes once the caller's new columns are drawn: a column ticked or unticked is drawn afresh in the other
  // section, and the focus goes with it; a Move button that can move its column no further gives the focus back to the
  // column, which says its new place, so it isn't left on a button that can't be pressed (and the other button's press
  // with the same key doesn't undo the move)
  const following = useRef<{ id: string; shown: boolean }>(undefined)
  const backToColumn = useRef<RefObject<HTMLButtonElement | null>>(undefined)
  useLayoutEffect(() => {
    const column = following.current
    if (column && listed.includes(column.id) === column.shown) {
      following.current = undefined
      focusOn(checkboxOf(panel.current, column.id))
    }
    if (backToColumn.current?.current?.disabled) {
      backToColumn.current = undefined
      focusOn(checkboxOf(panel.current, current))
    }
  })

  // Once the focus has moved on, nothing waits to take it
  function onFocus(event: FocusEvent<HTMLDivElement>) {
    const focused: EventTarget = event.target
    if (following.current && focused !== checkboxOf(panel.current, following.current.id)) following.current = undefined
    if (backToColumn.current && focused !== backToColumn.current.current) backToColumn.current = undefined
  }

  function show(id: string) {
    following.current = { id, shown: true }
    onShow(id)
  }

  function hide(id: string) {
    if (id === title) return
    following.current = { id, shown: false }
    onHide(id)
  }

  // The move is said once it's made, as nothing on the button changes but its description
  function move(button: RefObject<HTMLButtonElement | null>, to: number) {
    if (current === undefined) return
    if (button.current && button.current.ownerDocument.activeElement === button.current) backToColumn.current = button
    onMove(current, to)
    announcePolitely(place(current, to))
  }

  // Only the keys that move between the columns: each tick box keeps its own
  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const step = STEPS[event.key]
    if (!step || event.altKey || event.ctrlKey || event.metaKey) return
    const boxes = [...event.currentTarget.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')]
    const from = boxes.indexOf(event.target as HTMLInputElement)
    if (from < 0) return
    event.preventDefault()
    boxes[Math.min(Math.max(step(from, boxes.length), 0), boxes.length - 1)]?.focus()
  }

  // The drag: the column being dragged, and the gap the drop line is drawn in (before listed[line], or after the last
  // when it is listed.length). Gap 0, above Title, is never one.
  const dragging = useRef<string>(undefined)
  const [line, setLine] = useState<number>()
  // How many elements of Shown the drag is inside: dragenter and dragleave come in pairs for each element it crosses, and
  // WebKit may name none as dragleave's relatedTarget, which is why they are counted (as the Tree does)
  const inside = useRef(0)
  const ours = (event: DragEvent) => dragging.current !== undefined && Array.from(event.dataTransfer.types).includes(PANEL_COLUMN_TYPE)

  /** The gap between Shown's rows that the pointer is nearest, never above Title */
  function gapAt(event: DragEvent<HTMLElement>): number {
    const rows = [...event.currentTarget.querySelectorAll<HTMLElement>('[data-column]')]
    const below = rows.findIndex((row) => {
      const box = row.getBoundingClientRect()
      return event.clientY < box.top + box.height / 2
    })
    return Math.max(1, below < 0 ? rows.length : below)
  }

  /** Where a drop in `gap` moves the dragged column to, or nothing when it would stay where it is */
  function destination(gap: number): number | undefined {
    const from = dragging.current === undefined ? -1 : listed.indexOf(dragging.current)
    if (from < 1 || gap === from || gap === from + 1) return undefined
    return gap > from ? gap - 1 : gap
  }

  function endDrag() {
    dragging.current = undefined
    inside.current = 0
    setLine(undefined)
  }

  // Every drag that starts in the panel is a shown column's, or is cancelled (a column's name dragged as text), and none
  // reaches the list or the tree the panel is drawn over, whose own drag would take it. Each starts afresh, whatever an
  // earlier one left: a row drawn away mid-drag sends no dragleave or dragend.
  function onDragStart(event: DragEvent<HTMLDivElement>) {
    event.stopPropagation()
    endDrag()
    const id = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-column]')?.dataset.column : undefined
    if (id === undefined || id === title || !listed.includes(id)) return event.preventDefault()
    dragging.current = id
    event.dataTransfer.setData(PANEL_COLUMN_TYPE, id)
    event.dataTransfer.effectAllowed = 'move'
  }

  // Drops are taken only within Shown, and only of the panel's own drag
  const drops = {
    onDragEnter: (event: DragEvent<HTMLDivElement>) => {
      if (ours(event)) inside.current++
    },
    onDragOver: (event: DragEvent<HTMLDivElement>) => {
      if (!ours(event)) return
      // Without a cancelled dragover the browser won't drop here
      event.preventDefault()
      event.stopPropagation()
      event.dataTransfer.dropEffect = 'move'
      const gap = gapAt(event)
      setLine(destination(gap) === undefined ? undefined : gap)
    },
    onDragLeave: () => {
      // Only a drag that entered was counted in, so its leaving is counted out
      if (inside.current === 0) return
      inside.current--
      if (inside.current === 0) setLine(undefined)
    },
    onDrop: (event: DragEvent<HTMLDivElement>) => {
      if (!ours(event)) return
      event.preventDefault()
      event.stopPropagation()
      const id = dragging.current
      const to = destination(gapAt(event))
      endDrag()
      if (id !== undefined && to !== undefined) onMove(id, to)
    },
  }

  const dropOf = (index: number) => (line === index ? 'before' : line === listed.length && index === listed.length - 1 ? 'after' : undefined)

  return (
    <div ref={panel} className={styles.panel} onFocus={onFocus} onDragStart={onDragStart} onDragEnd={endDrag}>
      <div className={styles.heading}>
        <Heading slot="title" className={styles.title}>
          {t('columns.title')}
        </Heading>
        <Text slot="description" elementType="p" className={styles.for}>
          {forLabel}
        </Text>
      </div>
      {/* The handler only reads the keys that move between the tick boxes inside; the wrapper isn't a control of its own. */}
      <div className={styles.columns} onKeyDown={onKeyDown}>
        <CheckboxGroup
          className={styles.section}
          value={listed}
          onChange={(next) => {
            const gone = listed.find((id) => !next.includes(id))
            if (gone !== undefined) hide(gone)
          }}
        >
          <Label className={styles.legend}>{t('columns.shown')}</Label>
          <div className={styles.rows} {...drops}>
            {listed.map((id, index) => {
              const always = id === title
              return (
                <Checkbox
                  key={id}
                  value={id}
                  aria-label={nameOf(id)}
                  aria-describedby={`${ids}-${id}`}
                  isReadOnly={always}
                  excludeFromTabOrder={id !== stop}
                  onFocus={() => setCurrent(id)}
                  className={styles.row}
                  data-column={id}
                  data-drop={dropOf(index)}
                  render={always ? undefined : (props) => <label {...props} draggable />}
                >
                  <span className={styles.box} aria-hidden>
                    <Icon name="check" size={12} />
                  </span>
                  <span className={styles.name}>{nameOf(id)}</span>
                  {always ? (
                    <span id={`${ids}-${id}`} className={styles.always}>
                      {t('columns.alwaysShown')}
                    </span>
                  ) : (
                    <span id={`${ids}-${id}`} hidden>
                      {t('columns.position', { n: index + 1, total: listed.length })}
                    </span>
                  )}
                </Checkbox>
              )
            })}
          </div>
        </CheckboxGroup>
        {more.length > 0 && (
          <CheckboxGroup
            className={styles.section}
            value={[]}
            onChange={(next) => {
              const added = next[0]
              if (added !== undefined) show(added)
            }}
          >
            <Label className={styles.legend}>{t('columns.more')}</Label>
            <div className={styles.rows}>
              {more.map((column) => (
                <Checkbox
                  key={column.id}
                  value={column.id}
                  aria-label={column.name}
                  excludeFromTabOrder={column.id !== stop}
                  onFocus={() => setCurrent(column.id)}
                  className={styles.row}
                  data-column={column.id}
                >
                  <span className={styles.box} aria-hidden>
                    <Icon name="check" size={12} />
                  </span>
                  <span className={styles.name}>{column.name}</span>
                </Checkbox>
              ))}
            </div>
          </CheckboxGroup>
        )}
      </div>
      <div className={styles.order}>
        {moving && (
          <span id={`${ids}-moving`} hidden>
            {moving}
          </span>
        )}
        <Button ref={up} isDisabled={!canMoveUp} aria-describedby={moving && `${ids}-moving`} onPress={() => move(up, at - 1)}>
          {t('columns.moveUp')}
        </Button>
        <Button ref={down} isDisabled={!canMoveDown} aria-describedby={moving && `${ids}-moving`} onPress={() => move(down, at + 1)}>
          {t('columns.moveDown')}
        </Button>
      </div>
      <div className={styles.foot}>
        <Button onPress={onReset}>{t('columns.resetToDefault')}</Button>
      </div>
    </div>
  )
}
