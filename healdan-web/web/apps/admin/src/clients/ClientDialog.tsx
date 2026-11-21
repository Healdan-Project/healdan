// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { fieldErrors, idFrom, useApi } from '@healdan/shell'
import { Button, Dialog, TextField, toast } from '@healdan/ui'
import { clientLabel } from '../pickers/ClientPicker'
import { useDebounced } from '../pickers/typing'
import { CLIENT_SEARCH_LIMIT } from '../pickers/useClientSearch'
import { adminKeys, useAdminQuery, useNextClientNumber } from '../queries'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import { Refusal } from '../forms/Refusal'
import styles from './Clients.module.css'

type A = adminComponents['schemas']

/** The longest name the server keeps (`ClientService.MAX_NAME_LENGTH`), in UTF-16 units as Java counts them */
const NAME_MAX = 255

/** The third part of the suggested number's key, which a save leaves alone, as the dialog forgets it as it closes */
const NEXT_NUMBER = adminKeys.nextClientNumber[2]

export interface ClientDialogProps {
  /** The client to rename; without one, the dialog makes a new client, with the number the server suggests */
  client?: A['ClientBrief']
  /** The name a new client starts with, such as what was typed in a client search */
  initialName?: string
  /** The client as the server answered, made or renamed, just before the dialog closes; where to go next is the caller's */
  onSaved?: (client: A['Client']) => void
  /**
   * The dialog closes: Cancel, Esc, a click outside, or once saved. The caller stops drawing it. While a change is on its
   * way it can't be closed, as the server may make it anyway.
   */
  onClose: () => void
}

/** The client number typed, or undefined when it isn't a whole number from 1 */
function numberOf(text: string): number | undefined {
  const number = idFrom(text.trim())
  return number !== undefined && number >= 1 ? number : undefined
}

/**
 * The client a number belongs to already, if any, asked once the typing pauses, so New Client says so before its button
 * (spec 7.2). The search keeps the clients whose number starts with what is typed, by number, so the client that has it
 * comes first among those; a client whose name holds the digits can come before it, so the first 20 are looked through.
 * The server still refuses a number that's taken by the time it's sent.
 */
function useNumberTakenBy(number: number | undefined): A['Client'] | undefined {
  const settled = useDebounced(number)
  const found = useAdminQuery('/v1/admin/clients', {
    query: { q: String(settled ?? ''), limit: CLIENT_SEARCH_LIMIT },
    enabled: settled !== undefined,
    keepPrevious: true,
  })
  const clients: A['Client'][] = found.data?.items ?? []
  return number === undefined ? undefined : clients.find((one) => one.number === number)
}

/**
 * New Client, or renaming one (spec 8.2): a client's number is suggested as the highest plus one, can be typed over,
 * and can't change once the client is made, so renaming changes only its name. Nothing deletes a client. Problems the
 * form can see are said before its button; the server's refusal of a used number, or anything else, is said in the
 * dialog (useRefusalFor), and a field the server says is wrong beside it. Once saved, every list and search of clients
 * is asked again, and the client's own page has the answer at once; a rename asks again for the matters' rows, which
 * name their client. While the change is on its way, Cancel is greyed out and Esc and a click outside do nothing, so
 * nobody backs out of a client the server then makes: the dialog closes once it's made, or stays to say why it wasn't.
 */
export function ClientDialog({ client, initialName = '', onSaved, onClose }: ClientDialogProps) {
  const isNew = client === undefined
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const suggested = useNextClientNumber(isNew)
  // What the person typed in Number; until they do, it holds the suggestion
  const [typedNumber, setTypedNumber] = useState<string>()
  const [name, setName] = useState(client?.name ?? initialName)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const numberText = typedNumber ?? (suggested.data ? String(suggested.data.number) : '')
  const number = isNew ? numberOf(numberText) : undefined
  const takenBy = useNumberTakenBy(number)
  const trimmed = name.trim()

  const fields = fieldErrors(failure)
  const numberError =
    numberText.trim() !== '' && number === undefined
      ? t('admin.clientDialog.numberInvalid')
      : takenBy
        ? t('admin.clientDialog.numberUsed', { number: takenBy.number, name: takenBy.name })
        : fields.get('number')
  const nameError = trimmed.length > NAME_MAX ? t('fields.too-long') : fields.get('name')
  // A refusal that names none of the form's fields is said above its buttons, in the rule's words or the catalogue's
  const shownFields = isNew ? ['number', 'name'] : ['name']
  const refusal = failure !== undefined && !shownFields.some((field) => fields.has(field)) ? refusalFor(failure) : undefined
  const ready = trimmed !== '' && trimmed.length <= NAME_MAX && (isNew ? number !== undefined && !takenBy : trimmed !== client.name)

  /** The change the form asks for, or undefined when it asks for none */
  function request(): Promise<A['Client']> | undefined {
    if (client !== undefined) return api.send<A['Client']>('PATCH', apiPath`/v1/admin/clients/${client.id}`, { name: trimmed } satisfies A['NameChange'])
    return number === undefined ? undefined : api.send<A['Client']>('POST', '/v1/admin/clients', { number, name: trimmed } satisfies A['NewClient'])
  }

  async function save() {
    const sending = request()
    if (sending === undefined) return
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await sending
      // The client's own page has the answer at once; every other list and search of clients is asked again
      queryClient.setQueryData(adminKeys.client(saved.id), saved)
      void queryClient.invalidateQueries({ queryKey: adminKeys.clients, predicate: ({ queryKey }) => queryKey[2] !== saved.id && queryKey[2] !== NEXT_NUMBER })
      // The matters' rows name their client
      if (!isNew) void queryClient.invalidateQueries({ queryKey: adminKeys.matters })
      if (isNew) toast(t('admin.clientDialog.created', { client: clientLabel(saved) }))
      onSaved?.(saved)
      // Not through the dialog's own close, which is held while busy
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t(isNew ? 'admin.clientDialog.newTitle' : 'admin.clientDialog.renameTitle')} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy) void save()
          }}
        >
          {isNew ? (
            <>
              <TextField
                label={t('admin.clientDialog.number')}
                inputMode="numeric"
                required
                value={numberText}
                error={numberError}
                onChange={(value) => {
                  setTypedNumber(value)
                  // A change takes the last refusal away
                  setFailure(undefined)
                }}
              />
              <p className={styles.note}>{t('admin.clientDialog.numberNote')}</p>
            </>
          ) : (
            <p className={styles.note}>{t('admin.clientDialog.numberFixed', { number: client.number })}</p>
          )}
          <TextField
            label={t('admin.clientDialog.name')}
            autoFocus
            required
            value={name}
            error={nameError}
            onChange={(value) => {
              setName(value)
              setFailure(undefined)
            }}
          />
          <Refusal>{refusal}</Refusal>
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t(isNew ? 'admin.clientDialog.create' : 'admin.clientDialog.rename')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
