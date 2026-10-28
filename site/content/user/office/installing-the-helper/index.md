---
title: Installing the Healdan Helper
linkTitle: Installing the Helper
weight: 1
---

<!-- The pictures of the helper's windows on this page are renders of its real windows, drawn offscreen with test
data, not screenshots. Replace them with screenshots during the owner's acceptance (M3 plan, Task 21). -->

The Healdan Helper runs in your Mac's menu bar, and adds Healdan's menu and toolbar to LibreOffice. Install it on
each Mac you work on.

## Before You Start

- **LibreOffice 26.2 or later,** in the Applications folder, yours or the Mac's. Install it first: without it, the
  helper can't open anything, and says so.
- **Your Healdan server's address,** such as `https://docs.example.org`: the web page's address, without anything
  after the server's name.
- **Your Healdan username and password,** the ones you use in the web page.
- **A Mac with Apple silicon,** running macOS 26 or later.

## Installing on macOS

{{% steps %}}

### Get the Disk Image

Your administrator tells you where to get `Healdan-Helper-<version>.dmg`. Healdan's own releases are on
[GitHub](https://github.com/Healdan-Project/healdan/releases).

### Copy the Helper to Applications

Open the disk image, and drag **Healdan Helper** onto **Applications**.

### Open It from Applications

Open **Healdan Helper** from your Applications folder. Opened from the disk image instead, it works, but can't set
itself to start when you sign in to your Mac.

macOS asks whether you want to open an app downloaded from the internet: choose **Open**. The helper is signed, and
checked by Apple, so macOS doesn't warn about it otherwise.

Healdan's icon appears in the menu bar. The helper has no Dock icon, and shows a window only when it needs you.

A dot on the icon means the helper needs you: you're signed out, it couldn't start, a document closed unexpectedly, a
copy is Not Yet in Healdan, or LibreOffice's extension isn't installed. Point at the icon to see which.

### Sign In

The Sign In to Healdan window opens: see [The Sign In Window](#the-sign-in-window). Once you're signed in,
LibreOffice asks to add Healdan's extension: see [Adding Healdan to LibreOffice](#adding-healdan-to-libreoffice).

{{% /steps %}}

{{< callout type="info" >}}
The helper for Ubuntu isn't ready yet. Until it is, Healdan works in LibreOffice on macOS only.
{{< /callout >}}

## The Sign In Window

![The Sign In to Healdan window: Healdan's mark above the title and a line saying the helper keeps a sign-in in the keychain, never your password; then Server Address https://healdan.example, empty Username and Password fields, and the Cancel and Sign In buttons](sign-in.png)

Type the server's address, your username and your password, and choose **Sign In**.

- **The address** is your server's, such as `docs.example.org`. The helper adds `https://` if you leave it out, and
  refuses `http://`: your password and documents only travel encrypted. (`http://localhost` works, for a server on
  your own Mac.)
- **Your password isn't kept.** The helper swaps it for a sign-in of its own, named "Healdan Helper on" your Mac's
  name, and keeps that in your Mac's keychain.
- **Wrong passwords count,** as they do in the web page: five in a row block your username for 15 minutes, wherever
  you sign in. See [Signing In](../../getting-started/signing-in/#if-you-cant-sign-in).
- **"That address isn't a Healdan server"** means the address reached a server, but not Healdan. Check it with your
  administrator.

The helper stays signed in until you sign out or change your password, or it goes unused for 30 days, and for a
year at most: your administrator can change both. It then asks for your password again, and anything you save
meanwhile waits on your Mac until you sign in.

## Adding Healdan to LibreOffice

A few seconds after your first sign-in, LibreOffice opens and asks whether to install the extension **Healdan**,
and a notice from the helper says the same.

1. Choose **OK**.
2. Restart LibreOffice when it asks: quit it (**⌘Q**) and open it again.

The **Healdan** menu is now in Writer, Calc and Impress, with Save to Healdan, Open from Healdan, Save as New
Version, Save as New Document, Properties and, in Writer, Insert Doc ID. The same commands are on the Healdan
toolbar: **View › Toolbars › Healdan** shows or hides it. The Start Center's Healdan menu has Open from Healdan.

- **If you missed it,** choose **Install the LibreOffice Extension…** in the helper's menu, which asks again. Until
  the extension is in, the helper reminds you each time it starts.
- **After you install a new version of the helper,** LibreOffice may ask again, if it brings a newer extension.

## The Prompts to Expect

Each of these comes once.

| When | What asks | Choose |
|---|---|---|
| The first time you open the helper | macOS: open an app downloaded from the internet? | **Open** |
| The first time the helper starts | macOS: allow notifications from Healdan Helper? | **Allow**. Without them, the helper's latest notice shows in its menu instead |
| After your first sign-in | macOS may say that Healdan Helper was added to your login items | Nothing: it lets the helper start when you sign in |
| After your first sign-in | LibreOffice: install the extension Healdan? | **OK**, then restart LibreOffice |
| The first Open in App from each browser | The browser: allow this page to open Healdan Helper? | **Allow**. Ticking the box to remember it saves asking again |

The helper doesn't ask for your keychain password: it reads its own sign-in without one.

## The Helper's Menu

Click Healdan's icon in the menu bar.

| Item | What it does |
|---|---|
| Signed in as … on … | Who you are, and the server. "Not signed in" when you aren't |
| A notice | When notifications are off, the helper's latest notice. Choose it to clear it |
| The documents you have open | Each by its title, such as `Lease agreement #1234 v2.1`. Choose one to bring its window to the front. "(closed unexpectedly)" after a crash: see [When Things Go Wrong](../when-things-go-wrong/) |
| "1 save waiting to be sent" | Saves Healdan couldn't take yet. The helper keeps trying |
| Not Yet in Healdan | Copies with changes Healdan can't take, such as after your check-out ended, kept once their window closed. Choose one to open it as it is, then use Save as New Document: see [When Things Go Wrong](../when-things-go-wrong/#your-check-out-ended) |
| Open from Healdan… | The Open from Healdan window: see [Opening Documents](../opening-documents/) |
| Open Healdan in Browser | Healdan's web page, on the helper's server, in your usual browser. It works when the helper is signed out too: the web page has its own sign-in |
| Install the LibreOffice Extension… | Only while the extension isn't installed |
| Settings… | See [Settings](#settings) |
| Sign Out, or Sign In… | See [Signing Out](#signing-out) |
| About Healdan Helper | The helper's version, and the server's: see [About Healdan Helper](#about-healdan-helper) |
| Quit Healdan Helper | Stops the helper. The documents you have open stay checked out to you, and what you save meanwhile waits on your Mac until it starts again |

## Settings

![The Healdan Helper Settings window in three groups: Server, with the address healdan.example; Open Each Kind of File In, with Documents, Spreadsheets and Presentations, each with its file extensions and opening in LibreOffice; and This Computer, with Start at Sign-In On and the LibreOffice Extension's version and when it was installed](settings.png)

Settings has three groups:

- **Server:** the server the helper is signed in to, as its name, such as `healdan.example`. To use another, sign
  out, then sign in with its address. Before you do, close your documents, and check the menu shows no saves waiting
  and nothing Not Yet in Healdan. Any local copies left from the old server are moved to a folder of their own
  beside `Copies`, such as `Copies from healdan.example`, and a notice says so. The helper never sends them to the
  new server, where the same doc # can be a different document.
- **Open Each Kind of File In:** a row each for documents, spreadsheets and presentations, with the file extensions
  it covers. They open in LibreOffice. Other files, such as PDFs, open read-only in your Mac's usual app for them.
- **This Computer:**
  - **Start at Sign-In:** On or Off. After your first sign-in, the helper starts whenever you sign in to your Mac.
    To stop it, turn it off in **System Settings › General › Login Items & Extensions**.
  - **LibreOffice Extension:** the version installed, and when it was installed, or **Not Installed**. While the
    extension isn't installed, **Install…** asks LibreOffice to add it, as the menu's **Install the LibreOffice
    Extension…** does.

## About Healdan Helper

Choose **About Healdan Helper** in the helper's menu.

- **The helper's version,** and the server it signs in to with the version of Healdan it runs. It says "Not signed
  in to …" when you're signed out, and "… couldn't be reached" when Healdan doesn't answer.
- **Copy for Support** copies the helper's version, your Mac's, and the server with its version, as one line to
  paste into an email or an issue.
- **Healdan Docs** opens these pages, and **Licence: GNU LGPL v3** the licence the helper is free software under.
- **Open-Source Notices** lists the open-source software the helper is built with, and each one's licence.

## Signing Out

Choose **Sign Out** in the helper's menu.

- **Your saves go first.** The helper sends anything still waiting, and waits up to half a minute for it. If
  Healdan can't take it by then, you stay signed in, and a notice says so. If Healdan doesn't answer at all, see
  [If Healdan Can't Be Reached](#if-healdan-cant-be-reached).
- **Signing out ends this Mac's sign-in** on the server too, and removes it from the keychain.
- **While you're signed out,** LibreOffice's Open and Save show LibreOffice's own boxes, with a notice that the
  helper isn't running.

### If Healdan Can't Be Reached

When the helper gets no answer from the server, it can't end the sign-in there, so it asks whether to sign out on
this Mac only. That's for a server that's gone for good, or one you're moving away from.

- **Stay Signed In** keeps everything as it was. Choose it if you're only offline, and sign out once Healdan is
  back.
- **Sign Out on This Computer** removes the sign-in from your Mac's keychain and stops the helper, as Sign Out does.
  The server still counts this Mac as signed in until the sign-in expires or you change your password.
- **Healdan answers after all?** If the server is back by the time you choose, the helper ends the sign-in there
  too.
- **Saves that never reached Healdan** stay on your Mac, in the helper's `Copies` folder, and the window says how
  many there are. The helper sends them the next time you sign in to the same server. If you sign in to another
  server instead, they're moved to a folder of their own, as for the [Server](#settings) setting, and never sent.

## Removing the Helper

1. Check the helper's menu: no documents open, no saves waiting, and nothing Not Yet in Healdan.
2. Remove the extension from LibreOffice: **Tools › Extensions**, select **Healdan**, then **Remove**.
3. Choose **Sign Out**, then **Quit Healdan Helper**, and drag **Healdan Helper** from Applications to the Bin.

The helper's files are in `~/Library/Application Support/Healdan Helper/`. Its `Copies` folder holds the local copies
of the documents you had open, including any changes that never reached Healdan, and any `Copies from` folders hold
those left from other servers: look there before deleting it.
