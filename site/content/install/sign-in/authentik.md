---
title: Authentik
weight: 2
---

This page sets up sign-in through your own Authentik server. You need to be an administrator of Authentik, and
Healdan's public address set as [Sign-In Providers](../) says. Authentik's menus change between releases, so the
names below are today's, and the ideas are what to look for.

The examples use `healdan.example` for your Healdan server, `auth.firm.example` for Authentik, and `authentik` as the
provider's id in Healdan.

{{% steps %}}

### Create the Application and Its Provider

1. In Authentik's admin interface, go to **Applications → Applications** and choose **New Application**.
2. Name it, such as **Healdan**, and give it a slug, such as `healdan`. The slug is part of the issuer address, so
   choose it once.
3. Choose the OAuth2/OpenID provider type, and on its settings page:
   - Pick an authorisation flow. The implicit-consent one signs people in without asking each of them to approve
     Healdan.
   - Set the client type to **Confidential**.
   - Under **Redirect URIs**, enter `https://healdan.example/api/signin/authentik/callback`, as an exact match, not
     a regular expression. That is your server's address, then `/api/signin/`, the provider's id and `/callback`.
     Healdan's Sign-In tab shows the address it builds, so compare them letter for letter. The helper's sign-in
     uses this one address too.
4. Create the application. Open its provider and copy the **Client ID** and **Client Secret**.

The provider's issuer is `https://auth.firm.example/application/o/healdan/`: your Authentik's address, then
`/application/o/`, the slug and a slash. Authentik's provider page shows it, with the provider's OpenID configuration
address, which is that issuer followed by `.well-known/openid-configuration`.

### Choose a Signing Key

On the provider, set **Signing Key** to an RSA or EC certificate, such as Authentik's own self-signed one or one you
add under **System → Certificates**. Never leave it empty. Without a signing key, Authentik signs ID tokens with an
HMAC made from the client secret and publishes no keys, and Healdan refuses ID tokens signed that way. **Check** in
Healdan's Sign-In tab says so when that is the problem.

### Add the offline_access Scope Mapping

In the provider's protocol settings, the **Scopes** list should hold the mappings for `openid`, `profile`,
`email` and `offline_access`. Add `offline_access` if it isn't there. Authentik gives a refresh token only to a client
that asks for `offline_access`, and only if the provider has that mapping. Without one, Healdan has nothing to check
a helper's sign-in with, so helpers can't be [checked back](../#checking-back-with-the-provider), and **Check** warns
about it.

### Check the Groups

The default `profile` scope sends the person's groups in `groups`, by name, so leave the profile mapping in the
list, or one that sends `groups` as it does (see [Keeping First and Last Names Apart](#keeping-first-and-last-names-apart)).
In Healdan's group map, a provider group is an Authentik group's name, spelt exactly as Authentik spells it,
capitals included.

### Limit Who Can Use the Application

Open the application's **Policy / Group / User Bindings** tab, and bind the groups, users or policy that may use
Healdan. With nothing bound, every Authentik user can sign in to it. Healdan's group map still refuses anyone it
gives no group, but Authentik should refuse first.

### Stop People Changing Their Own Email

The Authentik preset leaves **Link by Email** off. Link by Email links a person to the Healdan account that has their
email, so a person who can set their own email at Authentik to someone else's could be linked to that someone's
account. Before you turn it on:

1. Go to **System → Settings** and check that **Allow users to change email** is off. Authentik's current
   documentation gives it as off to begin with, but an instance from an older release, or one an administrator has
   changed, may allow it.
2. Check that no flow or other route on your Authentik lets people edit their email.

Then you can turn **Link by Email** on in Healdan, to link the accounts people already have. You can turn it off again
once they've signed in: an account that's linked stays linked, and only a first sign-in uses the switch. With it off,
a person who already has a Healdan account is not linked to it. They get a new account, named from their Authentik
username, or are refused if Healdan already has an account with that name.

### Add the Provider in Healdan

On the Sign-In tab of **Administration → Healdan → Settings**, choose **Add**, give the id `authentik`, and choose the
**Authentik** preset. Then fill in:

| Setting | Value |
|---|---|
| Issuer | `https://auth.firm.example/application/o/healdan/`, exactly as Authentik's provider page gives it, the final slash included |
| Client ID | the provider's **Client ID** |
| Client Secret | the provider's **Client Secret** |
| Group map | a row for each Authentik group that may sign in: its name, and the Healdan group it maps to |

**Save**, then **Check**. Check confirms the issuer, that Authentik signs with RSA or EC, and that it offers
`offline_access`, tries the client ID and secret at Authentik's token endpoint, and shows the redirect address to
compare with the one you registered. If it says Authentik refused the client secret, copy the **Client Secret**
again, and compare the redirect address with the one under **Redirect URIs**: Authentik refuses the client for
either.

{{% /steps %}}

## The Authentik Preset

The preset fills in these, which you can leave as they are:

| Setting | Value | Why |
|---|---|---|
| Button Label | Sign In with Authentik | what the button says |
| Identity Claim | `sub` | by default a hash of the person's Authentik user id, which doesn't change |
| Username Claim | `preferred_username` | the username of a new account: the person's Authentik username |
| Email Claim | `email` | the email to link by, if **Link by Email** is on, and for the account |
| Groups Claim | `groups` | where the group names are |
| Link by Email | off | people may be able to change their own email: see above |

Leave the provider's subject setting at its default. If it's changed to something that can change, such as the
username or email, a person's `sub` changes, and Healdan takes their next sign-in for a new person.

Healdan reads `given_name` and `family_name` for the names, and `name` where they're missing. Authentik keeps one
name for each person, and by default sends all of it as `given_name`, with no `family_name`. Healdan takes a
`given_name` like that for a missing one, and splits `name` at its last space: Jane Doe gets the first name Jane
and the last name Doe. A last name of more than one word, such as de la Cruz, splits in the wrong place. For those,
give the person their first and last names apart in Authentik, as
[Keeping First and Last Names Apart](#keeping-first-and-last-names-apart) says. The names follow Authentik at each
sign-in, and so does the email, so put a name right in Authentik, not in Healdan, which changes it back at the next
sign-in.

Leave the provider's **Issuer mode** at its default, where each provider has its own issuer, based on the
application's slug. Healdan fetches the discovery document at the issuer followed by
`/.well-known/openid-configuration`, and Authentik publishes it under each application's slug, so the mode that
gives every provider the same issuer doesn't work with Healdan.

## Keeping First and Last Names Apart

A person's name in Authentik is one piece of text, and nothing in it says where the first name ends. To get every
name right, keep each person's first and last names as Authentik attributes, `given_name` and `family_name`, and
have the `profile` scope send them as the claims of the same names. For anyone without them, Healdan splits `name`,
as above.

### Give People Their Names

In Authentik's admin interface, go to **Directory → Users**, edit the person, and add both names to
**Attributes**, after anything already there:

```yaml
given_name: Maria
family_name: de la Cruz
```

Set both for each person. With only one, Healdan works the other out from `name`, which is right only where `name`
starts or ends with the one you set, and before 2026.8 the mapping below sends neither. There are two other ways to
fill them in:

- **From Authentik 2026.8,** you can turn on the **Given Name** and **Family Name** definitions under
  **Directory → Object attributes**, and the user form then has a field for each. Authentik calls object attributes
  a preview.
- **A prompt field** with the field key `attributes.given_name` or `attributes.family_name` stores what's typed in
  it as that attribute, when a User Write stage follows its Prompt stage, as in Authentik's default flows. Add the
  fields to an enrollment flow to ask people their names as they sign up, or to the user settings flow's
  `default-user-settings` stage to let people change their own. There, give each field the initial value
  `return user.attributes.get("given_name", "")` (or `"family_name"`), with **Interpret initial value as
  expression** on, so the form opens with the person's names. **Allow users to change name** in
  **System → Settings** doesn't cover these fields.

### Send Them to Healdan

**From Authentik 2026.8,** the default profile mapping sends a person's `given_name` and `family_name` attributes,
and the whole name as `given_name` for anyone without a `given_name` attribute, so there's nothing to change.

**Before 2026.8,** the default mapping sends only the whole name, so the provider needs a profile mapping of your
own:

1. Go to **Customization → Property Mappings**, open **authentik default OAuth Mapping: OpenID 'profile'**, and copy
   its expression.
2. Choose **Create**, then **Scope Mapping**. Name the mapping, such as **Healdan profile**, and set **Scope name**
   to `profile`.
3. Paste your copy as the expression, and change it to read like this:

   ```python
   claims = {
       "name": request.user.name,
       "preferred_username": request.user.username,
       "nickname": request.user.username,
       "groups": [group.name for group in request.user.groups.all()],
   }
   given = request.user.attributes.get("given_name")
   family = request.user.attributes.get("family_name")
   if given and family:
       claims["given_name"] = given
       claims["family_name"] = family
   return claims
   ```

   Keep the `groups` line as your copy has it: `request.user.groups` from Authentik 2026.2, as here, and
   `request.user.ak_groups` before. The group map needs `groups`. Without it, Healdan finds no group for anyone and
   refuses every sign-in, telling people their account doesn't give them access to Healdan.

   The mapping sends `given_name` and `family_name` only for a person who has both attributes. For everyone else it
   leaves `given_name` out, and Healdan splits `name`.
4. Open the provider, and in **Scopes**, take out the default profile mapping and add yours. Authentik runs every
   mapping in the list for the scope, in no set order, so with both there the default one could send the whole name
   over yours.
5. On the provider's **Preview** tab, choose a person who has both attributes, and check that the JWT payload has
   their `given_name`, `family_name` and `groups`.

When you upgrade Authentik to 2026.8 or later, put the default profile mapping back in place of yours. It then sends
the names itself, and Authentik keeps it in step with its own changes, such as the move from `ak_groups` to `groups`.

Healdan changes a person's names at their next sign-in, and when it next
[checks back](../#checking-back-with-the-provider) on their helper's sign-in.
