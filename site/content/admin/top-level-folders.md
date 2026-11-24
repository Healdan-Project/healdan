---
title: Top-Level Folders
weight: 4
---

The folders directly inside a matter's workspace are its skeleton, copied from its template. So only admins add,
rename, move or delete them, search folders included. Below the top level, everyone works under the usual
permissions. Administrators change a matter's folders on the **Workspace** tab of
[its page](../clients-and-matters/#a-matters-page) in the Admin app, and a template's on
[the template's page](../workspace-templates/#a-templates-page), both in [the Workspace Editor](#the-workspace-editor).

In the User app, **Rename**, **Move To…** and **Delete** are greyed out on them for anyone who isn't an administrator,
and the Delete and Move To… keys and drags refuse them with the reason. On the workspace folder itself **Rename** is
greyed out for everyone and **Move To…** and **Delete** aren't offered: an administrator moves and deletes it on the
matter's page in the Admin app. Nobody who isn't an administrator can move a folder to the top level of a workspace
either: the picker's choice says "Administrators only".

## The Workspace Folder Itself

- **It can't be renamed directly.** Its name follows the matter's: change the name with **Edit** on the matter's page.
  See [A Matter's Page](../clients-and-matters/#a-matters-page).
- **Only admins delete it,** or move it to another Store, with **Delete** and **Move to Another Store** on the same
  page.

## The Workspace Editor

The Workspace Editor is the same on a matter's **Workspace** tab and on a workspace template's page. Its folder tree is
on the left, and the folder you choose in it on the right.

### The Folder Tree

- **Every folder and search folder** in the workspace, at every level. The tree starts open wherever a folder holds
  something.
- **A lock** beside a folder means it has permissions of its own, rather than following another folder's. Pointing at
  the lock says "Own Permissions", and a screen reader reads it after the folder's name.
- **Choose a folder** with a click, or with the arrow keys and Enter, to show it on the right.
- **A folder's menu,** on a right-click, Shift+F10 or the menu key, has icons beside **New Folder**, **New Search Folder**,
  **Rename**, **Move To…** and **Delete**, as far as the folder takes them: a search folder holds no folders, and the
  workspace's own folder, at the top, has only the first two. In a matter's workspace, a folder below the top also has
  **Reindex**, last. On a folder in the tree, **F2** renames it and the Delete key (on a Mac, delete) deletes it.
- **New Folder** and **New Search Folder,** above the tree, make one in the folder chosen, or beside the search folder
  chosen.

The top folder is changed only from its own page. On a matter's, F2 and the Delete key say "This is the matter's own
folder: Edit renames the matter, and Delete moves it to the Trash."; a template's is renamed and deleted with the
template page's **Rename** and **Delete**.

### The Folder Chosen

On the right are the folder's name and, for any folder but the top, **Rename**, **Move To…** and **Delete**. Below
them are [Who Can Use This Folder](../permissions/#who-can-use-this-folder), and then the values the folder sets, or a
search folder's rule.

On a matter's **Workspace** tab, a folder below the top also has **Reindex**, after **Delete**, which opens the
[Search Index](../system/search-and-indexing/#reindex) with its Reindex dialog open and the folder chosen: the
documents in the folder and in every folder inside it, at every level, are indexed again, straight through, and search
keeps finding them meanwhile. The matter's own folder is reindexed from the matter's page, and a search folder, which
holds no documents, and a template's folders have no **Reindex**. Back from the dialog comes back here.

### Making, Renaming, Moving and Deleting Folders

- **New Folder** asks for a name, and **Create** makes the folder in the folder chosen. **New Search Folder** asks for
  its [rule](#a-search-folders-rule) too. Once it's made, Healdan says "Created" with its name, and the new folder is
  chosen, unless the folder chosen has changes that aren't saved.
- **Rename** changes a folder's or a search folder's name. A search folder's name follows a folder's rules, below.
- **Before the button,** each of them says when the name has a `/` or `\` in it ("A name can't have / or \ in it."), is
  over 255 characters ("That is too long."), or is the name of another folder or search folder beside it, in any case
  ("There's already a folder called Drafts here."). Changing only the case of a folder's own name works. Healdan also
  refuses a name with an HTML tag, a control character or a character that turns the text round, rather than change
  it.
- **Move To…** moves a folder within the workspace. **Into** lists the workspace's folders by their path, leaving out
  the folder itself, the folders inside it and the search folders, and says "It's in that folder already." for the
  one it's in, and when the folder you chose has one with its name. **Move** moves it. A matter's folder moves into
  another matter from the User app, and a template's stays in its template.
- **Delete** asks first, as in "Move Drafts to the Trash?", and says what the folder holds, counted afresh, such as
  "It holds 2 folders and 14 documents, which go with it." In a matter it says that an administrator can restore the
  folder from the Trash in Healdan. In a template it says that matters already made from the template keep their own
  copy of it, and "Healdan's Trash will list it but can't put it back in the template; the Classic Interface's Trash can." **Move to Trash** deletes it, and
  if it was the folder chosen, or held it, the folder it was in is chosen instead. In a matter, "Moved Drafts to the
  Trash" comes with **Undo**, which puts the folder back where it was, with what the delete took, and says why when it
  can't, such as when it has been restored already. A template's folder has no Undo, as the User app's Trash can't
  put it back: an administrator restores it in the Classic Interface (see
  [Workspace Templates](../workspace-templates/#renaming-and-deleting-a-template)).

Anything Healdan refuses shows in the dialog, in Healdan's own words, and while a change is on its way the dialog's
**Cancel** is greyed out and Esc does nothing. Healdan refuses, for administrators too:

- **the workspace's own folder,** which is renamed, moved and deleted with its matter or its template;
- **a name another folder or search folder in the same folder has,** in any case, hidden ones included, such as one
  another administrator gave a folder there a moment before;
- **a move into another workspace,** into a search folder, or into the folder itself or a folder inside it;
- **a move or a delete while a document in the folder is checked out,** by anyone, you included, and a delete while it
  holds an immutable document.

Inside a matter, a folder moved into a folder more restricted than itself is narrowed to fit, as on every route (see
[Folders Narrow, Never Widen](../permissions/#folders-narrow-never-widen)). Healdan records each folder made, renamed,
moved and deleted, and each search folder made, with who did it, naming the folder by its path in the workspace, as
`1001.1 – Charter Dispute/Documents`.

### The Values a Folder Sets

**Values It Sets** lists each property that passes down from folders to what's filed below them, such as Class and
Subclass (see [Properties](../../user/working-with-matters/properties/)):

- **Each has a field of its kind:** a menu for a property with a list of options, and for yes or no, each with **Not
  Set Here** to set nothing; a date picker for a date; a search for a person; and a text field for text, numbers,
  folders and documents, a folder or a document by its id, a whole number. A number that isn't one of its kind says
  "That isn't the right kind of value.", and **Save Values** waits until it's put right.
- **Under each field,** where its value comes from: "Set on this folder, and passed down.", or, when the folder sets
  nothing, the folder above that sets it, with its value, as in "From Privileged: Evidence", or the install's default
  Class, as in "The install's default: Correspondence".
- **A Subclass** is offered only within the Class the folder sets, or the one it takes from above.
- **Save Values** saves them all, and a property set to **Not Set Here** goes back to taking its value from above. A
  field you left alone is saved exactly as it was. While the save is on its way, the fields are greyed out.

Client and Matter aren't among them: they come from the matter. A value set to what the folder would take from above
anyway sets nothing of its own. In a matter, the folders and documents below that don't set their own take a change at
once. With no such property, it says "No property passes down from folders." In a template, it says that new matters
made from the template take them. Healdan records each value a save changes, with what it was before.

### A Search Folder's Rule

A search folder shows its **Rule** in place of values, as New Search Folder asks for it:

| Field | What it does |
|---|---|
| Changed Within (Days) | the window, a whole number of days from 1 |
| Only Changes by Whoever Opens It | ticked, it counts only the changes of whoever opens the folder; unticked, anyone's |
| Kind | **Everything**, **Documents, Not Emails** or **Emails Only** |
| Class | **Any**, or only documents of one Class |
| Subclass | once a Class is chosen: **Any**, or one of that Class's Subclasses |

**Save Rule** saves it, with the fields greyed out while it's on its way. A new search folder starts at 30 days,
anyone's changes, everything and any Class. In a matter, the search folder lists its matter's documents changed within
the window that whoever opens it can read, newest first. A template's is copied, with its rule, into each new matter
made from the template, and a change to its rule reaches only matters made afterwards. Healdan records each new search
folder with its rule, and each change to a rule with what it was before. See
[Search Folders](../../user/finding-documents/search-folders/) in the User Guide.

### Unsaved Changes

Choosing another folder while the folder chosen has changes that aren't saved asks first, as in "Leave the Changes to
Evidence?": **Discard Changes** opens the other folder, and **Cancel** keeps you where you were, with your changes.
While a save is on its way, the other folder opens once it's saved, and the panel says so; if Healdan refuses the
save, you stay, with the reason and your changes. On a matter's page, what you were changing on the **Workspace** tab
is kept while you look at the **Overview**.

Leaving the page while a change isn't saved, or while a save is on its way, asks the same question, whether by a link in
the left pane, the page's crumb, the find, another link or the browser's Back:

- **With a change not saved,** it says "The changes to Evidence aren't saved. Leaving this page discards them.", with
  **Discard Changes**, which leaves, and **Cancel**, which keeps you on the page with your changes.
- **With a save on its way,** it says "The changes to Evidence are still being saved. If you leave this page now, you
  won't hear whether they were.", with **Leave Page** and **Cancel**.

A reload, or closing the tab or the window, asks in the browser's own words. Changing a matter's tab doesn't ask, and
nor does deleting the matter or the template, whose changes go with it.

## Where Documents and Folders Can Go

Healdan enforces these for everyone, admins included, however the change arrives: the browser, WebDAV, CMIS or the
API.

- **Documents go only inside a matter's folders.** Not at a Store's top level, not at a workspace's top level, not
  in the Default workspace or the Templates Store, and not in a search folder.
- **Only New Matter creates folders directly in a Store.** Admins can create folders anywhere in the Templates Store.
- **Search folders hold no folders.**
- **Shortcuts aren't allowed inside Stores.**
