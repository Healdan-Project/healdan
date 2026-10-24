package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;

import java.util.List;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.sequence.SequenceDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

public class ApiKeyCountersSeedTest extends AbstractHealdanTestCase {

	// A counter named the way Healdan names one now, after the key's SHA-256 in upper-case hex
	private static final String DIGEST = "8F4C1B6F3EF1FD0C7B3A3F7B0E1B6DBB45E7E5B40A8DC4F4B8E0D1A3F5B2C9E7";

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private List<String> counters() {
		return jdbc().queryForList("select ld_name from ld_sequence where ld_name like 'loginfail-%' order by ld_name",
				String.class);
	}

	// Before Healdan named them by digest, a key's failure counter held the key itself
	@Test
	public void testDeletesCountersNamedAfterAKey() throws Exception {
		SequenceDAO sequences = SequenceDAO.get();
		sequences.next("loginfail-apikey-ld-0123456789abcdef", 0L, Tenant.SYSTEM_ID);
		sequences.next("loginfail-apikey-" + DIGEST, 0L, Tenant.SYSTEM_ID);
		sequences.next("loginfail-ip-10.0.0.9", 0L, Tenant.SYSTEM_ID);
		sequences.next("loginfail-username-author", 0L, Tenant.SYSTEM_ID);
		jdbc().update("delete from hd_setting where name = 'seed.apikey-counters'");

		Context.get(SeedRunner.class).runPending();

		assertEquals(List.of("loginfail-apikey-" + DIGEST, "loginfail-ip-10.0.0.9", "loginfail-username-author"),
				counters());
	}
}
