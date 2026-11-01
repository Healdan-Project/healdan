package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The group map's two halves (M4 spec section 8): which mapped groups a
 * person's provider groups give, and the change that makes to an account.
 * They read the groups, but store nothing.
 */
public class GroupSyncTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private Group litigation;

	private Group partners;

	private Group archive;

	@Before
	public void groups() throws Exception {
		litigation = fx.group("Litigation");
		partners = fx.group("Partners");
		archive = fx.group("Archive");
	}

	private static Set<String> names(User user) {
		return user.getGroups().stream().map(Group::getName).collect(Collectors.toSet());
	}

	@Test
	public void testOneProviderGroupMapsToTwo() throws Exception {
		List<GroupMapping> map = List.of(new GroupMapping("g-lit", litigation.getId()),
				new GroupMapping("g-lit", partners.getId()), new GroupMapping("g-arc", archive.getId()));

		assertEquals(Set.of(litigation.getId(), partners.getId()), GroupSync.mapped(map, List.of("g-lit", "g-x")));
		assertEquals(Set.of(litigation.getId(), partners.getId(), archive.getId()),
				GroupSync.mapped(map, List.of("g-arc", "g-lit")));
		assertEquals(Set.of(), GroupSync.mapped(map, List.of("g-x")));
		assertEquals(Set.of(), GroupSync.mapped(map, List.of()));
		// Values are compared exactly, as the provider sends them
		assertEquals(Set.of(), GroupSync.mapped(map, List.of("G-LIT", " g-lit")));
		assertEquals(Set.of(), GroupSync.mapped(List.of(), List.of("g-lit")));
	}

	@Test
	public void testDeletedGroupsMappingIgnored() throws Exception {
		List<GroupMapping> map = List.of(new GroupMapping("g-arc", archive.getId()),
				new GroupMapping("g-lit", litigation.getId()), new GroupMapping("g-gone", 987_654L));
		GroupDAO.get().delete(archive.getId(), 1);

		// The gate doesn't count it
		assertEquals(Set.of(), GroupSync.mapped(map, List.of("g-arc", "g-gone")));
		assertEquals(Set.of(litigation.getId()), GroupSync.mapped(map, List.of("g-arc", "g-lit")));

		// and the account isn't put in it, or taken out
		User author = UserDAO.get().findById(fx.user("author").getId(), true);
		assertTrue(GroupSync.apply(author, map, Set.of(archive.getId(), litigation.getId(), 987_654L)));
		assertEquals(Set.of(litigation.getId()), author.getGroups().stream().map(Group::getId)
				.filter(id -> id == archive.getId() || id == litigation.getId() || id == 987_654L)
				.collect(Collectors.toSet()));
	}

	@Test
	public void testApplyAddsAndRemovesMappedGroupsOnly() throws Exception {
		fx.group("Unmapped");
		User author = UserDAO.get().findById(fx.user("author", "Partners", "Unmapped").getId(), true);
		List<GroupMapping> map = List.of(new GroupMapping("g-lit", litigation.getId()),
				new GroupMapping("g-part", partners.getId()));

		assertTrue(GroupSync.apply(author, map, Set.of(litigation.getId())));

		Set<String> groups = names(author);
		assertTrue(groups.contains("Litigation"));
		assertFalse(groups.contains("Partners"));
		assertTrue(groups.contains("Unmapped"));
		assertTrue(groups.contains(HealdanConstants.EVERYONE_GROUP));
		assertTrue(groups.contains("_user_" + author.getId()));
		// The memberships CE writes follow the groups
		assertEquals(author.getGroups().stream().map(Group::getId).collect(Collectors.toSet()),
				author.getUserGroups().stream().map(ug -> ug.getGroupId()).collect(Collectors.toSet()));

		// It stores nothing: the caller stores the account
		assertFalse(names(UserDAO.get().findById(author.getId(), true)).contains("Litigation"));

		// Nothing granted takes the account out of every mapped group
		assertTrue(GroupSync.apply(author, map, Set.of()));
		assertFalse(names(author).contains("Litigation"));
		assertTrue(names(author).contains("Unmapped"));
	}

	@Test
	public void testApplyReturnsFalseWhenNothingChanged() throws Exception {
		User author = UserDAO.get().findById(fx.user("author", "Litigation").getId(), true);
		List<GroupMapping> map = List.of(new GroupMapping("g-lit", litigation.getId()),
				new GroupMapping("g-part", partners.getId()));
		Set<String> before = names(author);

		assertFalse(GroupSync.apply(author, map, Set.of(litigation.getId())));
		assertEquals(before, names(author));
		assertFalse(GroupSync.apply(author, List.of(), Set.of()));
		assertEquals(before, names(author));
	}

	/** Controller's ruling R9: never a person's own group, and Everyone is the listener's */
	@Test
	public void testOwnGroupsAndEveryoneAreLeftAlone() throws Exception {
		User author = UserDAO.get().findById(fx.user("author").getId(), true);
		User clerk = UserDAO.get().findById(fx.user("clerk").getId(), true);
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		Group authorsOwn = GroupDAO.get().findByName("_user_" + author.getId(), Tenant.DEFAULT_ID);
		Group clerksOwn = GroupDAO.get().findByName("_user_" + clerk.getId(), Tenant.DEFAULT_ID);
		// Rows the Sign-In tab refuses to save (SignInProviders.saveGroups), as data from elsewhere could hold
		List<GroupMapping> map = List.of(new GroupMapping("g-own", authorsOwn.getId()),
				new GroupMapping("g-clerk", clerksOwn.getId()), new GroupMapping("g-all", everyone.getId()));

		// A person's own group never lets anyone in
		assertEquals(Set.of(), GroupSync.mapped(map, List.of("g-own", "g-clerk")));

		// Nothing granted: the account keeps its own group and Everyone
		assertFalse(GroupSync.apply(author, map, Set.of()));
		assertTrue(names(author).contains("_user_" + author.getId()));
		assertTrue(names(author).contains(HealdanConstants.EVERYONE_GROUP));

		// and is never put in another person's own group
		assertFalse(GroupSync.apply(author, map, Set.of(clerksOwn.getId())));
		assertFalse(names(author).contains("_user_" + clerk.getId()));
	}

	@Test
	public void testEveryoneMappedLetsInButIsntSynced() throws Exception {
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		List<GroupMapping> map = List.of(new GroupMapping("g-all", everyone.getId()));

		assertEquals(Set.of(everyone.getId()), GroupSync.mapped(map, List.of("g-all")));
		User author = UserDAO.get().findById(fx.user("author").getId(), true);
		assertFalse(GroupSync.apply(author, map, Set.of()));
		assertTrue(names(author).contains(HealdanConstants.EVERYONE_GROUP));
	}

	@Test
	public void testReadOnlyAccountsKeepWhatCeGivesThem() throws Exception {
		User reader = fx.user("reader");
		reader = UserDAO.get().findById(reader.getId(), true);
		reader.setType(User.Type.READONLY);
		UserDAO.get().store(reader);
		reader = UserDAO.get().findById(reader.getId(), true);
		Set<String> before = names(reader);
		List<GroupMapping> map = List.of(new GroupMapping("g-lit", litigation.getId()));

		assertFalse(GroupSync.apply(reader, map, Set.of(litigation.getId())));
		assertEquals(before, names(reader));
	}
}
