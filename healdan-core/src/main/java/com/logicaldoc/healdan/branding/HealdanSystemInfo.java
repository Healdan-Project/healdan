package com.logicaldoc.healdan.branding;

import com.logicaldoc.core.SystemInfo;

/**
 * Healdan's product identification (M1 spec §2.9). CE's
 * {@link SystemInfo#get(long)} creates it through the {@code SystemInfo}
 * extension in healdan-core's {@code plugin.xml}, then sets the release, year,
 * run level, installation id and features from CE's configuration, so those
 * stay CE's.
 */
public class HealdanSystemInfo extends SystemInfo {

	public static final String PRODUCT = "Healdan";

	public static final String URL = "https://github.com/Healdan-Project/healdan";

	public HealdanSystemInfo() {
		super();
		productName = PRODUCT;
		product = PRODUCT;
		vendor = "Healdan Project";
		url = URL;
		bugs = URL + "/issues";
	}
}
