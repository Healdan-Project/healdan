package com.logicaldoc.healdan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.commons.io.IOUtils;

/**
 * Builds, at test time, the plugin jars that
 * {@code AbstractTestCase.getPluginArchives()} registers: one holding only the
 * {@code plugin.xml} of logicaldoc-core and one holding that of healdan-core.
 * <p>
 * A test class that needs an extra plugin adds it from its overridden
 * {@code getPluginArchives()}, so the plugin is registered for that class only:
 * 
 * <pre>
 * List&lt;String&gt; archives = new ArrayList&lt;&gt;(super.getPluginArchives());
 * archives.add(PluginFixtures.addTestPlugin("my-test", "&lt;extension .../&gt;"));
 * return archives;
 * </pre>
 */
public final class PluginFixtures {

	private static final String DIR = "healdan-plugins";

	private static final String CORE_ID = "logicaldoc-core";

	private static final String HEALDAN_ID = "healdan-core";

	private PluginFixtures() {
	}

	/**
	 * @return the classpath paths of the logicaldoc-core and healdan-core plugin
	 *         jars, created if necessary
	 */
	public static List<String> archives() {
		return new ArrayList<>(List.of(writeJar(CORE_ID, findPluginXml(CORE_ID)), writeJar(HEALDAN_ID, findPluginXml(HEALDAN_ID))));
	}

	/**
	 * Creates the jar of a test-only plugin that requires logicaldoc-core.
	 * 
	 * @param id the plugin id
	 * @param pluginXmlBody the content to place inside the {@code <plugin>}
	 *        element after its {@code <requires>}: typically
	 *        {@code <extension>} elements
	 * @return the classpath path of the jar, to be added to the archives
	 */
	public static String addTestPlugin(String id, String pluginXmlBody) {
		// The manifest parser validates against the DTD at the top of healdan's own plugin.xml
		String healdanXml = findPluginXml(HEALDAN_ID);
		String header = healdanXml.substring(0, healdanXml.indexOf("<plugin id="));
		String xml = header + "<plugin id=\"%s\" version=\"9.3.1\">\n<requires><import plugin-id=\"%s\" /></requires>\n%s\n</plugin>\n"
				.formatted(id, CORE_ID, pluginXmlBody);
		return writeJar(id, xml);
	}

	private static String findPluginXml(String pluginId) {
		try {
			Enumeration<URL> urls = PluginFixtures.class.getClassLoader().getResources("plugin.xml");
			for (URL url : Collections.list(urls)) {
				String xml = IOUtils.toString(url, StandardCharsets.UTF_8);
				if (xml.contains("<plugin id=\"" + pluginId + "\""))
					return xml;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		throw new IllegalStateException("No plugin.xml found on the classpath for " + pluginId);
	}

	private static String writeJar(String pluginId, String pluginXml) {
		File dir = new File("target/test-classes/" + DIR);
		dir.mkdirs();
		// The registry only accepts names ending in .jar and containing -plugin
		String name = pluginId + "-plugin.jar";
		try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(new File(dir, name)))) {
			jar.putNextEntry(new JarEntry("plugin.xml"));
			jar.write(pluginXml.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return DIR + "/" + name;
	}
}
