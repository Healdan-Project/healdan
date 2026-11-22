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
group, which also lets them use the Admin app. Adding someone to `admin` asks first, as the change is sent, before
anything is saved: at **Create User** in New User, at **Save** of their groups on their page, and at **Add** in a
group's **Add Member**. The question, "Add Ann Lee to admin?", says that members of `admin` can use the Admin app and
read every document, in every matter and Store, and that if they also work on the firm's documents, they need a
separate everyday account for that. **Add to admin** goes ahead, and **Cancel** sends nothing and keeps your change.

## The Users List

**Users**, at `/admin/users`, lists every person, by name, disabled and read-only accounts included, with how many
there are beside the title, such as "12 People". **New User**, beside the title, adds one: see [New User](#new-user).

| Column | What it shows |
|---|---|
| Name | their first and last name, or their username when both are blank, with **Read-Only** after a read-only account's |
| Username | what they sign in with, which never changes |
| Groups | the groups they're in, but Everyone, which every account but a read-only one is in, and their [personal group](#personal-groups) |
| Signs In With | **Password**, or the name of the [sign-in provider](../healdan-settings/#sign-in-providers) their account is linked to |
| Status | **Active**; **Disabled**, an account that can't sign in until it's enabled; or **Blocked**, an account whose username [the sign-in throttle](../healdan-settings/#the-sign-in-throttle) has blocked after too many wrong passwords. A disabled account is **Disabled**, blocked or not. Their page says when a block ends |
| Last Sign-In | when Healdan last opened a session for them, or **Never** |
| Devices | how many [devices](#devices) are signed in as them |

**Last Sign-In counts a device's sign-ins too.** It's when a session was last opened for the person, however they
signed in: the sign-in page, a sign-in provider, WebDAV or an API key, or a device such as Healdan Helper, which opens
a new session whenever its last one has timed out. So someone whose helper is in steady use has a recent Last Sign-In
even if they haven't typed their password for weeks. **Never** is an account nobody has signed in to yet, such as one
New User has just made.

The filters above the list all apply together:

- **Name, Username or Email** keeps the people whose username, email, first name, last name, or first and last name
  together, holds what you type, in any case. The list follows once you pause typing.
- **Group:** type part of a group's name, and choose the group, to keep its members.
- **Status:** a menu of **Active**, **Disabled** and **Blocked**, **All** to begin with.
- **Clear Filters,** which shows while a filter is set, takes them all off and keeps the order.

Click **Name**, **Username** or **Last Sign-In**, the columns that sort, to sort by it, and click it again to reverse
the order. The list starts by name, and Last Sign-In starts with the latest. People who tie go by username, and by Last
Sign-In, someone who has never signed in comes last either way. The list shows 100 people, and the next 100 as you
scroll down to them. The page's address keeps the filters and the order, so a reload or a copied link shows the same
list, and Back from a person's page comes back to it. With nobody yet it says "Nobody has an account yet.", and with
nobody who matches, "Nobody matches these filters."

Select a person, with a click or Enter, to open [their page](#a-persons-page).

The Users list, a person's page, the Groups list and a group's page ask Healdan again each time you come back to their
window, so a change another administrator made shows without a reload. If Healdan can't be read then, each keeps what
it showed, with "Couldn't refresh this, so it shows what was read last." and **Try Again**, and a dialog open on it
stays open, with what you typed in it.

## A Person's Page

A person's page, at `/admin/users/` and their account's number, has their name, with **Users**, in the crumb above it,
going back to the list, and their status beside the name: **Active**, **Disabled**, or **Blocked until** the time the
block ends. The Admin app's find opens it too. Beside the name are its actions:

- **Edit:** their names, email, language and time zone. See [Edit](#edit).
- **Reset Password**, for a password account only: an account that signs in through a provider isn't offered it. See
  [Reset Password](#reset-password).
- **Sign Out Everywhere:** see [Sign Out Everywhere](#sign-out-everywhere).
- **Clear Sign-In Block**, while they're **Blocked**: see [Clear Sign-In Block](#clear-sign-in-block).
- **Disable**, or **Enable** for a disabled account: see [Disable and Enable](#disable-and-enable).

**Disable**, **Sign Out Everywhere** and **Reset Password** ask first, and **Enable** and **Clear Sign-In Block**,
which take nothing away, don't. Where Healdan would refuse one of them, it's greyed out, and the page says why under
the actions, in Healdan's own words, such as "You can't disable your own account." on yours: see
[What Healdan Refuses](#what-healdan-refuses). Should something change meanwhile, Healdan's refusal shows when you try.

Below them are **Details**: their **Username**, **Email**, **Signs In With** (their sign-in provider, or their
password, with when it was last set, or that they must change it at their next sign-in), **Language**, **Time Zone**,
**Account Made** and **Last Sign-In**. A read-only account's details say it's one, and an administrator's that they
can use the Admin app and read every document. Then come **Groups**, **Signed-In Devices** and **What They Hold**,
which the sections below describe.

An address that names no person says "Person Not Found", with **Go to Users**, as one naming a system account or a
deleted account does.

### Their Groups

**Groups** lists their groups by name, each opening the group's page, `admin` marked **Reads Every Document**.
**Add a Group** adds them to one, finding it as you type, and the Remove button (an ×) beside a group takes them out
of it. **Save** saves their groups, and stays greyed out until something has changed; **Discard Changes** puts back
what's saved. Leaving the page with a change not saved, or while it's being saved, asks first, as the Workspace Editor
does: "Leave the Changes to Ann Lee?", with **Discard Changes**, or, while it's saving, **Leave Page**.

- **Everyone stays.** Every account but a read-only one is always in [Everyone](#the-everyone-group), so it has no
  Remove, and says why in its place.
- **A read-only account** is always in `guest`, and in no other group, so it has no **Add a Group**: see
  [Read-Only Accounts and `guest`](#read-only-accounts-and-guest).
- **Adding them to `admin`** asks when you press **Save**: see
  [Day-to-Day Accounts Shouldn't Be Admins](#day-to-day-accounts-shouldnt-be-admins). Where taking them out of it would
  be refused, such as for the `admin` account or yourself, `admin` has no Remove, and says why in its place: see
  [What Healdan Refuses](#what-healdan-refuses).
- **For an account that signs in through a provider,** a group the provider's group map names is the provider's to
  decide, and says so: "Set by Authentik's group map at each sign-in through it." Their next sign-in there puts it back
  as the map says, whatever you saved. Groups the map doesn't name stay as you set them. See
  [A Group a Sign-In Provider Maps](#a-group-a-sign-in-provider-maps).

### Devices

**Signed-In Devices** lists each device signed in as them, newest first: its **Device** name, such as "Healdan Helper
on MacBook", with its computer's id under it, the same at each of its sign-ins, so two devices of one name can be told
apart; what it **Signed In With**, **Password** or the sign-in provider; when it **Signed In**; when it was **Last
Used**, to within a few minutes; and when it **Expires**. It's the list the person sees in the User app's
[Signed-in Devices](../../user/getting-started/signed-in-devices/). The web page's own sign-in isn't a device, and
isn't listed: [Sign Out Everywhere](#sign-out-everywhere) ends it.

**Revoke**, beside a device, asks first, then signs that one device out: it's signed out at its next request, and the
person signs it in again whenever they're ready. Their other devices stay signed in.

### What They Hold

- **Their check-outs:** each document they have checked out, the latest first, with its **Doc #**, its title, its
  **Version** and its matter, which opens the matter's page, **Since** when, and, when one of their computers holds it,
  that **Computer** and its **App**, so you can tell them where to check it in. Documents they've only locked aren't
  listed: the Classic Interface's **Locked documents** report has those (see [Reports](../system/reports/)).
  **Release**, beside one, asks first, saying that what they haven't saved to Healdan is lost, and that the computer
  holding it can't save to it again, then ends the check-out, as the person's own **Release** in the User app does.
  The release is recorded in the document's history.
- **The matters they own:** how many live matters they own, not deleted or in the Trash, as "Owner of 3 matters",
  which opens the [Matters list](../clients-and-matters/#the-matters-list) with them as its **Owner** filter, or
  "They own no matters."

## New User

**New User**, beside the Users list's title, makes a password account: an ordinary one, never read-only. It asks for:

- **Username:** what they'll sign in with, at most 255 characters, with no `>` or control characters. It never changes
  afterwards. One that another account has, in any case, is taken, a deleted account's or a system account's
  included: New User names the person who has it before you press **Create User**, and Healdan refuses it whoever has
  it.
- **First Name** and **Last Name:** both needed, each at most 255 characters.
- **Email:** their email address, if they have one. Their temporary password can be emailed only to it.
- **Language:** a menu of the languages your install offers, filled in with the server's default.
- **Time Zone:** none unless you choose one: type part of its name, such as `london` or `new york`, and choose it,
  such as `Europe/London`.
- **Groups:** the groups they're in besides Everyone, which every account but a read-only one is in, so it isn't
  offered. **Add a Group** finds one as you type. Adding `admin` asks at **Create User**.
- **Email It to Them:** whether Healdan emails them their temporary password, offered once
  [outgoing email](../system/outgoing-email/#when-email-counts-as-set-up) counts as set up and an email is typed.
  Otherwise the form says the password will be shown, once, for you to give them.

**Create User** stays greyed out until the username and both names are there, and while New User can see something
Healdan would refuse, said beside its field, such as a username that's taken. Anything Healdan still refuses when you
press it shows in the dialog, in Healdan's own words, beside its field or above the buttons. While the account is being
made, **Cancel** is greyed out, and Esc and a click outside do nothing, as the account would be made anyway.

Once it's made, New User shows the temporary password, or that it was emailed, under "Added Ann Lee". **Done** closes
it and opens their page.

### The Temporary Password

Each new account gets a temporary password, which Healdan makes to meet the server's password rules. The person has
to change it at their first sign-in: the sign-in page asks them for a new one.

- **Emailed:** with **Email It to Them** ticked, Healdan emails it to them once the account is made, and says so,
  with the address, without showing it to you.
- **Shown once:** otherwise, New User shows it once the account is made, for you to give them, with **Copy**. "It
  isn't shown again: once this closes, it's gone." Nothing shows it again, and Healdan keeps no copy anyone can read:
  if it's lost, use [Reset Password](#reset-password).
- **Shown when it can't be emailed:** when outgoing email isn't set up, they have no email address, or the mail
  server didn't take the email within 15 seconds, the account is still made, and New User shows the password instead,
  saying why it wasn't emailed: "Couldn't email the temporary password: the mail server didn't take it in time. Give
  it to Ann Lee yourself."

The password is never in the record of the change, the server's log or any other page, and the Admin app keeps it only
while its window is open: never in the page's address or the browser's storage.

### People Who Sign In Through a Provider

While a [sign-in provider](../healdan-settings/#sign-in-providers) is on, New User says that people who sign in through
it needn't be added: their account is made at their first sign-in there, with the groups the provider's group map
gives them, and you adjust it on their page from then on. It also says that where a provider links accounts by email,
an account made here with the same email is linked to theirs at that sign-in.

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

**Edit**, on a person's page, changes their **First Name** and **Last Name**, both needed, their **Email**, their
**Language** and their **Time Zone**, with the same limits as New User. A username never changes. **Save** stays
greyed out until something has changed, and sends only what you changed, so a field you left alone keeps what it
holds, even if another administrator changed it while Edit was open. A language or time zone set elsewhere that Edit's
menus don't offer stays as it is unless you change it.

For an account that signs in through a provider, Edit says the provider may set the names and the email again at their
next sign-in through it, from what the provider has.

## Reset Password

**Reset Password**, for a password account, gives the person a new temporary password, which they have to change at
their next sign-in. It asks first, "Reset the Password of Ann Lee?", and says they're signed out everywhere. While
[outgoing email](../system/outgoing-email/#when-email-counts-as-set-up) is set up and they have an email address, it
offers **Email It to Them**; otherwise it says the password will be shown. Then it shows the password once, or says it
was emailed, as New User does (see [The Temporary Password](#the-temporary-password)).

A reset signs them out everywhere: every device signed in as them is signed out, and every session they have open is
closed, as a reset is often because the account may be in someone else's hands. It also lifts a
[sign-in block](#clear-sign-in-block) on their username, as any new password does.

- **An account that signs in through a provider** has no password in Healdan, so its page doesn't offer
  **Reset Password**, and Healdan refuses a reset for it. Use [Sign Out Everywhere](#sign-out-everywhere) to sign it
  out.
- **Your own account** isn't reset here: **Reset Password** is greyed out on your page, with "Change your own password
  from your person menu." You change it as anyone does, in
  [Your Profile and Password](../../user/getting-started/your-profile-and-password/).
- **Another administrator** can reset the `admin` account's password.

## Sign Out Everywhere

**Sign Out Everywhere** signs the person out of everything at once, after asking, "Sign Ann Lee Out Everywhere?":
every device signed in as them is signed out, and every session they have open is closed, however it was opened: the
web page's, the Classic Interface's, WebDAV's, an API key's or a device's. Each app and device is signed out at its
next request. Healdan then says what it did, as "Signed Ann Lee out: 2 sessions closed, 1 device signed out", or "Ann
Lee was signed in nowhere".

It changes nothing else: they can sign in again straight away, with the same password or through their provider. To
keep them out, [disable](#disable-and-enable) them, or [reset their password](#reset-password) if someone else may
know it.

Signing yourself out this way is allowed: the question says it signs you out here too, and the Admin app then shows
that you're signed out.

## Clear Sign-In Block

While a person is **Blocked**, after too many wrong passwords under their username, **Clear Sign-In Block** lets them
sign in again now, rather than when the block ends of itself, and asks nothing first. It clears only the wrong
passwords counted against their username: a network address blocked for too many failed sign-ins stays blocked, as
others may share it. A new password, by [Reset Password](#reset-password) or any other way, lifts the block too. See
[The Sign-In Throttle](../healdan-settings/#the-sign-in-throttle).

## Disable and Enable

**Disable** asks first, "Disable Ann Lee?", saying they can't sign in until their account is enabled again, that what
they've filed stays theirs, and that they're signed out everywhere, with how many devices. Then it disables the
account, and signs them out everywhere, as [Sign Out Everywhere](#sign-out-everywhere) does. Nothing else changes:
their documents, matters, history and groups stay theirs, and so do their check-outs, which you can release on their
page. A disabled owner is still offered in the Matters list's **Owner** filter, marked **Disabled**.

**Enable** lets them sign in again, with the password they had or through their provider, and asks nothing first.
Their devices stay signed out, and each signs in again.

The Admin app never deletes an account. Disable someone who's left, so that everything they did keeps their name.

## What Healdan Refuses

So that someone can always administer Healdan, Healdan refuses, each with a sentence that says why:

- **disabling the `admin` account,** or taking it out of the `admin` group;
- **disabling yourself, or taking yourself out of `admin`:** another administrator does it, as you'd lose the Admin
  app mid-change;
- **any change that would leave no enabled administrator,** an enabled account in `admin`: "That would leave no
  enabled administrator. Make someone else an administrator first." Two such changes at the same moment are made one
  after the other, the second counting the administrators the first left, so two administrators can't take each other
  out.

It also refuses:

- **changing a system account,** such as `_system`;
- **a password for an account that signs in through a provider,** and **resetting your own password** here;
- **taking anyone out of Everyone,** which every account but a read-only one is always in;
- **taking a read-only account out of `guest`, or adding it to any other group;**
- **renaming or deleting a fixed group,** **deleting a group a provider's group map names,** and **deleting a group
  whose name is too long to keep once it's deleted.** See [Groups](#groups).

A person's page and a group's say beforehand which of their actions would be refused, and why: an action is greyed
out, or a group's or a member's Remove is replaced by the reason. A change that would change nothing, such as
disabling someone who's disabled already, isn't refused: it leaves things as they are.

## Groups

**Groups**, at `/admin/groups`, lists every group by name, but the [personal groups](#personal-groups), with how many
there are beside the title, such as "7 Groups". Each has its **Description** and how many **Members** it has,
disabled and read-only accounts included. A fixed group is marked **Fixed**, and one a sign-in provider's group map
names **Mapped by** and the provider's name. **Name** keeps the groups whose name holds what you type, in any case,
once you pause typing, and the page's address keeps it. The list shows 100 groups, and the next 100 as you scroll down
to them. **New Group**, beside the title, makes one. Select a group, with a click or Enter, to open its page.

### A Group's Page

A group's page, at `/admin/groups/` and its number, has its name, with **Groups**, in the crumb above it, going back to
the list, and **Fixed** beside a fixed group's name. Beside the name are **Rename**, **Edit Description** and
**Delete**. Under them are:

- **Description**, or **None**;
- **Members:** how many people are in it;
- **Folders That Grant It:** how many folders give it a permission of their own, Stores and workspace templates'
  folders among them;
- **Sign-In Providers:** for a group a provider's group map names, those providers, with a note that someone who signs
  in through one is put in it, or taken out, at each sign-in there, so a change made here for them is set again at
  their next sign-in.

`admin`'s page says its members can use the Admin app and read every document, Everyone's that nobody is added to it
or taken out of it here, and `guest`'s that a read-only account is always in it. Where Healdan would refuse
**Rename** or **Delete**, it's greyed out, and the page says why under the actions: see
[Deleting a Group](#deleting-a-group). An address that names no group says "Group Not Found", with **Go to Groups**,
as one naming a personal group or a deleted one does.

**Members**, below, lists the people in the group by name and then username, each with their username and status, a
read-only account marked **Read-Only**, and each name opening their page. **Name, Username or Email** keeps the members
whose name, username or email holds what you type. It shows 100, and **Show More** the next 100.

- **Add Member** finds a person by name or username, among the accounts that can sign in, and **Add** adds them at
  once. Someone already in the group is said so before **Add**. Adding someone to `admin` asks first: see
  [Day-to-Day Accounts Shouldn't Be Admins](#day-to-day-accounts-shouldnt-be-admins).
- **Remove**, beside a member, asks first, "Take Ann Lee Out of Partners?", saying they lose what the group gives them
  unless another of their groups gives it too, then takes them out. Taking someone out of `admin` says they can no
  longer use the Admin app, nor read every document.
- **Where Healdan would refuse** taking someone out, the member's row says why in place of **Remove**: a read-only
  account in `guest`, the `admin` account in `admin`, and you in `admin`, which another administrator does. A change
  that would leave no enabled administrator is refused when you try it, in the question.
- **Everyone's members** can't be added or taken out, so its page has neither **Add Member** nor **Remove**: every
  account but a read-only one is always in it.
- **A read-only account** can't be added to any group but `guest`: Add Member says Healdan's refusal.
- **In a group a provider's group map names,** Add Member and Remove say that a change for someone who signs in
  through that provider lasts until their next sign-in there.

### New Group

**New Group** asks for:

- **Name:** at most 200 characters, not starting `_user_`, as personal groups' names do, and with no control
  character. One that another group has, in any case, is taken: New Group says so before its button.
- **Description:** at most 255 characters, if you want one.

It says the new group has no members, and gives nobody anything until a folder's or a Store's access names it: see
[Permissions](../permissions/) and [Who Can See a Store](../stores/#who-can-see-a-store). **Create Group** makes it,
and its page opens.

### Renaming a Group

**Rename**, on a group's page, changes its name, with New Group's limits; changing only the case of its own name
works. A renamed group keeps its members, and every folder, Store and provider's group map that names it, under its
new name. **Edit Description** changes its description, and an empty one leaves it with none. The
[fixed groups](#the-fixed-groups) keep their names, so their **Rename** is greyed out, but their descriptions can
change.

### Deleting a Group

**Delete**, on a group's page, asks first, "Delete Partners?", saying how many members are taken out of it and lose
what it gives them, how many folders give it a permission of their own, which nobody gets through it once it's
deleted, and that it can't be undone. **Delete Group** then takes every member out of the group, deletes it and goes
back to the Groups list. **Delete** is greyed out, with why, for:

- **a fixed group:** `admin`, `guest`, `publisher` and Everyone are never deleted;
- **a group a sign-in provider's group map names:** take it out of the map first, in the provider's window on
  [Healdan Settings' Sign-In tab](../healdan-settings/#the-group-map-and-its-gate) in the Classic Interface;
- **a group whose name is too long to keep once it's deleted,** a name of more than 200 characters given elsewhere:
  rename it to no more characters than the page says, then delete it.

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
