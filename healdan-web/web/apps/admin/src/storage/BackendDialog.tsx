// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { ApiError, apiPath, type adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, CheckField, Dialog, RadioChoices, TextField, toast } from '@healdan/ui'
import { Problems } from '../forms/Problems'
import { Refusal } from '../forms/Refusal'
import { plural, t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { backendsChanged } from './queries'
import { kindOf } from './text'
import styles from './Storage.module.css'

type A = adminComponents['schemas']
type Kind = A['Backend']['kind']

/** The longest name the server keeps for a backend */
const NAME_MAX = 100

/** What the window holds: a folder's path, or an S3 bucket's settings, and the secret only as typed */
interface Draft {
  kind: Kind
  name: string
  path: string
  endpoint: string
  region: string
  bucket: string
  prefix: string
  pathStyle: boolean
  accessKey: string
  secret: string
  encrypted: boolean
}

/** The fields the window shows, which a `422 validation` can name beside them */
const SHOWN = ['name', 'path', 'endpoint', 'region', 'bucket', 'prefix', 'accessKey', 'secret'] as const

/** What the window says above its buttons, from the last Test, Add or Save */
type Outcome =
  /** Test, pressed: it passed, or what it found */
  | { kind: 'tested'; problems: string[] }
  /** Add tests first, and adds nothing while Test fails */
  | { kind: 'not-added'; problems: string[] }
  /** Save tests first, and asks whether to save settings that fail */
  | { kind: 'anyway'; problems: string[] }
  /** Healdan's secret key file is missing as an S3 secret is typed: what a new key costs, and Make a New Key */
  | { kind: 'new-key'; sentence: string; sealed: number }
  | { kind: 'refused'; error: unknown }

function draftOf(backend: A['Backend'] | undefined): Draft {
  return {
    kind: backend?.kind ?? 's3',
    name: backend?.name ?? '',
    path: backend?.path ?? '',
    endpoint: backend?.endpoint ?? '',
    region: backend?.region ?? '',
    bucket: backend?.bucket ?? '',
    prefix: backend?.prefix ?? '',
    pathStyle: backend?.pathStyle ?? false,
    accessKey: backend?.accessKey ?? '',
    secret: '',
    // The kind's own choice, on for S3, until the person chooses
    encrypted: backend?.encrypted ?? true,
  }
}

/** Null for nothing typed, as the routes read a blank member */
const orNull = (text: string) => (text.trim() === '' ? null : text.trim())

/** "Test found 2 problems:" and each, as text */
function TestFound({ problems }: { problems: string[] }) {
  return <Problems lead={plural('admin.storage.testFound', problems.length)} problems={problems} />
}

/** A setting shown as text, which can't change here, and why */
function Fixed({ label, value, why }: { label: string; value: string; why?: string }) {
  return (
    <dl className={styles.fixed}>
      <dt>{label}</dt>
      <dd>
        <span>{value}</span>
        {why !== undefined && <span className={styles.note}>{why}</span>}
      </dd>
    </dl>
  )
}

export interface BackendDialogProps {
  /** The backend to edit; without one, the dialog adds a backend */
  backend?: A['Backend']
  /**
   * The backend just added and, for an encrypted one, its recovery key, which the caller shows at once
   * (RecoveryKeyDialog) and holds only while it does; just before the dialog closes
   */
  onAdded?: (added: A['AddedBackend']) => void
  /**
   * The dialog closes: Cancel, Esc, a click outside, or once added or saved. The caller stops drawing it. While a change
   * is on its way it can't be closed, as the server may make it anyway.
   */
  onClose: () => void
}

/**
 * Add a Storage Backend, and a backend's Edit (spec 8.6), with the Storage tab's rules. The add chooses the kind, a
 * local folder or an S3 bucket, and whether its files are encrypted, on for S3 to begin with; neither changes after.
 * Original takes only its name, and a bucket, prefix or path shows as text once files are there or a Store, live or in
 * the Trash, keeps its files on the backend. The S3 secret is never shown: its field starts empty, and only a secret typed in it replaces
 * the one stored.
 *
 * **Test** tries the settings as typed and says what it found, saving nothing. **Add** tests them first and adds
 * nothing while Test fails, saying why; **Save** tests them first and, if Test finds problems, asks whether to save
 * anyway, as a backend can be renamed during an outage. The settings are held while Test is on its way, so its answer
 * is always about the settings shown. When Healdan's secret key file is missing as an S3 secret is
 * typed, it says the server's sentence and what a new key costs, and **Make a New Key** sends the change again with
 * `newKey`. While the change is on its way, Cancel is greyed out and Esc and a click outside do nothing. The secret lives
 * only in this window's state, and goes only into the requests' bodies.
 */
export function BackendDialog({ backend, onAdded, onClose }: BackendDialogProps) {
  const isNew = backend === undefined
  const original = backend?.original === true
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [draft, setDraft] = useState<Draft>(() => draftOf(backend))
  const [outcome, setOutcome] = useState<Outcome>()
  const [testing, setTesting] = useState(false)
  const [busy, setBusy] = useState(false)

  const s3 = draft.kind === 's3'
  // Why the bucket, prefix and path can't change here, as the Storage tab says it, and while a Store in the Trash keeps its
  // files here, as the server refuses the change then too (P3-R38); none when they can
  const placeFixed =
    backend === undefined || original
      ? undefined
      : backend.inUse
        ? t('admin.backendDialog.fixedFiles')
        : backend.stores.length > 0
          ? t('admin.backendDialog.fixedStores')
          : backend.storesInTrash.length > 0
            ? t('admin.backendDialog.fixedTrash')
            : undefined

  function set<K extends keyof Draft>(field: K, value: Draft[K]) {
    setDraft((before) => {
      const next = { ...before, [field]: value }
      // A kind chosen takes its own encryption, as the Storage tab's does
      if (field === 'kind') next.encrypted = value === 's3'
      return next
    })
    // A change takes the last answer away
    setOutcome(undefined)
  }

  /** The kind's own settings, as typed, or as stored where they can't change here; never the other kind's */
  function settings() {
    const typedPlace = !original && placeFixed === undefined
    if (!s3) return { path: typedPlace ? draft.path.trim() : (backend?.path ?? '') }
    return {
      endpoint: draft.endpoint.trim(),
      region: draft.region.trim(),
      bucket: typedPlace ? draft.bucket.trim() : (backend?.bucket ?? ''),
      prefix: typedPlace ? orNull(draft.prefix) : (backend?.prefix ?? null),
      pathStyle: draft.pathStyle,
      accessKey: draft.accessKey.trim(),
      secret: draft.secret.trim() === '' ? null : draft.secret,
    }
  }

  /** What Test tries: the settings as the window holds them, with the backend being edited's id */
  function trial(): A['BackendTrial'] {
    return { ...(backend !== undefined ? { id: backend.id } : {}), kind: draft.kind, name: draft.name.trim(), ...settings(), encrypted: isNew ? draft.encrypted : backend.encrypted }
  }

  function addition(newKey: boolean): A['NewBackend'] {
    return { kind: draft.kind, name: draft.name.trim(), ...settings(), encrypted: draft.encrypted, ...(newKey ? { newKey: true } : {}) }
  }

  /** What changed from the backend as stored: only the kind's own members, only Original's name, and the secret only typed */
  function change(newKey: boolean): A['BackendChange'] {
    if (backend === undefined) return {}
    const body: A['BackendChange'] = {}
    const name = draft.name.trim()
    if (name !== backend.name) body.name = name
    if (original) return body
    const typedPlace = placeFixed === undefined
    if (!s3) {
      if (typedPlace && draft.path.trim() !== (backend.path ?? '')) body.path = draft.path.trim()
      return body
    }
    if (draft.endpoint.trim() !== (backend.endpoint ?? '')) body.endpoint = draft.endpoint.trim()
    if (draft.region.trim() !== (backend.region ?? '')) body.region = draft.region.trim()
    if (typedPlace && draft.bucket.trim() !== (backend.bucket ?? '')) body.bucket = draft.bucket.trim()
    if (typedPlace && draft.prefix.trim() !== (backend.prefix ?? '')) body.prefix = orNull(draft.prefix)
    if (draft.pathStyle !== backend.pathStyle) body.pathStyle = draft.pathStyle
    if (draft.accessKey.trim() !== (backend.accessKey ?? '')) body.accessKey = draft.accessKey.trim()
    if (draft.secret.trim() !== '') {
      body.secret = draft.secret
      if (newKey) body.newKey = true
    }
    return body
  }

  const name = draft.name.trim()
  const nameTooLong = name.length > NAME_MAX
  // What the kind needs, typed: all of it to add one, and on an edit what's stored, which can't be emptied (a member left
  // out stays as it's stored; an endpoint the server can't read isn't asked for here, and the save, which checks the one
  // stored, says what's wrong with it in the rule's words until another is typed)
  const kindNeeds: (keyof Draft)[] = original ? [] : s3 ? ['endpoint', 'region', 'accessKey', ...(placeFixed === undefined ? (['bucket'] as const) : []), ...(isNew ? (['secret'] as const) : [])] : placeFixed === undefined ? ['path'] : []
  const needed = isNew ? kindNeeds : kindNeeds.filter((field) => field !== 'secret' && (backend[field as keyof A['Backend']] ?? null) !== null)
  const complete = name !== '' && !nameTooLong && needed.every((field) => String(draft[field]).trim() !== '')
  const ready = complete && (isNew || Object.keys(change(false)).length > 0)

  const fields = fieldErrors(outcome?.kind === 'refused' ? outcome.error : undefined)
  const fieldError = (field: (typeof SHOWN)[number]) => fields.get(field)
  // A refusal that names none of the window's fields is said above its buttons, in the rule's words or the catalogue's
  const refusal = outcome?.kind === 'refused' && !SHOWN.some((field) => fields.has(field)) ? refusalFor(outcome.error) : undefined

  async function test() {
    setTesting(true)
    setOutcome(undefined)
    try {
      const tested = await api.send<A['BackendTestResult']>('POST', '/v1/admin/backends/test', trial())
      setOutcome({ kind: 'tested', problems: tested.problems })
    } catch (error) {
      setOutcome({ kind: 'refused', error })
    } finally {
      setTesting(false)
    }
  }

  /** Add or Save: tested first unless `tested`, then sent, with `newKey` once a new secret key is chosen */
  async function send({ newKey = false, tested = false }: { newKey?: boolean; tested?: boolean } = {}) {
    setBusy(true)
    setOutcome(undefined)
    try {
      if (!tested) {
        const found = await api.send<A['BackendTestResult']>('POST', '/v1/admin/backends/test', trial())
        if (found.problems.length > 0) {
          setOutcome({ kind: isNew ? 'not-added' : 'anyway', problems: found.problems })
          return
        }
      }
      if (backend === undefined) {
        const added = await api.send<A['AddedBackend']>('POST', '/v1/admin/backends', addition(newKey))
        backendsChanged(queryClient, added.backend)
        // An encrypted one's recovery key shows at once, which says it was added
        if (added.recoveryKey === null) toast(t('admin.backendDialog.added', { name: added.backend.name }))
        onAdded?.(added)
      } else {
        const saved = await api.send<A['Backend']>('PATCH', apiPath`/v1/admin/backends/${backend.id}`, change(newKey))
        backendsChanged(queryClient, saved)
        toast(t('admin.backendDialog.saved', { name: saved.name }))
      }
      // Not through the dialog's own close, which is held while busy
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.code === 'secret-key-missing') {
        const sealed = (error.body as { sealed?: unknown } | null)?.sealed
        setOutcome({ kind: 'new-key', sentence: error.detail.trim() === '' ? t('problems.secret-key-missing') : error.detail, sealed: typeof sealed === 'number' && sealed >= 1 ? sealed : 1 })
      } else {
        setOutcome({ kind: 'refused', error })
      }
    } finally {
      setBusy(false)
    }
  }

  const text = (field: 'name' | 'path' | 'endpoint' | 'region' | 'bucket' | 'prefix' | 'accessKey', label: string, extra: Partial<{ required: boolean; description: string; autoFocus: boolean; error: string; autoComplete: 'off' }> = {}) => (
    <TextField label={label} value={draft[field]} onChange={(value) => set(field, value)} error={fieldError(field)} {...extra} />
  )

  let said: ReactNode = null
  if (outcome?.kind === 'not-added' || outcome?.kind === 'anyway') {
    said = (
      <div className={styles.alert} role="alert">
        {outcome.kind === 'not-added' && <p>{t('admin.backendDialog.notAdded')}</p>}
        <TestFound problems={outcome.problems} />
        {outcome.kind === 'anyway' && <p>{t('admin.backendDialog.anyway')}</p>}
      </div>
    )
  } else if (outcome?.kind === 'new-key') {
    said = (
      <div className={styles.alert} role="alert">
        <p>{outcome.sentence}</p>
        <p>{plural('admin.backendDialog.newKeyLoses', outcome.sealed)}</p>
        <p>{t('admin.backendDialog.newKeyRestore')}</p>
      </div>
    )
  } else if (refusal !== undefined) {
    said = <Refusal>{refusal}</Refusal>
  }

  // The button that sends: Add or Save, or what the last answer asks
  const main =
    outcome?.kind === 'anyway' ? (
      <Button variant="default" isPending={busy} onPress={() => void send({ tested: true })}>
        {t('admin.backendDialog.saveAnyway')}
      </Button>
    ) : outcome?.kind === 'new-key' ? (
      <Button variant="danger" isPending={busy} onPress={() => void send({ newKey: true, tested: true })}>
        {t('admin.backendDialog.newKey')}
      </Button>
    ) : (
      <Button type="submit" variant="default" isDisabled={!ready || testing} isPending={busy}>
        {t(isNew ? 'admin.backendDialog.add' : 'admin.backendDialog.save')}
      </Button>
    )

  return (
    <Dialog
      title={isNew ? t('admin.backendDialog.addTitle') : t('admin.backendDialog.editTitle', { name: backend.name })}
      isOpen
      onOpenChange={(open) => !open && !busy && onClose()}
    >
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy && !testing && outcome?.kind !== 'anyway' && outcome?.kind !== 'new-key') void send()
          }}
        >
          {/* Held while Test is on its way too, so what it answers is about the settings shown */}
          <fieldset className={styles.form} disabled={busy || testing}>
            {isNew ? (
              <RadioChoices
                label={t('admin.backendDialog.kind')}
                choices={[
                  { value: 's3', label: t('admin.backends.kind.s3') },
                  { value: 'local', label: t('admin.backends.kind.local') },
                ]}
                value={draft.kind}
                onChange={(value) => set('kind', value === 'local' ? 'local' : 's3')}
              />
            ) : (
              <Fixed label={t('admin.backendDialog.kind')} value={kindOf(backend)} />
            )}
            {text('name', t('admin.backendDialog.name'), { required: true, autoFocus: true, error: nameTooLong ? t('fields.too-long') : fieldError('name'), description: original ? t('admin.backendDialog.originalName') : undefined })}
            {original ? (
              <Fixed label={t('admin.backendDialog.path')} value={backend.path ?? ''} why={t('admin.backendDialog.fixedConfig')} />
            ) : !s3 ? (
              placeFixed === undefined ? (
                text('path', t('admin.backendDialog.path'), { required: true, description: t('admin.backendDialog.pathNote') })
              ) : (
                <Fixed label={t('admin.backendDialog.path')} value={backend?.path ?? ''} why={placeFixed} />
              )
            ) : (
              <>
                {text('endpoint', t('admin.backendDialog.endpoint'), { required: true, description: t('admin.backendDialog.endpointNote') })}
                {text('region', t('admin.backendDialog.region'), { required: true })}
                {placeFixed === undefined ? (
                  <>
                    {text('bucket', t('admin.backendDialog.bucket'), { required: true })}
                    {text('prefix', t('admin.backendDialog.prefix'), { description: t('admin.backendDialog.prefixNote') })}
                  </>
                ) : (
                  <>
                    <Fixed label={t('admin.backendDialog.bucket')} value={backend?.bucket ?? ''} why={placeFixed} />
                    <Fixed label={t('admin.backendDialog.prefix')} value={backend?.prefix || t('admin.backendDialog.noPrefix')} why={placeFixed} />
                  </>
                )}
                {text('accessKey', t('admin.backendDialog.accessKey'), { required: true, autoComplete: 'off' })}
                <TextField
                  label={t('admin.backendDialog.secret')}
                  type="password"
                  autoComplete="new-password"
                  required={isNew}
                  value={draft.secret}
                  onChange={(value) => set('secret', value)}
                  error={fieldError('secret')}
                  description={isNew ? t('admin.backendDialog.secretNote') : backend.hasSecret ? t('admin.backendDialog.secretSet') : t('admin.backendDialog.secretNotSet')}
                />
                <CheckField label={t('admin.backendDialog.pathStyle')} description={t('admin.backendDialog.pathStyleNote')} checked={draft.pathStyle} onChange={(checked) => set('pathStyle', checked)} />
              </>
            )}
            {isNew ? (
              <CheckField label={t('admin.backendDialog.encrypt')} description={t('admin.backendDialog.encryptNote')} checked={draft.encrypted} onChange={(checked) => set('encrypted', checked)} />
            ) : (
              !original && <p className={styles.muted}>{t(backend.encrypted ? 'admin.backendDialog.encrypted' : 'admin.backendDialog.notEncrypted')}</p>
            )}
          </fieldset>
          <div className={styles.result} role="status">
            {outcome?.kind === 'tested' && (outcome.problems.length === 0 ? <p>{t('admin.storage.passed')}</p> : <TestFound problems={outcome.problems} />)}
          </div>
          {said}
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            {/* Whatever is typed: what's missing is among what Test finds */}
            <Button onPress={() => void test()} isDisabled={busy} isPending={testing}>
              {t('admin.backendDialog.test')}
            </Button>
            {main}
          </div>
        </form>
      )}
    </Dialog>
  )
}
