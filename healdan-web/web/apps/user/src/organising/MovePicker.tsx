// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useId, useState, type ReactNode } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, Dialog, Icon, plural, t, TextField, toast, type StringKey } from '@healdan/ui'
import type { OrganisingItems } from '../actions/batch'
import { planMove } from '../actions/move'
import { placeOf, type Place } from '../filing/dropTargets'
import { messageFor } from '../frame/errors'
import { folderIcon, matterIcon } from '../kindIcons'
import { fetchSubfolders, keys, useFolder, useMe, useMyMatters, useRecentMatters } from '../queries'
import { matterLabel, suggestable, useSuggestions } from '../search/Suggestions'
import { useApi } from '../services'
import styles from './organising.module.css'

type S = components['schemas']

export interface MovePickerProps {
  /** The folders and documents being moved; none for the Trash's restore, whose places the server alone decides */
  items: OrganisingItems
  /** The dialog's title, in place of "Move 2 Documents": the Trash's "Restore "Old Drafts" to…" */
  title?: string
  /** The folder the person chose to move the items into */
  onChoose: (place: Place) => void
  onCancel: () => void
}

/** "Move 2 Documents" for documents alone, as before; "Move "Old Drafts"" for one folder, and "Move 3 Items" with folders */
function titleOf(items: OrganisingItems): string {
  const [folder] = items.folders
  if (!folder) return plural('moveTitle', items.documents.length)
  if (items.folders.length === 1 && items.documents.length === 0) return t('move.oneTitle', { name: folder.name })
  return plural('move.manyTitle', items.folders.length + items.documents.length)
}

/**
 * Matters, or one matter's folders: the choice of a folder to move documents and folders into never browses Stores (spec
 * 6.7). Where they can't go is greyed out with the reason (folders and Trash spec 7.5).
 */
export function MovePicker({ items, title, onChoose, onCancel }: MovePickerProps) {
  // The folders from the chosen matter's top level down to the one being looked into; none while a matter is to be chosen
  const [trail, setTrail] = useState<number[]>([])
  return (
    <Dialog title={title ?? titleOf(items)} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <div className={styles.picker}>
          {trail.length === 0 ? (
            <Matters onChoose={(folderId) => setTrail([folderId])} />
          ) : (
            <Folders
              items={items}
              trail={trail}
              onOpen={(id) => setTrail((now) => [...now, id])}
              onBack={() => setTrail((now) => now.slice(0, -1))}
              onChoose={(place) => {
                close()
                onChoose(place)
              }}
            />
          )}
          <div className={styles.actions}>
            <Button onPress={close}>{t('common.cancel')}</Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

/** A place to open, or one greyed out with why it can't be */
function Choice({ onPress, reason, children }: { onPress?: () => void; reason?: string; children: ReactNode }) {
  return (
    <li>
      <Button variant="quiet" isDisabled={reason !== undefined} onPress={onPress}>
        {children}
        {reason && <span className={styles.why}>{reason}</span>}
      </Button>
    </li>
  )
}

function MatterChoices({ matters, onChoose }: { matters: S['MatterView'][]; onChoose: (folderId: number) => void }) {
  return (
    <ul className={styles.list}>
      {matters.map((matter) => (
        <Choice key={matter.id} onPress={() => onChoose(matter.folderId)}>
          <Icon {...matterIcon} />
          <span className={styles.matter}>
            {matter.display} <span className={styles.client} data-client>{matter.client.name}</span>
          </span>
        </Choice>
      ))}
    </ul>
  )
}

/** Recent Matters and My Matters, or what a search for a matter finds */
function Matters({ onChoose }: { onChoose: (folderId: number) => void }) {
  const api = useApi()
  const [text, setText] = useState('')
  const recent = useRecentMatters()
  const mine = useMyMatters()
  const found = useSuggestions(text)
  const searching = suggestable(text) !== undefined

  // A suggestion names its matter, not its folder
  async function open(id: number) {
    try {
      onChoose((await api.get<S['MatterView']>(apiPath`/v1/matters/${id}`)).folderId)
    } catch (error) {
      toast(messageFor(error))
    }
  }

  const recentMatters = recent.data?.items ?? []
  // A matter that is in both lists is shown once, under Recent
  const mineMatters = (mine.data?.items ?? []).filter((matter) => !recentMatters.some((one) => one.id === matter.id))

  return (
    <>
      <TextField label={t('move.search')} value={text} onChange={setText} />
      {searching ? (
        found && found.matters.length === 0 ? (
          <p className={styles.note}>{t('move.nothing')}</p>
        ) : (
          <ul className={styles.list} aria-label={t('matters.title')}>
            {found?.matters.map((matter) => (
              <Choice key={matter.id} onPress={() => void open(matter.id)}>
                <Icon {...matterIcon} />
                <span className={styles.matter}>
                  {matterLabel(matter)} <span className={styles.client} data-client>{matter.client.name}</span>
                </span>
              </Choice>
            ))}
          </ul>
        )
      ) : (
        <>
          {recentMatters.length > 0 && (
            <section aria-label={t('recentMatters.title')}>
              <h3 className={styles.heading}>{t('recentMatters.title')}</h3>
              <MatterChoices matters={recentMatters} onChoose={onChoose} />
            </section>
          )}
          {mineMatters.length > 0 && (
            <section aria-label={t('myMatters.title')}>
              <h3 className={styles.heading}>{t('myMatters.title')}</h3>
              <MatterChoices matters={mineMatters} onChoose={onChoose} />
            </section>
          )}
          {recent.isSuccess && mine.isSuccess && recentMatters.length + mineMatters.length === 0 && <p className={styles.note}>{t('common.none')}</p>}
        </>
      )}
    </>
  )
}

/**
 * Why Move Here is greyed out for the items where the picker is (folders and Trash spec 7.5): the folder itself or inside
 * it; and wherever planMove would move none of them: where every one of them is already, and where none of them can go:
 * for documents, a place that holds none (M1), worded at a matter's top level, and for folders, a matter's top level for
 * anyone but an administrator. While some can go, Move Here moves them and Refusals says the rest, as a drop does. Null
 * for a reason with no words; undefined when they can go there. With no items (the Trash's restore) nothing is greyed out,
 * and the server decides.
 */
function whyNotHere(items: OrganisingItems, view: S['FolderView'], admin: boolean | undefined): StringKey | null | undefined {
  // The path holds the folder itself
  const inside = new Set(view.path.map((step) => step.id))
  if (items.folders.some((folder) => inside.has(folder.id))) return 'move.itself'
  // Only what can be moved counts: a folder that can't is said in Refusals, wherever it was to go
  const movable = items.folders.filter((folder) => folder.canMove)
  if (items.documents.length + movable.length === 0) return undefined
  const plan = planMove({ documents: items.documents, folders: movable }, placeOf(view), admin)
  if (plan.documents.length + plan.folders.length > 0) return undefined
  if (plan.refused.length === 0) return 'move.already'
  if (plan.refused.some((one) => 'document' in one)) return view.kind === 'matter' ? 'move.chooseFolder' : null
  return 'move.adminsOnly'
}

/** From this many subfolders the picker offers a filter: fewer are quicker to tab through than to type for */
const FILTER_FROM = 8

/** One folder of the chosen matter: its subfolders to open, and Move Here, greyed out where the items can't go */
function Folders({
  items,
  trail,
  onOpen,
  onBack,
  onChoose,
}: {
  items: OrganisingItems
  trail: number[]
  onOpen: (id: number) => void
  onBack: () => void
  onChoose: (place: Place) => void
}) {
  const api = useApi()
  const me = useMe()
  const reasonId = useId()
  const here = trail[trail.length - 1]!
  const folder = useFolder(here)
  const subfolders = useQuery({ queryKey: keys.subfolders(here), queryFn: () => fetchSubfolders(api, String(here)) })
  const view = folder.data
  const why = view && whyNotHere(items, view, me.data?.admin)
  // Search folders hold nothing moved into them, so they aren't offered (plan correction 12)
  const folders = (subfolders.data ?? []).filter((one) => one.kind === 'folder')
  // A folder being moved is a place nothing goes into, and it doesn't open, so nothing inside it can be chosen
  const moving = new Set(items.folders.map((one) => one.id))
  // Each folder starts unfiltered. This is done here, not with a key, as a new component would drop the focus from Up One
  // Level to the page
  const [filter, setFilter] = useState('')
  const [filtered, setFiltered] = useState(here)
  if (filtered !== here) {
    setFiltered(here)
    setFilter('')
  }
  const filterable = folders.length >= FILTER_FROM
  // A refetch can take the field away; what was typed in it then hides nothing, as nothing could clear it
  const wanted = filterable ? filter.trim().toLocaleLowerCase() : ''
  const listed = wanted ? folders.filter((one) => one.name.toLocaleLowerCase().includes(wanted)) : folders
  return (
    <>
      <div>
        <Button variant="quiet" onPress={onBack}>
          <Icon name="chevronLeft" size={14} /> {trail.length === 1 ? t('move.back') : t('move.up')}
        </Button>
      </div>
      <h3 className={styles.heading}>{view ? t('move.folders', { name: view.name }) : t('common.loading')}</h3>
      {filterable && (
        <div>
          <TextField label={t('move.filter')} value={filter} onChange={setFilter} />
          {/* There before it has words, so a screen reader says them as they come */}
          <p role="status" className={styles.filterResult}>
            {listed.length === 0 && t('move.nothing')}
          </p>
        </div>
      )}
      {subfolders.isError ? (
        <p className={styles.note}>{messageFor(subfolders.error)}</p>
      ) : (
        <ul className={styles.list} aria-label={view ? t('move.folders', { name: view.name }) : undefined}>
          {listed.map((one) => (
            <Choice key={one.id} reason={moving.has(one.id) ? t('move.itself') : undefined} onPress={() => onOpen(one.id)}>
              <Icon {...folderIcon()} /> {one.name}
            </Choice>
          ))}
        </ul>
      )}
      {subfolders.isSuccess && folders.length === 0 && <p className={styles.note}>{t('move.noFolders')}</p>}
      {why && (
        <p id={reasonId} className={styles.note}>
          {t(why)}
        </p>
      )}
      <div className={styles.actions}>
        <Button variant="default" isDisabled={!view || why !== undefined} aria-describedby={why ? reasonId : undefined} onPress={() => view && onChoose(placeOf(view))}>
          {t('move.here')}
        </Button>
      </div>
    </>
  )
}
