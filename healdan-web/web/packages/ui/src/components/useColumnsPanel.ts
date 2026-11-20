// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { focusHeading } from './headingFocus'

/**
 * Set on the Table's frame while the last heading's end reaches the Columns button, which lies over the heading row's end:
 * the last column then makes room for it (Table.module.css). The last heading ends at the content's width less the scroll,
 * which is at or past the list's visible end whatever the scroll while the content is wider than the list, and the list
 * doesn't scroll while it isn't, so the content's width alone says whether it reaches the button.
 */
export const LAST_UNDER_BUTTON = 'data-last-under-button'

/**
 * Keeps the button at the visible end of the heading row, before a scrollbar that takes room (Windows', or a Mac's with a
 * mouse), which runs beside the heading row as beside the rows and which the button would otherwise cover; and says on the
 * frame whether the last heading reaches the button, except while a resize lasts, whose edge stays under the pointer.
 */
function follow(frame: HTMLElement | null, place: HTMLElement | null, resizeLasts: boolean) {
  const list = frame?.querySelector<HTMLElement>('[role="grid"]')
  if (!frame || !list || !place) return
  place.style.insetInlineEnd = `${list.offsetWidth - list.clientWidth}px`
  if (resizeLasts) return
  // The virtualiser's box for what the list holds, as wide as the columns
  const content = list.firstElementChild instanceof HTMLElement ? list.firstElementChild.offsetWidth : 0
  frame.toggleAttribute(LAST_UNDER_BUTTON, content > list.clientWidth - place.offsetWidth)
}

/**
 * The focus back to what opened the panel: the heading, whose focus is its name on a list whose columns resize (where the
 * heading's own menu gives it back), or the button, as for a heading whose column the panel hid
 */
function giveFocusBack(frame: HTMLElement | null, column: string | undefined, button: HTMLElement | null) {
  const headings = column === undefined ? [] : [...(frame?.querySelectorAll<HTMLElement>('[data-column-key]') ?? [])]
  const heading = headings.find((element) => element.getAttribute('data-column-key') === column)
  if (heading) focusHeading(heading)
  else button?.focus()
}

interface ColumnsPanelOptions {
  /** The list has the Columns button: its columns are chosen */
  hasButton: boolean
  /** Its columns resize, so React Aria's container is round the list, which is drawn anew as that comes or goes */
  resizable: boolean
  /** A resize of a column lasts */
  resizeLasts: boolean
}

/**
 * A document list's Columns panel (spec 4.5): whether it's open, what opened it, and the Columns button's place. The button
 * and the heading menu's Choose Columns… open the same panel; as it closes, the focus goes back to what opened it.
 */
export function useColumnsPanel(frame: RefObject<HTMLDivElement | null>, { hasButton, resizable, resizeLasts }: ColumnsPanelOptions) {
  const [isOpen, setOpen] = useState(false)
  // A list whose columns are no longer chosen has no panel to keep open
  if (isOpen && !hasButton) setOpen(false)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const placeRef = useRef<HTMLDivElement>(null)
  // The column whose heading's menu opened the panel; none when the button did
  const opener = useRef<string | undefined>(undefined)

  // Once the popover has gone, which contains the focus until then. React Aria would give it to what had it as the popover
  // opened, which for Choose Columns… is the menu's item, gone with its menu.
  const wasOpen = useRef(false)
  useLayoutEffect(() => {
    if (wasOpen.current && !isOpen) giveFocusBack(frame.current, opener.current, buttonRef.current)
    wasOpen.current = isOpen
  }, [frame, isOpen])

  // As a resize ends, the last column makes room for the button, or no longer, by the widths it left
  const resizeLastsNow = useRef(resizeLasts)
  useLayoutEffect(() => {
    resizeLastsNow.current = resizeLasts
    if (!resizeLasts) follow(frame.current, placeRef.current, false)
  }, [frame, resizeLasts])

  // Whenever the list's box (a scrollbar coming or going) or what it holds (columns shown, hidden or resized) changes size
  useLayoutEffect(() => {
    const element = frame.current
    const list = element?.querySelector<HTMLElement>('[role="grid"]')
    if (!hasButton || !element || !list) return
    const now = () => follow(element, placeRef.current, resizeLastsNow.current)
    now()
    if (typeof ResizeObserver === 'undefined') return () => element.removeAttribute(LAST_UNDER_BUTTON)
    const observer = new ResizeObserver(now)
    observer.observe(list)
    if (list.firstElementChild) observer.observe(list.firstElementChild)
    return () => {
      observer.disconnect()
      element.removeAttribute(LAST_UNDER_BUTTON)
    }
  }, [frame, hasButton, resizable])

  return {
    isOpen,
    /** Opens the panel, from a heading's menu by its column, or from the button */
    open: (column?: string) => {
      opener.current = column
      setOpen(true)
    },
    /** The popover's own: Esc or a click outside closes it */
    onOpenChange: setOpen,
    buttonRef,
    placeRef,
  }
}
