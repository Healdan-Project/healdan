---
title: Working in LibreOffice
weight: 5
---

With the Healdan Helper on your Mac, you save to and open from Healdan inside LibreOffice Writer, Calc, Impress and
Draw, without going through the web page. The title bar always shows the document's doc # and version:
`Lease agreement #1234 v2.1`.

## How It Fits Together

- **The Healdan Helper** is a small app in your Mac's menu bar. It keeps your sign-in, keeps a local copy of each
  document you have open, sends your saves to Healdan in the background, and shows the Save to Healdan, Open from
  Healdan and Properties windows.
- **Healdan's LibreOffice extension** adds a **Healdan** menu and toolbar to LibreOffice. It also takes over
  LibreOffice's own Open, Save and Save As, so that they go through the helper. The helper installs it for you.
- **Open in App,** in the web page, opens a document in LibreOffice through the helper.

The rules are the web page's. You can open, edit and file only what the web page would let you, and a document you
open is checked out to you, as it would be there.

## What You Need

- A Mac with Apple silicon, running macOS 26 or later.
- LibreOffice 26.2 or later.
- Your Healdan server's `https://` address: the one you use for the web page.

The helper for Ubuntu comes later, and Microsoft Office later still. See
[Known Limitations](../../install/known-limitations/).

## A Document's Day

1. **Create it** in LibreOffice, and press Save. The Save to Healdan window opens: choose a folder, fill in the
   properties and save. The title bar now reads `… #1235 v1.0`, and the document stays open, checked out to you.
2. **Save as you work.** Each save goes to Healdan as a snapshot, and the title moves on to `v1.1`, `v1.2` and so on.
3. **Close it.** Closing checks it back in.
4. **Open it again** from the web page's Open in App, or from LibreOffice's Open, which shows Open from Healdan.

{{< cards >}}
  {{< card link="installing-the-helper/" title="Installing the Healdan Helper" icon="desktop-computer" >}}
  {{< card link="saving-to-healdan/" title="Saving to Healdan" icon="upload" >}}
  {{< card link="opening-documents/" title="Opening Documents" icon="folder-open" >}}
  {{< card link="editing-and-versions/" title="Editing and Versions" icon="pencil" >}}
  {{< card link="doc-id-footer/" title="The Doc ID Footer" icon="hashtag" >}}
  {{< card link="when-things-go-wrong/" title="When Things Go Wrong" icon="support" >}}
{{< /cards >}}
