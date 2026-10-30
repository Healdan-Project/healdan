---
title: Interface Settings
weight: 2
---

How the web page looks and behaves for everyone is set under **Administration → Settings → GUI Settings**. Each
tab has its own **Save**. Others see a change after they next sign in.

## The GUI Settings Tab

The tab holds about sixty settings. These are the ones a firm is most likely to change:

| Setting | What it changes |
|---|---|
| Welcome message | a message everyone sees when they sign in. Empty to begin with |
| Upload max. size | the largest file anyone can upload, in MB. 100 to begin with. It applies to the Healdan Helper's saves too |
| Disallowed extensions | the kinds of file nobody can upload, such as `exe`. It applies to the Healdan Helper's saves too |
| Max. preview file size | the largest file that gets a preview, in MB. 0 means no limit. It applies to [Office previews](../../office-previews/) too |
| Preview timeout | how long a preview may take to open, in seconds |
| On double click | whether double-clicking a document downloads it or opens its preview |
| Document Tab | which of a document's tabs opens first: **properties** or **preview** |
| Note max size | the longest note anyone can add to a document, in characters |
| Session timeout | how many minutes of doing nothing sign someone out of the web page. 30 to begin with |
| Lock on editing | locks a document while someone changes its properties, so two people can't change them at once |
| Ask comment on save | asks for a comment each time someone saves a document's properties. Off to begin with |
| Save Login | shows **Remember me** on the sign-in page, which keeps a person's username and password in a cookie in that browser. Turn it off unless each computer is used by one person only |
| Show lost password link | shows **Lost your password?** on the sign-in page. It needs [outgoing email](../outgoing-email/) |
| Show language in login | lets people choose the web page's language on the sign-in page |

## The Grids Tab

**Columns in the Documents screen** and **Columns in the Search screen** are the columns everyone sees until they
choose their own. **Page size** and **Search hits** set how many documents a list shows at once.

Healdan's first start adds **Doc #**, **Author** and **Class** to the Documents screen, and **Doc #** and the
client's name (`clientname`) to the Search screen.

{{< callout type="warning" >}}
Keep each property someone might want as a column in one of the two lists. Saving the tab also sets which
properties the lists of documents carry: only those in either list. A property in neither shows empty in the
Documents and Search screens, even for someone who adds its column themselves.
{{< /callout >}}

## The Dashlets Tab

Dashlets are the small windows people can put on their **Dashboard**, such as **Documents checked out**. Each one
is a query. Change them with care: a dashlet's query isn't checked against permissions, so one that lists more than
a person's own documents shows them documents they can't open.

## The Languages Tab

The languages the web page can be shown in. Right-click a language and choose **Disable** to stop people choosing
it.
