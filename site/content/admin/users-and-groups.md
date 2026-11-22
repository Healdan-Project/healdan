---
title: Users and Groups
weight: 5
---

The people who sign in to Healdan, and the groups that give them access, are managed in
[the Admin app](../the-admin-app/): **Users** and **Groups** are under **People** in its left pane, at `/admin/users`
and `/admin/groups`. Only administrators see them. A few things are still done in the Classic Interface: see
[In the Classic Interface](#in-the-classic-interface).

A *person* here is an account that signs in: an ordinary account or a read-only one, enabled or disabled. System
accounts, such as `_system`, which runs Healdan's own jobs, aren't people: they aren't listed or counted, and nothing
here changes them. An account deleted in the Classic Interface isn't listed either.

## Day-to-Day Accounts Shouldn't Be Admins

{{< callout type="warning" >}}
An admin account skips every permission check, so it can see and change everything. Give each person, admins
included, an ordinary account for their everyday work, and keep an admin account for administration.
{{< /callout >}}

Healdan's own rules, such as where documents can go, still apply to admins. An admin is a member of the `admin`
group, which also lets them use the Admin app. Adding someone to `admin`, in New User, on their page or on the group's,
asks first, and says they'll be able to read every document.

## The Users List

**Users**, at `/admin/users`, lists every person, by name, disabled and read-only accounts included. **New User**,
beside the title, adds one: see [New User](#new-user). Select a person to open [their page](#a-persons-page).

| Column | What it shows |
|---|---|
| Name | their first and last name, or their username when both are blank |
| Username | what they sign in with, which never changes |
| Groups | the groups they're in, Everyone among them, but not their [personal group](#personal-groups) |
| Signs In With | **Password**, or the [sign-in provider](../healdan-settings/#sign-in-providers) their account is linked to |
| Status | **Active**; **Disabled**, an account that can't sign in until it's enabled; or **Blocked**, an account whose username [the sign-in throttle](../healdan-settings/#the-sign-in-throttle) has blocked after too many wrong passwords, with when the block ends. A disabled account is **Disabled**, blocked or not |
| Last Sign-In | when they last signed in, by any route, or **Never** |
| Devices | how many [devices](#devices) are signed in as them |

<!-- P4 docs pass 2 -->

**Last Sign-In counts a device's sign-ins too.** An app on a device, such as Healdan Helper, signs in again with its
own sign-in each time its last session has timed out, so someone whose helper is in use has a recent Last Sign-In
even if they haven't typed their password for weeks.

The filters above the list all apply together:

- **Finding someone:** typing keeps the people whose username, email, first name, last name, or first and last name
  together, holds what you type, in any case.
- **Group** keeps the members of one group, and **Status** the people with one status.

<!-- P4 docs pass 2 -->

Click **Name**, **Username** or **Last Sign-In** to sort by it, and click it again to reverse the order. The list
starts by name. People who tie go by username, and by Last Sign-In, someone who has never signed in comes last either
way.

<!-- P4 docs pass 2 -->

## A Person's Page

A person's page, at `/admin/users/` and their account's number, has their name, with **Users**, in the crumb above it,
going back to the list. The Admin app's find opens it too. Beside the name are its actions:

- **Edit:** their names, email, language and time zone. See [Edit](#edit).
- **Reset Password**, for a password account: see [Reset Password](#reset-password).
- **Sign Out Everywhere:** see [Sign Out Everywhere](#sign-out-everywhere).
- **Clear Sign-In Block**, while they're **Blocked**: see [Clear Sign-In Block](#clear-sign-in-block).
- **Disable**, or **Enable** for a disabled account: see [Disable and Enable](#disable-and-enable).

Where Healdan would refuse one of them, the page says why before you try it, such as **Disable** on your own account:
see [What Healdan Refuses](#what-healdan-refuses).

<!-- P4 docs pass 2 -->

Below them are their details: their username, email, how they sign in, their status, language and time zone, when the
account was made, when its password was last set and whether it has to be changed at their next sign-in (neither, for
an account that signs in through a provider, which has no password in Healdan), and when they last signed in. Then
come their groups, their devices and what they hold.

An address that names no person says so, as one naming a system account or a deleted account does.

### Their Groups

Their groups are listed by name. **Add a Group** adds them to one, finding it as you type, and the Remove button (an ×)
beside a group takes them out of it. **Save** saves their groups, and stays greyed out until something has changed.

<!-- P4 docs pass 2 -->

- **Everyone stays.** Every account but a read-only one is always in [Everyone](#the-everyone-group), so it can't be
  taken out.
- **A read-only account** is always in `guest`, and can't be added to any other group: see
  [Read-Only Accounts and `guest`](#read-only-accounts-and-guest).
- **Adding them to `admin`** asks first, and taking them out of it may be refused: see
  [What Healdan Refuses](#what-healdan-refuses).
- **For an account that signs in through a provider,** a group the provider's group map names is the provider's to
  decide: their next sign-in there puts it back as the map says, whatever you saved. Groups the map doesn't name stay
  as you set them. See [A Group a Sign-In Provider Maps](#a-group-a-sign-in-provider-maps).

### Devices

Their devices are listed newest first, each with its name, such as "Healdan Helper on MacBook", the sign-in provider
it signed in through, if any, when it signed in, when Healdan last heard from it, to within a few minutes, and when its
sign-in ends of itself. It's the list the person sees in the User app's
[Signed-in Devices](../../user/getting-started/signed-in-devices/). The web page's own sign-in isn't a device, and
isn't listed: [Sign Out Everywhere](#sign-out-everywhere) ends it.

**Revoke**, beside a device, signs that one device out, after asking: it's signed out at its next request, and the
person signs it in again whenever they're ready. Their other devices stay signed in.

<!-- P4 docs pass 2 -->

### What They Hold

- **Their check-outs:** each document they have checked out, the latest first, with its doc #, title, version and
  matter, when it was checked out and, when one of their computers holds it, that computer and its app, so you can
  tell them where to check it in. Documents they've only locked aren't listed: the Classic Interface's
  **Locked documents** report has those (see [Reports](../system/reports/)). **Release**, beside one, ends the
  check-out, after asking, as the person's own **Release** in the User app does: what they changed that hasn't
  reached Healdan doesn't come with it, and the release is recorded in the document's history.
- **The matters they own:** how many live matters they own, not deleted or in the Trash. Selecting it opens the
  [Matters list](../clients-and-matters/#the-matters-list) with them as its **Owner** filter.

<!-- P4 docs pass 2 -->

## New User

**New User**, beside the Users list's title, makes a password account: an ordinary one, never read-only. It asks for:

- **Username:** what they'll sign in with, at most 255 characters, with no `>` or control characters. It never changes
  afterwards. One that another account has, in any case, is taken, a deleted account's or a system account's
  included.
- **First Name** and **Last Name:** both needed, each at most 255 characters.
- **Email:** their email address, if they have one. Their temporary password can be emailed only to it.
- **Language:** filled in with the server's default.
- **Time Zone:** none unless you choose one, such as `Europe/London`.
- **Groups:** the groups they're in besides Everyone, which every account but a read-only one is in. Adding `admin`
  asks first.
- **Email the Password:** whether Healdan emails them their temporary password, offered once
  [outgoing email](../system/outgoing-email/#when-email-counts-as-set-up) counts as set up.

<!-- P4 docs pass 2 -->

New User says what Healdan would refuse before you press **Create User**, such as a username that's taken, and
anything Healdan still refuses when you press it shows in the dialog, in Healdan's own words. While the account is
being made, **Cancel** is greyed out and Esc does nothing.

<!-- P4 docs pass 2 -->

### The Temporary Password

Each new account gets a temporary password, which Healdan makes to meet the server's password rules. The person has
to change it at their first sign-in: the sign-in page asks them for a new one.

- **Emailed:** with **Email the Password** ticked, Healdan emails it to them once the account is made, and doesn't
  show it to you.
- **Shown once:** otherwise, New User shows it once the account is made, for you to give them. Nothing shows it again,
  and Healdan keeps no copy anyone can read: if it's lost, use [Reset Password](#reset-password).
- **Shown when it can't be emailed:** when outgoing email isn't set up, they have no email address, or the mail
  server didn't take the email within 15 seconds, the account is still made, and New User shows the password instead,
  saying why it wasn't emailed.

<!-- P4 docs pass 2 -->

The password is never in the record of the change, the server's log or any other page.

### People Who Sign In Through a Provider

While a [sign-in provider](../healdan-settings/#sign-in-providers) is on, New User says that people who sign in through
it don't need adding: their account appears at their first sign-in there, with the groups the provider's group map
gives them, and you adjust it on their page from then on.

### Adding Someone Before Their First Provider Sign-In

To have someone's account ready before their first sign-in through a provider, with its groups, grants or matters set
up, make it with New User, with the email the provider has for them. It's a password account until then. With the
provider's **Link by Email** on, as in the Entra ID preset, their first sign-in through it links the account and
removes its password: see [Which Account a Sign-In Is](../healdan-settings/#which-account-a-sign-in-is). Their
provider groups must still map to a Healdan group, or that sign-in is refused. Groups the map doesn't name stay as you
set them.

With **Link by Email** off, as in the Authentik preset, don't add them ahead: their first sign-in through the provider
doesn't link the account you made. It makes them a second account, or is refused when yours has the username the
provider gives them.

## Edit

**Edit**, on a person's page, changes their first and last names, both needed, their email, their language and their
time zone, with the same limits as New User. A username never changes. A language or time zone set elsewhere that
Edit's menus don't offer stays as it is unless you change it.

For an account that signs in through a provider, the names and the email follow the provider: its next sign-in there
sets them again from what the provider has.

## Reset Password

**Reset Password**, for a password account, gives the person a new temporary password, which they have to change at
their next sign-in. It asks how they get it, as New User does: emailed, or shown once for you to give them, and shown
anyway when it can't be emailed (see [The Temporary Password](#the-temporary-password)).

A reset signs them out everywhere, as the dialog says: every device signed in as them is signed out, and every session
they have open is closed, as a reset is often because the account may be in someone else's hands. It also lifts a
[sign-in block](#clear-sign-in-block) on their username, as any new password does.

- **An account that signs in through a provider** has no password in Healdan, so Healdan refuses a reset for it. Use
  [Sign Out Everywhere](#sign-out-everywhere) to sign it out.
- **Your own account** isn't reset here: Healdan refuses it, and you change your own password as anyone does, in
  [Your Profile and Password](../../user/getting-started/your-profile-and-password/).
- **Another administrator** can reset the `admin` account's password.

## Sign Out Everywhere

**Sign Out Everywhere** signs the person out of everything at once, after asking: every device signed in as them is
signed out, and every session they have open, in the web page and anywhere else, is closed, so each app and device is
signed out at its next request. Healdan then says how many sessions it closed and devices it signed out.

<!-- P4 docs pass 2 -->

It changes nothing else: they can sign in again straight away, with the same password or through their provider. To
keep them out, [disable](#disable-and-enable) them, or [reset their password](#reset-password) if someone else may
know it.

Signing yourself out this way is allowed, and signs you out of the Admin app too.

## Clear Sign-In Block

While a person is **Blocked**, after too many wrong passwords under their username, **Clear Sign-In Block** lets them
sign in again now, rather than when the block ends of itself. It clears only the wrong passwords counted against their
username: a network address blocked for too many failed sign-ins stays blocked, as others may share it. A new password,
by [Reset Password](#reset-password) or any other way, lifts the block too. See
[The Sign-In Throttle](../healdan-settings/#the-sign-in-throttle).

## Disable and Enable

**Disable** asks first, then disables the account: the person can't sign in until it's enabled, and they're signed out
everywhere, as [Sign Out Everywhere](#sign-out-everywhere) signs them out. Nothing else changes: their documents,
matters, history and groups stay theirs, and so do their check-outs, which you can release on their page. A disabled
owner is still offered in the Matters list's **Owner** filter, marked **Disabled**.

**Enable** lets them sign in again, with the password they had or through their provider. Their devices stay signed
out, and each signs in again.

The Admin app never deletes an account. Disable someone who's left, so that everything they did keeps their name.

## What Healdan Refuses

So that someone can always administer Healdan, Healdan refuses, each with a sentence that says why:

- **disabling the `admin` account,** or taking it out of the `admin` group;
- **disabling yourself, or taking yourself out of `admin`:** another administrator does it, as you'd lose the Admin
  app mid-change;
- **any change that would leave no enabled administrator,** an enabled account in `admin`. Two such changes at the
  same moment are made one after the other, the second counting the administrators the first left, so two
  administrators can't take each other out.

It also refuses:

- **changing a system account,** such as `_system`;
- **a password for an account that signs in through a provider;**
- **taking anyone out of Everyone,** which every account but a read-only one is always in;
- **taking a read-only account out of `guest`, or adding it to any other group;**
- **renaming or deleting a fixed group,** and **deleting a group a provider's group map names.** See
  [Groups](#groups).

A person's page says beforehand which of its actions would be refused, and why. A change that would change nothing,
such as disabling someone who's disabled already, isn't refused: it leaves things as they are.

## Groups

**Groups**, at `/admin/groups`, lists every group by name, but the [personal groups](#personal-groups): each with its
description, how many people are in it, disabled and read-only accounts included, and the sign-in providers whose
group map names it. Typing in the filter keeps the groups whose name holds what you type, in any case. **New Group**,
beside the title, makes one. Select a group to open its page.

### A Group's Page

A group's page, at `/admin/groups/` and its number, has its name, with **Groups**, in the crumb above it, going back to
the list. Under the name are its description, how many people are in it, how many folders give it a permission of
their own, Stores and workspace templates' folders among them, and the providers whose group map names it, with a note
that a member who signs in through one is put in it or taken out at each sign-in there. Beside the name are **Edit**,
for its name and description, and **Delete**.

<!-- P4 docs pass 2 -->

Below are its members, by name and then username, each with their status, a read-only account marked as one.
Typing in the filter keeps the members whose username, email or name holds what you type. Adding a person finds them
as you type, and the Remove button (an ×) beside a member takes them out, each at once. Adding someone to `admin` asks
first.

<!-- P4 docs pass 2 -->

- **Everyone's members** can't be taken out: every account but a read-only one is always in it.
- **A read-only account** can't be added to any group, as it's always in `guest` and no other, nor taken out of
  `guest`.
- **Taking someone out of `admin`** may be refused: see [What Healdan Refuses](#what-healdan-refuses).
- **In a group a provider's group map names,** a change for someone who signs in through that provider lasts until
  their next sign-in there.

### New Group

**New Group** asks for:

- **Name:** at most 200 characters, not starting `_user_`, as personal groups' names do. One that another group has,
  in any case, is taken.
- **Description:** at most 255 characters, if you want one.

The new group has no members, and gives nobody anything until a folder's or a Store's access names it: see
[Permissions](../permissions/) and [Who Can See a Store](../stores/#who-can-see-a-store). Once **Create Group** has
made it, its page opens.

<!-- P4 docs pass 2 -->

### Renaming a Group

**Edit**, on a group's page, changes its name or its description. A renamed group keeps its members, and every folder,
Store and provider's group map that names it, under its new name. The [fixed groups](#the-fixed-groups) keep their
names, but their descriptions can change.

### Deleting a Group

**Delete**, on a group's page, asks first, then takes every member out of the group and deletes it. The folders and
Stores whose access names it then give it to no one. The page's count of folders says beforehand how many give it a
permission. Healdan refuses to delete:

- **a fixed group:** `admin`, `guest`, `publisher` and Everyone are never deleted;
- **a group a sign-in provider's group map names:** take it out of the map first, in the provider's window on
  [Healdan Settings' Sign-In tab](../healdan-settings/#the-group-map-and-its-gate);
- **a group whose name is too long to keep once it's deleted,** a name of more than 200 characters given elsewhere:
  shorten it first, with **Edit**.

### The Fixed Groups

`admin`, `guest`, `publisher` and Everyone are never renamed or deleted, though their descriptions can change:

- **`admin`:** administrators, who skip every permission check and use the Admin app (see
  [Day-to-Day Accounts Shouldn't Be Admins](#day-to-day-accounts-shouldnt-be-admins));
- **`guest`:** read-only accounts' group (see [Read-Only Accounts and `guest`](#read-only-accounts-and-guest));
- **`publisher`:** one of LogicalDOC's own groups;
- **Everyone:** every account but read-only ones (see [The Everyone Group](#the-everyone-group)).

### The Everyone Group

LogicalDOC has no group of every user, so Healdan adds one, **Everyone**, on its first start.

- **Every user is in it,** except read-only users, and can't be taken out. Healdan puts a user back if a screen or
  the API drops them. A new account, from New User or a provider's first sign-in, is in it from the start.
- **It can't be renamed or deleted.** Its description can change.
- **Use it in templates** for access that's open to everyone at your firm, such as reading a matter's Documents
  folder.

### Read-Only Accounts and `guest`

A read-only account can read documents but never change one. It's always in its own group and `guest`, and in no
other: Healdan keeps it in `guest`, refuses to take it out, and refuses to add it to any other group, Everyone
included. The Admin app makes no read-only account, and doesn't change an account's kind: the Classic Interface makes
them. In a matter or a template, `guest` and read-only people can't be given more than Read Only: see
[Permissions](../permissions/#the-rules-it-keeps).

### Personal Groups

Each user also has a personal group that holds only them. It's how a permission names one person: a matter's owner,
for example, gets every permission on the workspace through theirs. Personal groups aren't listed under **Groups**, and
nothing in the Admin app changes them. A Store's **Who Can See It** takes groups only: to let one person see a Store,
add a group they're in.

### A Group a Sign-In Provider Maps

A group a [provider's group map](../healdan-settings/#the-group-map-and-its-gate) names is the provider's to decide for
the people who sign in through it. At each of their sign-ins there, they're put in it when one of their provider groups
maps to it, and taken out when none does. So a change you make to such a person's membership of it lasts only until
their next sign-in. The group's page names the providers whose map names it, and it can't be deleted while one does.
Other people's membership of it, and the groups the map doesn't name, stay as you set them.

## What Healdan Records

Healdan records each change made here, with who made it, when and from where:

- **a person** added, with every detail set and their groups; edited, with each detail before and after; disabled;
  enabled; given a temporary password, with whether it was emailed, but never the password; signed out everywhere,
  with how many sessions and devices; their sign-in block cleared; their groups changed, before and after; and one of
  their devices revoked, by its name;
- **a group** made, with its name and description; renamed or given a new description, before and after; deleted; and
  given a member or losing one.

A change that changes nothing, such as saving someone's groups as they were, isn't recorded.

## In the Classic Interface

The Classic Interface's **Administration → Access Control** still has:

- **Security:** the password rules, the session and cookie options, **Alert login from new device**, and the **Menus**
  tab, which gives each group its menus in the Classic Interface (see
  [Giving a Settings Screen to Others](../system/#giving-a-settings-screen-to-others)).
- **Read-only accounts:** they're made in its **Users**. The Admin app makes ordinary accounts only.
- **Deleting an account:** its **Users** deletes one, which also signs out its devices at once. The Admin app never
  deletes an account: disable it instead, which keeps it out and keeps everything it did with its name.
- **A password of your choosing:** its user editor sets one. The Admin app gives only temporary ones.

**Unlinking an account from its sign-in provider** is in the provider's window, on Healdan Settings' **Sign-In** tab:
see [Linked Accounts](../healdan-settings/#linked-accounts). Everyone signed in to the web page is listed on the
**Sessions** tab of [System Information](../system/system-information/).
