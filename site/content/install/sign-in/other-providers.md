---
title: Other Providers
weight: 3
---

Healdan signs people in through any provider that speaks OpenID Connect and meets the list below. Microsoft Entra ID
and Authentik have their own pages and presets. For another provider, such as Keycloak, Okta or Zitadel, use the
**Other** preset, which fills in the usual claim names and leaves the issuer, the client and the group map to you.
Healdan's own tests sign in against a Keycloak server. **Check**, described in the Admin Guide's
[Checking a Provider](../../../admin/healdan-settings/#checking-a-provider), tests most of the list for you.

The examples use `healdan.example` for your Healdan server and `idp.firm.example` for the provider.

## What the Provider Needs

- **OpenID Connect discovery.** The provider publishes its details at `<issuer>/.well-known/openid-configuration`,
  where `<issuer>` is the issuer address you enter in Healdan. The `issuer` it reports must equal what you enter,
  character for character, a final slash included.
- **The authorisation code flow with PKCE.** Healdan sends a PKCE challenge with the `S256` method, asks for the
  response in the query string (`response_mode=query`), and sends a `nonce` and a `state`. The provider must take
  the client secret at its token endpoint as `client_secret_post` (in the request's body) or `client_secret_basic`
  (in an HTTP Basic header), whichever its discovery document lists. Healdan uses `client_secret_post` when the
  document lists it, and `client_secret_basic` when it lists that and not `client_secret_post`, or lists no methods.
  A provider that lists only other methods, such as `private_key_jwt`, can't be used, and **Check** says so.
- **ID tokens signed with RSA or EC.** The algorithm is `RS256`, `RS384`, `RS512`, `PS256`, `PS384`, `PS512`,
  `ES256`, `ES384` or `ES512`, and the provider publishes its keys. Healdan refuses an unsigned ID token at a
  sign-in, and one signed with HMAC (`HS256` and the like), which some providers fall back on when they have no
  signing key.
- **HTTPS everywhere.** The issuer, and every address the provider gives for itself, start `https://`, and the
  certificate is one the app's Java trusts: Healdan uses Java's usual certificate checks.
- **`offline_access` and refresh tokens,** for the helper's [re-check](../#checking-back-with-the-provider). Healdan
  asks for the scopes `openid`, `profile` and `email` for everyone, and `offline_access` as well for the helper. A
  provider that doesn't offer `offline_access` still works, but a helper's sign-in isn't checked again, and lasts as
  a password sign-in's does. Check warns about it.
- **The groups claim in refreshed ID tokens too.** When a refresh answer carries an ID token, Healdan applies the
  person's details from it, the group map's gate included. A refreshed ID token without the groups claim therefore
  gives the person no group, so the helper is signed out and the person's mapped groups taken away. If your provider
  leaves the groups out of refreshed ID tokens, ask it to include them. A refreshed ID token that isn't signed
  (`alg` of `none`) is ignored, as if none came: nothing is updated and the helper is kept, so the person's details
  follow at their next sign-in.
- **The claims below,** in the ID token. Healdan reads claims from the ID token alone and doesn't call a userinfo
  address.

**Providers that turn refresh tokens over strictly.** Some providers give a new refresh token with every refresh and
refuse the old one at once. If Healdan's check goes through but its answer is lost on the way, the next check presents
a refresh token the provider has already used, the provider refuses it, and the helper is signed out and asks the
person to sign in again. A provider that lets the old token be used again for a short time can avoid this.

## The Claims

The **Other** preset fills in the claims below, and you can change them under **Claims** in the provider's window.

| Claim | Preset | What Healdan does with it |
|---|---|---|
| Identity | `sub` | names the person at this provider. It must be a text claim that stays the same for a person and differs between people. An email address, a username or any value that can change is the wrong choice: if it changes, Healdan takes the person for someone new. A claim that depends on the app registration, as Entra ID's `sub` does, changes when the registration is made again. It can't be changed in Healdan while any account is linked to the provider |
| Username | `preferred_username` | the username of a new account. A name that's taken, is `admin`, has a `>` in it, or is more than 255 characters is refused, and the person is told Healdan already has an account with their username. It's used only when the account is made |
| Email | `email` | the person's email, to link by if **Link by Email** is on, and the account's email. A value without an `@`, or with a space in it, counts as no email |
| Groups | `groups` | the person's groups, as a list of text values, or a single value. Each is compared, capitals and all, with the **Provider Group** column of the group map |

Healdan also reads `given_name` and `family_name` for a new account's first and last name, and `name` where they're
missing. The names and email follow the provider at each sign-in.

**Link by email is off in the preset,** because it makes Healdan trust the email claim to name an account. Healdan
doesn't read `email_verified`. Turn **Link by Email** on only where administrators set people's emails, or the
provider has checked them and people can't change them afterwards. While it's off, a person who already has a Healdan
account isn't linked to it: they get a new account, or are refused if the username is taken. Link by Email can be
on just while people first sign in, then off again: an account that is linked stays linked.

## Setting It Up

{{% steps %}}

### Register Healdan at the Provider

Create a confidential OpenID Connect client, which has a client secret. Give it the redirect address

```text
https://healdan.example/api/signin/<id>/callback
```

with your own address and the id you'll give the provider in Healdan. Allow the code flow, and the `offline_access`
scope. Give the client the claims above, the groups included, in the ID token.

### Add the Provider in Healdan

On the Sign-In tab of **Administration → Healdan → Settings**, choose **Add**, give the id, and choose the **Other**
preset. Then fill in:

| Setting | Value |
|---|---|
| Button Label | what the button says, such as **Sign In with Our Provider**. The preset's is **Sign In** |
| Issuer | the provider's issuer address, such as `https://idp.firm.example/realms/firm` |
| Client ID, Client Secret | from the client you registered |
| Claims | the names of the claims the provider uses, if they differ from the preset's |
| Group map | a row for each provider group that may sign in, and the Healdan group it maps to |

**Save**, then **Check**. It confirms the issuer and the signing algorithms, that every address is `https://`, that
the provider takes a client secret at its token endpoint, and that it offers `offline_access`, and shows the redirect
address to compare with the one you registered.

### Try It

Sign in with a test person in a private window, then look at the account Healdan made or linked and the groups it has.
If a helper is asked to sign in again soon after it did, check that refreshed ID tokens carry the groups claim, and
see the note above on refresh tokens.

{{% /steps %}}
