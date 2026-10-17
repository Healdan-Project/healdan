package com.logicaldoc.healdan.matter;

/**
 * How matter numbers are handed out.
 */
public enum NumberingMode {

	/** Each client numbers its matters from 1 */
	PER_CLIENT,

	/** One sequence of matter numbers for the whole install */
	SYSTEM_WIDE
}
