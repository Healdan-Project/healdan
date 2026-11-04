# M5 "Email": design

- **Milestone:** M5 in [`docs/ROADMAP.md`](../../ROADMAP.md)
- **Date:** 2026-11-03
- **Status:** design agreed with the owner on 2026-11-03, question by question and then section by section, and approved by the owner the same day, while the trial (§9) was being set up on the owner's Mac. The trial's results go in §9.5, and a row that fails changes the spec as §9.4 says, marked "changed by the trial". Implementation plan: [`docs/superpowers/plans/2026-11-03-m5-email.md`](../plans/2026-11-03-m5-email.md); approved the same day; its "Spec corrections and calls this plan makes" lists where it departs from this spec.
- **Inputs:**
  - the roadmap's M5 section, and its glossary;
  - the [M1 spec](2026-10-17-m1-move-in-design.md) §6.8 (dates and emails) and §7 (the rules on every route);
  - the [M2 spec](2026-10-23-m2-api-foundation-design.md), for `POST /v1/documents` and the upload limits;
  - the [M3 spec](2026-10-24-m3-office-integration-design.md) §5 (the helper and its windows), and the [M3.5 spec](2026-10-30-m3-5-microsoft-office-design.md) §5.8 (the add-in's port), §5.9 (pairing), §5.13 (Set Up Microsoft Office), §6 (the add-in) and §7 (the server's pages);
  - the [M4 spec](2026-11-01-m4-oidc-sign-in-design.md) §1 and §6, for why add-ins never sign in;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md) §5.2;
  - a design conversation with the owner on 2026-11-03;
  - a survey of this repository at `560c57b01`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard and approved section by section. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

*Store*, *workspace* and *template* mean Healdan's things here (roadmap glossary). "CE" is LogicalDOC Community Edition. "Outlook" means new Outlook for Mac unless a section says otherwise. "An email" is a document whose file is an `.eml` or `.msg` (M1 §6.8). "A copy of a message" is an email document whose Message-ID is that message's.

## 1. Intent

Email lives in Healdan next to the documents, filed into matters (roadmap). The owner reads mail in new Outlook for Mac (owner). A Healdan pane in Outlook shows whether the message you're reading is filed and where, suggests where it should go, and files it, or several selected messages, into a matter's folder through the Healdan Helper's Save window. Filed messages get a "Healdan" category in Outlook.

M1 already treats an uploaded `.eml` or `.msg` as an email. It gets From, To, CC, Subject, Sent and Received, is named after its subject, and the Emails and Docs & Emails search scopes find it. M5 adds the Outlook side, the Message-ID, a rule that a matter holds a message once, and attachments filed as documents of their own.

## 2. Goals, non-goals, anti-goals

### 2.1 Goals (M5 is done when)

1. The owner's walkthrough (§15.2) passes in new Outlook for Mac against the owner's server, on a Healdan Helper 0.8.0 release candidate.
2. Healdan Helper 0.8.0 is released, signed and notarised.
3. The docs pages in §14.3 ship with the code.

### 2.2 Non-goals

- **Outlook on the web on Linux.** It's phase 2, once the Linux helper exists (M3's phase 2) (owner). The add-in and the server are the same, so phase 2 is a short design of its own, not a redesign (§4).
- **Filing as you send,** and **filing a whole conversation** (owner: backlog).
- **Dragging an email into a folder in the web page** (owner: moved to M6, whose new UI takes dropped files and emails anyway). Meanwhile an `.eml` saved from Outlook can be uploaded with Upload, as today.
- **Moving or deleting a message in Outlook after filing.** Office.js can't move an item without Microsoft Graph (§3.3).
- **Rule-based filing,** an Outlook folder filed into a matter folder automatically (roadmap: later phase, backlog).
- **Legacy Outlook for Mac,** and **Outlook for Windows,** which comes with the Windows helper.
- **Importing from IMAP or POP mailboxes,** and **a Thunderbird add-on** (roadmap non-goals).
- **Refreshing an email's fields on a new version.** M1 fills them when the document is created, and a check-in of another file leaves them, and the Message-ID, as they were.

### 2.3 Anti-goals

- **No token in the add-in.** It talks only to the helper, as M3.5's does (M4 §1). The helper holds the sign-in.
- **The filed email is the message as received.** Healdan never strips, rewrites or re-encodes it; attachments filed separately are copies (owner).
- **No Microsoft Graph,** no Entra app registration, no second sign-in (owner: approach A, §5).
- **No rule only the add-in enforces.** Once per matter holds on every route (owner; roadmap principle 4).

## 3. Constraints

### 3.1 What M1, M2 and M3.5 give M5

- **M1:** `EmailDocumentListener` fills the email fields and the name on every route, from `EmailFields`, which reads an `.eml` with CE's `MailUtil.readMime` and an `.msg` with `MailUtil.msgToMail`. `EmailParser` indexes their text. The search scopes `emails` and `all` exist in the web page and the API.
- **M1:** documents inherit their folder's properties, which are copied onto each document, and a move between matters re-stamps them. Author and Typist are the creator unless sent.
- **M2:** `POST /v1/documents` files a file, with the upload limits (`upload.maxsize`, `upload.disallow`) in `Uploads`. `GET /v1/folders/recent` lists recent folders.
- **M3.5:** the helper's HTTPS port on loopback (47615 to 47618), its certificate, Set Up Microsoft Office, pairing by code, the `202` and `result` pattern for routes that wait on the user, and `AddinResource` serving pages and a manifest made with the server's address at `/api/addin/`.

### 3.2 Facts from the code

- **`MailUtil` keeps no part dispositions and no Content-IDs.** `addAttachments` collects every part with a file name, inline images included, and `EMail` has no Message-ID field. So Healdan walks an `.eml`'s parts itself, with Jakarta Mail (`MailUtil.readMime` gives the `MimeMessage`), for the Message-ID, the attachments and which are inline (§6.6).
- **Healdan's document listeners run in order:** the rules (position 100), the profile (120), then the email fields (130). The once-per-matter rule needs the Message-ID, so it runs after the email fields are read (§6.4).
- **CE's `DocumentLink`** joins two documents with a free-text `type`, and the web page's Links tab lists them.
- **`hd_matter`** is Healdan's matter table, with a row per matter. `MatterLocator` finds a folder's matter.
- **`Pairing`** accepts only Word, Excel and PowerPoint, and `AddinServer.KeyedRoutes` lists the keyed routes.
- **`AddinResource`** serves one manifest, `manifest.xml`, from `clients/office/manifest.template.xml`.
- **The add-in's pages come from the server.** While the server can't be reached, Office can't load the add-in at all (M3.5 §6.3), and Outlook is the same.

### 3.3 Facts from Outlook (to be confirmed by the trial)

From Microsoft's documentation. The trial (§9) checks each one on the owner's Mac before the spec is approved.

- **`item.getAsFileAsync`** (Mailbox requirement set 1.14) gives the message being read as a Base64 `.eml`, attachments included.
- **`item.internetMessageId`** gives the Message-ID, and **`item.getAllInternetHeadersAsync`** (1.8) the headers, among them `In-Reply-To` and `References`, without downloading the message.
- **`item.categories.addAsync`** (1.8) adds a category to a message; adding to the mailbox's master list is `mailbox.masterCategories.addAsync`, which needs the read/write mailbox permission.
- **Multi-select:** `mailbox.getSelectedItemsAsync` (1.13) lists the selected messages, and `mailbox.loadItemByIdAsync` (1.15) loads one so its `.eml` can be read. Whether new Outlook for Mac supports both is the least certain fact here.
- **A pinnable task pane** opened by a button on the reading surface (`MessageReadCommandSurface`, `SupportsPinning`).
- **Moving or deleting items** isn't in Office.js; it takes Microsoft Graph or EWS.

### 3.4 The setup (owner)

- New Outlook for Mac, signed in to the owner's Microsoft 365 tenant.
- The add-in deployed from the tenant's admin centre (Integrated apps), as M3.5's is.
- The owner's server over HTTPS, reachable from the Mac over the LAN or the VPN.

## 4. Phases (owner)

| Phase | What | When |
|---|---|---|
| 1 (M5) | New Outlook for Mac, through the Healdan Helper 0.8.0 | This spec |
| 2 | Outlook on the web on Linux, with the same add-in and server | Once the Linux helper exists (M3 phase 2), with a short design of its own |

## 5. The pieces (owner: approach A)

| Piece | Where | What it does |
|---|---|---|
| The Message-ID field | `healdan-core` | A new email field, filled on every route, and the `hd_email` table that finds it fast (§6.1, §6.2) |
| Existing emails | `healdan-core` | A background job that fills both for emails already in Healdan (§6.3) |
| Once per matter | `healdan-core` | The rule, on every route (§6.4) |
| Filing an email | `healdan-core`, `healdan-api` | `inspect`, `POST /v1/emails` and `GET /v1/emails` (§6.5 to §6.8) |
| The helper | `clients/helper/` | Outlook pairs; three routes; the Save window's email mode; several messages (§7) |
| The add-in | `clients/office/` | A second manifest, the pane, filing and the category (§8) |

Approach A was chosen over B, the helper fetching messages through Microsoft Graph (an Entra app registration and a second sign-in, Exchange Online only), and C, the helper splitting the attachments and filing each with a call of its own (no new endpoint, but a failure halfway leaves an email without its attachments, and the rule split between client and server). B stays in reserve if the trial finds `getAsFileAsync` can't carry the messages the owner files.

## 6. The server

### 6.1 The Message-ID field (call)

- **`emailmessageid`** joins the Healdan profile's email fields: a string, document only, read-only, filled from the message by `EmailDocumentListener` on every route, as the others are (M1 §6.8). A new seed step adds the attribute, as shipped steps never change.
- **The value** is the `Message-ID` header without its angle brackets or surrounding whitespace, compared exactly. An `.msg` gives it from its transport headers or msgparser's own field (the builder checks which). A message with no Message-ID gets none, and is never a duplicate.
- **It's shown and searched** like the other email fields, in Properties and in the Emails scopes.

### 6.2 `hd_email` (call)

- **A table of Healdan's own,** created by a Flyway script (`V8`), with one row per email document that has a Message-ID: the document's id, the Message-ID, its SHA-256 (indexed, as MariaDB can't index a long string well), and the document's matter.
- **The listener keeps it:** a row when an email is created, its matter changed where M1 re-stamps a document moved to another matter, and the row deleted when the document is deleted for good. Trashed documents keep their row; every lookup skips deleted documents.
- **It's an index, not the truth:** the attribute is. A row out of step is fixed by the background job of §6.3, which the builder may also run at startup if the table is found short.

### 6.3 Existing emails (call)

- **A background job** reads the Message-ID of each email already in Healdan, sets `emailmessageid` and adds its `hd_email` row. It starts after the migrations and seeds, runs in batches, skips documents it has done, and writes a `seed.`-style marker in `hd_setting` when it finishes. A failure on one document is logged and skipped.
- So an email uploaded before M5 counts as filed for the rule and shows in the pane (§15.2 row 10).

### 6.4 Once per matter (owner)

- **The rule.** A matter holds at most one copy of a message that the caller can read. A new email document whose matter already holds a copy the caller can read is refused, on every route: an upload in the web page, the API, WebDAV, CMIS, a copy, a restore from the trash, and a move from another matter. (owner)
- **Copies the caller can't read don't count** (owner), so a refusal never reveals a document. Where no user is behind the request (a system task), every copy counts.
- **Moves within a matter** and **new versions** aren't affected.
- **The refusal** is a `HealdanVetoException` with the code `already-filed` and the copy's doc # and folder, which the API answers as `409 already-filed` with the copy, and the web page shows as "This message is already in 998.1 as #12345."
- **Two filings at once** (two colleagues filing the same message): the check takes a lock on the matter's `hd_matter` row (`select … for update`) inside the transaction that stores the document, so the second waits and then sees the first's copy. (call)
- **Where it runs.** After the email fields are read: in `EmailDocumentListener`, or a listener of its own placed after it. Restores and the routes that write before a listener runs go through `HealdanHooks`, as M1's filing rule does. The builder lists each route with its test (§15.1).

### 6.5 Filing an email with its attachments (owner on attachments; call on the call)

- **The email is always filed whole,** as received, attachments inside (owner).
- **"Also file attachments as separate documents"** is off by default, with a tick per attachment (owner). Each ticked attachment becomes a document of its own:
  - in the same folder as the email, with its own doc #, inheriting the folder's properties as any upload does, named by the attachment's file name;
  - linked to the email with a CE document link of type `attachment` (email first), so the Links tab of each shows the other.
- **Inline parts aren't offered** (owner): a signature logo, say (§6.6).
- **An attached message** (a `message/rfc822` part, as in a forward) is offered as an attachment and filed as an `.eml`, so it gets its own email fields. If its matter already holds a copy, it's skipped and reported in the answer, not refused, so the email itself still files.
- **All or nothing.** `POST /v1/emails` checks everything first: the filing rule, the once-per-matter rule, the caller's permission on the folder, and each ticked attachment against `upload.maxsize` and `upload.disallow`. Only then does it store the email, then each attachment, then the links, in one transaction. If a store fails partway, what was stored is deleted before the answer, so the answer is either everything filed or nothing filed.

### 6.6 What counts as an attachment (call)

Healdan's own walk of the `.eml`'s parts (§3.2), shared by `inspect` and `POST /v1/emails` so the indexes match:

- **An attachment** is a part with a file name whose disposition is `attachment`, or a part with a file name and no disposition that the HTML body doesn't reference by its Content-ID, or a `message/rfc822` part.
- **Inline** is a part whose disposition is `inline` with a Content-ID, or any part the HTML body references as `cid:`. Inline parts are listed with `inline: true` and can't be ticked.
- **The index** is the part's position in a depth-first walk of the message, counting only attachments and inline parts.
- An S/MIME-encrypted or rights-protected message has no readable parts. It's filed as received, with no attachments offered, and its text isn't searched (§10).

### 6.7 The lookup (call)

- **`GET /v1/emails?messageId=…`,** repeated up to 100 times, answers, for each Message-ID asked, the copies the caller can read: doc #, name, matter (`998.1 – Healdan Demo`) and folder. Deleted documents are left out.
- **The pane asks once per message:** its own Message-ID, then its `In-Reply-To` and `References` ones. The first tells it whether the message is filed; the others where the thread went.
- **Suggestions** (call): the folders holding the thread's copies, newest first, where the caller may file (ADD on the folder, and the filing rule allows it); then the caller's recent folders (`/v1/folders/recent`) that the filing rule allows; at most five, without repeats. The helper builds them; the server answers the facts.

### 6.8 The API (call)

All three in `openapi.yaml`, under a new Emails tag, with `ApiTestCase` checking every answer against it.

| Call | Takes | Answers |
|---|---|---|
| `POST /v1/emails/inspect` | the `.eml`, multipart | `200`: its Message-ID, Subject, From, Sent, the attachments (`index`, `name`, `size`, `type`, `inline`) and the readable copies of the message. Nothing is stored. The upload limits apply. |
| `POST /v1/emails` | the `.eml`, `folderId`, `properties` (as `POST /v1/documents` takes them) and `attachments`, the indexes ticked | `201`: the email document and the attachment documents, and any attached message skipped as `already-filed`. `409 already-filed` with the copy; the filing rule's and the upload limits' refusals as `POST /v1/documents` gives them; `422` for an index that isn't an attachment. |
| `GET /v1/emails` | `messageId`, 1 to 100 times | `200`: for each Message-ID, its readable copies |

- **A repeat** after a lost answer meets `409 already-filed` naming the copy just made, which the helper takes as done (§7.2). No `saveId`: the rule itself makes the call safe to repeat for any message with a Message-ID, and Exchange gives every message one.
- **Only `.eml`** is taken by these calls. An `.msg` is still filed through `POST /v1/documents` or the web page, and the rule applies to it there.

## 7. The helper

### 7.1 Outlook pairs (call)

- **Outlook is one more app** on M3.5's port: the same certificate, Set Up Microsoft Office, pairing by code (M3.5 §5.9), and its own key in Outlook's add-in storage. `Pairing` accepts `Outlook`, and Settings lists it with the others.
- **It isn't in the copy cycle.** Emails aren't checked out and have no local copy, so none of M3's cycle, the copy store or the title applies. The helper's menu gains nothing: filing starts in Outlook.

### 7.2 Routes (call)

Keyed routes on the port, each taking `X-Healdan-Protocol` as the others do, and following M3.5 §5.8: a route still running after 20 seconds answers `202`, and the add-in collects its outcome through `result`. The body limit is 150 MiB, as for `file`.

| Route | Takes | Does |
|---|---|---|
| `mail/status` | Message-IDs: the message's, then its thread's | `GET /v1/emails`, then the suggestions (§6.7). Answers filed or not, the copies and up to five suggested folders. `409 signed-out` while signed out, as today. |
| `mail/file` | the `.eml`, and optionally a suggested folder's id | With a folder: `POST /v1/emails` there with no attachment copies and the folder's properties, and answers the outcome. Without: `inspect`, then the Save window's email mode (§7.3), then `POST /v1/emails`. |
| `mail/file-many` | the number of messages and their subjects | The Save window for several messages (§7.4), then answers a ticket |
| `mail/file-one` | the ticket and one `.eml` | Files that message as the ticket says, and answers filed, `already-filed` (with the copy) or failed (with why) |

- **File Here needs nothing typed.** If the folder's form has a required property that inheritance doesn't fill, `mail/file` opens the Save window at that folder instead, so a one-click filing never fails for a missing property.
- **`already-filed`** after a lost answer, or for a message filed by someone else meanwhile, is an outcome, not an error: the add-in shows the copy and adds the category.
- **The outcome** names the email's doc # and folder, and the attachments filed, so the pane can show them.

### 7.3 The Save window's email mode (owner: the helper's window)

The same Save window Word uses (M3 §5), with:

- **the subject** as the name, editable, as M1 names emails after their subject;
- **From, To, CC, Sent and Received,** read-only, from `inspect`;
- **the folder,** with search, recent folders and the matter's folders, as today, opened at the first suggestion when there is one;
- **the folder's properties form** (`/v1/folders/{id}/form`), Author and Typist defaulting to the person filing;
- **"Also file attachments as separate documents,"** unticked, then a tick per attachment with its name and size; inline parts aren't listed. Ticking the box ticks every attachment; each can be unticked.
- **Save** is on once the required properties are filled, as today. A refusal shows in the window, which stays open to choose another folder or untick an attachment. `already-filed` closes it with "Already in 998.1 as #12345", which counts as filed.

### 7.4 Several messages (owner; call on the flow)

- The add-in sends `mail/file-many` with the count and the subjects. The Save window shows "Filing 5 messages", the subjects listed, the folder and its properties, and one box, "Also file each message's attachments as separate documents", with no per-attachment ticks. Inline parts are left out as in §6.6.
- The answer is a ticket. The add-in then loads each message in turn, reads its `.eml` and sends `mail/file-one` with the ticket. The helper files them one at a time, in the order sent.
- A message whose matter already holds it is skipped and counted, not refused. At the end the helper shows one summary: "Filed 4 of 5 messages. 1 was already in 998.1."
- The ticket expires 10 minutes after its last use, and a used ticket can't change its folder.

## 8. The add-in

### 8.1 What it is (call)

- **A second manifest,** an XML manifest of Outlook's kind (`MailApp`), `clients/office/outlook-manifest.template.xml`, which `AddinResource` serves at `/api/addin/outlook-manifest.xml` made with the server's public address, as `manifest.xml` is (M3.5 §7). Its id is fixed and its version changes with the template.
  - A Healdan button on a message being read opens the pane, which can be pinned.
  - The add-in turns on for several selected messages.
  - The permission is read/write item, for the category.
- **Its pages** are in `clients/office/web/`: a pane page, its logic in a Node-testable classic script (as `healdan.js` is, M3.5 §6.1) and the Office.js calls in a glue script. The Content Security Policy of M3.5 §7 covers them.
- **It holds no token** and calls no server: only the helper's port (M3.5 §6.2).

### 8.2 The pane (call)

- **On each message,** as the reading pane moves, it reads `internetMessageId`, `In-Reply-To` and `References` and asks `mail/status`.
- **It shows:**
  - **Filed:** each readable copy as "998.1 › Correspondence, #12345", the doc # a link that opens the document in the web page;
  - **Not Filed,** or **Filed in another matter** when the copies are elsewhere;
  - the suggestions as **File Here** buttons, the folder written out, then **File…**.
- **While filing,** a waiting state, as M3.5's window has, until the outcome. Then the pane shows the new status.
- **Text from the server** (folder and matter names, subjects) is set as text, never HTML.

### 8.3 Filing, and the category (owner)

- **File Here** sends `mail/file` with the `.eml` and the folder; **File…** sends it without, and the helper's Save window opens.
- **After a filed or `already-filed` outcome,** the add-in adds the category "Healdan" to the message (owner). If adding it fails, the failure is logged in the pane's console, the filing still counts, and the pane still shows Filed.
- **The category** is added to the mailbox's master list once, if the trial finds that needs doing and the permission allows it; otherwise the message carries it unlisted, as Outlook allows (§9 T5).

### 8.4 Several selected (owner)

- With several messages selected, the pane shows "5 messages selected" and **File…** only: each message has its own thread, so there are no suggestions, and no status.
- File… sends `mail/file-many`, then, for each message in turn, loads it (`loadItemByIdAsync`), reads its `.eml`, sends `mail/file-one`, and adds the category when it's filed or `already-filed`.
- If the trial finds new Outlook for Mac can't do this (§9 T4), multi-select moves to phase 2, and the pane shows File… on one message only.

### 8.5 When the helper can't be reached

As M3.5 §6.3: the pane says Healdan Helper isn't running, isn't paired (with Pair), is signed out, or needs updating, and polls `ping` until it can go on. While the server can't be reached Outlook can't load the add-in, and Known Limitations says so.

## 9. The trial

### 9.1 Its question

Can a web add-in in new Outlook for Mac, deployed from the admin centre and served from the owner's server, read the message, reach the helper, and mark the message, well enough for §6 to §8 to be built as written?

### 9.2 What it has to show

| Row | Shows |
|---|---|
| T1 | An Outlook manifest deploys from the admin centre and loads in new Outlook for Mac. The button opens the pane, and the pane can be pinned and stays as messages change. The trial notes how long it took to appear. |
| T2 | The pane reaches an HTTPS port on `127.0.0.1` from another origin, preflight included, with a certificate the owner trusts, and a key it stores survives Outlook restarting. |
| T3 | `getAsFileAsync` gives a whole `.eml` with its attachments, for a small message and one of about 20 MB, and the time it takes. |
| T4 | With several messages selected, the add-in turns on, `getSelectedItemsAsync` lists them, and `loadItemByIdAsync` then `getAsFileAsync` gives each one's `.eml`. |
| T5 | `categories.addAsync` adds "Healdan" to a message, with and without a master-list entry, and the category shows in the message list on the Mac and on the owner's phone. Also on a message loaded by id (T4). |
| T6 | `internetMessageId` and `getAllInternetHeadersAsync` give the Message-ID, `In-Reply-To` and `References` of a reply. |

### 9.3 How it runs

As M3.5's did (M3.5 §8.3):

- **Throwaway code** in a scratch folder outside the repository, never committed: a trial manifest, a trial pane with a button per row, and a stand-in for the helper's port that saves each `.eml` it receives and reads its parts with Python's `email` package. Nothing is filed into Healdan, and the owner's server isn't touched.
- **The pages** are served from `https://localhost:8443`, and the stand-in listens on `https://127.0.0.1:47690`, clear of the real helper's ports, both with one self-signed certificate for `localhost` and `127.0.0.1` that the owner trusts for SSL.
- **The owner** deploys the trial manifest from the admin centre, presses the pane's buttons and ticks the rows. **Claude** reads the stand-in's log, which the pane also writes to, and the messages it saved, and fixes the trial's own bugs between rounds.

### 9.4 If a row fails

- **T1 or T2:** M5 stops and comes back to this spec; approach A rests on them.
- **T3** fails for large messages only: M5 goes on, the limit goes in Known Limitations, and approach B is weighed for phase 2.
- **T4:** multi-select moves to phase 2 (owner, 2026-11-03). §7.4 and §8.4 stay in the spec, marked for phase 2.
- **T5:** the category is dropped, and the pane alone shows what's filed.
- **T6:** suggestions are recent folders only.

### 9.5 Results

To be filled in after the trial, before the owner approves the spec.

## 10. Failures

The message is always still in Outlook, so nothing is queued for later (call).

| What happens | What the owner sees |
|---|---|
| The helper isn't running | The pane says "Healdan Helper Isn't Running", with Try Again |
| Outlook isn't paired | The pane shows Pair, as Word's does |
| The helper is signed out | The pane says to sign in from the helper's menu |
| The server can't be reached | The pane's status reads "Can't Reach Healdan". A filing fails with the helper's notification, and nothing is half filed. If Outlook can't load the add-in at all, Office's own message shows (§8.5) |
| A refusal: the filing rule, no permission, the size limit, a ticked attachment's extension | The Save window shows why and stays open, to choose another folder or untick |
| `already-filed` | The pane switches to Filed, naming the copy, and the category is added |
| The category can't be added | Logged; the filing still counts, and the pane still shows Filed |
| An encrypted (S/MIME) or rights-protected message | Filed as received; no attachments offered; its text isn't searched. Known Limitations says so |
| A message over `upload.maxsize` | Refused by `inspect`, naming the limit |
| One of several messages fails | The others go on; the summary names it and why |

## 11. Security (calls)

- **Readability decides everything shown.** The rule, `inspect`'s copies, the lookup and the suggestions count only documents the caller can read, so nothing reveals a document. A suggestion is a folder the caller may file into.
- **`inspect` stores nothing** and applies the upload limits, so it can't be used to put files on the server.
- **The add-in holds no token** (§2.3), and Outlook's key is one more pairing, kept and checked as M3.5's (M3.5 §5.9, §9).
- **Message-IDs, subjects and names are untrusted text.** The pane sets them as text, the web page shows them through `HealdanText` as other server text, and the SQL binds them.
- **The `.eml` is parsed only on the server,** by Jakarta Mail through `MailUtil.readMime`, with the same limits as any upload.

## 12. Data model

- **`hd_email`** (`V8__email_index.sql`, MariaDB and HSQLDB): `hd_docid` (primary key), `hd_messageid` (up to 998 characters, RFC 5322's line limit), `hd_messageidhash` (SHA-256, indexed with `hd_matterid`), `hd_matterid`. Flyway's rules as ever: a new script, never an edit to one that shipped.
- **`emailmessageid`,** a Healdan profile attribute added by a new seed step.
- **The background job's marker** in `hd_setting`.
- **CE's `ld_` tables** are unchanged; links are CE's `DocumentLink` rows.

## 13. Code organisation (calls)

- **`healdan-core`'s `email` package:** `MessageParts` (the walk of §6.6), the Message-ID in `EmailFields`, the rule (`OncePerMatter`), `EmailIndex` (`hd_email`), the background job, and `EmailFiling`, which files an email with its attachments and links in one transaction, for the API to call.
- **`healdan-api`:** `EmailResource` (`/v1/emails`, `/v1/emails/inspect`), with `Uploads`' limits; `ApiProblem` gains `already-filed`.
- **The helper:** `Healdan.Helper.Core` gains the mail routes and the several-message ticket (testable, no windows); the app gains the Save window's email mode.
- **The add-in:** `clients/office/outlook-manifest.template.xml`, `web/mail.html`, `web/mail.js` (the logic, Node-testable) and `web/mail-glue.js` (the Office.js calls).

## 14. Upstream files, fork-owned files and docs pages

### 14.1 Upstream files

None expected. The rule runs in Healdan's listeners and `HealdanHooks`, the walk uses Jakarta Mail through `MailUtil.readMime`, and links are CE's existing `DocumentLink`. If the build finds an edit is needed, it's listed here with its reason, as every milestone's are.

### 14.2 Fork-owned files

- `healdan-core`: the `email` package's new classes, the seed step, `V8__email_index.sql` for MariaDB and HSQLDB, and `plugin.xml` if the rule gets a listener of its own (with a unique extension id).
- `healdan-api`: `EmailResource`, `openapi.yaml`, `AddinResource` (the second manifest), and `RouteCoverageTest`'s expectations.
- `clients/office/`: the Outlook manifest template and the pane's pages.
- `clients/helper/`: `Pairing`, `AddinServer.KeyedRoutes`, the mail routes, and the Save window.
- `docker/tests/test-api.sh`: filing an email with an attachment, the lookup, and the duplicate refusal through the API and an upload.

### 14.3 Docs pages (the docs site, `site/content/`)

- **User Guide:** a new Filing Email from Outlook section (the pane, File Here and File…, several messages, attachments, duplicates, the category); Searching, for the Message-ID field.
- **Install manual:** Setting Up Outlook (deploying `outlook-manifest.xml` from the admin centre, and pairing), and Known Limitations (legacy Outlook, Outlook on the web on Linux waiting for phase 2, no moving or deleting mail after filing, encrypted messages, the add-in not loading while the server can't be reached).
- **Developer Guide:** the API page (the three calls), Desktop Clients (Outlook's routes and the several-message ticket), and the `hd_email` table where Healdan's data is described.

## 15. Verification

### 15.1 Automated

- **`healdan-core`:**
  - the Message-ID read from `.eml` and `.msg`, and none when it's missing;
  - the rule on every route: an upload, a copy, a restore, a move from another matter, and WebDAV and CMIS through the DAOs; a move within the matter and a new version are allowed;
  - only readable copies counting, and every copy counting with no user;
  - two filings at once into one matter, of which one files;
  - `hd_email` kept on create, a move between matters and a delete; the background job on a database with emails and none;
  - `MessageParts` on attachments, inline images, an unreferenced file part, a forwarded message and an encrypted message.
- **`healdan-api`:** `inspect`, `POST /v1/emails` and `GET /v1/emails` against `openapi.yaml` through `ApiTestCase`; all or nothing when an attachment's store fails; a ticked attachment refused by `upload.disallow` storing nothing; an attached message already in the matter skipped; `RouteCoverageTest`.
- **The helper:** the mail routes against a fake API, the `202` path, `already-filed` as an outcome, the several-message ticket and summary; a headless test of the Save window's email mode (`Healdan.Helper.Tests`).
- **The add-in:** Node tests of `mail.js`: the status shown for each answer, the suggestions, the several-message order, and the category only after a filed or `already-filed` outcome.
- **Smoke:** `docker/tests/test-api.sh`'s new steps (§14.2).

### 15.2 Manual acceptance (owner)

On a Healdan Helper 0.8.0 release candidate, in new Outlook for Mac, against the owner's server over HTTPS:

| Row | Steps | Passes when |
|---|---|---|
| 1 | Deploy `outlook-manifest.xml` from the admin centre; open the pane; pair | The pane pairs and can be pinned |
| 2 | Open an unfiled message | Not Filed, with recent folders as suggestions |
| 3 | File… into a matter's folder, no attachment copies | Filed; the message has the "Healdan" category; the web page shows it with its email fields and Message-ID |
| 4 | Open a reply in the same thread | The thread's folder is the first suggestion; File Here files it in one click |
| 5 | A message with two attachments and a signature logo: File…, tick one attachment | The email and one attachment document, linked as `attachment`; the logo isn't offered |
| 6 | File row 3's message again into the same matter, then into another matter | The first is refused naming the copy; the second is filed |
| 7 | Upload row 3's message, saved as `.eml`, through the web page into the same matter | Refused, naming the copy |
| 8 | Select five messages, one already filed; File… | "Filed 4 of 5 messages. 1 was already in …"; four new categories |
| 9 | Quit the helper, then stop the server, each during a filing | Each fails cleanly, nothing half filed; both work again once back |
| 10 | An email uploaded before the upgrade | Shows its Message-ID, and the pane shows it Filed |

## 16. Left to the builder

- Whether the rule is in `EmailDocumentListener` or a listener of its own after it.
- Where `hd_email`'s matter comes from on a move: M1's re-stamp path, or the document's stamped matter read afresh.
- Whether `.msg`'s Message-ID comes from msgparser's field or its transport headers.
- The pane's layout and wording, within §8.2, and its icons, rendered as the add-in's are.
- How the trial's helper build is made, and whether its pane shares code with the real one.

## 17. Decisions made in this session

| # | Decision | By |
|---|---|---|
| 1 | The owner files from new Outlook for Mac | owner |
| 2 | Phase 1 is new Outlook for Mac through the helper; Outlook on the web on Linux is phase 2, with the Linux helper | owner |
| 3 | M5 files the message being read and several selected messages; filing as you send and whole conversations go to the backlog | owner |
| 4 | The folder is picked in the helper's Save window; the pane shows status and one-click suggestions | owner |
| 5 | The email is always filed whole; attachments can also be filed as separate documents, off by default, one tick each, linked to the email; inline images aren't offered | owner |
| 6 | A matter holds a message once, by Message-ID; another matter can hold its own copy; only copies the caller can read count | owner |
| 7 | Filed messages get a "Healdan" category in Outlook | owner |
| 8 | Dragging email into the web page moves to M6 | owner |
| 9 | Once per matter holds on every route | owner |
| 10 | Approach A: add-in to helper to one server call; a trial on the owner's Mac before the spec is approved | owner |
| 11 | No queue during an outage: the message stays in Outlook | call |
| 12 | `hd_email` indexes Message-IDs; a background job fills existing emails | call |
| 13 | Attachments filed separately are linked with a CE document link of type `attachment` | call |
| 14 | `already-filed` after a lost answer counts as done; no `saveId` | call |
| 15 | If the trial's multi-select row fails, multi-select moves to phase 2 | owner |
