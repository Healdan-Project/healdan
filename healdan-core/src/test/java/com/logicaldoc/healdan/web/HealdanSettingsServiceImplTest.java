package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIHealdanSettings;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.docnumber.DocNumberCounter;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The Healdan Settings panel's server side (spec 8.6): who may read and write
 * the settings and clients, and what a save checks and changes.
 */
public class HealdanSettingsServiceImplTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private Session boss;

	private Session clerk;

	private Folder store;

	@Before
	public void prepare() throws Exception {
		boss = open(fx.user("boss", "admin"));
		clerk = open(fx.user("clerk"));
		store = fx.store("Client Work");
	}

	@After
	public void closeSessions() {
		SessionManager.get().kill(boss.getSid());
		SessionManager.get().kill(clerk.getSid());
	}

	private static Session open(User user) {
		return SessionManager.get().newSession(user.getUsername(), HealdanFixtures.PASSWORD,
				(com.logicaldoc.core.security.Client) null);
	}

	/** The service as the given session's user */
	private static HealdanSettingsServiceImpl as(Session session) {
		return new HealdanSettingsServiceImpl() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Session session() {
				return session;
			}
		};
	}

	private static String profileMatterLabel() throws Exception {
		Template template = TemplateDAO.get().findById(HealdanSettings.get().profileTemplateId(), true);
		return template.getAttribute(HealdanConstants.ATTR_MATTER).getLabel();
	}

	@Test
	public void testNonAdminCannotLoadOrSave() throws Exception {
		GUIHealdanSettings settings = as(boss).load();
		settings.setMatterLabel("Project");
		settings.setMatterLabelPlural("Projects");

		assertThrows(AccessDeniedException.class, () -> as(clerk).load());
		assertThrows(AccessDeniedException.class, () -> as(clerk).save(settings));
		assertEquals("Matter", HealdanSettings.get().matterLabel());
		assertEquals("Matter", profileMatterLabel());
	}

	@Test
	public void testNonAdminCannotManageClients() throws Exception {
		fx.client(998, "Healdan");
		GUIClient renamed = as(boss).listClients().get(0);
		renamed.setName("Changed");
		GUIClient added = new GUIClient();
		added.setNumber(999);
		added.setName("Personal");

		assertThrows(AccessDeniedException.class, () -> as(clerk).listClients());
		assertThrows(AccessDeniedException.class, () -> as(clerk).suggestClientNumber());
		assertThrows(AccessDeniedException.class, () -> as(clerk).saveClient(added));
		assertThrows(AccessDeniedException.class, () -> as(clerk).saveClient(renamed));
		assertNull(ClientDAO.get().findByNumber(999));
		assertEquals("Healdan", ClientDAO.get().findByNumber(998).getName());
	}

	@Test
	public void testLabelsForAnyone() throws Exception {
		assertArrayEquals(new String[] { "Matter", "Matters" }, as(clerk).labels());

		GUIHealdanSettings settings = as(boss).load();
		settings.setMatterLabel("  Project ");
		settings.setMatterLabelPlural("Projects");
		as(boss).save(settings);

		assertArrayEquals(new String[] { "Project", "Projects" }, as(clerk).labels());
	}

	@Test
	public void testLoadShowsTheSettings() throws Exception {
		GUIHealdanSettings settings = as(boss).load();

		assertEquals("Matter", settings.getMatterLabel());
		assertEquals("Matters", settings.getMatterLabelPlural());
		assertEquals(NumberingMode.PER_CLIENT.name(), settings.getNumberingMode());
		assertEquals("Docs", settings.getDefaultClass());
		assertTrue(settings.getClasses().toString(), settings.getClasses().contains("Docs"));
		assertEquals(30, settings.getRecentMattersDays());
		assertEquals(Long.valueOf(DocNumberCounter.get().peekNext()), settings.getNextDocNumber());
	}

	@Test
	public void testSaveStoresEveryValue() throws Exception {
		GUIHealdanSettings settings = as(boss).load();
		settings.setRecentMattersDays(14);
		settings.setNumberingMode(NumberingMode.SYSTEM_WIDE.name());
		GUIHealdanSettings saved = as(boss).save(settings);

		HealdanSettings stored = HealdanSettings.get();
		assertEquals(14, stored.recentMattersDays());
		assertEquals(NumberingMode.SYSTEM_WIDE, stored.numberingMode());
		assertEquals(14, saved.getRecentMattersDays());
	}

	@Test
	public void testSaveRefusesModeClash() throws Exception {
		Matter healdan = fx.matter(fx.client(998, "Healdan"), 1, "Demo", store);
		Matter personal = fx.matter(fx.client(999, "Personal"), 1, "Admin", store);

		GUIHealdanSettings settings = as(boss).load();
		settings.setNumberingMode(NumberingMode.SYSTEM_WIDE.name());
		settings.setMatterLabel("Project");
		settings.setMatterLabelPlural("Projects");
		ServerException refused = assertThrows(ServerException.class, () -> as(boss).save(settings));

		assertTrue(refused.getMessage(), refused.getMessage().contains("1: 998.1, 999.1"));
		// Nothing of the refused save is kept
		assertEquals(NumberingMode.PER_CLIENT, HealdanSettings.get().numberingMode());
		assertEquals("Matter", HealdanSettings.get().matterLabel());
		assertEquals("Matter", profileMatterLabel());
		assertTrue(healdan.getId() != personal.getId());
	}

	@Test
	public void testRaiseOnly() throws Exception {
		long next = DocNumberCounter.get().peekNext();

		GUIHealdanSettings settings = as(boss).load();
		settings.setNextDocNumber(next + 10);
		assertEquals(Long.valueOf(next + 10), as(boss).save(settings).getNextDocNumber());
		assertEquals(next + 10, DocNumberCounter.get().peekNext());

		settings.setNextDocNumber(next + 5);
		settings.setRecentMattersDays(7);
		ServerException refused = assertThrows(ServerException.class, () -> as(boss).save(settings));
		assertEquals("The next doc # can only be raised. The next document gets #" + (next + 10) + ".",
				refused.getMessage());
		assertEquals(next + 10, DocNumberCounter.get().peekNext());
		assertEquals(30, HealdanSettings.get().recentMattersDays());

		// Left out, the number stays as it is
		settings.setNextDocNumber(null);
		as(boss).save(settings);
		assertEquals(next + 10, DocNumberCounter.get().peekNext());
		assertEquals(7, HealdanSettings.get().recentMattersDays());
	}

	@Test
	public void testNextDocNumberHasACeiling() throws Exception {
		long next = DocNumberCounter.get().peekNext();

		GUIHealdanSettings settings = as(boss).load();
		settings.setNextDocNumber(1_000_000_000_000L);
		ServerException refused = assertThrows(ServerException.class, () -> as(boss).save(settings));
		assertEquals("The next doc # can't be higher than 999999999999.", refused.getMessage());
		settings.setNextDocNumber(Long.MAX_VALUE);
		assertThrows(ServerException.class, () -> as(boss).save(settings));
		assertEquals(next, DocNumberCounter.get().peekNext());

		settings.setNextDocNumber(999_999_999_999L);
		as(boss).save(settings);
		assertEquals(999_999_999_999L, DocNumberCounter.get().peekNext());
		assertEquals(999_999_999_999L, DocNumberCounter.get().next());
	}

	@Test
	public void testSaveRefusesBadValues() throws Exception {
		assertRefused("The singular label cannot be empty.", s -> s.setMatterLabel("  "));
		assertRefused("The plural label cannot be empty.", s -> s.setMatterLabelPlural(null));
		assertRefused("The singular label cannot be longer than 50 characters.",
				s -> s.setMatterLabel("x".repeat(51)));
		assertRefused("The plural label cannot contain emoji or other characters the database can't store.",
				s -> s.setMatterLabelPlural("Matters \uD83C\uDF89"));
		assertRefused("Unknown numbering mode: SOMETIMES", s -> s.setNumberingMode("SOMETIMES"));
		assertRefused("The default Class must be one of the Class options.", s -> s.setDefaultClass("Nonsense"));
		assertRefused("The Recent Matters window must be between 1 and 3650 days.",
				s -> s.setRecentMattersDays(0));
		assertRefused("The Recent Matters window must be between 1 and 3650 days.",
				s -> s.setRecentMattersDays(3651));
	}

	private interface Change {
		void apply(GUIHealdanSettings settings);
	}

	private void assertRefused(String message, Change change) throws Exception {
		GUIHealdanSettings settings = as(boss).load();
		change.apply(settings);
		ServerException refused = assertThrows(ServerException.class, () -> as(boss).save(settings));
		assertEquals(message, refused.getMessage());
		GUIHealdanSettings after = as(boss).load();
		assertEquals("Matter", after.getMatterLabel());
		assertEquals("Matters", after.getMatterLabelPlural());
		assertEquals(NumberingMode.PER_CLIENT.name(), after.getNumberingMode());
		assertEquals("Docs", after.getDefaultClass());
		assertEquals(30, after.getRecentMattersDays());
	}

	@Test
	public void testClientDuplicateRefused() throws Exception {
		assertEquals(1L, as(boss).suggestClientNumber());

		GUIClient healdan = new GUIClient();
		healdan.setNumber(998);
		healdan.setName("  Healdan ");
		GUIClient saved = as(boss).saveClient(healdan);
		assertTrue(saved.getId() != 0L);
		assertEquals("Healdan", saved.getName());
		assertEquals(999L, as(boss).suggestClientNumber());

		GUIClient duplicate = new GUIClient();
		duplicate.setNumber(998);
		duplicate.setName("Someone else");
		ServerException refused = assertThrows(ServerException.class, () -> as(boss).saveClient(duplicate));
		assertEquals("Client number 998 is already used.", refused.getMessage());

		List<GUIClient> clients = as(boss).listClients();
		assertEquals(1, clients.size());
		assertEquals("Healdan", clients.get(0).getName());
	}

	@Test
	public void testClientRenameKeepsItsNumber() throws Exception {
		Client client = fx.client(998, "Healdan");
		fx.client(1001, "Acme, Inc.");

		GUIClient edited = as(boss).listClients().get(0);
		assertEquals(998L, edited.getNumber());
		edited.setName("Healdan Project");
		edited.setNumber(5);
		as(boss).saveClient(edited);

		Client stored = ClientDAO.get().findById(client.getId());
		assertEquals("Healdan Project", stored.getName());
		assertEquals(998L, stored.getNumber());
		assertEquals(List.of(998L, 1001L), as(boss).listClients().stream().map(GUIClient::getNumber).toList());
	}

	@Test
	public void testLabelRenamesTemplateAttribute() throws Exception {
		GUIHealdanSettings settings = as(boss).load();
		settings.setMatterLabel("Project");
		settings.setMatterLabelPlural("Projects");
		as(boss).save(settings);

		assertEquals("Project", profileMatterLabel());
		assertEquals("Project", HealdanSettings.get().matterLabel());
		assertEquals("Projects", HealdanSettings.get().matterLabelPlural());
	}

	@Test
	public void testApiSettingsRoundTrip() throws Exception {
		GUIHealdanSettings settings = as(boss).load();
		assertEquals(30, settings.getApiTokenIdleDays());
		assertEquals(365, settings.getApiTokenMaxDays());
		assertEquals("", settings.getApiCorsOrigins());

		settings.setApiTokenIdleDays(10);
		settings.setApiTokenMaxDays(100);
		settings.setApiCorsOrigins("https://localhost:3000, https://addin.example.org");
		GUIHealdanSettings saved = as(boss).save(settings);

		GUIHealdanSettings loaded = as(boss).load();
		assertEquals(10, saved.getApiTokenIdleDays());
		assertEquals(10, loaded.getApiTokenIdleDays());
		assertEquals(100, loaded.getApiTokenMaxDays());
		assertEquals("https://localhost:3000, https://addin.example.org", loaded.getApiCorsOrigins());
		assertEquals(10, HealdanSettings.get().apiTokenIdleDays());
		assertEquals(100, HealdanSettings.get().apiTokenMaxDays());
		assertEquals(List.of("https://localhost:3000", "https://addin.example.org"),
				HealdanSettings.get().apiCorsOrigins());
	}

	@Test
	public void testApiSettingsRefused() throws Exception {
		String idle = "A device sign-in must expire after between 1 and 3650 unused days.";
		String max = "A device sign-in must last between 1 and 3650 days.";
		String order = "A device sign-in can't last fewer days than it may go unused.";

		assertApiRefused(0, 365, "", idle);
		assertApiRefused(3651, 3651, "", idle);
		assertApiRefused(30, 0, "", max);
		assertApiRefused(30, 3651, "", max);
		assertApiRefused(10, 5, "", order);
		assertApiRefused(30, 365, "*", origin("*"));
		assertApiRefused(30, 365, "https://a.test/path", origin("https://a.test/path"));
		assertApiRefused(30, 365, "https://ok.test, ftp://a.test", origin("ftp://a.test"));
	}

	private static String origin(String value) {
		return "'%s' isn't an origin. Write it as https://host or https://host:port, with no path.".formatted(value);
	}

	private void assertApiRefused(int idle, int max, String origins, String message) throws Exception {
		GUIHealdanSettings settings = as(boss).load();
		settings.setApiTokenIdleDays(idle);
		settings.setApiTokenMaxDays(max);
		settings.setApiCorsOrigins(origins);

		ServerException e = assertThrows(ServerException.class, () -> as(boss).save(settings));

		assertEquals(message, e.getMessage());
		assertEquals(30, HealdanSettings.get().apiTokenIdleDays());
		assertEquals(365, HealdanSettings.get().apiTokenMaxDays());
		assertTrue(HealdanSettings.get().apiCorsOrigins().isEmpty());
	}
}
