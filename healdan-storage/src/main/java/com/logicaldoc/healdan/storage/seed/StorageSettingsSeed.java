// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.seed.SeedStep;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.storage.StorageSettings;

/**
 * Writes the defaults of the storage settings (spec section 5.4), leaving alone any that already has a value, makes
 * this install's id once, and adds the message template the 24-hour alert sends, in English, which CE falls back to
 * for every language. A {@code storage.alert} that already exists is kept.
 */
@Component
public class StorageSettingsSeed implements SeedStep {

	static final String TEMPLATE = "storage.alert";

	private static final String SUBJECT = "$product - storage needs attention";

	private static final String BODY = "Files have been waiting more than 24 hours to reach the storage backend of "
			+ "their Store, or a storage backend has been failing for that long.<br/><br/>"
			+ "Open the Storage tab in the settings of $product to see which, and what to do.";

	private final HealdanSettings settings;

	private final StorageSettings storage;

	@Autowired
	public StorageSettingsSeed(HealdanSettings settings, StorageSettings storage) {
		this.settings = settings;
		this.storage = storage;
	}

	@Override
	public String name() {
		return "storage-settings";
	}

	@Override
	public void run() throws Exception {
		settings.setIfMissing(StorageSettings.HOLD_DAYS, Integer.toString(StorageSettings.DEFAULT_HOLD_DAYS));
		settings.setIfMissing(StorageSettings.CACHE_GB, Integer.toString(StorageSettings.DEFAULT_CACHE_GB));
		settings.setIfMissing(StorageSettings.TRANSFERS, Integer.toString(StorageSettings.DEFAULT_TRANSFERS));
		settings.setIfMissing(StorageSettings.SWEEP_SECONDS,
				Integer.toString(StorageSettings.DEFAULT_SWEEP_SECONDS));
		settings.setIfMissing(StorageSettings.RETEST_SECONDS,
				Integer.toString(StorageSettings.DEFAULT_RETEST_SECONDS));
		storage.installId();

		MessageTemplateDAO dao = MessageTemplateDAO.get();
		if (!dao.findByName(TEMPLATE, Tenant.DEFAULT_ID).isEmpty())
			return;
		MessageTemplate template = new MessageTemplate();
		template.setTenantId(Tenant.DEFAULT_ID);
		template.setName(TEMPLATE);
		template.setLanguage("en");
		template.setType(MessageTemplate.Type.SYSTEM);
		template.setDescription("Sent to the admin group when files wait for their storage backend, or a backend fails, "
				+ "for more than 24 hours");
		template.setSubject(SUBJECT);
		template.setBody(BODY);
		dao.store(template);
	}
}
