# Removing LogicalDOC's SOAP Services Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LogicalDOC's nine SOAP endpoints under `/services` are never built, so SOAP calls and WSDL requests get 404, while CE's REST at `/services/rest`, CMIS and WebDAV are unchanged.

**Architecture:**
- **`healdan-core`** gains `SoapRemover` (`com.logicaldoc.healdan.web`), a Spring `BeanDefinitionRegistryPostProcessor` that removes every bean definition building a SOAP server (a JAX-WS `Endpoint` or a CXF `ServerFactoryBean`), matched by type, before Spring builds any bean. No upstream server file changes.
- **`healdan-core`** also gains the seed step `smoke-api-user`, which makes the smoke test's non-admin `apiuser`, which `docker/tests/test-api.sh` used to make over SOAP.
- **The old GUI** drops the SOAP address from the Web Service tab: the change's only upstream edit.
- **The docs** (site and repository) say SOAP is gone, in the same pull request.

**Tech Stack:** JDK 25, Spring 6.2.8, CXF 4.0.10, JUnit 4, bash with `curl` for the image test, GWT 2.12 for the GUI edit, Hugo 0.168.0 for the docs site.

**Spec:** [`docs/superpowers/specs/2026-11-03-soap-removal-design.md`](../specs/2026-11-03-soap-removal-design.md). Read it first; this plan argues from it.

**Status:** written on 2026-11-03, after the spec's approval.

## Global Constraints

- SOAP only: CE's REST at `/services/rest`, CMIS and WebDAV don't change. (spec §1)
- No setting brings SOAP back. (spec §1, §3)
- No upstream server-side file changes. The one upstream edit is `WebservicesPanel` (spec §7).
- The `Soap*Service` classes, the `jaxws:endpoint` blocks in `context-webservice.xml`, the SOAP client classes and CXF's JAX-WS libraries all stay. (spec §4)
- A shipped seed step never changes, so the user comes from a new step, `smoke-api-user`. (spec §5)
- Every new source file Healdan writes opens with `SPDX-FileCopyrightText: 2026 The Healdan Project` and `SPDX-License-Identifier: LGPL-3.0-only`, in its comment syntax.
- `healdan-core`'s Java uses tabs, JUnit 4 and SLF4J, as `SmokeSignInSeed` does.
- Docs: Title Case for titles, headings and labels; British spelling; Healdan's glossary terms (Store, matter); lines wrapped at 120 columns, as the pages around them.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Before Starting

- `~/logicaldoc-dev.properties` must exist (an empty file works), or Spring-based tests fail in `setUp`.
- `healdan-core`'s tests run against the installed jars of version 9.3.2's modules. If `~/.m2/repository/com/logicaldoc/logicaldoc-core/9.3.2` is missing, install them first:

```bash
mvn -B -ntp -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -pl '!logicaldoc-webapp' install
```

- Copy `docker/maven-settings.xml` to `~/.m2/settings.xml` if it isn't there.

## File Structure

| File | Task | Responsibility |
|---|---|---|
| Create `healdan-core/src/main/java/com/logicaldoc/healdan/web/SoapRemover.java` | 1 | Removes SOAP server definitions before Spring builds beans |
| Create `healdan-core/src/test/java/com/logicaldoc/healdan/web/SoapRemoverTest.java` | 1 | The real context has no SOAP servers; the matcher keeps REST |
| Create `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SmokeApiUserSeed.java` | 2 | Makes `apiuser` on the smoke stack only |
| Create `healdan-core/src/test/java/com/logicaldoc/healdan/seed/SmokeApiUserSeedTest.java` | 2 | The step's user, its flag and its marker |
| Modify `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` | 2 | `smoke-api-user` in `ORDER` |
| Modify `docker/tests/test-api.sh` | 3 | The SOAP setup becomes two 404 checks |
| Modify `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/settings/protocols/WebservicesPanel.java` | 4 | No SOAP address on the Web Service tab |
| Modify seven site pages and five repository docs | 5 | Docs say SOAP is gone |

---

### Task 1: `SoapRemover`

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/web/SoapRemover.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/web/SoapRemoverTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `com.logicaldoc.healdan.web.SoapRemover`, a `@Component` with `postProcessBeanDefinitionRegistry(BeanDefinitionRegistry)` and the package-private constant `SOAP_TYPES` (`List<String>`). The docs (Task 5) name the class.

Background for the implementer:
- Upstream's `logicaldoc-webservice/src/main/resources/context/context-webservice.xml` declares nine `jaxws:endpoint`s (ids `auth`, `document`, `folder`, `search`, `tag`, `bookmark`, `system`, `security`, `documentMetadata`; addresses `/Auth` … `/DocumentMetadata`) and nine `jaxrs:server`s (`commentservice` at `/mobile/comments`, and `restAuth` … `restDocumentMetadata` at `/rest/…`).
- `jaxws:endpoint` makes a bean of class `org.apache.cxf.jaxws.spring.EndpointDefinitionParser$SpringEndpointImpl`, which extends `org.apache.cxf.jaxws.EndpointImpl`, which extends `jakarta.xml.ws.Endpoint`.
- `jaxws:server` makes `JaxWsServerFactoryBean`, which extends `org.apache.cxf.frontend.ServerFactoryBean`.
- `jaxrs:server` makes `JAXRSServerFactoryBean`, which extends `AbstractJAXRSFactoryBean` and `AbstractEndpointFactory`, not `ServerFactoryBean`.
- `healdan-core`'s main code doesn't compile against CXF, so `SoapRemover` loads the two types by name. Its tests do have CXF: `logicaldoc-webservice` is a test dependency, and it brings `cxf-rt-frontend-jaxws` and `cxf-rt-frontend-jaxrs`.
- Healdan's test context is `MainContext`, which loads every `@PluginContext` on the class path, `WebserviceContext` included, so the tests see the real SOAP endpoints.

- [ ] **Step 1: Write the failing test**

Create `healdan-core/src/test/java/com/logicaldoc/healdan/web/SoapRemoverTest.java`:

```java
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.apache.cxf.Bus;
import org.apache.cxf.endpoint.ServerRegistry;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxws.EndpointImpl;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

import jakarta.xml.ws.Endpoint;

public class SoapRemoverTest extends AbstractHealdanTestCase {

	/** The addresses of the nine SOAP services upstream's context-webservice.xml declares */
	private static final Set<String> SOAP_ADDRESSES = Set.of("/Auth", "/Document", "/Folder", "/Search", "/Tag",
			"/Bookmark", "/System", "/Security", "/DocumentMetadata");

	/** CE's REST servers in the same file, which stay */
	private static final List<String> REST_SERVERS = List.of("restAuth", "restDocument", "restFolder", "restSearch",
			"restTag", "restBookmark", "restSystem", "restDocumentMetadata", "commentservice");

	/**
	 * The real context, which loads the web services' XML: no SOAP endpoint is
	 * built, none is running, and CE's REST servers are all there
	 */
	@Test
	public void testTheContextHasNoSoapServices() {
		assertEquals(0, Context.get().getBeansOfType(Endpoint.class).size());

		for (String id : REST_SERVERS)
			assertTrue(id, Context.get().containsBean(id));

		List<String> addresses = Context.get(Bus.class).getExtension(ServerRegistry.class).getServers().stream()
				.map(server -> server.getEndpoint().getEndpointInfo().getAddress()).toList();
		assertTrue(addresses.toString(), addresses.stream().noneMatch(SOAP_ADDRESSES::contains));
		assertTrue(addresses.toString(), addresses.contains("/rest/auth"));
	}

	/**
	 * By type, whatever the id: a JAX-WS endpoint and a JAX-WS server factory
	 * go; a JAX-RS server and a definition with no class name stay
	 */
	@Test
	public void testRemovesSoapServersOnly() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.registerBeanDefinition("anEndpoint", new RootBeanDefinition(EndpointImpl.class));
		factory.registerBeanDefinition("aSoapServer", new RootBeanDefinition(JaxWsServerFactoryBean.class));
		factory.registerBeanDefinition("aRestServer", new RootBeanDefinition(JAXRSServerFactoryBean.class));
		RootBeanDefinition noClass = new RootBeanDefinition();
		noClass.setFactoryBeanName("aRestServer");
		noClass.setFactoryMethodName("create");
		factory.registerBeanDefinition("madeByAFactory", noClass);

		new SoapRemover().postProcessBeanDefinitionRegistry(factory);

		assertEquals(List.of("aRestServer", "madeByAFactory"), List.of(factory.getBeanDefinitionNames()));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl healdan-core test -Dtest=SoapRemoverTest`
Expected: a compilation failure, `cannot find symbol ... class SoapRemover`. (Comment out `testRemovesSoapServersOnly` for a moment to see `testTheContextHasNoSoapServices` fail on `expected:<0> but was:<9>`, which shows the test context really builds the SOAP endpoints. Restore it after.)

- [ ] **Step 3: Write the implementation**

Create `healdan-core/src/main/java/com/logicaldoc/healdan/web/SoapRemover.java`:

```java
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Removes LogicalDOC's SOAP web services (SOAP removal spec, section 3).
 * After every plugin context's XML is read and before any bean is built, it
 * removes each bean definition whose class would build a SOAP server: a
 * JAX-WS {@code Endpoint}, which {@code jaxws:endpoint} makes and upstream's
 * {@code context-webservice.xml} declares for all nine services, or a CXF
 * {@code ServerFactoryBean}, which {@code jaxws:server} makes. It matches by
 * type, never by id, so a SOAP endpoint upstream adds later goes too.
 * <p>
 * CE's REST servers are CXF {@code JAXRSServerFactoryBean}s, neither of the
 * two, and stay, as do the {@code Soap*Service} classes they extend. There's
 * no setting: SOAP never comes back.
 */
@Component
public class SoapRemover implements BeanDefinitionRegistryPostProcessor, BeanClassLoaderAware {

	/**
	 * The types whose definitions build a SOAP server, by name, since
	 * healdan-core doesn't compile against CXF
	 */
	static final List<String> SOAP_TYPES = List.of("jakarta.xml.ws.Endpoint",
			"org.apache.cxf.frontend.ServerFactoryBean");

	private static final Logger log = LoggerFactory.getLogger(SoapRemover.class);

	private ClassLoader classLoader = ClassUtils.getDefaultClassLoader();

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		List<Class<?>> soapTypes = SOAP_TYPES.stream().<Class<?>>map(this::load).filter(Objects::nonNull).toList();
		if (soapTypes.isEmpty())
			return;

		List<String> removed = new ArrayList<>();
		for (String name : registry.getBeanDefinitionNames()) {
			Class<?> type = load(registry.getBeanDefinition(name).getBeanClassName());
			if (type != null && soapTypes.stream().anyMatch(soap -> soap.isAssignableFrom(type))) {
				registry.removeBeanDefinition(name);
				removed.add(name);
			}
		}
		if (!removed.isEmpty())
			log.info("Removed LogicalDOC's SOAP web services: {}", String.join(", ", removed));
	}

	/**
	 * Loads a class without initialising it
	 *
	 * @param className the class's name, or {@code null}
	 *
	 * @return the class, or {@code null} when there's no name or it can't be
	 *         loaded
	 */
	private Class<?> load(String className) {
		if (className == null)
			return null;
		try {
			return ClassUtils.forName(className, classLoader);
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}
}
```

`ClassUtils.forName` doesn't run static initialisers. Spring 6.2's `BeanDefinitionRegistryPostProcessor` has a default `postProcessBeanFactory`, so nothing else needs implementing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl healdan-core test -Dtest=SoapRemoverTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`. The test log shows `Removed LogicalDOC's SOAP web services: auth, document, folder, search, tag, bookmark, system, security, documentMetadata`.

- [ ] **Step 5: Run the tests that call the SOAP classes directly**

They must pass unchanged, since they build `Soap*Service`s themselves:

Run: `mvn -pl healdan-core test -Dtest='ChannelFilingTest,ChannelTreePermissionsTest,SearchFolderToleranceTest,PermissionRulesTest,ProviderApiKeysTest,ProviderPasswordListenerTest,DocNumberTest,EmailDocumentListenerTest'`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/web/SoapRemover.java healdan-core/src/test/java/com/logicaldoc/healdan/web/SoapRemoverTest.java
git commit -m "Remove LogicalDOC's SOAP endpoints before Spring builds them" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The `smoke-api-user` seed step

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SmokeApiUserSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` (`ORDER`, lines 26–30)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/SmokeApiUserSeedTest.java`

**Interfaces:**
- Consumes: `SmokeFixtureSeed.ENV` (`"HEALDAN_SMOKE_FIXTURE"`, package-private), `SeedStep` (`name()`, `enabled()`, `run()`), `SeedRunner.runPending(List<SeedStep>)` (package-private; throws `HealdanStartupException` for a name not in `ORDER`).
- Produces: `SmokeApiUserSeed` with `USERNAME = "apiuser"` and `PASSWORD`, which must equal `USER_PW` in `docker/tests/test-api.sh` (Task 3).

Background for the implementer:
- SOAP's `storeUser` made a new user with `UserDAO.store`, its password through `setDecodedPassword`, and no groups. For a new user, `HibernateUserDAO.store` writes the `CREATED` history row itself, so the step needs no `UserHistory` of its own.
- `SmokeFixtureSeed` and `SmokeSignInSeed` in the same package are the pattern: a flag supplier for tests, `@Autowired` no-argument constructor for Spring.

- [ ] **Step 1: Write the failing test**

Create `healdan-core/src/test/java/com/logicaldoc/healdan/seed/SmokeApiUserSeedTest.java`:

```java
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistoryDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;

public class SmokeApiUserSeedTest extends AbstractHealdanTestCase {

	private static final String MARKER = "seed.smoke-api-user";

	private static SeedRunner runner() {
		return Context.get(SeedRunner.class);
	}

	@Test
	public void testMakesTheUser() throws Exception {
		SmokeApiUserSeed seed = new SmokeApiUserSeed(() -> "true");
		assertEquals("smoke-api-user", seed.name());
		assertTrue(seed.enabled());

		runner().runPending(List.of(seed));
		assertEquals("done", HealdanSettings.get().get(MARKER));

		// As SOAP's storeUser made it in docker/tests/test-api.sh
		User user = UserDAO.get().findByUsername("apiuser", true);
		assertNotNull(user);
		assertEquals("Api", user.getName());
		assertEquals("User", user.getFirstName());
		assertEquals("apiuser@example.com", user.getEmail());
		assertEquals("en", user.getLanguage());
		assertTrue(user.isEnabled());
		assertEquals(User.Type.DEFAULT, user.getType());
		assertEquals(UserSource.DEFAULT, user.getSource());
		assertEquals(Encrypter.encryptSHA256(SmokeApiUserSeed.PASSWORD), user.getPassword());
		assertFalse(user.isAdmin());
		assertTrue(UserHistoryDAO.get().findByUserId(user.getId()).stream()
				.anyMatch(h -> UserEvent.CREATED.toString().equals(h.getEvent())));

		// And never again
		runner().runPending(List.of(new SmokeApiUserSeed(() -> "true")));
		assertEquals(1, UserDAO.get().findByWhere("_entity.username = 'apiuser'", null, null).size());
	}

	@Test
	public void testNothingWithoutTheFlag() throws Exception {
		for (String flag : Arrays.asList(null, "", "false", "TRUE")) {
			SmokeApiUserSeed seed = new SmokeApiUserSeed(() -> flag);
			assertFalse(String.valueOf(flag), seed.enabled());
			runner().runPending(List.of(seed));
		}

		assertNull(UserDAO.get().findByUsername("apiuser"));
		// No marker, so a later start with the flag runs it
		assertNull(HealdanSettings.get().get(MARKER));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl healdan-core test -Dtest=SmokeApiUserSeedTest`
Expected: a compilation failure, `cannot find symbol ... class SmokeApiUserSeed`.

- [ ] **Step 3: Write the step**

Create `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SmokeApiUserSeed.java`:

```java
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_FIXTURE} is {@code true},
 * as {@code docker/tests/compose.smoke.yaml} sets it, creates the user
 * {@code apiuser}, not an admin, that {@code docker/tests/test-api.sh} signs in
 * as. The script made it with SOAP's {@code storeUser} until Healdan removed
 * LogicalDOC's SOAP services (SOAP removal spec, section 5), and this makes it
 * the same way: no groups of its own, and the {@code CREATED} history row that
 * storing a new user writes.
 * <p>
 * Without the variable the step does nothing and writes no marker, so it can
 * run on a later start.
 */
@Component
public class SmokeApiUserSeed implements SeedStep {

	static final String USERNAME = "apiuser";

	/**
	 * A throwaway password for a throwaway user on a throwaway stack:
	 * {@code USER_PW} in {@code docker/tests/test-api.sh}, which signs in with
	 * it
	 */
	static final String PASSWORD = "Api-Smoke-Pw-42!x";

	private final Supplier<String> flag;

	@Autowired
	public SmokeApiUserSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeApiUserSeed(Supplier<String> flag) {
		this.flag = flag;
	}

	@Override
	public String name() {
		return "smoke-api-user";
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	@Override
	public void run() throws Exception {
		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(USERNAME);
		user.setName("Api");
		user.setFirstName("User");
		user.setEmail("apiuser@example.com");
		user.setLanguage("en");
		user.setDecodedPassword(PASSWORD);
		user.setType(User.Type.DEFAULT);
		user.setSource(UserSource.DEFAULT);
		user.setEnabled(true);
		UserDAO.get().store(user);
	}
}
```

- [ ] **Step 4: Run the test to verify it still fails, on the order**

Run: `mvn -pl healdan-core test -Dtest=SmokeApiUserSeedTest`
Expected: `testMakesTheUser` errors with `HealdanStartupException: Unknown seed step 'smoke-api-user'`, because `SeedRunner.ORDER` doesn't list it yet.

- [ ] **Step 5: Add the step to the order**

In `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java`, replace:

```java
			"smoke-fixture", "smoke-signin", "demo-fixture");
```

with:

```java
			"smoke-fixture", "smoke-signin", "smoke-api-user", "demo-fixture");
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -pl healdan-core test -Dtest='SmokeApiUserSeedTest,SmokeFixtureSeedTest,SmokeSignInSeedTest,DemoFixtureSeedTest'`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/seed/SmokeApiUserSeed.java healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java healdan-core/src/test/java/com/logicaldoc/healdan/seed/SmokeApiUserSeedTest.java
git commit -m "Make the smoke test's apiuser in a seed step, not over SOAP" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `test-api.sh` checks that SOAP is gone

**Files:**
- Modify: `docker/tests/test-api.sh` (header comment, lines 5–10; `USER_PW`'s comment, line 19; the setup block, lines 122–136)

**Interfaces:**
- Consumes: `SmokeApiUserSeed.PASSWORD` (Task 2), which equals `USER_PW`; `check` and `http_code` from `docker/tests/lib.sh`.
- Produces: nothing other tasks use.

- [ ] **Step 1: Say so in the header**

In `docker/tests/test-api.sh`, replace:

```bash
# The Office add-in's flow against the JSON API in the real Docker image (M2 spec §14.1): sign in, find a matter,
```

with:

```bash
# First checks that LogicalDOC's SOAP services answer 404 (SOAP removal spec §5). Then
# the Office add-in's flow against the JSON API in the real Docker image (M2 spec §14.1): sign in, find a matter,
```

- [ ] **Step 2: Point the password at the seed**

Replace:

```bash
# A throwaway password for a throwaway user on a throwaway stack.
```

with:

```bash
# A throwaway password for a throwaway user on a throwaway stack. healdan-core's SmokeApiUserSeed makes the user
# with the same password (its PASSWORD), when the smoke overlay sets HEALDAN_SMOKE_FIXTURE=true.
```

- [ ] **Step 3: Replace the SOAP setup with the two checks**

Replace the block from `# --- Setup: a non-admin user, over SOAP, as the stack notes describe.` through `[ "$FAILED" = 0 ] || { head -c 500 "$OUT"; exit 1; }` (lines 122–136) with:

```bash
# --- Setup: the admin's session, and SOAP gone. -----------------------------------------------------------------
# The non-admin user comes from the smoke-api-user seed. This script stored it with SOAP's storeUser until Healdan
# removed LogicalDOC's SOAP services; the same request now finds no service.
admin_sid=$(curl -fsS -d username=admin -d password=admin "$BASE/services/rest/auth/loginForm" | tr -d '"[:space:]')
soap_user=$(cat <<XML
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ws="http://ws.logicaldoc.com">
<soapenv:Body><ws:storeUser><sid>$admin_sid</sid><user>
<username>$USER_NAME</username><password>$USER_PW</password><name>Api</name><firstName>User</firstName>
<email>apiuser@example.com</email><language>en</language><enabled>true</enabled>
</user></ws:storeUser></soapenv:Body></soapenv:Envelope>
XML
)
check "setup: SOAP's WSDL answers 404" test "$(http_code "$BASE/services/Auth?wsdl")" = 404
check "setup: SOAP's storeUser answers 404" test "$(http_code -H 'Content-Type: text/xml; charset=utf-8' \
  -H 'SOAPAction: ""' -d "$soap_user" "$BASE/services/Security")" = 404
[ "$FAILED" = 0 ] || exit 1
```

`admin_sid` stays: step 3 uses it.

- [ ] **Step 4: Check the script parses**

Run: `bash -n docker/tests/test-api.sh && echo ok`
Expected: `ok`. If `shellcheck` is installed, `shellcheck -x docker/tests/test-api.sh` adds no warning on the changed lines.

The script itself runs against a stack in Task 6.

- [ ] **Step 5: Commit**

```bash
git add docker/tests/test-api.sh
git commit -m "Check in test-api.sh that SOAP's endpoints answer 404" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: No SOAP address on the Web Service tab

**Files:**
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/settings/protocols/WebservicesPanel.java` (lines 78–82, 119 and 122)

**Interfaces:** none.

This is the change's only upstream edit (spec §7). GWT client code has no unit test; Task 6 checks it by hand.

- [ ] **Step 1: Remove the SOAP item**

Replace:

```java
		// SOAP Url
		StaticTextItem soapUrl = ItemFactory.newStaticTextItem("soapUrl", I18N.message("soapbaseurl"),
				GWT.getHostPageBaseURL() + "services");
		soapUrl.setColSpan(4);

		// REST Url
```

with:

```java
		// Healdan: no SOAP URL; Healdan removes LogicalDOC's SOAP services (SOAP removal spec, section 7)

		// REST Url
```

Replace:

```java
			webServiceForm.setItems(soapUrl, restUrl, wsEnabled, wsBasicAuthEnabled, recordCalls, recordCallsPayload,
					ttl, tenantStat, apicalls, apicallsCurrent);
		else
			webServiceForm.setItems(soapUrl, restUrl, wsEnabled, wsBasicAuthEnabled, apicalls, apicallsCurrent);
```

with:

```java
			webServiceForm.setItems(restUrl, wsEnabled, wsBasicAuthEnabled, recordCalls, recordCallsPayload, ttl,
					tenantStat, apicalls, apicallsCurrent);
		else
			webServiceForm.setItems(restUrl, wsEnabled, wsBasicAuthEnabled, apicalls, apicallsCurrent);
```

`GWT` and `I18N` are still used by the REST item, so no import goes.

- [ ] **Step 2: Compile the GUI's Java and run the icon check**

Run: `mvn -B -ntp -pl logicaldoc-gui -Dgwt.skipCompilation=true compile`
Expected: `BUILD SUCCESS`.

Run: `python3 .github/scripts/check-fontawesome-icons.py`
Expected: passes (no icon changed; CI runs it first).

- [ ] **Step 3: Re-install the GUI module**

`healdan-core` depends on it, so its later test runs use the installed jar:

Run: `mvn -B -ntp -pl logicaldoc-gui -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/settings/protocols/WebservicesPanel.java
git commit -m "Drop the SOAP address from the Web Service tab" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The docs

**Files:**
- Modify: `site/content/admin/system/webdav-cmis-and-web-services.md`
- Modify: `site/content/developer/api.md`
- Modify: `site/content/developer/how-healdan-extends-logicaldoc.md`
- Modify: `site/content/developer/tests.md`
- Modify: `site/content/developer/development-stack.md`
- Modify: `site/content/install/sign-in/_index.md`
- Modify: `site/content/install/known-limitations.md`
- Modify: `CLAUDE.md`
- Modify: `docs/ROADMAP.md`
- Modify: `docs/reference/logicaldoc-ce-capability-map.md`
- Modify: `docs/reference/healdan-api-inventory.md`

**Interfaces:**
- Consumes: the names `SoapRemover` (Task 1) and `smoke-api-user` (Task 2).
- Produces: nothing.

Each step replaces exact text; the old text is quoted from the files as they are on 2026-11-03.

- [ ] **Step 1: Admin Guide → WebDAV, CMIS and Web Services**

In `site/content/admin/system/webdav-cmis-and-web-services.md`, replace:

```markdown
Besides the web page and Healdan's own [API](../../../developer/api/), LogicalDOC has three other ways in: WebDAV,
CMIS, and its SOAP and REST web services. They're set under **Administration → Settings → Protocols**, one tab
each. All three are on to begin with.
```

with:

```markdown
Besides the web page and Healdan's own [API](../../../developer/api/), LogicalDOC has three other ways in: WebDAV,
CMIS, and its REST web services. They're set under **Administration → Settings → Protocols**, one tab each. All
three are on to begin with.

Healdan doesn't offer LogicalDOC's SOAP web services: their addresses answer `404 Not Found`.
```

Delete the table row:

```markdown
| SOAP web services | `https://healdan.example/services` | a username and password, or a LogicalDOC API key |
```

Replace:

```markdown
works only in the web services.
```

with:

```markdown
works only in the REST web services.
```

- [ ] **Step 2: Developer Guide → API**

In `site/content/developer/api.md`, replace:

```markdown
LogicalDOC's own web services are still there: SOAP and REST under `/services/`. They take LogicalDOC's API keys,
which start `ld-` and are sent as `X-API-KEY`. People make them in the web page, under the person icon in the top
bar, in **Security → API Keys**.
```

with:

```markdown
LogicalDOC's own REST web services are still there, under `/services/rest/`; Healdan doesn't offer LogicalDOC's SOAP
services. They take LogicalDOC's API keys, which start `ld-` and are sent as `X-API-KEY`. People make them in the
web page, under the person icon in the top bar, in **Security → API Keys**.
```

Replace:

```markdown
**A device token isn't an API key.** It works only under `/api`. LogicalDOC's services refuse it however it's sent:
to `/services/rest/auth/loginApiKey` or the SOAP service's `loginApiKey`, as `X-API-KEY` on any of their routes, or
as the password or key of a Basic sign-in, which WebDAV and CMIS use. So a lost token can't be turned into an API
key, which would outlast it. Still, guard a token as you would the password: until it's revoked, it signs in to the
API as you.
```

with:

```markdown
**A device token isn't an API key.** It works only under `/api`. LogicalDOC's services refuse it however it's sent:
to `/services/rest/auth/loginApiKey`, as `X-API-KEY` on any of their routes, or as the password or key of a Basic
sign-in, which WebDAV and CMIS use. So a lost token can't be turned into an API key, which would outlast it. Still,
guard a token as you would the password: until it's revoked, it signs in to the API as you.
```

- [ ] **Step 3: Developer Guide → How Healdan Extends LogicalDOC**

In `site/content/developer/how-healdan-extends-logicaldoc.md`, replace:

```markdown
- The feature modules build on core: `logicaldoc-webservice` (SOAP through CXF, and REST), `logicaldoc-webdav`,
  `logicaldoc-cmis`, `logicaldoc-dropbox`, `logicaldoc-android` and `logicaldoc-ios`.
```

with:

```markdown
- The feature modules build on core: `logicaldoc-webservice` (REST through CXF; Healdan's `SoapRemover` removes its
  SOAP endpoints), `logicaldoc-webdav`, `logicaldoc-cmis`, `logicaldoc-dropbox`, `logicaldoc-android` and
  `logicaldoc-ios`.
```

- [ ] **Step 4: Developer Guide → Tests and Development Stack**

In `site/content/developer/tests.md`, in the `docker/tests/test-api.sh` row, replace:

```markdown
the API through the image's real filter chain, as a new non-admin user: M2's client flow
```

with:

```markdown
the API through the image's real filter chain, as the non-admin `apiuser` that the `smoke-api-user` setup step makes: LogicalDOC's SOAP services answering 404, M2's client flow
```

In `site/content/developer/development-stack.md`, replace:

```markdown
- matter "1.1 – Smoke Matter", owned by `admin`, with that Documents folder. The Everyone group can read it,
  upload into it and download from it.

Without the variable that step does nothing, so it runs on a later start if you add the overlay then.
```

with:

```markdown
- matter "1.1 – Smoke Matter", owned by `admin`, with that Documents folder. The Everyone group can read it,
  upload into it and download from it;
- the user `apiuser`, not an admin, with a throwaway password, which `docker/tests/test-api.sh` signs in as.

Without the variable those steps do nothing, so they run on a later start if you add the overlay then.
```

The list's earlier items end in `;`, so the matter's line changes its `.` to `;`.

- [ ] **Step 5: Install manual → Sign-In Providers and Known Limitations**

In `site/content/install/sign-in/_index.md`, replace:

```markdown
  one afterwards, so no password signs it in: not the sign-in page's form, WebDAV, CMIS, or LogicalDOC's SOAP and REST
  web services. A scanner, a script or a WebDAV mount that signs in with a password needs an account that isn't linked.
```

with:

```markdown
  one afterwards, so no password signs it in: not the sign-in page's form, WebDAV, CMIS, or LogicalDOC's REST web
  services. A scanner, a script or a WebDAV mount that signs in with a password needs an account that isn't linked.
```

In `site/content/install/known-limitations.md`, replace:

```markdown
  has no Healdan password, so it can't sign in where Healdan asks for one: WebDAV, CMIS, and LogicalDOC's SOAP and
  REST web services. Unlinking the account from its provider gives it a password again.
```

with:

```markdown
  has no Healdan password, so it can't sign in where Healdan asks for one: WebDAV, CMIS, and LogicalDOC's REST web
  services. Unlinking the account from its provider gives it a password again.
```

- [ ] **Step 6: `CLAUDE.md`**

Replace:

```markdown
the feature modules build on core: `webservice` (SOAP via CXF plus REST), `webdav`,
```

with:

```markdown
the feature modules build on core: `webservice` (REST via CXF; Healdan's `SoapRemover` removes its SOAP endpoints), `webdav`,
```

Replace:

```markdown
- Other ways into the server are SOAP and REST at `/services/*` (`logicaldoc-webservice`, REST endpoints in `webservice/rest/endpoint`), WebDAV at `/webdav/*`, and CMIS.
```

with:

```markdown
- Other ways into the server are REST at `/services/rest/*` (`logicaldoc-webservice`, endpoints in `webservice/rest/endpoint`; `healdan-core`'s `SoapRemover` removes the module's SOAP endpoints at startup), WebDAV at `/webdav/*`, and CMIS.
```

Replace:

```markdown
which makes the `smoke-fixture` seed create Store "Smoke", a template, client 1 and matter 1.1 with a Documents folder.
```

with:

```markdown
which makes the `smoke-fixture` seed create Store "Smoke", a template, client 1 and matter 1.1 with a Documents folder, and the `smoke-api-user` seed the non-admin `apiuser` that `test-api.sh` signs in as.
```

Replace:

```markdown
through CE's real filter chain (sign in, file, check out,
```

with:

```markdown
through CE's real filter chain (SOAP's addresses answering 404, sign in, file, check out,
```

Replace:

```markdown
, and §2 of `docs/superpowers/specs/2026-11-02-server-messages-as-text-design.md` for the error popup (`GuiLog.serverError` escapes the server's message, which can name a group, a folder or a file). Keep each edit
```

with:

```markdown
, §2 of `docs/superpowers/specs/2026-11-02-server-messages-as-text-design.md` for the error popup (`GuiLog.serverError` escapes the server's message, which can name a group, a folder or a file), and §7 of `docs/superpowers/specs/2026-11-03-soap-removal-design.md` for SOAP (the Web Service tab's `WebservicesPanel` drops its SOAP address; the endpoints themselves go through the fork-owned `SoapRemover`, with no upstream edit). Keep each edit
```

- [ ] **Step 7: `docs/ROADMAP.md`**

After principle 9 (the line starting `9. **Open-source databases only.**`), add:

```markdown
10. **LogicalDOC's paid clients aren't a goal.** Healdan makes no effort to work with LogicalDOC's paid add-ins, extensions or apps. Their features only raise whether Healdan should build its own, as it did for Office. (owner, 2026-11-03)
```

Replace:

```markdown
- **Leave the old interfaces alone.** The existing SOAP, REST, WebDAV and CMIS interfaces stay as they are.
```

with:

```markdown
- **Leave the old interfaces alone.** The existing SOAP, REST, WebDAV and CMIS interfaces stay as they are. (changed on 2026-11-03: Healdan removed LogicalDOC's SOAP services (owner), in the [SOAP removal spec](superpowers/specs/2026-11-03-soap-removal-design.md); REST, WebDAV and CMIS stay.)
```

- [ ] **Step 8: Capability map and API inventory**

In `docs/reference/logicaldoc-ce-capability-map.md`, replace:

```markdown
- **SOAP mirrors REST** and adds a Security service for user and group CRUD. **REST has no security service.**
```

with:

```markdown
- **SOAP mirrors REST** and adds a Security service for user and group CRUD. **REST has no security service.** Healdan removes the SOAP endpoints, and the Security service with them ([SOAP removal spec](../superpowers/specs/2026-11-03-soap-removal-design.md), 2026-11-03).
```

Replace:

```markdown
  - Users and groups (SOAP only).
```

with:

```markdown
  - Users and groups (SOAP only, which Healdan removes).
```

In `docs/reference/healdan-api-inventory.md`, replace:

```markdown
| `/services/*` | WebserviceServlet (CXF) | SOAP + REST (`/services/rest/*`) | Existing SOAP/REST APIs: out of scope, kept as is. | none | stays as it is |
```

with:

```markdown
| `/services/*` | WebserviceServlet (CXF) | REST (`/services/rest/*`); Healdan removes the SOAP endpoints ([spec](../superpowers/specs/2026-11-03-soap-removal-design.md)) | Existing REST API: out of scope, kept as is. | none | stays as it is |
```

- [ ] **Step 9: Nothing else says SOAP is there**

Run: `grep -rn -i "soap" site/content CLAUDE.md docs/ROADMAP.md docs/reference | grep -v -i "ksoap\|soap removal\|removes\|removed\|SOAP's XML namespaces\|SOAP, CMIS and WebDAV XML\|doesn't offer\|answering 404\|SOAP only, which"`
Expected: only lines that describe CE's history or the capability map's records of past fixes (for example, the R78 and R79 rows). Read each one; any that says SOAP is offered today gets the same treatment as the steps above.

- [ ] **Step 10: Build and check the docs site**

Run each, as the `Docs site` check does:

```bash
python3 -m unittest discover -s .github/scripts/tests -v
```

```bash
hugo --source site --gc --minify --panicOnWarning
```

```bash
python3 .github/scripts/check-site-sources.py site
```

```bash
python3 .github/scripts/check-site-links.py site/public
```

```bash
python3 .github/scripts/check-logicaldoc-doc-links.py && python3 .github/scripts/check-logicaldoc-hosts.py
```

Expected: every one passes. Then `rm -rf site/public` (it isn't tracked).

- [ ] **Step 11: Commit**

```bash
git add site/content CLAUDE.md docs/ROADMAP.md docs/reference
git commit -m "Say in the docs that Healdan doesn't offer LogicalDOC's SOAP services" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Verify the whole change

**Files:** none changed, unless a check fails.

- [ ] **Step 1: Run the quick checks CI runs first**

```bash
python3 .github/scripts/check-fontawesome-icons.py && python3 .github/scripts/check-logicaldoc-doc-links.py && python3 .github/scripts/check-logicaldoc-hosts.py && python3 .github/scripts/check-maven-repositories.py && python3 .github/scripts/check-database-drivers.py
```

Expected: all pass.

- [ ] **Step 2: Run the API tests that touch LogicalDOC's routes**

`SoapRemover` runs in `healdan-api`'s test context too. Install `healdan-core` with its test-jar, then run them:

```bash
mvn -B -ntp -pl healdan-core -DskipTests install
```

```bash
mvn -pl healdan-api test -Dtest='LogicalDocRoutesTest,RouteCoverageTest'
```

Expected: both pass.

- [ ] **Step 3: Run the smoke test on a freshly built image**

It builds the image (the GWT compile included), starts a stack with the smoke and sign-in overlays, and runs `test-api.sh` as step 5c. It takes a while; run it in the background.

```bash
docker/smoke-test.sh
```

Expected: `ALL PASSED`, with `PASS  setup: SOAP's WSDL answers 404` and `PASS  setup: SOAP's storeUser answers 404` in step 5c's output, and the later steps signing in as `apiuser`.

If the 404 checks fail with another status, read the app container's log for `Removed LogicalDOC's SOAP web services`: its absence means `SoapRemover` didn't run (check `HealdanContext`'s scan reaches `com.logicaldoc.healdan.web`); its presence with a 200 means a SOAP endpoint was declared some other way, which `SoapRemoverTest` should then also catch.

- [ ] **Step 4: Check the Web Service tab by hand**

Start a throwaway stack with the smoke overlay, as the Developer Guide's Development Stack page describes:

```bash
LOGICALDOC_IMAGE=healdan-test:local LOGICALDOC_PORT=8090 docker compose -p healdan-test -f compose.yaml -f docker/tests/compose.smoke.yaml up -d --build
```

Sign in at `http://127.0.0.1:8090` as `admin` and open **Administration → Settings → Protocols → Web Service**. Expected: the REST base URL is shown and no SOAP base URL. Also open `http://127.0.0.1:8090/services/`: CXF's list shows the REST services and no SOAP ones. Then remove the stack:

```bash
docker compose -p healdan-test down -v
```

- [ ] **Step 5: Record the build**

In `docs/superpowers/specs/2026-11-03-soap-removal-design.md`, change the status line to say it was built, with the date and the pull request, once the pull request exists. Commit that with the pull request's last change.
