# The Demo Stack and the Docs Site's Remaining Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A throwaway Docker stack that holds an invented small law firm, built by one command, and from it every screenshot on the docs site and the site's last 21 interim pages, so the `interim` shortcode can be deleted and #56 can close.

**Architecture:**
- **The demo firm is data, not code.** `docker/demo/manifest.json` describes the firm: groups, users, Classes, Stores, templates, clients, matters, and a timeline of dated actions. Its documents are text sources in `docker/demo/sources/`, which `docker/demo/build.py` turns into real files in `docker/demo/files/` with the Healdan image's LibreOffice. Both the built files and the manifest are committed.
- **A seed step reads it.** `DemoFixtureSeed` (`demo-fixture`) runs only with `HEALDAN_DEMO_FIXTURE=true`. It refuses a repository that holds documents, or users besides `admin` and `_system` (Decision Log, 2026-10-30), builds the firm's structure (`DemoFirm`), then plays the timeline (`DemoTimeline`) as each user through CE's and Healdan's services. Before each action it checks that the user has the permission the web page would ask for.
- **Backdating.** `Backdater` remembers each table's highest id before an action and dates every newer row with the action's date afterwards. Documents are indexed inside the action that filed or changed them, so the background indexer never re-saves them with today's date.
- **A Compose overlay** (`docker/demo/compose.demo.yaml`) sets the variables and mounts `docker/demo/` read-only.
- **The docs work** follows in three more pull requests: the retaken screenshots, the User Guide's 13 pages with Dropbox hidden by a seed step, and the Admin Guide's 8 pages with the interim shortcode's removal.

**Tech Stack:** Java (the JDK the `Dockerfile` names) and JUnit 4 in `healdan-core`; Spring's `JdbcTemplate`; Jackson databind (already in the WAR through `logicaldoc-core`, declared `provided`); Python 3 standard library for `build.py`; LibreOffice and ImageMagick from the Healdan image; Docker Compose; Hugo 0.168.0 and Hextra for the docs.

**Spec:** [`docs/superpowers/specs/2026-10-29-demo-stack-and-docs-gaps-design.md`](../specs/2026-10-29-demo-stack-and-docs-gaps-design.md), approved by the owner on 2026-10-29. Every § below points into it. Where the two disagree, the spec wins, except for "Spec corrections and calls this plan makes".

**Status:** PR 1 (Tasks 1 to 6, the demo stack) is built, not yet merged. PRs 2 to 4 are not started.

## Global Constraints

Every task's requirements include these.

- **No upstream file changes** (spec §10). Code goes in `healdan-core`; CE's tables change only in their data, never their structure.
- **Never run the demo seed against real data.** The step refuses a repository with documents or with users besides `admin` and `_system`; nothing in this plan weakens that guard.
- **Every name is invented** (spec §4, §6.3): `.example` email domains, Ofcom's drama numbers (01632 960000–960999), no real companies, people, case law, court forms or Land Registry forms. British English. Every word written fresh, never copied from LogicalDOC's docs or anywhere else.
- **No real hostnames, internal addresses or the owner's surname** in any file, screenshot, commit or PR. A server address in a screenshot reads `healdan.example`; a computer name is neutral or cropped out.
- **The demo password is `Demo-Firm-2026`.** It meets CE's default policy (`context.properties` lines 45–53: 8+ characters, 2 uppercase, 2 lowercase, a digit, a symbol, no 4-character sequence, no character more than 3 times).
- **Seed steps never change once shipped** (CLAUDE.md). `demo-fixture` and `hide-dropbox` are new names, added to `SeedRunner.ORDER`.
- **Docs** follow the Writing Docs page (`site/content/developer/writing-docs.md`): Title Case for titles, headings and labels; sentence case, British spelling and Healdan's glossary in the body; quote a front-matter value containing ` #`, and end a heading that ends in `#` with `\#`. Pages describe `master` and only what Healdan does.
- **Screenshots** come from the demo stack: light mode, a true 1280 × 800 browser, cropped to the part that matters, PNG beside the page in a page bundle, each with alt text. Web screenshots are taken with headless Chrome driven over its DevTools protocol by a throwaway script in the scratchpad, as the docs-site build did (its plan's decision log, 2026-10-23, Task 10): the browser pane scales pages down.
- **Java** matches `healdan-core`: tabs, Javadoc that cites the spec (`demo spec §n`), `(call)` where the plan decided something, one public type per file.
- **Commits:** one per task, in the repo's style: an imperative subject, a body that says why, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Push and open a PR only with the owner's go-ahead. `gh` commands take `--repo Healdan-Project/healdan`.

## Review Focus

The risks a unit test doesn't reach. Each task names the ones it must check.

1. **The demo never touches real data.** The guard refuses any repository with a document in it or a user besides `admin` and `_system`, and the overlay is the only way the variable gets set.
2. **The demo shows only what Healdan allows.** Every action passes the permission check the web page would make, and Healdan's own rules (filing, P1, P2, Subclass) run on it unchanged.
3. **The dates hold after the stack settles.** Ten minutes after the first start, the Documents list, History, Versions, Notes, Messages and Recent Matters still show the timeline's dates, not today's.
4. **Nothing real leaks into a screenshot**: no hostname, IP address, `/Users/…` path, real name or real email address.
5. **Each page is true of `master`.** Every label, menu path and behaviour a page states was seen on the demo stack or read in the code, and the page says nothing about features Healdan doesn't have.

## Spec Corrections and Calls This Plan Makes

The owner hears about these when the plan is handed over. Task 6 folds 1–6 into the spec.

1. **The seed checks permissions itself** (spec §5.2 said the services do). Inside a seed every actor counts as an admin (`FilingRules.isAdmin`, the `SEED` operation), and `DocumentManager.create` checks where a document goes but not who files it. So `DemoTimeline` checks, before each action, the permission the web page asks for (Task 4's table), and fails on a refusal naming the user, the permission and the target.
2. **Each document is indexed inside its own action.** `DocumentManager.index` saves the document, which resets its last-modified date, and adds an Indexed history entry. Indexed by the background task, every document would show today's date.
3. **Backdating by watermark.** `Backdater` dates every row newer than the last action's highest id, in each table the demo writes, plus the last-modified date of a document an action changed. `EmailDocumentListener` forces every new document's creation date to now, so the creation date can't be set before the create.
4. **The guard refuses any document at all,** not only those outside the Templates Store. The filing rule keeps documents out of the Templates Store, so the two are the same, and the simpler query can't be wrong. A user besides CE's `admin` and `_system` is refused too (Decision Log, 2026-10-30).
5. **An email's dates move with its filing.** The seed rewrites a filed `.eml`'s `Date:` header to 25 minutes before its filing, and its `Received:` header to 5 minutes before, so its Sent and Received properties agree with the timeline.
6. **Messages last 60 days.** CE purges a message after its `dateScope` (10 days by default), so the demo's are given 60.
7. **Users are also in CE's `author` group,** which holds the menus everyday users need (`ld_menu_acl`); a new group holds none. Sam Okafor is in `admin`.
8. **998.3's restriction is an ACL on its workspace.** A matter whose manifest entry has an `acl` gets that ACL in place of the template's, plus every permission for the owner's personal group, as an admin would set it in the web page.
9. **The Classes are Docs (already there), Correspondence, Agreements, Court Documents, Finance and Firm,** with Subclasses (Task 5). Healdan ships only Docs.
10. **`docker/demo/` goes in `.dockerignore`.** The image doesn't need it, and its PDFs would bloat every build context.
11. **The timeline's day 0 is refused.** Every action is in the past, so a demo built at 08:00 never shows something filed at 14:00 today.

## File Structure

| File | Responsibility |
|---|---|
| `healdan-core/pom.xml` | Declares `jackson-databind`, `provided` |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/DemoFixtureSeed.java` | The step: flag, guard, wiring |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` | `demo-fixture` and `hide-dropbox` in `ORDER` |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoManifest.java` | The manifest's records, and reading it |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoClock.java` | A manifest day and time to a date |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/Backdater.java` | Dating what an action created |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoFirm.java` | Groups, users, Classes, Stores, templates, clients, matters; lookups |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoTimeline.java` | The dated actions, with their permission checks |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/EmailDates.java` | Moving an email's dates |
| `healdan-core/src/main/java/com/logicaldoc/healdan/seed/HideDropboxSeed.java` | PR 3: withdraws Dropbox's menu |
| `healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo/*Test.java` | Unit tests per class |
| `healdan-core/src/test/java/com/logicaldoc/healdan/seed/DemoFixtureSeedTest.java` | The step end to end, on the test manifest and the real one |
| `healdan-core/src/test/resources/demo/` | A small manifest and files for the tests |
| `docker/demo/manifest.json` | The firm (spec §4) |
| `docker/demo/sources/` | One text source per document |
| `docker/demo/sources/letterhead.css` | The shared letterhead |
| `docker/demo/build.py` | Sources to files, with the image's LibreOffice |
| `docker/demo/files/` | The built files, committed |
| `docker/demo/compose.demo.yaml` | The overlay |
| `docker/demo/README.md` | A short pointer to the Developer Guide |

---

## PR 1: The Demo Stack

This branch (`claude/docs-gaps-demo-seed-aa8579`) already holds the spec. PR 1 adds this plan and Tasks 1–6.

### Task 1: The Manifest and the Clock

**Files:**
- Modify: `healdan-core/pom.xml` (dependencies, after `logicaldoc-core` at ~86–91)
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoManifest.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoClock.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo/DemoManifestTest.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo/DemoClockTest.java`

**Interfaces:**
- Produces: `DemoManifest` with nested records `GroupSpec`, `UserSpec`, `ClassSpec`, `StoreSpec`, `FolderSpec`, `SearchFolderSpec`, `TemplateSpec`, `ClientSpec`, `MatterSpec`, `Step`; `static DemoManifest read(Path file) throws IOException`. `DemoClock(LocalDate today, ZoneId zone)`, `Date at(int day, String time)`.

- [ ] **Step 1: Declare Jackson.** It's already in the WAR through `logicaldoc-core`'s `geoip2`, and `logicaldoc-parent` manages its version (`${jackson.version}`, ~1500). Add to `healdan-core/pom.xml`, after the `logicaldoc-core` dependency:

```xml
		<dependency>
			<groupId>com.fasterxml.jackson.core</groupId>
			<artifactId>jackson-databind</artifactId>
			<scope>provided</scope>
		</dependency>
```

- [ ] **Step 2: Write the failing tests**

`DemoManifestTest.java`:

```java
package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;

public class DemoManifestTest {

	private static DemoManifest read(String json) throws Exception {
		Path file = Files.createTempFile("manifest-", ".json");
		Files.writeString(file, json);
		try {
			return DemoManifest.read(file);
		} finally {
			Files.delete(file);
		}
	}

	@Test
	public void testMissingListsReadAsEmpty() throws Exception {
		DemoManifest manifest = read("{}");
		assertTrue(manifest.groups().isEmpty());
		assertTrue(manifest.users().isEmpty());
		assertTrue(manifest.timeline().isEmpty());
	}

	@Test
	public void testAStepReadsItsKeywordFields() throws Exception {
		DemoManifest manifest = read("""
				{"timeline": [{"day": -3, "time": "14:30", "as": "pnair", "do": "file", "class": "Correspondence",
				"tags": ["charter"], "release": true}]}
				""");
		Step step = manifest.timeline().get(0);
		assertEquals(-3, step.day());
		assertEquals("14:30", step.time());
		assertEquals("pnair", step.as());
		assertEquals("file", step.action());
		assertEquals("Correspondence", step.docClass());
		assertEquals("charter", step.tags().get(0));
		assertTrue(step.release());
		assertTrue(step.to().isEmpty());
	}

	@Test
	public void testAFolderWithoutAnAclInherits() throws Exception {
		DemoManifest manifest = read("""
				{"templates": [{"name": "Litigation", "folders": [{"name": "Drafts"}]}]}
				""");
		assertNull(manifest.templates().get(0).folders().get(0).acl());
		assertTrue(manifest.templates().get(0).searchFolders().isEmpty());
	}

	@Test
	public void testAMisspeltFieldIsRefused() {
		assertThrows(UnrecognizedPropertyException.class, () -> read("{\"timeline\": [{\"dya\": -3}]}"));
	}

	@Test
	public void testAMatterWithoutAnAclKeepsItsTemplates() throws Exception {
		DemoManifest manifest = read("{\"matters\": [{\"ref\": \"1001.1\", \"name\": \"Charter Dispute\"}]}");
		assertNull(manifest.matters().get(0).acl());
	}
}
```

`DemoClockTest.java`:

```java
package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;

import org.junit.Test;

public class DemoClockTest {

	private static final ZoneId LONDON = ZoneId.of("Europe/London");

	private final DemoClock clock = new DemoClock(LocalDate.of(2026, 10, 29), LONDON);

	private static Date date(int y, int m, int d, int h, int min) {
		return Date.from(LocalDateTime.of(y, m, d, h, min).atZone(LONDON).toInstant());
	}

	@Test
	public void testADayAndTime() {
		assertEquals(date(2026, 10, 28, 14, 30), clock.at(-1, "14:30"));
		assertEquals(date(2026, 9, 17, 8, 5), clock.at(-42, "08:05"));
	}

	@Test
	public void testNoTimeIsNineInTheMorning() {
		assertEquals(date(2026, 10, 26, 9, 0), clock.at(-3, null));
	}

	@Test
	public void testTodayAndLaterAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> clock.at(0, "08:00"));
		assertThrows(IllegalArgumentException.class, () -> clock.at(2, null));
	}

	@Test
	public void testABadTimeIsRefused() {
		assertThrows(DateTimeParseException.class, () -> clock.at(-1, "2pm"));
	}
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='DemoManifestTest,DemoClockTest'`
Expected: compilation failure, `DemoManifest` and `DemoClock` not found.

- [ ] **Step 4: Write `DemoManifest`**

```java
package com.logicaldoc.healdan.seed.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The demo firm, as {@code docker/demo/manifest.json} describes it (demo spec
 * §4, §5.2). A list the manifest leaves out reads as empty; a field it
 * doesn't know is refused, so a misspelling fails the seed rather than being
 * ignored.
 * <p>
 * An ACL is a map from a group's name to the names of {@code Permission}s,
 * such as {@code {"Everyone": ["READ"]}}.
 */
public record DemoManifest(List<GroupSpec> groups, List<UserSpec> users, List<ClassSpec> classes,
		List<StoreSpec> stores, List<TemplateSpec> templates, List<ClientSpec> clients, List<MatterSpec> matters,
		List<Step> timeline) {

	public DemoManifest {
		groups = orEmpty(groups);
		users = orEmpty(users);
		classes = orEmpty(classes);
		stores = orEmpty(stores);
		templates = orEmpty(templates);
		clients = orEmpty(clients);
		matters = orEmpty(matters);
		timeline = orEmpty(timeline);
	}

	public record GroupSpec(String name, String description) {
	}

	/** @param groups the groups besides Everyone, CE's included */
	public record UserSpec(String username, String firstName, String name, String email, List<String> groups) {
		public UserSpec {
			groups = orEmpty(groups);
		}
	}

	/** A Class, and the Subclasses that belong to it */
	public record ClassSpec(String name, List<String> subclasses) {
		public ClassSpec {
			subclasses = orEmpty(subclasses);
		}
	}

	public record StoreSpec(String name, Map<String, List<String>> acl) {
		public StoreSpec {
			acl = orEmpty(acl);
		}
	}

	/** @param acl null when the folder inherits its parent's */
	public record FolderSpec(String name, Map<String, List<String>> acl, List<FolderSpec> folders) {
		public FolderSpec {
			folders = orEmpty(folders);
		}
	}

	/** @param kind {@code ALL}, {@code DOCUMENTS} or {@code EMAILS}; null for all */
	public record SearchFolderSpec(String name, int days, boolean onlyMine, String kind,
			@JsonProperty("class") String docClass, String subclass) {
	}

	public record TemplateSpec(String name, Map<String, List<String>> acl, List<FolderSpec> folders,
			List<SearchFolderSpec> searchFolders) {
		public TemplateSpec {
			acl = orEmpty(acl);
			folders = orEmpty(folders);
			searchFolders = orEmpty(searchFolders);
		}
	}

	public record ClientSpec(long number, String name) {
	}

	/**
	 * @param ref the matter's number, such as {@code 998.3}
	 * @param acl null to keep the template's; otherwise the workspace's ACL,
	 *        to which the owner's personal group is added with every
	 *        permission
	 */
	public record MatterSpec(String ref, String name, String description, String store, String owner,
			String template, Map<String, List<String>> acl) {
	}

	/**
	 * One action on the timeline (Task 4 of the plan lists what each needs).
	 *
	 * @param day days before the stack's first start, so -1 is the day
	 *        before
	 * @param time {@code HH:mm}, or null for 09:00
	 * @param as the username of the person acting
	 * @param action what they do
	 * @param key a name later steps use for the document this one files
	 * @param doc the key of the document this step acts on
	 * @param target the key of the second document, for a link
	 */
	public record Step(int day, String time, String as, @JsonProperty("do") String action, String key,
			String matter, String folder, String file, String name, String doc, String target, String author,
			String typist, @JsonProperty("class") String docClass, String subclass, String comments,
			List<String> tags, boolean release, String text, String subject, List<String> to, String password) {
		public Step {
			tags = orEmpty(tags);
			to = orEmpty(to);
		}
	}

	public static DemoManifest read(Path file) throws IOException {
		return new ObjectMapper().readValue(file.toFile(), DemoManifest.class);
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	private static <K, V> Map<K, V> orEmpty(Map<K, V> map) {
		return map == null ? Map.of() : map;
	}
}
```

- [ ] **Step 5: Write `DemoClock`**

```java
package com.logicaldoc.healdan.seed.demo;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Turns a manifest's day and time into a date, counted back from the day the
 * stack first starts, so the demo always looks a few weeks old (demo spec
 * §4.6).
 */
public final class DemoClock {

	private static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);

	private final LocalDate today;

	private final ZoneId zone;

	public DemoClock(LocalDate today, ZoneId zone) {
		this.today = today;
		this.zone = zone;
	}

	/**
	 * @param day days before today, so -1 is yesterday. Today and later are
	 *        refused, so nothing is dated after the stack started (call)
	 * @param time {@code HH:mm}, or null for 09:00
	 *
	 * @return the date
	 */
	public Date at(int day, String time) {
		if (day >= 0)
			throw new IllegalArgumentException(
					"Day %d isn't in the past: the timeline's days count back from -1".formatted(day));
		LocalTime at = time == null ? DEFAULT_TIME : LocalTime.parse(time);
		return Date.from(today.plusDays(day).atTime(at).atZone(zone).toInstant());
	}
}
```

- [ ] **Step 6: Run the tests to make sure they pass**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='DemoManifestTest,DemoClockTest'`
Expected: PASS, 9 tests.

- [ ] **Step 7: Commit**

```bash
git add healdan-core/pom.xml healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo
git commit -m "Read the demo firm's manifest and its dates" -m "The demo fixture describes the firm as data, so changing the demo never needs Java. Days count back from the first start, so the demo always looks a few weeks old.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: The Backdater and Email Dates

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/Backdater.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/EmailDates.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo/BackdaterTest.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo/EmailDatesTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `Backdater(JdbcTemplate jdbc)`, `void stamp(Date when)`, `void touch(long docId, Date when, boolean newFile)`; `EmailDates.restamp(byte[] eml, Date filed): byte[]`, with `SENT_BEFORE = Duration.ofMinutes(25)` and `RECEIVED_BEFORE = Duration.ofMinutes(5)`.

- [ ] **Step 1: Check the tables and columns.** Confirm each table and date column in `Backdater.TABLES` (Step 4) against `logicaldoc-core/src/main/resources/sql/logicaldoc-core.sql` and `healdan-core/src/main/resources/healdan/db/migration/hsqldb/V1__*.sql`, and that each table's key is `ld_id`. Fix the map, not the SQL, if one differs, and note it in the decision log.

- [ ] **Step 2: Write the failing tests**

`BackdaterTest.java`:

```java
package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.sql.Timestamp;
import java.util.Date;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientService;
import com.logicaldoc.util.spring.Context;

public class BackdaterTest extends AbstractHealdanTestCase {

	private static final Date LONG_AGO = Timestamp.valueOf("2026-09-17 08:30:00");

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private Client client(long number) throws Exception {
		User admin = UserDAO.get().findByUsername("admin");
		Client[] made = new Client[1];
		HealdanOperation.run(Kind.SEED, () -> made[0] = ClientService.get().create(number, "Client " + number, admin));
		return made[0];
	}

	private Timestamp created(Client client) {
		return jdbc().queryForObject("select ld_creation from hd_client where ld_id = ?", Timestamp.class,
				client.getId());
	}

	@Test
	public void testDatesWhatWasCreatedSinceTheLastStamp() throws Exception {
		Client before = client(901);
		Backdater backdater = new Backdater(jdbc());
		Client during = client(902);

		backdater.stamp(LONG_AGO);

		assertEquals(LONG_AGO.getTime(), created(during).getTime());
		assertNotEquals(LONG_AGO.getTime(), created(before).getTime());
	}

	@Test
	public void testAStampMovesTheMark() throws Exception {
		Backdater backdater = new Backdater(jdbc());
		Client first = client(903);
		backdater.stamp(LONG_AGO);
		Client second = client(904);
		Date later = Timestamp.valueOf("2026-10-01 10:00:00");

		backdater.stamp(later);

		assertEquals(LONG_AGO.getTime(), created(first).getTime());
		assertEquals(later.getTime(), created(second).getTime());
	}
}
```

`touch` is covered end to end by Task 4's test, which checks a checked-in document's last-modified date.

`EmailDatesTest.java`:

```java
package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Properties;

import org.junit.Test;

import jakarta.mail.Session;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeMessage;

public class EmailDatesTest {

	private static final String EML = """
			From: Ines Moreau <ines.moreau@saltmarsh-shipping.example>\r
			To: Tom Reeve <tom.reeve@ashcombe-reeve.example>\r
			Subject: Laytime\r
			Date: Mon, 1 Jan 2024 10:00:00 +0000\r
			Received: from relay.example by mx.example; Mon, 1 Jan 2024 10:01:00 +0000\r
			MIME-Version: 1.0\r
			Content-Type: text/plain; charset=UTF-8\r
			\r
			Date: this line is the body, and stays.\r
			""";

	private static final Date FILED = Timestamp.valueOf("2026-10-14 11:00:00");

	private static MimeMessage parse(byte[] eml) throws Exception {
		return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml));
	}

	@Test
	public void testSentAndReceivedComeBeforeTheFiling() throws Exception {
		MimeMessage message = parse(EmailDates.restamp(EML.getBytes(StandardCharsets.US_ASCII), FILED));

		assertEquals(FILED.getTime() - EmailDates.SENT_BEFORE.toMillis(), message.getSentDate().getTime());
		String[] received = message.getHeader("Received");
		assertEquals(1, received.length);
		Date when = new MailDateFormat().parse(received[0].substring(received[0].lastIndexOf(';') + 1).trim());
		assertEquals(FILED.getTime() - EmailDates.RECEIVED_BEFORE.toMillis(), when.getTime());
	}

	@Test
	public void testTheBodyIsUntouched() throws Exception {
		String out = new String(EmailDates.restamp(EML.getBytes(StandardCharsets.US_ASCII), FILED),
				StandardCharsets.US_ASCII);
		assertTrue(out.endsWith("\r\n\r\nDate: this line is the body, and stays.\r\n"));
		assertEquals("Laytime", parse(out.getBytes(StandardCharsets.US_ASCII)).getSubject());
	}
}
```

If `jakarta.mail` isn't on `healdan-core`'s test classpath, use the package `EmailFields` imports (`healdan-core/.../email/EmailFields.java`) and say so in the decision log.

- [ ] **Step 3: Run them to make sure they fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='BackdaterTest,EmailDatesTest'`
Expected: compilation failure, `Backdater` and `EmailDates` not found.

- [ ] **Step 4: Write `Backdater`**

```java
package com.logicaldoc.healdan.seed.demo;

import static java.util.Map.entry;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dates what the demo fixture creates (demo spec §5.2; plan call 3). It
 * remembers each table's highest id; {@link #stamp} dates every newer row
 * and moves the mark. Ids grow within a run, so each action's rows get that
 * action's date.
 * <p>
 * For the demo only: nothing else writes dates behind CE's back.
 */
public final class Backdater {

	/**
	 * Each table the demo writes, and its date columns
	 * ({@code sql/logicaldoc-core.sql}, Healdan's V1)
	 */
	static final Map<String, List<String>> TABLES = Map.ofEntries(
			entry("ld_document", List.of("ld_lastmodified", "ld_date", "ld_creation")),
			entry("ld_version", List.of("ld_lastmodified", "ld_date", "ld_creation", "ld_versiondate")),
			entry("ld_history", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_folder", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_folder_history", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_note", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_bookmark", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_ticket", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_link", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_systemmessage", List.of("ld_lastmodified", "ld_creation", "ld_sentdate", "ld_lastnotified")),
			entry("ld_user", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_user_history", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_group", List.of("ld_lastmodified", "ld_creation")),
			entry("hd_client", List.of("ld_lastmodified", "ld_creation")),
			entry("hd_matter", List.of("ld_lastmodified", "ld_creation")));

	private final JdbcTemplate jdbc;

	private final Map<String, Long> marks = new HashMap<>();

	public Backdater(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		mark();
	}

	/**
	 * Dates every row created since the last stamp, or since this backdater
	 * was made, then marks where the next action starts
	 */
	public void stamp(Date when) {
		Timestamp at = new Timestamp(when.getTime());
		for (Map.Entry<String, List<String>> table : TABLES.entrySet()) {
			List<String> columns = table.getValue();
			String set = columns.stream().map(c -> c + " = ?").collect(Collectors.joining(", "));
			List<Object> args = new ArrayList<>();
			columns.forEach(c -> args.add(at));
			args.add(marks.get(table.getKey()));
			jdbc.update("update %s set %s where ld_id > ?".formatted(table.getKey(), set), args.toArray());
		}
		mark();
	}

	/**
	 * Dates a document an action changed, whose save set its last-modified
	 * date to now
	 *
	 * @param newFile true when the action saved a new file, which also moves
	 *        the document's own date, as a check-in does
	 */
	public void touch(long docId, Date when, boolean newFile) {
		Timestamp at = new Timestamp(when.getTime());
		if (newFile)
			jdbc.update("update ld_document set ld_lastmodified = ?, ld_date = ? where ld_id = ?", at, at, docId);
		else
			jdbc.update("update ld_document set ld_lastmodified = ? where ld_id = ?", at, docId);
	}

	private void mark() {
		for (String table : TABLES.keySet())
			marks.put(table, jdbc.queryForObject("select coalesce(max(ld_id), 0) from " + table, Long.class));
	}
}
```

- [ ] **Step 5: Write `EmailDates`**

```java
package com.logicaldoc.healdan.seed.demo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves an email's dates to just before the demo files it, so its Sent and
 * Received properties agree with the timeline (plan call 5).
 * {@code EmailFields} reads Sent from {@code Date:} and Received from the
 * first {@code Received:} header.
 */
final class EmailDates {

	static final Duration SENT_BEFORE = Duration.ofMinutes(25);

	static final Duration RECEIVED_BEFORE = Duration.ofMinutes(5);

	private static final Pattern HEADERS_END = Pattern.compile("\\r?\\n\\r?\\n");

	/** A header line and its folded continuation lines */
	private static final Pattern RECEIVED = Pattern.compile("(?mi)^Received:.*(?:\\r?\\n[ \\t].*)*\\r?\\n");

	private static final Pattern DATE = Pattern.compile("(?mi)^Date:.*$");

	private EmailDates() {
	}

	static byte[] restamp(byte[] eml, Date filed) {
		// Headers are ASCII; ISO-8859-1 keeps every other byte as it was
		String text = new String(eml, StandardCharsets.ISO_8859_1);
		Matcher end = HEADERS_END.matcher(text);
		if (!end.find())
			throw new IllegalArgumentException("The email has no blank line after its headers");
		String newline = text.contains("\r\n") ? "\r\n" : "\n";
		String headers = text.substring(0, end.start()) + newline;
		String rest = text.substring(end.start() + newline.length());

		headers = RECEIVED.matcher(headers).replaceAll("");
		String sent = rfc1123(filed, SENT_BEFORE);
		Matcher date = DATE.matcher(headers);
		headers = date.find() ? date.replaceFirst(Matcher.quoteReplacement("Date: " + sent))
				: "Date: " + sent + newline + headers;
		headers = "Received: by mail.demo.example; " + rfc1123(filed, RECEIVED_BEFORE) + newline + headers;
		return (headers + rest).getBytes(StandardCharsets.ISO_8859_1);
	}

	private static String rfc1123(Date filed, Duration before) {
		return DateTimeFormatter.RFC_1123_DATE_TIME
				.format(ZonedDateTime.ofInstant(filed.toInstant().minus(before), ZoneId.systemDefault()));
	}
}
```

- [ ] **Step 6: Run the tests to make sure they pass**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='BackdaterTest,EmailDatesTest'`
Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo
git commit -m "Date the demo's rows and emails after the timeline" -m "CE stamps every row with now, so the demo dates each action's rows afterwards, by the highest id before the action. Emails' Sent and Received move with their filing.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: The Firm's Structure and the Step

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoFirm.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/DemoFixtureSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java:23-26` (`ORDER`)
- Create: `healdan-core/src/test/resources/demo/manifest.json` (structure only in this task; Task 4 adds the timeline)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/DemoFixtureSeedTest.java`

**Interfaces:**
- Consumes: `DemoManifest`, `DemoClock`, `Backdater` (Tasks 1–2).
- Produces: `DemoFirm(DemoManifest manifest, String password)`, `DemoFirm build() throws Exception`, `User user(String username)`, `User admin()`, `Folder workspace(String ref)`, `Folder folder(String ref, String path)`; `DemoFixtureSeed(UnaryOperator<String> env, Supplier<LocalDate> today)`, constants `ENV`, `DIR_ENV`, `PASSWORD_ENV`. Task 4 adds the timeline call to `DemoFixtureSeed.run`.

- [ ] **Step 1: Write the test manifest's structure**

`healdan-core/src/test/resources/demo/manifest.json`:

```json
{
  "groups": [
    {"name": "Partners", "description": "The firm's partners"}
  ],
  "users": [
    {"username": "hashcombe", "firstName": "Helen", "name": "Ashcombe",
     "email": "helen.ashcombe@ashcombe-reeve.example", "groups": ["Partners", "author"]},
    {"username": "pnair", "firstName": "Priya", "name": "Nair",
     "email": "priya.nair@ashcombe-reeve.example", "groups": ["author"]}
  ],
  "classes": [
    {"name": "Correspondence", "subclasses": ["Letter", "Email"]}
  ],
  "stores": [
    {"name": "Client Work", "acl": {"Everyone": ["READ"]}}
  ],
  "templates": [
    {"name": "Litigation",
     "acl": {"Everyone": ["READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD", "RENAME", "MOVE", "DELETE"]},
     "folders": [
       {"name": "Correspondence"},
       {"name": "Privileged", "acl": {"Partners": ["READ", "PREVIEW", "DOWNLOAD", "WRITE"]}}
     ],
     "searchFolders": [
       {"name": "My Recent Docs Here", "days": 14, "onlyMine": true, "kind": "ALL"}
     ]}
  ],
  "clients": [
    {"number": 1001, "name": "Saltmarsh Shipping Ltd"}
  ],
  "matters": [
    {"ref": "1001.1", "name": "Charter Dispute – MV Northern Star", "description": "Owners' claim for demurrage",
     "store": "Client Work", "owner": "hashcombe", "template": "Litigation"},
    {"ref": "1001.2", "name": "Vessel Sale", "store": "Client Work", "owner": "hashcombe", "template": "Litigation",
     "acl": {"Partners": ["READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD", "RENAME", "MOVE", "DELETE"]}}
  ],
  "timeline": []
}
```

Check `SearchFolderRule`'s validation for `days` (`healdan-core/.../searchfolder/SearchFolderRule.java`, `SearchFolderService.create`): if 14 isn't allowed, use the nearest allowed value here and in Task 5.

- [ ] **Step 2: Write the failing test**

`DemoFixtureSeedTest.java` (in the `seed` package, beside `SmokeFixtureSeedTest`, so it can reach the package-private constructor and `SeedRunner.ORDER`):

```java
package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class DemoFixtureSeedTest extends AbstractHealdanTestCase {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

	static final String PASSWORD = "Demo-Firm-2026";

	static final String TEST_DIR = "src/test/resources/demo";

	static DemoFixtureSeed seed(String dir) {
		Map<String, String> env = new HashMap<>();
		env.put(DemoFixtureSeed.ENV, "true");
		env.put(DemoFixtureSeed.DIR_ENV, dir);
		env.put(DemoFixtureSeed.PASSWORD_ENV, PASSWORD);
		return new DemoFixtureSeed(env::get, () -> TODAY);
	}

	static void run(DemoFixtureSeed seed) throws Exception {
		HealdanOperation.run(Kind.SEED, seed::run);
	}

	static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	static Matter matter(long client, long number) throws Exception {
		return MatterDAO.get().findByClientAndNumber(ClientDAO.get().findByNumber(client).getId(), number);
	}

	private static Set<Permission> granted(Folder folder, String group) throws Exception {
		long groupId = GroupDAO.get().findByName(group, Tenant.DEFAULT_ID).getId();
		FolderAccessControlEntry ace = FolderDAO.get().findById(folder.getId(), true).getAccessControlEntry(groupId);
		assertNotNull(group, ace);
		return ace.grantedPermissions();
	}

	@Test
	public void testOffWithoutTheFlag() {
		assertFalse(new DemoFixtureSeed(name -> null, () -> TODAY).enabled());
		assertEquals("demo-fixture", seed(TEST_DIR).name());
		assertTrue(seed(TEST_DIR).enabled());
		assertTrue(SeedRunner.ORDER.contains("demo-fixture"));
	}

	@Test
	public void testNeedsItsFolder() {
		DemoFixtureSeed noDir = new DemoFixtureSeed(name -> DemoFixtureSeed.ENV.equals(name) ? "true" : null,
				() -> TODAY);
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(noDir));
		assertTrue(e.getMessage(), e.getMessage().contains(DemoFixtureSeed.DIR_ENV));
	}

	@Test
	public void testBuildsTheFirm() throws Exception {
		run(seed(TEST_DIR));

		User pnair = UserDAO.get().findByUsername("pnair");
		assertEquals("Priya", pnair.getFirstName());
		assertTrue(pnair.isMemberOf("author"));
		assertTrue(pnair.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		User hashcombe = UserDAO.get().findByUsername("hashcombe");
		assertTrue(hashcombe.isMemberOf("Partners"));

		long setId = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true)
				.getId();
		List<String> classes = AttributeOptionDAO.get().findByAttribute(setId, HealdanConstants.ATTR_DOCCLASS)
				.stream().map(AttributeOption::getValue).toList();
		assertTrue(classes.toString(), classes.containsAll(List.of("Docs", "Correspondence")));
		assertEquals(Set.of("Letter", "Email"),
				AttributeOptionDAO.get()
						.findByAttributeAndCategory(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Correspondence")
						.stream().map(AttributeOption::getValue).collect(Collectors.toSet()));

		FolderDAO dao = FolderDAO.get();
		Folder store = dao.findByName(dao.findRoot(Tenant.DEFAULT_ID), "Client Work", Tenant.DEFAULT_ID, true).get(0);
		assertEquals(Folder.TYPE_WORKSPACE, store.getType());
		assertEquals(Set.of(Permission.READ), granted(store, HealdanConstants.EVERYONE_GROUP));

		Folder templates = dao.findById(HealdanSettings.get().templatesStoreId());
		Folder template = dao.findByName(templates, "Litigation", Tenant.DEFAULT_ID, true).get(0);
		Folder privileged = dao.findByName(template, "Privileged", Tenant.DEFAULT_ID, true).get(0);
		assertEquals(Set.of(Permission.READ, Permission.PREVIEW, Permission.DOWNLOAD, Permission.WRITE),
				granted(privileged, "Partners"));
		assertFalse(dao.findByName(template, "My Recent Docs Here", Tenant.DEFAULT_ID, true).isEmpty());

		Matter charter = matter(1001, 1);
		assertEquals("Charter Dispute – MV Northern Star", charter.getName());
		Matter sale = matter(1001, 2);
		assertTrue(dao.isReadAllowed(charter.getFolderId(), pnair.getId()));
		assertFalse(dao.isReadAllowed(sale.getFolderId(), pnair.getId()));
		assertTrue(dao.isReadAllowed(sale.getFolderId(), hashcombe.getId()));
	}

	@Test
	public void testDatesTheStructureBeforeTheTimeline() throws Exception {
		run(seed(TEST_DIR));
		Timestamp created = jdbc().queryForObject("select ld_creation from hd_client where hd_number = 1001",
				Timestamp.class);
		assertTrue(created.toString(), created.toLocalDateTime().toLocalDate().isBefore(TODAY));
	}

	@Test
	public void testAnUnknownGroupIsRefused() throws Exception {
		Path dir = tempDir.toPath().resolve("bad-group");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("manifest.json"), """
				{"users": [{"username": "x", "firstName": "X", "name": "Y", "email": "x@demo.example",
				"groups": ["Nobody"]}]}
				""");
		Exception e = assertThrows(Exception.class, () -> run(seed(dir.toString())));
		assertTrue(e.getMessage(), e.getMessage().contains("Nobody"));
	}
}
```

`tempDir` is `AbstractTestCase`'s (a `File` under `target/tmp`); if it's a `Path` there, drop `.toPath()`.

- [ ] **Step 3: Run it to make sure it fails**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=DemoFixtureSeedTest`
Expected: compilation failure, `DemoFixtureSeed` not found.

- [ ] **Step 4: Write `DemoFirm`**

```java
package com.logicaldoc.healdan.seed.demo;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientService;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderService;
import com.logicaldoc.healdan.seed.demo.DemoManifest.ClassSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.ClientSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.FolderSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.GroupSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.MatterSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.SearchFolderSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.StoreSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.TemplateSpec;
import com.logicaldoc.healdan.seed.demo.DemoManifest.UserSpec;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * The demo firm's structure (demo spec §4.1–§4.4): its groups, users,
 * Classes, Stores, templates, clients and matters, made as an admin would
 * make them in the web page. Then the lookups the timeline uses.
 */
public final class DemoFirm {

	private final DemoManifest manifest;

	private final String password;

	private final Map<String, User> users = new HashMap<>();

	private final Map<String, Folder> stores = new HashMap<>();

	private final Map<String, Folder> templates = new HashMap<>();

	private final Map<String, Matter> matters = new HashMap<>();

	private User admin;

	public DemoFirm(DemoManifest manifest, String password) {
		this.manifest = manifest;
		this.password = password;
	}

	public DemoFirm build() throws Exception {
		admin = UserDAO.get().findByUsername("admin");
		for (GroupSpec spec : manifest.groups())
			createGroup(spec);
		for (UserSpec spec : manifest.users())
			createUser(spec);
		createClasses();
		for (StoreSpec spec : manifest.stores())
			createStore(spec);
		for (TemplateSpec spec : manifest.templates())
			createTemplate(spec);
		createMatters();
		return this;
	}

	public User admin() {
		return admin;
	}

	public User user(String username) {
		User user = users.get(username);
		if (user == null)
			throw new IllegalArgumentException("The manifest has no user %s".formatted(username));
		return user;
	}

	public Folder workspace(String ref) throws PersistenceException {
		Matter matter = matters.get(ref);
		if (matter == null)
			throw new IllegalArgumentException("The manifest has no matter %s".formatted(ref));
		return FolderDAO.get().findById(matter.getFolderId());
	}

	/**
	 * @param path a folder inside the matter's workspace, such as
	 *        {@code Correspondence/Superseded}
	 */
	public Folder folder(String ref, String path) throws PersistenceException {
		Folder folder = workspace(ref);
		for (String name : path.split("/")) {
			List<Folder> found = FolderDAO.get().findByName(folder, name, Tenant.DEFAULT_ID, true);
			if (found.isEmpty())
				throw new IllegalArgumentException("Matter %s has no folder %s".formatted(ref, path));
			folder = found.get(0);
		}
		return folder;
	}

	private void createGroup(GroupSpec spec) throws PersistenceException {
		Group group = new Group();
		group.setName(spec.name());
		group.setDescription(spec.description());
		group.setTenantId(Tenant.DEFAULT_ID);
		GroupDAO.get().insert(group, 0);
	}

	private void createUser(UserSpec spec) throws Exception {
		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(spec.username());
		user.setFirstName(spec.firstName());
		user.setName(spec.name());
		user.setEmail(spec.email());
		user.setLanguage("en");
		user.setType(User.Type.DEFAULT);
		user.setEnabled(true);
		// CE's password policy runs on this store, so a weak demo password fails here
		user.setDecodedPassword(password);
		user.addGroup(group(HealdanConstants.EVERYONE_GROUP));
		for (String name : spec.groups())
			user.addGroup(group(name));
		UserHistory history = new UserHistory();
		history.setAuthor("System");
		UserDAO.get().store(user, history);
		users.put(spec.username(), UserDAO.get().findById(user.getId()));
	}

	private static Group group(String name) throws PersistenceException {
		Group group = GroupDAO.get().findByName(name, Tenant.DEFAULT_ID);
		if (group == null)
			throw new IllegalArgumentException("No group %s".formatted(name));
		return group;
	}

	/** Healdan ships the Class Docs only (plan call 9) */
	private void createClasses() throws PersistenceException {
		long setId = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true)
				.getId();
		AttributeOptionDAO dao = AttributeOptionDAO.get();
		List<String> existing = dao.findByAttribute(setId, HealdanConstants.ATTR_DOCCLASS).stream()
				.map(AttributeOption::getValue).toList();
		for (ClassSpec spec : manifest.classes()) {
			if (!existing.contains(spec.name()))
				dao.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCCLASS, spec.name()));
			for (String subclass : spec.subclasses())
				dao.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCSUBCLASS, subclass, spec.name()));
		}
	}

	private void createStore(StoreSpec spec) throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder vo = new Folder(spec.name());
		vo.setType(Folder.TYPE_WORKSPACE);
		vo.setTenantId(Tenant.DEFAULT_ID);
		Folder store = dao.create(dao.findRoot(Tenant.DEFAULT_ID), vo, false, systemHistory(FolderEvent.CREATED));
		setAcl(store, spec.acl(), null);
		stores.put(spec.name(), dao.findById(store.getId()));
	}

	private void createTemplate(TemplateSpec spec) throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder parent = dao.findById(HealdanSettings.get().templatesStoreId());
		Folder template = dao.create(parent, new Folder(spec.name()), true, systemHistory(FolderEvent.CREATED));
		// P1: a folder in a matter can't grant more than its parent, so the template's root has its own ACL
		setAcl(template, spec.acl(), null);
		template = dao.findById(template.getId());
		createFolders(template, spec.folders());
		for (SearchFolderSpec search : spec.searchFolders()) {
			SearchFolderRule.Kind kind = search.kind() == null ? SearchFolderRule.Kind.ALL
					: SearchFolderRule.Kind.valueOf(search.kind());
			SearchFolderService.get().create(template.getId(), search.name(), new SearchFolderRule(0, search.days(),
					search.onlyMine(), kind, search.docClass(), search.subclass()), admin);
		}
		templates.put(spec.name(), template);
	}

	private void createFolders(Folder parent, List<FolderSpec> specs) throws Exception {
		FolderDAO dao = FolderDAO.get();
		for (FolderSpec spec : specs) {
			Folder folder = dao.create(parent, new Folder(spec.name()), true, systemHistory(FolderEvent.CREATED));
			if (spec.acl() != null)
				setAcl(folder, spec.acl(), null);
			createFolders(dao.findById(folder.getId()), spec.folders());
		}
	}

	private void createMatters() throws Exception {
		Map<Long, Client> clients = new HashMap<>();
		for (ClientSpec spec : manifest.clients())
			clients.put(spec.number(), ClientService.get().create(spec.number(), spec.name(), admin));
		for (MatterSpec spec : manifest.matters()) {
			String[] number = spec.ref().split("\\.");
			if (number.length != 2)
				throw new IllegalArgumentException("Matter %s isn't client.number".formatted(spec.ref()));
			Client client = clients.get(Long.parseLong(number[0]));
			if (client == null)
				throw new IllegalArgumentException("The manifest has no client %s".formatted(number[0]));
			User owner = user(spec.owner());
			Matter matter = MatterService.get()
					.create(new NewMatter(client.getId(), Long.parseLong(number[1]), spec.name(), spec.description(),
							named(stores, spec.store()).getId(), owner.getId(),
							named(templates, spec.template()).getId()), admin);
			if (spec.acl() != null)
				setAcl(FolderDAO.get().findById(matter.getFolderId()), spec.acl(), owner);
			matters.put(spec.ref(), matter);
		}
	}

	private static Folder named(Map<String, Folder> folders, String name) {
		Folder folder = folders.get(name);
		if (folder == null)
			throw new IllegalArgumentException("The manifest has no Store or template %s".formatted(name));
		return folder;
	}

	/**
	 * Replaces a folder's ACL, as smoke-fixture does
	 *
	 * @param owner when not null, also gets every permission through their
	 *        personal group, as a matter's owner does (plan call 8)
	 */
	private static void setAcl(Folder folder, Map<String, List<String>> acl, User owner) throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder current = dao.findById(folder.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		for (Map.Entry<String, List<String>> entry : acl.entrySet()) {
			FolderAccessControlEntry ace = new FolderAccessControlEntry(group(entry.getKey()).getId());
			Set<Permission> permissions = EnumSet.noneOf(Permission.class);
			entry.getValue().forEach(p -> permissions.add(Permission.valueOf(p)));
			ace.grantPermissions(permissions);
			current.addAccessControlEntry(ace);
		}
		if (owner != null) {
			FolderAccessControlEntry ace = new FolderAccessControlEntry(
					UserDAO.get().findById(owner.getId()).getUserGroup().getId());
			ace.grantPermissions(Permission.all());
			current.addAccessControlEntry(ace);
		}
		dao.store(current, systemHistory(FolderEvent.PERMISSION));
	}

	/*
	 * The system user only labels the history. Its id stays 0 because with a
	 * real user id CE adds that user's group to a new folder's ACL.
	 */
	private static FolderHistory systemHistory(FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUserId(0L);
		history.setUserLogin("_system");
		history.setUsername("System");
		history.setTenantId(Tenant.DEFAULT_ID);
		history.setEvent(event);
		history.setComment("Healdan demo fixture");
		return history;
	}
}
```

If `Permission.all()` returns something other than a `Set<Permission>`, adapt the call to `grantPermissions`; if `User.setLanguage` takes a `Locale`, pass `Locale.ENGLISH`. Record either in the decision log.

- [ ] **Step 5: Write `DemoFixtureSeed`**

```java
package com.logicaldoc.healdan.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.seed.demo.Backdater;
import com.logicaldoc.healdan.seed.demo.DemoClock;
import com.logicaldoc.healdan.seed.demo.DemoFirm;
import com.logicaldoc.healdan.seed.demo.DemoManifest;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;
import com.logicaldoc.util.spring.Context;

/**
 * For screenshots only: when {@code HEALDAN_DEMO_FIXTURE} is {@code true},
 * fills a fresh stack with the demo firm that the manifest in
 * {@code HEALDAN_DEMO_DIR} describes, its users signing in with
 * {@code HEALDAN_DEMO_PASSWORD} (demo spec §5). {@code docker/demo}'s
 * overlay sets all three.
 * <p>
 * Without the flag the step does nothing and writes no marker. It refuses a
 * repository that holds any document, so it never mixes invented data into
 * real data (demo spec §5.3).
 */
@Component
public class DemoFixtureSeed implements SeedStep {

	static final String ENV = "HEALDAN_DEMO_FIXTURE";

	static final String DIR_ENV = "HEALDAN_DEMO_DIR";

	static final String PASSWORD_ENV = "HEALDAN_DEMO_PASSWORD";

	private final UnaryOperator<String> env;

	private final Supplier<LocalDate> today;

	@Autowired
	public DemoFixtureSeed() {
		this(System::getenv, LocalDate::now);
	}

	DemoFixtureSeed(UnaryOperator<String> env, Supplier<LocalDate> today) {
		this.env = env;
		this.today = today;
	}

	@Override
	public String name() {
		return "demo-fixture";
	}

	@Override
	public boolean enabled() {
		return "true".equals(env.apply(ENV));
	}

	/**
	 * Not one transaction: CE saves versions in the background, which a
	 * transaction held open here would block. A failure leaves a partial
	 * firm, and the next start's guard asks for a fresh stack (demo spec
	 * §5.3).
	 */
	@Override
	public void run() throws Exception {
		Path dir = Path.of(required(DIR_ENV));
		String password = required(PASSWORD_ENV);
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		refuseARepositoryWithDocuments(jdbc);

		Path manifestFile = dir.resolve("manifest.json");
		if (!Files.isRegularFile(manifestFile))
			throw new IllegalStateException("No demo manifest at %s".formatted(manifestFile));
		DemoManifest manifest = DemoManifest.read(manifestFile);
		DemoClock clock = new DemoClock(today.get(), ZoneId.systemDefault());

		Backdater backdater = new Backdater(jdbc);
		new DemoFirm(manifest, password).build();
		// The firm is set up the day before its first action
		int firstDay = manifest.timeline().stream().mapToInt(Step::day).min().orElse(-1);
		backdater.stamp(clock.at(firstDay - 1, "08:30"));
	}

	private String required(String name) {
		String value = env.apply(name);
		if (value == null || value.isBlank())
			throw new IllegalStateException("%s is set, but %s isn't".formatted(ENV, name));
		return value;
	}

	/** Plan call 4: the filing rule keeps documents out of the Templates Store, so any document is real data */
	private static void refuseARepositoryWithDocuments(JdbcTemplate jdbc) {
		Long documents = jdbc.queryForObject("select count(*) from ld_document", Long.class);
		if (documents != null && documents > 0)
			throw new IllegalStateException(("The demo fixture runs only on a fresh stack, and this one holds %d "
					+ "documents. Unset %s, or start over with docker compose down -v.").formatted(documents, ENV));
	}
}
```

- [ ] **Step 6: Add the step to `SeedRunner.ORDER`,** last, after `smoke-fixture`:

```java
	static final List<String> ORDER = List.of("settings", "everyone", "profile", "templates-store", "columns",
			"search-attributes", "search-client-column", "search-docnumber-column", "reindex-office",
			"queue-office-previews", "count-office-pages", "throttle-signin", "api-settings", "throttle-alert",
			"apikey-counters", "profile-comments", "queue-drawing-previews", "smoke-fixture", "demo-fixture");
```

- [ ] **Step 7: Run the test to make sure it passes**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='DemoFixtureSeedTest,SeedsTest,SmokeFixtureSeedTest'`
Expected: PASS. If `SeedsTest` pins `ORDER` or its last step, update it to the new list.

- [ ] **Step 8: Commit**

```bash
git add healdan-core/src
git commit -m "Build the demo firm's structure from its manifest" -m "The demo-fixture seed step creates the firm's groups, users, Classes, Stores, templates, clients and matters when HEALDAN_DEMO_FIXTURE is true, and refuses a repository that holds documents.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: The Timeline

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/demo/DemoTimeline.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/DemoFixtureSeed.java` (`run`)
- Modify: `healdan-core/src/test/resources/demo/manifest.json` (`timeline`)
- Create: `healdan-core/src/test/resources/demo/files/letter.txt`, `letter-v2.txt`, `memo.txt`, `reply.eml`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/DemoFixtureSeedTest.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/demo/DemoTimelineTest.java`

**Interfaces:**
- Consumes: `DemoFirm` (`user`, `workspace`, `folder`), `DemoClock.at`, `Backdater.stamp`/`touch`, `EmailDates.restamp`.
- Produces: `DemoTimeline(DemoFirm firm, Path files, DemoClock clock, Backdater backdater, Indexing indexing)`, `void run(List<Step> timeline)`, `long doc(String key)`, nested `interface Indexing { void index(long docId, DocumentHistory transaction) throws Exception; }`, `static void index(long docId, DocumentHistory transaction)`.

**The actions** (plan call 1). Each checks its permission with `AccessChecks.allowed` before acting, and fails with "<username> lacks <PERMISSION> on <target>":

| `do` | Needs | Fields | Permission checked | What it calls |
|---|---|---|---|---|
| `file` | `matter`, `folder`, `file` | `key`, `name`, `author`, `typist`, `class`, `subclass`, `comments`, `tags` | WRITE on the folder | `DocumentManager.create(File, Document, DocumentHistory)`, then indexes |
| `checkin` | `doc`, `file` | `release` | WRITE on the document | checks it out if it isn't, `checkin(…, release, null, …)`, indexes |
| `checkout` | `doc` | | WRITE | `DocumentManager.checkout` |
| `lock` | `doc` | | WRITE | `DocumentManager.lock(id, DocumentStatus.LOCKED, …)` |
| `password` | `doc`, `password` | | PASSWORD | `DocumentDAO.setPassword` |
| `note` | `doc`, `text` | | WRITE | `DocumentNoteDAO.store` |
| `tag` | `doc`, `tags` | | WRITE | `addTag`, `DocumentDAO.store` |
| `link` | `doc`, `target` | | WRITE on `doc`, READ on `target` | `DocumentLinkDAO.store`, type `default` |
| `move` | `doc`, `matter`, `folder` | | MOVE on the document, WRITE on the folder | `DocumentManager.moveToFolder` |
| `folder` | `matter`, `folder`, `name` | | ADD on the parent | `FolderDAO.create(parent, …, true, …)` |
| `delete` | `doc` | | DELETE | `DocumentDAO.delete` |
| `deleteFolder` | `matter`, `folder` | | DELETE on the folder | `FolderDAO.deleteTree` |
| `bookmark` | `doc` | | READ | `BookmarkDAO.store`, type DOCUMENT |
| `myMatter` | `matter` | | READ (MyMatters checks) | `MyMatters.add` |
| `message` | `to`, `subject`, `text` | | none | one `SystemMessage` per recipient, `dateScope` 60 (plan call 6) |
| `ticket` | `doc` | | DOWNLOAD | `DocumentManager.createTicket`, expiring 30 days after the real today |
| `grant` | `doc`, `to` (one username) | | SECURITY | adds a READ entry for that user's personal group to the document |

- [ ] **Step 1: Write the test timeline and files**

`files/letter.txt`: "Dear Sirs, We act for Saltmarsh Shipping Ltd in the matter of MV Northern Star. Yours faithfully, Ashcombe Reeve LLP". `files/letter-v2.txt`: the same with a second paragraph "We look forward to your reply by Friday." `files/memo.txt`: "Attendance note: call with the owners' P&I club about laytime." `files/reply.eml`:

```text
From: Ines Moreau <ines.moreau@saltmarsh-shipping.example>
To: Helen Ashcombe <helen.ashcombe@ashcombe-reeve.example>
Subject: Re: MV Northern Star - laytime
Date: Mon, 1 Jan 2024 10:00:00 +0000
Message-ID: <reply-1@saltmarsh-shipping.example>
MIME-Version: 1.0
Content-Type: text/plain; charset=UTF-8

Thank you for your letter. We will reply by Friday.
```

The test manifest's `timeline`:

```json
  "timeline": [
    {"day": -10, "time": "09:30", "as": "pnair", "do": "file", "key": "letter", "matter": "1001.1",
     "folder": "Correspondence", "file": "letter.txt", "name": "Letter to Owners.txt", "author": "hashcombe",
     "typist": "pnair", "class": "Correspondence", "subclass": "Letter", "tags": ["charter"]},
    {"day": -9, "time": "11:00", "as": "pnair", "do": "file", "key": "reply", "matter": "1001.1",
     "folder": "Correspondence", "file": "reply.eml"},
    {"day": -9, "time": "11:05", "as": "pnair", "do": "link", "doc": "letter", "target": "reply"},
    {"day": -8, "as": "hashcombe", "do": "checkin", "doc": "letter", "file": "letter-v2.txt", "release": true},
    {"day": -8, "time": "10:00", "as": "pnair", "do": "note", "doc": "letter", "text": "Owners want an answer by Friday."},
    {"day": -7, "as": "pnair", "do": "tag", "doc": "letter", "tags": ["urgent"]},
    {"day": -7, "time": "10:00", "as": "pnair", "do": "file", "key": "memo", "matter": "1001.1",
     "folder": "Correspondence", "file": "memo.txt", "name": "Attendance Note.txt"},
    {"day": -6, "as": "pnair", "do": "folder", "matter": "1001.1", "folder": "Correspondence", "name": "Superseded"},
    {"day": -6, "time": "09:10", "as": "pnair", "do": "file", "key": "old", "matter": "1001.1",
     "folder": "Correspondence/Superseded", "file": "letter.txt", "name": "Old Draft.txt"},
    {"day": -5, "as": "pnair", "do": "delete", "doc": "old"},
    {"day": -5, "time": "09:30", "as": "pnair", "do": "deleteFolder", "matter": "1001.1",
     "folder": "Correspondence/Superseded"},
    {"day": -4, "as": "pnair", "do": "checkout", "doc": "memo"},
    {"day": -4, "time": "12:00", "as": "hashcombe", "do": "file", "key": "terms", "matter": "1001.2",
     "folder": "Correspondence", "file": "letter.txt", "name": "Heads of Terms.txt"},
    {"day": -3, "as": "hashcombe", "do": "lock", "doc": "terms"},
    {"day": -3, "time": "09:30", "as": "hashcombe", "do": "grant", "doc": "terms", "to": ["pnair"]},
    {"day": -3, "time": "10:00", "as": "hashcombe", "do": "password", "doc": "reply", "password": "Owners-2026"},
    {"day": -2, "as": "hashcombe", "do": "move", "doc": "letter", "matter": "1001.2", "folder": "Correspondence"},
    {"day": -2, "time": "10:00", "as": "pnair", "do": "bookmark", "doc": "memo"},
    {"day": -2, "time": "10:05", "as": "pnair", "do": "myMatter", "matter": "1001.1"},
    {"day": -1, "as": "hashcombe", "do": "message", "to": ["pnair"], "subject": "Laytime",
     "text": "Can we speak about the laytime calculation this afternoon?"},
    {"day": -1, "time": "10:00", "as": "hashcombe", "do": "ticket", "doc": "terms"}
  ]
```

- [ ] **Step 2: Write the failing tests**

Add to `DemoFixtureSeedTest` (imports: `java.time.ZoneId`, `java.util.Date`, `java.util.HashSet`, `com.logicaldoc.core.document.Document`, `DocumentDAO`, `DocumentStatus`, `com.logicaldoc.healdan.navigation.MyMatters`, `com.logicaldoc.healdan.seed.demo.DemoClock`):

```java
	@Test
	public void testPlaysTheTimeline() throws Exception {
		run(seed(TEST_DIR));
		JdbcTemplate jdbc = jdbc();
		DemoClock clock = new DemoClock(TODAY, ZoneId.systemDefault());
		DocumentDAO docs = DocumentDAO.get();
		User pnair = UserDAO.get().findByUsername("pnair");
		User hashcombe = UserDAO.get().findByUsername("hashcombe");

		Document letter = byName("Letter to Owners.txt");
		docs.initialize(letter);
		assertEquals("2.0", letter.getVersion());
		// CE keeps a user property's user id in intValue: confirm in Attribute.setValue
		assertEquals(Long.valueOf(hashcombe.getId()),
				letter.getAttribute(HealdanConstants.ATTR_AUTHOR).getIntValue());
		assertEquals("Correspondence", letter.getValue(HealdanConstants.ATTR_DOCCLASS));
		assertEquals(Set.of("charter", "urgent"), new HashSet<>(docs.findTags(letter.getId())));
		assertEquals(correspondenceOf(matter(1001, 2)), letter.getFolder().getId());
		assertEquals(1, count("select count(*) from ld_note where ld_docid = ?", letter.getId()));

		// Filed on day -10 at 09:30, though CE's create stamped it with now
		assertEquals(clock.at(-10, "09:30").getTime(), letter.getCreation().getTime());
		// Its last change was the move, on day -2 at 09:00
		assertEquals(clock.at(-2, null).getTime(), letter.getLastModified().getTime());

		// Healdan names an email after its subject, with the colon replaced (M1 spec section 6)
		Document reply = byName("Re_ MV Northern Star - laytime.eml");
		docs.initialize(reply);
		assertEquals("Re: MV Northern Star - laytime", reply.getValue(HealdanConstants.ATTR_EMAILSUBJECT));
		assertEquals(clock.at(-9, "11:00").getTime() - 25 * 60_000L,
				((Date) reply.getValue(HealdanConstants.ATTR_EMAILSENT)).getTime());
		assertTrue(reply.getPassword() != null && !reply.getPassword().isEmpty());

		Document memo = byName("Attendance Note.txt");
		assertEquals(DocumentStatus.CHECKEDOUT, memo.getStatus());
		assertEquals(Long.valueOf(pnair.getId()), memo.getLockUserId());

		Document terms = byName("Heads of Terms.txt");
		assertEquals(DocumentStatus.LOCKED, terms.getStatus());
		assertTrue(docs.isReadAllowed(terms.getId(), pnair.getId()));
		assertFalse(FolderDAO.get().isReadAllowed(terms.getFolder().getId(), pnair.getId()));

		assertEquals(1, count("select count(*) from ld_document where ld_deleted <> 0"));
		assertEquals(1, count("select count(*) from ld_folder where ld_deleted <> 0 and ld_name = 'Superseded'"));
		assertEquals(1, count("select count(*) from ld_link"));
		assertEquals(1, count("select count(*) from ld_ticket"));
		assertEquals(1, count("select count(*) from ld_bookmark where ld_userid = ? and ld_targetid = ?",
				pnair.getId(), memo.getId()));
		assertEquals(1, count("select count(*) from ld_systemmessage"));
		assertEquals(1, MyMatters.get().list(pnair.getId()).size());

		// Doc #s follow the order of filing
		List<Long> numbers = jdbc.queryForList("select ld_customid from ld_document order by ld_creation, ld_id",
				String.class).stream().map(Long::valueOf).toList();
		assertEquals(numbers.stream().sorted().toList(), numbers);

		// Nothing is dated today
		Timestamp latest = jdbc.queryForObject("select max(ld_date) from ld_history", Timestamp.class);
		assertTrue(latest.toString(), latest.toLocalDateTime().toLocalDate().isBefore(TODAY));
	}

	@Test
	public void testRefusesASecondRun() throws Exception {
		run(seed(TEST_DIR));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(seed(TEST_DIR)));
		assertTrue(e.getMessage(), e.getMessage().contains("down -v"));
	}

	private static Document byName(String name) throws Exception {
		List<Long> ids = jdbc().queryForList("select ld_id from ld_document where ld_filename = ? and ld_deleted = 0",
				Long.class, name);
		assertEquals(name, 1, ids.size());
		return DocumentDAO.get().findById(ids.get(0));
	}

	private static long correspondenceOf(Matter matter) throws Exception {
		FolderDAO dao = FolderDAO.get();
		return dao.findByName(dao.findById(matter.getFolderId()), "Correspondence", Tenant.DEFAULT_ID, true).get(0)
				.getId();
	}

	private static int count(String sql, Object... args) {
		return jdbc().queryForObject(sql, Integer.class, args);
	}
```

If CE stores the user property's id elsewhere than `intValue`, assert on that field instead; read `Attribute.setValue(Object)` for a `User`.

`DemoTimelineTest.java` checks the refusals, with `DemoFirm` built from the test manifest and a `DemoTimeline` driven directly:

```java
package com.logicaldoc.healdan.seed.demo;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;
import com.logicaldoc.util.spring.Context;

public class DemoTimelineTest extends AbstractHealdanTestCase {

	private static final Path DIR = Path.of("src/test/resources/demo");

	private DemoTimeline timeline;

	@Before
	public void buildTheFirm() throws Exception {
		DemoManifest manifest = DemoManifest.read(DIR.resolve("manifest.json"));
		DemoFirm[] firm = new DemoFirm[1];
		HealdanOperation.run(Kind.SEED, () -> firm[0] = new DemoFirm(manifest, "Demo-Firm-2026").build());
		timeline = new DemoTimeline(firm[0], DIR.resolve("files"),
				new DemoClock(LocalDate.of(2026, 10, 29), ZoneId.systemDefault()),
				new Backdater(new JdbcTemplate(Context.get(DataSource.class))), DemoTimeline::index);
	}

	private static Step step(String as, String action, String matter, String folder, String file) {
		return new Step(-1, null, as, action, "k", matter, folder, file, null, null, null, null, null, null, null,
				null, null, false, null, null, null, null);
	}

	private void play(Step... steps) throws Exception {
		HealdanOperation.run(Kind.SEED, () -> timeline.run(List.of(steps)));
	}

	@Test
	public void testFilingWhereTheUserCantWriteIsRefused() {
		Exception e = assertThrows(Exception.class,
				() -> play(step("pnair", "file", "1001.2", "Correspondence", "letter.txt")));
		assertTrue(e.getMessage(), e.getMessage().contains("pnair lacks WRITE"));
	}

	@Test
	public void testAnUnknownActionIsRefused() {
		Exception e = assertThrows(Exception.class, () -> play(step("pnair", "shred", null, null, null)));
		assertTrue(e.getMessage(), e.getMessage().contains("shred"));
	}

	@Test
	public void testAMissingFileIsRefused() {
		Exception e = assertThrows(Exception.class,
				() -> play(step("pnair", "file", "1001.1", "Correspondence", "nowhere.txt")));
		assertTrue(e.getMessage(), e.getMessage().contains("nowhere.txt"));
	}

	@Test
	public void testFilingAtAMattersTopLevelIsRefusedByHealdan() {
		// The filing rule, not the demo's own check: the workspace's top level takes no documents
		assertThrows(Exception.class, () -> play(new Step(-1, null, "hashcombe", "file", "k", "1001.1", null,
				"letter.txt", null, null, null, null, null, null, null, null, null, false, null, null, null, null)));
	}
}
```

For the last test, `DemoTimeline.file` treats a null `folder` as the workspace itself, so Healdan's filing rule is what refuses it.

- [ ] **Step 3: Run them to make sure they fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='DemoFixtureSeedTest,DemoTimelineTest'`
Expected: compilation failure, `DemoTimeline` not found.

- [ ] **Step 4: Write `DemoTimeline`**

```java
package com.logicaldoc.healdan.seed.demo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentLink;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentNote;
import com.logicaldoc.core.document.DocumentNoteDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.communication.Message;
import com.logicaldoc.core.communication.Recipient;
import com.logicaldoc.core.communication.SystemMessage;
import com.logicaldoc.core.communication.SystemMessageDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.ticket.Ticket;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.navigation.MyMatters;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.seed.demo.DemoManifest.Step;
import com.logicaldoc.util.io.FileUtil;

/**
 * Plays the demo's timeline (demo spec §4.6, §5.2): each step as its user,
 * on its day, through the services the web page uses. Before each action it
 * checks the permission the web page would ask for, since inside a seed
 * Healdan counts everyone as an admin and CE's create checks no permission
 * (plan call 1). After each, {@link Backdater} dates what it wrote.
 */
public final class DemoTimeline {

	/** Indexes a document inside the action that filed or changed it (plan call 2) */
	@FunctionalInterface
	public interface Indexing {
		void index(long docId, DocumentHistory transaction) throws Exception;
	}

	private static final int MESSAGE_DAYS = 60;

	private static final Duration TICKET_LIFE = Duration.ofDays(30);

	private final DemoFirm firm;

	private final Path files;

	private final DemoClock clock;

	private final Backdater backdater;

	private final Indexing indexing;

	private final Map<String, Long> docs = new HashMap<>();

	public DemoTimeline(DemoFirm firm, Path files, DemoClock clock, Backdater backdater, Indexing indexing) {
		this.firm = firm;
		this.files = files;
		this.clock = clock;
		this.backdater = backdater;
		this.indexing = indexing;
	}

	public static void index(long docId, DocumentHistory transaction) throws Exception {
		DocumentManager.get().index(docId, null, transaction);
	}

	/** Plays the steps in day and time order; steps at the same moment keep the manifest's order */
	public void run(List<Step> timeline) throws Exception {
		List<Step> ordered = new ArrayList<>(timeline);
		ordered.sort(Comparator.comparingInt(Step::day).thenComparing(s -> s.time() == null ? "09:00" : s.time()));
		for (Step step : ordered) {
			Date when = clock.at(step.day(), step.time());
			try {
				perform(step, when);
			} catch (Exception e) {
				throw new IllegalStateException("Demo step '%s' by %s on day %d failed: %s".formatted(step.action(),
						step.as(), step.day(), e.getMessage()), e);
			}
			backdater.stamp(when);
		}
	}

	public long doc(String key) {
		Long id = docs.get(key);
		if (id == null)
			throw new IllegalArgumentException("No document has the key %s yet".formatted(key));
		return id;
	}

	private void perform(Step step, Date when) throws Exception {
		User actor = firm.user(step.as());
		switch (step.action()) {
		case "file" -> file(step, actor, when);
		case "checkin" -> checkin(step, actor, when);
		case "checkout" -> {
			Document doc = need(actor, Permission.WRITE, step.doc());
			DocumentManager.get().checkout(doc.getId(), history(actor, when));
			backdater.touch(doc.getId(), when, false);
		}
		case "lock" -> {
			Document doc = need(actor, Permission.WRITE, step.doc());
			DocumentManager.get().lock(doc.getId(), DocumentStatus.LOCKED, history(actor, when));
			backdater.touch(doc.getId(), when, false);
		}
		case "password" -> {
			Document doc = need(actor, Permission.PASSWORD, step.doc());
			DocumentDAO.get().setPassword(doc.getId(), step.password(), history(actor, when));
			backdater.touch(doc.getId(), when, false);
		}
		case "note" -> note(step, actor, when);
		case "tag" -> tag(step, actor, when);
		case "link" -> link(step, actor);
		case "move" -> move(step, actor, when);
		case "folder" -> {
			Folder parent = firm.folder(step.matter(), step.folder());
			need(actor, Permission.ADD, parent, step.matter() + "/" + step.folder());
			FolderDAO.get().create(parent, new Folder(step.name()), true, folderHistory(actor, when));
		}
		case "delete" -> {
			Document doc = need(actor, Permission.DELETE, step.doc());
			DocumentDAO.get().delete(doc.getId(), history(actor, when));
		}
		case "deleteFolder" -> {
			Folder folder = firm.folder(step.matter(), step.folder());
			need(actor, Permission.DELETE, folder, step.matter() + "/" + step.folder());
			List<Folder> left = FolderDAO.get().deleteTree(folder.getId(), folderHistory(actor, when));
			if (!left.isEmpty())
				throw new IllegalStateException("%d folders under %s weren't deleted".formatted(left.size(),
						step.folder()));
		}
		case "bookmark" -> bookmark(step, actor);
		case "myMatter" -> MyMatters.get().add(actor.getId(), firm.workspace(step.matter()).getId());
		case "message" -> message(step, actor, when);
		case "ticket" -> ticket(step, actor, when);
		case "grant" -> grant(step, actor, when);
		default -> throw new IllegalArgumentException("Unknown action %s".formatted(step.action()));
		}
	}

	private void file(Step step, User actor, Date when) throws Exception {
		String path = step.folder() == null ? "" : step.folder();
		Folder folder = step.folder() == null ? firm.workspace(step.matter()) : firm.folder(step.matter(), path);
		need(actor, Permission.WRITE, folder, step.matter() + "/" + path);

		Document vo = new Document();
		vo.setFolder(folder);
		vo.setFileName(step.name() != null ? step.name() : step.file());
		vo.setTenantId(Tenant.DEFAULT_ID);
		vo.setLanguage("en");
		Map<String, Object> values = new LinkedHashMap<>();
		if (step.author() != null)
			values.put(HealdanConstants.ATTR_AUTHOR, firm.user(step.author()));
		if (step.typist() != null)
			values.put(HealdanConstants.ATTR_TYPIST, firm.user(step.typist()));
		if (step.docClass() != null)
			values.put(HealdanConstants.ATTR_DOCCLASS, step.docClass());
		if (step.subclass() != null)
			values.put(HealdanConstants.ATTR_DOCSUBCLASS, step.subclass());
		if (step.comments() != null)
			values.put(HealdanConstants.ATTR_COMMENTS, step.comments());
		setProperties(vo, values);
		step.tags().forEach(vo::addTag);

		File content = content(step.file(), when);
		try {
			Document doc = DocumentManager.get().create(content, vo, history(actor, when)).getDocument();
			if (step.key() != null)
				docs.put(step.key(), doc.getId());
			indexing.index(doc.getId(), history(actor, when));
		} finally {
			Files.deleteIfExists(content.toPath());
		}
	}

	private void checkin(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.WRITE, step.doc());
		DocumentManager manager = DocumentManager.get();
		if (doc.getStatus() != DocumentStatus.CHECKEDOUT)
			manager.checkout(doc.getId(), history(actor, when));
		File content = content(step.file(), when);
		try (InputStream in = new FileInputStream(content)) {
			manager.checkin(doc.getId(), in, doc.getFileName(), step.release(), null, history(actor, when))
					.getDocument();
		} finally {
			Files.deleteIfExists(content.toPath());
		}
		indexing.index(doc.getId(), history(actor, when));
		backdater.touch(doc.getId(), when, true);
	}

	private void note(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.WRITE, step.doc());
		DocumentNote note = new DocumentNote();
		note.setTenantId(Tenant.DEFAULT_ID);
		note.setDocId(doc.getId());
		note.setUserId(actor.getId());
		note.setUsername(actor.getFullName());
		note.setMessage(step.text());
		note.setDate(when);
		DocumentNoteDAO.get().store(note, history(actor, when));
	}

	private void tag(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.WRITE, step.doc());
		DocumentDAO dao = DocumentDAO.get();
		dao.initialize(doc);
		step.tags().forEach(doc::addTag);
		dao.store(doc, history(actor, when));
		reindexIfNeeded(doc.getId(), actor, when);
		backdater.touch(doc.getId(), when, false);
	}

	private void link(Step step, User actor) throws Exception {
		Document from = need(actor, Permission.WRITE, step.doc());
		Document to = need(actor, Permission.READ, step.target());
		DocumentLink link = new DocumentLink();
		link.setTenantId(Tenant.DEFAULT_ID);
		link.setType("default");
		link.setDocument1(from);
		link.setDocument2(to);
		DocumentLinkDAO.get().store(link);
	}

	private void move(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.MOVE, step.doc());
		Folder target = firm.folder(step.matter(), step.folder());
		need(actor, Permission.WRITE, target, step.matter() + "/" + step.folder());
		DocumentDAO.get().initialize(doc);
		DocumentManager.get().moveToFolder(doc, target, history(actor, when)).getDocument();
		reindexIfNeeded(doc.getId(), actor, when);
		backdater.touch(doc.getId(), when, false);
	}

	private void bookmark(Step step, User actor) throws Exception {
		Document doc = need(actor, Permission.READ, step.doc());
		Bookmark bookmark = new Bookmark();
		bookmark.setTenantId(Tenant.DEFAULT_ID);
		bookmark.setUserId(actor.getId());
		bookmark.setTargetId(doc.getId());
		bookmark.setTitle(doc.getFileName());
		bookmark.setFileType(FileUtil.getExtension(doc.getFileName()));
		bookmark.setType(Bookmark.Type.DOCUMENT);
		BookmarkDAO.get().store(bookmark);
	}

	/** As the web page's MessageServiceImpl.saveMessage: one message per recipient */
	private void message(Step step, User actor, Date when) throws Exception {
		for (String username : step.to()) {
			User to = firm.user(username);
			SystemMessage message = new SystemMessage();
			message.setTenantId(Tenant.DEFAULT_ID);
			message.setAuthor(actor.getUsername());
			message.setSentDate(when);
			message.setLastNotified(when);
			message.setStatus(SystemMessage.STATUS_NEW);
			message.setType(Message.Type.SYSTEM);
			message.setSubject(step.subject());
			message.setMessageText(step.text());
			Recipient recipient = new Recipient();
			recipient.setName(to.getUsername());
			recipient.setAddress(to.getUsername());
			recipient.setType(Recipient.Type.SYSTEM);
			message.setRecipients(new HashSet<>(Set.of(recipient)));
			message.setDateScope(MESSAGE_DAYS);
			message.setConfirmation(0);
			SystemMessageDAO.get().store(message);
		}
	}

	private void ticket(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.DOWNLOAD, step.doc());
		Ticket ticket = new Ticket();
		ticket.setTenantId(Tenant.DEFAULT_ID);
		ticket.setDocId(doc.getId());
		ticket.setUserId(actor.getId());
		ticket.setType(Ticket.DOWNLOAD);
		// Expires from the real today, so the screenshots show a live link
		ticket.setExpired(Date.from(Instant.now().plus(TICKET_LIFE)));
		DocumentManager.get().createTicket(ticket, history(actor, when));
	}

	/** A document-only grant (demo spec §4.4): READ for one person who can't see its folder */
	private void grant(Step step, User actor, Date when) throws Exception {
		Document doc = need(actor, Permission.SECURITY, step.doc());
		if (step.to().size() != 1)
			throw new IllegalArgumentException("A grant names one user");
		User to = UserDAO.get().findById(firm.user(step.to().get(0)).getId());
		DocumentDAO dao = DocumentDAO.get();
		dao.initialize(doc);
		doc.addAccessControlEntry(new DocumentAccessControlEntry(to.getUserGroup().getId()));
		dao.store(doc, history(actor, when));
		backdater.touch(doc.getId(), when, false);
	}

	/**
	 * A copy of a demo file to file, with an email's dates moved to just
	 * before its filing (plan call 5)
	 */
	private File content(String name, Date when) throws IOException {
		Path source = files.resolve(name);
		if (!Files.isRegularFile(source))
			throw new IllegalArgumentException("No demo file %s".formatted(source));
		byte[] bytes = Files.readAllBytes(source);
		if (name.toLowerCase().endsWith(".eml"))
			bytes = EmailDates.restamp(bytes, when);
		Path copy = Files.createTempFile("demo-", "-" + source.getFileName());
		Files.write(copy, bytes);
		return copy.toFile();
	}

	/** CE marks a moved or retagged document to index again; the demo does it now, at the action's date */
	private void reindexIfNeeded(long docId, User actor, Date when) throws Exception {
		if (DocumentDAO.get().findById(docId).getIndexed() == IndexingStatus.TO_INDEX)
			indexing.index(docId, history(actor, when));
	}

	/** As the API's PropertyInput.set: each value on the value object's own copy of the profile's attribute */
	private static void setProperties(Document vo, Map<String, Object> values) {
		Template template = ProfileSchema.get().template();
		for (Map.Entry<String, Object> value : values.entrySet()) {
			Attribute own = vo.getAttribute(value.getKey());
			if (own == null) {
				own = new Attribute(template.getAttribute(value.getKey()));
				own.setInitialization(null);
				own.setValidation(null);
				vo.getAttributes().put(value.getKey(), own);
			}
			Inheritance.clear(own);
			own.setValue(value.getValue());
		}
	}

	private Document need(User actor, Permission permission, String key) throws Exception {
		Document doc = DocumentDAO.get().findById(doc(key));
		if (!AccessChecks.allowed(actor, permission, doc))
			throw new IllegalStateException("%s lacks %s on document %s, so couldn't do this in the web page"
					.formatted(actor.getUsername(), permission, key));
		return doc;
	}

	private static void need(User actor, Permission permission, Folder folder, String where) throws Exception {
		if (!AccessChecks.allowed(actor, permission, folder))
			throw new IllegalStateException("%s lacks %s on %s, so couldn't do this in the web page"
					.formatted(actor.getUsername(), permission, where));
	}

	private static DocumentHistory history(User actor, Date when) {
		DocumentHistory history = new DocumentHistory();
		history.setUser(actor);
		history.setDate(when);
		return history;
	}

	private static FolderHistory folderHistory(User actor, Date when) {
		FolderHistory history = new FolderHistory();
		history.setUser(actor);
		history.setDate(when);
		return history;
	}
}
```

Adjust to the real APIs where the compiler disagrees, keeping the behaviour: the package of `IndexingStatus`, `FileUtil.getExtension`, `Document.getStatus()`'s type, `Message.Type`. If indexing fails in the test context because it has no search engine, pass `(id, history) -> {}` as the `Indexing` in both tests, and record it in the decision log; the stack check (Task 6) then covers indexing.

- [ ] **Step 5: Play the timeline in the step.** In `DemoFixtureSeed.run`, keep the firm and add the timeline after the structure's stamp:

```java
		Backdater backdater = new Backdater(jdbc);
		DemoFirm firm = new DemoFirm(manifest, password).build();
		// The firm is set up the day before its first action
		int firstDay = manifest.timeline().stream().mapToInt(Step::day).min().orElse(-1);
		backdater.stamp(clock.at(firstDay - 1, "08:30"));
		new DemoTimeline(firm, dir.resolve("files"), clock, backdater, DemoTimeline::index).run(manifest.timeline());
```

- [ ] **Step 6: Run the tests to make sure they pass**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='DemoFixtureSeedTest,DemoTimelineTest,DemoManifestTest,DemoClockTest,BackdaterTest,EmailDatesTest'`
Expected: PASS. Where an assertion fails because CE behaves differently from what the test assumed (for example the email's renamed file name), check the behaviour in the code, fix the test to the real behaviour, and note it in the decision log. Never weaken the date, permission or guard assertions.

- [ ] **Step 7: Run all of `healdan-core`'s tests,** since the step is in `ORDER` and runs in every test that runs pending seeds (it's off without the flag):

Run: `mvn -B -ntp -pl healdan-core test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add healdan-core/src
git commit -m "Play the demo firm's timeline as its users" -m "Each action runs as its user on its day, after the permission check the web page would make, since inside a seed Healdan treats everyone as an admin. Documents are indexed inside their action so the background indexer never re-dates them.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: The Firm's Documents and Manifest

**Files:**
- Create: `docker/demo/build.py`, `docker/demo/sources/letterhead.css`, `docker/demo/sources/*`, `docker/demo/files/*`, `docker/demo/manifest.json`
- Modify: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/DemoFixtureSeedTest.java` (the real manifest)

**Interfaces:**
- Consumes: the manifest format (Task 1) and the actions (Task 4).
- Produces: `docker/demo/manifest.json` and `docker/demo/files/`, which Task 6's overlay mounts.

- [ ] **Step 1: Write the failing test** for the real manifest, in `DemoFixtureSeedTest`:

```java
	@Test
	public void testTheRealManifest() throws Exception {
		run(seed("../docker/demo"));
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		assertEquals(6, (int) jdbc.queryForObject(
				"select count(*) from ld_user where ld_username in "
						+ "('hashcombe','treeve','pnair','jclarke','cdoyle','sokafor')", Integer.class));
		assertEquals(12, (int) jdbc.queryForObject("select count(*) from hd_matter", Integer.class));
		int documents = jdbc.queryForObject("select count(*) from ld_document where ld_deleted = 0", Integer.class);
		assertTrue("documents: " + documents, documents >= 55 && documents <= 70);

		User pnair = UserDAO.get().findByUsername("pnair");
		assertFalse(FolderDAO.get().isReadAllowed(matter(998, 3).getFolderId(), pnair.getId()));
		assertTrue(FolderDAO.get().isReadAllowed(matter(998, 3).getFolderId(),
				UserDAO.get().findByUsername("treeve").getId()));
		assertEquals(1, (int) jdbc.queryForObject("select count(*) from ld_document where ld_status = 1 and "
				+ "ld_lockuserid = ?", Integer.class, UserDAO.get().findByUsername("jclarke").getId()));
		for (String ext : List.of("odt", "ods", "odg", "docx", "xlsx", "pdf", "eml", "png", "jpg"))
			assertTrue(ext, jdbc.queryForObject("select count(*) from ld_document where lower(ld_filename) like ?",
					Integer.class, "%." + ext) > 0);
	}
```

Use the status value CE stores for `DocumentStatus.CHECKEDOUT` (its ordinal, 1, if the column holds the ordinal; read `Document`'s mapping).

Run: `mvn -B -ntp -pl healdan-core test -Dtest=DemoFixtureSeedTest#testTheRealManifest`
Expected: FAIL, "No demo manifest at ../docker/demo/manifest.json".

- [ ] **Step 2: Write `build.py`**

```python
#!/usr/bin/env python3
"""Builds the demo stack's sample files from their text sources (demo spec section 6).

Every file docker/demo/manifest.json's timeline names is built into
docker/demo/files/ from the one source in docker/demo/sources/ with the same
stem:

  .html  -> .odt .docx .pdf .png .jpg  (Writer, through LibreOffice's HTML import)
  .fodt  -> .odt .docx .pdf
  .fods  -> .ods .xlsx .pdf
  .fodg  -> .odg .png .pdf
  .eml   -> .eml                       (each X-Demo-Attach: header becomes an attachment)

LibreOffice and ImageMagick run inside the Healdan image, so everyone gets the
same versions. Build the image first, then:

  python3 docker/demo/build.py [--image healdan-demo:local] [STEM ...]

LibreOffice stamps the time into what it writes, so the output isn't
byte-for-byte reproducible: rebuild in the same commit as a source change.
"""

import argparse
import email
import email.policy
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
from collections import defaultdict
from email.message import EmailMessage
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCES = HERE / "sources"
FILES = HERE / "files"
SOURCE_KINDS = (".html", ".fodt", ".fods", ".fodg", ".eml")

# LibreOffice's export filter for each output, by the source's kind
TARGETS = {
    ".html": {".odt": "odt:writer8", ".docx": "docx:MS Word 2007 XML", ".pdf": "pdf:writer_pdf_Export",
              ".png": "png:writer_png_Export"},
    ".fodt": {".odt": "odt:writer8", ".docx": "docx:MS Word 2007 XML", ".pdf": "pdf:writer_pdf_Export"},
    ".fods": {".ods": "ods:calc8", ".xlsx": "xlsx:Calc MS Excel 2007 XML", ".pdf": "pdf:calc_pdf_Export"},
    ".fodg": {".odg": "odg:draw8", ".png": "png:draw_png_Export", ".pdf": "pdf:draw_pdf_Export"},
}
# HTML must open in Writer, not Writer/Web, which can't save .docx
INFILTERS = {".html": "HTML (StarWriter)"}


def wanted(manifest):
    """Every file the timeline names"""
    return sorted({step["file"] for step in manifest.get("timeline", []) if "file" in step})


def source_for(output):
    stem = Path(output).stem
    found = [SOURCES / (stem + kind) for kind in SOURCE_KINDS if (SOURCES / (stem + kind)).exists()]
    if len(found) != 1:
        sys.exit(f"{output}: expected one source named {stem}.*, found {len(found)}")
    return found[0]


def docker(image, mounts, entrypoint, args):
    cmd = ["docker", "run", "--rm", "--entrypoint", entrypoint, "--user", f"{os.getuid()}:{os.getgid()}"]
    for host, inside in mounts:
        cmd += ["-v", f"{host}:{inside}"]
    subprocess.run(cmd + [image] + args, check=True)


def convert(image, sources, target, infilter, out):
    """One LibreOffice run for every source with the same target, into out"""
    args = ["-env:UserInstallation=file:///tmp/lo", "--headless"]
    if infilter:
        args.append(f"--infilter={infilter}")
    args += ["--convert-to", target, "--outdir", "/out"] + [f"/in/{s.name}" for s in sources]
    docker(image, [(SOURCES, "/in:ro"), (out, "/out")], "soffice", args)


def build_email(source, output):
    message = email.message_from_bytes(source.read_bytes(), policy=email.policy.SMTP)
    attachments = message.get_all("X-Demo-Attach") or []
    del message["X-Demo-Attach"]
    if attachments:
        built = EmailMessage(policy=email.policy.SMTP)
        for name, value in message.items():
            if name.lower() not in ("content-type", "content-transfer-encoding", "mime-version"):
                built[name] = value
        built.set_content(message.get_content())
        for name in attachments:
            ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
            maintype, subtype = ctype.split("/", 1)
            built.add_attachment((FILES / name).read_bytes(), maintype=maintype, subtype=subtype, filename=name)
        # A fixed boundary keeps the email's bytes the same from build to build
        built.set_boundary(f"demo-{source.stem}")
        message = built
    output.write_bytes(message.as_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", default="healdan-demo:local", help="a built Healdan image")
    parser.add_argument("stems", nargs="*", help="build only these files' stems")
    options = parser.parse_args()
    if subprocess.run(["docker", "image", "inspect", options.image], capture_output=True).returncode:
        sys.exit(f"No image {options.image}: build it first, for example with docker build -t {options.image} .")

    outputs = wanted(json.loads((HERE / "manifest.json").read_text()))
    if options.stems:
        outputs = [o for o in outputs if Path(o).stem in options.stems]
    FILES.mkdir(exist_ok=True)

    # Group the conversions, so LibreOffice starts once per kind and target
    groups = defaultdict(list)
    emails, jpgs = [], []
    for output in outputs:
        source, ext = source_for(output), Path(output).suffix.lower()
        if source.suffix == ".eml":
            emails.append((source, FILES / output))
        elif ext == ".jpg" and source.suffix == ".html":
            jpgs.append(output)
            groups[(source.suffix, TARGETS[".html"][".png"])].append(source)
        elif ext in TARGETS[source.suffix]:
            groups[(source.suffix, TARGETS[source.suffix][ext])].append(source)
        else:
            sys.exit(f"{output}: a {source.suffix} source can't make a {ext}")

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        for (kind, target), sources in sorted(groups.items()):
            convert(options.image, sorted(set(sources)), target, INFILTERS.get(kind), out)
        for output in jpgs:
            stem = Path(output).stem
            docker(options.image, [(out, "/out")], "convert", [f"/out/{stem}.png", "-quality", "85", f"/out/{output}"])
        for output in outputs:
            if (out / output).exists():
                shutil.copyfile(out / output, FILES / output)
    for source, output in emails:
        build_email(source, output)
    print(f"Built {len(outputs)} files in {FILES.relative_to(HERE.parent.parent)}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Try the pipeline on three documents first.** Build the image (`docker build -t healdan-demo:local .`). Write `letterhead.css` and one source of each of `.html`, `.fodt` and `.fods`, a temporary `manifest.json` naming their outputs, and run `python3 docker/demo/build.py`. Open each output in LibreOffice and look at it. If the HTML import loses the letterhead's layout badly, write letters as `.fodt` instead, and record the call in the decision log. Check `.dockerignore` keeps `docker/demo/` out of the image's context (Task 6 adds the line; add it now if the build is slow).

- [ ] **Step 4: Write the firm.** `docker/demo/manifest.json`'s structure, exactly:

```json
{
  "groups": [
    {"name": "Partners", "description": "The firm's partners"},
    {"name": "Fee Earners", "description": "Solicitors and paralegals who work on matters"},
    {"name": "Support Staff", "description": "Legal assistants and office staff"}
  ],
  "users": [
    {"username": "hashcombe", "firstName": "Helen", "name": "Ashcombe", "email": "helen.ashcombe@ashcombe-reeve.example", "groups": ["Partners", "Fee Earners", "author"]},
    {"username": "treeve", "firstName": "Tom", "name": "Reeve", "email": "tom.reeve@ashcombe-reeve.example", "groups": ["Partners", "Fee Earners", "author"]},
    {"username": "pnair", "firstName": "Priya", "name": "Nair", "email": "priya.nair@ashcombe-reeve.example", "groups": ["Fee Earners", "author"]},
    {"username": "jclarke", "firstName": "Jamie", "name": "Clarke", "email": "jamie.clarke@ashcombe-reeve.example", "groups": ["Fee Earners", "author"]},
    {"username": "cdoyle", "firstName": "Chris", "name": "Doyle", "email": "chris.doyle@ashcombe-reeve.example", "groups": ["Support Staff", "author"]},
    {"username": "sokafor", "firstName": "Sam", "name": "Okafor", "email": "sam.okafor@ashcombe-reeve.example", "groups": ["admin"]}
  ],
  "classes": [
    {"name": "Docs"},
    {"name": "Correspondence", "subclasses": ["Letter", "Email", "Attendance Note"]},
    {"name": "Agreements", "subclasses": ["Draft", "Executed"]},
    {"name": "Court Documents", "subclasses": ["Pleading", "Evidence", "Order"]},
    {"name": "Finance", "subclasses": ["Costs", "Calculation", "Statement"]},
    {"name": "Firm", "subclasses": ["Policy", "HR", "IT"]}
  ],
  "stores": [
    {"name": "Client Work", "acl": {"Everyone": ["READ"]}},
    {"name": "Firm", "acl": {"Everyone": ["READ"]}}
  ],
  "templates": [
    {"name": "Litigation",
     "acl": {"Everyone": ["READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD", "RENAME", "MOVE", "DELETE"]},
     "folders": [
       {"name": "Correspondence"}, {"name": "Pleadings"}, {"name": "Evidence"}, {"name": "Drafts"}, {"name": "Emails"},
       {"name": "Privileged", "acl": {"Partners": ["READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD", "RENAME", "MOVE", "DELETE"]}}
     ],
     "searchFolders": [
       {"name": "My Recent Docs Here", "days": 14, "onlyMine": true, "kind": "ALL"},
       {"name": "Emails in This Matter", "days": 90, "onlyMine": false, "kind": "EMAILS"}
     ]},
    {"name": "Transactional",
     "acl": {"Everyone": ["READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD", "RENAME", "MOVE", "DELETE"]},
     "folders": [
       {"name": "Correspondence"}, {"name": "Drafts"}, {"name": "Due Diligence"}, {"name": "Executed"}, {"name": "Emails"}
     ],
     "searchFolders": [
       {"name": "My Recent Docs Here", "days": 14, "onlyMine": true, "kind": "ALL"},
       {"name": "Emails in This Matter", "days": 90, "onlyMine": false, "kind": "EMAILS"}
     ]},
    {"name": "Firm",
     "acl": {"Everyone": ["READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD", "RENAME", "MOVE", "DELETE"]},
     "folders": [{"name": "Documents"}, {"name": "Policies"}],
     "searchFolders": [{"name": "My Recent Docs Here", "days": 14, "onlyMine": true, "kind": "ALL"}]}
  ],
  "clients": [
    {"number": 998, "name": "Ashcombe Reeve LLP"},
    {"number": 1001, "name": "Saltmarsh Shipping Ltd"},
    {"number": 1002, "name": "Brightwell Bakeries Ltd"},
    {"number": 1003, "name": "Kestrel Software Ltd"},
    {"number": 1004, "name": "Marsh Lane Community Trust"},
    {"number": 1005, "name": "Dr Ada Fenwick"},
    {"number": 1006, "name": "Oliver and Grace Pemberton"}
  ],
  "matters": [
    {"ref": "998.1", "name": "Firm Policies", "store": "Firm", "owner": "hashcombe", "template": "Firm"},
    {"ref": "998.2", "name": "IT and Systems", "store": "Firm", "owner": "sokafor", "template": "Firm"},
    {"ref": "998.3", "name": "HR", "store": "Firm", "owner": "hashcombe", "template": "Firm",
     "acl": {"Partners": ["READ", "PREVIEW", "DOWNLOAD", "PRINT", "EMAIL", "WRITE", "ADD", "RENAME", "MOVE", "DELETE"]}},
    {"ref": "1001.1", "name": "Charter Dispute – MV Northern Star", "store": "Client Work", "owner": "treeve", "template": "Litigation"},
    {"ref": "1001.2", "name": "Vessel Sale", "store": "Client Work", "owner": "treeve", "template": "Transactional"},
    {"ref": "1002.1", "name": "Commercial Lease", "store": "Client Work", "owner": "hashcombe", "template": "Transactional"},
    {"ref": "1002.2", "name": "Employment Tribunal", "store": "Client Work", "owner": "treeve", "template": "Litigation"},
    {"ref": "1003.1", "name": "Series A Investment", "store": "Client Work", "owner": "hashcombe", "template": "Transactional"},
    {"ref": "1003.2", "name": "Licence Review", "store": "Client Work", "owner": "hashcombe", "template": "Transactional"},
    {"ref": "1004.1", "name": "Grant Agreement", "store": "Client Work", "owner": "hashcombe", "template": "Transactional"},
    {"ref": "1005.1", "name": "Will and Lasting Power of Attorney", "store": "Client Work", "owner": "treeve", "template": "Transactional"},
    {"ref": "1006.1", "name": "House Purchase", "store": "Client Work", "owner": "hashcombe", "template": "Transactional"}
  ],
  "timeline": []
}
```

Give each matter a one-sentence `description`. If Docs is listed and already exists, `DemoFirm` skips it (Task 3).

- [ ] **Step 5: Write the documents and the timeline.** About 60 documents, over days -42 to -1, in the mix of spec §4.5:

| Format | Count | Must include |
|---|---|---|
| `.odt` | 12 | Attendance notes by `pnair` and `jclarke`; a memo in 998.1 Policies |
| `.ods` | 5 | A costs schedule (1001.1), a completion statement (1006.1) |
| `.odg` | 2 | A site plan (1002.1), a vessel deck plan (1001.2) |
| `.docx` | 15 | **The lease with tracked changes and comments** (1002.1, from a `.fodt`), for the Previews retake; contracts; the Series A subscription agreement in two versions |
| `.xlsx` | 5 | The laytime calculation (1001.1), the cap table (1003.1) |
| `.pdf` | 10 | Executed copies (1003.1 `Executed`, 1006.1), the particulars of claim as filed (1001.1 `Pleadings`), two letters from the other side |
| `.eml` | 10 | Client and counterparty emails in each busy matter's `Emails`; **two with `X-Demo-Attach:`** |
| `.png` | 1 | The site plan's export |
| `.jpg` | 1 | A scanned receipt (from `.html`) in 1001.1 `Evidence` |

Most documents go in the three busy matters (1001.1, 1002.1, 1003.1); every other matter gets at least two, and 998.3 holds the document granted to `pnair`. The timeline must include, with the users of spec §4.1 and §4.6:

- snapshots and a new version on at least four documents, by more than one user, with `cdoyle` as Typist where a partner is Author;
- `jclarke` checking out one document and keeping it checked out;
- one locked document (`treeve`) and one password-protected one;
- notes on at least three documents;
- tags on at least eight documents, some shared;
- a link from a letter to the email answering it;
- a download ticket by `treeve`;
- a document moved from 1002.2 to 1002.1 (filed in the wrong matter);
- a subfolder made and deleted, and three documents deleted, so the trash isn't empty;
- `myMatter` for each user on two or three matters, and `bookmark`s for `pnair` and `treeve`;
- six to eight messages between users over the last ten days, some still unread;
- the `grant` of a 998.3 document to `pnair` by `hashcombe`;
- activity in the last 30 days for every user, so Recent Matters isn't empty.

Each user acts only where their groups allow: `pnair` can't file in 998.3 or a Privileged folder, and the seed's permission check refuses it if the timeline tries. Content follows spec §6.3: invented, British English, `.example` domains, 01632 960xxx numbers.

- [ ] **Step 6: Build the files and run the real-manifest test**

```bash
python3 docker/demo/build.py
mvn -B -ntp -pl healdan-core test -Dtest=DemoFixtureSeedTest
```

Expected: `Built 60 files` (about), then PASS.

- [ ] **Step 7: Check the size and the content rules**

```bash
du -sh docker/demo
grep -rnoE "[[:alnum:]._%+-]+@[[:alnum:].-]+" docker/demo/sources docker/demo/manifest.json | grep -vE "\.example$"
grep -rnoE "https?://[^/\" <]+" docker/demo/sources | grep -vE "://[^/]*\.example"
grep -rnE "\b([0-9]{1,3}\.){3}[0-9]{1,3}\b|logicaldoc" docker/demo/sources docker/demo/manifest.json
grep -rnoE "\b0[1-9][0-9 ]{9,11}\b" docker/demo/sources | grep -v "01632 960"
python3 .github/scripts/check-logicaldoc-doc-links.py && python3 .github/scripts/check-logicaldoc-hosts.py
```

Expected: under 5 MB; the four `grep`s print nothing (every address and link ends in `.example`, no IP address, no phone number outside 01632 960xxx); both checks pass. Also search the sources for the owner's real names and hosts, which the session knows, without writing them into any file.

- [ ] **Step 8: Commit**

```bash
git add docker/demo healdan-core/src/test
git commit -m "Write the demo firm's documents and timeline" -m "About sixty invented documents for Ashcombe Reeve LLP, as text sources and the files LibreOffice builds from them, with six weeks of the firm's work on them.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: The Overlay, the Docs and a Look at the Stack

**Files:**
- Create: `docker/demo/compose.demo.yaml`, `docker/demo/README.md`
- Modify: `.dockerignore`, `compose.yaml:2` (the overlay list in its comment), `site/content/developer/development-stack.md` (after line 52), `site/content/developer/writing-docs.md:84-91`, `CLAUDE.md` (Build and Test's command block; after the smoke overlay bullet at line 87), `docs/ROADMAP.md:546`, `docs/superpowers/specs/2026-10-29-demo-stack-and-docs-gaps-design.md` (status and corrections)

**Interfaces:**
- Consumes: Tasks 1–5.

- [ ] **Step 1: Write the overlay**

`docker/demo/compose.demo.yaml`:

```yaml
# The demo stack: fills a fresh stack with an invented law firm, for the docs' screenshots
# (docs/superpowers/specs/2026-10-29-demo-stack-and-docs-gaps-design.md). Never use it on a
# server with real data. Run it as a stack of its own:
#   LOGICALDOC_IMAGE=healdan-demo:local LOGICALDOC_PORT=8091 docker compose -p healdan-demo \
#     -f compose.yaml -f docker/demo/compose.demo.yaml up -d --build
services:
  app:
    environment:
      HEALDAN_DEMO_FIXTURE: "true"
      HEALDAN_DEMO_DIR: /opt/healdan-demo
      # A test password for the six demo users, not a secret. It meets CE's default password policy.
      HEALDAN_DEMO_PASSWORD: Demo-Firm-2026
    volumes:
      - ./docker/demo:/opt/healdan-demo:ro
```

`docker/demo/README.md`: two sentences pointing to the Developer Guide's "A Demo Firm for Screenshots" section at `https://docs.healdan.com/developer/development-stack/#a-demo-firm-for-screenshots`.

Add to `.dockerignore`, after `compose*.yaml`:

```text
# The demo stack's sources and files: the overlay mounts them, and the image doesn't need them
docker/demo
```

Add `docker/demo/compose.demo.yaml` to `compose.yaml`'s comment listing the overlays (line 2).

- [ ] **Step 2: Start the demo stack and look** (Review Focus 1–3)

```bash
LOGICALDOC_IMAGE=healdan-demo:local LOGICALDOC_PORT=8091 docker compose -p healdan-demo -f compose.yaml -f docker/demo/compose.demo.yaml up -d --build
```

Wait for `Running seed step demo-fixture` and the app to answer on `http://localhost:8091`. Then, in the browser pane:

- sign in as `pnair` with `Demo-Firm-2026`: no password-change prompt, the Documents, Search and Dashboard tabs are there, My Matters and Recent Matters are filled, and Messages shows unread messages;
- open 1001.1: documents with doc #s, previews and thumbnails after a few minutes, the History, Versions and Notes tabs dated over past weeks;
- search for a word from one of the letters: full-text search finds it;
- try 998.3: not visible to `pnair`; visible to `treeve`;
- **wait ten minutes** after the first start, reload the Documents list, and check that Last Modified still shows past dates (Review Focus 3). If the background work moved a date, find what saved the document, extend `Backdater` or the timeline, and record it.

Sign-in problems (a forced password change, missing menus) are fixed in `DemoFirm`, with a test, before going on.

- [ ] **Step 3: Write the docs.** In `development-stack.md`, after "A Matter to Upload Into", a new section:

````markdown
## A Demo Firm for Screenshots

The docs' screenshots come from a demo stack that holds an invented law firm, Ashcombe Reeve LLP: six users,
seven clients, twelve matters and about sixty documents, with six weeks of their work on them. Run it as a stack
of its own:

```bash
LOGICALDOC_IMAGE=healdan-demo:local LOGICALDOC_PORT=8091 docker compose -p healdan-demo -f compose.yaml -f docker/demo/compose.demo.yaml up -d --build
```

The overlay sets `HEALDAN_DEMO_FIXTURE=true`, so Healdan's first start fills the empty stack from
`docker/demo/manifest.json`. Everything is dated over the six weeks before that start. Previews and thumbnails
follow a few minutes later.

| User | Name | Role |
|---|---|---|
| `hashcombe` | Helen Ashcombe | Partner |
| `treeve` | Tom Reeve | Partner |
| `pnair` | Priya Nair | Associate, the day-to-day user in most screenshots |
| `jclarke` | Jamie Clarke | Paralegal |
| `cdoyle` | Chris Doyle | Legal assistant |
| `sokafor` | Sam Okafor | IT and office manager, an admin |

Each signs in with the password `Demo-Firm-2026`. Remove the stack with `down -v` and the same `-p`.

- **Changing the firm.** Edit `docker/demo/manifest.json`, and the documents' text sources in
  `docker/demo/sources/`. Then rebuild their files with `python3 docker/demo/build.py`, which runs LibreOffice
  inside the Healdan image, and commit the sources and files together.
- **It refuses real data.** The step runs only on a repository with no documents in it, and a failure stops
  startup. Start over with `down -v`.

{{< callout type="warning" >}}
Never use the demo overlay on a server with real data.
{{< /callout >}}
````

In `writing-docs.md`'s Screenshots section, the first bullet becomes: "Take them from the [demo stack](../development-stack/#a-demo-firm-for-screenshots), signed in as the user the page is about: `pnair` for most User Guide pages, `sokafor` for the Admin Guide. Never use real client data." Add a bullet: "Take them at a true 1280 × 800, with headless Chrome: the browser pane scales the page down."

In `CLAUDE.md`, add to Build and Test's command block:

```bash
python3 docker/demo/build.py                              # the demo firm's files, from docker/demo/sources/ (needs a built image)
```

and after "The smoke fixture overlay" bullet:

```markdown
- **The demo overlay.** `docker/demo/compose.demo.yaml` sets `HEALDAN_DEMO_FIXTURE=true`, and the `demo-fixture` seed fills a fresh stack with the invented firm in `docker/demo/manifest.json`, for the docs' screenshots (the Developer Guide's "A Demo Firm for Screenshots"). It refuses a repository with documents. Its users sign in with `Demo-Firm-2026`. After changing a source in `docker/demo/sources/`, rebuild with `python3 docker/demo/build.py` and commit the files with it. `DemoFixtureSeedTest` runs the real manifest.
```

In `docs/ROADMAP.md:546`, add after the interim pages' sentence: "The [demo stack and docs gaps spec](superpowers/specs/2026-10-29-demo-stack-and-docs-gaps-design.md) plans the rest."

In the spec, set the status line to say PR 1 is built, and fold plan calls 1–6 into §5.2–§5.3 and §4.6, each marked "(changed in the plan)".

- [ ] **Step 4: Run the site's checks and the docs-site tests**

```bash
hugo --source site --gc --minify --panicOnWarning
python3 -m unittest discover -s .github/scripts/tests -v
python3 .github/scripts/check-site-sources.py
```

Expected: all pass.

- [ ] **Step 5: Commit, and show the owner the stack** (spec §8: the owner's first look)

```bash
git add docker/demo .dockerignore compose.yaml site/content/developer CLAUDE.md docs
git commit -m "Add the demo stack's overlay and its docs" -m "One command starts a throwaway stack holding the demo firm, for the docs' screenshots.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Ask the owner to look at the stack in the browser before opening PR 1.

---

## PR 2: Every Screenshot Retaken

Needs PR 1 merged. A new branch from `master`. Can run alongside PRs 3 and 4.

### Task 7: The Web App's Screenshots

**Files:**
- Modify (replace the PNG, keep its name): `site/content/user/getting-started/signing-in/sign-in.png`, `user/getting-started/getting-around/documents-screen.png`, `user/finding-documents/searching/scopes.png`, `user/finding-documents/finding-by-doc-number/doc-number-lookup.png`, `user/documents/previews/markup.png`, `user/working-with-matters/properties/ext-properties.png`, `admin/clients-and-matters/new-matter.png`, `admin/healdan-settings/healdan-settings.png`, `admin/workspace-templates/templates-store.png`
- Modify: each of those pages' `index.md` (alt text, and any example naming the old worlds)

- [ ] **Step 1: Start the demo stack** (Task 6's command) and wait for previews.
- [ ] **Step 2: Write the capture script** in the scratchpad (never committed): headless Chrome over its DevTools protocol, at a true 1280 × 800, light mode, signing in through the login form, navigating by clicks at fixed coordinates or by GWT's element ids, and saving a PNG per shot, cropped to the part the old screenshot showed. Sign in as `pnair` for the User Guide's shots and `sokafor` for the Admin Guide's.
- [ ] **Step 3: Take each shot,** matching what the old one shows, with the demo firm: the Documents screen in 1001.1's Correspondence; the scope picker; a `#` lookup of a 1001.1 letter; the lease's preview with its tracked changes (1002.1); a document's Ext. Properties with Author, Typist, Class and Subclass; the New Matter dialog filled in for a new matter under 1004 by `sokafor`, not saved; Healdan Settings; the Templates Store with Litigation open.
- [ ] **Step 4: Look at every image** at full size (Review Focus 4): no hostname, IP address, path, real name or real email; the browser's address bar isn't in the crop.
- [ ] **Step 5: Rewrite the alt text** and any example in those pages that names the old data ("Smoke", "Smoke template", "Harbour Shipping", "Fuel Quality Claim", "Alex Morgan").
- [ ] **Step 6: Check** with Task 6 Step 4's commands, and `grep -rnE "Smoke|Harbour Shipping|Alex Morgan" site/content --include=*.md` finds only `developer/` pages about the smoke test.
- [ ] **Step 7: Commit:** "Retake the web app's screenshots from the demo firm".

### Task 8: The Helper's Screenshots

**Files:**
- Modify (replace the PNG): `site/content/user/office/installing-the-helper/settings.png`, `installing-the-helper/sign-in.png`, `opening-documents/open-from-healdan.png`, `opening-documents/checked-out-on-another-computer.png`, `saving-to-healdan/save-to-healdan.png`, `editing-and-versions/properties.png`, `when-things-go-wrong/after-a-crash.png`
- Modify: their pages' alt text and examples ("998.1 – Healdan Demo", "Healdan", "Robert Example", "Jane Smith", "MacBook"); `site/content/developer/api.md`'s examples (`jsmith`, "Jane Smith", `jane@example.org`) to demo users; `docs/superpowers/plans/2026-10-24-m3-office-integration.md`'s follow-up on the renders (mark it done)

- [ ] **Step 1: Decide how the address reads `healdan.example`.** The helper shows its server's address. Ask the owner to add `127.0.0.1 healdan.example` to the Mac's `/etc/hosts` for the session, and sign the helper in to `http://healdan.example:8091`, if the helper accepts plain HTTP for it; otherwise crop the address out of Settings. Ask the owner too what the Mac's computer name shows as in "checked out to you on …", and use a neutral name or crop it.
- [ ] **Step 2: Take the shots.** Sign the helper in as `pnair`. If this session has computer use, take them with it (window captures, light mode); otherwise give the owner a numbered shot list naming each window, its state and the demo document to use, and wait for the files. For "checked out on another computer", `jclarke`'s checked-out document from the timeline is the one. For After a Crash, follow the When Things Go Wrong page's own steps to cause it.
- [ ] **Step 3: Look at every image** (Review Focus 4), as Task 7 Step 4.
- [ ] **Step 4: Rewrite alt text and examples,** then the API page's examples, keeping their JSON valid against `openapi.yaml`'s shapes.
- [ ] **Step 5: Check** with Task 6 Step 4's commands, and `grep -rnE "Healdan Demo|Robert Example|Jane Smith|jsmith|MacBook" site/content` finds nothing.
- [ ] **Step 6: Commit:** "Retake the helper's screenshots from the demo firm". Show the owner the 16 images before the PR merges (spec §8: the second look).

---

## PR 3: The User Guide's Pages

Needs PR 1 merged. A new branch from `master`. Can run alongside PRs 2 and 4.

**How each page task runs** (Tasks 10–12):
1. Read the sources listed for the page, then do each thing on the demo stack, as the user the page is for, and note every label and menu path exactly as shown.
2. Write the page fresh, replacing the `interim` shortcode and keeping the file's address. A page that gets screenshots becomes a page bundle: `git mv tags.md tags/index.md`.
3. Take its screenshots as in Task 7, and look at each (Review Focus 4).
4. Run Task 6 Step 4's checks.
5. Where the code and the stack differ, the stack wins; note it in the decision log.

### Task 9: Hide Dropbox

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/HideDropboxSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` (`ORDER`: `hide-dropbox` before `smoke-fixture`)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/HideDropboxSeedTest.java`
- Delete: `site/content/user/documents/dropbox.md`
- Modify: `site/content/user/documents/downloading-documents.md` (front matter `aliases: [/user/documents/dropbox/]`; Task 10 writes the page)

- [ ] **Step 1: Write the failing test.** The GUI takes the session's menus from `MenuDAO.findMenuIdByUserId(userId, true)` (`SecurityServiceImpl.java:243`). For an admin, `HibernateMenuDAO` returns every menu (~77, ~279, ~444), whatever `ld_menu_acl` says, so withdrawing the ACL alone wouldn't hide Dropbox from admins. The step also marks the menu deleted, which every menu query leaves out. The test database doesn't load `logicaldoc-dropbox.sql`, so the test inserts its rows first.

`HideDropboxSeedTest.java`:

```java
package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.menu.MenuDAO;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.util.spring.Context;

public class HideDropboxSeedTest extends AbstractHealdanTestCase {

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	/** As logicaldoc-dropbox.sql, lines 1–7 */
	private static void addDropboxMenu() {
		jdbc().update("insert into ld_menu (ld_id,ld_lastmodified,ld_creation,ld_deleted,ld_name,ld_parentid,"
				+ "ld_icon,ld_type,ld_tenantid,ld_recordversion,ld_position,ld_enabled) values "
				+ "(-2070,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,'dropbox',16,'document.png',1,1,1,1,1)");
		for (long group : new long[] { 2, 3, 4, -10000 })
			jdbc().update("insert into ld_menu_acl(ld_menuid, ld_groupid, ld_read, ld_write) values (-2070,?,1,0)",
					group);
	}

	private static boolean sees(String username) throws Exception {
		long userId = UserDAO.get().findByUsername(username).getId();
		return MenuDAO.get().findMenuIdByUserId(userId, true).contains(HideDropboxSeed.DROPBOX_MENU);
	}

	/** A member of author, one of the groups CE grants the menu */
	private static void addAuthor() throws Exception {
		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername("reader");
		user.setFirstName("Robin");
		user.setName("Reader");
		user.setEmail("reader@demo.example");
		user.setDecodedPassword("Demo-Firm-2026");
		user.addGroup(GroupDAO.get().findByName("author", Tenant.DEFAULT_ID));
		UserHistory history = new UserHistory();
		history.setAuthor("System");
		UserDAO.get().store(user, history);
	}

	@Test
	public void testHidesDropboxFromEveryone() throws Exception {
		addDropboxMenu();
		addAuthor();
		assertTrue(sees("reader"));
		assertTrue(sees("admin"));

		HealdanOperation.run(Kind.SEED, new HideDropboxSeed()::run);

		assertFalse(sees("reader"));
		assertFalse(sees("admin"));
		assertTrue(jdbc().queryForList("select ld_groupid from ld_menu_acl where ld_menuid = -2070").isEmpty());
	}

	@Test
	public void testNothingToDoWithoutDropbox() throws Exception {
		HealdanOperation.run(Kind.SEED, new HideDropboxSeed()::run);
		assertFalse(sees("admin"));
	}
}
```

- [ ] **Step 2: Run it to make sure it fails.** `mvn -B -ntp -pl healdan-core test -Dtest=HideDropboxSeedTest`. Expected: compilation failure, `HideDropboxSeed` not found.
- [ ] **Step 3: Write the step,** and add `hide-dropbox` to `SeedRunner.ORDER` before `smoke-fixture`:

```java
package com.logicaldoc.healdan.seed;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Hides Dropbox, which Healdan doesn't support or document (demo spec §2,
 * §8 PR 3). The GUI shows Dropbox only with its menu,
 * {@value #DROPBOX_MENU} ({@code MainMenu.addItemsWhenFolderOrDocumentSelected}).
 * Admins get every menu whatever its ACL ({@code HibernateMenuDAO}), so the
 * menu is also marked deleted, which every menu query leaves out.
 */
@Component
public class HideDropboxSeed implements SeedStep {

	static final long DROPBOX_MENU = -2070L;

	@Override
	public String name() {
		return "hide-dropbox";
	}

	@Override
	public void run() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		jdbc.update("delete from ld_menu_acl where ld_menuid = ?", DROPBOX_MENU);
		jdbc.update("update ld_menu set ld_deleted = 1 where ld_id = ?", DROPBOX_MENU);
	}
}
```

If the admin still sees the menu, read `HibernateMenuDAO.findMenuIdByUserIdAndPermission` for what it filters on, add that, and record it in the decision log. Menus are cached in the second-level cache: if the test still sees the menu after the update, evict it (`MenuDAO`'s or the session factory's cache) and say so in the step's Javadoc.
- [ ] **Step 4: Run the test, and `healdan-core`'s whole suite.** Expected: PASS.
- [ ] **Step 5: Check on the demo stack** (rebuilt): Dropbox doesn't show for `pnair` or `sokafor`.
- [ ] **Step 6: Delete `dropbox.md`,** and give `downloading-documents.md` the alias. `hugo --source site --panicOnWarning` and `check-site-links.py` must pass with the alias page.
- [ ] **Step 7: Commit:** "Hide Dropbox, which Healdan doesn't support".

### Task 10: Copying, Moving and Deleting; the Trash; History; Downloading

**Files:** `site/content/user/documents/copying-moving-and-deleting.md`, `the-trash.md`, `document-history.md`, `downloading-documents.md` (each may become a bundle)

**Sources:**
- `logicaldoc-gui/.../frontend/client/document/DocumentsMenu.java` (the right-click menu), `TrashPanel.java`, `DocumentHistoryPanel.java`, `PermaLinkDisplay.java`; `frontend/client/clipboard/` for cut, copy and paste.
- Healdan's rules: `healdan-core/.../rules/FilingRules.java` (filing, restore at ~321–365), M1 spec §6 and §7.1 (copies get a new doc #, a move re-stamps client and matter, only admins move a matter's top-level folders).
- The roadmap's M2 build notes on password-protected documents.

**Pages:**
- **Copying, Moving and Deleting:** copying (a new document with a new doc #), moving (within a matter, and to another matter, which changes its client and matter), what can't be moved where (the filing rule's places, in plain words), deleting, and who may do each.
- **The Trash:** what goes there, finding it, restoring (back into a matter's folder; needs DELETE), and deleting for good if CE offers it to users.
- **Document History:** the History tab's columns and the events a user sees, the demo's own filed, checked-in, moved and noted events as examples.
- **Downloading Documents:** one document, a version, several at once as a zip, and that a password-protected document asks for its password first.

**Screenshots:** the right-click menu on a 1001.1 document; the trash with the demo's deleted items; a History tab with several events.

- [ ] **Step 1–5:** as "How each page task runs".
- [ ] **Step 6: Commit:** "Write the Copying, Trash, History and Downloading pages".

### Task 11: Links, Locking, Notes, Tags, Sharing and Aliases

**Files:** `site/content/user/documents/links-between-documents.md`, `locking-a-document.md`, `notes.md`, `tags.md`, `sharing-a-document.md`, `aliases.md`

**Sources:**
- `frontend/client/document/LinksPanel.java`, `note/NotesPanel.java` (the Annotations button checks feature 82, which CE doesn't declare: see what it shows), `TicketDialog.java`, `TicketDisplay.java`, `AliasesPanel.java`, `DocumentStandardPropertiesPanel.java` (tags), `frontend/client/dashboard/tag/` (the tag cloud); `HealdanRpcChecks` for the permissions links and notes need.
- Healdan: the shortcut refusal (M1 spec §6, "Shortcuts"; find its message in `FilingRules`), the M2 build notes on locks and passwords through the API.

**Pages:**
- **Links Between Documents:** making a link, the Links tab, opening the other document, removing a link.
- **Locking a Document:** locking and unlocking, who can unlock, how a lock differs from a check-out; password protection: setting, opening, removing; and that the Healdan Helper and API can't open or save a locked or password-protected document (the M2 build notes).
- **Notes:** adding, editing and deleting notes, who sees them, that their text is searched. Annotations only as the stack shows them: if the button is disabled, one sentence saying they aren't available.
- **Tags:** adding and removing tags, searching by tag, the tag cloud on the Dashboard if CE shows it.
- **Sharing a Document:** a download ticket: making one, its link, expiry and limits, the Tickets report for admins, and the caution (spec §3): the link only works for someone who can reach the server, and Healdan's server is meant to stay on the firm's network or VPN.
- **Aliases:** a short page: CE's menus offer shortcuts and folder aliases, Healdan refuses them inside Stores, which is everywhere a document lives, and why (a shortcut can't carry its own doc #, and one across matters would break their properties and permissions). Point to Links Between Documents and Bookmarks instead.

**Screenshots:** the Links tab with the letter and its reply; the Notes tab; a ticket's dialog with its link (check the link shows `localhost:8091`; crop it or note it, never a real host).

- [ ] **Step 1–5:** as "How each page task runs".
- [ ] **Step 6: Commit:** "Write the Links, Locking, Notes, Tags, Sharing and Aliases pages".

### Task 12: Messages, and Your Profile and Password

**Files:** `site/content/user/getting-started/messages.md`, `your-profile-and-password.md`

**Sources:** `frontend/client/dashboard/messages/`, `dashboard/chat/` (whether CE shows a chat), `UserDashboard.java`; `frontend/client/account/Profile.java`, `ChangePassword.java`, `ApiKeys.java`, `TrustedDevices.java`, `LastLogins.java`, `contacts/`; CE's password policy (`context.properties` 45–53); Healdan's sign-in throttle (the M2 spec's throttle section, and the Admin Guide's pages that mention it).

**Pages:**
- **Messages:** the Dashboard's Messages, reading and replying, sending one to colleagues, how long they last, and the unread count in the status bar.
- **Your Profile and Password:** what the Account menu holds as the stack shows it: profile details, changing your password and the rules it must meet, last sign-ins. What API keys are in CE, and that Healdan's own clients use device tokens instead, with a link to the Developer Guide's API page only if the page needs one. What happens after five wrong passwords (the throttle).

**Screenshots:** the Dashboard's Messages with the demo's messages, signed in as `pnair`; the change-password dialog.

- [ ] **Step 1–5:** as "How each page task runs". Remove `test_site_build.py`'s use of an interim User page here only if this task makes it fail.
- [ ] **Step 6: Commit:** "Write the Messages and Profile pages".

---

## PR 4: The Admin Guide's System Pages, and the Cleanup

Needs PR 1 merged. A new branch from `master`. Can run alongside PRs 2 and 3. Sign in as `sokafor`.

### Task 13: Interface Settings, Outgoing Email, Property Templates and Reports

**Files:** `site/content/admin/system/interface-settings.md`, `outgoing-email.md`, `property-templates.md`, `reports.md`

**Sources:**
- `frontend/client/settings/gui/`, `settings/messages/` (email settings, message templates), `frontend/client/metadata/` (templates and attribute sets), `frontend/client/reports/*Report.java`.
- Healdan: `ThrottleAlertSeed` (the `bfa.alert` template), `ProfileSeed`, `ProfileSchema`, `HealdanSettings` (Class and Subclass options), the capability map §6 rows on report menus.

**Pages:**
- **Interface Settings:** what each setting changes, from the stack; which ones Healdan sets or depends on (read `SettingsSeed`, `ColumnsSeed`).
- **Outgoing Email:** SMTP settings, a test message, who receives the sign-in throttle's alert, Microsoft 365 if CE's screen offers it.
- **Property Templates:** the Healdan profile (what it holds, that Healdan puts it on every document and folder), what an admin can safely change (Class and Subclass options; check whether they're edited here or in Healdan Settings), and what not to touch (the profile's attributes, its name). Plain words on CE's other templates and attribute sets.
- **Reports:** each report the stack lists, what it shows, and which menu an admin must grant to delegate one.

**Screenshots:** the profile template's attributes; the Reports menu with one report open.

- [ ] **Step 1–5:** as "How each page task runs".
- [ ] **Step 6: Commit:** "Write the Interface, Email, Property Templates and Reports pages".

### Task 14: Scheduled Tasks, Search and Indexing, System Information, and WebDAV, CMIS and Web Services

**Files:** `site/content/admin/system/scheduled-tasks.md`, `search-and-indexing.md`, `system-information.md`, `webdav-cmis-and-web-services.md`; `.github/scripts/tests/test_site_build.py` (`test_interim_box_renders`, ~282–290, uses the WebDAV page)

**Sources:**
- `frontend/client/system/task/`, `settings/searchindex/`, `system/GeneralPanel.java`, `settings/protocols/`.
- Healdan: `healdan-core/.../parser/` (the parsers it adds), `ReindexOfficeSeed`, `HealdanSystemInfo`, the capability map §4.2 and §6 (WebDAV and CMIS permission fixes), `healdan-api`'s token rules (CLAUDE.md, "Device tokens work only at `/api`"), `site/content/developer/api.md`.

**Pages:**
- **Scheduled Tasks:** the tasks the stack lists, what each does, running one now, and changing a schedule. Healdan adds none.
- **Search and Indexing:** the index, what's indexed (Healdan's extraction for `.docx`, `.xlsx`, `.pptx`, `.eml` and `.msg`), re-indexing, and the parsers list.
- **System Information:** what the General screen shows, from `HealdanSystemInfo`.
- **WebDAV, CMIS and Web Services:** turning each on or off, their addresses on a server at `healdan.example`, that Healdan's rules apply on every route, and that the Healdan Helper uses `/api` with device tokens, which these routes refuse.

**Screenshots:** the Scheduled Tasks list; the Search and Indexing screen.

- [ ] **Step 1: Point `test_interim_box_renders` at a page that's still interim,** or, if none is left after PR 3, leave it to Task 15. Run `python3 -m unittest discover -s .github/scripts/tests -v` after each page.
- [ ] **Step 2–6:** as "How each page task runs".
- [ ] **Step 7: Commit:** "Write the Scheduled Tasks, Search, System and Protocols pages".

### Task 15: Retire the Interim Shortcode

Runs in whichever of PR 3 and PR 4 merges last (spec §8). Before starting, `grep -rl 'interim href' site/content` must list nothing.

**Files** (line numbers from the survey at `3b14c68a7`; re-check them):
- Delete: `site/layouts/_shortcodes/interim.html`
- Modify: `site/assets/css/custom.css:123-159` (the blank line, the comment and the `.healdan-interim` rules)
- Modify: `.github/scripts/check-site-sources.py` (header 12–14; `INTERIM` at 24–25; 50–51: any link to LogicalDOC's docs in `site/content/` is now an error, with no exception)
- Modify: `.github/scripts/check-logicaldoc-doc-links.py:9-10` (the comment)
- Modify: `.github/scripts/tests/test_check_site_sources.py:80-111` (the outside-interim test now expects every such link reported; drop the interim and escaped-interim tests)
- Modify: `.github/scripts/tests/test_site_build.py:282-295, 361-373, 396-398` (drop `test_interim_box_renders`; replace `healdan-interim` as the "not written" marker with whatever those tests need, or drop the check)
- Modify: `site/hugo.yaml:14` (the comment: only the landing page writes HTML now; keep `unsafe: true` only if the landing page still needs it)
- Modify: `site/README.md:23`; `site/content/developer/writing-docs.md:72-82` (delete "Pages Not Written Yet") and `:97-98` if it mentions interim pages; `site/content/developer/continuous-integration.md:125-126`
- Modify: `CLAUDE.md:18` ("Outside the docs site's interim pages, …" becomes "Nothing links to LogicalDOC's docs …") and `:97` (delete the interim bullet)
- Modify: `docs/ROADMAP.md:546` (the docs site has no interim pages left; #56 closed)
- Modify: the spec's status line

- [ ] **Step 1: Change the tests first** (`test_check_site_sources.py`, `test_site_build.py`) to expect no interim support, and run them: `python3 -m unittest discover -s .github/scripts/tests -v`. Expected: FAIL where the scripts still allow the shortcode.
- [ ] **Step 2: Make the changes** in the files above.
- [ ] **Step 3: Run everything:** the unittest discovery, the strict Hugo build, `check-site-sources.py`, `check-site-links.py` on the built site, `check-logicaldoc-doc-links.py` and `check-logicaldoc-hosts.py`. Expected: all pass, and `grep -rn "interim" site .github CLAUDE.md` finds only unrelated words.
- [ ] **Step 4: Commit:** "Retire the docs site's interim pages" with "Closes #56." in the body, so the PR closes the issue when it merges.

---

## Decision Log

| Date | Task | Decision | Why |
|---|---|---|---|
| 2026-10-29 | 2 | `Backdater` clears Hibernate's second-level cache after its updates. | Hibernate's second-level cache keeps each loaded row with the date it was loaded with, and the demo's JDBC dates never reach it. Document and Folder are cached, so a later save would write the stale date back over the backdated one. |
| 2026-10-29 | 2 to 5 | The test context sets `hibernate.jdbc.time_zone`, so the tests read backdated dates through JDBC. | In the tests Hibernate reads dates in another zone than the seed's JDBC writes them in. |
| 2026-10-29 | 3 | Healdan's P1 and P2 rules check each group separately, so the manifest grants Partners on the Litigation template's root, and READ on each Store besides Everyone. | A grant to Everyone doesn't cover Partners. |
| 2026-10-29 | 4 | Inside a seed Healdan counts everyone as an admin, so the timeline also runs the web page's own tree check before deleting a folder and refuses a non-admin at a matter's top level. Only a manifest user in the `admin` group (`sokafor`) counts as an admin. | The web page's checks that rely on Healdan's own rules were skipped inside a seed, so the demo could show what Healdan would refuse a real user. The timeline now runs `HealdanTreeChecks.deletedTrees` before a folder delete, and a top-level add or delete is refused for a non-admin. |
| 2026-10-29 | 4 | A note re-indexes and re-dates its document. | CE's note save marks the document to index and stamps it now, so the timeline indexes it again inside the action and dates it back. |
| 2026-10-29 | 4 | A grant gives READ, PREVIEW and DOWNLOAD. | M1: a document-only grant must open, preview and download. |
| 2026-10-29 | 4 | Deleting a document or a folder re-dates it, and `Backdater.touchFolder` was added. | `deleteTree` saves the folder with the real time and the Trash lists by last-modified, so the deletion needs its own date. |
| 2026-10-29 | 4 | A move makes a new subversion. | CE records one on a move, and the demo shows what CE does. |
| 2026-10-29 | 5b | The seed logs the history event the web page logs for each action (CHANGED, PERMISSION, DELETED, CHECKEDOUT, LOCKED), in `DemoTimeline`. | CE's History event defaults to an empty string, so CE's own "if no event" checks never fire. |
| 2026-10-29 | 5a | `build.py` passes `HOME=/tmp`, and refuses a `.docx` or `.odt` built from an `.html` that carries a comment. Letters stay HTML. The files were built with the existing local image. | LibreOffice's HTML import turns a `<meta>` tag it doesn't read into a comment, which a headless PDF doesn't show. The user has no home inside the image, and fontconfig and dconf complain without one. |
| 2026-10-29 | 5a | The documents' printed dates assume a first start on 2026-10-29, while the seed dates relative to the first start. | The files print fixed dates. A stack built months later shows letters whose printed dates differ from their filing dates. |
| 2026-10-29 | 5 | The real manifest has 123 steps and files 64 documents (61 live, 3 deleted) from 67 files of 2.8 MB. `DemoFixtureSeedTest.testTheRealManifest` runs it in about two and a half minutes (144 s measured; first noted as about 100 seconds). | Recorded so a later change can see what it grew from. |
| 2026-10-29 | 6 | `DemoTimeline.index` makes the PDF conversion first for a file CE reads through it (an image), then indexes. `DemoTimelineTest` stands in a converter that works. | The first start of the real stack stopped at the harbour receipt (`.jpg`): CE's conversion saves the document under the indexing, which then failed on a stale record. The tests' context has no working ImageMagick, so they never saw it. |
| 2026-10-29 | 6 | Spec §5.3 and §5.4 say the guard refuses any document at all (call 4), not only those outside the Templates Store. | The wording in the spec still said "outside the Templates Store". |
| 2026-10-30 | final review | The guard also refuses any `ld_user` other than CE's `admin` and `_system`. Spec §5.3 and §5.4, the Developer Guide and CLAUDE.md say so. The Task snippets above keep the wording they were written with. | "Any document" let in a real install that has users, Stores and clients but no documents yet: the demo would add `sokafor` to its `admin`, with a password the repository publishes. After a failure partway through the firm, the next start passed the guard and failed on a raw `AK_GROUP` constraint error. A fresh stack has only those two users, and the smoke fixture creates none. |
| 2026-10-30 | final review | `DemoFirm.folder` refuses a path with an empty part (`Correspondence/`, `/Correspondence`, `Correspondence//Drafts`). A step's day and time are read inside the step's `try`, so a day that isn't in the past or a bad time names the step. `setAcl` loads the owner with its groups. `build.py` exits non-zero and lists any output LibreOffice or ImageMagick didn't write, and copies nothing. | `isTopLevel` took `Correspondence/` for a deeper folder, so a non-admin's `deleteFolder` got through on a malformed manifest. A bad step failed without saying which. CE logged "User hashcombe has lost association with his group" on every demo start. `soffice` can exit 0 without writing a file, which `build.py` skipped silently. |
| 2026-10-30 | 13 | Property Templates says the Healdan profile template and attribute set can't be saved from the web page, so no property can be added to the profile there; only the Class and Subclass options can change. | LogicalDOC's template and attribute set forms accept a name of letters and digits only (`SimpleTextValidator`), and refuse "Healdan profile". Seen on the stack: saving either shows "Use alphanumeric chars only". The **Options** window saves on its own, so the lists still change. The User Guide's Properties page says an administrator can add properties, which isn't true until this is fixed. |
| 2026-10-30 | 13 | Reports says Locked documents lists nothing in this release. | Its SQL lacks a comma after `A.ld_lockuserid` (`LockedDocsDataServlet`), so MariaDB refuses it. On the stack it showed 0 documents while one was checked out and one locked. |
| 2026-10-30 | 13, 14 | The pages say where the stack and the code's defaults differ: WebDAV's Use cache is on (`WebDAVPlugin.install` sets it), Max Text shows 0 (no limit) where `context.properties` has -1, and turning a protocol off takes effect at once though the screen asks for a restart. | The stack wins. CMIS answered 302 as soon as it was saved off, and 200 again once it was saved on. |
| 2026-10-30 | 14 | WebDAV, CMIS and Web Services says recording web service calls needs **Save API calls** on the Default run level as well as **Record calls**. | `WebserviceInterceptor.recordWebserviceCall` checks both, and the Default run level has the aspect off. |
| 2026-10-30 | 14 | System Information warns that the Environment tab shows the database password. | The tab lists the container's environment, `DB_PASSWORD` among it, in plain text. |

## Follow-Ups

Left by the final review of PR 1, for the pull request that can use each. The ledger that tracked them is git-ignored, so this is the only record.

### PR 2: Screenshots

- **Time zone.** Run headless Chrome in the time zone `Europe/London` (for example with `Emulation.setTimezoneOverride`). The browser pane showed times in the Mac's zone, so a screenshot taken there shows other times than the stack's.
- **Printed dates.** The letters print dates that assume a first start on 2026-10-29. On a stack first started after midnight UTC on the 30th they are a day off, and they drift further for each later build. Build the screenshot stack on the day the files assume, or check each printed date against its filing before a screenshot shows it.
- **The lease, before `previews/markup.png`.** The engrossment, the counterpart and the draft never grant parking spaces 7 and 8 or attach the lease plan that the correspondence promises. The inserted clause 6.2's number sits outside its tracked insertion. Fix both in the sources and rebuild.
- **The Saltmarsh settlement letter** should be marked "Without prejudice save as to costs".
- **Other content polish,** only where a screenshot shows it: the costs schedule's grade for Priya and its counsel timing; "pilot station" against "harbour" in 1001.1; the 14:22 quote in `brightwell-client-reply.eml`; the licence terms' cl 5.3 against the 5.5 table.

### Dates the demo leaves at today (PRs 2 to 4: keep them out of a screenshot)

None is shown in the Documents list.

- `ld_startpublishing` on documents and versions, four `ld_version.ld_lastmodified` values, and `ld_user.ld_passwordchanged` and `ld_lastlogin`.
- The Converted history rows for images and drawings have a blank user, as CE's background indexing writes them.
- A date-filtered full-text search sees every document as dated the first start, because CE puts the date in the index when it indexes (`StandardSearchEngine` about lines 107 to 108, `FulltextSearch` about lines 111 to 118). Take no screenshot of a date-filtered search.

### Docs

- **The sign-in page of a fresh stack** shows "The application has to be restarted" (CE's `CorePlugin.install` calls `setRestartRequired`), and a restart clears it. The Developer Guide says so; the Install manual could too.
- **PR 3:** Recent Matters counts filing, check-ins, changes, renames and moves. Notes don't count.

### Test gaps, later

- The matter owner's grant (call 8) isn't asserted.
- The web page's status checks aren't mirrored by the timeline: a move or delete of a locked or immutable document, and a check-in of a document that someone else has checked out.
- The manifest's per-action "Needs" fields aren't enforced.
- `testTheRealManifest` takes about two and a half minutes. Move it into a class of its own, so CI's shard planner can place it.
