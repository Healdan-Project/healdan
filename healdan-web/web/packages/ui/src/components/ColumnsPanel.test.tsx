// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, createEvent, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { Dialog } from 'react-aria-components'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import tokensCss from '../tokens.css?raw'
import { ColumnsPanel } from './ColumnsPanel'
import panelCss from './ColumnsPanel.module.css?raw'

const offered = [
  { id: 'title', name: 'Title' },
  { id: 'docNumber', name: 'Doc #' },
  { id: 'version', name: 'Version' },
  { id: 'author', name: 'Author' },
  { id: 'edited', name: 'Edit Date' },
  { id: 'matter', name: 'Matter' },
  { id: 'client', name: 'Client' },
  { id: 'size', name: 'Size' },
]
const DEFAULT = ['title', 'docNumber', 'version', 'matter']

interface Calls {
  onShow?: (id: string) => void
  onHide?: (id: string) => void
  onMove?: (id: string, to: number) => void
  onReset?: () => void
}

// The panel is the caller's to keep, as a view keeps its layout: each change comes back as new props. It sits in a
// dialog, as the popover that holds it is one.
function Harness({ initial = DEFAULT, onShow, onHide, onMove, onReset }: Calls & { initial?: string[] }) {
  const [shown, setShown] = useState(initial)
  return (
    <Dialog>
      <ColumnsPanel
        forLabel="For Recent Documents"
        offered={offered}
        shown={shown}
        onShow={(id) => {
          onShow?.(id)
          setShown((before) => [...before, id])
        }}
        onHide={(id) => {
          onHide?.(id)
          setShown((before) => before.filter((other) => other !== id))
        }}
        onMove={(id, to) => {
          onMove?.(id, to)
          setShown((before) => {
            const rest = before.filter((other) => other !== id)
            rest.splice(to, 0, id)
            return rest
          })
        }}
        onReset={() => {
          onReset?.()
          setShown(DEFAULT)
        }}
      />
    </Dialog>
  )
}

const section = (name: string) => screen.getByRole('group', { name })
const box = (name: string) => screen.getByRole('checkbox', { name }) as HTMLInputElement
const names = (name: string) => within(section(name)).getAllByRole('checkbox').map((input) => input.getAttribute('aria-label'))
const button = (name: string) => screen.getByRole('button', { name }) as HTMLButtonElement

describe('ColumnsPanel', () => {
  it('titles itself Columns, with its list under it', () => {
    render(<Harness />)
    const heading = screen.getByRole('heading', { name: 'Columns' })
    expect(screen.getByRole('dialog', { name: 'Columns' })).toBeTruthy()
    expect(heading.nextElementSibling?.textContent).toBe('For Recent Documents')
  })

  it('lists the shown columns in order, Title first with Always Shown and no tick to change, then More Columns', async () => {
    const onHide = vi.fn()
    render(<Harness onHide={onHide} />)
    expect(names('Shown')).toEqual(['Title', 'Doc #', 'Version', 'Matter'])
    expect(within(section('Shown')).getAllByRole('checkbox').every((input) => (input as HTMLInputElement).checked)).toBe(true)
    // Each shown column says where it is, Title that it stays
    expect(screen.getByRole('checkbox', { name: 'Title', description: 'Always Shown' }).getAttribute('aria-readonly')).toBe('true')
    expect(screen.getByRole('checkbox', { name: 'Doc #', description: '2 of 4' })).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: 'Matter', description: '4 of 4' })).toBeTruthy()
    await userEvent.click(box('Title'))
    expect(onHide).not.toHaveBeenCalled()
    expect(box('Title').checked).toBe(true)
    // The rest the list offers, in its order, after Shown
    expect(names('More Columns')).toEqual(['Author', 'Edit Date', 'Client', 'Size'])
    expect(within(section('More Columns')).getAllByRole('checkbox').some((input) => (input as HTMLInputElement).checked)).toBe(false)
    expect(section('Shown').compareDocumentPosition(section('More Columns')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('ticking shows a column at the end of Shown, and unticking hides it', async () => {
    const onShow = vi.fn()
    const onHide = vi.fn()
    render(<Harness onShow={onShow} onHide={onHide} />)
    await userEvent.click(box('Client'))
    expect(onShow).toHaveBeenCalledWith('client')
    expect(names('Shown')).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Client'])
    expect(box('Client').checked).toBe(true)
    await userEvent.click(box('Doc #'))
    expect(onHide).toHaveBeenCalledWith('docNumber')
    expect(names('Shown')).toEqual(['Title', 'Version', 'Matter', 'Client'])
    expect(names('More Columns')).toEqual(['Doc #', 'Author', 'Edit Date', 'Size'])
    expect(box('Doc #').checked).toBe(false)
    expect(onShow).toHaveBeenCalledTimes(1)
    expect(onHide).toHaveBeenCalledTimes(1)
  })

  it('keeps the focus on a column Space ticks or unticks, as it moves to the other section', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    act(() => box('Client').focus())
    await user.keyboard(' ')
    expect(names('Shown')).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Client'])
    expect(document.activeElement).toBe(within(section('Shown')).getByRole('checkbox', { name: 'Client' }))
    await user.keyboard(' ')
    expect(document.activeElement).toBe(within(section('More Columns')).getByRole('checkbox', { name: 'Client' }))
  })

  it('moves through both sections by the arrow keys, and Tab reaches the list, Move Up, Move Down and Reset to Default', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.tab()
    expect(document.activeElement).toBe(box('Title'))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(box('Doc #'))
    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}')
    expect(document.activeElement).toBe(box('Author'))
    await user.keyboard('{End}')
    expect(document.activeElement).toBe(box('Size'))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(box('Size'))
    await user.keyboard('{Home}{ArrowUp}')
    expect(document.activeElement).toBe(box('Title'))
    await user.keyboard('{ArrowDown}{ArrowDown}')
    expect(document.activeElement).toBe(box('Version'))
    // The list is one stop, the column last focused
    await user.tab()
    expect(document.activeElement).toBe(button('Move Up'))
    await user.tab()
    expect(document.activeElement).toBe(button('Move Down'))
    await user.tab()
    expect(document.activeElement).toBe(button('Reset to Default'))
    await user.tab({ shift: true })
    await user.tab({ shift: true })
    await user.tab({ shift: true })
    expect(document.activeElement).toBe(box('Version'))
  })

  it('Move Up and Move Down move the focused column, never above Title', async () => {
    const user = userEvent.setup()
    const onMove = vi.fn()
    render(<Harness onMove={onMove} />)
    // Nothing focused yet, and Title, move nothing
    expect(button('Move Up').disabled).toBe(true)
    expect(button('Move Down').disabled).toBe(true)
    await user.tab()
    expect(button('Move Up').disabled).toBe(true)
    expect(button('Move Down').disabled).toBe(true)
    await user.keyboard('{ArrowDown}{ArrowDown}')
    expect(document.activeElement).toBe(box('Version'))
    await user.tab()
    await user.keyboard('{Enter}')
    expect(onMove).toHaveBeenLastCalledWith('version', 1)
    expect(names('Shown')).toEqual(['Title', 'Version', 'Doc #', 'Matter'])
    // It can go no higher: Move Up can't be pressed, and the keys go on to Move Down
    expect(button('Move Up').disabled).toBe(true)
    expect(document.activeElement).toBe(button('Move Down'))
    await user.keyboard('{Enter}')
    expect(onMove).toHaveBeenLastCalledWith('version', 2)
    await user.keyboard('{Enter}')
    expect(onMove).toHaveBeenLastCalledWith('version', 3)
    expect(names('Shown')).toEqual(['Title', 'Doc #', 'Matter', 'Version'])
    expect(button('Move Down').disabled).toBe(true)
    expect(document.activeElement).toBe(button('Move Up'))
    expect(screen.getByRole('checkbox', { name: 'Version', description: '4 of 4' })).toBeTruthy()
    // A column that isn't shown has nowhere to move
    act(() => box('Author').focus())
    expect(button('Move Up').disabled).toBe(true)
    expect(button('Move Down').disabled).toBe(true)
    expect(onMove).toHaveBeenCalledTimes(3)
  })

  it('Reset to Default calls onReset', async () => {
    const onReset = vi.fn()
    render(<Harness initial={['title', 'client']} onReset={onReset} />)
    await userEvent.click(button('Reset to Default'))
    expect(onReset).toHaveBeenCalledTimes(1)
    expect(names('Shown')).toEqual(['Title', 'Doc #', 'Version', 'Matter'])
  })

  // jsdom draws no style, so axe can't weigh colours: the stylesheet is read instead, and draws only in tokens.css's
  // colours, which it defines again for dark, in pairs tokens.test.ts checks in both themes
  it('passes axe in light and dark', async () => {
    const { container } = render(<Harness />)
    await expectNoAxeViolations(container)
    const colours = [...panelCss.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(?:^|[\s;{])(color|background|border(?:-[\w-]+)?):\s*([^;]+);/g)]
    expect(colours.length).toBeGreaterThan(0)
    for (const [, , value] of colours) {
      for (const token of value!.match(/--hd-[\w-]+/g) ?? []) expect(tokensCss).toContain(`${token}:`)
      expect(value!.replace(/var\(--hd-[\w-]+\)/g, '')).not.toMatch(/#|rgb|hsl/)
    }
    // The drop line is the accent, 2 pixels
    expect(panelCss).toMatch(/\[data-drop\]::before\s*\{(?=[^}]*height:\s*2px)(?=[^}]*background:\s*var\(--hd-accent-fill\))/)
  })
})

describe('ColumnsPanel drags', () => {
  const PANEL_TYPE = 'application/x-healdan-panel-column'

  /** A DataTransfer whose data is kept, as a drag's source writes it and a drop reads it */
  function transfer() {
    const stored = new Map<string, string>()
    return {
      get types() {
        return [...stored.keys()]
      },
      setData: (kind: string, data: string) => void stored.set(kind, data),
      getData: (kind: string) => stored.get(kind) ?? '',
      effectAllowed: 'uninitialized',
      dropEffect: 'none',
    }
  }

  // jsdom has no DragEvent, so the pointer's place is put on the event; and no layout, so each row is 20 pixels tall, one
  // under another, in the order they are drawn
  function fire(kind: 'dragStart' | 'dragEnter' | 'dragOver' | 'dragLeave' | 'drop' | 'dragEnd', element: Element, dataTransfer: object, clientY = 0) {
    const event = createEvent[kind](element, { dataTransfer })
    Object.defineProperty(event, 'clientY', { value: clientY })
    return fireEvent(element, event)
  }
  beforeEach(() => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const top = [...document.querySelectorAll('[data-column]')].indexOf(this) * 20
      return { top, bottom: top + 20, height: 20, left: 0, right: 200, width: 200, x: 0, y: top, toJSON: () => ({}) } as DOMRect
    })
  })
  afterEach(() => vi.restoreAllMocks())

  const row = (name: string) => box(name).closest('[data-column]') as HTMLElement

  it('drops a shown column between two others, with a line where it will go, and reports the move', () => {
    const onMove = vi.fn()
    render(<Harness onMove={onMove} />)
    const data = transfer()
    expect(fire('dragStart', row('Matter'), data)).toBe(true)
    expect(data.types).toEqual([PANEL_TYPE])
    fire('dragEnter', row('Doc #'), data, 25)
    // Above Doc #'s middle (rows at 0, 20, 40 and 60): between Title and Doc #
    expect(fire('dragOver', row('Doc #'), data, 25)).toBe(false)
    expect(row('Doc #').getAttribute('data-drop')).toBe('before')
    fire('drop', row('Doc #'), data, 25)
    fire('dragEnd', row('Matter'), data)
    expect(onMove).toHaveBeenCalledWith('matter', 1)
    expect(names('Shown')).toEqual(['Title', 'Matter', 'Doc #', 'Version'])
    expect(document.querySelector('[data-drop]')).toBeNull()
    // Below the last's middle: after it
    fire('dragStart', row('Matter'), data)
    fire('dragOver', row('Version'), data, 75)
    expect(row('Version').getAttribute('data-drop')).toBe('after')
    fire('drop', row('Version'), data, 75)
    expect(onMove).toHaveBeenLastCalledWith('matter', 3)
    expect(names('Shown')).toEqual(['Title', 'Doc #', 'Version', 'Matter'])
  })

  it('never drags Title nor a column that is not shown, and never drops above Title', () => {
    const onMove = vi.fn()
    render(<Harness onMove={onMove} />)
    expect(row('Title').getAttribute('draggable')).toBeNull()
    expect(row('Client').getAttribute('draggable')).toBeNull()
    expect(row('Doc #').getAttribute('draggable')).toBe('true')
    const data = transfer()
    expect(fire('dragStart', row('Title'), data)).toBe(false)
    const more = transfer()
    expect(fire('dragStart', row('Client'), more)).toBe(false)
    expect(more.types).toEqual([])
    fire('dragStart', row('Version'), data)
    // Above Title's middle is still below Title
    fire('dragOver', row('Title'), data, 2)
    expect(row('Doc #').getAttribute('data-drop')).toBe('before')
    expect(row('Title').getAttribute('data-drop')).toBeNull()
    fire('drop', row('Title'), data, 2)
    expect(onMove).toHaveBeenCalledWith('version', 1)
  })

  it('reports nothing for a drag that ends where it began', () => {
    const onMove = vi.fn()
    render(<Harness onMove={onMove} />)
    const data = transfer()
    fire('dragStart', row('Version'), data)
    // Just above Version, and just below it: it would stay where it is, so no line is drawn
    for (const y of [35, 45, 55, 65]) {
      fire('dragOver', row('Version'), data, y)
      expect(document.querySelector('[data-drop]')).toBeNull()
    }
    fire('drop', row('Version'), data, 45)
    fire('dragEnd', row('Version'), data)
    expect(onMove).not.toHaveBeenCalled()
    expect(names('Shown')).toEqual(['Title', 'Doc #', 'Version', 'Matter'])
  })

  it('takes only its own drags, and keeps them from the list it is drawn over', () => {
    const outside = { onDragStart: vi.fn(), onDragOver: vi.fn(), onDrop: vi.fn() }
    render(
      <div {...outside}>
        <Harness />
      </div>,
    )
    const data = transfer()
    fire('dragStart', row('Doc #'), data)
    fire('dragOver', row('Version'), data, 55)
    fire('drop', row('Version'), data, 55)
    expect(outside.onDragStart).not.toHaveBeenCalled()
    expect(outside.onDragOver).not.toHaveBeenCalled()
    expect(outside.onDrop).not.toHaveBeenCalled()
    // Rows dragged from a list, or files, aren't the panel's to take
    for (const types of [['application/x-healdan-documents'], ['Files']]) {
      expect(fire('dragOver', row('Doc #'), { types }, 25)).toBe(true)
      expect(document.querySelector('[data-drop]')).toBeNull()
    }
  })
})
