// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useCallback, useRef } from 'react'
import type { adminComponents } from '@healdan/api'
import { idFrom } from '@healdan/shell'
import { Button, CheckGroup, DatePicker, TextField } from '@healdan/ui'
import { useFocusWhenGone } from '../focus'
import { useChosen } from '../lists/useChosen'
import { useWords } from '../lists/useWords'
import { MatterPicker } from '../pickers/MatterPicker'
import { PersonPicker } from '../pickers/PersonPicker'
import { t } from '../strings'
import type { AuditSearch } from '../views/AuditView'
import { useMatterNamed, usePersonNamed } from './queries'
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

/** The doc # a bare number typed in the words box is, as a safe integer; anything else is words */
function docNumberIn(words: string): number | undefined {
  return /^\d+$/.test(words) ? idFrom(words) : undefined
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

  const words = useWords(search.doc !== undefined ? String(search.doc) : (search.q ?? ''), (typed) => {
    const doc = docNumberIn(typed)
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
