package com.logicaldoc.gui.frontend.client.healdan.settings;

import com.logicaldoc.gui.common.client.Session;
import com.logicaldoc.gui.frontend.client.administration.AdminScreen;
import com.smartgwt.client.types.Overflow;
import com.smartgwt.client.widgets.Button;
import com.smartgwt.client.widgets.layout.SectionStack;
import com.smartgwt.client.widgets.layout.SectionStackSection;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * The Healdan section of the administration menu, for administrators only
 */
public class HealdanAdminMenu extends VLayout {

	/**
	 * Adds the Healdan section to the administration menu, if the user is an
	 * administrator
	 *
	 * @param menu the administration menu
	 */
	public static void addTo(SectionStack menu) {
		if (!Session.get().isAdmin())
			return;

		SectionStackSection section = new SectionStackSection("Healdan");
		section.setExpanded(false);
		section.addItem(new HealdanAdminMenu());
		menu.addSection(section);
	}

	private HealdanAdminMenu() {
		setMargin(10);
		setMembersMargin(5);
		setOverflow(Overflow.AUTO);

		Button settings = new Button("Settings");
		settings.setWidth100();
		settings.setHeight(25);
		settings.addClickHandler(click -> AdminScreen.get().setContent(new HealdanSettingsPanel()));
		addMember(settings);
	}
}
