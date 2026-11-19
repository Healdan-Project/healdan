// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { UseQueryResult } from '@tanstack/react-query'
import { useId, type ReactNode } from 'react'
import type { adminComponents } from '@healdan/api'
import { formatDateTime, formatSize, plural as sharedPlural } from '@healdan/ui'
import type { UsageAnswer } from '../queries'
import { plural, t } from '../strings'
import { BarChart } from './BarChart'
import { PartState } from './PartState'
import styles from './Dashboard.module.css'

type A = adminComponents['schemas']

const counts = new Intl.NumberFormat('en-US')

function Total({ value, label, change, also }: { value: string; label: string; change: string; also?: string }) {
  return (
    <li className={styles.total}>
      <span className={styles.totalValue}>{value}</span>
      <span className={styles.totalLabel}>{label}</span>
      <span className={styles.secondary}>{change}</span>
      {also && <span className={styles.secondary}>{also}</span>}
    </li>
  )
}

/** The five totals, each with its recent change, and people with who's signed in now: one the server couldn't give is left out */
function Totals({ usage }: { usage: UsageAnswer }) {
  const { documents, storage, matters, clients, people } = usage
  return (
    <ul className={styles.totals} aria-label={t('admin.usage.totals')}>
      {documents && <Total value={counts.format(documents.total)} label={t('admin.usage.documents')} change={plural('admin.usage.last7', documents.last7Days)} />}
      {storage && <Total value={formatSize(storage.total)} label={t('admin.usage.storage')} change={t('admin.usage.sizeLast7', { size: formatSize(storage.last7Days) })} />}
      {matters && <Total value={counts.format(matters.total)} label={t('admin.usage.matters')} change={plural('admin.usage.last30', matters.last30Days)} />}
      {clients && <Total value={counts.format(clients.total)} label={t('admin.usage.clients')} change={plural('admin.usage.last30', clients.last30Days)} />}
      {people && (
        <Total
          value={counts.format(people.total)}
          label={t('admin.usage.people')}
          change={plural('admin.usage.last30', people.last30Days)}
          also={plural('admin.usage.signedIn', usage.signedIn)}
        />
      )}
    </ul>
  )
}

/** A card of the Usage part, with its title, which names the list it holds (`children` is given the title's id) */
function Card({ title, children }: { title: string; children: (titleId: string) => ReactNode }) {
  const id = useId()
  return (
    <div className={styles.card}>
      <h3 id={id} className={styles.cardTitle}>
        {title}
      </h3>
      {children(id)}
    </div>
  )
}

function backendLine(store: A['StoreUsage']): string {
  const size = formatSize(store.bytes)
  const where = store.backend === null ? size : t(store.backend.encrypted ? 'admin.usage.storeOnEncrypted' : 'admin.usage.storeOn', { size, backend: store.backend.name })
  return `${where} · ${sharedPlural('documents', store.documents)}`
}

function Stores({ stores, titleId }: { stores: A['StoreUsage'][]; titleId: string }) {
  if (stores.length === 0) return <p className={styles.secondary}>{t('common.none')}</p>
  // Each Store's bar is its share of all the Stores' bytes
  const all = stores.reduce((sum, store) => sum + store.bytes, 0)
  return (
    <ul className={styles.stores} aria-labelledby={titleId}>
      {stores.map((store) => (
        <li key={store.id} className={styles.store}>
          <span className={styles.storeName}>{store.name}</span>
          <span className={styles.storeLine}>{backendLine(store)}</span>
          <span className={styles.track} aria-hidden="true">
            <span className={styles.fill} data-fill style={{ width: `${all > 0 ? Math.round((store.bytes / all) * 100) : 0}%` }} />
          </span>
        </li>
      ))}
    </ul>
  )
}

function Busiest({ matters, titleId }: { matters: A['BusyMatter'][]; titleId: string }) {
  if (matters.length === 0) return <p className={styles.secondary}>{t('admin.usage.noneBusy')}</p>
  return (
    <ul className={styles.busiest} aria-labelledby={titleId}>
      {matters.map((matter) => (
        <li key={matter.id} className={styles.busy}>
          <span className={styles.busyName}>{t('matter.label', { number: matter.number, name: matter.name })}</span>
          <span className={styles.secondary}>{plural('admin.usage.filed', matter.filed)}</span>
        </li>
      ))}
    </ul>
  )
}

/** When the figures were worked out, or that they couldn't be since then, or at all */
function asOf(usage: UsageAnswer): ReactNode {
  if (usage.failedAt != null) {
    return (
      <p className={styles.caution}>{usage.computedAt === null ? t('admin.usage.failed') : t('admin.usage.failedSince', { when: formatDateTime(usage.computedAt) })}</p>
    )
  }
  return usage.computedAt === null ? null : <p className={styles.secondary}>{t('admin.usage.asOf', { when: formatDateTime(usage.computedAt) })}</p>
}

/**
 * Usage (spec 8.1): totals with their recent change, the documents filed in the last 30 days, Storage by Store, the
 * busiest matters this week, and who's signed in now. The server works it out at most every 15 minutes, never for a
 * request, and the part says when; until it has once since a start, it says so, with the signed-in count it counts at
 * each request. When the last try failed it keeps the last figures and says since when they couldn't be worked out.
 */
export function Usage({ usage }: { usage: UseQueryResult<UsageAnswer> }) {
  const id = useId()
  const data = usage.data
  return (
    <section aria-labelledby={id} className={styles.part}>
      <div className={styles.partHead}>
        <h2 id={id} className={styles.partTitle}>
          {t('admin.usage.title')}
        </h2>
        {data && asOf(data)}
      </div>
      <PartState query={usage}>
        {data && data.computedAt === null ? (
          <>
            {data.failedAt == null && <p className={styles.secondary}>{t('admin.usage.pending')}</p>}
            <p className={styles.secondary}>{plural('admin.usage.signedIn', data.signedIn)}</p>
          </>
        ) : (
          data && (
            <>
              <Totals usage={data} />
              <div className={styles.cards}>
                <Card title={t('admin.usage.filedChart')}>
                  {() => <BarChart label={t('admin.usage.filedChart')} days={data.filedPerDay} />}
                </Card>
                <Card title={t('admin.usage.stores')}>{(titleId) => <Stores stores={data.stores} titleId={titleId} />}</Card>
                <Card title={t('admin.usage.busiest')}>{(titleId) => <Busiest matters={data.busiestMatters} titleId={titleId} />}</Card>
              </div>
            </>
          )
        )}
      </PartState>
    </section>
  )
}
