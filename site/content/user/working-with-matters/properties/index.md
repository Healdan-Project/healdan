---
title: Properties
weight: 3
---

Every document and folder carries the same set of properties, the **Healdan profile**. In the app, select a document
and its details show them. In the Classic Interface, open its **Ext. Properties** tab. Its **Properties** tab shows the
doc #, which can't be edited, and the Date filed.

## The Properties

| Property | What it holds |
|---|---|
| Client | the client's number. Always the matter's; can't be changed |
| Matter | the matter's number. Always the matter's; can't be changed |
| Author | who the document is by. You, unless you change it |
| Typist | who filed it, for filing on someone else's behalf. You, unless you change it |
| Class | the kind of document, from a list your administrator keeps. Required; "Docs" unless something sets another |
| Subclass | a finer kind, from the chosen Class's list. Optional |
| Comments | anything worth noting about the document, up to 4000 characters. Optional |
| Legacy doc # | the document's number in the system it came from, if it was moved in from one |
| From, To, CC, Subject, Sent, Received, Message-ID | an email's details. See [Emails](../emails/) |

Your administrator can add more properties.

## Editing in the App

Choose **Edit** in a document's details. The properties become a form, made from the profile, so your firm's own
properties are in it.

![The details of Service Charge Budget 2026–27.xlsx, # 30, with its properties as a form: Client and Matter as text; Author with "Pri" typed and its list open, offering Jamie Clarke, the person it has now, first, then Priya Nair; Class (required) Finance, Subclass Statement, Comments and Legacy doc # as fields; and the Save and Cancel buttons](edit-properties.png)

- **A required property has a \* after its name.** Class is required.
- **A property picked from a list offers only its options.** A Subclass offers only the ones that belong to the Class
  you've chosen, and choosing another Class clears a Subclass that doesn't belong to it.
- **Dates** are picked from a calendar or typed. A property that's a number takes a number, and a yes-or-no is a tick
  box.
- **Author and Typist are people.** Type part of a name, and Healdan lists the people whose names hold it, up to 20;
  pick one with the arrow keys and Enter, or a click. The person the property has now comes first in the list. Text
  you leave half typed goes back to that person, and an empty field leaves the property empty (a required one is then
  marked when you save).
- **Client and Matter are text.** They come from the matter and can't be changed. The same goes for an email's From,
  To, Sent and Received.
- **Save sends only what you changed,** and it's saved as a new snapshot (see [Versions](../../documents/versions/)).
  **Cancel**, or Esc, puts everything back. By keyboard, the form opens with the first field ready to type in, and Enter in
  a field saves.
- **A property Healdan refuses is marked** under its field, such as "That is too long." or "That isn't one of the
  choices.", and nothing is saved until all of them are right.

**Edit** is greyed out when you can't change the document's properties: you don't have the Write permission, someone
else has it checked out or locked, it's checked out to you on another computer, or it's protected by a password.

## Comments

Comments is free text for anything worth noting about a document: what it is, who it's for, what's still to do.

- **It's searchable.** The search box's **Documents** scope finds a document whose Comments contain every word you
  type, in any case, as well as the documents containing them. `lease renewal` finds one whose Comments say "Draft
  renewal of the lease".
- **From LibreOffice,** the [Healdan Helper](../../office/)'s Save to Healdan window asks for Comments as you file a
  document.

## Properties Pass Down

Client, matter, Class, Subclass and any properties your administrator adds pass down from a folder to the folders
and documents inside it. A document filed in a folder whose Class is Invoices is an Invoice, without anyone setting
it. Author, Typist, Comments, Legacy doc # and the email details belong to one document and don't pass down.

## Overriding a Property

To give a document or folder a value of its own, set it on that document or folder. It then stops following its
folder for that property, and passes its own value down if it's a folder.

- **To follow the folder again,** set the property back to the value it would inherit, or clear it. In the app,
  a property whose own value takes the place of a folder's is marked **Overridden** while you edit, and **Follow
  Folder** beside it makes it follow the folder when you save. A value of the document's own that no folder would
  supply is just a value: clear it to remove it.
- **Client and Matter can't be overridden.** They always come from the matter.
- **Changing the Class** clears a Subclass that doesn't belong to the new Class.
