package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.java.plugin.registry.PluginDescriptor;
import org.java.plugin.registry.PluginPrerequisite;
import org.junit.Test;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.plugin.PluginRegistry;

/**
 * JPF accepts healdan-api's {@code plugin.xml} next to logicaldoc-core's and
 * healdan-core's. JPF drops a plugin it can't read without failing, so this
 * is the only warning.
 */
public class ApiPluginXmlTest extends AbstractHealdanTestCase {

	@Override
	protected List<String> getPluginArchives() {
		List<String> archives = new ArrayList<>(super.getPluginArchives());
		archives.add(writePluginJar());
		return archives;
	}

	@Test
	public void testPluginIsRegistered() {
		PluginDescriptor api = PluginRegistry.getInstance().getPlugin("healdan-api");
		assertNotNull("JPF dropped healdan-api's plugin.xml", api);

		assertTrue(api.getPrerequisites().stream().map(PluginPrerequisite::getPluginId).toList()
				.contains("healdan-core"));
		assertEquals(PluginRegistry.getInstance().getPlugin("healdan-core").getVersion(), api.getVersion());
		assertTrue(PluginRegistry.getInstance().getManager().isPluginActivated(api));
	}

	/**
	 * @return the classpath path of a plugin jar holding healdan-api's
	 *         {@code plugin.xml}
	 */
	private static String writePluginJar() {
		File dir = new File("target/test-classes/healdan-plugins");
		dir.mkdirs();
		// The registry only accepts names ending in .jar and containing -plugin
		try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(new File(dir, "healdan-api-plugin.jar")))) {
			jar.putNextEntry(new JarEntry("plugin.xml"));
			jar.write(Files.readAllBytes(Path.of("target/classes/plugin.xml")));
			jar.closeEntry();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return "healdan-plugins/healdan-api-plugin.jar";
	}
}
