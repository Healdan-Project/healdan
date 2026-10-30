---
title: Outgoing Email
weight: 4
---

Healdan sends email through your firm's mail server, which you set under **Administration → Settings → Outgoing
Email**. Until you do, nothing it tries to send goes out.

## What Healdan Sends

- **The sign-in throttle's alert.** When a username, an address or an API key is blocked, the people in
  `throttle.alert.recipients` get an email as well as a message in Healdan. See
  [the sign-in throttle](../../healdan-settings/#the-sign-in-throttle).
- **A new device.** When someone signs in from a browser or device Healdan hasn't seen them use, they get an email
  saying so. **Alert login from new device**, under **Administration → Access Control → Security**, turns it off.
- **Passwords.** A new person's username and password, when whoever adds them ticks **Notify credentials**. And
  when someone clicks **Lost your password?** on the sign-in page, a link; once they follow it, a new password,
  which they change when they next sign in.
- **Documents and notices.** **Send by email** on a document's menu, and the notice people chosen on the **Notify
  users** tab get when someone uploads or checks in documents. Send by email needs the Download and Email
  permissions on every document sent.

## The Mail Server

| Setting | What it holds |
|---|---|
| Protocol | **SMTP** for most mail servers, or **SMTP Microsoft 365** for Microsoft's |
| Server, Port | the mail server's address and port, such as `smtp.healdan.example` and 587 |
| Connection Security | **None**, **STARTTLS**, **TLS** or **SSL**. Ask whoever runs your mail server; it's usually STARTTLS on port 587 |
| Use secure authentication | sends the username and password encrypted |
| Username, Password | the account Healdan signs in to the mail server with, if it needs one |
| Sender Email | the address Healdan's emails come from. It's `logicaldoc@acme.com` to begin with, so change it |
| Use user's email as sender | sends what someone emails from their own address, rather than the Sender Email |
| Target folder, Foldering | keeps a copy of every email sent, as a document. Leave Target folder empty: Healdan files documents only in a matter's folders, and no one matter is the place for every email the firm sends |

Click **Save**.

### Microsoft 365

Choosing **SMTP Microsoft 365** fills in Microsoft's server, port 587 and STARTTLS, and adds a **Client ID**,
**Tenant ID** and **Client secret**. They name an app registered for your firm in Microsoft Entra ID that may send
mail by SMTP; whoever runs your Microsoft 365 sets it up.

## Testing It

**Save** first: the test uses the saved settings, not the ones on the screen. Then click **Test Connection**, enter
an address to send to, and Healdan sends it a short test email. It says "Connection established" if the mail server
took the email, or "Connection failed" if it didn't.

## Message Templates

The **Message Templates** tab holds the subject and text of each email Healdan sends, in each language: choose
the **Language** first. Select a template to change its wording, and click **Save**.

| Template | Sent for |
|---|---|
| `bfa.alert` | the sign-in throttle's alert, which Healdan adds |
| `newdevice` | a sign-in from a new device |
| `psw.rec1` | a username and password: a new person's, or a new one after a password reset |
| `psw.rec2` | the link a password reset starts with |
| `newdoc`, `checkin` | the notice to people chosen on **Notify users** |

Templates of the System type can't be deleted.
