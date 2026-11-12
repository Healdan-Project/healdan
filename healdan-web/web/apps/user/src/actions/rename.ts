// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, apiPath, type Api, type components } from '@healdan/api'
import { t } from '@healdan/ui'
import { fieldErrors, messageFor } from '../frame/errors'

type S = components['schemas']

export function renameDocument(api: Api, docNumber: number, name: string): Promise<S['DocumentView']> {
  return api.send('PATCH', apiPath`/v1/documents/${docNumber}`, { name })
}

/** What follows the last dot of a name that has something before it */
export function extensionOf(name: string): string | undefined {
  const dot = name.lastIndexOf('.')
  return dot > 0 && dot < name.length - 1 ? name.slice(dot + 1) : undefined
}

/**
 * What to tell the person of a refused rename, from the catalogue and the code the server gives the name: the server
 * decides what is wrong, so another extension (`extension`) is worded as what to do about it, and any other (`invalid`,
 * `too-long`, `required`) is the catalogue's message for the code.
 */
export function renameRefusal(error: unknown, current: string): string {
  if (error instanceof ApiError && error.code === 'validation') {
    const fields = (error.body as { fields?: { field?: unknown; code?: unknown }[] } | null)?.fields
    if (fields?.some((one) => one?.field === 'name' && one.code === 'extension')) {
      const ext = extensionOf(current)
      return ext ? t('rename.extension', { ext }) : t('rename.noExtension')
    }
    const named = fieldErrors(error).get('name')
    if (named) return named
  }
  return messageFor(error)
}
