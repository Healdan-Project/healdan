// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Icon, SelectField, toast, VisuallyHidden } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { plural, t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { AddPersonOrGroup } from './AddPersonOrGroup'
import { useEditState } from './edits'
import { EveryPermission } from './EveryPermission'
import { bodyOf, grantOf, sameGrants, sortGrants, type Grant } from './grants'
import { levelOf, NAMED_LEVELS, permissionsOf, type FolderLevel, type NamedLevel } from './levels'
import { useFolderAccess, workspaceChanged, workspaceKeys } from './queries'
import { andList } from './text'
import { below, findNode, followersOf, type WorkspaceNode } from './tree'
import page from '../matters/MatterPage.module.css'
import styles from './Workspace.module.css'

type A = adminComponents['schemas']

export interface AccessPanelProps {
  node: WorkspaceNode
  root: WorkspaceNode
  /** The folder it's in, which Follow Parent follows; undefined for the workspace's top */
  parent: WorkspaceNode | undefined
  kind: 'matter' | 'template'
  /** In a template, what New Matter would refuse of this folder, said as warnings that don't stop Save (spec 8.4) */
  warnings: readonly A['TemplateProblem'][]
}

const levelLabel = (level: FolderLevel) => t(`admin.access.level.${level}`)

/**
 * Who Can Use This Folder (spec 8.4, P2-R8, P2-R14, P2-R15): what the folder grants each group and person, as one of the
 * three levels or Custom, with the whole grid under Show Every Permission. A folder either follows another's permissions
 * (Set Own Permissions makes them its own) or has its own (Follow Parent gives them up, after asking). Save sends each
 * row as exact flags; the server's refusal is said beside Save, in the rule's words. Apply to Subfolders is offered only
 * where the server offers it, and asks first, naming the folders below whose own permissions it replaces.
 */
export function AccessPanel(props: AccessPanelProps) {
  const headingId = useId()
  const asked = useFolderAccess(props.node.id)
  return (
    <section aria-labelledby={headingId} className={styles.section}>
      {asked.isError ? (
        <>
          <h3 id={headingId} className={styles.sectionTitle}>
            {t('admin.access.title')}
          </h3>
          <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
        </>
      ) : asked.data === undefined ? (
        <>
          <h3 id={headingId} className={styles.sectionTitle}>
            {t('admin.access.title')}
          </h3>
          <p className={styles.note}>{t('common.loading')}</p>
        </>
      ) : (
        <AccessEditor {...props} access={asked.data} headingId={headingId} />
      )}
    </section>
  )
}

type Asking = 'add' | 'follow' | 'apply'

/** Where the focus goes once the last row that can be removed is: Add Person or Group */
const ADD = 'add'

function AccessEditor({ node, root, parent, kind, warnings, access, headingId }: AccessPanelProps & { access: A['FolderAccess']; headingId: string }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  // The rows being changed; null while they're the server's
  const [draft, setDraft] = useState<Grant[] | null>(null)
  const [settingOwn, setSettingOwn] = useState(false)
  const [every, setEvery] = useState(false)
  const [asking, setAsking] = useState<Asking | null>(null)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  // Where the focus goes once a row is removed, and its Remove with it: the next row's Remove, or Add Person or Group
  const removeButtons = useRef(new Map<string, HTMLButtonElement>())
  const addButton = useRef<HTMLButtonElement>(null)
  const refocus = useRef<string | undefined>(undefined)

  const isTop = node.id === root.id
  const editable = access.ownPermissions || settingOwn
  // A matter's own folder keeps its owner's row, at Full Access, as the server does (spec 8.4), and never follows its Store
  const ownerStays = kind === 'matter' && isTop
  // Whatever was stored for it (a read-only account's row holds seven flags), the row is shown and sent at Full Access
  const original = sortGrants(access.rows.map(grantOf).map((row) => (ownerStays && row.owner ? { ...row, permissions: permissionsOf('full-access') } : row)))
  const rows = draft ?? original
  const dirty = settingOwn || (draft !== null && !sameGrants(draft, original))
  const empty = editable ? rows.find((row) => row.permissions.length === 0) : undefined
  const locked = (row: Grant) => ownerStays && row.owner
  const followed = access.followsFolderId === null ? undefined : findNode(root, access.followsFolderId)
  const restricted = below(node).filter((one) => one.ownPermissions)
  // What follows this folder, and so takes its parent's permissions with it, should it follow its parent
  const followers = followersOf(node)
  useEditState('access', dirty, busy)

  // Once the rows are drawn without the one removed
  useEffect(() => {
    const key = refocus.current
    if (key === undefined) return
    refocus.current = undefined
    ;(key === ADD ? addButton.current : removeButtons.current.get(key))?.focus()
  })

  function change(next: Grant[]) {
    setDraft(sortGrants(next))
    setFailure(undefined)
  }

  function setPermissions(key: string, permissions: A['Permission'][]) {
    change(rows.map((row) => (row.key === key ? { ...row, permissions } : row)))
  }

  function remove(row: Grant) {
    const index = rows.findIndex((one) => one.key === row.key)
    const rest = rows.filter((one) => one.key !== row.key)
    refocus.current = rest.slice(index).find((one) => !locked(one))?.key ?? ADD
    change(rest)
  }

  /**
   * The access the server answered with is the folder's from now on, and what it reaches below is asked again. A read
   * still on its way, as on a return to the window, began before the save, so it's called off rather than land after it.
   */
  async function saved(answer: A['FolderAccess']) {
    await queryClient.cancelQueries({ queryKey: workspaceKeys.access(node.id), exact: true })
    queryClient.setQueryData(workspaceKeys.access(node.id), answer)
    setDraft(null)
    setSettingOwn(false)
    workspaceChanged(queryClient, root.id, kind, workspaceKeys.access(node.id))
  }

  async function save() {
    setBusy(true)
    setFailure(undefined)
    try {
      const body: A['FolderAccessChange'] = { rows: rows.map(bodyOf) }
      await saved(await api.send<A['FolderAccess']>('PUT', apiPath`/v1/admin/folders/${node.id}/access`, body))
      toast(t('admin.access.saved', { name: node.name }))
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  async function followParent() {
    const body: A['FolderAccessChange'] = { followParent: true }
    await saved(await api.send<A['FolderAccess']>('PUT', apiPath`/v1/admin/folders/${node.id}/access`, body))
    toast(t('admin.access.followed', { name: node.name, parent: parent?.name ?? '' }))
  }

  async function apply() {
    await api.send('POST', apiPath`/v1/admin/folders/${node.id}/apply-access`)
    workspaceChanged(queryClient, root.id, kind)
    toast(t('admin.access.applied', { name: node.name }))
  }

  const taken = {
    groups: new Set(rows.filter((row) => !row.person && row.groupId !== null).map((row) => row.groupId!)),
    people: new Set(rows.filter((row) => row.person && row.userId !== null).map((row) => row.userId!)),
  }

  return (
    <>
      <div className={styles.sectionHead}>
        <h3 id={headingId} className={styles.sectionTitle}>
          {t('admin.access.title')}
        </h3>
        {editable && (
          <Button ref={addButton} isDisabled={busy} onPress={() => setAsking('add')}>
            <span className={styles.withIcon}>
              <Icon name="plus" size={14} />
              {t('admin.access.add')}
            </span>
          </Button>
        )}
      </div>
      {!access.ownPermissions && !settingOwn && (
        <p className={styles.followed}>{followed ? t('admin.access.follows', { name: followed.name }) : t('admin.access.followsStore')}</p>
      )}
      {/* Held while a save is on its way, so nothing changed meanwhile is dropped under its Saved */}
      <fieldset disabled={busy} className={styles.hold}>
        <table className={styles.table} aria-label={t('admin.access.table', { name: node.name })}>
          <thead>
            <tr>
              <th scope="col">{t('admin.access.who')}</th>
              <th scope="col">{t('admin.access.access')}</th>
              {editable && (
                <th scope="col" className={styles.removeCell}>
                  <VisuallyHidden>{t('admin.access.removeColumn')}</VisuallyHidden>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const level = levelOf(row.permissions)
              return (
                <tr key={row.key}>
                  <th scope="row">
                    <span className={styles.who}>
                      <Icon name={row.person ? 'user' : 'usersGroup'} size={14} />
                      {row.name}
                      {row.owner && (
                        <>
                          {' '}
                          <span className={styles.chip}>{t('admin.access.owner')}</span>
                        </>
                      )}
                    </span>
                  </th>
                  {editable && !locked(row) ? (
                    <>
                      <td>
                        <SelectField
                          label={t('admin.access.levelFor', { name: row.name })}
                          labelHidden
                          value={level}
                          options={[...NAMED_LEVELS, ...(level === 'custom' ? (['custom'] as const) : [])].map((one) => ({ value: one, label: levelLabel(one) }))}
                          onChange={(value) => {
                            if ((NAMED_LEVELS as readonly string[]).includes(value)) setPermissions(row.key, permissionsOf(value as NamedLevel))
                          }}
                        />
                      </td>
                      <td className={styles.removeCell}>
                        <Button
                          variant="quiet"
                          isDisabled={busy}
                          aria-label={t('admin.access.remove', { name: row.name })}
                          ref={(button) => {
                            if (button) removeButtons.current.set(row.key, button)
                            else removeButtons.current.delete(row.key)
                          }}
                          onPress={() => remove(row)}
                        >
                          <Icon name="close" size={14} />
                        </Button>
                      </td>
                    </>
                  ) : (
                    <>
                      <td>{levelLabel(level)}</td>
                      {/* The owner's row on a matter's own folder: kept, at the level the server keeps it at */}
                      {editable && <td className={styles.removeCell} />}
                    </>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
        {rows.length === 0 && <p className={styles.note}>{t('admin.access.none')}</p>}
        {empty && <p className={styles.problem}>{t('admin.access.empty', { name: empty.name })}</p>}
        {every && <EveryPermission rows={rows} editable={editable} locked={locked} onChange={setPermissions} />}
      </fieldset>
      <div className={styles.buttons}>
        <Button aria-expanded={every} onPress={() => setEvery(!every)}>
          {t('admin.access.every')}
        </Button>
        {!access.ownPermissions && !settingOwn && <Button onPress={() => setSettingOwn(true)}>{t('admin.access.setOwn')}</Button>}
        {access.ownPermissions && !ownerStays && parent !== undefined && (
          <Button isDisabled={busy} onPress={() => setAsking('follow')}>
            {t('admin.access.followParent')}
          </Button>
        )}
        {access.canApplyToSubfolders && (
          <Button isDisabled={dirty || busy} onPress={() => setAsking('apply')}>
            {t('admin.access.apply')}
          </Button>
        )}
        {editable && (
          <span className={`${styles.buttons} ${styles.push}`}>
            {dirty && (
              <Button
                isDisabled={busy}
                onPress={() => {
                  setDraft(null)
                  setSettingOwn(false)
                  setFailure(undefined)
                }}
              >
                {t('admin.access.discard')}
              </Button>
            )}
            <Button variant="default" isDisabled={!dirty || empty !== undefined} isPending={busy} onPress={() => void save()}>
              {t('admin.access.save')}
            </Button>
          </span>
        )}
      </div>
      {failure !== undefined && (
        <p className={styles.problem} role="alert">
          {refusalFor(failure)}
        </p>
      )}
      {access.canApplyToSubfolders && dirty && <p className={styles.note}>{t('admin.access.applySaveFirst')}</p>}
      {ownerStays && editable && <p className={styles.note}>{t('admin.access.ownerStays')}</p>}
      {kind === 'matter' && editable && <p className={styles.note}>{t('admin.access.narrows')}</p>}
      {warnings.length > 0 && (
        <div role="note" className={styles.warning}>
          <Icon name="alertTriangle" />
          <div>
            <p>{t('admin.access.warnings')}</p>
            <ul>
              {warnings.map((warning, index) => (
                <li key={`${warning.code}-${warning.groupId}-${warning.storeId}-${index}`}>{warning.message}</li>
              ))}
            </ul>
          </div>
        </div>
      )}
      {asking === 'add' && <AddPersonOrGroup taken={taken} onAdd={(grant) => change([...rows, grant])} onClose={() => setAsking(null)} />}
      {asking === 'follow' && parent && (
        <Confirm title={t('admin.access.followTitle', { parent: parent.name })} confirm={t('admin.access.followParent')} run={followParent} onClose={() => setAsking(null)}>
          <p className={page.line}>{t('admin.access.followLine', { name: node.name, parent: parent.name })}</p>
          {followers.length > 0 && <p className={page.line}>{plural('admin.access.followBelow', followers.length, { names: andList(followers.map((one) => one.name)) })}</p>}
        </Confirm>
      )}
      {asking === 'apply' && (
        <Confirm title={t('admin.access.applyTitle', { name: node.name })} confirm={t('admin.access.apply')} run={apply} onClose={() => setAsking(null)}>
          <p className={page.line}>{t('admin.access.applyLine', { name: node.name })}</p>
          <p className={page.line}>
            {restricted.length > 0 ? t('admin.access.applyOwn', { names: andList(restricted.map((one) => one.name)) }) : t('admin.access.applyNoneOwn')}
          </p>
        </Confirm>
      )}
    </>
  )
}
