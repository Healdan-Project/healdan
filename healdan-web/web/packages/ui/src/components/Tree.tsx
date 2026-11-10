// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Button, Tree as AriaTree, TreeItem as AriaTreeItem, TreeItemContent, type Key } from 'react-aria-components'
import { Icon } from '../icons'
import { t } from '../strings'
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
}

const loadingSuffix = '\u0000loading'

// The caller owns what is expanded and what is loaded, so a node's children can be fetched when it is first expanded.
export function Tree({ nodes, expanded, onExpandedChange, onAction, current, ...rest }: TreeProps) {
  function item(node: TreeNode): ReactNode {
    const open = expanded.has(node.id)
    return (
      <AriaTreeItem
        key={node.id}
        id={node.id}
        textValue={node.textValue}
        hasChildItems={node.expandable || (node.children?.length ?? 0) > 0}
        className={styles.item}
        data-current={current === node.id ? 'true' : undefined}
      >
        <TreeItemContent>
          {({ hasChildItems, isExpanded }) => (
            <span className={styles.row}>
              {hasChildItems ? (
                <Button slot="chevron" className={styles.chevron}>
                  <Icon name="chevronRight" size={12} className={isExpanded ? styles.open : undefined} />
                </Button>
              ) : (
                <span className={styles.spacer} />
              )}
              {node.label}
            </span>
          )}
        </TreeItemContent>
        {node.children?.map(item)}
        {open && node.children === undefined && (
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
  )
}
