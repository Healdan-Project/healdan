// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import { Icon } from '../icons'
import { Menu, MenuItem } from './Menu'
import { Tree, type TreeNode, type TreeProps } from './Tree'

const nodes: TreeNode[] = [
  {
    id: 'm1',
    textValue: '1001.1 Charter Dispute',
    label: <>1001.1 – Charter Dispute</>,
    expandable: true,
    children: [
      { id: 'f1', textValue: 'Correspondence', label: 'Correspondence' },
      { id: 's1', textValue: 'My Recent Docs', label: 'My Recent Docs' },
    ],
  },
  { id: 'm2', textValue: '1002.1 Lease', label: <>1002.1 – Lease</>, expandable: true },
]

function Harness({
  onAction = vi.fn(),
  onExpand = vi.fn(),
  current,
  contextMenu,
}: {
  onAction?: (id: string) => void
  onExpand?: (id: string) => void
  current?: string
  contextMenu?: TreeProps['contextMenu']
}) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  return (
    <Tree
      aria-label="My Matters"
      nodes={nodes}
      expanded={expanded}
      current={current}
      onExpandedChange={(next) => {
        for (const id of next) if (!expanded.has(id)) onExpand(id)
        setExpanded(new Set(next))
      }}
      onAction={onAction}
      contextMenu={contextMenu}
    />
  )
}

describe('Tree', () => {
  it('shows its top level closed, and a node\'s children once it is expanded', () => {
    render(<Harness />)
    expect(screen.getByRole('row', { name: /1001\.1/ })).toBeTruthy()
    expect(screen.queryByRole('row', { name: /Correspondence/ })).toBeNull()
    const first = screen.getAllByRole('button', { name: /expand/i })[0] as HTMLElement
    fireEvent.click(first)
    expect(screen.getByRole('row', { name: /Correspondence/ })).toBeTruthy()
  })

  it('draws a label\'s icon whole and in its kind\'s colour, on the open row too', () => {
    const matter = (id: string, name: string): TreeNode => ({
      id,
      textValue: name,
      label: (
        <>
          <Icon name="briefcase" kind="matter" />
          {name}
        </>
      ),
    })
    render(<Tree aria-label="My Matters" nodes={[matter('m1', '1001.1 – Charter Dispute – MV Northern Star'), matter('m2', '1002.1')]} expanded={new Set()} onExpandedChange={vi.fn()} onAction={vi.fn()} current="m1" />)
    for (const name of [/1001\.1/, /1002\.1/]) {
      const icon = screen.getByRole('row', { name }).querySelector('svg')!
      // A long label is cut short, never its icon
      expect(getComputedStyle(icon).flexShrink).toBe('0')
      expect(getComputedStyle(icon).color).toBe('rgb(138, 90, 0)')
    }
  })

  it('reports an expansion once, and an action on a row', () => {
    const onExpand = vi.fn()
    const onAction = vi.fn()
    render(<Harness onExpand={onExpand} onAction={onAction} />)
    fireEvent.click(screen.getAllByRole('button', { name: /expand/i })[0] as HTMLElement)
    expect(onExpand).toHaveBeenCalledExactlyOnceWith('m1')
    fireEvent.click(screen.getByRole('row', { name: /Correspondence/ }))
    expect(onAction).toHaveBeenCalledWith('f1')
  })

  it('marks the current node', () => {
    render(<Harness current="m2" />)
    expect(screen.getByRole('row', { name: /1002\.1/ }).getAttribute('data-current')).toBe('true')
  })

  it('gives the current row aria-current', () => {
    render(<Harness current="m2" />)
    expect(screen.getByRole('row', { name: /1002\.1/ }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('row', { name: /1001\.1/ }).getAttribute('aria-current')).toBeNull()
  })

  it('works from the keyboard: arrows move, Right expands, Left collapses, Enter opens', () => {
    const onExpand = vi.fn()
    const onAction = vi.fn()
    render(<Harness onExpand={onExpand} onAction={onAction} />)
    const tree = screen.getByRole('treegrid')
    const first = screen.getByRole('row', { name: /1001\.1/ })
    const second = screen.getByRole('row', { name: /1002\.1/ })
    first.focus()
    expect(document.activeElement).toBe(first)
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(second)
    fireEvent.keyDown(second, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(first)
    fireEvent.keyDown(first, { key: 'ArrowRight' })
    expect(onExpand).toHaveBeenCalledExactlyOnceWith('m1')
    const child = screen.getByRole('row', { name: /Correspondence/ })
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(child)
    fireEvent.keyDown(child, { key: 'Enter' })
    fireEvent.keyUp(child, { key: 'Enter' })
    expect(onAction).toHaveBeenCalledWith('f1')
    fireEvent.keyDown(child, { key: 'ArrowLeft' }) // from a child, Left goes to its parent
    expect(document.activeElement).toBe(first)
    fireEvent.keyDown(first, { key: 'ArrowLeft' })
    expect(screen.queryByRole('row', { name: /Correspondence/ })).toBeNull()
    expect(tree).toBeTruthy()
  })

  it('puts a row\'s menu at its end, reached with Right once the row is open, and pressing it opens no row', async () => {
    const onAction = vi.fn()
    const onRemove = vi.fn()
    function WithMenu() {
      const [expanded, setExpanded] = useState<Set<string>>(new Set())
      const withMenu: TreeNode[] = nodes.map((node) => ({
        ...node,
        menu: (
          <Menu variant="quiet" label="⋯" aria-label={`More for ${node.textValue}`}>
            <MenuItem id="remove" onAction={() => onRemove(node.id)}>
              Remove
            </MenuItem>
          </Menu>
        ),
      }))
      return <Tree aria-label="My Matters" nodes={withMenu} expanded={expanded} onExpandedChange={setExpanded} onAction={onAction} />
    }
    render(<WithMenu />)
    const first = screen.getByRole('row', { name: /1001\.1/ })
    act(() => first.focus())
    fireEvent.keyDown(first, { key: 'ArrowRight' }) // opens it
    fireEvent.keyDown(first, { key: 'ArrowRight' }) // then into the row
    const more = screen.getByRole('button', { name: 'More for 1001.1 Charter Dispute' })
    expect(document.activeElement).toBe(more)
    fireEvent.click(more)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Remove' }))
    expect(onRemove).toHaveBeenCalledExactlyOnceWith('m1')
    expect(onAction).not.toHaveBeenCalled()
  })

  it('shows Loading while an expanded node has no children yet', () => {
    render(<Harness />)
    fireEvent.click(screen.getAllByRole('button', { name: /expand/i })[1] as HTMLElement)
    expect(screen.getByText('Loading…')).toBeTruthy()
  })

  it('passes axe', async () => {
    const { container } = render(<Harness />)
    fireEvent.click(screen.getAllByRole('button', { name: /expand/i })[0] as HTMLElement)
    await expectNoAxeViolations(container)
  })
})

describe('Tree drops', () => {
  const types = (list: string[]) => ({ dataTransfer: { types: list, files: [] } })

  function Droppable({ onDrop }: { onDrop: (id: string) => void }) {
    return (
      <Tree
        aria-label="My Matters"
        nodes={nodes}
        expanded={new Set(['m1'])}
        onExpandedChange={() => {}}
        onAction={() => {}}
        acceptsDrop={(id, list) => id !== 's1' && list.includes('Files')}
        onDrop={(id) => onDrop(id)}
      />
    )
  }

  it('lights up a node that takes what is dragged over it, takes the drop there, and puts the light out when it leaves', () => {
    const onDrop = vi.fn()
    const { container } = render(<Droppable onDrop={onDrop} />)
    const label = screen.getByText('Correspondence')
    fireEvent.dragEnter(label, types(['Files']))
    expect(fireEvent.dragOver(label, types(['Files']))).toBe(false)
    expect(container.querySelector('[data-drop-target="true"]')).toBeTruthy()
    fireEvent.dragLeave(label, { relatedTarget: document.body })
    expect(container.querySelector('[data-drop-target]')).toBeNull()
    fireEvent.drop(label, types(['Files']))
    expect(onDrop).toHaveBeenCalledWith('f1')
  })

  it('leaves what a node does not accept alone: the dragover isn\'t cancelled and a drop reaches nobody', () => {
    const onDrop = vi.fn()
    const { container } = render(<Droppable onDrop={onDrop} />)
    expect(fireEvent.dragOver(screen.getByText('Correspondence'), types(['text/html']))).toBe(true)
    expect(fireEvent.dragOver(screen.getByText('My Recent Docs'), types(['Files']))).toBe(true)
    fireEvent.drop(screen.getByText('My Recent Docs'), types(['Files']))
    expect(container.querySelector('[data-drop-target]')).toBeNull()
    expect(onDrop).not.toHaveBeenCalled()
  })

  it('keeps the light while the drag moves between a row\'s parts, though dragleave names no element (as WebKit may)', () => {
    const { container } = render(<Droppable onDrop={vi.fn()} />)
    const label = screen.getByText('Correspondence')
    const row = label.closest('[data-drop-target], span') as HTMLElement
    fireEvent.dragEnter(row, types(['Files']))
    fireEvent.dragOver(row, types(['Files']))
    fireEvent.dragEnter(label, types(['Files']))
    fireEvent.dragLeave(row, { ...types(['Files']), relatedTarget: null })
    expect(container.querySelector('[data-drop-target="true"]')).toBeTruthy()
    fireEvent.dragLeave(label, { ...types(['Files']), relatedTarget: null })
    expect(container.querySelector('[data-drop-target]')).toBeNull()
  })
})

describe('Tree right-click menu', () => {
  const contextMenu = {
    label: (id: string) => `Actions for ${id}`,
    // A search folder has none here, so it keeps the browser's menu
    items: (id: string) => (id === 's1' ? undefined : <MenuItem id="open">Open {id}</MenuItem>),
  }

  it('opens a node\'s menu on a right-click and on Shift+F10, and opens no node by it', async () => {
    const onAction = vi.fn()
    render(<Harness onAction={onAction} contextMenu={contextMenu} />)
    expect(fireEvent.contextMenu(screen.getByText('1002.1 – Lease'))).toBe(false)
    expect(await screen.findByRole('menu', { name: 'Actions for m2' })).toBeTruthy()
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
    expect(onAction).not.toHaveBeenCalled()

    fireEvent.click(screen.getAllByRole('button', { name: /expand/i })[0] as HTMLElement)
    const folder = screen.getByRole('row', { name: /Correspondence/ })
    act(() => folder.focus())
    fireEvent.keyDown(folder, { key: 'F10', shiftKey: true })
    expect(await screen.findByRole('menu', { name: 'Actions for f1' })).toBeTruthy()
  })

  it('leaves the browser\'s menu on a node with none', () => {
    render(<Harness contextMenu={contextMenu} />)
    fireEvent.click(screen.getAllByRole('button', { name: /expand/i })[0] as HTMLElement)
    expect(fireEvent.contextMenu(screen.getByRole('row', { name: /My Recent Docs/ }))).toBe(true)
  })
})
