// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type Api, type components } from '@healdan/api'
import { eachDocument, type Movable, type Outcome } from './batch'

type S = components['schemas']

export function moveDocument(api: Api, docNumber: number, folderId: number): Promise<S['DocumentView']> {
  return api.send('POST', apiPath`/v1/documents/${docNumber}/move`, { folderId })
}

/** Moves each document into the folder with a call of its own; a refused one doesn't stop the others. */
export function moveDocuments(api: Api, documents: readonly Movable[], folderId: number): Promise<Outcome> {
  return eachDocument(documents, (document) => moveDocument(api, document.docNumber, folderId))
}
