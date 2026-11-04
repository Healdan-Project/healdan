---
title: Healdan Settings
weight: 7
---

Healdan's own settings are under **Administration → Healdan → Settings**. Click **Save** after changing them.
Clients are saved from their own window, without Save. Signing in through a provider, such as Microsoft Entra ID,
is set up on the **Sign-In** tab beside them: see [Sign-In Providers](#sign-in-providers).

![The Healdan settings panel, with the Sign-In tab beside it: matter labels, numbering, default Class, the Recent Matters window, the next doc #, the two device sign-in limits, API origins allowed (CORS), and the client list with Add client and Edit, holding seven clients from 998 Ashcombe Reeve LLP to 1006 Oliver and Grace Pemberton](healdan-settings.png)

| Setting | What it does |
|---|---|
| Matter label, Plural label | the word Healdan uses for a matter, such as Matter and Matters, or Project and Projects. It shows in the scope picker, the left pane's lists, the New Matter dialog and this panel, and renames the Matter property and the right-click menu items. Others see it after they next sign in. "Client" can't be changed |
| Numbering | per client or system-wide. See [Matter Numbers](../clients-and-matters/#matter-numbers) |
| Default Class | the Class a document gets when nothing above it sets one. "Docs" to begin with |
| Recent Matters window | how many days of a person's work their Recent Matters list covers. 30 to begin with |
| Next doc # | the number the next document gets. It can only be raised, for example to start at 1000 after a trial. The panel shows the lowest number allowed |
| Device sign-in expires after (unused days) | how long a device signed in through Healdan's [API](../../developer/api/) stays signed in without being used. 30 to begin with, and from 1 to 3650. A change applies to every device at once |
| Device sign-in lasts at most (days) | how long a device's sign-in lasts however often it's used. 365 to begin with, from 1 to 3650, and no less than the unused days. A change applies to sign-ins made after it |
| API origins allowed (CORS) | the addresses, other than Healdan's own, whose pages may call the API, such as `https://localhost:3000`, separated by commas: scheme, host and an optional port, with no path and no `*`. Empty to begin with, which is right unless an add-in is served from another address |
| Clients | the client list. **Add client** adds one, and **Edit** changes the selected client's name |

## The Class and Subclass Lists

The choices for Class and Subclass belong to the Healdan profile's attribute set:

1. Go to **Administration → Document metadata → Templates**, and open the **Attribute sets** tab.
2. Select **Healdan profile**, then the **Class** or **Subclass** attribute, and open its **Options**.
3. For each Subclass option, put the Class it belongs to in its **Category**. Healdan refuses a Subclass whose
   Category isn't the document's Class.

## Device Sign-Ins

A device that signs in through the API, such as an Office add-in, keeps a token instead of the password. The token
works only in the API: LogicalDOC's web services refuse it. The web page has no list of devices; the API lists a
person's own. To sign out every device a person has signed in on, change their password or disable them. Deleting a
person stops their devices at once too.

**A lost device.** Change the person's password, which signs out every device. Then have them delete any LogicalDOC
API keys they don't recognise: under the person icon in the top bar, **Security → API Keys**, right-click a key and
choose **Delete**. A device token can't make a key, but whoever had the device may have had the web page too, and a
key outlasts a password change. Last, end the person's open sessions: under **Administration → System → General**,
open the **Sessions** tab, right-click each of their sessions and choose **Kill**. Neither a new password, a deleted
key nor disabling the person closes a web page or key session that's already open.

An account that [signs in through a provider](#sign-in-providers) has no password to change: **Change Password** answers that
the password is wrong. Disable the account and enable it again instead, under **Administration → Access Control →
Users**, which signs out every device. Its API keys
never sign in, so there are none to delete. Then end its open sessions in the same way.

## The Sign-In Throttle

Healdan limits wrong passwords on every way into it: the web page, the API, WebDAV, CMIS and LogicalDOC's web
services.

- **Five wrong passwords in a row for one username** block that username for 15 minutes, wherever the attempts
  come from.
- **Twenty failed sign-ins in a row from one network address** block that address for 15 minutes, for every
  username.
- **A block lifts by itself** 15 minutes after the last failed attempt. Attempts during a block aren't counted, so
  they don't make it longer. A successful sign-in with the password clears the count for its username and its
  address.
- **The account is never disabled,** so nobody has to unlock it.
- **People see** "Your username is temporarily blocked. Try after 15 minutes." on the sign-in page, or "Your IP is
  temporarily blocked. Try after 15 minutes." for a blocked address. The API answers `429 too-many-attempts`.
- **Alerts.** When a username, an address or an API key gets blocked, the users in `throttle.alert.recipients`
  (`admin` to begin with) get one message in Healdan about it, and an email too if
  [outgoing email](../system/outgoing-email/) is set up. Attempts during the block don't send more.
- **`admin` and the server's own address.** LogicalDOC never blocks the `admin` account signing in from
  `127.0.0.1` or `::1`. Only a request from inside the app's container comes from that address, and Healdan believes
  a forwarded `127.0.0.1` only from a proxy it trusts. Give `admin` a long password that isn't used anywhere else
  all the same.

Behind a reverse proxy, Healdan has to see each person's own address. See
[Reverse Proxy](../../install/reverse-proxy/#the-sign-in-throttle-behind-a-proxy), which says which proxies
Healdan trusts and how to set yours.

### Changing the Limits

The throttle has no screen in Healdan. Its settings are in the configuration volume's `context.properties`. While
the app runs, a settings screen that saves rewrites the whole file from what the app read when it started, and your
change would be lost. So stop the app first, and start it again afterwards. From the folder with Healdan's
`compose.yaml`:

```bash
docker compose stop app
docker run --rm -it -v logicaldoc_logicaldoc-conf:/conf busybox vi /conf/context.properties
docker compose start app
```

In `vi`, find a setting with `/` and its name, press `i` to edit, then `Esc` and `:wq` to save and quit. The app
writes each setting as `name=value`. [Configuration](../../install/configuration/#settings-without-a-screen) says
more about editing the file.

| Setting | Healdan's value | What it does |
|---|---|---|
| `throttle.enabled` | `true` | turns the throttle on or off |
| `throttle.username.max` | `5` | wrong passwords in a row that block a username |
| `throttle.username.wait` | `15` | minutes a username stays blocked |
| `throttle.ip.max` | `20` | failed sign-ins in a row that block an address |
| `throttle.ip.wait` | `15` | minutes an address stays blocked |
| `throttle.username.disableuser` | `false` | `true` would also disable the account when its username is blocked, so it stays shut until an admin enables it. Leave it `false` |
| `throttle.alert.recipients` | `admin` | the usernames alerted, separated by commas without spaces |

LogicalDOC ships the throttle switched off. Healdan sets the first six once per install, so values you change
afterwards stay.

## Sign-In Providers

A sign-in provider is a service your firm already signs people in with, such as Microsoft Entra ID or Authentik.
Once one is set up and on, the sign-in page shows its button, and people sign in there rather than with a Healdan
password. The **Sign-In** tab, beside Healdan's settings, sets providers up. Changes take effect at once, with no
restart. If a change stops people signing in, `admin` signs in with its password and puts it right: see
[`admin`, the Emergency Account](#admin-the-emergency-account). The Install manual's
[Sign-In Providers](../../install/sign-in/) has what to plan for, and the steps at the provider for Microsoft Entra
ID, Authentik and others. Entra ID's is experimental, as it hasn't been tried against a real Entra tenant yet.

### The Sign-In Tab

![The Sign-In tab: Add, Edit, Remove, Move Up and Move Down above the list of providers, which holds Sign In with Microsoft, id entra, preset Microsoft Entra ID, on, with no accounts linked; below it the options, with the password link's label, the admin alert and its recipients, and Outgoing Email saying it needs outgoing email, which isn't set up](sign-in-tab.png)

| Part | What it does |
|---|---|
| Providers | each provider, with its label, id and preset, whether it's on, and how many accounts are linked to it, in the order of their buttons on the sign-in page |
| Add | asks for the new provider's **Id** and **Preset**, then opens its window. The id is lower-case letters, digits and hyphens, at most 32, such as `entra`, and not `done`, `password` or `recheck`, which are Healdan's own. It's part of the redirect address, so it can't change |
| Edit | opens the selected provider's window. So does double-clicking it |
| Remove | removes the selected provider, once no account is linked to it. See [Taking a Provider Away](#taking-a-provider-away) |
| Move Up, Move Down | move the selected provider's button up or down the sign-in page |
| Password Link's Label | the link under the buttons that unfolds the password form. **Sign In with a Password** to begin with |
| Email When admin Signs In with Its Password, Alert Recipients | the `admin` alert. See [The `admin` Alert](#the-admin-alert) |
| Outgoing Email | whether the alert can be sent |

**Save**, under the options, saves the password link's label and the alert. A provider is saved from its own window.

### A Provider's Settings

![The Sign In with Microsoft provider's window: id entra and preset Microsoft Entra ID, the button label, an Entra ID issuer and client ID, an empty Client Secret marked Set, On and Link by Email ticked, the redirect address with its copy icon, the folded Claims, and a group map from two Entra ID group object ids to Fee Earners and Partners](provider-window.png)

| Setting | What it holds |
|---|---|
| Id, Preset | as chosen when the provider was added. The preset fills in the rest to suit the provider |
| Button Label | the button's words on the sign-in page, such as **Sign In with Microsoft** |
| Issuer | the provider's issuer address, which starts `https://`: `https://login.microsoftonline.com/<tenant id>/v2.0` for Entra ID, or `https://<authentik>/application/o/<slug>/` for Authentik. It must match the address the provider gives for itself exactly, a slash at the end included. While any account is linked it can't change, but for that slash: a link is the provider's id and the person's identity, so a new issuer would hand the links to whoever its identities are. Unlink everyone first |
| Client ID, Client Secret | from Healdan's registration at the provider. The secret is never shown again: its field is empty, with **Set** or **Not Set** beside it. Type a new one to replace it, or leave it empty to keep the one stored. A secret has at most 1000 characters, fewer in a script that takes more than one byte a character. If Healdan's secret key file is missing, Save asks before it makes a new key, which leaves every other provider's secret to enter again: see the Install manual's [Backup and Restore](../../install/backup-and-restore/#if-the-key-file-goes-missing) |
| On | off hides the button and stops the provider's sign-ins. See [Turning a Provider Off](#turning-a-provider-off) |
| Link by Email | whether a first sign-in links to the account that has the person's email. See [Which Account a Sign-In Is](#which-account-a-sign-in-is) |
| Redirect Address | the address to register at the provider: Healdan's public address, then `/api/signin/<id>/callback`. The icon beside it copies it |
| Claims | folded away, and filled in by the preset. **Identity Claim** names the person, and never changes at the provider; it can't change here while any account is linked. It can't be one people can change at some providers, which would let one person take over another's account: `email`, `preferred_username`, `upn`, `unique_name`, `name` or `nickname`. Use `sub` or `oid`. **Username Claim**, **Email Claim** and **Groups Claim** name where the provider puts a new account's username, the person's email and their groups |

Click **Save** in the window, which then opens again as saved. The provider and its group map are saved together:
if either can't be, neither is, and a new provider isn't added. The presets fill in:

| | Entra ID | Authentik | Other |
|---|---|---|---|
| Button Label | Sign In with Microsoft | Sign In with Authentik | Sign In |
| Identity Claim | `oid` | `sub` | `sub` |
| Username Claim | `preferred_username` | `preferred_username` | `preferred_username` |
| Email Claim | `email` | `email` | `email` |
| Groups Claim | `groups` (`roles` for app roles) | `groups` | `groups` |
| Link by Email | on | off | off |

Entra ID's `oid` stays the same for a person even if the app registration is made again, where its `sub` would
change.

### Checking a Provider

**Check**, in a provider's window, tests the provider's saved settings, so save your changes first. It fetches what
the provider publishes about itself, and its keys, and confirms that:

- the issuer it gives is the one set, exactly;
- every address it gives starts `https://`;
- it signs with RSA or EC, and publishes a key to sign with;
- it takes a client secret at its token endpoint, as `client_secret_post` or `client_secret_basic`;
- it offers `offline_access`, without which Healdan can't [check back](#checking-back-with-the-provider) on a
  Healdan Helper's sign-in;
- a client ID is set, and a client secret Healdan can read;
- the provider takes the client ID and secret.

To test the client secret, Check sends the client ID and secret to the provider's token endpoint as a sign-in does,
with a code it makes up. A provider that takes them refuses only the code, with `invalid_grant`, and Check passes.
If it refuses the client instead, with `invalid_client`, or HTTP 401 with `unauthorized_client` or no error code,
Check says the provider refused the client secret: copy the secret from the provider again, check the client ID, and
save. Authentik answers the same when the redirect address isn't registered with it exactly. Any other answer, or
none, is a problem too, which says the secret wasn't tested, as is an answer from a provider that's down or busy
(HTTP 5xx or 429), whatever it says. Check sends the secret only where a sign-in would, so not when the issuer
doesn't match or an address isn't `https://`, nor while Healdan doesn't know its own address, as the redirect
address is then only a guess: put those right first.

It shows the redirect address first, with an icon that copies it, then each problem in a sentence, or that
everything checks out. Check doesn't sign anyone in: try a sign-in through the button with a test person, which
also tests the group map.

When Healdan can't fetch what a provider publishes, as when it's down, it doesn't ask again for 30 seconds:
sign-ins through it fail at once meanwhile, and Check shows the same failure. Saving the provider asks again at
once.

If Healdan doesn't know its own address, the first problem says so, and the redirect address is only a guess from
the address you're using. Until `LOGICALDOC_PUBLIC_URL` is set to the server's `https://` address, a sign-in
through a provider works only on a page reached over `https://`, at the address it was reached at: see
[Configuration](../../install/configuration/).

### The Group Map and Its Gate

Each provider has a map from its groups to Healdan's, in its window: rows of **Provider Group** and **Healdan
Group**. **Add Row** adds one: type the provider group, and pick the Healdan group. **Remove Row** takes out the
selected row. Save the window to keep them.

- **A provider group** is a value the groups claim carries: an Entra ID group's object id, or an app role with the
  Groups Claim set to `roles`, or an Authentik group's name.
- **The map is the gate.** A sign-in whose provider groups map to no Healdan group is refused, and nothing is made or
  linked. The person is told their account doesn't give them access to Healdan. So the map is also the list of who
  may sign in. A linked account refused like this is also taken out of every mapped group.
- **Each sign-in syncs the groups.** For each Healdan group in the map, the person is put in it when one of their
  provider groups maps to it, and taken out when none does. Groups outside the map, Everyone and the person's
  personal group are left alone, so groups you give someone by hand stay. A group in the map is the provider's to
  decide: given by hand to a linked account, it goes at their next sign-in unless one of their provider groups maps
  to it. So map groups you don't also give by hand, rather than one such as `author` that you do.
- **One provider group can map to several Healdan groups,** and several provider groups to one.
- **Any group can be mapped, `admin` included.** An admin skips every permission check, so see
  [Day-to-Day Accounts Shouldn't Be Admins](../users-and-groups/#day-to-day-accounts-shouldnt-be-admins) first. A
  personal group can't be mapped, and the picker doesn't offer one.
- **Renaming a Healdan group keeps its rows.** A deleted group's rows are ignored, show as deleted, and go when the
  window is next saved.

### Which Account a Sign-In Is

When someone signs in through a provider, Healdan finds their account in this order:

1. **The account already linked** to that person at that provider.
2. **The gate.** Their provider groups must map to a Healdan group, or the sign-in is refused.
3. **Link by email,** when the provider's **Link by Email** is on. The provider's email for the person must match
   exactly one account's email, ignoring case. That account must not be `admin`, a system account or already
   linked, and must be enabled and not expired. It's then linked. An email that matches an account that can't be
   linked, or more than one account, is refused, rather than make a second account with the same email.
4. **Otherwise, a new account,** with the provider's username, first name, last name and email, the server's default
   language, and the groups the map gives. A username that's taken, is `admin`, or has a `>` in it is refused.

**Link by Email** is on in the Entra ID preset, where the tenant's admins set people's emails, and off in
Authentik's, where by default people can't change their own, though an older instance, or one an administrator has
changed, may allow it. Turn it on only where people can't: a person who can change their email at the provider could
be linked to whichever account has the email they type.

At every sign-in, the account's names and email follow the provider's. Its username never changes.

### What Linking Changes

Linking an account, or making one, turns it into a provider account:

- **It has no password.** Healdan removes it, and refuses to store one by any route: Change Password, **Lost your
  password?**, the user editor and LogicalDOC's web services. So no password signs it in: not the sign-in page's
  form, WebDAV, CMIS or the web services. See [Known Limitations](../../install/known-limitations/).
- **Its LogicalDOC API keys are deleted,** as a key would let it in without the provider. A key made afterwards,
  under the person icon's **Security → API Keys**, never signs in.
- **Its open sessions are closed,** in the web page, WebDAV, CMIS and the web services, however they were opened:
  with its password, with its keys, or by another account's key impersonating it. Only the sign-in through the
  provider that linked it stays signed in.
- **Its Healdan Helpers signed in with the old password are signed out,** and ask the person to sign in again,
  through the provider.
- **Everything else stays:** its user id, and with it its documents, check-outs, history, My Matters, favourites
  and grants.

### Readying an Account Before a First Sign-In

To have someone's account ready before their first sign-in, with its grants or matters set up, make it by hand
under **Administration → Access Control → Users**, with a password and their email exactly as the provider has it.
With the provider's **Link by Email** on, their first sign-in through it links the account, and removes the
password. Their provider groups must still map to a Healdan group, or that sign-in is refused. Groups the map
doesn't name stay as you set them.

### Linked Accounts

A provider's window lists the accounts linked to it: each one's username and name, how it was linked (**Email**, or
**New Account** for one the provider's sign-in made), when, and its last sign-in through the provider.

- **Unlink**, on an account's row, unlinks it after you confirm.
- **Unlink Everyone** unlinks every account linked to the provider, after a confirmation that says how many.

Unlinking an account:

- **keeps the account** and its user id, so it keeps its documents, check-outs, history, My Matters, favourites,
  grants and groups. Its groups are managed in Healdan from then on;
- **gives it a random temporary password,** which the person changes when they next sign in. Healdan emails it to
  them when [outgoing email is set up](../system/outgoing-email/#when-email-counts-as-set-up) and the account has an
  email address. Otherwise, or if the email can't be sent or the mail server doesn't take it within 15 seconds, the
  window shows it once, for you to pass on: nothing shows it again. Unlink Everyone lists each password it couldn't
  email, beside its username. You can also give an unlinked account a password of your own in the user editor;
- **signs out its Healdan Helpers,** and deletes its LogicalDOC API keys;
- **keeps the link as a record,** marked unlinked, with when and by whom.

If the person signs in through the provider again while it's on, the account is linked again by the usual rules,
and its password is removed again.

### Turning a Provider Off

Untick **On** in the provider's window, and save. If accounts are linked to it, Healdan first says how many people
can't sign in through it while it's off, and saves once you confirm. While it's off:

- its button leaves the sign-in page, and the Healdan Helper stops offering it, so its linked people can't sign in;
- Healdan stops contacting the provider;
- nothing is unlinked, so turning it on again lets everyone back in as before;
- Healdan Helpers already signed in through it keep working, within the [device sign-in](#device-sign-ins) limits,
  and web page sessions already open last until they've been idle for 30 minutes.

### Taking a Provider Away

1. **Turn it off,** so nobody new signs in through it.
2. **Unlink Everyone,** so each account gets a password.
3. **Remove it** on the Sign-In tab. Remove refuses while any account is linked, and says: to stop its sign-ins and
   keep its links, turn it off; to remove it, open it and Unlink Everyone first. Its settings and its group map go.
   The record of the accounts it linked stays.

### `admin`, the Emergency Account

`admin` is the break-glass account. It's never linked to a provider, and a provider username of `admin` is refused.
It keeps its password on every route, and the password link stays on the sign-in page whatever state a provider is
in, so `admin` can always sign in and put a provider right. Other accounts that aren't linked work the same way.

During a provider outage:

| Who | What happens |
|---|---|
| `admin`, and other accounts that aren't linked | they sign in with their passwords, as always |
| Healdan Helpers already signed in through the provider | they keep working: an outage never signs them out |
| Web page sessions already open | they last until they've been idle for 30 minutes |
| New sign-ins through the provider | they wait for it: until then, the button says the provider couldn't sign them in |

`admin` skips every permission check, and its password is a single factor. Give it a long random password that
isn't used anywhere else, keep it offline, and use `admin` only to put things right. The
[sign-in throttle](#the-sign-in-throttle), and keeping Healdan on your network or VPN, limit who can try it.

### The `admin` Alert

With **Email When admin Signs In with Its Password** ticked, Healdan emails the **Alert Recipients** each time
`admin` signs in with its password while a provider is on. The recipients are usernames, separated by commas, each
with an email address in Healdan: `admin` to begin with. `admin`'s address is LogicalDOC's placeholder,
`admin@admin.net`, until you change it, so give it a real one or name your own username instead. The alert is off to
begin with.

- **It needs [outgoing email](../system/outgoing-email/#when-email-counts-as-set-up).** Until that's set up, the tab
  says "Needs outgoing email, which isn't set up", nothing is sent, and no message in Healdan takes its place.
- **With no provider on,** `admin`'s password is an ordinary sign-in, and nothing is sent.

### Checking Back with the Provider

A Healdan Helper that signed in through a provider is checked with it again, so that someone the provider stops
also loses their helper. Web page sessions aren't checked again: see
[Known Limitations](../../install/known-limitations/).

- **When:** each time the helper's session opens again, after 30 idle minutes or a restart, and once the last check
  is more than eight hours old. A last check more than two hours in the future, as after the server's clock was set
  back, makes one due at once.
- **The provider accepts:** when its answer carries the person's details, their names, email and groups follow it,
  as at a sign-in, gate included. So a provider must send the groups claim then too, or the helper is signed out.
  An ID token that isn't signed (`alg` of `none`), as Microsoft's documentation describes Entra ID's, is ignored as
  if none came: the check counts as passed, nothing is updated, and the person's details follow at their next
  sign-in. The log says so once for the provider.
- **The provider refuses,** as when the person was disabled there or their sessions were ended: the helper is signed
  out, and asks the person to sign in again.
- **The provider can't be reached:** nothing changes, and Healdan tries again no sooner than 15 minutes later. An
  outage never signs anyone out.
- **The provider is off:** it isn't contacted, and the checks start again when it's on.
- **A provider that doesn't offer `offline_access`** gives Healdan nothing to check with, so its helpers' sign-ins
  aren't checked again, and last as a password sign-in's do. Check warns about it.
- **A provider that issues a new refresh token at each check** and refuses the old one can sign a helper out if its
  answer to a check is lost on the way. The helper then asks the person to sign in again.
