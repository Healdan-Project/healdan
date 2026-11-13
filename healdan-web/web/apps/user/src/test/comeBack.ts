// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act } from '@testing-library/react'

/** The person coming back to the window from another app: the window's own focus, which queryClient.ts listens for */
export const comeBack = () => act(() => void window.dispatchEvent(new Event('focus')))

/** A moment for whatever was to be asked to be asked, before a test checks that something wasn't */
export const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)))
