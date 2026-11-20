// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useMemo, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { goTo, useMessageFor } from '@healdan/shell'
import { ComboBox, Icon, type ComboBoxSection } from '@healdan/ui'
import { useAdminQuery } from '../queries'
import { t } from '../strings'
import { CLASSIC } from './LeftPane'
import styles from './FindBox.module.css'

type A = adminComponents['schemas']

/** The find waits this long for the typing to pause, and sends at most FIND_MAX characters, as the User app's search does */
export const FIND_DELAY_MS = 150
export const FIND_MAX = 100

export type Found = { kind: 'client'; client: A['FoundClient'] } | { kind: 'matter'; matter: A['FoundMatter'] } | { kind: 'person'; person: A['FoundPerson'] }

/**
 * Where a find opens what it found, by its kind. Clients, Matters and Users have no screen here until Phases 2 and 4,
 * so each opens the Classic Interface, where they're managed now. A matter doesn't open in the User app: the find gives
 * the matter's id, and the User app's addresses need its folder's.
 */
const DESTINATIONS: { [K in Found['kind']]: (found: Extract<Found, { kind: K }>) => string } = {
  client: () => CLASSIC,
  matter: () => CLASSIC,
  person: () => CLASSIC,
}

export function destinationOf(found: Found): string {
  return (DESTINATIONS[found.kind] as (one: Found) => string)(found)
}

/** What the text asks the server to find, or nothing: two characters, or a number of any length, as a client's is */
function findable(text: string): string | undefined {
  const q = text.trim().slice(0, FIND_MAX).trim()
  return q.length >= 2 || /^\d+$/.test(q) ? q : undefined
}

function useDebounced<V>(value: V, delay: number): V {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return settled
}

/** Each kind's options under its heading, by an id that says which it is: c1, m11, p7 */
function sectionsOf(answer: A['Found']): { sections: ComboBoxSection[]; byId: Map<string, Found> } {
  const byId = new Map<string, Found>()
  const sections: ComboBoxSection[] = []
  const clients = answer.clients.slice(0, 5)
  const matters = answer.matters.slice(0, 5)
  const people = answer.people.slice(0, 5)
  if (clients.length > 0) {
    sections.push({
      id: 'clients',
      title: t('admin.find.clients'),
      options: clients.map((client) => {
        byId.set(`c${client.id}`, { kind: 'client', client })
        const label = t('admin.find.client', { number: client.number, name: client.name })
        return { id: `c${client.id}`, textValue: label, icon: <Icon name="building" kind="client" />, label }
      }),
    })
  }
  if (matters.length > 0) {
    sections.push({
      id: 'matters',
      title: t('admin.find.matters'),
      options: matters.map((matter) => {
        byId.set(`m${matter.id}`, { kind: 'matter', matter })
        const label = t('matter.label', { number: matter.number, name: matter.name })
        return { id: `m${matter.id}`, textValue: label, icon: <Icon name="briefcase" kind="matter" />, label, meta: matter.client.name }
      }),
    })
  }
  if (people.length > 0) {
    sections.push({
      id: 'people',
      title: t('admin.find.people'),
      options: people.map((person) => {
        byId.set(`p${person.id}`, { kind: 'person', person })
        const meta = person.enabled ? person.username : `${person.username} · ${t('admin.find.disabled')}`
        return { id: `p${person.id}`, textValue: person.name, icon: <Icon name="user" />, label: person.name, meta }
      }),
    })
  }
  return { sections, byId }
}

const NOTHING: A['Found'] = { clients: [], matters: [], people: [] }

/**
 * Find a client, matter or person (spec 7.1): React Aria's combo box in the top bar, as the User app's search box is,
 * since its list opens over the page as the person types. The server's answer comes once the typing pauses, at most five
 * of each kind; the arrow keys and Enter, or a click, open one where it is managed, and Enter with none picked opens the
 * first. A screen reader hears when nothing was found, or the find failed.
 */
export function FindBox() {
  const messageFor = useMessageFor()
  const [text, setText] = useState('')
  const wanted = findable(text)
  const settled = useDebounced(wanted, FIND_DELAY_MS)
  const form = useRef<HTMLFormElement>(null)
  const find = useAdminQuery('/v1/admin/find', {
    query: { q: settled ?? '' },
    enabled: settled !== undefined,
    keepPrevious: true,
    // A keystroke back and forth needn't ask twice
    staleTime: 10_000,
  })
  // Nothing to show once the text is too short, whatever the last answer was
  const answer = wanted === undefined ? undefined : find.data
  const { sections, byId } = useMemo(() => sectionsOf(answer ?? NOTHING), [answer])
  // The answer to what is typed now, not one kept from older words while the new one comes
  const current = wanted !== undefined && wanted === settled && !find.isPlaceholderData
  const note = wanted === undefined ? '' : find.isError ? messageFor(find.error) : current && find.isSuccess && byId.size === 0 ? t('admin.find.none') : ''

  function open(id: string) {
    const found = byId.get(id)
    if (!found) return
    setText('')
    goTo(destinationOf(found))
  }

  return (
    <form ref={form} className={styles.find} role="search" onSubmit={(event) => event.preventDefault()}>
      <Icon name="search" className={styles.icon} />
      <ComboBox
        aria-label={t('admin.find.label')}
        placeholder={t('admin.find.label')}
        className={styles.combo}
        inputClassName={styles.box}
        anchorRef={form}
        value={text}
        onValueChange={setText}
        sections={sections}
        onSelect={open}
        onSubmit={() => {
          const first = sections[0]?.options[0]
          if (current && first) open(first.id)
        }}
      />
      {/* There before it has words, so a screen reader says them as they come */}
      <span role="status" className={styles.note}>
        {note}
      </span>
    </form>
  )
}
