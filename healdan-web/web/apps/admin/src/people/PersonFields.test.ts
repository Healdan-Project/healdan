// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { draftProblems, draftReady, type PersonDraft } from './PersonFields'

const draft = (over: Partial<PersonDraft> = {}): PersonDraft => ({ firstName: 'Ann', lastName: 'Lee', email: '', language: 'en', timeZone: '', ...over })
const emailProblem = (email: string) => draftProblems(draft({ email })).email

describe("A person's fields", () => {
  it("take an email the server takes: an @ that's neither first nor last, with no space or control character", () => {
    for (const email of ['ann@intranet', 'root@localhost', 'ann@example.test', 'a@b@c', ' ann@intranet ', 'ann.lee+work@example.test'])
      expect(emailProblem(email), email).toBeUndefined()
    // A no-break space isn't one the server refuses
    expect(emailProblem('ann\u00a0lee@intranet')).toBeUndefined()
  })

  it("refuse an email the server refuses, saying it isn't one", () => {
    for (const email of ['ann', 'ann@', '@intranet', 'ann@@', 'ann lee@intranet', 'ann@intra\tnet', 'ann@intra\u0085net', 'ann@intra\u3000net', 'ann@intra\u2028net', 'ann@\ud800x'])
      expect(emailProblem(email), JSON.stringify(email)).toBe("That isn't an email address.")
  })

  it('leave an email as it is when it is the one the form opened with, as a name stored blank is', () => {
    const opened = draft({ email: 'jamie at the office' })
    expect(draftProblems(opened).email).toBe("That isn't an email address.")
    expect(draftProblems({ ...opened, firstName: 'Jamie' }, opened).email).toBeUndefined()
    expect(draftReady({ ...opened, firstName: 'Jamie' }, {}, opened)).toBe(true)
    // Once it's changed, it's judged
    expect(draftProblems({ ...opened, email: 'jamie at home' }, opened).email).toBe("That isn't an email address.")
  })
})
