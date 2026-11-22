// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatDateTime, type Status } from '@healdan/ui'
import { t } from '../strings'

type A = adminComponents['schemas']

/**
 * The fixed groups a person's groups name by their names, which never change: Everyone, which every account but a
 * read-only one is in (the server finds it by this name too); `guest`, which a read-only account is always in; and
 * `admin`, whose members use this app and read every document.
 */
export const EVERYONE = 'Everyone'
export const GUEST = 'guest'
export const ADMIN = 'admin'

/** Whether a group is the `admin` group: by its flag where the server gave one, else by its name, which never changes */
export function isAdminGroup(group: { name: string; admin?: boolean }): boolean {
  return group.admin ?? group.name === ADMIN
}

/** A person's status as a dot shows it: active, a block that wants a look, or an account switched off */
export function statusDot(status: A['UserStatus']): Status {
  return status === 'active' ? 'ok' : status === 'blocked' ? 'attention' : 'off'
}

/** A person's status in words: Active, Disabled, Blocked, or, where the page says it, Blocked until when */
export function statusWords(person: Pick<A['UserRow'], 'status' | 'blockedUntil'>, until = false): string {
  if (person.status === 'blocked' && until && person.blockedUntil !== null) return t('admin.user.blockedUntil', { when: formatDateTime(person.blockedUntil) })
  return t(`admin.user.status.${person.status}`)
}

/** How a person signs in, as the list says it: Password, or the provider's label */
export function signsInWithWords(person: Pick<A['UserRow'], 'signsInWith'>): string {
  return person.signsInWith.kind === 'provider' && person.signsInWith.provider !== null ? person.signsInWith.provider.label : t('admin.users.password')
}

/** The provider a person signs in through, or undefined for a password account */
export function providerOf(person: Pick<A['UserRow'], 'signsInWith'>): A['ProviderBrief'] | undefined {
  return person.signsInWith.kind === 'provider' ? (person.signsInWith.provider ?? undefined) : undefined
}

/** A person's name, as a dialog names them, or `this person` while New User has none typed */
export function nameOf(first: string, last: string): string {
  const name = `${first.trim()} ${last.trim()}`.trim()
  return name === '' ? t('admin.newUser.them') : name
}
