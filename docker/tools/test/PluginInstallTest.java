// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

import org.java.plugin.registry.PluginDescriptor;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.plugin.PluginRegistry;

/**
 * Installs the WAR's plugins as DockerInit does, then starts them again as Tomcat does after it, each in a JVM of its
 * own, since LogicalDOC's PluginRegistry is a singleton. build.sh runs it with a conf folder on the classpath and copies
 * of the WAR's plugin jars and web.xml, which the plugins' installs edit instead of the WAR's.
 * Usage: PluginInstallTest first <default context.properties> <work dir> <the WAR's web.xml> | again
 */
public class PluginInstallTest {

    /** Two of the plugins' web.xml edits the WAR's own web.xml lacks: CMIS's version and DashletContent's mapping. */
    private static final List<String> EDITS = List.of("cmisVersion", "/data/dashletcontent");

    public static void main(String[] args) throws Exception {
        if ("first".equals(args[0])) {
            File work = new File(args[2]);
            File conf = new File(work, "conf/context.properties");
            Files.copy(new File(args[1]).toPath(), conf.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ContextProperties config = new ContextProperties(conf);
            config.setProperty("conf.plugindir", new File(work, "plugins").getPath() + "/");
            // ConfigPlan seeds the id before the plugins' install markers, which carry it, are made.
            config.setProperty("id", "plugin-install-test");
            config.write();
            byte[] warWebXml = Files.readAllBytes(new File(args[3]).toPath());

            DockerInit.PluginStart start = DockerInit.installPlugins();
            Check.isTrue(start.failed().isEmpty(), "every plugin starts outside Tomcat, but not " + start.failed());
            // A plugin without a class of its own (logicaldoc-gui) has no install to run, and so no marker.
            List<String> installable = PluginRegistry.getInstance().getPlugins().stream()
                    .filter(plugin -> plugin.getPluginClassName() != null && !plugin.getPluginClassName().isBlank())
                    .map(PluginDescriptor::getId).sorted().toList();
            Check.isTrue(installable.contains("logicaldoc-core"), "the WAR's plugins include core's: " + installable);
            Check.isTrue(start.installed().equals(installable),
                    "DockerInit's start installs every plugin: " + start.installed() + " of " + installable);
            Check.isTrue(start.restartRequired(), "DockerInit's start installs plugins that ask for a restart");
            Check.isTrue(Arrays.equals(warWebXml, Files.readAllBytes(new File(args[3]).toPath())),
                    "the installs leave the WAR's web.xml alone");
            String warText = new String(warWebXml, StandardCharsets.UTF_8);
            String copyText = Files.readString(new File(work, "webapp/WEB-INF/web.xml").toPath());
            for (String edit : EDITS)
                Check.isTrue(!warText.contains(edit) && copyText.contains(edit),
                        "the installs add " + edit + " to the copy of web.xml");
            Check.done("PluginInstallTest (DockerInit's start)");
        } else {
            DockerInit.PluginStart start = DockerInit.installPlugins();
            Check.isTrue(start.failed().isEmpty(), "every plugin starts again, but not " + start.failed());
            Check.isTrue(start.installed().isEmpty(), "Tomcat's start installs nothing, but " + start.installed());
            Check.isTrue(!start.restartRequired(),
                    "Tomcat's start, after DockerInit's, finds every plugin installed and asks for no restart");
            Check.done("PluginInstallTest (Tomcat's start)");
        }
    }
}
