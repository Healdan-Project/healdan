// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type Api, type components } from '@healdan/api'
import { launch } from './launch'

/** Downloads a document's file, or one version's: a link to `/content` that the web session carries, which the server records. */
export function download(api: Api, document: components['schemas']['DocumentView'], version?: string): void {
  launch(api.url(apiPath`/v1/documents/${document.docNumber}/content`, version ? { version } : undefined), document.name)
}
