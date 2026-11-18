// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type Api, type components } from '@healdan/api'

type S = components['schemas']

/** Moves a document to the Trash, and answers the Trash item the delete made, whose id a restore takes. */
export function removeDocument(api: Api, docNumber: number): Promise<S['TrashItem']> {
  return api.send('DELETE', apiPath`/v1/documents/${docNumber}`)
}
