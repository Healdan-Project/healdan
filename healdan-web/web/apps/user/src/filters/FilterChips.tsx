// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef } from 'react'
import { Button, formatDay, formatList, Icon, t } from '@healdan/ui'
import { typeLabel } from './FiltersPanel'
import { activeCount, type FilterKind, type Filters } from './useFilters'
import styles from './filters.module.css'

export interface FilterChipsProps {
  filters: Filters
  /** Authors' names by username, as far as known; a username shows as itself otherwise */
  authors?: ReadonlyMap<string, string>
  onRemove: (kind: FilterKind) => void
  onClear: () => void
  /** Where focus goes when no chip is left to take it: the funnel, which the chips are the filters of */
  onEmpty: () => void
}

function dateText(filters: Filters): string {
  const show = formatDay
  if (filters.from && filters.to) return t('filters.date.range', { from: show(filters.from), to: show(filters.to) })
  if (filters.from) return t('filters.date.since', { from: show(filters.from) })
  if (filters.to) return t('filters.date.until', { to: show(filters.to) })
  return filters.edited ? t(`filters.date.${filters.edited}`) : ''
}

/** A chip for each filter in use, above the list, so a filter that hides documents is never out of sight. */
export function FilterChips({ filters, authors, onRemove, onClear, onEmpty }: FilterChipsProps) {
  const buttons = useRef(new Map<FilterKind, HTMLButtonElement>())
  if (activeCount(filters) === 0) return null
  const chips: { kind: FilterKind; name: string; text: string }[] = []
  if (filters.edited || filters.from || filters.to) chips.push({ kind: 'edited', name: t('filters.editDate'), text: dateText(filters) })
  if (filters.type.length) chips.push({ kind: 'type', name: t('filters.type'), text: formatList(filters.type.map(typeLabel)) })
  if (filters.author.length) chips.push({ kind: 'author', name: t('filters.author'), text: formatList(filters.author.map((u) => authors?.get(u) ?? u)) })
  if (filters.class.length) chips.push({ kind: 'class', name: t('filters.class'), text: formatList(filters.class) })
  if (filters.client.length) chips.push({ kind: 'client', name: t('filters.client'), text: formatList(filters.client) })
  if (filters.matter.length) chips.push({ kind: 'matter', name: t('filters.matter'), text: formatList(filters.matter) })

  // The chip dropped is gone from the page, taking focus with it: it goes to the next chip's ×, else the previous
  // one's, else (with none left) to the funnel (spec section 5).
  function drop(index: number) {
    const heir = chips[index + 1] ?? chips[index - 1]
    if (heir) buttons.current.get(heir.kind)?.focus()
    else onEmpty()
    onRemove(chips[index]!.kind)
  }

  return (
    <div role="group" aria-label={t('filters.inUse')} className={styles.chips}>
      {chips.map(({ kind, name, text }, index) => (
        <span key={kind} className={styles.chip}>
          <span>{t('filters.chip', { name, text })}</span>
          <Button
            ref={(button) => {
              if (button) buttons.current.set(kind, button)
              else buttons.current.delete(kind)
            }}
            variant="quiet"
            aria-label={t('filters.remove', { name })}
            onPress={() => drop(index)}
          >
            <Icon name="close" size={14} />
          </Button>
        </span>
      ))}
      <Button
        variant="quiet"
        onPress={() => {
          onEmpty()
          onClear()
        }}
      >
        <span className={styles.clear}>{t('filters.clearAll')}</span>
      </Button>
    </div>
  )
}
