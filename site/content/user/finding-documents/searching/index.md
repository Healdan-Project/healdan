---
title: Searching
weight: 1
---

The search box is in the top bar, with the scope picker joined to its left. The picker says what to search:
**Documents** unless you change it. Press `/` anywhere on the page, except while you're typing in a field or a dialog
is open, and the cursor jumps to the box.

## Scopes

| Scope | Type | Finds |
|---|---|---|
| Documents | words | documents containing them, or whose Comments contain them all, without emails |
| Emails | words | emails containing them |
| Docs & Emails | words | documents and emails containing them |
| Folders | words | folders, inside matters, whose names contain them |
| Matters | a matter number such as `998.1`, or words | the matter with that number, opened straight away; or the matters whose name or description contains the words |
| Clients | a client number or words | the matters of every client whose number or name matches |

In every scope, `#1234` goes to document 1234 (see [`#` in the Box](#in-the-box)). Your install may call matters
*projects*: the Matters scope then uses that word.

Opening an entry under **Recent** in the left pane sets the picker to that scope, as does the start page, and so does
the address of a search. A choice you make in the picker stands until one of those changes it. Nothing about your
searches is kept: the box starts empty each time you open the page, except that the address of a search puts its
words in the box.

## Suggestions

After two characters, a list opens under the box with what the words match, as you type:

- **Matters** first, each as `1001.1 – Charter Dispute` with its client's name beside it, up to five;
- then **Documents** by title, each with its doc # and matter number, up to five;
- then **Search Documents for "…"**, naming the scope you're in.

Use the arrow keys to pick one and press Enter, or click it. A matter opens at its top level, a document opens at
its doc # result, and the last choice runs the search. With nothing picked, Enter runs the search as well. Esc closes
the list and leaves your words.

The list waits for a short pause in your typing, so it doesn't ask the server for every key. You only ever see what
you're allowed to read. Suggested documents come from the same index as a search (see [Full-Text
Search](#full-text-search)), so a document filed moments ago may not be suggested until the Indexer has read it, which
can be up to 30 minutes by default. Matters are suggested at once.

![The search box with "lease" typed in the Documents scope and its suggestions open: the matter 1002.1 – Commercial Lease with its client Brightwell Bakeries Ltd; five documents, among them Report on Lease – Unit 4 Weir Lane.odt, # 35 · 1002.1, and Lease – Draft for Client.docx, # 4 · 1002.1; and Search Documents for "lease" at the end](suggestions.png)

## `#` in the Box {#in-the-box}

Type `#` and a doc # and press Enter to go straight to that document: `#10388`, `#10388.2` for version 2, `#10388.2.1`
for snapshot 2.1, or the doc ID as a title shows it, `#10388 v2.1`. The page is the one in [Finding a Document by Doc
#](../finding-by-doc-number/). No list of suggestions opens for a `#`, and any scope will do.

## Matter Numbers

In the **Matters** scope:

- **`998.1` and Enter** opens matter 1 of client 998, at its top level.
- **A bare number,** such as `1`, or words, lists the matters that match. Which ones a bare number matches depends on
  how your install numbers matters: if each client numbers its own matters, it lists every matter numbered 1.
- **Each matter in the list shows its client's name,** so two clients' "Leases" matters can be told apart. Click or
  press Enter on one to open it.
- **Clients** lists the matters of the client you name, and opening a client under **Recent › Clients** searches that
  client's matters this way.

## Results

Enter on words opens the results at `/app/search`, with your words and the scope under the heading, such as `"charter"`
*in Documents, by relevance*.

- **Documents come by relevance,** best first, until you click a column header. A header sorts the results by that
  column, the server doing the sorting, and a second click reverses it. **Title**, **Doc #**, **Version**, **Author**
  and **Edit Date** sort; no header is marked while the order is relevance.
- **The Matter column** shows the matter number with the client's name beside it in paler type, for example `1001.1
  Saltmarsh Shipping Ltd`. Point at it to see the matter's name, or move to the row with a screen reader, which reads
  the name with the cell.
- **The count** above the list reads "6 documents". When there are more hits than the server counted, it says so,
  as "More than 10,000 documents", rather than guess. The server reads up to the first 10,000 hits of a search, and
  all of them when you sort by a column, so that is the largest number it names; a search by relevance can stop
  sooner and name a smaller one.
- **Rows load as you scroll,** 100 at a time.
- **Select a document** and its **Details** open at the right, as in a folder, with **Location** added: the folder the
  document is in, which you can click. See [Getting
  Around](../../getting-started/getting-around/#the-details-panel).
- **Folders, Matters and Clients** list what they found, without filters or sorting. Open a folder or a matter with
  Enter or a click.
- **The address holds the search:** the scope, your words, the filters, the sort and the selected document. Back,
  Reload and a copied link all bring you to the same results.

If you can read nothing that matches, the list says "Nothing found." You only ever see what you're allowed to read.

## Full-Text Search

Full-text search finds words inside documents, including Word, Excel and PowerPoint files, PDFs, and emails' text
and attachment names. A document's words are searchable once the server's Indexer has read it, which it does every 30
minutes unless your administrator has changed that, so a document filed a moment ago may not show up in a search yet.
A `#` and its doc # finds it at once.

An email's From, To, CC and Subject are part of its text, so words in them are found. Its Message-ID isn't, so the
search box doesn't find it. To find an email by its Message-ID, use the [Classic Interface](../../the-classic-interface/):
open the **Search** tab's **Full-text** section, choose **Healdan profile** as the **Template**, add **Message-ID** to
**Search in**, and type the whole Message-ID in **Expression**, exactly as its properties show it.

## Filters

In Healdan's app, **Filters** narrow the documents a folder lists, and the documents a search finds. Press the
**Filters** button, a funnel at the right end of the list's toolbar, and the filters open at the right of the list.
Press the button again, or **Close Filters** in the panel, to put them away. The Documents, Emails and Docs & Emails
scopes have them; Folders, Matters and Clients don't.

| Filter | Options |
|---|---|
| **Edit Date** | when the file last changed (filed, or a new version saved; not a change to its properties, a rename or a move): Any Time, Today, Last 7 Days, Last 30 Days, This Year, or Between… with a **From** day and a **To** day (either can be left empty; an end date before the start date isn't applied, and the field says so) |
| **File Type** | Word, Excel, PowerPoint, PDF, Email, OpenDocument, Picture, Other |
| **Author** | the people who are an Author of documents in the folder, or among the results |
| **Class** | the classes of documents in the folder, or among the results |
| **Client** | in search results only: the clients of the matters the results are in, by number |
| **Matter** | in search results only: the matters the results are in, such as `1001.1` |

Today and the days of a range are your own: they follow your computer's time zone, as the dates in the list do. A
document saved late in the evening counts as that day's, wherever the server is.

- **Each option shows a count:** how many documents ticking it would leave, among those you can read that pass the
  other filters. Options that would leave none aren't listed, apart from any you've already ticked.
- **Ticking several options in one list** keeps documents that match any of them, such as Word or PDF. Filters of
  different lists apply together, such as Word or PDF *and* by Priya Nair.
- **Chips** above the list name every filter in use, such as "File Type: Word, PDF", each with a **×** that drops it,
  and **Clear All** drops them all. A filter that no option in the panel offers any more still has its chip, so a
  filter can never leave a document out of sight without saying so.
- **The button's badge** counts the filters in use, even while the panel is closed. The count above the list then
  reads "3 of 7 documents" in a folder: the documents that pass, of all the folder's documents you can read. In a
  search it's the number of results that pass.
- **In a search, the counts can come from the most relevant matches only.** Counting every hit of a search with a great
  many would be slow, so the panel counts the best-ranked results you can read and says "Counts from the most relevant
  matches". A filter you've ticked that none of those counted is still listed, without a count, so it can be unticked.
- **A search's filters look among its first 10,000 hits,** the most the server reads, so a filter on a search with
  more hits than that can leave some out.
- **Filters list documents, not folders,** so a folder's own folders are left out while a filter is in use.
- **Clicking a document** while the filters are open puts the panel away to make room for that document's own
  panel. The filters stay set, and the button brings the panel back.
- **The filters are in the page's address,** so Back, bookmarks and a copied link keep them.

![Search results for "lease" in Documents, by relevance, with Filters open at the right and the File Type filter set to Word and PDF: the chip "File Type: Word, PDF" above eight documents with their doc #s, versions, matter numbers 1002.1 and 1003.1, authors and Edit Dates, such as # 48 on 1 Nov 2026 and # 62 on 12 Nov 2026, where long titles and names end in an ellipsis; and the filters' groups, with File Type counts of Word 5 and PDF 3, then Author](results-filters.png)
