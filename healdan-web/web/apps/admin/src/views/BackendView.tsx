// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useId, useState } from 'react'
import { ApiError, apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom, useApi } from '@healdan/shell'
import { Button, Dialog, formatList, formatSize, Icon, StatusDot, toast } from '@healdan/ui'
import { routes } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import { Fact } from '../facts/Fact'
import { Problems } from '../forms/Problems'
import { Refusal } from '../forms/Refusal'
import { BackendDialog } from '../storage/BackendDialog'
import { backendsChanged, isStorageUnavailable, storageKeys, useBackend } from '../storage/queries'
import { RecoveryKeyDialog } from '../storage/RecoveryKeyDialog'
import { filesOf, keyShownOf, kindOf, statusDot, statusWords, whereOf } from '../storage/text'
import { plural, t } from '../strings'
import clientStyles from '../clients/Clients.module.css'
import page from '../matters/MatterPage.module.css'
import styles from '../storage/Storage.module.css'
import pageStyles from './views.module.css'

type A = adminComponents['schemas']

/**
 * A backend's page, at /admin/backends/{id} (spec 8.6): its name and status, its facts (where it is, what it holds,
 * whether it's encrypted, its recovery key confirmed and last shown, the Stores on it, and those in the Trash on it) and
 * the Storage tab's actions: Test, Edit and Remove, and for an encrypted backend Show Recovery Key, Confirm Recovery Key
 * (while it isn't), Enter Recovery Key and Key History. Remove asks first; while something keeps the backend (a Store,
 * live or in the Trash, or files recorded or held there), it says what and how to free it instead, with no Remove (spec
 * 7.2, P3-R38). An address that names no backend, as every one does while storage backends are off, says so in the app's
 * words.
 */
export function BackendView({ id }: { id: string }) {
  const backendId = idFrom(id)
  return backendId === undefined ? <BackendNotFound /> : <BackendPage id={backendId} />
}

function BackendPage({ id }: { id: number }) {
  const asked = useBackend(id)
  if (asked.isError) {
    if (asked.error instanceof ApiError && asked.error.status === 404) return <BackendNotFound />
    return (
      <div className={styles.page}>
        {isStorageUnavailable(asked.error) ? <p className={styles.about}>{t('admin.backends.unavailable')}</p> : <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />}
      </div>
    )
  }
  if (asked.data === undefined) {
    return (
      <div className={styles.page}>
        <p className={styles.muted}>{t('common.loading')}</p>
      </div>
    )
  }
  // By its id, so another backend's page, by Back or Forward too, starts afresh: no dialog or Test's answer carried over
  return <Backend key={asked.data.id} backend={asked.data} />
}

type Open = 'edit' | 'remove' | 'show' | 'confirm' | 'enter' | 'history'

function Backend({ backend }: { backend: A['Backend'] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [open, setOpen] = useState<Open | null>(null)
  const [testing, setTesting] = useState(false)
  const [tested, setTested] = useState<{ problems: string[] } | { failure: unknown }>()
  const close = () => setOpen(null)

  async function test() {
    setTesting(true)
    setTested(undefined)
    try {
      const found = await api.send<A['BackendTestResult']>('POST', apiPath`/v1/admin/backends/${backend.id}/test`)
      setTested({ problems: found.problems })
      // Its status now says what Test found
      backendsChanged(queryClient)
    } catch (error) {
      setTested({ failure: error })
    } finally {
      setTesting(false)
    }
  }

  async function remove() {
    await api.send<void>('DELETE', apiPath`/v1/admin/backends/${backend.id}`)
    toast(t('admin.removeBackend.done', { name: backend.name }))
    await navigate({ to: routes.backends })
    queryClient.removeQueries({ queryKey: storageKeys.backend(backend.id) })
    backendsChanged(queryClient)
  }

  return (
    <div className={styles.page}>
      <nav aria-label={t('admin.crumbs.label')} className={clientStyles.crumbs}>
        <ol>
          <li>
            <Link to={routes.backends} className={clientStyles.crumbLink}>
              {t('admin.backends.title')}
            </Link>
          </li>
          <li aria-current="page">{backend.name}</li>
        </ol>
      </nav>
      <div className={clientStyles.head}>
        <h1 className={clientStyles.title}>{backend.name}</h1>
        <div className={clientStyles.actions}>
          <Button onPress={() => void test()} isPending={testing}>
            {t('admin.backend.test')}
          </Button>
          <Button onPress={() => setOpen('edit')}>
            <span className={page.withIcon}>
              <Icon name="pencil" size={14} />
              {t('admin.backend.edit')}
            </span>
          </Button>
          {backend.encrypted && (
            <>
              <Button onPress={() => setOpen('show')}>{t('admin.backend.showKey')}</Button>
              {!backend.keyConfirmed && <Button onPress={() => setOpen('confirm')}>{t('admin.backend.confirmKey')}</Button>}
              <Button onPress={() => setOpen('enter')}>{t('admin.backend.enterKey')}</Button>
              <Button onPress={() => setOpen('history')}>{t('admin.backend.keyHistory')}</Button>
            </>
          )}
          {!backend.original && (
            <Button onPress={() => setOpen('remove')}>
              <span className={page.withIcon}>
                <Icon name="trash" size={14} />
                {t('admin.backend.remove')}
              </span>
            </Button>
          )}
        </div>
      </div>
      <p className={styles.line}>
        <StatusDot status={statusDot(backend)}>{statusWords(backend)}</StatusDot>
      </p>
      {!backend.offered && <p className={styles.line}>{t('admin.backends.notOffered')}</p>}
      <div className={styles.result} role="status">
        {tested !== undefined &&
          'problems' in tested &&
          (tested.problems.length === 0 ? (
            <p>{t('admin.storage.passed')}</p>
          ) : (
            <Problems lead={plural('admin.storage.testFound', tested.problems.length)} problems={tested.problems} />
          ))}
      </div>
      {tested !== undefined && 'failure' in tested && <Refusal failure={tested.failure} />}
      <dl className={clientStyles.facts}>
        <Fact term="admin.backend.kind">{kindOf(backend)}</Fact>
        <Fact term="admin.backend.where">{whereOf(backend)}</Fact>
        <Fact term="admin.backend.holds">{t('admin.backend.holdsWhat', { files: filesOf(backend.files), size: formatSize(backend.bytes) })}</Fact>
        <Fact term="admin.backend.encrypted">{t(backend.encrypted ? 'admin.backends.yes' : 'admin.backends.no')}</Fact>
        {backend.encrypted && (
          <>
            <Fact term="admin.backend.key">{t(backend.keyConfirmed ? 'admin.backend.keyConfirmed' : 'admin.backend.keyNotConfirmed')}</Fact>
            <Fact term="admin.backend.keyShown">{keyShownOf(backend)}</Fact>
          </>
        )}
        <Fact term="admin.backend.stores">{backend.stores.length === 0 ? t('admin.backend.noStores') : formatList(backend.stores)}</Fact>
        {backend.storesInTrash.length > 0 && <Fact term="admin.backend.storesInTrash">{formatList(backend.storesInTrash)}</Fact>}
      </dl>
      {backend.original && <p className={styles.about}>{t('admin.backend.original')}</p>}
      {open === 'edit' && <BackendDialog backend={backend} onClose={close} />}
      {open === 'remove' && kept(backend) && <RemoveKept backend={backend} onClose={close} />}
      {open === 'remove' && !kept(backend) && (
        <Confirm title={t('admin.removeBackend.title', { name: backend.name })} confirm={t('admin.removeBackend.confirm')} run={remove} onClose={close}>
          <p className={styles.line}>{t('admin.removeBackend.only')}</p>
          <p className={styles.line}>{t('admin.removeBackend.place')}</p>
          {backend.encrypted && <p className={styles.line}>{t('admin.removeBackend.key')}</p>}
        </Confirm>
      )}
      {open === 'show' && <RecoveryKeyDialog mode="show" backend={backend} onClose={close} />}
      {open === 'confirm' && <RecoveryKeyDialog mode="confirm" backend={backend} onClose={close} />}
      {open === 'enter' && <RecoveryKeyDialog mode="enter" backend={backend} onClose={close} />}
      {open === 'history' && <RecoveryKeyDialog mode="history" backend={backend} onClose={close} />}
    </div>
  )
}

/**
 * Whether something keeps the backend, so Remove would be refused: a Store that keeps its files on it, live or in the Trash
 * (whose restore needs them), or a file recorded or a copy held there. The server's refusal still answers a race, and a
 * backend still being added.
 */
function kept(backend: A['Backend']): boolean {
  return backend.stores.length > 0 || backend.storesInTrash.length > 0 || backend.inUse
}

/** Remove, while something keeps the backend: each thing that does and the way to free it, with no Remove to press */
function RemoveKept({ backend, onClose }: { backend: A['Backend']; onClose: () => void }) {
  const describedBy = useId()
  return (
    <Dialog title={t('admin.removeBackend.keptTitle', { name: backend.name })} isOpen aria-describedby={describedBy} onOpenChange={(isOpen) => !isOpen && onClose()}>
      {(close) => (
        <div className={clientStyles.form}>
          <div id={describedBy} className={clientStyles.form}>
            {backend.stores.length > 0 && <Names lead={plural('admin.removeBackend.stores', backend.stores.length)} names={backend.stores} />}
            {backend.storesInTrash.length > 0 && <Names lead={plural('admin.removeBackend.storesInTrash', backend.storesInTrash.length)} names={backend.storesInTrash} />}
            {backend.inUse && <p className={styles.line}>{t('admin.removeBackend.inUse')}</p>}
          </div>
          <div className={clientStyles.buttons}>
            <Button variant="default" onPress={close}>
              {t('admin.removeBackend.close')}
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

/** A sentence, then the Stores it names, each drawn as text */
function Names({ lead, names }: { lead: string; names: string[] }) {
  return (
    <div>
      <p className={styles.line}>{lead}</p>
      <ul className={styles.names}>
        {/* Two Stores in the Trash can share a name, so by place */}
        {names.map((name, index) => (
          <li key={index}>{name}</li>
        ))}
      </ul>
    </div>
  )
}

/** No backend at the address: in the app's words, with the way to the Backends list */
function BackendNotFound() {
  return (
    <div className={pageStyles.view}>
      <h1 className={pageStyles.title}>{t('admin.backend.notFound.title')}</h1>
      <p className={pageStyles.line}>{t('admin.backend.notFound.message')}</p>
      <Link to={routes.backends} className={pageStyles.link}>
        {t('admin.backend.notFound.backends')}
      </Link>
    </div>
  )
}
