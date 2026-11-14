// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ApiError, apiPath, type components } from '@healdan/api'
import { Button, formatSize, formatVersion, Icon, plural, t, Tabs } from '@healdan/ui'
import { CheckoutControls } from '../actions/CheckoutControls'
import { copyLink } from '../actions/copyLink'
import { FavoriteStar } from '../actions/FavoriteStar'
import { OpenDownload } from '../actions/OpenDownload'
import { useOpenInApp } from '../actions/OpenInAppState'
import { fileIcon } from '../kindIcons'
import { ErrorPanel } from '../frame/ErrorPanel'
import { useQuickLook } from '../preview/QuickLookState'
import { useDocument, useProperties, useVersions } from '../queries'
import { useApi } from '../services'
import { useDetailsTab, type DetailsTab } from './DetailsTab'
import { isEditable } from './FieldsForm'
import { clearEditRequest, useEditRequest } from './editRequest'
import { HoldInfo } from './HoldInfo'
import { Properties, type DetailsContext } from './Properties'
import { PropertiesForm } from './PropertiesForm'
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
 * The selected document's details (spec section 6.2), as the Healdan Helper's Open window has them (changed after M6: the
 * owner's dogfooding punch list, 2026-11-14): a fixed header, with the thumbnail, the name, the doc #, version and size,
 * Open, Download and Copy Link, and while it's held its Check-Out Info; then the Properties and Versions tabs. Preview opens
 * Quick Look; Edit Properties, in the Properties tab, changes them in place. A # that named a version opens Versions with
 * it marked.
 */
export function DetailsPanel({ docNumber, row, version, context = 'folder' }: DetailsPanelProps) {
  const document = useDocument(docNumber, row)
  const properties = useProperties(docNumber)
  const versions = useVersions(docNumber, version)
  const quickLook = useQuickLook()
  const app = useOpenInApp()
  const { tab, setTab } = useDetailsTab()
  const doc = document.data
  const [editing, setEditing] = useState(false)
  const edit = useRef<HTMLButtonElement>(null)
  // A # that names a version shows it, in Versions, before the panel is first painted; the tab then stays there, as one
  // the person chose would
  useLayoutEffect(() => {
    if (version !== undefined) setTab('versions')
  }, [docNumber, version, setTab])
  // Edit Properties is grey while the form is open, and the keyboard's place is there once it's enabled again, not on the page
  const giveFocusBack = useRef(false)
  // The filing tray's Edit: once the properties are here, the form opens if the person may change any of them. It opens
  // once the Properties tab shows, which the request switches to before paint, so the form mounts showing and takes the
  // focus in its first field.
  const asked = useEditRequest(docNumber)
  const loaded = properties.data?.items
  if (asked && loaded && !editing && tab === 'properties' && loaded.some(isEditable)) setEditing(true)
  useEffect(() => {
    if (editing || !giveFocusBack.current) return
    giveFocusBack.current = false
    edit.current?.focus()
  }, [editing])
  useLayoutEffect(() => {
    if (!asked || !loaded) return
    if (tab !== 'properties') setTab('properties')
    else clearEditRequest()
  }, [asked, loaded, tab, setTab])

  if (failed(document)) return <ErrorPanel error={document.error} onRetry={() => void document.refetch()} />
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
        <Button onPress={() => void copyLink(doc.docNumber)}>{t('details.copyLink')}</Button>
      </div>
      <HoldInfo document={doc} />
      <CheckoutControls document={doc} />
      <Tabs
        label={t('details.tabs')}
        selected={tab}
        onSelect={(id) => setTab(id as DetailsTab)}
        tabs={[
          {
            id: 'properties',
            label: t('details.properties'),
            // An open form stays mounted on the Versions tab, with what's typed in it and any refusal of its save
            keepMounted: editing,
            content: (
              <>
                <div className={styles.tabTop}>
                  <Button ref={edit} isDisabled={editing || !properties.data?.items.some((field) => field.editable)} onPress={() => setEditing(true)}>
                    {t('details.editProperties')}
                  </Button>
                </div>
                {failed(properties) ? (
                  <p className={styles.note}>{t('details.propertiesUnavailable')}</p>
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
                  <p className={styles.note}>{t('common.loading')}</p>
                )}
              </>
            ),
          },
          {
            id: 'versions',
            label: versions.total === undefined ? t('details.versions') : plural('versionsTab', versions.total),
            content: versions.isError ? (
              <p className={styles.note}>{t('details.versionsUnavailable')}</p>
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
              <p className={styles.note}>{t('common.loading')}</p>
            ),
          },
        ]}
      />
    </div>
  )
}

/**
 * Whether a query has nothing to show: it never answered, or the document is gone or no longer readable (404). A refresh
 * that fails otherwise, as one on a return to the window can, keeps what's shown, and an open form with what's typed in it.
 */
function failed(query: { isError: boolean; data: unknown; error: unknown }): boolean {
  return query.isError && (query.data === undefined || (query.error instanceof ApiError && query.error.status === 404))
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
        <Icon {...fileIcon(document.name)} size={48} />
      )}
      <div className={styles.preview}>
        <Button onPress={() => quickLook.open(document.docNumber)}>{t('details.preview')}</Button>
      </div>
    </div>
  )
}
