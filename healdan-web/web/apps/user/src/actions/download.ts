// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type Api, type components } from '@healdan/api'
import { launchDownload } from './launch'

/**
 * Downloads a document's file, or one version's: a navigation to `/content` that the web session carries, which the server
 * records and names. `onSignedOut` hears of a session that has ended, which no request of the API client's would see.
 */
export function download(api: Api, document: components['schemas']['DocumentView'], version?: string, onSignedOut?: () => void): void {
  launchDownload(api.url(apiPath`/v1/documents/${document.docNumber}/content`, version ? { version } : undefined), onSignedOut)
}
