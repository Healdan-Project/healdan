---
title: Adding a Backend
weight: 1
---

On the [Backends list](../#the-backends-list), **Add Backend** opens **Add a Storage Backend**. Choose its **Kind**,
**S3** to begin with or **Local Folder**, and the window shows that kind's settings. A new backend holds nothing until
a Store uses it: see [Choosing a Store's Backend](../choosing-a-stores-backend/).

## An S3 Bucket

Make the bucket, and a user for Healdan that can use only that bucket, at the S3 service first: for Wasabi, the
Install manual's [Cloud Storage](../../../install/cloud-storage/) says how.

| Setting | What to enter |
|---|---|
| Name | a name of your own, such as Cloud Storage, of 100 characters at most. Each backend's name is its own, whatever its case |
| Endpoint | the service's address for the bucket's region, such as `https://s3.eu-west-1.wasabisys.com`. It must start `https://`. Healdan takes `http://` only for an S3 server whose every address is on a private network or the server itself, such as one in your office, and with **Path-Style Addressing** ticked. The files' names, and their contents unless the backend is encrypted, then cross that network unprotected (the secret itself never does, as requests are only signed with it), so use `https://` wherever you can |
| Region | the bucket's region, such as `eu-west-1` |
| Bucket | the bucket's name |
| Prefix | optional: a folder in the bucket, such as `healdan`, so one bucket can serve several backends, or several Healdan servers. Healdan adds a `/` to it |
| Access Key, Secret | the S3 user's access key and its secret. The secret is never shown again, and the access key is shown only on the backend's **Edit** |
| Path-Style Addressing | puts the bucket in the address's path rather than its host name. Some S3 servers need it, and Healdan was tested with Wasabi with it ticked |
| Encrypt | ticked for S3 to begin with. It can't change once the backend is added. See [Encryption and Recovery Keys](../encryption-and-recovery-keys/) |

**Two backends can't share a place.** Healdan refuses a bucket that another backend at the same S3 service uses with
the same prefix, or with a prefix that holds or sits inside this one's (an empty prefix holds every other), as the
two would write over each other's files. Every Amazon S3 endpoint is one service, as Amazon's bucket names are shared
across its regions; any other service is its endpoint's host and port, so two servers on one host are two. When it
adds a backend, it writes a small marker object in its place, `.healdan-backend` under the prefix, naming this
install and the backend, and it refuses a place whose marker names another install or backend: so two Healdan servers
can't share one place either.

## A Local Folder

A folder other than Original's is one you add to the app's container yourself, such as a share on a NAS: see the
Install manual's [Host Folders Instead of Volumes](../../../install/host-folders/#folders-for-more-storage-backends).

| Setting | What to enter |
|---|---|
| Name | a name of your own, as for S3 |
| Path | the folder's whole path inside the container, from `/`, such as `/mnt/archive`. It can't be, hold or sit inside Original's folder, staging, the read cache, the configuration folder or another backend's folder, links followed |
| Encrypt | unticked for a folder to begin with. Tick it for a folder on someone else's computer, such as a share on a NAS you don't control |

Choosing a kind sets **Encrypt** to that kind's own choice, so choose the kind first.

When it adds the backend, Healdan writes a marker file, `.healdan-backend`, in the folder, and makes a `.held` folder
there for the [held copies](../held-deletes/). If the marker is ever missing, as when a share didn't mount and the
path is an empty mount point, the backend fails and refuses saves, rather than writing files where nobody would find
them again. See [When a Backend Fails](../when-a-backend-fails/).

## Test, Then Add

**Test**, in the window, tries the settings as you typed them. For S3, it writes a test object in the bucket, reads
it back whole and in part, lists it and deletes it. For a folder, it checks that the folder exists, that Healdan can
write in it, and that it holds no other backend's marker. It says "Test passed." or, as in "Test found 2 problems:",
each problem it found, a setting still to type among them, and saves nothing. While it tests, the settings are held,
so what it says is always about the settings shown.

**Add** stays greyed out until the name and the kind's settings are typed: for S3 everything but the prefix, and for
a folder its path. It runs the same test first, and adds the backend only if it passes; otherwise the window says
"Test failed, so the backend wasn't added." and lists why. Then Healdan writes the backend's marker, makes an
encrypted backend's key and check object, and tests the backend once more in full. If any of those steps fails, the
ones before it are undone, so you can try again with the same name and place, and the window says why in Healdan's
words. While the backend is being added, **Cancel** is greyed out and Esc does nothing.

Once it's added, the backend's page opens. An encrypted backend first shows its recovery key, which you save and
confirm before any Store can use the backend: see
[Encryption and Recovery Keys](../encryption-and-recovery-keys/#saving-the-recovery-key). Its page opens when you
close that window.

## Testing a Saved Backend

**Test**, on the backend's page, tests it in full: its marker, a test object as above, and for an encrypted backend
its key, which must decrypt its check object. It says "Test passed." or each problem it found, under the backend's
status, and the status then shows what Test found. Test a backend after restoring onto a new server, after changing
its settings, and whenever you want to know that it works now: a backend that's OK isn't checked again until
something uses it.

## Changing a Backend

**Edit**, on the backend's page, opens a window named after it, such as **Edit Cloud Storage**.

- **What can change:** the name, and for S3 the endpoint, region, access key, secret and path style, to follow a
  renamed endpoint or a new access key.
- **Original** takes only its name. Its **Path** shows as text, "From the server's configuration."
- **The secret** field starts empty, with "Set: leave empty to keep it." under it. Type a secret only to replace the
  stored one. "Not Set: enter it again." means the stored secret can't be read, as after Healdan's secret key was
  made again: type it, and save.
- **The bucket, prefix and path** show as text, and can't change, once files are there ("Can't change once files are
  there."), while a Store keeps its files on the backend ("Can't change while a Store keeps its files here.") or while
  a Store in the Trash does ("Can't change while a Store in the Trash keeps its files here.", as its files stay there
  for its restore). To move a backend's files to another place, add a backend there and give its Stores the new one.
- **The kind and encryption** show as text, as "Encrypted, chosen when the backend was added.", and never change, as
  the recovery key is shown when the backend is added. To change encryption, remove the backend while it's empty and
  add it again.

**Save** stays greyed out until something has changed. It tests the settings first, with the stored secret unless you
typed one. If Test finds problems, the window lists them and asks "Save these settings anyway? The backend fails
until they work.", and **Save Anyway** saves them: so you can rename a backend during an outage, say. While the save
is on its way, **Cancel** is greyed out and Esc does nothing.

### If Healdan's Secret Key File Is Missing

Healdan seals each S3 secret with its secret key, a file in the configuration volume. If that file is missing when
you add an S3 backend or type a new secret in **Edit**, while values sealed with it are stored, nothing is added or
saved, and the window says so, as in "Healdan's secret key file is missing, and 3 stored values are sealed with it.
Restore it from a backup and save again, or make a new key." It says what a new key costs: the secret you typed is
kept, and the values sealed with the missing key can't be opened again, so each sign-in provider's client secret and
each other S3 backend's secret are entered again (those backends fail until then), and Healdan Helpers that signed in
through a provider sign in again.

- **If the file went missing by mistake,** press **Cancel**, restore it from a backup and try again: nothing needs
  restarting, and nothing is lost. See the Install manual's
  [Backup and Restore](../../../install/backup-and-restore/#if-the-key-file-goes-missing).
- **Make a New Key** makes a new key and adds or saves the backend with it. Do this only when there's no copy of the
  key to restore.
