// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useRef, useState, type ReactNode } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, formatCount, formatSize, Icon, StatusDot, Table, type TableColumn, type TableRow } from '@healdan/ui'
import { routes } from '../addresses'
import { Fact } from '../facts/Fact'
import { useFocusWhenGone } from '../focus'
import { useRowsHeight } from '../lists/useRowsHeight'
import { BackendDialog } from '../storage/BackendDialog'
import { isStorageUnavailable, storageKeys, useBackends, useStorageSettings } from '../storage/queries'
import { RecoveryKeyDialog } from '../storage/RecoveryKeyDialog'
import { StorageSettingsForm } from '../storage/StorageSettingsForm'
import { StuckFilesDialog, useStuckRetry } from '../storage/StuckFiles'
import { keyShownOf, kindOf, statusDot, statusLine, stuckOf, waitingOf, whereOf } from '../storage/text'
import { t } from '../strings'
import { Refusal } from '../forms/Refusal'
import clientStyles from '../clients/Clients.module.css'
import styles from '../storage/Storage.module.css'

type A = adminComponents['schemas']

interface BackendRow extends TableRow {
  backend: A['Backend']
}

/** The rows the list shows before it scrolls */
const ROWS_SHOWN = 8

const OTHER_LIST = '/v1/admin/storage/stuck-files'
const OTHER_RETRY = '/v1/admin/storage/stuck-files/retry'

/** The list's columns, as the Storage tab's: built when it first draws, and kept */
function backendColumns(): TableColumn<BackendRow>[] {
  return [
    {
      id: 'name',
      name: t('admin.backends.column.name'),
      isRowHeader: true,
      width: '2fr',
      minWidth: 140,
      cell: ({ backend }) => (
        <span className={styles.name}>
          <Icon name={backend.kind === 's3' ? 'database' : 'folder'} />
          <span className={clientStyles.muted}>{backend.name}</span>
        </span>
      ),
    },
    { id: 'kind', name: t('admin.backends.column.kind'), width: 110, cell: ({ backend }) => kindOf(backend) },
    { id: 'where', name: t('admin.backends.column.where'), width: '3fr', minWidth: 160, cell: ({ backend }) => whereOf(backend) },
    { id: 'encrypted', name: t('admin.backends.column.encrypted'), width: 100, cell: ({ backend }) => t(backend.encrypted ? 'admin.backends.yes' : 'admin.backends.no') },
    { id: 'files', name: t('admin.backends.column.files'), align: 'end', width: 90, cell: ({ backend }) => formatCount(backend.files) },
    { id: 'size', name: t('admin.backends.column.size'), align: 'end', width: 90, cell: ({ backend }) => formatSize(backend.bytes) },
    {
      id: 'status',
      name: t('admin.backends.column.status'),
      width: '3fr',
      minWidth: 160,
      cell: ({ backend }) => (
        <span className={clientStyles.muted}>
          <StatusDot status={statusDot(backend)}>{statusLine(backend)}</StatusDot>
        </span>
      ),
    },
    { id: 'keyShown', name: t('admin.backends.column.keyShown'), width: 190, cell: ({ backend }) => keyShownOf(backend) },
  ]
}

/** A page that has nothing to show but what it says: storage backends off, or no storage module on the server */
function Note({ children }: { children: ReactNode }) {
  return (
    <div className={styles.page}>
      <h1 className={clientStyles.title}>{t('admin.backends.title')}</h1>
      <p className={styles.about}>{children}</p>
    </div>
  )
}

/**
 * The Backends list, at /admin/backends (spec 8.6): everything the Storage tab shows of the backends. Each backend with
 * its kind, where it is, whether it's encrypted, the files recorded on it, its status and when its recovery key was last
 * shown, Original first; Add Backend; Refresh; a row opens its page. Under it, the files with no Store, waiting and
 * stuck, with Stuck Files and Retry, and the storage settings. On a server that keeps its files in LogicalDOC's own
 * store, it says so and shows no controls; on one without Healdan's storage module, it says it can't be shown. The
 * recovery key of an encrypted backend just added is held here, above what the list's answer draws, so its window stays
 * when the list, asked again as it opens and at each return to the window, can't be read.
 */
export function BackendsView() {
  const navigate = useNavigate()
  const overview = useBackends()
  // An encrypted backend just added and its recovery key, held only while the key's dialog is open
  const [added, setAdded] = useState<AddedKey>()

  function open(id: number) {
    void navigate({ to: routes.backend, params: { id: String(id) } })
  }

  let page: ReactNode
  if (overview.isError) {
    page = isStorageUnavailable(overview.error) ? (
      <Note>{t('admin.backends.unavailable')}</Note>
    ) : (
      <div className={styles.page}>
        <h1 className={clientStyles.title}>{t('admin.backends.title')}</h1>
        <ErrorPanel error={overview.error} onRetry={() => void overview.refetch()} />
      </div>
    )
  } else if (overview.data === undefined) {
    page = (
      <div className={styles.page}>
        <h1 className={clientStyles.title}>{t('admin.backends.title')}</h1>
        <p className={styles.muted}>{t('common.loading')}</p>
      </div>
    )
  } else if (!overview.data.on) {
    page = <Note>{t('admin.backends.off')}</Note>
  } else {
    page = <Backends overview={overview.data} open={open} onKey={setAdded} />
  }
  return (
    <>
      {page}
      {added !== undefined && (
        <RecoveryKeyDialog
          mode="added"
          backend={added.backend}
          recoveryKey={added.recoveryKey}
          onClose={() => {
            const id = added.backend.id
            setAdded(undefined)
            open(id)
          }}
        />
      )}
    </>
  )
}

/** An encrypted backend just added, and the recovery key its add answered */
interface AddedKey {
  backend: A['Backend']
  recoveryKey: string
}

function Backends({ overview, open, onKey }: { overview: A['BackendOverview']; open: (id: number) => void; onKey: (added: AddedKey) => void }) {
  const queryClient = useQueryClient()
  const headingId = useId()
  const [adding, setAdding] = useState(false)

  const columns = useMemo(() => backendColumns(), [])
  const rows = useMemo(() => overview.backends.map((backend): BackendRow => ({ id: String(backend.id), textValue: backend.name, backend })), [overview.backends])
  const { boxRef, height } = useRowsHeight(rows.length, ROWS_SHOWN)

  return (
    <div className={styles.page}>
      <div className={clientStyles.head}>
        <h1 id={headingId} className={clientStyles.title}>
          {t('admin.backends.title')}
        </h1>
        <div className={clientStyles.actions}>
          <Button
            onPress={() => {
              void queryClient.invalidateQueries({ queryKey: storageKeys.backends })
              void queryClient.invalidateQueries({ queryKey: storageKeys.storage })
            }}
          >
            <Icon name="refresh" size={14} /> {t('admin.backends.refresh')}
          </Button>
          <Button variant="default" onPress={() => setAdding(true)}>
            <Icon name="plus" size={14} /> {t('admin.backends.add')}
          </Button>
        </div>
      </div>
      <p className={styles.about}>{t('admin.backends.about')}</p>
      <div ref={boxRef} className={styles.list} style={{ height }}>
        <Table
          aria-labelledby={headingId}
          columns={columns}
          rows={rows}
          selectionMode="none"
          onOpen={(key) => {
            const id = idFrom(key)
            if (id !== undefined) open(id)
          }}
          empty={<p className={styles.muted}>{t('admin.backends.none')}</p>}
        />
      </div>
      <OtherFiles files={overview.otherFiles} />
      <Settings />
      {adding && (
        <BackendDialog
          onAdded={(answer) => {
            if (answer.recoveryKey === null) open(answer.backend.id)
            else onKey({ backend: answer.backend, recoveryKey: answer.recoveryKey })
          }}
          onClose={() => setAdding(false)}
        />
      )}
    </div>
  )
}

/**
 * The files whose document isn't in a Store (the Storage tab's Other Files): waiting and stuck, with Stuck Files and Retry.
 * Once a Retry leaves none stuck, both buttons go, and the section's heading takes the focus they had.
 */
function OtherFiles({ files }: { files: A['PendingFiles'] }) {
  const queryClient = useQueryClient()
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const [listing, setListing] = useState(false)
  const counted = () => void queryClient.invalidateQueries({ queryKey: storageKeys.backends })
  const retried = t('admin.stuck.otherRetried')
  const retrying = useStuckRetry({ list: OTHER_LIST, retry: OTHER_RETRY, retried, onRetried: counted })
  useFocusWhenGone(files.stuck === 0, heading)

  return (
    <section aria-labelledby={headingId} className={styles.section}>
      <h2 id={headingId} ref={heading} tabIndex={-1}>
        {t('admin.backends.other.title')}
      </h2>
      <p className={styles.muted}>{t('admin.backends.other.about')}</p>
      <dl className={clientStyles.facts}>
        <Fact term="admin.backends.other.waiting">{waitingOf(files.waiting, files.waitingBytes)}</Fact>
        <Fact term="admin.backends.other.stuck">{stuckOf(files.stuck)}</Fact>
      </dl>
      {files.stuck > 0 && (
        <div className={styles.row}>
          <Button onPress={() => setListing(true)}>{t('admin.backends.other.stuckFiles')}</Button>
          <Button isPending={retrying.busy} onPress={() => void retrying.run()}>
            {t('admin.backends.other.retry')}
          </Button>
        </div>
      )}
      <Refusal failure={retrying.failure} />
      {listing && (
        <StuckFilesDialog
          title={t('admin.stuck.otherTitle')}
          list={OTHER_LIST}
          retry={OTHER_RETRY}
          stuck={files.stuck}
          retried={retried}
          onRetried={counted}
          onClose={() => setListing(false)}
        />
      )}
    </section>
  )
}

/** The storage settings, once they've come; a failure to read them is said in their place */
function Settings() {
  const settings = useStorageSettings()
  const headingId = useId()
  if (settings.isError) {
    // The page above says it already when the server has no storage module
    if (isStorageUnavailable(settings.error)) return null
    return (
      <section aria-labelledby={headingId} className={styles.section}>
        <h2 id={headingId}>{t('admin.storageSettings.title')}</h2>
        <ErrorPanel error={settings.error} onRetry={() => void settings.refetch()} />
      </section>
    )
  }
  if (settings.data === undefined || !settings.data.on) return null
  return <StorageSettingsForm settings={settings.data} />
}
