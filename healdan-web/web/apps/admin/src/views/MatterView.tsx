// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link, useNavigate } from '@tanstack/react-router'
import { useId, useState, type ReactNode } from 'react'
import { ApiError, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, formatDateTime, formatSize, Icon, Tabs } from '@healdan/ui'
import { routes, userAppFolderHref } from '../addresses'
import { DeleteMatterDialog } from '../matters/DeleteMatterDialog'
import { EditMatterDialog } from '../matters/EditMatterDialog'
import { MoveMatterDialog } from '../matters/MoveMatterDialog'
import { plural, t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'
import { useMatter } from '../workspace/queries'
import { WorkspaceEditor } from '../workspace/WorkspaceEditor'
import styles from '../clients/Clients.module.css'
import page from '../matters/MatterPage.module.css'
import pageStyles from './views.module.css'

type A = adminComponents['schemas']

/** A matter's page's tabs (P2-R5): Reindex's and Activity's come with their phases */
export type MatterTab = 'overview' | 'workspace'

/** What a matter's page's address holds: the tab open, when it isn't the Overview */
export interface MatterSearch {
  tab?: Exclude<MatterTab, 'overview'>
}

/** The address's parameters the page reads; a tab it doesn't know opens the Overview */
export function matterSearch(search: Record<string, unknown>): MatterSearch {
  return { tab: search.tab === 'workspace' ? 'workspace' : undefined }
}

/**
 * A matter's page, at /admin/matters/{id} (spec 8.3, P2-R5): its title, client, Store, owner, template and totals; Edit,
 * Move to Another Store, Open in Healdan and Delete; and the Overview and Workspace tabs, the tab in the address. An
 * address that names no matter, or one in the Trash, says so in the app's words.
 */
export function MatterView({ id, search }: { id: string; search: MatterSearch }) {
  const matterId = idFrom(id)
  return matterId === undefined ? <MatterNotFound /> : <MatterPage id={matterId} tab={search.tab ?? 'overview'} />
}

function MatterPage({ id, tab }: { id: number; tab: MatterTab }) {
  const asked = useMatter(id)
  if (asked.isError) {
    if (asked.error instanceof ApiError && asked.error.status === 404) return <MatterNotFound />
    return (
      <div className={page.page}>
        <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
      </div>
    )
  }
  if (asked.data === undefined) {
    return (
      <div className={page.page}>
        <p className={page.muted}>{t('common.loading')}</p>
      </div>
    )
  }
  return <Matter matter={asked.data} tab={tab} />
}

/** "Saltmarsh Shipping Ltd · Client Work · owner Tom Reeve · from template Litigation · 412 documents, 4.6 GB" */
function summaryOf(matter: A['Matter']): string {
  const parts = [
    matter.client.name,
    matter.store.name,
    matter.owner ? t('admin.matter.ownedBy', { name: matter.owner.name }) : t('admin.matter.noOwner'),
    matter.template && t('admin.matter.fromTemplate', { name: matter.template.name }),
    matter.documents !== null && matter.bytes !== null && t('admin.matter.size', { documents: plural('documents', matter.documents), size: formatSize(matter.bytes) }),
  ]
  return parts.filter((part): part is string => typeof part === 'string').join(' · ')
}

type Dialog = 'edit' | 'move' | 'delete'

function Matter({ matter, tab }: { matter: A['Matter']; tab: MatterTab }) {
  const navigate = useNavigate({ from: routes.matter })
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const close = () => setDialog(null)
  // Once opened, the Workspace stays mounted behind the Overview, with what's being changed in it and the answer to a
  // save still on its way, as the User app's open properties form does; the tree isn't asked for until it's opened
  const [workspaceOpened, setWorkspaceOpened] = useState(tab === 'workspace')
  if (tab === 'workspace' && !workspaceOpened) setWorkspaceOpened(true)

  function choose(next: string) {
    // A tab isn't a step of its own, as an order isn't
    void navigate({ to: routes.matter, params: { id: String(matter.id) }, search: next === 'workspace' ? { tab: 'workspace' } : {}, replace: true })
  }

  return (
    <div className={page.page}>
      <nav aria-label={t('admin.crumbs.label')} className={styles.crumbs}>
        <ol>
          <li>
            <Link to={routes.matters} className={styles.crumbLink}>
              {t('admin.matters.title')}
            </Link>
          </li>
          <li aria-current="page">{matter.number}</li>
        </ol>
      </nav>
      <div className={styles.head}>
        <h1 className={styles.title}>{t('admin.matter.title', { number: matter.number, name: matter.name })}</h1>
        <div className={styles.actions}>
          <Button onPress={() => setDialog('edit')}>
            <span className={page.withIcon}>
              <Icon name="pencil" size={14} />
              {t('admin.matter.edit')}
            </span>
          </Button>
          <Button onPress={() => setDialog('move')}>
            <span className={page.withIcon}>
              <Icon name="arrowRight" size={14} />
              {t('admin.matter.move')}
            </span>
          </Button>
          <a className={page.linkButton} href={userAppFolderHref(matter.folderId)}>
            <Icon name="arrowUpRight" size={14} />
            {t('admin.matter.open')}
          </a>
          <Button onPress={() => setDialog('delete')}>
            <span className={page.withIcon}>
              <Icon name="trash" size={14} />
              {t('admin.matter.delete')}
            </span>
          </Button>
        </div>
      </div>
      <p className={page.summary}>{summaryOf(matter)}</p>
      <Tabs
        label={t('admin.matter.tabs')}
        selected={tab}
        onSelect={choose}
        tabs={[
          { id: 'overview', label: t('admin.matter.tab.overview'), content: <Overview matter={matter} /> },
          { id: 'workspace', label: t('admin.matter.tab.workspace'), keepMounted: workspaceOpened, content: <WorkspaceEditor rootFolderId={matter.folderId} kind="matter" /> },
        ]}
      />
      {dialog === 'edit' && <EditMatterDialog matter={matter} onClose={close} />}
      {dialog === 'move' && <MoveMatterDialog matter={matter} onClose={close} />}
      {/* A change left in its workspace went with the matter, so the list opens without asking */}
      {dialog === 'delete' && <DeleteMatterDialog matter={matter} onDeleted={() => navigate({ to: routes.matters, ignoreBlocker: true })} onClose={close} />}
    </div>
  )
}

const counts = new Intl.NumberFormat('en-US')

function Fact({ term, children }: { term: AdminStringKey; children: ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{t(term)}</dt>
      <dd>{children}</dd>
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id}>
      <h2 id={id}>{title}</h2>
      {children}
    </section>
  )
}

/** The Overview (spec 8.3): the description, the facts, and what's stuck or failing in the matter */
function Overview({ matter }: { matter: A['Matter'] }) {
  // The figures come from the summary, which hasn't been worked out since the server started while they're null
  const pending = matter.documents === null
  const trouble = [
    matter.indexFailed > 0 && plural('admin.matter.indexFailed', matter.indexFailed),
    matter.previewsGivenUp > 0 && plural('admin.matter.previewsGivenUp', matter.previewsGivenUp),
  ].filter((line): line is string => typeof line === 'string')
  return (
    <div className={page.overview}>
      <Section title={t('admin.matter.description')}>
        <p className={matter.description ? undefined : page.muted}>{matter.description ?? t('admin.matter.noDescription')}</p>
      </Section>
      <Section title={t('admin.matter.facts')}>
        <dl className={styles.facts}>
          <Fact term="admin.matter.client">
            <Link to={routes.client} params={{ id: String(matter.client.id) }} className={page.link}>
              {t('admin.client.label', { number: matter.client.number, name: matter.client.name })}
            </Link>
          </Fact>
          <Fact term="admin.matter.store">{matter.store.name}</Fact>
          <Fact term="admin.matter.owner">{matter.owner?.name ?? t('admin.matter.ownerGone')}</Fact>
          <Fact term="admin.matter.template">{matter.template?.name ?? t('admin.matter.noTemplate')}</Fact>
          <Fact term="admin.matter.documents">{pending ? t('admin.matter.pending') : counts.format(matter.documents ?? 0)}</Fact>
          <Fact term="admin.matter.bytes">{pending || matter.bytes === null ? t('admin.matter.pending') : formatSize(matter.bytes)}</Fact>
          <Fact term="admin.matter.lastActivity">
            {pending ? t('admin.matter.pending') : matter.lastActivity === null ? t('admin.matter.noActivity') : formatDateTime(matter.lastActivity)}
          </Fact>
        </dl>
        {matter.figuresAt !== null && <p className={page.muted}>{t('admin.matters.figuresAt', { when: formatDateTime(matter.figuresAt) })}</p>}
      </Section>
      <Section title={t('admin.matter.trouble')}>
        {trouble.length === 0 ? (
          <p className={page.muted}>{t('admin.matter.troubleNone')}</p>
        ) : (
          <ul>
            {trouble.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  )
}

/** No matter at the address: in the app's words, with the way to the Matters list */
function MatterNotFound() {
  return (
    <div className={pageStyles.view}>
      <h1 className={pageStyles.title}>{t('admin.matter.notFound.title')}</h1>
      <p className={pageStyles.line}>{t('admin.matter.notFound.message')}</p>
      <Link to={routes.matters} className={pageStyles.link}>
        {t('admin.matter.notFound.matters')}
      </Link>
    </div>
  )
}
