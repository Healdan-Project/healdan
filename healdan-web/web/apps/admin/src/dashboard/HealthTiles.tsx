// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { UseQueryResult } from '@tanstack/react-query'
import { useId } from 'react'
import type { adminComponents } from '@healdan/api'
import { StatusDot } from '@healdan/ui'
import type { DashboardAnswer } from '../queries'
import { ServerLink } from '../frame/ServerLink'
import { t } from '../strings'
import { adminEnUS, type AdminStringKey } from '../strings/en-US'
import { PartState } from './PartState'
import styles from './Dashboard.module.css'

type HealthTile = adminComponents['schemas']['HealthTile']

/** The tile's name, as the catalogue has it; a tile this app doesn't know yet (a newer server's) by its own name */
function nameOf(tile: HealthTile): string {
  const key = `admin.health.${tile.name}`
  return Object.hasOwn(adminEnUS, key) ? t(key as AdminStringKey) : tile.name
}

/** The tile's status in words; a status this app doesn't know yet (a newer server's) as the server names it */
function statusOf(tile: HealthTile): string {
  const key = `admin.status.${tile.status}`
  return Object.hasOwn(adminEnUS, key) ? t(key as AdminStringKey) : tile.status
}

/** How full, from 0 to 100, for a tile with a used and a total (the Disk and the Memory) */
function shareOf(tile: HealthTile): number | undefined {
  if (tile.problem != null || tile.used == null || tile.total == null || tile.total <= 0) return undefined
  return Math.min(100, Math.max(0, Math.round((tile.used / tile.total) * 100)))
}

function Tile({ tile }: { tile: HealthTile }) {
  const unreadable = tile.problem != null
  const share = shareOf(tile)
  return (
    <li className={styles.tile}>
      <div className={styles.tileTop}>
        <h3 className={styles.tileName}>
          {/* The name is the tile's link, which covers the tile, so the whole of it opens the screen behind it */}
          <ServerLink className={styles.tileLink} href={tile.link}>
            {nameOf(tile)}
          </ServerLink>
        </h3>
        <span data-status>
          <StatusDot status={tile.status}>{unreadable ? t('admin.status.unavailable') : statusOf(tile)}</StatusDot>
        </span>
      </div>
      <p className={unreadable || tile.status === 'attention' ? styles.tileCaution : styles.tileDetail} data-detail>
        {unreadable ? tile.problem : tile.detail}
      </p>
      {share !== undefined && (
        <div className={styles.track} aria-hidden="true">
          <div className={styles.fill} data-fill style={{ width: `${share}%` }} />
        </div>
      )}
    </li>
  )
}

/**
 * Server Health (spec 8.1): the ten tiles, in the server's order, each with its status in words and a line of detail, and
 * opening the screen behind it. A tile whose part couldn't be read shows why, and the other tiles are whole.
 */
export function HealthTiles({ dashboard }: { dashboard: UseQueryResult<DashboardAnswer> }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className={styles.part}>
      <h2 id={id} className={styles.partTitle}>
        {t('admin.health.title')}
      </h2>
      <PartState query={dashboard}>
        <ul className={styles.tiles}>
          {(dashboard.data?.health ?? []).map((tile) => (
            <Tile key={tile.name} tile={tile} />
          ))}
        </ul>
      </PartState>
    </section>
  )
}
