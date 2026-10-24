package com.logicaldoc.healdan.seed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * Writes the API's settings, leaving alone any that already has a value.
 */
@Component
public class ApiSettingsSeed implements SeedStep {

	private final HealdanSettings settings;

	@Autowired
	public ApiSettingsSeed(HealdanSettings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "api-settings";
	}

	@Override
	public void run() {
		settings.setIfMissing(HealdanSettings.API_TOKEN_IDLE_DAYS,
				Integer.toString(HealdanSettings.DEFAULT_API_TOKEN_IDLE_DAYS));
		settings.setIfMissing(HealdanSettings.API_TOKEN_MAX_DAYS,
				Integer.toString(HealdanSettings.DEFAULT_API_TOKEN_MAX_DAYS));
		settings.setIfMissing(HealdanSettings.API_CORS_ORIGINS, "");
	}
}
