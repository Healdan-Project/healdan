# Removing LogicalDOC's SOAP services: design

- **Date:** 2026-11-03
- **Status:** approved (owner, 2026-11-03), after a design session the same day.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. Decision

Healdan no longer offers LogicalDOC's SOAP web services. The nine SOAP endpoints under `/services` (`Auth`,
`Document`, `Folder`, `Search`, `Tag`, `Bookmark`, `System`, `Security` and `DocumentMetadata`) are never built, so
SOAP calls and WSDL requests get 404 (§3). There's no setting to bring them back. (owner)

The change covers SOAP only. CE's REST at `/services/rest` stays, and so does CMIS, which CE serves over AtomPub,
not SOAP. (owner)

This changes M2's call "Leave the old interfaces alone" (roadmap, M2). That call limited what M2 would change; it
wasn't a promise to keep them. The roadmap's non-goal for `/services/rest` still holds.

## 2. Why

- **Nothing Healdan builds uses SOAP.** The Healdan Helper, the LibreOffice extension and the Office add-in all use
  `/api`. The only caller was the smoke test, which made its non-admin user with SOAP's `storeUser` (§5).
- **Nobody Healdan serves needs it.** SOAP was LogicalDOC's interface for LogicalDOC's own clients. Healdan makes no
  effort to work with LogicalDOC's paid add-ins, extensions or apps; their features only raise whether Healdan should
  build its own, as it did for Office. (owner) The roadmap records this as principle 10 (§6).
- **It costs every rule Healdan adds.** Principle 4 has the server enforce every rule on every route, and the web
  services have needed fixes of their own: the permission gaps of R78 and R79, a folder create that could make a Store
  anywhere, and `replaceFile`, which deleted every other version's file. The capability map still lists open items on
  SOAP and REST (§6 there), and required properties aren't enforced on SOAP.
- **It's on by default and describes itself.** `WebservicePlugin.install` sets `webservice.enabled=true`, Basic
  authentication is on, and each endpoint's WSDL maps the whole service for anyone who can reach `/services`.
- **It speaks LogicalDOC's model.** A Store is a workspace there, and it knows nothing of clients, matters or doc #s.

## 3. How SOAP is removed

**`SoapRemover`** is a fork-owned class in `healdan-core`'s `com.logicaldoc.healdan.web` package, a Spring
`BeanDefinitionRegistryPostProcessor` with no dependencies of its own. `MainContext` loads every plugin's
`@PluginContext`, and `HealdanContext`'s scan finds the class. Spring runs it after every plugin context's XML has been
read, `context-webservice.xml` included, and before any bean is created. (call)

- **It removes every bean definition that would build a SOAP server.** A definition goes when its class, resolved with
  the bean factory's class loader, is a `jakarta.xml.ws.Endpoint` or a CXF `org.apache.cxf.frontend.ServerFactoryBean`:
  - `jaxws:endpoint`, which upstream uses for all nine, makes CXF's `EndpointDefinitionParser$SpringEndpointImpl`, an
    `Endpoint`;
  - `jaxws:server`, which upstream doesn't use today, makes a `JaxWsServerFactoryBean`, a `ServerFactoryBean`.

  It matches by type, never by id, so a SOAP endpoint that upstream adds later is removed too. CE's REST servers are
  CXF `JAXRSServerFactoryBean`s, which are neither, so they stay. A definition with no class name, or one whose class
  can't be loaded, is left alone.
- **It removes only definitions.** The `Soap*Service` classes, the interceptors and CXF's bus stay (§4). Each
  endpoint's implementor is named inside its `jaxws:endpoint`, so no `Soap*Service` instance is made for SOAP.
- **It logs what it removed,** in one INFO line at startup naming each definition's id.
- **No setting.** It always runs. (owner)

**What callers see.** Spring Security's catch-all chain permits `/services/*`, so a request reaches
`WebserviceServlet` and CXF as before. With no SOAP endpoints, CXF answers a SOAP call (`POST /services/Security`) and
a WSDL request (`/services/Auth?wsdl`) with 404 ("No service was found"), as it does for any address it doesn't know.
CXF's list at `/services/` shows only the REST services. `webservice.enabled` still turns CE's REST on and off, and
with it off, `/services/*` answers 302, as before.

## 4. What stays, and why

- **The `Soap*Service` classes.** CE's REST is built on them: each `Rest*Service` extends its `Soap*Service`. Healdan's
  web-service permission checks (`HealdanWsChecks`, and the edits for R78 and R79) live in them, and Healdan's tests
  call them directly. (call)
- **The nine `jaxws:endpoint` blocks in `context-webservice.xml`.** Removing them would be an upstream edit to a file
  upstream changed 9 times in the year to 2026-11-03, and a SOAP endpoint that upstream adds later would come back on.
  `SoapRemover` removes them at startup instead. (owner: approach A)
- **The rest of `logicaldoc-webservice`.** Upstream touched the module in about 90 commits in the same year, mostly
  core refactors passing through and its own permission fixes. Deleted files would conflict at each such sync, as the
  [open-source databases](2026-11-01-open-source-databases-design.md) spec found for the Oracle and SQL Server
  variants. The SOAP client classes stay for the same reason; nothing runs them.
- **CXF and its JAX-WS libraries.** `/api` and CE's REST run on CXF, and the module needs the JAX-WS frontend to
  compile.
- **The Web Service tab's settings,** which still apply to CE's REST.

**Not in this change.** CE's REST stays. It can go the same way once the smoke and image tests that call it move to
`/api`; that needs its own decision. Nothing replaces the Security service's user and group management over SOAP,
which CE's REST never had; Healdan's `/api` adds it if a client needs it.

## 5. The smoke test's user

`docker/tests/test-api.sh` made its non-admin user, `apiuser`, with SOAP's `storeUser`, since CE's REST can't make
users.

- **A new seed step, `smoke-api-user`, makes it instead.** It goes after `smoke-signin` in `SeedRunner.ORDER`, and
  runs only when `HEALDAN_SMOKE_FIXTURE` is `true`, as `smoke-fixture` does, so production never gets the user. It's a
  new step rather than a change to `smoke-fixture`, because a shipped seed step never changes. (call)
- **It makes the user as `storeUser` did:** username `apiuser`, name `Api`, first name `User`, email
  `apiuser@example.com`, language `en`, enabled, with the script's test password and no extra groups, stored with a
  `CREATED` history row. The password is a constant in the seed and in the script, each with a comment naming the
  other.
- **The script's setup step becomes two checks:** `GET /services/Auth?wsdl` answers 404, and the `storeUser`
  request it used to send to `/services/Security` answers 404. The steps after it sign in as `apiuser`, so they show
  the seed worked.

## 6. Docs

In the same pull request.

**Docs site:**

| Page | Change |
|---|---|
| Admin Guide → WebDAV, CMIS and Web Services (`site/content/admin/system/webdav-cmis-and-web-services.md`) | The SOAP row leaves the addresses table; a line says Healdan doesn't offer LogicalDOC's SOAP services; API keys work in CE's REST only |
| Developer Guide → API (`site/content/developer/api.md`) | "SOAP and REST under `/services/`" becomes REST only, and SOAP's `loginApiKey` goes |
| Developer Guide → How Healdan Extends LogicalDOC (`site/content/developer/how-healdan-extends-logicaldoc.md`) | The `logicaldoc-webservice` line says Healdan removes its SOAP endpoints, with `SoapRemover` |
| Install manual → Sign-In Providers (`site/content/install/sign-in/_index.md`) | "LogicalDOC's SOAP and REST" becomes "LogicalDOC's REST" |
| Install manual → Known Limitations (`site/content/install/known-limitations.md`) | The same |
| Developer Guide → Tests (`site/content/developer/tests.md`) | `test-api.sh`'s row says it runs as `apiuser`, which `smoke-api-user` makes, and checks that SOAP answers 404 |
| Developer Guide → Development Stack (`site/content/developer/development-stack.md`) | The smoke overlay's list gains `apiuser` |

The Developer Guide's Continuous Integration page keeps its mention of SOAP's XML namespaces, which the code still
holds.

**Repository:**

- **`CLAUDE.md`:** the Modules and "UI ↔ server" lines say Healdan removes SOAP's endpoints, the smoke fixture
  overlay's and step 5c's notes name `smoke-api-user` and the 404 checks, and the upstream edits list gains this spec
  (§7).
- **`docs/ROADMAP.md`:**
  - M2's "Leave the old interfaces alone" gains a note: SOAP removed (owner, 2026-11-03), with a link to this spec.
  - A new principle 10: "LogicalDOC's paid clients aren't a goal. Healdan makes no effort to work with LogicalDOC's
    paid add-ins, extensions or apps. Their features only raise whether Healdan should build its own, as it did for
    Office. (owner, 2026-11-03)"
- **`docs/reference/logicaldoc-ce-capability-map.md`:** §4.1 says Healdan removes SOAP's endpoints, and with them the
  Security service's user and group management, and the "Users and groups (SOAP only)" line says so too.
- **`docs/reference/healdan-api-inventory.md`:** the `/services/*` row says REST only, SOAP removed.

## 7. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/settings/protocols/WebservicesPanel.java` | The SOAP base URL item goes: its four lines, and `soapUrl` from both `setItems` calls, with a `Healdan:` comment where it was | The Web Service tab would otherwise show an address that answers nothing |

Upstream changed that file once in the year to 2026-11-03. No server-side upstream file changes.

## 8. Tests

- **`SoapRemoverTest`** (`healdan-core`):
  - With the real context, which loads `WebserviceContext` because `logicaldoc-webservice` is a test dependency: no
    bean is a `jakarta.xml.ws.Endpoint`; CE's REST servers are all still defined (`restAuth`, `restDocument`,
    `restFolder`, `restSearch`, `restTag`, `restBookmark`, `restSystem`, `restDocumentMetadata` and
    `commentservice`); and CXF's `ServerRegistry` on the context's bus holds no server at any of the nine SOAP
    addresses.
  - On a bare bean factory holding a `JaxWsServerFactoryBean` definition, a `JAXRSServerFactoryBean` definition and a
    definition with no class name: only the first is removed.
- **`SmokeApiUserSeedTest`** (`healdan-core`): with `HEALDAN_SMOKE_FIXTURE` unset the step is off; with it `true`, it
  makes `apiuser` with the fields in §5 and a password that signs in.
- **`docker/tests/test-api.sh`:** the two 404 checks in §5, on the real image, in the smoke test's step 5c.
- **Unchanged:** the tests that call the `Soap*Service` classes directly (`ChannelHarness`, `LogicalDocRoutesTest`,
  `SoapPermissionGapsTest` and others) stay. They now cover the code CE's REST inherits.
- **The GUI edit** is GWT client code that JUnit can't load; it's checked by hand on a local stack, where the Web
  Service tab shows the REST address and no SOAP one.
