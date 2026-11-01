---
title: Known Limitations
weight: 10
---

- **LogicalDOC's database schema is never migrated.** Healdan upgrades its own tables on every start, but not
  LogicalDOC's. If a new release changes LogicalDOC's tables, that SQL has to be applied to an existing database
  by hand, after a backup.
- **MariaDB only.** Healdan's own tables support MariaDB, and HSQLDB for its tests. MySQL isn't supported.
- **No HTTPS on its own.** The app speaks plain HTTP, so anything beyond the machine running Docker goes through a
  [reverse proxy](../reverse-proxy/).
- **No list of signed-in devices.** The web page doesn't show the devices a person has signed in on through the
  API. Changing their password or disabling them signs every one out. For a lost device, also delete any LogicalDOC
  API keys the person doesn't recognise, under the person icon, **Security → API Keys**: a key outlasts a password
  change. Sessions already open in the web page or with a key stay open until an administrator kills them. See
  [Healdan Settings](../../admin/healdan-settings/#device-sign-ins).
- **LibreOffice and Microsoft Office on macOS only.** The [Healdan Helper](../office-integration/), which brings
  Healdan into LibreOffice and, with its add-in, into Word, Excel and PowerPoint, runs on Macs with Apple silicon. The
  helper for Ubuntu comes next. Windows comes later.
- **PDFs are edited in CoffeePDF, on macOS only.** [CoffeePDF](https://github.com/coffeemuse/CoffeePDF) is the one app
  that edits a PDF from Healdan, and only on a Mac where it's installed with its Healdan support on. On any other
  computer, or a Mac without it, a PDF opens read-only in the computer's default app.
- **Office's own Save, Save As and Open aren't taken over.** LibreOffice's are, but Word, Excel and PowerPoint keep
  theirs, and Healdan's buttons are on the Healdan tab. **⌘S** on a document that isn't in Healdan yet saves it on the
  Mac, as before; use Save to Healdan to file it.
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
