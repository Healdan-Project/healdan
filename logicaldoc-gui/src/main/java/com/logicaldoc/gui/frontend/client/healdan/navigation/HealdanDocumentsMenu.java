package com.logicaldoc.gui.frontend.client.healdan.navigation;

import com.logicaldoc.gui.frontend.client.healdan.HealdanLabels;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanMatterService;
import com.smartgwt.client.widgets.layout.SectionStack;
import com.smartgwt.client.widgets.layout.SectionStackSection;

/**
 * My Matters and Recent Matters, the two sections above the Browser in the
 * Documents tab's left pane (spec 8.2). Each list loads when its header is
 * clicked.
 */
public final class HealdanDocumentsMenu {

	private static final String MY_MATTERS = "healdanmymatters";

	private static final String RECENT_MATTERS = "healdanrecentmatters";

	/** The name CE's {@code DocumentsMenu} gives its Browser section */
	private static final String BROWSER = "browser";

	private static MatterListPanel myMatters;

	private HealdanDocumentsMenu() {
	}

	/**
	 * Adds the sections where the menu's sections end now
	 *
	 * @param menu the Documents tab's left pane
	 */
	public static void addSections(SectionStack menu) {
		myMatters = new MatterListPanel(HealdanMatterService.Instance.get()::myMatters);
		MatterListPanel recentMatters = new MatterListPanel(HealdanMatterService.Instance.get()::recentMatters);
		SectionStackSection mySection = section(MY_MATTERS, myMatters);
		SectionStackSection recentSection = section(RECENT_MATTERS, recentMatters);
		menu.addSection(mySection);
		menu.addSection(recentSection);

		/*
		 * The Browser opens first, as in CE: with these sections collapsed
		 * above it, the stack would otherwise draw with every section shut, and
		 * the tree, never drawn, would ignore the first folder it's asked to open
		 */
		menu.addDrawHandler(draw -> {
			if (menu.getSection(BROWSER) != null)
				menu.expandSection(BROWSER);
		});

		menu.addSectionHeaderClickHandler(click -> {
			String name = click.getSection() != null ? click.getSection().getAttributeAsString("name") : null;
			if (MY_MATTERS.equals(name))
				myMatters.refresh();
			else if (RECENT_MATTERS.equals(name))
				recentMatters.refresh();
		});

		// The titles say the configured label, such as "My Projects"
		HealdanLabels.load(() -> {
			mySection.setTitle(myTitle());
			recentSection.setTitle(recentTitle());
		});
	}

	/**
	 * Loads My Matters again, after a matter was added or removed
	 */
	public static void refreshMyMatters() {
		if (myMatters != null)
			myMatters.refresh();
	}

	private static SectionStackSection section(String name, MatterListPanel panel) {
		SectionStackSection section = new SectionStackSection(
				MY_MATTERS.equals(name) ? myTitle() : recentTitle());
		section.setName(name);
		section.setCanCollapse(true);
		section.setExpanded(false);
		section.setItems(panel);
		return section;
	}

	private static String myTitle() {
		return "My " + HealdanLabels.plural();
	}

	private static String recentTitle() {
		return "Recent " + HealdanLabels.plural();
	}
}
