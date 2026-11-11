// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate, useParams, useRouterState, useSearch } from '@tanstack/react-router'
import { useEffect, useMemo, useRef, useState } from 'react'
import { apiPath, type components } from '@healdan/api'
import { ComboBox, Icon, Menu, MenuItem, t, toast } from '@healdan/ui'
import { messageFor } from '../frame/errors'
import { useApi } from '../services'
import { isRecentKind, isSearchScope, scopeIcons, searchScopes, type SearchScope } from './scopes'
import { SEARCH_ID, suggestable, suggestionSections, useSuggestions } from './Suggestions'
import styles from './SearchBox.module.css'

type S = components['schemas']

// `#10388`, `#10388.2`, `#10388v2`, `#10388.2.1`, a capital V as well, and the doc ID as titles show it, `#10388 v2.1`: what the server's lookup
// resolves, with the spacing a copied doc ID has. The address takes no spaces, so the reference is made without them.
const docReference = /^#\s*(\d+)(?:\s*[vV]\s*(\d+(?:\.\d+)?)|((?:\.\d+){1,2}))?$/

/** The address's reference for a typed `#`, or undefined when it isn't a doc #. */
function referenceOf(text: string): string | undefined {
  const parts = docReference.exec(text)
  if (!parts) return undefined
  return parts[1] + (parts[2] ? `v${parts[2]}` : (parts[3] ?? ''))
}
// A matter's number, `1001.1`, which the Matters scope opens
const matterNumber = /^\d+\.\d+$/

/**
 * The scope the address sets: Recent's entries and the start page are Documents, and a search's address names its own.
 * Elsewhere, the scope is whatever the person last chose.
 */
function useRouteScope(): SearchScope | undefined {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const { kind } = useParams({ strict: false })
  const { scope } = useSearch({ strict: false })
  if (pathname === '/') return 'documents'
  if (isRecentKind(kind)) return kind
  return pathname === '/search' ? (isSearchScope(scope) ? scope : 'documents') : undefined
}

/** The words of the search the address holds, so the box shows them on a reload and on Back. */
function useRouteWords(): string | undefined {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const { q } = useSearch({ strict: false })
  return pathname === '/search' ? q : undefined
}

/** A text field, or anything else the person types in: a `/` there is a character. */
function editable(element: HTMLElement): boolean {
  // isContentEditable is what browsers answer; jsdom has no such property, so the attribute is read as well
  return element.isContentEditable === true || ['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName) || element.closest('[contenteditable=""], [contenteditable="true"]') !== null
}

/**
 * Whether the page behind is out of reach: React Aria hides everything outside a modal dialog (or a menu or popover)
 * from assistive technology while it is open, so a box that is hidden belongs to a page the person can't use now.
 */
function hidden(box: HTMLElement | null): boolean {
  return box?.closest('[aria-hidden="true"], [inert]') != null
}

/** The scope picker joined to the search box, with its suggestions (spec section 6.3); `/` jumps to the box. */
export function SearchBox() {
  const api = useApi()
  const navigate = useNavigate()
  const routeScope = useRouteScope()
  const routeWords = useRouteWords()
  const [scope, setScope] = useState<SearchScope>(routeScope ?? 'documents')
  const [seenScope, setSeenScope] = useState(routeScope)
  // The address moved to another scope: follow it, but let a choice in the picker stand until then.
  if (routeScope !== seenScope) {
    setSeenScope(routeScope)
    if (routeScope) setScope(routeScope)
  }
  const [words, setWords] = useState(routeWords ?? '')
  const [seenWords, setSeenWords] = useState(routeWords)
  if (routeWords !== seenWords) {
    setSeenWords(routeWords)
    if (routeWords !== undefined) setWords(routeWords)
  }
  const box = useRef<HTMLInputElement>(null)
  const form = useRef<HTMLFormElement>(null)

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const typing = event.target instanceof HTMLElement && editable(event.target)
      if (event.key !== '/' || typing || event.ctrlKey || event.metaKey || event.altKey || hidden(box.current)) return
      event.preventDefault()
      box.current?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  const suggestions = useSuggestions(words)
  const asked = suggestable(words)
  const sections = useMemo(() => (asked === undefined ? [] : suggestionSections(suggestions, scope, asked)), [asked, suggestions, scope])

  async function openMatter(id: number) {
    try {
      const matter = await api.get<S['MatterView']>(apiPath`/v1/matters/${id}`)
      await navigate({ to: '/folders/$id', params: { id: String(matter.folderId) } })
    } catch (error) {
      toast(messageFor(error))
    }
  }

  function pick(id: string) {
    if (id === SEARCH_ID) {
      void submit(words)
      return
    }
    setWords('')
    if (id.startsWith('m')) void openMatter(Number(id.slice(1)))
    else void navigate({ to: '/doc/$ref', params: { ref: id.slice(1) } })
  }

  // The words a person typed. A # goes straight to that document; a matter number in the Matters scope opens the matter;
  // anything else is a search.
  async function submit(typed: string) {
    const text = typed.trim()
    // A blank search is refused by the server, so the box just stays.
    if (text === '') return
    const reference = referenceOf(text)
    if (reference) {
      void navigate({ to: '/doc/$ref', params: { ref: reference } })
      return
    }
    if (scope === 'matters' && matterNumber.test(text)) {
      try {
        const found = await api.get<{ items: S['MatterView'][] }>('/v1/search', { scope, q: text, limit: 10 })
        const matter = found.items.find((one) => `${one.client.number}.${one.number}` === text)
        if (matter) {
          await navigate({ to: '/folders/$id', params: { id: String(matter.folderId) } })
          return
        }
      } catch {
        // The results page asks again, and says what went wrong
      }
    }
    void navigate({ to: '/search', search: { scope, q: text } })
  }

  const scopeName = t(`scope.${scope}`)
  return (
    <form ref={form} className={styles.search} role="search" onSubmit={(event) => event.preventDefault()}>
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
        {searchScopes.map((one) => (
          <MenuItem key={one} id={one} onAction={() => setScope(one)}>
            {t(`scope.${one}`)}
          </MenuItem>
        ))}
      </Menu>
      <ComboBox
        aria-label={t('search.label')}
        placeholder={t('search.placeholder')}
        className={styles.combo}
        inputClassName={styles.box}
        inputRef={box}
        anchorRef={form}
        value={words}
        onValueChange={setWords}
        sections={sections}
        onSelect={pick}
        onSubmit={(text) => void submit(text)}
      />
    </form>
  )
}
