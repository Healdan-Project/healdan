// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react'
import type { adminComponents } from '@healdan/api'
import { Button, Highlight, Icon, SearchPicker } from '@healdan/ui'
import { routes } from '../addresses'
import { t } from '../strings'
import { useGroupPicker } from './useGroupPicker'
import styles from './GroupsField.module.css'

type A = adminComponents['schemas']

/** One of the list's groups, or a person's own group */
export interface GroupsFieldItem {
  /** Unique among the items: the group's id */
  id: number
  name: string
  /** A person's own group, drawn with a person's icon in place of a group's */
  person?: boolean
  /** Said after the name, as Custom, or what membership of the group gives */
  tag?: ReactNode
  /** Why it can't be taken away here: said in place of its Remove */
  locked?: string
  /** Said under it: what else is true of it, as a sign-in provider's group map setting it again */
  note?: string
  /** Its name links to the group's page, as a person's groups do; never a person's own group, which has none */
  linked?: boolean
}

export interface GroupsFieldProps {
  /** Above the list, which it names; without it the list is named by `listLabel` alone */
  label?: string
  listLabel?: string
  items: readonly GroupsFieldItem[]
  /** What it says while there's none */
  empty: string
  /** A group was picked to add; it's offered no more once the items have it */
  onAdd: (group: A['Group']) => void
  onRemove: (item: GroupsFieldItem) => void
  /** Under Add a Group: what a group added does */
  hint: string
  /** The groups not to offer, besides those listed */
  leaveOut?: (group: A['Group']) => boolean
  /** The Add a Group field, as when the focus is put there from outside */
  inputRef?: RefObject<HTMLInputElement | null>
  /** Remove does nothing, as while a save is on its way */
  isDisabled?: boolean
  /** False offers no group to add, as to a read-only account, which is in no group but its own and `guest` */
  adding?: boolean
  /** Said between the list and Add a Group: what's true of the list as a whole */
  children?: ReactNode
}

/** Where the focus goes once the last group is removed: Add a Group */
const ADD = 'add'

/**
 * A list of groups that more are added to, from a search (spec 7.2: anything that can grow long is a search), and that
 * each can be removed from: New Store's and a Store's who can see it, and a person's groups. The first groups by name are
 * offered while nothing is typed, and the groups found for what is, those listed left out. Once one is removed, and its
 * Remove with it, the focus goes to the next one's Remove, or to Add a Group. A group that can't be taken away has no
 * Remove, and says why in its place. Names are drawn as text.
 */
export function GroupsField({ label, listLabel, items, empty, onAdd, onRemove, hint, leaveOut, inputRef, isDisabled, adding = true, children }: GroupsFieldProps) {
  const labelId = useId()
  const groups = useGroupPicker(adding)
  const removeButtons = useRef(new Map<number, HTMLButtonElement>())
  const ownField = useRef<HTMLInputElement | null>(null)
  const addField = inputRef ?? ownField
  const refocus = useRef<number | typeof ADD | undefined>(undefined)
  const listed = new Set(items.map((item) => item.id))

  // Once the list is drawn without the group removed
  useEffect(() => {
    const key = refocus.current
    if (key === undefined) return
    refocus.current = undefined
    ;(key === ADD ? addField.current : removeButtons.current.get(key))?.focus()
  })

  function remove(item: GroupsFieldItem) {
    const index = items.findIndex((one) => one.id === item.id)
    const next = items.slice(index + 1).find((one) => one.locked === undefined)
    refocus.current = next?.id ?? ADD
    onRemove(item)
  }

  return (
    <div className={styles.field}>
      <div className={styles.who}>
        {label !== undefined && (
          <p id={labelId} className={styles.label}>
            {label}
          </p>
        )}
        {items.length === 0 ? (
          <p className={styles.note}>{empty}</p>
        ) : (
          <ul aria-labelledby={label !== undefined ? labelId : undefined} aria-label={label === undefined ? listLabel : undefined} className={styles.chosen}>
            {items.map((item) => (
              <li key={item.id} className={styles.group}>
                <span className={styles.groupText}>
                  <span className={styles.groupName}>
                    <Icon name={item.person ? 'user' : 'usersGroup'} size={14} />
                    {item.linked && !item.person ? (
                      <Link to={routes.group} params={{ id: String(item.id) }} className={`${styles.nameText} ${styles.link}`}>
                        {item.name}
                      </Link>
                    ) : (
                      <span className={styles.nameText}>{item.name}</span>
                    )}
                    {item.tag !== undefined && (
                      <>
                        {' '}
                        {item.tag}
                      </>
                    )}
                  </span>
                  {item.note !== undefined && <span className={styles.note}>{item.note}</span>}
                </span>
                {item.locked !== undefined ? (
                  <span className={styles.locked}>{item.locked}</span>
                ) : (
                  <Button
                    variant="quiet"
                    isDisabled={isDisabled}
                    aria-label={t('admin.groupsField.remove', { name: item.name })}
                    ref={(button) => {
                      if (button) removeButtons.current.set(item.id, button)
                      else removeButtons.current.delete(item.id)
                    }}
                    onPress={() => remove(item)}
                  >
                    <Icon name="close" size={14} />
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      {children}
      {adding && (
        <SearchPicker<A['Group']>
          label={t('admin.groupsField.add')}
          placeholder={t('admin.groupsField.find')}
          hint={hint}
          initialHeading={t('admin.groupsField.groups')}
          initial={groups.initial}
          search={groups.search}
          id={(group) => String(group.id)}
          render={(group, query) => (
            <span className={styles.option}>
              <Highlight text={group.name} query={query} />
              {group.description && (
                <>
                  {' '}
                  <span className={styles.description}>
                    <Highlight text={group.description} query={query} />
                  </span>
                </>
              )}
            </span>
          )}
          onPick={(group) => {
            if (!listed.has(group.id)) onAdd(group)
          }}
          leaveOut={(group) => listed.has(group.id) || (leaveOut?.(group) ?? false)}
          inputRef={addField}
        />
      )}
    </div>
  )
}
