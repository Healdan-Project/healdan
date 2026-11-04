---
title: Sign-In Providers
weight: 6
---

Healdan can sign people in through a service your firm already signs them in with, such as Microsoft Entra ID or
Authentik, so they need no Healdan password. This section is for whoever runs the server. It says how that works,
what to plan for, and how to set up [Microsoft Entra ID](entra-id/), [Authentik](authentik/) and
[any other OpenID Connect provider](other-providers/). The settings are in Healdan Settings' **Sign-In** tab, which
the Admin Guide describes under [Sign-In Providers](../../admin/healdan-settings/#sign-in-providers). Entra ID's
setup is experimental, as it hasn't been tried against a real Entra tenant yet.

{{< callout type="info" >}}
Provider accounts need Healdan Helper 0.6.0 or later. An earlier helper can only ask for a password, and a provider
account has none.
{{< /callout >}}

## How It Works

- **The sign-in page shows a button** for each provider that's on, such as **Sign In with Microsoft**. The password
  form is behind a link under the buttons, **Sign In with a Password** to begin with.
- **The person signs in at the provider,** in their browser, and the provider sends the browser back to Healdan.
  Healdan never sees their password. Only the person's browser goes between the two, so the provider never calls
  Healdan, and Healdan can stay on your network or VPN as long as people's browsers reach both. Healdan itself calls
  the provider from the server, over HTTPS, to fetch its keys and check the sign-in.
- **Healdan finds the person's account.** An account already linked to them is theirs. Otherwise, with **Link by
  Email** on, the account whose email matches theirs is linked to them. Otherwise Healdan makes a new account, from
  the provider's username, names and email. The Admin Guide has the rules, in
  [Which Account a Sign-In Is](../../admin/healdan-settings/#which-account-a-sign-in-is).
- **The groups decide who gets in.** Each provider has a map from its groups to Healdan's. Someone whose groups map
  to no Healdan group is refused, and nothing is made or linked, so the map is also the list of who may sign in. At
  each sign-in the person is put in the Healdan groups the map gives them, and taken out of those it doesn't.
- **The Healdan Helper signs in through the browser.** It opens the system browser at the provider, the person signs
  in there, and the helper gets its own device sign-in from Healdan. It uses the same redirect address as the web
  page, so there's nothing more to register.

## Before You Start

- **Healdan's public address.** Set `LOGICALDOC_PUBLIC_URL` in `.env` to the server's `https://` address, as
  [Configuration](../configuration/) and [Reverse Proxy](../reverse-proxy/) say. The redirect address you register
  at the provider is built from it. Until it's set, Healdan uses the address each page was reached at, which works
  only over `https://` and only for the address you registered. Once it's set, a sign-in started on another name
  for the server goes to that address first. Give people that one address for the web page and the helper.
- **The server reaches the provider.** Healdan calls the provider's issuer address and every address it publishes,
  all of which must start `https://`, directly or through the HTTP proxy that Java's own settings name: see
  [Known Limitations](../known-limitations/). Each call gives up if its whole answer hasn't come within 10
  seconds (20 for a helper's re-check), or is larger than 1 MB (50 KB for the provider's keys).
- **A way back in.** `admin` keeps its password: see [Break Glass](#break-glass).
- **A plan for the groups.** Decide which provider groups may sign in, and which Healdan group each maps to. Any
  Healdan group can be mapped, `admin` included, but an admin skips every permission check: see
  [Day-to-Day Accounts Shouldn't Be Admins](../../admin/users-and-groups/#day-to-day-accounts-shouldnt-be-admins).
- **A plan for existing accounts.** If people already have Healdan accounts, the easiest way to keep their history,
  check-outs and grants is Link by Email, which needs their emails at the provider to match their Healdan accounts'
  emails. Turn it on only where people can't change their own emails.

## Setting a Provider Up

{{% steps %}}

### Choose an Id

Each provider has an id of 1 to 32 lower-case letters, digits and hyphens, such as `entra` or `authentik`. It's part
of the redirect address, so it can't change once the provider is added. `done`, `password` and `recheck` are Healdan's
own, and can't be a provider's.

### Register Healdan at the Provider

At the provider, register Healdan as an application. The provider asks for a redirect address, which is where it
sends the person's browser back to after they sign in:

```text
https://healdan.example/api/signin/<id>/callback
```

Put your own address in place of `healdan.example` and the id in place of `<id>`. The provider gives you a client ID
and a client secret, which tell it which application is asking. Note both. The provider's page has the steps.

### Add the Provider in Healdan

As an administrator, go to **Administration → Healdan → Settings** and open the **Sign-In** tab. Choose **Add**, give
the id, choose the preset, then fill in the issuer, client ID and client secret, and the group map. The preset
fills in the rest. **Save**, and then **Check**, which tests the saved settings, the client secret among them.

### Try It Before You Tell Anyone

In a private browser window, sign in with a test person who's in a mapped group. If it fails, `admin` signs in with
its password and puts it right.

{{% /steps %}}

| Provider | Preset | Page |
|---|---|---|
| Microsoft Entra ID, for a Microsoft 365 tenant | Entra ID | [Microsoft Entra ID](entra-id/) |
| Authentik, on your own server | Authentik | [Authentik](authentik/) |
| Any other OpenID Connect provider | Other | [Other Providers](other-providers/) |

A provider's button shows only once it's on and has an issuer, a client ID and a client secret. Changes take effect at
once, with no restart.

## Break Glass

`admin` is the way back in. It's never linked to a provider, and a provider username of `admin` is refused. It keeps
its password on every route, and the password link stays on the sign-in page whatever state a provider is in, so
`admin` can always sign in and put a provider right. Other accounts that aren't linked work the same way.

During a provider outage:

| Who | What happens |
|---|---|
| `admin`, and other accounts that aren't linked | they sign in with their passwords, as always |
| Healdan Helpers already signed in through the provider | they keep working: an outage never signs them out |
| Web page sessions already open | they last until they've been idle for 30 minutes |
| New sign-ins through the provider | they wait for it: until then, the button says the provider couldn't sign them in |

Healdan runs without the provider too. It fetches the provider's details the first time it needs them, so a provider
that's down never stops Healdan starting.

{{< callout type="warning" >}}
`admin` skips every permission check, and its password is a single factor. Give it a long random password that isn't
used anywhere else, and keep that password offline, such as printed and locked away, or in a safe your firm already
uses for such things. Use `admin` only to put things right. The
[sign-in throttle](../../admin/healdan-settings/#the-sign-in-throttle), and keeping Healdan on your network or VPN,
limit who can try it.
{{< /callout >}}

**An alert when `admin` signs in.** If you'd like to know whenever `admin` is used, tick **Email When admin Signs In
with Its Password** on the Sign-In tab. Healdan then emails the people you name each time `admin` signs in with its
password while a provider is on. It's off to begin with, and it needs [outgoing email](../../admin/system/outgoing-email/):
until that's set up, nothing is sent. See [The admin Alert](../../admin/healdan-settings/#the-admin-alert).

## What Provider Accounts Can't Do

An account that signs in through a provider is a provider account from then on, and it works with the provider
alone:

- **It has no password,** on any route. Healdan removes it when the account is linked or made, and refuses to store
  one afterwards, so no password signs it in: not the sign-in page's form, WebDAV, CMIS, or LogicalDOC's SOAP and REST
  web services. A scanner, a script or a WebDAV mount that signs in with a password needs an account that isn't linked.
- **It can't use LogicalDOC's API keys.** Linking an account deletes the keys it had and closes the sessions they
  opened, and a key made afterwards, under the person icon's **Security → API Keys**, never signs in.

Unlinking an account gives it a password again. [Known Limitations](../known-limitations/) lists these and the other
limits of provider sign-in, and the Admin Guide's [What Linking Changes](../../admin/healdan-settings/#what-linking-changes)
says what stays.

## Checking Back with the Provider

Healdan checks a Healdan Helper's sign-in with the provider at least every 8 hours, and when the helper's session
opens again. Someone disabled at the provider, or whose sessions there were ended, is asked to sign in again. When the
provider can't be reached, nobody is signed out. Web page sessions aren't checked again: see
[Known Limitations](../known-limitations/). The check needs the provider to offer `offline_access`, and the Admin
Guide's [Checking Back with the Provider](../../admin/healdan-settings/#checking-back-with-the-provider) has the rest.

## Taking a Provider Off an Instance

To stop using a provider, without leaving anyone locked out:

1. **Turn it off,** by unticking **On** in the provider's window. Its button leaves the sign-in page, the helper stops
   offering it, and Healdan stops contacting it. Nothing is unlinked, so turning it on again lets everyone back in.
2. **Unlink Everyone,** in the provider's window. Each linked account gets a temporary password, which Healdan emails to
   the person if outgoing email is set up. Otherwise, or if an email can't go, the window shows that password once,
   for you to pass on.
3. **Remove** the provider on the Sign-In tab. Remove refuses while any account is linked. The provider's settings and
   group map go, and the record of the accounts it linked stays.

Accounts keep their user ids, so everything they own stays with them. The Admin Guide has the details in
[Taking a Provider Away](../../admin/healdan-settings/#taking-a-provider-away).

## The Secret Key

Healdan seals each provider's client secret, and the refresh tokens of helpers that signed in through a provider,
under a key in the configuration volume, `conf/healdan-secret.key`. Back it up with the database: a database restored
without its key reads those secrets as unset, and each provider needs its client secret entered again.

Healdan makes the key when it first seals a secret, and never makes another on its own once secrets are sealed. If
the file goes missing, providers count as off until you restore it, and nothing sealed is lost. If it's lost for
good, enter a provider's client secret again: Healdan says the key file is missing and asks before it makes a new
key.
[Backup and Restore](../backup-and-restore/#the-key-for-sign-in-secrets) says more.

## When a Sign-In Is Refused

The sign-in page tells the person only what they can do. The User Guide lists every message in
[When a Provider Sign-In Is Refused](../../user/getting-started/signing-in/#when-a-provider-sign-in-is-refused).
The log, `repository/logs/dms.log` in the repository volume, says why, naming the provider. It never holds a token, a
code or a secret. What to look for:

| The person is told | Usually |
|---|---|
| Their account doesn't give them access to Healdan | None of their provider groups is in the provider's group map. Add a row, or put them in a group that is. With Entra ID, it can also be that the groups claim isn't sent: see [Microsoft Entra ID](entra-id/#send-the-groups) |
| Their provider couldn't sign them in | The client secret is wrong or has expired, the issuer isn't the provider's exact issuer, or the provider can't be reached. **Check** on the Sign-In tab finds most of these. A redirect address that isn't registered exactly as Healdan builds it is refused on the provider's own page, before the person gets back to Healdan |
| Healdan can't tell which account is theirs | With Link by Email on, their email at the provider matches more than one Healdan account, or `admin`, or an account already linked to someone |
| Healdan already has an account with their username | A new account couldn't be made for them, because one with their username exists. Link by Email, or a different username at the provider, is the way round it |
| This account can't sign in now | The account Healdan found is disabled or past its expiry date, as with Link by Email matching a disabled account, or LogicalDOC's sign-in refused it, outside its working hours, say. Enable the account, or put its dates or hours right |
| That sign-in took too long or was already used | The person took more than 10 minutes at the provider, used the browser's Back button to send the provider's answer twice, or finished in another browser than the one they started in. It's also the sign-in starting on a different address from the one the provider sends the browser back to, which `LOGICALDOC_PUBLIC_URL` puts right. Starting again works |

{{< cards >}}
  {{< card link="entra-id/" title="Microsoft Entra ID" icon="office-building" >}}
  {{< card link="authentik/" title="Authentik" icon="key" >}}
  {{< card link="other-providers/" title="Other Providers" icon="puzzle" >}}
{{< /cards >}}
