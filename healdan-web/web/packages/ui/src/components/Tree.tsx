// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef, useState, type DragEvent, type ReactNode } from 'react'
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
}

const loadingSuffix = '\u0000loading'

// The caller owns what is expanded and what is loaded, so a node's children can be fetched when it is first expanded.
export function Tree({ nodes, expanded, onExpandedChange, onAction, current, acceptsDrop, onDrop, contextMenu, ...rest }: TreeProps) {
  // The node something is being dragged over, and takes
  const [over, setOver] = useState<string | undefined>()
  // How many elements of the tree the drag is inside: dragenter and dragleave come in pairs for each element it crosses, and
  // WebKit may name none as dragleave's relatedTarget, which is why they are counted
  const inside = useRef(0)

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

  return (
    <ItemContextMenu attribute="data-tree-key" menu={contextMenu}>
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
    </ItemContextMenu>
  )
}
