// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, useState, type DragEvent, type KeyboardEvent, type ReactNode } from 'react'
import { Button, Tree as AriaTree, TreeItem as AriaTreeItem, TreeItemContent, type Key } from 'react-aria-components'
import { Icon } from '../icons'
import { t } from '../strings'
import { ItemContextMenu, type ItemMenu } from './ContextMenu'
import styles from './Tree.module.css'

export interface TreeNode {
  /** Unique in the whole tree. */
  id: string
  /** The plain text a screen reader and type-ahead use. */
  textValue: string
  label: ReactNode
  /** Shows a chevron, even before its children are known. */
  expandable?: boolean
  /** Undefined until they are loaded: an expanded node without them shows Loading. */
  children?: TreeNode[]
  /** A control at the row's end, such as a Menu of what can be done with it */
  menu?: ReactNode
}

export interface TreeProps {
  'aria-label': string
  nodes: TreeNode[]
  expanded: Set<string>
  onExpandedChange: (expanded: Set<string>) => void
  /** A row was pressed or Entered: open what it stands for. */
  onAction: (id: string) => void
  /** The node that is open in the main area. */
  current?: string
  /** Whether a node takes what is being dragged over it, by the types of the data (`DataTransfer.types`): it then lights up */
  acceptsDrop?: (id: string, types: readonly string[]) => boolean
  /** Something the node accepts was dropped on it */
  onDrop?: (id: string, event: DragEvent) => void
  /**
   * A node's menu, in place of the browser's: a right-click on its row, or Shift+F10 or the menu key while the row has the
   * focus, opens it (ContextMenu). A node whose `items` are undefined keeps the browser's own.
   */
  contextMenu?: ItemMenu
  /** F2 on a row: rename what it stands for */
  onRename?: (id: string) => void
  /** Delete, or the Mac's delete key (Backspace), on a row: delete what it stands for */
  onDelete?: (id: string) => void
  /**
   * What a drag of a row carries, under `type`, as a move (the browser's own drag and drop, as the Table's rows): a row drags
   * only where `can` says so, which is asked of every row drawn after each render, so it must be cheap, and `data` makes the
   * payload once a drag starts
   */
  drag?: { type: string; can: (id: string) => boolean; data: (id: string) => string | undefined }
}

const loadingSuffix = '\u0000loading'

// The caller owns what is expanded and what is loaded, so a node's children can be fetched when it is first expanded.
export function Tree({ nodes, expanded, onExpandedChange, onAction, current, acceptsDrop, onDrop, contextMenu, onRename, onDelete, drag, ...rest }: TreeProps) {
  // The node something is being dragged over, and takes
  const [over, setOver] = useState<string | undefined>()
  // How many elements of the tree the drag is inside: dragenter and dragleave come in pairs for each element it crosses, and
  // WebKit may name none as dragleave's relatedTarget, which is why they are counted
  const inside = useRef(0)

  // React Aria's TreeItem has no prop for it, so a row takes `draggable` from `drag` when it's drawn (React Aria draws rows
  // after the tree, and apart from it), and every row drawn already takes it again after each render, as what a row carries
  // can change (a row whose name is a field drags nothing). The row's callback reads the latest `drag`, as the Table's does.
  const latestDrag = useRef(drag)
  const frame = useRef<HTMLDivElement>(null)
  const canDrag = (id: string) => latestDrag.current?.can(id) === true
  useEffect(() => {
    latestDrag.current = drag
    for (const row of frame.current?.querySelectorAll<HTMLElement>('[role="row"][data-tree-key]') ?? []) row.draggable = canDrag(row.getAttribute('data-tree-key') ?? '')
  })

  // A drag that starts on a row carries what `drag` gives for it; one on a row with nothing to carry is cancelled, and text
  // dragged out of a field in a row is the field's
  function onDragStart(event: DragEvent<HTMLDivElement>) {
    if (!drag) return
    const target = event.target instanceof Element ? event.target : null
    if (target?.closest('input, textarea')) return
    const id = target?.closest('[data-tree-key]')?.getAttribute('data-tree-key')
    const data = id && drag.can(id) ? drag.data(id) : undefined
    if (data === undefined) return event.preventDefault()
    event.dataTransfer.setData(drag.type, data)
    event.dataTransfer.effectAllowed = 'move'
  }

  function dropProps(node: TreeNode) {
    if (!acceptsDrop) return {}
    const takes = (event: DragEvent) => acceptsDrop(node.id, Array.from(event.dataTransfer.types))
    return {
      // Without a cancelled dragover the browser won't drop here
      onDragOver: (event: DragEvent) => {
        if (!takes(event)) return
        event.preventDefault()
        event.stopPropagation()
        setOver(node.id)
      },
      onDragEnter: (event: DragEvent) => {
        if (takes(event)) inside.current++
      },
      onDragLeave: () => {
        // Only a drag that entered was counted in, so its leaving is counted out
        if (inside.current === 0) return
        inside.current--
        if (inside.current === 0) setOver(undefined)
      },
      onDrop: (event: DragEvent) => {
        inside.current = 0
        setOver(undefined)
        if (!takes(event)) return
        event.preventDefault()
        event.stopPropagation()
        onDrop?.(node.id, event)
      },
    }
  }

  function item(node: TreeNode): ReactNode {
    return (
      <AriaTreeItem
        key={node.id}
        id={node.id}
        textValue={node.textValue}
        hasChildItems={node.expandable || (node.children?.length ?? 0) > 0}
        className={styles.item}
        data-current={current === node.id ? 'true' : undefined}
        data-tree-key={node.id}
        // React Aria's TreeItem doesn't pass aria-current to its row, so it is set on the element.
        ref={(row) => {
          if (current === node.id) row?.setAttribute('aria-current', 'page')
          else row?.removeAttribute('aria-current')
          if (row) row.draggable = canDrag(node.id)
        }}
      >
        <TreeItemContent>
          {({ hasChildItems, isExpanded }) => (
            <span className={styles.row} data-drop-target={over === node.id ? 'true' : undefined} {...dropProps(node)}>
              {hasChildItems ? (
                <Button slot="chevron" className={styles.chevron}>
                  <Icon name="chevronRight" size={12} className={isExpanded ? styles.open : undefined} />
                </Button>
              ) : (
                <span className={styles.spacer} />
              )}
              {node.label}
              {node.menu && <span className={styles.menu}>{node.menu}</span>}
            </span>
          )}
        </TreeItemContent>
        {node.children?.map(item)}
        {/* Present while closed too: Right expands only a row that has a child to show. */}
        {node.expandable && node.children === undefined && (
          <AriaTreeItem id={node.id + loadingSuffix} textValue={t('common.loading')} isDisabled className={styles.item}>
            <TreeItemContent>
              <span className={styles.row}>
                <span className={styles.spacer} />
                <span className={styles.loading}>{t('common.loading')}</span>
              </span>
            </TreeItemContent>
          </AriaTreeItem>
        )}
      </AriaTreeItem>
    )
  }

  // F2, Delete and Backspace are caught ahead of React Aria's keys, as the Table does, and with Alt, Ctrl or ⌘ left alone, as
  // the Table leaves them. A field inside a row stops its own keys, so typing never gets here. The Loading row stands for
  // nothing, and has no key.
  function onKeyDownCapture(event: KeyboardEvent<HTMLDivElement>) {
    const target = event.target instanceof Element ? event.target : null
    if (target?.getAttribute('role') !== 'row' || event.altKey || event.ctrlKey || event.metaKey) return
    const handler = event.key === 'F2' ? onRename : event.key === 'Delete' || event.key === 'Backspace' ? onDelete : undefined
    const id = target.getAttribute('data-tree-key')
    if (!handler || !id || id.endsWith(loadingSuffix)) return
    event.preventDefault()
    event.stopPropagation()
    handler(id)
  }

  return (
    <ItemContextMenu attribute="data-tree-key" menu={contextMenu}>
      <div ref={frame} className={styles.frame} onKeyDownCapture={onKeyDownCapture} onDragStart={onDragStart}>
        <AriaTree
          {...rest}
          className={styles.tree}
          selectionMode="none"
          expandedKeys={expanded}
          onExpandedChange={(keys) => onExpandedChange(new Set([...keys].map(String)))}
          onAction={(key: Key) => onAction(String(key))}
        >
          {nodes.map(item)}
        </AriaTree>
      </div>
    </ItemContextMenu>
  )
}
