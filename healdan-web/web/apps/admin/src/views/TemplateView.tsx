// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useId, useState } from 'react'
import { ApiError, apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom, useApi } from '@healdan/shell'
import { Button, Icon, toast } from '@healdan/ui'
import { routes } from '../addresses'
import { Confirm } from '../dialogs/Confirm'
import { TemplateDialog } from '../templates/TemplateDialog'
import { plural, t } from '../strings'
import { useTemplate, useTemplateProblems, workspaceKeys } from '../workspace/queries'
import { WorkspaceEditor } from '../workspace/WorkspaceEditor'
import styles from '../clients/Clients.module.css'
import page from '../matters/MatterPage.module.css'
import pageStyles from './views.module.css'

type A = adminComponents['schemas']

/**
 * A workspace template's page, at /admin/templates/{id} (spec 8.5): its name, Rename and Delete, its figures, that
 * changing it doesn't change matters already made from it, what New Matter would refuse in a matter made from it, as
 * warnings, and the Workspace Editor. An address that names no template says so in the app's words.
 */
export function TemplateView({ id }: { id: string }) {
  const templateId = idFrom(id)
  return templateId === undefined ? <TemplateNotFound /> : <TemplatePage id={templateId} />
}

function TemplatePage({ id }: { id: number }) {
  const asked = useTemplate(id)
  if (asked.isError) {
    if (asked.error instanceof ApiError && asked.error.status === 404) return <TemplateNotFound />
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
  return <Template template={asked.data} />
}

const counts = new Intl.NumberFormat('en-US')

function Template({ template }: { template: A['TemplateInfo'] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const problemsId = useId()
  const problems = useTemplateProblems(template.id).data ?? []
  const [dialog, setDialog] = useState<'rename' | 'delete' | null>(null)

  async function remove() {
    // Its Trash item isn't offered back: the Trash restores into a Store only, and answers a template 409 place-gone
    await api.send<A['DeletedFolder']>('DELETE', apiPath`/v1/admin/templates/${template.id}`)
    toast(t('admin.deleteTemplate.done', { name: template.name }))
    // Off its page first, so nothing asks for it again; a change left in its workspace went with it, so without asking
    await navigate({ to: routes.templates, ignoreBlocker: true })
    queryClient.removeQueries({ queryKey: workspaceKeys.template(template.id) })
    void queryClient.invalidateQueries({ queryKey: workspaceKeys.templates })
  }

  return (
    <div className={page.page}>
      <nav aria-label={t('admin.crumbs.label')} className={styles.crumbs}>
        <ol>
          <li>
            <Link to={routes.templates} className={styles.crumbLink}>
              {t('admin.templates.title')}
            </Link>
          </li>
          <li aria-current="page">{template.name}</li>
        </ol>
      </nav>
      <div className={styles.head}>
        <h1 className={styles.title}>{template.name}</h1>
        <div className={styles.actions}>
          <Button onPress={() => setDialog('rename')}>
            <span className={page.withIcon}>
              <Icon name="pencil" size={14} />
              {t('admin.template.rename')}
            </span>
          </Button>
          <Button onPress={() => setDialog('delete')}>
            <span className={page.withIcon}>
              <Icon name="trash" size={14} />
              {t('admin.template.delete')}
            </span>
          </Button>
        </div>
      </div>
      <dl className={styles.facts}>
        <div className={styles.fact}>
          <dt>{t('admin.template.folders')}</dt>
          <dd>{counts.format(template.folders)}</dd>
        </div>
        <div className={styles.fact}>
          <dt>{t('admin.template.matters')}</dt>
          <dd>{counts.format(template.matters)}</dd>
        </div>
      </dl>
      <p className={page.summary}>{t('admin.template.unchanged')}</p>
      {problems.length > 0 && (
        <section aria-labelledby={problemsId} className={page.problems}>
          <h2 id={problemsId}>
            <Icon name="alertTriangle" />
            {t('admin.template.problems')}
          </h2>
          <ul>
            {problems.map((problem, index) => (
              <li key={`${problem.code}-${problem.folderId}-${problem.storeId}-${problem.groupId}-${index}`}>{problem.message}</li>
            ))}
          </ul>
          <p>{t('admin.template.problemsLine')}</p>
        </section>
      )}
      <WorkspaceEditor rootFolderId={template.id} kind="template" />
      {dialog === 'rename' && <TemplateDialog template={template} onClose={() => setDialog(null)} />}
      {dialog === 'delete' && (
        <Confirm title={t('admin.deleteTemplate.title', { name: template.name })} confirm={t('admin.deleteTemplate.confirm')} run={remove} onClose={() => setDialog(null)}>
          {template.folders > 0 && <p className={page.line}>{t('admin.deleteTemplate.holds', { folders: plural('folders', template.folders) })}</p>}
          <p className={page.line}>{t('admin.deleteTemplate.unchanged')}</p>
        </Confirm>
      )}
    </div>
  )
}

/** No template at the address: in the app's words, with the way to the list */
function TemplateNotFound() {
  return (
    <div className={pageStyles.view}>
      <h1 className={pageStyles.title}>{t('admin.template.notFound.title')}</h1>
      <p className={pageStyles.line}>{t('admin.template.notFound.message')}</p>
      <Link to={routes.templates} className={pageStyles.link}>
        {t('admin.template.notFound.templates')}
      </Link>
    </div>
  )
}
