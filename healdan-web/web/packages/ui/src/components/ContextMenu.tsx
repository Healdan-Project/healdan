// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type PointerEvent, type ReactNode } from 'react'
import { Menu as AriaMenu, Popover } from 'react-aria-components'
import styles from './Menu.module.css'

export interface ContextMenuProps<T> {
  /**
   * A selector for the elements that have a menu, such as a list's rows: the nearest one around what was right-clicked, or
   * around the focus for the keyboard, is the one the menu is for. Anywhere else keeps the browser's own menu.
   */
  target: string
  /** What an element's menu acts on; undefined leaves the browser's own menu there */
  subjectOf: (element: HTMLElement) => T | undefined
  /** The menu's name for a screen reader */
  label: (subject: T) => string
  /** The menu's items, drawn again while it's open, so they follow what they act on */
  items: (subject: T) => ReactNode
  /** Called as the menu opens, before it's drawn: a list selects a row that isn't selected */
  onOpen?: (subject: T) => void
  children: ReactNode
}

/** A menu for each item of a list or a tree, by the item's key: what Table's and Tree's `contextMenu` take */
export interface ItemMenu {
  /** The menu's name for a screen reader */
  label: (key: string) => string
  /** What the menu offers for the item; undefined keeps the browser's own menu on it */
  items: (key: string) => ReactNode | undefined
  /** Called as the menu opens, before it's drawn: a list selects a row that isn't selected */
  onOpen?: (key: string) => void
}

/** The ContextMenu of the elements with the attribute, whose value is the item's key */
export function ItemContextMenu({ attribute, menu, children }: { attribute: string; menu: ItemMenu | undefined; children: ReactNode }) {
  return (
    <ContextMenu
      target={`[${attribute}]`}
      subjectOf={(element) => {
        const key = element.getAttribute(attribute)
        return key !== null && menu?.items(key) !== undefined ? key : undefined
      }}
      label={(key) => menu?.label(key) ?? ''}
      items={(key) => menu?.items(key)}
      onOpen={(key) => menu?.onOpen?.(key)}
    >
      {children}
    </ContextMenu>
  )
}

/** Where the browser's own menu stays: copy, paste and spelling work there */
const EDITABLE = 'input, textarea, select, [contenteditable]:not([contenteditable="false"])'

/** What marks a ContextMenu's own element (below) */
const AREA = 'data-context-menu'

/** A Mac's Control-click is its right-click; elsewhere Control-click adds to a selection */
const isMac = () => typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)

/** The keys that ask for a menu: Shift+F10, and the menu key some keyboards have */
function asksForMenu(event: KeyboardEvent): boolean {
  if (event.key === 'ContextMenu') return !event.altKey && !event.ctrlKey && !event.metaKey
  return event.key === 'F10' && event.shiftKey && !event.altKey && !event.ctrlKey && !event.metaKey
}

interface Opened<T> {
  subject: T
  /** Where it opens, in the window's pixels */
  point: { x: number; y: number }
  /** By a key, so its first item has the focus */
  keyboard: boolean
}

/**
 * A menu in place of the browser's own, for the elements of `children` that `target` names: a right-click (or a Mac's
 * Control-click) opens it at the pointer, and Shift+F10 or the menu key at the element the focus is on. Esc, a click
 * outside it or one of its items closes it, and the focus goes back to that element. Fields keep the browser's menu,
 * and so does anything `target` doesn't name, or whose `subjectOf` is undefined. Every action it offers is also on a
 * toolbar or a button: the menu is a shortcut, never the only way.
 */
export function ContextMenu<T>({ target, subjectOf, label, items, onOpen, children }: ContextMenuProps<T>) {
  const area = useRef<HTMLDivElement>(null)
  const popover = useRef<HTMLElement>(null)
  // The element the menu is for: the popover places itself by it, and React Aria gives the focus back to it on closing
  const anchor = useRef<HTMLElement | null>(null)
  const [opened, setOpened] = useState<Opened<T>>()
  // React Aria gives the focus back to what had it as the menu opened, once the menu has gone (in the next frame)
  const close = () => setOpened(undefined)

  // A right-click outside the open menu closes it, and the browser's own menu shows there, as React Aria's own context
  // menus do: everything outside the menu is inert while it's open, so the click lands on the page's body, which no React
  // handler hears. A plain click outside is React Aria's to close it with.
  const isOpen = opened !== undefined
  useEffect(() => {
    if (!isOpen) return
    const onMouseDown = (event: globalThis.MouseEvent) => {
      const secondary = event.button === 2 || (event.button === 0 && event.ctrlKey)
      if (secondary && !(event.target instanceof Node && popover.current?.contains(event.target))) close()
    }
    globalThis.document.addEventListener('mousedown', onMouseDown)
    return () => globalThis.document.removeEventListener('mousedown', onMouseDown)
  }, [isOpen])

  function find(at: EventTarget | null): { element: HTMLElement; subject: T } | undefined {
    if (!(at instanceof Element) || !area.current?.contains(at) || at.closest(EDITABLE)) return undefined
    // A ContextMenu inside this one has the elements inside it to itself
    if (at.closest(`[${AREA}]`) !== area.current) return undefined
    const element = at.closest<HTMLElement>(target)
    if (!element || !area.current.contains(element)) return undefined
    const subject = subjectOf(element)
    return subject === undefined ? undefined : { element, subject }
  }

  function open(found: { element: HTMLElement; subject: T }, point: Opened<T>['point'], keyboard: boolean) {
    anchor.current = found.element
    // The focus is on the element (or inside it) when the menu opens, so it's where the focus goes back to
    if (!found.element.contains(globalThis.document.activeElement)) found.element.focus({ preventScroll: true })
    onOpen?.(found.subject)
    setOpened({ subject: found.subject, point, keyboard })
  }

  function onContextMenu(event: MouseEvent<HTMLDivElement>) {
    // React hands the open menu's events, and those of the layer under it, up through this element. No browser menu shows
    // over this one; a right-click on the layer outside it closes it, as a click there does.
    if (!area.current?.contains(event.target as Node)) {
      if (!opened) return
      event.preventDefault()
      if (!popover.current?.contains(event.target as Node)) close()
      return
    }
    // A key's own contextmenu event, which some browsers send after Shift+F10 or the menu key, is the menu already open
    if (opened) return event.preventDefault()
    const found = find(event.target)
    if (!found) return
    event.preventDefault()
    open(found, { x: event.clientX, y: event.clientY }, false)
  }

  function onKeyDownCapture(event: KeyboardEvent<HTMLDivElement>) {
    if (!asksForMenu(event)) return
    const found = find(event.target)
    if (!found) return
    event.preventDefault()
    event.stopPropagation()
    // At the focused element: its start, just below it
    const box = (event.target as Element).getBoundingClientRect()
    open(found, { x: box.left, y: box.bottom }, true)
  }

  // A Mac's Control-click is a right-click: the list under it mustn't take it as a click that selects
  function onPointerDownCapture(event: PointerEvent<HTMLDivElement> | MouseEvent<HTMLDivElement>) {
    if (event.button === 0 && event.ctrlKey && isMac() && find(event.target)) event.stopPropagation()
  }

  return (
    // Only passes on what the elements inside it are asked; it isn't a control of its own
    <div ref={area} data-context-menu="" className={styles.area} onContextMenu={onContextMenu} onKeyDownCapture={onKeyDownCapture} onPointerDownCapture={onPointerDownCapture} onMouseDownCapture={onPointerDownCapture}>
      {children}
      {opened && (
        <Popover
          ref={popover}
          className={styles.popover}
          trigger="ContextMenu"
          isOpen
          onOpenChange={(isOpen) => !isOpen && close()}
          triggerRef={anchor}
          getTargetRect={() => new DOMRect(opened.point.x, opened.point.y, 0, 0)}
          placement="bottom start"
          offset={2}
          // React Aria makes the popover a dialog, as it does a Menu's, which a screen reader names by the menu's name
          aria-label={label(opened.subject)}
        >
          <AriaMenu className={styles.menu} aria-label={label(opened.subject)} autoFocus={opened.keyboard ? 'first' : true} onClose={close}>
            {items(opened.subject)}
          </AriaMenu>
        </Popover>
      )}
    </div>
  )
}
