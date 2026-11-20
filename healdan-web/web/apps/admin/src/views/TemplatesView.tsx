// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom } from '@healdan/shell'
import { Button, Icon, Table, type TableColumn, type TableRow } from '@healdan/ui'
import { templateHref } from '../addresses'
import { TemplateDialog } from '../templates/TemplateDialog'
import { t } from '../strings'
import { useTemplates } from '../workspace/queries'
import styles from '../clients/Clients.module.css'

type A = adminComponents['schemas']

interface TemplateRow extends TableRow {
  template: A['TemplateInfo']
}

const counts = new Intl.NumberFormat('en-US')

/** The list's columns, built when it first draws, so the firm's own word for a matter heads the last */
function templateColumns(): TableColumn<TemplateRow>[] {
  return [
    {
      id: 'name',
      name: t('admin.templates.column.name'),
      isRowHeader: true,
      width: '3fr',
      minWidth: 160,
      cell: ({ template }) => (
        <span className={styles.number}>
          <Icon name="template" />
          {template.name}
        </span>
      ),
    },
    { id: 'folders', name: t('admin.templates.column.folders'), align: 'end', width: 120, cell: ({ template }) => counts.format(template.folders) },
    { id: 'matters', name: t('admin.templates.column.matters'), align: 'end', width: 200, cell: ({ template }) => counts.format(template.matters) },
  ]
}

/**
 * Workspace Templates, at /admin/templates (spec 8.5): each template with its folders and the matters made from it, a
 * short list in the server's order, by name. New Template makes one, empty or a copy, and opens its page; a row opens
 * its page. A fresh install's empty list says a template is needed before the first matter.
 */
export function TemplatesView() {
  const navigate = useNavigate()
  const headingId = useId()
  const templates = useTemplates()
  const [making, setMaking] = useState(false)
  const columns = useMemo(() => templateColumns(), [])
  const rows = useMemo(() => (templates.data ?? []).map((template): TemplateRow => ({ id: String(template.id), textValue: template.name, template })), [templates.data])
  const empty = templates.isPending ? (
    t('common.loading')
  ) : (
    <div className={styles.form}>
      <p className={styles.empty}>{t('admin.templates.none')}</p>
      <p className={styles.empty}>{t('admin.templates.noneLine')}</p>
    </div>
  )

  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 id={headingId} className={styles.title}>
          {t('admin.templates.title')}
        </h1>
        <div className={styles.actions}>
          <Button variant="default" onPress={() => setMaking(true)}>
            <Icon name="plus" size={14} /> {t('admin.templates.new')}
          </Button>
        </div>
      </div>
      {templates.isError ? (
        <ErrorPanel error={templates.error} onRetry={() => void templates.refetch()} />
      ) : (
        <Table
          aria-labelledby={headingId}
          columns={columns}
          rows={rows}
          selectionMode="none"
          onOpen={(key) => {
            const id = idFrom(key)
            if (id !== undefined) void navigate({ href: templateHref(id) })
          }}
          empty={empty}
        />
      )}
      {making && <TemplateDialog onSaved={(made) => void navigate({ href: templateHref(made.id) })} onClose={() => setMaking(false)} />}
    </div>
  )
}
