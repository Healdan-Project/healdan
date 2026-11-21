---
title: Encryption and Recovery Keys
weight: 2
---

An encrypted backend holds only encrypted files. Healdan encrypts each file on the server before it leaves, so the S3
service, or whoever runs the share a folder is on, can't read it. S3 backends are encrypted unless you untick
**Encrypt** when you add them; local folders aren't unless you tick it.

- **How:** each file is encrypted with the backend's own key (AES-256-GCM, in segments of 1 MiB, with Google's Tink
  library), and bound to its document and name, so one file's object can't be passed off as another's. A part of a
  file, such as the start of a video, is decrypted alone, without the rest.
- **What the S3 service still sees:** each object's size and name. The names follow LogicalDOC's layout under the
  prefix, such as `healdan/123/45/doc/1.2-thumb.png` for a thumbnail of version 1.2 of the document whose internal
  id is 12345: never a document's title or file name.
- **What isn't encrypted:** staging and the [read cache](../the-read-cache/), which are on the server. A backend's
  S3 secret is sealed apart, with Healdan's secret key, as sign-in providers' secrets are.

## The Key and the Recovery Key

- **The key** is 256 random bits that Healdan makes when the backend is added. It's kept in a file in the
  configuration volume, in `healdan-storage-keys`, which only the app's user can read. It's never in the database,
  so a copy of the database and the bucket together read nothing.
- **The recovery key** is the same key written out as 52 characters, in 13 groups of four. It uses no I, L, O or U,
  so it can't be misread, and it's read in any case, with spaces and hyphens ignored.

**Lose both the key file and the recovery key, and the backend's files are lost for good:** nobody can read them,
Healdan included. The key file is in the configuration volume's backups; keep the recovery key somewhere else.

## Saving the Recovery Key

When you add an encrypted backend, its recovery key shows at once, in a window named after the backend, such as
**Recovery Key of Cloud Storage**. It says to save the key now, away from the server, and that losing both the key
file and the recovery key loses the backend's files for good. That showing is recorded, as every later one is (see
[Showing It Again](#showing-it-again)).

1. Save it now, away from the server: in the firm's password manager, or on paper kept locked away. **Copy**, beside
   it, puts it on the clipboard, ready to paste into the password manager. If the browser doesn't let it use the
   clipboard, the window says "Couldn't copy the recovery key. Select it and copy it, or write it down."
2. Type its last group of four characters in **Last Group**, and press **Confirm**. Healdan says "The recovery key of
   Cloud Storage is confirmed: Stores can use it". A group that isn't the key's last is refused: "That isn't the last
   group of this backend's recovery key: type its last four characters, as it was shown."

Until you confirm it, no Store can use the backend: a Store's choice of backend doesn't offer it, the backend's status
ends "Its recovery key isn't confirmed, so no Store can use it yet.", and its page's **Recovery Key** says **Not
Confirmed**. **Later** closes the window without confirming, and the backend's page opens. To confirm it from there:

- **Confirm Recovery Key**, on its page while the key isn't confirmed, asks for the last group from where you saved
  the key, without showing it again.
- **Show Recovery Key** shows the key again, and asks for its last group too, until it's confirmed.

## Showing It Again

On the backend's page, **Show Recovery Key** asks first, "Show the Recovery Key of Cloud Storage?", saying that each
showing is recorded, with who and when, in its Key History, and that the server's log says it was shown. **Show
Recovery Key**, in that question, shows the key, in the same window as at the add, with **Copy**; **Cancel** shows
nothing and records nothing. A backend whose key file is missing has no key to show: "The key file of this backend is
missing or can't be read, so there is no key to show: enter its recovery key to put the file back."

Every showing is recorded, the one when the backend was added included:

- **Key Last Shown,** on the Backends list and the backend's page, shows the latest, and to whom.
- **Key History,** on the backend's page, lists every showing and every entering of its recovery key, newest first:
  **When**, **What** (**Recovery Key Shown** or **Recovery Key Entered**) and **By**. Once the backend is removed,
  no screen shows its history, as its page has gone, but Healdan keeps it in its records and in the administration
  record.

The server's log records each showing too, but never the key.

## Putting a Lost Key File Back

A backend whose key file is missing or isn't its own fails. Test says "The key file of this backend is missing", or
that the key file "isn't the key it was made with", as after a restore from the wrong backup. To put it back:

1. On the backend's page, press **Enter Recovery Key**.
2. Type or paste the key in **Recovery Key**, and press **Enter**. It's read in any case, with spaces and hyphens
   ignored.

Healdan checks the key against the backend before it writes anything, and refuses one that isn't the backend's: "That
recovery key isn't this backend's: its check object doesn't decrypt with it, so nothing was written. Check the key,
and type it again." When it's right, it writes the key file again: "The recovery key is right, and the key file of
Cloud Storage is back". The backend is OK again, and each entering is recorded in its Key History.

A key file deleted while the server runs isn't noticed until **Test** or the next start, as the server keeps using
the key it read, so nothing is lost meanwhile.

## A Wrong Key Is Caught

Each encrypted backend holds a small check object, `.healdan-check`, that Healdan encrypted when it added the
backend. Healdan decrypts it when the server starts and on **Test**. If the key file holds another key, the backend
fails and refuses saves, so no file is ever written under the wrong key. Enter its recovery key to put the right one
back.

## Changing Encryption

Encryption can't be turned on or off once a backend is added, and its key can't be changed in place. To change
either, add a new backend, give its Stores the new one, and remove the old one once its files have moved and its
held copies are gone: see [Removing a Backend](../removing-a-backend/).
