// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Plugin } from 'vite'

export function pdfjsDataFiles(): { path: string; file: string }[]
export function pdfjsData(): Plugin
