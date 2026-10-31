---
title: Installing the Healdan Helper
linkTitle: Installing the Helper
weight: 1
---

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
copy is Not Yet in Healdan, or LibreOffice's extension isn't installed or needs updating. Point at the icon to see
which, such as "LibreOffice Extension Needs Updating". The extension's dot goes by itself a few seconds after you
accept the extension in LibreOffice.

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

The **Healdan** menu is now in Writer, Calc, Impress and Draw, with Save to Healdan, Open from Healdan, Save as New
Version, Save as New Document, Properties and, in Writer, Insert Doc ID. The same commands are on the Healdan
toolbar: **View › Toolbars › Healdan** shows or hides it. The Start Center's Healdan menu has Open from Healdan.

The toolbar's buttons are pictures, in this order, with a thin line between each pair:

| Button | Command |
|---|---|
| ![Save to Healdan](toolbar-save-to-healdan.png) | Save to Healdan |
| ![Open from Healdan](toolbar-open-from-healdan.png) | Open from Healdan |
| ![Save as New Version](toolbar-save-as-new-version.png) | Save as New Version |
| ![Save as New Document](toolbar-save-as-new-document.png) | Save as New Document |
| ![Properties](toolbar-properties.png) | Properties |
| ![Insert Doc ID](toolbar-insert-doc-id.png) | Insert Doc ID (Writer only) |

Point at a button to see its name. Save to Healdan and Open from Healdan carry Healdan's shield, so they aren't
mistaken for LibreOffice's own Save and Open.

- **If you missed it,** choose **Install the LibreOffice Extension…** in the helper's menu, which asks again. Until
  the extension is in, the helper reminds you each time it starts.
- **After you install a new version of the helper,** LibreOffice may ask again, if it brings a newer extension.

## Setting Up Microsoft Office

Word, Excel and PowerPoint reach Healdan Helper on your Mac through a secure connection, which they use only once your
Mac trusts a certificate the helper makes for itself. **Set Up Microsoft Office** does that, and tells you about the two
other things macOS asks for later. You do it once, however many of the three apps you use.

- **When it opens:** the first time the helper starts with Word, Excel or PowerPoint installed on your Mac, once you've
  signed in. If you choose **Not Now**, it doesn't open again by itself: choose **Set Up Microsoft Office…** in the
  helper's menu when you're ready. That item is in the menu only while Set Up has something to do, and goes once it's
  done.
- **The window** has three rows:

  | Row | What it means |
  |---|---|
  | **Trust Healdan Helper's certificate** | macOS asks for your password once. Word, Excel and PowerPoint use the certificate to reach Healdan Helper on this Mac. **Set Up** does this one |
  | **Allow Healdan Helper to control each app** | The first time the helper saves a document in Word, Excel or PowerPoint, macOS asks whether it may. Choose **Allow**. Each app asks for itself |
  | **Grant each app the Copies folder** | The first time an app saves into Healdan Helper's `Copies` folder, it asks for access. Click **Select…**, go up one level, select `Copies`, and click **Grant Access**. The chooser opens inside `Copies`, where Grant Access is greyed out, which is why you go up first |

- **Set Up** asks macOS to trust the certificate. macOS asks for your password (or Touch ID), and a tick shows in the
  window when it's done. If you cancel the password prompt, or macOS refuses, a line under the rows says so, and you can
  choose **Set Up** again. The other two rows aren't done by Set Up: macOS asks for them when each app first needs them,
  and the window only tells you what to expect.
- **The certificate** is the helper's own, made on your Mac, and good for the addresses `localhost` and `127.0.0.1`
  only, which are your own Mac's. It isn't a certificate authority, so it can't vouch for any website, and its private
  key stays in the helper's folder (`addin-key.pem`, in `~/Library/Application Support/Healdan Helper/`), readable by you
  alone. macOS trusts it for secure connections only, in your login keychain, where Keychain Access lists it as
  "Healdan Helper (this Mac)".
- **It lasts 800 days.** When fewer than 30 are left, **Set Up Microsoft Office…** comes back in the menu. Its
  **Set Up** makes a new certificate and asks for your password to trust it: the helper never makes one by itself.
  Until then the old one keeps working; once it has run out, Word, Excel and PowerPoint can't reach the helper until
  you set up again.
- **Once Set Up is done,** Word, Excel and PowerPoint can reach the helper straight away, without restarting it,
  whether you're signed in or not. Signed out, the Healdan pane says so. Without a trusted certificate they can't reach
  it, and the pane says that instead.

## Pairing Word, Excel and PowerPoint

The first time you press a Healdan button in Word, Excel or PowerPoint, the app pairs with the helper, so that only
your own add-in can use it.

1. The Healdan pane opens in the app and asks for a code, and the helper shows a window: "Word wants to connect to
   Healdan Helper", with a six-digit code in large digits.
2. Type the code in the Healdan pane. The space in the middle doesn't matter.
3. The helper's window closes, and the app is paired.

- **Each app pairs once,** and stays paired when you quit it or restart your Mac. Word, Excel and PowerPoint keep
  their own pairing, so each asks the first time you use Healdan in it.
- **The code expires after two minutes,** and three wrong tries cancel it. Press the Healdan button again for a new
  code. Only one code is shown at a time: a new one replaces the window. After nine wrong codes in a row, pairing
  pauses for ten minutes.
- **If you didn't press a Healdan button,** choose **Cancel**. The code shows only on your screen, so whoever asked
  for it can't use it.
- **If an app forgets its pairing,** as it does when Office's cache is cleared, the next Healdan button asks for a new
  code.
- **What the helper keeps:** only a fingerprint of each app's key, from which the key can't be worked out, in
  `addin-keys.json` in the helper's folder, readable by you alone. The helper answers only Healdan's add-in, loaded from
  your own Healdan server, and only once it's paired: a web page, another program, or another user of your Mac can't
  use it.

## The Prompts to Expect

Each of these comes once.

| When | What asks | Choose |
|---|---|---|
| The first time you open the helper | macOS: open an app downloaded from the internet? | **Open** |
| The first time the helper starts | macOS: allow notifications from Healdan Helper? | **Allow**. Without them, the helper's latest notice shows in its menu instead |
| After your first sign-in | macOS may say that Healdan Helper was added to your login items | Nothing: it lets the helper start when you sign in |
| After your first sign-in | LibreOffice: install the extension Healdan? | **OK**, then restart LibreOffice |
| After your first sign-in, with Word, Excel or PowerPoint installed | The helper's Set Up Microsoft Office window, and when you choose Set Up, macOS asking for your password to change your certificate trust settings | **Set Up**, then your password: see [Setting Up Microsoft Office](#setting-up-microsoft-office) |
| The first time you press a Healdan button in each of Word, Excel and PowerPoint | The helper's window shows a six-digit code | Type it in the app's Healdan pane: see [Pairing Word, Excel and PowerPoint](#pairing-word-excel-and-powerpoint) |
| The first time the helper saves in each of Word, Excel and PowerPoint | macOS: allow Healdan Helper to control the app? | **Allow** |
| The first time each of them saves into the helper's `Copies` folder | The app: grant access to the folder | **Select…**, up one level, `Copies`, **Grant Access** |
| The first Open in App from each browser | The browser: allow this page to open Healdan Helper? | **Allow**. Ticking the box to remember it saves asking again |

Apart from the password macOS asks for when you set up Microsoft Office, none of these asks for your Mac's password. The
helper doesn't ask for your keychain password: it reads its own sign-in without one.

## The Helper's Menu

Click Healdan's icon in the menu bar.

| Item | What it does |
|---|---|
| Signed in as … on … | Who you are, and the server. "Not signed in" when you aren't |
| A notice | When notifications are off, the helper's latest notice. Choose it to clear it |
| The documents you have open | Each by its title, such as `Lease agreement #1234 v2.1`. Choose one to bring its window to the front. "(closed unexpectedly)" after a crash: see [When Things Go Wrong](../when-things-go-wrong/) |
| "1 save waiting to be sent" | Saves Healdan couldn't take yet. The helper keeps trying |
| Not Yet in Healdan | Copies with changes Healdan can't take, such as after your check-out ended, kept once their window closed. Choose one to open it as it is, then use Save as New Document: see [When Things Go Wrong](../when-things-go-wrong/#your-check-out-ended) |
| "2 copies waiting for healdan.example" | Copies set aside for that server, which come back when you sign in to it: see [Settings](#settings). Choose this line to see their folder. They're usually from a server you used before, but a copy that couldn't come back because it was open shows here too, and comes back at the next start once you close it |
| Open from Healdan… | The Open from Healdan window: see [Opening Documents](../opening-documents/) |
| Open Healdan in Browser | Healdan's web page, on the helper's server, in your usual browser. It works when the helper is signed out too: the web page has its own sign-in |
| Install the LibreOffice Extension… | Only while the extension isn't installed, or an older version is |
| Set Up Microsoft Office… | Only while Word, Excel or PowerPoint is installed and the helper's certificate isn't trusted yet, or is nearly out of date: see [Setting Up Microsoft Office](#setting-up-microsoft-office) |
| Settings… | See [Settings](#settings) |
| Sign Out, or Sign In… | See [Signing Out](#signing-out) |
| About Healdan Helper | The helper's version, and the server's: see [About Healdan Helper](#about-healdan-helper) |
| Quit Healdan Helper | Stops the helper. The documents you have open stay checked out to you, and what you save meanwhile waits on your Mac until it starts again |

## Settings

![The Healdan Helper Settings window below its Server group: Open Each Kind of File In, with Documents, Spreadsheets, Presentations and Drawings, each with its file extensions and opening in LibreOffice; and This Computer, with Start at Sign-In On and the LibreOffice Extension's version, 0.3.0, installed on 29 October 2026](settings.png)

Settings has four groups (three while your Mac has no Word, Excel or PowerPoint and no app is paired):

- **Server:** the server the helper is signed in to, as you'd type it to sign in, such as `healdan.example`. To use
  another, sign out, then sign in with its address. Before you do, close your documents, and check the menu shows no
  saves waiting and nothing Not Yet in Healdan. While a document from the old server is still open in LibreOffice, the
  helper doesn't sign in: the Sign In window names the document for you to close first. If it names one you've
  already closed, quit LibreOffice and sign in again. Any local copies left from the old server are moved to a folder
  of their own beside `Copies`, such as `Copies from healdan.example`, and a notice says so. The helper never sends them
  to the new server, where the same doc # can be a different document. They come back, with any saves still to send,
  when you sign in to the old server again. Meanwhile the menu shows how many are waiting.
- **Open Each Kind of File In:** a row each for documents, spreadsheets, presentations and drawings, with the file
  extensions it covers, and the app that opens them. Other files, such as PDFs, open read-only in your Mac's usual app
  for them.
  - **A pop-up** offers LibreOffice and Microsoft Office, whichever are installed. Microsoft Office is offered for
    documents when Word is installed, for spreadsheets when Excel is, and for presentations when PowerPoint is. Choose
    one, and it's kept straight away. It applies to the documents you open from then on: one you have open already
    stays in the app it opened in, until you close it.
  - **Until you choose,** a kind opens in Microsoft Office when your Mac's app for its usual file type (`.docx` for
    documents, `.xlsx` for spreadsheets, `.pptx` for presentations) is Word, Excel or PowerPoint, and in LibreOffice
    when it isn't. So on a Mac that opens `.docx` files in Word, new documents open in Word without your changing
    anything.
  - **With one app to offer,** the row shows it with no pop-up. Drawings are always LibreOffice's, as Microsoft Office
    doesn't open them.
  - **OpenDocument files** (`.odt`, `.ott`, `.ods`, `.odp` and `.odg` drawings) always open in LibreOffice, whatever
    you choose, while it's installed. Your choice is for Microsoft's file types, such as `.docx`, `.xls` and `.pptx`.
- **Microsoft Office:** shown while Word, Excel or PowerPoint is installed on your Mac, or an app is still paired.
  - **Set Up:** shown while one of them is installed. **Done** once your Mac trusts the helper's certificate. While it
    doesn't, or the certificate is nearly out of date, it says **Needed**, and **Set Up…** opens the Set Up Microsoft
    Office window: see [Setting Up Microsoft Office](#setting-up-microsoft-office).
  - **Paired Apps:** each of Word, Excel and PowerPoint that has paired with the helper, and the date it last paired,
    or **None**. **Forget** makes the helper forget an app: it asks for a new code the next time you press a Healdan
    button in it. See [Pairing Word, Excel and PowerPoint](#pairing-word-excel-and-powerpoint).
- **This Computer:**
  - **Start at Sign-In:** On or Off. After your first sign-in, the helper starts whenever you sign in to your Mac.
    To stop it, turn it off in **System Settings › General › Login Items & Extensions**. While it isn't on, a line
    under it says why, or how to turn it on.
  - **LibreOffice Extension:** the version installed, and when it was installed, or **Not Installed**. While the
    extension isn't installed, **Install…** asks LibreOffice to add it, as the menu's **Install the LibreOffice
    Extension…** does. While an older version is installed, the row shows that version, with **Update…**, which asks
    LibreOffice to add the helper's newer one in the same way. A line under the row says when LibreOffice is waiting
    for you to accept it, and the row shows the new version a few seconds after you do.

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
  server instead, they're moved to a folder of their own, as for the [Server](#settings) setting, and wait there until
  you sign in to the same server again.

## Removing the Helper

1. Check the helper's menu: no documents open, no saves waiting, nothing Not Yet in Healdan, and no copies waiting
   for another server.
2. Remove the extension from LibreOffice: **Tools › Extensions**, select **Healdan**, then **Remove**.
3. Choose **Sign Out**, then **Quit Healdan Helper**, and drag **Healdan Helper** from Applications to the Bin.

The helper's files are in `~/Library/Application Support/Healdan Helper/`. Its `Copies` folder holds the local copies
of the documents you had open, including any changes that never reached Healdan, and any `Copies from` folders hold
those left from other servers: look there before deleting it.
