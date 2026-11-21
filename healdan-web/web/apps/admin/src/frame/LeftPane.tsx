// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link, useRouterState, type RegisteredRouter, type RouteIds } from '@tanstack/react-router'
import { useId, type ReactNode } from 'react'
import { Icon, VisuallyHidden, type IconName } from '@healdan/ui'
import { ADMIN_BASE, routes } from '../addresses'
import { useDashboardCounts, type AttentionCode } from '../queries'
import { plural, t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'
import styles from './LeftPane.module.css'

/** The address the Classic Interface opens at, which the pane's foot links to (spec 6.4) */
export const CLASSIC = '/frontend.jsp'

/**
 * A screen's address, as the router knows it, so only a shipped screen has one. Every page is a child of the root, so
 * its route's id is its path.
 */
export type NavRoute = Exclude<RouteIds<RegisteredRouter['routeTree']>, '__root__'>

export interface NavItem {
  label: AdminStringKey
  route: NavRoute
  icon: IconName
  /** The attention codes whose counts the entry shows, added up, where something needs attention there */
  count?: readonly AttentionCode[]
}

export interface NavGroup {
  /** The group's heading; null for what stands above the groups, as the Dashboard does */
  group: AdminStringKey | null
  items: readonly NavItem[]
}

/**
 * The left pane's screens, in groups, in the order spec 7.1 lists them. A screen joins only once its phase has shipped,
 * and a group with none isn't drawn. The Classic Interface is at the pane's foot, apart from them.
 */
export const NAV: readonly NavGroup[] = [
  { group: null, items: [{ label: 'admin.dashboard.title', route: routes.dashboard, icon: 'dashboard' }] },
  {
    group: 'admin.nav.firm',
    items: [
      { label: 'admin.nav.clients', route: routes.clients, icon: 'building' },
      { label: 'admin.nav.matters', route: routes.matters, icon: 'briefcase' },
      { label: 'admin.nav.templates', route: routes.templates, icon: 'template' },
    ],
  },
  // Users and Groups, in Phase 4
  { group: 'admin.nav.people', items: [] },
  // Stores and Backends, in Phase 3: a backend that can't be reached, or is failing, needs attention
  {
    group: 'admin.nav.storage',
    items: [
      { label: 'admin.nav.stores', route: routes.stores, icon: 'archive' },
      { label: 'admin.nav.backends', route: routes.backends, icon: 'database', count: ['backend-unreachable'] },
    ],
  },
  // Search Index, in Phase 6
  { group: 'admin.nav.search', items: [] },
  // Errors, Server Log, Audit Trail and Background Jobs, in Phase 5
  { group: 'admin.nav.monitoring', items: [] },
]

/** The label of the screen at a path of the app (with ADMIN_BASE, no query), or undefined when no shipped screen is there */
export function screenAt(href: string, nav: readonly NavGroup[] = NAV): string | undefined {
  if (href !== ADMIN_BASE && !href.startsWith(`${ADMIN_BASE}/`)) return undefined
  const path = href.slice(ADMIN_BASE.length) || '/'
  const item = nav.flatMap((group) => group.items).find((one) => one.route === path)
  return item && t(item.label)
}

/** How many need attention at a screen: its codes' counts in the Dashboard's list, added up */
export function countOf(item: NavItem, attention: readonly { code: AttentionCode; count: number }[]): number {
  return attention.filter((one) => item.count?.includes(one.code)).reduce((sum, one) => sum + one.count, 0)
}

// The open screen: its own address, and a page under a list's (a client's under Clients); the Dashboard only at its own
function isOpen(route: string, pathname: string): boolean {
  return route === '/' ? pathname === '/' : pathname === route || pathname.startsWith(`${route}/`)
}

function Entry({ item, pathname, count }: { item: NavItem; pathname: string; count: number }) {
  const label = t(item.label)
  return (
    <Link to={item.route} className={styles.entry} aria-current={isOpen(item.route, pathname) ? 'page' : undefined}>
      <Icon name={item.icon} />
      {count >= 1 ? (
        <>
          {/* With a count the name is one string, "Errors, 14 need attention", read in place of the words and number */}
          <span className={styles.text} aria-hidden="true">
            {label}
          </span>
          <VisuallyHidden>{plural('admin.nav.count', count, { label })}</VisuallyHidden>
          <span className={styles.badge} aria-hidden="true" data-count>
            {count}
          </span>
        </>
      ) : (
        <span className={styles.text}>{label}</span>
      )}
    </Link>
  )
}

function Group({ group, children }: { group: AdminStringKey; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className={styles.section}>
      <h2 id={id} className={styles.heading}>
        {t(group)}
      </h2>
      {children}
    </section>
  )
}

/** The left pane (spec 7.1): each shipped screen one click away, with a count where something needs attention. */
export function LeftPane({ nav = NAV }: { nav?: readonly NavGroup[] }) {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const shown = nav.filter((group) => group.items.length > 0)
  // The counts come from the Dashboard's list, asked for only when a screen shown has one, and on no timer of the pane's
  const counted = shown.some((group) => group.items.some((item) => item.count && item.count.length > 0))
  const attention = useDashboardCounts(counted).data?.attention ?? []
  const list = (items: readonly NavItem[]) => (
    <ul className={styles.list}>
      {items.map((item) => (
        <li key={item.route + item.label}>
          <Entry item={item} pathname={pathname} count={countOf(item, attention)} />
        </li>
      ))}
    </ul>
  )
  return (
    <nav className={styles.pane} aria-label={t('pane.label')}>
      <div className={styles.sections}>
        {shown.map((group, index) =>
          group.group === null ? <div key={index}>{list(group.items)}</div> : <Group key={group.group} group={group.group}>{list(group.items)}</Group>,
        )}
      </div>
      {/* What isn't here yet, apart from the screens (spec 6.4) */}
      <div className={styles.foot}>
        <a href={CLASSIC} className={styles.entry}>
          <Icon name="external" />
          <span className={styles.text}>{t('person.classic')}</span>
        </a>
      </div>
    </nav>
  )
}
