// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueries } from '@tanstack/react-query'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { Button, Icon, ItemContextMenu, Menu, MenuItem, Tree, t, type ItemMenu, type TreeNode } from '@healdan/ui'
import { favoriteTooltip } from '../actions/favoriteName'
import { useMyMattersChange } from '../actions/myMatters'
import { carriesRows, placeOf, takesDrag, type Place } from '../filing/dropTargets'
import { useFiling } from '../filing/FilingProvider'
import { FavoriteMenuItems, folderMenu, MatterMenuItems } from '../menus/ContextMenus'
import { useOrganising } from '../organising/OrganisingProvider'
import { fetchSubfolders, idFrom, keys, useFavorites, useFolder, useMyMatters, useRecentMatters } from '../queries'
import { useApi } from '../services'
import { fileIcon, folderIcon, matterIcon } from '../kindIcons'
import { recentKinds, scopeIcons } from '../search/scopes'
import { ErrorPanel } from './ErrorPanel'
import { messageFor } from './errors'
import { FavoriteNameField } from './FavoriteNameField'
import styles from './LeftPane.module.css'

type S = components['schemas']

/**
 * A section of the pane. With a `menu`, its links have a right-click menu, by the key each carries as `data-menu-key`
 * (changed after M6: the owner's dogfooding punch list, 2026-11-14).
 */
function Section({ title, action, menu, children }: { title: string; action?: ReactNode; menu?: ItemMenu; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className={styles.section}>
      <div className={styles.headingRow}>
        <h2 id={id} className={styles.heading}>
          {title}
        </h2>
        {action}
      </div>
      {menu ? (
        <ItemContextMenu attribute="data-menu-key" menu={menu}>
          {children}
        </ItemContextMenu>
      ) : (
        children
      )}
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
                {/* Each entry is also a scope, so it has the scope's icon */}
                <Icon {...scopeIcons[kind]} />
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
  // Each link carries its matter's folder as its key
  const matterAt = (key: string) => items.find((matter) => String(matter.folderId) === key)
  const menu: ItemMenu = {
    label: (key) => t('menu.for', { name: matterAt(key)?.display ?? '' }),
    items: (key) => {
      const matter = matterAt(key)
      return matter && <MatterMenuItems folderId={matter.folderId} />
    },
  }
  return (
    <Section title={t('recentMatters.title')} menu={menu}>
      {stateOf(recent, recent.isSuccess && items.length === 0) ?? (
        <ul className={styles.list}>
          {items.map((matter) => (
            <li key={matter.id}>
              <Link
                to="/folders/$id"
                params={{ id: String(matter.folderId) }}
                data-menu-key={String(matter.folderId)}
                className={styles.entry}
                aria-current={currentFolder(pathname) === String(matter.folderId) ? 'page' : undefined}
              >
                <Icon {...matterIcon} />
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
  return (
    <>
      <Icon {...folderIcon(folder.kind)} />
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
  // And what each folder is, for its right-click menu
  const folders = new Map<string, S['FolderView']>()

  function folderNode(folder: S['FolderView']): TreeNode {
    const id = String(folder.id)
    places.set(id, placeOf(folder))
    folders.set(id, folder)
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
          <Icon {...matterIcon} />
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
          contextMenu={{
            label: (id) => t('menu.for', { name: matters.find((matter) => String(matter.folderId) === id)?.display ?? folders.get(id)?.name ?? '' }),
            // A matter has its own menu, as its ⋯ has, and a folder its row's in a list; a folder that failed to load has none
            items: (id) => {
              if (matters.some((matter) => String(matter.folderId) === id)) return <MatterMenuItems folderId={Number(id)} />
              const folder = folders.get(id)
              return folder && folderMenu(folder)
            },
          }}
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

/** A favorite's icon: its item's kind */
function favoriteIcon(favorite: S['FavoriteView']): ReactNode {
  const item = favorite.item
  return item.type === 'document' ? <Icon {...fileIcon(item.name)} /> : <Icon {...folderIcon(item.kind)} />
}

/**
 * My Favorites, by each favorite's own name, in the server's order (changed after M6: the owner's dogfooding punch list,
 * 2026-11-14). Its tooltip says what it really is and where, and F2 or its menu's Rename Favorite makes the name a field.
 */
function FavoritesSection() {
  const favorites = useFavorites()
  // The favorite whose name is a field now
  const [renaming, setRenaming] = useState<number>()
  const items = favorites.data?.items ?? []
  // Each link carries its bookmark's id as its key
  const favoriteAt = (key: string) => items.find((favorite) => String(favorite.id) === key)
  const menu: ItemMenu = {
    label: (key) => t('menu.for', { name: favoriteAt(key)?.name ?? '' }),
    items: (key) => {
      const favorite = favoriteAt(key)
      return favorite && <FavoriteMenuItems favorite={favorite} onRename={() => setRenaming(favorite.id)} />
    },
  }
  const list = useRef<HTMLUListElement>(null)
  const entry = (favorite: S['FavoriteView']) => ({
    className: styles.entry,
    title: favoriteTooltip(favorite),
    'data-menu-key': String(favorite.id),
    onKeyDown: (event: KeyboardEvent) => {
      if (event.key !== 'F2' || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
      event.preventDefault()
      setRenaming(favorite.id)
    },
  })
  function renamed(id: number, focusLink: boolean) {
    // Only this favorite's field: another may have opened since
    setRenaming((current) => (current === id ? undefined : current))
    // The link is back once this has rendered, in its new place if the name moved it
    if (focusLink) requestAnimationFrame(() => list.current?.querySelector<HTMLElement>(`[data-menu-key="${id}"]`)?.focus())
  }
  return (
    <Section title={t('favorites.mine')} menu={menu}>
      {stateOf(favorites, favorites.isSuccess && items.length === 0) ?? (
        <ul ref={list} className={styles.list}>
          {items.map((favorite) => (
            <li key={favorite.id}>
              {renaming === favorite.id ? (
                <FavoriteNameField favorite={favorite} icon={favoriteIcon(favorite)} onDone={(focusLink) => renamed(favorite.id, focusLink)} />
              ) : favorite.item.type === 'document' ? (
                <Link to="/doc/$ref" params={{ ref: String(favorite.item.docNumber) }} {...entry(favorite)}>
                  {favoriteIcon(favorite)}
                  <span className={styles.text}>{favorite.name}</span>
                </Link>
              ) : (
                <Link to="/folders/$id" params={{ id: String(favorite.item.id) }} {...entry(favorite)}>
                  {favoriteIcon(favorite)}
                  <span className={styles.text}>{favorite.name}</span>
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
