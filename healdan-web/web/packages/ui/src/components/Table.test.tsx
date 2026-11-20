// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, createEvent, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { bringObservedIntoView } from '../test/intersection'
import '../tokens.css'
import { Icon } from '../icons'
import { COLUMN_TYPE, Table, type TableColumn, type TableProps, type TableResizing, type TableRow, type TableSort } from './Table'
import tableCss from './Table.module.css?raw'
import { ColumnsPanel } from './ColumnsPanel'
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

// Defaults a list gives: a share of the width, and pixels
const sized: TableColumn<Item>[] = [
  { ...columns[0]!, width: '3fr', minWidth: 220 },
  { ...columns[1]!, width: 100 },
  { ...columns[2]!, width: 120 },
]

// A document list's columns: Author comes after Title, so Doc # can move either way
const listColumns: TableColumn<Item>[] = [columns[0]!, { id: 'author', name: 'Author', allowsSorting: true, cell: () => 'Ann' }, columns[1]!, columns[2]!]

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

  it("a newest-first column's first click sorts descending", () => {
    const onSort = vi.fn<(sort: TableSort) => void>()
    const dated: TableColumn<Item>[] = [...columns, { id: 'edited', name: 'Edit Date', allowsSorting: true, newestFirst: true, cell: () => '' }]
    const { rerender } = render(<Harness columns={dated} onSort={onSort} sort={{ column: 'title', direction: 'ascending' }} />)
    fireEvent.click(screen.getByRole('columnheader', { name: 'Edit Date' }))
    expect(onSort).toHaveBeenLastCalledWith({ column: 'edited', direction: 'descending' })
    // Then the other way, as any column
    rerender(<Harness columns={dated} onSort={onSort} sort={{ column: 'edited', direction: 'descending' }} />)
    fireEvent.click(screen.getByRole('columnheader', { name: /Edit Date/ }))
    expect(onSort).toHaveBeenLastCalledWith({ column: 'edited', direction: 'ascending' })
    // A column that isn't newest first still starts ascending
    fireEvent.click(screen.getByRole('columnheader', { name: 'Doc #' }))
    expect(onSort).toHaveBeenLastCalledWith({ column: 'docNumber', direction: 'ascending' })
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

describe('Table heading menu', () => {
  const header = (name: RegExp | string) => screen.getByRole('columnheader', { name })

  // What every list can switch on
  function options() {
    return {
      onSort: vi.fn<(sort: TableSort) => void>(),
      resizing: { widths: {}, onResizeEnd: vi.fn<(widths: Record<string, number>) => void>(), onReset: vi.fn<(column: string) => void>() },
      onResetColumns: vi.fn<() => void>(),
    }
  }

  // What a document list switches on besides
  const choice = () => ({ onMove: vi.fn<(column: string, to: number) => void>(), onHide: vi.fn<(column: string) => void>(), panel: null })

  /** The open menu's items in order, a separator as a dash */
  const entries = (menu: HTMLElement) =>
    [...menu.querySelectorAll('[role^="menuitem"], [role="separator"]')].map((one) => (one.getAttribute('role') === 'separator' ? '—' : one.textContent))

  async function openMenu(name: string) {
    expect(fireEvent.contextMenu(header(name))).toBe(false)
    return screen.findByRole('menu', { name: `${name} Column` })
  }

  async function closeMenu() {
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    fireEvent.keyUp(document.activeElement!, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  }

  it('opens a heading\'s menu on a right-click and on Shift+F10, its first item focused, and gives the focus back on Esc', async () => {
    const { rerender } = render(<Harness onSort={vi.fn()} />)
    await openMenu('Doc #')
    await closeMenu()
    await waitFor(() => expect(document.activeElement).toBe(header('Doc #')))

    // The arrows reach the headings from the rows
    const first = row(/Letter 1/)
    act(() => first.focus())
    press(first, 'ArrowUp')
    expect(document.activeElement).toBe(header('Title'))
    press(header('Title'), 'ArrowRight')
    expect(document.activeElement).toBe(header('Doc #'))
    fireEvent.keyDown(header('Doc #'), { key: 'F10', shiftKey: true })
    const menu = await screen.findByRole('menu', { name: 'Doc # Column' })
    await waitFor(() => expect(document.activeElement).toBe(within(menu).getByRole('menuitemradio', { name: 'Sort Ascending' })))
    await closeMenu()
    await waitFor(() => expect(document.activeElement).toBe(header('Doc #')))

    // And the menu key
    fireEvent.keyDown(header('Doc #'), { key: 'ContextMenu' })
    expect(await screen.findByRole('menu', { name: 'Doc # Column' })).toBeTruthy()
    await closeMenu()

    // A heading whose column resizes has its focus on its edge's slider (React Aria's), where its menu opens from
    rerender(<Harness {...options()} />)
    act(() => row(/Letter 1/).focus())
    press(row(/Letter 1/), 'ArrowUp')
    const slider = within(header('Title')).getByRole('slider')
    expect(document.activeElement).toBe(slider)
    // jsdom lays nothing out: the heading and its edge's slider, hidden at the heading's end, are given their boxes
    header('Title').getBoundingClientRect = () => new DOMRect(40, 0, 220, 34)
    slider.getBoundingClientRect = () => new DOMRect(247, 0, 1, 1)
    fireEvent.keyDown(slider, { key: 'F10', shiftKey: true })
    const resizable = await screen.findByRole('menu', { name: 'Title Column' })
    // Below the heading, at its start, not by the slider over the next heading
    const popover = resizable.closest<HTMLElement>('.popover')!
    expect({ left: popover.style.left, top: popover.style.top }).toEqual({ left: '40px', top: '36px' })
    await waitFor(() => expect(document.activeElement).toBe(within(resizable).getByRole('menuitemradio', { name: 'Sort Ascending' })))
    await closeMenu()
    await waitFor(() => expect(document.activeElement).toBe(slider))
  })

  it('offers Sort Ascending, Sort Descending, Resize Column and Reset Columns on every list, and Move Left, Move Right, Hide Doc #, Choose Columns… on a document list', async () => {
    const sort = { column: 'docNumber', direction: 'ascending' } as const
    const { rerender } = render(<Harness columns={listColumns} sort={sort} {...options()} />)
    let menu = await openMenu('Doc #')
    expect(entries(menu)).toEqual(['Sort Ascending', 'Sort Descending', '—', 'Resize Column', '—', 'Reset Columns'])
    // The current sort is ticked, drawn and as a screen reader hears it
    const ascending = within(menu).getByRole('menuitemradio', { name: 'Sort Ascending' })
    const descending = within(menu).getByRole('menuitemradio', { name: 'Sort Descending' })
    expect([ascending.getAttribute('aria-checked'), descending.getAttribute('aria-checked')]).toEqual(['true', 'false'])
    expect([ascending.querySelector('.check'), descending.querySelector('.check')].map(Boolean)).toEqual([true, false])
    await closeMenu()

    rerender(<Harness columns={listColumns} sort={sort} {...options()} choice={choice()} />)
    menu = await openMenu('Doc #')
    expect(entries(menu)).toEqual(['Sort Ascending', 'Sort Descending', '—', 'Resize Column', '—', 'Move Left', 'Move Right', 'Hide Doc #', '—', 'Choose Columns…', 'Reset Columns'])
    await expectNoAxeViolations(menu)
  })

  it('leaves Move and Hide off Title, Move Left off the column after Title, and Move Right off the last', async () => {
    render(<Harness columns={listColumns} {...options()} choice={choice()} />)
    expect(entries(await openMenu('Title'))).toEqual(['Sort Ascending', 'Sort Descending', '—', 'Resize Column', '—', 'Choose Columns…', 'Reset Columns'])
    await closeMenu()
    expect(entries(await openMenu('Author'))).toEqual(['Sort Ascending', 'Sort Descending', '—', 'Resize Column', '—', 'Move Right', 'Hide Author', '—', 'Choose Columns…', 'Reset Columns'])
    await closeMenu()
    // Note doesn't sort, so it has no Sort items either
    expect(entries(await openMenu('Note'))).toEqual(['Resize Column', '—', 'Move Left', 'Hide Note', '—', 'Choose Columns…', 'Reset Columns'])
  })

  it('moves a column to the place it ends at, hides it and resets the columns, and the panel Choose Columns… opens gives the focus back to the heading', async () => {
    const user = userEvent.setup()
    const chosen = choice()
    const given = { onSort: vi.fn(), onResetColumns: vi.fn() }
    render(<Harness columns={listColumns} {...given} choice={chosen} />)
    await user.click(within(await openMenu('Doc #')).getByRole('menuitem', { name: 'Move Left' }))
    expect(chosen.onMove).toHaveBeenLastCalledWith('docNumber', 1)
    await user.click(within(await openMenu('Doc #')).getByRole('menuitem', { name: 'Move Right' }))
    expect(chosen.onMove).toHaveBeenLastCalledWith('docNumber', 3)
    await user.click(within(await openMenu('Doc #')).getByRole('menuitem', { name: 'Hide Doc #' }))
    expect(chosen.onHide).toHaveBeenCalledExactlyOnceWith('docNumber')
    await user.click(within(await openMenu('Doc #')).getByRole('menuitem', { name: 'Reset Columns' }))
    expect(given.onResetColumns).toHaveBeenCalledOnce()
    await user.click(within(await openMenu('Doc #')).getByRole('menuitem', { name: 'Choose Columns…' }))
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    // The Columns panel (which this list leaves empty), until Esc closes it
    await screen.findByRole('dialog')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(header('Doc #')))
    expect(chosen.onMove).toHaveBeenCalledTimes(2)
    expect(given.onSort).not.toHaveBeenCalled()
  })

  it('offers Sort by Relevance only while sorted, and calls relevance.onSelect', async () => {
    const user = userEvent.setup()
    const relevance = { onSelect: vi.fn() }
    const { rerender } = render(<Harness onSort={vi.fn()} relevance={relevance} />)
    expect(entries(await openMenu('Doc #'))).toEqual(['Sort Ascending', 'Sort Descending'])
    await closeMenu()

    rerender(<Harness onSort={vi.fn()} relevance={relevance} sort={{ column: 'title', direction: 'ascending' }} />)
    const menu = await openMenu('Doc #')
    expect(entries(menu)).toEqual(['Sort Ascending', 'Sort Descending', 'Sort by Relevance'])
    // A plain item, not one of the sorts' choices: it never shows while it's the list's order, so it would never be checked
    const byRelevance = within(menu).getByRole('menuitem', { name: 'Sort by Relevance' })
    expect(byRelevance.hasAttribute('aria-checked')).toBe(false)
    await closeMenu()
    // On a column that doesn't sort, it's the only item
    expect(entries(await openMenu('Note'))).toEqual(['Sort by Relevance'])
    expect(within(screen.getByRole('menu')).queryAllByRole('menuitemradio')).toEqual([])
    await user.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Sort by Relevance' }))
    expect(relevance.onSelect).toHaveBeenCalledOnce()
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  })

  it('Sort Descending on a heading sorts by it', async () => {
    const user = userEvent.setup()
    const onSort = vi.fn()
    render(<Harness onSort={onSort} />)
    await user.click(within(await openMenu('Doc #')).getByRole('menuitemradio', { name: 'Sort Descending' }))
    expect(onSort).toHaveBeenCalledExactlyOnceWith({ column: 'docNumber', direction: 'descending' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    // "Ascending" is ascending whatever the column
    await user.click(within(await openMenu('Doc #')).getByRole('menuitemradio', { name: 'Sort Ascending' }))
    expect(onSort).toHaveBeenLastCalledWith({ column: 'docNumber', direction: 'ascending' })
  })

  it('Resize Column starts a resize by keys, which the arrows widen and Enter ends; an empty list has none to start', async () => {
    const user = userEvent.setup()
    const given = options()
    const { rerender } = render(<Harness columns={sized} {...given} />)
    await user.click(within(await openMenu('Doc #')).getByRole('menuitem', { name: 'Resize Column' }))
    const slider = within(header('Doc #')).getByRole('slider')
    await waitFor(() => expect(document.activeElement).toBe(slider))
    press(slider, 'ArrowRight')
    expect(header('Doc #').style.width).toBe('110px')
    press(slider, 'Enter')
    expect(given.resizing.onResizeEnd).toHaveBeenCalledExactlyOnceWith({ title: 220, docNumber: 110 })

    // An empty list draws no edges, so nothing would start a resize
    rerender(<Harness columns={sized} {...given} rows={[]} />)
    expect(entries(await openMenu('Doc #'))).toEqual(['Sort Ascending', 'Sort Descending', '—', 'Reset Columns'])
  })

  it('keeps the browser\'s menu on a heading when no option is given', () => {
    const { rerender } = render(<Harness />)
    for (const one of screen.getAllByRole('columnheader')) expect(fireEvent.contextMenu(one)).toBe(true)
    const title = header('Title')
    act(() => title.focus())
    expect(fireEvent.keyDown(title, { key: 'F10', shiftKey: true })).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
    // Nor on a heading whose menu would offer nothing: Note doesn't sort, and nothing else is switched on
    rerender(<Harness onSort={vi.fn()} />)
    expect(fireEvent.contextMenu(header('Note'))).toBe(true)
    expect(fireEvent.contextMenu(header('Doc #'))).toBe(false)
  })
})

describe('Table heading drag', () => {
  const header = (name: RegExp | string) => screen.getByRole('columnheader', { name })
  const choice = () => ({ onMove: vi.fn<(column: string, to: number) => void>(), onHide: vi.fn<(column: string) => void>(), panel: null })
  const rowDrag = () => ({ type: 'application/x-test-rows', data: vi.fn((keys: string[]) => keys.join(',')) })
  // A press of the mouse's main button that React Aria takes as one: jsdom's pointer events would otherwise read as a screen
  // reader's, which React Aria leaves to the click
  const down = { button: 0, pointerType: 'mouse', pointerId: 1, pressure: 0.5 }

  /** What a browser's DataTransfer does in a drag, which jsdom lacks: one is carried from the dragstart to the dragend */
  function transfer(types: string[] = []) {
    const data = new Map(types.map((type) => [type, '']))
    return {
      get types() {
        return [...data.keys()]
      },
      setData: (type: string, value: string) => void data.set(type, value),
      getData: (type: string) => data.get(type) ?? '',
      effectAllowed: 'uninitialized',
      dropEffect: 'none',
    }
  }
  type Transfer = ReturnType<typeof transfer>

  /**
   * A drag event on `target`, with the pointer at `clientX`: jsdom has no DragEvent, so the place is set on the event. False
   * when it was cancelled, as a dragover is where the browser would drop.
   */
  function dragEvent(type: 'dragStart' | 'dragEnter' | 'dragOver' | 'dragLeave' | 'drop' | 'dragEnd', target: Element, dataTransfer: Transfer, clientX = 0) {
    const event = createEvent[type](target, { dataTransfer })
    Object.defineProperty(event, 'clientX', { value: clientX })
    return fireEvent(target, event)
  }

  // jsdom lays nothing out: the headings are given their boxes, Title 0 to 220, Author to 320, Doc # to 420 and Note to 540
  function layOut() {
    let left = 0
    for (const [name, width] of [['Title', 220], ['Author', 100], ['Doc #', 100], ['Note', 120]] as const) {
      const box = new DOMRect(left, 0, width, 34)
      header(name).getBoundingClientRect = () => box
      left += width
    }
  }

  /** Drags the heading named `from` and drops it over the heading named `over`, at `clientX`, as a browser does */
  function dragHeading(from: string, over: string, clientX: number) {
    const data = transfer()
    dragEvent('dragStart', header(from), data)
    dragEvent('dragEnter', header(over), data, clientX)
    dragEvent('dragOver', header(over), data, clientX)
    dragEvent('drop', header(over), data, clientX)
    dragEvent('dragEnd', header(from), data)
  }

  /** The headings the drop line is drawn on, and where on them */
  const lines = () => screen.getAllByRole('columnheader').flatMap((one) => (one.hasAttribute('data-drop-line') ? [`${one.textContent} ${one.getAttribute('data-drop-line')}`] : []))

  it('drags a heading between two others and reports the move', () => {
    const chosen = choice()
    // A drop zone around the list, as a folder's view has, which would take a drop it heard of as one with no files
    const zone = vi.fn()
    render(
      <div onDrop={zone}>
        <Harness columns={listColumns} choice={chosen} />
      </div>,
    )
    layOut()
    // Author past Doc #'s midpoint (370) goes after Doc #, so it ends where Doc # is now
    dragHeading('Author', 'Doc #', 390)
    expect(chosen.onMove).toHaveBeenCalledExactlyOnceWith('author', 2)
    // Note before Author's midpoint (270) goes before Author
    dragHeading('Note', 'Author', 240)
    expect(chosen.onMove).toHaveBeenLastCalledWith('note', 1)
    expect(chosen.onMove).toHaveBeenCalledTimes(2)
    expect(zone).not.toHaveBeenCalled()
  })

  it('drops a heading past the last one\'s midpoint at the end', () => {
    const chosen = choice()
    render(<Harness columns={listColumns} choice={chosen} />)
    layOut()
    // Past Note's midpoint (480), after Note: the last place
    dragHeading('Author', 'Note', 500)
    expect(chosen.onMove).toHaveBeenCalledExactlyOnceWith('author', listColumns.length - 1)
  })

  it('keeps each heading\'s ref while the headings draw again, as at each step of a drag over them, so React doesn\'t set it again', () => {
    render(<Harness columns={listColumns} choice={choice()} />)
    layOut()
    const author = header('Author')
    const set = vi.spyOn(author, 'draggable', 'set')
    const data = transfer()
    dragEvent('dragStart', author, data)
    dragEvent('dragEnter', header('Doc #'), data, 390)
    dragEvent('dragOver', header('Doc #'), data, 390)
    // The headings drew again, each time for the line
    expect(lines()).toEqual(['Doc # edge'])
    dragEvent('dragOver', header('Note'), data, 500)
    expect(lines()).toEqual(['Note end'])
    dragEvent('dragEnd', author, data)
    expect(lines()).toEqual([])
    expect(set).not.toHaveBeenCalled()
    expect(author.getAttribute('draggable')).toBe('true')
  })

  it('starts nothing from the Columns button, which is no heading, and takes no heading dropped on it', () => {
    const chosen = choice()
    const onSort = vi.fn()
    render(<Harness columns={listColumns} choice={chosen} onSort={onSort} />)
    layOut()
    const button = screen.getByRole('button', { name: 'Columns' })
    const fromButton = transfer()
    expect(dragEvent('dragStart', button, fromButton)).toBe(false)
    expect(fromButton.types).toEqual([])
    // Nor from the place it sits in, round it
    const fromPlace = transfer()
    expect(dragEvent('dragStart', button.parentElement!, fromPlace)).toBe(false)
    expect(fromPlace.types).toEqual([])
    // A heading dragged over it isn't dropped there
    const heading = transfer()
    dragEvent('dragStart', header('Author'), heading)
    dragEvent('dragEnter', button, heading, 530)
    expect(dragEvent('dragOver', button, heading, 530)).toBe(true)
    expect(lines()).toEqual([])
    dragEvent('drop', button, heading, 530)
    dragEvent('dragEnd', header('Author'), heading)
    expect(chosen.onMove).not.toHaveBeenCalled()
    expect(onSort).not.toHaveBeenCalled()
  })

  it('carries only the column\'s type, never the rows\'', () => {
    const rows = rowDrag()
    render(<Harness columns={listColumns} choice={choice()} drag={rows} />)
    const data = transfer()
    expect(dragEvent('dragStart', header('Author'), data)).toBe(true)
    expect(data.types).toEqual([COLUMN_TYPE])
    expect(data.getData(COLUMN_TYPE)).toBe('author')
    expect(data.effectAllowed).toBe('move')
    expect(rows.data).not.toHaveBeenCalled()
    // A row's drag is as it was
    const fromRow = transfer()
    dragEvent('dragStart', row(/Letter 1/), fromRow)
    expect(fromRow.types).toEqual(['application/x-test-rows'])
  })

  it('makes the headings draggable only with `choice`, and Title never', () => {
    const draggable = () => screen.getAllByRole('columnheader').map((one) => one.getAttribute('draggable'))
    const { rerender } = render(<Harness columns={listColumns} onSort={vi.fn()} />)
    expect(draggable()).toEqual([null, null, null, null])
    // Nor do its headings take a heading's drag
    expect(dragEvent('dragOver', header('Doc #'), transfer([COLUMN_TYPE]), 390)).toBe(true)
    rerender(<Harness columns={listColumns} onSort={vi.fn()} choice={choice()} />)
    expect(draggable()).toEqual([null, 'true', 'true', 'true'])
    rerender(<Harness columns={listColumns} onSort={vi.fn()} />)
    expect(draggable()).toEqual([null, null, null, null])
  })

  it('can\'t drag Title, nor drop before it', () => {
    const chosen = choice()
    render(<Harness columns={listColumns} choice={chosen} />)
    layOut()
    const data = transfer()
    expect(dragEvent('dragStart', header('Title'), data)).toBe(false)
    expect(data.types).toEqual([])
    // Doc # before Title's midpoint (110) goes after Title, as nothing goes before it
    dragHeading('Doc #', 'Title', 20)
    expect(chosen.onMove).toHaveBeenCalledExactlyOnceWith('docNumber', 1)
  })

  it('a drag that ends where it began moves nothing, and sorts nothing', async () => {
    const chosen = choice()
    const onSort = vi.fn()
    render(<Harness columns={listColumns} choice={chosen} onSort={onSort} />)
    layOut()
    // On Author itself, either side of its midpoint, and past Title's, where Author already is
    for (const [over, clientX] of [['Author', 230], ['Author', 310], ['Title', 200]] as const) {
      fireEvent.pointerDown(header('Author'), down)
      dragHeading('Author', over, clientX)
      // A release after the drag, should a browser send one, is no click: React Aria ended the press when the drag started
      fireEvent.pointerUp(header('Author'), down)
    }
    // Nor a drag that ends with no drop: let go outside the list, or cancelled with Esc
    const data = transfer()
    dragEvent('dragStart', header('Author'), data)
    dragEvent('dragEnter', header('Note'), data, 500)
    dragEvent('dragOver', header('Note'), data, 500)
    dragEvent('dragEnd', header('Author'), data)
    // React Aria clicks a released press itself after 80 ms where no click came, so the check waits that out
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(chosen.onMove).not.toHaveBeenCalled()
    expect(onSort).not.toHaveBeenCalled()
  })

  it('a press that doesn\'t move sorts as a click does, and the keys sort as before', async () => {
    const user = userEvent.setup()
    const onSort = vi.fn()
    render(<Harness columns={listColumns} choice={choice()} onSort={onSort} />)
    const author = header('Author')
    expect(author.getAttribute('draggable')).toBe('true')
    // Nothing takes the press's default, from which the browser starts its drag (only a browser drags: Task 12's test does)
    expect(fireEvent.pointerDown(author, down)).toBe(true)
    expect(fireEvent.mouseDown(author, { button: 0 })).toBe(true)
    fireEvent.pointerCancel(author, down)
    expect(onSort).not.toHaveBeenCalled()
    await user.click(author)
    expect(onSort).toHaveBeenCalledExactlyOnceWith({ column: 'author', direction: 'ascending' })
    act(() => author.focus())
    press(author, 'Enter')
    expect(onSort).toHaveBeenCalledTimes(2)
    expect(onSort).toHaveBeenLastCalledWith({ column: 'author', direction: 'ascending' })
  })

  it('starts nothing from a heading\'s edge, which resizes', () => {
    const resizing = { widths: {}, onResizeEnd: vi.fn(), onReset: vi.fn() }
    render(<Harness columns={listColumns} choice={choice()} resizing={resizing} />)
    const author = header('Author')
    const edge = author.querySelector<HTMLElement>('.resizer')!
    // The edge lies over the next heading's first 12 pixels too, so a drag from it is a resize's, whichever heading it's on
    const fromEdge = transfer()
    expect(dragEvent('dragStart', edge, fromEdge)).toBe(false)
    expect(fromEdge.types).toEqual([])
    // A browser fires the drag at the heading, the element that drags, whatever in it was pressed: the press says where it began
    const pressed = transfer()
    fireEvent.pointerDown(edge, down)
    expect(dragEvent('dragStart', author, pressed)).toBe(false)
    fireEvent.pointerUp(edge, down)
    expect(pressed.types).toEqual([])
    // A press on the heading's name drags it
    const fromName = transfer()
    fireEvent.pointerDown(author.querySelector('.heading')!, down)
    expect(dragEvent('dragStart', author, fromName)).toBe(true)
    expect(fromName.types).toEqual([COLUMN_TYPE])
    dragEvent('dragEnd', author, fromName)
    expect(resizing.onResizeEnd).not.toHaveBeenCalled()
  })

  it('takes neither a row\'s drag nor files over the headings, and a heading\'s drag is taken nowhere but its own headings', () => {
    const chosen = choice()
    render(<Harness columns={listColumns} choice={chosen} drag={rowDrag()} />)
    layOut()
    // A row's drag: no cancelled dragover, so no drop there, and no line
    const rows = transfer()
    dragEvent('dragStart', row(/Letter 1/), rows)
    dragEvent('dragEnter', header('Doc #'), rows, 390)
    expect(dragEvent('dragOver', header('Doc #'), rows, 390)).toBe(true)
    expect(lines()).toEqual([])
    dragEvent('dragEnd', row(/Letter 1/), rows)
    // Files from the computer
    expect(dragEvent('dragOver', header('Doc #'), transfer(['Files']), 390)).toBe(true)
    expect(lines()).toEqual([])
    // A heading's drag over a row
    const heading = transfer()
    dragEvent('dragStart', header('Author'), heading)
    expect(dragEvent('dragOver', row(/Letter 1/), heading, 390)).toBe(true)
    expect(lines()).toEqual([])
    // Over its own headings it is taken
    expect(dragEvent('dragOver', header('Doc #'), heading, 390)).toBe(false)
    dragEvent('dragEnd', header('Author'), heading)
    expect(chosen.onMove).not.toHaveBeenCalled()
  })

  it('takes no heading dragged from another list', () => {
    const documents = choice()
    const folders = choice()
    render(
      <>
        <Harness aria-label="Documents" columns={listColumns} choice={documents} />
        <Harness aria-label="Folders" columns={listColumns} choice={folders} />
      </>,
    )
    const headingOf = (list: string, name: string) => within(screen.getByRole('grid', { name: list })).getByRole('columnheader', { name })
    const data = transfer()
    dragEvent('dragStart', headingOf('Documents', 'Author'), data)
    expect(dragEvent('dragOver', headingOf('Folders', 'Note'), data, 500)).toBe(true)
    dragEvent('drop', headingOf('Folders', 'Note'), data, 500)
    dragEvent('dragEnd', headingOf('Documents', 'Author'), data)
    expect(folders.onMove).not.toHaveBeenCalled()
    expect(documents.onMove).not.toHaveBeenCalled()
  })

  it('draws a line where the heading would go while it\'s over the headings, gone once it leaves them, drops or ends', () => {
    render(<Harness columns={listColumns} choice={choice()} />)
    layOut()
    const data = transfer()
    dragEvent('dragStart', header('Author'), data)
    dragEvent('dragEnter', header('Author'), data, 270)
    dragEvent('dragOver', header('Author'), data, 270)
    // Where it is, it would go nowhere: no line
    expect(lines()).toEqual([])
    dragEvent('dragEnter', header('Doc #'), data, 390)
    dragEvent('dragLeave', header('Author'), data, 390)
    dragEvent('dragOver', header('Doc #'), data, 390)
    // On the edge between Doc # and Note
    expect(lines()).toEqual(['Doc # edge'])
    dragEvent('dragEnter', header('Note'), data, 500)
    dragEvent('dragLeave', header('Doc #'), data, 500)
    dragEvent('dragOver', header('Note'), data, 500)
    // After the last heading, at the list's edge
    expect(lines()).toEqual(['Note end'])
    // Down out of the headings, onto a row
    dragEvent('dragEnter', row(/Letter 1/), data, 500)
    dragEvent('dragLeave', header('Note'), data, 500)
    expect(lines()).toEqual([])
    // Back, and dropped
    dragEvent('dragEnter', header('Doc #'), data, 390)
    dragEvent('dragOver', header('Doc #'), data, 390)
    expect(lines()).toEqual(['Doc # edge'])
    dragEvent('drop', header('Doc #'), data, 390)
    expect(lines()).toEqual([])
    dragEvent('dragEnd', header('Author'), data)
    // A drag that ends with no drop
    const again = transfer()
    dragEvent('dragStart', header('Author'), again)
    dragEvent('dragEnter', header('Doc #'), again, 390)
    dragEvent('dragOver', header('Doc #'), again, 390)
    expect(lines()).toEqual(['Doc # edge'])
    dragEvent('dragEnd', header('Author'), again)
    expect(lines()).toEqual([])
    // jsdom draws nothing, so the rules are read: 2 pixels of the accent on the edge, and inside the last heading
    expect(tableCss).toMatch(/\.column\[data-drop-line\]::after \{[^}]*inset-inline-end: -1px;[^}]*width: 2px;[^}]*background: var\(--hd-accent-fill\)/)
    expect(tableCss).toMatch(/\.column\[data-drop-line='end'\]::after \{[^}]*inset-inline-end: 0/)
  })
})

describe('Table Columns button and panel', () => {
  const header = (name: RegExp | string) => screen.getByRole('columnheader', { name })
  const columnsButton = () => screen.getByRole('button', { name: 'Columns' })
  // While the panel is open React Aria hides the rest of the page from a screen reader, so the list is read past that
  const headings = () => [...document.querySelectorAll('[data-column-key]')].map((one) => one.getAttribute('data-column-key'))
  const offered: TableColumn<Item>[] = [...listColumns, { id: 'client', name: 'Client', cell: () => 'Mason Holdings' }]
  const DEFAULT = listColumns.map((column) => column.id)

  /** A document list that keeps its columns, as a view does: what the panel changes comes back as the list's columns, at once */
  function Chosen(props: HarnessProps) {
    const [shown, setShown] = useState(DEFAULT)
    const move = (id: string, to: number) =>
      setShown((before) => {
        const others = before.filter((other) => other !== id)
        others.splice(to, 0, id)
        return others
      })
    const hide = (id: string) => setShown((before) => before.filter((other) => other !== id))
    return (
      <Harness
        onSort={() => undefined}
        {...props}
        columns={shown.map((id) => offered.find((column) => column.id === id)!)}
        choice={{
          onMove: move,
          onHide: hide,
          panel: <ColumnsPanel forLabel="For Correspondence" offered={offered} shown={shown} onShow={(id) => setShown((before) => [...before, id])} onHide={hide} onMove={move} onReset={() => setShown(DEFAULT)} />,
        }}
      />
    )
  }

  /** Where the panel's popover is placed: jsdom lays nothing out, so by the box a test gives the button */
  const placeOf = (dialog: HTMLElement) => {
    const popover = dialog.closest<HTMLElement>('.popover')!
    return { placement: popover.getAttribute('data-placement'), left: popover.style.left, top: popover.style.top }
  }

  async function chooseColumnsFrom(heading: string) {
    expect(fireEvent.contextMenu(header(heading))).toBe(false)
    await userEvent.click(within(await screen.findByRole('menu', { name: `${heading} Column` })).getByRole('menuitem', { name: 'Choose Columns…' }))
    return screen.findByRole('dialog', { name: 'Columns', description: 'For Correspondence' })
  }

  async function closeWithEscape() {
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  }

  it('the Columns button and Choose Columns… open the same panel, and Esc gives the focus back to what opened it', async () => {
    const user = userEvent.setup()
    render(<Chosen />)
    const button = columnsButton()
    expect([button.getAttribute('aria-haspopup'), button.getAttribute('aria-expanded')]).toEqual(['dialog', 'false'])
    // At the heading row's end
    button.getBoundingClientRect = () => new DOMRect(600, 4, 26, 26)
    await user.click(button)
    // Named by the panel's title, and described by the list it's for
    const dialog = await screen.findByRole('dialog', { name: 'Columns', description: 'For Correspondence' })
    expect(within(dialog).getByRole('group', { name: 'Shown' })).toBeTruthy()
    expect(button.getAttribute('aria-expanded')).toBe('true')
    // Below the button, at its end
    const place = placeOf(dialog)
    expect(place).toEqual({ placement: 'bottom', left: '626px', top: '38px' })
    await closeWithEscape()
    expect(document.activeElement).toBe(button)
    expect(button.getAttribute('aria-expanded')).toBe('false')

    // From a heading's menu: the same panel, by the button
    const again = await chooseColumnsFrom('Doc #')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(within(again).getByRole('group', { name: 'Shown' })).toBeTruthy()
    expect(placeOf(again)).toEqual(place)
    expect(button.getAttribute('aria-expanded')).toBe('true')
    await closeWithEscape()
    expect(document.activeElement).toBe(header('Doc #'))
  })

  it('gives the focus back to a resizable heading\'s edge, where its focus is, and to the button once the heading has gone', async () => {
    const user = userEvent.setup()
    render(<Chosen resizing={{ widths: {}, onResizeEnd: vi.fn(), onReset: vi.fn() }} />)
    // The arrows reach the headings from the rows; a resizable heading's focus is its edge's slider
    const first = row(/Letter 1/)
    act(() => first.focus())
    press(first, 'ArrowUp')
    const slider = within(header('Title')).getByRole('slider')
    expect(document.activeElement).toBe(slider)
    fireEvent.keyDown(slider, { key: 'F10', shiftKey: true })
    const menu = await screen.findByRole('menu', { name: 'Title Column' })
    await waitFor(() => expect(menu.contains(document.activeElement)).toBe(true))
    // Its last item, as this list has no Reset Columns
    await user.keyboard('{End}')
    expect(document.activeElement).toBe(within(menu).getByRole('menuitem', { name: 'Choose Columns…' }))
    await user.keyboard('{Enter}')
    await screen.findByRole('dialog', { name: 'Columns' })
    await closeWithEscape()
    expect(document.activeElement).toBe(slider)

    // The panel hides the column whose heading opened it: the focus goes to the Columns button
    const dialog = await chooseColumnsFrom('Author')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Author' }))
    expect(headings()).toEqual(['title', 'docNumber', 'note'])
    await closeWithEscape()
    expect(document.activeElement).toBe(columnsButton())
  })

  it('draws no Columns button without `choice`', () => {
    const given = { onSort: vi.fn(), onResetColumns: vi.fn() }
    const { container, rerender } = render(<Harness columns={listColumns} {...given} />)
    // What the frame draws, besides React Aria's own markers round the list (a template and its focus scope's ends)
    const frame = container.querySelector('.frame')!
    const drawn = () => [...frame.children].filter((one) => one.className !== '').map((one) => one.className)
    // The frame holds the list alone, as before, and no heading makes room for a button
    expect(drawn()).toEqual(['table'])
    expect(screen.queryByRole('button', { name: 'Columns' })).toBeNull()
    expect(container.querySelector('.beforeButton')).toBeNull()

    rerender(<Harness columns={listColumns} {...given} choice={{ onMove: vi.fn(), onHide: vi.fn(), panel: null }} />)
    expect(drawn()).toEqual(['table', 'columnsButton'])
    rerender(<Harness columns={listColumns} {...given} />)
    expect(drawn()).toEqual(['table'])
    expect(screen.queryByRole('button', { name: 'Columns' })).toBeNull()
    expect(container.querySelector('.beforeButton')).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('keeps the panel open with its focus while the list behind it changes, and through a refetch\'s new rows', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<Chosen />)
    await user.click(columnsButton())
    const dialog = await screen.findByRole('dialog', { name: 'Columns' })
    // Title's box is the columns' stop for Tab; End goes to Client, the last of More Columns, and Space ticks it
    await user.tab()
    await user.keyboard('{End}')
    expect(document.activeElement).toBe(within(dialog).getByRole('checkbox', { name: 'Client' }))
    await user.keyboard(' ')
    // The list behind shows it at once, at its end, and the panel stays open with its focus on the column, now in Shown
    expect(headings()).toEqual([...DEFAULT, 'client'])
    const client = within(within(dialog).getByRole('group', { name: 'Shown' })).getByRole('checkbox', { name: 'Client' }) as HTMLInputElement
    expect(client.checked).toBe(true)
    expect(document.activeElement).toBe(client)
    expect(screen.getByRole('dialog', { name: 'Columns' })).toBe(dialog)

    // A refetch draws the list again with new rows
    rerender(<Chosen rows={items(5)} />)
    expect(document.querySelectorAll('[data-row-key]')).toHaveLength(5)
    expect(screen.getByRole('dialog', { name: 'Columns' })).toBe(dialog)
    expect(document.activeElement).toBe(client)
    // And moving the column draws the list's columns in the new order, the panel still open
    await user.click(within(dialog).getByRole('button', { name: 'Move Up' }))
    expect(headings()).toEqual(['title', 'author', 'docNumber', 'client', 'note'])
    expect(screen.getByRole('dialog', { name: 'Columns' })).toBe(dialog)
  })

  it('closes on a click outside, and gives the focus back to what opened it', async () => {
    const user = userEvent.setup()
    render(<Chosen />)
    await user.click(columnsButton())
    await screen.findByRole('dialog', { name: 'Columns' })
    // The page under the popover, which a click outside it lands on
    await user.click(screen.getByTestId('underlay'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(columnsButton())

    await chooseColumnsFrom('Doc #')
    await user.click(screen.getByTestId('underlay'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(header('Doc #'))
  })

  it('sits at the heading row\'s end outside every heading and the list, comes after the list for Tab, and has its name as its tooltip', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <>
        <Chosen />
        <button>After</button>
      </>,
    )
    const button = columnsButton()
    // Not a column of the list, nor in a heading or a row
    expect(button.closest('[data-column-key], [role="grid"], [role="row"]')).toBeNull()
    expect(screen.getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Title', 'Author', 'Doc #', 'Note'])
    expect(button.closest('[title]')?.getAttribute('title')).toBe('Columns')
    // A right-click on it keeps the browser's menu
    expect(fireEvent.contextMenu(button)).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
    await expectNoAxeViolations(container)
    await user.tab()
    expect(document.activeElement).toBe(row(/Letter 1/))
    await user.tab()
    expect(document.activeElement).toBe(button)
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'After' }))
  })

  it('stays at the visible end of the heading row, before a scrollbar that takes room, and the last heading keeps clear of it', () => {
    // jsdom has no ResizeObserver: these are called back by hand, as a browser calls them when a box changes
    const observers: ((entries: unknown[]) => void)[] = []
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: (entries: unknown[]) => void) {
          observers.push(callback)
        }
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    )
    try {
      render(<Chosen resizing={{ widths: {}, onResizeEnd: vi.fn(), onReset: vi.fn() }} />)
      const place = columnsButton().parentElement!
      expect(place.className).toBe('columnsButton')
      // A scrollbar 15 pixels wide, beside the heading row as beside the rows
      const grid = screen.getByRole('grid')
      Object.defineProperty(grid, 'offsetWidth', { configurable: true, value: 815 })
      Object.defineProperty(grid, 'clientWidth', { configurable: true, value: 800 })
      act(() => observers.forEach((observer) => observer([])))
      expect(place.style.insetInlineEnd).toBe('15px')
    } finally {
      vi.unstubAllGlobals()
    }
    // Only the last heading, and its cells, make room for the button
    expect(screen.getAllByRole('columnheader').map((one) => one.classList.contains('beforeButton'))).toEqual([false, false, false, true])
    expect(within(row(/Letter 1/)).getAllByRole('gridcell').map((one) => one.classList.contains('beforeButton'))).toEqual([false, false, true])
    // jsdom draws nothing, so the rules are read: a square the heading row's height over its end, on the heading's surface,
    // shown pressed while its panel is open; the last heading's name ends before it, a column of numbers' cells in line with
    // it, and the last edge and the drop line after the last heading are drawn where the button begins
    expect(tableCss).toMatch(/\.columnsButton \{[^}]*position: absolute;[^}]*inset-block-start: 0;[^}]*inset-inline-end: 0;[^}]*width: var\(--row-height\);[^}]*height: var\(--row-height\);[^}]*border-bottom: 1px solid var\(--hd-line\);[^}]*background: var\(--hd-window\);[^}]*color: var\(--hd-text-secondary\)/)
    expect(tableCss).toMatch(/\.columnsButton > button\[aria-expanded='true'\] \{[^}]*border-color: var\(--hd-pressed-edge\);[^}]*background: var\(--hd-pressed\);[^}]*color: var\(--hd-text\)/)
    expect(tableCss).toMatch(/\.column\.beforeButton,\s*\.cell\.end\.beforeButton \{[^}]*padding-inline-end: var\(--row-height\)/)
    expect(tableCss).toMatch(/\.beforeButton > \.resizer\[data-last\] \{[^}]*inset-inline-end: var\(--row-height\)/)
    expect(tableCss).toMatch(/\.column\.beforeButton\[data-drop-line='end'\]::after \{[^}]*inset-inline-end: var\(--row-height\)/)
  })

  it('passes axe with the panel open', async () => {
    const user = userEvent.setup()
    render(<Chosen />)
    await user.click(columnsButton())
    const dialog = await screen.findByRole('dialog', { name: 'Columns' })
    await expectNoAxeViolations(dialog.closest('.popover')!)
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
