// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useCallback, useId, useRef, useState, type ReactNode, type RefObject } from 'react'
import { ApiError, apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom, useApi, useMe } from '@healdan/shell'
import { Button, formatDate, formatDateTime, Icon, StatusDot, Tabs, toast, type IconName } from '@healdan/ui'
import { routes } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import { useFocusWhenGone } from '../focus'
import { Problems, type Problem } from '../forms/Problems'
import { Refusal } from '../forms/Refusal'
import { ActivityPanel } from '../monitoring/ActivityPanel'
import { CheckoutsPanel } from '../people/CheckoutsPanel'
import { DevicesPanel } from '../people/DevicesPanel'
import { EditUserDialog } from '../people/EditUserDialog'
import { GroupsEditor } from '../people/GroupsEditor'
import { peopleChanged, personChanged, useUser, useUserOptions } from '../people/queries'
import { ResetPasswordDialog } from '../people/ResetPasswordDialog'
import { StaleNote } from '../people/StaleNote'
import { providerOf, statusDot, statusWords } from '../people/text'
import { mattersInSentence } from '../stores/text'
import { plural, t } from '../strings'
import { EditsContext, type EditState } from '../workspace/edits'
import { LeavePageQuestion } from '../workspace/LeavePageQuestion'
import clientStyles from '../clients/Clients.module.css'
import page from '../matters/MatterPage.module.css'
import styles from '../people/People.module.css'
import pageStyles from './views.module.css'

type A = adminComponents['schemas']

/** A person's page's tabs: their Overview, and their Activity, the Audit Trail of what they did and what was done to them */
export type UserTab = 'overview' | 'activity'

/** What a person's page's address holds: the tab open, when it isn't the Overview */
export interface UserSearch {
  tab?: Exclude<UserTab, 'overview'>
}

/** The address's parameters the page reads; a tab it doesn't know opens the Overview */
export function userSearch(search: Record<string, unknown>): UserSearch {
  return { tab: search.tab === 'activity' ? 'activity' : undefined }
}

/**
 * A person's page, at /admin/users/{id} (spec 8.7): their name and status; Edit, Reset Password (for a password account),
 * Sign Out Everywhere, Clear Sign-In Block (while blocked), and Disable or Enable; then, in the Overview tab, their
 * details, their groups, their signed-in devices and what they hold (check-outs, and the matters they own, in the Matters
 * list), and in the Activity tab what they did and what was done to them (P4-R4, P5-R10), the tab in the address. An
 * action the server would refuse is greyed out, with the server's sentence under the actions (`refusals`), which describes
 * it; its own refusal still shows, for a change made meanwhile, until a dialog opens or the person's status changes.
 * Disable and Sign Out Everywhere ask first. While a new read fails, the page and any dialog open on it stay, with a note
 * that it couldn't refresh (P4-R15). An address that names nobody, or a deleted or system account, says so in the app's
 * words.
 */
export function UserView({ id, search = {} }: { id: string; search?: UserSearch }) {
  const userId = idFrom(id)
  return userId === undefined ? <UserNotFound /> : <UserPage id={userId} tab={search.tab ?? 'overview'} />
}

function UserPage({ id, tab }: { id: number; tab: UserTab }) {
  const asked = useUser(id)
  if (asked.isError && asked.error instanceof ApiError && asked.error.status === 404) return <UserNotFound />
  if (asked.data === undefined) {
    return <div className={page.page}>{asked.isError ? <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} /> : <p className={page.muted}>{t('common.loading')}</p>}</div>
  }
  // By its id, so another person's page, by Back or Forward too, starts afresh: no dialog or change carried over
  return <Person key={asked.data.id} person={asked.data} tab={tab} stale={asked.isError} onRetry={() => void asked.refetch()} />
}

type Open = 'edit' | 'reset' | 'disable' | 'signOut'

/** A page's action: its icon and words */
function Action({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <span className={page.withIcon}>
      <Icon name={icon} size={14} />
      {children}
    </span>
  )
}

/** A section of the page, named by its heading, which takes the focus when what had it goes from the section */
function Section({ title, heading, wide = false, children }: { title: string; heading?: RefObject<HTMLHeadingElement | null>; wide?: boolean; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className={wide ? `${styles.section} ${styles.wide}` : styles.section}>
      <h2 id={id} ref={heading} tabIndex={heading ? -1 : undefined} className={styles.sectionTitle}>
        {title}
      </h2>
      {children}
    </section>
  )
}

/** How a person signs in, as their details say it: the provider, or a password with when it was set */
function signsInWith(person: A['User']): string {
  const provider = providerOf(person)
  if (provider !== undefined) return provider.label
  if (person.mustChangePassword) return t('admin.user.mustChange')
  return person.passwordChanged === null ? t('admin.users.password') : t('admin.user.passwordSet', { when: formatDate(person.passwordChanged) })
}

function Person({ person, tab, stale, onRetry }: { person: A['User']; tab: UserTab; stale: boolean; onRetry: () => void }) {
  const api = useApi()
  const navigate = useNavigate({ from: routes.user })
  // Once opened, the Overview stays mounted behind Activity, with a change to their groups unsaved, or on its way, still
  // there, as a matter's Workspace does; nothing of it is asked for until it's opened
  const [overviewOpened, setOverviewOpened] = useState(tab === 'overview')
  if (tab === 'overview' && !overviewOpened) setOverviewOpened(true)
  const queryClient = useQueryClient()
  const own = useMe().data?.id === person.id
  const options = useUserOptions().data
  const [open, setOpen] = useState<Open | null>(null)
  const [busy, setBusy] = useState<'enable' | 'clear-block' | null>(null)
  // What Enable or Clear Sign-In Block was refused with, said under the actions
  const [failure, setFailure] = useState<unknown>()
  const [statusSeen, setStatusSeen] = useState(person.status)
  // The person's status changed, by a change here or a new read: what was refused before no longer stands
  if (person.status !== statusSeen) {
    setStatusSeen(person.status)
    setFailure(undefined)
  }
  const devicesHeading = useRef<HTMLHeadingElement>(null)
  const holdsHeading = useRef<HTMLHeadingElement>(null)
  const signOutButton = useRef<HTMLButtonElement>(null)
  // Clear Sign-In Block goes once the person isn't blocked, so the focus it had goes to the action before it
  useFocusWhenGone(person.status !== 'blocked', signOutButton)
  const why = { enable: useId(), disable: useId(), resetPassword: useId() }
  const close = () => setOpen(null)
  // A dialog opening takes the page's last refusal away
  const show = (dialog: Open) => {
    setFailure(undefined)
    setOpen(dialog)
  }
  const provider = providerOf(person)
  const disabled = person.status === 'disabled'
  // Where the groups stand (edits.ts), so leaving the page over a change unsaved, or a save on its way, asks first
  const parts = useRef(new Map<string, EditState>())
  const [standing, setStanding] = useState<EditState>('clean')
  const report = useCallback((part: string, state: EditState) => {
    if (state === 'clean') parts.current.delete(part)
    else parts.current.set(part, state)
    const all = [...parts.current.values()]
    setStanding(all.includes('saving') ? 'saving' : all.length > 0 ? 'dirty' : 'clean')
  }, [])
  const changing = useCallback(() => parts.current.size > 0, [])

  // The sentences of the actions the server would refuse, said under them: only those the page offers, each describing
  // its greyed button (why), so a screen reader says which is whose
  const offered: { action: keyof typeof why; sentence: string | null }[] = [
    disabled ? { action: 'enable', sentence: person.refusals.enable } : { action: 'disable', sentence: person.refusals.disable },
    { action: 'resetPassword', sentence: provider === undefined ? person.refusals.resetPassword : null },
  ]
  const refused: Problem[] = offered.flatMap(({ action, sentence }) => (sentence === null ? [] : [{ key: action, id: why[action], sentence }]))
  const describedBy = (action: keyof typeof why) => (refused.some((problem) => problem.key === action) ? why[action] : undefined)

  /** Enable and Clear Sign-In Block: nothing is taken away, so nothing is asked */
  async function run(action: 'enable' | 'clear-block') {
    setBusy(action)
    setFailure(undefined)
    try {
      const saved = await api.send<A['User']>('POST', action === 'enable' ? apiPath`/v1/admin/users/${person.id}/enable` : apiPath`/v1/admin/users/${person.id}/clear-block`)
      peopleChanged(queryClient, saved)
      toast(t(action === 'enable' ? 'admin.user.enabled' : 'admin.user.blockCleared', { name: saved.name }))
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(null)
    }
  }

  async function disable() {
    const saved = await api.send<A['User']>('POST', apiPath`/v1/admin/users/${person.id}/disable`)
    // The devices it signed out
    peopleChanged(queryClient, saved, { devices: true })
    toast(t('admin.disableUser.done', { name: saved.name }))
  }

  async function signOut() {
    const out = await api.send<A['SignedOut']>('POST', apiPath`/v1/admin/users/${person.id}/sign-out`)
    toast(
      out.sessions === 0 && out.devices === 0
        ? t('admin.signOutUser.nothing', { name: person.name })
        : t('admin.signOutUser.done', { name: person.name, sessions: plural('admin.signOutUser.sessions', out.sessions), devices: plural('admin.signOutUser.devices', out.devices) }),
    )
    // Signed out here too, when it was the caller's own: the next request is a 401, which signs the app out
    personChanged(queryClient, person.id, { devices: true })
  }

  const language = options?.languages.find((one) => one.code === person.language)?.name ?? person.language

  function choose(next: string) {
    // A tab isn't a step of its own, as an order isn't
    void navigate({ to: routes.user, params: { id: String(person.id) }, search: next === 'activity' ? { tab: 'activity' } : {}, replace: true })
  }

  return (
    <div className={page.page}>
      <nav aria-label={t('admin.crumbs.label')} className={clientStyles.crumbs}>
        <ol>
          <li>
            <Link to={routes.users} className={clientStyles.crumbLink}>
              {t('admin.users.title')}
            </Link>
          </li>
          <li aria-current="page">{person.name}</li>
        </ol>
      </nav>
      <div className={clientStyles.head}>
        <h1 className={clientStyles.title}>{person.name}</h1>
        <StatusDot status={statusDot(person.status)}>{statusWords(person, true)}</StatusDot>
        <div className={clientStyles.actions}>
          <Button onPress={() => show('edit')}>
            <Action icon="pencil">{t('admin.user.edit')}</Action>
          </Button>
          {/* A provider account has no password in Healdan to reset (spec 8.7) */}
          {provider === undefined && (
            <Button isDisabled={person.refusals.resetPassword !== null} aria-describedby={describedBy('resetPassword')} onPress={() => show('reset')}>
              <Action icon="key">{t('admin.user.resetPassword')}</Action>
            </Button>
          )}
          <Button ref={signOutButton} onPress={() => show('signOut')}>
            <Action icon="logout">{t('admin.user.signOut')}</Action>
          </Button>
          {person.status === 'blocked' && (
            <Button isPending={busy === 'clear-block'} onPress={() => void run('clear-block')}>
              <Action icon="lockOpen">{t('admin.user.clearBlock')}</Action>
            </Button>
          )}
          {disabled ? (
            <Button isDisabled={person.refusals.enable !== null} aria-describedby={describedBy('enable')} isPending={busy === 'enable'} onPress={() => void run('enable')}>
              <Action icon="userCheck">{t('admin.user.enable')}</Action>
            </Button>
          ) : (
            <Button variant="danger" isDisabled={person.refusals.disable !== null} aria-describedby={describedBy('disable')} onPress={() => show('disable')}>
              <Action icon="userOff">{t('admin.user.disable')}</Action>
            </Button>
          )}
        </div>
      </div>
      <StaleNote failed={stale} onRetry={onRetry} />
      {refused.length > 0 && (
        <div className={styles.refused}>
          <Problems problems={refused} />
        </div>
      )}
      <Refusal failure={failure} />
      <Tabs
        label={t('admin.user.tabs')}
        selected={tab}
        onSelect={choose}
        tabs={[
          {
            id: 'overview',
            label: t('admin.user.tab.overview'),
            keepMounted: overviewOpened,
            content: (
              <EditsContext.Provider value={report}>
                <div className={styles.sections}>
                  <Section title={t('admin.user.details')}>
                    <dl className={styles.details}>
                      <dt>{t('admin.user.username')}</dt>
                      <dd>{person.username}</dd>
                      <dt>{t('admin.user.email')}</dt>
                      <dd>{person.email ?? t('admin.user.none')}</dd>
                      <dt>{t('admin.user.signsInWith')}</dt>
                      <dd>{signsInWith(person)}</dd>
                      <dt>{t('admin.user.language')}</dt>
                      <dd>{language}</dd>
                      <dt>{t('admin.user.timeZone')}</dt>
                      <dd>{person.timeZone ?? t('admin.user.none')}</dd>
                      <dt>{t('admin.user.created')}</dt>
                      <dd>{formatDate(person.created)}</dd>
                      <dt>{t('admin.user.lastSignIn')}</dt>
                      <dd>{person.lastSignIn === null ? t('admin.users.never') : formatDateTime(person.lastSignIn)}</dd>
                    </dl>
                    {person.readOnly && <p className={styles.note}>{t('admin.user.readOnly')}</p>}
                    {person.admin && <p className={styles.note}>{t('admin.user.isAdmin')}</p>}
                  </Section>
                  <Section title={t('admin.user.groups')}>
                    <GroupsEditor person={person} />
                  </Section>
                  <Section title={t('admin.user.devices')} heading={devicesHeading} wide>
                    <DevicesPanel person={person} heading={devicesHeading} />
                  </Section>
                  <Section title={t('admin.user.holds')} heading={holdsHeading} wide>
                    <CheckoutsPanel person={person} heading={holdsHeading} />
                    <p className={styles.line}>
                      {person.mattersOwned > 0 ? (
                        // The matters they own, in the Matters list, filtered by them as owner
                        <Link to={routes.matters} search={{ owner: person.id }} className={styles.link}>
                          {t('admin.user.owns', { matters: mattersInSentence(person.mattersOwned) })}
                        </Link>
                      ) : (
                        t('admin.user.ownsNone')
                      )}
                    </p>
                  </Section>
                </div>
              </EditsContext.Provider>
            ),
          },
          { id: 'activity', label: t('admin.user.tab.activity'), content: <ActivityPanel fixed={{ userId: person.id }} name={person.name} /> },
        ]}
      />
      <LeavePageQuestion standing={standing} name={person.name} changing={changing} />
      {open === 'edit' && <EditUserDialog person={person} onClose={close} />}
      {open === 'reset' && <ResetPasswordDialog person={person} onClose={close} />}
      {open === 'disable' && (
        <Confirm title={t('admin.disableUser.title', { name: person.name })} confirm={t('admin.disableUser.confirm')} run={disable} onClose={close}>
          <p className={styles.line}>{t('admin.disableUser.line')}</p>
          <p className={styles.line}>{person.devices > 0 ? plural('admin.disableUser.devices', person.devices) : t('admin.disableUser.noDevices')}</p>
        </Confirm>
      )}
      {open === 'signOut' && (
        <Confirm title={t('admin.signOutUser.title', { name: person.name })} confirm={t('admin.signOutUser.confirm')} run={signOut} onClose={close}>
          <p className={styles.line}>{t('admin.signOutUser.line')}</p>
          {own && <p className={styles.line}>{t('admin.signOutUser.yourself')}</p>}
        </Confirm>
      )}
    </div>
  )
}

/** Nobody at the address: in the app's words, with the way to the Users list */
function UserNotFound() {
  return (
    <div className={pageStyles.view}>
      <h1 className={pageStyles.title}>{t('admin.user.notFound.title')}</h1>
      <p className={pageStyles.line}>{t('admin.user.notFound.message')}</p>
      <Link to={routes.users} className={pageStyles.link}>
        {t('admin.user.notFound.users')}
      </Link>
    </div>
  )
}
