// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import com.logicaldoc.util.config.ContextProperties;

/**
 * Installs the WAR's plugins as DockerInit does, then starts them again as Tomcat does after it, each in a JVM of its
 * own, since LogicalDOC's PluginRegistry is a singleton. build.sh runs it with a conf folder on the classpath and copies
 * of the WAR's jars and web.xml, which the plugins' installs edit instead of the WAR's.
 * Usage: PluginInstallTest first <default context.properties> <work dir> <the WAR's web.xml> | again
 */
public class PluginInstallTest {

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

            Check.isTrue(DockerInit.installPlugins(), "DockerInit's start installs plugins that ask for a restart");
            Check.isTrue(Arrays.equals(warWebXml, Files.readAllBytes(new File(args[3]).toPath())),
                    "the installs edited the copy of web.xml, not the WAR's");
            Check.done("PluginInstallTest (DockerInit's start)");
        } else {
            Check.isTrue(!DockerInit.installPlugins(),
                    "Tomcat's start, after DockerInit's, finds every plugin installed and asks for no restart");
            Check.done("PluginInstallTest (Tomcat's start)");
        }
    }
}
