// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useBlocker } from '@tanstack/react-router'
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { ApiError, apiPath, type adminComponents, type components } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Icon, toast, toastWithAction, type ToastAction } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { AccessPanel } from './AccessPanel'
import { EditsContext, type EditState } from './edits'
import { DeleteFolderDialog, FolderNameDialog, MoveFolderDialog, namesBeside } from './FolderDialogs'
import { actionsOf, FolderTree, type FolderAction } from './FolderTree'
import { PropertiesPanel } from './PropertiesPanel'
import { useTemplateProblems, useWorkspaceTree, workspaceChanged } from './queries'
import { SearchFolderPanel } from './SearchFolderPanel'
import { below, findNode, holding, parentOf, type WorkspaceNode } from './tree'
import page from '../matters/MatterPage.module.css'
import styles from './Workspace.module.css'

type A = adminComponents['schemas']
type S = components['schemas']

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
 * is said as warnings beside the folder's Save, which still saves. A matter's folder deleted here has Undo; a template's
 * hasn't, as the Trash can't put it back. Leaving the page over a change unsaved, or a save on its way, asks first.
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
  const refusalFor = useRefusalFor()
  const foldersId = useId()
  const [selected, setSelected] = useState(root.id)
  const [expanded, setExpanded] = useState(() => holding(root))
  const [open, setOpen] = useState<Open | null>(null)
  // Where each panel of the chosen folder stands (edits.ts), and the folder chosen over an unsaved change, asked about,
  // or over a save on its way, which opens once it's saved
  const parts = useRef(new Map<string, EditState>())
  const [leaving, setLeaving] = useState<number | null>(null)
  const [waiting, setWaiting] = useState<number | null>(null)
  const waitingFor = useRef<number | null>(null)
  // Where the panels stand together: a save on its way, else a change unsaved, else nothing, for leaving the page
  const [standing, setStanding] = useState<EditState>('clean')

  const wait = (id: number | null) => {
    waitingFor.current = id
    setWaiting(id)
  }

  const report = useCallback((part: string, state: EditState) => {
    const was = parts.current.get(part)
    if (state === 'clean') parts.current.delete(part)
    else parts.current.set(part, state)
    const all = [...parts.current.values()]
    setStanding(all.includes('saving') ? 'saving' : all.length > 0 ? 'dirty' : 'clean')
    const next = waitingFor.current
    if (was !== 'saving' || state === 'saving' || next === null) return
    // Refused: the panel says why, and the folder chosen meanwhile is forgotten
    if (state === 'dirty') return wait(null)
    const states = [...parts.current.values()]
    if (states.includes('saving')) return
    wait(null)
    if (states.includes('dirty')) setLeaving(next)
    else setSelected(next)
  }, [])

  // Leaving the page over a change, by a link, the find, the crumb or Back, asks first; changing the tab doesn't, as the
  // editor stays where it is. A reload or closing the window over one is asked about by the browser, in its own words.
  const changing = standing !== 'clean'
  const shouldBlock = useCallback(({ current, next }: { current: { pathname: string }; next: { pathname: string } }) => current.pathname !== next.pathname && parts.current.size > 0, [])
  const blocker = useBlocker({ shouldBlockFn: shouldBlock, enableBeforeUnload: false, disabled: !changing, withResolver: true })
  useEffect(() => {
    if (!changing) return
    const ask = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', ask)
    return () => window.removeEventListener('beforeunload', ask)
  }, [changing])

  /** Another folder chosen: at once, after asking over an unsaved change, or once a save on its way is saved */
  function choose(id: number) {
    if (id === selected) return wait(null)
    const states = [...parts.current.values()]
    if (states.includes('saving')) return wait(id)
    if (states.includes('dirty')) return setLeaving(id)
    setSelected(id)
  }

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
    // The new folder is chosen, unless that would drop a change to the chosen one
    if (parts.current.size === 0) setSelected(made.id)
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
    const deleted = await api.send<A['DeletedFolder']>('DELETE', apiPath`/v1/admin/folders/${on.id}`)
    changed()
    // Only a chosen folder that went with it gives way, to the folder it was in
    if (on.id === node.id || below(on).some((one) => one.id === node.id)) {
      parts.current.clear()
      setSelected((from ?? root).id)
    }
    const done = t('admin.deleteFolder.done', { name: on.name })
    // A matter's folder comes back from the delete's Trash item; a template's, or one of its folders, doesn't: the Trash
    // restores into a Store only, and answers it 409 place-gone
    if (kind === 'matter') toastWithAction(done, undo(deleted.trashItemId, on.name))
    else toast(done)
  }

  /**
   * The toast's Undo for a delete: restores its Trash item back where it was, once, however often it's pressed, as the
   * User app's Undo does. What happened since can refuse it: an item no longer in the Trash, a place gone, a rule.
   */
  function undo(trashItemId: number, name: string): ToastAction {
    let used = false
    return {
      label: t('undo.label'),
      onAction: () => {
        if (used) return
        used = true
        void restore(trashItemId, name)
      },
    }
  }

  async function restore(trashItemId: number, name: string) {
    try {
      await api.send<S['RestoredItem']>('POST', apiPath`/v1/trash/${trashItemId}/restore`)
    } catch (error) {
      toast(t('admin.deleteFolder.undoRefused', { name, reason: undoRefusal(error) }))
      return
    }
    changed()
    toast(t('undo.done', { name }))
  }

  /** Why Undo didn't restore it: restored already or removed (404), its place gone, or the rule's own words */
  function undoRefusal(error: unknown): string {
    if (error instanceof ApiError && error.status === 404) return t('undo.alreadyRestored')
    if (error instanceof ApiError && error.code === 'place-gone') return t('undo.placeGone')
    return refusalFor(error)
  }

  return (
    <EditsContext.Provider value={report}>
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
          <FolderTree root={root} kind={kind} selected={node.id} onSelect={choose} expanded={expanded} onExpandedChange={setExpanded} onAction={act} />
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
          {/* Always there, so a screen reader hears it as it changes */}
          <p role="status" className={styles.note}>
            {waiting !== null && t('admin.workspace.waiting', { name: findNode(root, waiting)?.name ?? '' })}
          </p>
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
        {leaving !== null && (
          <Confirm
            title={t('admin.workspace.leaveTitle', { name: node.name })}
            confirm={t('admin.access.discard')}
            run={async () => {
              parts.current.clear()
              setSelected(leaving)
            }}
            onClose={() => setLeaving(null)}
          >
            <p className={page.line}>{t('admin.workspace.leaveLine', { name: node.name, next: findNode(root, leaving)?.name ?? '' })}</p>
          </Confirm>
        )}
        {blocker.status === 'blocked' && (
          <Confirm
            title={t('admin.workspace.leaveTitle', { name: node.name })}
            confirm={standing === 'saving' ? t('admin.workspace.leavePage') : t('admin.access.discard')}
            run={async () => blocker.proceed()}
            onClose={() => blocker.reset()}
          >
            <p className={page.line}>{t(standing === 'saving' ? 'admin.workspace.leaveSavingLine' : 'admin.workspace.leavePageLine', { name: node.name })}</p>
          </Confirm>
        )}
      </div>
    </EditsContext.Provider>
  )
}
