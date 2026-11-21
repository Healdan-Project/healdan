// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Dialog, formatDateTime, Icon, TextField, toast } from '@healdan/ui'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { backendsChanged, useKeyEvents } from './queries'
import styles from './Storage.module.css'

type A = adminComponents['schemas']

export type RecoveryKeyDialogProps = {
  /** An encrypted backend */
  backend: A['Backend']
  /**
   * The dialog closes: its Cancel, Close or Later, Esc, a click outside, or once done. The caller stops drawing it, and a
   * key it was given goes with it. While a change is on its way it can't be closed.
   */
  onClose: () => void
} & (
  | /** Just added: its key, from the add's answer, shown at once, then its last group asked for */ { mode: 'added'; recoveryKey: string }
  | /** Show Recovery Key: asks first, then shows it, asking for its last group while it isn't confirmed */ { mode: 'show' }
  | /** Confirm Recovery Key: its last group typed back, the key not shown */ { mode: 'confirm' }
  | /** Enter Recovery Key: its key typed, to put its key file back */ { mode: 'enter' }
  | /** Key History: every showing and entering */ { mode: 'history' }
)

/**
 * An encrypted backend's recovery key (spec 8.6), as the Storage tab's windows have it: shown at its add, asked for again
 * with Show Recovery Key, confirmed by its last group, entered to put a lost key file back, and its Key History. A key is
 * held only in this dialog's state while it's open: never in the query cache, the address, the browser's storage, a
 * toast or a log. Each change holds the dialog while it's on its way.
 */
export function RecoveryKeyDialog(props: RecoveryKeyDialogProps) {
  switch (props.mode) {
    case 'added':
      return <ShownKey backend={props.backend} recoveryKey={props.recoveryKey} ask onClose={props.onClose} />
    case 'show':
      return <ShowKey backend={props.backend} onClose={props.onClose} />
    case 'confirm':
      return <ConfirmKey backend={props.backend} onClose={props.onClose} />
    case 'enter':
      return <EnterKey backend={props.backend} onClose={props.onClose} />
    case 'history':
      return <KeyHistory backend={props.backend} onClose={props.onClose} />
  }
}

/** A change of the key's that holds its dialog while it's on its way: confirm or enter, answered with the backend */
function useKeyChange(backend: A['Backend'], route: 'confirm' | 'enter', done: string, onClose: () => void) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  async function run(body: A['RecoveryKeyConfirmation'] | A['RecoveryKeyEntry']) {
    setBusy(true)
    setFailure(undefined)
    try {
      const path = route === 'confirm' ? apiPath`/v1/admin/backends/${backend.id}/recovery-key/confirm` : apiPath`/v1/admin/backends/${backend.id}/recovery-key/enter`
      const saved = await api.send<A['Backend']>('POST', path, body)
      backendsChanged(queryClient, saved)
      toast(done)
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return { run, busy, failure, clear: () => setFailure(undefined) }
}

function Refusal({ failure }: { failure: unknown }) {
  const refusalFor = useRefusalFor()
  return failure === undefined ? null : (
    <p className={styles.refusal} role="alert">
      {refusalFor(failure)}
    </p>
  )
}

/** The key in two lines of its groups, as the Storage tab draws it, with Copy */
function KeyText({ recoveryKey }: { recoveryKey: string }) {
  const groups = recoveryKey.split(' ')
  const half = Math.ceil(groups.length / 2)
  const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard
  return (
    <div className={styles.row}>
      <div role="group" aria-label={t('admin.recoveryKey.key')} className={styles.key}>
        <span className={styles.keyLine}>{groups.slice(0, half).join(' ')}</span> <span className={styles.keyLine}>{groups.slice(half).join(' ')}</span>
      </div>
      {clipboard !== undefined && (
        <Button onPress={() => void clipboard.writeText(recoveryKey).then(() => toast(t('admin.recoveryKey.copied')))}>
          <span className={styles.row}>
            <Icon name="copy" size={14} />
            {t('admin.recoveryKey.copy')}
          </span>
        </Button>
      )}
    </div>
  )
}

/**
 * Where the focus goes as the shown key opens: the field it asks for, or, with none, its heading. It takes over from a
 * dialog that goes as it comes (Show's question, or the add's window), whose focus would otherwise be left on the page
 * or on the dialog as a whole. Again a frame later, as the dialog that went can take the focus as it leaves.
 */
function useFocusOnOpen(inside: RefObject<HTMLElement | null>, field: boolean) {
  useEffect(() => {
    function place() {
      const dialog = inside.current?.closest('[role="dialog"]')
      if (!(dialog instanceof HTMLElement)) return
      const active = document.activeElement
      // Something in the dialog was given the focus already, by the person or by the field's own autofocus
      if (active !== dialog && active !== null && dialog.contains(active)) return
      const target = field ? dialog.querySelector('input') : document.getElementById(dialog.getAttribute('aria-labelledby') ?? '')
      if (!(target instanceof HTMLElement)) return
      if (!field) target.tabIndex = -1
      target.focus()
    }
    place()
    const frame = requestAnimationFrame(place)
    return () => cancelAnimationFrame(frame)
  }, [inside, field])
}

/** The key shown, why it matters, and, while it isn't confirmed (`ask`), its last group asked for, with Later */
function ShownKey({ backend, recoveryKey, ask, onClose }: { backend: A['Backend']; recoveryKey: string; ask: boolean; onClose: () => void }) {
  const [lastGroup, setLastGroup] = useState('')
  const confirming = useKeyChange(backend, 'confirm', t('admin.recoveryKey.confirmed', { name: backend.name }), onClose)
  const name = backend.name
  const inside = useRef<HTMLFormElement>(null)
  useFocusOnOpen(inside, ask)
  return (
    <Dialog title={t('admin.recoveryKey.title', { name })} isOpen onOpenChange={(open) => !open && !confirming.busy && onClose()}>
      {(close) => (
        <form
          ref={inside}
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ask && lastGroup.trim() !== '' && !confirming.busy) void confirming.run({ lastGroup: lastGroup.trim() })
          }}
        >
          {ask && <p className={styles.line}>{t('admin.recoveryKey.saveNow', { name })}</p>}
          <p className={styles.line}>{t('admin.recoveryKey.why', { name })}</p>
          <p className={styles.line}>{t('admin.recoveryKey.notAgain')}</p>
          <KeyText recoveryKey={recoveryKey} />
          {ask && (
            <fieldset className={styles.form} disabled={confirming.busy}>
              <p className={styles.line}>{t('admin.recoveryKey.askLast', { name })}</p>
              <TextField
                label={t('admin.recoveryKey.lastGroup')}
                autoComplete="off"
                required
                value={lastGroup}
                onChange={(value) => {
                  setLastGroup(value)
                  confirming.clear()
                }}
              />
            </fieldset>
          )}
          <Refusal failure={confirming.failure} />
          <div className={styles.buttons}>
            {ask ? (
              <>
                <Button onPress={close} isDisabled={confirming.busy}>
                  {t('admin.recoveryKey.later')}
                </Button>
                <Button type="submit" variant="default" isDisabled={lastGroup.trim() === ''} isPending={confirming.busy}>
                  {t('admin.recoveryKey.confirm')}
                </Button>
              </>
            ) : (
              <Button variant="default" onPress={close}>
                {t('admin.recoveryKey.close')}
              </Button>
            )}
          </div>
        </form>
      )}
    </Dialog>
  )
}

/**
 * Show Recovery Key: asks first, saying each showing is recorded, then asks the server, and shows the key it answers,
 * held only here. It isn't asked for until the question is answered, as the asking is what's recorded.
 */
function ShowKey({ backend, onClose }: { backend: A['Backend']; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const describedBy = useId()
  const [recoveryKey, setRecoveryKey] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  async function show() {
    setBusy(true)
    setFailure(undefined)
    try {
      const answer = await api.send<A['RecoveryKey']>('POST', apiPath`/v1/admin/backends/${backend.id}/recovery-key/show`)
      setRecoveryKey(answer.recoveryKey)
      // When it was last shown, and to whom, and its history, have changed
      backendsChanged(queryClient)
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  if (recoveryKey !== undefined) return <ShownKey backend={backend} recoveryKey={recoveryKey} ask={!backend.keyConfirmed} onClose={onClose} />
  return (
    <Dialog title={t('admin.recoveryKey.showTitle', { name: backend.name })} warning isOpen aria-describedby={describedBy} onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <div className={styles.form}>
          <p id={describedBy} className={styles.line}>
            {t('admin.recoveryKey.showLine')}
          </p>
          <Refusal failure={failure} />
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button variant="default" onPress={() => void show()} isPending={busy}>
              {t('admin.recoveryKey.show')}
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

/** Confirm Recovery Key, from where it was saved: its last group typed back, which lets Stores use the backend */
function ConfirmKey({ backend, onClose }: { backend: A['Backend']; onClose: () => void }) {
  const [lastGroup, setLastGroup] = useState('')
  const confirming = useKeyChange(backend, 'confirm', t('admin.recoveryKey.confirmed', { name: backend.name }), onClose)
  return (
    <Dialog title={t('admin.recoveryKey.confirmTitle', { name: backend.name })} isOpen onOpenChange={(open) => !open && !confirming.busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (lastGroup.trim() !== '' && !confirming.busy) void confirming.run({ lastGroup: lastGroup.trim() })
          }}
        >
          <fieldset className={styles.form} disabled={confirming.busy}>
            <p className={styles.line}>{t('admin.recoveryKey.confirmLine', { name: backend.name })}</p>
            <TextField
              label={t('admin.recoveryKey.lastGroup')}
              autoComplete="off"
              autoFocus
              required
              value={lastGroup}
              onChange={(value) => {
                setLastGroup(value)
                confirming.clear()
              }}
            />
          </fieldset>
          <Refusal failure={confirming.failure} />
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={confirming.busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={lastGroup.trim() === ''} isPending={confirming.busy}>
              {t('admin.recoveryKey.confirm')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

/** Enter Recovery Key: the key typed or pasted, which the server checks against the backend before it writes the key file */
function EnterKey({ backend, onClose }: { backend: A['Backend']; onClose: () => void }) {
  const [typed, setTyped] = useState('')
  const entering = useKeyChange(backend, 'enter', t('admin.recoveryKey.entered', { name: backend.name }), onClose)
  return (
    <Dialog title={t('admin.recoveryKey.enterTitle', { name: backend.name })} isOpen onOpenChange={(open) => !open && !entering.busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (typed.trim() !== '' && !entering.busy) void entering.run({ recoveryKey: typed.trim() })
          }}
        >
          <fieldset className={styles.form} disabled={entering.busy}>
            <p className={styles.line}>{t('admin.recoveryKey.enterLine', { name: backend.name })}</p>
            <TextField
              label={t('admin.recoveryKey.enterField')}
              autoComplete="off"
              autoFocus
              required
              value={typed}
              description={t('admin.recoveryKey.enterNote')}
              onChange={(value) => {
                setTyped(value)
                entering.clear()
              }}
            />
          </fieldset>
          <Refusal failure={entering.failure} />
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={entering.busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={typed.trim() === ''} isPending={entering.busy}>
              {t('admin.recoveryKey.enter')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

/** Key History: each showing and entering of the key, newest first, with when and by whom; never the key */
function KeyHistory({ backend, onClose }: { backend: A['Backend']; onClose: () => void }) {
  const events = useKeyEvents(backend.id)
  let body
  if (events.isError) body = <ErrorPanel error={events.error} onRetry={() => void events.refetch()} />
  else if (events.data === undefined) body = <p className={styles.muted}>{t('common.loading')}</p>
  else if (events.data.length === 0) body = <p className={styles.muted}>{t('admin.recoveryKey.history.none')}</p>
  else
    body = (
      <div className={styles.tableBox}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">{t('admin.recoveryKey.history.when')}</th>
              <th scope="col">{t('admin.recoveryKey.history.what')}</th>
              <th scope="col">{t('admin.recoveryKey.history.by')}</th>
            </tr>
          </thead>
          <tbody>
            {events.data.map((event, index) => (
              <tr key={index}>
                <td>{formatDateTime(event.at)}</td>
                <td>{t(event.event === 'entered' ? 'admin.recoveryKey.history.entered' : 'admin.recoveryKey.history.shown')}</td>
                <td className={styles.wrap}>{event.username}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  return (
    <Dialog title={t('admin.recoveryKey.historyTitle', { name: backend.name })} isOpen onOpenChange={(open) => !open && onClose()}>
      {(close) => (
        <div className={styles.form}>
          {body}
          <div className={styles.buttons}>
            <Button variant="default" onPress={close}>
              {t('admin.recoveryKey.close')}
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}
