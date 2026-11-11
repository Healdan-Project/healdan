// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueries, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { useId, useState, type ReactNode } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, Icon, Menu, MenuItem, Tree, t, toast, type IconName, type TreeNode } from '@healdan/ui'
import { carriesRows, placeOf, takesDrag, type Place } from '../filing/dropTargets'
import { useFiling } from '../filing/FilingProvider'
import { useOrganising } from '../organising/OrganisingProvider'
import { fetchSubfolders, idFrom, keys, useFavorites, useFolder, useMyMatters, useRecentMatters } from '../queries'
import { useApi } from '../services'
import { recentKinds } from '../search/scopes'
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

function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className={styles.section}>
      <div className={styles.headingRow}>
        <h2 id={id} className={styles.heading}>
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  )
}

// What a section shows when its list isn't ready, is empty or failed; undefined when the list is there to show. While
// it's on its way the section says so, rather than draw an empty list or tree.
function stateOf(query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }, empty: boolean): ReactNode {
  if (query.isError) return <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
  if (query.isPending) return <p className={styles.none}>{t('common.loading')}</p>
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

/** The open folder, when the address is one's, so the pane can find its matter */
function useCurrentFolder() {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  return useFolder(idFrom(currentFolder(pathname))).data
}

/** A change to My Matters, then the list asked again; a refusal is said in a toast. */
function useMyMattersChange() {
  const api = useApi()
  const queryClient = useQueryClient()
  return async (method: 'PUT' | 'DELETE', folderId: number) => {
    try {
      await api.send(method, apiPath`/v1/me/matters/${folderId}`)
      await queryClient.invalidateQueries({ queryKey: keys.myMatters })
    } catch (error) {
      toast(messageFor(error))
    }
  }
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
  const change = useMyMattersChange()
  const filing = useFiling()
  const organising = useOrganising()
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const matters = mine.data?.items ?? []

  // The open folder's matter, from its path: the workspace's folder is what My Matters holds.
  const here = useCurrentFolder()
  const path = here?.path ?? []
  const top = path.findIndex((step) => step.kind === 'matter')
  const workspace = top >= 0 ? path[top]!.id : undefined
  const kept = mine.isSuccess && matters.some((matter) => matter.folderId === workspace)

  // The tree opens to the open folder: each folder above it, from its matter down, is expanded once it's known.
  const trail = kept ? path.slice(top, -1).map((step) => String(step.id)) : []
  const [opened, setOpened] = useState('')
  if (trail.join('/') !== opened) {
    setOpened(trail.join('/'))
    if (trail.some((id) => !expanded.has(id))) setExpanded(new Set([...expanded, ...trail]))
  }

  // A node's subfolders are asked for when it is first expanded; the answer stays for the cache's 30 seconds.
  const open = [...expanded]
  const loaded = useQueries({
    queries: open.map((id) => ({
      queryKey: keys.subfolders(Number(id)),
      queryFn: () => fetchSubfolders(api, id),
    })),
  })
  const answers = new Map(open.map((id, i) => [id, loaded[i]]))

  function childrenOf(id: string): TreeNode[] | undefined {
    const answer = answers.get(id)
    if (answer?.data) return answer.data.map(folderNode)
    if (answer?.isError) {
      const text = messageFor(answer.error)
      return [{ id: `${id}:error`, textValue: text, label: <span className={styles.client}>{text}</span> }]
    }
    return undefined
  }

  // What each node stands for as a place to drop files: a folder's kind says a search folder refuses, a matter its top level
  const places = new Map<string, Place>()

  function folderNode(folder: S['FolderView']): TreeNode {
    const id = String(folder.id)
    places.set(id, placeOf(folder))
    return {
      id,
      textValue: folder.name,
      label: folderLabel(folder),
      expandable: folder.kind === 'folder',
      children: expanded.has(id) ? childrenOf(id) : undefined,
    }
  }

  const nodes: TreeNode[] = matters.map((matter) => {
    const id = String(matter.folderId)
    places.set(id, { id: matter.folderId, kind: 'matter', name: matter.display })
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
      menu: (
        <Menu variant="quiet" label={<Icon name="dots" />} aria-label={t('tree.more', { name: matter.display })}>
          <MenuItem id="remove" onAction={() => void change('DELETE', matter.folderId)}>
            {t('myMatters.remove')}
          </MenuItem>
        </Menu>
      ),
    }
  })

  // + adds the open folder's matter, when it's in one that isn't in My Matters yet.
  const add =
    workspace !== undefined && mine.isSuccess && !kept ? (
      <Button variant="quiet" aria-label={t('myMatters.add')} onPress={() => void change('PUT', workspace)}>
        <Icon name="plus" size={14} />
      </Button>
    ) : undefined

  return (
    <Section title={t('myMatters.title')} action={add}>
      {stateOf(mine, mine.isSuccess && matters.length === 0) ?? (
        <Tree
          aria-label={t('myMatters.tree')}
          nodes={nodes}
          expanded={expanded}
          onExpandedChange={setExpanded}
          current={currentFolder(pathname)}
          // Files are filed into the folder, and the list's own rows are moved there; both light the folder up
          acceptsDrop={(id, types) => places.has(id) && (takesDrag(types) || carriesRows(types))}
          onDrop={(id, event) => {
            const place = places.get(id)
            if (!place) return
            if (carriesRows(Array.from(event.dataTransfer.types))) organising.dropRows(event, place)
            else filing.drop(event, place)
          }}
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
