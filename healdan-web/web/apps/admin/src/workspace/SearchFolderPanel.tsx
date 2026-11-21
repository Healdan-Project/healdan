// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, idFrom, useApi } from '@healdan/shell'
import { Button, CheckField, SelectField, TextField, toast } from '@healdan/ui'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { useEditState } from './edits'
import { useFolderProperties, useSearchFolder, workspaceChanged, workspaceKeys } from './queries'
import type { WorkspaceNode } from './tree'
import styles from './Workspace.module.css'

type A = adminComponents['schemas']
type Rule = A['SearchFolderRule']

/** A rule as its fields hold it, the days as typed and no Class as empty */
export interface RuleDraft {
  days: string
  onlyMine: boolean
  kind: Rule['kind']
  docClass: string
  docSubclass: string
}

/** A new search folder's rule: everything changed in the last 30 days, by anyone */
export const NEW_RULE: RuleDraft = { days: '30', onlyMine: false, kind: 'all', docClass: '', docSubclass: '' }

export function draftOfRule(rule: Rule): RuleDraft {
  return { days: String(rule.days), onlyMine: rule.onlyMine, kind: rule.kind, docClass: rule.docClass ?? '', docSubclass: rule.docSubclass ?? '' }
}

/** The days as a whole number from 1, or undefined */
function daysOf(text: string): number | undefined {
  const days = idFrom(text.trim())
  return days !== undefined && days >= 1 ? days : undefined
}

/** The rule the fields say, every member given, or undefined while the days aren't a whole number from 1 */
export function ruleOf(draft: RuleDraft): Rule | undefined {
  const days = daysOf(draft.days)
  if (days === undefined) return undefined
  const docClass = draft.docClass.trim() === '' ? null : draft.docClass.trim()
  return { days, onlyMine: draft.onlyMine, kind: draft.kind, docClass, docSubclass: docClass === null || draft.docSubclass.trim() === '' ? null : draft.docSubclass.trim() }
}

/** The Classes and Subclasses a rule can name: the profile's, as a workspace's top folder lists them; none asked while not `enabled` */
export function useClassOptions(rootFolderId: number, enabled = true) {
  const properties = useFolderProperties(rootFolderId, enabled).data?.properties ?? []
  return {
    classes: properties.find((one) => one.name === 'docclass')?.options ?? undefined,
    subclasses: properties.find((one) => one.name === 'docsubclass')?.options ?? undefined,
  }
}

export interface RuleFieldsProps {
  draft: RuleDraft
  onChange: (draft: RuleDraft) => void
  classes: A['PropertyOption'][] | undefined
  subclasses: A['PropertyOption'][] | undefined
}

/**
 * A search folder's rule as fields (spec 8.4): the window in days, whether only the changes of whoever opens it count,
 * the kind of document, and a Class and a Subclass, the Subclass only once there's a Class and only among its own.
 */
export function RuleFields({ draft, onChange, classes, subclasses }: RuleFieldsProps) {
  const set = (change: Partial<RuleDraft>) => onChange({ ...draft, ...change })
  const daysError = draft.days.trim() !== '' && daysOf(draft.days) === undefined ? t('admin.searchFolder.daysInvalid') : undefined
  const kinds: Rule['kind'][] = ['all', 'documents', 'emails']
  const within = (subclasses ?? []).filter((option) => option.class === null || option.class === draft.docClass)
  return (
    <>
      <TextField label={t('admin.searchFolder.days')} inputMode="numeric" required value={draft.days} error={daysError} onChange={(days) => set({ days })} />
      <CheckField label={t('admin.searchFolder.onlyMine')} checked={draft.onlyMine} onChange={(onlyMine) => set({ onlyMine })} />
      <SelectField
        label={t('admin.searchFolder.kind')}
        value={draft.kind}
        options={kinds.map((kind) => ({ value: kind, label: t(`admin.searchFolder.kind.${kind}`) }))}
        onChange={(kind) => set({ kind: kind as Rule['kind'] })}
      />
      {classes ? (
        <SelectField
          label={t('admin.searchFolder.class')}
          value={draft.docClass}
          empty={t('admin.searchFolder.any')}
          options={classes.map((option) => ({ value: option.value, label: option.label }))}
          onChange={(docClass) => set({ docClass, docSubclass: (subclasses ?? []).some((one) => one.value === draft.docSubclass && one.class === docClass) ? draft.docSubclass : '' })}
        />
      ) : (
        <TextField label={t('admin.searchFolder.class')} value={draft.docClass} onChange={(docClass) => set({ docClass })} />
      )}
      {draft.docClass.trim() !== '' &&
        (subclasses ? (
          <SelectField
            label={t('admin.searchFolder.subclass')}
            value={draft.docSubclass}
            empty={t('admin.searchFolder.any')}
            options={within.map((option) => ({ value: option.value, label: option.label }))}
            onChange={(docSubclass) => set({ docSubclass })}
          />
        ) : (
          <TextField label={t('admin.searchFolder.subclass')} value={draft.docSubclass} onChange={(docSubclass) => set({ docSubclass })} />
        ))}
    </>
  )
}

export interface SearchFolderPanelProps {
  node: WorkspaceNode
  root: WorkspaceNode
  kind: 'matter' | 'template'
}

/**
 * A search folder's rule, for editing (spec 8.4), in place of the values a folder sets: saved whole, as the route
 * takes it. Its name changes as a folder's does (P2-R15). Problems are said before Save; the server's refusal beside it.
 */
export function SearchFolderPanel({ node, root, kind }: SearchFolderPanelProps) {
  const headingId = useId()
  const asked = useSearchFolder(node.id)
  return (
    <section aria-labelledby={headingId} className={styles.section}>
      <h3 id={headingId} className={styles.sectionTitle}>
        {t('admin.searchFolder.title')}
      </h3>
      <p className={styles.note}>{t(kind === 'template' ? 'admin.searchFolder.template' : 'admin.searchFolder.about')}</p>
      {asked.isError ? (
        <ErrorPanel error={asked.error} onRetry={() => void asked.refetch()} />
      ) : asked.data === undefined ? (
        <p className={styles.note}>{t('common.loading')}</p>
      ) : (
        <RuleForm folder={asked.data} node={node} root={root} kind={kind} />
      )}
    </section>
  )
}

function RuleForm({ folder, node, root, kind }: { folder: A['SearchFolder'] } & SearchFolderPanelProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const { classes, subclasses } = useClassOptions(root.id)
  const initial = draftOfRule(folder.rule)
  const [draft, setDraft] = useState<RuleDraft | null>(null)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const held = useRef<HTMLFieldSetElement>(null)
  const saveButton = useRef<HTMLButtonElement>(null)
  const shown = draft ?? initial
  const rule = ruleOf(shown)
  const dirty = draft !== null && JSON.stringify(ruleOf(draft) ?? draft) !== JSON.stringify(ruleOf(initial))
  useEditState('rule', dirty, busy)

  async function save() {
    if (rule === undefined) return
    // The fields are held while it saves, so a field Enter saved from would drop the focus: it goes to Save Rule
    if (held.current?.contains(document.activeElement)) saveButton.current?.focus()
    setBusy(true)
    setFailure(undefined)
    try {
      const answer = await api.send<A['SearchFolder']>('PATCH', apiPath`/v1/admin/search-folders/${node.id}`, { rule } satisfies A['SearchFolderChange'])
      // A read still on its way began before the save, so it's called off rather than land after it
      await queryClient.cancelQueries({ queryKey: workspaceKeys.searchFolder(node.id), exact: true })
      queryClient.setQueryData(workspaceKeys.searchFolder(node.id), answer)
      setDraft(null)
      workspaceChanged(queryClient, root.id, kind, workspaceKeys.searchFolder(node.id))
      toast(t('admin.searchFolder.saved', { name: node.name }))
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className={styles.fields}
      onSubmit={(event) => {
        event.preventDefault()
        if (dirty && rule && !busy) void save()
      }}
    >
      {/* Held while the save is on its way, so nothing changed meanwhile is dropped under its Saved */}
      <fieldset ref={held} disabled={busy} className={styles.hold}>
        <RuleFields
          draft={shown}
          classes={classes}
          subclasses={subclasses}
          onChange={(next) => {
            setDraft(next)
            setFailure(undefined)
          }}
        />
      </fieldset>
      {failure !== undefined && (
        <p className={styles.problem} role="alert">
          {refusalFor(failure)}
        </p>
      )}
      <div className={styles.buttons}>
        <Button ref={saveButton} type="submit" variant="default" isDisabled={!dirty || rule === undefined} isPending={busy}>
          {t('admin.searchFolder.save')}
        </Button>
      </div>
    </form>
  )
}
