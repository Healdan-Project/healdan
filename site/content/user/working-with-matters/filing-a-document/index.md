---
title: Filing a Document
weight: 2
---

## Where Documents Can Go

Every document belongs to a matter. Healdan accepts a document only in a folder **inside** a matter's workspace:

- not at a Store's top level;
- not at the top level of a matter's workspace;
- not in a search folder, whose contents come from a rule.

So open a folder such as the matter's Documents folder first. The same rule applies however a document arrives:
dropped in the app, uploaded in the browser, saved over WebDAV, sent through the API, copied, moved or restored from the trash.

## Dropping Files on a Folder

In the app, drag files from your desktop onto the documents list of a folder, onto a folder's row in that list, or onto a
folder in the **My Matters** tree. A folder's row or a tree folder lights up as the files come over it, and a drop on a
folder's row files into that folder: drop on **Documents** at a matter's top level, and the files go into Documents.
Anywhere else on the page, a dropped file is ignored, so the browser doesn't open it in place of the app. Healdan files
the files at once, as one document each, with:

- **the folder's own Class,** if it sets one, otherwise the template's default;
- **you as Author and Typist,** and the client and matter from the matter;
- **the file's name as its title.**

An email (`.eml` or `.msg`) takes its subject as its title and fills in its email properties from the message. See
[Emails](../emails/).

### The Form That Comes First

If a required property has no default, Healdan can't file the files without asking, so a form appears first. It also
appears when you hold **Option (⌥)** as you drop, if you want to set something. It lists the files, each with a title you
can edit, and one form of properties for all of them. Its button says how many it will file, such as **File 3 Items**. A
required property is marked, and a property picked from a list offers only its choices. **Author** and **Typist**
are people: type part of a name and pick the person from the list Healdan finds. An email has no title to edit: it
takes its subject, and the form says so. While a form is open the page behind it takes no drop; files dropped before
it appears get a form of their own, which opens when the first closes. This is the same form as
**Edit** in a document's details: see [Properties](../properties/).

![The File Documents form for three files going into the Correspondence folder of matter 1002.1: a title field for each of Note of Call – Hale.docx, Letter to Cardew Hollis.docx and Lease Plan Notes.txt, then Client 1002 – Brightwell Bakeries Ltd, Matter 1002.1 – Commercial Lease, Author and Typist as people to pick, each holding Priya Nair, Class Correspondence, Subclass Letter, Comments and Legacy doc #, and the File 3 Items and Cancel buttons](filing-form.png)

### The Tray

A tray in the bottom left corner of the page shows each file as it goes: **Waiting**, **Uploading…**, **Filed as #
10501**, or why it was refused, with the folder it went into, so a drop that went somewhere you didn't expect can be
seen. Its heading counts them, such as "2 Filed, 1 Refused". **Collapse** folds the list away to the heading, and **Clear
Finished** takes the filed and refused items off and keeps the running ones. At most three upload at a time, and they go on while you move about the app. If you try
to leave the page while any are still uploading, the browser asks first.

![The tray after the three files were filed, headed "3 Filed" with Clear Finished and Collapse: Note of Call – Hale.docx filed as # 66, Letter to Cardew Hollis.docx as # 65 and Lease Plan Notes.txt as # 64, each in Correspondence, with Edit and Dismiss buttons](filing-tray.png)

- **Edit** on a filed file selects the document and opens its properties form in the details.
- **Show** on an email that is already in the matter opens the copy. See [Emails](../emails/).
- **Dismiss** (the cross) takes a finished item off the tray.

New documents appear in the list with one short highlight, which a browser set to reduce motion doesn't show.

### When Files Are Refused

One file's refusal doesn't stop the others. The tray gives the reason in Healdan's own words:

- **A folder where documents can't go,** such as a matter's top level or a search folder, files nothing and says
  **Choose a folder inside the matter**, as does a drop on its row or on it in the tree. A folder you can't write to says so too.
- **A file over the upload limit,** one of a kind your administrator doesn't allow, an empty one, or one whose name
  isn't allowed. The tray says which property it is about, such as "Name: …" or "Class: This needs a value.".
- **A message already in the matter,** which says **Already in this matter as # 10455**.
- **Files that are unavailable for now,** when the Store's backend can't be reached.

Dropping something that isn't a file, such as a link or text, files nothing and says where to find the file instead.

## Uploading with a Button

**Upload** on the toolbar above the documents list opens your computer's file picker for the open folder. Its files go
through the same queue and the same tray as dropped ones. Hold **Option (⌥)** as you choose **Upload**, or press
Option-Enter with the button focused, when you want the form to come first. In the Classic Interface, the upload
button on its toolbar still works.

## Dropping a File on a Document

Drop **one** file on a document's row, and if its extension is the document's (`.docx` on a `.docx`, in any case), Healdan
asks **Save as Version 4 of # 10431, or File as a New Document?** The first saves the file as the document's next version,
as **Save as new version** does in the Classic Interface (see [Versions](../../documents/versions/)), and the tray says
**Saved as Version 4 of # 10431**. The second files it as a document of its own. **File as a New Document** is the
default, so pressing Enter costs an extra document, not a version, if the drop wasn't meant for the row.

A file of another kind than the document, and any drop of more than one file, is filed as new documents in the open
folder, without asking: Healdan keeps a document's extension, so the file can't be its version.

A document that is checked out by someone else, checked out on a computer, or locked can't take a version from the
page, as the page isn't a computer. The question then says who holds it and since when, and that it's released on the
computer it's checked out on, or in the Classic Interface (**Unlock**), and offers only **File as a New Document**.
A check-out of your own from the page is checked in by the save. If Healdan refuses a version and leaves the document
checked out to you, which you hadn't done, it releases it again, and the tray says the version wasn't saved.

## What Happens When You File

- **The document gets its doc #** straight away: a number that's unique across the whole system and never changes.
  See [Finding a Document by Doc #](../../finding-documents/finding-by-doc-number/).
- **It takes its folder's properties:** the client and matter, and anything the folders above it set, such as its
  Class. See [Properties](../properties/).
- **You're its Author and its Typist,** unless you change them in the form that comes first (hold Option as you drop,
  or press Option-Enter on **Upload**), or later with **Edit** in its details. Typist is for filing on someone else's
  behalf: a secretary filing a partner's letter makes the partner its Author.
- **Its Date filed is now.** It records when the document entered Healdan, and can't be changed.
- **An email gets its sender, recipients, subject and dates** read into its properties. See [Emails](../emails/).

## Moving and Copying

- **Moving a document to another matter** gives it the new matter's client, matter and folder properties, except
  any you set on the document itself. It keeps its doc #.
- **A copy is a new document,** with a doc # of its own.
- **Shortcuts aren't allowed** inside Stores.
