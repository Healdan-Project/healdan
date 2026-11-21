// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class SmokeClassesSeedTest extends AbstractHealdanTestCase {

	private SmokeClassesSeed seed(String flag) {
		return new SmokeClassesSeed(() -> flag);
	}

	private long setId() throws Exception {
		return AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true).getId();
	}

	@Test
	public void testAddsTwoClassesAfterTheDefault() throws Exception {
		assertEquals(List.of("Docs"), ProfileSchema.get().classOptions());

		SmokeClassesSeed seed = seed("true");
		assertEquals("smoke-classes", seed.name());
		assertTrue(seed.enabled());
		Context.get(SeedRunner.class).runPending(List.of(seed));

		assertEquals(List.of("Docs", "Correspondence", "Pleadings"), ProfileSchema.get().classOptions());
		assertEquals("done", HealdanSettings.get().get("seed.smoke-classes"));
	}

	@Test
	public void testRunsAfterTheProfileAndTheOtherSmokeSteps() {
		assertTrue(SeedRunner.ORDER.indexOf("smoke-classes") > SeedRunner.ORDER.indexOf("profile"));
		assertEquals(SeedRunner.ORDER.indexOf("smoke-second-matter") + 1, SeedRunner.ORDER.indexOf("smoke-classes"));
	}

	@Test
	public void testASecondRunAddsNothing() throws Exception {
		SeedRunner runner = Context.get(SeedRunner.class);
		runner.runPending(List.of(seed("true")));
		List<String> first = ProfileSchema.get().classOptions();

		HealdanSettings.get().delete("seed.smoke-classes");
		runner.runPending(List.of(seed("true")));

		assertEquals(first, ProfileSchema.get().classOptions());
		assertEquals(3, AttributeOptionDAO.get().findByAttribute(setId(), HealdanConstants.ATTR_DOCCLASS).size());
		assertEquals("done", HealdanSettings.get().get("seed.smoke-classes"));
	}

	@Test
	public void testAClassTheListHoldsIsLeftAlone() throws Exception {
		AttributeOptionDAO dao = AttributeOptionDAO.get();
		AttributeOption pleadings = new AttributeOption(setId(), HealdanConstants.ATTR_DOCCLASS, "Pleadings");
		pleadings.setPosition(1);
		dao.store(pleadings);

		Context.get(SeedRunner.class).runPending(List.of(seed("true")));

		assertEquals(List.of("Docs", "Pleadings", "Correspondence"), ProfileSchema.get().classOptions());
		List<AttributeOption> options = dao.findByAttribute(setId(), HealdanConstants.ATTR_DOCCLASS);
		assertEquals(pleadings.getId(),
				options.stream().filter(option -> "Pleadings".equals(option.getValue())).findFirst().orElseThrow()
						.getId());
	}

	@Test
	public void testNothingWithoutTheFlag() throws Exception {
		for (String flag : new String[] { null, "", "false" }) {
			SmokeClassesSeed seed = seed(flag);
			assertFalse(String.valueOf(flag), seed.enabled());
			Context.get(SeedRunner.class).runPending(List.of(seed));
		}
		assertNull(HealdanSettings.get().get("seed.smoke-classes"));
		assertEquals(List.of("Docs"), ProfileSchema.get().classOptions());
	}
}
