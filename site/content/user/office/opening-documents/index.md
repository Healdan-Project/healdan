---
title: Opening Documents
weight: 3
---

You can open a Healdan document in LibreOffice, Word, Excel or PowerPoint from the web page, or from inside the app.
Either way, a document you can edit opens checked out to you, and one you can't opens as a read-only copy.

## From the Web Page: Open in App

Select one document, then choose **Open in App** on the toolbar, or right-click it and choose **Open in App**. The
document opens in the app you've chosen for its kind in the helper's Settings (see
[Opening in Word, Excel and PowerPoint](#opening-in-word-excel-and-powerpoint)), and the helper's menu lists it.

- **It's on** for one document at a time, of a kind LibreOffice or Microsoft Office edits: `.doc`, `.docx`, `.docm`,
  `.dotx`, `.dotm`, `.rtf`, `.odt`, `.ott`, `.xls`, `.xlsx`, `.xlsm`, `.ods`, `.ppt`, `.pptx`, `.pptm`, `.odp` and
  `.odg`. You need permission to download it. The helper opens only these kinds, and PDFs, from a link: a link to
  any other file is refused, with a notice (see [Other Kinds of File](#other-kinds-of-file)).
- **It's on while the document is checked out** too, so you can reopen your own, and others get a read-only copy.
- **The first time,** your browser asks whether this page may open Healdan Helper. Allow it, and tick the box to
  remember, if there is one.
- **A notice appears** in the web page: "If nothing opened, install the Healdan Helper", with a link to
  [Installing the Healdan Helper](../installing-the-helper/). If the document did open, ignore it.
- **Use one address.** The helper opens only documents from the server it's signed in to, so open the web page at
  the address you signed the helper in with. If you sign in to the helper as `docs.example.org`, a web page opened
  as `192.168.1.20:8080` has its links refused, with a notice saying why.

Double-clicking a document in the web page still shows its preview.

## From LibreOffice: Open from Healdan

Any of LibreOffice's ways of opening shows the **Open from Healdan** window: **⌘O**, **File › Open**, **Open File**
in the Start Center, and **Healdan › Open from Healdan…**. So does **Open from Healdan…** in the helper's menu.

![The Open from Healdan window: Recent Documents listed in the middle, Particulars of Claim – Draft.docx selected and checked out to you on Office PC, and its details on the right, under a header with its name, #54 and v2.0, and Properties and Versions tabs, with Properties showing its thumbnail, its matter 1001.1 – Charter Dispute – MV Northern Star, its client, folder, author, Class and Subclass, when it was last saved, and that it's checked out to you on Office PC](open-from-healdan.png)

- **On the left:** Recent Documents (where it starts), Checked Out to Me and Favorites, then Recent Matters, My
  Matters and Recent Folders to browse.
- **In the middle:** each document's title, doc #, version, matter, and when it was last saved. Under the title,
  who has it checked out: "checked out to you on Office PC", or "checked out to Jamie Clarke". The Matter column shows
  the matter's number: point at it to see the matter's name and its client.
- **Matters** are listed with their client's name after them, in smaller type: `1002.1 – Commercial Lease (Brightwell Bakeries Ltd)`.
- **On the right:** the selected document's details. A header names it, with its doc # and version, as in
  `#1234 · v2.1`. Its star adds the document to your Favorites, or takes it off. Two tabs sit under the header:
  - **Properties,** where each Open window starts, shows a small preview of the first page, when Healdan has made
    one, and the document's main properties, with the matter's client under the matter.
  - **Versions** lists the document's versions, the number of them in brackets.

  The tab stays as you left it while you choose other documents. Before you choose a document, the pane asks you to.
- **To find a document,** type in the search box: it searches documents, unless you choose another scope beside it.
  `#1234` and Enter shows document 1234, and `#1234 v2.1` selects that version. That's what the box's hint, ", or
  type #1234", means. It shows while you search documents, but a typed doc # works from any scope.
- **To open it,** double-click it, or select it and choose **Open**.

At the bottom:

- **Open Read-Only:** tick it, then choose **Open**, for a copy you can read but not save to Healdan, even if you
  could edit it.
- **An older version** always opens read-only: choose **Versions** above the details, then the version in the list.
  The header then shows that version, with the warning **Older version: opens read-only**, and Open Read-Only is
  ticked for you. Choosing another document goes back to its current version.
- **Open from Local Computer…** shows LibreOffice's own Open box, for a file on your Mac.

## Opening in Word, Excel and PowerPoint

Which app opens a Healdan document depends on its kind of file, and you choose it in the helper's **Settings**, under
**Open Each Kind of File In**: see [Settings](../installing-the-helper/#settings). Until you choose, documents open in
Word, spreadsheets in Excel and presentations in PowerPoint when your Mac already opens `.docx`, `.xlsx` and `.pptx`
files in them, and in LibreOffice when it doesn't.

- **From the web page,** Open in App opens the copy in that app. A `.docx` opens in Word, a `.xlsx` in Excel and a
  `.pptx` in PowerPoint, if you chose them.
- **From inside the app,** choose **Open from Healdan** on the **Healdan** tab. It shows the same window as above,
  where you can search for `#1234`. **Word's own Open** and **File › Open** stay Office's own, and show files on your
  Mac.
- **OpenDocument files** (`.odt`, `.ods`, `.odp` and `.odg` drawings) open in LibreOffice, whatever you've chosen,
  while it's installed. Office turns the Healdan tab off in a window holding one.
- **The first time** the helper opens a copy in an app, macOS asks whether Healdan Helper may control it, and the
  first time the app saves into the helper's `Copies` folder it asks for access to it: see
  [The Prompts to Expect](../installing-the-helper/#the-prompts-to-expect).
- **The title bar** reads `Lease agreement #1234 v2.1`, as in LibreOffice. A copy open in Word or Excel takes each
  saved version's name as the title moves on; one in PowerPoint keeps the version it opened as. See
  [Editing and Versions](../editing-and-versions/#in-word-excel-and-powerpoint).
- **A read-only copy** opens with "(read-only)" at the end of its title, and a notice says why, as for LibreOffice.
  In Excel and PowerPoint, **Save as New Document** on the Healdan tab files your changes. Word turns its ribbon off
  for a read-only copy, the Healdan tab included: see [Read-Only Copies](#read-only-copies).

## What Happens When It Opens

If you can edit the document and nobody else has it checked out, the helper checks it out to you on this Mac,
downloads it and opens it. The title bar shows its doc # and version: `Lease agreement #1234 v2.1`. Each save goes
to Healdan, and closing checks it back in: see [Editing and Versions](../editing-and-versions/).

A document you already have open comes to the front instead of opening twice, and clicking Open in App twice opens
it once.

## Read-Only Copies

A document opens as a read-only copy when:

- someone else has it checked out, or it's locked in the web page;
- you may read it but not edit it;
- you ticked Open Read-Only, or chose an older version.

Its title ends in "(read-only)", and a notice says why, such as "Opened read-only: Jamie Clarke has it checked out."
The app opens it read-only, and nothing you do to it reaches the document. To file your changes as a new document,
with its own doc # (see [Editing and Versions](../editing-and-versions/#save-as-new-document)):

- **In LibreOffice,** save it: Save as New Document opens instead.
- **In Excel and PowerPoint,** press **Save to Healdan** or **Save as New Document** on the Healdan tab. Their own
  Save can't save over the copy, so if Excel asks whether to save your changes as you close it, choose
  **Don't Save**.
- **In Word,** the ribbon is off, the Healdan tab included, and Word's banner offers **Duplicate**, which is Word's
  own Save As. Save the duplicate in a folder of your own, such as Documents, then press **Save to Healdan** on it:
  it isn't in Healdan, so the Save window files it as a new document. Then delete the file you saved in your
  folder, which Healdan doesn't look after.

The copy is deleted when you close it in LibreOffice. Word, Excel and PowerPoint leave no sign that a read-only
copy's window has closed, so it's deleted when you quit the app.

## Checked Out to You on Another Computer

If you have the document checked out on another of your computers, the helper asks first:

![The Checked Out on Another Computer window: Healdan's mark with a warning triangle, then #54 is checked out to you on Office PC (LibreOffice), when it was last saved to Healdan, a warning that edits on Office PC that haven't reached Healdan won't be in this copy, and the Open Read-Only, Edit Here Instead and Cancel buttons](checked-out-on-another-computer.png)

- **Open Read-Only** opens a read-only copy here, and leaves the check-out where it is.
- **Edit Here Instead** moves the check-out to this computer and opens the document for editing. What you saved on
  the other computer since its last save to Healdan isn't in it. The other computer keeps its copy, but its next save
  or close is refused: see [When Things Go Wrong](../when-things-go-wrong/#your-check-out-ended).
- **Cancel** does nothing.

A document checked out in the web page isn't held on any computer, so it opens for editing on whichever you use.

## Other Kinds of File

A PDF opens in [CoffeePDF](https://github.com/coffeemuse/CoffeePDF), checked out like any document you can edit, when
CoffeePDF is installed with Healdan support on (in CoffeePDF's Settings). Otherwise it opens read-only in your Mac's
usual app for PDFs. An image or any other file LibreOffice doesn't edit opens read-only in your Mac's usual app for it,
from the Open from Healdan window. A read-only copy isn't checked out.

- **A link opens a PDF too.** The helper opens only documents, spreadsheets, presentations, drawings and PDFs from a
  link, whichever way it was made. A link to another kind of file says "… wasn't opened: a link opens only
  documents, spreadsheets, presentations, drawings and PDFs". Open it from the Open from Healdan window, or download
  it from the web page.
- **Your Mac checks it.** The helper marks a copy it opens in your Mac's usual app as downloaded from the internet, so
  macOS checks it as it would any download.
- **Never the kinds on its list.** The helper keeps a list of the common kinds of file that can run a program or
  change your Mac when opened, such as `.app`, `.command`, `.pkg`, `.dmg`, `.sh` or `.mobileconfig`. A file of one
  of those kinds isn't downloaded or opened: the helper says it never opens that kind of file. If you need it,
  download it from the web page. A kind that isn't on the list, such as a `.zsh` script, can still open, and then
  your Mac's check is what protects you.

## What Can't Be Opened

- **A password-protected document.** Open it in the web page.
- **Anything while Healdan can't be reached.** Opening always needs the server; only saves can wait for it.
