---
title: Working in Office Apps
weight: 5
---

With the Healdan Helper on your Mac, you save to and open from Healdan inside LibreOffice Writer, Calc, Impress and
Draw, and inside Microsoft Word, Excel and PowerPoint, without going through the web page. The title bar always shows
the document's doc # and version: `Lease agreement #1234 v2.1`.

## How It Fits Together

- **The Healdan Helper** is a small app in your Mac's menu bar. It keeps your sign-in, keeps a local copy of each
  document you have open, sends your saves to Healdan in the background, and shows the Save to Healdan, Open from
  Healdan and Properties windows.
- **Healdan's LibreOffice extension** adds a **Healdan** menu and toolbar to LibreOffice. It also takes over
  LibreOffice's own Open, Save and Save As, so that they go through the helper. The helper installs it for you.
- **Healdan's add-in for Microsoft Office** adds a **Healdan** tab to the ribbon in Word, Excel and PowerPoint, with
  the same buttons: Save to Healdan, Open from Healdan, Save as New Version, Save as New Document, Properties and, in
  Word only, Insert Doc ID. Your administrator deploys it once from Microsoft 365, and the helper does the work. The
  add-in doesn't take over Office's own Open, Save or Save As: see
  [In Word, Excel and PowerPoint](#in-word-excel-and-powerpoint).
- **Open in App,** in the web page, opens a document in LibreOffice, Word, Excel or PowerPoint through the helper,
  whichever you've chosen for that kind of file in the helper's Settings.

The rules are the web page's. You can open, edit and file only what the web page would let you, and a document you
open is checked out to you, as it would be there.

## What You Need

- A Mac with Apple silicon, running macOS 26 or later.
- LibreOffice 26.2 or later, or Microsoft Word, Excel or PowerPoint from Microsoft 365, or both.
- For the Healdan tab in Word, Excel and PowerPoint, an administrator who has deployed the add-in: see
  [Deploying the Add-in](../../install/office-integration/#deploying-the-add-in).
- Your Healdan server's `https://` address: the one you use for the web page.

The helper for Ubuntu comes later, and Microsoft Office on Windows later still. See
[Known Limitations](../../install/known-limitations/).

## A Document's Day

1. **Create it** in LibreOffice, and press Save. The Save to Healdan window opens: choose a folder, fill in the
   properties and save. The title bar now reads `… #1235 v1.0`, and the document stays open, checked out to you.
2. **Save as you work.** Each save goes to Healdan as a snapshot, and the title moves on to `v1.1`, `v1.2` and so on.
3. **Close it.** Closing checks it back in.
4. **Open it again** from the web page's Open in App, or from LibreOffice's Open, which shows Open from Healdan.

## In Word, Excel and PowerPoint

The day is the same, with the Healdan tab where LibreOffice has its Healdan menu and toolbar:

1. **Create it** in Word, Excel or PowerPoint, and press **Save to Healdan** on the Healdan tab. The same window
   opens, and the title bar then reads `… #1235 v1.0`.
2. **Save as you work** with **⌘S**, as ever. Each save goes to Healdan, and in Word and Excel the title moves on with
   it.
3. **Close it.** Closing checks it back in.
4. **Open it again** from the web page's Open in App, or with **Open from Healdan** on the Healdan tab.

What's different from LibreOffice:

- **Office's own Open, Save and Save As stay Office's own.** Use the Healdan tab to file a new document and to open
  one from Healdan. **⌘S** on a document that isn't in Healdan yet saves it on your Mac, as before.
- **The first press in each app pairs it with the helper.** The Healdan window asks for a code that the helper shows:
  see [Pairing Word, Excel and PowerPoint](installing-the-helper/#pairing-word-excel-and-powerpoint).
- **A message shows in a small Healdan window,** over the document, when a button has something to tell you, such as
  that the helper isn't running. Close it when you've read it.
- **Insert Doc ID is Word's.** Excel and PowerPoint have the other five buttons.
- **Documents saved as OpenDocument** (`.odt`, `.ods` and `.odp`) open in LibreOffice, where the Healdan menu is, and
  Office turns the Healdan tab off in a window holding one.

{{< cards >}}
  {{< card link="installing-the-helper/" title="Installing the Healdan Helper" icon="desktop-computer" >}}
  {{< card link="saving-to-healdan/" title="Saving to Healdan" icon="upload" >}}
  {{< card link="opening-documents/" title="Opening Documents" icon="folder-open" >}}
  {{< card link="editing-and-versions/" title="Editing and Versions" icon="pencil" >}}
  {{< card link="doc-id-footer/" title="The Doc ID Footer" icon="hashtag" >}}
  {{< card link="when-things-go-wrong/" title="When Things Go Wrong" icon="support" >}}
{{< /cards >}}
