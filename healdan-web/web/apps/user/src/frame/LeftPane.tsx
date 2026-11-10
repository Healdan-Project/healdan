// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueries } from '@tanstack/react-query'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { useId, useState, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { Icon, Tree, t, type IconName, type TreeNode } from '@healdan/ui'
import { keys, useFavorites, useMyMatters, useRecentMatters } from '../queries'
import type { PageOf } from '../pages'
import { useApi } from '../services'
import { recentKinds } from '../views/RecentKind'
import { ErrorPanel } from './ErrorPanel'
import { messageFor } from './errors'
import styles from './LeftPane.module.css'

type S = components['schemas']

const recentIcons: Record<(typeof recentKinds)[number], IconName> = {
  documents: 'document',
  emails: 'email',
  folders: 'folder',
  clients: 'building',
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className={styles.section}>
      <h2 id={id} className={styles.heading}>
        {title}
      </h2>
      {children}
    </section>
  )
}

// What a section shows when its list isn't ready, is empty or failed; undefined when the list is there to show.
function stateOf(query: { isError: boolean; error: unknown; refetch: () => unknown }, empty: boolean): ReactNode {
  if (query.isError) return <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
  return empty ? <p className={styles.none}>{t('common.none')}</p> : undefined
}

// "1001.1 – Charter Dispute", then the client smaller and muted (M1's format).
function MatterLabel({ matter }: { matter: S['MatterView'] }) {
  return (
    <>
      {matter.display} <span className={styles.client}>{matter.client.name}</span>
    </>
  )
}

function currentFolder(pathname: string): string | undefined {
  return /^\/folders\/([^/]+)/.exec(pathname)?.[1]
}

function RecentSection() {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  return (
    <Section title={t('recent.title')}>
      <ul className={styles.list}>
        {recentKinds.map((kind) => {
          const here = pathname === `/recent/${kind}` || (kind === 'documents' && pathname === '/')
          return (
            <li key={kind}>
              <Link to="/recent/$kind" params={{ kind }} className={styles.entry} aria-current={here ? 'page' : undefined}>
                <Icon name={recentIcons[kind]} />
                {t(`recent.${kind}`)}
              </Link>
            </li>
          )
        })}
      </ul>
    </Section>
  )
}

function RecentMattersSection() {
  const recent = useRecentMatters()
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const items = recent.data?.items ?? []
  return (
    <Section title={t('recentMatters.title')}>
      {stateOf(recent, recent.isSuccess && items.length === 0) ?? (
        <ul className={styles.list}>
          {items.map((matter) => (
            <li key={matter.id}>
              <Link
                to="/folders/$id"
                params={{ id: String(matter.folderId) }}
                className={styles.entry}
                aria-current={currentFolder(pathname) === String(matter.folderId) ? 'page' : undefined}
              >
                <Icon name="briefcase" />
                <span className={styles.text}>
                  <MatterLabel matter={matter} />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

function folderLabel(folder: S['FolderView']): ReactNode {
  const icon: IconName = folder.kind === 'searchFolder' ? 'folderSearch' : 'folder'
  return (
    <>
      <Icon name={icon} />
      <span className={styles.text}>{folder.name}</span>
    </>
  )
}

function MyMattersSection() {
  const api = useApi()
  const navigate = useNavigate()
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const mine = useMyMatters()
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  // A node's subfolders are asked for when it is first expanded; the answer stays for the cache's 30 seconds.
  const open = [...expanded]
  const loaded = useQueries({
    queries: open.map((id) => ({
      queryKey: keys.subfolders(Number(id)),
      queryFn: () => api.get<PageOf<S['FolderView'] | S['DocumentView']>>(`/v1/folders/${encodeURIComponent(id)}/children`, { type: 'folder' }),
    })),
  })
  const answers = new Map(open.map((id, i) => [id, loaded[i]]))

  function childrenOf(id: string): TreeNode[] | undefined {
    const answer = answers.get(id)
    if (answer?.data) return answer.data.items.filter((item): item is S['FolderView'] => item.type === 'folder').map(folderNode)
    if (answer?.isError) {
      const text = messageFor(answer.error)
      return [{ id: `${id}:error`, textValue: text, label: <span className={styles.client}>{text}</span> }]
    }
    return undefined
  }

  function folderNode(folder: S['FolderView']): TreeNode {
    const id = String(folder.id)
    return {
      id,
      textValue: folder.name,
      label: folderLabel(folder),
      expandable: folder.kind === 'folder',
      children: expanded.has(id) ? childrenOf(id) : undefined,
    }
  }

  const matters = mine.data?.items ?? []
  const nodes: TreeNode[] = matters.map((matter) => {
    const id = String(matter.folderId)
    return {
      id,
      textValue: `${matter.display} ${matter.client.name}`,
      label: (
        <>
          <Icon name="briefcase" />
          <span className={styles.text}>
            <MatterLabel matter={matter} />
          </span>
        </>
      ),
      expandable: true,
      children: expanded.has(id) ? childrenOf(id) : undefined,
    }
  })

  return (
    <Section title={t('myMatters.title')}>
      {stateOf(mine, mine.isSuccess && matters.length === 0) ?? (
        <Tree
          aria-label={t('myMatters.tree')}
          nodes={nodes}
          expanded={expanded}
          onExpandedChange={setExpanded}
          current={currentFolder(pathname)}
          onAction={(id) => {
            // The ids of a failed node's message aren't folders.
            if (!id.includes(':')) void navigate({ to: '/folders/$id', params: { id } })
          }}
        />
      )}
    </Section>
  )
}

function FavoritesSection() {
  const favorites = useFavorites()
  const items = favorites.data?.items ?? []
  return (
    <Section title={t('favorites.mine')}>
      {stateOf(favorites, favorites.isSuccess && items.length === 0) ?? (
        <ul className={styles.list}>
          {items.map((favorite) => (
            <li key={favorite.id}>
              {favorite.item.type === 'document' ? (
                <Link to="/doc/$ref" params={{ ref: String(favorite.item.docNumber) }} className={styles.entry}>
                  <Icon name="star" />
                  <span className={styles.text}>{favorite.item.name}</span>
                </Link>
              ) : (
                <Link to="/folders/$id" params={{ id: String(favorite.item.id) }} className={styles.entry}>
                  <Icon name="star" />
                  <span className={styles.text}>{favorite.item.name}</span>
                </Link>
              )}
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

export function LeftPane() {
  return (
    <nav className={styles.pane} aria-label={t('pane.label')}>
      <RecentSection />
      <RecentMattersSection />
      <MyMattersSection />
      <FavoritesSection />
    </nav>
  )
}
