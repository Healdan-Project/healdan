// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// What the server shows as a picture's own bytes (healdan-core's MediaTypes, which the app can't share, and which has the
// last word): the pictures every browser the app supports draws, by extension, with their media types. A TIFF or a HEIC
// isn't one: Chromium and Firefox draw neither, and Safari draws only a TIFF's first page, so they preview as a PDF.
const DRAWN: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  svg: 'image/svg+xml',
}
const DRAWN_TYPES = new Set(Object.values(DRAWN))

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/** Whether a file of this name previews as itself: a picture every browser draws */
export function isDrawnPicture(name: string): boolean {
  return Object.hasOwn(DRAWN, extensionOf(name))
}

/**
 * What an email's attachment is shown as in place (R56): a PDF or a picture every browser draws, by the type the sender
 * gave it or, as many mailers send any file as `application/octet-stream`, by its name; anything else downloads.
 */
export function attachmentShownAs(type: string, name: string): 'pdf' | 'picture' | undefined {
  if (type === 'application/pdf') return 'pdf'
  if (DRAWN_TYPES.has(type)) return 'picture'
  const extension = extensionOf(name)
  if (extension === 'pdf') return 'pdf'
  return Object.hasOwn(DRAWN, extension) ? 'picture' : undefined
}
