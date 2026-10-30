---
title: Locking a Document
weight: 4
---

Locking a document freezes it: nobody can change it until it's unlocked. A password goes further, and keeps its
contents from anyone who doesn't know the password.

## Locking

1. Right-click the document and choose **Lock**. You need the Write permission on it.
2. **Give a reason for this lock**, such as "Filed at court: don't change", and choose **Ok**.

The document shows a padlock in the documents list. Your locked documents are also listed on the Dashboard, under
**Documents locked**. The reason is kept in the document's [history](../document-history/), as the comment on "The
document has been locked".

### What a Lock Stops

While it's locked, nobody can check the document out, edit its properties, rename it, cut it or delete it: those are
greyed out on its menu, for you too. It can still be read, previewed, downloaded and copied.

- **In LibreOffice,** a locked document opens read-only, even for the person who locked it. See
  [Opening Documents](../../office/opening-documents/#read-only-copies).
- **Through Healdan's API,** saves are refused, even for the person who locked it.

### Unlocking

Right-click the document and choose **Unlock**. Only the person who locked it, or an administrator, can unlock it.
Anyone else is refused, with a message naming who has it locked.

### Locking or Checking Out

A [check-out](../checking-out-and-checking-in/) says "I'm editing this": it stops others saving over your work, and
your check-in saves it. A lock says "nobody edits this", the person who locked it included. **Unlock** ends either.

While you edit a document's properties, it shows a padlock too, until you choose **Save** or **Cancel**.

## Password Protection

A password-protected document shows a key in the documents list. Before Healdan shows anything of it, it asks for
the password: "The document is protected by password:". Type it and choose **Ok**. Healdan remembers it until you
close or reload the page.

- **Setting a password:** right-click the document, choose **More → Set password**, and type the password. You need
  the Password permission, which your administrator grants.
- **Removing it:** **More → Unset password**, then give the current password. The same permission is needed.
- **A forgotten password:** an administrator can remove it without knowing it, from the password prompt's
  **Unset password** button.

Tell the people who need the document its password some other way than Healdan, as you would for a protected file.

### What a Password Stops

- **The Healdan Helper and Healdan's API** can't open, download, check out or save a password-protected document,
  though its properties can still be read. Open it in the web page instead. See
  [Opening Documents](../../office/opening-documents/#what-cant-be-opened).
- **Downloading several documents together** doesn't work with a password-protected one among them. See
  [Downloading Documents](../downloading-documents/#a-password-protected-document).
