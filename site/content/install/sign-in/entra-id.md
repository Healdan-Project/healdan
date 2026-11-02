---
title: Microsoft Entra ID
weight: 1
---

This page sets up sign-in through Microsoft Entra ID for a Microsoft 365 tenant of your own. You need an account that
can register applications and assign people to them, such as a Cloud Application Administrator, and Healdan's public
address set as [Sign-In Providers](../) says. Microsoft renames things from time to time, so the names below are
today's, and the ideas are what to look for.

The examples use `healdan.example` for your Healdan server and `entra` as the provider's id.

{{% steps %}}

### Register the Application

1. In the [Microsoft Entra admin center](https://entra.microsoft.com), go to **Entra ID → App registrations** and
   choose **New registration**.
2. Name it, such as **Healdan**, and under **Supported account types** choose **Single tenant only**. Healdan's
   issuer is your tenant's alone, so don't choose a multitenant option.
3. Choose **Register**. On the **Overview** page, copy the **Application (client) ID** and the **Directory (tenant)
   ID**.
4. Under **Manage**, choose **Authentication**, then **Add Redirect URI**, the **Web** tile, and enter
   `https://healdan.example/api/signin/entra/callback`. That is your server's address, then `/api/signin/`, the
   provider's id and `/callback`. Healdan's Sign-In tab shows the address it builds, so compare them letter for
   letter. Choose **Configure**.
5. Leave the implicit and hybrid flow options off, as Healdan uses the authorisation code flow. The helper's sign-in
   uses this one redirect address too, so there's nothing more to register.

### Add a Client Secret

1. Under **Manage**, choose **Certificates & secrets**, then **Client secrets** and **New client secret**.
2. Describe it, such as **Healdan until October 2027**, and choose how long it lasts. A client secret lasts at most 24
   months, and Microsoft recommends less than 12.
3. Choose **Add**, and copy the secret's **Value** now. Entra never shows it again. The **Secret ID** beside it is not
   the secret.

Put the expiry date in a calendar: see [When the Client Secret Expires](#when-the-client-secret-expires).

### Send the Groups

Healdan needs to know the person's groups, and its group map is the list of who may sign in.

1. Under **Manage**, choose **Token configuration**, then **Add groups claim**.
2. Choose **Groups assigned to the application**, make sure the ID token is among the token types, and save.
3. In **Entra ID → Enterprise apps**, open the application, choose **Users and groups**, then **Add user/group**, and
   assign the groups whose members may use Healdan. Assigning a group needs Microsoft Entra ID P1 or P2.

The groups arrive as object ids, which is what the group map holds. Find a group's under **Entra ID → Groups**, in
its **Overview**.

**Why "assigned to the application."** The other choices, such as **Security groups**, send all of a person's groups,
and a person in more than about 200 of them gets no groups claim at all, only a pointer to Microsoft Graph, which
Healdan doesn't follow. Healdan then refuses their sign-in as having no group and says so in its log. Sending only the
groups assigned to the application keeps the claim short for everyone. An assignment doesn't reach nested groups, so
assign the group that people are direct members of.

**App roles instead.** If you can't assign groups, or want roles that exist only for Healdan, define app roles under
**App roles → Create app role** on the app registration, with **Users/Groups** as the allowed member type and a
**Value** with no spaces. Assign people to the roles under **Enterprise apps → Users and groups**. Entra sends the
roles in `roles`, so set the provider's **Groups Claim** to `roles` in Healdan, and put each role's value in the group
map.

### Add the Optional Claims

Back under **Token configuration**, choose **Add optional claim**, the **ID** token type, and tick `email`,
`family_name` and `given_name`. Add them. If the portal offers to turn on the Microsoft Graph email and profile
permissions, say yes.

Healdan uses the email to link a person to the account that has it, and for the account's email, and the names for the
account's first and last name. Entra sends an email only for people whose mail attribute is set in your directory.
Healdan links by email in the Entra preset, so a person with no email claim isn't linked to their Healdan account: they
get a new one. Fill in people's mail before they sign in.

### Require Assignment

Without this, any account in your tenant, guests included, can sign in to the application at Microsoft's end. Healdan's
group map still refuses anyone it gives no group, but Entra should refuse first.

1. In **Entra ID → Enterprise apps**, open the application and choose **Properties**.
2. Set **Assignment Required** to **Yes**, and save.
3. While assignment is required, people can't give their own consent, so you grant it for them. First the
   application has to ask for the permissions Healdan needs, `openid`, `profile`, `email` and `offline_access`. On
   the app registration, choose **API permissions**, then **Add a permission**, **Microsoft Graph** and **Delegated
   permissions**, and tick those four. Skip any the list already shows, and add the rest.
4. Back on **API permissions**, choose **Grant admin consent** for your tenant, and confirm.

### Add the Provider in Healdan

On the Sign-In tab of **Administration → Healdan → Settings**, choose **Add**, give the id `entra`, and choose the
**Entra ID** preset. Then fill in:

| Setting | Value |
|---|---|
| Issuer | `https://login.microsoftonline.com/<tenant id>/v2.0`, with the **Directory (tenant) ID** in place of `<tenant id>` |
| Client ID | the **Application (client) ID** |
| Client Secret | the secret's **Value** |
| Group map | a row for each provider group: its object id (or role value), and the Healdan group it maps to |

**Save**, then **Check**. Check confirms the issuer, that Entra signs with RSA, takes a client secret at its token
endpoint and offers `offline_access`, tries the client ID and secret there, and shows the redirect address to compare
with the one you registered. If it says Entra refused the client secret, copy the secret's **Value** again, not its
**Secret ID**.

{{% /steps %}}

## The Entra ID Preset

The preset fills in these, which you can leave as they are:

| Setting | Value | Why |
|---|---|---|
| Button Label | Sign In with Microsoft | what the button says |
| Identity Claim | `oid` | the person's object id in your tenant. It stays the same if the app registration is ever made again, where `sub` would change |
| Username Claim | `preferred_username` | the username of a new account, usually the person's UPN, such as `jane@firm.example`. Microsoft says it can change, so Healdan uses it only when it makes the account, and the account's username never changes afterwards |
| Email Claim | `email` | the email to link by, and for the account |
| Groups Claim | `groups` (`roles` for app roles) | where the groups are |
| Link by Email | on | the tenant's admins set people's mail, and people can't change it |

Healdan reads `given_name` and `family_name` for the names, and `name` where they're missing. They follow Entra at each
sign-in, and so does the email.

## Groups Between Sign-Ins

Healdan [checks a helper's sign-in](../#checking-back-with-the-provider) with Entra at least every 8 hours. When
Entra's answer carries a signed ID token, the person's names, email and groups follow it. Microsoft's documentation
describes the ID token in a refresh answer as unsigned, and Healdan ignores an unsigned ID token on a refresh, as if
none came: the check counts as passed, and nothing is updated. If your tenant's answers are like that, the log says
once, naming the provider, that its refreshed ID tokens are unsigned, and a change to someone's groups in Entra
reaches Healdan when they next sign in. Someone Entra refuses outright, as a person who's been disabled, is still
signed out by the check.

## When the Client Secret Expires

The client secret stops working on its expiry date. Then:

- **The button fails.** The person is told their provider couldn't sign them in. The log says what Entra answered.
- **Helpers already signed in keep working.** Healdan signs a helper out only when Entra explicitly refuses its
  sign-in, as it does for someone who's been disabled. An expired client secret is a fault in Healdan's setup, not a
  refusal about the person, so the checks log an error and carry on.
- **`admin` still signs in** with its password, to put it right.

To replace the secret, add a new one in Entra under **Certificates & secrets**, then in the provider's window in
Healdan type the new **Value** into **Client Secret** and **Save**. No restart is needed. Do it before the old one
expires if you can: Entra keeps both working until you delete the old one.

## When Entra Refuses a Sign-In

| Entra says | Usually |
|---|---|
| AADSTS50011, the reply address doesn't match | The redirect address registered under **Authentication** differs from the one Healdan builds: the id, `https`, or a stray slash |
| AADSTS50105, the administrator has blocked users who aren't granted access | **Assignment Required** is on, and the person, or a group they're in, isn't assigned to the application |

When Entra signs a person in but Healdan refuses them, see [When a Sign-In Is Refused](../#when-a-sign-in-is-refused).
A Conditional Access policy that asks for a fresh sign-in can also make Entra refuse a helper's check, and the helper
then asks the person to sign in again.
