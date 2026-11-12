// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, useState } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, formatSize, formatVersion, Icon, t } from '@healdan/ui'
import { CheckoutControls } from '../actions/CheckoutControls'
import { copyLink } from '../actions/copyLink'
import { FavoriteStar } from '../actions/FavoriteStar'
import { OpenDownload } from '../actions/OpenDownload'
import { useOpenInApp } from '../actions/OpenInAppState'
import { fileIcon } from '../fileIcon'
import { ErrorPanel } from '../frame/ErrorPanel'
import { useQuickLook } from '../preview/QuickLookState'
import { useDocument, useProperties, useVersions } from '../queries'
import { useApi } from '../services'
import { isEditable } from './FieldsForm'
import { clearEditRequest, useEditRequest } from './editRequest'
import { Properties, type DetailsContext } from './Properties'
import { PropertiesForm } from './PropertiesForm'
import { Section } from './Section'
import { Versions } from './Versions'
import styles from './details.module.css'

type S = components['schemas']

export interface DetailsPanelProps {
  docNumber: number
  /** The document's row where it was listed, shown at once while its details come */
  row?: S['DocumentView']
  /** The version a # named, marked in Versions */
  version?: string
  /** In search results Location says which folder the document is in */
  context?: DetailsContext
}

/**
 * The selected document's details (spec section 6.2): its thumbnail, name, doc #, version and size, the actions, its
 * properties and its versions. Preview opens Quick Look; Edit changes the properties in place.
 */
export function DetailsPanel({ docNumber, row, version, context = 'folder' }: DetailsPanelProps) {
  const document = useDocument(docNumber, row)
  const properties = useProperties(docNumber)
  const versions = useVersions(docNumber, version)
  const quickLook = useQuickLook()
  const app = useOpenInApp()
  const doc = document.data
  const [editing, setEditing] = useState(false)
  const edit = useRef<HTMLButtonElement>(null)
  // Edit is grey while the form is open, and the keyboard's place is there once it's enabled again, not on the page
  const giveFocusBack = useRef(false)
  // The filing tray's Edit: once the properties are here, the form opens if the person may change any of them
  const asked = useEditRequest(docNumber)
  const loaded = properties.data?.items
  if (asked && loaded && !editing && loaded.some(isEditable)) setEditing(true)
  useEffect(() => {
    if (editing || !giveFocusBack.current) return
    giveFocusBack.current = false
    edit.current?.focus()
  }, [editing])
  useEffect(() => {
    if (asked && loaded) clearEditRequest()
  }, [asked, loaded])

  if (document.isError) return <ErrorPanel error={document.error} onRetry={() => void document.refetch()} />
  if (!doc) return <p className={styles.note}>{t('common.loading')}</p>

  return (
    <div className={styles.panel}>
      <Thumbnail key={`${doc.docNumber}v${doc.fileVersion}`} document={doc} />
      <div className={styles.header}>
        <h2 className={styles.name}>{doc.name}</h2>
        <FavoriteStar what={{ docNumber: doc.docNumber }} add={t('favorites.addDocument')} remove={t('favorites.removeDocument')} />
      </div>
      <p className={styles.meta}>{t('details.meta', { n: doc.docNumber, version: formatVersion(doc.fileVersion), size: formatSize(doc.size) })}</p>
      <div className={styles.actions}>
        <OpenDownload document={doc} />
        <Button ref={edit} isDisabled={editing || !properties.data?.items.some((field) => field.editable)} onPress={() => setEditing(true)}>
          {t('details.edit')}
        </Button>
        <Button onPress={() => void copyLink(doc.docNumber)}>{t('details.copyLink')}</Button>
      </div>
      <CheckoutControls document={doc} />
      {properties.isError ? (
        <Section title={t('details.properties')}>
          <p className={styles.note}>{t('details.propertiesUnavailable')}</p>
        </Section>
      ) : properties.data && editing ? (
        <PropertiesForm
          document={doc}
          fields={properties.data.items}
          onClose={() => {
            giveFocusBack.current = true
            setEditing(false)
          }}
        />
      ) : properties.data ? (
        <Properties document={doc} fields={properties.data.items} context={context} />
      ) : (
        <Section title={t('details.properties')}>
          <p className={styles.note}>{t('common.loading')}</p>
        </Section>
      )}
      {versions.isError ? (
        <Section title={t('details.versions')}>
          <p className={styles.note}>{t('details.versionsUnavailable')}</p>
        </Section>
      ) : versions.items ? (
        <Versions
          versions={versions.items}
          selected={version}
          onShowMore={versions.hasMore ? versions.loadMore : undefined}
          loading={versions.isLoadingMore}
          canOpen={doc.canOpen}
          onPreview={(of) => quickLook.open(doc.docNumber, of)}
          onDownload={(of) => app.download(doc, of)}
        />
      ) : (
        <Section title={t('details.versions')}>
          <p className={styles.note}>{t('common.loading')}</p>
        </Section>
      )}
    </div>
  )
}

/** How long a missing thumbnail waits before it's asked for again, and how many times */
export const THUMBNAIL_RETRY_MS = 5000
const THUMBNAIL_TRIES = 4

// The server's thumbnail, a PNG of the first page, addressed by the file version so a new version's is never the old one
// from the browser's cache (the component is keyed by it too). A document with none yet (404), or one the person can't
// open, shows the icon of its kind instead of a broken image; one that's missing is asked for again a little later, as
// the server makes it in the background after a filing or a save.
function Thumbnail({ document }: { document: S['DocumentView'] }) {
  const api = useApi()
  const quickLook = useQuickLook()
  const [failed, setFailed] = useState(false)
  const [tries, setTries] = useState(1)
  useEffect(() => {
    if (!failed || tries >= THUMBNAIL_TRIES) return
    const timer = setTimeout(() => {
      setTries((now) => now + 1)
      setFailed(false)
    }, THUMBNAIL_RETRY_MS)
    return () => clearTimeout(timer)
  }, [failed, tries])
  return (
    <div className={styles.thumb}>
      {document.canOpen && !failed ? (
        <img key={tries} src={api.url(apiPath`/v1/documents/${document.docNumber}/thumbnail`, { v: document.fileVersion })} alt="" className={styles.image} onError={() => setFailed(true)} />
      ) : (
        <Icon name={fileIcon(document.name)} size={48} />
      )}
      <div className={styles.preview}>
        <Button onPress={() => quickLook.open(document.docNumber)}>{t('details.preview')}</Button>
      </div>
    </div>
  )
}
