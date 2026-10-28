---
title: Editing and Versions
weight: 4
---

<!-- The picture of the Properties window is a render of the helper's real window, drawn offscreen with test data,
not a screenshot. Replace it with a screenshot during the owner's acceptance (M3 plan, Task 21). -->

While a Healdan document is open in LibreOffice, it's checked out to you, and the window is the helper's local copy
of it. You edit and save as usual, and the helper sends each save to Healdan.

## The Title Bar

The title bar shows the document's name, doc # and version: `Lease agreement #1234 v2.1`. The version is the one
your copy holds, so it moves on with each save that reaches Healdan. A read-only copy's title ends in "(read-only)".

The title's form is also the doc ID: type `#1234 v2.1` into any search box to find that version. See
[Versions](../../documents/versions/) for how versions and snapshots are numbered.

## Saving as You Work

Press **⌘S**, or **File › Save**, as you always would.

- **LibreOffice saves at once,** to your Mac. You never wait for the server.
- **The helper sends the save to Healdan** as a snapshot, once the file has stopped changing. A few seconds later
  the title bar moves on: `v2.1` becomes `v2.2`.
- **A Word, Excel or PowerPoint file** saves in its own format, without LibreOffice asking whether to keep it.
- **Saves close together** go to Healdan in order. If a newer save is waiting before an older one has started, only
  the newer one goes, as it holds everything. The title bar ends on your last save.
- **If Healdan can't be reached,** the save waits on your Mac until it can. See
  [When Things Go Wrong](../when-things-go-wrong/#healdan-cant-be-reached).

Saving never renames the document in Healdan, whatever the copy's file is called.

## Save as New Version

Choose **Healdan › Save as New Version**, or **Save as New Version** on the Healdan toolbar, to start the document's
next version: `v2.2` becomes `v3.0`.

- Unsaved changes are saved first, and that save becomes the new version.
- With no unsaved changes, the document as it stands becomes the new version.
- If you save again while the new version is still waiting for an earlier save to reach Healdan, that later save
  becomes the new version instead, as it holds everything.
- A read-only copy can't: use Save as New Document instead.

## Save as New Document

Choose **Healdan › Save as New Document…**, or **File › Save As** on a Healdan document, to file what's in the window
as a new document with its own doc #. The Save as New Document window is the Save to Healdan window (see
[Saving to Healdan](../saving-to-healdan/)), filled in from the original:

- it starts in the original's folder, with its properties;
- the name is the original's;
- the original's format is chosen, if the app offers it.

Choose where it goes, change what you need to, and save. Then:

- **The window becomes the new document,** with its own title, such as `Lease agreement #1240 v1.0`, checked out to
  you.
- **The original is let go:** its check-out ends, and it stays at the last version you saved to it. Changes you hadn't
  saved go only into the new document.
- **The doc ID footer,** if the document has one, shows the new document's number: see
  [The Doc ID Footer](../doc-id-footer/).

## Properties

Choose **Healdan › Properties…**, or **Properties** on the Healdan toolbar, to see or change the document's
properties without leaving LibreOffice. They're the properties of the Ext. Properties tab in the web page.

![The Properties window for #1234: Client / Matter 998.1 – Healdan Demo from the matter, Author and Typist, Class and Subclass from Drafts, Comments, and two more properties, with Cancel and Save](properties.png)

- **Client / Matter,** marked "from the matter", shows the matter, with its client's name under it. It can't be
  changed here.
- **Save** stores your changes in Healdan straight away. It's on once something has changed.
- **If you may not edit the document,** the properties are shown read-only.
- **On a document that isn't in Healdan yet,** Properties opens Save to Healdan instead.

## Closing

Close the document's window as usual. If LibreOffice asks whether to save your changes, **Save** sends them to
Healdan, and **Don't Save** leaves Healdan at your last save.

- **The helper checks the document in.** Changes Healdan hasn't had yet go with the check-in; otherwise the check-out
  is simply released.
- **Then it deletes the local copy,** once Healdan has confirmed it, and the document leaves the helper's menu.
- **LibreOffice's Recent Documents** doesn't list Healdan's copies. To reopen a document, open it from Healdan.

## The Local Copies

The helper keeps the copies in `~/Library/Application Support/Healdan Helper/Copies/`, named after the title, such as
`Lease agreement #1234 v2.1.docx`. A copy keeps the name it was opened with while it's open, even as the title moves
on. Leave the folder to the helper: opening, moving or renaming a copy yourself would cut it off from Healdan.

A copy with changes Healdan hasn't confirmed is never deleted, even after a crash or a refused save. Once its window
has closed, the helper's menu lists it under **Not Yet in Healdan** until you file it. See
[When Things Go Wrong](../when-things-go-wrong/#your-check-out-ended).

When you sign in to another server, the copies left from the old one move to a folder of their own beside `Copies`,
such as `Copies from healdan.example`, and the helper never sends them to the new server.
