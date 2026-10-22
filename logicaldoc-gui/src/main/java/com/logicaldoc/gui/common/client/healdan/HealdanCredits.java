package com.logicaldoc.gui.common.client.healdan;

import com.smartgwt.client.types.Alignment;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.layout.VStack;

/**
 * The credits on the login page and in About: Healdan's copyright, then
 * LogicalDOC's, whose Community Edition Healdan is a modified version of, the
 * trademarks and the license. It lives in the common module because the login
 * module can't see the frontend's Healdan package. English only, like the rest
 * of Healdan's GUI text.
 */
public final class HealdanCredits {

	/**
	 * The year of Healdan's copyright line. CE's {@code product.year} is
	 * LogicalDOC's range, which {@link #BASED_ON} states as theirs.
	 */
	public static final String YEAR = "2026";

	/** The credit to LogicalDOC, as two lines */
	public static final String BASED_ON = "<div>Based on LogicalDOC Community Edition</div>"
			+ "<div>&copy; 2006-2027 LOGICALDOC Srl</div>";

	public static final String LICENSE_URL = "https://github.com/Healdan-Project/healdan/blob/master/LICENSE";

	private HealdanCredits() {
	}

	/**
	 * The trademark notice. It replaces CE's {@code copyrights} message, which
	 * called the product a registered trademark of the vendor.
	 *
	 * @param product the product's name, as the branding gives it
	 * @param vendor the vendor's name, as the branding gives it
	 *
	 * @return the notice, as HTML
	 */
	public static String trademarks(String product, String vendor) {
		return product + " and its logo are trademarks of " + vendor + ".<br/>LogicalDOC is a trademark of "
				+ "LOGICALDOC Srl, which doesn't endorse " + product + ".";
	}

	/**
	 * About's lines below Healdan's copyright: the credit to LogicalDOC, the
	 * trademarks and the license
	 *
	 * @param product the product's name, as the branding gives it
	 * @param vendor the vendor's name, as the branding gives it
	 *
	 * @return the lines, stacked and centered
	 */
	public static Canvas aboutCredits(String product, String vendor) {
		VStack credits = new VStack();
		credits.setWidth100();
		credits.setMembersMargin(5);
		credits.setMembers(centered(BASED_ON, 30), centered(trademarks(product, vendor), 30),
				centered("Free software under the <a href='" + LICENSE_URL + "' target='_blank'>GNU LGPL v3</a>", 15));
		return credits;
	}

	private static Label centered(String html, int height) {
		Label label = new Label(html);
		label.setHeight(height);
		label.setAlign(Alignment.CENTER);
		return label;
	}
}
