---
title: Search Folders
weight: 3
---

A **search folder** is a folder in a matter whose list of documents comes from a rule, such as "documents anyone
changed in the last 30 days". It can't hold documents or folders of its own. A template might include two, for
example:

- **Recent Work:** documents anyone in the matter changed in the last 30 days.
- **My Recent Work:** documents you changed in the last 30 days. Each person sees their own list.

## What a Search Folder Shows

- **Only its own matter.** A search folder looks at its matter's workspace and everything in it.
- **Changes in a window,** such as the last 30 days. A change is filing a document, a new version or snapshot, a
  property edit, a rename, or a move into the matter. Checking a document out or locking it doesn't count.
- **Newest change first,** worked out each time you open the folder.
- **Only what you can read.**

## Creating One

Right-click a folder in the matter and choose **New search folder…**. You need the Add permission on that folder, and
only admins can create one directly inside a matter's workspace, as with any folder there. The rule has these parts:

| Field | Choices |
|---|---|
| Changed within | the last *N* days |
| Only my changes | ticked: only the changes of the person viewing; unticked: anyone's |
| Show | Documents and emails, Documents only, or Emails only |
| Class | optional: only documents of this Class |
| Subclass | optional: only documents of this Subclass. It needs a Class |

To change a rule, right-click the search folder and choose **Edit search folder…**, which needs the Write permission.
Renaming it needs Rename.
