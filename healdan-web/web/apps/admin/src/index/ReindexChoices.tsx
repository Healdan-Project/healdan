// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { UseQueryResult } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, type Ref } from 'react'
import type { adminComponents } from '@healdan/api'
import { Button, CheckField, Highlight, Icon, RadioChoices, SearchPicker, TextField } from '@healdan/ui'
import { Problems } from '../forms/Problems'
import { Refusal } from '../forms/Refusal'
import { StaleNote } from '../people/StaleNote'
import { matterLabel } from '../pickers/MatterPicker'
import { plural, t } from '../strings'
import { placed, type WorkspaceNode } from '../workspace/tree'
import formStyles from '../forms/Form.module.css'
import type { MatterChoice } from './reindexQueries'
import type { TypedDocNumber } from './reindexText'
import styles from './Reindex.module.css'

type A = adminComponents['schemas']

/**
 * A list a choice draws from: a line while it's first asked, the catalogue's words and Try Again when it couldn't be read,
 * and, once it has been, the note with Try Again while a new read fails, over the list kept (P4-R15).
 */
function ReadState({ asked }: { asked: UseQueryResult }) {
  if (asked.data === undefined) {
    if (!asked.isError) return <p className={formStyles.note}>{t('common.loading')}</p>
    return (
      <div className={styles.unread}>
        <Refusal failure={asked.error} />
        <Button onPress={() => void asked.refetch()}>{t('common.tryAgain')}</Button>
      </div>
    )
  }
  return <StaleNote failed={asked.isError} onRetry={() => void asked.refetch()} />
}

export interface MatterFinderProps {
  /** Recent Matters, while nothing is typed */
  initial: MatterChoice[]
  search: (q: string) => Promise<MatterChoice[]>
  /** The matter chosen, offered first among Recent Matters when it isn't one, so it's always shown as chosen */
  chosen: MatterChoice | null
  onPick: (matter: MatterChoice) => void
  inputRef?: Ref<HTMLInputElement>
  /** Nothing can be typed or picked, as while the start is on its way */
  isDisabled?: boolean
}

/**
 * Matter, and Folder's first step (spec 8.9, P6-R18): a search by number, name or client that starts from Recent Matters,
 * the words marked in each row, with its client and, where the row counts them, its documents.
 */
export function MatterFinder({ initial, search, chosen, onPick, inputRef, isDisabled = false }: MatterFinderProps) {
  const rows = chosen === null || initial.some((one) => one.id === chosen.id) ? initial : [chosen, ...initial]
  return (
    <SearchPicker<MatterChoice>
      label={t('admin.reindex.matter')}
      placeholder={t('admin.reindex.findMatter')}
      hint={t('admin.reindex.matterHint')}
      initialHeading={t('admin.reindex.recentMatters')}
      initial={rows}
      search={search}
      id={(matter) => String(matter.id)}
      render={(matter, query) => (
        <span className={styles.row}>
          <Icon name="briefcase" kind="matter" />
          <span className={styles.rowText}>
            <span>
              <Highlight text={matterLabel(matter)} query={query} />
            </span>
            <span className={styles.secondary}>
              <Highlight text={matter.client} query={query} />
            </span>
          </span>
          {matter.documents !== null && <span className={styles.count}>{plural('documents', matter.documents)}</span>}
        </span>
      )}
      onPick={onPick}
      selectedId={chosen === null ? undefined : String(chosen.id)}
      inputRef={inputRef}
      // One character is enough, as a matter's number can be one digit, as MatterPicker takes it
      minLength={1}
      isDisabled={isDisabled}
    />
  )
}

/** A folder of the matter's workspace, as Folder lists it: its depth below the top, and the folders it's in */
interface FolderRow {
  node: WorkspaceNode
  depth: number
  /** The folders it's in, below the top, joined: none for one of the top's own */
  path: string
}

/** The matter's folders, below its own, in the tree's order: search folders left out, as they hold no documents */
function folderRows(root: WorkspaceNode): FolderRow[] {
  return placed(root)
    .filter((one) => one.node.id !== root.id && one.node.kind === 'folder')
    .map((one) => ({ node: one.node, depth: one.path.length - 1, path: one.path.slice(1, -1).join(' › ') }))
}

export interface FolderChoiceProps {
  /** Finding the matter, while none is chosen */
  finder: Omit<MatterFinderProps, 'chosen' | 'inputRef' | 'isDisabled'>
  matter: MatterChoice | null
  /** Back to the search, for another matter */
  onClearMatter: () => void
  tree: UseQueryResult<A['WorkspaceNode']>
  folderId: number | undefined
  onPickFolder: (folderId: number) => void
  /** Nothing can be typed or picked, as while the start is on its way */
  isDisabled?: boolean
}

/**
 * Folder (spec 8.9, P6-R18): a matter found first, as Matter finds it, then its folders from the workspace tree, indented,
 * filtered by words, with no count of their own: the preview counts the one picked, every folder inside it included. The
 * focus follows the step: to the folders once the matter is chosen, and back to the search when another is wanted.
 */
export function FolderChoice({ finder, matter, onClearMatter, tree, folderId, onPickFolder, isDisabled = false }: FolderChoiceProps) {
  const finderInput = useRef<HTMLInputElement>(null)
  const filterInput = useRef<HTMLInputElement>(null)
  const otherMatter = useRef<HTMLButtonElement>(null)
  const stepped = useRef<'matter' | 'cleared' | null>(null)
  const rows = useMemo(() => (tree.data ? folderRows(tree.data) : []), [tree.data])
  // The filter is the folders' own: nothing is asked of the server for it
  const filter = useCallback(async (q: string) => {
    const words = q.toLowerCase()
    return rows.filter((row) => row.node.name.toLowerCase().includes(words))
  }, [rows])

  // Once the field of the next step is drawn: the folders come once the matter's tree is read
  useEffect(() => {
    const settled = tree.data !== undefined || tree.isError
    // A matter with no folders of its own, or a tree that couldn't be read, has no folders to go to: its way back, then
    const next = stepped.current === 'matter' ? (filterInput.current ?? (settled ? otherMatter.current : null)) : stepped.current === 'cleared' ? finderInput.current : null
    if (next === null) return
    next.focus()
    stepped.current = null
  })

  if (matter === null) {
    return (
      <MatterFinder
        {...finder}
        chosen={null}
        inputRef={finderInput}
        isDisabled={isDisabled}
        onPick={(picked) => {
          stepped.current = 'matter'
          finder.onPick(picked)
        }}
      />
    )
  }
  return (
    <div className={styles.pane}>
      <div className={styles.chosen}>
        <Icon name="briefcase" kind="matter" />
        <span className={styles.chosenName}>
          {t('admin.reindex.folderMatter', { name: matterLabel(matter) })}
        </span>
        <Button
          ref={otherMatter}
          variant="quiet"
          isDisabled={isDisabled}
          aria-label={t('admin.reindex.otherMatter')}
          onPress={() => {
            stepped.current = 'cleared'
            onClearMatter()
          }}
        >
          <Icon name="close" size={14} />
        </Button>
      </div>
      <ReadState asked={tree} />
      {tree.data !== undefined &&
        (rows.length === 0 ? (
          <p className={formStyles.note}>{t('admin.reindex.noFolders')}</p>
        ) : (
          <SearchPicker<FolderRow>
            label={t('admin.reindex.folder')}
            placeholder={t('admin.reindex.filterFolders')}
            hint={t('admin.reindex.folderHint')}
            initialHeading={t('admin.reindex.folders', { name: matterLabel(matter) })}
            initial={rows}
            search={filter}
            id={(row) => String(row.node.id)}
            render={(row, query) => (
              // Indented by its depth in the tree; found by a filter, it says the folders it's in instead
              <span className={styles.row} style={query === '' ? { paddingInlineStart: `calc(${row.depth - 1} * var(--hd-space-5))` } : undefined}>
                <Icon name="folder" kind="folder" />
                <span className={styles.rowText}>
                  <span>
                    <Highlight text={row.node.name} query={query} />
                  </span>
                  {query !== '' && row.path !== '' && <span className={styles.secondary}>{t('admin.reindex.folderIn', { path: row.path })}</span>}
                </span>
              </span>
            )}
            onPick={(row) => onPickFolder(row.node.id)}
            selectedId={folderId === undefined ? undefined : String(folderId)}
            inputRef={filterInput}
            // The filter is the folders' own, so one letter is enough
            minLength={1}
            isDisabled={isDisabled}
          />
        ))}
    </div>
  )
}

export interface DocumentsChoiceProps {
  text: string
  onChange: (text: string) => void
  typed: TypedDocNumber[]
  /** What the last preview found for each doc #, by its number */
  matches: Map<number, A['ReindexMatch']>
  /** What's wrong with the doc #s, as more than the server takes, or the server's refusal of the field */
  error?: string
}

/**
 * Documents (spec 8.9): doc #s typed or pasted, each shown as the preview matched it, with its file name and matter, or as
 * no such document; one the reindex would leave out says why. What isn't a whole number is no document, and isn't sent.
 */
export function DocumentsChoice({ text, onChange, typed, matches, error }: DocumentsChoiceProps) {
  return (
    <div className={styles.pane}>
      <TextField label={t('admin.reindex.docNumbers')} description={t('admin.reindex.docNumbersHint')} multiline rows={3} autoComplete="off" value={text} onChange={onChange} error={error} />
      {typed.length > 0 && (
        <ul aria-label={t('admin.reindex.matches')} className={styles.matches}>
          {typed.map((one) => {
            const match = one.docNumber === undefined ? undefined : matches.get(one.docNumber)
            const missing = one.docNumber === undefined || match?.status === 'not-found'
            return (
              <li key={one.key} className={styles.match}>
                <Icon name={missing ? 'close' : match === undefined ? 'document' : 'check'} size={14} className={missing ? styles.missing : undefined} />
                <span className={styles.rowText}>
                  <span>
                    {t('admin.reindex.docNumber', { number: one.text })}
                    {match?.document && ` ${match.document.fileName}`}
                  </span>
                  {match?.status === 'left-out' && <span className={styles.secondary}>{t('admin.reindex.leftOutDocument')}</span>}
                </span>
                <span className={styles.count}>{missing ? t('admin.reindex.noSuchDocument') : (match?.document?.matter?.number ?? '')}</span>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

export interface StoreChoiceProps {
  stores: UseQueryResult<A['StoreInfo'][]>
  storeId: number | undefined
  onPick: (storeId: number) => void
}

/** Store (spec 8.9): a plain list, as a firm has few Stores (spec 7.2), each with its documents */
export function StoreChoice({ stores, storeId, onPick }: StoreChoiceProps) {
  const list = stores.data ?? []
  return (
    <div className={styles.pane}>
      <ReadState asked={stores} />
      {stores.data !== undefined &&
        (list.length === 0 ? (
          <p className={formStyles.note}>{t('admin.reindex.noStores')}</p>
        ) : (
          <RadioChoices
            label={t('admin.reindex.store')}
            value={storeId === undefined ? '' : String(storeId)}
            onChange={(value) => onPick(Number(value))}
            choices={list.map((store) => ({
              value: String(store.id),
              label: store.name,
              description: store.documents === null ? t('admin.reindex.notCounted') : plural('documents', store.documents),
            }))}
          />
        ))}
    </div>
  )
}

export interface EverythingChoiceProps {
  /** "All 1,284 documents, in about 37 minutes…", once the preview has counted them */
  line: string | undefined
  emptyFirst: boolean
  onEmptyFirst: (emptyFirst: boolean) => void
}

/**
 * Everything (spec 8.9): every document, how long they take, and Empty the index first, unticked, under its warning: for
 * a damaged index only, as search misses documents until the run ends.
 */
export function EverythingChoice({ line, emptyFirst, onEmptyFirst }: EverythingChoiceProps) {
  return (
    <div className={styles.pane}>
      {/* There before it has words, so a screen reader says them as they come */}
      <p role="status" className={styles.line}>
        {line}
      </p>
      <Problems
        framed
        problems={[
          {
            key: 'empty-first',
            sentence: t('admin.reindex.emptyFirstWarning'),
            action: <CheckField label={t('admin.reindex.emptyFirst')} checked={emptyFirst} onChange={onEmptyFirst} />,
          },
        ]}
      />
    </div>
  )
}
