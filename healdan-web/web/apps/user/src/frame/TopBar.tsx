// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Icon, Menu, MenuItem, t, type IconName } from '@healdan/ui'
import { isRecentKind, recentKinds, type RecentKind } from '../views/RecentKind'
import { HelpMenu } from './HelpMenu'
import { Mark } from './Mark'
import { PersonMenu } from './PersonMenu'
import styles from './TopBar.module.css'

const scopeIcons: Record<RecentKind, IconName> = {
  documents: 'document',
  emails: 'email',
  folders: 'folder',
  clients: 'building',
}

// The Recent entry that is open, or the scope in a search's address, sets the scope the next search runs in.
function useRouteScope(): RecentKind | undefined {
  const { kind } = useParams({ strict: false })
  const { scope } = useSearch({ strict: false })
  if (isRecentKind(kind)) return kind
  return isRecentKind(scope) ? scope : undefined
}

export function TopBar() {
  const routeScope = useRouteScope()
  const [scope, setScope] = useState<RecentKind>(routeScope ?? 'documents')
  const [seen, setSeen] = useState(routeScope)
  // The route moved to another scope: follow it, but let a choice in the picker stand until then.
  if (routeScope !== seen) {
    setSeen(routeScope)
    if (routeScope) setScope(routeScope)
  }
  const [query, setQuery] = useState('')
  const box = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target
      const typing =
        target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
      if (event.key !== '/' || typing || event.ctrlKey || event.metaKey || event.altKey) return
      event.preventDefault()
      box.current?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  function search(event: FormEvent) {
    event.preventDefault()
    // A blank search is refused by the server, so the box just stays.
    if (query.trim() === '') return
    void navigate({ to: '/search', search: { scope, q: query } })
  }

  const scopeName = t(`recent.${scope}`)
  return (
    <header className={styles.bar}>
      <Link className={styles.brand} to="/">
        <Mark />
        <span>{t('app.name')}</span>
      </Link>
      <form className={styles.search} role="search" onSubmit={search}>
        <Menu
          label={
            <span className={styles.scope}>
              <Icon name={scopeIcons[scope]} />
              {scopeName}
              <Icon name="chevronDown" size={13} />
            </span>
          }
          aria-label={`${t('search.scope')}: ${scopeName}`}
        >
          {recentKinds.map((kind) => (
            <MenuItem key={kind} id={kind} onAction={() => setScope(kind)}>
              {t(`recent.${kind}`)}
            </MenuItem>
          ))}
        </Menu>
        <input
          ref={box}
          className={styles.box}
          type="search"
          aria-label={t('search.label')}
          placeholder={t('search.placeholder')}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </form>
      <div className={styles.right}>
        <HelpMenu />
        <PersonMenu />
      </div>
    </header>
  )
}
