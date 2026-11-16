# Template and attribute set imports refused, exports kept: design

- **Date:** 2026-11-15
- **Status:** approved (owner, 2026-11-15), as merge fixes in the upstream sync of 2026-11-15
  (Healdan-Project/healdan#243): four small upstream edits, two GUI hooks and two tests.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The change

Upstream's `021eef44a` and `70ce7850f` add Import and Export to the Classic Interface's **Administration → Document
metadata → Templates** screen, on its **Templates** and **Attribute sets** tabs. A template's or an attribute set's
right-click menu has **Export**, which downloads `template-<name>.xml`, or `attributeset-<name>.zip` holding
`object.xml` and a CSV of options for each list attribute. Each tab's toolbar has **Import**, which uploads such a file
and makes a new template or set from it.

The file is CE's own `Template` or `AttributeSet` object, written by Java's bean encoder (`IOUtil.serialize`) and read
back by the import with `IOUtil.deserialize`. Healdan doesn't read that format from uploads.

**Healdan refuses the import and keeps the export.** (owner) `TemplateServiceImpl.importTemplate` and
`AttributeSetServiceImpl.importAttributeSet` call `HealdanImports.refuse()` once the session is checked, before they
read the upload, so they answer "Healdan doesn't import templates or attribute sets." to everyone, administrators
included. The tabs no longer show **Import**. An import of Healdan's own, in a format it parses, is on the backlog
(Healdan-Project/healdan#245). (owner)

**Each export needs Document metadata, as upstream's check means to.** Upstream's servlets ask
`MenuDAO.isReadAllowed(Menu.ADMINISTRATION_METADATA, …)`, but build the exception without throwing it, so the export
goes ahead for anyone signed in. Healdan throws, as its other data servlets do (`TicketsDataServlet`): an
`IOException` naming the user and the menu, which `AbstractDataServlet` answers with an error and no file.

## 2. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-webapp/src/main/java/com/logicaldoc/web/service/TemplateServiceImpl.java` | `importTemplate` calls `HealdanImports.refuse()` after `validateSession()`, marked `Healdan:` | No import of CE's file (§1) |
| `logicaldoc-webapp/src/main/java/com/logicaldoc/web/service/AttributeSetServiceImpl.java` | `importAttributeSet` calls `HealdanImports.refuse()` after `validateSession()`, marked `Healdan:` | The same |
| `logicaldoc-webapp/src/main/java/com/logicaldoc/web/data/TemplatesDataServlet.java` | The export's menu check throws an `IOException` instead of building an unthrown `PermissionException`, whose import goes, marked `Healdan:` | The export needs Document metadata (§1) |
| `logicaldoc-webapp/src/main/java/com/logicaldoc/web/data/AttributeSetsDataServlet.java` | The same | The same |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/metadata/template/TemplatesPanel.java` | The toolbar doesn't add the Import button: its `addButton` line is a `Healdan:` comment | No button for a refused import |
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/metadata/template/AttributeSetsPanel.java` | The same | The same |

`HealdanImports` (`logicaldoc-webapp/src/main/java/com/logicaldoc/web/service/HealdanImports.java`) is new and
Healdan's. Upstream's `TemplateUploader`, `AttributeSetUploader` and their two strings stay, unused.

## 3. Tests

Both classes are in `UPSTREAM_TESTS`.

- **`MetadataImportsTest`** puts a file the import would read among an administrator's uploads, as the upload servlet
  does: a template written by `IOUtil.serialize`, and a zip with an attribute set's `object.xml`. Each import is
  refused with `HealdanImports.REFUSAL`, and no template or set of that name exists after.
- **`MetadataExportsTest`** asks each servlet for the fixture's template or set. The author, without Document metadata,
  gets nothing, and with it gets the template's XML or the set's zip with `object.xml`. An administrator gets both.

Before the edits the four refusals failed: both imports made their template or set, and the author got both exports.
CE's `TemplateServiceImplTest` and Healdan's `AttributeSetServiceImplAuthorizationTest` still pass.

## 4. Later

- **Healdan's own import,** and an export in the same format: Healdan-Project/healdan#245. (owner)
- **The export's file names.** Each list attribute's options go into `<attribute name>.csv` inside a temporary folder,
  and the download is named after the template or set, unquoted. Neither name is checked on the server. Revisit them
  with #245. (call)
- **The import's temporary CSVs** are never deleted. Moot while the import is refused. (call)
