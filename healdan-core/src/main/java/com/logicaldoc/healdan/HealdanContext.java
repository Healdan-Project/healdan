// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

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
