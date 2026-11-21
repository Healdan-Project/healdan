// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The File Type sort is two sorts that must agree. The browser sorts a one-page list by the names the File Type filter
// shows (compareRows.ts, through the catalogue's filters.type.* strings), and the server sorts a paged list by the order
// healdan-core's FileKinds.BY_NAME gives the kinds, which is those names' A to Z written out. A name changed in the
// catalogue changes the browser's order alone, so this fails until BY_NAME follows it.

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

const web = join(fileURLToPath(import.meta.url), '..', '..')
const catalogue = readFileSync(join(web, 'packages', 'ui', 'src', 'strings', 'en-US.ts'), 'utf8')
const fileKinds = readFileSync(join(web, '..', '..', 'healdan-core', 'src', 'main', 'java', 'com', 'logicaldoc', 'healdan', 'listing', 'FileKinds.java'), 'utf8')

/** The catalogue's name for each kind, by the kind: { word: 'Word', ... } */
function names() {
  const found = {}
  for (const [, kind, name] of catalogue.matchAll(/'filters\.type\.([a-z]+)':\s*'([^']*)'/g)) found[kind] = name
  return found
}

/** FileKinds.BY_NAME's kinds, in its order, each constant read as the word it holds */
function byName() {
  const words = {}
  for (const [, constant, word] of fileKinds.matchAll(/public static final String ([A-Z]+) = "([a-z]+)";/g)) words[constant] = word
  const list = /static final List<String> BY_NAME = List\.of\(([^)]*)\)/.exec(fileKinds)
  assert.ok(list, 'FileKinds.java declares BY_NAME as a List.of of its kinds')
  return list[1].split(',').map((constant) => {
    const word = words[constant.trim()]
    assert.ok(word, `BY_NAME's ${constant.trim()} is one of FileKinds' kinds`)
    return word
  })
}

test("the server's File Type order is the File Type filter's names, A to Z", () => {
  const labels = names()
  const kinds = byName()
  assert.deepEqual([...kinds].sort(), Object.keys(labels).sort(), 'BY_NAME holds exactly the kinds the catalogue names')
  // As compareRows.ts compares text: lower-cased, by UTF-16 code unit
  const key = (kind) => labels[kind].toLowerCase()
  const expected = [...kinds].sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0))
  assert.deepEqual(kinds, expected)
})
