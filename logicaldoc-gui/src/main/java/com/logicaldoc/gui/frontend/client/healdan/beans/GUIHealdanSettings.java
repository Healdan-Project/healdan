package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.logicaldoc.gui.common.client.beans.GUIValue;

/**
 * What the Healdan Settings panel shows and saves (spec 8.6)
 */
public class GUIHealdanSettings implements Serializable {

	private static final long serialVersionUID = 1L;

	private String matterLabel;

	private String matterLabelPlural;

	/** The name of a numbering mode: PER_CLIENT or SYSTEM_WIDE */
	private String numberingMode;

	/** Null when no Store is the default */
	private Long defaultStoreId;

	/** The Stores to choose from: the folder id as code, the name as value */
	private List<GUIValue> stores = new ArrayList<>();

	private String defaultClass;

	/** The Class options to choose from */
	private List<String> classes = new ArrayList<>();

	private int recentMattersDays;

	/**
	 * The number the next document gets. A save leaves the counter alone when
	 * this is null or unchanged, and refuses to lower it.
	 */
	private Long nextDocNumber;

	public String getMatterLabel() {
		return matterLabel;
	}

	public void setMatterLabel(String matterLabel) {
		this.matterLabel = matterLabel;
	}

	public String getMatterLabelPlural() {
		return matterLabelPlural;
	}

	public void setMatterLabelPlural(String matterLabelPlural) {
		this.matterLabelPlural = matterLabelPlural;
	}

	public String getNumberingMode() {
		return numberingMode;
	}

	public void setNumberingMode(String numberingMode) {
		this.numberingMode = numberingMode;
	}

	public Long getDefaultStoreId() {
		return defaultStoreId;
	}

	public void setDefaultStoreId(Long defaultStoreId) {
		this.defaultStoreId = defaultStoreId;
	}

	public List<GUIValue> getStores() {
		return stores;
	}

	public void setStores(List<GUIValue> stores) {
		this.stores = stores;
	}

	public String getDefaultClass() {
		return defaultClass;
	}

	public void setDefaultClass(String defaultClass) {
		this.defaultClass = defaultClass;
	}

	public List<String> getClasses() {
		return classes;
	}

	public void setClasses(List<String> classes) {
		this.classes = classes;
	}

	public int getRecentMattersDays() {
		return recentMattersDays;
	}

	public void setRecentMattersDays(int recentMattersDays) {
		this.recentMattersDays = recentMattersDays;
	}

	public Long getNextDocNumber() {
		return nextDocNumber;
	}

	public void setNextDocNumber(Long nextDocNumber) {
		this.nextDocNumber = nextDocNumber;
	}
}
