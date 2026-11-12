// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, Dialog, Icon, plural, t, TextField, toast } from '@healdan/ui'
import type { Movable } from '../actions/batch'
import { messageFor } from '../frame/errors'
import { fetchSubfolders, keys, useFolder, useMyMatters, useRecentMatters } from '../queries'
import { matterLabel, suggestable, useSuggestions } from '../search/Suggestions'
import { useApi } from '../services'
import styles from './organising.module.css'

type S = components['schemas']

export interface MovePickerProps {
  documents: readonly Movable[]
  /** The folder the person chose to move the documents into */
  onChoose: (folder: { id: number; name: string }) => void
  onCancel: () => void
}

/** Matters, or one matter's folders: the choice of a folder to move documents into never browses Stores (spec 6.7). */
export function MovePicker({ documents, onChoose, onCancel }: MovePickerProps) {
  // The folders from the chosen matter's top level down to the one being looked into; none while a matter is to be chosen
  const [trail, setTrail] = useState<number[]>([])
  return (
    <Dialog title={plural('moveTitle', documents.length)} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <div className={styles.picker}>
          {trail.length === 0 ? (
            <Matters onChoose={(folderId) => setTrail([folderId])} />
          ) : (
            <Folders
              trail={trail}
              onOpen={(id) => setTrail((now) => [...now, id])}
              onBack={() => setTrail((now) => now.slice(0, -1))}
              onChoose={(folder) => {
                close()
                onChoose(folder)
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

function Choice({ onPress, children }: { onPress: () => void; children: ReactNode }) {
  return (
    <li>
      <Button variant="quiet" onPress={onPress}>
        {children}
      </Button>
    </li>
  )
}

function MatterChoices({ matters, onChoose }: { matters: S['MatterView'][]; onChoose: (folderId: number) => void }) {
  return (
    <ul className={styles.list}>
      {matters.map((matter) => (
        <Choice key={matter.id} onPress={() => onChoose(matter.folderId)}>
          <Icon name="briefcase" />
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
                <Icon name="briefcase" />
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

/** One folder of the chosen matter: its subfolders to open, and Move Here, which needs a folder inside the matter */
function Folders({ trail, onOpen, onBack, onChoose }: { trail: number[]; onOpen: (id: number) => void; onBack: () => void; onChoose: (folder: { id: number; name: string }) => void }) {
  const api = useApi()
  const here = trail[trail.length - 1]!
  const folder = useFolder(here)
  const subfolders = useQuery({ queryKey: keys.subfolders(here), queryFn: () => fetchSubfolders(api, String(here)) })
  const view = folder.data
  // A matter's top level and a search folder hold no documents (M1); the server says so again
  const holds = view?.kind === 'folder' && view.canFile
  // Search folders hold no documents, so they aren't offered
  const folders = (subfolders.data ?? []).filter((one) => one.kind === 'folder')
  return (
    <>
      <div>
        <Button variant="quiet" onPress={onBack}>
          <Icon name="chevronLeft" size={14} /> {trail.length === 1 ? t('move.back') : t('move.up')}
        </Button>
      </div>
      <h3 className={styles.heading}>{view ? t('move.folders', { name: view.name }) : t('common.loading')}</h3>
      {subfolders.isError ? (
        <p className={styles.note}>{messageFor(subfolders.error)}</p>
      ) : (
        <ul className={styles.list} aria-label={view ? t('move.folders', { name: view.name }) : undefined}>
          {folders.map((one) => (
            <Choice key={one.id} onPress={() => onOpen(one.id)}>
              <Icon name="folder" /> {one.name}
            </Choice>
          ))}
        </ul>
      )}
      {subfolders.isSuccess && folders.length === 0 && <p className={styles.note}>{t('move.noFolders')}</p>}
      {view && !holds && view.kind === 'matter' && <p className={styles.note}>{t('move.chooseFolder')}</p>}
      <div className={styles.actions}>
        <Button variant="default" isDisabled={!holds} onPress={() => view && onChoose({ id: view.id, name: view.name })}>
          {t('move.here')}
        </Button>
      </div>
    </>
  )
}
