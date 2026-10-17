package com.logicaldoc.healdan.seed;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * Writes the default value of every setting that has one, leaving alone any
 * setting that already has a value.
 */
@Component
public class SettingsSeed implements SeedStep {

	private final HealdanSettings settings;

	@Autowired
	public SettingsSeed(HealdanSettings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "settings";
	}

	@Override
	public void run() {
		Map<String, String> defaults = new LinkedHashMap<>();
		defaults.put(HealdanSettings.MATTER_LABEL, HealdanSettings.DEFAULT_MATTER_LABEL);
		defaults.put(HealdanSettings.MATTER_LABEL_PLURAL, HealdanSettings.DEFAULT_MATTER_LABEL_PLURAL);
		defaults.put(HealdanSettings.MATTER_NUMBERING, HealdanSettings.DEFAULT_NUMBERING.name());
		defaults.put(HealdanSettings.CLASS_DEFAULT, HealdanSettings.DEFAULT_CLASS);
		defaults.put(HealdanSettings.RECENT_MATTERS_DAYS, Integer.toString(HealdanSettings.DEFAULT_RECENT_MATTERS_DAYS));
		defaults.forEach(settings::setIfMissing);
	}
}
