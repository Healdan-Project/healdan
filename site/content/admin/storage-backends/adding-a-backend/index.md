---
title: Adding a Backend
weight: 1
---

On the **Storage** tab, **Add Backend** opens **Add a Storage Backend**. Choose its **Kind**, **S3** or **Local
Folder**, and the window shows that kind's settings. A new backend holds nothing until a Store uses it: see
[Choosing a Store's Backend](../choosing-a-stores-backend/).

![The Add a Storage Backend window for an S3 backend named Cloud Storage, at the demo stack's stand-in S3 server: endpoint http://garage:3900, region garage, bucket healdan-smoke, prefix healdan, an access key, a secret shown as dots, and Path-Style Addressing and Encrypt ticked, with Test, Add and Close below](add-backend.png)

## An S3 Bucket

Make the bucket, and a user for Healdan that can use only that bucket, at the S3 service first: for Wasabi, the
Install manual's [Cloud Storage](../../../install/cloud-storage/) says how.

| Setting | What to enter |
|---|---|
| Name | a name of your own, such as Cloud Storage. Each backend's name is its own, whatever its case |
| Endpoint | the service's address for the bucket's region, such as `https://s3.eu-west-1.wasabisys.com`. It must start `https://`. Healdan takes `http://` only for an S3 server whose every address is on a private network or the server itself, such as one in your office, and with **Path-Style Addressing** ticked. The files' names, and their contents unless the backend is encrypted, then cross that network unprotected (the secret itself never does, as requests are only signed with it), so use `https://` wherever you can |
| Region | the bucket's region, such as `eu-west-1` |
| Bucket | the bucket's name |
| Prefix | optional: a folder in the bucket, such as `healdan`, so one bucket can serve several backends, or several Healdan servers. Healdan adds a `/` to it |
| Access Key, Secret | the S3 user's access key and its secret. The secret is never shown again |
| Path-Style Addressing | puts the bucket in the address's path rather than its host name. Some S3 servers need it, and Healdan was tested with Wasabi with it ticked |
| Encrypt | ticked for S3 to begin with. It can't change once the backend is added. See [Encryption and Recovery Keys](../encryption-and-recovery-keys/) |

**Two backends can't share a place.** Healdan refuses a bucket that another backend on the same host uses with the
same prefix, or with a prefix that holds or sits inside this one's (an empty prefix holds every other), as the two
would write over each other's files. When it adds a backend, it writes a small marker object in its place, `.healdan-backend` under
the prefix, naming this install and the backend, and it refuses a place whose marker names another install or
backend: so two Healdan servers can't share one place either.

## A Local Folder

A folder other than Original's is one you add to the app's container yourself, such as a share on a NAS: see the
Install manual's [Host Folders Instead of Volumes](../../../install/host-folders/#folders-for-more-storage-backends).

| Setting | What to enter |
|---|---|
| Name | a name of your own, as for S3 |
| Path | the folder's whole path inside the container, from `/`, such as `/mnt/archive`. It can't be, hold or sit inside Original's folder, staging, the read cache, the configuration folder or another backend's folder, links followed |
| Encrypt | unticked for a folder to begin with. Tick it for a folder on someone else's computer, such as a share on a NAS you don't control |

When it adds the backend, Healdan writes a marker file, `.healdan-backend`, in the folder, and makes a `.held` folder
there for the [held copies](../held-deletes/). If the marker is ever missing, as when a share didn't mount and the
path is an empty mount point, the backend fails and refuses saves, rather than writing files where nobody would find
them again. See [When a Backend Fails](../when-a-backend-fails/).

## Test, Then Add

**Test**, in the window, tries the settings as you typed them. For S3, it writes a test object in the bucket, reads
it back whole and in part, lists it and deletes it. For a folder, it checks that the folder exists, that Healdan can
write in it, and that it holds no other backend's marker. It shows **Test passed.**, or each problem it found, and
saves nothing.

**Add** runs the same test, and adds the backend only if it passes; otherwise it says "Test failed, so the backend
wasn't added", with why. Then it writes the backend's marker, makes an encrypted backend's key and check object, and
tests the backend once more in full. If any of those steps fails, the ones before it are undone, so you can try again
with the same name and place.

An encrypted backend then shows its recovery key, which you save and confirm before any Store can use the backend:
see [Encryption and Recovery Keys](../encryption-and-recovery-keys/#saving-the-recovery-key).

## Testing a Saved Backend

Select a backend on the tab and press **Test** to test it in full: its marker, a test object as above, and for an
encrypted backend its key, which must decrypt its check object. Its **Status** then shows what Test found. Test a
backend after restoring onto a new server, after changing its settings, and whenever you want to know that it works
now: a backend that's OK isn't checked again until something uses it.

## Changing a Backend

**Edit**, or double-clicking a backend, opens its window.

![The window of the S3 backend Cloud Storage: Kind S3; its name, endpoint and region editable; Bucket healdan-smoke and Prefix healdan/ shown as text, marked can't change once files are there; the access key; an empty Secret marked Set: leave empty to keep it; Path-Style Addressing ticked; and Encrypt ticked and greyed out, chosen when the backend was added](edit-backend.png)

- **What can change:** the name, and for S3 the endpoint, region, access key, secret and path style, to follow a
  renamed endpoint or a new access key.
- **The secret** field starts empty, with **Set: leave empty to keep it** beside it. Type a secret only to replace
  the stored one. **Not Set: enter it again** means the stored secret can't be read, as after Healdan's secret key
  was made again: type it, and save.
- **The bucket, prefix and path** can't change once files are there, which the window says beside them, nor while a
  Store keeps its files on the backend. To move a backend's files to another place, add a backend there and give
  its Stores the new one.
- **Encrypt** never changes, as the recovery key is shown when the backend is added. To change it, remove the
  backend while it's empty and add it again. The kind never changes either.

**Save** tests the settings first. If Test finds problems, it lists them and asks whether to save anyway, as the
backend fails until they work: so you can rename a backend during an outage, say.

If Healdan's secret key file is missing when you type a secret, Save asks before it makes a new key, which would
leave every other sealed secret to enter again: see the Install manual's
[Backup and Restore](../../../install/backup-and-restore/#if-the-key-file-goes-missing). Say no, and restore the
file.
