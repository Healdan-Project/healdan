package com.logicaldoc.healdan;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.SystemInfo;
import com.logicaldoc.core.security.Tenant;

/**
 * Proves that a test class can register an extra plugin before the registry is
 * built.
 */
public class TestPluginHookTest extends AbstractHealdanTestCase {

	@Override
	protected List<String> getPluginArchives() {
		List<String> archives = new ArrayList<>(super.getPluginArchives());
		archives.add(PluginFixtures.addTestPlugin("healdan-hook-test", """
				<extension plugin-id="logicaldoc-core" point-id="Feature" id="featureHookTest">
					<parameter id="name" value="Feature_9999" />
				</extension>"""));
		return archives;
	}

	@Test
	public void testExtraPluginIsRegistered() {
		assertTrue(SystemInfo.get(Tenant.DEFAULT_ID).getFeatures().contains("Feature_9999"));
	}
}
