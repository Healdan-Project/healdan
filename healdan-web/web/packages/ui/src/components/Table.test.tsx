// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { bringObservedIntoView } from '../test/intersection'
import { Table, type TableColumn, type TableProps, type TableRow, type TableSort } from './Table'
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

  it('puts a text cell in a box that ends in an ellipsis, with the whole text still in it', () => {
    render(<Harness rows={[{ id: 'd1', textValue: 'A long name', name: 'A very long name that will not fit in its cell' }]} />)
    const text = screen.getByText('A very long name that will not fit in its cell')
    // Not the cell itself (a flex box, which clips its text with no ellipsis), but a box of its own
    expect(text.closest('[role="rowheader"], [role="gridcell"]')).not.toBe(text)
    expect(text.className).toMatch(/text/)
    expect(tableCss).toMatch(/\.text \{[^}]*overflow: hidden;[^}]*text-overflow: ellipsis;[^}]*white-space: nowrap/)
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
