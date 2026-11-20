// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useRef, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { fieldErrors, idFrom, useApi, useMessageFor } from '@healdan/shell'
import { Button, Dialog, Icon, SelectField, TextField, toast, VisuallyHidden } from '@healdan/ui'
import { matterHref } from '../addresses'
import { ClientDialog } from '../clients/ClientDialog'
import { ClientPicker } from '../pickers/ClientPicker'
import { PersonPicker } from '../pickers/PersonPicker'
import { adminKeys, useNextMatterNumber, useStores, useTemplates, useWorkspaceTree } from '../queries'
import { NewStoreDialog } from '../stores/NewStoreDialog'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import formStyles from '../forms/Form.module.css'
import styles from './NewMatter.module.css'
import { useMatterCheck } from './useMatterCheck'

type A = adminComponents['schemas']

/** The form's fields, each of which a problem of the check or a 422 can be about */
type Field = 'client' | 'number' | 'name' | 'description' | 'store' | 'owner' | 'template'

/** The field each of the check's problems is about; those about none are said under the form */
const PROBLEM_FIELD: Partial<Record<A['MatterProblemCode'], Field>> = {
  'client-missing': 'client',
  'number-invalid': 'number',
  'number-taken': 'number',
  'name-invalid': 'name',
  'description-invalid': 'description',
  'store-missing': 'store',
  'template-missing': 'template',
  'owner-missing': 'owner',
  'owner-disabled': 'owner',
}

/** The member of `NewMatter` a 422 names for each field */
const MEMBER: Record<Field, keyof A['NewMatter']> = {
  client: 'clientId',
  number: 'number',
  name: 'name',
  description: 'description',
  store: 'storeId',
  owner: 'ownerId',
  template: 'templateId',
}

/** The matter's number typed, or undefined when it isn't a whole number from 1 */
function numberOf(text: string): number | undefined {
  const number = idFrom(text.trim())
  return number !== undefined && number >= 1 ? number : undefined
}

export interface NewMatterDialogProps {
  /** The client it's for, as a client's page sets it; another can still be picked */
  client?: A['ClientBrief']
  /** The dialog closes: Cancel, Esc, a click outside, or once the matter is made. The caller stops drawing it. */
  onClose: () => void
}

/**
 * New Matter (spec 8.3): Client (a search, with New Client…), Number (the client's next free one, which can be typed
 * over), Store (a menu, with New Store…), Name, Description, Owner (a search of those who can sign in) and Template (a
 * menu), with what the matter will contain beside them. The server checks the form as it changes, once the typing pauses
 * (useMatterCheck), and what it would refuse is said before Create (spec 7.2): beside the field it's about, or under the
 * form, where a group the template lets in that can't read the Store has Let {group} Read the Store to put it right
 * there. Create waits until the check of the form as it is finds nothing. Once made, the matter's page opens.
 */
export function NewMatterDialog({ client: preset, onClose }: NewMatterDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const refusalFor = useRefusalFor()
  const [client, setClient] = useState<A['ClientBrief'] | null>(preset ?? null)
  // What was last searched for in Client, which New Client… starts from. Leaving the box puts back what it holds (PickField),
  // which is no new search: so the text is kept as the focus goes to New Client…, by keys or a click
  const [clientText, setClientText] = useState('')
  const leavingClient = useRef(false)
  // What the person typed in Number; until they do, and again for each client picked, it holds the suggestion
  const [typedNumber, setTypedNumber] = useState<string>()
  const [storeId, setStoreId] = useState<number>()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [owner, setOwner] = useState<A['PersonBrief'] | null>(null)
  const [templateId, setTemplateId] = useState<number>()
  const [making, setMaking] = useState<'client' | 'store'>()
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const suggested = useNextMatterNumber(client?.id)
  const stores = useStores().data ?? []
  const templates = useTemplates()

  const numberText = typedNumber ?? (suggested.data ? String(suggested.data.number) : '')
  const number = numberOf(numberText)
  const trimmedName = name.trim()
  const trimmedDescription = description.trim()

  const check = useMatterCheck({
    clientId: client?.id ?? null,
    number: number ?? null,
    name: trimmedName === '' ? null : trimmedName,
    description: trimmedDescription === '' ? null : trimmedDescription,
    storeId: storeId ?? null,
    ownerId: owner?.id ?? null,
    templateId: templateId ?? null,
  })

  // Beside each field: what the form sees itself, else what the server named in a 422, else what the check found
  const fields = fieldErrors(failure)
  function errorOf(field: Field): string | undefined {
    if (field === 'number' && numberText.trim() !== '' && number === undefined) return t('admin.newMatter.numberInvalid')
    const named = fields.get(MEMBER[field])
    if (named !== undefined) return named
    const found = check.problems.find((problem) => PROBLEM_FIELD[problem.code] === field)
    return found && refusalFor(found)
  }
  const elsewhere = check.problems.filter((problem) => PROBLEM_FIELD[problem.code] === undefined)
  // A refusal that names none of the form's fields is said above its buttons, in the rule's words or the catalogue's
  const refusal = failure !== undefined && !Object.values(MEMBER).some((member) => fields.has(member)) ? refusalFor(failure) : undefined

  const filled = client !== null && number !== undefined && storeId !== undefined && trimmedName !== '' && owner !== null && templateId !== undefined
  const ready = filled && check.current && check.problems.length === 0

  /** A change to the form takes the last refusal away */
  function changed() {
    setFailure(undefined)
  }

  function chooseClient(next: A['ClientBrief'] | null) {
    setClient(next)
    // Each client's numbers are its own
    setTypedNumber(undefined)
    changed()
  }

  async function save() {
    if (!filled) return
    setBusy(true)
    setFailure(undefined)
    try {
      const made = await api.send<A['Matter']>('POST', '/v1/admin/matters', {
        clientId: client.id,
        number,
        name: trimmedName,
        description: trimmedDescription === '' ? null : trimmedDescription,
        storeId,
        ownerId: owner.id,
        templateId,
      } satisfies A['NewMatter'])
      // The lists that count matters: the Matters list, the clients' (a client's matters) and the templates' (made from it)
      void queryClient.invalidateQueries({ queryKey: adminKeys.matters })
      void queryClient.invalidateQueries({ queryKey: adminKeys.clients })
      void queryClient.invalidateQueries({ queryKey: adminKeys.templates })
      toast(t('admin.newMatter.created', { number: made.number, name: made.name }))
      // Not through the dialog's own close, which is held while busy
      onClose()
      void navigate({ href: matterHref(made.id) })
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  const prefix = client !== null && number !== undefined ? `${client.number}.${number}` : undefined
  const folderName =
    prefix !== undefined && trimmedName !== '' ? t('admin.newMatter.folder', { number: prefix, name: trimmedName }) : (prefix ?? (trimmedName || t('admin.newMatter.unnamed')))

  return (
    // Held while Create Matter is on its way, by Cancel, Esc or a click outside alike: the matter would still be made
    <Dialog title={t('admin.newMatter.title')} size="wide" isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <>
          <form
            className={formStyles.form}
            onSubmit={(event) => {
              event.preventDefault()
              if (ready && !busy) void save()
            }}
          >
            <div className={styles.columns}>
              <div className={styles.fields}>
                <div className={`${styles.span} ${formStyles.withAction}`}>
                  <div
                    className={formStyles.grow}
                    onFocusCapture={() => {
                      leavingClient.current = false
                    }}
                    onBlurCapture={() => {
                      leavingClient.current = true
                    }}
                  >
                    <ClientPicker
                      required
                      value={client}
                      error={errorOf('client')}
                      onChange={chooseClient}
                      onTextChange={(text) => {
                        if (!leavingClient.current) setClientText(text)
                      }}
                    />
                  </div>
                  <Button onPress={() => setMaking('client')}>{t('admin.newMatter.newClient')}</Button>
                </div>
                <div className={formStyles.field}>
                  <TextField
                    label={t('admin.newMatter.number')}
                    inputMode="numeric"
                    required
                    value={numberText}
                    error={errorOf('number')}
                    onChange={(value) => {
                      setTypedNumber(value)
                      changed()
                    }}
                  />
                  {client !== null && suggested.data && <p className={formStyles.note}>{t('admin.newMatter.numberNext', { number: suggested.data.number })}</p>}
                </div>
                <div className={formStyles.withAction}>
                  <div className={formStyles.grow}>
                    <SelectField
                      label={t('admin.newMatter.store')}
                      required
                      value={storeId === undefined ? '' : String(storeId)}
                      error={errorOf('store')}
                      onChange={(value) => {
                        setStoreId(idFrom(value))
                        changed()
                      }}
                      options={stores.map((store) => ({ value: String(store.id), label: store.name }))}
                    />
                  </div>
                  <Button onPress={() => setMaking('store')}>{t('admin.newMatter.newStore')}</Button>
                </div>
                <div className={styles.span}>
                  <TextField
                    label={t('admin.newMatter.name')}
                    // With the client set already, the name is the next thing to type
                    autoFocus={preset !== undefined}
                    required
                    value={name}
                    error={errorOf('name')}
                    onChange={(value) => {
                      setName(value)
                      changed()
                    }}
                  />
                </div>
                <div className={styles.span}>
                  <TextField
                    label={t('admin.newMatter.description')}
                    multiline
                    rows={2}
                    value={description}
                    error={errorOf('description')}
                    onChange={(value) => {
                      setDescription(value)
                      changed()
                    }}
                  />
                </div>
                <div>
                  {/* Only those who can sign in can own a matter */}
                  <PersonPicker
                    label={t('admin.newMatter.owner')}
                    accounts="enabled"
                    required
                    value={owner}
                    error={errorOf('owner')}
                    onChange={(next) => {
                      setOwner(next)
                      changed()
                    }}
                  />
                </div>
                <div className={formStyles.field}>
                  <SelectField
                    label={t('admin.newMatter.template')}
                    required
                    value={templateId === undefined ? '' : String(templateId)}
                    error={errorOf('template')}
                    onChange={(value) => {
                      setTemplateId(idFrom(value))
                      changed()
                    }}
                    options={(templates.data ?? []).map((template) => ({ value: String(template.id), label: template.name }))}
                  />
                  {templates.data?.length === 0 && <p className={formStyles.note}>{t('admin.newMatter.noTemplates')}</p>}
                </div>
              </div>
              <WillContain templateId={templateId} folderName={folderName} />
            </div>
            <Problems problems={elsewhere} current={check.current} storeName={(id) => stores.find((store) => store.id === id)?.name} onFixed={check.recheck} />
            {refusal !== undefined && (
              <p className={formStyles.refusal} role="alert">
                {refusal}
              </p>
            )}
            <div className={formStyles.buttons}>
              <Button onPress={close} isDisabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
                {t('admin.newMatter.create')}
              </Button>
            </div>
          </form>
          {/* Outside the form, so a submit in either never reaches New Matter's (React's events follow the tree into portals) */}
          {making === 'client' && (
            <ClientDialog initialName={clientText.trim()} onSaved={(made) => chooseClient({ id: made.id, number: made.number, name: made.name })} onClose={() => setMaking(undefined)} />
          )}
          {making === 'store' && (
            <NewStoreDialog
              onSaved={(made) => {
                setStoreId(made.id)
                changed()
              }}
              onClose={() => setMaking(undefined)}
            />
          )}
        </>
      )}
    </Dialog>
  )
}

/**
 * What the matter will contain (spec 8.3): its own folder, named as it will be, and under it the chosen template's folders
 * and search folders, as the template's tree gives them.
 */
function WillContain({ templateId, folderName }: { templateId: number | undefined; folderName: string }) {
  const headingId = useId()
  const tree = useWorkspaceTree(templateId)
  const messageFor = useMessageFor()
  const said =
    templateId === undefined ? t('admin.newMatter.chooseTemplate') : tree.isError ? messageFor(tree.error) : tree.data === undefined ? t('common.loading') : undefined
  return (
    <section aria-labelledby={headingId} className={styles.contains}>
      <h3 id={headingId} className={styles.containsTitle}>
        {t('admin.newMatter.contains')}
      </h3>
      {said !== undefined || tree.data === undefined ? (
        <p className={formStyles.note}>{said}</p>
      ) : (
        <ul className={styles.tree}>
          <li>
            <span data-node className={styles.node}>
              <Icon name="briefcase" kind="matter" />
              <span className={styles.nodeName}>{folderName}</span>
            </span>
            {tree.data.children.length === 0 ? <p className={formStyles.note}>{t('admin.newMatter.emptyTemplate')}</p> : <Nodes nodes={tree.data.children} />}
          </li>
        </ul>
      )}
    </section>
  )
}

function Nodes({ nodes }: { nodes: A['WorkspaceNode'][] }) {
  return (
    <ul className={styles.tree}>
      {nodes.map((node) => (
        <li key={node.id}>
          <span data-node className={styles.node}>
            <Icon name={node.kind === 'search-folder' ? 'folderSearch' : 'folder'} kind="folder" />
            <span className={styles.nodeName}>
              {node.name}
              {node.kind === 'search-folder' && <VisuallyHidden>{t('admin.newMatter.searchFolder')}</VisuallyHidden>}
            </span>
          </span>
          {node.children.length > 0 && <Nodes nodes={node.children} />}
        </li>
      ))}
    </ul>
  )
}

/** Where a fix is: on its way, or refused, by the problem it puts right */
interface Fix {
  key: string
  pending: boolean
  failure?: unknown
}

/**
 * The check's problems that are about no one field, each in the server's words: a folder of the template that grants more
 * than its parent, and a group the template lets in, or the owner, who can't read the Store. That one has Let {group}
 * Read the Store, which adds the group to those who can see the Store, keeping every one whose row grants Read, then asks
 * for the check again. It's offered only while the check is of the form as it is (`current`), so it never grants Read on
 * a Store the form no longer names, and one at a time, so a second never reads who can see the Store before the first
 * has saved, which it would then take away.
 */
function Problems({
  problems,
  current,
  storeName,
  onFixed,
}: {
  problems: A['MatterProblem'][]
  current: boolean
  storeName: (id: number) => string | undefined
  onFixed: () => Promise<void>
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [fix, setFix] = useState<Fix>()
  // The one on its way keeps its focus, pending; every other waits
  const waiting = !current || fix?.pending === true

  async function grant(storeId: number, groupId: number, groupName: string, key: string) {
    setFix({ key, pending: true })
    try {
      const access = await api.get<A['StoreAccess']>(apiPath`/v1/admin/stores/${storeId}/access`)
      // A row that grants Read is one who can see the Store; a row without it lets nobody see it, so it isn't sent as one
      const readers = access.rows.filter((row) => row.permissions.includes('read')).map((row) => row.groupId)
      const groupIds = [...new Set([...readers, groupId])]
      const saved = await api.send<A['StoreAccess']>('PUT', apiPath`/v1/admin/stores/${storeId}/access`, { groupIds } satisfies A['StoreAccessChange'])
      queryClient.setQueryData(adminKeys.storeAccess(storeId), saved)
      toast(t('admin.newMatter.granted', { group: groupName, store: storeName(storeId) ?? t('admin.newMatter.theStore') }))
      // The button stays busy until the check has been asked again, so it isn't offered again meanwhile
      await onFixed()
      setFix(undefined)
    } catch (error) {
      setFix({ key, pending: false, failure: error })
    }
  }

  return (
    // There before it has problems, so a screen reader says each as it comes
    <div aria-live="polite">
      {problems.length > 0 && (
        <ul className={styles.problems}>
          {problems.map((problem, index) => {
            const key = `${problem.code}:${problem.storeId ?? ''}:${problem.groupId ?? ''}:${index}`
            const { storeId, groupId, groupName } = problem
            const canGrant = problem.fixAction === 'grant-store-read' && storeId !== null && groupId !== null && groupName !== null
            const mine = fix?.key === key ? fix : undefined
            return (
              <li key={key} className={styles.problem}>
                <Icon name="alertTriangle" className={styles.problemSign} />
                <div className={styles.problemText}>
                  <p className={styles.problemLine}>{refusalFor(problem)}</p>
                  {canGrant && (
                    <p className={styles.problemLine}>
                      <Button isPending={mine?.pending} isDisabled={waiting && mine?.pending !== true} onPress={() => void grant(storeId, groupId, groupName, key)}>
                        {t('admin.newMatter.grant', { group: groupName })}
                      </Button>{' '}
                      <span>{t('admin.newMatter.orAnotherStore')}</span>
                    </p>
                  )}
                  {mine?.failure !== undefined && (
                    <p className={formStyles.refusal} role="alert">
                      {refusalFor(mine.failure)}
                    </p>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
