---
title: Filing Email from Outlook
weight: 6
---

With Healdan Helper on your Mac and your administrator's add-in in Outlook, you file the message you're reading into a
matter without leaving Outlook. The Healdan pane shows whether the message is already in Healdan, and files it with
one click. This page is about one message at a time, in your own mailbox: the add-in's button is off in shared and
delegated mailboxes. The pane works in the new Outlook for Mac: in Outlook on the web or the new Outlook for Windows,
it says it works in the new Outlook for Mac only for now, and files nothing.

## The Healdan Pane

Choose the **Healdan** button on Outlook's toolbar while you read a message, and the pane opens beside it. Pin it, with
the pin at its top right, and it stays open and follows you from message to message.

The pane's first line says where the message stands:

- **Filed** means a copy of the message is in a matter you can read. Each copy is listed as its matter and folder,
  then its doc #, as in `998.1 › Correspondence, #12345`. Choose the doc # to open the copy in Healdan's web page.
- **Not Filed** means no copy is in Healdan.
- **Filed in Another Matter** means the message is filed, but elsewhere than the conversation: the newest copy of the
  conversation's messages is in a matter that none of this message's own copies is in. You can still file this message
  into that matter: see [Already Filed](#already-filed).

The first time, Outlook isn't paired with the helper, and the pane asks for a code. Healdan Helper shows a six-digit
code in a window on your Mac. Type it in the pane and choose **Pair**. See
[Pairing Word, Excel, PowerPoint and Outlook](../installing-the-helper/#pairing-word-excel-powerpoint-and-outlook).

If the helper isn't running or is signed out, the pane says so and looks again every few seconds, so it recovers when
you start or sign in to the helper.

## Filing a Message

When a message isn't filed, the pane offers up to five **File Here** buttons, each naming a folder. They are folders
you filed earlier messages of the same conversation into, or filed into most recently, and that you may file into.

- **File Here** files the message into that folder in one click. Healdan names it after its subject and fills in its
  properties from the message.
- **File…** opens the helper's Save window, where you choose the folder and fill in the properties yourself, as for a
  document. It opens on the first suggestion, or on your recent folders when there is none.

While the helper works, the pane says so. If the Save window is open, finish there. When it's done, the pane says
`Filed as 998.1 › Correspondence, #1234`, and checks the message again.

## Attachments

In the Save window, tick **Also file attachments as separate documents** to file each attachment as a document of its
own, linked to the email. File Here files the email alone. An encrypted or rights-protected message is filed whole,
but Healdan can't read inside it, so it offers no attachments to tick.

## Already Filed

A matter keeps one copy of a message. If the message is already in the matter you chose, Healdan says **Already
Filed** with the copy's doc #, and files nothing again. The same message may be filed in two different matters.

## The Healdan Category

A message that Healdan has filed, or found already filed, gets Outlook's **Healdan** category, so you can find filed
mail in Outlook. The category is blue. The first time, Healdan adds it to your mailbox's list of categories, and you can
change its colour in Outlook afterwards. If Outlook won't add the category, the message is filed all the same. If you
open another message before a filing ends, the message you filed doesn't get the category, and the pane, showing the
other message, says so.

## When Healdan Can't Be Reached

If the pane says **Can't Reach Healdan**, the helper is running but couldn't get an answer from the server, or
couldn't read the message (it's not a normal email). **File…** stays on, so you can try again. Nothing is
filed until the pane says so.

## A Message That Is Too Large

If the pane says **This message is larger than Healdan accepts**, the message, with its attachments, is over the upload limit. An administrator can raise the limit. Nothing is filed.
