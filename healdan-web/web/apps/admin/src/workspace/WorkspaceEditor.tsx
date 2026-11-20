// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Icon, toast } from '@healdan/ui'
import { t } from '../strings'
import { AccessPanel } from './AccessPanel'
import { DeleteFolderDialog, FolderNameDialog, MoveFolderDialog, namesBeside } from './FolderDialogs'
import { actionsOf, FolderTree, type FolderAction } from './FolderTree'
import { PropertiesPanel } from './PropertiesPanel'
import { useTemplateProblems, useWorkspaceTree, workspaceChanged } from './queries'
import { SearchFolderPanel } from './SearchFolderPanel'
import { findNode, holding, parentOf, type WorkspaceNode } from './tree'
import styles from './Workspace.module.css'

type A = adminComponents['schemas']

export interface WorkspaceEditorProps {
  /** The workspace's top folder: a matter's own (its `folderId`), or a template's (its id) */
  rootFolderId: number
  kind: 'matter' | 'template'
}

/**
 * The Workspace Editor (spec 8.4), the same for a matter's Workspace tab and a workspace template's page: the folder
 * tree on the left, with its search folders and a lock on each folder with permissions of its own, and the chosen
 * folder on the right, with who can use it and the values it sets, or a search folder's rule. New Folder, New Search
 * Folder, Rename, Move To… and Delete work on every folder below the top. In a template, what New Matter would refuse
 * is said as warnings beside the folder's Save, which still saves.
 */
export function WorkspaceEditor({ rootFolderId, kind }: WorkspaceEditorProps) {
  const tree = useWorkspaceTree(rootFolderId)
  const problems = useTemplateProblems(rootFolderId, kind === 'template')
  if (tree.isError) return <ErrorPanel error={tree.error} onRetry={() => void tree.refetch()} />
  if (tree.data === undefined) return <p className={styles.note}>{t('common.loading')}</p>
  return <Editor root={tree.data} kind={kind} problems={problems.data ?? []} />
}

/** A dialog the editor has open, and what it acts on */
type Open = { action: FolderAction; node: WorkspaceNode; parent: WorkspaceNode | undefined }

function Editor({ root, kind, problems }: { root: WorkspaceNode; kind: 'matter' | 'template'; problems: readonly A['TemplateProblem'][] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const foldersId = useId()
  const [selected, setSelected] = useState(root.id)
  const [expanded, setExpanded] = useState(() => holding(root))
  const [open, setOpen] = useState<Open | null>(null)

  // A folder that has gone, from here or elsewhere, gives way to the top
  const node = findNode(root, selected) ?? root
  const parent = parentOf(root, node.id)
  const isTop = node.id === root.id
  const topNote = t(kind === 'matter' ? 'admin.workspace.matterTop' : 'admin.workspace.templateTop')
  // Something new goes in the folder chosen, or beside the search folder chosen
  const making = node.kind === 'folder' ? node : (parent ?? root)

  function act(action: FolderAction, on: WorkspaceNode) {
    const top = on.id === root.id
    if (!actionsOf(on, top).includes(action)) {
      // F2 or Delete on the top says why not, as its panel does
      if (top) toast(topNote)
      return
    }
    setOpen({ action, node: on, parent: parentOf(root, on.id) })
  }

  const changed = () => workspaceChanged(queryClient, root.id, kind)
  const close = () => setOpen(null)

  async function create(into: WorkspaceNode, name: string, rule?: A['SearchFolderRule']) {
    const made = rule
      ? await api.send<A['SearchFolder']>('POST', apiPath`/v1/admin/folders/${into.id}/search-folders`, { name, rule } satisfies A['NewSearchFolder'])
      : await api.send<A['WorkspaceNode']>('POST', apiPath`/v1/admin/folders/${into.id}/children`, { name } satisfies A['NewWorkspaceFolder'])
    changed()
    setExpanded((was) => new Set([...was, String(into.id)]))
    setSelected(made.id)
    toast(t('admin.folderDialog.created', { name: made.name }))
  }

  async function rename(on: WorkspaceNode, name: string) {
    await api.send('PATCH', apiPath`/v1/admin/folders/${on.id}`, { name } satisfies A['FolderRename'])
    changed()
  }

  async function move(on: WorkspaceNode, into: WorkspaceNode) {
    await api.send('POST', apiPath`/v1/admin/folders/${on.id}/move`, { folderId: into.id } satisfies A['FolderMove'])
    changed()
    setExpanded((was) => new Set([...was, String(into.id)]))
    toast(t('admin.moveFolder.moved', { name: on.name, folder: into.name }))
  }

  async function remove(on: WorkspaceNode, from: WorkspaceNode | undefined) {
    await api.send('DELETE', apiPath`/v1/admin/folders/${on.id}`)
    changed()
    setSelected((from ?? root).id)
    toast(t('admin.deleteFolder.done', { name: on.name }))
  }

  return (
    <div className={styles.editor}>
      <section aria-labelledby={foldersId} className={styles.treePane}>
        <div className={styles.paneHead}>
          <h2 id={foldersId} className={styles.paneTitle}>
            {t('admin.workspace.folders')}
          </h2>
          <Button onPress={() => setOpen({ action: 'new-folder', node: making, parent: parentOf(root, making.id) })}>
            <span className={styles.withIcon}>
              <Icon name="folderPlus" size={14} />
              {t('admin.workspace.newFolder')}
            </span>
          </Button>
          <Button onPress={() => setOpen({ action: 'new-search-folder', node: making, parent: parentOf(root, making.id) })}>
            <span className={styles.withIcon}>
              <Icon name="folderSearch" size={14} />
              {t('admin.workspace.newSearchFolder')}
            </span>
          </Button>
        </div>
        <FolderTree root={root} kind={kind} selected={node.id} onSelect={setSelected} expanded={expanded} onExpandedChange={setExpanded} onAction={act} />
      </section>
      <section aria-label={t('admin.workspace.chosen')} className={styles.panel}>
        <div className={styles.panelHead}>
          <h2 className={styles.folderName}>
            {isTop && kind === 'matter' ? (
              <Icon name="briefcase" kind="matter" />
            ) : isTop ? (
              <Icon name="template" />
            ) : (
              <Icon name={node.kind === 'search-folder' ? 'folderSearch' : 'folder'} kind="folder" />
            )}
            <span>{node.name}</span>
          </h2>
          {!isTop && (
            <div className={styles.buttons}>
              <Button onPress={() => act('rename', node)}>
                <span className={styles.withIcon}>
                  <Icon name="pencil" size={14} />
                  {t('admin.workspace.rename')}
                </span>
              </Button>
              <Button onPress={() => act('move', node)}>
                <span className={styles.withIcon}>
                  <Icon name="arrowRight" size={14} />
                  {t('admin.workspace.move')}
                </span>
              </Button>
              <Button onPress={() => act('delete', node)}>
                <span className={styles.withIcon}>
                  <Icon name="trash" size={14} />
                  {t('admin.workspace.delete')}
                </span>
              </Button>
            </div>
          )}
        </div>
        {isTop && <p className={styles.note}>{topNote}</p>}
        {node.kind === 'search-folder' && <p className={styles.note}>{t('admin.workspace.searchFolder')}</p>}
        {/* Keyed by the folder, so what was being changed in one is never carried to another */}
        <AccessPanel key={`access-${node.id}`} node={node} root={root} parent={parent} kind={kind} warnings={problems.filter((one) => one.folderId === node.id)} />
        {node.kind === 'search-folder' ? (
          <SearchFolderPanel key={`rule-${node.id}`} node={node} root={root} kind={kind} />
        ) : (
          <PropertiesPanel key={`values-${node.id}`} node={node} root={root} kind={kind} />
        )}
      </section>
      {open?.action === 'new-folder' && (
        <FolderNameDialog
          title={t('admin.folderDialog.newTitle', { name: open.node.name })}
          submit={t('admin.folderDialog.create')}
          taken={namesBeside(open.node)}
          send={(name) => create(open.node, name)}
          onClose={close}
        />
      )}
      {open?.action === 'new-search-folder' && (
        <FolderNameDialog
          title={t('admin.folderDialog.newSearchTitle', { name: open.node.name })}
          submit={t('admin.folderDialog.create')}
          taken={namesBeside(open.node)}
          rule={{ rootFolderId: root.id }}
          send={(name, rule) => create(open.node, name, rule)}
          onClose={close}
        />
      )}
      {open?.action === 'rename' && (
        <FolderNameDialog
          title={t('admin.folderDialog.renameTitle', { name: open.node.name })}
          submit={t('admin.folderDialog.rename')}
          initial={open.node.name}
          taken={namesBeside(open.parent, open.node.id)}
          send={(name) => rename(open.node, name)}
          onClose={close}
        />
      )}
      {open?.action === 'move' && open.parent && <MoveFolderDialog node={open.node} parent={open.parent} root={root} send={(into) => move(open.node, into)} onClose={close} />}
      {open?.action === 'delete' && <DeleteFolderDialog node={open.node} kind={kind} run={() => remove(open.node, open.parent)} onClose={close} />}
    </div>
  )
}
