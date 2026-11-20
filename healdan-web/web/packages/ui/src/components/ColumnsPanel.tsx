// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, useLayoutEffect, useRef, useState, type DragEvent, type FocusEvent, type KeyboardEvent, type RefObject } from 'react'
import { Checkbox, CheckboxGroup, Heading, Label } from 'react-aria-components'
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
  /** The shown columns' ids, in their order: Title's first */
  shown: string[]
  onShow: (id: string) => void
  onHide: (id: string) => void
  /** Moves a shown column to the index it ends at in `shown`, never 0 (Title's) */
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
 * The content of a list's Columns popover (spec §4.5), a dialog's: Shown, the shown columns in their order, Title first
 * and always shown, then More Columns, the rest the list offers, in its order. A tick shows a column at the end of Shown
 * and an untick hides it, at once: the caller keeps the columns, and the panel draws what it is given. Move Up and Move
 * Down move the column the keys are on (or were last on); a shown column but Title also drags within Shown, by the
 * browser's own drag and drop, so React Aria's isn't loaded for it.
 *
 * The columns are two groups of tick boxes, one stop for Tab across both: the column last focused, Title at first.
 * The arrow keys, Home and End move through both groups, and Space ticks. A tick box rather than a list box's options, as
 * ticking is what the panel asks of a person, and Title's stays focusable, read-only, saying it is Always Shown.
 */
export function ColumnsPanel({ forLabel, offered, shown, onShow, onHide, onMove, onReset }: ColumnsPanelProps) {
  const ids = useId()
  const title = shown[0]
  const names = new Map(offered.map((column) => [column.id, column.name]))
  const nameOf = (id: string) => names.get(id) ?? id
  const more = offered.filter((column) => !shown.includes(column.id))

  // The column the keys are on, or were last on: what Move Up and Move Down move, and the list's stop for Tab
  const [current, setCurrent] = useState<string>()
  const stop = current !== undefined && names.has(current) ? current : title
  const at = current === undefined ? -1 : shown.indexOf(current)
  const canMoveUp = at >= 2
  const canMoveDown = at >= 1 && at < shown.length - 1

  const panel = useRef<HTMLDivElement>(null)
  const up = useRef<HTMLButtonElement>(null)
  const down = useRef<HTMLButtonElement>(null)
  // Where the focus goes once the caller's new columns are drawn: a column ticked or unticked is drawn afresh in the other
  // section, and the focus goes with it; a Move button that can move its column no further gives the focus to the other,
  // or back to the column, so it isn't left on a button that can't be pressed
  const following = useRef<{ id: string; shown: boolean }>(undefined)
  const handingOver = useRef<RefObject<HTMLButtonElement | null>>(undefined)
  useLayoutEffect(() => {
    const column = following.current
    if (column && shown.includes(column.id) === column.shown) {
      following.current = undefined
      focusOn(checkboxOf(panel.current, column.id))
    }
    const pressed = handingOver.current
    if (pressed?.current?.disabled) {
      handingOver.current = undefined
      const other = (pressed === up ? down : up).current
      focusOn(other && !other.disabled ? other : checkboxOf(panel.current, current))
    }
  })

  // Once the focus has moved on, nothing waits to take it
  function onFocus(event: FocusEvent<HTMLDivElement>) {
    const focused: EventTarget = event.target
    if (following.current && focused !== checkboxOf(panel.current, following.current.id)) following.current = undefined
    if (handingOver.current && focused !== handingOver.current.current) handingOver.current = undefined
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

  function move(button: RefObject<HTMLButtonElement | null>, to: number) {
    if (current === undefined) return
    if (button.current && button.current.ownerDocument.activeElement === button.current) handingOver.current = button
    onMove(current, to)
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

  // The drag: the column being dragged, and the gap the drop line is drawn in (before shown[line], or after the last
  // when it is shown.length). Gap 0, above Title, is never one.
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
    const from = dragging.current === undefined ? -1 : shown.indexOf(dragging.current)
    if (from < 1 || gap === from || gap === from + 1) return undefined
    return gap > from ? gap - 1 : gap
  }

  function endDrag() {
    dragging.current = undefined
    inside.current = 0
    setLine(undefined)
  }

  // Every drag that starts in the panel is a shown column's, or is cancelled (a column's name dragged as text), and none
  // reaches the list or the tree the panel is drawn over, whose own drag would take it
  function onDragStart(event: DragEvent<HTMLDivElement>) {
    event.stopPropagation()
    const id = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-column]')?.dataset.column : undefined
    if (id === undefined || id === title || !shown.includes(id)) return event.preventDefault()
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

  const dropOf = (index: number) => (line === index ? 'before' : line === shown.length && index === shown.length - 1 ? 'after' : undefined)

  return (
    <div ref={panel} className={styles.panel} onFocus={onFocus} onDragStart={onDragStart} onDragEnd={endDrag}>
      <div className={styles.heading}>
        <Heading slot="title" className={styles.title}>
          {t('columns.title')}
        </Heading>
        <p className={styles.for}>{forLabel}</p>
      </div>
      {/* The handler only reads the keys that move between the tick boxes inside; the wrapper isn't a control of its own. */}
      <div className={styles.columns} onKeyDown={onKeyDown}>
        <CheckboxGroup
          className={styles.section}
          value={shown}
          onChange={(next) => {
            const gone = shown.find((id) => !next.includes(id))
            if (gone !== undefined) hide(gone)
          }}
        >
          <Label className={styles.legend}>{t('columns.shown')}</Label>
          <div className={styles.rows} {...drops}>
            {shown.map((id, index) => {
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
                      {t('columns.position', { n: index + 1, total: shown.length })}
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
        <Button ref={up} isDisabled={!canMoveUp} onPress={() => move(up, at - 1)}>
          {t('columns.moveUp')}
        </Button>
        <Button ref={down} isDisabled={!canMoveDown} onPress={() => move(down, at + 1)}>
          {t('columns.moveDown')}
        </Button>
      </div>
      <div className={styles.foot}>
        <Button onPress={onReset}>{t('columns.resetToDefault')}</Button>
      </div>
    </div>
  )
}
