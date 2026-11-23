---
title: Known Limitations
weight: 13
---

- **LogicalDOC's database schema is never migrated.** Healdan upgrades its own tables on every start, but not
  LogicalDOC's. If a new version changes LogicalDOC's tables, that SQL has to be run on an existing database by
  hand, after a backup and before the new version's first start. Upgrading's
  [Changes to LogicalDOC's Tables](../upgrading/#changes-to-logicaldocs-tables) gives it for each change. The app
  checks as it starts, and stops if a table or column is missing.
- **PostgreSQL only.** Healdan runs on PostgreSQL 18. MariaDB, MySQL, Oracle and SQL Server aren't supported. See
  [Supported Databases](../what-runs/#supported-databases). An install made on MariaDB, before November 2026, moves
  its data across once: see [Moving from MariaDB to PostgreSQL](../moving-from-mariadb/).
- **An administrator's tag search misses tags with a backslash.** In the Classic Interface, a search by a tag that
  holds a `\`, run by an administrator, finds no document: LogicalDOC writes each `\` into its query twice, and
  PostgreSQL looks for both. Others' tag searches find them.
- **No HTTPS on its own.** The app speaks plain HTTP, so anything beyond the machine running Docker goes through a
  [reverse proxy](../reverse-proxy/).
- **Signed-in Devices lists device sign-ins only.** The app's **Signed-in Devices** shows a person the sign-ins of
  their Healdan Helper on each Mac, and any program signed in through the API with a password, and revokes them one by
  one. It doesn't list the web page's own sessions, which **Sign Out** ends, or LogicalDOC's API keys. Changing a
  person's password or disabling them signs every device out, and **Sign Out Everywhere**, on their page in the Admin
  app, signs out every device and closes their open sessions too. For a lost device, also delete any LogicalDOC API
  keys the person doesn't recognise, under the person icon, **Security → API Keys** in the Classic Interface: a key
  outlasts a password change. See [Healdan Settings](../../admin/healdan-settings/#device-sign-ins) and the User
  Guide's [Signed-in Devices](../../user/getting-started/signed-in-devices/).
- **The Admin app leaves a person's LogicalDOC API keys.** **Reset Password** and **Sign Out Everywhere**, on a person's
  page, don't delete them: a key the person made signs in again once they've chosen their new password after a reset,
  and straight away after Sign Out Everywhere, until it's deleted. Only the key's owner can delete it, under the person
  icon, **Security → API Keys** in the Classic Interface. While the account is disabled, no key signs it in. See
  [Reset Password](../../admin/users-and-groups/#reset-password).
- **Microsoft Entra ID is experimental.** Signing in through Entra ID hasn't been tried against a real Entra tenant
  yet, only through Authentik and a stand-in provider. See [Microsoft Entra ID](../sign-in/entra-id/).
- **Provider accounts have no password.** An account that signs in through a provider, such as Microsoft Entra ID,
  has no Healdan password, so it can't sign in where Healdan asks for one: WebDAV, CMIS, and LogicalDOC's REST web
  services. Unlinking the account from its provider gives it a password again.
- **LogicalDOC's screens don't say why a provider account is refused.** A provider account has no password and
  allows no one to impersonate it. In the Classic Interface, **Change password** is greyed out for one, under the
  person icon's **Security** and in an administrator's users list, though a list loaded before the account was
  linked still offers it, and then says "Wrong password". Under the person icon's **Security → Allowed
  Impersonators**, the list opens empty, and adding someone fails with an error that doesn't say why. Both are
  LogicalDOC's. The User Guide's
  [If You Sign In Through a Provider](../../user/getting-started/your-profile-and-password/#if-you-sign-in-through-a-provider)
  says what the person finds there.
- **Provider accounts can't use LogicalDOC's API keys.** An account that signs in through a provider never signs in
  with an API key, on any route, as the key would skip the provider. Linking an account to a provider deletes the
  API keys it had and closes the sessions they opened, and so does unlinking it. A key a provider account makes on
  the web page, under the person icon, **Security → API Keys**, never signs in either, and unlinking deletes it
  rather than let it start working.
- **Web page sessions aren't checked again with the provider.** Healdan checks a Healdan Helper sign-in with its
  provider every eight hours, but not a sign-in to the web page. A web page session stays open after the person is
  disabled at the provider, until they sign out, it times out, or an administrator kills it.
- **An account has one identity.** Each account is linked to one person at one provider. Someone who also signs in
  through a second provider isn't linked to the account they have: Healdan refuses the sign-in, or makes them a
  second account.
- **Signing out doesn't sign out of the provider.** Signing out of Healdan ends the Healdan session only, and the
  person stays signed in at the provider. On a shared computer, the next person to choose the provider's button may
  be signed straight back in as them, so sign out of the provider too.
- **The `admin` alert needs outgoing email.** The email Healdan can send when `admin` signs in with its password goes
  only by [outgoing email](../../admin/system/outgoing-email/). Until a mail server other than `localhost` and a
  Sender Email other than `logicaldoc@acme.com` are set, nothing is sent, and no message in Healdan takes its place.
- **Providers are reached without LogicalDOC's proxy.** Healdan calls a sign-in provider directly, or through the
  proxy that Java's own settings name (`-Dhttps.proxyHost`, `-Dhttps.proxyPort` and `-Dhttp.nonProxyHosts`, in
  [`CATALINA_OPTS`](../configuration/)), not the one under **Administration → Settings → Proxy**. Setting
  `CATALINA_OPTS` replaces its default, `-Xmx2g`, so keep `-Xmx2g` beside the proxy's flags. It must be an HTTP
  proxy, and only the first one Java's settings give is tried: a SOCKS proxy (`-DsocksProxyHost`) makes every call
  to a provider fail, and `-Dhttps.protocols` isn't read (`-Djdk.tls.client.protocols` is).
- **LibreOffice on macOS and Ubuntu, Microsoft Office on macOS only.** The [Healdan Helper](../office-integration/),
  which brings Healdan into LibreOffice and, on a Mac, with its add-in, into Word, Excel and PowerPoint, runs on Macs
  with Apple silicon and on Ubuntu 26.04. Microsoft Office isn't on Ubuntu, and Windows comes later.
- **The helper for Ubuntu is for Ubuntu 26.04 only.** Its package needs the `libicu78` library, which is Ubuntu
  26.04's, so it won't install on an earlier Ubuntu. The arm64 package is built, but hasn't been run on an arm64
  machine.
- **A link clicked while the helper is signed out isn't opened, on Ubuntu.** One clicked while it's still signing in
  waits up to 30 seconds. If the helper has no sign-in by then (it's signed out, its Sign In window was dismissed or is
  still up, or a first sign-in took longer), the link is dropped, with a line in the helper's log, and a notice says
  "Healdan Helper didn't open #1234. Sign in from its menu, then click the link again." Sign in, then click Open in App
  again.
- **LibreOffice from Ubuntu's App Center (snap) or Flathub (Flatpak) isn't supported.** Their sandboxes can't reach the
  helper's copies or its extension. Install Ubuntu's own LibreOffice with `sudo apt install libreoffice`.
- **The helper's tray icon on Ubuntu needs the AppIndicator extension,** which Ubuntu's GNOME has on by default. With
  it turned off, the helper runs with no icon, and so no menu.
- **The helper installs LibreOffice's extension itself only while LibreOffice is closed,** on Ubuntu. With LibreOffice
  open at the helper's start, LibreOffice asks the person to install it instead: **OK**, then restart LibreOffice.
- **PDFs are edited in CoffeePDF, on macOS only.** [CoffeePDF](https://github.com/coffeemuse/CoffeePDF) is the one app
  that edits a PDF from Healdan, and only on a Mac where it's installed with its Healdan support on. On any other
  computer, Ubuntu's included, or a Mac without it, a PDF opens read-only in the computer's default app.
- **Office's own Save, Save As and Open aren't taken over.** LibreOffice's are, but Word, Excel and PowerPoint keep
  theirs, and Healdan's buttons are on the Healdan tab. **⌘S** on a document that isn't in Healdan yet saves it on the
  Mac, as before; use Save to Healdan to file it.
- **No Healdan tab while no document is open.** Word, Excel and PowerPoint show no ribbon then, so the tab's Open from
  Healdan can't be pressed. Use **Open from Healdan…** in the helper's menu, **Open in App** on the web page, or a new
  blank document: see
  [Opening in Word, Excel and PowerPoint](../../user/office/opening-documents/#opening-in-word-excel-and-powerpoint).
- **Word for the web isn't supported.** The Healdan tab's buttons need the helper on the Mac, which a web browser
  can't reach, so they say they work in the desktop apps only.
- **The Healdan tab needs Healdan.** Office loads the tab's buttons from the Healdan server, so while it can't be
  reached, a button shows Office's own "Sorry, we can't load the add-in". **⌘S** on a Healdan document still saves it
  on the Mac, and the helper sends it once Healdan is back.
- **A Healdan button stops after about five minutes.** Office ends any add-in button still running by then, so a Save
  to Healdan window left open that long may file nothing. Press Save to Healdan again.
- **OpenDocument files open in LibreOffice.** A Word, Excel or PowerPoint window that holds an OpenDocument file
  (`.odt`, `.ods`, `.odp`) has its Healdan tab turned off by Office, and Excel and PowerPoint for Mac can't save in
  those formats. So the helper opens OpenDocument files in LibreOffice whichever app a kind of file is set to, while
  LibreOffice is installed, and a document filed from Word, Excel or PowerPoint is filed in the app's own format.
- **Word turns the Healdan tab off on a read-only copy.** Word turns its whole ribbon off for a document it opens
  read-only, so changes to someone else's checked-out document are filed by saving Word's Duplicate in a folder of
  your own, then pressing Save to Healdan on it: see
  [Read-Only Copies](../../user/office/opening-documents/#read-only-copies). Excel and PowerPoint keep the tab on.
  Word, Excel and PowerPoint also leave no sign that a read-only copy's window has closed, so the helper deletes the
  copy when the app quits.
- **Each Office app asks for permission once.** Word, Excel and PowerPoint each ask macOS to let Healdan Helper control
  them, and ask for access to the helper's `Copies` folder, where the folder chooser opens inside `Copies` with
  Grant Access greyed out, so the person goes up one level and selects `Copies`. See
  [Installing the Healdan Helper](../../user/office/installing-the-helper/#the-prompts-to-expect).
- **Open Recent keeps entries for old copies.** Word, Excel and PowerPoint list each document opened from Healdan, as
  it was opened: the helper hands them the file as a double click does, as Word's own open met Grant File Access for
  every new copy. The names Word's and Excel's copies take as you save stay off the list. An entry for a copy that's
  gone can't be removed by the helper, and choosing it fails: Word says "user does not have access privileges". Open
  the document from Healdan instead.
- **The extension is installed by a click.** On macOS, LibreOffice's command-line installer can't install Healdan's
  extension, so the helper opens it in LibreOffice for the person to accept: the first time, and whenever a new
  version of the helper brings a newer extension.
- **The doc ID footer is Writer's and Word's only.** Calc, Impress, Draw, Excel and PowerPoint show the doc # and
  version in the title bar, but have no footer field.
- **Stamping removes a signature.** A Word or LibreOffice file with a digital signature that carries
  `Healdan Doc ID` loses its signature when a save through the helper or the API stamps it, as the stamp changes
  the signed content. The parts Healdan rewrites can also change form, such as the order of attributes, though not
  what they say. File signed documents without the property, or through the web page, which doesn't stamp.
- **Only saves through the helper update the doc ID.** Healdan writes the doc ID into files stored through its API,
  as the helper's are. A document with the footer that's uploaded or checked in through the web page keeps the
  number its footer had, until its next save through the helper.
- **Dates west of UTC in the web page.** The helper treats a date property as a calendar date, stored as midnight
  UTC. West of UTC, as in the Americas, the web page shows such a date as the day before.
- **A new document's check-out has no app.** For a document filed from LibreOffice and still open, another of the
  person's computers warns that it's "checked out to you on Office PC", without "(LibreOffice)". Once it's opened again,
  the warning names the app.
- **The title moves on only while there are no unsaved changes.** In Word and Excel, the title bar follows the
  version Healdan holds after each save. If the document has changes you haven't saved, the helper waits: the title
  moves on at your next save, and may jump over a version. PowerPoint's title doesn't move on: it stays at the version
  the presentation opened as, because renaming it would make its own Save ask where to save. LibreOffice's copy keeps
  its file name too, but its title bar moves on. In PowerPoint the helper's menu shows the version Healdan has.
- **Two people signed in to one Mac at once.** The Office add-in finds the helper by trying a few ports on the Mac in
  order, and uses the first that answers from a helper signed in to the same server. When two people each have a helper
  running, signed in to the same server, the second person's Word, Excel and PowerPoint may reach the first person's
  helper, which shows its pairing code on the first person's screen, so the second person can't pair, and an app
  already paired with their own helper may be asked to pair again. Their LibreOffice extension and the web page are
  unaffected. Microsoft Office works for the second person once the first has quit their helper or signed out of the
  Mac.

## The User App

- **Some things are still in the Classic Interface.** The app at `/app/` finds, previews, files, checks out, edits
  the properties of (Author and Typist included), renames, moves and deletes documents and folders, makes folders, restores
  and removes what was deleted, and adds documents and folders to My Favorites and takes them off. Other things are done in the Classic
  Interface, one choice away in the app's menu on the same sign-in: [The Classic
  Interface](../../user/the-classic-interface/) lists them. They are:
  - permissions on documents and folders;
  - a document's history;
  - notes, and links between documents;
  - sharing: shared links, emailing a document from the server, and exporting as a ZIP;
  - your profile and password, and your messages;
  - making and editing search folders;
  - choosing and reordering the list's columns, and setting properties on many documents at once;
  - copying documents and folders, and locking documents;
  - tags, and a bookmark's own name and description (the Bookmarks section; the app's My Favorites adds and removes
    them).

  Administration is there as well, apart from what [the Admin app](#the-admin-app) has: its Dashboard, its clients and
  matters with their workspaces, the workspace templates, the Stores and the storage backends. Only an administrator
  moves or deletes a matter's own folder, on the matter's page in the Admin app, or deletes a Store, on the Store's page
  there, and the User app's Trash doesn't restore a Store's item: a Store is restored from **Deleted Stores** on the
  Admin app's Stores page, or in the Classic Interface.
- **The Classic Interface's Trash lists a deleted folder's contents as rows of their own.** Healdan records a folder
  and what it held as one item, but LogicalDOC's Trash screen lists every deleted row, so a deleted folder's documents
  and subfolders show beside it. It also restores into the folder open in its tree, not where the item was deleted
  from. See [The Trash and Removed Items](../../admin/the-trash-and-removed-items/#in-the-classic-interface).
- **A moved or created folder takes a number when a name is already there out of sight.** Moving a folder through
  the [API](../../developer/api/) into a folder that holds a same-named folder you can't read makes LogicalDOC name it
  `Letters(1)`, as creating one does, so that nothing tells you the other exists.
- **A secure address.** The app works only at an `https://` address, or on the machine Healdan runs on (`localhost`
  or `127.0.0.1`). At a plain `http://` address on the network, browsers don't send the `Sec-Fetch-Site` header the
  web session needs, so the app shows a page that says it needs a secure address, with a link to the Classic Interface,
  which works there. Put Healdan behind a proxy that handles HTTPS: see [Reverse Proxy](../reverse-proxy/).
- **Current browsers only.** The app is made for the current Safari, Chrome, Firefox and Edge, meaning the releases
  of the last two years, and is tested in Chromium and WebKit (Chrome and Edge, and Safari). An older browser may
  show it wrongly or not at all.
- **Tablets work, phones don't.** The app is designed for a window 1,280 pixels wide or more, and works at a
  tablet's 768: the left pane and the details, Filters and preview fold away behind buttons in the top bar, and open
  over the list. On a phone's width it isn't supported.
- **Only the root of a host name.** The app is at `/app/` and finds its files and the API from the root of the same
  host name, so Healdan can't be published under a path such as `/healdan/`: give it a host name of its own, as
  [Reverse Proxy](../reverse-proxy/) shows.
- **English only.** The app's words are US English, whatever language a person chose for the Classic Interface.
- **The helper's state isn't shown.** The app doesn't say whether Healdan Helper is running. Open in App hands
  the document to it, and says what to do if nothing opened.

## The Admin App

- **The Dashboard, clients, matters, templates, Stores, storage backends, users and groups, and monitoring so far.**
  The Admin app at `/admin/` has its Dashboard, the clients and matters with a page for each, each matter's workspace,
  the workspace templates, the Stores with a page for each, the storage backends, the users and groups with a page for
  each, and the errors, the server's log, the Audit Trail and the background jobs. The permissions of documents,
  Healdan Settings and search and indexing are all in the Classic Interface, which the Admin app's left pane and its
  links open, and so are the Security settings, making read-only accounts, deleting accounts and unlinking one from
  its sign-in provider. A Store's **Who Can See It** adds groups, not people: to let one person see a Store, add a
  group they're in. See [The Admin App](../../admin/the-admin-app/), [Users and Groups](../../admin/users-and-groups/)
  and [Monitoring](../../admin/monitoring/).
- **For the `admin` group only.** A group given Administration screens through the Classic Interface's menus doesn't
  get the Admin app, and keeps using those screens there.
- **The Audit Trail names the account acted as.** What someone does in a session opened as another account, as an
  [allowed impersonator](../../user/getting-started/your-profile-and-password/#allowed-impersonators), is listed under
  that account, with nothing of who signed in. A change to an account whose row doesn't name who made it shows "Not
  recorded" under Who. See the Admin Guide's
  [Audit Trail](../../admin/monitoring/audit-trail/#what-isnt-recorded).
- **As the User app,** it needs a [secure address](#the-user-app) and a host name of its own, is made for current
  browsers, works on a tablet but not a phone, and is in US English.

## Storage Backends

- **A backend per Store, not per matter.** Each Store keeps its files on one
  [storage backend](../../admin/storage-backends/). A matter that needs its files somewhere else moves to a Store
  that keeps them there.
- **Encryption is chosen when a backend is added.** It can't be added to a backend that holds files, or taken away,
  and a backend's key can't be changed in place. Add a new backend and give its Stores the new one instead.
- **A file rewritten in place keeps only its newest bytes.** When LogicalDOC writes a new file under an old one's
  name, as **Replace file** does, the old bytes go once the new file reaches the backend, at once on a local one. A
  backup restored from before then gets the new file, as on a server without storage backends. See
  [Held Deletes and the Holding Period](../../admin/storage-backends/held-deletes/#replace-file).
- **A large file can arrive cut short in an outage.** A file larger than a tenth of the
  [read cache](../../admin/storage-backends/the-read-cache/#large-files) is read straight from its bucket as it's
  sent. If the bucket stops answering part-way, the download stops short rather than showing the outage message.
- **Some of LogicalDOC's screens don't show the outage message.** While an S3 backend can't be reached, the web
  page's downloads and shared links show "This file is in …'s cloud storage, which can't be reached right now.", and
  a document's preview shows LogicalDOC's "not available" page, for a file that isn't in the read cache. But:
  - an email's attachment, opened from the email's preview, shows a bare error page;
  - a ZIP export leaves out the files it can't read, and doesn't say so;
  - thumbnails show as broken images.
- **LogicalDOC's Stores screen is hidden,** under **Administration → Settings**, and its Folders tab with it. So
  Original's folder (`store.1.dir`) and LogicalDOC's six other folders (`conf.dbdir`, `conf.exportdir`,
  `conf.importdir`, `conf.logdir`, `conf.plugindir` and `conf.userdir`) are set only in the configuration volume's
  `context.properties`: see [Settings Without a Screen](../configuration/#settings-without-a-screen). In Healdan's
  Docker image they're paths inside the container, which only break things when changed.

## Filing Email from Outlook

- **Only the new Outlook for Mac.** The add-in's pane works in the new Outlook for Mac. The add-in needs Outlook's
  Mailbox 1.14, which legacy Outlook for Mac lacks, so it doesn't show there, and Outlook on Windows and on the web,
  including on Linux, wait for a later phase. Outlook on the web and the new Outlook for Windows have Mailbox 1.14, so
  the **Healdan** button shows there for the people the add-in is deployed to, but the pane only says that it works in
  the new Outlook for Mac for now. Deploy it to the Mac users who'll file email: see
  [Deploying the Outlook Add-in](../office-integration/#deploying-the-outlook-add-in).
- **Dragging a message into the browser works in Safari only.** Dragged out of the new Outlook for Mac onto the User
  app's folder list, a message arrives in Safari as an `.eml` file named after its subject, and is filed like any file. In
  Chrome the page gets no drop at all. Drag the message to Finder first, or file it from the add-in's pane. Other
  browsers haven't been tried.
- **The primary mailbox only.** The add-in works in your own mailbox, not in shared or delegated ones: Outlook turns
  its button off there.
- **Filing is one message at a time for now.** Filing several messages at once isn't available yet: the new Outlook for
  Mac 16.114 can't read the selected messages. With several selected, Outlook turns the Healdan button off and closes
  a pinned pane, which comes back when you select one message.
- **A message over the upload limit can't be filed.** Healdan refuses a message larger than its upload limit
  (100 MB as Healdan is installed) and the pane says so. An administrator sets it under **Administration → Settings →
  GUI Settings** as **Upload max. size**: see [Interface Settings](../../admin/system/interface-settings/).
- **Moving or deleting the mail afterwards isn't followed.** Healdan files a copy of the message. Moving it to
  another folder in Outlook, or deleting it, changes nothing in Healdan, and the other way round.
- **Encrypted and rights-protected messages.** Healdan files an encrypted message (S/MIME or PGP/MIME) or a
  rights-protected one whole, as received, but can't read inside it: it offers no attachments to file separately, and
  what it encrypts or protects isn't searched, though its sender, recipients, subject and dates are. A part that is
  encrypted or protected without its type saying so may still be offered as an attachment, as the file it is. Whether
  the new Outlook for Mac gives the add-in such a message hasn't been tried: if it won't, the pane says "Outlook
  wouldn't give Healdan this message. Try again." and nothing is filed. Signed messages file as any other,
  attachments included, except one signed opaquely (S/MIME) more than three times over: it's filed whole, as an
  encrypted one is, with no attachments offered. Real mail is signed at most twice.
- **The pane needs the server to load.** Outlook loads the add-in's pane from your Healdan server. While the
  server can't be reached, the pane doesn't open, even though the helper is running.
- **During an outage, a filing waits for the proxy.** If the server stops while a message is being filed, the pane
  and the Save window say they're filing until the request fails. Behind a reverse proxy that is the proxy's own
  timeout, often a minute or more, and then they say Can't Reach Healdan. Nothing is filed, and filing again works once
  the server is back.
