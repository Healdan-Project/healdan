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
