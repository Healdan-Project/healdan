// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, CheckField, Dialog, formatList, TextField } from '@healdan/ui'
import { useFocusWhenGone } from '../focus'
import { Problems } from '../forms/Problems'
import { Refusal } from '../forms/Refusal'
import { GroupsField } from '../pickers/GroupsField'
import { typed, useDebounced } from '../pickers/typing'
import { useAdminQuery } from '../queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import formStyles from '../forms/Form.module.css'
import { AddAdminQuestion } from './AddAdminQuestion'
import { draftProblems, draftReady, PersonFields, serverProblems, usernameProblem, type PersonDraft, type PersonField } from './PersonFields'
import { peopleChanged, useUserOptions } from './queries'
import { PasswordShown } from './TemporaryPassword'
import { EVERYONE, isAdminGroup, nameOf } from './text'
import styles from './People.module.css'

type A = adminComponents['schemas']

export interface NewUserDialogProps {
  /** The person made, once the dialog that shows their temporary password closes: where to go next is the caller's */
  onCreated?: (user: A['User']) => void
  /**
   * The dialog closes: Cancel, Esc, a click outside, or, once the person is made, Done alone, as their password is shown
   * only there (P4-R38). The caller stops drawing it. While Create is on its way it can't be closed, as the server would
   * make the account anyway.
   */
  onClose: () => void
}

/** The fields the form shows, which a `422 validation` can name beside them */
const SHOWN: readonly PersonField[] = ['username', 'firstName', 'lastName', 'email', 'language', 'timeZone']

/** The most people a username's look-up reads, the first by name */
const TAKEN_LIMIT = 20

/**
 * The person who has a username already, if any, asked once the typing pauses, so New User says so before its button
 * (spec 7.2): the Users list's words find the people whose username holds it, and the one whose username is it, in any
 * case, is the one. The server still refuses a username a deleted or system account has, or one taken meanwhile.
 */
function useUsernameTakenBy(username: string): A['UserRow'] | undefined {
  const q = typed(username)
  const settled = useDebounced(q)
  const found = useAdminQuery('/v1/admin/users', { query: { q: settled, limit: TAKEN_LIMIT }, enabled: settled !== '', keepPrevious: true })
  if (q === '' || q !== settled) return undefined
  const people: A['UserRow'][] = found.data?.items ?? []
  return people.find((person) => person.username.toLowerCase() === q.toLowerCase())
}

/** A person made, and what the create answered of their temporary password, held only while the dialog shows it */
interface Made {
  answer: A['CreatedUser']
  asked: boolean
}

/**
 * New User (spec 8.7): a password account, with its username, names, email, language and time zone, and the groups it's
 * in besides Everyone, picked from a search. Create User asks first when it adds them to `admin`. While a sign-in provider is on, the form says
 * that people who sign in through it needn't be added. Once made, its temporary password is shown once, with Copy, or
 * emailed when that was asked and could go, and the dialog says which; the password goes as the dialog closes (P4-R7).
 * Emailing is offered only while outgoing email is set up and an email is typed. What the form can see is wrong is said
 * before Create, a username someone has among them, and so is a failed read of what it offers (the languages above all,
 * one of which every account has), with Try Again; the server's refusal of a field beside it, and the rest above the
 * buttons. While Create is on its way, the dialog holds. The dialog that shows the password closes only from Done.
 */
export function NewUserDialog({ onCreated, onClose }: NewUserDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const offered = useUserOptions()
  const options = offered.data
  // What it offers couldn't be read, and nothing was before: Language has no choices, so nothing can be made yet. Decided
  // on the options missing after a failure, not on the query's error, which a new read clears as it starts: so the note,
  // and Try Again, pending, stay until they come
  const unread = options === undefined && offered.errorUpdateCount > 0
  const languageMenu = useRef<HTMLSelectElement>(null)
  // Try Again goes once they come, so the focus goes to the menu they filled, which has its choices by then
  useFocusWhenGone(!unread, languageMenu)
  const [username, setUsername] = useState('')
  const [draft, setDraft] = useState<PersonDraft>({ firstName: '', lastName: '', email: '', language: '', timeZone: '' })
  const [groups, setGroups] = useState<A['Group'][]>([])
  const [emailTicked, setEmailTicked] = useState(false)
  const [asking, setAsking] = useState(false)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [made, setMade] = useState<Made>()

  // The language New User fills in until another is chosen
  const language = draft.language || options?.defaultLanguage || ''
  const filled: PersonDraft = { ...draft, language }
  const takenBy = useUsernameTakenBy(username)
  const server = serverProblems(failure)
  const own = draftProblems(filled)
  const errors: Partial<Record<PersonField, string>> = {
    username: usernameProblem(username) ?? (takenBy ? t('admin.newUser.usernameTakenBy', { name: takenBy.name }) : undefined) ?? server.get('username'),
    firstName: own.firstName ?? server.get('firstName'),
    lastName: own.lastName ?? server.get('lastName'),
    email: own.email ?? server.get('email'),
    language: server.get('language'),
    timeZone: server.get('timeZone'),
  }
  // A refusal that names none of the form's fields is said above its buttons, in the rule's words or the catalogue's
  const refusal = failure !== undefined && !SHOWN.some((field) => server.has(field)) ? refusalFor(failure) : undefined
  const email = draft.email.trim()
  const canEmail = options?.outgoingEmail === true && email !== ''
  const ready = options !== undefined && username.trim() !== '' && usernameProblem(username) === undefined && takenBy === undefined && draftReady(filled)
  const name = nameOf(draft.firstName, draft.lastName)

  function change() {
    // A change takes the last refusal away
    setFailure(undefined)
  }

  function add(group: A['Group']) {
    setGroups((before) => (before.some((one) => one.id === group.id) ? before : [...before, group]))
    change()
  }

  /** Create User: adding them to `admin` asks first */
  function submit() {
    if (groups.some(isAdminGroup)) setAsking(true)
    else void create()
  }

  async function create() {
    setBusy(true)
    setFailure(undefined)
    const asked = canEmail && emailTicked
    try {
      const body: A['NewUser'] = {
        username: username.trim(),
        firstName: draft.firstName.trim(),
        lastName: draft.lastName.trim(),
        email: email === '' ? null : email,
        language,
        timeZone: draft.timeZone === '' ? null : draft.timeZone,
        groupIds: groups.map((group) => group.id),
        emailPassword: asked,
      }
      const answer = await api.send<A['CreatedUser']>('POST', '/v1/admin/users', body)
      // The person's page has them at once, without their password; the lists, the find and the groups ask again
      peopleChanged(queryClient, answer.user)
      setMade({ answer, asked })
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  function finish() {
    const user = made?.answer.user
    // The password goes with the dialog
    setMade(undefined)
    if (user !== undefined) onCreated?.(user)
    onClose()
  }

  if (made !== undefined) {
    const user = made.answer.user
    return (
      // Closed only by Done: Esc and a click outside do nothing, as this is the password's only showing (P4-R38)
      <Dialog title={t('admin.newUser.created', { name: user.name })} isOpen onOpenChange={() => undefined}>
        <div className={formStyles.form}>
          <PasswordShown person={user} answer={made.answer} asked={made.asked} />
          <div className={formStyles.buttons}>
            {/* The form went as this came, so the focus is put inside: on Copy, or here when nothing is shown */}
            <Button variant="default" autoFocus={made.answer.temporaryPassword === null} onPress={finish}>
              {t('admin.password.done')}
            </Button>
          </div>
        </div>
      </Dialog>
    )
  }

  return (
    // Held while Create is on its way, by Cancel, Esc or a click outside alike: the account would still be made
    <Dialog title={t('admin.newUser.title')} size="wide" isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <>
          <form
            className={formStyles.form}
            onSubmit={(event) => {
              event.preventDefault()
              if (ready && !busy && !asking) submit()
            }}
          >
            {options !== undefined && options.providers.length > 0 && (
              <p className={formStyles.note}>{t('admin.newUser.providers', { providers: formatList(options.providers.map((provider) => provider.label)) })}</p>
            )}
            <fieldset disabled={busy} className={formStyles.hold}>
              <TextField
                label={t('admin.newUser.username')}
                autoFocus
                required
                autoComplete="off"
                description={t('admin.newUser.usernameNote')}
                value={username}
                error={errors.username}
                onChange={(value) => {
                  setUsername(value)
                  change()
                }}
              />
              <PersonFields
                draft={filled}
                options={options}
                errors={errors}
                languageRef={languageMenu}
                onChange={(next) => {
                  setDraft(next)
                  change()
                }}
              />
              <GroupsField
                label={t('admin.newUser.groups')}
                items={groups.map((group) => ({
                  id: group.id,
                  name: group.name,
                  tag: isAdminGroup(group) ? <span className={styles.warning}>{t('admin.userGroups.readsEverything')}</span> : undefined,
                }))}
                empty={t('admin.newUser.noGroups')}
                hint={t('admin.newUser.hint')}
                // Everyone needn't be added: every account but a read-only one is in it, and New User makes none of those
                leaveOut={(group) => group.name === EVERYONE}
                onAdd={add}
                onRemove={(item) => {
                  setGroups(groups.filter((one) => one.id !== item.id))
                  change()
                }}
              >
                <p className={formStyles.note}>{t('admin.newUser.everyone')}</p>
              </GroupsField>
              {options !== undefined &&
                (canEmail ? (
                  <CheckField label={t('admin.password.email')} description={t('admin.password.emailTo', { email })} checked={emailTicked} onChange={setEmailTicked} />
                ) : (
                  <p className={formStyles.note}>{t(options.outgoingEmail ? 'admin.password.noEmail' : 'admin.password.notSetUp')}</p>
                ))}
            </fieldset>
            {/* There before it has words, so a screen reader says them as they come */}
            <div role="status">
              {unread && (
                <Problems
                  problems={[
                    {
                      key: 'options',
                      sentence: t('admin.newUser.optionsUnread'),
                      action: (
                        <Button isPending={offered.isFetching} onPress={() => void offered.refetch()}>
                          {t('common.tryAgain')}
                        </Button>
                      ),
                    },
                  ]}
                />
              )}
            </div>
            <Refusal>{refusal}</Refusal>
            <div className={formStyles.buttons}>
              <Button onPress={close} isDisabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
                {t('admin.newUser.create')}
              </Button>
            </div>
          </form>
          {/* Outside the form, so its Confirm never submits New User's (React's events follow the tree into portals) */}
          {asking && <AddAdminQuestion name={name} onConfirm={() => void create()} onClose={() => setAsking(false)} />}
        </>
      )}
    </Dialog>
  )
}
