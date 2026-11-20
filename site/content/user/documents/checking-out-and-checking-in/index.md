---
title: Checking Out and Checking In
weight: 3
---

Checking a document out tells everyone you're editing it. While it's checked out to you, nobody else can save over
it: others can still read, preview and download it. Checking it in again saves your changes as its next snapshot or
version, and lets others edit it.

## In LibreOffice

With the [Healdan Helper](../../office/), you don't check out or in yourself:

- **Opening a document** you can edit checks it out to you, on the computer you open it on.
- **Each save** goes to Healdan, and the document stays checked out to you.
- **Closing it** checks it in.

See [Opening Documents](../../office/opening-documents/) and [Editing and Versions](../../office/editing-and-versions/).

### On Which Computer

A check-out from LibreOffice belongs to the computer it was made on, so two of your computers can't overwrite each
other's work. Opening the document on another of your computers warns you first, and **Edit Here Instead** moves the
check-out there. The first computer then keeps its copy, but can't save it to the document. See
[Checked Out to You on Another Computer](../../office/opening-documents/#checked-out-to-you-on-another-computer).

When you can't get back to the first computer, because it was lost or stolen, say, release its check-out in the app
instead: see [Your Check-Out on Another Computer](#your-check-out-on-another-computer).

A check-out from the app or the Classic Interface belongs to no computer: it's yours, wherever you are.

## In the App

Select a document, and while someone has it checked out or locked, its details show a shaded box above the
**Properties** and **Versions** tabs, so it's there on both. **Check Out** is in the details, under that box, and in
the toolbar above the list.

### In the Lists

Every list of documents marks one that's held, at the end of its title: a pencil for a check-out, and a padlock for a
lock. That's a folder, search results, Recent, Checked Out to Me and the result of a `#` search. The mark is an icon
only, and stays put when you point at the row, select it or move to it with the arrow keys. Point at the mark, and its
tooltip says who has the document, such as "Checked out by Alex Marsh", "Checked out by you" or "Locked by Alex
Marsh". A screen reader reads the same. The details say since when, and on which computer. A free document has no
mark.

The marks are brought up to date with the rest of the page, each time you come back to it (see
[Keeping Up to Date](../../getting-started/getting-around/#keeping-up-to-date)): a document you open in Word shows the
pencil when you click back into the web page. Search results are the exception: their marks stay as they were until
you search again.

### Checked Out to Me

**Checked Out to Me**, at the top of the left pane above **Recent**, lists every document checked out to you,
wherever you checked it out: in the app, in the Classic Interface, or on any of your computers through the
[Healdan Helper](../../office/). It's the place to look for work still in progress, or for a document you forgot to
check in. Beside it, a count says how many there are, once there's at least one.

![Checked Out to Me open in the main area, its entry highlighted at the top of the left pane with 4 beside it. Its list shows three documents on MacBook, each with its app beside the computer's name, LibreOffice cut short with an ellipsis, then one on Web Page, the latest check-out first](checked-out-to-me.png)

| Column | What it shows |
|---|---|
| **Title** | The document's name, with the pencil at its end |
| **Doc #**, **Version** | Its doc # and version |
| **Matter** | Its matter's number and name, such as `1002.1 – Commercial Lease`; point at it for the whole name and the client |
| **Computer** | The computer it's checked out on, such as "MacBook", with the app beside it in paler type, such as Word. **Web Page** for one checked out in the app or the Classic Interface, which belongs to no computer |
| **Checked Out** | When you checked it out, or last saved to it from the computer that holds it, as **Check-Out Time** in its [Check-Out Info](#check-out-info) |
| **Edit Date** | When it was last saved to Healdan |

The latest check-out comes first, and no column header sorts the list. A document leaves it once it's checked in or
released. Select, preview, open and right-click its documents as in any list, and release them from it: see
[Giving Up a Check-Out](#giving-up-a-check-out) and [Your Check-Out on Another Computer](#your-check-out-on-another-computer).

It lists check-outs only. A document locked with the Classic Interface's **Lock** isn't in it (see
[Locking a Document](../locking-a-document/)), and nor is one you can no longer read.

### Check-Out Info

While the document is checked out, the details show **Check-Out Info**, with a row for each thing Healdan knows:

| Row | What it says |
|---|---|
| **User** | Who has it checked out, with "(You)" after your own name |
| **Check-Out Time** | When they checked it out, or last saved to it from the computer that holds it |
| **Application** | The app it's open in, such as Excel or LibreOffice |
| **Computer** | The computer that holds it, by the name of its sign-in, such as "Healdan Helper on MacBook" |

**Application** and **Computer** show only for a check-out made on a computer, through the
[Healdan Helper](../../office/), and only to the person who has it checked out and to administrators, so an
administrator can tell someone which computer to check it in on. Everyone else sees **User** and **Check-Out Time**.
A check-out from the app or the Classic Interface belongs to no computer, so it has neither.

A locked document shows **Lock Info** instead, with **User** and **Lock Time**.

### Checking Out

Choose **Check Out**. Healdan checks the document out to you, and the details show its **Check-Out Info**, with you as
the **User**. It doesn't download anything: to edit the file, use **Open** (see
[Opening Documents](../../office/opening-documents/)), or **Download** it (see
[Downloading Documents](../downloading-documents/)).

![The details of Lease – Draft for Client.docx, # 4, after Check Out: Check-Out Info, with User Priya Nair (You) and Check-Out Time 14 Nov 2026, 17:23, and the Check In and Release buttons under it, below its name and Favorites star and Open, Download and Copy Link](checked-out-by-you.png)

You need permission to write the document. **Check Out** is greyed out while someone else has the document checked out
or locked, and for a document you can't write.

### Checking In

While the document is checked out to you from the app, **Check In** is in its details. Choose it, then choose the file
to upload. Healdan saves it as the document's next snapshot, such as 2.3, and checks the document in. The file is
saved under the document's own name, whatever the file you chose is called. To start the next version instead, such as
3.0, use the Classic Interface (see below). See [Versions](../versions/).

### Giving Up a Check-Out

To stop editing without saving anything, choose **Release**, under its **Check-Out Info**, in the toolbar in place
of **Check Out**, or in the document's right-click menu. The document stays as it was. Select several documents
checked out to you, in [Checked Out to Me](#checked-out-to-me) say, to release them all at once.

### When Someone Else Has It

The details' **Check-Out Info** names who has the document and since when. The app never takes someone else's
check-out over, and offers no **Check In** or **Release** for it. It's released by the person who made it, or by an
administrator in the Classic Interface, with **Unlock**.

A locked document's **Lock Info** says who locked it, and when. A lock is taken off in the Classic Interface (see
[Locking a Document](../locking-a-document/)).

If the check-out changes under you, or a rule refuses it, the app says so in a few words, such as "It's checked out.",
and shows the document's hold as it now is.

### Your Check-Out on Another Computer

A check-out you made by opening a document through the [Healdan Helper](../../office/) belongs to that computer,
which **Computer** names, and the app offers no **Check In** for it. Check it in on that computer, by closing the
document there. When you can't, because the computer was lost or stolen, or it's somewhere you can't get to, choose
**Release…** instead: under the **Check-Out Info**, in the toolbar in place of **Check Out**, or in the document's
right-click menu.

![The details of Lease – Engrossment.docx, # 48, held on a computer: Check-Out Info, with User Priya Nair (You), Check-Out Time 17 Nov 2026, 15:19, Application Word and Computer Healdan Helper on MacBook, and the Release… button under it, below its name and Favorites star and Open, Download and Copy Link](checked-out-on-computer.png)

Releasing it throws away whatever was done on that computer since its last save to Healdan, so Healdan asks first,
naming the computer:

![The warning Release 3 Documents from MacBook?, over Checked Out to Me with MacBook's three documents selected. It lists Lease – Engrossment.docx, Due Diligence Tracker.xlsx and Licence Review – Advice to Kestrel.odt, each with its doc #, app and last save to Healdan, then a red box saying that changes on MacBook that haven't reached Healdan will be lost, a note linking to Signed-in Devices, the red Release 3 Documents button, and Cancel](release-warning.png)

- **The list** has the documents checked out on that computer, each with its doc #, its app and when it was last
  saved to Healdan. Work done on the computer since then isn't in Healdan.
- **Cancel** has the focus, so Enter or Esc releases nothing.
- **Release 3 Documents**, or **Release Document** for one, the red button, releases them. Healdan says "Released
  3 documents", and they leave **Checked Out to Me**.
- **Signed-in Devices,** in the note at the end, opens the list of your devices, so you can revoke the computer's
  sign-in: see [A Lost or Stolen Computer](../../getting-started/signed-in-devices/#a-lost-or-stolen-computer).
  Releasing doesn't revoke it, and revoking doesn't release its check-outs: for a lost computer, do both.

You can release several documents at once, from one computer or several, as long as each is checked out to you: the
warning then lists each computer's documents under its name, and two computers with the same name, such as a new Mac
restored from a lost one's backup, get a list each. Check-outs made in the app among them are released too, without
being listed. If Healdan refuses any, it names them, and the rest are released.

The document's history records the release, naming the computer and the app, such as "Released while checked out on
Healdan Helper on MacBook (Word)". The history is the **History** tab in the Classic Interface, which administrators
see, and anyone their administrator has given the History menu: see [Document History](../document-history/).

#### Rescuing That Computer's Work

Work on the computer that hadn't reached Healdan isn't in the document, but it may not be lost:

- **If the computer comes back,** its Healdan Helper refuses the next save there and keeps its copy: choose
  **Save as New Document** to file the changes as a document of their own. See
  [Your Check-Out Ended](../../office/when-things-go-wrong/#your-check-out-ended).
- **If its app had crashed,** and you reopen the kept copy on that computer while nobody has the document and nothing
  newer has been stored in Healdan, the Healdan Helper checks it out to you again, and the copy saves as before. See
  [Reopening After a Crash](../../office/when-things-go-wrong/#reopening-after-a-crash).
- **If you have a copy of the file,** drop it on the document's row in the app and choose to save it as the
  document's next version. See [Dropping a File on a Document](../../working-with-matters/filing-a-document/#dropping-a-file-on-a-document).

## In the Classic Interface

### Checking Out

Right-click the document and choose **Checkout**. Healdan checks it out to you and downloads it, so you can edit it
on your computer. In the documents list, a checked-out document shows the edit icon; point at it to see who has it.

You need permission to write the document and to download it. A document someone else has checked out or locked
can't be checked out until they're done.

### Checking In

Right-click the document and choose **Checkin**.

1. Tick **New version (otherwise a snapshot)** to start the next version, such as 3.0 after 2.2. Leave it unticked
   for a snapshot, such as 2.3. See [Versions](../versions/).
2. Leave **Check file name** ticked to make sure you upload the right file: it must have the document's file name.
3. Type a **Comment** saying what changed, and choose the file to upload.
4. Choose **Submit**, check the properties, then choose **Checkin**.

Only you, or an administrator, can check in a document checked out to you.

### Giving Up a Check-Out

To stop editing without saving anything, right-click the document and choose **Unlock**. The document stays as it
was. Only you, or an administrator, can unlock a document checked out to you.

Checking in or unlocking in the Classic Interface works whichever computer holds the check-out. If you have the document open
in LibreOffice at the time, that copy can't be saved to the document any more: see
[When Things Go Wrong](../../office/when-things-go-wrong/#your-check-out-ended).

## Checking Out and Locking

Locking is different. A locked document can't be checked out by anyone until it's unlocked, and LibreOffice opens
it read-only, even for the person who locked it. See [Locking a Document](../locking-a-document/).
