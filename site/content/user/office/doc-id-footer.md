---
title: The Doc ID Footer
weight: 5
---

A Writer document can carry its doc ID in its footer, `#1234 v2.1`, so that every printout and PDF says which
document and version it is. It reads the same as the title bar and the search box.

## Inserting It

In Writer, choose **Healdan › Insert Doc ID**, or **Insert Doc ID** on the Healdan toolbar.

- **A field goes into the footer** of the page style the cursor is in, at its end. If that page style has no footer,
  one is turned on, and if it has separate left, right or first-page footers, each gets the field.
- **The document gets a property,** `Healdan Doc ID`, which the field shows. You can see it in **File › Properties ›
  Custom Properties**.
- **Choosing it again** adds no second field.

Then save, as for any other change.

## What It Shows

- **The doc # and version of your copy,** such as `#1234 v2.1`. After each save that reaches Healdan, the footer
  moves on with the title bar, without LibreOffice thinking the document has changed.
- **On a document that isn't in Healdan yet,** the field is empty, and a notice says so. It fills in when you save to
  Healdan, with the new doc #.
- **After Save as New Document,** the new document's own number.

## Healdan Keeps It Right

Each time the helper saves a file to Healdan, Healdan writes the number it stores the file as into the property and
into the footer's text. So Healdan's copy, its preview, and every download show the right number, whoever opens them
and in whatever app.

- **The formats:** `.docx`, `.docm`, `.dotx`, `.dotm`, `.odt` and `.ott`. Healdan changes only the property and the
  fields that show it, and only in files that have the property.
- **Uploads and check-ins in the web page** aren't updated. A document with the footer that you upload or check in
  there keeps the number its footer had, until its next save through the helper.

## Removing It

Delete the field from the footer, then delete the `Healdan Doc ID` property in **File › Properties › Custom
Properties**, and save. Without the property, Healdan stores the file as it is.

## Only in Writer

Calc and Impress have no Insert Doc ID. Their documents still show the doc # and version in the title bar.
