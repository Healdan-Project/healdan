# Group names as text, not HTML: design

- **Date:** 2026-11-02
- **Status:** approved (owner, 2026-11-02), as small upstream edits in their own pull request. The owner first approved
  the Groups list alone, then widened it the same day to every screen that shows a group's name or description.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The change

CE's screens showed a group's name, and in some places its description, as HTML: SmartGWT writes a grid cell's value,
a pick list's choice, a select's chosen value and a multi-combo's buttons as HTML unless told otherwise, and CE's own
cell formatters join values into HTML. So markup in a name or description was rendered rather than shown as typed.
The group form checks a new group's name in the browser only, and a description not at all; the server and the web
services store both as given.

**Every screen shows a group's name and description as text, not HTML.** (owner) With these edits no screen in this
repository is left showing either as HTML.

- **Fixed at the shared pieces.** Where one piece serves many screens (the users and security grids' formatters, the
  group pickers), the edit is there, not in each screen. (owner)
- **Only the value is escaped.** Formatters keep their own HTML, such as the group icon. ListGrid's `escapeHTML`
  flag escapes a formatter's whole output, so a column with a formatter escapes the value inside the formatter
  instead; a column without one sets the flag. (owner)
- **User names too.** The users grids' formatter and the user column (`UserListGridField`) show user names as well as
  group names, so those are now text as well, which is right for them too. (owner)
- **Nothing is escaped twice.** Every value reaches these pieces as stored: the data servlets write names into
  `CDATA` as they are, and no screen escapes them first.
- **`HealdanText.escape`, not `Util.escapeHTML`.** CE's helper escapes only `<` and `>`, so a name with `&` in it
  would still be read as HTML. `HealdanText.escape` uses GWT's `SafeHtmlUtils.htmlEscape`, which Healdan's GUI code
  already uses, and escapes `&`, `<`, `>` and both quotes. (call)
- **In the GUI, not the data servlets.** `GroupsDataServlet` and the ACL servlets' values are also used as values: the
  pickers' chosen values, and the lists' Export CSV, which writes records as they are and is unchanged. (call)
- **The Groups list's hidden Source column stays as it is.** It holds `local` for every group CE or Healdan makes:
  nothing in this repository sets it from what anyone types. (call)

What SmartGWT 14.1 does, read in its source, decides each edit:

- A `MultiComboBoxItem` titles each chosen value's button with the item's display value, as HTML, and passes the
  display value (the group's name) to the item's value formatter. Its inner combo box's pick list doesn't take an
  `escapeHTML` flag from the item, so `HealdanText` gives it a name field that escapes.
- A `SelectItem` with its own pick list fields needs the flag on each field, and on the item for the chosen value.

The edits are small hooks into CE's code, kept small for upstream syncs. Each is marked `Healdan:`, and new code
lives in Healdan's own `HealdanText` (`logicaldoc-gui/.../common/client/healdan/`).

## 2. Upstream edits

All under `logicaldoc-gui/src/main/java/com/logicaldoc/gui/`.

| File | Edit | Why |
|---|---|---|
| `frontend/client/security/group/GroupsPanel.java` | `setEscapeHTML(true)` on the list's Name and Description fields | The Groups list |
| `common/client/grid/formatters/UserCellFormatter.java` | Escapes the value before wrapping it | The users list's columns, its Groups column among them; the same in Allowed Impersonators, a group's Users tab, and the user selector's pick list |
| `common/client/grid/UserListGridField.java` | `AvatarCellFormatter.format` escapes the value it passes on; the avatar and group icon stay HTML | The user column across the GUI, and the security grids' entity column, which shows a group's name: folders, documents, notes, menus, templates, stamps, workflows, automation routines and reports |
| `common/client/util/ItemFactory.java` | `newGroupSelector`: `setEscapeHTML(true)` on the pick list's Name and Description fields and on the item | Every group picker: the group form's Inherit, each security grid's Add Group, the subscriptions panels, the user search, the calendar, messages and the workflow task editor |
| `common/client/widgets/GroupSelectorCombo.java` | The value formatter escapes the name and keeps the icon; `HealdanText.showChoicesAsText` | Groups in contacts, reading requests, saved searches and working time |
| `frontend/client/security/user/UserPropertiesPanel.java` | `HealdanText.showAsText` on the Groups multi-combo | The user editor's groups |
| `frontend/client/security/ldap/LDAPServerDetailsPanel.java` | `HealdanText.showAsText` on the default groups multi-combo | An LDAP server's default groups |
| `frontend/client/workflow/designer/TaskEditor.java` | `setEscapeHTML(true)` on the candidates' Label field | A task's candidates, groups among them |
| `frontend/client/calendar/CalendarEventDialog.java` | The attendees' Name formatter escapes the value | An event's attendees, groups among them |

`ColoredListGridField`, which `UserListGridField` extends, isn't edited: its other subclasses (file names, folders,
dates, ratings and more) pass their own HTML through its formatter.

## 3. Tests

`HealdanTextTest` (in `logicaldoc-gui`, added to `UPSTREAM_TESTS`) checks `HealdanText.escape`, which the formatters
use: markup, ampersands and quotes escaped, plain text and numbers unchanged, null empty. The hooks themselves are
GWT client code that runs only in a browser, so no JUnit test can load the screens; the GUI module's GWT compile
builds them (`mvn -pl logicaldoc-gui install` locally, and the Docker image build in CI).
