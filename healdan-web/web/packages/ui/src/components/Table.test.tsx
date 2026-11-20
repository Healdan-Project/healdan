// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { bringObservedIntoView } from '../test/intersection'
import '../tokens.css'
import { Icon } from '../icons'
import { Table, type TableColumn, type TableProps, type TableResizing, type TableRow, type TableSort } from './Table'
import tableCss from './Table.module.css?raw'
import { MenuItem } from './Menu'

interface Item extends TableRow {
  name: string
  number?: number
}

const columns: TableColumn<Item>[] = [
  { id: 'title', name: 'Title', isRowHeader: true, allowsSorting: true, cell: (row) => row.name },
  { id: 'docNumber', name: 'Doc #', allowsSorting: true, align: 'end', cell: (row) => row.number },
  { id: 'note', name: 'Note', cell: () => '' },
]

function items(count: number): Item[] {
  return Array.from({ length: count }, (_, i) => ({ id: `d${i + 1}`, textValue: `Letter ${i + 1}`, name: `Letter ${i + 1}`, number: 10000 + i }))
}

const folderRow: Item = { id: 'f1', textValue: 'Pleadings', name: 'Pleadings', selectable: false }
// A folder that can be selected, with the documents, though a click opens it
const openingRow: Item = { id: 'f2', textValue: 'Drafts', name: 'Drafts', opensOnClick: true }

type HarnessProps = Partial<Omit<TableProps<Item>, 'selection' | 'onSelectionChange'>> & {
  initial?: string[]
  onSelectionChange?: (keys: Set<string>) => void
}

// The Table's selection is the caller's, as the folder view keeps it in the address.
function Harness({ initial = [], onSelectionChange, rows = items(3), ...rest }: HarnessProps) {
  const [selection, setSelection] = useState(new Set(initial))
  return (
    <Table
      aria-label="Correspondence"
      columns={columns}
      rows={rows}
      selectionMode="multiple"
      selection={selection}
      onSelectionChange={(keys) => {
        setSelection(keys)
        onSelectionChange?.(keys)
      }}
      {...rest}
    />
  )
}

const row = (name: RegExp | string) => screen.getByRole('row', { name })

function press(element: Element, key: string) {
  fireEvent.keyDown(element, { key })
  fireEvent.keyUp(element, { key })
}

describe('Table', () => {
  it('shows its columns and a row per item, the first column naming each row', () => {
    render(<Harness />)
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers).toEqual(['Title', 'Doc #', 'Note'])
    expect(screen.getAllByRole('row')).toHaveLength(4)
    expect(within(row(/Letter 2/)).getByRole('rowheader').textContent).toBe('Letter 2')
  })

  it('sorts by a header when clicked, and the other way when clicked again', () => {
    const onSort = vi.fn<(sort: TableSort) => void>()
    const { rerender } = render(<Harness onSort={onSort} />)
    fireEvent.click(screen.getByRole('columnheader', { name: 'Doc #' }))
    expect(onSort).toHaveBeenLastCalledWith({ column: 'docNumber', direction: 'ascending' })

    rerender(<Harness onSort={onSort} sort={{ column: 'docNumber', direction: 'ascending' }} />)
    expect(screen.getByRole('columnheader', { name: /Doc #/ }).getAttribute('aria-sort')).toBe('ascending')
    fireEvent.click(screen.getByRole('columnheader', { name: /Doc #/ }))
    expect(onSort).toHaveBeenLastCalledWith({ column: 'docNumber', direction: 'descending' })

    // A column that doesn't sort doesn't ask
    onSort.mockClear()
    fireEvent.click(screen.getByRole('columnheader', { name: 'Note' }))
    expect(onSort).not.toHaveBeenCalled()
  })

  it('selects a row when clicked, and more with Cmd or Shift', async () => {
    const user = userEvent.setup()
    const onSelectionChange = vi.fn()
    render(<Harness onSelectionChange={onSelectionChange} />)
    await user.click(within(row(/Letter 1/)).getByRole('rowheader'))
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['d1']))
    expect(row(/Letter 1/).getAttribute('aria-selected')).toBe('true')
    await user.keyboard('{Shift>}')
    await user.click(within(row(/Letter 3/)).getByRole('rowheader'))
    await user.keyboard('{/Shift}')
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['d1', 'd2', 'd3']))
    // Cmd on a Mac; jsdom isn't one, so React Aria takes Ctrl there
    await user.keyboard('{Control>}')
    await user.click(within(row(/Letter 2/)).getByRole('rowheader'))
    await user.keyboard('{/Control}')
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['d1', 'd3']))
  })

  it('opens a row that can\'t be selected when it is clicked, and any row on a double-click', async () => {
    const user = userEvent.setup()
    const onOpen = vi.fn()
    const onSelectionChange = vi.fn()
    render(<Harness rows={[folderRow, ...items(2)]} onOpen={onOpen} onSelectionChange={onSelectionChange} />)
    await user.click(within(row(/Pleadings/)).getByRole('rowheader'))
    expect(onOpen).toHaveBeenLastCalledWith('f1')
    expect(onSelectionChange).not.toHaveBeenCalled()

    await user.dblClick(within(row(/Letter 2/)).getByRole('rowheader'))
    expect(onOpen).toHaveBeenLastCalledWith('d2')
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['d2']))
  })

  it('opens a row that opens on a click, and selects it with Shift or Cmd as any row', async () => {
    const user = userEvent.setup()
    const onOpen = vi.fn()
    const onSelectionChange = vi.fn()
    render(<Harness rows={[openingRow, ...items(2)]} onOpen={onOpen} onSelectionChange={onSelectionChange} />)
    await user.click(within(row(/Drafts/)).getByRole('rowheader'))
    expect(onOpen).toHaveBeenCalledExactlyOnceWith('f2')
    expect(onSelectionChange).not.toHaveBeenCalled()

    await user.click(within(row(/Letter 1/)).getByRole('rowheader'))
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['d1']))
    await user.keyboard('{Shift>}')
    await user.click(within(row(/Drafts/)).getByRole('rowheader'))
    await user.keyboard('{/Shift}')
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['f2', 'd1']))
    expect(row(/Drafts/).getAttribute('aria-selected')).toBe('true')
    // Cmd on a Mac; jsdom isn't one, so React Aria takes Ctrl there
    await user.keyboard('{Control>}')
    await user.click(within(row(/Drafts/)).getByRole('rowheader'))
    await user.keyboard('{/Control}')
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['d1']))
    expect(onOpen).toHaveBeenCalledOnce()
  })

  it('opens a row that opens on a click once for a double-click, and leaves a right-click to the menu', async () => {
    const user = userEvent.setup()
    const onOpen = vi.fn()
    const onSelectionChange = vi.fn()
    render(<Harness rows={[openingRow, ...items(1)]} onOpen={onOpen} onSelectionChange={onSelectionChange} />)
    await user.dblClick(within(row(/Drafts/)).getByRole('rowheader'))
    expect(onOpen).toHaveBeenCalledExactlyOnceWith('f2')
    await user.pointer({ keys: '[MouseRight]', target: within(row(/Drafts/)).getByRole('rowheader') })
    expect(onOpen).toHaveBeenCalledOnce()
    expect(onSelectionChange).not.toHaveBeenCalled()
  })

  it('leaves a tap on a row that opens on a click to React Aria, which opens it once', async () => {
    const user = userEvent.setup()
    const onOpen = vi.fn()
    render(<Harness rows={[openingRow, ...items(1)]} onOpen={onOpen} />)
    await user.pointer({ keys: '[TouchA]', target: within(row(/Drafts/)).getByRole('rowheader') })
    expect(onOpen).toHaveBeenCalledExactlyOnceWith('f2')
  })

  it('selects a row that opens on a click with the arrows, as any row', () => {
    const onSelectionChange = vi.fn()
    render(<Harness rows={[...items(1), openingRow]} onSelectionChange={onSelectionChange} />)
    const first = row(/Letter 1/)
    act(() => first.focus())
    press(first, 'ArrowDown')
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['f2']))
  })

  it('works from the keyboard: the arrows move the selection; Enter opens, Space previews, F2 renames, Delete deletes', () => {
    const onOpen = vi.fn()
    const onPreview = vi.fn()
    const onRename = vi.fn()
    const onDelete = vi.fn()
    const onSelectionChange = vi.fn()
    render(
      <Harness
        initial={['d1']}
        onSelectionChange={onSelectionChange}
        onOpen={onOpen}
        onPreview={onPreview}
        onRename={onRename}
        onDelete={onDelete}
      />,
    )
    const first = row(/Letter 1/)
    act(() => first.focus())
    press(first, 'ArrowDown')
    const second = row(/Letter 2/)
    expect(document.activeElement).toBe(second)
    expect(onSelectionChange).toHaveBeenLastCalledWith(new Set(['d2']))
    expect(second.getAttribute('aria-selected')).toBe('true')
    expect(first.getAttribute('aria-selected')).toBe('false')

    press(second, 'Enter')
    expect(onOpen).toHaveBeenCalledExactlyOnceWith('d2')
    press(second, ' ')
    expect(onPreview).toHaveBeenCalledExactlyOnceWith('d2')
    press(second, 'F2')
    expect(onRename).toHaveBeenCalledExactlyOnceWith('d2')
    press(second, 'Delete')
    expect(onDelete).toHaveBeenCalledExactlyOnceWith(new Set(['d2']))
    // Space previewed instead of changing the selection
    expect(second.getAttribute('aria-selected')).toBe('true')
  })

  it('leaves Space to the headers, which it sorts', () => {
    const onPreview = vi.fn()
    const onSort = vi.fn()
    render(<Harness onPreview={onPreview} onSort={onSort} />)
    const header = screen.getByRole('columnheader', { name: 'Title' })
    act(() => header.focus())
    press(header, ' ')
    expect(onPreview).not.toHaveBeenCalled()
    expect(onSort).toHaveBeenCalledExactlyOnceWith({ column: 'title', direction: 'ascending' })
  })

  it('asks for more rows once the end comes into view, until it has them all', async () => {
    const loadMore = vi.fn()
    const { rerender } = render(<Harness rows={items(100)} total={250} loadMore={loadMore} />)
    expect(loadMore).not.toHaveBeenCalled()
    act(() => bringObservedIntoView())
    expect(loadMore).toHaveBeenCalledTimes(1)

    rerender(<Harness rows={items(250)} total={250} loadMore={loadMore} />)
    loadMore.mockClear()
    act(() => bringObservedIntoView())
    expect(loadMore).not.toHaveBeenCalled()
  })

  it('takes the focus itself when it has no rows, and shows the ring there', async () => {
    const user = userEvent.setup()
    render(<Harness rows={[]} empty="This folder is empty." />)
    await user.tab()
    const grid = screen.getByRole('grid')
    expect(document.activeElement).toBe(grid)
    expect(grid.getAttribute('data-focus-visible')).toBe('true')
    // jsdom draws nothing, so the rule that draws the ring is read instead
    expect(tableCss).toMatch(/\.table\[data-focus-visible\]\)::after\s*\{[^}]*box-shadow:\s*var\(--hd-focus-inset\)/)
  })

  it('leaves the keys of a control inside a row to the control', () => {
    const onPreview = vi.fn()
    const onRename = vi.fn()
    const onDelete = vi.fn()
    const withField: TableColumn<Item>[] = [
      ...columns,
      { id: 'edit', name: 'Edit', cell: (item) => <input aria-label={`Name of ${item.name}`} defaultValue={item.name} /> },
    ]
    render(<Harness columns={withField} initial={['d1']} onPreview={onPreview} onRename={onRename} onDelete={onDelete} />)
    const field = screen.getByRole('textbox', { name: 'Name of Letter 1' })
    act(() => field.focus())
    for (const key of [' ', 'F2', 'Delete', 'Backspace']) press(field, key)
    expect(onPreview).not.toHaveBeenCalled()
    expect(onRename).not.toHaveBeenCalled()
    expect(onDelete).not.toHaveBeenCalled()
  })

  it('marks itself busy while the rows it shows are about to change', () => {
    render(<Harness busy />)
    expect(screen.getByRole('grid').closest('[aria-busy="true"]')).toBeTruthy()
    // Sighted people see it too, as a strip along the top: the rows keep their colours, and so their contrast (R79)
    expect(tableCss).toMatch(/\[aria-busy='true'\]::before\s*\{/)
    expect(tableCss).not.toMatch(/\[aria-busy='true'\]\s*\{[^}]*opacity/)
  })

  it('shows what it is given when there are no rows', () => {
    render(<Harness rows={[]} empty="This folder is empty." />)
    expect(screen.getByText('This folder is empty.')).toBeTruthy()
  })

  it('passes axe with 100 rows', async () => {
    const { container } = render(<Harness rows={[folderRow, ...items(99)]} initial={['d3']} total={250} loadMore={vi.fn()} sort={{ column: 'title', direction: 'ascending' }} />)
    expect(screen.getAllByRole('row')).toHaveLength(101)
    await expectNoAxeViolations(container)
  })
})

describe('Table highlights', () => {
  it('marks a row that was just made and a row something is being dropped on, and the flash stops for reduced motion', () => {
    render(<Harness rows={[{ ...items(2)[0]!, flash: true }, { ...items(2)[1]!, dropping: true }]} />)
    const [flash, dropping] = screen.getAllByRole('row').slice(1)
    expect(flash!.getAttribute('data-flash')).toBe('true')
    expect(flash!.getAttribute('data-dropping')).toBeNull()
    expect(dropping!.getAttribute('data-dropping')).toBe('true')
    expect(tableCss).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*\.row\[data-flash\] \{\s*animation: none/)
  })

  it('drags the selected rows with the type and data the caller makes of them, or the one row when it is not selected', () => {
    const stored = new Map<string, string>()
    const data = { setData: (type: string, text: string) => void stored.set(type, text), effectAllowed: 'uninitialized' }
    const make = vi.fn((keys: string[]) => (keys.every((key) => key.startsWith('d')) ? keys.join(',') : undefined))
    render(<Harness initial={['d1', 'd2']} drag={{ type: 'application/x-test-rows', data: make }} rows={[folderRow, ...items(3)]} />)

    fireEvent.dragStart(row(/Letter 1/), { dataTransfer: data })
    expect(make).toHaveBeenLastCalledWith(['d1', 'd2'])
    expect(stored.get('application/x-test-rows')).toBe('d1,d2')

    stored.clear()
    fireEvent.dragStart(row(/Letter 3/), { dataTransfer: data })
    expect(make).toHaveBeenLastCalledWith(['d3'])
    expect(stored.get('application/x-test-rows')).toBe('d3')
  })

  it('makes the rows that can be selected draggable, and no row without drag', () => {
    const { unmount } = render(<Harness drag={{ type: 'application/x-test-rows', data: (keys) => keys.join(',') }} rows={[folderRow, ...items(1)]} />)
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('true')
    expect(row(/Pleadings/).getAttribute('draggable')).toBe('false')
    unmount()
    render(<Harness />)
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('false')
  })

  it('keeps the row named by `undraggable` undraggable through every later render, and the others draggable', () => {
    const drag = { type: 'application/x-test-rows', data: (keys: string[]) => keys.join(',') }
    // The same rows throughout, as a list that has not changed gives: the Table has to draw the change itself
    const same = items(2)
    const { rerender } = render(<Harness drag={drag} rows={same} />)
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('true')
    rerender(<Harness drag={drag} undraggable="d1" rows={same} />)
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('false')
    expect(row(/Letter 2/).getAttribute('draggable')).toBe('true')
    // A refetch draws the rows again, with new objects: the ref callbacks run again, and must still say false
    rerender(<Harness drag={drag} undraggable="d1" rows={items(2)} />)
    rerender(<Harness drag={drag} undraggable="d1" rows={items(2)} />)
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('false')
    // Once nothing is named it drags again
    rerender(<Harness drag={drag} rows={items(2)} />)
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('true')
  })

  it('does not draw the rows again to change what is draggable: React Aria would give the focus back to the row, out of its field', () => {
    const drag = { type: 'application/x-test-rows', data: (keys: string[]) => keys.join(',') }
    const same = items(2)
    const cell = vi.fn((row: Item) => row.name)
    const spied: TableColumn<Item>[] = [{ id: 'title', name: 'Title', isRowHeader: true, cell }]
    const { rerender } = render(<Harness drag={drag} rows={same} columns={spied} />)
    const drawn = cell.mock.calls.length
    rerender(<Harness drag={drag} undraggable="d1" rows={same} columns={spied} />)
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('false')
    expect(cell.mock.calls.length).toBe(drawn)
  })

  it('cancels a drag of rows that carry nothing, and leaves one that starts in a field alone', () => {
    const data = { setData: vi.fn(), effectAllowed: 'uninitialized' }
    render(
      <Harness
        drag={{ type: 'application/x-test-rows', data: (keys) => (keys.includes('f1') ? undefined : keys.join(',')) }}
        rows={[folderRow, ...items(1)]}
        columns={[{ id: 'title', name: 'Title', isRowHeader: true, cell: (item) => (item.id === 'd1' ? <input aria-label="Name" /> : item.name) }]}
      />,
    )
    expect(fireEvent.dragStart(row(/Pleadings/), { dataTransfer: data })).toBe(false)
    expect(data.setData).not.toHaveBeenCalled()
    expect(fireEvent.dragStart(screen.getByRole('textbox', { name: 'Name' }), { dataTransfer: data })).toBe(true)
    expect(data.setData).not.toHaveBeenCalled()
  })

  // jsdom works out the colours from the stylesheets and tokens.css (light), though it draws nothing
  it('draws a kind\'s icon in its colour, keeps it on Inactive selection, and draws it in the fill\'s own colour while the list has the focus', () => {
    const withIcons: TableColumn<Item>[] = [
      {
        id: 'title',
        name: 'Title',
        isRowHeader: true,
        cell: (one) => (
          <>
            <Icon name="filePdf" kind="pdf" />
            <Icon name="lock" />
            {one.name}
          </>
        ),
      },
    ]
    render(<Harness columns={withIcons} initial={['d1']} />)
    const colours = (name: RegExp) => [...row(name).querySelectorAll('svg')].map((svg) => getComputedStyle(svg).color)
    const pdf = 'rgb(196, 22, 28)'
    const secondary = 'rgb(110, 110, 115)'
    const text = 'rgb(29, 29, 31)'
    const onFill = 'rgb(255, 255, 255)'
    // The PDF's colour, then the padlock muted; selected, the padlock takes the row's text and the PDF keeps its colour
    expect(colours(/Letter 2/)).toEqual([pdf, secondary])
    expect(colours(/Letter 1/)).toEqual([pdf, text])
    act(() => row(/Letter 1/).focus())
    expect(getComputedStyle(row(/Letter 1/)).backgroundColor).toBe('rgb(12, 105, 192)')
    expect(colours(/Letter 1/)).toEqual([onFill, onFill])
    expect(colours(/Letter 2/)).toEqual([pdf, secondary])
  })

  it('puts a text cell in a box that ends in an ellipsis, with the whole text still in it', () => {
    render(<Harness rows={[{ id: 'd1', textValue: 'A long name', name: 'A very long name that will not fit in its cell' }]} />)
    const text = screen.getByText('A very long name that will not fit in its cell')
    // Not the cell itself (a flex box, which clips its text with no ellipsis), but a box of its own
    expect(text.closest('[role="rowheader"], [role="gridcell"]')).not.toBe(text)
    expect(text.className).toMatch(/text/)
    expect(tableCss).toMatch(/\.text \{[^}]*overflow: hidden;[^}]*text-overflow: ellipsis;[^}]*white-space: nowrap/)
  })
})

describe('Table resizing', () => {
  function resizing(widths: Record<string, number> = {}) {
    return { widths, onResizeEnd: vi.fn<(widths: Record<string, number>) => void>(), onReset: vi.fn<(column: string) => void>() }
  }

  const header = (name: RegExp | string) => screen.getByRole('columnheader', { name })
  const edge = (name: RegExp | string) => header(name).querySelector<HTMLElement>('.resizer')!

  // Defaults a list gives: a share of the width, and pixels
  const sized: TableColumn<Item>[] = [
    { ...columns[0]!, width: '3fr', minWidth: 220 },
    { ...columns[1]!, width: 100 },
    { ...columns[2]!, width: 120 },
  ]

  // A list that keeps its widths, as a view does: a resize's widths merged over those kept, a reset taking one away
  function Kept({ kept: initial = {}, onResizeEnd, onReset, ...rest }: Omit<HarnessProps, 'resizing'> & { kept?: Record<string, number> } & Partial<Omit<TableResizing, 'widths'>>) {
    const [widths, setWidths] = useState(initial)
    return (
      <Harness
        {...rest}
        resizing={{
          widths,
          onResizeEnd: (fixed) => {
            onResizeEnd?.(fixed)
            setWidths((kept) => ({ ...kept, ...fixed }))
          },
          onReset: (column) => {
            onReset?.(column)
            setWidths((kept) => Object.fromEntries(Object.entries(kept).filter(([id]) => id !== column)))
          },
        }}
      />
    )
  }

  /** Starts a resize of Doc # from its edge's slider and widens it by 10 pixels */
  function widenDocNumber() {
    const slider = within(header('Doc #')).getByRole('slider')
    act(() => slider.focus())
    press(slider, 'Enter')
    press(slider, 'ArrowRight')
  }

  it('draws no resizer without `resizing`, as before', () => {
    const { container } = render(<Harness />)
    expect(container.querySelector('.react-aria-ResizableTableContainer, .resizable')).toBeNull()
    for (const one of screen.getAllByRole('columnheader')) {
      expect(one.querySelector('.react-aria-ColumnResizer, .resizer')).toBeNull()
      expect(one.querySelector('input[type="range"]')).toBeNull()
      // The virtualiser sizes the heading's box; the heading itself has no width of its own
      expect(one.style.width).toBe('')
    }
  })

  it('draws a resizer on every heading\'s edge, 24 pixels wide', () => {
    render(<Harness resizing={resizing()} />)
    for (const { name } of columns) {
      // The heading keeps its own name, which its edge's slider doesn't add to
      const one = header(name)
      expect(one.querySelectorAll('.resizer')).toHaveLength(1)
      // Named for its column, for a screen reader, which hears the width as it changes
      expect(within(one).getByRole('slider', { name: `Resize ${name}` }).getAttribute('aria-valuetext')).toMatch(/^\d+ pixels$/)
    }
    // jsdom lays nothing out, so the rules are read: the target is 24 pixels, centred on the edge, with the accent's line on it
    expect(tableCss).toMatch(/\.resizer \{[^}]*inset-inline-end: -12px;[^}]*width: 24px;[^}]*cursor: col-resize/)
    expect(tableCss).toMatch(/\.resizer\[data-hovered\]::before,\s*\.resizer\[data-resizing\]::before \{[^}]*background: var\(--hd-accent-fill\)/)
    expect(tableCss).toMatch(/\.resizer::before \{[^}]*width: 2px/)
  })

  it('takes no click from the heading\'s sort, and lies in nothing that drags', async () => {
    const user = userEvent.setup()
    const onSort = vi.fn()
    render(<Harness resizing={resizing()} onSort={onSort} drag={{ type: 'application/x-test-rows', data: (keys) => keys.join(',') }} />)
    await user.click(edge('Doc #'))
    expect(onSort).not.toHaveBeenCalled()
    // The browser starts a drag only from an element that is draggable, as the rows are, which jsdom can't do: the browser
    // test that drags an edge (Task 12's) is the check that its drag resizes
    expect(row(/Letter 1/).getAttribute('draggable')).toBe('true')
    expect(edge('Doc #').closest('[draggable="true"]')).toBeNull()
    // The heading itself still sorts
    await user.click(header('Doc #'))
    expect(onSort).toHaveBeenCalledExactlyOnceWith({ column: 'docNumber', direction: 'ascending' })
  })

  it('keeps a heading\'s hover style off while the pointer is on its edge, where a click doesn\'t sort', async () => {
    const user = userEvent.setup()
    render(<Harness resizing={resizing()} onSort={vi.fn()} />)
    // jsdom works the colours out from the stylesheets and tokens.css (light)
    const secondary = 'rgb(110, 110, 115)'
    const text = 'rgb(29, 29, 31)'
    expect(getComputedStyle(header('Doc #')).color).toBe(secondary)
    await user.hover(edge('Doc #'))
    expect(header('Doc #').getAttribute('data-hovered')).toBe('true')
    expect(getComputedStyle(header('Doc #')).color).toBe(secondary)
    await user.hover(header('Doc #').querySelector('.heading')!)
    expect(getComputedStyle(header('Doc #')).color).toBe(text)
  })

  it('draws a kept width on its column', () => {
    render(<Harness resizing={resizing({ docNumber: 140 })} />)
    expect(header('Doc #').style.width).toBe('140px')
  })

  it('draws a width set from outside, and the column\'s default once it is taken away', () => {
    const { rerender } = render(<Harness columns={sized} resizing={resizing({ docNumber: 140 })} />)
    expect(header('Doc #').style.width).toBe('140px')
    rerender(<Harness columns={sized} resizing={resizing()} />)
    expect(header('Doc #').style.width).toBe('100px')
    rerender(<Harness columns={sized} resizing={resizing({ docNumber: 260 })} />)
    expect(header('Doc #').style.width).toBe('260px')
  })

  it('hands out, at a resize\'s end, the width of the column resized and of those to its left, which it fixed', () => {
    const kept = resizing()
    render(<Harness columns={sized} resizing={kept} />)
    // Enter on the edge's slider starts a resize, and Right widens the column by 10 pixels, which the heading draws at once
    widenDocNumber()
    expect(header('Doc #').style.width).toBe('110px')
    expect(kept.onResizeEnd).not.toHaveBeenCalled()
    press(within(header('Doc #')).getByRole('slider'), 'Enter')
    // Title, to its left, is fixed at its width then (jsdom's list has no width, so its least); Note, to its right, keeps its default
    expect(kept.onResizeEnd).toHaveBeenCalledExactlyOnceWith({ title: 220, docNumber: 110 })
  })

  it('widens a column as its edge is dragged, and hands out the widths when the drag ends', async () => {
    const user = userEvent.setup()
    const kept = resizing({ docNumber: 140 })
    render(<Harness resizing={kept} />)
    await user.pointer([{ keys: '[MouseLeft>]', target: edge('Doc #'), coords: { pageX: 300 } }, { coords: { pageX: 340 } }])
    expect(header('Doc #').style.width).toBe('180px')
    expect(kept.onResizeEnd).not.toHaveBeenCalled()
    await user.pointer({ keys: '[/MouseLeft]' })
    // Title, a share of the width, is fixed at React Aria's least, 75, as jsdom's list has no width
    expect(kept.onResizeEnd).toHaveBeenCalledExactlyOnceWith({ title: 75, docNumber: 180 })
  })

  it('puts a column\'s default back on a double-click on its edge', async () => {
    const user = userEvent.setup()
    const onReset = vi.fn()
    const onResizeEnd = vi.fn()
    render(<Kept columns={sized} kept={{ docNumber: 140 }} onReset={onReset} onResizeEnd={onResizeEnd} />)
    expect(header('Doc #').style.width).toBe('140px')
    await user.dblClick(edge('Doc #'))
    expect(onReset).toHaveBeenCalledExactlyOnceWith('docNumber')
    expect(header('Doc #').style.width).toBe('100px')
    // Its presses moved nothing, so they fixed nothing
    expect(onResizeEnd).not.toHaveBeenCalled()
  })

  it('ends a resize cut short by the list emptying or resizing going off: nothing kept, the kept widths drawn, the keys back', () => {
    const kept = resizing()
    const { rerender } = render(<Harness columns={sized} resizing={kept} />)
    widenDocNumber()
    expect(header('Doc #').style.width).toBe('110px')
    rerender(<Harness columns={sized} resizing={kept} rows={[]} />)
    rerender(<Harness columns={sized} resizing={kept} />)
    expect(header('Doc #').style.width).toBe('100px')
    // The list's own keys work again, which React Aria turns off for a resize
    const first = row(/Letter 1/)
    act(() => first.focus())
    press(first, 'ArrowDown')
    expect(document.activeElement).toBe(row(/Letter 2/))
    // And a width set from outside is drawn
    const later = { ...kept, widths: { docNumber: 150 } }
    rerender(<Harness columns={sized} resizing={later} />)
    expect(header('Doc #').style.width).toBe('150px')

    widenDocNumber()
    expect(header('Doc #').style.width).toBe('160px')
    rerender(<Harness columns={sized} />)
    rerender(<Harness columns={sized} resizing={later} />)
    expect(header('Doc #').style.width).toBe('150px')
    expect(kept.onResizeEnd).not.toHaveBeenCalled()
  })

  it('passes axe, and is one stop for Tab, its edges\' sliders none', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <>
        <Harness rows={[folderRow, ...items(9)]} sort={{ column: 'title', direction: 'ascending' }} resizing={resizing({ docNumber: 140 })} />
        <button>After</button>
      </>,
    )
    await expectNoAxeViolations(container)
    await user.tab()
    expect(document.activeElement).toBe(row(/Pleadings/))
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'After' }))
    await user.tab({ shift: true })
    expect(document.activeElement).toBe(row(/Pleadings/))
  })

  it('draws no edges on an empty list, which takes the focus itself and lets Tab go on', async () => {
    // React Aria takes an empty list's keys as a resize's, so an edge's slider would keep Tab
    const user = userEvent.setup()
    render(
      <>
        <Harness rows={[]} resizing={resizing({ docNumber: 140 })} />
        <button>After</button>
      </>,
    )
    expect(document.querySelector('.resizer')).toBeNull()
    expect(header('Doc #').style.width).toBe('140px')
    await user.tab()
    const grid = screen.getByRole('grid')
    expect(document.activeElement).toBe(grid)
    expect(grid.getAttribute('data-focus-visible')).toBe('true')
    // Its ring is drawn as before, with React Aria's container between the frame and the list
    expect(grid.parentElement!.className).toBe('resizable')
    expect(tableCss).toMatch(/\.frame:has\(> \.resizable > \.table\[data-focus-visible\]\)::after \{[^}]*box-shadow:\s*var\(--hd-focus-inset\)/)
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'After' }))
  })

  it('a kept layout wider than the list scrolls sideways and keeps each column\'s width', () => {
    // jsdom lays nothing out, so the list is given a width, 2,000 pixels, which React Aria measures it by
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 2000 })
    try {
      const { rerender } = render(<Harness resizing={resizing()} />)
      const grid = screen.getByRole('grid')
      // What the list holds, which the virtualiser sizes
      const content = () => Number.parseFloat((grid.firstElementChild as HTMLElement).style.width)
      // Shares of the width fill the list
      expect(screen.getAllByRole('columnheader').map((one) => one.style.width)).toEqual(['667px', '666px', '667px'])
      expect(content()).toBe(grid.clientWidth)

      rerender(<Harness resizing={resizing({ title: 1000, docNumber: 900, note: 1100 })} />)
      // Each column is drawn at its width, its box the virtualiser's too: none is squeezed to fit
      const headers = screen.getAllByRole('columnheader')
      expect(headers.map((one) => one.style.width)).toEqual(['1000px', '900px', '1100px'])
      expect(headers.map((one) => one.parentElement!.style.width)).toEqual(['1000px', '900px', '1100px'])
      // So what it holds is wider than the list, which scrolls sideways: React Aria leaves the box's overflow to the stylesheet
      expect(content()).toBe(3000)
      expect(content()).toBeGreaterThan(grid.clientWidth)
      expect(getComputedStyle(grid).overflowX).toBe('auto')
      // Rows keep their height: nothing wraps
      expect(row(/Letter 1/).parentElement!.style.height).toBe('34px')
    } finally {
      Reflect.deleteProperty(HTMLElement.prototype, 'clientWidth')
    }
  })
})

describe('Table right-click menu', () => {
  const contextMenu = (onOpen = vi.fn()) => ({
    label: (key: string) => `Actions for ${key}`,
    // The folder row has none, so it keeps the browser's menu
    items: (key: string) => (key.startsWith('d') ? <MenuItem id="open">Open {key}</MenuItem> : undefined),
    onOpen,
  })

  it('opens a row\'s menu on a right-click, and the right-click selects nothing by itself', async () => {
    const onSelectionChange = vi.fn()
    const onOpen = vi.fn()
    render(<Harness rows={[folderRow, ...items(2)]} initial={['d1', 'd2']} onSelectionChange={onSelectionChange} contextMenu={contextMenu(onOpen)} />)
    const target = row(/Letter 2/)
    fireEvent.pointerDown(target, { button: 2, pointerType: 'mouse' })
    fireEvent.mouseDown(target, { button: 2 })
    expect(fireEvent.contextMenu(target)).toBe(false)
    expect(await screen.findByRole('menu', { name: 'Actions for d2' })).toBeTruthy()
    expect(onOpen).toHaveBeenCalledExactlyOnceWith('d2')
    expect(onSelectionChange).not.toHaveBeenCalled()
    expect(target.getAttribute('draggable')).toBe('false')
  })

  it('opens on Shift+F10 on the focused row, and leaves the browser\'s menu on a header and on a row with none', async () => {
    render(<Harness rows={[folderRow, ...items(2)]} contextMenu={contextMenu()} />)
    expect(fireEvent.contextMenu(screen.getByRole('columnheader', { name: 'Title' }))).toBe(true)
    expect(fireEvent.contextMenu(row(/Pleadings/))).toBe(true)
    const target = row(/Letter 1/)
    act(() => target.focus())
    fireEvent.keyDown(target, { key: 'F10', shiftKey: true })
    expect(await screen.findByRole('menu', { name: 'Actions for d1' })).toBeTruthy()
  })

  it('takes a Mac\'s Control-click on a row as its right-click, not as a click that selects', async () => {
    const platform = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
    // Restored whether the test passes or not, so no later test runs on a Mac
    try {
      const onSelectionChange = vi.fn()
      render(<Harness initial={['d1', 'd2']} onSelectionChange={onSelectionChange} contextMenu={contextMenu()} />)
      const target = within(row(/Letter 2/)).getByRole('rowheader')
      fireEvent.pointerDown(target, { button: 0, ctrlKey: true, pointerType: 'mouse' })
      fireEvent.mouseDown(target, { button: 0, ctrlKey: true })
      fireEvent.contextMenu(target, { button: 0, ctrlKey: true })
      fireEvent.pointerUp(target, { button: 0, ctrlKey: true, pointerType: 'mouse' })
      fireEvent.mouseUp(target, { button: 0, ctrlKey: true })
      expect(await screen.findByRole('menu', { name: 'Actions for d2' })).toBeTruthy()
      expect(onSelectionChange).not.toHaveBeenCalled()
    } finally {
      platform.mockRestore()
    }
  })
})

// A list that leads to a page (the Admin app's): nothing is selected, and a click or Enter opens the row
describe('Table with selectionMode="none"', () => {
  function Plain(props: Partial<Omit<TableProps<Item>, 'selectionMode' | 'selection' | 'onSelectionChange'>>) {
    return <Table aria-label="Matters" columns={columns} rows={items(3)} selectionMode="none" {...props} />
  }

  it('selects no row and draws no checkbox or selected state, by click or by keys', async () => {
    const user = userEvent.setup()
    const { container } = render(<Plain />)
    const grid = screen.getByRole('grid', { name: 'Matters' })
    expect(grid.hasAttribute('aria-multiselectable')).toBe(false)
    await user.click(within(row(/Letter 2/)).getByRole('rowheader'))
    const second = row(/Letter 2/)
    act(() => second.focus())
    press(second, 'ArrowDown')
    press(row(/Letter 3/), ' ')
    for (const one of screen.getAllByRole('row')) {
      expect(one.hasAttribute('aria-selected')).toBe(false)
      expect(one.hasAttribute('data-selected')).toBe(false)
    }
    expect(screen.queryAllByRole('checkbox')).toEqual([])
    expect(container.querySelector('[data-selected]')).toBeNull()
    await expectNoAxeViolations(container)
  })

  it('needs the selection and its handler in every other mode, at type-check time', () => {
    // @ts-expect-error a list that selects is given the selection it keeps
    const multiple = <Table aria-label="Matters" columns={columns} rows={items(1)} selectionMode="multiple" />
    // @ts-expect-error single, the default, too
    const single = <Table aria-label="Matters" columns={columns} rows={items(1)} />
    expect([multiple, single]).toHaveLength(2)
  })

  it('opens a row on a click and on Enter, and deletes the focused row', async () => {
    const user = userEvent.setup()
    const onOpen = vi.fn()
    const onDelete = vi.fn()
    render(<Plain onOpen={onOpen} onDelete={onDelete} />)
    await user.click(within(row(/Letter 2/)).getByRole('rowheader'))
    expect(onOpen).toHaveBeenLastCalledWith('d2')
    const first = row(/Letter 1/)
    act(() => first.focus())
    press(first, 'Enter')
    expect(onOpen).toHaveBeenLastCalledWith('d1')
    press(first, 'Delete')
    expect(onDelete).toHaveBeenCalledExactlyOnceWith(new Set(['d1']))
  })
})
