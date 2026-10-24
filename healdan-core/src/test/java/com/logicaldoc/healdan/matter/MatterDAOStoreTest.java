package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;

import java.util.List;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.util.spring.Context;

/**
 * Spec 6.2: the matters of a Store, for the admin-only Store lists.
 */
public class MatterDAOStoreTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private Matter bare(Client client, long number, Folder store) throws Exception {
		Folder workspace = HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE,
				() -> fx.folder(store, MatterDisplay.format(client.getNumber(), number, "M" + number)));
		Matter matter = new Matter();
		matter.setClientId(client.getId());
		matter.setNumber(number);
		matter.setName("M" + number);
		matter.setFolderId(workspace.getId());
		matter.setOwnerId(fx.admin().getId());
		MatterDAO.get().store(matter);
		return matter;
	}

	private static List<String> numbers(List<Matter> matters) throws Exception {
		return matters.stream().map(m -> {
			try {
				return ClientDAO.get().findById(m.getClientId()).getNumber() + "." + m.getNumber();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}).toList();
	}

	@Test
	public void testOrderedByClientThenMatter() throws Exception {
		Folder store = fx.store("Main");
		Folder other = fx.store("Other");
		Client c998 = fx.client(998, "Healdan");
		Client c997 = fx.client(997, "Earlier");
		bare(c998, 2, store);
		bare(c998, 1, store);
		bare(c997, 5, store);
		bare(c998, 3, other);

		MatterDAO dao = MatterDAO.get();
		assertEquals(List.of("997.5", "998.1", "998.2"), numbers(dao.findByStore(store.getId(), 0, 50)));
		assertEquals(3, dao.countByStore(store.getId()));
		assertEquals(List.of("998.1", "998.2"), numbers(dao.findByStore(store.getId(), 1, 50)));
		assertEquals(List.of("997.5", "998.1"), numbers(dao.findByStore(store.getId(), 0, 2)));
		assertEquals(List.of(), numbers(dao.findByStore(store.getId(), 5, 2)));
		assertEquals(List.of("998.3"), numbers(dao.findByStore(other.getId(), 0, 50)));
		assertEquals(1, dao.countByStore(other.getId()));
		assertEquals(0, dao.countByStore(Folder.DEFAULTWORKSPACEID));
	}

	@Test
	public void testDeletedWorkspaceLeftOut() throws Exception {
		Folder store = fx.store("Main");
		Client client = fx.client(998, "Healdan");
		Matter kept = bare(client, 1, store);
		Matter gone = bare(client, 2, store);
		new JdbcTemplate(Context.get(DataSource.class))
				.update("update ld_folder set ld_deleted = 1 where ld_id = ?", gone.getFolderId());

		MatterDAO dao = MatterDAO.get();
		assertEquals(List.of("998.1"), numbers(dao.findByStore(store.getId(), 0, 50)));
		assertEquals(1, dao.countByStore(store.getId()));
		assertEquals(kept.getId(), dao.findByStore(store.getId(), 0, 50).get(0).getId());
	}
}
