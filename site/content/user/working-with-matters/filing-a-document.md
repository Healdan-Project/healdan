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
uploaded in the browser, saved over WebDAV, sent through the API, copied, moved or restored from the trash.

## Uploading

Open the folder, then use the upload button on the toolbar above the documents list. The upload window also takes
files dropped onto it from your desktop.

## What Happens When You File

- **The document gets its doc #** straight away: a number that's unique across the whole system and never changes.
  See [Finding a Document by Doc #](../../finding-documents/finding-by-doc-number/).
- **It takes its folder's properties:** the client and matter, and anything the folders above it set, such as its
  Class. See [Properties](../properties/).
- **You're its Author and its Typist,** unless you change them. Typist is for filing on someone else's behalf.
- **Its Date filed is now.** It records when the document entered Healdan, and can't be changed.
- **An email gets its sender, recipients, subject and dates** read into its properties. See [Emails](../emails/).

## Moving and Copying

- **Moving a document to another matter** gives it the new matter's client, matter and folder properties, except
  any you set on the document itself. It keeps its doc #.
- **A copy is a new document,** with a doc # of its own.
- **Shortcuts aren't allowed** inside Stores.
