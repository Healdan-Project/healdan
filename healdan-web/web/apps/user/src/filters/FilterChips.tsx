// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, formatDay, Icon, t } from '@healdan/ui'
import { typeLabel } from './FiltersPanel'
import { activeCount, type FilterKind, type Filters } from './useFilters'
import styles from './filters.module.css'

export interface FilterChipsProps {
  filters: Filters
  /** Authors' names by username, as far as known; a username shows as itself otherwise */
  authors?: ReadonlyMap<string, string>
  onRemove: (kind: FilterKind) => void
  onClear: () => void
}

function dateText(filters: Filters): string {
  const show = formatDay
  if (filters.from && filters.to) return t('filters.date.range', { from: show(filters.from), to: show(filters.to) })
  if (filters.from) return t('filters.date.since', { from: show(filters.from) })
  if (filters.to) return t('filters.date.until', { to: show(filters.to) })
  return filters.edited ? t(`filters.date.${filters.edited}`) : ''
}

/** A chip for each filter in use, above the list, so a filter that hides documents is never out of sight. */
export function FilterChips({ filters, authors, onRemove, onClear }: FilterChipsProps) {
  if (activeCount(filters) === 0) return null
  const chips: { kind: FilterKind; name: string; text: string }[] = []
  if (filters.edited || filters.from || filters.to) chips.push({ kind: 'edited', name: t('filters.editDate'), text: dateText(filters) })
  if (filters.type.length) chips.push({ kind: 'type', name: t('filters.type'), text: filters.type.map(typeLabel).join(', ') })
  if (filters.author.length) chips.push({ kind: 'author', name: t('filters.author'), text: filters.author.map((u) => authors?.get(u) ?? u).join(', ') })
  if (filters.class.length) chips.push({ kind: 'class', name: t('filters.class'), text: filters.class.join(', ') })
  if (filters.client.length) chips.push({ kind: 'client', name: t('filters.client'), text: filters.client.join(', ') })
  if (filters.matter.length) chips.push({ kind: 'matter', name: t('filters.matter'), text: filters.matter.join(', ') })
  return (
    <div role="group" aria-label={t('filters.inUse')} className={styles.chips}>
      {chips.map(({ kind, name, text }) => (
        <span key={kind} className={styles.chip}>
          <span>
            {name}: {text}
          </span>
          <Button variant="quiet" aria-label={t('filters.remove', { name })} onPress={() => onRemove(kind)}>
            <Icon name="close" size={14} />
          </Button>
        </span>
      ))}
      <Button variant="quiet" onPress={onClear}>
        <span className={styles.clear}>{t('filters.clearAll')}</span>
      </Button>
    </div>
  )
}
