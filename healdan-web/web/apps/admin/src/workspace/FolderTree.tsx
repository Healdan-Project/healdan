// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Icon, MenuItem, Tree, type ItemMenu, type TreeNode } from '@healdan/ui'
import { t } from '../strings'
import { findNode, type WorkspaceNode } from './tree'
import styles from './Workspace.module.css'

/** What the tree's menus and keys ask the editor to do with a node */
export type FolderAction = 'new-folder' | 'new-search-folder' | 'rename' | 'move' | 'delete'

export interface FolderTreeProps {
  root: WorkspaceNode
  /** A matter's workspace, its top the matter's own folder, or a template's */
  kind: 'matter' | 'template'
  /** The node chosen, whose panel is shown */
  selected: number
  onSelect: (id: number) => void
  expanded: Set<string>
  onExpandedChange: (expanded: Set<string>) => void
  /** A menu item, F2 or Delete on a node */
  onAction: (action: FolderAction, node: WorkspaceNode) => void
}

function NodeLabel({ node, top, kind }: { node: WorkspaceNode; top: boolean; kind: 'matter' | 'template' }) {
  return (
    <>
      {top ? (
        kind === 'matter' ? (
          <Icon name="briefcase" kind="matter" />
        ) : (
          <Icon name="template" />
        )
      ) : node.kind === 'search-folder' ? (
        <Icon name="folderSearch" kind="folder" />
      ) : (
        <Icon name="folder" kind="folder" />
      )}
      <span className={styles.nodeName}>{node.name}</span>
      {/* The row's name says so too (its textValue); the lock is what's drawn, as the mockup has it */}
      {node.ownPermissions && (
        <span className={styles.lock} title={t('admin.workspace.own')}>
          <Icon name="lock" size={14} />
        </span>
      )}
    </>
  )
}

/** The actions a node offers: a folder holds new folders and search folders; all but the top are renamed, moved and deleted */
export function actionsOf(node: WorkspaceNode, top: boolean): FolderAction[] {
  const making: FolderAction[] = node.kind === 'folder' ? ['new-folder', 'new-search-folder'] : []
  return top ? making : [...making, 'rename', 'move', 'delete']
}

const ACTIONS = {
  'new-folder': { label: 'admin.workspace.newFolder', icon: 'folderPlus' },
  'new-search-folder': { label: 'admin.workspace.newSearchFolder', icon: 'folderSearch' },
  rename: { label: 'admin.workspace.rename', icon: 'edit' },
  move: { label: 'admin.workspace.move', icon: 'folderSymlink' },
  delete: { label: 'admin.workspace.delete', icon: 'trash' },
} as const

/**
 * A workspace's folders and search folders (spec 8.4), every one shown, with a lock on each folder that has permissions
 * of its own. A click or Enter chooses a node; the arrow keys walk the tree, as React Aria's does. F2 renames, Delete
 * deletes, and a right-click, Shift+F10 or the menu key opens a node's menu of what it offers (actionsOf).
 */
export function FolderTree({ root, kind, selected, onSelect, expanded, onExpandedChange, onAction }: FolderTreeProps) {
  const nodeOf = (key: string) => findNode(root, Number(key))
  const toTreeNode = (node: WorkspaceNode): TreeNode => ({
    id: String(node.id),
    textValue: node.ownPermissions ? t('admin.workspace.ownName', { name: node.name }) : node.name,
    label: <NodeLabel node={node} top={node.id === root.id} kind={kind} />,
    children: node.children.map(toTreeNode),
  })
  const menu: ItemMenu = {
    label: (key) => t('admin.workspace.menu', { name: nodeOf(key)?.name ?? '' }),
    items: (key) => {
      const node = nodeOf(key)
      if (!node) return undefined
      return actionsOf(node, node.id === root.id).map((action) => (
        <MenuItem key={action} id={action} icon={ACTIONS[action].icon} onAction={() => onAction(action, node)}>
          {t(ACTIONS[action].label)}
        </MenuItem>
      ))
    },
  }
  const act = (action: FolderAction) => (key: string) => {
    const node = nodeOf(key)
    if (node) onAction(action, node)
  }
  return (
    <Tree
      aria-label={t('admin.workspace.tree', { name: root.name })}
      nodes={[toTreeNode(root)]}
      expanded={expanded}
      onExpandedChange={onExpandedChange}
      current={String(selected)}
      onAction={(key) => onSelect(Number(key))}
      contextMenu={menu}
      onRename={act('rename')}
      onDelete={act('delete')}
    />
  )
}
