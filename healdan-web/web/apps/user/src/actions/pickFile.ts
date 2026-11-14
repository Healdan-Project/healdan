// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/**
 * Asks the browser's file picker for one file, from a control that has no picker of its own (a menu's item, which is gone
 * once it's chosen): a hidden field is added to the page for the choice and taken away after it. Undefined when the person
 * cancels. Called from the press that asks, as a browser opens a picker only for one.
 */
export function pickFile({ accept, label }: { accept?: string; label: string }): Promise<File | undefined> {
  return new Promise((resolve) => {
    const field = document.createElement('input')
    field.type = 'file'
    field.hidden = true
    field.setAttribute('aria-label', label)
    if (accept) field.accept = accept
    const done = (file: File | undefined) => {
      field.remove()
      resolve(file)
    }
    field.addEventListener('change', () => done(field.files?.[0]))
    field.addEventListener('cancel', () => done(undefined))
    document.body.append(field)
    field.click()
  })
}
