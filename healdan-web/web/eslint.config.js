// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['**/dist/**', '**/node_modules/**', 'packages/api/src/schema.d.ts'] },
  ...tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,
  {
    rules: {
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
