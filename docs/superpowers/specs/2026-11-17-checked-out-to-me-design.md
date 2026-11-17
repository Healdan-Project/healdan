# Checked Out to Me in the User app: design

- **Date:** 2026-11-17
- **Status:** design agreed with the owner on 2026-11-17, question by question and then through a
  [mockup](2026-11-17-checked-out-to-me-mockup.html) in the browser; the owner approved this written spec the same day.
  The mockup is the page the owner saw, with a short head added so it opens on its own. Where it and this spec differ,
  this spec wins.
- **Inputs:**
  - the owner's request, 2026-11-17: a "Checked Out Documents" view in the User app showing every document checked out
    by the person signed in, for "a quick review of any work that may be in progress or just not checked in" (working on
    several machines, one offline), and Release from the browser for "the off case": a laptop stolen with documents
    checked out on it, and a replacement machine to carry on from;
  - the [M6 spec](2026-11-10-m6-user-app-design.md) §6.1 (the frame), §6.2 (Check-Out Info), §6.7 (right-click menus),
    §8.1 (the web session is no device, R14) and §16 (later phases);
  - the [M3 spec](2026-10-24-m3-office-integration-design.md) §5.10 (a copy whose check-out ended) and §9.4 (check-outs
    held on a device);
  - a survey of this repository at `556e26f46`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard
and approved. "The app" is the User app at `/app/`; "CE" is LogicalDOC Community Edition; "the Helper" is the Healdan
Helper.

## 1. Intent

The Helper checks documents out on each computer it runs on, and a check-out can outlive the work: a document left open
on the office iMac, one checked out on a laptop that's now offline, or one nobody checked in. The app has no list of
them. This adds **Checked Out to Me**, a view of every document checked out to the person, with the computer and app
that hold each one.

It also lets the person release a check-out that another of their computers holds, from the browser. Today only that
computer, or an administrator in the Classic Interface, can. The case is a laptop stolen with documents checked out on
it: the laptop never comes back, and the person carries on from a replacement machine (owner). Because releasing throws
away whatever that computer hadn't saved to Healdan, the app asks first, with a warning that says so plainly (owner: "a
big scary warning dialog").

## 2. Scope

**In** (owner):

- the view, and its entry at the top of the left pane with a count;
- Release for a check-out held on another of the person's computers, everywhere the app offers Release, with the warning
  first;
- the API's allowance for it: a flag on the existing release.

**Out:**

- **Locks.** The list is check-outs only, as `GET /v1/me/checkouts` is; a lock taken with the Classic Interface's Lock
  stays the Classic Interface's (M6 §16 has locking as a later phase) (call).
- **Other people's check-outs.** An administrator still releases someone else's in the Classic Interface, with Unlock.
  The app offers Release only on the person's own check-outs (call).
- **Taking a check-out over into the browser.** R14 stands: the web page holds no check-out as a device does. The
  Helper's Edit Here Instead stays the way to carry on editing on the replacement computer (call).
- **Revoking from the warning.** The warning points to Signed-in Devices and opens it; revoking stays there (call).

**Anti-goal:** a check-out another computer holds released without the person having seen the warning. The API refuses
such a release unless the request says it was meant (§4).

## 3. What exists

- **The list is already in the API.** `GET /v1/me/checkouts` (`MeResource.checkouts`) returns the documents the caller
  has checked out (`DocumentStatus.CHECKEDOUT`, so no locks) and can still read, latest `hold.since` first, as one page
  with its `total`. `ListsTest.testCheckouts` covers it, and the Helper's Open window uses it for its own **Checked Out
  to Me** place. The app never has.
- **Each document says where it's held.** `hold.heldOn` names the computer (the token's name, such as `Healdan Helper on
  MacBook`) and the app, for the holder and administrators. It's null for a check-out made from the web page, which no
  device holds. The details' Check-Out Info (`details/HoldInfo.tsx`) shows both.
- **The release refuses another computer's check-out.** `DELETE /v1/documents/{docNumber}/checkout`
  (`CheckoutResource.release`) lets the holder and administrators release. For the holder it then calls
  `refuseElsewhere`, which answers `409 held-elsewhere`, with the `hold` and `lastSaved`, when another device holds the
  check-out. The web session has no device (`Caller.token()` is null), so in the browser every check-out the Helper made
  is refused, an administrator's own included.
- **The app offers Release only for the person's own check-out from the web page** (`useDocumentActions`, `release:
  document && yours`), one document at a time.
- **The Helper already copes with losing a check-out.** Its saves send `ifHeld: true`, so after a release the next save
  from that computer is `409 not-held`. `CopyCycle.RefusedAsync` ends the check-out there and keeps the copy, and the
  notice says "It can't be saved to Healdan any more. Your copy is kept: use Save as New Document to file your
  changes." Reopening a kept copy asks first, too (M3 §5.10). Nothing in the Helper changes.
- **Revoking a device doesn't release its check-outs.** Holds are kept by device id (`DeviceHolds`), and signing in again
  on the same device keeps them (`ApiTokens.issue`). So for a stolen laptop, revoking its sign-in and releasing its
  check-outs are two steps.

## 4. The API (owner: a flag on the release)

### 4.1 `DELETE /v1/documents/{docNumber}/checkout?elsewhere=true`

- **`elsewhere`**, a boolean query parameter, false when absent. With `true`, the person who holds the check-out may
  release it while another of their devices holds it: `release` skips `refuseElsewhere`. It's a query parameter because
  a `DELETE` with a body is dropped by some clients and proxies (call).
- **Nothing else changes.** Someone else, not an administrator, still gets `409 checked-out` or `409 locked`, flag or not;
  an administrator releases as today. A free document is answered as it is, and one the caller can't read is `404`. On a
  check-out held on the caller's own device, or by no device, the flag changes nothing.
- **Any route may send it** (call): the rule is about the person, not the browser. The Helper doesn't send it; it keeps
  Edit Here Instead.
- **The history says where it was.** The release is CE's `UNLOCKED` event, as today. When it ends a check-out another
  device held, its comment names that device and app, so the document's history shows which computer's work was set
  aside (call; the builder words it, plainly, in English like CE's other comments).
- **The contract.** `openapi.yaml` adds the parameter, and the operation's description says what it allows. The
  `held-elsewhere` sentence there gains "unless `elsewhere=true`".

## 5. The app

### 5.1 The entry (owner: at the top of the left pane)

- **Checked Out to Me** is an entry of its own at the top of the left pane, above Recent. It isn't one of Recent's
  entries, because each of those is also a search scope, which opening it sets, and this isn't one (owner, option A in
  the mockup).
- It has the hold marker's pencil, and a count badge with how many documents are checked out to the person: the `total`
  of the same query the view shows, so the two always agree. The entry is always there, and the badge shows only from 1
  up (call).
- In a narrow window it's in the Navigation drawer with the rest of the pane.

### 5.2 The view

- **Address** `/app/checked-out`, heading **Checked Out to Me** (call).
- **Built as Recent Documents is:** the same list, with several rows selectable, the toolbar, the details beside it, the
  reading pane and Quick Look, the right-click menu, F2 and dragging. Rows come in the server's order, latest check-out
  first, with no sorting by header, as Recent's lists.
- **Columns** (owner, from the mockup): Title, with the hold marker as in every list; Doc #; Version; Matter;
  **Computer**; **Checked Out**; Edit Date. Author makes room for the two new ones.
  - **Computer** is the short name of `hold.heldOn.name`, with the app beside it, muted (changed in the build: the
    Standard row's 34 pixels clip a second line). The short name drops a leading `Healdan Helper on `, as the Helper's
    `DeviceNames.Short` does, so a Helper's computer reads "MacBook". A check-out from the web page has no `heldOn`, and
    reads **Web Page**.
  - **Checked Out** is `hold.since`, and **Edit Date** is `lastModified`, both in the list's date format. Edit Date is
    when the document was last saved to Healdan.
  - With the reading pane open, the narrow list keeps Title, Doc # and Edit Date, as everywhere.
- **Empty:** "Nothing is checked out to you."
- **Keeping up to date:** one query, which refreshes when the window comes back (#228) and after the person's own
  check-outs, check-ins, releases and saves, as the other lists do.

### 5.3 Release… (owner)

- **The same action wherever the app offers Release:** the toolbar, in Check Out's place, a row's right-click menu and
  Check-Out Info. So a document checked out on the stolen laptop can be released from a search or a folder too. (Changed
  while planning: the toolbar's More holds only Rename, Move To…, Delete and Copy Link, and the toolbar's own button
  already offers Release, as the mockup draws it.)
- **A selection** (call): Release is offered when every selected document is checked out to the person, and releases
  them one after another, as Move and Delete do (`eachDocument`). What was refused is said as Move's and Delete's
  refusals are, by name.
- **Its label** is **Release…** when any of them is held on a computer, which asks first, and **Release** otherwise.
- **A check-out from the web page** releases at once, as today. When the selection holds any check-out another computer
  holds, the warning comes first, and on Release each of those is sent with `elsewhere=true`. Check-outs from the web
  page in the same selection are released with them, without being listed in the warning.

### 5.4 The warning (owner)

As in the mockup's state 2; the wording is the call the owner approved there.

- An alert dialog titled **Release 3 Documents from MacBook?**, or **Release Grant Agreement – Draft.docx from MacBook?**
  for one. A selection held on two or more computers is titled **Release 3 Documents from Other Computers?**, with a list
  for each computer. A computer is its device (`hold.device.id`), not its name, so two computers with the same name, such
  as a new Mac restored from the stolen one's backup, are two lists (changed in the build).
- "They're checked out to you on MacBook:", then each document's icon, name, doc #, app and when it was last saved to
  Healdan ("Saved yesterday, 17:05", from `lastModified`).
- A red box: **Changes on MacBook that haven't reached Healdan will be lost.** "Once released, MacBook can't save to
  these documents again, and the work in its copies can't be carried on there. If you get its copies back, upload each
  one as a new version of its document to rescue those changes."
- A note: "If MacBook was lost or stolen, also revoke its sign-in in Signed-in Devices", where Signed-in Devices opens
  that dialog.
- **Cancel** has the focus, so Enter does nothing harmful, and Esc is Cancel. The red button names what it does:
  **Release 3 Documents**, or **Release Document**.
- The strings go in `packages/ui`'s catalogue, in US English as the app's other strings are; the docs stay British.

## 6. Files

### 6.1 Upstream files

None.

### 6.2 Fork-owned files

- `healdan-api`: `CheckoutResource.java`, `healdan/api/openapi.yaml`, `CheckoutResourceTest.java`.
- `healdan-web/web/apps/user/src`: `router.tsx`; `views/CheckedOutView.tsx` (new), with what it shares with
  `views/RecentView.tsx`; `views/listColumns.tsx` (Computer and Checked Out); `frame/LeftPane.tsx`; `queries.ts` (the
  check-outs query and its key); `actions/checkout.ts`, `actions/useDocumentActions.ts`, `actions/CheckoutControls.tsx`
  and `details/HoldInfo.tsx` (Release for another computer's check-out, and for a selection); a new dialog for the
  warning under `actions/`; the right-click menus; their tests.
- `healdan-web/web/packages/ui/src/strings/en-US.ts`.
- `healdan-web/web/e2e/`: a new `checked-out.spec.ts`, and `a11y.spec.ts`.
- The docs pages in §8.

## 7. Verification

### 7.1 Server (JUnit, in CI's shards)

In `CheckoutResourceTest`, a document checked out on a device token:

- released from the web session without the flag: `409 held-elsewhere`, as today;
- with `elsewhere=true`: `200`, the document free, its `DeviceHolds` row gone, and an `UNLOCKED` history row whose
  comment names the device;
- with the flag from another of the holder's device tokens: `200`;
- with the flag by someone else: `409 checked-out`; by an administrator: `200`, as today;
- with the flag on a free document: `200`, as it is;
- after the release, a save from the first device with `ifHeld: true`: `409 not-held`.

`ApiTestCase` checks every answer against the contract.

### 7.2 App (Vitest, in the `Web` check)

The entry and its badge (none at 0), the columns (the short computer name, the app, Web Page), Release's label and where
it's offered, the warning's title, lists per computer, Cancel's focus, `elsewhere=true` sent only after Release in the
warning, check-outs from the web page released without it, and refusals said by name.

### 7.3 End to end (Playwright, in Chromium and WebKit, step 9 of the smoke test)

`checked-out.spec.ts`, in a folder of its own inside the shared `E2E` folder: it files a document through the API with
a device token, checks it out with that token naming the app Word, and opens Checked Out to Me. The row shows the
token's computer and Word, and the entry's count includes it. Release… shows the warning naming that computer; Cancel
leaves it checked out; Release frees it, as the API then says, the row goes and the count drops. A save from the device
token with `ifHeld: true` is then `409 not-held`. `a11y.spec.ts` adds the view and the warning, in both themes.

## 8. Docs (in the same pull request)

- **User Guide, Checking Out and Checking In** (`site/content/user/documents/checking-out-and-checking-in/index.md`):
  Checked Out to Me; "When Someone Else Has It" and "On Which Computer" lose "the app never takes a check-out over, and
  offers no Check In or Release for it" for releasing a check-out held on another of your computers, the warning, and the
  ways to rescue its work (on that computer, the Helper's Save as New Document; or upload the copy as a new version, by
  dropping it on the document's row). Pictures of the view and the warning from the demo stack (call).
- **User Guide, Getting Around** (`site/content/user/getting-started/getting-around/index.md`): the left pane's new
  entry.
- **User Guide, Signed-in Devices** (`site/content/user/getting-started/signed-in-devices/index.md`): a lost or stolen
  computer: revoke its sign-in, and release its check-outs from Checked Out to Me.
- **User Guide, When Things Go Wrong** (`site/content/user/office/when-things-go-wrong/index.md`), Your Check-Out
  Ended: it was released from Checked Out to Me on another of your computers.
- **Developer Guide, API** (`site/content/developer/api.md`): the `elsewhere` parameter.
- **The M6 spec:** "changed after M6" notes in §6.1 (the left pane), §6.2 (Check-Out Info), §6.7 (the right-click
  menus) and §8.1 (what the web session may do with a device's check-out).
- **The roadmap:** this feature in the backlog's "User app: further enhancements", and its status once built.

## 9. Decisions made in this session

- Release from the browser for another computer's check-out, behind a warning (owner).
- A flag on the existing release, not a route for several documents or a move then a release (owner, of three).
- The entry at the top of the left pane, not under Recent or in the account menu (owner, of three).
- The columns, the warning's wording, and Release offered everywhere the app offers it (owner, from the mockup).
- Calls: locks and other people's check-outs left out; the badge only from 1; `/app/checked-out`; a query parameter;
  the history comment; any route may send the flag; a selection released one at a time.

## 10. Left to the builder

The history comment's wording, the dialog's layout within the app's `Dialog`, how the view shares Recent's list, and
whether the date columns say "Today 09:12" or a full date, as the list's format does.
