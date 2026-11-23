// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/**
 * An answer the server gives only when the test says, as a slow one would be: a fake route returns `answered`, and the test
 * calls `answer` with the body, or `refuse` with the error, when it wants the request to end
 */
export function slowAnswer<T>() {
  let answer!: (value: T) => void
  let refuse!: (error: unknown) => void
  const answered = new Promise<T>((resolve, reject) => {
    answer = resolve
    refuse = reject
  })
  return { answered, answer, refuse }
}
