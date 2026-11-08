---
title: Cloud Storage
weight: 7
---

Healdan can keep a Store's files in a bucket at an S3 service, so that they survive a fire, a theft or a failed disk
at the office, and the server's own disk stays small. This page sets a bucket up at Wasabi. Once it's added, the
Admin Guide's [Storage Backends](../../admin/storage-backends/) says how backends work, and how a Store moves to one.

{{< callout type="info" >}}
Healdan is tested with Wasabi, and with Garage, a self-hosted S3 server that its own tests run against. Other S3
services should work, but aren't tested: see [Other S3 Services](#other-s3-services).
{{< /callout >}}

## Before You Start

- **Healdan encrypts every file before it leaves the server,** with a key it keeps in the configuration volume, so
  Wasabi holds only files it can't read. When you add the backend, Healdan shows its recovery key once: have the
  firm's password manager ready for it. See
  [Encryption and Recovery Keys](../../admin/storage-backends/encryption-and-recovery-keys/).
- **The server must reach Wasabi over HTTPS,** port 443.
- **Choose a region.** Each of Wasabi's regions has its own endpoint, `https://s3.<region>.wasabisys.com`, such as
  `https://s3.eu-west-1.wasabisys.com` for the region `eu-west-1`, in the United Kingdom. Wasabi lists them all on its
  [service URLs](https://docs.wasabi.com/docs/service-urls-for-wasabis-storage-regions) page. Choose the one your
  firm's rules on where client data may be kept allow, nearest the office.

## Setting Up Wasabi

{{% steps %}}

### Make the Bucket

In Wasabi's console, create a bucket in the region you chose, with a name of your own, such as
`ashcombe-reeve-healdan`. Leave versioning off: Healdan keeps every deleted file itself for the holding period (see
[Held Deletes and the Holding Period](../../admin/storage-backends/held-deletes/)), so versioning would only keep
more copies, each billed.

### Clear Incomplete Uploads After 7 Days

Healdan uploads a file over 64 MB in parts, and abandons the upload cleanly when a part fails. An upload cut short by
a crash or a lost connection leaves its parts in the bucket, and Wasabi deletes them after 31 days by itself. It bills
them as stored for their first 30 days, then as deleted for the rest of the plan's minimum storage duration (see
Wasabi's page on
[incomplete multipart uploads](https://docs.wasabi.com/docs/how-does-wasabi-handle-incomplete-multi-part-uploads)).

To clear them sooner, open the bucket's settings, and on its **Lifecycle** tab create a rule for the whole bucket that
deletes incomplete multipart uploads after 7 days. Wasabi applies it to uploads started after the rule is set (see
Wasabi's [Lifecycle](https://docs.wasabi.com/docs/lifecycle) page). The rule keeps the bucket tidy, but the plan's
minimum storage duration still applies to the parts it deletes, as to any object deleted early (see
[Wasabi's 90-Day Minimum](#wasabis-90-day-minimum)).

### Make a User for Healdan

Give Healdan a user of its own, which can use only this bucket, so that its access key can't reach the firm's other
buckets. In Wasabi's console, create a policy with this text, the bucket's name in place of
`ashcombe-reeve-healdan`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::ashcombe-reeve-healdan"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:AbortMultipartUpload"
      ],
      "Resource": "arn:aws:s3:::ashcombe-reeve-healdan/*"
    }
  ]
}
```

It lets Healdan do only what it does: list the bucket, read, write and delete files, and abandon an upload in parts.
Then create a user with programmatic access, which gives it an access key, attach the policy to it, and keep its
access key and secret key: Wasabi shows the secret key once.

### Add the Backend in Healdan

Under **Administration → Healdan → Settings**, open the **Storage** tab and press **Add Backend**:

| Setting | What to enter |
|---|---|
| Kind | S3 |
| Name | a name of your own, such as Wasabi |
| Endpoint | the region's endpoint, such as `https://s3.eu-west-1.wasabisys.com` |
| Region | the region, such as `eu-west-1` |
| Bucket | the bucket's name |
| Prefix | optional: a folder in the bucket, such as `healdan`, so the bucket can serve more than one backend or server |
| Access Key, Secret | the user's access key and secret key |
| Path-Style Addressing | ticked, as Healdan was tested with Wasabi |
| Encrypt | ticked |

Press **Test**, then **Add**. Save the recovery key in the firm's password manager, type its last group of four, and
press **Confirm**. See [Adding a Backend](../../admin/storage-backends/adding-a-backend/).

### Give a Store the Backend

In the **Stores** list on the same tab, choose the new backend as a Store's **Backend**, such as Client Work's. Its
files move to Wasabi in the background, and new saves into it go there too. See
[Choosing a Store's Backend](../../admin/storage-backends/choosing-a-stores-backend/).

{{% /steps %}}

## Wasabi's 90-Day Minimum

On Wasabi's pay-as-you-go plan, every object is billed for at least 90 days: one deleted sooner is billed for the
rest of the 90 days anyway (see Wasabi's
[minimum storage duration policy](https://docs.wasabi.com/docs/how-does-wasabis-minimum-storage-duration-policy-work)).
Other Wasabi plans can have another minimum.

Healdan holds a deleted file's object for the holding period, 30 days to begin with, before it deletes it, so that a
backup restored meanwhile still finds it. An object less than 90 days old costs nothing more for that, since Wasabi
would bill those days anyway. A holding period of up to 90 days suits Wasabi, and the
[holding period](../../admin/storage-backends/held-deletes/#the-holding-period) should be at least as long as the
oldest backup you'd restore: see [Backup and Restore](../backup-and-restore/).

## Other S3 Services

Any service that speaks Amazon S3's API should work. Healdan uses only plain calls: it writes objects with a
`Content-MD5` header and in parts, reads them whole and by range, lists, and deletes. It doesn't use versioning or the
service's own encryption. **Test**, when you add the backend, tries most of them.

- **The endpoint must start `https://`.** Healdan takes `http://` only for a server whose every address is on a
  private network or the server itself, such as one in your office, and only with **Path-Style Addressing** ticked, so
  that the address it checks is the one it connects to. The files' names, and their contents unless the backend is
  encrypted, then cross that network unprotected.
- **Path-Style Addressing** puts the bucket in the address's path rather than its host name, as some self-hosted
  servers need.
- **Garage,** which Healdan's tests run against, has no access policies of its own: give Healdan a key allowed on its
  bucket alone.
