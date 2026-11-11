// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, formatSize, formatVersion, Icon, t } from '@healdan/ui'
import { copyLink } from '../actions/copyLink'
import { fileIcon } from '../fileIcon'
import { ErrorPanel } from '../frame/ErrorPanel'
import { useDocument, useProperties, useVersions } from '../queries'
import { useApi } from '../services'
import { Properties, type DetailsContext } from './Properties'
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
 * properties and its versions. Open, Download, Edit and Preview wait for the tasks that build them.
 */
export function DetailsPanel({ docNumber, row, version, context = 'folder' }: DetailsPanelProps) {
  const document = useDocument(docNumber, row)
  const properties = useProperties(docNumber)
  const versions = useVersions(docNumber)
  const doc = document.data

  if (document.isError) return <ErrorPanel error={document.error} onRetry={() => void document.refetch()} />
  if (!doc) return <p className={styles.note}>{t('common.loading')}</p>

  return (
    <div className={styles.panel}>
      <Thumbnail key={doc.docNumber} document={doc} />
      <h2 className={styles.name}>{doc.name}</h2>
      <p className={styles.meta}>{t('details.meta', { n: doc.docNumber, version: formatVersion(doc.fileVersion), size: formatSize(doc.size) })}</p>
      <div className={styles.actions}>
        <Button isDisabled>{t('details.open')}</Button>
        <Button isDisabled>{t('details.download')}</Button>
        <Button isDisabled>{t('details.edit')}</Button>
        <Button onPress={() => void copyLink(doc.docNumber)}>{t('details.copyLink')}</Button>
      </div>
      {properties.isError ? (
        <Section title={t('details.properties')}>
          <p className={styles.note}>{t('details.propertiesUnavailable')}</p>
        </Section>
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
      ) : versions.data ? (
        <Versions versions={versions.data.items} selected={version} />
      ) : (
        <Section title={t('details.versions')}>
          <p className={styles.note}>{t('common.loading')}</p>
        </Section>
      )}
    </div>
  )
}

// The server's thumbnail, a PNG of the first page. A document with none yet (404), or one the person can't open, shows
// the icon of its kind instead of a broken image.
function Thumbnail({ document }: { document: S['DocumentView'] }) {
  const api = useApi()
  const [failed, setFailed] = useState(false)
  return (
    <div className={styles.thumb}>
      {document.canOpen && !failed ? (
        <img src={api.url(apiPath`/v1/documents/${document.docNumber}/thumbnail`)} alt="" className={styles.image} onError={() => setFailed(true)} />
      ) : (
        <Icon name={fileIcon(document.name)} size={48} />
      )}
      <div className={styles.preview}>
        <Button isDisabled>{t('details.preview')}</Button>
      </div>
    </div>
  )
}
