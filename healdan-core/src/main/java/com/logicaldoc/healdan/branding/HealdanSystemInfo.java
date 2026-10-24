package com.logicaldoc.healdan.branding;

import com.logicaldoc.core.SystemInfo;

/**
 * Healdan's product identification (M1 spec §2.9). CE's
 * {@link SystemInfo#get(long)} creates it through the {@code SystemInfo}
 * extension in healdan-core's {@code plugin.xml}, then sets the release, year,
 * run level, installation id and features from CE's configuration, so those
 * stay CE's. Help opens Healdan's docs site.
 */
public class HealdanSystemInfo extends SystemInfo {

	public static final String PRODUCT = "Healdan";

	public static final String URL = "https://github.com/Healdan-Project/healdan";

	/**
	 * Healdan's docs site, which Help opens
	 */
	public static final String HELP = "https://docs.healdan.com";

	/**
	 * CE's value for a link the GUI leaves out (Help > Forum, About's site link)
	 */
	public static final String NONE = "-";

	public HealdanSystemInfo() {
		super();
		productName = PRODUCT;
		product = PRODUCT;
		vendor = "Healdan Project";
		url = URL;
		bugs = URL + "/issues";
		help = HELP;
		// The repository has no discussions, and Healdan has no support desk
		forum = NONE;
		support = "";
		vendorAddress = "";
		vendorCap = "";
		vendorCity = "";
		vendorCountry = "";
	}
}
