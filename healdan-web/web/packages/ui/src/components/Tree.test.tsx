// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Menu, MenuItem } from './Menu'
import { Tree, type TreeNode } from './Tree'

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

function Harness({ onAction = vi.fn(), onExpand = vi.fn(), current }: { onAction?: (id: string) => void; onExpand?: (id: string) => void; current?: string }) {
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
