package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokenDAO;
import com.logicaldoc.util.spring.Context;

public class SignInDataTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private Set<String> columns(String table) {
		return new TreeSet<>(jdbc().queryForList(
				"select lower(column_name) from information_schema.columns where lower(table_name) = ?", String.class,
				table));
	}

	private static SignInIdentity linked(String provider, String identity, long userId) {
		SignInIdentity row = new SignInIdentity();
		row.setProvider(provider);
		row.setIdentity(identity);
		row.setUserId(userId);
		row.setLinked(new Date());
		row.setHow("email");
		return row;
	}

	private static SignInIdentity unlinked(String provider, String identity, long userId) {
		SignInIdentity row = linked(provider, identity, userId);
		row.setUnlinked(new Date());
		row.setUnlinkedBy(1L);
		return row;
	}

	private static SignInGroup group(String provider, String value, long groupId) {
		SignInGroup row = new SignInGroup();
		row.setProvider(provider);
		row.setValue(value);
		row.setGroupId(groupId);
		return row;
	}

	@Test
	public void testMigrationMadeTheTables() {
		assertTrue(columns("hd_signin_identity").containsAll(List.of("ld_id", "hd_provider", "hd_identity",
				"hd_userid", "hd_linked", "hd_how", "hd_lastsignin", "hd_unlinked", "hd_unlinkedby", "hd_linkkey",
				"hd_linkuser")));
		assertTrue(columns("hd_signin_group")
				.containsAll(List.of("ld_id", "hd_provider", "hd_value", "hd_groupid")));
		assertTrue(columns("hd_api_token").containsAll(List.of("hd_provider", "hd_refresh", "hd_checked")));
		assertEquals(1, jdbc().queryForObject(
				"select count(*) from \"hd_schema_history\" where \"version\" = '6' and \"success\" = true",
				Integer.class).intValue());
	}

	@Test
	public void testIdentityRoundTrip() throws Exception {
		User author = fx.user("clerk");
		SignInIdentity row = linked("entra", "oid-1", author.getId());
		row.setLastSignIn(new Date());
		SignInIdentityDAO.get().store(row);

		SignInIdentity found = SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow();
		assertEquals(row.getId(), found.getId());
		assertEquals("entra", found.getProvider());
		assertEquals("oid-1", found.getIdentity());
		assertEquals(author.getId(), found.getUserId());
		assertEquals("email", found.getHow());
		assertNotNull(found.getLinked());
		assertNotNull(found.getLastSignIn());
		assertNull(found.getUnlinked());
		assertNull(found.getUnlinkedBy());
		assertTrue(found.isLinked());

		assertEquals(row.getId(), SignInIdentityDAO.get().findLinkedByUser(author.getId()).orElseThrow().getId());
		assertEquals(Optional.empty(), SignInIdentityDAO.get().findLinked("entra", "oid-2"));
		assertEquals(Optional.empty(), SignInIdentityDAO.get().findLinked("authentik", "oid-1"));
		assertEquals(Optional.empty(), SignInIdentityDAO.get().findLinkedByUser(author.getId() + 1000));
	}

	@Test
	public void testIdentityIsCaseSensitive() throws Exception {
		User author = fx.user("clerk");
		SignInIdentityDAO.get().store(linked("authentik", "AbC", author.getId()));

		assertTrue(SignInIdentityDAO.get().findLinked("authentik", "AbC").isPresent());
		assertFalse(SignInIdentityDAO.get().findLinked("authentik", "abc").isPresent());
	}

	@Test
	public void testOneLinkPerIdentityAndUser() throws Exception {
		User clerk = fx.user("clerk");
		User paralegal = fx.user("paralegal");
		SignInIdentityDAO.get().store(linked("entra", "oid-1", clerk.getId()));

		// The identity is taken
		assertThrows(ConstraintViolationException.class,
				() -> SignInIdentityDAO.get().store(linked("entra", "oid-1", paralegal.getId())));
		// The user is taken, even at another provider
		assertThrows(ConstraintViolationException.class,
				() -> SignInIdentityDAO.get().store(linked("entra", "oid-2", clerk.getId())));
		assertThrows(ConstraintViolationException.class,
				() -> SignInIdentityDAO.get().store(linked("authentik", "sub-9", clerk.getId())));

		// and the one link stands, with nothing else stored
		assertEquals(1, jdbc().queryForObject("select count(*) from hd_signin_identity", Integer.class).intValue());
		assertEquals(clerk.getId(), SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow().getUserId());

		// The same identity text at another provider is another identity
		SignInIdentityDAO.get().store(linked("authentik", "oid-1", paralegal.getId()));
		assertEquals(2, jdbc().queryForObject("select count(*) from hd_signin_identity", Integer.class).intValue());
	}

	@Test
	public void testStoreSetsTheLinkColumns() throws Exception {
		User clerk = fx.user("clerk");
		SignInIdentity row = linked("entra", "oid-1", clerk.getId());
		// Whatever a caller left in them, or got wrong
		row.setLinkKey("entra:other");
		row.setLinkUser(-5L);
		SignInIdentityDAO.get().store(row);

		assertEquals("entra:oid-1", jdbc().queryForObject("select hd_linkkey from hd_signin_identity", String.class));
		assertEquals(clerk.getId(),
				jdbc().queryForObject("select hd_linkuser from hd_signin_identity", Long.class).longValue());

		// Unlinking by setting the date alone frees both
		SignInIdentity stored = SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow();
		stored.setUnlinked(new Date());
		SignInIdentityDAO.get().store(stored);

		assertNull(jdbc().queryForObject("select hd_linkkey from hd_signin_identity", String.class));
		assertNull(jdbc().queryForObject("select hd_linkuser from hd_signin_identity", Long.class));
	}

	@Test
	public void testUnlinkedRowsRepeat() throws Exception {
		User clerk = fx.user("clerk");
		SignInIdentityDAO.get().store(unlinked("entra", "oid-1", clerk.getId()));
		SignInIdentityDAO.get().store(unlinked("entra", "oid-1", clerk.getId()));

		assertEquals(2, jdbc().queryForObject("select count(*) from hd_signin_identity", Integer.class).intValue());
		assertEquals(Optional.empty(), SignInIdentityDAO.get().findLinked("entra", "oid-1"));
		assertEquals(Optional.empty(), SignInIdentityDAO.get().findLinkedByUser(clerk.getId()));
		assertFalse(SignInIdentityDAO.get().findAll().get(0).isLinked());

		// and the identity and the user can be linked again, after being unlinked
		SignInIdentityDAO.get().store(linked("entra", "oid-1", clerk.getId()));
		assertTrue(SignInIdentityDAO.get().findLinked("entra", "oid-1").isPresent());
		assertTrue(SignInIdentityDAO.get().findLinkedByUser(clerk.getId()).isPresent());
	}

	@Test
	public void testUnlinkingInPlaceFreesTheIdentityAndTheUser() throws Exception {
		User clerk = fx.user("clerk");
		User paralegal = fx.user("paralegal");
		SignInIdentity row = linked("entra", "oid-1", clerk.getId());
		SignInIdentityDAO.get().store(row);

		row = SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow();
		row.setUnlinked(new Date());
		row.setUnlinkedBy(1L);
		SignInIdentityDAO.get().store(row);

		assertFalse(SignInIdentityDAO.get().findAll().get(0).isLinked());
		assertEquals(Optional.empty(), SignInIdentityDAO.get().findLinked("entra", "oid-1"));
		// the identity can go to another account
		SignInIdentityDAO.get().store(linked("entra", "oid-1", paralegal.getId()));
		assertEquals(paralegal.getId(),
				SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow().getUserId());
	}

	@Test
	public void testCountLinked() throws Exception {
		User one = fx.user("one");
		User two = fx.user("two");
		User three = fx.user("three");
		User four = fx.user("four");
		SignInIdentityDAO.get().store(linked("entra", "oid-1", one.getId()));
		SignInIdentityDAO.get().store(linked("entra", "oid-2", two.getId()));
		SignInIdentityDAO.get().store(unlinked("entra", "oid-3", three.getId()));
		SignInIdentityDAO.get().store(linked("authentik", "sub-1", four.getId()));

		assertEquals(2, SignInIdentityDAO.get().countLinked("entra"));
		assertEquals(1, SignInIdentityDAO.get().countLinked("authentik"));
		assertEquals(0, SignInIdentityDAO.get().countLinked("nobody"));
	}

	@Test
	public void testCountLinkedLeavesOutDeletedAccounts() throws Exception {
		User one = fx.user("one");
		User two = fx.user("two");
		User three = fx.user("three");
		SignInIdentityDAO.get().store(linked("entra", "oid-1", one.getId()));
		SignInIdentityDAO.get().store(linked("entra", "oid-2", two.getId()));
		SignInIdentityDAO.get().store(linked("authentik", "sub-1", three.getId()));
		assertEquals(2, SignInIdentityDAO.get().countLinked("entra"));

		// CE's delete marks the account and renames it, and doesn't tell the listeners: the link stays in the table
		UserDAO.get().delete(two.getId());
		UserDAO.get().delete(three.getId());

		assertEquals(1, SignInIdentityDAO.get().countLinked("entra"));
		assertEquals(0, SignInIdentityDAO.get().countLinked("authentik"));
		assertEquals(3, jdbc().queryForObject("select count(*) from hd_signin_identity", Integer.class).intValue());
		// as findLinked(provider), which joins the accounts, counts them
		assertEquals(SignInIdentityDAO.get().findLinked("entra").size(), SignInIdentityDAO.get().countLinked("entra"));
		assertEquals(SignInIdentityDAO.get().findLinked("authentik").size(),
				SignInIdentityDAO.get().countLinked("authentik"));
	}

	@Test
	public void testFindLinkedByProviderIsInUsernameOrder() throws Exception {
		User b = fx.user("bravo");
		User a = fx.user("alpha");
		User c = fx.user("charlie");
		User d = fx.user("delta");
		SignInIdentityDAO.get().store(linked("entra", "oid-b", b.getId()));
		SignInIdentityDAO.get().store(linked("entra", "oid-a", a.getId()));
		SignInIdentityDAO.get().store(linked("entra", "oid-c", c.getId()));
		SignInIdentityDAO.get().store(unlinked("entra", "oid-d", d.getId()));
		SignInIdentityDAO.get().store(linked("authentik", "sub-1", d.getId()));

		List<SignInIdentity> found = SignInIdentityDAO.get().findLinked("entra");

		assertEquals(List.of(a.getId(), b.getId(), c.getId()), found.stream().map(SignInIdentity::getUserId).toList());
		assertEquals(List.of(), SignInIdentityDAO.get().findLinked("nobody"));
	}

	@Test
	public void testGroupReplace() throws Exception {
		SignInGroupDAO dao = SignInGroupDAO.get();
		dao.replace("entra", List.of(group("entra", "Partners", 10), group("entra", "Staff", 11)));
		dao.replace("authentik", List.of(group("authentik", "partners", 10)));
		assertEquals(2, dao.findByProvider("entra").size());
		assertEquals(1, dao.findByProvider("authentik").size());

		dao.replace("entra", List.of(group("entra", "Partners", 10), group("entra", "Clerks", 12),
				group("entra", "Clerks", 13)));

		List<SignInGroup> entra = dao.findByProvider("entra");
		assertEquals(Set.of("Partners:10", "Clerks:12", "Clerks:13"),
				new TreeSet<>(entra.stream().map(g -> g.getValue() + ":" + g.getGroupId()).toList()));
		// The other provider's rows are as they were
		List<SignInGroup> authentik = dao.findByProvider("authentik");
		assertEquals(1, authentik.size());
		assertEquals("partners", authentik.get(0).getValue());
		assertEquals(10L, authentik.get(0).getGroupId());
		// and the rows are gone, not marked: nothing is left to collide with
		assertEquals(4, jdbc().queryForObject("select count(*) from hd_signin_group", Integer.class).intValue());

		dao.replace("entra", List.of());
		assertEquals(List.of(), dao.findByProvider("entra"));
		assertEquals(1, dao.findByProvider("authentik").size());
	}

	@Test
	public void testGroupReplaceTakesTheSameRowsAgain() throws Exception {
		SignInGroupDAO dao = SignInGroupDAO.get();
		dao.replace("entra", List.of(group("entra", "Partners", 10)));

		// Unique on provider, value and group: the old row must go before the new one comes
		dao.replace("entra", List.of(group("entra", "Partners", 10)));

		assertEquals(1, dao.findByProvider("entra").size());
		assertEquals(1, jdbc().queryForObject("select count(*) from hd_signin_group", Integer.class).intValue());
	}

	@Test
	public void testGroupReplaceKeepsTheOldRowsWhenTheNewOnesFail() throws Exception {
		SignInGroupDAO dao = SignInGroupDAO.get();
		dao.replace("entra", List.of(group("entra", "Partners", 10)));

		// The same row twice breaks the unique index
		assertThrows(ConstraintViolationException.class,
				() -> dao.replace("entra", List.of(group("entra", "Staff", 11), group("entra", "Staff", 11))));

		assertEquals(List.of("Partners"), dao.findByProvider("entra").stream().map(SignInGroup::getValue).toList());
	}

	@Test
	public void testTokenColumns() throws Exception {
		User clerk = fx.user("clerk");
		ApiToken token = new ApiToken();
		token.setUserId(clerk.getId());
		token.setHash("a".repeat(64));
		token.setName("Laptop");
		token.setLastUsed(new Date());
		token.setExpires(new Date(System.currentTimeMillis() + 86400000L));
		token.setProvider("entra");
		token.setRefresh("v1:" + "x".repeat(3000));
		Date checked = new Date(System.currentTimeMillis() - 60000L);
		token.setChecked(checked);
		ApiTokenDAO.get().store(token);

		ApiToken stored = ApiTokenDAO.get().findById(token.getId());
		assertEquals("entra", stored.getProvider());
		assertEquals("v1:" + "x".repeat(3000), stored.getRefresh());
		assertNotNull(stored.getChecked());
		assertTrue(Math.abs(checked.getTime() - stored.getChecked().getTime()) < 1000);

		// A token from a password sign-in has none of the three
		ApiToken plain = new ApiToken();
		plain.setUserId(clerk.getId());
		plain.setHash("b".repeat(64));
		plain.setName("Other");
		plain.setLastUsed(new Date());
		plain.setExpires(new Date(System.currentTimeMillis() + 86400000L));
		ApiTokenDAO.get().store(plain);
		ApiToken other = ApiTokenDAO.get().findById(plain.getId());
		assertNull(other.getProvider());
		assertNull(other.getRefresh());
		assertNull(other.getChecked());
	}
}
