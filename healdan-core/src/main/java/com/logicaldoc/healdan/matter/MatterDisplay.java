package com.logicaldoc.healdan.matter;

/**
 * How a matter is shown: {@code <client>.<matter> – <name>}, unpadded, with an
 * en dash. It is also the name of the matter's workspace folder.
 * <p>
 * The en dash is written as an escape: the build reads sources as ISO-8859-1
 * ({@code logicaldoc-parent}), which would turn a literal one into three
 * other characters.
 */
public final class MatterDisplay {

	private MatterDisplay() {
	}

	/**
	 * @param client the client number
	 * @param matter the matter number
	 * @param name the matter name
	 * @return for example {@code 998.1 – Healdan Demo}
	 */
	public static String format(long client, long matter, String name) {
		return "%d.%d \u2013 %s".formatted(client, matter, name);
	}
}
