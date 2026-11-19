// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { readdirSync } from 'node:fs'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'

const web = dirname(fileURLToPath(import.meta.url))
const apps = join(web, 'apps')
// The workspace's packages, each @healdan/<its folder>. Any other @healdan/<name> is an app's.
const packages = new Set(readdirSync(join(web, 'packages'), { withFileTypes: true }).filter((entry) => entry.isDirectory()).map((entry) => entry.name))

// The app a file is in (its folder under apps/), or undefined when it's in none
function appOf(path) {
  const inApps = relative(apps, path)
  return inApps === '' || inApps.startsWith('..') || isAbsolute(inApps) ? undefined : inApps.split(sep)[0]
}

// No app imports another's code, by a relative path or by its package (@healdan/<app>): what two apps need goes into a
// package, so neither app's build holds the other's.
const noAppImportsAnother = {
  meta: {
    type: 'problem',
    schema: [],
    messages: { another: "An app imports no other app's code ({{app}}): what both need goes into a package, such as packages/shell." },
  },
  create(context) {
    const own = appOf(context.filename)
    if (own === undefined) return {}
    // The app an import reaches, or undefined when it reaches none
    function appImported(specifier) {
      if (specifier.startsWith('.')) return appOf(resolve(dirname(context.filename), specifier))
      const name = /^@healdan\/([^/]+)/.exec(specifier)?.[1]
      return name !== undefined && !packages.has(name) ? name : undefined
    }
    function check(source) {
      if (source?.type !== 'Literal' || typeof source.value !== 'string') return
      const app = appImported(source.value)
      if (app !== undefined && app !== own) context.report({ node: source, messageId: 'another', data: { app } })
    }
    return {
      ImportDeclaration: (node) => check(node.source),
      ExportNamedDeclaration: (node) => check(node.source),
      ExportAllDeclaration: (node) => check(node.source),
      ImportExpression: (node) => check(node.source),
    }
  },
}

export default tseslint.config(
  { ignores: ['**/dist/**', '**/node_modules/**', 'packages/api/src/schema.d.ts'] },
  ...tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,
  {
    plugins: { healdan: { rules: { 'no-app-imports-another': noAppImportsAnother } } },
    rules: {
      'healdan/no-app-imports-another': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: "dangerouslySetInnerHTML is forbidden: the server's text is never HTML.",
        },
      ],
    },
  },
  {
    // Screens use @healdan/ui's components; only that package may import React Aria Components.
    ignores: ['packages/ui/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['react-aria-components', 'react-aria-components/*', 'react-aria', 'react-aria/*', 'react-stately', 'react-stately/*', '@react-aria/*', '@react-stately/*'],
              message: "Import components from '@healdan/ui'; only packages/ui wraps React Aria.",
            },
          ],
        },
      ],
    },
  },
)
