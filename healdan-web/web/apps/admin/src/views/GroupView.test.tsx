// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import {
  adminGroup,
  everyone,
  feeEarners,
  groupDetailOf,
  groupDetails,
  groupRoutes,
  guestGroup,
  me,
  memberList,
  partners,
  priyaRow,
  problem,
  publisherGroup,
  samRow,
  trainees,
  userRow,
  memberOf,
  type Routes,
} from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']
type Sent = { body?: unknown }

const main = () => screen.getByRole('main')
const facts = () => Array.from(main().querySelector('dl')!.querySelectorAll('dt'), (term) => [term.textContent, term.nextElementSibling?.textContent])
const action = (name: string) => within(main()).queryByRole('button', { name })
const isDisabled = (button: HTMLElement | null) => button?.getAttribute('disabled') !== null
/** What a button's description says: the sentence its aria-describedby names */
const why = (button: HTMLElement | null) => document.getElementById(button?.getAttribute('aria-describedby') ?? '')?.textContent
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const membersOf = (name: string) => within(main()).getByRole('table', { name: `Members of ${name}` })
const memberRows = (name: string) => within(membersOf(name)).getAllByRole('row').slice(1).map((row) => Array.from(row.children, (cell) => cell.textContent))
const memberQueries = (api: { callsTo: (pattern: string) => { query?: Record<string, unknown> }[] }, id: number) => api.callsTo(`GET /v1/admin/groups/${id}/members`).map((call) => call.query)

/** Where a group's details are, by id */
function detailOf(id: number): A['GroupDetail'] {
  return groupDetails[id]!
}

async function openGroup(id: number, over: Routes = {}) {
  const rendered = await renderApp(`/groups/${id}`, { ...groupRoutes(), ...over })
  await within(main()).findByRole('heading', { level: 1 })
  return rendered
}

/** A person found by the Add Member dialog's search, once the typing pauses and its list opens */
async function pickPerson(dialog: HTMLElement, typed: string, option: string) {
  await userEvent.type(within(dialog).getByRole('combobox', { name: 'Person' }), typed)
  await pause()
  await userEvent.click(await screen.findByRole('option', { name: option }))
}

describe("A group's page", () => {
  it('shows its description, members and the folders that grant it something, then its members, each with Remove', async () => {
    const { api, view } = await openGroup(trainees.id)
    expect(within(main()).getByRole('navigation', { name: 'Breadcrumb' }).textContent).toBe('GroupsTrainees')
    expect(within(main()).getByRole('heading', { level: 1 }).textContent).toBe('Trainees')
    expect(facts()).toEqual([
      ['Description', 'Trainee solicitors'],
      ['Members', '3'],
      ['Folders That Grant It', '3'],
    ])
    expect(['Rename', 'Edit Description', 'Delete', 'Add Member'].map((name) => isDisabled(action(name)))).toEqual([false, false, false, false])
    await within(main()).findByRole('table', { name: 'Members of Trainees' })
    expect(memberRows('Trainees')).toEqual([
      ['Jamie Clarke', 'jclarke', 'Blocked', 'Remove'],
      ['Priya Nair', 'priya', 'Active', 'Remove'],
      ['Sally Oldham', 'sally.old', 'Disabled', 'Remove'],
    ])
    // A member's row opens their page
    expect(within(membersOf('Trainees')).getByRole('link', { name: 'Priya Nair' }).getAttribute('href')).toBe(`/admin/users/${priyaRow.id}`)
    expect(within(membersOf('Trainees')).getByRole('button', { name: 'Remove Priya Nair' })).toBeTruthy()
    expect(memberQueries(api, trainees.id)).toEqual([{ offset: 0, limit: 100 }])
    await expectNoAxeViolations(view.container)
  })

  it("greys out Rename and Delete for a fixed group, saying why, and keeps admin's account and the caller in admin", async () => {
    const { view } = await openGroup(adminGroup.id, { 'GET /v1/me': { ...me, id: samRow.id, username: samRow.username, name: samRow.name } })
    expect(isDisabled(action('Rename'))).toBe(true)
    expect(isDisabled(action('Delete'))).toBe(true)
    expect(isDisabled(action('Edit Description'))).toBe(false)
    expect(within(main()).getByText('admin is one of the fixed groups: it keeps its name and is never deleted. Its description can change.')).toBeTruthy()
    // Its one sentence tells a screen reader why for both greyed actions
    expect(why(action('Rename'))).toBe('admin is one of the fixed groups: it keeps its name and is never deleted. Its description can change.')
    expect(why(action('Delete'))).toBe('admin is one of the fixed groups: it keeps its name and is never deleted. Its description can change.')
    expect(action('Edit Description')?.getAttribute('aria-describedby')).toBeNull()
    expect(within(main()).getByText('Members of admin can use Healdan Admin and read every document, in every matter and Store.')).toBeTruthy()
    await within(main()).findByRole('table', { name: 'Members of admin' })
    expect(memberRows('admin')).toEqual([
      ['Alex Morgan', 'admin', 'Active', 'The admin account is always in admin.'],
      ['Sam Okafor', 'sam', 'Active', 'Another administrator can take you out of admin.'],
    ])
    expect(within(main()).queryByRole('button', { name: /^Remove/ })).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it('offers no Add Member or Remove on Everyone, which every account but a read-only one is always in', async () => {
    const { view } = await openGroup(everyone.id)
    expect(isDisabled(action('Rename'))).toBe(true)
    expect(isDisabled(action('Delete'))).toBe(true)
    expect(action('Add Member')).toBeNull()
    expect(within(main()).getByText('Every account but a read-only one is in Everyone, so nobody is added to it or taken out of it here.')).toBeTruthy()
    const members = await within(main()).findByRole('table', { name: 'Members of Everyone' })
    expect(within(members).getAllByRole('row').slice(1)).toHaveLength(6)
    expect(within(members).queryByRole('button')).toBeNull()
    expect(within(members).queryByRole('columnheader', { name: 'Actions' })).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it("opens LogicalDOC's publisher, whose id is negative, as a fixed group", async () => {
    const { api } = await openGroup(publisherGroup.id)
    expect(within(main()).getByRole('heading', { level: 1 }).textContent).toBe('publisher')
    expect(isDisabled(action('Rename'))).toBe(true)
    expect(isDisabled(action('Delete'))).toBe(true)
    expect(await within(main()).findByText('Nobody is in this group.')).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/groups/-10000')).toHaveLength(1)
    expect(memberQueries(api, publisherGroup.id)).toEqual([{ offset: 0, limit: 100 }])
  })

  it('keeps a read-only account in guest', async () => {
    await openGroup(guestGroup.id)
    await within(main()).findByRole('table', { name: 'Members of guest' })
    expect(memberRows('guest')).toEqual([['Ronnie Hale Read-Only', 'rhale', 'Active', 'A read-only account is always in guest.']])
  })

  it("says what a sign-in provider's group map sets again, and why the group isn't deleted", async () => {
    const { view } = await openGroup(feeEarners.id)
    expect(facts()).toContainEqual(['Sign-In Providers', 'Authentik'])
    expect(
      within(main()).getByText(
        "Authentik's group map names Fee Earners: someone who signs in through it is put in it, or taken out, at each sign-in there, so a change made here for them is set again at their next sign-in.",
      ),
    ).toBeTruthy()
    expect(isDisabled(action('Delete'))).toBe(true)
    expect(isDisabled(action('Rename'))).toBe(false)
    expect(within(main()).getByText("Authentik's group map names Fee Earners, so it isn't deleted: take it out of the map first, on the Sign-In tab of Healdan Settings in the Classic Interface.")).toBeTruthy()
    expect(why(action('Delete'))).toBe("Authentik's group map names Fee Earners, so it isn't deleted: take it out of the map first, on the Sign-In tab of Healdan Settings in the Classic Interface.")
    expect(action('Rename')?.getAttribute('aria-describedby')).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it('greys out Delete for a name too long for a deleted group to keep, saying how short it has to be', async () => {
    await openGroup(partners.id, { 'GET /v1/admin/groups/3': groupDetailOf({ ...partners, name: 'P'.repeat(254), members: 1 }) })
    expect(isDisabled(action('Delete'))).toBe(true)
    expect(within(main()).getByText('Its name is too long for a deleted group to keep: rename it to 253 characters or fewer, then delete it.')).toBeTruthy()
  })

  it('renames it, saying before the button that another group has the name, in any case', async () => {
    let sent: unknown
    const { view } = await openGroup(partners.id, {
      'PATCH /v1/admin/groups/3': ({ body }: Sent) => {
        sent = body
        return { ...detailOf(partners.id), name: 'Senior Partners' }
      },
    })
    await userEvent.click(action('Rename')!)
    const dialog = await screen.findByRole('dialog', { name: 'Rename Partners' })
    const name = within(dialog).getByRole('textbox', { name: 'Name' }) as HTMLInputElement
    const rename = within(dialog).getByRole('button', { name: 'Rename' })
    expect(name.value).toBe('Partners')
    expect(isDisabled(rename)).toBe(true)
    await userEvent.clear(name)
    await userEvent.type(name, 'trainees')
    await pause()
    expect(await within(dialog).findByText("There's already a group called Trainees.")).toBeTruthy()
    expect(isDisabled(rename)).toBe(true)
    await userEvent.clear(name)
    await userEvent.type(name, 'Senior Partners ')
    await pause()
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(rename)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rename Partners' })).toBeNull())
    expect(sent).toEqual({ name: 'Senior Partners' } satisfies A['GroupChange'])
    expect(within(main()).getByRole('heading', { level: 1 }).textContent).toBe('Senior Partners')
    expect(await screen.findByText('Renamed Partners to Senior Partners')).toBeTruthy()
  })

  it("renames it to its own name in another case, which isn't taken", async () => {
    let sent: unknown
    await openGroup(partners.id, {
      'PATCH /v1/admin/groups/3': ({ body }: Sent) => {
        sent = body
        return { ...detailOf(partners.id), name: 'PARTNERS' }
      },
    })
    await userEvent.click(action('Rename')!)
    const dialog = await screen.findByRole('dialog', { name: 'Rename Partners' })
    const name = within(dialog).getByRole('textbox', { name: 'Name' })
    await userEvent.clear(name)
    await userEvent.type(name, 'PARTNERS')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    await waitFor(() => expect(sent).toEqual({ name: 'PARTNERS' }))
  })

  it('changes its description, a blank one taking it away', async () => {
    const sent: unknown[] = []
    const { view } = await openGroup(partners.id, {
      'PATCH /v1/admin/groups/3': ({ body }: Sent) => {
        sent.push(body)
        const description = (body as A['GroupChange']).description ?? null
        return { ...detailOf(partners.id), description }
      },
    })
    await userEvent.click(action('Edit Description')!)
    let dialog = await screen.findByRole('dialog', { name: 'Edit the Description of Partners' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Description' }), ' The partners ')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(facts()).toContainEqual(['Description', 'The partners'])
    expect(await screen.findByText('Saved the description of Partners')).toBeTruthy()

    await userEvent.click(action('Edit Description')!)
    dialog = await screen.findByRole('dialog', { name: 'Edit the Description of Partners' })
    expect((within(dialog).getByRole('textbox', { name: 'Description' }) as HTMLTextAreaElement).value).toBe('The partners')
    await userEvent.clear(within(dialog).getByRole('textbox', { name: 'Description' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(sent).toEqual([{ description: 'The partners' }, { description: null }] satisfies A['GroupChange'][])
    expect(facts()).toContainEqual(['Description', 'None'])
  })

  it('asks before Delete, naming its members and the folders that grant it something, then goes back to the list', async () => {
    const { api, router, view } = await openGroup(trainees.id, { 'DELETE /v1/admin/groups/4': undefined })
    await userEvent.click(action('Delete')!)
    const question = await screen.findByRole('alertdialog', { name: 'Delete Trainees?' })
    expect(question.textContent).toContain('Its 3 members are taken out of it, and lose what it gives them.')
    expect(question.textContent).toContain("3 folders give it a permission of their own: once it's deleted, nobody gets those permissions through it.")
    expect(question.textContent).toContain("This can't be undone.")
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Delete Group' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/groups'))
    expect(api.callsTo('DELETE /v1/admin/groups/4')).toHaveLength(1)
    expect(await screen.findByText('Deleted Trainees')).toBeTruthy()
  })

  it("says an empty group's delete takes nobody out, and keeps the question open with the server's sentence", async () => {
    await openGroup(partners.id, {
      'GET /v1/admin/groups/3': { ...detailOf(partners.id), members: 0, folders: 0 },
      'GET /v1/admin/groups/3/members': memberList({}, []),
      'DELETE /v1/admin/groups/3': problem(409, 'refused', 'A sign-in provider maps this group now.'),
    })
    await userEvent.click(action('Delete')!)
    const question = await screen.findByRole('alertdialog', { name: 'Delete Partners?' })
    expect(question.textContent).toContain('Nobody is in it.')
    expect(question.textContent).toContain('No folder gives it a permission of its own.')
    await userEvent.click(within(question).getByRole('button', { name: 'Delete Group' }))
    expect((await within(question).findByRole('alert')).textContent).toBe('A sign-in provider maps this group now.')
  })

  it('says so when the address names no group', async () => {
    const { view } = await renderApp('/groups/999', groupRoutes())
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Group Not Found' })).toBeTruthy()
    expect(within(main()).getByRole('link', { name: 'Go to Groups' }).getAttribute('href')).toBe('/admin/groups')
    view.unmount()
    await renderApp('/groups/x7', groupRoutes())
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Group Not Found' })).toBeTruthy()
  })

  it("keeps the page, and a dialog open on it, while a new read fails, and says it couldn't refresh (P4-R15)", async () => {
    let failing = false
    await openGroup(partners.id, { 'GET /v1/admin/groups/3': () => (failing ? problem(500, 'server-error') : detailOf(partners.id)) })
    await userEvent.click(action('Rename')!)
    const dialog = await screen.findByRole('dialog', { name: 'Rename Partners' })
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Name' }), { target: { value: 'Equity Partners' } })
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findByText("Couldn't refresh this, so it shows what was read last.")).toBeTruthy()
    expect(screen.getByRole('dialog', { name: 'Rename Partners' })).toBe(dialog)
    expect((within(dialog).getByRole('textbox', { name: 'Name' }) as HTMLInputElement).value).toBe('Equity Partners')
  })
})

describe("A group's members", () => {
  it('keeps those whose name, username or email holds the words typed, once the typing pauses', async () => {
    const { api } = await openGroup(trainees.id)
    await within(main()).findByRole('table', { name: 'Members of Trainees' })
    await userEvent.type(within(main()).getByRole('textbox', { name: 'Name, Username or Email' }), ' pri ')
    await pause()
    await waitFor(() => expect(memberQueries(api, trainees.id).at(-1)).toEqual({ q: 'pri', offset: 0, limit: 100 }))
    await waitFor(() => expect(memberRows('Trainees').map((row) => row[0])).toEqual(['Priya Nair']))
    await userEvent.type(within(main()).getByRole('textbox', { name: 'Name, Username or Email' }), 'zzz')
    await pause()
    expect(await within(main()).findByText('No member matches these words.')).toBeTruthy()
  })

  it('shows 100 at a time, with Show More for the next', async () => {
    const many = Array.from({ length: 150 }, (_, i) => memberOf(userRow({ id: 2000 + i, username: `p${i}`, name: `Person ${String(i + 1).padStart(3, '0')}` })))
    const { api } = await openGroup(trainees.id, { 'GET /v1/admin/groups/4/members': ({ query }: { query?: Record<string, unknown> }) => memberList(query, many) })
    await within(main()).findByRole('table', { name: 'Members of Trainees' })
    expect(memberRows('Trainees')).toHaveLength(100)
    expect(within(main()).getByText('100 of 150 shown')).toBeTruthy()
    await userEvent.click(within(main()).getByRole('button', { name: 'Show More' }))
    await waitFor(() => expect(memberRows('Trainees')).toHaveLength(150))
    expect(within(main()).queryByRole('button', { name: 'Show More' })).toBeNull()
    expect(memberQueries(api, trainees.id).map((query) => [query?.offset, query?.limit])).toEqual([
      [0, 100],
      [100, 100],
    ])
  })

  it('adds a person found by name, then reads the members again', async () => {
    let sent = 0
    const { api, view } = await openGroup(partners.id, {
      'PUT /v1/admin/groups/3/members/7': () => {
        sent += 1
        return undefined
      },
    })
    await userEvent.click(action('Add Member')!)
    const dialog = await screen.findByRole('dialog', { name: 'Add a Member to Partners' })
    const add = within(dialog).getByRole('button', { name: 'Add' })
    expect(isDisabled(add)).toBe(true)
    await pickPerson(dialog, 'priya', 'Priya Nair priya')
    await pause()
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(add)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add a Member to Partners' })).toBeNull())
    expect(sent).toBe(1)
    expect(await screen.findByText('Added Priya Nair to Partners')).toBeTruthy()
    await waitFor(() => expect(memberQueries(api, partners.id).filter((query) => query?.q === undefined).length).toBeGreaterThan(1))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/groups/3').length).toBeGreaterThan(1))
  })

  it("says before the button that someone is in the group already", async () => {
    await openGroup(partners.id)
    await userEvent.click(action('Add Member')!)
    const dialog = await screen.findByRole('dialog', { name: 'Add a Member to Partners' })
    await pickPerson(dialog, 'helen', 'Helen Ashcombe helen')
    expect(await within(dialog).findByText('Helen Ashcombe is in Partners already.')).toBeTruthy()
    expect(isDisabled(within(dialog).getByRole('button', { name: 'Add' }))).toBe(true)
  })

  it("asks before adding someone to admin, saying they'll read every document, and Cancel sends nothing", async () => {
    let sent = 0
    const { view } = await openGroup(adminGroup.id, {
      'PUT /v1/admin/groups/1/members/7': () => {
        sent += 1
        return undefined
      },
    })
    await userEvent.click(action('Add Member')!)
    const dialog = await screen.findByRole('dialog', { name: 'Add a Member to admin' })
    await pickPerson(dialog, 'priya', 'Priya Nair priya')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    let question = await screen.findByRole('alertdialog', { name: 'Add Priya Nair to admin?' })
    expect(question.textContent).toContain('Members of admin can use Healdan Admin and read every document')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(sent).toBe(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    question = await screen.findByRole('alertdialog', { name: 'Add Priya Nair to admin?' })
    await userEvent.click(within(question).getByRole('button', { name: 'Add to admin' }))
    expect(await screen.findByText('Added Priya Nair to admin')).toBeTruthy()
    expect(sent).toBe(1)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it("keeps Add Member open with the server's sentence when it refuses, as for a read-only account", async () => {
    await openGroup(partners.id, { 'PUT /v1/admin/groups/3/members/13': problem(409, 'refused', "A read-only account is in no group but its own and guest.") })
    await userEvent.click(action('Add Member')!)
    const dialog = await screen.findByRole('dialog', { name: 'Add a Member to Partners' })
    await pickPerson(dialog, 'ronnie', 'Ronnie Hale rhale')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('A read-only account is in no group but its own and guest.')
  })

  it("says in Add Member that a sign-in provider's group map sets the membership again", async () => {
    await openGroup(feeEarners.id)
    await userEvent.click(action('Add Member')!)
    const dialog = await screen.findByRole('dialog', { name: 'Add a Member to Fee Earners' })
    expect(within(dialog).getByText("Authentik's group map names Fee Earners: for someone who signs in through it, their membership is set again at their next sign-in there.")).toBeTruthy()
  })

  it('asks before taking someone out, then reads the members again', async () => {
    const { api, view } = await openGroup(trainees.id, { 'DELETE /v1/admin/groups/4/members/7': undefined })
    await within(main()).findByRole('table', { name: 'Members of Trainees' })
    await userEvent.click(within(membersOf('Trainees')).getByRole('button', { name: 'Remove Priya Nair' }))
    const question = await screen.findByRole('alertdialog', { name: 'Take Priya Nair Out of Trainees?' })
    expect(question.textContent).toContain('They lose what Trainees gives them, unless another of their groups gives it too.')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Remove' }))
    expect(await screen.findByText('Took Priya Nair out of Trainees')).toBeTruthy()
    expect(api.callsTo('DELETE /v1/admin/groups/4/members/7')).toHaveLength(1)
    await waitFor(() => expect(memberQueries(api, trainees.id).length).toBeGreaterThan(1))
  })

  it("keeps Remove's question open with the server's sentence, as for the last enabled administrator", async () => {
    await openGroup(adminGroup.id, { 'DELETE /v1/admin/groups/1/members/10': problem(409, 'refused', 'That would leave no enabled administrator.') })
    await within(main()).findByRole('table', { name: 'Members of admin' })
    await userEvent.click(within(membersOf('admin')).getByRole('button', { name: 'Remove Sam Okafor' }))
    const question = await screen.findByRole('alertdialog', { name: 'Take Sam Okafor Out of admin?' })
    expect(question.textContent).toContain('They can no longer use Healdan Admin, nor read every document.')
    await userEvent.click(within(question).getByRole('button', { name: 'Remove' }))
    expect((await within(question).findByRole('alert')).textContent).toBe('That would leave no enabled administrator.')
  })
})

describe('The groups a page names', () => {
  it("link to each group's page from a person's page", async () => {
    await renderApp(`/users/${priyaRow.id}`, groupRoutes())
    const list = await within(main()).findByRole('list', { name: 'Groups of Priya Nair' })
    expect(within(list).getByRole('link', { name: 'Trainees' }).getAttribute('href')).toBe(`/admin/groups/${trainees.id}`)
    expect(within(list).getByRole('link', { name: 'Everyone' }).getAttribute('href')).toBe(`/admin/groups/${everyone.id}`)
  })

  it('include Groups under People in the left pane', async () => {
    await renderApp('/groups', groupRoutes())
    const pane = screen.getByRole('navigation', { name: 'Navigation' })
    const people = within(pane).getByRole('region', { name: 'People' })
    expect(within(people).getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Users', '/admin/users'],
      ['Groups', '/admin/groups'],
    ])
    expect(within(people).getByRole('link', { name: 'Groups' }).getAttribute('aria-current')).toBe('page')
  })
})
