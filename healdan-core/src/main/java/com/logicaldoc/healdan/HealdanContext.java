package com.logicaldoc.healdan;

import org.springframework.context.annotation.ComponentScan;

import com.logicaldoc.util.spring.PluginContext;

/**
 * Spring context of the Healdan plug-in.
 */
@PluginContext
@ComponentScan("com.logicaldoc.healdan")
public class HealdanContext {
	// Context of the healdan plugin
}
