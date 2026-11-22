// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState, type Ref } from 'react'
import { ApiError, type adminComponents } from '@healdan/api'
import { fieldErrors } from '@healdan/shell'
import { PickField, SelectField, TextField } from '@healdan/ui'
import { t } from '../strings'
import styles from './People.module.css'

type A = adminComponents['schemas']

/** The longest username, name and email the server keeps, in UTF-16 units as Java counts them */
export const FIELD_MAX = 255

/** What New User and Edit hold of a person besides their username, as typed: '' for no email or time zone */
export interface PersonDraft {
  firstName: string
  lastName: string
  email: string
  language: string
  timeZone: string
}

export type PersonField = keyof PersonDraft | 'username'

/**
 * A character an email can't hold, as the server checks one (UserForm): a control character, a space the server counts
 * as one (a no-break space isn't), a line or paragraph separator, or half of a pair of UTF-16 units on its own, which
 * Healdan can't keep
 */
function notInEmail(code: number): boolean {
  return (
    code <= 0x20 ||
    (code >= 0x7f && code <= 0x9f) ||
    code === 0x1680 ||
    (code >= 0x2000 && code <= 0x200a && code !== 0x2007) ||
    code === 0x2028 ||
    code === 0x2029 ||
    code === 0x205f ||
    code === 0x3000 ||
    (code >= 0xd800 && code <= 0xdfff)
  )
}

/** An email address as the server takes one: an @ that's neither first nor last, and nothing it can't hold (notInEmail) */
function isEmail(text: string): boolean {
  if (!text.includes('@') || text.startsWith('@') || text.endsWith('@')) return false
  // By code point, so a lone half of a pair is one on its own
  return ![...text].some((character) => notInEmail(character.codePointAt(0) ?? 0))
}

/** Whether a username holds what the server doesn't keep in one: > or a control character (SignInAccounts' rule) */
function unkept(username: string): boolean {
  return [...username].some((character) => {
    const code = character.codePointAt(0) ?? 0
    return character === '>' || code < 0x20 || (code >= 0x7f && code <= 0x9f)
  })
}

/** What the form can see is wrong with a username before it's sent */
export function usernameProblem(username: string): string | undefined {
  const trimmed = username.trim()
  if (unkept(trimmed)) return t('admin.newUser.usernameInvalid')
  if (trimmed.length > FIELD_MAX) return t('fields.too-long')
  return undefined
}

/**
 * What the form can see is wrong with the fields it shares, before they're sent; a field missing is said by its mark.
 * Given what Edit opened with (`opened`), a field still as it was isn't judged, as Save leaves it out and it stays as it
 * is, as a name stored blank does: an email made elsewhere, say.
 */
export function draftProblems(draft: PersonDraft, opened?: PersonDraft): Partial<Record<keyof PersonDraft, string>> {
  const problems: Partial<Record<keyof PersonDraft, string>> = {}
  const judged = (field: 'firstName' | 'lastName' | 'email') => opened === undefined || draft[field].trim() !== opened[field].trim()
  for (const field of ['firstName', 'lastName', 'email'] as const) if (judged(field) && draft[field].trim().length > FIELD_MAX) problems[field] = t('fields.too-long')
  const email = draft.email.trim()
  if (problems.email === undefined && judged('email') && email !== '' && !isEmail(email)) problems.email = t('admin.newUser.emailInvalid')
  return problems
}

/** The names a form may leave blank: in Edit, one stored blank, which the server keeps as it is (P4-R29, R31) */
export type BlankNames = Partial<Record<'firstName' | 'lastName', boolean>>

/**
 * Whether the names the form needs are there, but those it may leave blank, and nothing it can see is wrong, a field
 * still as Edit opened with it (`opened`) left unjudged
 */
export function draftReady(draft: PersonDraft, blank: BlankNames = {}, opened?: PersonDraft): boolean {
  const named = (field: 'firstName' | 'lastName') => blank[field] === true || draft[field].trim() !== ''
  return named('firstName') && named('lastName') && Object.keys(draftProblems(draft, opened)).length === 0
}

/**
 * What a `422 validation` says of each field, in the app's words: a username another account has, or one it can't keep,
 * and an email that isn't one, said as the form says them; any other code as the shared catalogue says it
 */
export function serverProblems(failure: unknown): Map<string, string> {
  const messages = fieldErrors(failure)
  if (!(failure instanceof ApiError)) return messages
  const fields = (failure.body as { fields?: unknown } | null)?.fields
  if (!Array.isArray(fields)) return messages
  for (const item of fields as { field?: unknown; code?: unknown }[]) {
    if (item.field === 'username' && item.code === 'taken') messages.set('username', t('admin.newUser.usernameTaken'))
    if (item.field === 'username' && item.code === 'invalid') messages.set('username', t('admin.newUser.usernameInvalid'))
    if (item.field === 'email' && item.code === 'invalid') messages.set('email', t('admin.newUser.emailInvalid'))
  }
  return messages
}

/** The time zones a search offers at most */
const ZONES_SHOWN = 50

/** A time zone's name as it's matched: in any case, with its underscores as spaces, as `new york` finds America/New_York */
const matchable = (text: string) => text.toLowerCase().replace(/_/g, ' ')

/**
 * A time zone, found as it's typed among the IANA names the server knows, as it knows hundreds (`timeZones` in the
 * options); one the account has that the server doesn't list is kept, as it is. Emptied, the person has none.
 */
function TimeZoneField({ value, zones, onChange, error }: { value: string; zones: readonly string[]; onChange: (zone: string) => void; error?: string }) {
  const [text, setText] = useState('')
  const picked = value === '' ? null : { id: value, label: value }
  const q = matchable(text.trim())
  const options = q === '' ? [] : zones.filter((zone) => matchable(zone).includes(q)).slice(0, ZONES_SHOWN).map((zone) => ({ id: zone, label: zone }))
  return (
    <PickField
      label={t('admin.newUser.timeZone')}
      error={error}
      value={picked}
      options={options}
      onTextChange={(next) => setText(next === picked?.label ? '' : next)}
      onChange={(next) => onChange(next?.id ?? '')}
    />
  )
}

export interface PersonFieldsProps {
  draft: PersonDraft
  onChange: (draft: PersonDraft) => void
  options: A['UserOptions'] | undefined
  /** What's wrong with each field: the form's own problems first, then the server's */
  errors: Partial<Record<PersonField, string>>
  /** The first name takes the focus as the form opens, as Edit's does */
  autoFocus?: boolean
  /** The names it may leave blank, which aren't marked as needed */
  blank?: BlankNames
  /** The Language menu, for moving the focus to it, as when its choices come after a failed read */
  languageRef?: Ref<HTMLSelectElement>
}

/**
 * The fields New User and Edit share (spec 8.7): First Name and Last Name, which are needed but for one Edit found blank
 * (`blank`), Email, Language, a menu of the languages offered, and Time Zone. A language the person has that the options don't list stays, shown as its code,
 * so a save never changes it unasked.
 */
export function PersonFields({ draft, onChange, options, errors, autoFocus, blank = {}, languageRef }: PersonFieldsProps) {
  const set = (field: keyof PersonDraft, value: string) => onChange({ ...draft, [field]: value })
  return (
    <>
      <div className={styles.pair}>
        <TextField label={t('admin.newUser.firstName')} required={blank.firstName !== true} autoFocus={autoFocus} value={draft.firstName} error={errors.firstName} onChange={(value) => set('firstName', value)} />
        <TextField label={t('admin.newUser.lastName')} required={blank.lastName !== true} value={draft.lastName} error={errors.lastName} onChange={(value) => set('lastName', value)} />
      </div>
      <TextField label={t('admin.newUser.email')} value={draft.email} error={errors.email} onChange={(value) => set('email', value)} />
      <div className={styles.pair}>
        <SelectField
          ref={languageRef}
          label={t('admin.newUser.language')}
          required
          value={draft.language}
          error={errors.language}
          options={(options?.languages ?? []).map((language) => ({ value: language.code, label: language.name }))}
          onChange={(value) => set('language', value)}
        />
        <TimeZoneField value={draft.timeZone} zones={options?.timeZones ?? []} error={errors.timeZone} onChange={(value) => set('timeZone', value)} />
      </div>
    </>
  )
}
