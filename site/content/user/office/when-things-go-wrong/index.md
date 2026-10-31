---
title: When Things Go Wrong
weight: 6
---

The helper's first rule is that nothing you save is lost. When something goes wrong, it keeps your work on your Mac
and tells you, through a notification or, when it needs an answer, a window of its own.

## Healdan Can't Be Reached

You're offline, or the server is down, when you save.

- **Your save is safe.** LibreOffice saves to your Mac as usual, and the document stays checked out to you.
- **The helper keeps trying.** A notice says "A save is waiting for Healdan", and the helper's menu shows how many
  saves are waiting.
- **When Healdan is back,** the helper sends them, in order, and says "Your saves have reached Healdan." The title
  bar moves on to your last save, however long Healdan was away.
- **Restarting is safe.** If your Mac restarts first, the helper finds the waiting saves when it starts again and
  sends them.

Opening a document needs Healdan, so that has to wait.

## The Helper Isn't Running

You quit it, or haven't signed in since your Mac started.

- **LibreOffice's Open and Save** show LibreOffice's own boxes, with a notice that the helper isn't running.
- **A save on a Healdan document** is still kept on your Mac, and goes to Healdan once the helper is running again.
  The title bar then moves on to your last save, however long the helper was away.
- **The notice goes by itself** once LibreOffice hears from the helper again, such as when your kept save reaches
  Healdan, or when you next use a Healdan command.

Start **Healdan Helper** from your Applications folder.

## LibreOffice or Your Mac Crashes

The document stays checked out to you, and your local copy is kept as you last saved it. Within about ten seconds of
LibreOffice going, or as soon as the helper starts after your Mac restarts, this window opens:

![The crash window: Healdan's mark with a warning triangle, and the headline Subscription Agreement – Draft #32 v2.0 closed unexpectedly. It's still checked out to you, and your local copy is kept. With the Send and Check In and Reopen buttons](after-a-crash.png)

- **Reopen** opens your kept copy, with what you last saved, and carries on as before.
- **Send and Check In** sends what Healdan hasn't had yet, and checks the document in.
- **Closing the window** decides nothing: the document stays in the helper's menu, marked "(closed unexpectedly)",
  until you choose it, which reopens it. The helper's icon in the menu bar shows a dot until you choose the
  document.

A read-only copy has nothing to lose: the window says so, and offers **Remove Copy**.

## Word, Excel or PowerPoint Crashes

The same window opens when Word, Excel or PowerPoint goes away with a Healdan document open: the document stays
checked out to you, and your local copy is kept as you last saved it.

Word keeps more than that. When it starts again, it reopens the document by itself, with what you'd typed since your
last save, as "… - Recovered". Its own Save would ask where to save it, as Word treats a recovered document as one
that was never saved. You don't need to:

- **The helper saves it back by itself,** over your local copy, as soon as Word has it open again, and sends it to
  Healdan as your next save. The title moves on, and you carry on as before.
- **Reopen,** in the crash window, starts Word, which brings the recovered document back, and the helper saves it in
  the same way.
- **Send and Check In** sends what you last saved, and checks the document in.

If Excel or PowerPoint brings a document back by itself in the same way, the helper saves it back too.

## Your Check-Out Ended

While you had the document open, its check-out ended:

- it was checked in or unlocked in the web page, by you or an administrator;
- you chose **Edit Here Instead** for it on another of your computers.

Your next save, or closing the window, is then refused, so it can't land on a document that's moved on. A notice says
"It can't be saved to Healdan any more. Your copy is kept: use Save as New Document to file your changes."

- **While it's open:** choose **Healdan › Save as New Document…** to file your changes as a new document.
- **If you've closed it:** the helper's menu lists it under **Not Yet in Healdan**. Choose it there to open your kept
  copy as it is, with no check-out, then choose Save as New Document. Opening the document again from Healdan opens
  the kept copy too, rather than downloading over it.

The copy stays on your Mac, and in that list, until you've filed it or its changes have reached Healdan.

## Someone Changed It in the Web Page

Anyone who may edit a document can change its properties or rename it in the web page while you have it open. Your
next save then starts from a version Healdan has moved past.

- **If only the properties or the name changed,** the helper sends your save again on top of them, by itself.
- **If someone stored newer content,** your save isn't sent over it. Your copy is kept, and you're told to use Save as
  New Document.

## Signed Out or Signed In Again

When your sign-in stops working (you changed your password, or the helper wasn't used for a long time), the helper
says "Sign in to Healdan again. Your saves wait on this computer until you do", and shows the Sign In window. The
documents you have open stay checked out to this Mac.

The helper also shows the Sign In window, once, when the sign-in in your keychain wasn't made for it: the first time
an updated helper starts, if the sign-in is from a version that didn't record which copy of the helper it belongs to,
or after the helper's files were deleted from your Mac. It never uses such a sign-in, as Healdan would then take this
Mac's check-outs for another computer's, and it signs it out.

## A Link Didn't Open

When Open in App doesn't open anything:

- **Nothing at all happened:** the helper isn't installed, or your browser didn't hand the link over. See
  [Installing the Healdan Helper](../installing-the-helper/).
- **Nothing happened, and there's more than one copy of Healdan Helper on your Mac,** such as an old one left in
  Downloads: macOS may hand the link to a copy that isn't the one running. A current copy passes the link on to the
  running helper, but an old one can't. Keep only the copy in Applications.
- **"That link is from …, but Healdan Helper is signed in to …":** open the web page at the address the helper is
  signed in to: **Open Healdan in Browser** in the helper's menu opens it. See
  [Opening Documents](../opening-documents/#from-the-web-page-open-in-app).
- **"Sign in to Healdan Helper to open documents":** you're signed out. Choose **Sign In…** in the helper's menu,
  then click Open in App again.
- **One of the helper's windows is waiting for you,** such as Save to Healdan. The helper ignores links meanwhile,
  and brings that window to the front: finish with it, then click Open in App again.
- **"#1234 is still being checked in":** you closed it moments ago. Open it again once the check-in is done.
- **"… wasn't opened: a link opens only documents, spreadsheets, presentations and drawings":** the link was to a PDF
  or another kind of file. Open it from **Open from Healdan…**, or download it from the web page. See
  [Other Kinds of File](../opening-documents/#other-kinds-of-file).

## Asking for Help

The helper's log is `~/Library/Logs/Healdan Helper/helper.log`, and the LibreOffice extension's is `extension.log`
beside it. They hold no passwords and no document contents, so you can send them to your administrator.
