// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useCallback, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { idFrom } from '@healdan/shell'
import { Button, CheckGroup, DatePicker, TextField } from '@healdan/ui'
import { useFocusWhenGone } from '../focus'
import { useChosen } from '../lists/useChosen'
import { useWords } from '../lists/useWords'
import { MatterPicker } from '../pickers/MatterPicker'
import { PersonPicker } from '../pickers/PersonPicker'
import { useUser } from '../people/queries'
import { t } from '../strings'
import type { AuditSearch } from '../views/AuditView'
import { useMatter } from '../workspace/queries'
import { auditKinds, inKindOrder, kindLabel } from './text'
import styles from './Audit.module.css'

type A = adminComponents['schemas']

export interface KindsFilterProps {
  /** The kinds a person may tick, in the server's order */
  offered: readonly A['AuditKind'][]
  /** The kinds ticked; none is every kind */
  value: readonly A['AuditKind'][]
  onChange: (kinds: A['AuditKind'][] | undefined) => void
}

/** The kinds of row kept, as tick boxes in a row: none ticked keeps every kind. The kinds go out in the server's order. */
export function KindsFilter({ offered, value, onChange }: KindsFilterProps) {
  return (
    <div className={styles.kinds}>
      <CheckGroup
        label={t('admin.audit.kinds')}
        choices={offered.map((kind) => ({ value: kind, label: kindLabel(kind) }))}
        value={[...value]}
        onChange={(next) => onChange(inKindOrder(next))}
      />
    </div>
  )
}

/**
 * The doc # the words box holds, as a safe integer: a bare number, or one after `#`, as each row's Detail writes a doc #
 * (`# 10388`); anything else is words
 */
function docNumberIn(words: string): number | undefined {
  const number = /^#?\s*(\d+)$/.exec(words)?.[1]
  return number === undefined ? undefined : idFrom(number)
}

/**
 * The person the address names by id, by name, for the Person box after a reload: asked under their page's key while
 * nothing was picked here (`ask`), and "Person 7" when the server can't name them, as for an account that has gone, so the
 * box never looks empty while the filter holds
 */
function usePersonNamed(id: number | undefined, ask: boolean): A['PersonBrief'] | null {
  const asked = useUser(id ?? -1, ask && id !== undefined)
  if (id === undefined) return null
  if (asked.data?.id === id) return { id, username: asked.data.username, name: asked.data.name }
  return asked.isError ? { id, username: '', name: t('admin.audit.personById', { id }) } : null
}

/** The matter the address names by id, by number and name, for the Matter box, as usePersonNamed names a person */
function useMatterNamed(id: number | undefined, ask: boolean): A['MatterBrief'] | null {
  const asked = useMatter(id ?? -1, ask && id !== undefined)
  if (id === undefined) return null
  if (asked.data?.id === id) return { id, number: asked.data.number, name: asked.data.name }
  // Known by its id alone: no number, so the box shows the name alone
  return asked.isError ? { id, number: '', name: t('admin.audit.matterById', { id }) } : null
}

export interface AuditFiltersProps {
  search: AuditSearch
  /** A change of filters, which the Audit Trail puts in its address */
  onChange: (next: Partial<AuditSearch>) => void
}

/**
 * The Audit Trail's filters (spec 8.8): the kinds; a person and a matter, each a search (P5-R23) showing who or what is
 * chosen, by name, from the address too, and emptied to take it off; the first and the last day; and one box for a doc #,
 * a name or an address, a bare number being a doc # and anything else words, sent once the typing pauses. Clear Filters
 * takes them all off.
 */
export function AuditFilters({ search, onChange }: AuditFiltersProps) {
  // The person and the matter the address names, by name: as picked here, else as the server names them by id
  const [chosenPerson, choosePerson] = useChosen<A['PersonBrief']>(search.user)
  const [chosenMatter, chooseMatter] = useChosen<A['MatterBrief']>(search.matter)
  const namedPerson = usePersonNamed(search.user, chosenPerson === undefined)
  const namedMatter = useMatterNamed(search.matter, chosenMatter === undefined)
  const person = chosenPerson !== undefined ? chosenPerson : search.user === undefined ? null : namedPerson
  const matter = chosenMatter !== undefined ? chosenMatter : search.matter === undefined ? null : namedMatter

  // The doc # as it was typed (`# 10388`), which the box goes on showing once the address names that doc #
  const [typedDoc, setTypedDoc] = useState<string>()
  const docText = search.doc === undefined ? undefined : typedDoc !== undefined && docNumberIn(typedDoc) === search.doc ? typedDoc : String(search.doc)
  const words = useWords(docText ?? search.q ?? '', (typed) => {
    const doc = docNumberIn(typed)
    setTypedDoc(doc === undefined ? undefined : typed)
    onChange(doc !== undefined ? { doc, q: undefined } : { doc: undefined, q: typed === '' ? undefined : typed })
  })
  const filtered = Object.values(search).some((value) => value !== undefined)

  // Clear Filters goes once nothing is filtered, so the focus it had goes to the words box
  const wordsInput = useRef<HTMLInputElement | null>(null)
  const wordsBox = useCallback((element: HTMLDivElement | null) => {
    wordsInput.current = element?.querySelector('input') ?? null
  }, [])
  useFocusWhenGone(!filtered, wordsInput)

  return (
    <div role="group" aria-label={t('admin.audit.filters')} className={styles.filters}>
      <KindsFilter offered={auditKinds} value={search.kind ?? []} onChange={(kind) => onChange({ kind })} />
      <div className={styles.fields}>
        <div className={styles.filter}>
          {/* Over the Users list, so whoever matches is offered, those who have left too */}
          <PersonPicker
            label={t('admin.audit.person')}
            accounts="users"
            value={person}
            onChange={(next) => {
              choosePerson(next)
              onChange({ user: next?.id })
            }}
          />
        </div>
        <div className={styles.filter}>
          <MatterPicker
            value={matter}
            onChange={(next) => {
              chooseMatter(next)
              onChange({ matter: next?.id })
            }}
          />
        </div>
        <div className={styles.day}>
          <DatePicker label={t('admin.audit.from')} value={search.from} onChange={(from) => onChange({ from })} />
        </div>
        <div className={styles.day}>
          <DatePicker label={t('admin.audit.to')} value={search.to} onChange={(to) => onChange({ to })} />
        </div>
        <div ref={wordsBox} className={`${styles.filter} ${styles.words}`}>
          <TextField label={t('admin.audit.text')} value={words.text} onChange={words.type} />
        </div>
        {filtered && (
          <Button
            onPress={() => {
              words.clear()
              choosePerson(null)
              chooseMatter(null)
              onChange({ kind: undefined, user: undefined, matter: undefined, doc: undefined, from: undefined, to: undefined, q: undefined })
            }}
          >
            {t('admin.audit.clear')}
          </Button>
        )}
      </div>
    </div>
  )
}
