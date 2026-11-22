// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, Dialog, TextField, toast } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { typed, useDebounced } from '../pickers/typing'
import { useAdminQuery } from '../queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import formStyles from '../forms/Form.module.css'
import { groupChanged } from './groupQueries'
import { groupDescriptionProblem, groupFieldProblems, groupNameProblem } from './groupText'

type A = adminComponents['schemas']

/** The groups a name's look-up reads at most: those whose name holds it, the first by name */
const TAKEN_LIMIT = 100

/** The fields New Group shows, which a `422 validation` can name beside them */
const FIELDS = ['name', 'description']

/**
 * The group that has a name already, in any case, if any, asked once the typing pauses, so a form says so before its
 * button (spec 7.2): the groups whose name holds it, and the one whose name is it, but the group being renamed. The
 * server still refuses a name taken meanwhile, or one a deleted group kept.
 */
function useNameTakenBy(name: string, exceptId?: number): A['Group'] | undefined {
  const q = typed(name)
  const settled = useDebounced(q)
  const found = useAdminQuery('/v1/admin/groups', { query: { q: settled, limit: TAKEN_LIMIT }, enabled: settled !== '', keepPrevious: true })
  if (q === '' || q !== settled) return undefined
  const groups: A['Group'][] = found.data?.items ?? []
  return groups.find((group) => group.id !== exceptId && group.name.toLowerCase() === q.toLowerCase())
}

/** A change on its way, which holds the dialog, and what it was refused with */
function useSending() {
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  async function send(change: () => Promise<void>) {
    setBusy(true)
    setFailure(undefined)
    try {
      await change()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }
  return { failure, busy, send, clear: () => setFailure(undefined) }
}

export interface NewGroupDialogProps {
  /** The group made, just before the dialog closes: where to go next is the caller's */
  onCreated?: (group: A['GroupDetail']) => void
  /** The dialog closes: Cancel, Esc, a click outside, or once made. While Create is on its way it can't be. */
  onClose: () => void
}

/**
 * New Group (spec 8.7): its name and, if wanted, a description. What the form can see is wrong is said before Create
 * Group, a name another group has, in any case, among it; the server's refusal of a field beside it, and the rest above
 * the buttons. The group made has no members and grants nothing. While Create is on its way, the dialog holds.
 */
export function NewGroupDialog({ onCreated, onClose }: NewGroupDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const sending = useSending()
  const takenBy = useNameTakenBy(name)
  const server = groupFieldProblems(sending.failure)
  const nameError = groupNameProblem(name, takenBy) ?? server.get('name')
  const descriptionError = groupDescriptionProblem(description) ?? server.get('description')
  const refusal = sending.failure !== undefined && !FIELDS.some((field) => server.has(field)) ? refusalFor(sending.failure) : undefined
  const ready = name.trim() !== '' && groupNameProblem(name, takenBy) === undefined && groupDescriptionProblem(description) === undefined

  function create() {
    void sending.send(async () => {
      const about = description.trim()
      const made = await api.send<A['GroupDetail']>('POST', '/v1/admin/groups', { name: name.trim(), description: about === '' ? null : about } satisfies A['NewGroup'])
      groupChanged(queryClient, made)
      toast(t('admin.newGroup.done', { name: made.name }))
      onCreated?.(made)
      // Not through the dialog's own close, which is held while busy
      onClose()
    })
  }

  return (
    <Dialog title={t('admin.newGroup.title')} isOpen onOpenChange={(open) => !open && !sending.busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !sending.busy) create()
          }}
        >
          <fieldset disabled={sending.busy} className={formStyles.hold}>
            <TextField
              label={t('admin.newGroup.name')}
              autoFocus
              required
              autoComplete="off"
              value={name}
              error={nameError}
              onChange={(value) => {
                setName(value)
                sending.clear()
              }}
            />
            <TextField
              label={t('admin.newGroup.description')}
              multiline
              rows={2}
              value={description}
              error={descriptionError}
              onChange={(value) => {
                setDescription(value)
                sending.clear()
              }}
            />
            <p className={formStyles.note}>{t('admin.newGroup.note')}</p>
          </fieldset>
          <Refusal>{refusal}</Refusal>
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={sending.busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={sending.busy}>
              {t('admin.newGroup.create')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

export interface GroupDialogProps {
  group: A['GroupDetail']
  /** The dialog closes: Cancel, Esc, a click outside, or once changed. While the change is on its way it can't be. */
  onClose: () => void
}

/**
 * Rename (spec 8.7): the group's name alone, which its members, folders, Stores and sign-in providers' group maps follow. A
 * name another group has, in any case, is said before the button, as the server would refuse it; changing only the case of
 * its own name renames it. A fixed group's page doesn't offer it. While the rename is on its way, the dialog holds.
 */
export function RenameGroupDialog({ group, onClose }: GroupDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [name, setName] = useState(group.name)
  const sending = useSending()
  const takenBy = useNameTakenBy(name, group.id)
  const server = groupFieldProblems(sending.failure)
  const trimmed = name.trim()
  const nameError = groupNameProblem(name, takenBy) ?? server.get('name')
  const refusal = sending.failure !== undefined && !server.has('name') ? refusalFor(sending.failure) : undefined
  const ready = trimmed !== '' && trimmed !== group.name && groupNameProblem(name, takenBy) === undefined

  function rename() {
    void sending.send(async () => {
      const saved = await api.send<A['GroupDetail']>('PATCH', apiPath`/v1/admin/groups/${group.id}`, { name: trimmed } satisfies A['GroupChange'])
      groupChanged(queryClient, saved)
      toast(t('admin.renameGroup.done', { before: group.name, after: saved.name }))
      onClose()
    })
  }

  return (
    <Dialog title={t('admin.renameGroup.title', { name: group.name })} isOpen onOpenChange={(open) => !open && !sending.busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !sending.busy) rename()
          }}
        >
          <fieldset disabled={sending.busy} className={formStyles.hold}>
            <TextField
              label={t('admin.renameGroup.name')}
              autoFocus
              required
              autoComplete="off"
              value={name}
              error={nameError}
              onChange={(value) => {
                setName(value)
                sending.clear()
              }}
            />
            <p className={formStyles.note}>{t('admin.renameGroup.note')}</p>
          </fieldset>
          <Refusal>{refusal}</Refusal>
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={sending.busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={sending.busy}>
              {t('admin.renameGroup.rename')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

/**
 * Edit Description (spec 8.7): what the group is for, a fixed group's too; blank takes it away. While the change is on its
 * way, the dialog holds.
 */
export function GroupDescriptionDialog({ group, onClose }: GroupDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [description, setDescription] = useState(group.description ?? '')
  const sending = useSending()
  const server = groupFieldProblems(sending.failure)
  const trimmed = description.trim()
  const error = groupDescriptionProblem(description) ?? server.get('description')
  const refusal = sending.failure !== undefined && !server.has('description') ? refusalFor(sending.failure) : undefined
  const ready = trimmed !== (group.description ?? '') && groupDescriptionProblem(description) === undefined

  function save() {
    void sending.send(async () => {
      const saved = await api.send<A['GroupDetail']>('PATCH', apiPath`/v1/admin/groups/${group.id}`, { description: trimmed === '' ? null : trimmed } satisfies A['GroupChange'])
      groupChanged(queryClient, saved)
      toast(t('admin.groupDescription.done', { name: saved.name }))
      onClose()
    })
  }

  return (
    <Dialog title={t('admin.groupDescription.title', { name: group.name })} isOpen onOpenChange={(open) => !open && !sending.busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !sending.busy) save()
          }}
        >
          <fieldset disabled={sending.busy} className={formStyles.hold}>
            <TextField
              label={t('admin.groupDescription.label')}
              autoFocus
              multiline
              rows={3}
              value={description}
              error={error}
              onChange={(value) => {
                setDescription(value)
                sending.clear()
              }}
            />
          </fieldset>
          <Refusal>{refusal}</Refusal>
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={sending.busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={sending.busy}>
              {t('admin.groupDescription.save')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
