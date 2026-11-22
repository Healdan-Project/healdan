// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, type adminComponents } from '@healdan/api'
import { fieldErrors } from '@healdan/shell'
import { formatList } from '@healdan/ui'
import { plural, t } from '../strings'
import { ADMIN, EVERYONE, GUEST } from './text'

type A = adminComponents['schemas']

/** The longest name the Admin app gives a group, so a deleted one can keep `<name>.<id>` within its column */
export const GROUP_NAME_MAX = 200
/** The longest description, and the longest name a group's column holds, in UTF-16 units as Java counts them */
export const GROUP_TEXT_MAX = 255

/** What a person's own group's name starts with, which no other group's may */
const OWN_GROUP = '_user_'

/** Whether text holds a control character, which a group's name and description can't */
function holdsControl(text: string): boolean {
  return [...text].some((character) => {
    const code = character.codePointAt(0) ?? 0
    return code < 0x20 || (code >= 0x7f && code <= 0x9f)
  })
}

/**
 * What the form can see is wrong with a group's name before it's sent: one another group has, in any case (`takenBy`, the
 * group found with it), one a person's own group's could be, a control character, or too long. Blank is said by its mark.
 */
export function groupNameProblem(name: string, takenBy?: Pick<A['Group'], 'name'>): string | undefined {
  const trimmed = name.trim()
  if (trimmed.length > GROUP_NAME_MAX) return t('admin.groupName.tooLong', { max: GROUP_NAME_MAX })
  if (trimmed.startsWith(OWN_GROUP)) return t('admin.groupName.ownGroup')
  if (holdsControl(trimmed)) return t('admin.groupName.control')
  if (takenBy !== undefined) return t('admin.groupName.taken', { name: takenBy.name })
  return undefined
}

/** What the form can see is wrong with a description before it's sent */
export function groupDescriptionProblem(description: string): string | undefined {
  const trimmed = description.trim()
  if (trimmed.length > GROUP_TEXT_MAX) return t('admin.groupDescription.tooLong', { max: GROUP_TEXT_MAX })
  if (holdsControl(trimmed)) return t('admin.groupDescription.control')
  return undefined
}

/**
 * What a `422 validation` says of a group's name and description, in the app's words: `taken`, `too-long` and `invalid` as
 * the form says them, any other code as the shared catalogue says it
 */
export function groupFieldProblems(failure: unknown): Map<string, string> {
  const messages = fieldErrors(failure)
  if (!(failure instanceof ApiError)) return messages
  const fields = (failure.body as { fields?: unknown } | null)?.fields
  if (!Array.isArray(fields)) return messages
  const words: Record<string, Record<string, string>> = {
    name: { taken: t('admin.groupName.takenOnServer'), 'too-long': t('admin.groupName.tooLong', { max: GROUP_NAME_MAX }), invalid: t('admin.groupName.invalid') },
    description: { 'too-long': t('admin.groupDescription.tooLong', { max: GROUP_TEXT_MAX }), invalid: t('admin.groupDescription.control') },
  }
  for (const item of fields as { field?: unknown; code?: unknown }[]) {
    const said = typeof item.field === 'string' && typeof item.code === 'string' ? words[item.field]?.[item.code] : undefined
    if (said !== undefined && typeof item.field === 'string') messages.set(item.field, said)
  }
  return messages
}

/** The providers whose group map names a group, in a sentence: "Authentik", "Authentik and Entra ID" */
export function mappersOf(group: Pick<A['Group'], 'mappedBy'>): string {
  return formatList(group.mappedBy)
}

/** Everyone, which every account but a read-only one is always in, by its name, which never changes */
export function isEveryone(group: Pick<A['Group'], 'name'>): boolean {
  return group.name === EVERYONE
}

/** `guest`, which a read-only account is always in */
export function isGuest(group: Pick<A['Group'], 'name'>): boolean {
  return group.name === GUEST
}

/** Why the group isn't renamed, as the server would refuse it, or undefined: a fixed group keeps its name */
export function renameRefusal(group: A['GroupDetail']): string | undefined {
  return group.fixed ? t('admin.group.fixed', { name: group.name }) : undefined
}

/**
 * Why the group isn't deleted, as the server would refuse it, or undefined: a fixed group, one a sign-in provider's group
 * map names, and one whose name is too long for a deleted group to keep (`<name>.<id>` within the column). A fixed group's
 * sentence is Rename's too, so it's said once.
 */
export function deleteRefusal(group: A['GroupDetail']): string | undefined {
  if (group.fixed) return undefined
  if (group.mappedBy.length > 0) return plural('admin.group.mappedDelete', group.mappedBy.length, { providers: mappersOf(group), name: group.name })
  const room = GROUP_TEXT_MAX - 1 - String(group.id).length
  if (group.name.length > room) return t('admin.group.tooLongToDelete', { max: room })
  return undefined
}

/**
 * Why a member isn't taken out of the group, as the server would refuse it, said in place of their Remove, or undefined:
 * a read-only account from `guest`; the `admin` account, and the person signed in here, from `admin`. Everyone keeps
 * everyone, which its page says once. A change that would leave no enabled administrator is the server's to say.
 */
export function memberLock(group: A['GroupDetail'], member: A['GroupMember'], ownId: number | undefined): string | undefined {
  if (isGuest(group) && member.readOnly) return t('admin.groupMembers.guest')
  if (!group.admin) return undefined
  if (member.username === ADMIN) return t('admin.groupMembers.adminAccount')
  if (member.id === ownId) return t('admin.groupMembers.yourself')
  return undefined
}
