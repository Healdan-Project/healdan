// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import type { components } from '@healdan/api'
import { Icon, t } from '@healdan/ui'
import styles from './views.module.css'

type S = components['schemas']

// Where a folder is: its client, then its matter as client.matter – name, then each folder down to this one (spec
// section 6.1). The Store isn't shown: people find matters, not Stores. Healdan has no page of a client's, so the client
// is text; the folder you're in is text too, marked as the current page.
export function Breadcrumb({ folder }: { folder: S['FolderView'] }) {
  const top = folder.path.findIndex((step) => step.kind === 'matter')
  const matter = folder.matter
  // A Store is in no matter, so it has no breadcrumb
  if (top < 0 || !matter) return null
  const crumbs = [
    { key: 'client', text: matter.client.name, id: undefined },
    ...folder.path.slice(top).map((step) => ({ key: String(step.id), text: step.kind === 'matter' ? matter.display : step.name, id: step.id })),
  ]
  return (
    <nav aria-label={t('breadcrumb.label')} className={styles.breadcrumb}>
      <ol>
        {crumbs.map((crumb, i) => {
          const last = i === crumbs.length - 1
          return (
            <li key={crumb.key}>
              {i > 0 && <Icon name="chevronRight" size={12} />}
              {last ? (
                <span aria-current="page" className={styles.here}>
                  {crumb.text}
                </span>
              ) : crumb.id !== undefined ? (
                <Link to="/folders/$id" params={{ id: String(crumb.id) }}>
                  {crumb.text}
                </Link>
              ) : (
                <span>{crumb.text}</span>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
