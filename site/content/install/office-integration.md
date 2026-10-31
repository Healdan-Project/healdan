---
title: Office Integration
weight: 5
---

People can save to and open from Healdan inside LibreOffice, through the **Healdan Helper**: a small app on each
person's Mac, with an extension it adds to LibreOffice. This page is for whoever runs the server. The User Guide's
[Working in LibreOffice](../../user/office/) is for the people using it.

## What the Server Needs

- **HTTPS.** The helper refuses a server at a plain `http://` address, apart from `localhost`, and checks the
  certificate as the Mac does. So Healdan needs a [reverse proxy](../reverse-proxy/) with a certificate the Macs
  trust, such as Let's Encrypt's. A certificate from your own authority works once each Mac trusts that authority.
- **One address.** Open in App, in the web page, hands the helper a link naming the server the page was opened at,
  and the helper opens it only if that's the server it's signed in to, port included. Give people one address, the
  one in `LOGICALDOC_PUBLIC_URL`, and have them use it for both.
- **Nothing to switch on.** The API the helper talks to, under `/api`, and Open in App are on in every install. The
  helper isn't a web page, so it needs no entry in **API origins allowed (CORS)**.
- **Room for saves.** Each save sends the whole file through the proxy, so keep the proxy's upload size and time
  limits generous, as the [Reverse Proxy](../reverse-proxy/) page's examples do.

## What People Need

- A Mac with Apple silicon and macOS 26 or later, with LibreOffice 26.2 or later installed first.
- The helper's disk image, `Healdan-Helper-<version>.dmg`. Healdan's releases are on
  [GitHub](https://github.com/Healdan-Project/healdan/releases), each under a `helper-v<version>` tag, signed and
  notarised. Tell people where to get it, and which version.
- The same permissions as in the web page: Download to open a document, Write to edit it, and Add on a folder to
  file into it. The helper enforces nothing of its own, and can't do anything the web page wouldn't allow.

[Installing the Healdan Helper](../../user/office/installing-the-helper/) takes them through the rest. The helper
doesn't update itself: to move people to a new version, have them install the new disk image over the old one.

The helper for Ubuntu comes later. See [Known Limitations](../known-limitations/).

## Device Sign-Ins

Each helper signs in once with the person's username and password, and keeps a device sign-in, named
`Healdan Helper on <computer>`, in the Mac's keychain. It never stores the password.

- **The usual limits apply.** A sign-in unused for 30 days stops working, and none lasts more than 365 days. Change
  both in [Healdan Settings](../../admin/healdan-settings/#device-sign-ins).
- **Changing someone's password,** or disabling them, signs out every helper of theirs. The helper then asks for
  the password again, and keeps any saves waiting on the Mac meanwhile.
- **The sign-in throttle** counts the helper's wrong passwords like any others.

## The Comments Property

The helper's Save to Healdan window asks for **Comments**, a free-text property that comes after Subclass in the
profile. Healdan adds it to the profile once, when it starts, so documents filed before then have it too, empty. A
profile that already has an attribute named `comments` is left as it is. The Documents search scope finds documents
by the words in it.

## Security Notes

- **The links carry no credentials.** Open in App's link is `healdan://open/<doc #>?server=<host>`, with no session
  id or password, unlike LogicalDOC's "Edit with Office" links. The helper uses its own sign-in.
- **A link can only open a document,** and only from the helper's own server. It never saves, files, releases or
  checks in anything, and it's ignored while one of the helper's windows is waiting for an answer.
- **Links open only what LibreOffice edits:** documents, spreadsheets, presentations and drawings. A link to any other
  kind of file is refused with a notice, before anything is downloaded, so a page can't hand a file someone put in a
  matter to whatever app the Mac has for it.
- **Other files** open only from the helper's Open from Healdan window, where the person chose them, read-only in the
  Mac's default app. Each copy is marked as downloaded from the internet (macOS's quarantine), so Gatekeeper checks
  it as it does a browser's downloads. The helper never downloads or opens a kind of file on its fixed list of 24
  extensions, which run a program or change the Mac when opened: apps, `.pkg` and `.mpkg` installers, `.dmg` disk
  images, common scripts, Automator and Shortcuts files, internet location files, kernel extensions and
  configuration profiles. People download those from the web page. A kind it doesn't list still opens, and the
  quarantine is then the check: disk images other than `.dmg`, such as `.iso` or `.sparseimage`, and kinds such as
  `.zsh`, `.pyc` or `.jnlp`.
- **The risk that's left.** Opening a document someone can edit checks it out to them, with no extra question.
  Browsers ask before a site hands a link to an app, and remember the answer per site. So a page the person has
  allowed could open, and check out, documents they can edit, though it can't change them. Their next close checks
  each one back in.
- **The token** is in the macOS keychain and in the helper's memory, never in a file or a log, and works only
  under `/api`.
- **Local copies** are in `~/Library/Application Support/Healdan Helper/Copies/`, readable only by the person signed
  in to the Mac. Read-only copies, and copies once checked in, are deleted. A copy with changes that haven't
  reached Healdan stays until they have, or until the person files it as a new document; the helper's menu lists
  those it can't send. Turn on FileVault on laptops.
- **LibreOffice talks to the helper** over `127.0.0.1` only, with a secret in a file only the person can read, so
  nobody else on the Mac can drive it.
- **Stamping.** When it stores a Word or LibreOffice file that carries the `Healdan Doc ID` property, through the
  API, Healdan updates that property and the fields showing it to the stored version's doc ID. The rest of the
  content stays as it was, though the parts Healdan rewrites may change form, and a signed file loses its signature:
  see [Known Limitations](../known-limitations/). Files without the property are stored as they arrive.

## Microsoft Office

Word, Excel and PowerPoint get their Healdan tab from a web add-in that Microsoft 365 loads from your server. Healdan
serves the add-in's pages, icons and manifest itself, at `/api/addin/` under your install's address, so there's
nothing to install on the server and nothing to switch on. They're the same for everyone and need no sign-in, as
Office loads them before anyone has signed in to anything.

- **HTTPS at the address people use.** Office loads the add-in over HTTPS only, so Healdan needs a
  [reverse proxy](../reverse-proxy/) with a certificate the computers running Office trust. Set
  `LOGICALDOC_PUBLIC_URL` to that address, as for the helper. Without it, Healdan uses the address the request came
  to, as the proxy forwards it in `X-Forwarded-Proto`. Plain `http://` works only for `localhost`, `127.0.0.1` and
  `[::1]`, for trying it on your own machine.
- **The manifest** is at `https://healdan.example/api/addin/manifest.xml`, with your own address in place of
  `healdan.example`. Healdan makes it for each request, with your address in every link and as the add-in's one app
  domain, so the file you download points at your server. Open it in a browser to check the address, and upload
  that file to Microsoft 365.
- **When Healdan doesn't know an HTTPS address,** the manifest's address answers `409` with the code
  `no-public-address`, and a message that says to set `LOGICALDOC_PUBLIC_URL` to the server's `https://` address.
  Put it in `.env`, then restart Healdan: [Configuration](../configuration/) has the setting.
- **Uploads and upgrades.** The manifest's add-in ID never changes, and its version changes only when the manifest
  itself does, which is what makes Microsoft 365 take a new one. The pages change with Healdan: an upgrade updates
  them for everyone, and needs no new upload unless the manifest changed too.
- **The admin centre's steps** for deploying the manifest are on this page too, as part of this section.
