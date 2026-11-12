// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Ref } from 'react'
import { Button, Icon, t } from '@healdan/ui'
import styles from './filters.module.css'

export interface FiltersButtonProps {
  /** Filters are showing in the right region */
  open: boolean
  /** How many filters are in use */
  active: number
  onPress: () => void
  ref?: Ref<HTMLButtonElement>
}

/** The funnel at the toolbar's right end. Its badge counts the filters in use, so they show even with the panel closed. */
export function FiltersButton({ open, active, onPress, ref }: FiltersButtonProps) {
  return (
    <Button ref={ref} aria-pressed={open} aria-label={active > 0 ? t('filters.buttonInUse', { n: active }) : t('filters.button')} onPress={onPress}>
      <span className={styles.funnel}>
        <Icon name="filter" />
        {t('filters.button')}
        {/* The name reads "Filters 2 in Use", which holds the words and the number the eye sees (WCAG 2.5.3) */}
        {active > 0 && (
          <>
            {' '}
            <span className={styles.badge}>{active}</span>
          </>
        )}
      </span>
    </Button>
  )
}
