package com.logicaldoc.healdan.branding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import com.logicaldoc.core.SystemInfo;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.gui.common.client.beans.GUIBranding;
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

		// Nothing left that points at LogicalDOC's forum, support desk or office
		assertEquals("-", info.getForum());
		assertEquals("", info.getSupport());
		assertEquals("", info.getVendorAddress());
		assertEquals("", info.getVendorCap());
		assertEquals("", info.getVendorCity());
		assertEquals("", info.getVendorCountry());

		// SystemInfo.get still fills in CE's release, tenant and features
		assertEquals(Context.get().getConfig().getProperty("product.release"), info.getRelease());
		assertEquals(Tenant.DEFAULT_ID, info.getTenantId());
		assertTrue(info.getFeatures().contains("Feature_71"));
	}

	/**
	 * Help opens Healdan's docs site, in the GUI (which copies SystemInfo's
	 * value into its branding) and in the GUI's own default.
	 */
	@Test
	public void testHelpOpensHealdanDocs() {
		assertEquals("https://docs.healdan.com", HealdanSystemInfo.HELP);
		assertEquals(HealdanSystemInfo.HELP, SystemInfo.get(Tenant.DEFAULT_ID).getHelp());
		assertEquals(HealdanSystemInfo.HELP, new GUIBranding().getHelp());
	}

	/**
	 * CE's SystemInfo.get uses the first SystemInfo extension it finds, in no
	 * set order. A second one in any module, from an upstream sync or a new
	 * plugin, could take the product name back in the webapp while this
	 * module's own registry still holds only Healdan's.
	 */
	@Test
	public void testHealdanDeclaresTheOnlySystemInfo() throws IOException {
		// Tests run in healdan-core, so the parent is the reactor's root
		Path root = Path.of("..");
		assertTrue(Files.exists(root.resolve("logicaldoc-core/src/main/resources/plugin.xml")));

		Pattern systemInfo = Pattern.compile("point-id\\s*=\\s*\"SystemInfo\"");
		List<String> declaring = new ArrayList<>();
		try (Stream<Path> modules = Files.list(root)) {
			for (Path module : modules.sorted().toList()) {
				Path manifest = module.resolve("src/main/resources/plugin.xml");
				if (!Files.exists(manifest))
					continue;
				Matcher matcher = systemInfo
						.matcher(new String(Files.readAllBytes(manifest), StandardCharsets.ISO_8859_1));
				while (matcher.find())
					declaring.add(module.getFileName().toString());
			}
		}
		assertEquals(List.of("healdan-core"), declaring);
	}
}
