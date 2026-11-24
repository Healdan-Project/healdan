// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { ApiError, type adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, Dialog, ToggleButtons } from '@healdan/ui'
import type { ReindexKind } from '../addresses'
import { Problems } from '../forms/Problems'
import { Refusal } from '../forms/Refusal'
import { useStores } from '../queries'
import { plural, t } from '../strings'
import { useMatter, useWorkspaceTree } from '../workspace/queries'
import { findNode } from '../workspace/tree'
import formStyles from '../forms/Form.module.css'
import { DocumentsChoice, EverythingChoice, FolderChoice, MatterFinder, StoreChoice } from './ReindexChoices'
import { reindexChanged } from './queries'
import { choiceOfRow, useMatterChoices, useReindexPreview, type MatterChoice } from './reindexQueries'
import { estimateLine, everythingLine, MOST_DOC_NUMBERS, readDocNumbers } from './reindexText'
import styles from './Reindex.module.css'

type A = adminComponents['schemas']

export interface ReindexDialogProps {
  /** The choice it opens on: the address's `reindex` */
  kind: ReindexKind
  /** What's already chosen, the address's `id`: a matter's id (not its folder's) for Matter, a folder's, or a Store's */
  id?: number
  /** For Folder, the matter whose workspace the folder is in, by its id: the address's `matter` */
  matter?: number
  /** The reindex as the server started it, once it's among the Search Index's, just before the dialog closes */
  onStarted?: (job: A['ReindexJob']) => void
  /** The dialog closes: Cancel, Esc, a click outside, or once started. The caller stops drawing it. */
  onClose: () => void
}

const KINDS: ReindexKind[] = ['documents', 'folder', 'matter', 'store', 'everything']

/** Whether the start was refused for more doc #s than a reindex takes (`422`, `too-many` on `docNumbers`) */
function refusedAsTooMany(failure: unknown): boolean {
  if (!(failure instanceof ApiError) || failure.code !== 'validation') return false
  const fields = (failure.body as { fields?: unknown } | null)?.fields
  return Array.isArray(fields) && fields.some((one: { field?: unknown; code?: unknown } | null) => one?.field === 'docNumbers' && one.code === 'too-many')
}

/** Chosen here, or not yet: undefined while the dialog still takes what the address chose */
type Picked<T> = T | undefined

/**
 * Reindex (spec 8.9, P6-R18): one of five choices, Documents, Folder, Matter, Store or Everything, the one the address
 * named open, with what it named chosen. Once a choice is complete, the preview says how many documents it covers and
 * about how long they take, asked again once a change settles, its last answer kept meanwhile; the button states the
 * count, and is greyed out while there's nothing to reindex or the preview is on its way. What the server would refuse is
 * said before it (spec 7.2). While the start is on its way the dialog holds: its choices, Cancel, Esc and a click outside
 * do nothing. Once started, the reindex joins those waiting or running (reindexChanged), goes to `onStarted`, and the dialog closes.
 */
export function ReindexDialog({ kind: opening, id, matter: matterParam, onStarted, onClose }: ReindexDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalId = useId()
  const [kind, setKind] = useState<ReindexKind>(opening)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  // The matter Matter and Folder share: the one picked here, else the one the address named, once it's read
  const addressMatterId = opening === 'matter' ? id : opening === 'folder' ? matterParam : undefined
  const addressMatter = useMatter(addressMatterId ?? -1, addressMatterId !== undefined)
  const [pickedMatter, setPickedMatter] = useState<Picked<MatterChoice | null>>()
  const matter = pickedMatter !== undefined ? pickedMatter : addressMatter.data ? choiceOfRow(addressMatter.data) : null
  const choices = useMatterChoices()

  // Folder: a folder of that matter's workspace, the one picked here or the address's, while it's in the tree
  const tree = useWorkspaceTree(kind === 'folder' && matter !== null ? matter.folderId : undefined)
  const [pickedFolder, setPickedFolder] = useState<Picked<{ matterId: number; folderId: number } | null>>()
  const folderPick = pickedFolder !== undefined ? pickedFolder : opening === 'folder' && id !== undefined && matterParam !== undefined ? { matterId: matterParam, folderId: id } : null
  const folderNode = folderPick !== null && matter !== null && folderPick.matterId === matter.id && tree.data ? findNode(tree.data, folderPick.folderId) : undefined
  const folderId = folderNode !== undefined && folderNode.kind === 'folder' && folderNode.id !== tree.data?.id ? folderNode.id : undefined

  // Store: one of the Stores listed
  const stores = useStores()
  const [pickedStore, setPickedStore] = useState<number>()
  const storeWanted = pickedStore ?? (opening === 'store' ? id : undefined)
  const storeId = stores.data?.some((store) => store.id === storeWanted) ? storeWanted : undefined

  const [text, setText] = useState('')
  const { typed, docNumbers } = readDocNumbers(text)
  const tooMany = docNumbers.length > MOST_DOC_NUMBERS
  const [emptyFirst, setEmptyFirst] = useState(false)

  // What the choice covers, once it's complete
  const scope: A['ReindexScope'] | undefined =
    kind === 'documents'
      ? docNumbers.length > 0 && !tooMany
        ? { kind, docNumbers }
        : undefined
      : kind === 'folder'
        ? folderId === undefined
          ? undefined
          : { kind, targetId: folderId }
        : kind === 'matter'
          ? matter === null
            ? undefined
            : { kind, targetId: matter.id }
          : kind === 'store'
            ? storeId === undefined
              ? undefined
              : { kind, targetId: storeId }
            : { kind }
  const { preview, current, failure: previewFailure } = useReindexPreview(scope)

  const fields = fieldErrors(failure)
  // The doc #s the server refused are said beside them; anything else, Empty the Index First's refusal too, above the
  // buttons, as no field's own words fit it
  const fieldShown = kind === 'documents' && fields.has('docNumbers')
  const refusal = preview?.refusal ?? null
  const ready = scope !== undefined && current && preview !== undefined && preview.count > 0 && refusal === null
  const label = kind === 'everything' ? t(emptyFirst ? 'admin.reindex.startEmptied' : 'admin.reindex.startEverything') : preview !== undefined && preview.count > 0 ? plural('admin.reindex.startCount', preview.count) : t('admin.reindex.start')
  const estimate = preview !== undefined && preview.estimateSeconds !== null ? (kind === 'everything' ? everythingLine : estimateLine)(preview.count, preview.estimateSeconds) : undefined
  const matches = new Map((preview?.documents ?? []).map((match) => [match.docNumber, match]))

  function change() {
    // A change takes the last refusal away
    setFailure(undefined)
  }

  async function start() {
    if (scope === undefined) return
    setBusy(true)
    setFailure(undefined)
    try {
      // Empty the Index First is sent only from Everything, and only when ticked
      const body: A['ReindexScope'] = scope.kind === 'everything' && emptyFirst ? { ...scope, emptyFirst: true } : scope
      const job = await api.send<A['ReindexJob']>('POST', '/v1/admin/reindex', body)
      // In the Search Index's reindexes at once, so its card shows, and every list of them asked again
      await reindexChanged(queryClient, job)
      onStarted?.(job)
      // Not through the dialog's own close, which is held while busy
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    // Held while the start is on its way, by Cancel, Esc or a click outside alike: the reindex would still start
    <Dialog title={t('admin.reindex.title')} size="wide" isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void start()
          }}
        >
          <fieldset disabled={busy} className={`${formStyles.hold} ${styles.body}`}>
            <ToggleButtons
              label={t('admin.reindex.choices')}
              value={kind}
              onChange={(next) => {
                setKind(next as ReindexKind)
                change()
              }}
              choices={KINDS.map((one) => ({ value: one, label: t(`admin.reindex.kind.${one}`) }))}
            />
            {kind === 'matter' && (
              <MatterFinder
                initial={choices.initial}
                search={choices.search}
                chosen={matter}
                onPick={(picked) => {
                  setPickedMatter(picked)
                  change()
                }}
                isDisabled={busy}
              />
            )}
            {kind === 'folder' && (
              <FolderChoice
                finder={{
                  initial: choices.initial,
                  search: choices.search,
                  onPick: (picked) => {
                    setPickedMatter(picked)
                    change()
                  },
                }}
                matter={matter}
                onClearMatter={() => {
                  setPickedMatter(null)
                  setPickedFolder(null)
                  change()
                }}
                tree={tree}
                folderId={folderId}
                onPickFolder={(picked) => {
                  if (matter !== null) setPickedFolder({ matterId: matter.id, folderId: picked })
                  change()
                }}
                isDisabled={busy}
              />
            )}
            {kind === 'documents' && (
              <DocumentsChoice
                text={text}
                onChange={(next) => {
                  setText(next)
                  change()
                }}
                typed={typed}
                matches={matches}
                // The limit in the reindex's own words, which it's checked against here first, as the server does
                error={tooMany || refusedAsTooMany(failure) ? t('reindex.tooMany') : fields.get('docNumbers')}
              />
            )}
            {kind === 'store' && (
              <StoreChoice
                stores={stores}
                storeId={storeId}
                onPick={(picked) => {
                  setPickedStore(picked)
                  change()
                }}
              />
            )}
            {kind === 'everything' && (
              <EverythingChoice
                line={estimate}
                emptyFirst={emptyFirst}
                onEmptyFirst={(next) => {
                  setEmptyFirst(next)
                  change()
                }}
              />
            )}
          </fieldset>
          <div className={styles.estimate}>
            {/* There before it has words, so a screen reader says them as they come; Everything says its own in its part */}
            {kind !== 'everything' && (
              <p role="status" className={styles.line}>
                {scope !== undefined && estimate}
              </p>
            )}
            {scope !== undefined && preview !== undefined && preview.skipped > 0 && kind !== 'documents' && (
              <p className={formStyles.note}>{plural('admin.reindex.leftOut', preview.skipped)}</p>
            )}
          </div>
          {/* What a start would be refused with now, before the button, which it describes */}
          {scope !== undefined && refusal !== null && <Problems problems={[{ key: 'refusal', id: refusalId, sentence: refusal }]} />}
          <Refusal failure={previewFailure} />
          <Refusal failure={failure !== undefined && !fieldShown ? failure : undefined} />
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy} aria-describedby={scope !== undefined && refusal !== null ? refusalId : undefined}>
              {label}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
