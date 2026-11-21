// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import { type adminComponents } from '@healdan/api'
import { fieldErrors, idFrom } from '@healdan/shell'
import { Button, Dialog, SelectField, TextField } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { useFolderContents } from './queries'
import { NEW_RULE, RuleFields, ruleOf, useClassOptions, type RuleDraft } from './SearchFolderPanel'
import { holdsWhat, nameProblem } from './text'
import { below, placed, type WorkspaceNode } from './tree'
import page from '../matters/MatterPage.module.css'
import styles from '../clients/Clients.module.css'

type A = adminComponents['schemas']

/** The names a folder's name can't take: those of everything else in the folder it's in */
export const namesBeside = (parent: WorkspaceNode | undefined, except?: number) => (parent?.children ?? []).filter((one) => one.id !== except).map((one) => one.name)

export interface FolderNameDialogProps {
  title: string
  submit: string
  /** The name it starts with: a rename's, the folder's own */
  initial?: string
  /** The names it can't take */
  taken: readonly string[]
  /** For New Search Folder: its rule's fields too, under the name; the workspace's top folder lists the Classes */
  rule?: { rootFolderId: number }
  /** Sends what was asked for; a refusal it throws stays in the dialog */
  send: (name: string, rule?: A['SearchFolderRule']) => Promise<void>
  /** The dialog closes: Cancel, Esc, a click outside, or once done. The caller stops drawing it. */
  onClose: () => void
}

/**
 * New Folder, New Search Folder and Rename in a workspace: a name, checked as the server would before the button (a slash,
 * the length, one the folder beside it has; spec 7.2, P2-R15), and a new search folder's rule. A field the server names
 * is said beside it, any other refusal in the rule's words. While it's on its way the dialog holds, as ClientDialog does.
 */
export function FolderNameDialog({ title, submit, initial = '', taken, rule, send, onClose }: FolderNameDialogProps) {
  const refusalFor = useRefusalFor()
  const classOptions = useClassOptions(rule?.rootFolderId ?? -1, rule !== undefined)
  const [name, setName] = useState(initial)
  const [draft, setDraft] = useState<RuleDraft>(NEW_RULE)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const trimmed = name.trim()
  const fields = fieldErrors(failure)
  const nameError = nameProblem(trimmed, taken) ?? fields.get('name')
  const sentRule = rule ? ruleOf(draft) : undefined
  const refusal = failure !== undefined && !fields.has('name') ? refusalFor(failure) : undefined
  const ready = trimmed !== '' && trimmed !== initial && nameError === undefined && (rule === undefined || sentRule !== undefined)

  async function go() {
    setBusy(true)
    setFailure(undefined)
    try {
      await send(trimmed, sentRule)
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={title} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void go()
          }}
        >
          <TextField
            label={t('admin.folderDialog.name')}
            autoFocus
            required
            value={name}
            error={nameError}
            onChange={(value) => {
              setName(value)
              setFailure(undefined)
            }}
          />
          {rule && (
            <RuleFields
              draft={draft}
              classes={classOptions.classes}
              subclasses={classOptions.subclasses}
              onChange={(next) => {
                setDraft(next)
                setFailure(undefined)
              }}
            />
          )}
          {refusal !== undefined && (
            <p className={styles.refusal} role="alert">
              {refusal}
            </p>
          )}
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {submit}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

export interface MoveFolderDialogProps {
  node: WorkspaceNode
  /** The folder it's in now */
  parent: WorkspaceNode
  root: WorkspaceNode
  send: (into: WorkspaceNode) => Promise<void>
  onClose: () => void
}

/**
 * Move To… within a workspace: a menu of its folders, each by its path, leaving out the folder itself, what's inside it
 * and the search folders, which hold no folders (a folder moves to another matter in the User app). It says before Move
 * that the folder it's in moves nothing, and that a folder there has the name.
 */
export function MoveFolderDialog({ node, parent, root, send, onClose }: MoveFolderDialogProps) {
  const refusalFor = useRefusalFor()
  const [into, setInto] = useState('')
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const inside = new Set([node.id, ...below(node).map((one) => one.id)])
  const targets = placed(root).filter((one) => one.node.kind === 'folder' && !inside.has(one.node.id))
  const target = targets.find((one) => one.node.id === idFrom(into))?.node
  const already = target?.id === parent.id
  const clash = target && !already ? nameProblem(node.name, namesBeside(target)) : undefined
  const problem = already ? t('admin.moveFolder.already') : clash
  const ready = target !== undefined && problem === undefined

  async function go() {
    if (!target) return
    setBusy(true)
    setFailure(undefined)
    try {
      await send(target)
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t('admin.moveFolder.title', { name: node.name })} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void go()
          }}
        >
          <SelectField
            label={t('admin.moveFolder.into')}
            required
            value={into}
            options={targets.map((one) => ({ value: String(one.node.id), label: one.path.join(' › ') }))}
            error={problem}
            onChange={(value) => {
              setInto(value)
              setFailure(undefined)
            }}
          />
          {failure !== undefined && (
            <p className={styles.refusal} role="alert">
              {refusalFor(failure)}
            </p>
          )}
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t('admin.moveFolder.confirm')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

export interface DeleteFolderDialogProps {
  node: WorkspaceNode
  kind: 'matter' | 'template'
  run: () => Promise<void>
  onClose: () => void
}

/**
 * Delete in a workspace: asks first, naming what the folder holds, and where it can come back from: a matter's from the
 * Trash, a template's from nowhere. A matter's folder is counted afresh; a template's, which the public route doesn't
 * reach (it answers only under a Store) and which holds no documents, from the tree the editor has.
 */
export function DeleteFolderDialog({ node, kind, run, onClose }: DeleteFolderDialogProps) {
  const contents = useFolderContents(node.id, kind === 'matter')
  const what = holdsWhat(kind === 'template' ? { folders: below(node).length, documents: 0 } : contents.data)
  return (
    <Confirm title={t('admin.deleteFolder.title', { name: node.name })} confirm={t('admin.deleteFolder.confirm')} run={run} onClose={onClose}>
      {what !== undefined && <p className={page.line}>{t('admin.deleteFolder.holds', { what })}</p>}
      <p className={page.line}>{t(kind === 'template' ? 'admin.deleteFolder.template' : 'admin.deleteFolder.matter')}</p>
      {/* Its Trash item is listed, but the Trash's Restore offers no folder of the Templates Store */}
      {kind === 'template' && <p className={page.line}>{t('admin.deleteFolder.templateTrash')}</p>}
    </Confirm>
  )
}
