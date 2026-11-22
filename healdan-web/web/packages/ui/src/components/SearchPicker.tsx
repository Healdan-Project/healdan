// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { isValidElement, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode, type Ref, type RefObject } from 'react'
import { Autocomplete, Input, Label, ListBox, ListBoxItem, Text, TextField as AriaTextField, type Selection } from 'react-aria-components'
import { Icon } from '../icons'
import { plural, t } from '../strings'
import fieldStyles from './Fields.module.css'
import styles from './SearchPicker.module.css'

/** A search starts at two characters, waits for the typing to pause, and sends at most this many characters, as the User app's do. */
export const SEARCH_MIN = 2
export const SEARCH_MAX = 100
export const SEARCH_DELAY_MS = 150

export interface SearchPickerProps<T> {
  /** The field's label, above it */
  label: string
  placeholder: string
  /** A line under the field, which describes it to a screen reader too: what the search matches */
  hint?: string
  /** Above the starting rows: "Recent Matters" */
  initialHeading: string
  /** The rows shown while nothing is typed; none shows no heading and no list */
  initial: T[]
  /** What the server finds for the words typed, trimmed */
  search: (q: string) => Promise<T[]>
  /** Unique among the rows */
  id: (item: T) => string
  /** A row's content, given the words its answer was found for ('' for the starting rows), which `Highlight` marks */
  render: (item: T, query: string) => ReactNode
  /** A row was picked: the arrow keys and Enter, or a click */
  onPick: (item: T) => void
  /** The row picked, which shows as picked */
  selectedId?: string
  /**
   * The rows to leave out, of the starting rows and of what is found, as those a list it adds to has already: asked each
   * time it draws, so a row picked from an answer goes from it at once
   */
  leaveOut?: (item: T) => boolean
  /** The field itself, for moving the focus to it, as when the last row its list added is removed */
  inputRef?: Ref<HTMLInputElement>
}

/** What the text asks the server to find, or nothing while it is too short */
function searchable(text: string): string | undefined {
  const q = text.trim().slice(0, SEARCH_MAX).trim()
  return q.length >= SEARCH_MIN ? q : undefined
}

/** The value once it has stayed the same for `delay` milliseconds */
function useDebounced<V>(value: V, delay: number): V {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return settled
}

/**
 * A stop of its own (tabindex 0) while the box holds more than its height shows, so it scrolls, and then a region, so a
 * screen reader says what the stop is: named by its field's label and the heading over its rows together (`names`, their
 * ids), so it never takes the name of a region it's in that its heading shares, as a person's Groups. None otherwise.
 */
function markStop(box: HTMLElement, names: string) {
  if (box.scrollHeight > box.clientHeight) {
    box.tabIndex = 0
    box.setAttribute('role', 'region')
    box.setAttribute('aria-labelledby', names)
  } else {
    box.removeAttribute('tabindex')
    box.removeAttribute('role')
    box.removeAttribute('aria-labelledby')
  }
}

/**
 * Makes the box a stop of its own while it scrolls, named by what `names` gives (ids): looked at after each render, as
 * rows come and go, and whenever the box's own size changes, as its height follows the window's. It's set on the element,
 * which React draws with no tabindex, role or name, as it's what the browser has laid out.
 */
function useStopWhileScrolling(box: RefObject<HTMLElement | null>, drawn: boolean, names: string) {
  useLayoutEffect(() => {
    if (box.current) markStop(box.current, names)
  })
  useLayoutEffect(() => {
    const element = box.current
    if (!drawn || element === null || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => markStop(element, names))
    observer.observe(element)
    return () => observer.disconnect()
  }, [box, drawn, names])
}

/** The characters a regular expression would read as its own, which a query's words are taken without */
const special = /[\^$\\.*+?()[\]{}|/]/g

/**
 * The text, with each word of the query marked wherever it matches, in any case. The text and the query are only ever
 * text: nothing in either is read as markup or as a pattern.
 */
export function Highlight({ text, query }: { text: string; query: string }) {
  const words = query.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return <>{text}</>
  // The longest first, so a word that holds a shorter one is marked whole
  const pattern = new RegExp(`(${words.sort((a, b) => b.length - a.length).map((word) => word.replace(special, '\\$&')).join('|')})`, 'giu')
  // Splitting on a captured pattern puts each match at an odd index
  return <>{text.split(pattern).map((part, index) => (index % 2 === 1 ? <mark key={index} className={styles.mark}>{part}</mark> : part))}</>
}

/**
 * A row's plain text, from what it draws. React Aria warns without one, though it only reads it for type-ahead, which this
 * list doesn't take (its keys are the field's), so a row it can't read is named by its id there.
 */
function textOf(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(textOf).join('')
  if (!isValidElement<{ text?: string; children?: ReactNode }>(node)) return ''
  return node.type === Highlight ? (node.props.text ?? '') : textOf(node.props.children)
}

type Answer<T> = { q: string; items: T[] } | { q: string; failed: true }

/**
 * A search field over a list of rows to pick from, as the Move To… picker's matters are: the starting rows while nothing is
 * typed (Recent Matters), then what the server finds once the typing pauses, with the words marked. The focus stays in the
 * field: the arrow keys move through the rows, and Enter picks one (React Aria's Autocomplete). An answer to an older
 * question is dropped, and the last answer stays until the next, so the list doesn't blink while typing.
 */
export function SearchPicker<T>({ label, placeholder, hint, initialHeading, initial, search, id, render, onPick, selectedId, leaveOut, inputRef }: SearchPickerProps<T>) {
  const [text, setText] = useState('')
  const wanted = searchable(text)
  const settled = useDebounced(wanted, SEARCH_DELAY_MS)
  const [answer, setAnswer] = useState<Answer<T>>()
  const scroller = useRef<HTMLDivElement>(null)
  const labelId = useId()
  const headingId = useId()

  // The caller's search is read when it is asked, so one made afresh at each render doesn't ask again
  const searchRef = useRef(search)
  useEffect(() => {
    searchRef.current = search
  })
  useEffect(() => {
    if (settled === undefined) return
    // A newer question, or the picker closing, drops this one's answer whenever it comes
    let current = true
    searchRef.current(settled).then(
      (items) => current && setAnswer({ q: settled, items }),
      () => current && setAnswer({ q: settled, failed: true }),
    )
    return () => {
      current = false
    }
  }, [settled])

  const shown = wanted === undefined ? undefined : answer
  const kept = (items: T[]) => (leaveOut ? items.filter((item) => !leaveOut(item)) : items)
  const found = shown && 'items' in shown ? kept(shown.items) : []
  const rows = wanted === undefined ? kept(initial) : found
  const query = shown?.q ?? ''
  const heading = rows.length === 0 ? undefined : wanted === undefined ? initialHeading : plural('searchPicker.results', rows.length)
  const note = shown === undefined ? '' : 'failed' in shown ? t('problems.unknown') : found.length === 0 ? t('search.empty') : ''
  useStopWhileScrolling(scroller, rows.length > 0, `${labelId} ${headingId}`)

  function choose(keys: Selection) {
    if (keys === 'all') return
    const [key] = [...keys]
    const item = rows.find((one) => id(one) === String(key))
    if (item) onPick(item)
  }

  return (
    <div className={styles.picker}>
      <Autocomplete inputValue={text} onInputChange={setText} disableAutoFocusFirst>
        <AriaTextField className={fieldStyles.field}>
          {/* Its id names the rows' box too, while that's a stop */}
          <Label id={labelId} className={fieldStyles.label}>
            {label}
          </Label>
          <Input ref={inputRef} className={fieldStyles.input} placeholder={placeholder} />
          {hint && (
            <Text slot="description" className={styles.hint}>
              {hint}
            </Text>
          )}
        </AriaTextField>
        {heading && (
          <h3 id={headingId} className={styles.heading}>
            {heading}
          </h3>
        )}
        {rows.length > 0 && (
          // The box the rows scroll in. The arrow keys in the field reach every row and scroll it into view, as the focus
          // stays in the field (virtual focus), but what a list longer than its box holds below would show only to a
          // pointer: so while it scrolls, the box is a stop of its own, which Tab reaches after the field and the arrow
          // keys then scroll (WCAG 2.1.1), and a region named by the field's label and the heading, so a screen reader says
          // what it reached. A press on its scrollbar leaves the focus where it is, as the list's own does.
          <div
            ref={scroller}
            className={styles.scroll}
            onMouseDown={(event) => {
              if (event.target === event.currentTarget) event.preventDefault()
            }}
          >
            <ListBox
              // Named as its heading says, in place of Autocomplete's own "Suggestions"
              aria-label={heading}
              className={styles.list}
              items={rows}
              dependencies={[query, render, id]}
              selectionMode="single"
              // Picking the row that is picked changes nothing
              disallowEmptySelection
              selectedKeys={selectedId === undefined ? [] : [selectedId]}
              onSelectionChange={choose}
            >
              {(item) => {
                const content = render(item, query)
                return (
                  <ListBoxItem id={id(item)} textValue={textOf(content).trim() || id(item)} className={styles.option}>
                    {({ isSelected }) => (
                      <>
                        {content}
                        {isSelected && <Icon name="check" className={styles.check} />}
                      </>
                    )}
                  </ListBoxItem>
                )
              }}
            </ListBox>
          </div>
        )}
        {/* There before it has words, so a screen reader says them as they come */}
        <p role="status" className={styles.note}>
          {note}
        </p>
      </Autocomplete>
    </div>
  )
}
