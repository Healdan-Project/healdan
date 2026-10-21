package com.logicaldoc.healdan.branding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.SystemInfo;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

public class HealdanSystemInfoTest extends AbstractHealdanTestCase {

	@Test
	public void testProductNameIsHealdan() {
		SystemInfo info = SystemInfo.get(Tenant.DEFAULT_ID);
		assertEquals("Healdan", info.getProductName());
		assertEquals("Healdan", info.getProduct());
		assertEquals("Healdan Project", info.getVendor());
		assertEquals("https://github.com/Healdan-Project/healdan", info.getUrl());
		assertEquals("https://github.com/Healdan-Project/healdan/issues", info.getBugs());

		// SystemInfo.get still fills in CE's release, tenant and features
		assertEquals(Context.get().getConfig().getProperty("product.release"), info.getRelease());
		assertEquals(Tenant.DEFAULT_ID, info.getTenantId());
		assertTrue(info.getFeatures().contains("Feature_71"));
	}
}
