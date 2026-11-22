// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, Dialog, toast } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import formStyles from '../forms/Form.module.css'
import { draftProblems, draftReady, PersonFields, serverProblems, type BlankNames, type PersonDraft, type PersonField } from './PersonFields'
import { peopleChanged, useUserOptions } from './queries'
import { providerOf } from './text'

type A = adminComponents['schemas']

/** The fields the form shows, which a `422 validation` can name beside them */
const SHOWN: readonly PersonField[] = ['firstName', 'lastName', 'email', 'language', 'timeZone']

function draftOf(person: A['User']): PersonDraft {
  return { firstName: person.firstName ?? '', lastName: person.lastName ?? '', email: person.email ?? '', language: person.language, timeZone: person.timeZone ?? '' }
}

/** What Save sends: each field that changed, trimmed, and nothing else, as a member left out stays as it is */
function changesOf(before: PersonDraft, after: PersonDraft): A['UserChange'] {
  const change: A['UserChange'] = {}
  if (after.firstName.trim() !== before.firstName.trim()) change.firstName = after.firstName.trim()
  if (after.lastName.trim() !== before.lastName.trim()) change.lastName = after.lastName.trim()
  if (after.email.trim() !== before.email.trim()) change.email = after.email.trim() === '' ? null : after.email.trim()
  if (after.language !== before.language) change.language = after.language
  if (after.timeZone !== before.timeZone) change.timeZone = after.timeZone === '' ? null : after.timeZone
  return change
}

/**
 * Edit (spec 8.7; P4-R11): a person's names, email, language and time zone; their username never changes. A name stored
 * blank may stay blank, and one that wasn't is needed (P4-R29, R31). Save sends only
 * what changed since the dialog opened, so a language or a time zone the options don't list stays as it is unless
 * another is chosen. For an
 * account linked to a sign-in provider, the form says the provider may set the names and email again at its next
 * sign-in. The server's refusal of a field is said beside it, and the rest above the buttons; while Save is on its way,
 * the dialog holds. Once saved, the person's page has the answer, and the lists, the find and the matters' rows, which
 * name their owners, ask again.
 */
export function EditUserDialog({ person, onClose }: { person: A['User']; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const options = useUserOptions().data
  // What the form opened with, kept: a new read of the person while it's open, as at a return to the window or after
  // another administrator's edit, changes neither the fields nor what counts as changed, so Save sends only what was
  // typed here, never the old value of a field someone else has changed meanwhile
  const [before] = useState(() => draftOf(person))
  const [draft, setDraft] = useState(before)
  // A name stored blank may stay blank, as the server keeps it (P4-R29, R31: a provider's claims can leave one blank);
  // one that wasn't blank when the dialog opened is still needed. Left blank, it's unchanged, so it isn't sent
  const [blank] = useState<BlankNames>(() => ({ firstName: before.firstName.trim() === '', lastName: before.lastName.trim() === '' }))
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const server = serverProblems(failure)
  // A field as it opened isn't judged, as Save leaves it as it is: an email made elsewhere stays until it's changed
  const own = draftProblems(draft, before)
  const errors: Partial<Record<PersonField, string>> = {
    firstName: own.firstName ?? server.get('firstName'),
    lastName: own.lastName ?? server.get('lastName'),
    email: own.email ?? server.get('email'),
    language: server.get('language'),
    timeZone: server.get('timeZone'),
  }
  const refusal = failure !== undefined && !SHOWN.some((field) => server.has(field)) ? refusalFor(failure) : undefined
  const change = changesOf(before, draft)
  const ready = draftReady(draft, blank, before) && Object.keys(change).length > 0
  const provider = providerOf(person)

  async function save() {
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await api.send<A['User']>('PATCH', apiPath`/v1/admin/users/${person.id}`, change)
      peopleChanged(queryClient, saved, { matters: true })
      toast(t('admin.editUser.saved', { name: saved.name }))
      // Not through the dialog's own close, which is held while busy
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t('admin.editUser.title', { name: person.name })} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void save()
          }}
        >
          {provider !== undefined && <p className={formStyles.note}>{t('admin.editUser.provider', { provider: provider.label })}</p>}
          <fieldset disabled={busy} className={formStyles.hold}>
            <PersonFields
              draft={draft}
              options={options}
              errors={errors}
              autoFocus
              blank={blank}
              onChange={(next) => {
                setDraft(next)
                // A change takes the last refusal away
                setFailure(undefined)
              }}
            />
          </fieldset>
          <Refusal>{refusal}</Refusal>
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t('admin.editUser.save')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
