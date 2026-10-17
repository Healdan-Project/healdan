package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The numbering table of spec 5.1, row by row. The two "Matters scope" rows
 * are the search UI's (Task 18); here they are the DAO lookups behind them.
 */
public class MatterNumberingTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private MatterNumbering numbering;

	private Client c998;

	private Client c999;

	private Folder store;

	@Before
	public void prepare() throws Exception {
		numbering = MatterNumbering.get();
		c998 = fx.client(998, "Healdan");
		c999 = fx.client(999, "Other");
		store = fx.store("Main");
	}

	private void inPerClientMode() throws Exception {
		numbering.setMode(NumberingMode.PER_CLIENT);
	}

	private void inSystemWideMode() throws Exception {
		numbering.setMode(NumberingMode.SYSTEM_WIDE);
	}

	@Test
	public void testPerClientIsTheDefault() {
		assertEquals(NumberingMode.PER_CLIENT, HealdanSettings.get().numberingMode());
	}

	@Test
	public void testPerClientUniqueAcrossTheClientsMatters() throws Exception {
		fx.matter(c998, 1, "First", store);

		// 998.1 and 999.1 can both exist
		numbering.checkAvailable(c999.getId(), 1);
		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> numbering.checkAvailable(c998.getId(), 1));
		assertTrue(e.getMessage(), e.getMessage().contains("already used"));
		numbering.checkAvailable(c998.getId(), 2);
	}

	@Test
	public void testPerClientSuggestionIsTheClientsHighestPlusOne() throws Exception {
		fx.matter(c998, 1, "First", store);
		fx.matter(c998, 5, "Fifth", store);
		fx.matter(c999, 40, "Far away", store);

		assertEquals(6, numbering.suggest(c998.getId()));
		assertEquals(41, numbering.suggest(c999.getId()));
	}

	@Test
	public void testPerClientSuggestionIsOneForAClientWithoutMatters() throws Exception {
		fx.matter(c998, 3, "Third", store);

		assertEquals(1, numbering.suggest(c999.getId()));
	}

	@Test
	public void testPerClientMattersScope998Dot1JumpsToTheMatter() throws Exception {
		Matter matter = fx.matter(c998, 1, "First", store);
		fx.matter(c999, 1, "Other first", store);

		assertEquals(matter.getId(), MatterDAO.get().findByClientAndNumber(c998.getId(), 1).getId());
	}

	@Test
	public void testPerClientMattersScopeBareNumberListsEveryMatterWithIt() throws Exception {
		fx.matter(c998, 1, "First", store);
		fx.matter(c999, 1, "Other first", store);
		fx.matter(c999, 2, "Other second", store);

		List<Matter> found = MatterDAO.get().findByNumber(1);

		assertEquals(List.of(c998.getId(), c999.getId()),
				found.stream().map(Matter::getClientId).collect(Collectors.toList()));
	}

	@Test
	public void testSystemWideUniqueAcrossAllMatters() throws Exception {
		fx.matter(c998, 1, "First", store);
		inSystemWideMode();

		// Another client can't use 1 either
		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> numbering.checkAvailable(c999.getId(), 1));
		assertTrue(e.getMessage(), e.getMessage().contains("998"));
		numbering.checkAvailable(c999.getId(), 2);
	}

	@Test
	public void testSystemWideSuggestionIsTheSystemsHighestPlusOne() throws Exception {
		fx.matter(c998, 1, "First", store);
		fx.matter(c999, 7, "Seventh", store);
		inSystemWideMode();

		assertEquals(8, numbering.suggest(c998.getId()));
		assertEquals(8, numbering.suggest(c999.getId()));
	}

	@Test
	public void testSystemWideMattersScope998Dot1JumpsToTheMatter() throws Exception {
		Matter matter = fx.matter(c998, 1, "First", store);
		inSystemWideMode();

		assertEquals(matter.getId(), MatterDAO.get().findByClientAndNumber(c998.getId(), 1).getId());
	}

	@Test
	public void testSystemWideMattersScopeBareNumberFindsExactlyOneMatter() throws Exception {
		Matter matter = fx.matter(c998, 1, "First", store);
		fx.matter(c999, 2, "Second", store);
		inSystemWideMode();

		List<Matter> found = MatterDAO.get().findByNumber(1);

		assertEquals(1, found.size());
		assertEquals(matter.getId(), found.get(0).getId());
	}

	@Test
	public void testSwitchToSystemWideIsRefusedWhileClientsShareANumber() throws Exception {
		fx.matter(c998, 1, "First", store);
		fx.matter(c999, 1, "Other first", store);
		fx.matter(c998, 2, "Second", store);
		fx.matter(c999, 2, "Other second", store);
		fx.matter(c999, 3, "Third", store);

		HealdanRuleException e = assertThrows(HealdanRuleException.class, this::inSystemWideMode);

		assertEquals(List.of("1: 998.1, 999.1", "2: 998.2, 999.2"), numbering.systemWideClashes());
		assertTrue(e.getMessage(), e.getMessage().contains("1: 998.1, 999.1"));
		assertTrue(e.getMessage(), e.getMessage().contains("2: 998.2, 999.2"));
		assertEquals(NumberingMode.PER_CLIENT, HealdanSettings.get().numberingMode());
	}

	@Test
	public void testSwitchToSystemWideIsAllowedWithoutClashes() throws Exception {
		fx.matter(c998, 1, "First", store);
		fx.matter(c999, 2, "Second", store);

		inSystemWideMode();

		assertEquals(NumberingMode.SYSTEM_WIDE, HealdanSettings.get().numberingMode());
	}

	@Test
	public void testSwitchToPerClientAlwaysSucceeds() throws Exception {
		inSystemWideMode();
		fx.matter(c998, 1, "First", store);
		fx.matter(c999, 2, "Second", store);

		inPerClientMode();

		assertEquals(NumberingMode.PER_CLIENT, HealdanSettings.get().numberingMode());
	}

	@Test
	public void testSwitchToPerClientSucceedsWhenAlreadyPerClient() throws Exception {
		fx.matter(c998, 1, "First", store);
		fx.matter(c999, 1, "Other first", store);

		inPerClientMode();

		assertEquals(NumberingMode.PER_CLIENT, HealdanSettings.get().numberingMode());
	}

	@Test
	public void testADeletedMattersNumberStaysReserved() throws Exception {
		Matter matter = fx.matter(c998, 1, "First", store);
		fx.matter(c999, 4, "Fourth", store);
		MatterDAO.get().delete(matter.getId(), Matter.DELETED_CODE_DEFAULT);
		assertNull(MatterDAO.get().findByClientAndNumber(c998.getId(), 1));

		assertThrows(HealdanRuleException.class, () -> numbering.checkAvailable(c998.getId(), 1));
		assertEquals(2, numbering.suggest(c998.getId()));
		inSystemWideMode();
		assertThrows(HealdanRuleException.class, () -> numbering.checkAvailable(c999.getId(), 1));
		assertEquals(5, numbering.suggest(c999.getId()));
	}

	@Test
	public void testTheHighestNumberIsStillReservedWhenItsMatterIsDeleted() throws Exception {
		Matter matter = fx.matter(c998, 3, "Third", store);
		MatterDAO.get().delete(matter.getId(), Matter.DELETED_CODE_DEFAULT);

		assertEquals(3, MatterDAO.get().maxNumber(c998.getId()));
		assertEquals(3, MatterDAO.get().maxNumber(null));
		assertEquals(4, numbering.suggest(c998.getId()));
	}

	@Test
	public void testANumberBelowOneIsRefused() {
		assertThrows(HealdanRuleException.class, () -> numbering.checkAvailable(c998.getId(), 0));
		assertThrows(HealdanRuleException.class, () -> numbering.checkAvailable(c998.getId(), -3));
	}

	@Test
	public void testMaxNumberIsZeroWithoutMatters() throws Exception {
		assertEquals(0, MatterDAO.get().maxNumber(null));
		assertEquals(0, MatterDAO.get().maxNumber(c998.getId()));
		assertEquals(1, numbering.suggest(c998.getId()));
	}

	@Test
	public void testFindLiveSkipsDeletedMattersAndMattersWithoutAWorkspace() throws Exception {
		Matter live = fx.matter(c998, 1, "Live", store);
		Matter deleted = fx.matter(c998, 2, "Deleted", store);
		Matter trashed = fx.matter(c998, 3, "Trashed workspace", store);
		MatterDAO.get().delete(deleted.getId(), Matter.DELETED_CODE_DEFAULT);
		FolderHistory history = new FolderHistory();
		history.setUser(UserDAO.get().findByUsername("admin"));
		FolderDAO.get().delete(trashed.getFolderId(), history);

		List<Matter> found = MatterDAO.get().findLive();

		assertEquals(List.of(live.getId()), found.stream().map(Matter::getId).collect(Collectors.toList()));
		assertNotNull(MatterDAO.get().findByFolderId(live.getFolderId()));
	}
}
