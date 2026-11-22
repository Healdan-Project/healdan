// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, Dialog, toast } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { PersonPicker } from '../pickers/PersonPicker'
import { plural, t } from '../strings'
import formStyles from '../forms/Form.module.css'
import { AddAdminQuestion } from './AddAdminQuestion'
import { groupKeys, membersChanged } from './groupQueries'
import { mappersOf } from './groupText'

type A = adminComponents['schemas']

/** The members a look-up for someone reads at most: those whose name, username or email holds their username */
const MEMBER_LIMIT = 50

/**
 * Whether the person is in the group already, asked once they're picked, so the dialog says so before its button (spec
 * 7.2); undefined until it's known
 */
function useIsMember(groupId: number, person: A['PersonBrief'] | null): boolean | undefined {
  const api = useApi()
  const query = { q: person?.username ?? '', limit: MEMBER_LIMIT }
  const asked = useQuery({
    queryKey: [...groupKeys.members(groupId), query],
    queryFn: ({ signal }) => api.get<A['GroupMemberList']>(apiPath`/v1/admin/groups/${groupId}/members`, query, { signal }),
    enabled: person !== null,
  })
  if (person === null || asked.data === undefined) return undefined
  const members: A['GroupMember'][] = asked.data.items ?? []
  return members.some((member) => member.id === person.id)
}

export interface AddMemberDialogProps {
  group: A['GroupDetail']
  /** The dialog closes: Cancel, Esc, a click outside, or once they're added. While Add is on its way it can't be. */
  onClose: () => void
}

/**
 * Add Member (spec 8.7): a person found as their name is typed (spec 7.2: anything that can grow long is a search), from
 * the accounts that can sign in, added to the group at once. Someone in it already is said so before Add. Adding someone
 * to `admin` asks first, saying they'll read every document, and the question holds while the change is on its way. A
 * group a sign-in provider's group map names says the map sets the membership again for someone who signs in through it.
 * The server's refusal, such as of a read-only account in any group but `guest`, is said in the rule's words.
 */
export function AddMemberDialog({ group, onClose }: AddMemberDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [person, setPerson] = useState<A['PersonBrief'] | null>(null)
  const [asking, setAsking] = useState(false)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const already = useIsMember(group.id, person)
  const ready = person !== null && already !== true

  /** Adds them, or throws what it was refused with, for whoever asked to say it */
  async function add(who: A['PersonBrief']) {
    await api.send<void>('PUT', apiPath`/v1/admin/groups/${group.id}/members/${who.id}`)
    membersChanged(queryClient)
    toast(t('admin.addMember.done', { name: who.name, group: group.name }))
    onClose()
  }

  async function addHere(who: A['PersonBrief']) {
    setBusy(true)
    setFailure(undefined)
    try {
      await add(who)
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  /** Add: adding them to `admin` asks first */
  function submit() {
    if (person === null) return
    if (group.admin) setAsking(true)
    else void addHere(person)
  }

  return (
    // Held while Add is on its way, by Cancel, Esc or a click outside alike: they'd be added anyway
    <Dialog title={t('admin.addMember.title', { group: group.name })} isOpen onOpenChange={(open) => !open && !busy && !asking && onClose()}>
      {(close) => (
        <>
          <form
            className={formStyles.form}
            onSubmit={(event) => {
              event.preventDefault()
              if (ready && !busy && !asking) submit()
            }}
          >
            <fieldset disabled={busy} className={formStyles.hold}>
              <div className={formStyles.field}>
                <PersonPicker
                  label={t('admin.addMember.person')}
                  accounts="enabled"
                  required
                  value={person}
                  error={already === true && person !== null ? t('admin.addMember.already', { name: person.name, group: group.name }) : undefined}
                  onChange={(next) => {
                    setPerson(next)
                    setFailure(undefined)
                  }}
                />
                <p className={formStyles.note}>{t('admin.addMember.find')}</p>
              </div>
              {group.mappedBy.length > 0 && (
                <p className={formStyles.note}>{plural('admin.addMember.mapped', group.mappedBy.length, { providers: mappersOf(group), group: group.name })}</p>
              )}
            </fieldset>
            <Refusal failure={failure} />
            <div className={formStyles.buttons}>
              <Button onPress={close} isDisabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
                {t('admin.addMember.add')}
              </Button>
            </div>
          </form>
          {/* Outside the form, so its Confirm never submits Add Member's (React's events follow the tree into portals) */}
          {asking && person !== null && <AddAdminQuestion name={person.name} onConfirm={() => add(person)} onClose={() => setAsking(false)} />}
        </>
      )}
    </Dialog>
  )
}
