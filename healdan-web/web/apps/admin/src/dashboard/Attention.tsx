// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { UseQueryResult } from '@tanstack/react-query'
import { useId } from 'react'
import type { adminComponents } from '@healdan/api'
import { Icon, formatList } from '@healdan/ui'
import { pathOf } from '../addresses'
import { CLASSIC, screenAt } from '../frame/LeftPane'
import { ServerLink } from '../frame/ServerLink'
import type { AttentionCode, DashboardAnswer } from '../queries'
import { plural, t } from '../strings'
import { adminEnUS, adminPlurals, type AdminPluralKey, type AdminStringKey } from '../strings/en-US'
import { PartState } from './PartState'
import styles from './Dashboard.module.css'

type Item = adminComponents['schemas']['AttentionItem']

/** The row's sentence: how many, of what. A code this app doesn't know yet (a newer server's) says the server's detail only. */
function messageOf(item: Item): string | undefined {
  const key = `admin.attention.${item.code}`
  return Object.hasOwn(adminPlurals, key) ? plural(key as AdminPluralKey, item.count) : undefined
}

/**
 * What a row's link says: the screen it opens, the Classic Interface until that screen ships, or Open elsewhere. Its path
 * decides, so a query or a hash on it changes nothing.
 */
export function linkLabel(link: string): string {
  const path = pathOf(link)
  if (path === undefined) return t('admin.attention.open')
  if (path === CLASSIC) return t('person.classic')
  return screenAt(path) ?? t('admin.attention.open')
}

function labelOf(code: AttentionCode): string {
  const key = `admin.attention.label.${code}`
  return Object.hasOwn(adminEnUS, key) ? t(key as AdminStringKey) : code
}

function Row({ item }: { item: Item }) {
  const id = useId()
  const message = messageOf(item)
  return (
    <li className={styles.item}>
      <Icon name="alertTriangle" className={styles.itemIcon} />
      <span className={styles.itemText}>
        {message && (
          <span id={id} data-message>
            {message}
          </span>
        )}
        <span className={styles.secondary} data-detail>
          {item.detail}
        </span>
      </span>
      <ServerLink className={styles.go} href={item.link} aria-describedby={message ? id : undefined}>
        {linkLabel(item.link)}
        <Icon name="chevronRight" size={14} />
      </ServerLink>
    </li>
  )
}

/**
 * Needs Attention, first on the Dashboard (spec 8.1): what an administrator should act on, in the server's order, each
 * linking to where it's fixed. With nothing to report it says so in one line; but never while a source couldn't be
 * checked, which it names instead.
 */
export function Attention({ dashboard }: { dashboard: UseQueryResult<DashboardAnswer> }) {
  const id = useId()
  const items = dashboard.data?.attention ?? []
  const unchecked = dashboard.data?.unchecked ?? []
  return (
    <section aria-labelledby={id} className={styles.attention}>
      <h2 id={id} className={styles.attentionTitle}>
        <Icon name="alertTriangle" />
        {t('admin.attention.title')}
      </h2>
      <PartState query={dashboard}>
        {items.length > 0 && (
          <ul className={styles.items}>
            {items.map((item) => (
              <Row key={item.code} item={item} />
            ))}
          </ul>
        )}
        {unchecked.length > 0 && <p className={styles.attentionLine}>{t('admin.attention.unchecked', { what: formatList(unchecked.map(labelOf)) })}</p>}
        {items.length === 0 && unchecked.length === 0 && <p className={styles.attentionLine}>{t('admin.attention.none')}</p>}
      </PartState>
    </section>
  )
}
